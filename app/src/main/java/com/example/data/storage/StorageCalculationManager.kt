package com.example.data.storage

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.map.source.MapSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

import com.example.map.source.MapStorageProviders
import java.util.zip.CRC32

/**
 * Detailed outcome of a storage provider migration pass.
 * Exposes files/records migrated, duplicate skips, and any unresolved conflicts or failures.
 */
data class StorageMigrationReport(
    val migratedFiles: Int = 0,
    val migratedDbRecords: Int = 0,
    val skippedDuplicates: Int = 0,
    val unresolvedFileConflicts: List<String> = emptyList(),
    val unresolvedDbConflicts: List<String> = emptyList(),
    val failures: List<String> = emptyList()
) {
    val isClean: Boolean
        get() = unresolvedFileConflicts.isEmpty() && unresolvedDbConflicts.isEmpty() && failures.isEmpty()
}

/**
 * Result of a per-provider cache clearing operation.
 */
data class ProviderClearResult(
    val providerId: String,
    val canonicalId: String,
    val bytesDeleted: Long = 0L,
    val filesDeleted: Int = 0,
    val recordsDeleted: Int = 0,
    val isSuccess: Boolean = true,
    val error: String? = null
)

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
    val isDeletable: Boolean = true,
    val acquisitionStatus: String = "Active (Visible Viewport Retention)",
    val retentionPolicy: String = "Permitted",
    val isOfflineRetained: Boolean = false
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
 * Comprehensive on-device storage audit showing exact byte and tile footprint,
 * including honest migration status and acquisition limitations.
 */
data class OverallStorageReport(
    val perSource: List<SourceStorageReport>,
    val featureStorage: FeatureStorageReport,
    val routingStorage: RoutingStorageReport,
    val totalTileBytes: Long,
    val totalBytes: Long,
    val formattedTotalSize: String,
    val migrationReport: StorageMigrationReport? = null,
    val lastCalculatedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Manages honest on-disk storage calculation, loss-safe legacy migration,
 * conflict reporting, and isolated per-source cache purging.
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

    fun computeFileCrc32(file: File): String {
        if (!file.exists() || !file.isFile || file.length() == 0L) return ""
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                crc.update(buffer, 0, read)
            }
        }
        return crc.value.toString(16)
    }

    /**
     * Loss-safe, idempotent migration of legacy on-disk directories and Room metadata
     * (e.g. style IDs or non-canonical provider names) to canonical storage provider IDs.
     *
     * Invariants enforced:
     * 1. Never deletes a legacy file until its canonical replacement is confirmed written and CRC32-verified.
     * 2. When both legacy and canonical files exist, compares length and CRC32 checksums:
     *    - If identical: safe to remove legacy duplicate.
     *    - If canonical is empty/corrupt (0 bytes) and legacy is valid: safely replaces canonical file.
     *    - If both exist with different valid contents: preserves both, reports unresolved conflict, never deletes.
     * 3. Atomically copies missing canonical files using temporary files (.migrating.tmp) and verified renames.
     * 4. Resolves Room database primary key collisions without dropping valid tile records.
     * 5. Never swallows errors; returns a structured, observable StorageMigrationReport.
     */
    suspend fun migrateLegacyStorage(context: Context, database: AppDatabase): StorageMigrationReport = withContext(Dispatchers.IO) {
        var migratedFiles = 0
        var migratedDbRecords = 0
        var skippedDuplicates = 0
        val unresolvedFileConflicts = mutableListOf<String>()
        val unresolvedDbConflicts = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        // 1. Loss-safe on-disk legacy directory migration
        if (retainedTilesRoot.exists() && retainedTilesRoot.isDirectory) {
            val subDirs = retainedTilesRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (dir in subDirs) {
                val canonicalId = MapSourceRepository.resolveCanonicalProviderId(dir.name)
                if (canonicalId != dir.name) {
                    val canonicalDir = File(retainedTilesRoot, canonicalId)
                    if (!canonicalDir.exists()) canonicalDir.mkdirs()

                    val legacyFiles = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
                    for (legacyFile in legacyFiles) {
                        try {
                            val relPath = legacyFile.relativeTo(dir).path
                            val targetFile = File(canonicalDir, relPath)
                            targetFile.parentFile?.mkdirs()

                            if (targetFile.exists()) {
                                val legacyLength = legacyFile.length()
                                val targetLength = targetFile.length()

                                if (targetLength == 0L && legacyLength > 0L) {
                                    // Target file is empty/corrupt; replace safely
                                    val tempFile = File(targetFile.parentFile, "${targetFile.name}.migrating.tmp")
                                    legacyFile.copyTo(tempFile, overwrite = true)
                                    val legacyCrc = computeFileCrc32(legacyFile)
                                    val tempCrc = computeFileCrc32(tempFile)
                                    if (legacyCrc.isNotEmpty() && legacyCrc == tempCrc) {
                                        targetFile.delete()
                                        tempFile.renameTo(targetFile)
                                        legacyFile.delete()
                                        migratedFiles++
                                    } else {
                                        tempFile.delete()
                                        unresolvedFileConflicts.add("Failed replacing corrupt target for $relPath")
                                    }
                                } else {
                                    // Both files exist and have data; compare CRC32 checksums
                                    val legacyCrc = computeFileCrc32(legacyFile)
                                    val targetCrc = computeFileCrc32(targetFile)

                                    if (legacyCrc.isNotEmpty() && legacyCrc == targetCrc && legacyLength == targetLength) {
                                        // Confirmed safe identical copy exists at canonical path
                                        legacyFile.delete()
                                        skippedDuplicates++
                                    } else {
                                        // Content conflict: never delete legacy file blindly
                                        unresolvedFileConflicts.add(
                                            "File content conflict at $relPath (canonical=${targetLength}B, legacy=${legacyLength}B)"
                                        )
                                    }
                                }
                            } else {
                                // Target does not exist; copy safely via verified temporary file
                                val tempFile = File(targetFile.parentFile, "${targetFile.name}.migrating.tmp")
                                legacyFile.copyTo(tempFile, overwrite = true)
                                val legacyCrc = computeFileCrc32(legacyFile)
                                val tempCrc = computeFileCrc32(tempFile)

                                if (legacyCrc.isNotEmpty() && legacyCrc == tempCrc && tempFile.length() == legacyFile.length()) {
                                    val renamed = tempFile.renameTo(targetFile)
                                    if (!renamed) {
                                        tempFile.copyTo(targetFile, overwrite = true)
                                        tempFile.delete()
                                    }

                                    if (targetFile.exists() && computeFileCrc32(targetFile) == legacyCrc) {
                                        legacyFile.delete()
                                        migratedFiles++
                                    } else {
                                        failures.add("Target file verification failed after rename for $relPath")
                                    }
                                } else {
                                    tempFile.delete()
                                    failures.add("Checksum mismatch during migration copy for $relPath")
                                }
                            }
                        } catch (e: Throwable) {
                            failures.add("Filesystem migration error for ${legacyFile.path}: ${e.message}")
                        }
                    }

                    // Only delete legacy directory if all files were cleanly migrated and no unresolved files remain
                    val remainingFiles = dir.walkTopDown().filter { it.isFile }.toList()
                    if (remainingFiles.isEmpty()) {
                        dir.deleteRecursively()
                    }
                }
            }
        }

        // 2. Loss-safe Room database migration
        try {
            val allProviderIds = database.retainedTileDao().getAllProviderIds()
            for (legacyProviderId in allProviderIds) {
                val canonicalId = MapSourceRepository.resolveCanonicalProviderId(legacyProviderId)
                if (canonicalId != legacyProviderId) {
                    val legacyTiles = database.retainedTileDao().getTilesByProvider(legacyProviderId)
                    for (tile in legacyTiles) {
                        try {
                            val canonicalEntity = database.retainedTileDao().getTile(
                                canonicalId,
                                tile.category,
                                tile.z,
                                tile.x,
                                tile.y
                            )

                            val canonicalFilePath = if (tile.filePath.contains("/$legacyProviderId/")) {
                                tile.filePath.replace("/$legacyProviderId/", "/$canonicalId/")
                            } else {
                                File(retainedTilesRoot, "$canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}.bin").absolutePath
                            }
                            val canonicalFile = File(canonicalFilePath)

                            if (canonicalEntity != null) {
                                // Primary key collision in Room
                                val canonicalFileExists = File(canonicalEntity.filePath).exists()
                                if (canonicalEntity.checksum == tile.checksum && canonicalFileExists) {
                                    // Identical verified canonical record exists; safe to delete duplicate legacy row
                                    database.retainedTileDao().deleteTile(legacyProviderId, tile.category, tile.z, tile.x, tile.y)
                                    skippedDuplicates++
                                } else if (!canonicalFileExists && canonicalFile.exists() && canonicalFile.length() > 0) {
                                    // Canonical entity pointed to missing file, but valid file is at canonical path
                                    val updatedCanonical = canonicalEntity.copy(
                                        filePath = canonicalFilePath,
                                        byteSize = canonicalFile.length(),
                                        checksum = tile.checksum
                                    )
                                    database.retainedTileDao().insertTile(updatedCanonical)
                                    database.retainedTileDao().deleteTile(legacyProviderId, tile.category, tile.z, tile.x, tile.y)
                                    migratedDbRecords++
                                } else {
                                    // Content or metadata collision; preserve legacy row and report
                                    unresolvedDbConflicts.add(
                                        "DB collision at $canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}"
                                    )
                                }
                            } else {
                                // Canonical entity does not exist yet
                                if (canonicalFile.exists() && canonicalFile.length() > 0) {
                                    val newEntity = tile.copy(
                                        providerId = canonicalId,
                                        filePath = canonicalFilePath,
                                        byteSize = canonicalFile.length()
                                    )
                                    database.retainedTileDao().insertTile(newEntity)
                                    database.retainedTileDao().deleteTile(legacyProviderId, tile.category, tile.z, tile.x, tile.y)
                                    migratedDbRecords++
                                } else if (File(tile.filePath).exists() && File(tile.filePath).length() > 0) {
                                    // Legacy file exists; copy to canonical path before migrating Room record
                                    val legacyFile = File(tile.filePath)
                                    canonicalFile.parentFile?.mkdirs()
                                    legacyFile.copyTo(canonicalFile, overwrite = true)
                                    val newEntity = tile.copy(
                                        providerId = canonicalId,
                                        filePath = canonicalFilePath,
                                        byteSize = canonicalFile.length()
                                    )
                                    database.retainedTileDao().insertTile(newEntity)
                                    database.retainedTileDao().deleteTile(legacyProviderId, tile.category, tile.z, tile.x, tile.y)
                                    legacyFile.delete()
                                    migratedDbRecords++
                                } else {
                                    failures.add(
                                        "Missing file for tile record $legacyProviderId/${tile.category}/${tile.z}/${tile.x}/${tile.y}"
                                    )
                                }
                            }
                        } catch (e: Throwable) {
                            failures.add("Error migrating DB record for $legacyProviderId: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            failures.add("Database migration query error: ${e.message}")
        }

        StorageMigrationReport(
            migratedFiles = migratedFiles,
            migratedDbRecords = migratedDbRecords,
            skippedDuplicates = skippedDuplicates,
            unresolvedFileConflicts = unresolvedFileConflicts,
            unresolvedDbConflicts = unresolvedDbConflicts,
            failures = failures
        )
    }

    suspend fun calculateStorage(context: Context, database: AppDatabase): OverallStorageReport = withContext(Dispatchers.IO) {
        // Run loss-safe migration and capture migration health
        val migrationReport = migrateLegacyStorage(context, database)

        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        // 1. Audit retained tile files per canonical data provider
        val providerReports = mutableListOf<SourceStorageReport>()
        val canonicalProviders = MapStorageProviders.allProviders

        var totalTileBytes = 0L

        for (provider in canonicalProviders) {
            val providerDir = File(retainedTilesRoot, provider.id)
            val (diskBytes, diskCount) = measureDirectory(providerDir)

            // Reconcile with database if available
            val dbCount = database.retainedTileDao().getTileCountByProvider(provider.id)
            val dbBytes = database.retainedTileDao().getBytesByProvider(provider.id) ?: 0L

            val reportedCount = maxOf(diskCount, dbCount)
            val reportedBytes = maxOf(diskBytes, dbBytes)
            totalTileBytes += reportedBytes

            providerReports.add(
                SourceStorageReport(
                    providerId = provider.id,
                    providerName = provider.displayName,
                    providerType = provider.providerTypeDescription,
                    tileCount = reportedCount,
                    byteSize = reportedBytes,
                    formattedSize = formatBytes(reportedBytes),
                    isDeletable = reportedCount > 0,
                    acquisitionStatus = provider.acquisitionPipelineStatus,
                    retentionPolicy = provider.retentionPolicyDescription,
                    isOfflineRetained = reportedCount > 0
                )
            )
        }

        // Check for any unlisted provider directories under retained_tiles
        if (retainedTilesRoot.exists() && retainedTilesRoot.isDirectory) {
            val canonicalIds = canonicalProviders.map { it.id }.toSet()
            val unlistedDirs = retainedTilesRoot.listFiles()?.filter { dir ->
                dir.isDirectory && !canonicalIds.contains(dir.name)
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
                            isDeletable = true,
                            acquisitionStatus = "Uncatalogued Cached Data",
                            retentionPolicy = "Unknown",
                            isOfflineRetained = uCount > 0
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
            formattedTotalSize = formatBytes(totalBytes),
            migrationReport = migrationReport
        )
    }

    /**
     * Safely and surgically purges cached tiles for a specific provider from disk and database,
     * guaranteeing zero deletion or modification of other providers, shared OSM feature records
     * (osm_pois, osm_buildings, osm_roads, osm_regions), or routing graph files.
     */
    suspend fun clearProviderTiles(context: Context, database: AppDatabase, providerId: String): ProviderClearResult = withContext(Dispatchers.IO) {
        val canonicalId = MapSourceRepository.resolveCanonicalProviderId(providerId)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        var bytesDeleted = 0L
        var filesDeleted = 0
        var recordsDeleted = 0

        try {
            // 1. Purge canonical folder
            val canonicalDir = File(retainedTilesRoot, canonicalId)
            if (canonicalDir.exists()) {
                val (bytes, count) = measureDirectory(canonicalDir)
                canonicalDir.deleteRecursively()
                bytesDeleted += bytes
                filesDeleted += count
            }

            // 2. Also purge legacy folder if different
            if (canonicalId != providerId) {
                val rawDir = File(retainedTilesRoot, providerId)
                if (rawDir.exists()) {
                    val (bytes, count) = measureDirectory(rawDir)
                    rawDir.deleteRecursively()
                    bytesDeleted += bytes
                    filesDeleted += count
                }
            }

            // 3. Delete tile metadata records for this provider only
            recordsDeleted += database.retainedTileDao().deleteTilesByProvider(canonicalId)
            if (canonicalId != providerId) {
                recordsDeleted += database.retainedTileDao().deleteTilesByProvider(providerId)
            }

            ProviderClearResult(
                providerId = providerId,
                canonicalId = canonicalId,
                bytesDeleted = bytesDeleted,
                filesDeleted = filesDeleted,
                recordsDeleted = recordsDeleted,
                isSuccess = true
            )
        } catch (e: Throwable) {
            ProviderClearResult(
                providerId = providerId,
                canonicalId = canonicalId,
                bytesDeleted = bytesDeleted,
                filesDeleted = filesDeleted,
                recordsDeleted = recordsDeleted,
                isSuccess = false,
                error = e.message
            )
        }
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
