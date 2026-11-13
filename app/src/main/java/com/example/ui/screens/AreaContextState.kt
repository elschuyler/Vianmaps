package com.example.ui.screens

import com.example.data.area.AreaDetailsResult
import com.example.data.area.ContextCoordinateMatch
import com.example.data.db.SavedAreaEntity
import java.util.Locale
import kotlin.math.abs

/**
 * UI state for the anchored single-tap Area Details popup.
 * Tracks both the selected geographic coordinate and its live projected screen position
 * so the card remains visually anchored to the geographical point during map panning/zooming.
 */
data class AreaDetailsUiState(
    val latitude: Double,
    val longitude: Double,
    val screenX: Float? = null,
    val screenY: Float? = null,
    val isExpanded: Boolean = true,
    val details: AreaDetailsResult = AreaDetailsResult(
        latitude = latitude,
        longitude = longitude
    ),
    val selectedNearbyIdsForSave: Set<String> = emptySet(),
    val userNotesDraft: String = "",
    val customNameDraft: String = ""
)

/**
 * UI state for the anchored long-press Map Context Menu.
 */
data class MapContextMenuUiState(
    val latitude: Double,
    val longitude: Double,
    val screenX: Float? = null,
    val screenY: Float? = null,
    val isResolvingMatches: Boolean = true,
    val matches: ContextCoordinateMatch = ContextCoordinateMatch()
)

/**
 * Edit dialog state for renaming/annotating/tagging a persistently saved offline area.
 */
data class SavedAreaEditorState(
    val areaId: String,
    val name: String,
    val category: String,
    val addressLabel: String,
    val userNotes: String,
    val tagsText: String,
    val errorMessage: String? = null
)

/**
 * Honest notice modal for context actions whose dedicated subsystem belongs to a future milestone
 * (Memory Palace Room, Coordinate Note, Coordinate To-do, Geodesic Measure).
 */
data class PlannedActionNoticeState(
    val title: String,
    val featureGroup: String,
    val latitude: Double,
    val longitude: Double,
    val summary: String,
    val plannedBehavior: String
)

object AreaCoordinateFormatter {
    fun formatDecimalDegrees(lat: Double, lng: Double): String {
        return String.format(Locale.US, "%.6f, %.6f", lat, lng)
    }

    fun formatDms(lat: Double, lng: Double): String {
        return "${toDmsComponent(lat, isLat = true)}  ${toDmsComponent(lng, isLat = false)}"
    }

    private fun toDmsComponent(decimal: Double, isLat: Boolean): String {
        val hemi = when {
            isLat && decimal >= 0.0 -> "N"
            isLat -> "S"
            decimal >= 0.0 -> "E"
            else -> "W"
        }
        val absVal = abs(decimal)
        val deg = absVal.toInt()
        val remMin = (absVal - deg) * 60.0
        val min = remMin.toInt()
        val sec = (remMin - min) * 60.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", deg, min, sec, hemi)
    }

    fun formatTimestampRelativeOrShort(epochMs: Long): String {
        if (epochMs <= 0L) return "Unknown"
        val formatter = java.text.SimpleDateFormat("MMM d, HH:mm", Locale.US)
        return formatter.format(java.util.Date(epochMs))
    }

    fun suggestedLabelFromMatch(matches: ContextCoordinateMatch): String {
        return matches.savedArea?.name
            ?: matches.placemark?.name
            ?: matches.nearbyOsmPoi?.name
            ?: ""
    }

    fun suggestedAddressFromSavedArea(area: SavedAreaEntity?): String {
        if (area == null) return ""
        return area.addressLabel.ifBlank {
            listOf(area.locality, area.region, area.country).filter { it.isNotBlank() }.joinToString(", ")
        }
    }
}
