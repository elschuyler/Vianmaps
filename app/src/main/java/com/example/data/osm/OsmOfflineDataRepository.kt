package com.example.data.osm

import com.example.data.tile.TileCoord
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import java.util.concurrent.ConcurrentHashMap

data class OsmPoiEntity(
    val osmId: Long,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val tags: Map<String, String> = emptyMap(),
    val sourceTile: TileCoord
)

data class OsmBuildingEntity(
    val osmId: Long,
    val heightMeters: Float = 0f,
    val minHeightMeters: Float = 0f,
    val buildingType: String = "yes",
    val sourceTile: TileCoord
)

data class OsmRegionEntity(
    val osmId: Long,
    val name: String,
    val adminLevel: Int = 8,
    val sourceTile: TileCoord
)

data class OsmRoadEntity(
    val osmId: Long,
    val name: String? = null,
    val highwayClass: String,
    val isOneway: Boolean = false,
    val sourceTile: TileCoord
)

/**
 * Repository for storing, indexing, and deduplicating canonical OSM features
 * acquired alongside retained map tiles.
 *
 * Implements strict feature deduplication by stable OSM feature ID so that
 * vector features clipped or duplicated across adjacent tile boundaries are
 * stored once as canonical geographic records.
 */
class OsmOfflineDataRepository {

    // Thread-safe in-memory stores indexed by canonical OSM ID
    private val pois = ConcurrentHashMap<Long, OsmPoiEntity>()
    private val buildings = ConcurrentHashMap<Long, OsmBuildingEntity>()
    private val regions = ConcurrentHashMap<Long, OsmRegionEntity>()
    private val roads = ConcurrentHashMap<Long, OsmRoadEntity>()

    /**
     * Ingests POIs for a retained tile, deduplicating by OSM ID.
     * Returns count of newly inserted features.
     */
    fun savePoiFeatures(features: List<OsmPoiEntity>): Int {
        var newCount = 0
        features.forEach { poi ->
            if (pois.putIfAbsent(poi.osmId, poi) == null) {
                newCount++
            }
        }
        if (newCount > 0) {
            DiagnosticRepository.log(
                severity = LogSeverity.DEBUG,
                source = "OsmOfflineDataRepository",
                description = "Ingested $newCount new POIs (Total canonical POIs: ${pois.size})"
            )
        }
        return newCount
    }

    fun saveBuildingFeatures(features: List<OsmBuildingEntity>): Int {
        var newCount = 0
        features.forEach { building ->
            if (buildings.putIfAbsent(building.osmId, building) == null) {
                newCount++
            }
        }
        return newCount
    }

    fun saveRegionFeatures(features: List<OsmRegionEntity>): Int {
        var newCount = 0
        features.forEach { region ->
            if (regions.putIfAbsent(region.osmId, region) == null) {
                newCount++
            }
        }
        return newCount
    }

    fun saveRoadFeatures(features: List<OsmRoadEntity>): Int {
        var newCount = 0
        features.forEach { road ->
            if (roads.putIfAbsent(road.osmId, road) == null) {
                newCount++
            }
        }
        return newCount
    }

    fun searchPois(query: String, category: String? = null, limit: Int = 20): List<OsmPoiEntity> {
        val q = query.trim().lowercase()
        return pois.values.asSequence()
            .filter { poi ->
                (category == null || poi.category.equals(category, ignoreCase = true)) &&
                (poi.name.lowercase().contains(q) || poi.category.lowercase().contains(q))
            }
            .take(limit)
            .toList()
    }

    fun getPoiCount(): Int = pois.size
    fun getBuildingCount(): Int = buildings.size
    fun getRegionCount(): Int = regions.size
    fun getRoadCount(): Int = roads.size
}
