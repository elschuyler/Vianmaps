package com.example.map.composition

import com.example.map.source.MapSourceRepository
import com.example.map.source.MapStyleDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a saved map composition combining a base map style with
 * independently managed overlay layers (roads, labels, POIs, boundaries, buildings).
 */
data class MapComposition(
    val id: String,
    val name: String,
    val description: String,
    val baseStyleId: String,
    val isPinnedToQuickChoice: Boolean = true,
    val isStarter: Boolean = false,
    val showRoads: Boolean = true,
    val showLabels: Boolean = true,
    val showPois: Boolean = true,
    val showBoundaries: Boolean = true,
    val showBuildings: Boolean = true,
    val satelliteOpacity: Float = 1.0f
) {
    val baseStyle: MapStyleDefinition
        get() = MapSourceRepository.getStyleById(baseStyleId)

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("baseStyleId", baseStyleId)
            put("isPinnedToQuickChoice", isPinnedToQuickChoice)
            put("isStarter", isStarter)
            put("showRoads", showRoads)
            put("showLabels", showLabels)
            put("showPois", showPois)
            put("showBoundaries", showBoundaries)
            put("showBuildings", showBuildings)
            put("satelliteOpacity", satelliteOpacity.toDouble())
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MapComposition {
            return MapComposition(
                id = json.optString("id", "comp_${System.currentTimeMillis()}"),
                name = json.optString("name", "Custom Map"),
                description = json.optString("description", ""),
                baseStyleId = json.optString("baseStyleId", MapSourceRepository.defaultStyle.id),
                isPinnedToQuickChoice = json.optBoolean("isPinnedToQuickChoice", true),
                isStarter = json.optBoolean("isStarter", false),
                showRoads = json.optBoolean("showRoads", true),
                showLabels = json.optBoolean("showLabels", true),
                showPois = json.optBoolean("showPois", true),
                showBoundaries = json.optBoolean("showBoundaries", true),
                showBuildings = json.optBoolean("showBuildings", true),
                satelliteOpacity = json.optDouble("satelliteOpacity", 1.0).toFloat()
            )
        }

        fun listToJsonString(list: List<MapComposition>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJsonString(jsonString: String?): List<MapComposition> {
            if (jsonString.isNullOrEmpty()) return MapCompositionDefaults.starterCompositions
            return try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<MapComposition>()
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }
                if (list.isEmpty()) MapCompositionDefaults.starterCompositions else list
            } catch (_: Throwable) {
                MapCompositionDefaults.starterCompositions
            }
        }
    }
}

/**
 * Sensible starter compositions built from genuinely operational map sources.
 */
object MapCompositionDefaults {

    val SATELLITE_HYBRID = MapComposition(
        id = "comp_satellite_hybrid",
        name = "Satellite Hybrid",
        description = "Global satellite imagery with OpenStreetMap vector roads, labels & POIs",
        baseStyleId = "hybrid_satellite_osm",
        isPinnedToQuickChoice = true,
        isStarter = true,
        showRoads = true,
        showLabels = true,
        showPois = true,
        showBoundaries = true,
        showBuildings = true
    )

    val EVERYDAY_STREET = MapComposition(
        id = "comp_everyday_street",
        name = "Everyday Street",
        description = "OpenFreeMap Liberty vector base with full roads, buildings & POIs",
        baseStyleId = "openfreemap_liberty",
        isPinnedToQuickChoice = true,
        isStarter = true,
        showRoads = true,
        showLabels = true,
        showPois = true,
        showBoundaries = true,
        showBuildings = true
    )

    val EXPLORE_BOUNDARIES = MapComposition(
        id = "comp_explore_boundaries",
        name = "Explore & Boundaries",
        description = "Satellite imagery highlighting administrative boundaries, water & landmarks",
        baseStyleId = "hybrid_satellite_osm",
        isPinnedToQuickChoice = true,
        isStarter = true,
        showRoads = false,
        showLabels = true,
        showPois = true,
        showBoundaries = true,
        showBuildings = false
    )

    val HIGH_CONTRAST_NAV = MapComposition(
        id = "comp_high_contrast_nav",
        name = "High-Contrast Navigation",
        description = "OpenFreeMap Bright style optimized for street grid legibility",
        baseStyleId = "openfreemap_bright",
        isPinnedToQuickChoice = true,
        isStarter = true,
        showRoads = true,
        showLabels = true,
        showPois = true,
        showBoundaries = false,
        showBuildings = true
    )

    val MUTED_POSITRON = MapComposition(
        id = "comp_muted_positron",
        name = "Muted Positron",
        description = "Subtle grayscale vector map designed for overlay notes & paths",
        baseStyleId = "openfreemap_positron",
        isPinnedToQuickChoice = false,
        isStarter = true,
        showRoads = true,
        showLabels = true,
        showPois = false,
        showBoundaries = true,
        showBuildings = false
    )

    val starterCompositions: List<MapComposition> = listOf(
        SATELLITE_HYBRID,
        EVERYDAY_STREET,
        EXPLORE_BOUNDARIES,
        HIGH_CONTRAST_NAV,
        MUTED_POSITRON
    )

    val defaultComposition = SATELLITE_HYBRID
}
