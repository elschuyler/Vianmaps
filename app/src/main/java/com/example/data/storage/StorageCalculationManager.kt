package com.example.data.storage

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.map.source.MapSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Storage metrics for an individual map provider / source.
 */
data class SourceStorageReport(
    val providerId: String,
    val providerName: String,
    val providerType: String,
    val tileCount: Int,
    val byteSize: Long,
    val formattedSize: String,
    val isDeletable: Boolean = true
)

/**
 * Storage metrics for offline OpenStreetMap feature records in Room SQLite.
 */
data class FeatureStorageReport(
    val poiCount: Int,
    val buildingCount: Int,
    val roadCount: Int,
    val regionCount: Int,
    val databaseSizeBytes: Long,
    val formattedDatabaseSize: String
)

/**
 * Storage metrics for the offline routing graph index.
 */
data class RoutingStorageReport(
    val downloadedTiles: Int,
    val byteSize: Long,
    val formattedSize: String,
    val status: String
)

/**
 * Comprehensive on-device storage audit showing exact byte and tile footprint.
 */
data class OverallStorageReport(
    val perSource: List<SourceStorageReport>,
    val featureStorage: FeatureStorageReport,
    val routingStorage: RoutingStorageReport,
    val totalTileBytes: Long,
    val totalBytes: Long,
    val formattedTotalSize: String,
    val lastCalculatedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Manages honest on-disk storage calculation and safe per-source cache purging.
 */
object StorageCalculationManager {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    suspend fun calculateStorage(context: Context, database: AppDatabase): OverallStorageReport = withContext(Dispatchers.IO) {
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        // 1. Audit retained tile files per provider on disk
        val providerReports = mutableListOf<SourceStorageReport>()
        val knownStyles = MapSourceRepository.availableStyles.filter { !it.isTemporaryDevelopmentStyle }

        var totalTileBytes = 0L

        for (style in knownStyles) {
            val providerDir = File(retainedTilesRoot, style.id)
            val (diskBytes, diskCount) = measureDirectory(providerDir)
            
            // Reconcile with database if available
            val dbCount = database.retainedTileDao().getTileCountByProvider(style.id)
            val dbBytes = database.retainedTileDao().getBytesByProvider(style.id) ?: 0L

            val reportedCount = maxOf(diskCount, dbCount)
            val reportedBytes = maxOf(diskBytes, dbBytes)
            totalTileBytes += reportedBytes

            providerReports.add(
                SourceStorageReport(
                    providerId = style.id,
                    providerName = style.displayName,
                    providerType = if (style.isCompositeHybrid) "Satellite Hybrid" else "Vector Map",
                    tileCount = reportedCount,
                    byteSize = reportedBytes,
                    formattedSize = formatBytes(reportedBytes),
                    isDeletable = reportedCount > 0
                )
            )
        }

        // Check for any unlisted provider directories under retained_tiles
        if (retainedTilesRoot.exists() && retainedTilesRoot.isDirectory) {
            val unlistedDirs = retainedTilesRoot.listFiles()?.filter { dir ->
                dir.isDirectory && knownStyles.none { it.id == dir.name }
            } ?: emptyList()

            for (dir in unlistedDirs) {
                val (uBytes, uCount) = measureDirectory(dir)
                if (uCount > 0 || uBytes > 0) {
                    totalTileBytes += uBytes
                    providerReports.add(
                        SourceStorageReport(
                            providerId = dir.name,
                            providerName = "Other (${dir.name})",
                            providerType = "Cached Tiles",
                            tileCount = uCount,
                            byteSize = uBytes,
                            formattedSize = formatBytes(uBytes),
                            isDeletable = true
                        )
                    )
                }
            }
        }

        // 2. Measure Room SQLite Database files
        val dbFile = context.getDatabasePath("map_app_master.db")
        var dbBytes = 0L
        if (dbFile.exists()) dbBytes += dbFile.length()
        val walFile = File(dbFile.path + "-wal")
        if (walFile.exists()) dbBytes += walFile.length()
        val shmFile = File(dbFile.path + "-shm")
        if (shmFile.exists()) dbBytes += shmFile.length()

        val poiCount = database.osmPoiDao().getPoiCount()
        val buildingCount = database.osmBuildingDao().getBuildingCount()
        val roadCount = database.osmRoadDao().getRoadCount()
        val regionCount = database.osmRegionDao().getRegionCount()

        val featureReport = FeatureStorageReport(
            poiCount = poiCount,
            buildingCount = buildingCount,
            roadCount = roadCount,
            regionCount = regionCount,
            databaseSizeBytes = dbBytes,
            formattedDatabaseSize = formatBytes(dbBytes)
        )

        // 3. Routing graph inspection
        val routingDir = File(context.filesDir, "routing_graph")
        val (routingBytes, _) = measureDirectory(routingDir)
        val downloadedRoutingTiles = database.routingGraphTileDao().getDownloadedTileCount()
        val routingReport = RoutingStorageReport(
            downloadedTiles = downloadedRoutingTiles,
            byteSize = routingBytes,
            formattedSize = formatBytes(routingBytes),
            status = if (downloadedRoutingTiles > 0) "$downloadedRoutingTiles graph tiles active" else "Not configured (0 bytes)"
        )

        // 4. Overall total
        val totalBytes = totalTileBytes + dbBytes + routingBytes

        OverallStorageReport(
            perSource = providerReports,
            featureStorage = featureReport,
            routingStorage = routingReport,
            totalTileBytes = totalTileBytes,
            totalBytes = totalBytes,
            formattedTotalSize = formatBytes(totalBytes)
        )
    }

    /**
     * Safely purges cached tiles for a specific provider from disk and database,
     * without touching user records or shared OSM features.
     */
    suspend fun clearProviderTiles(context: Context, database: AppDatabase, providerId: String): Long = withContext(Dispatchers.IO) {
        val providerDir = File(File(context.filesDir, "retained_tiles"), providerId)
        val bytesDeleted = if (providerDir.exists()) {
            val (bytes, _) = measureDirectory(providerDir)
            providerDir.deleteRecursively()
            bytes
        } else 0L

        database.retainedTileDao().deleteTilesByProvider(providerId)
        bytesDeleted
    }

    private fun measureDirectory(dir: File?): Pair<Long, Int> {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0L to 0
        var totalBytes = 0L
        var totalCount = 0

        val stack = ArrayDeque<File>()
        stack.add(dir)

        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            val files = current.listFiles() ?: continue
            for (file in files) {
                if (file.isDirectory) {
                    stack.add(file)
                } else {
                    totalBytes += file.length()
                    totalCount++
                }
            }
        }
        return totalBytes to totalCount
    }
}
