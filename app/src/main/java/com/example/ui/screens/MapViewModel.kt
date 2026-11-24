package com.example.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.MapCameraState
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.diagnostics.NetworkDiagnosticsMonitor
import com.example.map.activation.MapRuntimeDependencies
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

import com.example.data.area.AreaDataProvenance
import com.example.data.area.AreaDetailsRepository
import com.example.data.area.AreaDetailsResult
import com.example.data.area.AreaRemoteLookupGateway
import com.example.data.db.OsmPoiRecord
import com.example.data.db.SavedAreaEntity
import com.example.data.db.UserPlaceEntity
import com.example.data.osm.CategoryHighlightedPoi
import com.example.data.osm.PoiCategoryHighlightDefaults
import com.example.data.osm.PoiHighlightCategory
import com.example.data.osm.PoiViewportBounds
import com.example.data.places.CreatePlaceOutcome
import com.example.data.places.RouteGeometryCodec
import com.example.data.places.RoutePoint
import com.example.data.places.RouteWaypointDraft
import com.example.data.places.SavedRouteModel
import com.example.data.storage.OverallStorageReport
import com.example.map.composition.MapComposition
import com.example.map.composition.MapCompositionDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

data class PoiCategoryHighlightUiState(
    val selectedCategoryIds: Set<String> = emptySet(),
    val isAllPoisSelected: Boolean = false,
    val matchedPois: List<CategoryHighlightedPoi> = emptyList(),
    val categoryMatchCounts: Map<String, Int> = emptyMap(),
    val totalMatchingCount: Int = 0,
    val isDisplayTruncated: Boolean = false,
    val displayLimit: Int? = null,
    val isRefreshing: Boolean = false,
    val hasQueriedViewport: Boolean = false,
    val isPausedByZoom: Boolean = false,
    val currentZoom: Double = 2.0,
    val minHighlightZoom: Double = PoiCategoryHighlightDefaults.MIN_POI_HIGHLIGHT_ZOOM,
    val lastViewport: PoiViewportBounds? = null,
    val errorMessage: String? = null
) {
    val hasActiveSelection: Boolean
        get() = isAllPoisSelected || selectedCategoryIds.isNotEmpty()
}

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
    val categoryHighlightState: PoiCategoryHighlightUiState = PoiCategoryHighlightUiState(),
    val placemarks: List<UserPlaceEntity> = emptyList(),
    val savedRoutes: List<SavedRouteModel> = emptyList(),
    val selectedPlacemark: UserPlaceEntity? = null,
    val selectedRoute: SavedRouteModel? = null,
    val isPickingPlacemarkLocation: Boolean = false,
    val placemarkEditorState: PlacemarkEditorState? = null,
    val routeDraft: RouteDraftUiState = RouteDraftUiState(),
    val routeMetadataEditorTarget: SavedRouteModel? = null,
    val savedAreas: List<SavedAreaEntity> = emptyList(),
    val areaDetailsState: AreaDetailsUiState? = null,
    val mapContextMenuState: MapContextMenuUiState? = null,
    val savedAreaEditorState: SavedAreaEditorState? = null,
    val plannedActionNotice: PlannedActionNoticeState? = null,
    val userFeedbackMessage: String? = null,
    val hasLoadedPreferences: Boolean = false
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
    data object SavedAreasWorkspace : ActiveModal
    data object MemoryPalaceWorkspace : ActiveModal
    data object ToolsPopup : ActiveModal
    data object PoiSearch : ActiveModal
    data object PoiHighlight : ActiveModal
    data class PlaceholderTool(val name: String, val description: String) : ActiveModal
}

class MapViewModel(
    application: Application,
    private val dependencies: MapRuntimeDependencies = MapRuntimeDependencies(application)
) : AndroidViewModel(application) {

    private val preferencesRepository = dependencies.preferencesRepository
    private val osmRepository = dependencies.osmRepository
    private val placesRepository = dependencies.placesRepository
    private var areaRepository = dependencies.areaRepository
    private val storageService = dependencies.storageService
    val networkMonitor: NetworkDiagnosticsMonitor = dependencies.networkMonitor

    private var searchJob: Job? = null
    private var categoryHighlightJob: Job? = null
    private var categoryQueryGeneration: Long = 0L
    private var areaLookupJob: Job? = null
    private var contextLookupJob: Job? = null
    private var onlineOverrideForTesting: Boolean? = null

    private val hasLocalCameraOverride = AtomicBoolean(false)
    private val hasPendingCameraPersistence = AtomicBoolean(false)
    private val hasLocalNorthLockOverride = AtomicBoolean(false)
    private val hasLocalInternetOverride = AtomicBoolean(false)
    private val hasLocalStyleOverride = AtomicBoolean(false)
    private val hasLocalCompositionOverride = AtomicBoolean(false)
    private val hasLocalTopPanelOverride = AtomicBoolean(false)

    private val pendingPrefJobsLock = Any()
    private val pendingPreferenceJobs = LinkedHashSet<Job>()
    private val cameraStateLock = Any()
    private val preferenceWriteMutex = Mutex()
    private val cameraPersistenceFrozen = AtomicBoolean(false)

    @Volatile
    private var lastNonCameraWriteError: Throwable? = null

    @Volatile
    internal var isCleared: Boolean = false
        private set

    @Volatile
    internal var onAwaitingPendingPreferenceWritesForTesting: (() -> Unit)? = null

    internal fun isCameraPersistenceFrozenForTesting(): Boolean = cameraPersistenceFrozen.get()

    internal fun pendingPreferenceJobCountForTesting(): Int = synchronized(pendingPrefJobsLock) {
        pendingPreferenceJobs.size
    }

    fun hasSessionCameraOverride(): Boolean = hasLocalCameraOverride.get()

    fun createMapEngineController(
        initialLat: Double,
        initialLng: Double,
        initialZoom: Double,
        initialBearing: Double,
        onCameraMoved: (lat: Double, lng: Double, zoom: Double, bearing: Double) -> Unit
    ): MapEngineController {
        return dependencies.createEngineController(
            initialLat = initialLat,
            initialLng = initialLng,
            initialZoom = initialZoom,
            initialBearing = initialBearing,
            onCameraMoved = onCameraMoved
        )
    }

    internal fun configureAreaTesting(
        gateway: AreaRemoteLookupGateway? = null,
        onlineOverride: Boolean? = null
    ) {
        if (gateway != null) {
            areaRepository = AreaDetailsRepository(
                context = dependencies.appContext,
                remoteGateway = gateway,
                db = dependencies.database
            )
        }
        onlineOverrideForTesting = onlineOverride
    }

    private fun isNetworkLookupPermitted(): Boolean {
        val deviceOnline = onlineOverrideForTesting ?: networkMonitor.isDeviceOnline.value
        return AppNetworkPolicy.isNetworkAllowed() && deviceOnline
    }

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        networkMonitor.ensureRegistered()

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
            areaRepository.observeSavedAreas().collect { areas ->
                _uiState.value = _uiState.value.copy(savedAreas = areas)
            }
        }

        viewModelScope.launch {
            val savedPrefs = preferencesRepository.userPreferencesFlow.first()
            val compositions = MapComposition.listFromJsonString(savedPrefs.savedCompositionsJson)
            val activeComp = compositions.find { it.id == savedPrefs.activeCompositionId }
                ?: MapCompositionDefaults.defaultComposition
            val resolvedStyle = MapSourceRepository.availableStyles.find { it.id == savedPrefs.selectedStyleId }
                ?: activeComp.baseStyle

            val currentUi = _uiState.value
            val resolvedInternet = if (hasLocalInternetOverride.get()) {
                currentUi.isInternetEnabled
            } else {
                savedPrefs.isInternetEnabled
            }
            AppNetworkPolicy.setInternetEnabled(resolvedInternet)

            val resolvedCamera = if (hasLocalCameraOverride.get()) {
                val curCam = currentUi.camera
                if (hasLocalNorthLockOverride.get()) {
                    curCam
                } else {
                    curCam.copy(isNorthLocked = savedPrefs.cameraState.isNorthLocked)
                }
            } else if (hasLocalNorthLockOverride.get()) {
                savedPrefs.cameraState.copy(isNorthLocked = currentUi.camera.isNorthLocked)
            } else {
                savedPrefs.cameraState
            }

            val latestHighlight = currentUi.categoryHighlightState
            val seededZoom = if (latestHighlight.lastViewport != null || hasLocalCameraOverride.get()) {
                latestHighlight.currentZoom
            } else {
                resolvedCamera.zoom
            }
            _uiState.value = currentUi.copy(
                camera = resolvedCamera,
                isTopPanelCollapsed = if (hasLocalTopPanelOverride.get()) currentUi.isTopPanelCollapsed else savedPrefs.isTopPanelCollapsed,
                isInternetEnabled = resolvedInternet,
                currentStyle = if (hasLocalStyleOverride.get()) currentUi.currentStyle else resolvedStyle,
                activeComposition = if (hasLocalCompositionOverride.get()) currentUi.activeComposition else activeComp,
                savedCompositions = compositions,
                hasLoadedPreferences = true,
                categoryHighlightState = latestHighlight.copy(
                    currentZoom = seededZoom,
                    isPausedByZoom = seededZoom < latestHighlight.minHighlightZoom
                )
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

    private fun launchPreferenceWrite(
        isCameraWrite: Boolean = false,
        block: suspend () -> Unit
    ): Job {
        val job = viewModelScope.launch {
            try {
                preferenceWriteMutex.withLock {
                    block()
                }
                if (!isCameraWrite) {
                    lastNonCameraWriteError = null
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (!isCameraWrite) {
                    lastNonCameraWriteError = t
                }
                DiagnosticRepository.log(
                    severity = LogSeverity.ERROR,
                    source = "MapViewModel",
                    description = "Preference write failed: ${t.message}",
                    stage = "PREF_WRITE_FAILED",
                    throwable = t
                )
            }
        }
        synchronized(pendingPrefJobsLock) {
            pendingPreferenceJobs.add(job)
        }
        job.invokeOnCompletion {
            synchronized(pendingPrefJobsLock) {
                pendingPreferenceJobs.remove(job)
            }
        }
        return job
    }

    /**
     * Ordered asynchronous shutdown flush executed before tearing down an outgoing Map session:
     * 1. Stop accepting new camera persistence updates from the outgoing session.
     * 2. Atomically capture the latest camera state and dirty flag.
     * 3. Await earlier queued preference writes so they settle and cannot overwrite the final camera state afterward.
     * 4. Persist the final camera state only if modified in this session (preserving existing saved camera on unmoved launches).
     * 5. Surface cancellation or IO persistence failures explicitly instead of silently succeeding.
     */
    suspend fun prepareAndFlushForDeactivation(): Result<Unit> {
        val finalCam: MapCameraState
        val needsCameraFlush: Boolean
        synchronized(cameraStateLock) {
            cameraPersistenceFrozen.set(true)
            finalCam = _uiState.value.camera
            needsCameraFlush = hasPendingCameraPersistence.get()
        }
        val jobsSnapshot = synchronized(pendingPrefJobsLock) {
            pendingPreferenceJobs.toList()
        }

        return try {
            if (jobsSnapshot.any { it.isActive }) {
                onAwaitingPendingPreferenceWritesForTesting?.invoke()
            }
            jobsSnapshot.forEach { job ->
                if (job.isActive) {
                    job.join()
                }
            }
            preferenceWriteMutex.withLock {
                withContext(Dispatchers.IO) {
                    val priorNonCameraError = lastNonCameraWriteError
                    if (priorNonCameraError != null) {
                        throw priorNonCameraError
                    }
                    if (needsCameraFlush) {
                        preferencesRepository.saveCameraState(
                            finalCam.latitude,
                            finalCam.longitude,
                            finalCam.zoom,
                            finalCam.bearing
                        )
                        synchronized(cameraStateLock) {
                            hasPendingCameraPersistence.set(false)
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            synchronized(cameraStateLock) {
                if (!isCleared) {
                    cameraPersistenceFrozen.set(false)
                }
            }
            throw ce
        } catch (t: Throwable) {
            synchronized(cameraStateLock) {
                if (!isCleared) {
                    cameraPersistenceFrozen.set(false)
                }
            }
            DiagnosticRepository.log(
                severity = LogSeverity.ERROR,
                source = "MapViewModel",
                description = "Camera/preference flush failed during deactivation: ${t.message}",
                stage = "SHUTDOWN_FLUSH_FAILED",
                throwable = t
            )
            Result.failure(t)
        }
    }

    fun onCameraMoved(lat: Double, lng: Double, zoom: Double, bearing: Double) {
        synchronized(cameraStateLock) {
            if (isCleared || cameraPersistenceFrozen.get()) {
                return
            }
            hasLocalCameraOverride.set(true)
            hasPendingCameraPersistence.set(true)
            val current = _uiState.value.camera
            val updated = current.copy(
                latitude = lat,
                longitude = lng,
                zoom = zoom,
                bearing = bearing
            )
            _uiState.value = _uiState.value.copy(camera = updated)
        }

        launchPreferenceWrite(isCameraWrite = true) {
            val target = synchronized(cameraStateLock) {
                if (cameraPersistenceFrozen.get() || isCleared || !hasPendingCameraPersistence.get()) {
                    null
                } else {
                    _uiState.value.camera
                }
            } ?: return@launchPreferenceWrite

            preferencesRepository.saveCameraState(
                target.latitude,
                target.longitude,
                target.zoom,
                target.bearing
            )

            synchronized(cameraStateLock) {
                if (!cameraPersistenceFrozen.get()) {
                    val latest = _uiState.value.camera
                    if (latest.latitude == target.latitude &&
                        latest.longitude == target.longitude &&
                        latest.zoom == target.zoom &&
                        latest.bearing == target.bearing
                    ) {
                        hasPendingCameraPersistence.set(false)
                    }
                }
            }
        }
    }

    fun toggleTopPanel() {
        hasLocalTopPanelOverride.set(true)
        val newCollapsed = !_uiState.value.isTopPanelCollapsed
        _uiState.value = _uiState.value.copy(isTopPanelCollapsed = newCollapsed)
        launchPreferenceWrite {
            preferencesRepository.saveTopPanelCollapsed(newCollapsed)
        }
    }

    fun toggleInternet() {
        hasLocalInternetOverride.set(true)
        val newInternet = !_uiState.value.isInternetEnabled
        AppNetworkPolicy.setInternetEnabled(newInternet)
        _uiState.value = _uiState.value.copy(isInternetEnabled = newInternet)
        launchPreferenceWrite {
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
        hasLocalNorthLockOverride.set(true)
        val newLocked = !_uiState.value.camera.isNorthLocked
        val updatedCamera = _uiState.value.camera.copy(
            isNorthLocked = newLocked,
            bearing = if (newLocked) 0.0 else _uiState.value.camera.bearing
        )
        _uiState.value = _uiState.value.copy(camera = updatedCamera)
        launchPreferenceWrite {
            preferencesRepository.saveNorthLocked(newLocked)
        }
    }

    fun selectStyle(style: MapStyleDefinition) {
        hasLocalStyleOverride.set(true)
        _uiState.value = _uiState.value.copy(currentStyle = style)
        DiagnosticRepository.log(
            severity = LogSeverity.INFO,
            source = "MapSourceRepository",
            description = "Selected style: ${style.displayName} (${style.providerName})",
            stage = "STYLE_SWITCH"
        )
        launchPreferenceWrite {
            preferencesRepository.saveSelectedStyleId(style.id)
        }
    }

    fun selectComposition(composition: MapComposition, controller: MapEngineController? = null) {
        hasLocalCompositionOverride.set(true)
        hasLocalStyleOverride.set(true)
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
        launchPreferenceWrite {
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

        launchPreferenceWrite {
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

        launchPreferenceWrite {
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

        launchPreferenceWrite {
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

        launchPreferenceWrite {
            val jsonString = MapComposition.listToJsonString(updatedList)
            preferencesRepository.saveSavedCompositionsJson(jsonString)
        }
    }

    fun refreshStorageReport() {
        _uiState.value = _uiState.value.copy(isCalculatingStorage = true)
        viewModelScope.launch {
            try {
                val report = storageService.calculateStorageReport()
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
                val result = storageService.clearProviderCache(providerId)
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

    fun openPoiHighlight(controller: MapEngineController? = null) {
        _uiState.value = _uiState.value.copy(
            activeModal = ActiveModal.PoiHighlight,
            settingsPopupOpen = false
        )
        controller?.dispatchCurrentViewportIfReady()
    }

    fun toggleHighlightCategory(
        categoryId: String,
        controller: MapEngineController? = null,
        debounceMs: Long = PoiCategoryHighlightDefaults.SELECTION_CHANGE_DEBOUNCE_MS
    ) {
        if (PoiHighlightCategory.fromId(categoryId) == null) return
        val current = _uiState.value.categoryHighlightState
        val baseSelected = if (current.isAllPoisSelected) emptySet() else current.selectedCategoryIds
        val nextSelected = if (categoryId in baseSelected) {
            baseSelected - categoryId
        } else {
            baseSelected + categoryId
        }

        if (nextSelected.isEmpty()) {
            clearAllHighlightCategories(controller)
            return
        }

        val paused = current.currentZoom < current.minHighlightZoom
        _uiState.value = _uiState.value.copy(
            categoryHighlightState = current.copy(
                selectedCategoryIds = nextSelected,
                isAllPoisSelected = false,
                isPausedByZoom = paused,
                matchedPois = if (paused || current.isAllPoisSelected) emptyList() else current.matchedPois,
                categoryMatchCounts = if (paused || current.isAllPoisSelected) emptyMap() else current.categoryMatchCounts,
                totalMatchingCount = if (paused || current.isAllPoisSelected) 0 else current.totalMatchingCount,
                isDisplayTruncated = false,
                displayLimit = null,
                isRefreshing = false,
                errorMessage = null
            )
        )

        if (paused) {
            categoryHighlightJob?.cancel()
            categoryQueryGeneration++
            controller?.updateCategoryHighlightedPois(emptyList(), isVisible = false)
            return
        }

        val viewport = current.lastViewport ?: return
        scheduleCategoryViewportRefresh(
            viewport = viewport,
            zoom = current.currentZoom,
            controller = controller,
            debounceMs = debounceMs
        )
    }

    fun toggleAllPoisHighlight(
        controller: MapEngineController? = null,
        debounceMs: Long = PoiCategoryHighlightDefaults.SELECTION_CHANGE_DEBOUNCE_MS
    ) {
        val current = _uiState.value.categoryHighlightState
        if (current.isAllPoisSelected) {
            clearAllHighlightCategories(controller)
            return
        }

        val paused = current.currentZoom < current.minHighlightZoom
        _uiState.value = _uiState.value.copy(
            categoryHighlightState = current.copy(
                selectedCategoryIds = emptySet(),
                isAllPoisSelected = true,
                isPausedByZoom = paused,
                matchedPois = emptyList(),
                categoryMatchCounts = emptyMap(),
                totalMatchingCount = 0,
                isDisplayTruncated = false,
                displayLimit = null,
                isRefreshing = false,
                errorMessage = null
            )
        )

        if (paused) {
            categoryHighlightJob?.cancel()
            categoryQueryGeneration++
            controller?.updateCategoryHighlightedPois(emptyList(), isVisible = false)
            return
        }

        val viewport = current.lastViewport ?: return
        scheduleCategoryViewportRefresh(
            viewport = viewport,
            zoom = current.currentZoom,
            controller = controller,
            debounceMs = debounceMs
        )
    }

    fun clearAllHighlightCategories(controller: MapEngineController? = null) {
        categoryHighlightJob?.cancel()
        categoryQueryGeneration++
        val current = _uiState.value.categoryHighlightState
        _uiState.value = _uiState.value.copy(
            categoryHighlightState = current.copy(
                selectedCategoryIds = emptySet(),
                isAllPoisSelected = false,
                matchedPois = emptyList(),
                categoryMatchCounts = emptyMap(),
                totalMatchingCount = 0,
                isDisplayTruncated = false,
                displayLimit = null,
                isRefreshing = false,
                hasQueriedViewport = false,
                isPausedByZoom = current.currentZoom < current.minHighlightZoom,
                errorMessage = null
            )
        )
        controller?.clearCategoryHighlights()
    }

    fun onCameraSettledForCategoryHighlight(
        bounds: PoiViewportBounds,
        zoom: Double,
        controller: MapEngineController? = null,
        debounceMs: Long = PoiCategoryHighlightDefaults.CAMERA_SETTLE_DEBOUNCE_MS
    ) {
        val current = _uiState.value.categoryHighlightState
        val paused = zoom < current.minHighlightZoom

        if (paused) {
            categoryHighlightJob?.cancel()
            categoryQueryGeneration++
            _uiState.value = _uiState.value.copy(
                categoryHighlightState = current.copy(
                    currentZoom = zoom,
                    lastViewport = bounds,
                    isPausedByZoom = true,
                    matchedPois = emptyList(),
                    categoryMatchCounts = emptyMap(),
                    totalMatchingCount = 0,
                    isDisplayTruncated = false,
                    displayLimit = null,
                    isRefreshing = false,
                    errorMessage = null
                )
            )
            controller?.updateCategoryHighlightedPois(emptyList(), isVisible = false)
            return
        }

        _uiState.value = _uiState.value.copy(
            categoryHighlightState = current.copy(
                currentZoom = zoom,
                lastViewport = bounds,
                isPausedByZoom = false
            )
        )

        if (!current.hasActiveSelection) {
            return
        }

        scheduleCategoryViewportRefresh(
            viewport = bounds,
            zoom = zoom,
            controller = controller,
            debounceMs = debounceMs
        )
    }

    fun onTilePoisIngested(
        controller: MapEngineController? = null,
        debounceMs: Long = PoiCategoryHighlightDefaults.TILE_INGESTION_COALESCE_MS
    ) {
        val current = _uiState.value.categoryHighlightState
        if (!current.hasActiveSelection) return
        if (current.isPausedByZoom || current.currentZoom < current.minHighlightZoom) return
        val viewport = current.lastViewport ?: return

        scheduleCategoryViewportRefresh(
            viewport = viewport,
            zoom = current.currentZoom,
            controller = controller,
            debounceMs = debounceMs
        )
    }

    fun setCategoryHighlightMinZoom(
        minZoom: Double,
        controller: MapEngineController? = null,
        debounceMs: Long = 0L
    ) {
        if (!minZoom.isFinite()) return
        val current = _uiState.value.categoryHighlightState
        val paused = current.currentZoom < minZoom
        _uiState.value = _uiState.value.copy(
            categoryHighlightState = current.copy(
                minHighlightZoom = minZoom,
                isPausedByZoom = paused
            )
        )
        val viewport = current.lastViewport
        if (paused || !current.hasActiveSelection) {
            categoryHighlightJob?.cancel()
            categoryQueryGeneration++
            _uiState.value = _uiState.value.copy(
                categoryHighlightState = _uiState.value.categoryHighlightState.copy(
                    matchedPois = emptyList(),
                    categoryMatchCounts = emptyMap(),
                    totalMatchingCount = 0,
                    isDisplayTruncated = false,
                    displayLimit = null,
                    isRefreshing = false
                )
            )
            controller?.updateCategoryHighlightedPois(emptyList(), isVisible = false)
        } else if (viewport != null) {
            scheduleCategoryViewportRefresh(
                viewport = viewport,
                zoom = current.currentZoom,
                controller = controller,
                debounceMs = debounceMs
            )
        }
    }

    private fun scheduleCategoryViewportRefresh(
        viewport: PoiViewportBounds,
        zoom: Double,
        controller: MapEngineController?,
        debounceMs: Long
    ) {
        categoryHighlightJob?.cancel()
        val requestGeneration = ++categoryQueryGeneration

        categoryHighlightJob = viewModelScope.launch {
            try {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                ensureActive()

                val latestBeforeQuery = _uiState.value.categoryHighlightState
                if (requestGeneration != categoryQueryGeneration ||
                    !latestBeforeQuery.hasActiveSelection ||
                    latestBeforeQuery.currentZoom < latestBeforeQuery.minHighlightZoom
                ) {
                    return@launch
                }

                if (latestBeforeQuery.isAllPoisSelected) {
                    _uiState.value = _uiState.value.copy(
                        categoryHighlightState = latestBeforeQuery.copy(
                            isRefreshing = true,
                            errorMessage = null
                        )
                    )

                    val result = osmRepository.queryAllHighlightedPois(bounds = viewport)
                    ensureActive()

                    val latestAfterQuery = _uiState.value.categoryHighlightState
                    if (requestGeneration != categoryQueryGeneration ||
                        !latestAfterQuery.isAllPoisSelected ||
                        latestAfterQuery.currentZoom < latestAfterQuery.minHighlightZoom
                    ) {
                        return@launch
                    }

                    _uiState.value = _uiState.value.copy(
                        categoryHighlightState = latestAfterQuery.copy(
                            matchedPois = result.items,
                            categoryMatchCounts = emptyMap(),
                            totalMatchingCount = result.totalMatchingCount,
                            isDisplayTruncated = result.isTruncated,
                            displayLimit = result.displayLimit,
                            isRefreshing = false,
                            hasQueriedViewport = true,
                            isPausedByZoom = false,
                            currentZoom = zoom,
                            lastViewport = viewport,
                            errorMessage = null
                        )
                    )
                    controller?.updateCategoryHighlightedPois(
                        pois = result.items,
                        isVisible = result.items.isNotEmpty()
                    )
                    return@launch
                }

                val selectedCategories = PoiHighlightCategory.entries.filter {
                    it.id in latestBeforeQuery.selectedCategoryIds
                }
                if (selectedCategories.isEmpty()) return@launch

                _uiState.value = _uiState.value.copy(
                    categoryHighlightState = latestBeforeQuery.copy(
                        isRefreshing = true,
                        errorMessage = null
                    )
                )

                val matches = osmRepository.queryCategoryHighlightedPois(
                    bounds = viewport,
                    selectedCategories = selectedCategories
                )
                ensureActive()

                val latestAfterQuery = _uiState.value.categoryHighlightState
                if (requestGeneration != categoryQueryGeneration ||
                    latestAfterQuery.isAllPoisSelected ||
                    latestAfterQuery.selectedCategoryIds.isEmpty() ||
                    latestAfterQuery.currentZoom < latestAfterQuery.minHighlightZoom
                ) {
                    return@launch
                }

                val counts = HashMap<String, Int>()
                for (item in matches) {
                    for (catId in item.matchedCategoryIds) {
                        counts[catId] = (counts[catId] ?: 0) + 1
                    }
                }

                _uiState.value = _uiState.value.copy(
                    categoryHighlightState = latestAfterQuery.copy(
                        matchedPois = matches,
                        categoryMatchCounts = counts,
                        totalMatchingCount = matches.size,
                        isDisplayTruncated = false,
                        displayLimit = null,
                        isRefreshing = false,
                        hasQueriedViewport = true,
                        isPausedByZoom = false,
                        currentZoom = zoom,
                        lastViewport = viewport,
                        errorMessage = null
                    )
                )
                controller?.updateCategoryHighlightedPois(
                    pois = matches,
                    isVisible = matches.isNotEmpty()
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (requestGeneration == categoryQueryGeneration) {
                    val latest = _uiState.value.categoryHighlightState
                    _uiState.value = _uiState.value.copy(
                        categoryHighlightState = latest.copy(
                            isRefreshing = false,
                            errorMessage = "Unable to query visible POIs: ${e.message ?: "Unknown error"}"
                        )
                    )
                }
            }
        }
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

    // =========================================================================
    // Phase 4.2: Area Details Popup, Saved Areas & Long-Press Context Actions
    // =========================================================================

    fun updateAnchoredScreenCoordinates(controller: MapEngineController) {
        val currentArea = _uiState.value.areaDetailsState
        val currentCtx = _uiState.value.mapContextMenuState
        if (currentArea == null && currentCtx == null) return

        val nextArea = currentArea?.let { area ->
            val projected = controller.projectToScreen(area.latitude, area.longitude)
            if (projected != null) {
                area.copy(screenX = projected.x, screenY = projected.y)
            } else {
                area
            }
        }
        val nextCtx = currentCtx?.let { ctx ->
            val projected = controller.projectToScreen(ctx.latitude, ctx.longitude)
            if (projected != null) {
                ctx.copy(screenX = projected.x, screenY = projected.y)
            } else {
                ctx
            }
        }
        _uiState.value = _uiState.value.copy(
            areaDetailsState = nextArea,
            mapContextMenuState = nextCtx
        )
    }

    fun onEmptyMapSingleTap(
        latitude: Double,
        longitude: Double,
        controller: MapEngineController? = null
    ) {
        if (!RoutePoint(latitude, longitude).isValid()) return
        val current = _uiState.value

        // If a context menu is open, a tap on the map cleanly dismisses it without accidentally jumping to another popup
        if (current.mapContextMenuState != null) {
            dismissMapContextMenu(controller)
            return
        }

        // Priority guard: do not hijack active modal/draft/pick/editor workflows
        if (current.routeDraft.isActive ||
            current.isPickingPlacemarkLocation ||
            current.activeModal != null ||
            current.settingsPopupOpen ||
            current.placemarkEditorState != null ||
            current.routeMetadataEditorTarget != null ||
            current.savedAreaEditorState != null ||
            current.plannedActionNotice != null
        ) {
            return
        }

        openAreaDetailsAt(
            latitude = latitude,
            longitude = longitude,
            controller = controller,
            moveCamera = false,
            forceOnlineRefresh = false
        )
    }

    fun openAreaDetailsAt(
        latitude: Double,
        longitude: Double,
        controller: MapEngineController? = null,
        moveCamera: Boolean = false,
        forceOnlineRefresh: Boolean = false
    ) {
        if (!RoutePoint(latitude, longitude).isValid()) return

        areaLookupJob?.cancel()
        contextLookupJob?.cancel()

        controller?.setAreaSelectionPin(latitude, longitude)
        if (moveCamera) {
            controller?.centerOnLocation(latitude, longitude, 16.0)
        }

        val screenPos = controller?.projectToScreen(latitude, longitude)
        val allowOnline = isNetworkLookupPermitted()

        _uiState.value = _uiState.value.copy(
            activeModal = null,
            settingsPopupOpen = false,
            mapContextMenuState = null,
            selectedPlacemark = null,
            selectedRoute = null,
            areaDetailsState = AreaDetailsUiState(
                latitude = latitude,
                longitude = longitude,
                screenX = screenPos?.x,
                screenY = screenPos?.y,
                isExpanded = true,
                details = AreaDetailsResult(
                    latitude = latitude,
                    longitude = longitude,
                    isLoadingOnline = allowOnline,
                    isOffline = !allowOnline
                )
            )
        )
        controller?.updateUserPlacemarks(_uiState.value.placemarks, null)
        controller?.updateSavedRoutes(_uiState.value.savedRoutes, null)

        areaLookupJob = viewModelScope.launch {
            try {
                val localSnapshot = areaRepository.queryLocalSnapshot(latitude, longitude)
                val activeCheck = _uiState.value.areaDetailsState ?: return@launch
                if (activeCheck.latitude != latitude || activeCheck.longitude != longitude) return@launch

                val shouldRunOnline = forceOnlineRefresh || !localSnapshot.isSavedSnapshot
                val isLoading = shouldRunOnline && allowOnline
                val preSelected = localSnapshot.nearbyPlaces.filter { it.selectedForSave }.map { it.id }.toSet()

                _uiState.value = _uiState.value.copy(
                    areaDetailsState = _uiState.value.areaDetailsState?.copy(
                        details = localSnapshot.copy(
                            isLoadingOnline = isLoading,
                            isOffline = !allowOnline && shouldRunOnline
                        ),
                        selectedNearbyIdsForSave = preSelected,
                        userNotesDraft = localSnapshot.userNotes,
                        customNameDraft = localSnapshot.locationIdentity?.primaryName.orEmpty()
                    )
                )

                if (!shouldRunOnline) {
                    return@launch
                }

                val enriched = areaRepository.enrichWithOnlineData(
                    localBaseline = localSnapshot,
                    isOnlineAllowed = allowOnline,
                    forceRefresh = forceOnlineRefresh
                )

                val postCheck = _uiState.value.areaDetailsState ?: return@launch
                if (postCheck.latitude != latitude || postCheck.longitude != longitude) return@launch

                val updatedSelectedIds = (postCheck.selectedNearbyIdsForSave +
                    enriched.nearbyPlaces.filter { it.selectedForSave }.map { it.id }).toSet()

                _uiState.value = _uiState.value.copy(
                    areaDetailsState = postCheck.copy(
                        details = enriched,
                        selectedNearbyIdsForSave = updatedSelectedIds,
                        customNameDraft = postCheck.customNameDraft.ifBlank {
                            enriched.locationIdentity?.primaryName.orEmpty()
                        }
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                val active = _uiState.value.areaDetailsState ?: return@launch
                _uiState.value = _uiState.value.copy(
                    areaDetailsState = active.copy(
                        details = active.details.copy(
                            isLoadingOnline = false,
                            errorMessage = e.message ?: "Unable to resolve area details."
                        )
                    )
                )
            }
        }
    }

    fun dismissAreaDetails(controller: MapEngineController? = null) {
        areaLookupJob?.cancel()
        areaLookupJob = null
        _uiState.value = _uiState.value.copy(areaDetailsState = null)
        if (_uiState.value.mapContextMenuState == null) {
            controller?.clearAreaSelectionPin()
        }
    }

    fun toggleAreaDetailsExpanded() {
        val current = _uiState.value.areaDetailsState ?: return
        _uiState.value = _uiState.value.copy(
            areaDetailsState = current.copy(isExpanded = !current.isExpanded)
        )
    }

    fun toggleAreaNearbyItemForSave(itemId: String) {
        val current = _uiState.value.areaDetailsState ?: return
        val nextSet = if (current.selectedNearbyIdsForSave.contains(itemId)) {
            current.selectedNearbyIdsForSave - itemId
        } else {
            current.selectedNearbyIdsForSave + itemId
        }
        _uiState.value = _uiState.value.copy(
            areaDetailsState = current.copy(selectedNearbyIdsForSave = nextSet)
        )
    }

    fun updateAreaCustomNameDraft(name: String) {
        val current = _uiState.value.areaDetailsState ?: return
        _uiState.value = _uiState.value.copy(
            areaDetailsState = current.copy(customNameDraft = name)
        )
    }

    fun updateAreaNotesDraft(notes: String) {
        val current = _uiState.value.areaDetailsState ?: return
        _uiState.value = _uiState.value.copy(
            areaDetailsState = current.copy(userNotesDraft = notes)
        )
    }

    fun saveCurrentAreaDetails(
        customName: String? = null,
        userNotes: String? = null
    ) {
        val current = _uiState.value.areaDetailsState ?: return
        viewModelScope.launch {
            val resolvedName = (customName ?: current.customNameDraft).trim().takeIf { it.isNotEmpty() }
            val resolvedNotes = userNotes ?: current.userNotesDraft
            val outcome = areaRepository.saveAreaSnapshot(
                result = current.details,
                customName = resolvedName,
                selectedNearbyIds = current.selectedNearbyIdsForSave,
                userNotes = resolvedNotes
            )
            outcome.fold(
                onSuccess = { savedEntity ->
                    val reloaded = areaRepository.queryLocalSnapshot(savedEntity.latitude, savedEntity.longitude)
                    val refreshedCurrent = _uiState.value.areaDetailsState
                    if (refreshedCurrent != null &&
                        refreshedCurrent.latitude == current.latitude &&
                        refreshedCurrent.longitude == current.longitude
                    ) {
                        _uiState.value = _uiState.value.copy(
                            areaDetailsState = refreshedCurrent.copy(
                                details = reloaded,
                                selectedNearbyIdsForSave = reloaded.nearbyPlaces.map { it.id }.toSet(),
                                userNotesDraft = savedEntity.userNotes,
                                customNameDraft = savedEntity.name
                            ),
                            userFeedbackMessage = "Saved area '${savedEntity.name}' offline"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            userFeedbackMessage = "Saved area '${savedEntity.name}' offline"
                        )
                    }
                    refreshStorageReport()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        userFeedbackMessage = err.message ?: "Unable to save area offline."
                    )
                }
            )
        }
    }

    fun refreshAreaDetailsOnline(controller: MapEngineController? = null) {
        val current = _uiState.value.areaDetailsState ?: return
        if (!isNetworkLookupPermitted()) {
            _uiState.value = _uiState.value.copy(
                areaDetailsState = current.copy(
                    details = current.details.copy(
                        isLoadingOnline = false,
                        isOffline = true,
                        partialWarning = "Internet is off or device is offline. Existing data preserved."
                    )
                ),
                userFeedbackMessage = "Cannot refresh online: Internet is offline or blocked."
            )
            return
        }
        val savedId = current.details.savedAreaId
        if (current.details.isSavedSnapshot && !savedId.isNullOrBlank()) {
            areaLookupJob?.cancel()
            _uiState.value = _uiState.value.copy(
                areaDetailsState = current.copy(
                    details = current.details.copy(isLoadingOnline = true, partialWarning = null)
                )
            )
            areaLookupJob = viewModelScope.launch {
                val result = areaRepository.refreshSavedAreaOnline(savedId, isOnlineAllowed = true)
                result.fold(
                    onSuccess = { updated ->
                        val reloaded = areaRepository.queryLocalSnapshot(updated.latitude, updated.longitude)
                        val active = _uiState.value.areaDetailsState
                        if (active != null && active.details.savedAreaId == savedId) {
                            _uiState.value = _uiState.value.copy(
                                areaDetailsState = active.copy(
                                    details = reloaded.copy(isLoadingOnline = false),
                                    customNameDraft = updated.name,
                                    userNotesDraft = updated.userNotes
                                ),
                                userFeedbackMessage = "Refreshed '${updated.name}' online"
                            )
                        }
                    },
                    onFailure = { err ->
                        val active = _uiState.value.areaDetailsState
                        if (active != null && active.details.savedAreaId == savedId) {
                            _uiState.value = _uiState.value.copy(
                                areaDetailsState = active.copy(
                                    details = active.details.copy(
                                        isLoadingOnline = false,
                                        partialWarning = err.message ?: "Refresh failed; existing saved snapshot preserved."
                                    )
                                ),
                                userFeedbackMessage = err.message ?: "Refresh failed; existing saved snapshot preserved."
                            )
                        }
                    }
                )
            }
            return
        }
        openAreaDetailsAt(
            latitude = current.latitude,
            longitude = current.longitude,
            controller = controller,
            moveCamera = false,
            forceOnlineRefresh = true
        )
    }

    fun openSavedAreaOnMap(area: SavedAreaEntity, controller: MapEngineController? = null) {
        areaLookupJob?.cancel()
        contextLookupJob?.cancel()
        controller?.centerOnLocation(area.latitude, area.longitude, 16.0)
        controller?.setAreaSelectionPin(area.latitude, area.longitude)
        val screenPos = controller?.projectToScreen(area.latitude, area.longitude)

        _uiState.value = _uiState.value.copy(
            activeModal = null,
            settingsPopupOpen = false,
            mapContextMenuState = null,
            selectedPlacemark = null,
            selectedRoute = null
        )

        areaLookupJob = viewModelScope.launch {
            val snapshot = areaRepository.queryLocalSnapshot(area.latitude, area.longitude)
            _uiState.value = _uiState.value.copy(
                areaDetailsState = AreaDetailsUiState(
                    latitude = area.latitude,
                    longitude = area.longitude,
                    screenX = screenPos?.x,
                    screenY = screenPos?.y,
                    isExpanded = true,
                    details = snapshot,
                    selectedNearbyIdsForSave = snapshot.nearbyPlaces.map { it.id }.toSet(),
                    userNotesDraft = area.userNotes,
                    customNameDraft = area.name
                )
            )
        }
    }

    fun openSavedAreaEditor(area: SavedAreaEntity) {
        _uiState.value = _uiState.value.copy(
            savedAreaEditorState = SavedAreaEditorState(
                areaId = area.id,
                name = area.name,
                category = area.category,
                addressLabel = area.addressLabel,
                userNotes = area.userNotes,
                tagsText = area.tagsCsv
            )
        )
    }

    fun dismissSavedAreaEditor() {
        _uiState.value = _uiState.value.copy(savedAreaEditorState = null)
    }

    fun saveEditedAreaMetadata(
        areaId: String,
        name: String,
        category: String,
        addressLabel: String,
        userNotes: String,
        tagsText: String
    ) {
        viewModelScope.launch {
            val result = areaRepository.updateSavedAreaMetadata(
                areaId = areaId,
                name = name,
                category = category,
                addressLabel = addressLabel,
                userNotes = userNotes,
                tagsText = tagsText
            )
            result.fold(
                onSuccess = { updated ->
                    val active = _uiState.value.areaDetailsState
                    val refreshedAreaDetails = if (active?.details?.savedAreaId == updated.id) {
                        val reloaded = areaRepository.queryLocalSnapshot(updated.latitude, updated.longitude)
                        active.copy(
                            details = reloaded,
                            userNotesDraft = updated.userNotes,
                            customNameDraft = updated.name
                        )
                    } else {
                        active
                    }
                    _uiState.value = _uiState.value.copy(
                        savedAreaEditorState = null,
                        areaDetailsState = refreshedAreaDetails,
                        userFeedbackMessage = "Updated saved area '${updated.name}'"
                    )
                },
                onFailure = { err ->
                    val currentEditor = _uiState.value.savedAreaEditorState ?: return@fold
                    _uiState.value = _uiState.value.copy(
                        savedAreaEditorState = currentEditor.copy(
                            name = name,
                            category = category,
                            addressLabel = addressLabel,
                            userNotes = userNotes,
                            tagsText = tagsText,
                            errorMessage = err.message ?: "Unable to update saved area."
                        )
                    )
                }
            )
        }
    }

    fun refreshSavedAreaFromWorkspace(areaId: String) {
        viewModelScope.launch {
            val allowed = isNetworkLookupPermitted()
            val result = areaRepository.refreshSavedAreaOnline(areaId, isOnlineAllowed = allowed)
            result.fold(
                onSuccess = { updated ->
                    val active = _uiState.value.areaDetailsState
                    val nextActive = if (active?.details?.savedAreaId == updated.id) {
                        val reloaded = areaRepository.queryLocalSnapshot(updated.latitude, updated.longitude)
                        active.copy(
                            details = reloaded,
                            customNameDraft = updated.name,
                            userNotesDraft = updated.userNotes
                        )
                    } else {
                        active
                    }
                    _uiState.value = _uiState.value.copy(
                        areaDetailsState = nextActive,
                        userFeedbackMessage = "Refreshed '${updated.name}' online"
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        userFeedbackMessage = err.message ?: "Unable to refresh saved area online."
                    )
                }
            )
        }
    }

    fun deleteSavedArea(areaId: String, controller: MapEngineController? = null) {
        viewModelScope.launch {
            val deleted = areaRepository.deleteSavedArea(areaId)
            if (deleted) {
                val active = _uiState.value.areaDetailsState
                val nextActive = if (active?.details?.savedAreaId == areaId) {
                    val reloaded = areaRepository.queryLocalSnapshot(active.latitude, active.longitude)
                    active.copy(details = reloaded)
                } else {
                    active
                }
                _uiState.value = _uiState.value.copy(
                    areaDetailsState = nextActive,
                    savedAreaEditorState = if (_uiState.value.savedAreaEditorState?.areaId == areaId) null else _uiState.value.savedAreaEditorState,
                    userFeedbackMessage = "Saved area deleted"
                )
                refreshStorageReport()
            }
        }
    }

    // --- Long-Press Map Context Menu & Actions ---

    fun onMapLongPressed(
        latitude: Double,
        longitude: Double,
        controller: MapEngineController? = null
    ) {
        if (!RoutePoint(latitude, longitude).isValid()) return
        val current = _uiState.value
        if (current.routeDraft.isActive ||
            current.isPickingPlacemarkLocation ||
            current.activeModal != null ||
            current.settingsPopupOpen ||
            current.placemarkEditorState != null ||
            current.routeMetadataEditorTarget != null ||
            current.savedAreaEditorState != null ||
            current.plannedActionNotice != null
        ) {
            return
        }

        areaLookupJob?.cancel()
        contextLookupJob?.cancel()
        controller?.setAreaSelectionPin(latitude, longitude)
        val screenPos = controller?.projectToScreen(latitude, longitude)

        _uiState.value = _uiState.value.copy(
            areaDetailsState = null,
            mapContextMenuState = MapContextMenuUiState(
                latitude = latitude,
                longitude = longitude,
                screenX = screenPos?.x,
                screenY = screenPos?.y,
                isResolvingMatches = true
            )
        )

        contextLookupJob = viewModelScope.launch {
            try {
                val matches = areaRepository.findContextMatchesAt(latitude, longitude)
                val activeCtx = _uiState.value.mapContextMenuState ?: return@launch
                if (activeCtx.latitude == latitude && activeCtx.longitude == longitude) {
                    _uiState.value = _uiState.value.copy(
                        mapContextMenuState = activeCtx.copy(
                            isResolvingMatches = false,
                            matches = matches
                        )
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                val activeCtx = _uiState.value.mapContextMenuState ?: return@launch
                _uiState.value = _uiState.value.copy(
                    mapContextMenuState = activeCtx.copy(isResolvingMatches = false)
                )
            }
        }
    }

    fun dismissMapContextMenu(controller: MapEngineController? = null) {
        contextLookupJob?.cancel()
        contextLookupJob = null
        _uiState.value = _uiState.value.copy(mapContextMenuState = null)
        if (_uiState.value.areaDetailsState == null) {
            controller?.clearAreaSelectionPin()
        }
    }

    fun contextOpenExistingSavedArea(controller: MapEngineController? = null) {
        val area = _uiState.value.mapContextMenuState?.matches?.savedArea ?: return
        openSavedAreaOnMap(area, controller)
    }

    fun contextOpenExistingPlacemark(controller: MapEngineController? = null) {
        val place = _uiState.value.mapContextMenuState?.matches?.placemark ?: return
        _uiState.value = _uiState.value.copy(mapContextMenuState = null)
        controller?.clearAreaSelectionPin()
        selectPlacemark(place, controller)
    }

    fun contextOpenExistingRoute(controller: MapEngineController? = null) {
        val route = _uiState.value.mapContextMenuState?.matches?.route ?: return
        _uiState.value = _uiState.value.copy(mapContextMenuState = null)
        controller?.clearAreaSelectionPin()
        selectSavedRoute(route, controller)
    }

    fun contextAddPlacemarkHere(controller: MapEngineController? = null) {
        val ctx = _uiState.value.mapContextMenuState ?: return
        controller?.clearAreaSelectionPin()
        val suggestedName = AreaCoordinateFormatter.suggestedLabelFromMatch(ctx.matches)
        val suggestedAddr = AreaCoordinateFormatter.suggestedAddressFromSavedArea(ctx.matches.savedArea)
        _uiState.value = _uiState.value.copy(
            mapContextMenuState = null,
            isPickingPlacemarkLocation = false,
            placemarkEditorState = PlacemarkEditorState(
                editingPlaceId = null,
                name = suggestedName,
                addressLabel = suggestedAddr,
                latitude = ctx.latitude,
                longitude = ctx.longitude,
                nearbyDuplicatePlace = ctx.matches.placemark
            )
        )
    }

    fun contextOpenAreaDetails(controller: MapEngineController? = null) {
        val ctx = _uiState.value.mapContextMenuState ?: return
        openAreaDetailsAt(
            latitude = ctx.latitude,
            longitude = ctx.longitude,
            controller = controller,
            moveCamera = false,
            forceOnlineRefresh = false
        )
    }

    fun contextWikiSearchArea(controller: MapEngineController? = null) {
        val ctx = _uiState.value.mapContextMenuState ?: return
        openAreaDetailsAt(
            latitude = ctx.latitude,
            longitude = ctx.longitude,
            controller = controller,
            moveCamera = false,
            forceOnlineRefresh = isNetworkLookupPermitted()
        )
    }

    fun contextRouteFromHere(controller: MapEngineController? = null) {
        val ctx = _uiState.value.mapContextMenuState ?: return
        val origin = RoutePoint(ctx.latitude, ctx.longitude)
        if (!origin.isValid()) return
        controller?.clearAreaSelectionPin()
        val startPoints = listOf(origin)
        _uiState.value = _uiState.value.copy(
            mapContextMenuState = null,
            areaDetailsState = null,
            selectedPlacemark = null,
            selectedRoute = null,
            routeDraft = RouteDraftUiState(
                isActive = true,
                points = startPoints,
                waypointNamesByPointIndex = mapOf(0 to "Start")
            ),
            userFeedbackMessage = "Route started at pressed location. Tap map to add points."
        )
        controller?.updateRouteDraftOverlay(startPoints)
    }

    fun contextRouteToHere(controller: MapEngineController? = null) {
        val ctx = _uiState.value.mapContextMenuState ?: return
        val destination = RoutePoint(ctx.latitude, ctx.longitude)
        if (!destination.isValid()) return
        controller?.clearAreaSelectionPin()
        val cam = _uiState.value.camera
        val centerOrigin = RoutePoint(cam.latitude, cam.longitude)
        val distanceFromCenter = if (centerOrigin.isValid()) {
            RouteGeometryCodec.haversineMeters(
                centerOrigin.latitude,
                centerOrigin.longitude,
                destination.latitude,
                destination.longitude
            )
        } else {
            0.0
        }

        val draftPoints: List<RoutePoint>
        val waypointMap: Map<Int, String>
        val msg: String
        if (centerOrigin.isValid() && distanceFromCenter >= 25.0) {
            draftPoints = listOf(centerOrigin, destination)
            waypointMap = mapOf(0 to "Origin", 1 to "Destination")
            msg = "Draft route created from map center to destination."
        } else {
            draftPoints = listOf(destination)
            waypointMap = mapOf(0 to "Destination")
            msg = "Destination set. Tap another map point to complete route."
        }

        _uiState.value = _uiState.value.copy(
            mapContextMenuState = null,
            areaDetailsState = null,
            selectedPlacemark = null,
            selectedRoute = null,
            routeDraft = RouteDraftUiState(
                isActive = true,
                points = draftPoints,
                waypointNamesByPointIndex = waypointMap
            ),
            userFeedbackMessage = msg
        )
        controller?.updateRouteDraftOverlay(draftPoints)
    }

    fun contextShowPlannedAction(
        title: String,
        featureGroup: String,
        summary: String,
        plannedBehavior: String,
        controller: MapEngineController? = null
    ) {
        val ctx = _uiState.value.mapContextMenuState ?: return
        controller?.clearAreaSelectionPin()
        _uiState.value = _uiState.value.copy(
            mapContextMenuState = null,
            plannedActionNotice = PlannedActionNoticeState(
                title = title,
                featureGroup = featureGroup,
                latitude = ctx.latitude,
                longitude = ctx.longitude,
                summary = summary,
                plannedBehavior = plannedBehavior
            )
        )
    }

    fun dismissPlannedActionNotice() {
        _uiState.value = _uiState.value.copy(plannedActionNotice = null)
    }

    override fun onCleared() {
        isCleared = true
        cameraPersistenceFrozen.set(true)
        super.onCleared()
        searchJob?.cancel()
        categoryHighlightJob?.cancel()
        areaLookupJob?.cancel()
        contextLookupJob?.cancel()
        networkMonitor.unregister()
    }
}
