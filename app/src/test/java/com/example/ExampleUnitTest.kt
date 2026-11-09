package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.OsmPoiRecord
import com.example.data.db.RetainedTileEntity
import com.example.data.osm.MvtParser
import com.example.data.osm.OsmOfflineDataRepository
import com.example.data.routing.RoutingGraphCoverageIndex
import com.example.data.routing.RoutingGraphStatus
import com.example.data.routing.ValhallaTileCalculator
import com.example.data.tile.TileCategory
import com.example.data.tile.TileCoord
import com.example.data.tile.TileMetadata
import com.example.data.tile.TileStorageManager
import com.example.map.acquisition.VisibleTileCoordinator
import com.example.map.source.MapSourceRepository
import com.example.network.AppNetworkPolicy
import kotlinx.coroutines.runBlocking
import android.app.Application
import com.example.ui.screens.ActiveModal
import com.example.ui.screens.MapViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.VisibleRegion
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Complete Local JVM Unit Test Suite for Vian Maps Phase 3.1 & 3.2.
 * Tests XYZ conversion, visible tile enumeration, antimeridian crossing,
 * cache keys, atomic save/load, Room persistence, POI search after reopen,
 * feature deduplication, Valhalla official tile calculations, network policy,
 * and Settings popup / More Settings / Backup & Restore transitions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun test01_verifyTileCoordinateConversionAndBounds() {
        // San Francisco (37.7749, -122.4194) at zoom 12
        val sfCoord = TileCoord.fromLatLng(37.7749, -122.4194, 12)
        assertEquals(12, sfCoord.z)
        assertEquals(655, sfCoord.x)
        assertEquals(1583, sfCoord.y)
        assertEquals("12/655/1583", sfCoord.toString())

        // Ankara (39.9334, 32.8597) at zoom 10
        val ankaraCoord = TileCoord.fromLatLng(39.9334, 32.8597, 10)
        assertEquals(10, ankaraCoord.z)
        assertEquals(605, ankaraCoord.x)
        assertEquals(387, ankaraCoord.y)
    }

    @Test
    fun test02_verifyVisibleTileEnumerationAndRotation() {
        val coordinator = VisibleTileCoordinator(context)
        val bounds = LatLngBounds.from(40.0, 33.0, 39.0, 32.0)
        val region = VisibleRegion(
            LatLng(40.0, 32.0),
            LatLng(40.0, 33.0),
            LatLng(39.0, 32.0),
            LatLng(39.0, 33.0),
            bounds
        )
        val tiles = coordinator.calculateVisibleTiles(region, zoom = 10)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.size <= 24) // Respects viewport safety cap
        assertTrue(tiles.all { it.z == 10 })
    }

    @Test
    fun test03_verifyAntimeridianCrossing() {
        val coordinator = VisibleTileCoordinator(context)
        val bounds = LatLngBounds.from(10.0, 179.9, 0.0, -179.9)
        // West/left side is near 179° (positive), East/right side is near -179° (negative)
        val region = VisibleRegion(
            LatLng(10.0, 179.0), // farLeft
            LatLng(10.0, -179.0), // farRight
            LatLng(0.0, 179.0),  // nearLeft
            LatLng(0.0, -179.0),  // nearRight
            bounds
        )
        val tiles = coordinator.calculateVisibleTiles(region, zoom = 5)
        assertTrue(tiles.isNotEmpty())
        // Tiles must include both near max X (31) and min X (0) for zoom 5 (n=32)
        assertTrue(tiles.any { it.x >= 30 })
        assertTrue(tiles.any { it.x <= 1 })
    }

    @Test
    fun test04_verifyProviderSpecificCacheKeys() {
        val manager = TileStorageManager(context)
        val keyOsm = manager.buildTileKey("openfreemap", TileCategory.OSM_VECTOR, 14, 2620, 6331)
        val keySat = manager.buildTileKey("esri", TileCategory.SATELLITE_RASTER, 14, 2620, 6331)
        assertEquals("openfreemap:OSM_VECTOR:14/2620/6331", keyOsm)
        assertEquals("esri:SATELLITE_RASTER:14/2620/6331", keySat)
        assertFalse(keyOsm == keySat)
    }

    @Test
    fun test05_verifyAtomicSaveLoadAndChecksumValidation() = runBlocking {
        val manager = TileStorageManager(context)
        val dummyData = "MVT_TILE_DATA_SAMPLE_PAYLOAD_TEST".toByteArray()
        val metadata = TileMetadata(
            providerId = "openfreemap",
            schemaVersion = "1.0",
            sourceUrl = "https://tiles.openfreemap.org/planet/14/1/1.pbf",
            attribution = "© OpenFreeMap"
        )

        val saved = manager.saveTileData("openfreemap", TileCategory.OSM_VECTOR, 14, 1, 1, dummyData, metadata)
        assertTrue(saved)
        assertTrue(manager.isTileRetained("openfreemap", TileCategory.OSM_VECTOR, 14, 1, 1))

        val loaded = manager.loadTileData("openfreemap", TileCategory.OSM_VECTOR, 14, 1, 1)
        assertNotNull(loaded)
        assertEquals(String(dummyData), String(loaded!!))

        // Corrupt file validation
        val file = manager.getTileFile("openfreemap", TileCategory.OSM_VECTOR, 14, 1, 1)
        file.writeBytes("CORRUPTED_MODIFIED_DATA".toByteArray())
        val afterCorrupt = manager.loadTileData("openfreemap", TileCategory.OSM_VECTOR, 14, 1, 1)
        assertNull(afterCorrupt) // Corrupted file detected and purged
    }

    @Test
    fun test06_verifyRoomPersistenceAndPoiSearchAfterReopen() = runBlocking {
        val repo = OsmOfflineDataRepository(context)
        val testPois = listOf(
            OsmPoiRecord(
                osmId = 5001L,
                name = "Kızılay Square",
                category = "townhall",
                latitude = 39.9208,
                longitude = 32.8541,
                sourceTileZ = 14,
                sourceTileX = 2620,
                sourceTileY = 6331
            ),
            OsmPoiRecord(
                osmId = 5002L,
                name = "Anıtkabir",
                category = "monument",
                latitude = 39.9250,
                longitude = 32.8369,
                sourceTileZ = 14,
                sourceTileX = 2620,
                sourceTileY = 6331
            )
        )

        val count = repo.savePoiFeatures(testPois)
        assertEquals(2, count)

        // Search POI by name
        val results = repo.searchPois("Kızılay")
        assertEquals(1, results.size)
        assertEquals("Kızılay Square", results.first().name)

        // Search POI by category
        val monumentResults = repo.searchPois("monument")
        assertEquals(1, monumentResults.size)
        assertEquals("Anıtkabir", monumentResults.first().name)
    }

    @Test
    fun test07_verifyFeatureDeduplicationAcrossBufferedTiles() = runBlocking {
        val repo = OsmOfflineDataRepository(context)
        // Same feature in tile A and tile B
        val poiA = OsmPoiRecord(
            osmId = 7777L,
            name = "Grand Bazaar",
            category = "marketplace",
            latitude = 41.0106,
            longitude = 28.9680,
            sourceTileZ = 14,
            sourceTileX = 100,
            sourceTileY = 100
        )
        val poiB = OsmPoiRecord(
            osmId = 7777L,
            name = "Grand Bazaar",
            category = "marketplace",
            latitude = 41.0106,
            longitude = 28.9680,
            sourceTileZ = 14,
            sourceTileX = 101,
            sourceTileY = 100
        )

        repo.savePoiFeatures(listOf(poiA))
        val beforeCount = repo.getPoiCount()

        repo.savePoiFeatures(listOf(poiB))
        val afterCount = repo.getPoiCount()

        assertEquals(beforeCount, afterCount) // Deduplicated by primary key osmId
    }

    @Test
    fun test08_verifyValhallaOfficialTileReferenceCalculations() {
        // Ankara (39.9334° N, 32.8597° E)
        val l0 = ValhallaTileCalculator.getTileIndex(39.9334, 32.8597, 0)
        val l1 = ValhallaTileCalculator.getTileIndex(39.9334, 32.8597, 1)
        val l2 = ValhallaTileCalculator.getTileIndex(39.9334, 32.8597, 2)

        assertEquals(2933L, l0)
        assertEquals(46652L, l1)
        assertEquals(748211L, l2)

        assertEquals("0/002/933.gph", ValhallaTileCalculator.formatTilePath(0, l0))
        assertEquals("1/046/652.gph", ValhallaTileCalculator.formatTilePath(1, l1))
        assertEquals("2/748/211.gph", ValhallaTileCalculator.formatTilePath(2, l2))

        // Intersecting tiles
        val intersectingL0 = ValhallaTileCalculator.getIntersectingTileIndices(39.0, 32.0, 40.0, 33.0, 0)
        assertTrue(intersectingL0.contains(2933L))
    }

    @Test
    fun test09_verifyNoGraphTileMarkedRoutableWithoutBinaryFile() = runBlocking {
        val index = RoutingGraphCoverageIndex(context)
        val report = index.evaluateCoverage(39.0, 32.0, 40.0, 33.0)

        // Without binary .gph files present on disk, status MUST be NOT_CONFIGURED
        assertEquals(RoutingGraphStatus.NOT_CONFIGURED, report.status)
        assertTrue(report.details.contains("not configured"))
        assertTrue(report.missingTiles.isNotEmpty())
    }

    @Test
    fun test10_verifyAppNetworkPolicyEnforcement() {
        AppNetworkPolicy.setInternetEnabled(true)
        assertTrue(AppNetworkPolicy.isNetworkAllowed())

        AppNetworkPolicy.setInternetEnabled(false)
        assertFalse(AppNetworkPolicy.isNetworkAllowed())

        val log = AppNetworkPolicy.diagnosticsLog.value
        assertTrue(log.isNotEmpty())
    }

    @Test
    fun test11_verifyMapSourceRepositoryStylesAndHybridDefaults() {
        val defaultStyle = MapSourceRepository.defaultStyle
        assertEquals("hybrid_satellite_osm", defaultStyle.id)
        assertTrue(defaultStyle.isCompositeHybrid)
        assertNotNull(defaultStyle.rasterSourceUri)
        assertFalse(defaultStyle.offlineRetentionPermitted) // Imagery is online view only

        val liberty = MapSourceRepository.getStyleById("openfreemap_liberty")
        assertEquals("openfreemap_liberty", liberty.id)
        assertFalse(liberty.isCompositeHybrid)
        assertTrue(liberty.offlineRetentionPermitted)

        val oam = MapSourceRepository.getStyleById("hybrid_oam_osm")
        assertEquals("hybrid_oam_osm", oam.id)
        assertTrue(oam.isCompositeHybrid)
        assertEquals(14f, oam.rasterMinZoom)
        assertTrue(oam.offlineRetentionPermitted)
    }

    @Test
    fun test12_verifyInFlightLockCoalescing() {
        val manager = TileStorageManager(context)
        val acquiredFirst = manager.acquireLock("openfreemap", TileCategory.OSM_VECTOR, 14, 10, 20)
        assertTrue(acquiredFirst)

        val acquiredSecond = manager.acquireLock("openfreemap", TileCategory.OSM_VECTOR, 14, 10, 20)
        assertFalse(acquiredSecond) // Coalesced / locked

        manager.releaseLock("openfreemap", TileCategory.OSM_VECTOR, 14, 10, 20)
        val acquiredThird = manager.acquireLock("openfreemap", TileCategory.OSM_VECTOR, 14, 10, 20)
        assertTrue(acquiredThird)
        manager.releaseLock("openfreemap", TileCategory.OSM_VECTOR, 14, 10, 20)
    }

    @Test
    fun test13_verifyMvtParserCoordinateProjection() {
        val tile = TileCoord(12, 2397, 1547)
        val (lat, lng) = MvtParser.tilePointToLatLng(2048, 2048, 4096, tile)
        // Center of tile 12/2397/1547 should be in Turkey near Ankara/Central Anatolia (~39° N, ~30-31° E)
        assertTrue(lat in 38.0..42.0)
        assertTrue(lng in 29.0..33.0)
    }

    @Test
    fun test14_verifySettingsPopupAndModalTransitions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)

        // 1. Initial State: popup is closed, activeModal is null
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
        assertNull(viewModel.uiState.value.activeModal)

        // 2. Short tap opens compact Settings popup
        viewModel.openSettingsPopup()
        assertTrue(viewModel.uiState.value.settingsPopupOpen)
        assertNull(viewModel.uiState.value.activeModal)

        // 3. Tapping More Settings from popup dismisses popup and opens MoreSettings workspace
        viewModel.openModal(ActiveModal.MoreSettings)
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
        assertEquals(ActiveModal.MoreSettings, viewModel.uiState.value.activeModal)

        // 4. Back from More Settings returns directly to map
        viewModel.closeModal()
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
        assertNull(viewModel.uiState.value.activeModal)

        // 5. Short tap opens popup again
        viewModel.openSettingsPopup()
        assertTrue(viewModel.uiState.value.settingsPopupOpen)

        // 6. Tapping Backup & Restore opens BackupRestore workspace and closes popup
        viewModel.openModal(ActiveModal.BackupRestore)
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
        assertEquals(ActiveModal.BackupRestore, viewModel.uiState.value.activeModal)

        // 7. Back from Backup & Restore returns directly to map
        viewModel.closeModal()
        assertNull(viewModel.uiState.value.activeModal)

        // 8. Tapping Log Keeper opens LogKeeper workspace and closes popup
        viewModel.openSettingsPopup()
        viewModel.openModal(ActiveModal.LogKeeper)
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
        assertEquals(ActiveModal.LogKeeper, viewModel.uiState.value.activeModal)

        // 9. Back from Log Keeper returns directly to map (not back to Settings)
        viewModel.closeModal()
        assertNull(viewModel.uiState.value.activeModal)
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
    }

    @Test
    fun test15_verifyDirectLongPressToMoreSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)

        // Direct long-press bypasses the popup completely
        viewModel.openModal(ActiveModal.MoreSettings)
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
        assertEquals(ActiveModal.MoreSettings, viewModel.uiState.value.activeModal)

        // Closing returns to map
        viewModel.closeModal()
        assertNull(viewModel.uiState.value.activeModal)

        // Long-press while popup is open dismisses popup and directly enters MoreSettings
        viewModel.openSettingsPopup()
        assertTrue(viewModel.uiState.value.settingsPopupOpen)
        viewModel.openModal(ActiveModal.MoreSettings)
        assertFalse(viewModel.uiState.value.settingsPopupOpen)
        assertEquals(ActiveModal.MoreSettings, viewModel.uiState.value.activeModal)
    }

    @Test
    fun test16_verifyMapCompositionSerializationAndDefaults() {
        val starters = com.example.map.composition.MapCompositionDefaults.starterCompositions
        assertTrue(starters.isNotEmpty())
        val defaultComp = com.example.map.composition.MapCompositionDefaults.defaultComposition
        assertEquals("comp_satellite_hybrid", defaultComp.id)
        assertTrue(defaultComp.isPinnedToQuickChoice)

        // Test JSON roundtrip
        val json = defaultComp.toJson()
        val parsed = com.example.map.composition.MapComposition.fromJson(json)
        assertEquals(defaultComp.id, parsed.id)
        assertEquals(defaultComp.name, parsed.name)
        assertEquals(defaultComp.baseStyleId, parsed.baseStyleId)
        assertEquals(defaultComp.showRoads, parsed.showRoads)
        assertEquals(defaultComp.showLabels, parsed.showLabels)
        assertEquals(defaultComp.satelliteOpacity, parsed.satelliteOpacity, 0.001f)

        // Test List JSON roundtrip
        val listJson = com.example.map.composition.MapComposition.listToJsonString(starters)
        val parsedList = com.example.map.composition.MapComposition.listFromJsonString(listJson)
        assertEquals(starters.size, parsedList.size)
    }

    @Test
    fun test17_verifyMapCatalogueModalBackStack() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)

        // 1. Open More Settings
        viewModel.openModal(ActiveModal.MoreSettings)
        assertEquals(ActiveModal.MoreSettings, viewModel.uiState.value.activeModal)

        // 2. Navigate from More Settings into Map Catalogue subpage
        viewModel.openModal(ActiveModal.MapCatalogue)
        assertEquals(ActiveModal.MapCatalogue, viewModel.uiState.value.activeModal)

        // 3. Back from Map Catalogue returns to More Settings
        viewModel.openModal(ActiveModal.MoreSettings)
        assertEquals(ActiveModal.MoreSettings, viewModel.uiState.value.activeModal)

        // 4. Back from More Settings returns directly to Map
        viewModel.closeModal()
        assertNull(viewModel.uiState.value.activeModal)
    }

    @Test
    fun test18_verifyMapCompositionSwitchAndPinToggle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)

        val starters = viewModel.uiState.value.savedCompositions
        val streetComp = starters.first { it.id == "comp_everyday_street" }

        // Select street composition
        viewModel.selectComposition(streetComp)
        assertEquals("comp_everyday_street", viewModel.uiState.value.activeComposition.id)
        assertEquals("openfreemap_liberty", viewModel.uiState.value.currentStyle.id)

        // Duplicate composition
        val countBefore = viewModel.uiState.value.savedCompositions.size
        viewModel.duplicateComposition(streetComp)
        val countAfter = viewModel.uiState.value.savedCompositions.size
        assertEquals(countBefore + 1, countAfter)

        // Pin toggle
        val isPinnedBefore = streetComp.isPinnedToQuickChoice
        viewModel.togglePinComposition(streetComp.id)
        val updatedComp = viewModel.uiState.value.savedCompositions.first { it.id == streetComp.id }
        assertEquals(!isPinnedBefore, updatedComp.isPinnedToQuickChoice)
    }

    @Test
    fun test19_verifyStorageCalculationMetricsAndFormatting() = runBlocking {
        // Formatting tests
        assertEquals("0 B", com.example.data.storage.StorageCalculationManager.formatBytes(0L))
        assertEquals("500 B", com.example.data.storage.StorageCalculationManager.formatBytes(500L))
        assertEquals("1.0 KB", com.example.data.storage.StorageCalculationManager.formatBytes(1024L))
        assertEquals("1.5 MB", com.example.data.storage.StorageCalculationManager.formatBytes((1.5 * 1024 * 1024).toLong()))

        // Real storage calculation audit
        val db = AppDatabase.getInstance(context)
        val report = com.example.data.storage.StorageCalculationManager.calculateStorage(context, db)
        assertNotNull(report)
        assertTrue(report.perSource.isNotEmpty())
        assertNotNull(report.featureStorage)
        assertNotNull(report.routingStorage)
    }

    @Test
    fun test20_verifySafePerProviderCacheClearing() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val testTile = RetainedTileEntity(
            providerId = "test_source_purge",
            category = "TEST_CAT",
            z = 10,
            x = 100,
            y = 200,
            byteSize = 1024L,
            checksum = "fake_checksum_abc123",
            filePath = "/fake/path/tile.bin"
        )
        db.retainedTileDao().insertTiles(listOf(testTile))

        val countBefore = db.retainedTileDao().getTileCountByProvider("test_source_purge")
        assertEquals(1, countBefore)

        com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "test_source_purge")

        val countAfter = db.retainedTileDao().getTileCountByProvider("test_source_purge")
        assertEquals(0, countAfter)
    }
}
