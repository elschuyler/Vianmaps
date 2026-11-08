package com.example.data.routing

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Official Valhalla Graph Tile Hierarchy Calculator.
 *
 * Implements the official Valhalla GraphId coordinate hierarchy (valhalla/baldr/tilehierarchy.h):
 * - Level 0: 4.0 degree tiles for transit / major highway network. 90 columns x 45 rows = 4,050 tiles.
 * - Level 1: 1.0 degree tiles for arterial roadway network. 360 columns x 180 rows = 64,800 tiles.
 * - Level 2: 0.25 degree tiles for local streets and intersections. 1,440 columns x 720 rows = 1,036,800 tiles.
 *
 * Provides exact tile index computation and bounding-box intersection calculations.
 */
object ValhallaTileCalculator {

    data class HierarchyLevel(
        val level: Int,
        val tileSizeDegrees: Double,
        val name: String,
        val cols: Int,
        val rows: Int
    )

    val LEVELS = listOf(
        HierarchyLevel(level = 0, tileSizeDegrees = 4.0, name = "highway", cols = 90, rows = 45),
        HierarchyLevel(level = 1, tileSizeDegrees = 1.0, name = "arterial", cols = 360, rows = 180),
        HierarchyLevel(level = 2, tileSizeDegrees = 0.25, name = "local", cols = 1440, rows = 720)
    )

    /**
     * Computes the official Valhalla tile index for a given lat/lng coordinate and hierarchy level.
     */
    fun getTileIndex(lat: Double, lng: Double, level: Int): Long {
        require(level in 0..2) { "Valhalla hierarchy levels are 0, 1, or 2" }
        val h = LEVELS[level]
        val clampedLat = lat.coerceIn(-90.0, 89.999999)
        val clampedLng = lng.coerceIn(-180.0, 179.999999)

        val row = floor((clampedLat + 90.0) / h.tileSizeDegrees).toInt().coerceIn(0, h.rows - 1)
        val col = floor((clampedLng + 180.0) / h.tileSizeDegrees).toInt().coerceIn(0, h.cols - 1)

        return (row.toLong() * h.cols) + col
    }

    /**
     * Formats the Valhalla tile relative filesystem path according to Valhalla's standard:
     * e.g. Level 2, tileIndex 748211 -> "2/748/211.gph"
     */
    fun formatTilePath(level: Int, tileIndex: Long): String {
        val folder = (tileIndex / 1000).toString().padStart(3, '0')
        val file = (tileIndex % 1000).toString().padStart(3, '0')
        return "$level/$folder/$file.gph"
    }

    /**
     * Computes all intersecting Valhalla native graph tile indices for a geographic bounding box.
     */
    fun getIntersectingTileIndices(
        southLat: Double,
        westLng: Double,
        northLat: Double,
        eastLng: Double,
        level: Int
    ): List<Long> {
        val h = LEVELS[level]
        val minRow = floor((southLat.coerceIn(-90.0, 89.999999) + 90.0) / h.tileSizeDegrees).toInt().coerceIn(0, h.rows - 1)
        val maxRow = floor((northLat.coerceIn(-90.0, 89.999999) + 90.0) / h.tileSizeDegrees).toInt().coerceIn(0, h.rows - 1)

        val minCol = floor((westLng.coerceIn(-180.0, 179.999999) + 180.0) / h.tileSizeDegrees).toInt().coerceIn(0, h.cols - 1)
        val maxCol = floor((eastLng.coerceIn(-180.0, 179.999999) + 180.0) / h.tileSizeDegrees).toInt().coerceIn(0, h.cols - 1)

        val results = mutableListOf<Long>()
        if (minCol <= maxCol) {
            for (r in minRow..maxRow) {
                for (c in minCol..maxCol) {
                    results.add((r.toLong() * h.cols) + c)
                }
            }
        } else {
            // Antimeridian crossing: minCol..359 and 0..maxCol
            for (r in minRow..maxRow) {
                for (c in minCol until h.cols) {
                    results.add((r.toLong() * h.cols) + c)
                }
                for (c in 0..maxCol) {
                    results.add((r.toLong() * h.cols) + c)
                }
            }
        }
        return results
    }
}
