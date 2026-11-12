package com.example.map.engine

import android.content.Context
import com.example.data.db.OsmPoiRecord
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.map.acquisition.VisibleTileCoordinator
import com.example.map.composition.MapComposition
import com.example.map.source.MapStyleDefinition
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Controller and lifecycle manager for the native MapLibre MapView.
 * Provides clean encapsulation away from Compose business logic.
 * Manages hybrid style composition and triggers visible-tile acquisition.
 */
class MapEngineController(
    private val context: Context,
    val initialLat: Double,
    val initialLng: Double,
    val initialZoom: Double,
    val initialBearing: Double,
    private val onCameraMoved: (lat: Double, lng: Double, zoom: Double, bearing: Double) -> Unit
) {
    var maplibreMap: MapLibreMap? = null
        private set

    var currentStyleDefinition: MapStyleDefinition? = null
        private set

    var currentComposition: MapComposition? = null
        private set

    var isNorthLocked: Boolean = false

    var currentHighlightedPoi: OsmPoiRecord? = null
        private set

    var isHighlightVisible: Boolean = true
        private set

    var lastCameraFocusTarget: Pair<Double, Double>? = null
        private set

    val visibleTileCoordinator = VisibleTileCoordinator(context)

    companion object {
        const val HIGHLIGHT_SOURCE_ID = "vian-selected-highlight-source"
        const val HIGHLIGHT_HALO_LAYER_ID = "vian-selected-highlight-halo"
        const val HIGHLIGHT_CORE_LAYER_ID = "vian-selected-highlight-core"
    }

    fun attachMap(
        map: MapLibreMap,
        styleDef: MapStyleDefinition,
        onStatusChanged: (status: MapEngineStatus, stageDescription: String?, error: String?) -> Unit
    ) {
        this.maplibreMap = map
        this.currentStyleDefinition = styleDef

        map.uiSettings.isLogoEnabled = true
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isCompassEnabled = false // Custom compass bottom navigation control is used
        map.uiSettings.isRotateGesturesEnabled = !isNorthLocked
        map.uiSettings.isTiltGesturesEnabled = false // 2D flat mode

        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(initialLat, initialLng))
            .zoom(initialZoom)
            .bearing(if (isNorthLocked) 0.0 else initialBearing)
            .tilt(0.0)
            .build()

        loadStyle(styleDef, onStatusChanged)

        map.addOnCameraMoveListener {
            val position = map.cameraPosition
            val target = position.target
            if (target != null) {
                onCameraMoved(
                    target.latitude,
                    target.longitude,
                    position.zoom,
                    position.bearing
                )
            }
        }

        map.addOnCameraIdleListener {
            currentStyleDefinition?.let { curStyle ->
                visibleTileCoordinator.onCameraSettled(map, curStyle)
            }
        }
    }

    fun loadStyle(
        styleDef: MapStyleDefinition,
        onStatusChanged: ((status: MapEngineStatus, stageDescription: String?, error: String?) -> Unit)? = null
    ) {
        val map = maplibreMap ?: run {
            onStatusChanged?.invoke(
                MapEngineStatus.WAITING_FOR_MAP_VIEW,
                "Map instance not attached yet",
                null
            )
            return
        }

        val activeStyle = map.style
        if (activeStyle != null && activeStyle.isFullyLoaded && currentStyleDefinition?.id == styleDef.id) {
            // Style is already fully loaded and active. Avoid redundant reloads and shader recompilation.
            currentComposition?.let { comp ->
                applyCompositionOverlays(activeStyle, comp)
            }
            applyPoiHighlightToStyle(activeStyle)
            onStatusChanged?.invoke(
                MapEngineStatus.STYLE_READY,
                "Style loaded: ${styleDef.displayName} (${activeStyle.layers.size} layers)",
                null
            )
            visibleTileCoordinator.onCameraSettled(map, styleDef)
            return
        }

        this.currentStyleDefinition = styleDef

        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapEngineController",
            description = "Selected map style ID: ${styleDef.id}, URL: ${styleDef.styleUri} (Provider: ${styleDef.providerName})",
            stage = "STYLE_SELECTION"
        )

        onStatusChanged?.invoke(
            MapEngineStatus.LOADING_STYLE,
            "Loading style: ${styleDef.displayName}",
            null
        )
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapEngineController",
            description = "Style loading started: ${styleDef.styleUri}",
            stage = "STYLE_LOADING_STARTED"
        )

        map.setStyle(Style.Builder().fromUri(styleDef.styleUri)) { style ->
            // If composite hybrid style, inject satellite raster layer beneath vector overlays
            if (styleDef.isCompositeHybrid && !styleDef.rasterSourceUri.isNullOrEmpty()) {
                applyHybridRasterComposition(style, styleDef)
            }

            val layerCount = style.layers.size
            val sourceCount = style.sources.size
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MapEngineController",
                description = "Style loaded successfully (${styleDef.displayName}): $layerCount layers, $sourceCount sources",
                stage = "STYLE_LOADED"
            )
            // Apply overlay visibility if an active composition is set
            currentComposition?.let { comp ->
                applyCompositionOverlays(style, comp)
            }
            // Restore selected POI highlight marker without duplicating sources or layers
            applyPoiHighlightToStyle(style)

            onStatusChanged?.invoke(
                MapEngineStatus.STYLE_READY,
                "Style loaded: ${styleDef.displayName} ($layerCount layers)",
                null
            )

            // Trigger initial visible tile acquisition
            visibleTileCoordinator.onCameraSettled(map, styleDef)
        }
    }

    /**
     * Applies a saved map composition. If the base style matches the currently loaded style,
     * overlay layer visibilities and opacity are toggled instantly on the active style
     * without triggering an expensive style reload.
     */
    fun applyComposition(
        composition: MapComposition,
        onStatusChanged: ((status: MapEngineStatus, stageDescription: String?, error: String?) -> Unit)? = null
    ) {
        this.currentComposition = composition
        val map = maplibreMap ?: run {
            onStatusChanged?.invoke(
                MapEngineStatus.WAITING_FOR_MAP_VIEW,
                "Map instance not attached yet",
                null
            )
            return
        }

        val activeStyle = map.style
        if (activeStyle != null && currentStyleDefinition?.id == composition.baseStyleId) {
            applyCompositionOverlays(activeStyle, composition)
            applyPoiHighlightToStyle(activeStyle)
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MapEngineController",
                description = "Applied composition '${composition.name}' overlay parameters directly to active style",
                stage = "COMPOSITION_APPLIED"
            )
            onStatusChanged?.invoke(
                MapEngineStatus.STYLE_READY,
                "Applied composition: ${composition.name}",
                null
            )
        } else {
            loadStyle(composition.baseStyle, onStatusChanged)
        }
    }

    private fun applyCompositionOverlays(style: Style, composition: MapComposition) {
        try {
            // Satellite raster opacity
            style.getLayer("satellite-raster-layer")?.setProperties(
                PropertyFactory.rasterOpacity(composition.satelliteOpacity)
            )

            for (layer in style.layers) {
                val id = layer.id.lowercase()
                if (id.startsWith("vian-selected-highlight-")) continue
                when {
                    id.contains("poi") -> {
                        layer.setProperties(
                            PropertyFactory.visibility(if (composition.showPois) Property.VISIBLE else Property.NONE)
                        )
                    }
                    id.contains("label") || id.contains("place_") || id.contains("shield") -> {
                        layer.setProperties(
                            PropertyFactory.visibility(if (composition.showLabels) Property.VISIBLE else Property.NONE)
                        )
                    }
                    id.contains("boundary") || id.contains("admin") -> {
                        layer.setProperties(
                            PropertyFactory.visibility(if (composition.showBoundaries) Property.VISIBLE else Property.NONE)
                        )
                    }
                    id.contains("building") -> {
                        layer.setProperties(
                            PropertyFactory.visibility(if (composition.showBuildings) Property.VISIBLE else Property.NONE)
                        )
                    }
                    id.contains("road") || id.contains("tunnel") || id.contains("bridge") ||
                    id.contains("transportation") || id.contains("highway") || id.contains("motorway") ||
                    id.contains("path") || id.contains("ferry") || id.contains("railway") -> {
                        layer.setProperties(
                            PropertyFactory.visibility(if (composition.showRoads) Property.VISIBLE else Property.NONE)
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MapEngineController",
                description = "Failed to apply layer overlay visibility: ${e.message}"
            )
        }
    }

    private fun applyHybridRasterComposition(style: Style, styleDef: MapStyleDefinition) {
        try {
            val sourceId = "satellite-raster-source"
            val layerId = "satellite-raster-layer"

            if (style.getSource(sourceId) == null && styleDef.rasterSourceUri != null) {
                val tileSet = TileSet("2.2.0", styleDef.rasterSourceUri).apply {
                    minZoom = styleDef.rasterMinZoom
                    maxZoom = styleDef.rasterMaxZoom
                    attribution = styleDef.attribution
                }
                val rasterSource = RasterSource(sourceId, tileSet, 256)
                style.addSource(rasterSource)
            }

            if (style.getLayer(layerId) == null && style.getSource(sourceId) != null) {
                val rasterLayer = RasterLayer(layerId, sourceId).apply {
                    minZoom = styleDef.rasterMinZoom
                    maxZoom = styleDef.rasterMaxZoom
                }

                // Place above background / natural_earth, beneath roads and labels
                val naturalEarth = style.getLayer("natural_earth")
                val background = style.getLayer("background")
                if (naturalEarth != null) {
                    style.addLayerAbove(rasterLayer, "natural_earth")
                } else if (background != null) {
                    style.addLayerAbove(rasterLayer, "background")
                } else {
                    style.addLayerAt(rasterLayer, 0)
                }
            }

            // Hide opaque landcover/landuse solid polygon fill layers so real terrain shows through
            val fillLayersToHide = listOf(
                "park",
                "landuse_residential",
                "landcover_wood",
                "landcover_grass",
                "landcover_ice",
                "landcover_wetland",
                "landuse_pitch",
                "landuse_track",
                "landuse_cemetery",
                "landuse_hospital",
                "landuse_school",
                "landcover_sand",
                "aeroway_fill"
            )
            for (lId in fillLayersToHide) {
                style.getLayer(lId)?.setProperties(
                    PropertyFactory.visibility(Property.NONE)
                )
            }

            // Adjust water fill opacity to 0.35 so ocean/lake tint is preserved over satellite texture
            style.getLayer("water")?.setProperties(
                PropertyFactory.fillOpacity(0.35f)
            )

            // Adjust building fill opacity to 0.25 so satellite building roofs remain visible
            style.getLayer("building")?.setProperties(
                PropertyFactory.fillOpacity(0.25f)
            )

            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MapEngineController",
                description = "Hybrid composition successfully applied: Satellite imagery rendered beneath OSM vector roads and labels",
                stage = "HYBRID_COMPOSED"
            )
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MapEngineController",
                description = "Non-fatal hybrid composition note: ${e.message}. Preserving clean vector layers."
            )
        }
    }

    fun retryOrRefresh(
        styleDef: MapStyleDefinition,
        onStatusChanged: (status: MapEngineStatus, stageDescription: String?, error: String?) -> Unit
    ) {
        val map = maplibreMap
        if (map == null) {
            onStatusChanged(
                MapEngineStatus.WAITING_FOR_MAP_VIEW,
                "Cannot refresh: MapView is not ready",
                "MapView null reference"
            )
            return
        }

        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapEngineController",
            description = "Initiating map retry/refresh for style ${styleDef.displayName}",
            stage = "RETRY"
        )
        loadStyle(styleDef, onStatusChanged)
    }

    fun zoomIn() {
        maplibreMap?.let { map ->
            map.animateCamera(CameraUpdateFactory.zoomIn())
        }
    }

    fun zoomOut() {
        maplibreMap?.let { map ->
            map.animateCamera(CameraUpdateFactory.zoomOut())
        }
    }

    fun setNorthLock(locked: Boolean) {
        this.isNorthLocked = locked
        maplibreMap?.let { map ->
            map.uiSettings.isRotateGesturesEnabled = !locked
            if (locked) {
                resetNorth()
            }
        }
    }

    fun resetNorth() {
        maplibreMap?.let { map ->
            val current = map.cameraPosition
            val update = CameraPosition.Builder(current)
                .bearing(0.0)
                .tilt(0.0)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(update))
        }
    }

    fun setStyle(styleUri: String, onLoaded: () -> Unit = {}) {
        maplibreMap?.setStyle(Style.Builder().fromUri(styleUri)) {
            onLoaded()
        }
    }

    fun refreshVisibleMap() {
        maplibreMap?.let { map ->
            val currentStyle = map.style?.uri
            if (!currentStyle.isNullOrEmpty()) {
                map.setStyle(Style.Builder().fromUri(currentStyle))
            }
        }
    }

    fun centerOnLocation(lat: Double, lng: Double, zoom: Double? = null) {
        lastCameraFocusTarget = Pair(lat, lng)
        maplibreMap?.let { map ->
            val update = CameraPosition.Builder()
                .target(LatLng(lat, lng))
                .zoom(zoom ?: map.cameraPosition.zoom)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(update))
        }
    }

    fun highlightPoi(poi: OsmPoiRecord, moveCamera: Boolean = true, targetZoom: Double = 16.0) {
        currentHighlightedPoi = poi
        isHighlightVisible = true
        if (moveCamera) {
            lastCameraFocusTarget = Pair(poi.latitude, poi.longitude)
            maplibreMap?.let { map ->
                val zoomToUse = maxOf(map.cameraPosition.zoom, targetZoom)
                val update = CameraPosition.Builder()
                    .target(LatLng(poi.latitude, poi.longitude))
                    .zoom(zoomToUse)
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(update))
            }
        }
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                applyPoiHighlightToStyle(style)
            }
        }
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapEngineController",
            description = "Highlighted POI '${poi.name}' (${poi.category}) at ${poi.latitude}, ${poi.longitude}",
            stage = "POI_HIGHLIGHTED"
        )
    }

    fun setHighlightVisible(visible: Boolean) {
        isHighlightVisible = visible
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                applyPoiHighlightToStyle(style)
            }
        }
    }

    fun syncHighlightedPoiState(poi: OsmPoiRecord?, visible: Boolean) {
        currentHighlightedPoi = poi
        isHighlightVisible = visible
        if (poi != null) {
            maplibreMap?.style?.let { style ->
                if (style.isFullyLoaded) {
                    applyPoiHighlightToStyle(style)
                }
            }
        } else {
            clearPoiHighlight()
        }
    }

    fun clearPoiHighlight() {
        currentHighlightedPoi = null
        lastCameraFocusTarget = null
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                removeHighlightFromStyle(style)
            }
        }
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapEngineController",
            description = "Cleared selected POI highlight from map",
            stage = "POI_HIGHLIGHT_CLEARED"
        )
    }

    private fun applyPoiHighlightToStyle(style: Style) {
        val poi = currentHighlightedPoi
        if (poi == null) {
            removeHighlightFromStyle(style)
            return
        }
        try {
            val feature = Feature.fromGeometry(Point.fromLngLat(poi.longitude, poi.latitude))
            val collection = FeatureCollection.fromFeature(feature)

            val existingSource = style.getSourceAs<GeoJsonSource>(HIGHLIGHT_SOURCE_ID)
            if (existingSource != null) {
                existingSource.setGeoJson(collection)
            } else {
                style.addSource(GeoJsonSource(HIGHLIGHT_SOURCE_ID, collection))
            }

            val visibilityValue = if (isHighlightVisible) Property.VISIBLE else Property.NONE

            val existingHalo = style.getLayer(HIGHLIGHT_HALO_LAYER_ID)
            if (existingHalo != null) {
                existingHalo.setProperties(PropertyFactory.visibility(visibilityValue))
            } else {
                val haloLayer = CircleLayer(HIGHLIGHT_HALO_LAYER_ID, HIGHLIGHT_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(20f),
                    PropertyFactory.circleColor("#380284C7"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor("#0284C7"),
                    PropertyFactory.visibility(visibilityValue)
                )
                style.addLayer(haloLayer)
            }

            val existingCore = style.getLayer(HIGHLIGHT_CORE_LAYER_ID)
            if (existingCore != null) {
                existingCore.setProperties(PropertyFactory.visibility(visibilityValue))
            } else {
                val coreLayer = CircleLayer(HIGHLIGHT_CORE_LAYER_ID, HIGHLIGHT_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor("#0284C7"),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.visibility(visibilityValue)
                )
                style.addLayerAbove(coreLayer, HIGHLIGHT_HALO_LAYER_ID)
            }
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MapEngineController",
                description = "Failed applying POI highlight layer: ${e.message}"
            )
        }
    }

    private fun removeHighlightFromStyle(style: Style) {
        try {
            if (style.getLayer(HIGHLIGHT_CORE_LAYER_ID) != null) {
                style.removeLayer(HIGHLIGHT_CORE_LAYER_ID)
            }
            if (style.getLayer(HIGHLIGHT_HALO_LAYER_ID) != null) {
                style.removeLayer(HIGHLIGHT_HALO_LAYER_ID)
            }
            if (style.getSource(HIGHLIGHT_SOURCE_ID) != null) {
                style.removeSource(HIGHLIGHT_SOURCE_ID)
            }
        } catch (_: Throwable) {}
    }
}
