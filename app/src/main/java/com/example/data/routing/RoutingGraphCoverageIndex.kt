package com.example.data.routing

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.RoutingGraphTileRecord
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GraphTileRef(
    val level: Int,
    val tileIndex: Long,
    val description: String,
    val relativePath: String = ValhallaTileCalculator.formatTilePath(level, tileIndex)
)

enum class RoutingGraphStatus {
    AVAILABLE,
    MISSING_COVERAGE,
    NOT_CONFIGURED
}

data class GraphCoverageReport(
    val status: RoutingGraphStatus,
    val totalRequiredTiles: Int,
    val availableTiles: Int,
    val missingTiles: List<GraphTileRef>,
    val details: String
)

/**
 * Manages the offline routing graph tile coverage index independently from
 * map display tiles.
 *
 * Honors the strict project constraint:
 * A routable graph tile must contain the binary graph data (.gph) needed by the local routing engine.
 * Never treats visualization MVT tiles as routable binary graph tiles.
 * If no compatible binary graph source is configured or downloaded, reports NOT_CONFIGURED or MISSING_COVERAGE.
 */
class RoutingGraphCoverageIndex(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val graphDao = db.routingGraphTileDao()

    private val baseGraphDir = File(context.filesDir, "routing_graph")

    /**
     * Calculates the required Valhalla graph tiles for an area and evaluates readiness.
     */
    suspend fun evaluateCoverage(
        southLat: Double,
        westLng: Double,
        northLat: Double,
        eastLng: Double
    ): GraphCoverageReport = withContext(Dispatchers.IO) {
        val required = mutableListOf<GraphTileRef>()

        // Check each hierarchy level (L0: highway, L1: arterial, L2: local)
        for (lvl in 0..2) {
            val indices = ValhallaTileCalculator.getIntersectingTileIndices(southLat, westLng, northLat, eastLng, lvl)
            indices.forEach { idx ->
                required.add(
                    GraphTileRef(
                        level = lvl,
                        tileIndex = idx,
                        description = "Level $lvl (${ValhallaTileCalculator.LEVELS[lvl].name})"
                    )
                )
            }
        }

        var availableCount = 0
        val missing = mutableListOf<GraphTileRef>()

        for (tileRef in required) {
            val file = File(baseGraphDir, tileRef.relativePath)
            if (file.exists() && file.length() > 0) {
                availableCount++
            } else {
                missing.add(tileRef)
            }
        }

        val status = when {
            required.isEmpty() -> RoutingGraphStatus.NOT_CONFIGURED
            availableCount == required.size -> RoutingGraphStatus.AVAILABLE
            availableCount == 0 -> RoutingGraphStatus.NOT_CONFIGURED
            else -> RoutingGraphStatus.MISSING_COVERAGE
        }

        val details = when (status) {
            RoutingGraphStatus.AVAILABLE -> "All $availableCount required binary graph tiles present on disk."
            RoutingGraphStatus.MISSING_COVERAGE -> "Partial graph: $availableCount / ${required.size} tiles available. Navigation restricted."
            RoutingGraphStatus.NOT_CONFIGURED -> "Binary graph tiles (.gph) not configured for this footprint. Offline routing unavailable."
        }

        GraphCoverageReport(
            status = status,
            totalRequiredTiles = required.size,
            availableTiles = availableCount,
            missingTiles = missing,
            details = details
        )
    }

    suspend fun getDownloadedTileCount(): Int = withContext(Dispatchers.IO) {
        graphDao.getDownloadedTileCount()
    }
}
