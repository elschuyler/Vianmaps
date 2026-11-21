package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.AppDatabaseContract
import com.example.diagnostics.DiagnosticRepository
import com.example.diagnostics.LogSeverity
import com.example.map.activation.MapActivationCoordinator
import com.example.map.activation.MapActivationResult
import com.example.map.activation.MapActivationState
import com.example.map.activation.MapFeatureActivation
import com.example.map.activation.MapRuntimeInitializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
}
