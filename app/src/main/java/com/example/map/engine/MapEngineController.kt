package com.example.map.engine

import android.content.Context
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
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

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

    val visibleTileCoordinator = VisibleTileCoordinator(context)

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
        maplibreMap?.let { map ->
            val update = CameraPosition.Builder()
                .target(LatLng(lat, lng))
                .zoom(zoom ?: map.cameraPosition.zoom)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(update))
        }
    }
}
