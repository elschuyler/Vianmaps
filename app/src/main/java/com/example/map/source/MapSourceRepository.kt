package com.example.map.source

/**
 * Encapsulates map style/source information, licensing status, and attribution.
 *
 * NOTE: Mark development styles as temporary. Keep style URLs and attribution isolated
 * here rather than scattered throughout UI code.
 */
data class MapStyleDefinition(
    val id: String,
    val displayName: String,
    val styleUri: String,
    val attribution: String,
    val isTemporaryDevelopmentStyle: Boolean = true,
    val licenseNotes: String
)

object MapSourceRepository {
    // Official MapLibre Demo Tiles style (Demotiles vector format hosted by MapLibre)
    // Permitted for development and prototyping. Attribution: MapLibre contributors & OpenStreetMap.
    val DEMO_TILES = MapStyleDefinition(
        id = "maplibre_demo",
        displayName = "MapLibre Vector (Dev)",
        styleUri = "https://demotiles.maplibre.org/style.json",
        attribution = "© MapLibre contributors © OpenStreetMap contributors",
        isTemporaryDevelopmentStyle = true,
        licenseNotes = "MapLibre Demo Tiles style for development/testing. Do not assume persistent offline redistribution rights."
    )

    // OpenStreetMap standard fallback style (rendered via MapLibre raster specification)
    val OSM_RASTER_DEV = MapStyleDefinition(
        id = "osm_raster",
        displayName = "OSM Standard (Dev)",
        styleUri = "asset://osm_raster_style.json",
        attribution = "© OpenStreetMap contributors (ODbL)",
        isTemporaryDevelopmentStyle = true,
        licenseNotes = "Standard OSM raster tile style definition. Subject to OSM tile usage policies."
    )

    val availableStyles = listOf(DEMO_TILES, OSM_RASTER_DEV)

    val defaultStyle = DEMO_TILES

    fun getStyleById(id: String): MapStyleDefinition {
        return availableStyles.firstOrNull { it.id == id } ?: defaultStyle
    }
}
