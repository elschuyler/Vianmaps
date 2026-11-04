package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "map_app_preferences")

data class MapCameraState(
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val zoom: Double = 11.0,
    val bearing: Double = 0.0,
    val isNorthLocked: Boolean = false
)

data class AppUserPreferences(
    val cameraState: MapCameraState = MapCameraState(),
    val isTopPanelCollapsed: Boolean = true,
    val isInternetEnabled: Boolean = true,
    val selectedStyleId: String = "maplibre_demo"
)

class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val LATITUDE = doublePreferencesKey("camera_lat")
        val LONGITUDE = doublePreferencesKey("camera_lng")
        val ZOOM = doublePreferencesKey("camera_zoom")
        val BEARING = doublePreferencesKey("camera_bearing")
        val NORTH_LOCKED = booleanPreferencesKey("camera_north_locked")
        val TOP_PANEL_COLLAPSED = booleanPreferencesKey("top_panel_collapsed")
        val INTERNET_ENABLED = booleanPreferencesKey("pref_internet_enabled")
        val SELECTED_STYLE_ID = stringPreferencesKey("pref_selected_style_id")
    }

    val userPreferencesFlow: Flow<AppUserPreferences> = context.dataStore.data.map { prefs ->
        val lat = prefs[Keys.LATITUDE] ?: 37.7749
        val lng = prefs[Keys.LONGITUDE] ?: -122.4194
        val zoom = prefs[Keys.ZOOM] ?: 11.0
        val bearing = prefs[Keys.BEARING] ?: 0.0
        val northLocked = prefs[Keys.NORTH_LOCKED] ?: false
        val collapsed = prefs[Keys.TOP_PANEL_COLLAPSED] ?: true
        val internet = prefs[Keys.INTERNET_ENABLED] ?: true
        val styleId = prefs[Keys.SELECTED_STYLE_ID] ?: "maplibre_demo"

        AppUserPreferences(
            cameraState = MapCameraState(
                latitude = lat,
                longitude = lng,
                zoom = zoom,
                bearing = bearing,
                isNorthLocked = northLocked
            ),
            isTopPanelCollapsed = collapsed,
            isInternetEnabled = internet,
            selectedStyleId = styleId
        )
    }

    suspend fun saveCameraState(lat: Double, lng: Double, zoom: Double, bearing: Double) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LATITUDE] = lat
            prefs[Keys.LONGITUDE] = lng
            prefs[Keys.ZOOM] = zoom
            prefs[Keys.BEARING] = bearing
        }
    }

    suspend fun saveNorthLocked(locked: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NORTH_LOCKED] = locked
        }
    }

    suspend fun saveTopPanelCollapsed(collapsed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOP_PANEL_COLLAPSED] = collapsed
        }
    }

    suspend fun saveInternetEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.INTERNET_ENABLED] = enabled
        }
    }

    suspend fun saveSelectedStyleId(styleId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_STYLE_ID] = styleId
        }
    }
}
