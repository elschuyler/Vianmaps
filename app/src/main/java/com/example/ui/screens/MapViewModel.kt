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
import com.example.data.db.UserPlaceEntity
import com.example.data.osm.OsmOfflineDataRepository
import com.example.data.places.CreatePlaceOutcome
import com.example.data.places.PlacesAndRoutesRepository
import com.example.data.places.RouteGeometryCodec
import com.example.data.places.RoutePoint
import com.example.data.places.RouteWaypointDraft
import com.example.data.places.SavedRouteModel
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

data class PlacemarkEditorState(
    val editingPlaceId: String? = null,
    val name: String = "",
    val description: String = "",
    val addressLabel: String = "",
    val category: String = "placemark",
    val tagsText: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val nearbyDuplicatePlace: UserPlaceEntity? = null,
    val errorMessage: String? = null
)

data class RouteDraftUiState(
    val isActive: Boolean = false,
    val editingRouteId: String? = null,
    val initialName: String = "",
    val initialDescription: String = "",
    val points: List<RoutePoint> = emptyList(),
    val waypointNamesByPointIndex: Map<Int, String> = emptyMap(),
    val showSaveDialog: Boolean = false,
    val errorMessage: String? = null
) {
    val totalDistanceMeters: Double
        get() = RouteGeometryCodec.computePolylineDistanceMeters(points)
}

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
    val poiSearchState: PoiSearchUiState = PoiSearchUiState(),
    val placemarks: List<UserPlaceEntity> = emptyList(),
    val savedRoutes: List<SavedRouteModel> = emptyList(),
    val selectedPlacemark: UserPlaceEntity? = null,
    val selectedRoute: SavedRouteModel? = null,
    val isPickingPlacemarkLocation: Boolean = false,
    val placemarkEditorState: PlacemarkEditorState? = null,
    val routeDraft: RouteDraftUiState = RouteDraftUiState(),
    val routeMetadataEditorTarget: SavedRouteModel? = null,
    val userFeedbackMessage: String? = null
)

sealed interface ActiveModal {
    data object MoreSettings : ActiveModal
    data object MapCatalogue : ActiveModal
    data object BackupRestore : ActiveModal
    data object LogKeeper : ActiveModal
    data object MapsPopup : ActiveModal
    data object PlacesPopup : ActiveModal
    data object PlacemarksWorkspace : ActiveModal
    data object RoutesWorkspace : ActiveModal
    data object MemoryPalaceWorkspace : ActiveModal
    data object ToolsPopup : ActiveModal
    data object PoiSearch : ActiveModal
    data object PoiHighlight : ActiveModal
    data class PlaceholderTool(val name: String, val description: String) : ActiveModal
}

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = AppPreferencesRepository(application)
    private val osmRepository = OsmOfflineDataRepository(application)
    private val placesRepository = PlacesAndRoutesRepository(application)
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
            placesRepository.observePlacemarks().collect { list ->
                val currentSelected = _uiState.value.selectedPlacemark
                val refreshedSelected = currentSelected?.let { sel -> list.find { it.id == sel.id } }
                _uiState.value = _uiState.value.copy(
                    placemarks = list,
                    selectedPlacemark = refreshedSelected
                )
            }
        }

        viewModelScope.launch {
            placesRepository.observeRoutes().collect { routes ->
                val currentSelected = _uiState.value.selectedRoute
                val refreshedSelected = currentSelected?.let { sel -> routes.find { it.route.id == sel.route.id } }
                _uiState.value = _uiState.value.copy(
                    savedRoutes = routes,
                    selectedRoute = refreshedSelected
                )
            }
        }

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

    // =========================================================================
    // Phase 4.1: Shared Places, Placemarks & Saved Routes Actions
    // =========================================================================

    fun consumeUserFeedbackMessage() {
        _uiState.value = _uiState.value.copy(userFeedbackMessage = null)
    }

    fun startCreatePlacemarkAtMapCenter() {
        val cam = _uiState.value.camera
        _uiState.value = _uiState.value.copy(
            isPickingPlacemarkLocation = false,
            placemarkEditorState = PlacemarkEditorState(
                editingPlaceId = null,
                latitude = cam.latitude,
                longitude = cam.longitude
            )
        )
    }

    fun startPickPlacemarkOnMap() {
        _uiState.value = _uiState.value.copy(
            activeModal = null,
            settingsPopupOpen = false,
            isPickingPlacemarkLocation = true,
            selectedPlacemark = null,
            selectedRoute = null
        )
    }

    fun cancelPickPlacemarkOnMap() {
        _uiState.value = _uiState.value.copy(isPickingPlacemarkLocation = false)
    }

    fun onMapCoordinatePickedForPlacemark(latitude: Double, longitude: Double) {
        if (!RoutePoint(latitude, longitude).isValid()) return
        _uiState.value = _uiState.value.copy(
            isPickingPlacemarkLocation = false,
            activeModal = null,
            settingsPopupOpen = false,
            placemarkEditorState = PlacemarkEditorState(
                editingPlaceId = null,
                latitude = latitude,
                longitude = longitude
            )
        )
    }

    fun openEditPlacemark(place: UserPlaceEntity) {
        _uiState.value = _uiState.value.copy(
            isPickingPlacemarkLocation = false,
            placemarkEditorState = PlacemarkEditorState(
                editingPlaceId = place.id,
                name = place.name,
                description = place.description,
                addressLabel = place.addressLabel.orEmpty(),
                category = place.category,
                tagsText = place.tagsCsv,
                latitude = place.latitude,
                longitude = place.longitude
            )
        )
    }

    fun dismissPlacemarkEditor() {
        _uiState.value = _uiState.value.copy(placemarkEditorState = null)
    }

    fun savePlacemarkFromEditor(
        name: String,
        description: String,
        addressLabel: String,
        category: String,
        tagsText: String,
        latitude: Double,
        longitude: Double,
        allowDuplicateLocation: Boolean = false,
        controller: MapEngineController? = null
    ) {
        val editor = _uiState.value.placemarkEditorState ?: return
        val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        viewModelScope.launch {
            if (editor.editingPlaceId != null) {
                val result = placesRepository.updatePlacemark(
                    placeId = editor.editingPlaceId,
                    name = name,
                    description = description,
                    addressLabel = addressLabel,
                    category = category,
                    tags = tags,
                    latitude = latitude,
                    longitude = longitude
                )
                result.fold(
                    onSuccess = { updated ->
                        val updatedList = _uiState.value.placemarks.map { if (it.id == updated.id) updated else it }
                        _uiState.value = _uiState.value.copy(
                            placemarks = updatedList,
                            selectedPlacemark = if (_uiState.value.selectedPlacemark?.id == updated.id) updated else _uiState.value.selectedPlacemark,
                            placemarkEditorState = null,
                            userFeedbackMessage = "Updated placemark '${updated.name}'"
                        )
                        controller?.updateUserPlacemarks(updatedList, _uiState.value.selectedPlacemark?.id)
                    },
                    onFailure = { err ->
                        _uiState.value = _uiState.value.copy(
                            placemarkEditorState = editor.copy(
                                name = name,
                                description = description,
                                addressLabel = addressLabel,
                                category = category,
                                tagsText = tagsText,
                                errorMessage = err.message ?: "Unable to update placemark."
                            )
                        )
                    }
                )
            } else {
                when (
                    val outcome = placesRepository.createPlacemark(
                        name = name,
                        latitude = latitude,
                        longitude = longitude,
                        description = description,
                        addressLabel = addressLabel,
                        category = category,
                        tags = tags,
                        allowDuplicateLocation = allowDuplicateLocation
                    )
                ) {
                    is CreatePlaceOutcome.Created -> {
                        val created = outcome.place
                        val updatedList = listOf(created) + _uiState.value.placemarks.filter { it.id != created.id }
                        _uiState.value = _uiState.value.copy(
                            placemarks = updatedList,
                            selectedPlacemark = created,
                            selectedRoute = null,
                            placemarkEditorState = null,
                            activeModal = null,
                            userFeedbackMessage = "Saved placemark '${created.name}'"
                        )
                        controller?.updateUserPlacemarks(updatedList, created.id)
                        controller?.focusOnPlacemark(created)
                        refreshStorageReport()
                    }
                    is CreatePlaceOutcome.DuplicateNearby -> {
                        _uiState.value = _uiState.value.copy(
                            placemarkEditorState = editor.copy(
                                name = name,
                                description = description,
                                addressLabel = addressLabel,
                                category = category,
                                tagsText = tagsText,
                                latitude = latitude,
                                longitude = longitude,
                                nearbyDuplicatePlace = outcome.existing,
                                errorMessage = null
                            )
                        )
                    }
                    is CreatePlaceOutcome.ValidationError -> {
                        _uiState.value = _uiState.value.copy(
                            placemarkEditorState = editor.copy(
                                name = name,
                                description = description,
                                addressLabel = addressLabel,
                                category = category,
                                tagsText = tagsText,
                                errorMessage = outcome.message
                            )
                        )
                    }
                }
            }
        }
    }

    fun selectExistingDuplicatePlacemark(existing: UserPlaceEntity, controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(placemarkEditorState = null)
        selectPlacemark(existing, controller)
    }

    fun selectPlacemark(place: UserPlaceEntity, controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(
            selectedPlacemark = place,
            selectedRoute = null,
            activeModal = null,
            settingsPopupOpen = false,
            isPickingPlacemarkLocation = false
        )
        controller?.updateUserPlacemarks(_uiState.value.placemarks, place.id)
        controller?.focusOnPlacemark(place)
    }

    fun selectPlacemarkById(placeId: String, controller: MapEngineController? = null) {
        val found = _uiState.value.placemarks.firstOrNull { it.id == placeId } ?: return
        selectPlacemark(found, controller)
    }

    fun clearSelectedPlacemark(controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(selectedPlacemark = null)
        controller?.updateUserPlacemarks(_uiState.value.placemarks, null)
    }

    fun deletePlacemark(placeId: String, controller: MapEngineController? = null) {
        viewModelScope.launch {
            val deleted = placesRepository.deletePlacemark(placeId)
            if (deleted) {
                val updatedList = _uiState.value.placemarks.filter { it.id != placeId }
                val nextSelected = if (_uiState.value.selectedPlacemark?.id == placeId) null else _uiState.value.selectedPlacemark
                _uiState.value = _uiState.value.copy(
                    placemarks = updatedList,
                    selectedPlacemark = nextSelected,
                    placemarkEditorState = if (_uiState.value.placemarkEditorState?.editingPlaceId == placeId) null else _uiState.value.placemarkEditorState,
                    userFeedbackMessage = "Placemark deleted"
                )
                controller?.updateUserPlacemarks(updatedList, nextSelected?.id)
                refreshStorageReport()
            }
        }
    }

    // --- Saved Routes & Draft Route Creation ---

    fun startNewRouteCreation(controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(
            activeModal = null,
            settingsPopupOpen = false,
            isPickingPlacemarkLocation = false,
            selectedPlacemark = null,
            selectedRoute = null,
            routeDraft = RouteDraftUiState(isActive = true)
        )
        controller?.updateRouteDraftOverlay(emptyList())
    }

    fun startEditingRouteGeometry(routeModel: SavedRouteModel, controller: MapEngineController? = null) {
        val wpMap = mutableMapOf<Int, String>()
        if (routeModel.points.isNotEmpty()) {
            for (wp in routeModel.waypoints) {
                val nearestIdx = routeModel.points.indices.minByOrNull { idx ->
                    RouteGeometryCodec.haversineMeters(
                        routeModel.points[idx].latitude,
                        routeModel.points[idx].longitude,
                        wp.latitude,
                        wp.longitude
                    )
                }
                if (nearestIdx != null) {
                    wpMap[nearestIdx] = wp.name
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            activeModal = null,
            settingsPopupOpen = false,
            isPickingPlacemarkLocation = false,
            selectedPlacemark = null,
            routeMetadataEditorTarget = null,
            routeDraft = RouteDraftUiState(
                isActive = true,
                editingRouteId = routeModel.route.id,
                initialName = routeModel.route.name,
                initialDescription = routeModel.route.description,
                points = routeModel.points,
                waypointNamesByPointIndex = wpMap
            )
        )
        controller?.updateRouteDraftOverlay(routeModel.points)
        controller?.focusOnRoute(routeModel)
    }

    fun addRouteDraftPoint(latitude: Double, longitude: Double, controller: MapEngineController? = null) {
        val draft = _uiState.value.routeDraft
        if (!draft.isActive) return
        val pt = RoutePoint(latitude, longitude)
        if (!pt.isValid()) return
        val nextPoints = draft.points + pt
        _uiState.value = _uiState.value.copy(
            routeDraft = draft.copy(points = nextPoints, errorMessage = null)
        )
        controller?.updateRouteDraftOverlay(nextPoints)
    }

    fun undoRouteDraftPoint(controller: MapEngineController? = null) {
        val draft = _uiState.value.routeDraft
        if (!draft.isActive || draft.points.isEmpty()) return
        val removedIndex = draft.points.lastIndex
        val nextPoints = draft.points.dropLast(1)
        val nextWaypoints = draft.waypointNamesByPointIndex - removedIndex
        _uiState.value = _uiState.value.copy(
            routeDraft = draft.copy(
                points = nextPoints,
                waypointNamesByPointIndex = nextWaypoints,
                errorMessage = null
            )
        )
        controller?.updateRouteDraftOverlay(nextPoints)
    }

    fun markLastDraftPointAsWaypoint(name: String) {
        val draft = _uiState.value.routeDraft
        if (!draft.isActive || draft.points.isEmpty()) return
        val idx = draft.points.lastIndex
        val cleanName = name.trim().ifEmpty { "Waypoint ${draft.waypointNamesByPointIndex.size + 1}" }
        _uiState.value = _uiState.value.copy(
            routeDraft = draft.copy(
                waypointNamesByPointIndex = draft.waypointNamesByPointIndex + (idx to cleanName)
            )
        )
    }

    fun openSaveRouteDialog() {
        val draft = _uiState.value.routeDraft
        if (!draft.isActive) return
        if (draft.points.size < 2) {
            _uiState.value = _uiState.value.copy(
                routeDraft = draft.copy(errorMessage = "Tap at least 2 points on the map before saving.")
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            routeDraft = draft.copy(showSaveDialog = true, errorMessage = null)
        )
    }

    fun dismissSaveRouteDialog() {
        val draft = _uiState.value.routeDraft
        _uiState.value = _uiState.value.copy(
            routeDraft = draft.copy(showSaveDialog = false, errorMessage = null)
        )
    }

    fun cancelRouteDraft(controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(routeDraft = RouteDraftUiState())
        controller?.updateRouteDraftOverlay(emptyList())
    }

    fun finishAndSaveRouteDraft(
        name: String,
        description: String,
        waypointNamesByPointIndex: Map<Int, String> = _uiState.value.routeDraft.waypointNamesByPointIndex,
        controller: MapEngineController? = null
    ) {
        val draft = _uiState.value.routeDraft
        val waypoints = waypointNamesByPointIndex.entries
            .sortedBy { it.key }
            .mapNotNull { (index, wpName) ->
                val pt = draft.points.getOrNull(index) ?: return@mapNotNull null
                RouteWaypointDraft(
                    name = wpName.trim().ifEmpty { "Waypoint" },
                    latitude = pt.latitude,
                    longitude = pt.longitude
                )
            }

        viewModelScope.launch {
            val result = placesRepository.saveRoute(
                routeId = draft.editingRouteId,
                name = name,
                description = description,
                points = draft.points,
                waypoints = waypoints
            )
            result.fold(
                onSuccess = { saved ->
                    val updatedList = listOf(saved) + _uiState.value.savedRoutes.filter { it.route.id != saved.route.id }
                    _uiState.value = _uiState.value.copy(
                        savedRoutes = updatedList,
                        selectedRoute = saved,
                        selectedPlacemark = null,
                        routeDraft = RouteDraftUiState(),
                        userFeedbackMessage = "Saved route '${saved.route.name}'"
                    )
                    controller?.updateRouteDraftOverlay(emptyList())
                    controller?.updateSavedRoutes(updatedList, saved.route.id)
                    controller?.focusOnRoute(saved)
                    refreshStorageReport()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        routeDraft = draft.copy(
                            showSaveDialog = true,
                            errorMessage = err.message ?: "Unable to save route."
                        )
                    )
                }
            )
        }
    }

    fun openRouteMetadataEditor(routeModel: SavedRouteModel) {
        _uiState.value = _uiState.value.copy(routeMetadataEditorTarget = routeModel)
    }

    fun dismissRouteMetadataEditor() {
        _uiState.value = _uiState.value.copy(routeMetadataEditorTarget = null)
    }

    fun updateRouteMetadataAndWaypoints(
        routeModel: SavedRouteModel,
        name: String,
        description: String,
        updatedWaypoints: List<RouteWaypointDraft>,
        controller: MapEngineController? = null
    ) {
        viewModelScope.launch {
            val result = placesRepository.saveRoute(
                routeId = routeModel.route.id,
                name = name,
                description = description,
                points = routeModel.points,
                waypoints = updatedWaypoints
            )
            result.fold(
                onSuccess = { updated ->
                    val updatedList = _uiState.value.savedRoutes.map {
                        if (it.route.id == updated.route.id) updated else it
                    }
                    _uiState.value = _uiState.value.copy(
                        savedRoutes = updatedList,
                        selectedRoute = if (_uiState.value.selectedRoute?.route?.id == updated.route.id) updated else _uiState.value.selectedRoute,
                        routeMetadataEditorTarget = null,
                        userFeedbackMessage = "Updated route '${updated.route.name}'"
                    )
                    controller?.updateSavedRoutes(updatedList, _uiState.value.selectedRoute?.route?.id)
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        userFeedbackMessage = err.message ?: "Unable to update route."
                    )
                }
            )
        }
    }

    fun selectSavedRoute(routeModel: SavedRouteModel, controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(
            selectedRoute = routeModel,
            selectedPlacemark = null,
            activeModal = null,
            settingsPopupOpen = false,
            isPickingPlacemarkLocation = false
        )
        controller?.updateSavedRoutes(_uiState.value.savedRoutes, routeModel.route.id)
        controller?.focusOnRoute(routeModel)
    }

    fun selectSavedRouteById(routeId: String, controller: MapEngineController? = null) {
        val found = _uiState.value.savedRoutes.firstOrNull { it.route.id == routeId } ?: return
        selectSavedRoute(found, controller)
    }

    fun clearSelectedRoute(controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(selectedRoute = null)
        controller?.updateSavedRoutes(_uiState.value.savedRoutes, null)
    }

    fun deleteSavedRoute(routeId: String, controller: MapEngineController? = null) {
        viewModelScope.launch {
            val deleted = placesRepository.deleteRoute(routeId)
            if (deleted) {
                val updatedList = _uiState.value.savedRoutes.filter { it.route.id != routeId }
                val nextSelected = if (_uiState.value.selectedRoute?.route?.id == routeId) null else _uiState.value.selectedRoute
                _uiState.value = _uiState.value.copy(
                    savedRoutes = updatedList,
                    selectedRoute = nextSelected,
                    routeMetadataEditorTarget = if (_uiState.value.routeMetadataEditorTarget?.route?.id == routeId) null else _uiState.value.routeMetadataEditorTarget,
                    userFeedbackMessage = "Route deleted"
                )
                controller?.updateSavedRoutes(updatedList, nextSelected?.route?.id)
                refreshStorageReport()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        networkMonitor.unregister()
    }
}
