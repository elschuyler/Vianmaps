package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.ui.theme.SlateDark900
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
        bottomBar = {
            BottomNavigationBar(
                isNorthLocked = uiState.camera.isNorthLocked,
                onSettingsClick = { viewModel.openModal(ActiveModal.Settings) },
                onSettingsLongClick = { viewModel.openQuickSettings() },
                onMapsClick = { viewModel.openModal(ActiveModal.MapsPopup) },
                onPlacesClick = { viewModel.openModal(ActiveModal.PlacesPopup) },
                onPlacesLongClick = { viewModel.openModal(ActiveModal.MemoryPalaceWorkspace) },
                onCompassToggleClick = { viewModel.toggleNorthLock() },
                onToolsLongClick = { viewModel.openModal(ActiveModal.ToolsPopup) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .background(SlateDark900)
        ) {
            // The Native MapLibre MapView hosted inside AndroidView
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
                    }
                },
                update = { /* Map lifecycle handled via DisposableEffect below */ }
            )

            // Top Coordinate & Info Panel (Collapsed by default, toggleable)
            TopCoordinateInfoPanel(
                cameraState = uiState.camera,
                isCollapsed = uiState.isTopPanelCollapsed,
                isInternetEnabled = uiState.isInternetEnabled,
                locationStatusText = uiState.locationStatusText,
                onToggleCollapse = { viewModel.toggleTopPanel() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )

            // Right-side Map Controls (5 buttons strictly top to bottom)
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
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
            )

            // Modals & Bottom sheets
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
