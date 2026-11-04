package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.ui.components.BottomNavigationBar
import com.example.ui.components.RightSideMapControls
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
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle back button for modals
    BackHandler(enabled = uiState.activeModal != null || uiState.quickSettingsOpen) {
        if (uiState.quickSettingsOpen) {
            viewModel.closeQuickSettings()
        } else {
            viewModel.closeModal()
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

    // Initialize MapLibre singleton once
    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
    }

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

    // Sync North Lock setting with controller
    LaunchedEffect(uiState.camera.isNorthLocked) {
        controller.setNorthLock(uiState.camera.isNorthLocked)
    }

    // Sync style change
    LaunchedEffect(uiState.currentStyle.styleUri) {
        if (controller.maplibreMap != null) {
            controller.setStyle(uiState.currentStyle.styleUri)
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
            // The Native MapLibre MapView hosted inside AndroidView (full-bleed under all controls)
            var mapViewRef by remember { androidx.compose.runtime.mutableStateOf<MapView?>(null) }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

            androidx.compose.runtime.DisposableEffect(lifecycleOwner, mapViewRef) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_START -> mapViewRef?.onStart()
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> mapViewRef?.onStop()
                        androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> mapViewRef?.onDestroy()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    mapViewRef?.onDestroy()
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        onCreate(null)
                        getMapAsync { maplibreMap ->
                            controller.attachMap(
                                map = maplibreMap,
                                currentStyleUri = uiState.currentStyle.styleUri,
                                onStyleLoaded = { /* Style ready */ }
                            )
                        }
                        mapViewRef = this
                    }
                },
                update = { /* MapView lifecycle managed via observer */ }
            )

            // Top-left Coordinate & Info Panel
            TopCoordinateInfoPanel(
                cameraState = uiState.camera,
                isCollapsed = uiState.isTopPanelCollapsed,
                isInternetEnabled = uiState.isInternetEnabled,
                locationStatusText = uiState.locationStatusText,
                onToggleCollapse = { viewModel.toggleTopPanel() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 14.dp, top = 8.dp)
            )

            // Top-right Compass Indicator (Reflects live map rotation, click resets to North-Up)
            com.example.ui.components.MapCompassIndicator(
                bearing = uiState.camera.bearing,
                isNorthLocked = uiState.camera.isNorthLocked,
                onResetNorth = { controller.resetNorth() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 14.dp, top = 8.dp)
            )

            // Mid-left Compact Map Scale Bar
            com.example.ui.components.MapScaleBar(
                latitude = uiState.camera.latitude,
                zoom = uiState.camera.zoom,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 14.dp, top = 70.dp)
            )

            // Bottom-right Map Attribution Badge (Directly credits OpenFreeMap, OpenMapTiles, OpenStreetMap)
            com.example.ui.components.MapAttributionBadge(
                attributionText = uiState.currentStyle.attribution,
                onClick = { viewModel.openModal(ActiveModal.MapsPopup) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 246.dp)
            )

            // Bottom-aligned Control Layer
            // - Bottom Navigation Pill shifted further LEFT
            // - Independent circular Right-Side controls aligned near bottom-right
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Floating Bottom Navigation Pill (Shifted LEFT for thumb reach and right stack room)
                BottomNavigationBar(
                    isNorthLocked = uiState.camera.isNorthLocked,
                    onSettingsClick = { viewModel.openModal(ActiveModal.Settings) },
                    onSettingsLongClick = { viewModel.openQuickSettings() },
                    onMapsClick = { viewModel.openModal(ActiveModal.MapsPopup) },
                    onPlacesClick = { viewModel.openModal(ActiveModal.PlacesPopup) },
                    onPlacesLongClick = { viewModel.openModal(ActiveModal.MemoryPalaceWorkspace) },
                    onCompassToggleClick = { viewModel.toggleNorthLock() },
                    onToolsLongClick = { viewModel.openModal(ActiveModal.ToolsPopup) },
                    modifier = Modifier.padding(bottom = 2.dp)
                )

                // Right-side Map Controls (5 independent circular buttons aligned bottom-right)
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
                        controller.refreshVisibleMap()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Refreshing visible map area...")
                        }
                    },
                    onZoomIn = { controller.zoomIn() },
                    onZoomOut = { controller.zoomOut() },
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }

            // Floating Popups & Modals (All unified in crisp White & Sky Blue)
            AppModalManager(
                activeModal = uiState.activeModal,
                quickSettingsOpen = uiState.quickSettingsOpen,
                currentStyle = uiState.currentStyle,
                onSelectStyle = { style ->
                    viewModel.selectStyle(style)
                    controller.setStyle(style.styleUri)
                },
                onDismissModal = { viewModel.closeModal() },
                onDismissQuickSettings = { viewModel.closeQuickSettings() },
                onOpenTool = { name, desc ->
                    viewModel.openModal(ActiveModal.PlaceholderTool(name, desc))
                },
                onOpenMemoryPalace = {
                    viewModel.openModal(ActiveModal.MemoryPalaceWorkspace)
                }
            )
        }
    }
}
