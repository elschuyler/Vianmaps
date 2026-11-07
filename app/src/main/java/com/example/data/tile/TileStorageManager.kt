package com.example.data.tile

import android.content.Context
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
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

data class TileBoundingBox(
    val northLat: Double,
    val southLat: Double,
    val westLng: Double,
    val eastLng: Double
)

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
    val schemaVersion: String,
    val fetchTimestamp: Long = System.currentTimeMillis(),
    val etag: String? = null,
    val byteSize: Long = 0L,
    val attribution: String = ""
)

data class TileStorageStats(
    val totalTiles: Int,
    val totalBytes: Long,
    val categoryBreakdown: Map<TileCategory, Int>
)

/**
 * Persistent, automatic tile-by-tile offline storage manager.
 * Stores individually viewed map tiles and associated geographic data under persistent
 * internal app storage (retained across app restarts and cache evictions).
 * Enforces independent availability across data categories and in-flight request deduplication.
 */
class TileStorageManager(private val context: Context) {

    private val baseStorageDir: File = File(context.filesDir, "retained_tiles").apply {
        if (!exists()) mkdirs()
    }

    // In-flight request lock map to prevent redundant concurrent fetches for the same tile
    private val inFlightLocks = ConcurrentHashMap<String, Long>()

    // In-memory coverage index mapping "category:z/x/y" -> TileMetadata
    private val coverageIndex = ConcurrentHashMap<String, TileMetadata>()

    init {
        // Scan pre-existing retained tiles on disk into coverage index
        scanExistingTiles()
    }

    private fun scanExistingTiles() {
        try {
            var count = 0
            TileCategory.values().forEach { cat ->
                val catDir = File(baseStorageDir, cat.dirName)
                if (catDir.exists() && catDir.isDirectory) {
                    catDir.walkTopDown().filter { it.isFile && it.extension == "bin" }.forEach { file ->
                        val parts = file.parentFile?.parentFile?.name to file.nameWithoutExtension
                        // e.g. path format: category/z/x/y.bin
                        val pathParts = file.relativeTo(baseStorageDir).path.split(File.separator)
                        if (pathParts.size == 4) {
                            val z = pathParts[1].toIntOrNull()
                            val x = pathParts[2].toIntOrNull()
                            val y = pathParts[3].substringBeforeLast(".").toIntOrNull()
                            if (z != null && x != null && y != null) {
                                val key = buildTileKey(cat, z, x, y)
                                coverageIndex[key] = TileMetadata(
                                    providerId = "persisted_local",
                                    schemaVersion = "1.0",
                                    fetchTimestamp = file.lastModified(),
                                    byteSize = file.length()
                                )
                                count++
                            }
                        }
                    }
                }
            }
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "TileStorageManager",
                description = "Indexed $count persistent offline tiles from disk storage",
                stage = "TILE_INDEX_READY"
            )
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "TileStorageManager",
                description = "Error scanning existing tiles: ${e.message}"
            )
        }
    }

    fun buildTileKey(category: TileCategory, z: Int, x: Int, y: Int): String {
        return "${category.name}:$z/$x/$y"
    }

    fun isTileRetained(category: TileCategory, z: Int, x: Int, y: Int): Boolean {
        val key = buildTileKey(category, z, x, y)
        if (coverageIndex.containsKey(key)) return true
        val file = getTileFile(category, z, x, y)
        return file.exists() && file.length() > 0
    }

    fun getTileFile(category: TileCategory, z: Int, x: Int, y: Int): File {
        val zDir = File(File(File(baseStorageDir, category.dirName), z.toString()), x.toString())
        if (!zDir.exists()) zDir.mkdirs()
        return File(zDir, "$y.bin")
    }

    fun acquireLock(category: TileCategory, z: Int, x: Int, y: Int): Boolean {
        val key = buildTileKey(category, z, x, y)
        val now = System.currentTimeMillis()
        val existing = inFlightLocks.putIfAbsent(key, now)
        if (existing == null) return true
        // Stale lock timeout: 15 seconds
        if (now - existing > 15_000L) {
            inFlightLocks[key] = now
            return true
        }
        return false
    }

    fun releaseLock(category: TileCategory, z: Int, x: Int, y: Int) {
        val key = buildTileKey(category, z, x, y)
        inFlightLocks.remove(key)
    }

    @Synchronized
    fun saveTileData(
        category: TileCategory,
        z: Int,
        x: Int,
        y: Int,
        data: ByteArray,
        metadata: TileMetadata
    ): Boolean {
        return try {
            val file = getTileFile(category, z, x, y)
            FileOutputStream(file).use { it.write(data) }
            val key = buildTileKey(category, z, x, y)
            coverageIndex[key] = metadata.copy(byteSize = data.size.toLong())
            releaseLock(category, z, x, y)
            true
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "TileStorageManager",
                description = "Failed to save tile $category $z/$x/$y: ${e.message}"
            )
            false
        }
    }

    fun loadTileData(category: TileCategory, z: Int, x: Int, y: Int): ByteArray? {
        val file = getTileFile(category, z, x, y)
        return if (file.exists() && file.length() > 0) {
            try {
                file.readBytes()
            } catch (e: Throwable) {
                null
            }
        } else {
            null
        }
    }

    fun getStorageStats(): TileStorageStats {
        val breakdown = mutableMapOf<TileCategory, Int>()
        TileCategory.values().forEach { cat ->
            breakdown[cat] = coverageIndex.keys.count { it.startsWith(cat.name) }
        }
        val totalBytes = coverageIndex.values.sumOf { it.byteSize }
        return TileStorageStats(
            totalTiles = coverageIndex.size,
            totalBytes = totalBytes,
            categoryBreakdown = breakdown
        )
    }
}
