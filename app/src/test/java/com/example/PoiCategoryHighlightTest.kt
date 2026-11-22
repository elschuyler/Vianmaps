package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.OsmPoiRecord
import com.example.data.osm.OsmOfflineDataRepository
import com.example.data.osm.PoiCategoryHighlightDefaults
import com.example.data.osm.PoiHighlightCategory
import com.example.data.osm.PoiLatLngCorner
import com.example.data.osm.PoiViewportBounds
import com.example.diagnostics.DiagnosticRepository
import com.example.map.activation.MapRuntimeDependencies
import com.example.map.engine.MapEngineController
import com.example.network.AppNetworkPolicy
import com.example.ui.components.PoiHighlightPopupContent
import com.example.ui.screens.MapViewModel
import com.example.ui.screens.PoiCategoryHighlightUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PoiCategoryHighlightTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var osmRepo: OsmOfflineDataRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DiagnosticRepository.clear(logNotice = false)
        AppNetworkPolicy.setInternetEnabled(true)
        db = AppDatabase.getInstance(context)
        runBlocking {
            db.osmPoiDao().clearAllPois()
        }
        osmRepo = OsmOfflineDataRepository(context, db)
    }

    @After
    fun tearDown() {
        runBlocking {
            db.osmPoiDao().clearAllPois()
        }
        AppNetworkPolicy.setInternetEnabled(true)
    }

    private fun pumpLooperUntil(timeoutMs: Long = 3000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25L))
            Thread.sleep(15L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun test01_categoryToMvtClassificationHandlesSubclassesAndHospitalPharmacyOverlap() {
        val pharmacyUnderHospital = OsmPoiRecord(
            osmId = 1L, name = "Corner Pharmacy", category = "hospital", subCategory = "pharmacy",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val trueHospital = OsmPoiRecord(
            osmId = 2L, name = "City Hospital", category = "hospital", subCategory = "hospital",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val supermarketGrocery = OsmPoiRecord(
            osmId = 3L, name = "Fresh Mart", category = "shop", subCategory = "supermarket",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val busStop = OsmPoiRecord(
            osmId = 4L, name = "Main Ave Stop", category = "bus", subCategory = "bus_stop",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val standaloneAtm = OsmPoiRecord(
            osmId = 5L, name = "ATM", category = "bank", subCategory = "atm",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val bankWithoutAtm = OsmPoiRecord(
            osmId = 6L, name = "Metro Bank", category = "bank", subCategory = "bank",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val generalShop = OsmPoiRecord(
            osmId = 7L, name = "Corner Bakery", category = "shop", subCategory = "bakery",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val restaurant = OsmPoiRecord(
            osmId = 8L, name = "Bistro 24", category = "restaurant", subCategory = "restaurant",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val cafe = OsmPoiRecord(
            osmId = 9L, name = "Roast Cafe", category = "cafe", subCategory = "cafe",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val fuel = OsmPoiRecord(
            osmId = 10L, name = "Highway Fuel", category = "fuel", subCategory = "fuel",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val school = OsmPoiRecord(
            osmId = 11L, name = "Oak School", category = "school", subCategory = "school",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val parking = OsmPoiRecord(
            osmId = 12L, name = "Central Parking", category = "parking", subCategory = "parking",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )

        assertTrue(PoiHighlightCategory.PHARMACY.matches(pharmacyUnderHospital))
        assertFalse("Hospital must NOT misclassify class=hospital/subclass=pharmacy", PoiHighlightCategory.HOSPITAL.matches(pharmacyUnderHospital))
        assertTrue(PoiHighlightCategory.HOSPITAL.matches(trueHospital))
        assertTrue(PoiHighlightCategory.GROCERY.matches(supermarketGrocery))
        assertTrue(PoiHighlightCategory.SHOP.matches(generalShop))
        assertTrue(PoiHighlightCategory.RESTAURANT.matches(restaurant))
        assertTrue(PoiHighlightCategory.CAFE.matches(cafe))
        assertTrue(PoiHighlightCategory.BUS_STOP.matches(busStop))
        assertTrue(PoiHighlightCategory.ATM.matches(standaloneAtm))
        assertFalse("Bank without ATM subclass must not match ATM", PoiHighlightCategory.ATM.matches(bankWithoutAtm))
        assertTrue(PoiHighlightCategory.FUEL.matches(fuel))
        assertTrue(PoiHighlightCategory.SCHOOL.matches(school))
        assertTrue(PoiHighlightCategory.PARKING.matches(parking))

        val recordsByCategory = listOf(
            generalShop, supermarketGrocery, restaurant, cafe,
            pharmacyUnderHospital, trueHospital, busStop, standaloneAtm,
            fuel, school, parking
        )
        for (cat in PoiHighlightCategory.entries) {
            assertTrue("Category ${cat.id} must match at least one MVT record", recordsByCategory.any { cat.matches(it) })
        }
    }

    @Test
    fun test02_viewportFilteringAndNoSilent150LimitTruncation(): Unit = runBlocking {
        // Insert 185 cafes inside the viewport and 10 cafes outside
        val inside = (1..185).map { idx ->
            OsmPoiRecord(
                osmId = 10_000L + idx,
                name = "Cafe #$idx",
                category = "amenity",
                subCategory = "cafe",
                latitude = 39.9200 + (idx * 0.00001),
                longitude = 32.8500 + (idx * 0.00001),
                sourceTileZ = 14,
                sourceTileX = 9687,
                sourceTileY = 6199
            )
        }
        val outside = (1..10).map { idx ->
            OsmPoiRecord(
                osmId = 20_000L + idx,
                name = "Far Cafe #$idx",
                category = "cafe",
                subCategory = "cafe",
                latitude = 41.0000 + (idx * 0.001),
                longitude = 29.0000,
                sourceTileZ = 14,
                sourceTileX = 9000,
                sourceTileY = 6000
            )
        }
        db.osmPoiDao().insertPois(inside + outside)

        // Build rotated 4-corner viewport enclosing all 185 inside cafes
        val bounds = PoiViewportBounds.fromCorners(
            listOf(
                PoiLatLngCorner(39.9190, 32.8490),
                PoiLatLngCorner(39.9230, 32.8550),
                PoiLatLngCorner(39.9250, 32.8510),
                PoiLatLngCorner(39.9180, 32.8530)
            )
        )
        assertNotNull(bounds)
        assertFalse(bounds!!.crossesAntimeridian)

        val matches = osmRepo.queryCategoryHighlightedPois(bounds, listOf(PoiHighlightCategory.CAFE))
        assertEquals("All 185 visible cafes must be returned without 150 truncation", 185, matches.size)
        assertTrue(matches.none { it.poi.osmId >= 20_000L })
    }

    @Test
    fun test03_antimeridianCrossingQueriesBothSidesAndDeduplicates(): Unit = runBlocking {
        val westSide = OsmPoiRecord(
            osmId = 30_001L, name = "East Edge Fuel", category = "fuel", subCategory = "fuel",
            latitude = -16.5, longitude = 179.92, sourceTileZ = 14, sourceTileX = 16383, sourceTileY = 8900
        )
        val eastSide = OsmPoiRecord(
            osmId = 30_002L, name = "West Edge Fuel", category = "fuel", subCategory = "fuel",
            latitude = -16.5, longitude = -179.88, sourceTileZ = 14, sourceTileX = 0, sourceTileY = 8900
        )
        val boundaryExact = OsmPoiRecord(
            osmId = 30_003L, name = "Date Line Fuel", category = "fuel", subCategory = "fuel",
            latitude = -16.5, longitude = 180.0, sourceTileZ = 14, sourceTileX = 16383, sourceTileY = 8900
        )
        val nonCrossingCenter = OsmPoiRecord(
            osmId = 30_004L, name = "Prime Meridian Fuel", category = "fuel", subCategory = "fuel",
            latitude = -16.5, longitude = 0.0, sourceTileZ = 14, sourceTileX = 8192, sourceTileY = 8900
        )
        db.osmPoiDao().insertPois(listOf(westSide, eastSide, boundaryExact, nonCrossingCenter))

        val crossBounds = PoiViewportBounds.fromCorners(
            listOf(
                PoiLatLngCorner(-16.6, 179.8),
                PoiLatLngCorner(-16.4, -179.8),
                PoiLatLngCorner(-16.4, 179.85),
                PoiLatLngCorner(-16.6, -179.75)
            )
        )
        assertNotNull(crossBounds)
        assertTrue(crossBounds!!.crossesAntimeridian)
        assertEquals(2, crossBounds.longitudeRanges().size)

        val matches = osmRepo.queryCategoryHighlightedPois(crossBounds, listOf(PoiHighlightCategory.FUEL))
        val ids = matches.map { it.poi.osmId }.toSet()
        assertEquals(setOf(30_001L, 30_002L, 30_003L), ids)
        assertEquals("Zero duplicates allowed across split queries", 3, matches.size)
    }

    @Test
    fun test04_multiSelectDeselectClearAllAndSearchIndependence(): Unit = runBlocking {
        db.osmPoiDao().insertPois(
            listOf(
                OsmPoiRecord(40_001L, "Sun Cafe", "cafe", "cafe", 39.921, 32.851, 14, 1, 1),
                OsmPoiRecord(40_002L, "City Bakery", "shop", "bakery", 39.922, 32.852, 14, 1, 1),
                OsmPoiRecord(40_003L, "Shell Station", "fuel", "fuel", 39.923, 32.853, 14, 1, 1),
                OsmPoiRecord(40_004L, "Museum Hall", "tourism", "museum", 39.924, 32.854, 14, 1, 1)
            )
        )

        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app, MapRuntimeDependencies(app))
        val controller = MapEngineController(app, 39.922, 32.852, 14.0, 0.0, { _, _, _, _ -> })
        val viewport = PoiViewportBounds(39.91, 39.93, 32.84, 32.86)

        // Separate overlay source/layer IDs guaranteed
        assertNotEquals(MapEngineController.HIGHLIGHT_SOURCE_ID, MapEngineController.CATEGORY_HIGHLIGHT_SOURCE_ID)
        assertNotEquals(MapEngineController.HIGHLIGHT_CORE_LAYER_ID, MapEngineController.CATEGORY_HIGHLIGHT_CORE_LAYER_ID)

        // Search picks Museum Hall independently
        val museum = db.osmPoiDao().getPoiById(40_004L)!!
        viewModel.selectSearchResult(museum, controller)
        assertEquals(40_004L, controller.currentHighlightedPoi?.osmId)

        // Set viewport above threshold
        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 14.0, controller = controller, debounceMs = 0L)

        // Multi-select Cafe + Shop
        viewModel.toggleHighlightCategory("cafe", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.categoryMatchCounts["cafe"] == 1 }

        viewModel.toggleHighlightCategory("shop", controller, debounceMs = 0L)
        pumpLooperUntil {
            viewModel.uiState.value.categoryHighlightState.matchedPois.size == 2
        }

        val multiState = viewModel.uiState.value.categoryHighlightState
        assertEquals(setOf("cafe", "shop"), multiState.selectedCategoryIds)
        assertEquals(setOf(40_001L, 40_002L), controller.currentCategoryHighlightedPois.map { it.poi.osmId }.toSet())
        assertTrue(controller.isCategoryHighlightVisible)

        // Deselect Cafe -> only Shop remains
        viewModel.toggleHighlightCategory("cafe", controller, debounceMs = 0L)
        pumpLooperUntil {
            viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 &&
                !viewModel.uiState.value.categoryHighlightState.isRefreshing
        }
        assertEquals(setOf("shop"), viewModel.uiState.value.categoryHighlightState.selectedCategoryIds)
        assertEquals(listOf(40_002L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        // Clear All clears category overlays while preserving Search single-POI highlight
        viewModel.clearAllHighlightCategories(controller)
        assertTrue(viewModel.uiState.value.categoryHighlightState.selectedCategoryIds.isEmpty())
        assertTrue(controller.currentCategoryHighlightedPois.isEmpty())
        assertFalse(controller.isCategoryHighlightVisible)
        assertEquals("Search single-POI highlight must remain untouched", 40_004L, controller.currentHighlightedPoi?.osmId)
    }

    @Test
    fun test05_zoomOutPausesWithoutClearingAndZoomInResumes(): Unit = runBlocking {
        db.osmPoiDao().insertPois(
            listOf(
                OsmPoiRecord(50_001L, "Campus School", "school", "school", 39.921, 32.851, 14, 1, 1)
            )
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app, MapRuntimeDependencies(app))
        val controller = MapEngineController(app, 39.921, 32.851, 14.0, 0.0, { _, _, _, _ -> })
        val viewport = PoiViewportBounds(39.91, 39.93, 32.84, 32.86)

        assertEquals(13.0, PoiCategoryHighlightDefaults.MIN_POI_HIGHLIGHT_ZOOM, 0.001)

        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 14.0, controller = controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("school", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }
        assertTrue(controller.isCategoryHighlightVisible)

        // Zoom out below threshold (12.0 < 13.0) -> hides overlay, retains selections
        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 12.0, controller = controller, debounceMs = 0L)
        val pausedState = viewModel.uiState.value.categoryHighlightState
        assertTrue(pausedState.isPausedByZoom)
        assertEquals(setOf("school"), pausedState.selectedCategoryIds)
        assertTrue(pausedState.matchedPois.isEmpty())
        assertFalse(controller.isCategoryHighlightVisible)
        assertTrue(controller.currentCategoryHighlightedPois.isEmpty())

        // Zoom back in (>= 13.0) -> resumes automatically and queries viewport
        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 13.5, controller = controller, debounceMs = 0L)
        pumpLooperUntil {
            !viewModel.uiState.value.categoryHighlightState.isPausedByZoom &&
                viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1
        }
        assertEquals(setOf("school"), viewModel.uiState.value.categoryHighlightState.selectedCategoryIds)
        assertTrue(controller.isCategoryHighlightVisible)
        assertEquals(listOf(50_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        // Verify threshold adjustment pauses cleanly if raised above current zoom (14.5 > 13.5)
        viewModel.setCategoryHighlightMinZoom(14.5, controller, debounceMs = 0L)
        assertTrue(viewModel.uiState.value.categoryHighlightState.isPausedByZoom)
        assertFalse(controller.isCategoryHighlightVisible)
    }

    @Test
    fun test06_debouncedRefreshCancelsObsoleteQueriesEmptyViewportAndOffline(): Unit = runBlocking {
        AppNetworkPolicy.setInternetEnabled(false)
        assertFalse(AppNetworkPolicy.isNetworkAllowed())

        db.osmPoiDao().insertPois(
            listOf(
                OsmPoiRecord(60_001L, "Area A Parking", "parking", "parking", 39.921, 32.851, 14, 1, 1),
                OsmPoiRecord(60_002L, "Area B Parking", "parking", "parking", 40.921, 29.851, 14, 2, 2)
            )
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app, MapRuntimeDependencies(app))
        val controller = MapEngineController(app, 39.921, 32.851, 14.0, 0.0, { _, _, _, _ -> })

        val viewportA = PoiViewportBounds(39.90, 39.94, 32.83, 32.87)
        val viewportB = PoiViewportBounds(40.90, 40.94, 29.83, 29.87)
        val emptyViewportC = PoiViewportBounds(45.00, 45.05, 10.00, 10.05)

        viewModel.onCameraSettledForCategoryHighlight(viewportA, zoom = 14.0, controller = controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("parking", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }
        assertEquals(listOf(60_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        // Trigger viewportA debounced, then quickly supersede with viewportB before debounce finishes
        viewModel.onCameraSettledForCategoryHighlight(viewportA, zoom = 14.0, controller = controller, debounceMs = 180L)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(30L))
        viewModel.onCameraSettledForCategoryHighlight(viewportB, zoom = 14.0, controller = controller, debounceMs = 80L)

        pumpLooperUntil(timeoutMs = 3000L) {
            viewModel.uiState.value.categoryHighlightState.matchedPois.firstOrNull()?.poi?.osmId == 60_002L
        }
        assertEquals(listOf(60_002L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        // Pan to empty local viewport in offline mode -> keeps category active without silent deselection
        viewModel.onCameraSettledForCategoryHighlight(emptyViewportC, zoom = 14.0, controller = controller, debounceMs = 0L)
        pumpLooperUntil {
            viewModel.uiState.value.categoryHighlightState.lastViewport == emptyViewportC &&
                !viewModel.uiState.value.categoryHighlightState.isRefreshing &&
                viewModel.uiState.value.categoryHighlightState.matchedPois.isEmpty()
        }
        val emptyState = viewModel.uiState.value.categoryHighlightState
        assertTrue(emptyState.hasQueriedViewport)
        assertEquals("Category selection must persist when viewport has 0 local POIs", setOf("parking"), emptyState.selectedCategoryIds)
        assertFalse(controller.isCategoryHighlightVisible)
    }

    @Test
    fun test07_popupUiRendersCategoryPillsClearAllAndHonestFeedback() {
        val toggledIds = mutableListOf<String>()
        var clearAllClicks = 0

        composeTestRule.setContent {
            PoiHighlightPopupContent(
                highlightState = PoiCategoryHighlightUiState(
                    selectedCategoryIds = setOf("cafe"),
                    isPausedByZoom = true,
                    currentZoom = 11.4,
                    minHighlightZoom = 13.0
                ),
                onToggleCategory = { toggledIds.add(it) },
                onClearAll = { clearAllClicks++ },
                onClose = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("poi_highlight_popup").assertIsDisplayed()
        composeTestRule.onNodeWithTag("highlight_zoom_paused_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("highlight_category_pill_restaurant").performClick()
        composeTestRule.onNodeWithTag("highlight_clear_all_button").performClick()

        assertEquals(listOf("restaurant"), toggledIds)
        assertEquals(1, clearAllClicks)
    }

    @Test
    fun test08_tilePoiIngestionRefreshesActiveCategoriesAndCompositionPreservesOverlay(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val deps = MapRuntimeDependencies(app)
        val viewModel = MapViewModel(app, deps)
        val controller = MapEngineController(app, 39.921, 32.851, 14.0, 0.0, { _, _, _, _ -> })
        val viewport = PoiViewportBounds(39.91, 39.93, 32.84, 32.86)

        assertTrue(MapEngineController.CATEGORY_HIGHLIGHT_SOURCE_ID.startsWith("vian-"))
        assertTrue(MapEngineController.CATEGORY_HIGHLIGHT_HALO_LAYER_ID.startsWith("vian-"))
        assertTrue(MapEngineController.CATEGORY_HIGHLIGHT_CORE_LAYER_ID.startsWith("vian-"))

        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 14.0, controller = controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("pharmacy", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.hasQueriedViewport }
        assertTrue(viewModel.uiState.value.categoryHighlightState.matchedPois.isEmpty())

        // Simulate new vector tile POIs arriving into Room via shared OsmOfflineDataRepository
        val pharmacy = OsmPoiRecord(80_001L, "Night Pharmacy", "hospital", "pharmacy", 39.9215, 32.8515, 14, 1, 1)
        deps.osmRepository.savePoiFeatures(listOf(pharmacy))
        viewModel.onTilePoisIngested(controller, debounceMs = 0L)

        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }
        assertEquals(listOf(80_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })
        assertTrue(controller.isCategoryHighlightVisible)

        // Switching composition updates controller composition while preserving category highlight state
        val customComp = com.example.map.composition.MapCompositionDefaults.defaultComposition.copy(showPois = false)
        controller.applyComposition(customComp)
        assertEquals(listOf(80_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })
        assertTrue(controller.isCategoryHighlightVisible)
    }
}
