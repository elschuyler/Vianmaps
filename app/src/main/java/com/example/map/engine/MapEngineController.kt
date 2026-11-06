package com.example.map.engine

import android.content.Context
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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

    fun attachMap(map: MapLibreMap, currentStyleUri: String, onStyleLoaded: () -> Unit) {
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
