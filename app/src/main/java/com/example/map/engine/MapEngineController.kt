package com.example.map.engine

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import com.example.data.db.OsmPoiRecord
import com.example.data.db.UserPlaceEntity
import com.example.data.places.RoutePoint
import com.example.data.places.SavedRouteModel
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.map.acquisition.VisibleTileCoordinator
import com.example.map.composition.MapComposition
import com.example.map.source.MapStyleDefinition
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
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

    var currentPlacemarks: List<UserPlaceEntity> = emptyList()
        private set

    var selectedPlacemarkId: String? = null
        private set

    var currentSavedRoutes: List<SavedRouteModel> = emptyList()
        private set

    var selectedRouteId: String? = null
        private set

    var currentDraftRoutePoints: List<RoutePoint> = emptyList()
        private set

    var currentDraftWaypointPoints: List<RoutePoint> = emptyList()
        private set

    var currentAreaPin: RoutePoint? = null
        private set

    private var lastLongPressElapsedMs: Long = 0L

    var onMapTap: ((lat: Double, lng: Double) -> Boolean)? = null
    var onEmptyMapSingleTap: ((lat: Double, lng: Double) -> Unit)? = null
    var onMapLongPress: ((lat: Double, lng: Double) -> Unit)? = null
    var onPlacemarkTapped: ((placeId: String) -> Unit)? = null
    var onRouteTapped: ((routeId: String) -> Unit)? = null
    var onHighlightedPoiTapped: (() -> Unit)? = null

    val visibleTileCoordinator = VisibleTileCoordinator(context)

    companion object {
        const val HIGHLIGHT_SOURCE_ID = "vian-selected-highlight-source"
        const val HIGHLIGHT_HALO_LAYER_ID = "vian-selected-highlight-halo"
        const val HIGHLIGHT_CORE_LAYER_ID = "vian-selected-highlight-core"

        const val USER_PLACEMARKS_SOURCE_ID = "vian-user-placemarks-source"
        const val USER_PLACEMARKS_LAYER_ID = "vian-user-placemarks-layer"
        const val USER_SELECTED_PLACEMARK_SOURCE_ID = "vian-user-selected-placemark-source"
        const val USER_SELECTED_PLACEMARK_HALO_ID = "vian-user-selected-placemark-halo"

        const val USER_ROUTES_SOURCE_ID = "vian-user-routes-source"
        const val USER_ROUTES_CASING_LAYER_ID = "vian-user-routes-casing"
        const val USER_ROUTES_LINE_LAYER_ID = "vian-user-routes-line"
        const val USER_WAYPOINTS_SOURCE_ID = "vian-user-waypoints-source"
        const val USER_WAYPOINTS_LAYER_ID = "vian-user-waypoints-layer"

        const val DRAFT_ROUTE_SOURCE_ID = "vian-user-route-draft-source"
        const val DRAFT_ROUTE_LINE_LAYER_ID = "vian-user-route-draft-line"
        const val DRAFT_ROUTE_POINTS_SOURCE_ID = "vian-user-route-draft-points-source"
        const val DRAFT_ROUTE_POINTS_LAYER_ID = "vian-user-route-draft-points"

        const val AREA_PIN_SOURCE_ID = "vian-area-pin-source"
        const val AREA_PIN_HALO_LAYER_ID = "vian-area-pin-halo"
        const val AREA_PIN_CORE_LAYER_ID = "vian-area-pin-core"
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

        map.addOnMapClickListener { latLng ->
            var hitPlaceId: String? = null
            var hitRouteId: String? = null
            var hitPoi = false
            try {
                val screenPoint = map.projection.toScreenLocation(latLng)
                val hitBox = RectF(
                    screenPoint.x - 24f,
                    screenPoint.y - 24f,
                    screenPoint.x + 24f,
                    screenPoint.y + 24f
                )
                hitPlaceId = map.queryRenderedFeatures(hitBox, USER_PLACEMARKS_LAYER_ID)
                    .firstOrNull { it.hasProperty("placeId") }
                    ?.getStringProperty("placeId")
                    ?.takeIf { it.isNotBlank() }

                if (hitPlaceId == null) {
                    hitRouteId = map.queryRenderedFeatures(hitBox, USER_ROUTES_LINE_LAYER_ID)
                        .firstOrNull { it.hasProperty("routeId") }
                        ?.getStringProperty("routeId")
                        ?.takeIf { it.isNotBlank() }
                }

                if (hitPlaceId == null && hitRouteId == null && currentHighlightedPoi != null && isHighlightVisible) {
                    hitPoi = map.queryRenderedFeatures(hitBox, HIGHLIGHT_CORE_LAYER_ID).isNotEmpty()
                }
            } catch (_: Throwable) {
            }
            dispatchMapTap(
                lat = latLng.latitude,
                lng = latLng.longitude,
                hitPlacemarkId = hitPlaceId,
                hitRouteId = hitRouteId,
                hitHighlightedPoi = hitPoi
            )
        }

        map.addOnMapLongClickListener { latLng ->
            dispatchMapLongPress(latLng.latitude, latLng.longitude)
            true
        }
    }

    fun dispatchMapTap(
        lat: Double,
        lng: Double,
        hitPlacemarkId: String? = null,
        hitRouteId: String? = null,
        hitHighlightedPoi: Boolean = false,
        nowMs: Long = android.os.SystemClock.elapsedRealtime()
    ): Boolean {
        if ((nowMs - lastLongPressElapsedMs) in 0L..350L) {
            return true
        }
        if (onMapTap?.invoke(lat, lng) == true) {
            return true
        }
        if (!hitPlacemarkId.isNullOrBlank()) {
            onPlacemarkTapped?.invoke(hitPlacemarkId)
            return true
        }
        if (!hitRouteId.isNullOrBlank()) {
            onRouteTapped?.invoke(hitRouteId)
            return true
        }
        if (hitHighlightedPoi) {
            onHighlightedPoiTapped?.invoke()
            return true
        }
        onEmptyMapSingleTap?.invoke(lat, lng)
        return true
    }

    fun dispatchMapLongPress(
        lat: Double,
        lng: Double,
        nowMs: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        lastLongPressElapsedMs = nowMs
        onMapLongPress?.invoke(lat, lng)
    }

    fun projectToScreen(lat: Double, lng: Double): android.graphics.PointF? {
        val map = maplibreMap ?: return null
        return try {
            map.projection.toScreenLocation(LatLng(lat, lng))
        } catch (_: Throwable) {
            null
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
            applyAllVianOverlaysToStyle(activeStyle)
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
            // Restore all user-owned overlays (routes, placemarks, draft, selected POI) cleanly
            applyAllVianOverlaysToStyle(style)

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
            applyAllVianOverlaysToStyle(activeStyle)
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
                if (id.startsWith("vian-")) continue
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

    private fun applyAllVianOverlaysToStyle(style: Style) {
        applySavedRoutesToStyle(style)
        applyUserPlacemarksToStyle(style)
        applyRouteDraftToStyle(style)
        applyAreaPinToStyle(style)
        applyPoiHighlightToStyle(style)
    }

    fun setAreaSelectionPin(lat: Double, lng: Double) {
        val pt = RoutePoint(lat, lng)
        currentAreaPin = if (pt.isValid()) pt else null
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                applyAreaPinToStyle(style)
            }
        }
    }

    fun clearAreaSelectionPin() {
        currentAreaPin = null
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                applyAreaPinToStyle(style)
            }
        }
    }

    fun updateUserPlacemarks(placemarks: List<UserPlaceEntity>, selectedId: String?) {
        currentPlacemarks = placemarks
        selectedPlacemarkId = selectedId
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                applyUserPlacemarksToStyle(style)
            }
        }
    }

    fun updateSavedRoutes(routes: List<SavedRouteModel>, selectedId: String?) {
        currentSavedRoutes = routes
        selectedRouteId = selectedId
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                applySavedRoutesToStyle(style)
            }
        }
    }

    fun updateRouteDraftOverlay(points: List<RoutePoint>, waypointPoints: List<RoutePoint> = emptyList()) {
        currentDraftRoutePoints = points
        currentDraftWaypointPoints = waypointPoints
        maplibreMap?.style?.let { style ->
            if (style.isFullyLoaded) {
                applyRouteDraftToStyle(style)
            }
        }
    }

    fun focusOnPlacemark(place: UserPlaceEntity, targetZoom: Double = 16.0) {
        lastCameraFocusTarget = Pair(place.latitude, place.longitude)
        maplibreMap?.let { map ->
            val zoomToUse = maxOf(map.cameraPosition.zoom, targetZoom)
            val update = CameraPosition.Builder()
                .target(LatLng(place.latitude, place.longitude))
                .zoom(zoomToUse)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(update))
        }
    }

    fun focusOnRoute(route: SavedRouteModel) {
        val validPoints = route.points.filter { it.isValid() }
        if (validPoints.isEmpty()) return
        if (validPoints.size == 1) {
            val pt = validPoints.first()
            centerOnLocation(pt.latitude, pt.longitude, 15.0)
            return
        }

        val minLat = validPoints.minOf { it.latitude }
        val maxLat = validPoints.maxOf { it.latitude }
        val minLng = validPoints.minOf { it.longitude }
        val maxLng = validPoints.maxOf { it.longitude }
        val centerLat = (minLat + maxLat) / 2.0
        val centerLng = (minLng + maxLng) / 2.0
        lastCameraFocusTarget = Pair(centerLat, centerLng)

        maplibreMap?.let { map ->
            if ((maxLat - minLat) < 1e-5 && (maxLng - minLng) < 1e-5) {
                val update = CameraPosition.Builder()
                    .target(LatLng(centerLat, centerLng))
                    .zoom(maxOf(map.cameraPosition.zoom, 15.5))
                    .build()
                map.animateCamera(CameraUpdateFactory.newCameraPosition(update))
            } else {
                try {
                    val builder = LatLngBounds.Builder()
                    validPoints.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
                    val bounds = builder.build()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                } catch (_: Throwable) {
                    val update = CameraPosition.Builder()
                        .target(LatLng(centerLat, centerLng))
                        .zoom(14.0)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(update))
                }
            }
        }
    }

    private fun applyUserPlacemarksToStyle(style: Style) {
        try {
            val allFeatures = currentPlacemarks
                .filter { RoutePoint(it.latitude, it.longitude).isValid() }
                .map { place ->
                    Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).apply {
                        addStringProperty("placeId", place.id)
                        addStringProperty("name", place.name)
                    }
                }
            val collection = FeatureCollection.fromFeatures(allFeatures)
            val existingSource = style.getSourceAs<GeoJsonSource>(USER_PLACEMARKS_SOURCE_ID)
            if (existingSource != null) {
                existingSource.setGeoJson(collection)
            } else {
                style.addSource(GeoJsonSource(USER_PLACEMARKS_SOURCE_ID, collection))
            }

            if (style.getLayer(USER_PLACEMARKS_LAYER_ID) == null) {
                val layer = CircleLayer(USER_PLACEMARKS_LAYER_ID, USER_PLACEMARKS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor("#D97706"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")
                )
                style.addLayer(layer)
            }

            val selectedPlace = currentPlacemarks.firstOrNull { it.id == selectedPlacemarkId }
            val selectedFeatures = if (selectedPlace != null && RoutePoint(selectedPlace.latitude, selectedPlace.longitude).isValid()) {
                listOf(Feature.fromGeometry(Point.fromLngLat(selectedPlace.longitude, selectedPlace.latitude)))
            } else {
                emptyList()
            }
            val selectedCollection = FeatureCollection.fromFeatures(selectedFeatures)
            val selSource = style.getSourceAs<GeoJsonSource>(USER_SELECTED_PLACEMARK_SOURCE_ID)
            if (selSource != null) {
                selSource.setGeoJson(selectedCollection)
            } else {
                style.addSource(GeoJsonSource(USER_SELECTED_PLACEMARK_SOURCE_ID, selectedCollection))
            }

            if (style.getLayer(USER_SELECTED_PLACEMARK_HALO_ID) == null) {
                val haloLayer = CircleLayer(USER_SELECTED_PLACEMARK_HALO_ID, USER_SELECTED_PLACEMARK_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(18f),
                    PropertyFactory.circleColor("#40F59E0B"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor("#D97706")
                )
                style.addLayerBelow(haloLayer, USER_PLACEMARKS_LAYER_ID)
            }
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MapEngineController",
                description = "Failed applying user placemark overlay: ${e.message}"
            )
        }
    }

    private fun applySavedRoutesToStyle(style: Style) {
        try {
            val routeFeatures = currentSavedRoutes.mapNotNull { model ->
                val validPts = model.points.filter { it.isValid() }
                if (validPts.size < 2) return@mapNotNull null
                val lngLats = validPts.map { Point.fromLngLat(it.longitude, it.latitude) }
                Feature.fromGeometry(LineString.fromLngLats(lngLats)).apply {
                    addStringProperty("routeId", model.route.id)
                    addBooleanProperty("selected", model.route.id == selectedRouteId)
                }
            }
            val routeCollection = FeatureCollection.fromFeatures(routeFeatures)
            val routeSource = style.getSourceAs<GeoJsonSource>(USER_ROUTES_SOURCE_ID)
            if (routeSource != null) {
                routeSource.setGeoJson(routeCollection)
            } else {
                style.addSource(GeoJsonSource(USER_ROUTES_SOURCE_ID, routeCollection))
            }

            if (style.getLayer(USER_ROUTES_CASING_LAYER_ID) == null) {
                val casing = LineLayer(USER_ROUTES_CASING_LAYER_ID, USER_ROUTES_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor("#FFFFFF"),
                    PropertyFactory.lineWidth(6.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineOpacity(0.9f)
                )
                style.addLayer(casing)
            }
            if (style.getLayer(USER_ROUTES_LINE_LAYER_ID) == null) {
                val line = LineLayer(USER_ROUTES_LINE_LAYER_ID, USER_ROUTES_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor("#059669"),
                    PropertyFactory.lineWidth(4.0f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
                style.addLayerAbove(line, USER_ROUTES_CASING_LAYER_ID)
            }

            val waypointFeatures = currentSavedRoutes.flatMap { model ->
                model.waypoints
                    .filter { RoutePoint(it.latitude, it.longitude).isValid() }
                    .map { wp ->
                        Feature.fromGeometry(Point.fromLngLat(wp.longitude, wp.latitude)).apply {
                            addStringProperty("routeId", model.route.id)
                            addStringProperty("waypointId", wp.id)
                        }
                    }
            }
            val wpCollection = FeatureCollection.fromFeatures(waypointFeatures)
            val wpSource = style.getSourceAs<GeoJsonSource>(USER_WAYPOINTS_SOURCE_ID)
            if (wpSource != null) {
                wpSource.setGeoJson(wpCollection)
            } else {
                style.addSource(GeoJsonSource(USER_WAYPOINTS_SOURCE_ID, wpCollection))
            }

            if (style.getLayer(USER_WAYPOINTS_LAYER_ID) == null) {
                val wpLayer = CircleLayer(USER_WAYPOINTS_LAYER_ID, USER_WAYPOINTS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(6.0f),
                    PropertyFactory.circleColor("#047857"),
                    PropertyFactory.circleStrokeWidth(2.0f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")
                )
                style.addLayerAbove(wpLayer, USER_ROUTES_LINE_LAYER_ID)
            }
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MapEngineController",
                description = "Failed applying saved routes overlay: ${e.message}"
            )
        }
    }

    private fun applyRouteDraftToStyle(style: Style) {
        try {
            val validPts = currentDraftRoutePoints.filter { it.isValid() }
            val lineFeatures = if (validPts.size >= 2) {
                listOf(Feature.fromGeometry(LineString.fromLngLats(validPts.map { Point.fromLngLat(it.longitude, it.latitude) })))
            } else {
                emptyList()
            }
            val lineCollection = FeatureCollection.fromFeatures(lineFeatures)
            val lineSource = style.getSourceAs<GeoJsonSource>(DRAFT_ROUTE_SOURCE_ID)
            if (lineSource != null) {
                lineSource.setGeoJson(lineCollection)
            } else {
                style.addSource(GeoJsonSource(DRAFT_ROUTE_SOURCE_ID, lineCollection))
            }
            if (style.getLayer(DRAFT_ROUTE_LINE_LAYER_ID) == null) {
                val draftLine = LineLayer(DRAFT_ROUTE_LINE_LAYER_ID, DRAFT_ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor("#E11D48"),
                    PropertyFactory.lineWidth(4.5f),
                    PropertyFactory.lineDasharray(arrayOf(2.0f, 1.5f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
                style.addLayer(draftLine)
            }

            val pointFeatures = validPts.map { pt ->
                Feature.fromGeometry(Point.fromLngLat(pt.longitude, pt.latitude))
            }
            val ptCollection = FeatureCollection.fromFeatures(pointFeatures)
            val ptSource = style.getSourceAs<GeoJsonSource>(DRAFT_ROUTE_POINTS_SOURCE_ID)
            if (ptSource != null) {
                ptSource.setGeoJson(ptCollection)
            } else {
                style.addSource(GeoJsonSource(DRAFT_ROUTE_POINTS_SOURCE_ID, ptCollection))
            }
            if (style.getLayer(DRAFT_ROUTE_POINTS_LAYER_ID) == null) {
                val draftPoints = CircleLayer(DRAFT_ROUTE_POINTS_LAYER_ID, DRAFT_ROUTE_POINTS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(6.5f),
                    PropertyFactory.circleColor("#E11D48"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")
                )
                style.addLayerAbove(draftPoints, DRAFT_ROUTE_LINE_LAYER_ID)
            }
        } catch (e: Throwable) {
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "MapEngineController",
                description = "Failed applying draft route overlay: ${e.message}"
            )
        }
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

    private fun applyAreaPinToStyle(style: Style) {
        try {
            val pin = currentAreaPin
            val features = if (pin != null && pin.isValid()) {
                listOf(Feature.fromGeometry(Point.fromLngLat(pin.longitude, pin.latitude)))
            } else {
                emptyList()
            }
            val collection = FeatureCollection.fromFeatures(features)
            val source = style.getSourceAs<GeoJsonSource>(AREA_PIN_SOURCE_ID)
            if (source != null) {
                source.setGeoJson(collection)
            } else {
                style.addSource(GeoJsonSource(AREA_PIN_SOURCE_ID, collection))
            }
            if (style.getLayer(AREA_PIN_HALO_LAYER_ID) == null) {
                val halo = CircleLayer(AREA_PIN_HALO_LAYER_ID, AREA_PIN_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(18f),
                    PropertyFactory.circleColor("#336366F1"),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#4F46E5")
                )
                style.addLayer(halo)
            }
            if (style.getLayer(AREA_PIN_CORE_LAYER_ID) == null) {
                val core = CircleLayer(AREA_PIN_CORE_LAYER_ID, AREA_PIN_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(7.5f),
                    PropertyFactory.circleColor("#4F46E5"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")
                )
                style.addLayerAbove(core, AREA_PIN_HALO_LAYER_ID)
            }
        } catch (_: Throwable) {
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
