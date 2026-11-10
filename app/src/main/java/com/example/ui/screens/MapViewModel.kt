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

import com.example.map.engine.MapEngineController
import com.example.map.engine.MapEngineStatus

import com.example.data.db.AppDatabase
import com.example.data.storage.OverallStorageReport
import com.example.data.storage.StorageCalculationManager
import com.example.map.composition.MapComposition
import com.example.map.composition.MapCompositionDefaults

data class MapUiState(
    val camera: MapCameraState = MapCameraState(),
    val isTopPanelCollapsed: Boolean = true,
    val isInternetEnabled: Boolean = true,
    val currentStyle: MapStyleDefinition = MapSourceRepository.defaultStyle,
    val activeComposition: MapComposition = MapCompositionDefaults.defaultComposition,
    val savedCompositions: List<MapComposition> = MapCompositionDefaults.starterCompositions,
    val storageReport: OverallStorageReport? = null,
    val isCalculatingStorage: Boolean = false,
    val isLocationActive: Boolean = false,
    val locationStatusText: String = "Location Off (Tap 2nd right control to request)",
    val isMapReady: Boolean = false,
    val mapStatus: MapEngineStatus = MapEngineStatus.INITIALIZING,
    val mapLoadingStage: String = "INITIALIZING",
    val lastMapError: String? = null,
    val activeModal: ActiveModal? = null,
    val settingsPopupOpen: Boolean = false
)

sealed interface ActiveModal {
    data object MoreSettings : ActiveModal
    data object MapCatalogue : ActiveModal
    data object BackupRestore : ActiveModal
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
            val compositions = MapComposition.listFromJsonString(savedPrefs.savedCompositionsJson)
            val activeComp = compositions.find { it.id == savedPrefs.activeCompositionId }
                ?: MapCompositionDefaults.defaultComposition
            val style = activeComp.baseStyle

            AppNetworkPolicy.setInternetEnabled(savedPrefs.isInternetEnabled)

            _uiState.value = _uiState.value.copy(
                camera = savedPrefs.cameraState,
                isTopPanelCollapsed = savedPrefs.isTopPanelCollapsed,
                isInternetEnabled = savedPrefs.isInternetEnabled,
                currentStyle = style,
                activeComposition = activeComp,
                savedCompositions = compositions
            )

            // Initial storage metrics calculation in background
            refreshStorageReport()
        }
    }

    fun onMapStatusChanged(
        status: MapEngineStatus,
        stageDescription: String? = null,
        error: String? = null
    ) {
        val stageLabel = stageDescription ?: status.label
        val isReady = when (status) {
            MapEngineStatus.STYLE_READY,
            MapEngineStatus.RENDERING,
            MapEngineStatus.FULLY_RENDERED -> true
            MapEngineStatus.FAILED -> false
            else -> _uiState.value.isMapReady
        }
        _uiState.value = _uiState.value.copy(
            mapStatus = status,
            mapLoadingStage = stageLabel,
            isMapReady = isReady,
            lastMapError = error ?: if (status != MapEngineStatus.FAILED) null else _uiState.value.lastMapError
        )

        val severity = if (error != null || status == MapEngineStatus.FAILED) LogSeverity.ERROR else LogSeverity.INFO
        val desc = buildString {
            append("Map status -> [${status.name}] $stageLabel")
            if (!error.isNullOrEmpty()) {
                append(" | Error: $error")
            }
        }
        DiagnosticRepository.log(
            severity = severity,
            source = "MapEngine",
            description = desc,
            stage = status.name
        )
    }

    fun onMapStageChanged(stage: String, isReady: Boolean? = null, error: String? = null) {
        val status = when (stage) {
            "INITIALIZING" -> MapEngineStatus.INITIALIZING
            "WAITING_FOR_MAP_VIEW" -> MapEngineStatus.WAITING_FOR_MAP_VIEW
            "LOADING_STYLE", "Style Attached" -> MapEngineStatus.LOADING_STYLE
            "Style Loaded" -> MapEngineStatus.STYLE_READY
            "Rendering Frame" -> MapEngineStatus.RENDERING
            "Fully Rendered" -> MapEngineStatus.FULLY_RENDERED
            "Map Load Failed", "Render Error" -> MapEngineStatus.FAILED
            "RETRYING" -> MapEngineStatus.RETRYING
            else -> _uiState.value.mapStatus
        }
        onMapStatusChanged(status = status, stageDescription = stage, error = error)
    }

    fun retryOrRefreshMap(controller: MapEngineController) {
        onMapStatusChanged(
            status = MapEngineStatus.RETRYING,
            stageDescription = "Retrying map render and style load"
        )
        controller.retryOrRefresh(
            styleDef = _uiState.value.currentStyle,
            onStatusChanged = { status, desc, err ->
                onMapStatusChanged(status, desc, err)
            }
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

    fun selectComposition(composition: MapComposition, controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(
            activeComposition = composition,
            currentStyle = composition.baseStyle
        )
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapComposition",
            description = "Selected composition: ${composition.name} (Base: ${composition.baseStyle.displayName})",
            stage = "COMPOSITION_SWITCH"
        )
        viewModelScope.launch {
            preferencesRepository.saveActiveCompositionId(composition.id)
            preferencesRepository.saveSelectedStyleId(composition.baseStyleId)
        }
        controller?.applyComposition(composition) { status, desc, err ->
            onMapStatusChanged(status, desc, err)
        }
    }

    fun saveComposition(composition: MapComposition, controller: MapEngineController? = null) {
        val currentList = _uiState.value.savedCompositions.toMutableList()
        val index = currentList.indexOfFirst { it.id == composition.id }
        if (index >= 0) {
            currentList[index] = composition
        } else {
            currentList.add(composition)
        }

        val isActive = _uiState.value.activeComposition.id == composition.id
        _uiState.value = _uiState.value.copy(
            savedCompositions = currentList,
            activeComposition = if (isActive) composition else _uiState.value.activeComposition
        )

        viewModelScope.launch {
            val jsonString = MapComposition.listToJsonString(currentList)
            preferencesRepository.saveSavedCompositionsJson(jsonString)
        }

        if (isActive && controller != null) {
            controller.applyComposition(composition) { status, desc, err ->
                onMapStatusChanged(status, desc, err)
            }
        }
    }

    fun deleteComposition(compositionId: String, controller: MapEngineController? = null) {
        val currentList = _uiState.value.savedCompositions.filter { it.id != compositionId }
        val updatedList = if (currentList.isEmpty()) MapCompositionDefaults.starterCompositions else currentList

        var newActive = _uiState.value.activeComposition
        if (newActive.id == compositionId) {
            newActive = updatedList.firstOrNull() ?: MapCompositionDefaults.defaultComposition
            selectComposition(newActive, controller)
        }

        _uiState.value = _uiState.value.copy(
            savedCompositions = updatedList,
            activeComposition = newActive
        )

        viewModelScope.launch {
            val jsonString = MapComposition.listToJsonString(updatedList)
            preferencesRepository.saveSavedCompositionsJson(jsonString)
        }
    }

    fun duplicateComposition(composition: MapComposition) {
        val newId = "comp_${System.currentTimeMillis()}"
        val duplicate = composition.copy(
            id = newId,
            name = "${composition.name} Copy",
            isStarter = false,
            isPinnedToQuickChoice = true
        )
        val currentList = _uiState.value.savedCompositions + duplicate
        _uiState.value = _uiState.value.copy(savedCompositions = currentList)

        viewModelScope.launch {
            val jsonString = MapComposition.listToJsonString(currentList)
            preferencesRepository.saveSavedCompositionsJson(jsonString)
        }
    }

    fun togglePinComposition(compositionId: String) {
        val updatedList = _uiState.value.savedCompositions.map {
            if (it.id == compositionId) it.copy(isPinnedToQuickChoice = !it.isPinnedToQuickChoice) else it
        }
        val updatedActive = if (_uiState.value.activeComposition.id == compositionId) {
            _uiState.value.activeComposition.copy(isPinnedToQuickChoice = !_uiState.value.activeComposition.isPinnedToQuickChoice)
        } else {
            _uiState.value.activeComposition
        }

        _uiState.value = _uiState.value.copy(
            savedCompositions = updatedList,
            activeComposition = updatedActive
        )

        viewModelScope.launch {
            val jsonString = MapComposition.listToJsonString(updatedList)
            preferencesRepository.saveSavedCompositionsJson(jsonString)
        }
    }

    fun refreshStorageReport() {
        _uiState.value = _uiState.value.copy(isCalculatingStorage = true)
        viewModelScope.launch {
            try {
                val db = AppDatabase.getInstance(getApplication())
                val report = StorageCalculationManager.calculateStorage(getApplication(), db)
                _uiState.value = _uiState.value.copy(
                    storageReport = report,
                    isCalculatingStorage = false
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(isCalculatingStorage = false)
            }
        }
    }

    fun clearProviderCache(providerId: String) {
        viewModelScope.launch {
            try {
                val db = AppDatabase.getInstance(getApplication())
                val result = StorageCalculationManager.clearProviderTiles(getApplication(), db, providerId)
                DiagnosticRepository.log(
                    severity = if (result.isSuccess) LogSeverity.INFO else LogSeverity.WARNING,
                    source = "StorageCalculationManager",
                    description = "Cleared cache for $providerId: ${result.filesDeleted} files, ${result.recordsDeleted} records, ${result.bytesDeleted} bytes (Success: ${result.isSuccess}${if (result.error != null) " - ${result.error}" else ""})"
                )
                refreshStorageReport()
            } catch (e: Throwable) {
                DiagnosticRepository.log(
                    severity = LogSeverity.ERROR,
                    source = "StorageCalculationManager",
                    description = "Failed clearing cache for $providerId: ${e.message}"
                )
                refreshStorageReport()
            }
        }
    }

    fun openModal(modal: ActiveModal) {
        _uiState.value = _uiState.value.copy(activeModal = modal, settingsPopupOpen = false)
    }

    fun closeModal() {
        _uiState.value = _uiState.value.copy(activeModal = null, settingsPopupOpen = false)
    }

    fun openSettingsPopup() {
        _uiState.value = _uiState.value.copy(settingsPopupOpen = true, activeModal = null)
    }

    fun closeSettingsPopup() {
        _uiState.value = _uiState.value.copy(settingsPopupOpen = false)
    }

    override fun onCleared() {
        super.onCleared()
        networkMonitor.unregister()
    }
}
