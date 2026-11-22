package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.example.map.composition.MapCompositionDefaults
import com.example.map.engine.MapEngineController
import com.example.network.AppNetworkPolicy
import com.example.ui.components.PoiHighlightPopupContent
import com.example.ui.screens.MapViewModel
import com.example.ui.screens.PoiCategoryHighlightUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun test01_categoryClassificationRetainedRemovedAndNewCategoriesWithStrictDistinctions() {
        // 1. Cafe and Parking are no longer selectable
        assertNull(PoiHighlightCategory.fromId("cafe"))
        assertNull(PoiHighlightCategory.fromId("parking"))
        assertFalse(PoiHighlightCategory.entries.any { it.id == "cafe" || it.id == "parking" })
        assertEquals(12, PoiHighlightCategory.entries.size)

        // 2. Pharmacy vs Hospital strict separation
        val pharmacyUnderHospital = OsmPoiRecord(
            osmId = 1L, name = "Corner Pharmacy", category = "hospital", subCategory = "pharmacy",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val trueHospital = OsmPoiRecord(
            osmId = 2L, name = "City Hospital", category = "hospital", subCategory = "hospital",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        assertTrue(PoiHighlightCategory.PHARMACY.matches(pharmacyUnderHospital))
        assertFalse("Hospital must NOT misclassify class=hospital/subclass=pharmacy", PoiHighlightCategory.HOSPITAL.matches(pharmacyUnderHospital))
        assertTrue(PoiHighlightCategory.HOSPITAL.matches(trueHospital))

        // 3. Bank vs Standalone ATM strict separation
        val standaloneAtm = OsmPoiRecord(
            osmId = 3L, name = "ATM", category = "bank", subCategory = "atm",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val bankOpenMapTiles = OsmPoiRecord(
            osmId = 4L, name = "Metro Bank", category = "bank", subCategory = "bank",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val bankAmenity = OsmPoiRecord(
            osmId = 5L, name = "Harbor Bank", category = "Amenity", subCategory = "Bank",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        assertTrue(PoiHighlightCategory.ATM.matches(standaloneAtm))
        assertFalse("Bank must NOT match standalone ATM", PoiHighlightCategory.BANK.matches(standaloneAtm))
        assertTrue(PoiHighlightCategory.BANK.matches(bankOpenMapTiles))
        assertTrue(PoiHighlightCategory.BANK.matches(bankAmenity))
        assertFalse("ATM must NOT match bank record", PoiHighlightCategory.ATM.matches(bankOpenMapTiles))

        // 4. Copy Shop matches copyshop only, not unrelated shops
        val copyShopOpenMapTiles = OsmPoiRecord(
            osmId = 6L, name = "Quick Print & Copy", category = "shop", subCategory = "copyshop",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val copyShopDirect = OsmPoiRecord(
            osmId = 7L, name = "Campus Copy", category = "copyshop", subCategory = "copy_shop",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val bakeryShop = OsmPoiRecord(
            osmId = 8L, name = "Corner Bakery", category = "shop", subCategory = "bakery",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val stationeryShop = OsmPoiRecord(
            osmId = 9L, name = "Paper & Pen", category = "shop", subCategory = "stationery",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        assertTrue(PoiHighlightCategory.COPY_SHOP.matches(copyShopOpenMapTiles))
        assertTrue(PoiHighlightCategory.COPY_SHOP.matches(copyShopDirect))
        assertFalse("Bakery must not match Copy Shop", PoiHighlightCategory.COPY_SHOP.matches(bakeryShop))
        assertFalse("Stationery must not match Copy Shop", PoiHighlightCategory.COPY_SHOP.matches(stationeryShop))

        // 5. Church / Worship matches place_of_worship and excludes tourism/historic/pilgrimage/miracle
        val parishChurch = OsmPoiRecord(
            osmId = 10L, name = "St. Mary Church", category = "place_of_worship", subCategory = "place_of_worship",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val mosqueAmenity = OsmPoiRecord(
            osmId = 11L, name = "Central Mosque", category = "amenity", subCategory = "place_of_worship",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val synagogueReligionSub = OsmPoiRecord(
            osmId = 12L, name = "Beth Shalom", category = "Place_Of_Worship", subCategory = "jewish",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val templeSub = OsmPoiRecord(
            osmId = 13L, name = "Lotus Temple", category = "religious", subCategory = "temple",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val touristAttraction = OsmPoiRecord(
            osmId = 14L, name = "City Landmark", category = "tourism", subCategory = "attraction",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val historicMonument = OsmPoiRecord(
            osmId = 15L, name = "Old Obelisk", category = "historic", subCategory = "monument",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val historicChurchRuin = OsmPoiRecord(
            osmId = 16L, name = "Ancient Abbey Ruins", category = "historic", subCategory = "church",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val pilgrimageMiracle = OsmPoiRecord(
            osmId = 17L, name = "Miracle Spring", category = "attraction", subCategory = "miracle",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val waysideShrine = OsmPoiRecord(
            osmId = 18L, name = "Roadside Shrine", category = "historic", subCategory = "wayside_shrine",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        assertTrue(PoiHighlightCategory.CHURCH_WORSHIP.matches(parishChurch))
        assertTrue(PoiHighlightCategory.CHURCH_WORSHIP.matches(mosqueAmenity))
        assertTrue(PoiHighlightCategory.CHURCH_WORSHIP.matches(synagogueReligionSub))
        assertTrue(PoiHighlightCategory.CHURCH_WORSHIP.matches(templeSub))
        assertFalse(PoiHighlightCategory.CHURCH_WORSHIP.matches(touristAttraction))
        assertFalse(PoiHighlightCategory.CHURCH_WORSHIP.matches(historicMonument))
        assertFalse(PoiHighlightCategory.CHURCH_WORSHIP.matches(historicChurchRuin))
        assertFalse(PoiHighlightCategory.CHURCH_WORSHIP.matches(pilgrimageMiracle))
        assertFalse(PoiHighlightCategory.CHURCH_WORSHIP.matches(waysideShrine))

        // 6. All 12 active categories match their expected MVT inputs
        val supermarketGrocery = OsmPoiRecord(
            osmId = 19L, name = "Fresh Mart", category = "shop", subCategory = "supermarket",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val busStop = OsmPoiRecord(
            osmId = 20L, name = "Main Ave Stop", category = "bus", subCategory = "bus_stop",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val restaurant = OsmPoiRecord(
            osmId = 21L, name = "Bistro 24", category = "restaurant", subCategory = "restaurant",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val fuel = OsmPoiRecord(
            osmId = 22L, name = "Highway Fuel", category = "fuel", subCategory = "fuel",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val school = OsmPoiRecord(
            osmId = 23L, name = "Oak School", category = "school", subCategory = "school",
            latitude = 39.92, longitude = 32.85, sourceTileZ = 14, sourceTileX = 1, sourceTileY = 1
        )
        val records = listOf(
            bakeryShop, supermarketGrocery, restaurant, pharmacyUnderHospital, trueHospital,
            busStop, standaloneAtm, fuel, school, parishChurch, bankOpenMapTiles, copyShopOpenMapTiles
        )
        for (cat in PoiHighlightCategory.entries) {
            assertTrue("Category ${cat.id} must match at least one MVT record", records.any { cat.matches(it) })
        }

        // Overlapping Shop + Copy Shop prefers specific primary category while matching both without duplicates
        val primary = PoiHighlightCategory.pickPrimaryCategory(
            PoiHighlightCategory.matchSelectedCategories(
                copyShopOpenMapTiles,
                listOf(PoiHighlightCategory.SHOP, PoiHighlightCategory.COPY_SHOP)
            )
        )
        assertEquals(PoiHighlightCategory.COPY_SHOP, primary)
    }

    @Test
    fun test02_viewportFilteringNoSilent150TruncationAndAllPoisHonestLimit(): Unit = runBlocking {
        // Insert 185 restaurants inside rotated viewport + 10 outside
        val inside = (1..185).map { idx ->
            OsmPoiRecord(
                osmId = 10_000L + idx,
                name = "Bistro #$idx",
                category = "amenity",
                subCategory = "restaurant",
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
                name = "Far Bistro #$idx",
                category = "restaurant",
                subCategory = "restaurant",
                latitude = 41.0000 + (idx * 0.001),
                longitude = 29.0000,
                sourceTileZ = 14,
                sourceTileX = 9000,
                sourceTileY = 6000
            )
        }
        db.osmPoiDao().insertPois(inside + outside)

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

        val matches = osmRepo.queryCategoryHighlightedPois(bounds, listOf(PoiHighlightCategory.RESTAURANT))
        assertEquals("All 185 visible restaurants must be returned without 150 truncation", 185, matches.size)
        assertTrue(matches.none { it.poi.osmId >= 20_000L })

        // Verify All POIs exposes exact total count even when bounded by a display limit
        val boundedAllResult = osmRepo.queryAllHighlightedPois(bounds, limit = 150)
        assertEquals(150, boundedAllResult.items.size)
        assertEquals(185, boundedAllResult.totalMatchingCount)
        assertTrue(boundedAllResult.isTruncated)
        assertEquals(150, boundedAllResult.displayLimit)

        val fullAllResult = osmRepo.queryAllHighlightedPois(bounds)
        assertEquals(185, fullAllResult.items.size)
        assertEquals(185, fullAllResult.totalMatchingCount)
        assertFalse(fullAllResult.isTruncated)
    }

    @Test
    fun test03_antimeridianCrossingQueriesBothSidesAndDeduplicatesForCategoriesAndAllPois(): Unit = runBlocking {
        val westSide = OsmPoiRecord(
            osmId = 30_001L, name = "East Edge Fuel", category = "fuel", subCategory = "fuel",
            latitude = -16.5, longitude = 179.92, sourceTileZ = 14, sourceTileX = 16383, sourceTileY = 8900
        )
        val eastSide = OsmPoiRecord(
            osmId = 30_002L, name = "West Edge Worship", category = "place_of_worship", subCategory = "place_of_worship",
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

        val fuelMatches = osmRepo.queryCategoryHighlightedPois(crossBounds, listOf(PoiHighlightCategory.FUEL))
        assertEquals(setOf(30_001L, 30_003L), fuelMatches.map { it.poi.osmId }.toSet())
        assertEquals(2, fuelMatches.size)

        val allMatches = osmRepo.queryAllHighlightedPois(crossBounds)
        assertEquals(setOf(30_001L, 30_002L, 30_003L), allMatches.items.map { it.poi.osmId }.toSet())
        assertEquals(3, allMatches.totalMatchingCount)
        assertFalse(allMatches.isTruncated)
    }

    @Test
    fun test04_allPoisMutualExclusionUnselectableRemovedMultiSelectClearAllAndSearchIndependence(): Unit = runBlocking {
        db.osmPoiDao().insertPois(
            listOf(
                OsmPoiRecord(40_001L, "City Bank", "bank", "bank", 39.921, 32.851, 14, 1, 1),
                OsmPoiRecord(40_002L, "Bank ATM", "bank", "atm", 39.9215, 32.8515, 14, 1, 1),
                OsmPoiRecord(40_003L, "Quick Copy Shop", "shop", "copyshop", 39.922, 32.852, 14, 1, 1),
                OsmPoiRecord(40_004L, "Corner Bakery", "shop", "bakery", 39.9225, 32.8525, 14, 1, 1),
                OsmPoiRecord(40_005L, "Old Mosque", "place_of_worship", "place_of_worship", 39.923, 32.853, 14, 1, 1),
                OsmPoiRecord(40_006L, "Landmark Tower", "tourism", "attraction", 39.9235, 32.8535, 14, 1, 1),
                OsmPoiRecord(40_007L, "Harbor Bench", "general", null, 39.924, 32.854, 14, 1, 1),
                OsmPoiRecord(40_008L, "Far Museum", "tourism", "museum", 41.500, 29.000, 14, 2, 2)
            )
        )

        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app, MapRuntimeDependencies(app))
        val controller = MapEngineController(app, 39.922, 32.852, 14.0, 0.0, { _, _, _, _ -> })
        val viewport = PoiViewportBounds(39.91, 39.93, 32.84, 32.86)

        assertNotEquals(MapEngineController.HIGHLIGHT_SOURCE_ID, MapEngineController.CATEGORY_HIGHLIGHT_SOURCE_ID)
        assertNotEquals(MapEngineController.HIGHLIGHT_CORE_LAYER_ID, MapEngineController.CATEGORY_HIGHLIGHT_CORE_LAYER_ID)

        // Independent single-POI Search highlight
        val museum = db.osmPoiDao().getPoiById(40_008L)!!
        viewModel.selectSearchResult(museum, controller)
        assertEquals(40_008L, controller.currentHighlightedPoi?.osmId)

        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 14.0, controller = controller, debounceMs = 0L)

        // Removed categories cannot be toggled on
        viewModel.toggleHighlightCategory("cafe", controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("parking", controller, debounceMs = 0L)
        assertFalse(viewModel.uiState.value.categoryHighlightState.hasActiveSelection)

        // Overlapping Shop + Copy Shop creates 1 marker per osmId without duplicates
        viewModel.toggleHighlightCategory("shop", controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("copy_shop", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 2 }
        assertEquals(setOf(40_003L, 40_004L), controller.currentCategoryHighlightedPois.map { it.poi.osmId }.toSet())
        assertEquals(2, controller.currentCategoryHighlightedPois.size)
        val copyItem = controller.currentCategoryHighlightedPois.first { it.poi.osmId == 40_003L }
        assertEquals(PoiHighlightCategory.COPY_SHOP, copyItem.primaryCategory)

        // Selecting All POIs clears individual categories and renders every local viewport POI in neutral color
        viewModel.toggleAllPoisHighlight(controller, debounceMs = 0L)
        pumpLooperUntil {
            viewModel.uiState.value.categoryHighlightState.isAllPoisSelected &&
                viewModel.uiState.value.categoryHighlightState.matchedPois.size == 7
        }
        val allState = viewModel.uiState.value.categoryHighlightState
        assertTrue(allState.isAllPoisSelected)
        assertTrue(allState.selectedCategoryIds.isEmpty())
        assertEquals(7, allState.totalMatchingCount)
        assertEquals(setOf(40_001L, 40_002L, 40_003L, 40_004L, 40_005L, 40_006L, 40_007L), controller.currentCategoryHighlightedPois.map { it.poi.osmId }.toSet())
        assertTrue(controller.currentCategoryHighlightedPois.all {
            it.primaryCategory == null && it.markerColorHex == PoiCategoryHighlightDefaults.ALL_POIS_MARKER_COLOR_HEX
        })

        // Selecting an individual category exits All POIs mode cleanly
        viewModel.toggleHighlightCategory("bank", controller, debounceMs = 0L)
        pumpLooperUntil {
            !viewModel.uiState.value.categoryHighlightState.isAllPoisSelected &&
                !viewModel.uiState.value.categoryHighlightState.isRefreshing &&
                viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1
        }
        assertFalse(viewModel.uiState.value.categoryHighlightState.isAllPoisSelected)
        assertEquals(setOf("bank"), viewModel.uiState.value.categoryHighlightState.selectedCategoryIds)
        assertEquals("Bank must exclude standalone ATM", listOf(40_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        // Switch back to All POIs, then verify Clear All resets everything while Search stays untouched
        viewModel.toggleAllPoisHighlight(controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 7 }
        viewModel.clearAllHighlightCategories(controller)

        val cleared = viewModel.uiState.value.categoryHighlightState
        assertFalse(cleared.isAllPoisSelected)
        assertTrue(cleared.selectedCategoryIds.isEmpty())
        assertTrue(cleared.matchedPois.isEmpty())
        assertEquals(0, cleared.totalMatchingCount)
        assertTrue(controller.currentCategoryHighlightedPois.isEmpty())
        assertFalse(controller.isCategoryHighlightVisible)
        assertEquals("Search single-POI highlight must remain untouched", 40_008L, controller.currentHighlightedPoi?.osmId)
    }

    @Test
    fun test05_zoomOutPausesWithoutClearingForCategoriesAndAllPoisAndZoomInResumes(): Unit = runBlocking {
        db.osmPoiDao().insertPois(
            listOf(
                OsmPoiRecord(50_001L, "Campus School", "school", "school", 39.921, 32.851, 14, 1, 1),
                OsmPoiRecord(50_002L, "Town Fountain", "general", null, 39.922, 32.852, 14, 1, 1)
            )
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app, MapRuntimeDependencies(app))
        val controller = MapEngineController(app, 39.921, 32.851, 14.0, 0.0, { _, _, _, _ -> })
        val viewport = PoiViewportBounds(39.91, 39.93, 32.84, 32.86)

        assertEquals(13.0, PoiCategoryHighlightDefaults.MIN_POI_HIGHLIGHT_ZOOM, 0.001)

        // Category pause & resume
        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 14.0, controller = controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("school", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }

        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 12.0, controller = controller, debounceMs = 0L)
        assertTrue(viewModel.uiState.value.categoryHighlightState.isPausedByZoom)
        assertEquals(setOf("school"), viewModel.uiState.value.categoryHighlightState.selectedCategoryIds)
        assertFalse(controller.isCategoryHighlightVisible)

        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 13.5, controller = controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }
        assertEquals(listOf(50_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        // All POIs pause & auto-resume
        viewModel.toggleAllPoisHighlight(controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 2 }
        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 11.8, controller = controller, debounceMs = 0L)
        val pausedAll = viewModel.uiState.value.categoryHighlightState
        assertTrue(pausedAll.isPausedByZoom)
        assertTrue(pausedAll.isAllPoisSelected)
        assertTrue(pausedAll.matchedPois.isEmpty())
        assertFalse(controller.isCategoryHighlightVisible)

        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 14.2, controller = controller, debounceMs = 0L)
        pumpLooperUntil {
            !viewModel.uiState.value.categoryHighlightState.isPausedByZoom &&
                viewModel.uiState.value.categoryHighlightState.matchedPois.size == 2
        }
        assertTrue(viewModel.uiState.value.categoryHighlightState.isAllPoisSelected)
        assertTrue(controller.isCategoryHighlightVisible)
    }

    @Test
    fun test06_debouncedRefreshCancelsObsoleteQueriesEmptyViewportAndOffline(): Unit = runBlocking {
        AppNetworkPolicy.setInternetEnabled(false)
        assertFalse(AppNetworkPolicy.isNetworkAllowed())

        db.osmPoiDao().insertPois(
            listOf(
                OsmPoiRecord(60_001L, "Area A Bank", "bank", "bank", 39.921, 32.851, 14, 1, 1),
                OsmPoiRecord(60_002L, "Area B Bank", "bank", "bank", 40.921, 29.851, 14, 2, 2),
                OsmPoiRecord(60_003L, "Area B Statue", "historic", "monument", 40.922, 29.852, 14, 2, 2)
            )
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app, MapRuntimeDependencies(app))
        val controller = MapEngineController(app, 39.921, 32.851, 14.0, 0.0, { _, _, _, _ -> })

        val viewportA = PoiViewportBounds(39.90, 39.94, 32.83, 32.87)
        val viewportB = PoiViewportBounds(40.90, 40.94, 29.83, 29.87)
        val emptyViewportC = PoiViewportBounds(45.00, 45.05, 10.00, 10.05)

        // Category debounce cancellation
        viewModel.onCameraSettledForCategoryHighlight(viewportA, zoom = 14.0, controller = controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("bank", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }
        assertEquals(listOf(60_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        viewModel.onCameraSettledForCategoryHighlight(viewportA, zoom = 14.0, controller = controller, debounceMs = 180L)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(30L))
        viewModel.onCameraSettledForCategoryHighlight(viewportB, zoom = 14.0, controller = controller, debounceMs = 80L)

        pumpLooperUntil(timeoutMs = 3000L) {
            viewModel.uiState.value.categoryHighlightState.matchedPois.firstOrNull()?.poi?.osmId == 60_002L
        }
        assertEquals(listOf(60_002L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })

        // All POIs debounce cancellation + empty viewport in offline mode
        viewModel.toggleAllPoisHighlight(controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 2 }
        viewModel.onCameraSettledForCategoryHighlight(viewportA, zoom = 14.0, controller = controller, debounceMs = 180L)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(30L))
        viewModel.onCameraSettledForCategoryHighlight(emptyViewportC, zoom = 14.0, controller = controller, debounceMs = 80L)

        pumpLooperUntil {
            viewModel.uiState.value.categoryHighlightState.lastViewport == emptyViewportC &&
                !viewModel.uiState.value.categoryHighlightState.isRefreshing &&
                viewModel.uiState.value.categoryHighlightState.hasQueriedViewport &&
                viewModel.uiState.value.categoryHighlightState.matchedPois.isEmpty()
        }
        val emptyState = viewModel.uiState.value.categoryHighlightState
        assertTrue("All POIs must remain active when viewport has 0 local POIs", emptyState.isAllPoisSelected)
        assertEquals(0, emptyState.totalMatchingCount)
        assertFalse(controller.isCategoryHighlightVisible)
    }

    @Test
    fun test07_popupUiRendersAllPoisUpdatedCategoriesAndHonestFeedback() {
        val toggledIds = mutableListOf<String>()
        var allPoisClicks = 0
        var clearAllClicks = 0

        composeTestRule.setContent {
            PoiHighlightPopupContent(
                highlightState = PoiCategoryHighlightUiState(
                    isAllPoisSelected = true,
                    hasQueriedViewport = true,
                    lastViewport = PoiViewportBounds(39.9, 39.95, 32.8, 32.85),
                    currentZoom = 14.2,
                    minHighlightZoom = 13.0,
                    totalMatchingCount = 900,
                    isDisplayTruncated = true,
                    displayLimit = 750,
                    matchedPois = List(750) { idx ->
                        com.example.data.osm.CategoryHighlightedPoi(
                            poi = OsmPoiRecord(idx.toLong(), "P$idx", "general", null, 39.9, 32.8, 14, 1, 1),
                            primaryCategory = null,
                            matchedCategoryIds = emptyList()
                        )
                    }
                ),
                onToggleCategory = { toggledIds.add(it) },
                onToggleAllPois = { allPoisClicks++ },
                onClearAll = { clearAllClicks++ },
                onClose = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("poi_highlight_popup").assertIsDisplayed()
        composeTestRule.onNodeWithTag("highlight_all_pois_pill").assertIsDisplayed()
        composeTestRule.onNodeWithText("All POIs (750/900)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Showing 750 of 900 local POIs in viewport (display limit 750)").assertIsDisplayed()

        composeTestRule.onNodeWithTag("highlight_category_pill_cafe").assertDoesNotExist()
        composeTestRule.onNodeWithTag("highlight_category_pill_parking").assertDoesNotExist()

        composeTestRule.onNodeWithTag("highlight_category_pill_church_worship").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("highlight_category_pill_bank").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("highlight_category_pill_copy_shop").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("highlight_all_pois_pill").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("highlight_category_pill_bank").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("highlight_clear_all_button").performClick()

        assertEquals(1, allPoisClicks)
        assertEquals(listOf("bank"), toggledIds)
        assertEquals(1, clearAllClicks)
    }

    @Test
    fun test08_tilePoiIngestionRefreshesCategoriesAndAllPoisAndCompositionPreservesOverlay(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val deps = MapRuntimeDependencies(app)
        val viewModel = MapViewModel(app, deps)
        val controller = MapEngineController(app, 39.921, 32.851, 14.0, 0.0, { _, _, _, _ -> })
        val viewport = PoiViewportBounds(39.91, 39.93, 32.84, 32.86)

        assertTrue(MapEngineController.CATEGORY_HIGHLIGHT_SOURCE_ID.startsWith("vian-"))
        assertTrue(MapEngineController.CATEGORY_HIGHLIGHT_HALO_LAYER_ID.startsWith("vian-"))
        assertTrue(MapEngineController.CATEGORY_HIGHLIGHT_CORE_LAYER_ID.startsWith("vian-"))

        viewModel.onCameraSettledForCategoryHighlight(viewport, zoom = 14.0, controller = controller, debounceMs = 0L)
        viewModel.toggleHighlightCategory("church_worship", controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.hasQueriedViewport }
        assertTrue(viewModel.uiState.value.categoryHighlightState.matchedPois.isEmpty())

        // Ingest place_of_worship POI -> active category refreshes automatically
        val church = OsmPoiRecord(80_001L, "St. Luke Chapel", "place_of_worship", "place_of_worship", 39.9215, 32.8515, 14, 1, 1)
        deps.osmRepository.savePoiFeatures(listOf(church))
        viewModel.onTilePoisIngested(controller, debounceMs = 0L)

        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }
        assertEquals(listOf(80_001L), controller.currentCategoryHighlightedPois.map { it.poi.osmId })
        assertTrue(controller.isCategoryHighlightVisible)

        // Switch to All POIs, ingest an uncategorized POI -> All POIs refreshes automatically
        viewModel.toggleAllPoisHighlight(controller, debounceMs = 0L)
        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 1 }
        val monument = OsmPoiRecord(80_002L, "Civic Arch", "historic", "monument", 39.9218, 32.8518, 14, 1, 1)
        deps.osmRepository.savePoiFeatures(listOf(monument))
        viewModel.onTilePoisIngested(controller, debounceMs = 0L)

        pumpLooperUntil { viewModel.uiState.value.categoryHighlightState.matchedPois.size == 2 }
        assertEquals(setOf(80_001L, 80_002L), controller.currentCategoryHighlightedPois.map { it.poi.osmId }.toSet())

        // Switching composition updates controller composition while preserving overlay state
        val customComp = MapCompositionDefaults.defaultComposition.copy(showPois = false)
        controller.applyComposition(customComp)
        assertEquals(setOf(80_001L, 80_002L), controller.currentCategoryHighlightedPois.map { it.poi.osmId }.toSet())
        assertTrue(controller.isCategoryHighlightVisible)
    }
}
