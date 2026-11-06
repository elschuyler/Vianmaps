package com.example.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.MapCameraState
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.diagnostics.NetworkDiagnosticsMonitor
import com.example.map.source.MapSourceRepository
import com.example.map.source.MapStyleDefinition
import com.example.network.AppNetworkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MapUiState(
    val camera: MapCameraState = MapCameraState(),
    val isTopPanelCollapsed: Boolean = true,
    val isInternetEnabled: Boolean = true,
    val currentStyle: MapStyleDefinition = MapSourceRepository.defaultStyle,
    val isLocationActive: Boolean = false,
    val locationStatusText: String = "Location Off (Tap 2nd right control to request)",
    val isMapReady: Boolean = false,
    val mapLoadingStage: String = "INITIALIZING",
    val lastMapError: String? = null,
    val activeModal: ActiveModal? = null,
    val quickSettingsOpen: Boolean = false
)

sealed interface ActiveModal {
    data object Settings : ActiveModal
    data object LogKeeper : ActiveModal
    data object MapsPopup : ActiveModal
    data object PlacesPopup : ActiveModal
    data object MemoryPalaceWorkspace : ActiveModal
    data object ToolsPopup : ActiveModal
    data class PlaceholderTool(val name: String, val description: String) : ActiveModal
}

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = AppPreferencesRepository(application)
    val networkMonitor = NetworkDiagnosticsMonitor(application)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapViewModel",
            description = "MapViewModel initialized"
        )

        viewModelScope.launch {
            val savedPrefs = preferencesRepository.userPreferencesFlow.first()
            val style = MapSourceRepository.getStyleById(savedPrefs.selectedStyleId)

            AppNetworkPolicy.setInternetEnabled(savedPrefs.isInternetEnabled)

            _uiState.value = _uiState.value.copy(
                camera = savedPrefs.cameraState,
                isTopPanelCollapsed = savedPrefs.isTopPanelCollapsed,
                isInternetEnabled = savedPrefs.isInternetEnabled,
                currentStyle = style
            )
        }
    }

    fun onMapStageChanged(stage: String, isReady: Boolean? = null, error: String? = null) {
        _uiState.value = _uiState.value.copy(
            mapLoadingStage = stage,
            isMapReady = isReady ?: _uiState.value.isMapReady,
            lastMapError = error ?: _uiState.value.lastMapError
        )
        val severity = if (error != null) LogSeverity.ERROR else LogSeverity.INFO
        DiagnosticRepository.log(
            severity = severity,
            source = "MapEngine",
            description = "Map stage -> $stage" + (error?.let { ": $it" } ?: ""),
            stage = stage
        )
    }

    fun onCameraMoved(lat: Double, lng: Double, zoom: Double, bearing: Double) {
        val current = _uiState.value.camera
        val updated = current.copy(
            latitude = lat,
            longitude = lng,
            zoom = zoom,
            bearing = bearing
        )
        _uiState.value = _uiState.value.copy(camera = updated)

        viewModelScope.launch {
            preferencesRepository.saveCameraState(lat, lng, zoom, bearing)
        }
    }

    fun toggleTopPanel() {
        val newCollapsed = !_uiState.value.isTopPanelCollapsed
        _uiState.value = _uiState.value.copy(isTopPanelCollapsed = newCollapsed)
        viewModelScope.launch {
            preferencesRepository.saveTopPanelCollapsed(newCollapsed)
        }
    }

    fun toggleInternet() {
        val newInternet = !_uiState.value.isInternetEnabled
        AppNetworkPolicy.setInternetEnabled(newInternet)
        _uiState.value = _uiState.value.copy(isInternetEnabled = newInternet)
        viewModelScope.launch {
            preferencesRepository.saveInternetEnabled(newInternet)
        }
    }

    fun toggleLocationMode(onPermissionNeeded: () -> Unit) {
        val currentlyActive = _uiState.value.isLocationActive
        if (!currentlyActive) {
            onPermissionNeeded()
        } else {
            _uiState.value = _uiState.value.copy(
                isLocationActive = false,
                locationStatusText = "Location turned off"
            )
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "LocationManager",
                description = "Location tracking disabled by user"
            )
        }
    }

    fun setLocationPermissionGranted(granted: Boolean) {
        if (granted) {
            _uiState.value = _uiState.value.copy(
                isLocationActive = true,
                locationStatusText = "Location Standby (Active GPS is off until navigation)"
            )
            DiagnosticRepository.log(
                severity = LogSeverity.INFO,
                source = "LocationManager",
                description = "Location permission GRANTED"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isLocationActive = false,
                locationStatusText = "Location Permission Denied"
            )
            DiagnosticRepository.log(
                severity = LogSeverity.WARNING,
                source = "LocationManager",
                description = "Location permission DENIED by user"
            )
        }
    }

    fun toggleNorthLock() {
        val newLocked = !_uiState.value.camera.isNorthLocked
        val updatedCamera = _uiState.value.camera.copy(
            isNorthLocked = newLocked,
            bearing = if (newLocked) 0.0 else _uiState.value.camera.bearing
        )
        _uiState.value = _uiState.value.copy(camera = updatedCamera)
        viewModelScope.launch {
            preferencesRepository.saveNorthLocked(newLocked)
        }
    }

    fun selectStyle(style: MapStyleDefinition) {
        _uiState.value = _uiState.value.copy(currentStyle = style)
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapSourceRepository",
            description = "Selected style: ${style.displayName} (${style.providerName})",
            stage = "STYLE_SWITCH"
        )
        viewModelScope.launch {
            preferencesRepository.saveSelectedStyleId(style.id)
        }
    }

    fun openModal(modal: ActiveModal) {
        _uiState.value = _uiState.value.copy(activeModal = modal, quickSettingsOpen = false)
    }

    fun closeModal() {
        _uiState.value = _uiState.value.copy(activeModal = null, quickSettingsOpen = false)
    }

    fun openQuickSettings() {
        _uiState.value = _uiState.value.copy(quickSettingsOpen = true)
    }

    fun closeQuickSettings() {
        _uiState.value = _uiState.value.copy(quickSettingsOpen = false)
    }

    fun onMapStageChanged(stage: String, error: String? = null, isReady: Boolean? = null) {
        _uiState.value = _uiState.value.copy(
            mapLoadingStage = stage,
            lastMapError = error,
            isMapReady = isReady ?: _uiState.value.isMapReady
        )
    }

    override fun onCleared() {
        super.onCleared()
        networkMonitor.unregister()
    }
}
