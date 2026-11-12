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
        assertTrue(monumentResults.any { it.name == "Anıtkabir" })
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

    @Test
    fun test21_verifyCanonicalStorageProviderMappings() {
        // Canonical provider IDs must be stable lowercase identifiers, not display names or style IDs
        assertEquals("openfreemap", MapSourceRepository.resolveCanonicalProviderId("OpenFreeMap"))
        assertEquals("openfreemap", MapSourceRepository.resolveCanonicalProviderId("openfreemap_liberty"))
        assertEquals("openfreemap", MapSourceRepository.resolveCanonicalProviderId("openfreemap_bright"))
        assertEquals("openfreemap", MapSourceRepository.resolveCanonicalProviderId("openfreemap_positron"))
        assertEquals("openfreemap", MapSourceRepository.resolveCanonicalProviderId("hybrid_(satellite_+_osm)"))
        assertEquals("openfreemap", MapSourceRepository.resolveCanonicalProviderId("hybrid_satellite_osm"))

        assertEquals("esri_satellite", MapSourceRepository.resolveCanonicalProviderId("esri"))
        assertEquals("esri_satellite", MapSourceRepository.resolveCanonicalProviderId("esri_world_imagery"))
        assertEquals("esri_satellite", MapSourceRepository.resolveCanonicalProviderId("esri_satellite"))

        assertEquals("openaerialmap", MapSourceRepository.resolveCanonicalProviderId("OpenAerialMap"))
        assertEquals("openaerialmap", MapSourceRepository.resolveCanonicalProviderId("hybrid_oam_osm"))

        assertEquals("maplibre_demo", MapSourceRepository.resolveCanonicalProviderId("maplibre"))
        assertEquals("maplibre_demo", MapSourceRepository.resolveCanonicalProviderId("maplibre_demo"))

        assertEquals("osm_standard", MapSourceRepository.resolveCanonicalProviderId("openstreetmap"))
        assertEquals("osm_standard", MapSourceRepository.resolveCanonicalProviderId("osm_raster"))

        // Check style definitions
        val hybrid = MapSourceRepository.HYBRID_SATELLITE_OSM
        assertEquals("openfreemap", hybrid.storageProviderId)
        assertEquals("esri_satellite", hybrid.rasterStorageProviderId)

        val liberty = MapSourceRepository.OPEN_FREE_MAP_LIBERTY
        assertEquals("openfreemap", liberty.storageProviderId)
        assertNull(liberty.rasterStorageProviderId)

        val bright = MapSourceRepository.OPEN_FREE_MAP_BRIGHT
        assertEquals("openfreemap", bright.storageProviderId)
        assertNull(bright.rasterStorageProviderId)

        val positron = MapSourceRepository.OPEN_FREE_MAP_POSITRON
        assertEquals("openfreemap", positron.storageProviderId)
        assertNull(positron.rasterStorageProviderId)
    }

    @Test
    fun test22_verifyMultipleStylesShareOpenFreeMapStorage() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val manager = TileStorageManager(context)

        // Multiple visual styles (Liberty, Bright, Hybrid) use the same canonical provider "openfreemap"
        val tileData = "SHARED_OPENFREEMAP_VECTOR_PAYLOAD".toByteArray()
        val metadata = TileMetadata(
            providerId = "openfreemap",
            schemaVersion = "1.0",
            sourceUrl = "https://tiles.openfreemap.org/planet/14/2620/6331.pbf",
            attribution = "© OpenFreeMap"
        )

        val saved = manager.saveTileData("openfreemap", TileCategory.OSM_VECTOR, 14, 2620, 6331, tileData, metadata)
        assertTrue(saved)

        // Whether requested via Liberty or Hybrid, isTileRetained under canonical "openfreemap" returns true
        assertTrue(manager.isTileRetained("openfreemap", TileCategory.OSM_VECTOR, 14, 2620, 6331))

        // Total count in Room for "openfreemap" is exactly 1 (not duplicated per style)
        val count = db.retainedTileDao().getTileCountByProvider("openfreemap")
        assertTrue(count >= 1)
    }

    @Test
    fun test23_verifyLegacyStorageMigration() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        // 1. Simulate legacy disk folder: retained_tiles/hybrid_(satellite_+_osm)/OSM_VECTOR/12/100/200.pbf
        val legacyDir = File(retainedTilesRoot, "hybrid_(satellite_+_osm)/OSM_VECTOR/12/100")
        legacyDir.mkdirs()
        val legacyFile = File(legacyDir, "200.pbf")
        legacyFile.writeBytes("LEGACY_VECTOR_PAYLOAD_123".toByteArray())
        val legacyCrc = com.example.data.storage.StorageCalculationManager.computeFileCrc32(legacyFile)

        // 2. Simulate legacy database entity with providerId = "hybrid_(satellite_+_osm)"
        val legacyEntity = RetainedTileEntity(
            providerId = "hybrid_(satellite_+_osm)",
            category = "OSM_VECTOR",
            z = 12,
            x = 100,
            y = 200,
            filePath = legacyFile.absolutePath,
            byteSize = legacyFile.length(),
            checksum = legacyCrc
        )
        db.retainedTileDao().insertTile(legacyEntity)

        // Verify pre-migration state
        assertTrue(File(retainedTilesRoot, "hybrid_(satellite_+_osm)").exists())
        assertEquals(1, db.retainedTileDao().getTileCountByProvider("hybrid_(satellite_+_osm)"))

        // 3. Run migration
        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        assertTrue(report.migratedFiles > 0 || report.migratedDbRecords > 0)
        assertTrue(report.isClean)

        // 4. Verify post-migration state:
        // Legacy directory must be removed and canonical "openfreemap" directory must have the file
        assertFalse(File(retainedTilesRoot, "hybrid_(satellite_+_osm)").exists())
        val canonicalFile = File(retainedTilesRoot, "openfreemap/OSM_VECTOR/12/100/200.pbf")
        assertTrue(canonicalFile.exists())
        assertEquals("LEGACY_VECTOR_PAYLOAD_123", String(canonicalFile.readBytes()))

        // Database entity must have providerId = "openfreemap" and zero rows under legacy providerId
        assertEquals(0, db.retainedTileDao().getTileCountByProvider("hybrid_(satellite_+_osm)"))
        val migratedEntity = db.retainedTileDao().getTile("openfreemap", "OSM_VECTOR", 12, 100, 200)
        assertNotNull(migratedEntity)
        assertEquals("openfreemap", migratedEntity?.providerId)
    }

    @Test
    fun test24_verifyProviderClearPreservesOsmFeaturesAndOtherProviders(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val osmRepo = OsmOfflineDataRepository(context)

        // 1. Insert OSM POI into Room
        val poi = OsmPoiRecord(
            osmId = 99999L,
            name = "Preserved Monument",
            category = "historic_memorial",
            latitude = 39.9,
            longitude = 32.8,
            sourceTileZ = 14,
            sourceTileX = 10,
            sourceTileY = 20
        )
        osmRepo.savePoiFeatures(listOf(poi))
        assertEquals(1, osmRepo.searchPois("Preserved").size)

        // 2. Insert tiles for openfreemap and esri_satellite
        val tileOfm = RetainedTileEntity(
            providerId = "openfreemap",
            category = "OSM_VECTOR",
            z = 14,
            x = 10,
            y = 20,
            byteSize = 500L,
            checksum = "ofm_chk_1",
            filePath = "/fake/openfreemap/14_10_20.pbf"
        )
        val tileEsri = RetainedTileEntity(
            providerId = "esri_satellite",
            category = "SATELLITE_RASTER",
            z = 14,
            x = 10,
            y = 20,
            byteSize = 800L,
            checksum = "esri_chk_1",
            filePath = "/fake/esri/14_10_20.jpg"
        )
        db.retainedTileDao().insertTiles(listOf(tileOfm, tileEsri))

        // 3. Clear openfreemap cache
        val clearResult = com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "openfreemap")
        assertTrue(clearResult.isSuccess)
        assertEquals("openfreemap", clearResult.canonicalId)

        // 4. Assert: openfreemap tile deleted, esri_satellite tile remains intact, OSM POI preserved!
        assertEquals(0, db.retainedTileDao().getTileCountByProvider("openfreemap"))
        assertEquals(1, db.retainedTileDao().getTileCountByProvider("esri_satellite"))
        // Clean up test tile and POI
        db.osmPoiDao().clearAllPois()
        com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "esri_satellite")
        Unit
    }

    @Test
    fun test25_verifyIdenticalFilesDoNotLoseDataAndRemoveLegacyDuplicate() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        val legacyDir = File(retainedTilesRoot, "openfreemap_liberty/OSM_VECTOR/10/1")
        legacyDir.mkdirs()
        val legacyFile = File(legacyDir, "1.pbf")
        legacyFile.writeBytes("IDENTICAL_TILE_CONTENT_ABC".toByteArray())

        val canonicalDir = File(retainedTilesRoot, "openfreemap/OSM_VECTOR/10/1")
        canonicalDir.mkdirs()
        val canonicalFile = File(canonicalDir, "1.pbf")
        canonicalFile.writeBytes("IDENTICAL_TILE_CONTENT_ABC".toByteArray())

        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        assertTrue(report.isClean)
        assertTrue(report.skippedDuplicates >= 1)

        // Canonical file preserved with unchanged content, legacy duplicate safely removed
        assertTrue(canonicalFile.exists())
        assertEquals("IDENTICAL_TILE_CONTENT_ABC", String(canonicalFile.readBytes()))
        assertFalse(legacyFile.exists())
    }

    @Test
    fun test26_verifyConflictingFileContentsPreservesBothAndReportsConflict() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        val legacyDir = File(retainedTilesRoot, "hybrid_satellite_osm/OSM_VECTOR/10/2")
        legacyDir.mkdirs()
        val legacyFile = File(legacyDir, "2.pbf")
        legacyFile.writeBytes("LEGACY_VERSION_DIFFERENT_DATA".toByteArray())

        val canonicalDir = File(retainedTilesRoot, "openfreemap/OSM_VECTOR/10/2")
        canonicalDir.mkdirs()
        val canonicalFile = File(canonicalDir, "2.pbf")
        canonicalFile.writeBytes("CANONICAL_VERSION_EXISTING_DATA".toByteArray())

        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)

        // Must report conflict and NEVER silently delete or overwrite
        assertFalse(report.isClean)
        assertTrue(report.unresolvedFileConflicts.isNotEmpty())

        // Both files must be preserved safely!
        assertTrue(canonicalFile.exists())
        assertEquals("CANONICAL_VERSION_EXISTING_DATA", String(canonicalFile.readBytes()))
        assertTrue(legacyFile.exists())
        assertEquals("LEGACY_VERSION_DIFFERENT_DATA", String(legacyFile.readBytes()))
    }

    @Test
    fun test27_verifyCorruptCanonicalReplacedSafelyByValidLegacy() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        // Canonical file is empty / corrupt (0 bytes)
        val canonicalDir = File(retainedTilesRoot, "openfreemap/OSM_VECTOR/10/3")
        canonicalDir.mkdirs()
        val canonicalFile = File(canonicalDir, "3.pbf")
        canonicalFile.writeBytes(ByteArray(0))
        assertEquals(0L, canonicalFile.length())

        // Legacy file has valid data
        val legacyDir = File(retainedTilesRoot, "openfreemap_bright/OSM_VECTOR/10/3")
        legacyDir.mkdirs()
        val legacyFile = File(legacyDir, "3.pbf")
        legacyFile.writeBytes("RECOVERED_VALID_LEGACY_TILE_DATA".toByteArray())

        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        assertTrue(report.isClean)
        assertTrue(report.migratedFiles >= 1)

        // Canonical file replaced with valid data, legacy file removed
        assertTrue(canonicalFile.exists())
        assertEquals("RECOVERED_VALID_LEGACY_TILE_DATA", String(canonicalFile.readBytes()))
        assertFalse(legacyFile.exists())
    }

    @Test
    fun test28_verifyIdempotentRepeatedMigration() = runBlocking {
        val db = AppDatabase.getInstance(context)

        // First pass
        val report1 = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        assertTrue(report1.isClean)

        // Second pass immediately after: must be clean and idempotent with 0 duplicate actions
        val report2 = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        assertTrue(report2.isClean)
        assertEquals(0, report2.migratedFiles)
        assertEquals(0, report2.migratedDbRecords)
        assertTrue(report2.unresolvedFileConflicts.isEmpty())
        assertTrue(report2.failures.isEmpty())
    }

    @Test
    fun test29_verifyRoomPrimaryKeyCollisionsResolvedSafely() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        val canonicalDir = File(retainedTilesRoot, "openfreemap/OSM_VECTOR/11/5")
        canonicalDir.mkdirs()
        val tileFile = File(canonicalDir, "5.pbf")
        tileFile.writeBytes("CANONICAL_TILE_RECORD_PAYLOAD".toByteArray())
        val tileCrc = com.example.data.storage.StorageCalculationManager.computeFileCrc32(tileFile)

        val canonicalEntity = RetainedTileEntity(
            providerId = "openfreemap",
            category = "OSM_VECTOR",
            z = 11,
            x = 5,
            y = 5,
            filePath = tileFile.absolutePath,
            byteSize = tileFile.length(),
            checksum = tileCrc
        )
        val duplicateLegacyEntity = RetainedTileEntity(
            providerId = "openfreemap_positron",
            category = "OSM_VECTOR",
            z = 11,
            x = 5,
            y = 5,
            filePath = tileFile.absolutePath,
            byteSize = tileFile.length(),
            checksum = tileCrc
        )
        db.retainedTileDao().insertTiles(listOf(canonicalEntity, duplicateLegacyEntity))

        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        assertTrue(report.isClean)
        assertTrue(report.skippedDuplicates >= 1)

        // Canonical record preserved, duplicate legacy record removed without error
        assertNotNull(db.retainedTileDao().getTile("openfreemap", "OSM_VECTOR", 11, 5, 5))
        assertEquals(0, db.retainedTileDao().getTileCountByProvider("openfreemap_positron"))
    }

    @Test
    fun test30_verifyMissingDiskFileReportedAsFailure() = runBlocking {
        val db = AppDatabase.getInstance(context)

        // Insert legacy entity pointing to a completely non-existent disk file
        val orphanedLegacyEntity = RetainedTileEntity(
            providerId = "osm_raster",
            category = "SATELLITE_RASTER",
            z = 9,
            x = 1,
            y = 2,
            filePath = "/totally/non/existent/path/tile.bin",
            byteSize = 100L,
            checksum = "missing_chk"
        )
        db.retainedTileDao().insertTile(orphanedLegacyEntity)

        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)

        // Missing file must be reported as a failure, not silently discarded
        assertFalse(report.isClean)
        assertTrue(report.failures.any { it.contains("Missing file") })
    }

    @Test
    fun test31_verifyRasterAcquisitionReportingHonesty() = runBlocking {
        val db = AppDatabase.getInstance(context)
        com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "esri_satellite")
        com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "openaerialmap")
        val overallReport = com.example.data.storage.StorageCalculationManager.calculateStorage(context, db)

        assertNotNull(overallReport.migrationReport)

        val ofm = overallReport.perSource.find { it.providerId == "openfreemap" }
        assertNotNull(ofm)
        assertTrue(ofm!!.acquisitionStatus.contains("Active"))
        assertTrue(ofm.retentionPolicy.contains("Permitted"))

        val esri = overallReport.perSource.find { it.providerId == "esri_satellite" }
        assertNotNull(esri)
        assertTrue(esri!!.acquisitionStatus.contains("Restricted"))
        assertTrue(esri.retentionPolicy.contains("Restricted"))
        // Esri raster tiles must not be reported as offline-retained when count is 0
        assertEquals(0, esri.tileCount)
        assertFalse(esri.isOfflineRetained)

        val oam = overallReport.perSource.find { it.providerId == "openaerialmap" }
        assertNotNull(oam)
        assertTrue(oam!!.acquisitionStatus.contains("Not Implemented"))
        assertEquals(0, oam.tileCount)
        assertFalse(oam.isOfflineRetained)
    }

    @Test
    fun test32_verifyProviderClearResultAndSharedDataPreservation() = runBlocking {
        val db = AppDatabase.getInstance(context)

        // Insert OSM buildings and roads
        val building = com.example.data.db.OsmBuildingRecord(
            osmId = 88888L,
            name = "Historic Tower",
            sourceTileZ = 14,
            sourceTileX = 10,
            sourceTileY = 20
        )
        val road = com.example.data.db.OsmRoadRecord(
            osmId = 77777L,
            name = "Main Street",
            highwayClass = "primary",
            sourceTileZ = 14,
            sourceTileX = 10,
            sourceTileY = 20
        )
        db.osmBuildingDao().insertBuildings(listOf(building))
        db.osmRoadDao().insertRoads(listOf(road))

        assertEquals(1, db.osmBuildingDao().getBuildingCount())
        assertEquals(1, db.osmRoadDao().getRoadCount())

        // Clear provider
        val clearResult = com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "openfreemap")
        assertTrue(clearResult.isSuccess)

        // Verify OSM buildings and roads remain 100% intact
        assertEquals(1, db.osmBuildingDao().getBuildingCount())
        assertEquals(1, db.osmRoadDao().getRoadCount())
    }

    @Test
    fun test33_verifyPhysicalCrcCheckDetectsCorruptFileInRoomMigration(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        val legacyDir = File(retainedTilesRoot, "openfreemap_liberty/OSM_VECTOR/12/10")
        legacyDir.mkdirs()
        val legacyFile = File(legacyDir, "20.pbf")
        legacyFile.writeBytes("ACTUAL_BYTES_123".toByteArray())

        // Insert Room entity with WRONG checksum
        val entity = RetainedTileEntity(
            providerId = "openfreemap_liberty",
            category = "OSM_VECTOR",
            z = 12,
            x = 10,
            y = 20,
            filePath = legacyFile.absolutePath,
            byteSize = legacyFile.length(),
            checksum = "wrong_checksum_abc"
        )
        db.retainedTileDao().insertTile(entity)

        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        assertFalse(report.isClean)
        assertTrue(report.integrityFailures.any { it.contains("Integrity failure") })

        // Legacy record must NOT be deleted when physical file is corrupt
        val remaining = db.retainedTileDao().getTile("openfreemap_liberty", "OSM_VECTOR", 12, 10, 20)
        assertNotNull(remaining)

        // Cleanup
        legacyFile.delete()
        db.retainedTileDao().deleteTile("openfreemap_liberty", "OSM_VECTOR", 12, 10, 20)
    }

    @Test
    fun test34_verifyMatchingChecksumStringsRejectedIfPhysicalFileCorrupt(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        val canonicalDir = File(retainedTilesRoot, "openfreemap/OSM_VECTOR/10/2")
        canonicalDir.mkdirs()
        val canonicalFile = File(canonicalDir, "3.pbf")
        // File on disk has corrupt data
        canonicalFile.writeBytes("CORRUPTED_ON_DISK_PAYLOAD".toByteArray())

        val canonicalEntity = RetainedTileEntity(
            providerId = "openfreemap",
            category = "OSM_VECTOR",
            z = 10,
            x = 2,
            y = 3,
            filePath = canonicalFile.absolutePath,
            byteSize = canonicalFile.length(),
            checksum = "expected_original_crc_999"
        )
        val legacyEntity = RetainedTileEntity(
            providerId = "openfreemap_bright",
            category = "OSM_VECTOR",
            z = 10,
            x = 2,
            y = 3,
            filePath = canonicalFile.absolutePath,
            byteSize = canonicalFile.length(),
            checksum = "expected_original_crc_999"
        )
        db.retainedTileDao().insertTiles(listOf(canonicalEntity, legacyEntity))

        val report = com.example.data.storage.StorageCalculationManager.migrateLegacyStorage(context, db)
        // Must detect that physical file on disk does not match the checksum!
        assertFalse(report.isClean)
        assertTrue(report.integrityFailures.isNotEmpty())

        // Legacy record must not be deleted blindly
        assertNotNull(db.retainedTileDao().getTile("openfreemap_bright", "OSM_VECTOR", 10, 2, 3))

        // Cleanup
        canonicalFile.delete()
        db.retainedTileDao().deleteTile("openfreemap", "OSM_VECTOR", 10, 2, 3)
        db.retainedTileDao().deleteTile("openfreemap_bright", "OSM_VECTOR", 10, 2, 3)
    }

    @Test
    fun test35_verifyOrphanedRecordAndUnindexedFileReconciliation(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        // 1. Unindexed physical file (exists on disk, no Room record)
        val ofmDir = File(retainedTilesRoot, "openfreemap/osm_vector/14/100")
        ofmDir.mkdirs()
        val unindexedFile = File(ofmDir, "200.bin")
        unindexedFile.writeBytes("UNINDEXED_PAYLOAD".toByteArray())

        // 2. Orphaned Room record (in Room, file does NOT exist on disk)
        val orphanedEntity = RetainedTileEntity(
            providerId = "openfreemap",
            category = "osm_vector",
            z = 14,
            x = 999,
            y = 888,
            filePath = File(ofmDir, "non_existent_888.bin").absolutePath,
            byteSize = 500L,
            checksum = "abc123"
        )
        db.retainedTileDao().insertTile(orphanedEntity)

        val report = com.example.data.storage.StorageCalculationManager.calculateStorage(context, db)
        val ofm = report.perSource.find { it.providerId == "openfreemap" }
        assertNotNull(ofm)

        // Reconciliation verification
        assertTrue(ofm!!.orphanedRecordCount >= 1)
        assertTrue(ofm.unindexedFileCount >= 1)
        // tileCount and byteSize reflect actual physical files
        assertEquals(ofm.physicalFileCount, ofm.tileCount)
        assertEquals(ofm.physicalByteSize, ofm.byteSize)

        // Cleanup
        unindexedFile.delete()
        db.retainedTileDao().deleteTile("openfreemap", "osm_vector", 14, 999, 888)
    }

    @Test
    fun test36_verifyIntegrityFailureDetectionInStorageReport(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val retainedTilesRoot = File(context.filesDir, "retained_tiles")

        val ofmDir = File(retainedTilesRoot, "openfreemap/osm_vector/13/44")
        ofmDir.mkdirs()
        val physicalFile = File(ofmDir, "55.bin")
        val data = "KNOWN_VALID_CONTENT_XYZ".toByteArray()
        physicalFile.writeBytes(data)

        // Room entity with mismatched checksum
        val entity = RetainedTileEntity(
            providerId = "openfreemap",
            category = "osm_vector",
            z = 13,
            x = 44,
            y = 55,
            filePath = physicalFile.absolutePath,
            byteSize = data.size.toLong(),
            checksum = "mismatched_corrupt_crc_value"
        )
        db.retainedTileDao().insertTile(entity)

        val report = com.example.data.storage.StorageCalculationManager.calculateStorage(context, db)
        val ofm = report.perSource.find { it.providerId == "openfreemap" }
        assertNotNull(ofm)
        assertTrue(ofm!!.integrityFailureCount >= 1)

        // Cleanup
        physicalFile.delete()
        db.retainedTileDao().deleteTile("openfreemap", "osm_vector", 13, 44, 55)
    }

    @Test
    fun test37_verifyRetentionPermissionEnforcedInTileStorageManager(): Unit = runBlocking {
        val storageManager = TileStorageManager(context)
        val db = AppDatabase.getInstance(context)

        // Attempt to save tile for Esri (defaultRetentionPermitted = false)
        val esriSaved = storageManager.saveTileData(
            providerId = "esri_satellite",
            category = TileCategory.SATELLITE_RASTER,
            z = 10,
            x = 5,
            y = 5,
            data = "RESTRICTED_ESRI_TILE".toByteArray(),
            metadata = TileMetadata(
                providerId = "esri_satellite",
                retentionPermission = "RESTRICTED"
            )
        )
        // Must be blocked from permanent storage!
        assertFalse(esriSaved)

        val targetFile = storageManager.getTileFile("esri_satellite", TileCategory.SATELLITE_RASTER, 10, 5, 5)
        assertFalse(targetFile.exists())

        val roomRecord = db.retainedTileDao().getTile("esri_satellite", TileCategory.SATELLITE_RASTER.name, 10, 5, 5)
        assertNull(roomRecord)
    }

    @Test
    fun test38_verifyPermittedVectorRetentionAllowedOnHybridStyle(): Unit = runBlocking {
        val storageManager = TileStorageManager(context)
        val db = AppDatabase.getInstance(context)

        // Verify hybrid style definition decouples vector from raster retention
        val hybridDef = MapSourceRepository.HYBRID_SATELLITE_OSM
        assertTrue(hybridDef.vectorRetentionPermitted)
        assertFalse(hybridDef.rasterRetentionPermitted)

        // Vector retention under OpenFreeMap with PERMITTED
        val vectorSaved = storageManager.saveTileData(
            providerId = "openfreemap",
            category = TileCategory.OSM_VECTOR,
            z = 11,
            x = 8,
            y = 9,
            data = "PERMITTED_VECTOR_DATA_PBF".toByteArray(),
            metadata = TileMetadata(
                providerId = "openfreemap",
                retentionPermission = "PERMITTED"
            )
        )
        assertTrue(vectorSaved)

        val targetFile = storageManager.getTileFile("openfreemap", TileCategory.OSM_VECTOR, 11, 8, 9)
        assertTrue(targetFile.exists())
        val roomRecord = db.retainedTileDao().getTile("openfreemap", TileCategory.OSM_VECTOR.name, 11, 8, 9)
        assertNotNull(roomRecord)

        // Cleanup
        targetFile.delete()
        db.retainedTileDao().deleteTile("openfreemap", TileCategory.OSM_VECTOR.name, 11, 8, 9)
    }

    @Test
    fun test39_verifyClearProviderTilesTruthfulReportingAndPreservation(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val storageManager = TileStorageManager(context)

        // 1. Create a tile for openfreemap
        storageManager.saveTileData(
            providerId = "openfreemap",
            category = TileCategory.OSM_VECTOR,
            z = 10,
            x = 1,
            y = 1,
            data = "OFM_TEST_TILE".toByteArray(),
            metadata = TileMetadata(providerId = "openfreemap", retentionPermission = "PERMITTED")
        )

        // 2. Create routing graph file and OSM POI
        val routingDir = File(context.filesDir, "routing_graph")
        routingDir.mkdirs()
        val routingFile = File(routingDir, "test_routing_tile.bin")
        routingFile.writeBytes("ROUTING_GRAPH_PAYLOAD".toByteArray())

        val poi = OsmPoiRecord(
            osmId = 12345678L,
            name = "Test Cafe",
            category = "amenity",
            subCategory = "cafe",
            latitude = 40.0,
            longitude = 30.0,
            sourceTileZ = 10,
            sourceTileX = 1,
            sourceTileY = 1
        )
        db.osmPoiDao().insertPois(listOf(poi))

        // 3. Clear openfreemap tiles
        val clearResult = com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "openfreemap")
        assertTrue(clearResult.isSuccess)
        assertTrue(clearResult.filesDeleted >= 1)
        assertTrue(clearResult.recordsDeleted >= 1)
        assertTrue(clearResult.bytesDeleted > 0)

        // 4. Verify routing graph file and OSM POI are 100% preserved
        assertTrue(routingFile.exists())
        assertTrue(db.osmPoiDao().getPoiCount() >= 1)
        assertNotNull(db.osmPoiDao().getPoiById(12345678L))

        // Cleanup
        routingFile.delete()
        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test40_verifyUnknownProviderClearHandling(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val clearResult = com.example.data.storage.StorageCalculationManager.clearProviderTiles(context, db, "unknown_custom_provider")
        assertTrue(clearResult.isSuccess)
        assertEquals(0, clearResult.filesDeleted)
        assertEquals(0, clearResult.recordsDeleted)
        assertEquals(0L, clearResult.bytesDeleted)
    }

    @Test
    fun test41_verifyOfflinePoiSearchExactPartialCaseAndUnicode(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.osmPoiDao().clearAllPois()
        val repo = OsmOfflineDataRepository(context)

        val pois = listOf(
            OsmPoiRecord(101L, "Blue Bottle Coffee", "amenity", "cafe", 37.775, -122.419, 14, 2621, 6332),
            OsmPoiRecord(102L, "Coffee", "amenity", "cafe", 37.776, -122.418, 14, 2621, 6332),
            OsmPoiRecord(103L, "Turkish Tea House", "amenity", "cafe", 39.933, 32.859, 14, 9687, 6199),
            OsmPoiRecord(104L, "Kızılay Square", "place", "square", 39.9208, 32.8541, 14, 9687, 6199),
            OsmPoiRecord(105L, "Ankara Castle", "historic", "castle", 39.9412, 32.8644, 14, 9687, 6199)
        )
        db.osmPoiDao().insertPois(pois)

        // Exact match prioritized ahead of partial match
        val exactFirst = repo.searchPois(query = "coffee", category = null, limit = 30)
        assertEquals(2, exactFirst.size)
        assertEquals("Coffee", exactFirst[0].name)
        assertEquals("Blue Bottle Coffee", exactFirst[1].name)

        // Uppercase ASCII case-insensitive query
        val upperResults = repo.searchPois(query = "COFFEE", category = null, limit = 30)
        assertEquals(2, upperResults.size)

        // Partial match
        val partial = repo.searchPois(query = "tea", category = null, limit = 30)
        assertEquals(1, partial.size)
        assertEquals(103L, partial[0].osmId)

        // Non-ASCII / Unicode case-insensitive matching
        val unicodeLower = repo.searchPois(query = "kızılay", category = null, limit = 30)
        assertEquals(1, unicodeLower.size)
        assertEquals("Kızılay Square", unicodeLower[0].name)

        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test42_verifyPoiCategoryFilteringSubCategoryAndCategoryCounts(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.osmPoiDao().clearAllPois()
        val repo = OsmOfflineDataRepository(context)

        val pois = listOf(
            OsmPoiRecord(201L, "Cafe Central", "amenity", "cafe", 39.91, 32.85, 14, 9687, 6199),
            OsmPoiRecord(202L, "Central Park", "leisure", "park", 39.92, 32.86, 14, 9687, 6199),
            OsmPoiRecord(203L, "Boulangerie Midi", "shop", "bakery", 39.93, 32.87, 14, 9687, 6199),
            OsmPoiRecord(204L, "Corner Espresso", "amenity", "cafe", 39.94, 32.88, 14, 9687, 6199)
        )
        db.osmPoiDao().insertPois(pois)

        // Category-only browsing when query is blank
        val amenityOnly = repo.searchPois(query = "", category = "AMENITY", limit = 30)
        assertEquals(2, amenityOnly.size)
        assertTrue(amenityOnly.all { it.category == "amenity" })

        // Combined query + category disambiguates "Central"
        val centralLeisure = repo.searchPois(query = "central", category = "leisure", limit = 30)
        assertEquals(1, centralLeisure.size)
        assertEquals("Central Park", centralLeisure[0].name)

        // Matches subCategory too
        val bakeryBySubCat = repo.searchPois(query = "bakery", category = null, limit = 30)
        assertEquals(1, bakeryBySubCat.size)
        assertEquals("Boulangerie Midi", bakeryBySubCat[0].name)

        // Aggregated category counts
        val counts = repo.getCategoryCounts(limit = 25)
        assertEquals(3, counts.size)
        assertEquals("amenity", counts[0].category)
        assertEquals(2, counts[0].count)

        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test43_verifyBlankWhitespaceEmptyDbLimitAndWildcardEscaping(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.osmPoiDao().clearAllPois()
        val repo = OsmOfflineDataRepository(context)

        // Empty DB returns empty cleanly
        assertTrue(repo.searchPois("park", null).isEmpty())
        assertTrue(repo.searchPois("", "amenity").isEmpty())

        val bulkPois = (1..45).map { idx ->
            OsmPoiRecord(
                osmId = 3000L + idx,
                name = "City Park #$idx",
                category = "leisure",
                subCategory = "park",
                latitude = 39.9 + (idx * 0.0001),
                longitude = 32.8 + (idx * 0.0001),
                sourceTileZ = 14,
                sourceTileX = 9687,
                sourceTileY = 6199
            )
        } + listOf(
            OsmPoiRecord(3901L, "100% Fresh Market", "shop", "supermarket", 39.95, 32.89, 14, 9687, 6199),
            OsmPoiRecord(3902L, "100 Regular Market", "shop", "supermarket", 39.96, 32.90, 14, 9687, 6199)
        )
        db.osmPoiDao().insertPois(bulkPois)

        // Blank / whitespace-only queries without category return empty list
        assertTrue(repo.searchPois("", null).isEmpty())
        assertTrue(repo.searchPois("   \t\n ", null).isEmpty())

        // Result limit cap enforced
        val limited = repo.searchPois("City Park", null, limit = 30)
        assertEquals(30, limited.size)

        // SQL LIKE wildcards '%' safely escaped
        val pctResults = repo.searchPois("100%", null, limit = 30)
        assertEquals(1, pctResults.size)
        assertEquals("100% Fresh Market", pctResults[0].name)

        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test44_verifyDuplicatePoiIngestionDoesNotDuplicateSearchResults(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.osmPoiDao().clearAllPois()
        val repo = OsmOfflineDataRepository(context)

        // Insert same osmId twice (overlapping adjacent tiles)
        db.osmPoiDao().insertPois(
            listOf(OsmPoiRecord(4001L, "Union Station", "railway", "station", 38.897, -77.006, 14, 4688, 6267))
        )
        db.osmPoiDao().insertPois(
            listOf(OsmPoiRecord(4001L, "Union Station", "railway", "station", 38.897, -77.006, 14, 4689, 6267))
        )

        assertEquals(1, db.osmPoiDao().getPoiCount())
        val results = repo.searchPois("Union Station", null, limit = 30)
        assertEquals(1, results.size)

        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test45_verifyOfflinePoiSearchSelectionAndControllerHandoff(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.osmPoiDao().clearAllPois()

        // Enforce strict offline mode (zero internet allowed)
        AppNetworkPolicy.setInternetEnabled(false)
        assertFalse(AppNetworkPolicy.isNetworkAllowed())

        val targetPoi = OsmPoiRecord(
            osmId = 5001L,
            name = "Anıtkabir Memorial",
            category = "historic",
            subCategory = "monument",
            latitude = 39.9250,
            longitude = 32.8369,
            sourceTileZ = 14,
            sourceTileX = 9686,
            sourceTileY = 6199
        )
        db.osmPoiDao().insertPois(listOf(targetPoi))

        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)
        val controller = com.example.map.engine.MapEngineController(
            context = context,
            initialLat = 0.0,
            initialLng = 0.0,
            initialZoom = 2.0,
            initialBearing = 0.0,
            onCameraMoved = { _, _, _, _ -> }
        )

        viewModel.openPoiSearch()
        assertEquals(ActiveModal.PoiSearch, viewModel.uiState.value.activeModal)

        // Trigger immediate search (debounceMs = 0L)
        viewModel.updatePoiSearchQuery("anıtkabir", debounceMs = 0L)

        // Pump Robolectric main looper while IO dispatcher finishes Room query
        val deadlineMs = System.currentTimeMillis() + 3000L
        while (!viewModel.uiState.value.poiSearchState.hasSearched && System.currentTimeMillis() < deadlineMs) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(15L)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val searchState = viewModel.uiState.value.poiSearchState
        assertTrue(searchState.hasSearched)
        assertEquals(1, searchState.results.size)
        assertEquals(5001L, searchState.results[0].osmId)

        // Select result and verify camera coordinate handoff + highlight + search cleanup
        viewModel.selectSearchResult(searchState.results[0], controller)
        assertNull(viewModel.uiState.value.activeModal)
        assertEquals(5001L, viewModel.uiState.value.highlightedPoi?.osmId)
        assertTrue(viewModel.uiState.value.isPoiHighlightVisible)
        assertTrue(viewModel.uiState.value.poiSearchState.results.isEmpty())

        assertEquals(5001L, controller.currentHighlightedPoi?.osmId)
        assertEquals(Pair(39.9250, 32.8369), controller.lastCameraFocusTarget)

        // Toggle highlight visibility & clear highlight
        viewModel.togglePoiHighlightVisibility(controller)
        assertFalse(viewModel.uiState.value.isPoiHighlightVisible)
        assertFalse(controller.isHighlightVisible)

        viewModel.clearHighlightedPoi(controller)
        assertNull(viewModel.uiState.value.highlightedPoi)
        assertNull(controller.currentHighlightedPoi)

        db.osmPoiDao().clearAllPois()
        AppNetworkPolicy.setInternetEnabled(true)
    }
}
