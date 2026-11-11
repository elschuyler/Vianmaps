package com.example.map.source

import com.example.data.tile.TileCategory

/**
 * Encapsulates map style and source information, licensing status, and attribution.
 *
 * Supports both pure vector styles and genuine composite Hybrid styles
 * (satellite raster imagery rendered beneath OSM vector roads, boundaries, and labels).
 */
data class MapStyleDefinition(
    val id: String,
    val displayName: String,
    val styleUri: String,
    val attribution: String,
    val providerName: String,
    val isTemporaryDevelopmentStyle: Boolean = false,
    val licenseNotes: String,
    val isCompositeHybrid: Boolean = false,
    val rasterSourceUri: String? = null,
    val rasterMinZoom: Float = 0f,
    val rasterMaxZoom: Float = 19f,
    val offlineRetentionPermitted: Boolean = true,
    val vectorRetentionPermitted: Boolean = true,
    val rasterRetentionPermitted: Boolean = false,
    val vectorTileUrlTemplate: String = "https://tiles.openfreemap.org/planet/20261101_080013_pt/{z}/{x}/{y}.pbf",
    val storageProviderId: String = MapStorageProviders.OPEN_FREE_MAP.id,
    val rasterStorageProviderId: String? = null
)

/**
 * Definition of an underlying data provider that provides tiles to map styles.
 * Visual styles (e.g. Liberty, Bright, Positron) share underlying providers (e.g. OpenFreeMap).
 */
data class StorageProviderDefinition(
    val id: String,
    val displayName: String,
    val category: TileCategory,
    val providerTypeDescription: String,
    val defaultRetentionPermitted: Boolean,
    val retentionPolicyDescription: String = if (defaultRetentionPermitted) "Permanent offline retention permitted" else "Online viewing / transient cache only",
    val acquisitionPipelineStatus: String = "Online Rendering Only"
)

object MapStorageProviders {
    val OPEN_FREE_MAP = StorageProviderDefinition(
        id = "openfreemap",
        displayName = "OpenFreeMap",
        category = TileCategory.OSM_VECTOR,
        providerTypeDescription = "OpenStreetMap Vector Tiles",
        defaultRetentionPermitted = true,
        retentionPolicyDescription = "Permitted (ODbL Vector Data)",
        acquisitionPipelineStatus = "Active (Visible Viewport Retention)"
    )

    val ESRI_SATELLITE = StorageProviderDefinition(
        id = "esri_satellite",
        displayName = "Esri World Imagery",
        category = TileCategory.SATELLITE_RASTER,
        providerTypeDescription = "Satellite Raster Imagery",
        defaultRetentionPermitted = false,
        retentionPolicyDescription = "Restricted (Online Browsing Only)",
        acquisitionPipelineStatus = "Online Rendering Only (Offline Retention Restricted by Terms of Use)"
    )

    val OPEN_AERIAL_MAP = StorageProviderDefinition(
        id = "openaerialmap",
        displayName = "OpenAerialMap",
        category = TileCategory.SATELLITE_RASTER,
        providerTypeDescription = "Contributed Aerial Imagery",
        defaultRetentionPermitted = true,
        retentionPolicyDescription = "Permitted (CC-BY 4.0)",
        acquisitionPipelineStatus = "Online Rendering Only (Dedicated Raster Retention Pipeline Not Implemented)"
    )

    val MAPLIBRE_DEMO = StorageProviderDefinition(
        id = "maplibre_demo",
        displayName = "MapLibre Demo Tiles",
        category = TileCategory.OSM_VECTOR,
        providerTypeDescription = "Diagnostic Vector Tiles",
        defaultRetentionPermitted = false,
        retentionPolicyDescription = "Restricted (Diagnostic Only)",
        acquisitionPipelineStatus = "Diagnostic Only (Transient)"
    )

    val OSM_STANDARD = StorageProviderDefinition(
        id = "osm_standard",
        displayName = "OpenStreetMap Standard",
        category = TileCategory.SATELLITE_RASTER,
        providerTypeDescription = "Standard OSM Raster Tiles",
        defaultRetentionPermitted = false,
        retentionPolicyDescription = "Restricted (Tile Usage Policy)",
        acquisitionPipelineStatus = "Online Fallback Only"
    )

    val allProviders = listOf(
        OPEN_FREE_MAP,
        ESRI_SATELLITE,
        OPEN_AERIAL_MAP,
        MAPLIBRE_DEMO,
        OSM_STANDARD
    )

    fun getProviderById(id: String): StorageProviderDefinition? {
        val canonical = MapSourceRepository.resolveCanonicalProviderId(id)
        return allProviders.firstOrNull { it.id == canonical }
    }
}

object MapSourceRepository {

    /**
     * Resolves raw, legacy, or style identifiers to the canonical storage provider ID.
     */
    fun resolveCanonicalProviderId(rawId: String): String {
        val normalized = rawId.trim().lowercase().replace(" ", "_")
        return when (normalized) {
            "openfreemap",
            "openfreemap_liberty",
            "openfreemap_bright",
            "openfreemap_positron",
            "hybrid_(satellite_+_osm)",
            "hybrid_satellite_osm",
            "osm_vector",
            "openmaptiles" -> MapStorageProviders.OPEN_FREE_MAP.id

            "esri",
            "esri_world_imagery",
            "esri_satellite" -> MapStorageProviders.ESRI_SATELLITE.id

            "openaerialmap",
            "open_aerial_map",
            "hybrid_oam_osm" -> MapStorageProviders.OPEN_AERIAL_MAP.id

            "maplibre",
            "maplibre_demo",
            "demotiles" -> MapStorageProviders.MAPLIBRE_DEMO.id

            "openstreetmap",
            "osm_raster",
            "osm_carto",
            "osm_standard" -> MapStorageProviders.OSM_STANDARD.id

            else -> normalized
        }
    }

    // Default Hybrid: Satellite raster imagery + OpenFreeMap OSM vector overlay
    // Uses dependable global Esri World Imagery beneath OSM vector roads, highways, boundaries, and labels.
    val HYBRID_SATELLITE_OSM = MapStyleDefinition(
        id = "hybrid_satellite_osm",
        displayName = "Hybrid (Satellite + OSM)",
        styleUri = "https://tiles.openfreemap.org/styles/liberty",
        attribution = "© Esri, Maxar, Earthstar Geographics © OpenFreeMap © OpenStreetMap contributors",
        providerName = "Hybrid (Satellite + OSM)",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "Composite style: Satellite raster imagery rendered beneath OSM vector features. Online viewing permitted; permanent offline retention restricted by imagery provider license.",
        isCompositeHybrid = true,
        rasterSourceUri = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        rasterMinZoom = 0f,
        rasterMaxZoom = 19f,
        offlineRetentionPermitted = false, // Raster imagery is online/cache only; OSM vector data is retained
        vectorRetentionPermitted = true,
        rasterRetentionPermitted = false,
        storageProviderId = MapStorageProviders.OPEN_FREE_MAP.id,
        rasterStorageProviderId = MapStorageProviders.ESRI_SATELLITE.id
    )

    // OpenAerialMap Hybrid candidate (Zoom 14+ imagery, CC-BY 4.0)
    val HYBRID_OAM_OSM = MapStyleDefinition(
        id = "hybrid_oam_osm",
        displayName = "Hybrid (OpenAerialMap + OSM)",
        styleUri = "https://tiles.openfreemap.org/styles/liberty",
        attribution = "© OpenAerialMap / Open Imagery Network contributors (CC-BY 4.0) © OpenFreeMap © OpenStreetMap contributors",
        providerName = "OpenAerialMap",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "Contributed aerial imagery under CC-BY 4.0. Patchy coverage; zoom 14+ serves visual imagery.",
        isCompositeHybrid = true,
        rasterSourceUri = "https://global.imagery.hotosm.org/{z}/{x}/{y}.png",
        rasterMinZoom = 14f,
        rasterMaxZoom = 19f,
        offlineRetentionPermitted = true,
        vectorRetentionPermitted = true,
        rasterRetentionPermitted = true,
        storageProviderId = MapStorageProviders.OPEN_FREE_MAP.id,
        rasterStorageProviderId = MapStorageProviders.OPEN_AERIAL_MAP.id
    )

    // OpenFreeMap Liberty Vector Style
    val OPEN_FREE_MAP_LIBERTY = MapStyleDefinition(
        id = "openfreemap_liberty",
        displayName = "OpenFreeMap Liberty (Vector)",
        styleUri = "https://tiles.openfreemap.org/styles/liberty",
        attribution = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
        providerName = "OpenFreeMap",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "OpenFreeMap free vector tiles. Vector data retained for offline viewing.",
        isCompositeHybrid = false,
        offlineRetentionPermitted = true,
        vectorRetentionPermitted = true,
        rasterRetentionPermitted = false,
        storageProviderId = MapStorageProviders.OPEN_FREE_MAP.id,
        rasterStorageProviderId = null
    )

    // OpenFreeMap Bright Vector Style
    val OPEN_FREE_MAP_BRIGHT = MapStyleDefinition(
        id = "openfreemap_bright",
        displayName = "OpenFreeMap Bright (Vector)",
        styleUri = "https://tiles.openfreemap.org/styles/bright",
        attribution = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
        providerName = "OpenFreeMap",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "OpenFreeMap bright style. OpenStreetMap data under ODbL.",
        isCompositeHybrid = false,
        offlineRetentionPermitted = true,
        vectorRetentionPermitted = true,
        rasterRetentionPermitted = false,
        storageProviderId = MapStorageProviders.OPEN_FREE_MAP.id,
        rasterStorageProviderId = null
    )

    // OpenFreeMap Positron (Subtle, muted vector style)
    val OPEN_FREE_MAP_POSITRON = MapStyleDefinition(
        id = "openfreemap_positron",
        displayName = "OpenFreeMap Positron (Vector)",
        styleUri = "https://tiles.openfreemap.org/styles/positron",
        attribution = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
        providerName = "OpenFreeMap",
        isTemporaryDevelopmentStyle = false,
        licenseNotes = "OpenFreeMap positron muted vector style.",
        isCompositeHybrid = false,
        offlineRetentionPermitted = true,
        vectorRetentionPermitted = true,
        rasterRetentionPermitted = false,
        storageProviderId = MapStorageProviders.OPEN_FREE_MAP.id,
        rasterStorageProviderId = null
    )

    // Temporary diagnostic vector style for engine verification
    val MAPLIBRE_DEMO_TILES = MapStyleDefinition(
        id = "maplibre_demo",
        displayName = "MapLibre Demo (Diagnostic)",
        styleUri = "https://demotiles.maplibre.org/style.json",
        attribution = "© MapLibre © OpenStreetMap contributors",
        providerName = "MapLibre",
        isTemporaryDevelopmentStyle = true,
        licenseNotes = "Temporary lightweight diagnostic style used for engine rendering verification.",
        isCompositeHybrid = false,
        offlineRetentionPermitted = false,
        vectorRetentionPermitted = false,
        rasterRetentionPermitted = false,
        storageProviderId = MapStorageProviders.MAPLIBRE_DEMO.id,
        rasterStorageProviderId = null
    )

    // OpenStreetMap standard fallback style
    val OSM_RASTER_DEV = MapStyleDefinition(
        id = "osm_raster",
        displayName = "OSM Standard (Raster)",
        styleUri = "asset://osm_raster_style.json",
        attribution = "© OpenStreetMap contributors (ODbL)",
        providerName = "OpenStreetMap",
        isTemporaryDevelopmentStyle = true,
        licenseNotes = "Standard OSM raster tile fallback. Subject to OSM tile usage policies.",
        isCompositeHybrid = false,
        offlineRetentionPermitted = false,
        vectorRetentionPermitted = false,
        rasterRetentionPermitted = false,
        storageProviderId = MapStorageProviders.OSM_STANDARD.id,
        rasterStorageProviderId = null
    )

    val availableStyles = listOf(
        HYBRID_SATELLITE_OSM,
        HYBRID_OAM_OSM,
        OPEN_FREE_MAP_LIBERTY,
        OPEN_FREE_MAP_BRIGHT,
        OPEN_FREE_MAP_POSITRON,
        MAPLIBRE_DEMO_TILES,
        OSM_RASTER_DEV
    )

    // Intended product default is the Hybrid (Satellite + OSM) map
    val defaultStyle = HYBRID_SATELLITE_OSM

    fun getStyleById(id: String): MapStyleDefinition {
        return availableStyles.firstOrNull { it.id == id } ?: defaultStyle
    }
}
