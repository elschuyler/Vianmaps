package com.example.data.routing

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

data class GraphTileRef(
    val level: Int,
    val tileId: Long,
    val description: String
)

data class GraphTileCoverage(
    val tileRef: GraphTileRef,
    val isAvailableOffline: Boolean,
    val byteSize: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Manages the offline routing graph tile coverage index independently from
 * map display tiles.
 *
 * Routing graphs (e.g. Valhalla / OSRM hierarchies) do not map 1:1 to XYZ map tiles.
 * Valhalla divides the world into three hierarchical tile grids:
 * - Level 0: 4-degree tiles for major highway networks.
 * - Level 1: 1-degree tiles for arterial roadways.
 * - Level 2: 0.25-degree tiles for local streets and intersections.
 */
class RoutingGraphCoverageIndex {

    // Index of available graph tiles: "level:tileId" -> GraphTileCoverage
    private val coverage = ConcurrentHashMap<String, GraphTileCoverage>()

    companion object {
        fun computeTileId(lat: Double, lng: Double, tileSizeDegrees: Double, level: Int): Long {
            val clampedLat = lat.coerceIn(-90.0, 90.0)
            val clampedLng = lng.coerceIn(-180.0, 180.0)
            val row = floor((clampedLat + 90.0) / tileSizeDegrees).toLong()
            val col = floor((clampedLng + 180.0) / tileSizeDegrees).toLong()
            val colsPerRow = (360.0 / tileSizeDegrees).toLong()
            return (level.toLong() shl 40) or (row * colsPerRow + col)
        }

        fun getRequiredGraphTiles(lat: Double, lng: Double): List<GraphTileRef> {
            val l0 = GraphTileRef(
                level = 0,
                tileId = computeTileId(lat, lng, 4.0, 0),
                description = "Level 0 Highway (4°)"
            )
            val l1 = GraphTileRef(
                level = 1,
                tileId = computeTileId(lat, lng, 1.0, 1),
                description = "Level 1 Arterial (1°)"
            )
            val l2 = GraphTileRef(
                level = 2,
                tileId = computeTileId(lat, lng, 0.25, 2),
                description = "Level 2 Local (0.25°)"
            )
            return listOf(l0, l1, l2)
        }
    }

    fun markTileAvailable(tileRef: GraphTileRef, byteSize: Long = 0L) {
        val key = "${tileRef.level}:${tileRef.tileId}"
        coverage[key] = GraphTileCoverage(
            tileRef = tileRef,
            isAvailableOffline = true,
            byteSize = byteSize
        )
    }

    fun isTileAvailable(tileRef: GraphTileRef): Boolean {
        val key = "${tileRef.level}:${tileRef.tileId}"
        return coverage[key]?.isAvailableOffline == true
    }

    fun isCompleteGraphAvailable(lat: Double, lng: Double): Boolean {
        val required = getRequiredGraphTiles(lat, lng)
        return required.all { isTileAvailable(it) }
    }

    fun getRetainedGraphTileCount(): Int = coverage.size
}
