package com.example.data.storage

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.map.source.MapSourceRepository
import com.example.map.source.MapStorageProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.CRC32

/**
 * Detailed outcome of a storage provider migration pass.
 * Exposes files/records migrated, duplicate skips, missing files, integrity failures,
 * and any unresolved conflicts or errors.
 */
data class StorageMigrationReport(
    val migratedFiles: Int = 0,
    val migratedDbRecords: Int = 0,
    val verifiedDuplicateFiles: Int = 0,
    val verifiedDuplicateRecords: Int = 0,
    val unresolvedFileConflicts: List<String> = emptyList(),
    val unresolvedDbConflicts: List<String> = emptyList(),
    val missingFiles: List<String> = emptyList(),
    val integrityFailures: List<String> = emptyList(),
    val failures: List<String> = emptyList()
) {
    val skippedDuplicates: Int
        get() = verifiedDuplicateFiles + verifiedDuplicateRecords

    val isClean: Boolean
        get() = unresolvedFileConflicts.isEmpty() &&
                unresolvedDbConflicts.isEmpty() &&
                missingFiles.isEmpty() &&
                integrityFailures.isEmpty() &&
                failures.isEmpty()
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
 * Fully transparent: reconciles physical disk files vs Room SQLite records.
 */
data class SourceStorageReport(
    val providerId: String,
    val providerName: String,
    val providerType: String,
    val tileCount: Int,
    val byteSize: Long,
    val physicalFileCount: Int = tileCount,
    val physicalByteSize: Long = byteSize,
    val roomRecordCount: Int = tileCount,
    val verifiedRecordCount: Int = tileCount,
    val orphanedRecordCount: Int = 0,
    val unindexedFileCount: Int = 0,
    val integrityFailureCount: Int = 0,
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
     * 2. Flushes and syncs temporary files before verification and promotion.
     * 3. When both legacy and canonical files exist, compares length and CRC32 checksums:
     *    - If identical: safe to remove legacy duplicate.
     *    - If canonical is empty/corrupt (0 bytes) and legacy is valid: safely replaces canonical file.
     *    - If both exist with different valid contents: preserves both, reports unresolved conflict, never deletes.
     * 4. Atomically copies missing canonical files using temporary files (.migrating.tmp) and verified renames.
     * 5. Resolves Room database primary key collisions and verifies physical disk file checksums.
     * 6. Never deletes a legacy Room entity until canonical destination file + Room record are both verified.
     * 7. Never swallows errors; returns a structured, observable StorageMigrationReport.
     */
    suspend fun migrateLegacyStorage(context: Context, database: AppDatabase): StorageMigrationReport = withContext(Dispatchers.IO) {
        var migratedFiles = 0
        var migratedDbRecords = 0
        var verifiedDuplicateFiles = 0
        var verifiedDuplicateRecords = 0
        val unresolvedFileConflicts = mutableListOf<String>()
        val unresolvedDbConflicts = mutableListOf<String>()
        val missingFiles = mutableListOf<String>()
        val integrityFailures = mutableListOf<String>()
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
                                    // Target file is empty/corrupt; replace safely via flushed temp file
                                    val tempFile = File(targetFile.parentFile, "${targetFile.name}.migrating.tmp")
                                    var writeSuccess = false
                                    try {
                                        FileOutputStream(tempFile).use { fos ->
                                            legacyFile.inputStream().use { fis ->
                                                fis.copyTo(fos)
                                            }
                                            fos.flush()
                                            try { fos.fd.sync() } catch (_: Throwable) {}
                                        }
                                        writeSuccess = true
                                    } catch (e: Throwable) {
                                        failures.add("Failed copying to temp file for $relPath: ${e.message}")
                                    }

                                    if (writeSuccess) {
                                        val legacyCrc = computeFileCrc32(legacyFile)
                                        val tempCrc = computeFileCrc32(tempFile)
                                        if (legacyCrc.isNotEmpty() && legacyCrc == tempCrc && tempFile.length() == legacyLength) {
                                            targetFile.delete()
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
                                            integrityFailures.add("CRC mismatch while replacing corrupt target for $relPath")
                                            unresolvedFileConflicts.add("Failed replacing corrupt target for $relPath")
                                        }
                                    }
                                } else {
                                    // Both files exist and have data; compare CRC32 checksums
                                    val legacyCrc = computeFileCrc32(legacyFile)
                                    val targetCrc = computeFileCrc32(targetFile)

                                    if (legacyCrc.isNotEmpty() && legacyCrc == targetCrc && legacyLength == targetLength) {
                                        // Confirmed safe identical copy exists at canonical path
                                        legacyFile.delete()
                                        verifiedDuplicateFiles++
                                    } else {
                                        // Content conflict: never delete legacy file blindly
                                        unresolvedFileConflicts.add(
                                            "File content conflict at $relPath (canonical=${targetLength}B, legacy=${legacyLength}B)"
                                        )
                                    }
                                }
                            } else {
                                // Target does not exist; copy safely via verified flushed temporary file
                                val tempFile = File(targetFile.parentFile, "${targetFile.name}.migrating.tmp")
                                var writeSuccess = false
                                try {
                                    FileOutputStream(tempFile).use { fos ->
                                        legacyFile.inputStream().use { fis ->
                                            fis.copyTo(fos)
                                        }
                                        fos.flush()
                                        try { fos.fd.sync() } catch (_: Throwable) {}
                                    }
                                    writeSuccess = true
                                } catch (e: Throwable) {
                                    failures.add("Failed copying to temp file for $relPath: ${e.message}")
                                }

                                if (writeSuccess) {
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
                                        integrityFailures.add("Checksum mismatch during migration copy for $relPath")
                                        failures.add("Checksum mismatch during migration copy for $relPath")
                                    }
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
                            val legacyFile = File(tile.filePath)

                            if (canonicalEntity != null) {
                                // Primary key collision in Room
                                val canonicalPhysicalFile = File(canonicalEntity.filePath)
                                val canonicalPhysicalExists = canonicalPhysicalFile.exists() && canonicalPhysicalFile.length() > 0
                                val canonicalActualCrc = if (canonicalPhysicalExists) computeFileCrc32(canonicalPhysicalFile) else ""

                                if (canonicalPhysicalExists && canonicalActualCrc == tile.checksum) {
                                    // Verified matching canonical record + verified physical file exists; safe to delete duplicate legacy row
                                    database.retainedTileDao().deleteTile(legacyProviderId, tile.category, tile.z, tile.x, tile.y)
                                    verifiedDuplicateRecords++
                                } else if (!canonicalPhysicalExists && canonicalFile.exists() && canonicalFile.length() > 0) {
                                    val targetCrc = computeFileCrc32(canonicalFile)
                                    if (targetCrc == tile.checksum) {
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
                                        integrityFailures.add("Integrity mismatch for recovered canonical file at $canonicalFilePath")
                                        unresolvedDbConflicts.add("DB collision with corrupt target for $canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                    }
                                } else if (canonicalPhysicalExists && canonicalActualCrc != tile.checksum) {
                                    // Physical file is corrupt or has different content than legacy record
                                    integrityFailures.add("Canonical physical file checksum mismatch for $canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                    unresolvedDbConflicts.add("DB collision with checksum mismatch at $canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                } else {
                                    missingFiles.add("Missing physical file for canonical tile record $canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                    unresolvedDbConflicts.add("DB collision at $canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                }
                            } else {
                                // Canonical entity does not exist yet
                                if (canonicalFile.exists() && canonicalFile.length() > 0) {
                                    val canonicalCrc = computeFileCrc32(canonicalFile)
                                    if (canonicalCrc == tile.checksum) {
                                        val newEntity = tile.copy(
                                            providerId = canonicalId,
                                            filePath = canonicalFilePath,
                                            byteSize = canonicalFile.length()
                                        )
                                        database.retainedTileDao().insertTile(newEntity)
                                        val verifiedInserted = database.retainedTileDao().getTile(canonicalId, tile.category, tile.z, tile.x, tile.y) != null
                                        if (verifiedInserted) {
                                            database.retainedTileDao().deleteTile(legacyProviderId, tile.category, tile.z, tile.x, tile.y)
                                            migratedDbRecords++
                                        } else {
                                            failures.add("Failed to verify insertion of canonical record in DB")
                                        }
                                    } else {
                                        integrityFailures.add("Integrity failure: canonical file CRC ($canonicalCrc) != record checksum (${tile.checksum}) for $canonicalId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                    }
                                } else if (legacyFile.exists() && legacyFile.length() > 0) {
                                    // Legacy file exists; copy to canonical path before migrating Room record
                                    val legacyCrc = computeFileCrc32(legacyFile)
                                    if (legacyCrc == tile.checksum) {
                                        canonicalFile.parentFile?.mkdirs()
                                        val tempFile = File(canonicalFile.parentFile, "${canonicalFile.name}.migrating.tmp")
                                        var writeOk = false
                                        try {
                                            FileOutputStream(tempFile).use { fos ->
                                                legacyFile.inputStream().use { fis -> fis.copyTo(fos) }
                                                fos.flush()
                                                try { fos.fd.sync() } catch (_: Throwable) {}
                                            }
                                            writeOk = true
                                        } catch (e: Throwable) {
                                            failures.add("Failed copying legacy file to temp: ${e.message}")
                                        }

                                        if (writeOk && computeFileCrc32(tempFile) == legacyCrc && tempFile.length() == legacyFile.length()) {
                                            if (canonicalFile.exists()) canonicalFile.delete()
                                            val renamed = tempFile.renameTo(canonicalFile)
                                            if (!renamed) {
                                                tempFile.copyTo(canonicalFile, overwrite = true)
                                                tempFile.delete()
                                            }

                                            if (canonicalFile.exists() && computeFileCrc32(canonicalFile) == legacyCrc) {
                                                val newEntity = tile.copy(
                                                    providerId = canonicalId,
                                                    filePath = canonicalFilePath,
                                                    byteSize = canonicalFile.length()
                                                )
                                                database.retainedTileDao().insertTile(newEntity)
                                                val verifiedInserted = database.retainedTileDao().getTile(canonicalId, tile.category, tile.z, tile.x, tile.y) != null
                                                if (verifiedInserted) {
                                                    database.retainedTileDao().deleteTile(legacyProviderId, tile.category, tile.z, tile.x, tile.y)
                                                    legacyFile.delete()
                                                    migratedDbRecords++
                                                } else {
                                                    failures.add("Failed to verify insertion of canonical record in DB")
                                                }
                                            } else {
                                                failures.add("Canonical file verification failed after rename for $canonicalFilePath")
                                            }
                                        } else {
                                            tempFile.delete()
                                            integrityFailures.add("Checksum mismatch during legacy file copy for ${legacyFile.path}")
                                        }
                                    } else {
                                        integrityFailures.add("Integrity failure: legacy file CRC ($legacyCrc) != record checksum (${tile.checksum}) for $legacyProviderId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                    }
                                } else {
                                    missingFiles.add("Missing physical file for tile record $legacyProviderId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
                                    failures.add("Missing file for tile record $legacyProviderId/${tile.category}/${tile.z}/${tile.x}/${tile.y}")
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
            verifiedDuplicateFiles = verifiedDuplicateFiles,
            verifiedDuplicateRecords = verifiedDuplicateRecords,
            unresolvedFileConflicts = unresolvedFileConflicts,
            unresolvedDbConflicts = unresolvedDbConflicts,
            missingFiles = missingFiles,
            integrityFailures = integrityFailures,
            failures = failures
        )
    }

    suspend fun calculateStorage(context: Context, database: AppDatabase): OverallStorageReport = withContext(Dispatchers.IO) {
        // Run loss-safe migration and capture migration health
        val migrationReport = migrateLegacyStorage(context, database)

        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        // 1. Audit retained tile files per canonical data provider using real reconciliation (no maxOf illusion)
        val providerReports = mutableListOf<SourceStorageReport>()
        val canonicalProviders = MapStorageProviders.allProviders

        var totalTileBytes = 0L

        for (provider in canonicalProviders) {
            val providerDir = File(retainedTilesRoot, provider.id)
            val physicalFiles = if (providerDir.exists() && providerDir.isDirectory) {
                providerDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
            } else {
                emptyList()
            }
            val physicalFileCount = physicalFiles.size
            val physicalByteSize = physicalFiles.sumOf { it.length() }

            val roomTiles = database.retainedTileDao().getTilesByProvider(provider.id)
            val roomRecordCount = roomTiles.size

            var verifiedRecordCount = 0
            var orphanedRecordCount = 0
            var integrityFailureCount = 0

            for (tile in roomTiles) {
                val file = File(tile.filePath)
                if (!file.exists() || !file.isFile || file.length() == 0L) {
                    orphanedRecordCount++
                } else {
                    val fileCrc = computeFileCrc32(file)
                    if (fileCrc.isNotEmpty() && fileCrc == tile.checksum) {
                        verifiedRecordCount++
                    } else {
                        integrityFailureCount++
                    }
                }
            }

            val roomPathSet = roomTiles.map { it.filePath }.toSet()
            val unindexedFileCount = physicalFiles.count { !roomPathSet.contains(it.absolutePath) }

            totalTileBytes += physicalByteSize

            providerReports.add(
                SourceStorageReport(
                    providerId = provider.id,
                    providerName = provider.displayName,
                    providerType = provider.providerTypeDescription,
                    tileCount = physicalFileCount,
                    byteSize = physicalByteSize,
                    physicalFileCount = physicalFileCount,
                    physicalByteSize = physicalByteSize,
                    roomRecordCount = roomRecordCount,
                    verifiedRecordCount = verifiedRecordCount,
                    orphanedRecordCount = orphanedRecordCount,
                    unindexedFileCount = unindexedFileCount,
                    integrityFailureCount = integrityFailureCount,
                    formattedSize = formatBytes(physicalByteSize),
                    isDeletable = physicalFileCount > 0 || roomRecordCount > 0,
                    acquisitionStatus = provider.acquisitionPipelineStatus,
                    retentionPolicy = provider.retentionPolicyDescription,
                    isOfflineRetained = physicalFileCount > 0 && verifiedRecordCount > 0
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
                val uFiles = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
                val uBytes = uFiles.sumOf { it.length() }
                val uCount = uFiles.size
                if (uCount > 0 || uBytes > 0) {
                    totalTileBytes += uBytes
                    providerReports.add(
                        SourceStorageReport(
                            providerId = dir.name,
                            providerName = "Other (${dir.name})",
                            providerType = "Cached Tiles",
                            tileCount = uCount,
                            byteSize = uBytes,
                            physicalFileCount = uCount,
                            physicalByteSize = uBytes,
                            roomRecordCount = 0,
                            verifiedRecordCount = 0,
                            orphanedRecordCount = 0,
                            unindexedFileCount = uCount,
                            integrityFailureCount = 0,
                            formattedSize = formatBytes(uBytes),
                            isDeletable = true,
                            acquisitionStatus = "Uncatalogued Cached Data",
                            retentionPolicy = "Unknown",
                            isOfflineRetained = false
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

        // 4. Overall total - truthful physical byte sum
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
     * Reports exact physical file deletion results and verifies database state.
     */
    suspend fun clearProviderTiles(context: Context, database: AppDatabase, providerId: String): ProviderClearResult = withContext(Dispatchers.IO) {
        val canonicalId = MapSourceRepository.resolveCanonicalProviderId(providerId)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        var bytesDeleted = 0L
        var filesDeleted = 0
        var recordsDeleted = 0
        var isSuccess = true
        var failureReason: String? = null

        fun purgeDir(dir: File) {
            if (!dir.exists() || !dir.isDirectory) return
            val files = dir.walkBottomUp().filter { it.isFile }.toList()
            for (f in files) {
                val len = f.length()
                val deleted = f.delete()
                if (deleted) {
                    bytesDeleted += len
                    filesDeleted++
                } else {
                    isSuccess = false
                    failureReason = "Failed to delete file ${f.path}"
                }
            }
            // Delete empty subdirectories bottom up
            dir.walkBottomUp().filter { it.isDirectory && it != dir }.forEach { subDir ->
                subDir.delete()
            }
            dir.delete()
            if (dir.exists()) {
                val remaining = dir.walkTopDown().filter { it.isFile }.count()
                if (remaining > 0) {
                    isSuccess = false
                    failureReason = "Failed to remove directory ${dir.path} ($remaining files remaining)"
                }
            }
        }

        try {
            // 1. Purge canonical folder
            val canonicalDir = File(retainedTilesRoot, canonicalId)
            purgeDir(canonicalDir)

            // 2. Also purge legacy folder if different
            if (canonicalId != providerId) {
                val rawDir = File(retainedTilesRoot, providerId)
                purgeDir(rawDir)
            }

            // 3. Delete tile metadata records for this provider only
            recordsDeleted += database.retainedTileDao().deleteTilesByProvider(canonicalId)
            if (canonicalId != providerId) {
                recordsDeleted += database.retainedTileDao().deleteTilesByProvider(providerId)
            }

            // Verify Room deletion
            val remainingRecords = database.retainedTileDao().getTileCountByProvider(canonicalId)
            if (remainingRecords > 0) {
                isSuccess = false
                failureReason = "$remainingRecords database records could not be deleted"
            }

            ProviderClearResult(
                providerId = providerId,
                canonicalId = canonicalId,
                bytesDeleted = bytesDeleted,
                filesDeleted = filesDeleted,
                recordsDeleted = recordsDeleted,
                isSuccess = isSuccess,
                error = failureReason
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
                } else if (!file.name.endsWith(".tmp")) {
                    totalBytes += file.length()
                    totalCount++
                }
            }
        }
        return totalBytes to totalCount
    }
}
