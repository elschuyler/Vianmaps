package com.example.data.osm

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.OsmBuildingRecord
import com.example.data.db.OsmPoiRecord
import com.example.data.db.OsmRegionRecord
import com.example.data.db.OsmRoadRecord
import com.example.data.tile.TileCoord
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

    suspend fun searchPois(query: String, category: String? = null, limit: Int = 20): List<OsmPoiRecord> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (category.isNullOrBlank()) {
            poiDao.searchPois(q, limit)
        } else {
            poiDao.searchPoisByCategory(q, category, limit)
        }
    }

    suspend fun getPoiCount(): Int = withContext(Dispatchers.IO) { poiDao.getPoiCount() }
    suspend fun getBuildingCount(): Int = withContext(Dispatchers.IO) { buildingDao.getBuildingCount() }
    suspend fun getRegionCount(): Int = withContext(Dispatchers.IO) { regionDao.getRegionCount() }
    suspend fun getRoadCount(): Int = withContext(Dispatchers.IO) { roadDao.getRoadCount() }
}
