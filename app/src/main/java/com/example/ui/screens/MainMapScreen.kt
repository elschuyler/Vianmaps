package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.map.engine.MapEngineStatus
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.map.engine.MapEngineController
import com.example.ui.components.AppModalManager
import com.example.ui.components.AreaDetailsPopup
import com.example.ui.components.BottomNavigationBar
import com.example.ui.components.MapContextMenu
import com.example.ui.components.PickLocationHudBanner
import com.example.ui.components.PlacemarkEditorDialog
import com.example.ui.components.PlannedActionNoticeDialog
import com.example.ui.components.RightSideMapControls
import com.example.ui.components.RouteCreationHudBar
import com.example.ui.components.RouteMetadataEditorDialog
import com.example.ui.components.SaveRouteDialog
import com.example.ui.components.SavedAreaEditorDialog
import com.example.ui.components.SelectedPlacemarkBanner
import com.example.ui.components.SelectedPoiHighlightBanner
import com.example.ui.components.SelectedRouteBanner
import com.example.ui.components.TopCoordinateInfoPanel
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

@Composable
fun MainMapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDeviceOnline by viewModel.networkMonitor.isDeviceOnline.collectAsStateWithLifecycle()
    val connectionType by viewModel.networkMonitor.connectionType.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Remember MapEngineController across recompositions
    val controller = remember {
        MapEngineController(
            context = context,
            initialLat = uiState.camera.latitude,
            initialLng = uiState.camera.longitude,
            initialZoom = uiState.camera.zoom,
            initialBearing = uiState.camera.bearing,
            onCameraMoved = { lat, lng, zoom, bearing ->
                viewModel.onCameraMoved(lat, lng, zoom, bearing)
            }
        )
    }

    // Handle back button for modals:
    // Back from Map Catalogue returns to More Settings
    // Back from Log Keeper, More Settings, Backup & Restore returns directly to Map
    // Back while compact popup is open dismisses the popup
    BackHandler(
        enabled = uiState.savedAreaEditorState != null ||
            uiState.plannedActionNotice != null ||
            uiState.placemarkEditorState != null ||
            uiState.routeMetadataEditorTarget != null ||
            uiState.routeDraft.showSaveDialog ||
            uiState.routeDraft.isActive ||
            uiState.isPickingPlacemarkLocation ||
            uiState.mapContextMenuState != null ||
            uiState.areaDetailsState != null ||
            uiState.activeModal != null ||
            uiState.settingsPopupOpen
    ) {
        when {
            uiState.savedAreaEditorState != null -> viewModel.dismissSavedAreaEditor()
            uiState.plannedActionNotice != null -> viewModel.dismissPlannedActionNotice()
            uiState.placemarkEditorState != null -> viewModel.dismissPlacemarkEditor()
            uiState.routeMetadataEditorTarget != null -> viewModel.dismissRouteMetadataEditor()
            uiState.routeDraft.showSaveDialog -> viewModel.dismissSaveRouteDialog()
            uiState.routeDraft.isActive -> viewModel.cancelRouteDraft(controller)
            uiState.isPickingPlacemarkLocation -> viewModel.cancelPickPlacemarkOnMap()
            uiState.mapContextMenuState != null -> viewModel.dismissMapContextMenu(controller)
            uiState.areaDetailsState != null -> viewModel.dismissAreaDetails(controller)
            uiState.settingsPopupOpen -> viewModel.closeSettingsPopup()
            uiState.activeModal is ActiveModal.MapCatalogue -> viewModel.openModal(ActiveModal.MoreSettings)
            else -> viewModel.closeModal()
        }
    }

    // Permission launcher for location - only when requested explicitly
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setLocationPermissionGranted(isGranted)
        if (!isGranted) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Location permission denied. Map functions without location tracking.")
            }
        }
    }

    // Sync North Lock setting with controller
    LaunchedEffect(uiState.camera.isNorthLocked) {
        controller.setNorthLock(uiState.camera.isNorthLocked)
    }

    // Sync style change
    LaunchedEffect(uiState.currentStyle.id) {
        if (controller.maplibreMap != null) {
            controller.loadStyle(uiState.currentStyle) { status, desc, err ->
                viewModel.onMapStatusChanged(status, desc, err)
            }
        }
    }

    // Keep MapEngineController highlight state in sync with ViewModel across recompositions/rotation
    LaunchedEffect(uiState.highlightedPoi?.osmId, uiState.isPoiHighlightVisible) {
        controller.syncHighlightedPoiState(uiState.highlightedPoi, uiState.isPoiHighlightVisible)
    }

    // Keep MapEngineController user placemarks, saved routes, and active route draft synced
    LaunchedEffect(uiState.placemarks, uiState.selectedPlacemark?.id) {
        controller.updateUserPlacemarks(uiState.placemarks, uiState.selectedPlacemark?.id)
    }

    LaunchedEffect(uiState.savedRoutes, uiState.selectedRoute?.route?.id) {
        controller.updateSavedRoutes(uiState.savedRoutes, uiState.selectedRoute?.route?.id)
    }

    LaunchedEffect(uiState.routeDraft.points, uiState.routeDraft.isActive) {
        if (uiState.routeDraft.isActive) {
            controller.updateRouteDraftOverlay(uiState.routeDraft.points)
        } else {
            controller.updateRouteDraftOverlay(emptyList())
        }
    }

    LaunchedEffect(uiState.camera.latitude, uiState.camera.longitude, uiState.camera.zoom, uiState.camera.bearing) {
        if (uiState.areaDetailsState != null || uiState.mapContextMenuState != null) {
            viewModel.updateAnchoredScreenCoordinates(controller)
        }
    }

    // Wire interactive map tap, long-press, placemark tap, route tap, and POI tap handlers without recreating MapView
    LaunchedEffect(controller) {
        controller.onMapTap = { lat, lng ->
            val current = viewModel.uiState.value
            when {
                current.routeDraft.isActive && !current.routeDraft.showSaveDialog -> {
                    viewModel.addRouteDraftPoint(lat, lng, controller)
                    true
                }
                current.isPickingPlacemarkLocation -> {
                    viewModel.onMapCoordinatePickedForPlacemark(lat, lng)
                    true
                }
                else -> false
            }
        }
        controller.onEmptyMapSingleTap = { lat, lng ->
            viewModel.onEmptyMapSingleTap(lat, lng, controller)
        }
        controller.onMapLongPress = { lat, lng ->
            viewModel.onMapLongPressed(lat, lng, controller)
        }
        controller.onPlacemarkTapped = { placeId ->
            viewModel.dismissAreaDetails(controller)
            viewModel.dismissMapContextMenu(controller)
            viewModel.selectPlacemarkById(placeId, controller)
        }
        controller.onRouteTapped = { routeId ->
            viewModel.dismissAreaDetails(controller)
            viewModel.dismissMapContextMenu(controller)
            viewModel.selectSavedRouteById(routeId, controller)
        }
        controller.onHighlightedPoiTapped = {
            viewModel.dismissAreaDetails(controller)
            viewModel.dismissMapContextMenu(controller)
            viewModel.recenterOnHighlightedPoi(controller)
        }
    }

    // Surface one-shot feedback messages cleanly via Snackbar
    val feedbackMessage = uiState.userFeedbackMessage
    LaunchedEffect(feedbackMessage) {
        if (!feedbackMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(feedbackMessage)
            viewModel.consumeUserFeedbackMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { _ ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Create and retain exactly one MapView instance across all recompositions
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

            val mapView = remember {
                val mv = MapView(context)
                val mvId = System.identityHashCode(mv)
                DiagnosticRepository.log(
                    severity = LogSeverity.INFO,
                    source = "MainMapScreen",
                    description = "MapView instantiated (id=#$mvId)",
                    stage = "MAPVIEW_CREATED"
                )

                // Attach listeners BEFORE onCreate and getMapAsync
                var hasLoggedFirstFrame = false

                mv.addOnWillStartLoadingMapListener {
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MapLibreEngine",
                        description = "Will start loading map (MapView#$mvId)",
                        stage = "WILL_START_LOADING_MAP"
                    )
                }

                // Stage 10: Style loaded successfully (listener callback)
                mv.addOnDidFinishLoadingStyleListener {
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MapLibreEngine",
                        description = "Style loaded successfully (MapView#$mvId)",
                        stage = "STYLE_LOADED"
                    )
                    viewModel.onMapStatusChanged(MapEngineStatus.STYLE_READY, "Style Loaded", null)
                }

                // Stage 11: First rendered frame received
                mv.addOnDidFinishRenderingFrameListener { _, _, _ ->
                    if (!hasLoggedFirstFrame) {
                        hasLoggedFirstFrame = true
                        DiagnosticRepository.log(
                            severity = LogSeverity.INFO,
                            source = "MapLibreEngine",
                            description = "First rendered frame received (MapView#$mvId)",
                            stage = "FIRST_FRAME_RENDERED"
                        )
                        viewModel.onMapStatusChanged(MapEngineStatus.RENDERING, "First frame rendered", null)
                    }
                }

                // Stage 12: Map fully rendered
                mv.addOnDidFinishRenderingMapListener { fullyRendered ->
                    if (fullyRendered) {
                        DiagnosticRepository.log(
                            severity = LogSeverity.INFO,
                            source = "MapLibreEngine",
                            description = "Map tiles and layers fully rendered (MapView#$mvId)",
                            stage = "FULLY_RENDERED"
                        )
                        viewModel.onMapStatusChanged(MapEngineStatus.FULLY_RENDERED, "Fully Rendered", null)
                    }
                }

                // Stage 13: Failure listeners
                mv.addOnDidFailLoadingMapListener { error ->
                    val msg = "Map load failed: $error (MapView#$mvId)"
                    DiagnosticRepository.log(
                        severity = LogSeverity.ERROR,
                        source = "MapLibreEngine",
                        description = msg,
                        stage = "LOAD_FAILED"
                    )
                    viewModel.onMapStatusChanged(MapEngineStatus.FAILED, "Map Load Failed", msg)
                }

                mv.addOnRenderErrorListener {
                    val msg = "Rendering frame error in native GL pipeline (MapView#$mvId)"
                    DiagnosticRepository.log(
                        severity = LogSeverity.ERROR,
                        source = "MapLibreEngine",
                        description = msg,
                        stage = "RENDER_ERROR"
                    )
                    viewModel.onMapStatusChanged(MapEngineStatus.FAILED, "Render Error", msg)
                }

                mv.addOnShaderCompileFailedListener { program, type, error ->
                    val msg = "Shader compile failed (prog: $program, type: $type): $error (MapView#$mvId)"
                    DiagnosticRepository.log(
                        severity = LogSeverity.ERROR,
                        source = "MapLibreEngine",
                        description = msg,
                        stage = "SHADER_ERROR"
                    )
                    viewModel.onMapStatusChanged(MapEngineStatus.FAILED, "Shader Compile Failed", msg)
                }

                mv.addOnGlyphsErrorListener { fontStack, rangeStart, rangeEnd ->
                    val msg = "Glyphs missing for font: ${fontStack.joinToString()} range: $rangeStart-$rangeEnd"
                    DiagnosticRepository.log(
                        severity = LogSeverity.WARNING,
                        source = "MapLibreEngine",
                        description = msg,
                        stage = "GLYPHS_ERROR"
                    )
                }

                mv.addOnSpriteErrorListener { spriteId, url ->
                    val msg = "Sprite load error for id: $spriteId url: $url"
                    DiagnosticRepository.log(
                        severity = LogSeverity.WARNING,
                        source = "MapLibreEngine",
                        description = msg,
                        stage = "SPRITE_ERROR"
                    )
                }

                mv.addOnDidBecomeIdleListener {
                    DiagnosticRepository.log(
                        severity = LogSeverity.DEBUG,
                        source = "MapLibreEngine",
                        description = "Engine idle (quiescent)",
                        stage = "IDLE"
                    )
                }

                // Stage 4: MapView "onCreate()" completed
                mv.onCreate(null)
                DiagnosticRepository.log(
                    severity = LogSeverity.INFO,
                    source = "MainMapScreen",
                    description = "MapView 'onCreate()' completed (MapView#$mvId)",
                    stage = "MAPVIEW_ONCREATE"
                )

                // Stage 6: "getMapAsync()" requested
                DiagnosticRepository.log(
                    severity = LogSeverity.INFO,
                    source = "MainMapScreen",
                    description = "'getMapAsync()' requested (MapView#$mvId)",
                    stage = "GET_MAP_ASYNC_REQUESTED"
                )
                mv.getMapAsync { maplibreMap ->
                    // Stage 7: Map-ready callback received
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MainMapScreen",
                        description = "Map-ready callback received (MapView#$mvId)",
                        stage = "MAP_READY"
                    )
                    val snapshotState = viewModel.uiState.value
                    controller.syncHighlightedPoiState(
                        poi = snapshotState.highlightedPoi,
                        visible = snapshotState.isPoiHighlightVisible
                    )
                    controller.updateUserPlacemarks(
                        placemarks = snapshotState.placemarks,
                        selectedId = snapshotState.selectedPlacemark?.id
                    )
                    controller.updateSavedRoutes(
                        routes = snapshotState.savedRoutes,
                        selectedId = snapshotState.selectedRoute?.route?.id
                    )
                    if (snapshotState.routeDraft.isActive) {
                        controller.updateRouteDraftOverlay(snapshotState.routeDraft.points)
                    }
                    val pinTarget = snapshotState.areaDetailsState?.let { it.latitude to it.longitude }
                        ?: snapshotState.mapContextMenuState?.let { it.latitude to it.longitude }
                    if (pinTarget != null) {
                        controller.setAreaSelectionPin(pinTarget.first, pinTarget.second)
                    }
                    controller.attachMap(
                        map = maplibreMap,
                        styleDef = snapshotState.currentStyle,
                        onStatusChanged = { status, desc, err ->
                            viewModel.onMapStatusChanged(status, desc, err)
                        }
                    )
                }

                mv
            }

            // Single Lifecycle Owner for MapView:
            // Keyed ONLY on lifecycleOwner (NOT mapView or mutableState), preventing premature disposal on recomposition.
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val mvId = System.identityHashCode(mapView)
                DiagnosticRepository.log(
                    severity = LogSeverity.INFO,
                    source = "MainMapScreen",
                    description = "DisposableEffect entered for LifecycleOwner#${System.identityHashCode(lifecycleOwner)} with MapView#$mvId",
                    stage = "DISPOSABLE_EFFECT_ENTRY"
                )

                var destructionReason: String? = null
                fun safeDestroy(reason: String) {
                    if (destructionReason == null) {
                        destructionReason = reason
                        DiagnosticRepository.log(
                            severity = LogSeverity.INFO,
                            source = "MainMapScreen",
                            description = "MapView destruction initiated by: $reason (MapView#$mvId)",
                            stage = "DESTROYED"
                        )
                        try {
                            mapView.onDestroy()
                        } catch (e: Throwable) {
                            DiagnosticRepository.log(
                                severity = LogSeverity.WARNING,
                                source = "MainMapScreen",
                                description = "Exception during MapView.onDestroy: ${e.message}",
                                throwable = e
                            )
                        }
                    } else {
                        DiagnosticRepository.log(
                            severity = LogSeverity.DEBUG,
                            source = "MainMapScreen",
                            description = "Ignored redundant destruction request from $reason (already destroyed by $destructionReason)"
                        )
                    }
                }

                val observer = LifecycleEventObserver { _, event ->
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MainMapScreen",
                        description = "Lifecycle observer event -> $event for MapView#$mvId",
                        stage = "LIFECYCLE_EVENT"
                    )
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            mapView.onStart()
                            DiagnosticRepository.log(LogSeverity.INFO, "MainMapScreen", "MapView.onStart() dispatched (MapView#$mvId)", stage = "LIFECYCLE_STARTED")
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            mapView.onResume()
                            DiagnosticRepository.log(LogSeverity.INFO, "MainMapScreen", "MapView.onResume() dispatched (MapView#$mvId)", stage = "LIFECYCLE_RESUMED")
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            mapView.onPause()
                            DiagnosticRepository.log(LogSeverity.INFO, "MainMapScreen", "MapView.onPause() dispatched (MapView#$mvId)", stage = "LIFECYCLE_PAUSED")
                        }
                        Lifecycle.Event.ON_STOP -> {
                            mapView.onStop()
                            DiagnosticRepository.log(LogSeverity.INFO, "MainMapScreen", "MapView.onStop() dispatched (MapView#$mvId)", stage = "LIFECYCLE_STOPPED")
                        }
                        Lifecycle.Event.ON_DESTROY -> {
                            safeDestroy("ACTIVITY_ON_DESTROY")
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                DiagnosticRepository.log(
                    severity = LogSeverity.INFO,
                    source = "MainMapScreen",
                    description = "Lifecycle observer attached to LifecycleOwner#${System.identityHashCode(lifecycleOwner)}",
                    stage = "OBSERVER_REGISTERED"
                )

                val memoryCallbacks = object : android.content.ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
                    override fun onLowMemory() {
                        mapView.onLowMemory()
                        DiagnosticRepository.log(LogSeverity.WARNING, "MainMapScreen", "Forwarded onLowMemory() to MapView#$mvId", stage = "LOW_MEMORY")
                    }
                    @Suppress("DEPRECATION")
                    override fun onTrimMemory(level: Int) {
                        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                            mapView.onLowMemory()
                        }
                    }
                }
                context.registerComponentCallbacks(memoryCallbacks)

                onDispose {
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MainMapScreen",
                        description = "DisposableEffect.onDispose invoked for LifecycleOwner#${System.identityHashCode(lifecycleOwner)} (MapView#$mvId)",
                        stage = "COMPOSE_DISPOSAL"
                    )
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    try {
                        context.unregisterComponentCallbacks(memoryCallbacks)
                    } catch (_: Throwable) {}
                    controller.visibleTileCoordinator.stop()
                    safeDestroy("COMPOSE_DISPOSAL")
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MainMapScreen",
                        description = "AndroidView factory invoked (returning MapView#${System.identityHashCode(mapView)})",
                        stage = "ANDROID_VIEW_FACTORY"
                    )
                    mapView
                },
                onRelease = { releasedView ->
                    DiagnosticRepository.log(
                        severity = LogSeverity.INFO,
                        source = "MainMapScreen",
                        description = "AndroidView onRelease invoked for MapView#${System.identityHashCode(releasedView)}",
                        stage = "ANDROID_VIEW_RELEASE"
                    )
                },
                update = { /* MapView updates managed via state and controller */ }
            )

            // Fixed White Status Bar Protection Strip
            // Guarantees Android 15 / SDK 36 edge-to-edge transparent system bars display dark readable icons over pure white
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Color.White)
                    .align(Alignment.TopCenter)
            )

            // Top-left Coordinate & Scale Tile Cluster (Matching Reference Image)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp, end = 58.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Top coordinate box (foldable on tap with details & Sensor Panel button)
                TopCoordinateInfoPanel(
                    cameraState = uiState.camera,
                    isCollapsed = uiState.isTopPanelCollapsed,
                    isInternetEnabled = uiState.isInternetEnabled,
                    locationStatusText = uiState.locationStatusText,
                    onToggleCollapse = { viewModel.toggleTopPanel() },
                    onOpenSensorPanel = {
                        viewModel.openModal(
                            ActiveModal.PlaceholderTool(
                                name = "Sensor Panel",
                                description = "Live GNSS NMEA telemetry, magnetic compass calibration, barometer / altitude readings, and accelerometer data logging will connect in an upcoming release phase."
                            )
                        )
                    }
                )

                // Stepped Scale Ruler Tile (Ratio + Bracket Scale)
                com.example.ui.components.MapSteppedScaleTile(
                    latitude = uiState.camera.latitude,
                    zoom = uiState.camera.zoom
                )
            }

            // Top-right Compass Indicator (Reflects live map rotation, click resets to North-Up)
            com.example.ui.components.MapCompassIndicator(
                bearing = uiState.camera.bearing,
                isNorthLocked = uiState.camera.isNorthLocked,
                onResetNorth = { controller.resetNorth() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 8.dp)
            )

            // Right-side Floating Controls (Zoom +/- & Telemetry)
            // Positioned at bottom-right in the gap beside the bottom bar
            RightSideMapControls(
                isInternetEnabled = uiState.isInternetEnabled,
                isLocationActive = uiState.isLocationActive,
                onToggleInternet = {
                    viewModel.toggleInternet()
                    coroutineScope.launch {
                        val status = if (!uiState.isInternetEnabled) "In-App Internet ON" else "In-App Internet OFF (Blocked)"
                        snackbarHostState.showSnackbar(status)
                    }
                },
                onToggleLocation = {
                    viewModel.toggleLocationMode(
                        onPermissionNeeded = {
                            val finePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                            if (finePerm == PackageManager.PERMISSION_GRANTED) {
                                viewModel.setLocationPermissionGranted(true)
                            } else {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    )
                },
                onRefreshMaps = {
                    viewModel.retryOrRefreshMap(controller)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Retrying map style and render...")
                    }
                },
                onZoomIn = { controller.zoomIn() },
                onZoomOut = { controller.zoomOut() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 8.dp, bottom = 8.dp)
            )

            // Compact Floating Bottom Navigation Bar (Anchored to Bottom-Left per Reference Screenshot)
            BottomNavigationBar(
                isNorthLocked = uiState.camera.isNorthLocked,
                onSettingsClick = { viewModel.openSettingsPopup() },
                onSettingsLongClick = { viewModel.openModal(ActiveModal.MoreSettings) },
                onMapsClick = { viewModel.openModal(ActiveModal.MapsPopup) },
                onPlacesClick = { viewModel.openModal(ActiveModal.PlacesPopup) },
                onPlacesLongClick = { viewModel.openModal(ActiveModal.MemoryPalaceWorkspace) },
                onCompassToggleClick = { viewModel.toggleNorthLock() },
                onToolsLongClick = {
                    viewModel.openModal(ActiveModal.ToolsPopup)
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 8.dp, bottom = 8.dp)
            )

            // Anchored, non-blocking Area Details Popup & Long-Press Context Menu
            // Rendered cleanly over MapView so surrounding gestures (pan/pinch/double-tap) work naturally.
            if (uiState.activeModal == null && !uiState.settingsPopupOpen) {
                val activeContextMenu = uiState.mapContextMenuState
                val activeAreaDetails = uiState.areaDetailsState
                if (activeContextMenu != null) {
                    MapContextMenu(
                        state = activeContextMenu,
                        onDismiss = { viewModel.dismissMapContextMenu(controller) },
                        onOpenExistingSavedArea = { viewModel.contextOpenExistingSavedArea(controller) },
                        onOpenExistingPlacemark = { viewModel.contextOpenExistingPlacemark(controller) },
                        onOpenExistingRoute = { viewModel.contextOpenExistingRoute(controller) },
                        onAddPlacemarkHere = { viewModel.contextAddPlacemarkHere(controller) },
                        onOpenAreaDetails = { viewModel.contextOpenAreaDetails(controller) },
                        onWikiSearchArea = { viewModel.contextWikiSearchArea(controller) },
                        onRouteFromHere = { viewModel.contextRouteFromHere(controller) },
                        onRouteToHere = { viewModel.contextRouteToHere(controller) },
                        onPlannedAction = { title, group, summary, planned ->
                            viewModel.contextShowPlannedAction(title, group, summary, planned, controller)
                        }
                    )
                } else if (activeAreaDetails != null) {
                    val linkedSavedArea = activeAreaDetails.details.savedAreaId?.let { id ->
                        uiState.savedAreas.find { it.id == id }
                    }
                    AreaDetailsPopup(
                        state = activeAreaDetails,
                        savedAreaEntity = linkedSavedArea,
                        onDismiss = { viewModel.dismissAreaDetails(controller) },
                        onToggleExpanded = { viewModel.toggleAreaDetailsExpanded() },
                        onToggleNearbySelection = { itemId -> viewModel.toggleAreaNearbyItemForSave(itemId) },
                        onUpdateCustomName = { name -> viewModel.updateAreaCustomNameDraft(name) },
                        onUpdateNotesDraft = { notes -> viewModel.updateAreaNotesDraft(notes) },
                        onSaveArea = { viewModel.saveCurrentAreaDetails() },
                        onEditSavedArea = { area -> viewModel.openSavedAreaEditor(area) },
                        onRefreshOnline = { viewModel.refreshAreaDetailsOnline(controller) },
                        onDeleteSavedArea = { areaId -> viewModel.deleteSavedArea(areaId, controller) },
                        onCopyFeedback = { msg ->
                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                }
            }

            // Exclusive floating map HUD / inspection banner above bottom bar when no modal, context popup, or area details is open
            val hudModifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 64.dp, bottom = 60.dp)
            if (uiState.activeModal == null &&
                !uiState.settingsPopupOpen &&
                uiState.areaDetailsState == null &&
                uiState.mapContextMenuState == null
            ) {
                val activeSelectedPlacemark = uiState.selectedPlacemark
                val activeSelectedRoute = uiState.selectedRoute
                val highlightedPoi = uiState.highlightedPoi
                when {
                    uiState.routeDraft.isActive -> {
                        RouteCreationHudBar(
                            routeDraft = uiState.routeDraft,
                            onAddMapCenterPoint = {
                                val cam = viewModel.uiState.value.camera
                                viewModel.addRouteDraftPoint(cam.latitude, cam.longitude, controller)
                            },
                            onUndoPoint = { viewModel.undoRouteDraftPoint(controller) },
                            onMarkLastPointWaypoint = { wpName -> viewModel.markLastDraftPointAsWaypoint(wpName) },
                            onFinish = { viewModel.openSaveRouteDialog() },
                            onCancel = { viewModel.cancelRouteDraft(controller) },
                            modifier = hudModifier
                        )
                    }
                    uiState.isPickingPlacemarkLocation -> {
                        PickLocationHudBanner(
                            onUseMapCenter = { viewModel.startCreatePlacemarkAtMapCenter() },
                            onCancel = { viewModel.cancelPickPlacemarkOnMap() },
                            modifier = hudModifier
                        )
                    }
                    activeSelectedPlacemark != null -> {
                        SelectedPlacemarkBanner(
                            place = activeSelectedPlacemark,
                            onCenter = { viewModel.selectPlacemark(activeSelectedPlacemark, controller) },
                            onEdit = { viewModel.openEditPlacemark(activeSelectedPlacemark) },
                            onDelete = { viewModel.deletePlacemark(activeSelectedPlacemark.id, controller) },
                            onDismiss = { viewModel.clearSelectedPlacemark(controller) },
                            modifier = hudModifier
                        )
                    }
                    activeSelectedRoute != null -> {
                        SelectedRouteBanner(
                            routeModel = activeSelectedRoute,
                            onCenter = { viewModel.selectSavedRoute(activeSelectedRoute, controller) },
                            onEdit = { viewModel.openRouteMetadataEditor(activeSelectedRoute) },
                            onDelete = { viewModel.deleteSavedRoute(activeSelectedRoute.route.id, controller) },
                            onDismiss = { viewModel.clearSelectedRoute(controller) },
                            modifier = hudModifier
                        )
                    }
                    highlightedPoi != null -> {
                        SelectedPoiHighlightBanner(
                            poi = highlightedPoi,
                            isHighlightVisible = uiState.isPoiHighlightVisible,
                            onRecenter = { viewModel.recenterOnHighlightedPoi(controller) },
                            onToggleVisibility = { viewModel.togglePoiHighlightVisibility(controller) },
                            onDismiss = { viewModel.clearHighlightedPoi(controller) },
                            modifier = hudModifier
                        )
                    }
                }
            }

            // Floating Popups & Modals (All unified in crisp White & Sky Blue)
            AppModalManager(
                activeModal = uiState.activeModal,
                settingsPopupOpen = uiState.settingsPopupOpen,
                currentStyle = uiState.currentStyle,
                uiState = uiState,
                isDeviceOnline = isDeviceOnline,
                connectionType = connectionType,
                onSelectStyle = { style ->
                    viewModel.selectStyle(style)
                },
                onSelectComposition = { comp ->
                    viewModel.selectComposition(comp, controller)
                },
                onSaveComposition = { comp ->
                    viewModel.saveComposition(comp, controller)
                },
                onDeleteComposition = { compId ->
                    viewModel.deleteComposition(compId, controller)
                },
                onDuplicateComposition = { comp ->
                    viewModel.duplicateComposition(comp)
                },
                onTogglePinComposition = { compId ->
                    viewModel.togglePinComposition(compId)
                },
                onRefreshStorageReport = {
                    viewModel.refreshStorageReport()
                },
                onClearProviderCache = { providerId ->
                    viewModel.clearProviderCache(providerId)
                },
                onDismissModal = { viewModel.closeModal() },
                onDismissSettingsPopup = { viewModel.closeSettingsPopup() },
                onOpenMoreSettings = { viewModel.openModal(ActiveModal.MoreSettings) },
                onOpenMapCatalogue = { viewModel.openModal(ActiveModal.MapCatalogue) },
                onBackFromMapCatalogue = { viewModel.openModal(ActiveModal.MoreSettings) },
                onOpenBackupRestore = { viewModel.openModal(ActiveModal.BackupRestore) },
                onOpenTool = { name, desc ->
                    viewModel.openModal(ActiveModal.PlaceholderTool(name, desc))
                },
                onOpenMemoryPalace = {
                    viewModel.openModal(ActiveModal.MemoryPalaceWorkspace)
                },
                onOpenLogKeeper = {
                    viewModel.openModal(ActiveModal.LogKeeper)
                },
                onOpenPoiSearch = {
                    viewModel.openPoiSearch()
                },
                onOpenPoiHighlight = {
                    viewModel.openPoiHighlight()
                },
                onPoiQueryChange = { query ->
                    viewModel.updatePoiSearchQuery(query)
                },
                onPoiCategorySelect = { category ->
                    viewModel.selectPoiSearchCategory(category)
                },
                onSelectPoiResult = { poi ->
                    viewModel.selectSearchResult(poi, controller)
                },
                onTogglePoiHighlightVisibility = {
                    viewModel.togglePoiHighlightVisibility(controller)
                },
                onRecenterPoiHighlight = {
                    viewModel.recenterOnHighlightedPoi(controller)
                },
                onClearPoiHighlight = {
                    viewModel.clearHighlightedPoi(controller)
                },
                onOpenPlacemarksWorkspace = {
                    viewModel.openModal(ActiveModal.PlacemarksWorkspace)
                },
                onOpenRoutesWorkspace = {
                    viewModel.openModal(ActiveModal.RoutesWorkspace)
                },
                onOpenSavedAreasWorkspace = {
                    viewModel.openModal(ActiveModal.SavedAreasWorkspace)
                },
                onStartCreatePlacemarkAtCenter = {
                    viewModel.startCreatePlacemarkAtMapCenter()
                },
                onStartPickPlacemarkOnMap = {
                    viewModel.startPickPlacemarkOnMap()
                },
                onSelectPlacemarkOnMap = { place ->
                    viewModel.selectPlacemark(place, controller)
                },
                onOpenEditPlacemark = { place ->
                    viewModel.openEditPlacemark(place)
                },
                onDeletePlacemark = { placeId ->
                    viewModel.deletePlacemark(placeId, controller)
                },
                onStartCreateRouteOnMap = {
                    viewModel.startNewRouteCreation(controller)
                },
                onSelectRouteOnMap = { routeModel ->
                    viewModel.selectSavedRoute(routeModel, controller)
                },
                onOpenEditRouteMetadata = { routeModel ->
                    viewModel.openRouteMetadataEditor(routeModel)
                },
                onEditRouteGeometryOnMap = { routeModel ->
                    viewModel.startEditingRouteGeometry(routeModel, controller)
                },
                onDeleteSavedRoute = { routeId ->
                    viewModel.deleteSavedRoute(routeId, controller)
                },
                onOpenSavedAreaOnMap = { area ->
                    viewModel.openSavedAreaOnMap(area, controller)
                },
                onOpenEditSavedArea = { area ->
                    viewModel.openSavedAreaEditor(area)
                },
                onRefreshSavedAreaOnline = { areaId ->
                    viewModel.refreshSavedAreaFromWorkspace(areaId)
                },
                onDeleteSavedArea = { areaId ->
                    viewModel.deleteSavedArea(areaId, controller)
                }
            )

            // Phase 4.1 & 4.2 Editor / Notice Dialogs
            val savedAreaEditorState = uiState.savedAreaEditorState
            if (savedAreaEditorState != null) {
                SavedAreaEditorDialog(
                    editorState = savedAreaEditorState,
                    onDismiss = { viewModel.dismissSavedAreaEditor() },
                    onSave = { name, cat, addr, notes, tags ->
                        viewModel.saveEditedAreaMetadata(
                            areaId = savedAreaEditorState.areaId,
                            name = name,
                            category = cat,
                            addressLabel = addr,
                            userNotes = notes,
                            tagsText = tags
                        )
                    }
                )
            }

            val plannedNotice = uiState.plannedActionNotice
            if (plannedNotice != null) {
                PlannedActionNoticeDialog(
                    state = plannedNotice,
                    onDismiss = { viewModel.dismissPlannedActionNotice() }
                )
            }

            val placemarkEditorState = uiState.placemarkEditorState
            if (placemarkEditorState != null) {
                PlacemarkEditorDialog(
                    editorState = placemarkEditorState,
                    onDismiss = { viewModel.dismissPlacemarkEditor() },
                    onSave = { name, desc, addr, cat, tags, lat, lng, allowDup ->
                        viewModel.savePlacemarkFromEditor(
                            name = name,
                            description = desc,
                            addressLabel = addr,
                            category = cat,
                            tagsText = tags,
                            latitude = lat,
                            longitude = lng,
                            allowDuplicateLocation = allowDup,
                            controller = controller
                        )
                    },
                    onOpenExistingDuplicate = { existing ->
                        viewModel.selectExistingDuplicatePlacemark(existing, controller)
                    }
                )
            }

            if (uiState.routeDraft.isActive && uiState.routeDraft.showSaveDialog) {
                SaveRouteDialog(
                    routeDraft = uiState.routeDraft,
                    onDismiss = { viewModel.dismissSaveRouteDialog() },
                    onSave = { name, desc, wpMap ->
                        viewModel.finishAndSaveRouteDraft(
                            name = name,
                            description = desc,
                            waypointNamesByPointIndex = wpMap,
                            controller = controller
                        )
                    }
                )
            }

            val routeMetadataTarget = uiState.routeMetadataEditorTarget
            if (routeMetadataTarget != null) {
                RouteMetadataEditorDialog(
                    routeModel = routeMetadataTarget,
                    onDismiss = { viewModel.dismissRouteMetadataEditor() },
                    onEditGeometryOnMap = {
                        viewModel.startEditingRouteGeometry(routeMetadataTarget, controller)
                    },
                    onSaveChanges = { name, desc, updatedWps ->
                        viewModel.updateRouteMetadataAndWaypoints(
                            routeModel = routeMetadataTarget,
                            name = name,
                            description = desc,
                            updatedWaypoints = updatedWps,
                            controller = controller
                        )
                    }
                )
            }
        }
    }
}
