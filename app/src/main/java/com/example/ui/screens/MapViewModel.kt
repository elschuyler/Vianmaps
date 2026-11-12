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
import com.example.data.db.OsmPoiRecord
import com.example.data.osm.OsmOfflineDataRepository
import com.example.data.storage.OverallStorageReport
import com.example.data.storage.StorageCalculationManager
import com.example.map.composition.MapComposition
import com.example.map.composition.MapCompositionDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class PoiCategoryOption(
    val category: String,
    val count: Int
)

data class PoiSearchUiState(
    val query: String = "",
    val selectedCategory: String? = null,
    val results: List<OsmPoiRecord> = emptyList(),
    val availableCategories: List<PoiCategoryOption> = emptyList(),
    val totalLocalPoiCount: Int = 0,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null
)

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
    val settingsPopupOpen: Boolean = false,
    val highlightedPoi: OsmPoiRecord? = null,
    val isPoiHighlightVisible: Boolean = true,
    val poiSearchState: PoiSearchUiState = PoiSearchUiState()
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
    data object PoiSearch : ActiveModal
    data object PoiHighlight : ActiveModal
    data class PlaceholderTool(val name: String, val description: String) : ActiveModal
}

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = AppPreferencesRepository(application)
    private val osmRepository = OsmOfflineDataRepository(application)
    val networkMonitor = NetworkDiagnosticsMonitor(application)

    private var searchJob: Job? = null

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

    fun openPoiSearch() {
        _uiState.value = _uiState.value.copy(
            activeModal = ActiveModal.PoiSearch,
            settingsPopupOpen = false,
            poiSearchState = _uiState.value.poiSearchState.copy(errorMessage = null)
        )
        refreshLocalPoiCatalogStats()
    }

    fun openPoiHighlight() {
        _uiState.value = _uiState.value.copy(
            activeModal = ActiveModal.PoiHighlight,
            settingsPopupOpen = false
        )
    }

    fun refreshLocalPoiCatalogStats() {
        viewModelScope.launch {
            try {
                val totalCount = osmRepository.getPoiCount()
                val categoryCounts = osmRepository.getCategoryCounts(limit = 25)
                    .map { PoiCategoryOption(category = it.category, count = it.count) }
                _uiState.value = _uiState.value.copy(
                    poiSearchState = _uiState.value.poiSearchState.copy(
                        totalLocalPoiCount = totalCount,
                        availableCategories = categoryCounts
                    )
                )
            } catch (e: Throwable) {
                DiagnosticRepository.log(
                    severity = LogSeverity.WARNING,
                    source = "MapViewModel",
                    description = "Failed reading local POI catalog summary: ${e.message}"
                )
            }
        }
    }

    fun updatePoiSearchQuery(query: String, debounceMs: Long = 200L) {
        val currentSearch = _uiState.value.poiSearchState
        _uiState.value = _uiState.value.copy(
            poiSearchState = currentSearch.copy(query = query)
        )
        executePoiSearch(
            query = query,
            category = currentSearch.selectedCategory,
            debounceMs = debounceMs
        )
    }

    fun selectPoiSearchCategory(category: String?) {
        val currentSearch = _uiState.value.poiSearchState
        val normalized = category?.trim()?.takeIf { it.isNotEmpty() }
        val nextCategory = if (currentSearch.selectedCategory.equals(normalized, ignoreCase = true)) {
            null
        } else {
            normalized
        }
        _uiState.value = _uiState.value.copy(
            poiSearchState = currentSearch.copy(selectedCategory = nextCategory)
        )
        executePoiSearch(
            query = currentSearch.query,
            category = nextCategory,
            debounceMs = 0L
        )
    }

    private fun executePoiSearch(query: String, category: String?, debounceMs: Long) {
        searchJob?.cancel()
        val trimmed = query.trim()
        val cat = category?.trim()?.takeIf { it.isNotEmpty() }

        if (trimmed.isEmpty() && cat == null) {
            _uiState.value = _uiState.value.copy(
                poiSearchState = _uiState.value.poiSearchState.copy(
                    results = emptyList(),
                    isSearching = false,
                    hasSearched = false,
                    errorMessage = null
                )
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            poiSearchState = _uiState.value.poiSearchState.copy(
                isSearching = true,
                errorMessage = null
            )
        )

        searchJob = viewModelScope.launch {
            try {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                val matches = osmRepository.searchPois(query = trimmed, category = cat, limit = 30)
                _uiState.value = _uiState.value.copy(
                    poiSearchState = _uiState.value.poiSearchState.copy(
                        results = matches,
                        isSearching = false,
                        hasSearched = true,
                        errorMessage = null
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                DiagnosticRepository.log(
                    severity = LogSeverity.WARNING,
                    source = "PoiSearch",
                    description = "Local Room POI search failed: ${e.message}"
                )
                _uiState.value = _uiState.value.copy(
                    poiSearchState = _uiState.value.poiSearchState.copy(
                        results = emptyList(),
                        isSearching = false,
                        hasSearched = true,
                        errorMessage = "Unable to query local POI database: ${e.message ?: "Unknown error"}"
                    )
                )
            }
        }
    }

    fun selectSearchResult(poi: OsmPoiRecord, controller: MapEngineController? = null) {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            highlightedPoi = poi,
            isPoiHighlightVisible = true,
            activeModal = null,
            settingsPopupOpen = false,
            poiSearchState = _uiState.value.poiSearchState.copy(
                query = "",
                selectedCategory = null,
                results = emptyList(),
                isSearching = false,
                hasSearched = false,
                errorMessage = null
            )
        )
        controller?.highlightPoi(poi, moveCamera = true)
    }

    fun togglePoiHighlightVisibility(controller: MapEngineController? = null) {
        val nextVisible = !_uiState.value.isPoiHighlightVisible
        _uiState.value = _uiState.value.copy(isPoiHighlightVisible = nextVisible)
        controller?.setHighlightVisible(nextVisible)
    }

    fun recenterOnHighlightedPoi(controller: MapEngineController? = null) {
        val poi = _uiState.value.highlightedPoi ?: return
        _uiState.value = _uiState.value.copy(isPoiHighlightVisible = true)
        controller?.highlightPoi(poi, moveCamera = true)
    }

    fun clearHighlightedPoi(controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(
            highlightedPoi = null,
            isPoiHighlightVisible = true
        )
        controller?.clearPoiHighlight()
    }

    fun openModal(modal: ActiveModal) {
        if (_uiState.value.activeModal is ActiveModal.PoiSearch && modal !is ActiveModal.PoiSearch) {
            releaseTemporarySearchResources()
        }
        _uiState.value = _uiState.value.copy(activeModal = modal, settingsPopupOpen = false)
    }

    fun closeModal() {
        if (_uiState.value.activeModal is ActiveModal.PoiSearch) {
            releaseTemporarySearchResources()
        }
        _uiState.value = _uiState.value.copy(activeModal = null, settingsPopupOpen = false)
    }

    private fun releaseTemporarySearchResources() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            poiSearchState = _uiState.value.poiSearchState.copy(
                query = "",
                selectedCategory = null,
                results = emptyList(),
                isSearching = false,
                hasSearched = false,
                errorMessage = null
            )
        )
    }

    fun openSettingsPopup() {
        _uiState.value = _uiState.value.copy(settingsPopupOpen = true, activeModal = null)
    }

    fun closeSettingsPopup() {
        _uiState.value = _uiState.value.copy(settingsPopupOpen = false)
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        networkMonitor.unregister()
    }
}
