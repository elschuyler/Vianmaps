package com.example.map.acquisition

import android.content.Context
import com.example.data.osm.MvtParser
import com.example.data.osm.OsmOfflineDataRepository
import com.example.data.routing.RoutingGraphCoverageIndex
import com.example.data.tile.TileCategory
import com.example.data.tile.TileCoord
import com.example.data.tile.TileMetadata
import com.example.data.tile.TileStorageManager
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.map.source.MapStyleDefinition
import com.example.network.AppNetworkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.VisibleRegion
import org.maplibre.android.maps.MapLibreMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

data class AcquisitionCoverageStatus(
    val visibleTileCount: Int = 0,
    val newlyAcquiredTiles: Int = 0,
    val cumulativeSessionTiles: Int = 0,
    val cumulativeSessionBytes: Long = 0L,
    val isThrottledOrLimited: Boolean = false,
    val limitReason: String? = null
)

/**
 * Live Map Visible-Tile Acquisition Coordinator.
 *
 * Coordinates real-time discovery of XYZ tiles intersecting the active map camera viewport.
 * Enforces:
 * - 750ms camera settlement debouncing
 * - Precise visible region geometry & rotation calculation (no speculative prefetching)
 * - Antimeridian crossing & wrapped X coordinate handling
 * - AppNetworkPolicy Internet OFF immediate cancellation
 * - OpenFreeMap TOS limits: Max 4 concurrent HTTP connections, 3,000 tile / 50MB session caps, and unique User-Agent
 * - Atomic persistence via TileStorageManager & MapLibre C++ OfflineManager resource injection
 * - Automatic OSM feature decoding (POIs, buildings, roads, regions) into Room Database
 * - Independent Valhalla graph coverage evaluation
 *
 * Vector vs. Raster Boundary Note:
 * This coordinator strictly acquires OpenFreeMap OSM vector tiles (TileCategory.OSM_VECTOR)
 * for the visible viewport. Raster imagery (e.g. Esri World Imagery or OpenAerialMap) is
 * rendered directly by MapLibre's C++ OpenGL engine via raster layer source URLs into MapLibre's
 * native ambient cache. There is no separate manual raster file downloader, and Esri imagery
 * offline retention is restricted by its Terms of Use.
 */
class VisibleTileCoordinator(
    private val context: Context,
    private val tileStorageManager: TileStorageManager = TileStorageManager(context),
    private val osmRepository: OsmOfflineDataRepository = OsmOfflineDataRepository(context),
    private val graphIndex: RoutingGraphCoverageIndex = RoutingGraphCoverageIndex(context)
) {

    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeAcquisitionJob: Job? = null

    // OpenFreeMap published constraint: Max 4 concurrent requests
    private val networkSemaphore = Semaphore(4)

    // OpenFreeMap session constraints: Max 3,000 tiles and 50MB
    private val sessionTileCounter = AtomicInteger(0)
    private val sessionByteCounter = AtomicLong(0L)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _coverageStatus = MutableStateFlow(AcquisitionCoverageStatus())
    val coverageStatus: StateFlow<AcquisitionCoverageStatus> = _coverageStatus.asStateFlow()

    fun onCameraSettled(map: MapLibreMap, currentStyle: MapStyleDefinition) {
        activeAcquisitionJob?.cancel()

        activeAcquisitionJob = coordinatorScope.launch {
            // Debounce 750ms to ensure the user has finished panning/zooming
            delay(750)

            if (!AppNetworkPolicy.isNetworkAllowed()) {
                DiagnosticRepository.log(
                    severity = LogSeverity.INFO,
                    source = "VisibleTileCoordinator",
                    description = "Skipped visible tile acquisition: Internet is switched OFF in app policy"
                )
                return@launch
            }

            processVisibleCoverage(map, currentStyle)
        }
    }

    private suspend fun processVisibleCoverage(map: MapLibreMap, styleDef: MapStyleDefinition) {
        val visibleRegion: VisibleRegion = withContext(Dispatchers.Main) {
            try {
                map.projection.visibleRegion
            } catch (e: Throwable) {
                null
            }
        } ?: return

        val zoom = withContext(Dispatchers.Main) {
            map.cameraPosition.zoom
        }

        // OpenFreeMap / OpenMapTiles vector tiles top out at zoom 14; zooming past 14 still uses z=14 vector tiles
        val integerZoom = floor(zoom).toInt().coerceIn(0, 14)
        val visibleTiles = calculateVisibleTiles(visibleRegion, integerZoom)

        if (visibleTiles.isEmpty()) return

        _coverageStatus.value = _coverageStatus.value.copy(
            visibleTileCount = visibleTiles.size
        )

        // Check OpenFreeMap session limits
        if (sessionTileCounter.get() >= 3000 || sessionByteCounter.get() >= 50L * 1024L * 1024L) {
            _coverageStatus.value = _coverageStatus.value.copy(
                isThrottledOrLimited = true,
                limitReason = "OpenFreeMap session threshold reached (3,000 tiles / 50MB). Auto-retention paused."
            )
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "VisibleTileCoordinator",
                description = "OpenFreeMap session limit reached. Pausing automatic background retention to comply with TOS."
            )
            return
        }

        // Process each visible tile using canonical storage provider ID
        val providerId = styleDef.storageProviderId
        for (tile in visibleTiles) {
            // Ensure Internet is still ON
            if (!AppNetworkPolicy.isNetworkAllowed()) break

            // Retain OSM vector tile & features
            acquireVectorTileAndOsmFeatures(tile, styleDef, providerId)

            // Evaluate routing graph coverage for tile bounds
            val (sLat, wLng, nLat, eLng) = getTileBounds(tile)
            val graphReport = graphIndex.evaluateCoverage(sLat, wLng, nLat, eLng)
            DiagnosticRepository.log(
                severity = LogSeverity.DEBUG,
                source = "VisibleTileCoordinator",
                description = "Tile $tile Valhalla Graph Status: [${graphReport.status}] ${graphReport.details}"
            )
        }
    }

    private suspend fun acquireVectorTileAndOsmFeatures(
        tile: TileCoord,
        styleDef: MapStyleDefinition,
        providerId: String
    ) {
        // 1. Check if already retained locally
        if (tileStorageManager.isTileRetained(providerId, TileCategory.OSM_VECTOR, tile.z, tile.x, tile.y)) {
            return
        }

        // 2. Lock deduplication
        if (!tileStorageManager.acquireLock(providerId, TileCategory.OSM_VECTOR, tile.z, tile.x, tile.y)) {
            return
        }

        try {
            val tileUrl = styleDef.vectorTileUrlTemplate
                .replace("{z}", tile.z.toString())
                .replace("{x}", tile.x.toString())
                .replace("{y}", tile.y.toString())

            val request = Request.Builder()
                .url(tileUrl)
                .header("User-Agent", "VianMaps/1.0 (Android; Contact: schuylervianilewis@gmail.com)")
                .build()

            networkSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bodyBytes = response.body?.bytes()
                        if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                            val etag = response.header("ETag")
                            val metadata = TileMetadata(
                                providerId = providerId,
                                schemaVersion = "1.0",
                                fetchTimestamp = System.currentTimeMillis(),
                                etag = etag,
                                byteSize = bodyBytes.size.toLong(),
                                sourceUrl = tileUrl,
                                attribution = styleDef.attribution,
                                retentionPermission = if (styleDef.vectorRetentionPermitted) "PERMITTED" else "RESTRICTED"
                            )

                            // Save to durable file storage and sync with Room & MapLibre OfflineManager
                            val saved = tileStorageManager.saveTileData(
                                providerId = providerId,
                                category = TileCategory.OSM_VECTOR,
                                z = tile.z,
                                x = tile.x,
                                y = tile.y,
                                data = bodyBytes,
                                metadata = metadata
                            )

                            if (saved) {
                                sessionTileCounter.incrementAndGet()
                                sessionByteCounter.addAndGet(bodyBytes.size.toLong())

                                // Decode MVT and store canonical features in Room
                                val decoded = MvtParser.parseTile(bodyBytes, tile)
                                val insertedCount = osmRepository.ingestDecodedTile(decoded)

                                DiagnosticRepository.log(
                                    severity = LogSeverity.INFO,
                                    source = "VisibleTileCoordinator",
                                    description = "Retained visible vector tile $tile (${bodyBytes.size} bytes). Extracted $insertedCount canonical OSM entities.",
                                    stage = "TILE_RETAINED"
                                )
                            }
                        }
                    } else {
                        DiagnosticRepository.log(
                            severity = LogSeverity.WARNING,
                            source = "VisibleTileCoordinator",
                            description = "Tile fetch returned HTTP ${response.code} for $tileUrl"
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.DEBUG,
                source = "VisibleTileCoordinator",
                description = "Vector tile acquisition error for $tile: ${e.message}"
            )
        } finally {
            tileStorageManager.releaseLock(providerId, TileCategory.OSM_VECTOR, tile.z, tile.x, tile.y)
        }
    }

    /**
     * Calculates the exact list of XYZ tiles intersecting the visible screen.
     * Takes map rotation and fractional bounds into account, preventing speculative downloads.
     */
    fun calculateVisibleTiles(region: VisibleRegion, zoom: Int): List<TileCoord> {
        val corners = listOfNotNull(region.nearLeft, region.nearRight, region.farRight, region.farLeft)
        if (corners.isEmpty()) return emptyList()

        val minLat = corners.minOf { it.latitude }
        val maxLat = corners.maxOf { it.latitude }
        val nearL = region.nearLeft
        val nearR = region.nearRight
        val isAntimeridianCrossed = nearL != null && nearR != null && nearL.longitude > nearR.longitude

        val n = 1 shl zoom
        val (minX, maxX) = if (isAntimeridianCrossed) {
            val startX = floor((nearL.longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val endX = floor((nearR.longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            Pair(startX, endX)
        } else {
            val minL = corners.minOf { it.longitude }
            val maxL = corners.maxOf { it.longitude }
            val sX = floor((minL.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val eX = floor((maxL.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            Pair(sX, eX)
        }

        val clampedMaxLat = maxLat.coerceIn(-85.05112878, 85.05112878)
        val clampedMinLat = minLat.coerceIn(-85.05112878, 85.05112878)

        val minY = floor((1.0 - asinh(tan(Math.toRadians(clampedMaxLat))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        val maxY = floor((1.0 - asinh(tan(Math.toRadians(clampedMinLat))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)

        val tiles = mutableListOf<TileCoord>()
        if (!isAntimeridianCrossed && minX <= maxX) {
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    tiles.add(TileCoord(zoom, x, y))
                }
            }
        } else {
            // Antimeridian crossing: minX..n-1 and 0..maxX
            for (x in minX until n) {
                for (y in minY..maxY) {
                    tiles.add(TileCoord(zoom, x, y))
                }
            }
            for (x in 0..maxX) {
                for (y in minY..maxY) {
                    tiles.add(TileCoord(zoom, x, y))
                }
            }
        }

        // Safety cap: Never acquire more than 24 tiles for a single viewport view
        return tiles.take(24)
    }

    private data class Bounds(val southLat: Double, val westLng: Double, val northLat: Double, val eastLng: Double)

    private fun getTileBounds(tile: TileCoord): Bounds {
        val n = 1 shl tile.z
        val westLng = tile.x.toDouble() / n * 360.0 - 180.0
        val eastLng = (tile.x + 1).toDouble() / n * 360.0 - 180.0
        val northLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * tile.y.toDouble() / n))))
        val southLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (tile.y + 1).toDouble() / n))))
        return Bounds(southLat, westLng, northLat, eastLng)
    }

    fun stop() {
        activeAcquisitionJob?.cancel()
        coordinatorScope.coroutineContext[Job]?.cancelChildren()
    }
}
