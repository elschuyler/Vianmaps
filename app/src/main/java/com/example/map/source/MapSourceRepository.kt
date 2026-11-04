package com.example.map.source

/**
 * Encapsulates map style and source information, licensing status, and attribution.
 *
 * NOTE: Provider URLs and attribution are cleanly decoupled here to support future
 * migration to self-hosted Protomaps / PMTiles archives without altering UI code.
 */
data class MapStyleDefinition(
    val id: String,
    val displayName: String,
    val styleUri: String,
    val attribution: String,
    val providerName: String,
    val isTemporaryDevelopmentStyle: Boolean = false,
    val licenseNotes: String
)

object MapSourceRepository {
    // OpenFreeMap Liberty Vector Style
    // Features full OSM vector dataset: highways, local streets, road names, water, land,
    // building footprints, and points of interest (POIs).
    // Powered by OpenMapTiles vector schema, hosted by OpenFreeMap.
    val OPEN_FREE_MAP_LIBERTY = MapStyleDefinition(
        id = "openfreemap_liberty",
        displayName = "OpenFreeMap Liberty (Vector)",
        styleUri = "https://tiles.openfreemap.org/styles/liberty",
        attribution = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
        providerName = "OpenFreeMap",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "OpenFreeMap free vector tiles for development and testing. Self-hosting via PMTiles recommended for offline/bulk production."
    )

    // OpenFreeMap Bright Vector Style (High contrast clean streets and labels)
    val OPEN_FREE_MAP_BRIGHT = MapStyleDefinition(
        id = "openfreemap_bright",
        displayName = "OpenFreeMap Bright (Vector)",
        styleUri = "https://tiles.openfreemap.org/styles/bright",
        attribution = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
        providerName = "OpenFreeMap",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "OpenFreeMap bright style. OpenStreetMap data under ODbL."
    )

    // OpenFreeMap Positron (Subtle, muted vector style ideal for data overlay)
    val OPEN_FREE_MAP_POSITRON = MapStyleDefinition(
        id = "openfreemap_positron",
        displayName = "OpenFreeMap Positron (Vector)",
        styleUri = "https://tiles.openfreemap.org/styles/positron",
        attribution = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
        providerName = "OpenFreeMap",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "OpenFreeMap positron muted vector style."
    )

    // OpenStreetMap standard fallback style (rendered via MapLibre raster specification)
    val OSM_RASTER_DEV = MapStyleDefinition(
        id = "osm_raster",
        displayName = "OSM Standard (Raster)",
        styleUri = "asset://osm_raster_style.json",
        attribution = "© OpenStreetMap contributors (ODbL)",
        providerName = "OpenStreetMap",
        isTemporaryDevelopmentStyle = true,
        licenseNotes = "Standard OSM raster tile fallback. Subject to OSM tile usage policies."
    )

    val availableStyles = listOf(
        OPEN_FREE_MAP_LIBERTY,
        OPEN_FREE_MAP_BRIGHT,
        OPEN_FREE_MAP_POSITRON,
        OSM_RASTER_DEV
    )

    // OpenFreeMap Liberty is the default vector style
    val defaultStyle = OPEN_FREE_MAP_LIBERTY

    fun getStyleById(id: String): MapStyleDefinition {
        return availableStyles.firstOrNull { it.id == id } ?: defaultStyle
    }
}
