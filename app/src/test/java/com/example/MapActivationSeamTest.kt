package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.AppDatabaseContract
import com.example.data.preferences.AppPreferencesRepository
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.host.AppHostDestination
import com.example.host.AppHostShell
import com.example.host.EXTRA_DESTINATION_NOTES
import com.example.host.EXTRA_INITIAL_DESTINATION
import com.example.host.resolveInitialHostDestination
import com.example.map.acquisition.VisibleTileCoordinator
import com.example.map.activation.MapActivationBoundary
import com.example.map.activation.MapActivationCoordinator
import com.example.map.activation.MapActivationResult
import com.example.map.activation.MapActivationState
import com.example.map.activation.MapDeactivationResult
import com.example.map.activation.MapFeatureActivation
import com.example.map.activation.MapRuntimeDependencies
import com.example.map.activation.MapRuntimeInitializer
import com.example.map.activation.ProcessMapRuntimeState
import com.example.map.engine.MapEngineController
import com.example.map.engine.MapViewLifecycleCoordinator
import com.example.map.engine.MapViewLifecycleTarget
import com.example.map.source.MapSourceRepository
import com.example.ui.components.BottomNavigationBar
import com.example.ui.screens.MapViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapActivationSeamTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DiagnosticRepository.clear(logNotice = false)
    }

    @Test
    fun stage1_01_mapRuntimeRemainsInactiveBeforeExplicitActivation() {
        assertEquals(MapActivationState.Inactive, MapFeatureActivation.state.value)
        assertFalse("MapLibre must not be eagerly initialized", MapLibre.hasInstance())

        val initCalls = AtomicInteger(0)
        val lazyContentConstructions = AtomicInteger(0)
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { initCalls.incrementAndGet() },
            activeContent = {
                lazyContentConstructions.incrementAndGet()
                Text("Map Active Content")
            }
        )

        assertEquals(MapActivationState.Inactive, coordinator.state.value)
        assertEquals(0, initCalls.get())

        composeTestRule.setContent {
            coordinator.Content()
        }
        composeTestRule.waitForIdle()

        assertEquals("Map content must remain lazy before activate()", 0, lazyContentConstructions.get())
        assertEquals(0, initCalls.get())
        assertEquals(MapActivationState.Inactive, coordinator.state.value)
    }

    @Test
    fun stage1_02_activationInitializesMapOnceWithApplicationContextAndRendersContent() {
        val initCalls = AtomicInteger(0)
        val lazyContentConstructions = AtomicInteger(0)
        var capturedContext: Context? = null

        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { ctx ->
                initCalls.incrementAndGet()
                capturedContext = ctx
            },
            activeContent = {
                lazyContentConstructions.incrementAndGet()
                Text("Map Active Content")
            }
        )

        composeTestRule.setContent {
            coordinator.Content()
        }
        composeTestRule.waitForIdle()
        assertEquals(0, lazyContentConstructions.get())

        val wrappedActivityLikeContext = ContextWrapper(context)
        val result = coordinator.activate(wrappedActivityLikeContext)

        assertEquals(MapActivationResult.Activated, result)
        assertEquals(MapActivationState.Active, coordinator.state.value)
        assertEquals(1, initCalls.get())
        assertSame(context.applicationContext, capturedContext)

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Map Active Content").assertIsDisplayed()
        assertEquals(1, lazyContentConstructions.get())
    }

    @Test
    fun stage1_03_repeatedAndConcurrentActivationIsSafeAndIdempotent() {
        val initCalls = AtomicInteger(0)
        val startedLatch = CountDownLatch(1)
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer {
                initCalls.incrementAndGet()
                Thread.sleep(50L)
            }
        )

        val threadCount = 6
        val executor = Executors.newFixedThreadPool(threadCount)
        val doneLatch = CountDownLatch(threadCount)
        val results = MutableList<MapActivationResult?>(threadCount) { null }

        try {
            for (i in 0 until threadCount) {
                executor.submit {
                    startedLatch.await(2, TimeUnit.SECONDS)
                    results[i] = coordinator.activate(context)
                    doneLatch.countDown()
                }
            }
            startedLatch.countDown()
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, initCalls.get())
        assertEquals(MapActivationState.Active, coordinator.state.value)
        assertEquals(1, results.count { it == MapActivationResult.Activated })
        assertEquals(threadCount - 1, results.count { it == MapActivationResult.AlreadyActive })

        val sequentialRepeat = coordinator.activate(context)
        assertEquals(MapActivationResult.AlreadyActive, sequentialRepeat)
        assertEquals(1, initCalls.get())
    }

    @Test
    fun stage1_04_initializationFailureIsObservableAndBlocksMapContent() {
        val attemptCounter = AtomicInteger(0)
        val lazyContentConstructions = AtomicInteger(0)
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer {
                if (attemptCounter.incrementAndGet() == 1) {
                    throw UnsatisfiedLinkError("Simulated missing native MapLibre binary")
                }
            },
            activeContent = {
                lazyContentConstructions.incrementAndGet()
                Text("Map Active Content")
            }
        )

        composeTestRule.setContent {
            coordinator.Content()
        }
        composeTestRule.waitForIdle()

        val failedResult = coordinator.activate(context)
        assertTrue(failedResult is MapActivationResult.Failed)
        val failedTyped = failedResult as MapActivationResult.Failed
        assertEquals("Simulated missing native MapLibre binary", failedTyped.message)
        assertTrue(failedTyped.cause is UnsatisfiedLinkError)

        val currentState = coordinator.state.value
        assertTrue(currentState is MapActivationState.Failed)
        assertEquals("Simulated missing native MapLibre binary", (currentState as MapActivationState.Failed).message)

        composeTestRule.waitForIdle()
        assertEquals("Failed activation must never construct Map content/ViewModel", 0, lazyContentConstructions.get())

        val errorEvents = DiagnosticRepository.getSnapshot().filter {
            it.severity == LogSeverity.ERROR && it.stage == "MAP_ACTIVATION_FAILED"
        }
        assertTrue("Activation failure must be logged to DiagnosticRepository", errorEvents.isNotEmpty())
        assertNotNull(DiagnosticRepository.lastFatalError.value)

        // Explicit retry after failure can succeed if runtime becomes available
        val recoveryResult = coordinator.activate(context)
        assertEquals(MapActivationResult.Activated, recoveryResult)
        assertEquals(MapActivationState.Active, coordinator.state.value)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Map Active Content").assertIsDisplayed()
        assertEquals(1, lazyContentConstructions.get())
    }

    @Test
    fun stage1_05_mainActivityHostDecouplingAndRoomV3Preserved() {
        val fieldTypeNames = MainActivity::class.java.declaredFields.map { it.type.name }
        assertFalse(fieldTypeNames.any { it.contains("MapViewModel") })
        assertFalse(fieldTypeNames.any { it.contains("MapEngineController") })
        assertFalse(fieldTypeNames.any { it.contains("MapLibre") })
        assertFalse(fieldTypeNames.any { it.contains("OfflineManager") })
        assertTrue(fieldTypeNames.any { it.contains("MapActivationBoundary") })

        assertEquals("map_app_master.db", AppDatabaseContract.DATABASE_NAME)
        assertEquals(3, AppDatabaseContract.CURRENT_VERSION)
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
    }

    @Test
    fun stage2_01_dependenciesRemainNullBeforeActivationAndAfterFailure() {
        val depsCreated = AtomicInteger(0)
        var shouldFail = true
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer {
                if (shouldFail) throw IllegalStateException("Simulated SDK init failure")
            },
            dependenciesFactory = { ctx ->
                depsCreated.incrementAndGet()
                MapRuntimeDependencies(ctx)
            }
        )

        assertNull(coordinator.dependenciesOrNull())
        assertEquals(0, depsCreated.get())

        val failed = coordinator.activate(context)
        assertTrue(failed is MapActivationResult.Failed)
        assertNull("Dependencies must NOT be created when activation fails", coordinator.dependenciesOrNull())
        assertEquals(0, depsCreated.get())

        shouldFail = false
        val activated = coordinator.activate(context)
        assertEquals(MapActivationResult.Activated, activated)
        val firstDeps = coordinator.dependenciesOrNull()
        assertNotNull(firstDeps)
        assertEquals(1, depsCreated.get())

        val repeat = coordinator.activate(context)
        assertEquals(MapActivationResult.AlreadyActive, repeat)
        assertSame(firstDeps, coordinator.dependenciesOrNull())
        assertEquals(1, depsCreated.get())
    }

    @Test
    fun stage2_02_mapRuntimeDependenciesOwnsSingleSharedInstancesAndNoDuplicates() {
        val dbLookups = AtomicInteger(0)
        val deps = MapRuntimeDependencies(
            context = context,
            databaseProvider = { ctx ->
                dbLookups.incrementAndGet()
                AppDatabase.getInstance(ctx)
            }
        )

        // Container itself must not eagerly touch Room database until a consumer needs it
        assertEquals(0, dbLookups.get())

        val db1 = deps.database
        val db2 = deps.database
        assertSame(db1, db2)
        assertEquals(1, dbLookups.get())

        assertSame(deps.preferencesRepository, deps.preferencesRepository)
        assertSame(deps.tileStorageManager, deps.tileStorageManager)
        assertSame(deps.osmRepository, deps.osmRepository)
        assertSame(deps.graphIndex, deps.graphIndex)
        assertSame(deps.placesRepository, deps.placesRepository)
        assertSame(deps.areaRemoteGateway, deps.areaRemoteGateway)
        assertSame(deps.areaRepository, deps.areaRepository)
        assertSame(deps.storageService, deps.storageService)
        assertSame(deps.networkMonitor, deps.networkMonitor)

        val controller1 = deps.createEngineController(37.7, -122.4, 11.0, 0.0) { _, _, _, _ -> }
        val controller2 = deps.createEngineController(37.7, -122.4, 11.0, 0.0) { _, _, _, _ -> }
        assertNotSame(controller1, controller2)

        val coord1 = controller1.visibleTileCoordinator
        val coord2 = controller2.visibleTileCoordinator
        val osmField = VisibleTileCoordinator::class.java.getDeclaredField("osmRepository").apply {
            isAccessible = true
        }
        val tileField = VisibleTileCoordinator::class.java.getDeclaredField("tileStorageManager").apply {
            isAccessible = true
        }
        val graphField = VisibleTileCoordinator::class.java.getDeclaredField("graphIndex").apply {
            isAccessible = true
        }
        assertSame(deps.osmRepository, osmField.get(coord1))
        assertSame(deps.osmRepository, osmField.get(coord2))
        assertSame(deps.tileStorageManager, tileField.get(coord1))
        assertSame(deps.tileStorageManager, tileField.get(coord2))
        assertSame(deps.graphIndex, graphField.get(coord1))
        assertSame(deps.graphIndex, graphField.get(coord2))
    }

    @Test
    fun stage2_03_mapViewModelUsesInjectedDependenciesAndStorageService(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val deps = MapRuntimeDependencies(app)
        val viewModel = deps.viewModelFactory(app).create(MapViewModel::class.java)

        assertSame(deps.networkMonitor, viewModel.networkMonitor)

        val controller = viewModel.createMapEngineController(38.4, 27.1, 12.0, 0.0) { _, _, _, _ -> }
        val osmField = VisibleTileCoordinator::class.java.getDeclaredField("osmRepository").apply {
            isAccessible = true
        }
        assertSame(deps.osmRepository, osmField.get(controller.visibleTileCoordinator))

        // MapViewModel must not hold direct AppDatabase field references
        val vmFields = MapViewModel::class.java.declaredFields.map { it.type.name }
        assertFalse(vmFields.any { it.contains("AppDatabase") })
        assertTrue(vmFields.any { it.contains("MapRuntimeDependencies") })
        assertTrue(vmFields.any { it.contains("MapStorageService") })

        val report = deps.storageService.calculateStorageReport()
        assertNotNull(report)
        assertTrue(report.perSource.isNotEmpty())
        assertEquals("map_app_master.db", AppDatabaseContract.DATABASE_NAME)
        assertEquals(3, AppDatabaseContract.CURRENT_VERSION)
    }

    @Test
    fun stage2_04_networkMonitorIdempotentRegistrationAndWarmRelaunch() {
        val deps = MapRuntimeDependencies(context)
        val monitor = deps.networkMonitor
        monitor.ensureRegistered()
        monitor.ensureRegistered()
        monitor.unregister()
        monitor.unregister()
        // Re-registering cleanly simulates warm relaunch in the same process
        monitor.ensureRegistered()
        monitor.unregister()
    }

    @Test
    fun stage2_05_mainActivityRemainsThinHostWithoutMapDependencies() {
        val hostFieldTypes = MainActivity::class.java.declaredFields.map { it.type.name }
        assertTrue(hostFieldTypes.any { it.contains("MapActivationBoundary") })
        assertFalse(hostFieldTypes.any { it.contains("MapRuntimeDependencies") })
        assertFalse(hostFieldTypes.any { it.contains("AppPreferencesRepository") })
        assertFalse(hostFieldTypes.any { it.contains("OsmOfflineDataRepository") })
        assertFalse(hostFieldTypes.any { it.contains("PlacesAndRoutesRepository") })
        assertFalse(hostFieldTypes.any { it.contains("AreaDetailsRepository") })
        assertFalse(hostFieldTypes.any { it.contains("NetworkDiagnosticsMonitor") })
        assertFalse(hostFieldTypes.any { it.contains("MapStorageService") })
        assertFalse(hostFieldTypes.any { it.contains("MapViewModel") })
        assertFalse(hostFieldTypes.any { it.contains("MapEngineController") })
    }

    @Test
    fun ab1_01_coldLaunchIntoNotesDoesNotActivateMapOrInitializeMapLibre() {
        assertEquals(AppHostDestination.MAP, resolveInitialHostDestination(null))
        val notesIntent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_INITIAL_DESTINATION, EXTRA_DESTINATION_NOTES)
        assertEquals(AppHostDestination.NOTES, resolveInitialHostDestination(notesIntent))

        // Cold launch real MainActivity directly into Notes
        val controller = Robolectric.buildActivity(MainActivity::class.java, notesIntent)
            .create()
            .start()
            .resume()
        try {
            assertEquals(MapActivationState.Inactive, MapFeatureActivation.state.value)
            assertFalse(MapFeatureActivation.isRuntimeInitialized)
            assertFalse("Cold-launching Notes must not initialize MapLibre", MapLibre.hasInstance())
            assertEquals(0, ProcessMapRuntimeState.initializationCount)
        } finally {
            controller.pause().stop().destroy()
        }

        // Verify host shell stays inactive on Notes until Return to Map is tapped
        val sdkInitCalls = AtomicInteger(0)
        val depsCreated = AtomicInteger(0)
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { sdkInitCalls.incrementAndGet() },
            dependenciesFactory = { ctx ->
                depsCreated.incrementAndGet()
                MapRuntimeDependencies(ctx)
            },
            activeSessionContent = { _, _, _, _, _ ->
                Text("Map Session Active")
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.NOTES,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()

        assertEquals(MapActivationState.Inactive, coordinator.state.value)
        assertFalse(coordinator.isRuntimeInitialized)
        assertEquals(0, sdkInitCalls.get())
        assertEquals(0, depsCreated.get())
        assertNull(coordinator.dependenciesOrNull())
        composeTestRule.onNodeWithTag("notes_placeholder_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("notes_return_to_map_button").assertIsDisplayed()

        // Tapping Return to Map activates Map cleanly
        composeTestRule.onNodeWithTag("notes_return_to_map_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(MapActivationState.Active, coordinator.state.value)
        assertTrue(coordinator.isRuntimeInitialized)
        assertEquals(1, sdkInitCalls.get())
        assertEquals(1, depsCreated.get())
        assertEquals(0, composeTestRule.onAllNodesWithTag("notes_placeholder_screen").fetchSemanticsNodes().size)
        composeTestRule.onNodeWithText("Map Session Active").assertIsDisplayed()
    }

    @Test
    fun ab1_02_defaultHostLaunchActivatesMapOnceAndRendersMap() {
        val sdkInitCalls = AtomicInteger(0)
        val depsCreated = AtomicInteger(0)
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { sdkInitCalls.incrementAndGet() },
            dependenciesFactory = { ctx ->
                depsCreated.incrementAndGet()
                MapRuntimeDependencies(ctx)
            },
            activeSessionContent = { modifier, _, viewModel, onOpenNotes, registerTeardown ->
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                Box(modifier = modifier.fillMaxSize()) {
                    BottomNavigationBar(
                        isNorthLocked = state.camera.isNorthLocked,
                        onSettingsClick = {},
                        onSettingsLongClick = {},
                        onMapsClick = {},
                        onPlacesClick = {},
                        onPlacesLongClick = onOpenNotes,
                        onCompassToggleClick = { viewModel.toggleNorthLock() },
                        onToolsLongClick = {}
                    )
                }
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()

        assertEquals(MapActivationState.Active, coordinator.state.value)
        assertTrue(coordinator.isRuntimeInitialized)
        assertEquals(1, sdkInitCalls.get())
        assertEquals(1, depsCreated.get())
        composeTestRule.onNodeWithTag("bottom_nav_bar").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("notes_placeholder_screen").fetchSemanticsNodes().size)
    }

    @Test
    fun ab1_03_mapToNotesDeactivatesSessionClearsViewModelUnregistersNetworkAndRemovesMapUi() {
        val sdkInitCalls = AtomicInteger(0)
        val mapViewCreations = AtomicInteger(0)
        val mapViewDestroys = AtomicInteger(0)
        val capturedViewModels = mutableListOf<MapViewModel>()
        val capturedControllers = mutableListOf<MapEngineController>()
        val capturedDeps = mutableListOf<MapRuntimeDependencies>()

        lateinit var coordinator: MapActivationCoordinator
        coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { sdkInitCalls.incrementAndGet() },
            dependenciesFactory = { ctx ->
                MapRuntimeDependencies(ctx).also { capturedDeps.add(it) }
            },
            activeSessionContent = { modifier, _, viewModel, onOpenNotes, registerTeardown ->
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val controller = remember {
                    mapViewCreations.incrementAndGet()
                    viewModel.createMapEngineController(
                        initialLat = uiState.camera.latitude,
                        initialLng = uiState.camera.longitude,
                        initialZoom = uiState.camera.zoom,
                        initialBearing = uiState.camera.bearing,
                        onCameraMoved = { lat, lng, zoom, bearing ->
                            viewModel.onCameraMoved(lat, lng, zoom, bearing)
                        }
                    )
                }
                remember {
                    capturedViewModels.add(viewModel)
                    capturedControllers.add(controller)
                    true
                }

                DisposableEffect(controller) {
                    registerTeardown { controller.release() }
                    onDispose {
                        registerTeardown(null)
                        controller.release()
                        mapViewDestroys.incrementAndGet()
                    }
                }

                Box(modifier = modifier.fillMaxSize()) {
                    BottomNavigationBar(
                        isNorthLocked = uiState.camera.isNorthLocked,
                        onSettingsClick = {},
                        onSettingsLongClick = {},
                        onMapsClick = {},
                        onPlacesClick = {},
                        onPlacesLongClick = onOpenNotes,
                        onCompassToggleClick = { viewModel.toggleNorthLock() },
                        onToolsLongClick = {}
                    )
                }
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()

        assertEquals(1, capturedViewModels.size)
        val firstVm = capturedViewModels.first()
        val firstController = capturedControllers.first()
        val firstDeps = capturedDeps.first()
        assertFalse(firstVm.isCleared)
        assertFalse(firstController.isReleased)
        assertTrue(firstDeps.networkMonitor.isCallbackRegistered)
        assertNotNull(coordinator.sessionViewModelStoreOrNull())

        // Long-press Placemarks button to navigate Map -> Notes (async coroutine deactivation)
        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitUntil(3_000L) {
            coordinator.state.value is MapActivationState.Inactive
        }
        composeTestRule.waitForIdle()

        // Notes placeholder visible, Map UI completely removed from composition
        composeTestRule.onNodeWithTag("notes_placeholder_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("notes_return_to_map_button").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("bottom_nav_bar").fetchSemanticsNodes().size)

        // Session torn down cleanly while process SDK init remains true
        assertEquals(MapActivationState.Inactive, coordinator.state.value)
        assertTrue(coordinator.isRuntimeInitialized)
        assertEquals(1, sdkInitCalls.get())
        assertEquals(1, mapViewCreations.get())
        assertEquals(1, mapViewDestroys.get())
        assertTrue(firstController.isReleased)
        assertTrue(firstVm.isCleared)
        assertFalse(firstDeps.networkMonitor.isCallbackRegistered)
        assertNull(coordinator.dependenciesOrNull())
        assertNull(coordinator.sessionViewModelStoreOrNull())
    }

    @Test
    fun ab1_04_notesToMapCreatesFreshSessionReusesProcessSdkAndRestoresCameraAndPreferences() {
        val sdkInitCalls = AtomicInteger(0)
        val mapViewCreations = AtomicInteger(0)
        val mapViewDestroys = AtomicInteger(0)
        val capturedViewModels = mutableListOf<MapViewModel>()
        val capturedControllers = mutableListOf<MapEngineController>()
        val capturedDeps = mutableListOf<MapRuntimeDependencies>()
        val capturedStores = mutableListOf<ViewModelStore>()

        lateinit var coordinator: MapActivationCoordinator
        coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { sdkInitCalls.incrementAndGet() },
            dependenciesFactory = { ctx ->
                MapRuntimeDependencies(ctx).also { capturedDeps.add(it) }
            },
            activeSessionContent = { modifier, _, viewModel, onOpenNotes, registerTeardown ->
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val controller = remember {
                    mapViewCreations.incrementAndGet()
                    viewModel.createMapEngineController(
                        initialLat = uiState.camera.latitude,
                        initialLng = uiState.camera.longitude,
                        initialZoom = uiState.camera.zoom,
                        initialBearing = uiState.camera.bearing,
                        onCameraMoved = { lat, lng, zoom, bearing ->
                            viewModel.onCameraMoved(lat, lng, zoom, bearing)
                        }
                    )
                }
                remember {
                    capturedViewModels.add(viewModel)
                    capturedControllers.add(controller)
                    coordinator.sessionViewModelStoreOrNull()?.let { capturedStores.add(it) }
                    true
                }

                LaunchedEffect(uiState.hasLoadedPreferences) {
                    if (uiState.hasLoadedPreferences && !viewModel.hasSessionCameraOverride()) {
                        controller.applyRestoredCamera(
                            lat = uiState.camera.latitude,
                            lng = uiState.camera.longitude,
                            zoom = uiState.camera.zoom,
                            bearing = uiState.camera.bearing
                        )
                    }
                }

                DisposableEffect(controller) {
                    registerTeardown { controller.release() }
                    onDispose {
                        registerTeardown(null)
                        controller.release()
                        mapViewDestroys.incrementAndGet()
                    }
                }

                Box(modifier = modifier.fillMaxSize()) {
                    BottomNavigationBar(
                        isNorthLocked = uiState.camera.isNorthLocked,
                        onSettingsClick = {},
                        onSettingsLongClick = {},
                        onMapsClick = {},
                        onPlacesClick = {},
                        onPlacesLongClick = onOpenNotes,
                        onCompassToggleClick = { viewModel.toggleNorthLock() },
                        onToolsLongClick = {}
                    )
                }
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(3_000L) {
            capturedViewModels.firstOrNull()?.uiState?.value?.hasLoadedPreferences == true
        }

        val vm1 = capturedViewModels[0]
        val ctrl1 = capturedControllers[0]
        val deps1 = capturedDeps[0]
        val store1 = capturedStores[0]

        // Mutate camera, style, and internet immediately before leaving Map
        val initialInternet = vm1.uiState.value.isInternetEnabled
        val targetStyle = MapSourceRepository.OPEN_FREE_MAP_BRIGHT
        vm1.selectStyle(targetStyle)
        vm1.toggleInternet()
        vm1.onCameraMoved(lat = 48.8566, lng = 2.3522, zoom = 14.75, bearing = 33.5)

        // Immediately leave Map -> Notes without waiting on async coroutine scheduling
        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitUntil(5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            coordinator.state.value is MapActivationState.Inactive
        }
        composeTestRule.waitForIdle()

        assertEquals(MapActivationState.Inactive, coordinator.state.value)
        assertTrue(vm1.isCleared)
        assertTrue(ctrl1.isReleased)
        assertFalse(deps1.networkMonitor.isCallbackRegistered)

        // Return Notes -> Map
        composeTestRule.onNodeWithTag("notes_return_to_map_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(3_000L) {
            capturedViewModels.size == 2 && capturedViewModels[1].uiState.value.hasLoadedPreferences
        }

        val vm2 = capturedViewModels[1]
        val ctrl2 = capturedControllers[1]
        val deps2 = capturedDeps[1]
        val store2 = capturedStores[1]

        // Process SDK initialized once only; fresh Map session objects created
        assertEquals(1, sdkInitCalls.get())
        assertEquals(2, mapViewCreations.get())
        assertEquals(1, mapViewDestroys.get())
        assertNotSame(vm1, vm2)
        assertNotSame(ctrl1, ctrl2)
        assertNotSame(deps1, deps2)
        assertNotSame(store1, store2)
        assertFalse(vm2.isCleared)
        assertFalse(ctrl2.isReleased)
        assertTrue(deps2.networkMonitor.isCallbackRegistered)

        // Persisted camera, style, and internet preference restored accurately
        val restoredState = vm2.uiState.value
        assertEquals(48.8566, restoredState.camera.latitude, 1e-4)
        assertEquals(2.3522, restoredState.camera.longitude, 1e-4)
        assertEquals(14.75, restoredState.camera.zoom, 1e-4)
        assertEquals(33.5, restoredState.camera.bearing, 1e-4)
        assertEquals(targetStyle.id, restoredState.currentStyle.id)
        assertEquals(!initialInternet, restoredState.isInternetEnabled)
    }

    @Test
    fun ab1_05_tapPlacemarksAndOtherBottomControlsRemainIntact() {
        val placesTapCount = AtomicInteger(0)
        val settingsTapCount = AtomicInteger(0)
        val mapsTapCount = AtomicInteger(0)
        val compassTapCount = AtomicInteger(0)
        val openNotesCalls = AtomicInteger(0)

        composeTestRule.setContent {
            BottomNavigationBar(
                isNorthLocked = false,
                onSettingsClick = { settingsTapCount.incrementAndGet() },
                onSettingsLongClick = {},
                onMapsClick = { mapsTapCount.incrementAndGet() },
                onPlacesClick = { placesTapCount.incrementAndGet() },
                onPlacesLongClick = { openNotesCalls.incrementAndGet() },
                onCompassToggleClick = { compassTapCount.incrementAndGet() },
                onToolsLongClick = {}
            )
        }
        composeTestRule.waitForIdle()

        // Tap Placemarks opens Places popup, NOT Notes
        composeTestRule.onNodeWithTag("nav_button_places").performClick()
        composeTestRule.onNodeWithTag("nav_button_settings").performClick()
        composeTestRule.onNodeWithTag("nav_button_maps").performClick()
        composeTestRule.onNodeWithTag("nav_button_compass_tools").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, placesTapCount.get())
        assertEquals(1, settingsTapCount.get())
        assertEquals(1, mapsTapCount.get())
        assertEquals(1, compassTapCount.get())
        assertEquals(0, openNotesCalls.get())

        // Long-press Placemarks opens Notes
        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals(1, openNotesCalls.get())
        assertEquals(1, placesTapCount.get())
    }

    @Test
    fun ab1_06_repeatedSwitchingAndIdempotentDeactivationAreLeakFree() {
        val sdkInitCalls = AtomicInteger(0)
        val mapViewCreations = AtomicInteger(0)
        val mapViewDestroys = AtomicInteger(0)
        val viewModels = mutableListOf<MapViewModel>()
        val controllers = mutableListOf<MapEngineController>()
        val depsList = mutableListOf<MapRuntimeDependencies>()

        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { sdkInitCalls.incrementAndGet() },
            dependenciesFactory = { ctx ->
                MapRuntimeDependencies(ctx).also { depsList.add(it) }
            },
            activeSessionContent = { modifier, _, viewModel, onOpenNotes, registerTeardown ->
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val controller = remember {
                    mapViewCreations.incrementAndGet()
                    viewModel.createMapEngineController(
                        initialLat = uiState.camera.latitude,
                        initialLng = uiState.camera.longitude,
                        initialZoom = uiState.camera.zoom,
                        initialBearing = uiState.camera.bearing,
                        onCameraMoved = { lat, lng, zoom, bearing ->
                            viewModel.onCameraMoved(lat, lng, zoom, bearing)
                        }
                    )
                }
                remember {
                    viewModels.add(viewModel)
                    controllers.add(controller)
                    true
                }
                DisposableEffect(controller) {
                    registerTeardown { controller.release() }
                    onDispose {
                        registerTeardown(null)
                        controller.release()
                        mapViewDestroys.incrementAndGet()
                    }
                }
                Box(modifier = modifier.fillMaxSize()) {
                    BottomNavigationBar(
                        isNorthLocked = uiState.camera.isNorthLocked,
                        onSettingsClick = {},
                        onSettingsLongClick = {},
                        onMapsClick = {},
                        onPlacesClick = {},
                        onPlacesLongClick = onOpenNotes,
                        onCompassToggleClick = { viewModel.toggleNorthLock() },
                        onToolsLongClick = {}
                    )
                }
            }
        )

        // Deactivating while already inactive is a safe no-op
        runBlocking {
            assertEquals(MapDeactivationResult.AlreadyInactive, coordinator.deactivate())
        }
        assertEquals(0, coordinator.deactivationCount)

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()

        // Cycle 1: Map -> Notes -> Map
        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitUntil(3_000L) { coordinator.state.value is MapActivationState.Inactive }
        composeTestRule.waitForIdle()
        // Repeated direct deactivate while already inactive must remain idempotent
        runBlocking {
            assertEquals(MapDeactivationResult.AlreadyInactive, coordinator.deactivate())
        }

        composeTestRule.onNodeWithTag("notes_return_to_map_button").performClick()
        composeTestRule.waitForIdle()

        // Cycle 2: Map -> Notes -> Map
        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitUntil(3_000L) { coordinator.state.value is MapActivationState.Inactive }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("notes_return_to_map_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, sdkInitCalls.get())
        assertEquals(3, mapViewCreations.get())
        assertEquals(2, mapViewDestroys.get())
        assertEquals(3, viewModels.size)
        assertEquals(3, depsList.size)
        assertEquals(2, coordinator.deactivationCount)

        // Stale callbacks on prior controllers/ViewModels do not affect active session
        val staleController = controllers[0]
        staleController.onEmptyMapSingleTap = { _, _ -> throw AssertionError("Stale callback should be cleared") }
        staleController.release()
        assertTrue(viewModels[0].isCleared)
        assertTrue(viewModels[1].isCleared)
        assertFalse(viewModels[2].isCleared)
        assertFalse(depsList[0].networkMonitor.isCallbackRegistered)
        assertFalse(depsList[1].networkMonitor.isCallbackRegistered)
        assertTrue(depsList[2].networkMonitor.isCallbackRegistered)
    }

    @Test
    fun ab1_07_activationFailureRecoveryResetsCleanlyAndRetriesWithoutDuplicateInit() {
        val shouldFail = AtomicBoolean(true)
        val sdkInitCalls = AtomicInteger(0)
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer {
                if (shouldFail.get()) {
                    throw IllegalStateException("Simulated SDK failure")
                }
                sdkInitCalls.incrementAndGet()
            }
        )

        val failed = coordinator.activate(context)
        assertTrue(failed is MapActivationResult.Failed)
        assertFalse(coordinator.isRuntimeInitialized)
        assertNull(coordinator.dependenciesOrNull())
        assertNull(coordinator.sessionViewModelStoreOrNull())
        assertEquals(0, sdkInitCalls.get())

        // Deactivating from Failed state resets to Inactive cleanly
        runBlocking {
            assertEquals(MapDeactivationResult.Deactivated, coordinator.deactivate())
        }
        assertEquals(MapActivationState.Inactive, coordinator.state.value)
        assertFalse(coordinator.isRuntimeInitialized)

        // Retry succeeds once runtime becomes available
        shouldFail.set(false)
        assertEquals(MapActivationResult.Activated, coordinator.activate(context))
        assertEquals(MapActivationState.Active, coordinator.state.value)
        assertTrue(coordinator.isRuntimeInitialized)
        assertEquals(1, sdkInitCalls.get())
        assertNotNull(coordinator.dependenciesOrNull())
        assertNotNull(coordinator.sessionViewModelStoreOrNull())
    }

    // -------------------------------------------------------------------------
    // Phase AB-1 Correction Pass — Camera Persistence, MapView Lifecycle,
    // and Activation Recovery Tests
    // -------------------------------------------------------------------------

    private class ControllableDataStore(
        private val delegate: DataStore<Preferences>
    ) : DataStore<Preferences> {
        @Volatile
        var failNextUpdateWith: Throwable? = null

        @Volatile
        var failUpdatesPersistentlyWith: Throwable? = null

        @Volatile
        var updateGate: CompletableDeferred<Unit>? = null

        @Volatile
        var onUpdateEntered: CompletableDeferred<Unit>? = null

        val updateCount = AtomicInteger(0)

        override val data: Flow<Preferences>
            get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            onUpdateEntered?.complete(Unit)
            updateGate?.await()
            failUpdatesPersistentlyWith?.let { throw it }
            failNextUpdateWith?.let { err ->
                failNextUpdateWith = null
                throw err
            }
            updateCount.incrementAndGet()
            return delegate.updateData(transform)
        }
    }

    private fun createIsolatedTestDataStore(name: String): ControllableDataStore {
        val dir = File(context.filesDir, "datastore_test_$name")
        dir.mkdirs()
        val file = File(dir, "$name.preferences_pb").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file }
        )
        return ControllableDataStore(raw)
    }

    private fun waitUntilWithMainLooper(
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        while (System.currentTimeMillis() < deadline) {
            mainLooper.idle()
            if (condition()) return
            Thread.sleep(10L)
        }
        mainLooper.idle()
        assertTrue("Condition still not satisfied after ${timeoutMs}ms", condition())
    }

    @Test
    fun ab1_fix_01_rapidCameraMovesAndImmediateMapToNotesPersistsLatestNonBlocking() {
        val dataStore = createIsolatedTestDataStore("fix01_${System.nanoTime()}")
        val capturedViewModels = mutableListOf<MapViewModel>()

        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { },
            dependenciesFactory = { ctx ->
                MapRuntimeDependencies(
                    context = ctx,
                    preferencesRepositoryProvider = { appCtx ->
                        AppPreferencesRepository(appCtx, dataStore)
                    }
                )
            },
            activeSessionContent = { modifier, _, viewModel, onOpenNotes, registerTeardown ->
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                remember {
                    capturedViewModels.add(viewModel)
                    true
                }
                DisposableEffect(viewModel) {
                    registerTeardown { }
                    onDispose { registerTeardown(null) }
                }
                Box(modifier = modifier.fillMaxSize()) {
                    BottomNavigationBar(
                        isNorthLocked = uiState.camera.isNorthLocked,
                        onSettingsClick = {},
                        onSettingsLongClick = {},
                        onMapsClick = {},
                        onPlacesClick = {},
                        onPlacesLongClick = onOpenNotes,
                        onCompassToggleClick = { viewModel.toggleNorthLock() },
                        onToolsLongClick = {}
                    )
                }
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitUntil(5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            capturedViewModels.firstOrNull()?.uiState?.value?.hasLoadedPreferences == true
        }

        val vm1 = capturedViewModels[0]
        // Rapid burst of camera moves right before leaving Map -> Notes
        vm1.onCameraMoved(10.0, 20.0, 5.0, 0.0)
        vm1.onCameraMoved(11.0, 21.0, 6.0, 10.0)
        vm1.onCameraMoved(12.0, 22.0, 7.0, 20.0)
        vm1.onCameraMoved(51.5074, -0.1278, 15.25, 88.0)

        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitUntil(5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            coordinator.state.value is MapActivationState.Inactive
        }
        composeTestRule.waitForIdle()

        // Confirm directly in DataStore AND on Map re-activation
        val stored = runBlocking {
            AppPreferencesRepository(context, dataStore).userPreferencesFlow.first()
        }
        assertEquals(51.5074, stored.cameraState.latitude, 1e-4)
        assertEquals(-0.1278, stored.cameraState.longitude, 1e-4)
        assertEquals(15.25, stored.cameraState.zoom, 1e-4)
        assertEquals(88.0, stored.cameraState.bearing, 1e-4)

        composeTestRule.onNodeWithTag("notes_return_to_map_button").performClick()
        composeTestRule.waitUntil(5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            capturedViewModels.size == 2 && capturedViewModels[1].uiState.value.hasLoadedPreferences
        }
        val restored = capturedViewModels[1].uiState.value.camera
        assertEquals(51.5074, restored.latitude, 1e-4)
        assertEquals(-0.1278, restored.longitude, 1e-4)
        assertEquals(15.25, restored.zoom, 1e-4)
        assertEquals(88.0, restored.bearing, 1e-4)
    }

    @Test
    fun ab1_fix_02_earlierSlowPreferenceWriteCannotOverwriteFinalCameraState() {
        val dataStore = createIsolatedTestDataStore("fix02_${System.nanoTime()}")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val deps = MapRuntimeDependencies(
            context = app,
            preferencesRepositoryProvider = { ctx -> AppPreferencesRepository(ctx, dataStore) }
        )
        val viewModel = deps.viewModelFactory(app).create(MapViewModel::class.java)
        waitUntilWithMainLooper { viewModel.uiState.value.hasLoadedPreferences }

        // Gate the first queued write in DataStore so an earlier write is still in-flight
        val firstWriteEntered = CompletableDeferred<Unit>()
        val allowFirstWriteToFinish = CompletableDeferred<Unit>()
        dataStore.onUpdateEntered = firstWriteEntered
        dataStore.updateGate = allowFirstWriteToFinish

        viewModel.onCameraMoved(1.111, 2.222, 8.0, 5.0)
        waitUntilWithMainLooper { firstWriteEntered.isCompleted }

        // While the earlier write is paused in-flight, move camera to final coordinates and begin shutdown
        viewModel.onCameraMoved(35.6762, 139.6503, 16.5, 45.0)
        dataStore.onUpdateEntered = null

        val flushResult = CompletableDeferred<Result<Unit>>()
        val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        shutdownScope.launch {
            flushResult.complete(viewModel.prepareAndFlushForDeactivation())
        }

        // Release the earlier slow write AFTER shutdown already froze and captured final coordinates
        allowFirstWriteToFinish.complete(Unit)
        dataStore.updateGate = null

        waitUntilWithMainLooper { flushResult.isCompleted }
        val result = runBlocking { flushResult.await() }
        assertTrue(result.isSuccess)

        val persisted = runBlocking {
            deps.preferencesRepository.userPreferencesFlow.first().cameraState
        }
        assertEquals(35.6762, persisted.latitude, 1e-4)
        assertEquals(139.6503, persisted.longitude, 1e-4)
        assertEquals(16.5, persisted.zoom, 1e-4)
        assertEquals(45.0, persisted.bearing, 1e-4)
        shutdownScope.cancel()
    }

    @Test
    fun ab1_fix_03_cancelledShutdownStopsCleanlyWithoutCorruptingSessionOrFreezeState() {
        val dataStore = createIsolatedTestDataStore("fix03_${System.nanoTime()}")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val deps = MapRuntimeDependencies(
            context = app,
            preferencesRepositoryProvider = { ctx -> AppPreferencesRepository(ctx, dataStore) }
        )
        val viewModel = deps.viewModelFactory(app).create(MapViewModel::class.java)
        waitUntilWithMainLooper { viewModel.uiState.value.hasLoadedPreferences }

        val writeEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        dataStore.onUpdateEntered = writeEntered
        dataStore.updateGate = gate

        viewModel.onCameraMoved(40.7128, -74.0060, 12.0, 0.0)
        waitUntilWithMainLooper { writeEntered.isCompleted }

        val cancellationObserved = CompletableDeferred<Boolean>()
        val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = shutdownScope.launch {
            try {
                viewModel.prepareAndFlushForDeactivation()
                cancellationObserved.complete(false)
            } catch (_: CancellationException) {
                cancellationObserved.complete(true)
            }
        }

        // Cancel shutdown while awaiting earlier write
        job.cancel()
        gate.complete(Unit)
        dataStore.updateGate = null
        dataStore.onUpdateEntered = null

        waitUntilWithMainLooper { cancellationObserved.isCompleted }
        assertTrue(runBlocking { cancellationObserved.await() })
        assertFalse(viewModel.isCleared)

        // Subsequent camera move on still-active session must still be accepted & persisted cleanly
        viewModel.onCameraMoved(41.9028, 12.4964, 14.0, 15.0)
        val finalResultDeferred = CompletableDeferred<Result<Unit>>()
        shutdownScope.launch {
            finalResultDeferred.complete(viewModel.prepareAndFlushForDeactivation())
        }
        waitUntilWithMainLooper { finalResultDeferred.isCompleted }
        assertTrue(runBlocking { finalResultDeferred.await() }.isSuccess)

        val persisted = runBlocking {
            deps.preferencesRepository.userPreferencesFlow.first().cameraState
        }
        assertEquals(41.9028, persisted.latitude, 1e-4)
        assertEquals(12.4964, persisted.longitude, 1e-4)
        shutdownScope.cancel()
    }

    @Test
    fun ab1_fix_04_dataStoreWriteFailureSurfacedExplicitlyAndRetrySucceedsCleanly() {
        val dataStore = createIsolatedTestDataStore("fix04_${System.nanoTime()}")
        val capturedViewModels = mutableListOf<MapViewModel>()

        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { },
            dependenciesFactory = { ctx ->
                MapRuntimeDependencies(
                    context = ctx,
                    preferencesRepositoryProvider = { appCtx ->
                        AppPreferencesRepository(appCtx, dataStore)
                    }
                )
            },
            activeSessionContent = { modifier, _, viewModel, onOpenNotes, registerTeardown ->
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                remember {
                    capturedViewModels.add(viewModel)
                    true
                }
                DisposableEffect(viewModel) {
                    registerTeardown { }
                    onDispose { registerTeardown(null) }
                }
                Box(modifier = modifier.fillMaxSize()) {
                    BottomNavigationBar(
                        isNorthLocked = uiState.camera.isNorthLocked,
                        onSettingsClick = {},
                        onSettingsLongClick = {},
                        onMapsClick = {},
                        onPlacesClick = {},
                        onPlacesLongClick = onOpenNotes,
                        onCompassToggleClick = { viewModel.toggleNorthLock() },
                        onToolsLongClick = {}
                    )
                }
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitUntil(3_000L) {
            capturedViewModels.firstOrNull()?.uiState?.value?.hasLoadedPreferences == true
        }

        val vm = capturedViewModels[0]
        dataStore.failUpdatesPersistentlyWith = IOException("Simulated disk write failure")
        vm.onCameraMoved(52.5200, 13.4050, 13.5, 12.0)

        // Attempt Map -> Notes while DataStore persistence fails
        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitUntil(3_000L) {
            composeTestRule.onAllNodesWithTag("map_deactivation_error_dialog").fetchSemanticsNodes().isNotEmpty()
        }

        // Failure MUST block silent completion and remain on Active Map session
        composeTestRule.onNodeWithTag("map_deactivation_error_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Simulated disk write failure", substring = true).assertIsDisplayed()
        assertEquals(MapActivationState.Active, coordinator.state.value)
        assertFalse(vm.isCleared)
        assertEquals(0, composeTestRule.onAllNodesWithTag("notes_placeholder_screen").fetchSemanticsNodes().size)

        // Clear disk fault and tap Retry in error dialog -> succeeds cleanly
        dataStore.failUpdatesPersistentlyWith = null
        composeTestRule.onNodeWithTag("map_deactivation_retry_button").performClick()
        composeTestRule.waitUntil(3_000L) {
            coordinator.state.value is MapActivationState.Inactive
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("notes_placeholder_screen").assertIsDisplayed()
        assertTrue(vm.isCleared)
        val persisted = runBlocking {
            AppPreferencesRepository(context, dataStore).userPreferencesFlow.first().cameraState
        }
        assertEquals(52.5200, persisted.latitude, 1e-4)
        assertEquals(13.4050, persisted.longitude, 1e-4)
    }

    @Test
    fun ab1_fix_05_unmovedDefaultCameraDoesNotOverwriteSavedCameraAndPreservesPreferences() {
        val dataStore = createIsolatedTestDataStore("fix05_${System.nanoTime()}")
        runBlocking {
            val repo = AppPreferencesRepository(context, dataStore)
            repo.saveCameraState(48.8566, 2.3522, 14.75, 33.5)
            repo.saveSelectedStyleId(MapSourceRepository.OPEN_FREE_MAP_BRIGHT.id)
            repo.saveInternetEnabled(false)
            repo.saveNorthLocked(true)
        }

        val capturedViewModels = mutableListOf<MapViewModel>()
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer { },
            dependenciesFactory = { ctx ->
                MapRuntimeDependencies(
                    context = ctx,
                    preferencesRepositoryProvider = { appCtx ->
                        AppPreferencesRepository(appCtx, dataStore)
                    }
                )
            },
            activeSessionContent = { modifier, _, viewModel, onOpenNotes, registerTeardown ->
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                remember {
                    capturedViewModels.add(viewModel)
                    true
                }
                DisposableEffect(viewModel) {
                    registerTeardown { }
                    onDispose { registerTeardown(null) }
                }
                Box(modifier = modifier.fillMaxSize()) {
                    BottomNavigationBar(
                        isNorthLocked = uiState.camera.isNorthLocked,
                        onSettingsClick = {},
                        onSettingsLongClick = {},
                        onMapsClick = {},
                        onPlacesClick = {},
                        onPlacesLongClick = onOpenNotes,
                        onCompassToggleClick = { viewModel.toggleNorthLock() },
                        onToolsLongClick = {}
                    )
                }
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()

        // Immediately exit Map -> Notes BEFORE moving camera (even before/right as prefs hydrate)
        composeTestRule.onNodeWithTag("nav_button_places").performTouchInput { longClick() }
        composeTestRule.waitUntil(3_000L) { coordinator.state.value is MapActivationState.Inactive }
        composeTestRule.waitForIdle()

        val afterLeave = runBlocking {
            AppPreferencesRepository(context, dataStore).userPreferencesFlow.first()
        }
        assertEquals(48.8566, afterLeave.cameraState.latitude, 1e-4)
        assertEquals(2.3522, afterLeave.cameraState.longitude, 1e-4)
        assertEquals(14.75, afterLeave.cameraState.zoom, 1e-4)
        assertEquals(33.5, afterLeave.cameraState.bearing, 1e-4)
        assertEquals(MapSourceRepository.OPEN_FREE_MAP_BRIGHT.id, afterLeave.selectedStyleId)
        assertFalse(afterLeave.isInternetEnabled)
        assertTrue(afterLeave.cameraState.isNorthLocked)
    }

    private class RecordingMapViewTarget(
        private val id: String,
        private val events: MutableList<String>
    ) : MapViewLifecycleTarget {
        override fun onCreate() { events.add("$id:ON_CREATE") }
        override fun onStart() { events.add("$id:ON_START") }
        override fun onResume() { events.add("$id:ON_RESUME") }
        override fun onPause() { events.add("$id:ON_PAUSE") }
        override fun onStop() { events.add("$id:ON_STOP") }
        override fun onDestroy() { events.add("$id:ON_DESTROY") }
        override fun onLowMemory() { events.add("$id:ON_LOW_MEMORY") }
    }

    @Test
    fun ab1_fix_06_internalMapToNotesDisposesWithPauseStopDestroyAfterControllerRelease() {
        val events = mutableListOf<String>()
        val target = RecordingMapViewTarget("mv1", events)
        val lifecycleCoordinator = MapViewLifecycleCoordinator(target, "mv1")
        val controller = MapEngineController(
            context = context,
            initialLat = 37.7,
            initialLng = -122.4,
            initialZoom = 11.0,
            initialBearing = 0.0,
            onCameraMoved = { _, _, _, _ -> }
        )

        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        lifecycleCoordinator.dispatchCreate()
        lifecycleCoordinator.dispatchStart()
        lifecycleCoordinator.dispatchResume()
        assertTrue(lifecycleCoordinator.isStarted)
        assertTrue(lifecycleCoordinator.isResumed)

        // Internal Map -> Notes happens while host Activity remains RESUMED
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        val destroyed = lifecycleCoordinator.dispatchDestroy("COMPOSE_DISPOSAL") {
            controller.release()
            events.add("CONTROLLER_RELEASED")
        }
        assertTrue(destroyed)
        assertTrue(controller.isReleased)
        assertTrue(lifecycleCoordinator.isDestroyed)
        assertFalse(lifecycleCoordinator.isResumed)
        assertFalse(lifecycleCoordinator.isStarted)

        assertEquals(
            listOf(
                "mv1:ON_CREATE",
                "mv1:ON_START",
                "mv1:ON_RESUME",
                "CONTROLLER_RELEASED",
                "mv1:ON_PAUSE",
                "mv1:ON_STOP",
                "mv1:ON_DESTROY"
            ),
            events
        )
    }

    @Test
    fun ab1_fix_07_activityPauseStopFollowedByInternalDisposeDoesNotDuplicatePauseOrStop() {
        val events = mutableListOf<String>()
        val target = RecordingMapViewTarget("mv1", events)
        val lifecycleCoordinator = MapViewLifecycleCoordinator(target, "mv1")

        lifecycleCoordinator.dispatchCreate()
        lifecycleCoordinator.dispatchStart()
        lifecycleCoordinator.dispatchResume()
        // Host Activity goes into background: ON_PAUSE -> ON_STOP
        lifecycleCoordinator.dispatchPause()
        lifecycleCoordinator.dispatchStop()
        assertFalse(lifecycleCoordinator.isResumed)
        assertFalse(lifecycleCoordinator.isStarted)

        // Later Compose disposal while already stopped
        lifecycleCoordinator.dispatchDestroy("COMPOSE_DISPOSAL") {
            events.add("CONTROLLER_RELEASED")
        }

        assertEquals(
            listOf(
                "mv1:ON_CREATE",
                "mv1:ON_START",
                "mv1:ON_RESUME",
                "mv1:ON_PAUSE",
                "mv1:ON_STOP",
                "CONTROLLER_RELEASED",
                "mv1:ON_DESTROY"
            ),
            events
        )
        assertEquals(1, events.count { it == "mv1:ON_PAUSE" })
        assertEquals(1, events.count { it == "mv1:ON_STOP" })
        assertEquals(1, events.count { it == "mv1:ON_DESTROY" })
    }

    @Test
    fun ab1_fix_08_repeatedTeardownIdempotentAndNotesToMapCreatesFreshTarget() {
        val events = mutableListOf<String>()
        val mv1 = MapViewLifecycleCoordinator(RecordingMapViewTarget("mv1", events), "mv1")

        mv1.dispatchCreate()
        mv1.dispatchStart()
        mv1.dispatchResume()
        assertTrue(mv1.dispatchDestroy("ACTIVITY_ON_DESTROY") { events.add("CTRL1_RELEASE") })
        assertFalse(mv1.dispatchDestroy("COMPOSE_DISPOSAL") { events.add("CTRL1_RELEASE_DUP") })
        assertEquals(1, events.count { it == "mv1:ON_DESTROY" })
        assertEquals(1, events.count { it == "CTRL1_RELEASE" })

        // Subsequent Notes -> Map constructs a fresh MapView + coordinator + session
        val mv2 = MapViewLifecycleCoordinator(RecordingMapViewTarget("mv2", events), "mv2")
        mv2.dispatchCreate()
        mv2.dispatchStart()
        mv2.dispatchResume()
        assertTrue(mv2.isCreated)
        assertTrue(mv2.isStarted)
        assertTrue(mv2.isResumed)
        assertFalse(mv2.isDestroyed)
        assertEquals("mv2:ON_RESUME", events.last())
    }

    @Test
    fun ab1_fix_09_onLowMemoryIgnoredOnceDestroyed() {
        val events = mutableListOf<String>()
        val coordinator = MapViewLifecycleCoordinator(RecordingMapViewTarget("mv1", events), "mv1")
        coordinator.dispatchCreate()
        coordinator.dispatchLowMemory()
        coordinator.dispatchDestroy("COMPOSE_DISPOSAL")
        coordinator.dispatchLowMemory()

        assertEquals(1, events.count { it == "mv1:ON_LOW_MEMORY" })
    }

    @Test
    fun ab1_fix_10_synchronousActivationFailureShowsRecoveryUiRetriesOnceAndAllowsOpenNotes() {
        val shouldFail = AtomicBoolean(true)
        val sdkInitCalls = AtomicInteger(0)
        val depsCreated = AtomicInteger(0)
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer {
                if (shouldFail.get()) {
                    throw IllegalStateException("Simulated native MapLibre load failure")
                }
                sdkInitCalls.incrementAndGet()
            },
            dependenciesFactory = { ctx ->
                depsCreated.incrementAndGet()
                MapRuntimeDependencies(ctx)
            },
            activeSessionContent = { _, _, _, _, _ ->
                Text("Map Ready UI")
            }
        )

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.NOTES,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()

        // Request Notes -> Map while runtime init fails synchronously
        composeTestRule.onNodeWithTag("notes_return_to_map_button").performClick()
        composeTestRule.waitForIdle()

        // Must show recovery screen with reason, Retry, and Open Notes (NEVER blank Map)
        composeTestRule.onNodeWithTag("map_activation_error_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_activation_error_message")
            .assertTextContains("Simulated native MapLibre load failure")
        composeTestRule.onNodeWithTag("map_activation_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_activation_open_notes_button").assertIsDisplayed()
        assertEquals(0, sdkInitCalls.get())
        assertEquals(0, depsCreated.get())

        // Open Notes remains fully usable while Map activation is failing
        composeTestRule.onNodeWithTag("map_activation_open_notes_button").performClick()
        composeTestRule.waitUntil(3_000L) {
            composeTestRule.onAllNodesWithTag("notes_placeholder_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("notes_placeholder_screen").assertIsDisplayed()
        assertEquals(MapActivationState.Inactive, coordinator.state.value)

        // Return to Map (still failing) -> Recovery UI again -> clear error -> tap Retry
        composeTestRule.onNodeWithTag("notes_return_to_map_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("map_activation_error_screen").assertIsDisplayed()

        shouldFail.set(false)
        composeTestRule.onNodeWithTag("map_activation_retry_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Map Ready UI").assertIsDisplayed()
        assertEquals(MapActivationState.Active, coordinator.state.value)
        assertEquals(1, sdkInitCalls.get())
        assertEquals(1, depsCreated.get())
        assertEquals(1, coordinator.sessionActivationCount)
    }

    @Test
    fun ab1_fix_11_asynchronousPostRequestFailureTransitionsToRecoveryAndRecoversCleanly() {
        val fakeBoundary = object : MapActivationBoundary {
            private val _state = MutableStateFlow<MapActivationState>(MapActivationState.Inactive)
            override val state: StateFlow<MapActivationState> = _state.asStateFlow()
            override val isRuntimeInitialized: Boolean
                get() = _state.value is MapActivationState.Active

            var activationAttempts = 0
            var succeedOnAttempt = false

            override fun activate(context: Context): MapActivationResult {
                activationAttempts++
                return if (succeedOnAttempt) {
                    _state.value = MapActivationState.Active
                    MapActivationResult.Activated
                } else {
                    _state.value = MapActivationState.Activating
                    MapActivationResult.Activated
                }
            }

            override suspend fun deactivate(): MapDeactivationResult {
                _state.value = MapActivationState.Inactive
                return MapDeactivationResult.Deactivated
            }

            fun failAsynchronously(reason: String) {
                _state.value = MapActivationState.Failed(reason)
            }

            @Composable
            override fun Content(modifier: Modifier, onOpenNotes: () -> Unit) {
                Text("Async Map Active UI")
            }
        }

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = fakeBoundary
            )
        }
        composeTestRule.waitForIdle()

        // While activating asynchronously, shows loading screen instead of blank screen
        composeTestRule.onNodeWithTag("map_activation_loading_screen").assertIsDisplayed()

        // Async failure arrives AFTER Map was requested
        fakeBoundary.failAsynchronously("Async MapLibre native bridge crash")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("map_activation_error_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_activation_error_message")
            .assertTextContains("Async MapLibre native bridge crash")

        // Retry succeeds cleanly
        fakeBoundary.succeedOnAttempt = true
        composeTestRule.onNodeWithTag("map_activation_retry_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Async Map Active UI").assertIsDisplayed()
        assertEquals(2, fakeBoundary.activationAttempts)
    }

    @Test
    fun ab1_fix_12_coldLaunchFailureIntoMapShowsRecoveryInsteadOfBlankScreen() {
        val coordinator = MapActivationCoordinator(
            runtimeInitializer = MapRuntimeInitializer {
                throw IllegalStateException("Cold-launch renderer unavailable")
            }
        )
        coordinator.activate(context)
        assertTrue(coordinator.state.value is MapActivationState.Failed)

        composeTestRule.setContent {
            AppHostShell(
                initialDestination = AppHostDestination.MAP,
                mapFeature = coordinator
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("map_activation_error_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_activation_error_message")
            .assertTextContains("Cold-launch renderer unavailable")
        composeTestRule.onNodeWithTag("map_activation_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_activation_open_notes_button").assertIsDisplayed()
    }
}
