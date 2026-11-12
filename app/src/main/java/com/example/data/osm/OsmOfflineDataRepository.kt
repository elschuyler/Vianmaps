package com.example.data.osm

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.CategoryCountTuple
import com.example.data.db.OsmBuildingRecord
import com.example.data.db.OsmPoiRecord
import com.example.data.db.OsmRegionRecord
import com.example.data.db.OsmRoadRecord
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production-ready OSM offline data repository backed by Room Database.
 * Persists canonical OSM features (POIs, buildings, administrative regions, roads)
 * acquired from vector tiles, enforcing strict deduplication across tile boundary buffers.
 * Survives app restarts, force stops, and functions completely offline in airplane mode.
 */
class OsmOfflineDataRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val poiDao = db.osmPoiDao()
    private val buildingDao = db.osmBuildingDao()
    private val regionDao = db.osmRegionDao()
    private val roadDao = db.osmRoadDao()

    /**
     * Ingests POIs for a retained tile, deduplicating by OSM ID in Room.
     * Returns count of inserted features.
     */
    suspend fun savePoiFeatures(features: List<OsmPoiRecord>): Int = withContext(Dispatchers.IO) {
        if (features.isEmpty()) return@withContext 0
        poiDao.insertPois(features)
        DiagnosticRepository.log(
            severity = LogSeverity.DEBUG,
            source = "OsmOfflineDataRepository",
            description = "Ingested and persisted ${features.size} POIs into Room"
        )
        features.size
    }

    suspend fun saveBuildingFeatures(features: List<OsmBuildingRecord>): Int = withContext(Dispatchers.IO) {
        if (features.isEmpty()) return@withContext 0
        buildingDao.insertBuildings(features)
        features.size
    }

    suspend fun saveRegionFeatures(features: List<OsmRegionRecord>): Int = withContext(Dispatchers.IO) {
        if (features.isEmpty()) return@withContext 0
        regionDao.insertRegions(features)
        features.size
    }

    suspend fun saveRoadFeatures(features: List<OsmRoadRecord>): Int = withContext(Dispatchers.IO) {
        if (features.isEmpty()) return@withContext 0
        roadDao.insertRoads(features)
        features.size
    }

    suspend fun ingestDecodedTile(decoded: MvtDecodedTile): Int = withContext(Dispatchers.IO) {
        val p = savePoiFeatures(decoded.pois)
        val b = saveBuildingFeatures(decoded.buildings)
        val reg = saveRegionFeatures(decoded.regions)
        val rd = saveRoadFeatures(decoded.roads)
        p + b + reg + rd
    }

    suspend fun searchPois(query: String, category: String? = null, limit: Int = 30): List<OsmPoiRecord> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val cat = category?.trim()?.takeIf { it.isNotEmpty() }
        val safeLimit = limit.coerceIn(1, 100)

        if (q.isEmpty() && cat == null) {
            return@withContext emptyList()
        }

        if (q.isEmpty() && cat != null) {
            return@withContext poiDao.getPoisByCategoryOnly(cat, safeLimit)
        }

        val escapedQ = escapeLikeWildcards(q)
        val sqlMatches = if (cat == null) {
            poiDao.searchPois(escapedQ, q, safeLimit)
        } else {
            poiDao.searchPoisByCategory(escapedQ, cat, q, safeLimit)
        }

        // SQLite LIKE only folds ASCII case ([A-Za-z]). If query includes non-ASCII Unicode letters
        // (e.g. Turkish İ/ı/ş/ö/ü) and SQL results have headroom, supplement with bounded Unicode matching.
        if (q.any { it.code > 127 } && sqlMatches.size < safeLimit) {
            val candidates = if (cat != null) {
                poiDao.getPoisByCategoryOnly(cat, limit = 500)
            } else {
                poiDao.getAllPois(limit = 500)
            }
            val seenIds = sqlMatches.map { it.osmId }.toHashSet()
            val extraMatches = candidates.filter { poi ->
                poi.osmId !in seenIds && (
                    poi.name.contains(q, ignoreCase = true) ||
                        poi.category.contains(q, ignoreCase = true) ||
                        (poi.subCategory?.contains(q, ignoreCase = true) == true)
                    )
            }
            if (extraMatches.isNotEmpty()) {
                return@withContext (sqlMatches + extraMatches)
                    .sortedWith(
                        compareBy<OsmPoiRecord>(
                            { !it.name.equals(q, ignoreCase = true) },
                            { !it.name.startsWith(q, ignoreCase = true) },
                            { it.name.lowercase() }
                        )
                    )
                    .take(safeLimit)
            }
        }

        sqlMatches
    }

    suspend fun getCategoryCounts(limit: Int = 25): List<CategoryCountTuple> = withContext(Dispatchers.IO) {
        poiDao.getCategoryCounts(limit.coerceIn(1, 50))
    }

    suspend fun getSamplePois(limit: Int = 10): List<OsmPoiRecord> = withContext(Dispatchers.IO) {
        poiDao.getAllPois(limit.coerceIn(1, 50))
    }

    suspend fun getPoiCount(): Int = withContext(Dispatchers.IO) { poiDao.getPoiCount() }
    suspend fun getBuildingCount(): Int = withContext(Dispatchers.IO) { buildingDao.getBuildingCount() }
    suspend fun getRegionCount(): Int = withContext(Dispatchers.IO) { regionDao.getRegionCount() }
    suspend fun getRoadCount(): Int = withContext(Dispatchers.IO) { roadDao.getRoadCount() }

    private fun escapeLikeWildcards(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
