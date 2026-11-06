package com.example.map.engine

import android.content.Context
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.map.source.MapStyleDefinition
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Controller and lifecycle manager for the native MapLibre MapView.
 * Provides clean encapsulation away from Compose business logic.
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

    var isNorthLocked: Boolean = false

    fun attachMap(
        map: MapLibreMap,
        styleDef: MapStyleDefinition,
        onStatusChanged: (status: MapEngineStatus, stageDescription: String?, error: String?) -> Unit
    ) {
        this.maplibreMap = map

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
    }

    fun attachMap(map: MapLibreMap, currentStyleUri: String, onStyleLoaded: () -> Unit) {
        this.maplibreMap = map

        map.uiSettings.isLogoEnabled = true
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isRotateGesturesEnabled = !isNorthLocked
        map.uiSettings.isTiltGesturesEnabled = false

        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(initialLat, initialLng))
            .zoom(initialZoom)
            .bearing(if (isNorthLocked) 0.0 else initialBearing)
            .tilt(0.0)
            .build()

        map.setStyle(Style.Builder().fromUri(currentStyleUri)) {
            onStyleLoaded()
        }

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

        // Stage 8: Selected map style ID and style URL
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapEngineController",
            description = "Selected map style ID: ${styleDef.id}, URL: ${styleDef.styleUri} (Provider: ${styleDef.providerName})",
            stage = "STYLE_SELECTION"
        )

        // Stage 9: Style loading started
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
            // Stage 10: Style loaded successfully
            val layerCount = style.layers.size
            val sourceCount = style.sources.size
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "MapEngineController",
                description = "Style loaded successfully (${styleDef.displayName}): $layerCount layers, $sourceCount sources",
                stage = "STYLE_LOADED"
            )
            onStatusChanged?.invoke(
                MapEngineStatus.STYLE_READY,
                "Style loaded: ${styleDef.displayName} ($layerCount layers)",
                null
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
