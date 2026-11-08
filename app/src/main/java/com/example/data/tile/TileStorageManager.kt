package com.example.data.tile

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.RetainedTileEntity
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.offline.OfflineManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Geographic tile coordinate representation in the standard Slippy map / OSM XYZ tile grid.
 */
data class TileCoord(val z: Int, val x: Int, val y: Int) {
    override fun toString(): String = "$z/$x/$y"

    companion object {
        fun fromLatLng(lat: Double, lng: Double, zoom: Int): TileCoord {
            val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
            val n = 1 shl zoom
            val x = floor((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val rad = Math.toRadians(clampedLat)
            val y = floor((1.0 - asinh(tan(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return TileCoord(zoom, x, y)
        }
    }
}

enum class TileCategory(val dirName: String) {
    SATELLITE_RASTER("satellite_raster"),
    OSM_VECTOR("osm_vector"),
    OSM_POI_INDEX("osm_poi"),
    OSM_REGION_INDEX("osm_region"),
    OSM_BUILDING_INDEX("osm_building"),
    ROUTING_GRAPH("routing_graph")
}

data class TileMetadata(
    val providerId: String,
    val schemaVersion: String = "1.0",
    val fetchTimestamp: Long = System.currentTimeMillis(),
    val etag: String? = null,
    val byteSize: Long = 0L,
    val sourceUrl: String = "",
    val attribution: String = "",
    val retentionPermission: String = "PERMITTED",
    val checksum: String = ""
)

data class TileStorageStats(
    val totalTiles: Int,
    val totalBytes: Long,
    val categoryBreakdown: Map<TileCategory, Int>
)

/**
 * Production-ready, durable tile-by-tile offline storage manager.
 * Stores individually viewed map tiles and associated geographic data under persistent
 * internal app storage (retained across app restarts, force-stops, and cache evictions).
 * Enforces atomic file writes, CRC32 checksums, Room metadata synchronization,
 * MapLibre C++ OfflineManager resource injection, and in-flight request deduplication.
 */
class TileStorageManager(private val context: Context) {

    private val baseStorageDir: File = File(context.filesDir, "retained_tiles").apply {
        if (!exists()) mkdirs()
    }

    private val db = AppDatabase.getInstance(context)
    private val tileDao = db.retainedTileDao()

    // In-flight request locks: "provider:category:z/x/y" -> timestamp
    private val inFlightLocks = ConcurrentHashMap<String, Long>()

    // Fast memory cache of confirmed retained tiles: "provider:category:z/x/y" -> Boolean
    private val memoryCoverageCache = ConcurrentHashMap<String, Boolean>()

    fun buildTileKey(providerId: String, category: TileCategory, z: Int, x: Int, y: Int): String {
        return "$providerId:${category.name}:$z/$x/$y"
    }

    fun isTileRetained(providerId: String, category: TileCategory, z: Int, x: Int, y: Int): Boolean {
        val key = buildTileKey(providerId, category, z, x, y)
        if (memoryCoverageCache[key] == true) return true
        val file = getTileFile(providerId, category, z, x, y)
        val exists = file.exists() && file.length() > 0
        if (exists) memoryCoverageCache[key] = true
        return exists
    }

    fun getTileFile(providerId: String, category: TileCategory, z: Int, x: Int, y: Int): File {
        val providerDir = File(baseStorageDir, sanitizeDir(providerId))
        val catDir = File(providerDir, category.dirName)
        val zDir = File(catDir, z.toString())
        val xDir = File(zDir, x.toString())
        if (!xDir.exists()) xDir.mkdirs()
        return File(xDir, "$y.bin")
    }

    fun acquireLock(providerId: String, category: TileCategory, z: Int, x: Int, y: Int): Boolean {
        val key = buildTileKey(providerId, category, z, x, y)
        val now = System.currentTimeMillis()
        val existing = inFlightLocks.putIfAbsent(key, now)
        if (existing == null) return true
        // 15-second stale lock expiration
        if (now - existing > 15_000L) {
            inFlightLocks[key] = now
            return true
        }
        return false
    }

    fun releaseLock(providerId: String, category: TileCategory, z: Int, x: Int, y: Int) {
        val key = buildTileKey(providerId, category, z, x, y)
        inFlightLocks.remove(key)
    }

    /**
     * Atomically writes tile data to disk, calculates CRC32 checksum,
     * updates Room database metadata, and registers the URL with MapLibre's native OfflineManager.
     */
    suspend fun saveTileData(
        providerId: String,
        category: TileCategory,
        z: Int,
        x: Int,
        y: Int,
        data: ByteArray,
        metadata: TileMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getTileFile(providerId, category, z, x, y)
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            // Compute CRC32
            val crc = CRC32()
            crc.update(data)
            val checksum = crc.value.toString(16)

            // Atomic file write
            FileOutputStream(tempFile).use { fos ->
                fos.write(data)
                fos.flush()
            }
            if (targetFile.exists()) targetFile.delete()
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // Sync with Room Database
            val entity = RetainedTileEntity(
                providerId = providerId,
                category = category.name,
                z = z,
                x = x,
                y = y,
                filePath = targetFile.absolutePath,
                byteSize = data.size.toLong(),
                checksum = checksum,
                fetchTimestamp = metadata.fetchTimestamp,
                etag = metadata.etag,
                sourceUrl = metadata.sourceUrl,
                attribution = metadata.attribution,
                retentionPermission = metadata.retentionPermission,
                integrityStatus = "VALID"
            )
            tileDao.insertTile(entity)

            // Sync with MapLibre Native Offline C++ Resource Cache
            if (metadata.sourceUrl.isNotEmpty() && (category == TileCategory.OSM_VECTOR || category == TileCategory.SATELLITE_RASTER)) {
                try {
                    OfflineManager.getInstance(context).putResourceWithUrl(
                        metadata.sourceUrl,
                        data,
                        metadata.fetchTimestamp,
                        0L, // no expiration
                        metadata.etag,
                        false // mustRevalidate = false for offline playback
                    )
                } catch (e: Throwable) {
                    DiagnosticRepository.log(
                        severity = LogSeverity.DEBUG,
                        source = "TileStorageManager",
                        description = "MapLibre putResourceWithUrl non-fatal note: ${e.message}"
                    )
                }
            }

            val key = buildTileKey(providerId, category, z, x, y)
            memoryCoverageCache[key] = true
            true
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "TileStorageManager",
                description = "Failed saving tile $providerId $category $z/$x/$y: ${e.message}"
            )
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
            releaseLock(providerId, category, z, x, y)
        }
    }

    suspend fun loadTileData(
        providerId: String,
        category: TileCategory,
        z: Int,
        x: Int,
        y: Int
    ): ByteArray? = withContext(Dispatchers.IO) {
        val file = getTileFile(providerId, category, z, x, y)
        if (!file.exists() || file.length() == 0L) return@withContext null

        try {
            val bytes = file.readBytes()
            // Verify integrity against Room checksum if available
            val record = tileDao.getTile(providerId, category.name, z, x, y)
            if (record != null && record.checksum.isNotEmpty()) {
                val crc = CRC32()
                crc.update(bytes)
                val currentChecksum = crc.value.toString(16)
                if (currentChecksum != record.checksum) {
                    DiagnosticRepository.log(
                        severity = LogSeverity.ERROR,
                        source = "TileStorageManager",
                        description = "Corrupted tile detected: $providerId $category $z/$x/$y. Recovering by purge."
                    )
                    file.delete()
                    tileDao.deleteTile(providerId, category.name, z, x, y)
                    memoryCoverageCache.remove(buildTileKey(providerId, category, z, x, y))
                    return@withContext null
                }
            }
            bytes
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun getStorageStats(): TileStorageStats = withContext(Dispatchers.IO) {
        val count = tileDao.getTileCount()
        val bytes = tileDao.getTotalBytes() ?: 0L
        val breakdown = mutableMapOf<TileCategory, Int>()
        TileCategory.values().forEach { cat ->
            breakdown[cat] = tileDao.getTileCountByCategory(cat.name)
        }
        TileStorageStats(count, bytes, breakdown)
    }

    private fun sanitizeDir(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
