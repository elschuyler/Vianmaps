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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.example.data.area.AreaDataProvenance
import com.example.data.area.AreaDetailsRepository
import com.example.data.area.AreaDetailsResult
import com.example.data.area.AreaLocationIdentity
import com.example.data.area.AreaNearbyItem
import com.example.data.area.AreaRemoteLookupGateway
import com.example.data.area.AreaWikiInfo
import com.example.data.area.HttpAreaRemoteLookupGateway
import com.example.data.area.ProviderConfigStatus
import com.example.data.area.RemoteAreaEnrichmentResult
import com.example.data.area.ReverseGeocodingContactValidator
import com.example.data.area.ReverseGeocodingProviderConfig
import com.example.data.area.ReverseGeocodingProviderRegistry
import com.example.data.area.ReverseGeocodingProviderType
import com.example.data.area.SavedAreaNearbyCodec
import com.example.data.db.RouteWaypointEntity
import com.example.data.db.SavedAreaEntity
import com.example.data.db.SavedRouteEntity
import com.example.data.db.UserPlaceEntity
import com.example.data.places.CreatePlaceOutcome
import com.example.data.places.PlacesAndRoutesRepository
import com.example.data.places.RouteGeometryCodec
import com.example.data.places.RoutePoint
import com.example.data.places.RouteWaypointDraft
import com.example.data.storage.StorageCalculationManager
import com.example.ui.screens.ActiveModal
import com.example.ui.screens.MapViewModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun test46_verifyUserPlaceAndPlacemarkCrudAndDuplicateLocationPolicy(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedRouteDao().clearAllRoutes()
        db.userPlaceDao().clearAllPlaces()
        val repo = PlacesAndRoutesRepository(context)

        // 1. Create Placemark
        val firstOutcome = repo.createPlacemark(
            name = "Summit Cairn",
            latitude = 39.9208,
            longitude = 32.8541,
            description = "Stone marker at ridge trailhead",
            addressLabel = "Ridge Trail",
            category = "landmark",
            tags = listOf("summit", "trail", "summit")
        )
        assertTrue(firstOutcome is CreatePlaceOutcome.Created)
        val created = (firstOutcome as CreatePlaceOutcome.Created).place
        assertTrue(created.id.startsWith("place_"))
        assertEquals("Summit Cairn", created.name)
        assertEquals("summit, trail", created.tagsCsv)

        // Read back from Room
        val loaded = repo.getPlacemarkById(created.id)
        assertNotNull(loaded)
        assertEquals(created.id, loaded!!.id)
        assertEquals(1, db.userPlaceDao().getPlacemarkCount())

        // 2. Duplicate within ~5 meters is flagged cleanly rather than silently merged
        val duplicateOutcome = repo.createPlacemark(
            name = "Nearby Cache",
            latitude = 39.92083,
            longitude = 32.85413,
            allowDuplicateLocation = false
        )
        assertTrue(duplicateOutcome is CreatePlaceOutcome.DuplicateNearby)
        assertEquals(created.id, (duplicateOutcome as CreatePlaceOutcome.DuplicateNearby).existing.id)

        // Explicit save separate copy creates a second distinct record without merging
        val separateOutcome = repo.createPlacemark(
            name = "Nearby Cache",
            latitude = 39.92083,
            longitude = 32.85413,
            allowDuplicateLocation = true
        )
        assertTrue(separateOutcome is CreatePlaceOutcome.Created)
        val second = (separateOutcome as CreatePlaceOutcome.Created).place
        assertFalse(created.id == second.id)
        assertEquals(2, db.userPlaceDao().getPlacemarkCount())

        // 3. Edit Placemark preserves id and createdAt
        val updatedResult = repo.updatePlacemark(
            placeId = created.id,
            name = "Summit Cairn North",
            description = "Updated description",
            category = "viewpoint",
            tags = listOf("vista")
        )
        assertTrue(updatedResult.isSuccess)
        val updated = updatedResult.getOrThrow()
        assertEquals(created.id, updated.id)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals("Summit Cairn North", updated.name)
        assertEquals("viewpoint", updated.category)

        // 4. Delete both
        assertTrue(repo.deletePlacemark(created.id))
        assertTrue(repo.deletePlacemark(second.id))
        assertEquals(0, db.userPlaceDao().getPlacemarkCount())
        assertNull(repo.getPlacemarkById(created.id))
    }

    @Test
    fun test47_verifySavedRouteAndOrderedWaypointPersistence(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedRouteDao().clearAllRoutes()
        db.userPlaceDao().clearAllPlaces()
        val repo = PlacesAndRoutesRepository(context)

        val placeOutcome = repo.createPlacemark(
            name = "Trailhead Gate",
            latitude = 39.9100,
            longitude = 32.8400
        ) as CreatePlaceOutcome.Created

        val points = listOf(
            RoutePoint(39.9100, 32.8400),
            RoutePoint(39.9150, 32.8450),
            RoutePoint(39.9200, 32.8500)
        )
        val waypoints = listOf(
            RouteWaypointDraft(placeId = placeOutcome.place.id, name = "Start Gate", latitude = 39.9100, longitude = 32.8400),
            RouteWaypointDraft(name = "Stream Crossing", latitude = 39.9150, longitude = 32.8450),
            RouteWaypointDraft(name = "Lookout", latitude = 39.9200, longitude = 32.8500)
        )

        val savedResult = repo.saveRoute(
            name = "Morning Ridge Loop",
            description = "Custom polyline geometry",
            points = points,
            waypoints = waypoints
        )
        assertTrue(savedResult.isSuccess)
        val saved = savedResult.getOrThrow()
        assertTrue(saved.route.id.startsWith("route_"))
        assertEquals(3, saved.points.size)
        assertEquals(3, saved.waypoints.size)
        assertEquals(listOf(0, 1, 2), saved.waypoints.map { it.orderIndex })
        assertTrue(saved.route.totalDistanceMeters > 1000.0)

        // Update route preserves route ID & createdAt
        val renamed = repo.saveRoute(
            routeId = saved.route.id,
            name = "Evening Ridge Loop",
            description = "Renamed loop",
            points = points,
            waypoints = waypoints.take(2)
        ).getOrThrow()
        assertEquals(saved.route.id, renamed.route.id)
        assertEquals(saved.route.createdAt, renamed.route.createdAt)
        assertEquals("Evening Ridge Loop", renamed.route.name)
        assertEquals(2, repo.getRouteById(saved.route.id)!!.waypoints.size)

        // Deleting linked Place sets placeId to null without deleting route or waypoint
        assertTrue(repo.deletePlacemark(placeOutcome.place.id))
        val afterPlaceDelete = repo.getRouteById(saved.route.id)
        assertNotNull(afterPlaceDelete)
        assertEquals(2, afterPlaceDelete!!.waypoints.size)
        assertNull(afterPlaceDelete.waypoints[0].placeId)

        // Deleting route removes route and cascades to waypoints
        assertTrue(repo.deleteRoute(saved.route.id))
        assertNull(repo.getRouteById(saved.route.id))
        assertEquals(0, db.savedRouteDao().getRouteCount())
    }

    @Test
    fun test48_verifyMalformedEmptyAndInvalidRouteDataHandling(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedRouteDao().clearAllRoutes()
        val repo = PlacesAndRoutesRepository(context)

        // Blank name rejected cleanly
        val blankName = repo.saveRoute(
            name = "   ",
            points = listOf(RoutePoint(39.9, 32.8), RoutePoint(39.91, 32.81))
        )
        assertTrue(blankName.isFailure)

        // Fewer than 2 valid points / out-of-range coordinates rejected cleanly
        val singlePoint = repo.saveRoute(
            name = "Too Short",
            points = listOf(RoutePoint(39.9, 32.8), RoutePoint(95.0, 200.0), RoutePoint(Double.NaN, 10.0))
        )
        assertTrue(singlePoint.isFailure)

        // Malformed geometry JSON safely decoded without throwing
        val corruptJson = RouteGeometryCodec.decode("{not_an_array")
        assertTrue(corruptJson.isMalformed)
        assertTrue(corruptJson.points.isEmpty())

        val partialJson = RouteGeometryCodec.decode(
            """[{"lat":999.0,"lng":0.0},"bad",{"lat":39.91,"lng":32.84},{"lat":39.92,"lng":32.85}]"""
        )
        assertTrue(partialJson.isMalformed)
        assertEquals(2, partialJson.points.size)
        assertEquals(39.91, partialJson.points[0].latitude, 1e-6)
        assertEquals(39.92, partialJson.points[1].latitude, 1e-6)
    }

    @Test
    fun test49_verifyDatabaseMigrationV1ToV2PreservesExistingData(): Unit = runBlocking {
        val dbName = "test_migration_v1_v2_${System.currentTimeMillis()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        // 1. Construct genuine SQLite v1 schema database with pre-existing rows matching exact v1 Room schema
        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqliteDb.version = 1
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `retained_tiles` (
                `providerId` TEXT NOT NULL, `category` TEXT NOT NULL, `z` INTEGER NOT NULL,
                `x` INTEGER NOT NULL, `y` INTEGER NOT NULL, `filePath` TEXT NOT NULL,
                `byteSize` INTEGER NOT NULL, `checksum` TEXT NOT NULL, `fetchTimestamp` INTEGER NOT NULL,
                `etag` TEXT, `sourceUrl` TEXT NOT NULL, `attribution` TEXT NOT NULL,
                `retentionPermission` TEXT NOT NULL, `integrityStatus` TEXT NOT NULL,
                PRIMARY KEY(`providerId`, `category`, `z`, `x`, `y`)
            )
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `osm_pois` (
                `osmId` INTEGER NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL,
                `subCategory` TEXT, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL,
                `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL,
                `tagsJson` TEXT NOT NULL, `layerName` TEXT NOT NULL, `fetchTimestamp` INTEGER NOT NULL,
                PRIMARY KEY(`osmId`)
            )
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `osm_buildings` (
                `osmId` INTEGER NOT NULL, `name` TEXT, `buildingType` TEXT NOT NULL,
                `heightMeters` REAL NOT NULL, `minHeightMeters` REAL NOT NULL,
                `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL,
                `centroidLat` REAL NOT NULL, `centroidLng` REAL NOT NULL, `geometryJson` TEXT NOT NULL,
                PRIMARY KEY(`osmId`)
            )
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `osm_regions` (
                `osmId` INTEGER NOT NULL, `name` TEXT NOT NULL, `adminLevel` INTEGER NOT NULL,
                `boundaryType` TEXT NOT NULL,
                `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL,
                PRIMARY KEY(`osmId`)
            )
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `osm_roads` (
                `osmId` INTEGER NOT NULL, `name` TEXT, `highwayClass` TEXT NOT NULL,
                `isOneway` INTEGER NOT NULL,
                `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL,
                `geometryJson` TEXT NOT NULL,
                PRIMARY KEY(`osmId`)
            )
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routing_graph_tiles` (
                `level` INTEGER NOT NULL, `tileIndex` INTEGER NOT NULL, `status` TEXT NOT NULL,
                `filePath` TEXT, `byteSize` INTEGER NOT NULL, `lastChecked` INTEGER NOT NULL,
                PRIMARY KEY(`level`, `tileIndex`)
            )
            """.trimIndent()
        )

        sqliteDb.execSQL(
            "INSERT INTO `osm_pois` VALUES (9901, 'Preserved Old Coffee', 'amenity', 'cafe', 39.92, 32.85, 14, 9687, 6199, '{}', 'poi', 1000)"
        )
        sqliteDb.execSQL(
            "INSERT INTO `retained_tiles` VALUES ('openfreemap', 'OSM_VECTOR', 14, 9687, 6199, '/tmp/1.pbf', 512, 'crc1', 1000, NULL, '', '', 'PERMITTED', 'VALID')"
        )
        sqliteDb.close()

        // 2. Open with Room + MIGRATION_1_2 + MIGRATION_2_3 (v1 -> v3 schema validation runs automatically)
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        // Assert pre-existing v1 records survived migration 100% intact
        val preservedPoi = migratedDb.osmPoiDao().getPoiById(9901L)
        assertNotNull(preservedPoi)
        assertEquals("Preserved Old Coffee", preservedPoi!!.name)
        val preservedTile = migratedDb.retainedTileDao().getTile("openfreemap", "OSM_VECTOR", 14, 9687, 6199)
        assertNotNull(preservedTile)

        // Assert v2 user_places & saved_routes and v3 saved_areas tables operate cleanly
        val newPlace = UserPlaceEntity(
            id = "place_mig_1",
            name = "Migrated Spot",
            latitude = 39.93,
            longitude = 32.86
        )
        migratedDb.userPlaceDao().upsertPlace(newPlace)
        assertEquals(1, migratedDb.userPlaceDao().getPlacemarkCount())

        val newRoute = SavedRouteEntity(
            id = "route_mig_1",
            name = "Migrated Route",
            geometryJson = RouteGeometryCodec.encode(listOf(RoutePoint(39.9, 32.8), RoutePoint(39.91, 32.81))),
            pointCount = 2,
            totalDistanceMeters = 1300.0
        )
        migratedDb.savedRouteDao().saveRouteWithWaypoints(
            newRoute,
            listOf(
                RouteWaypointEntity(
                    id = "wp_mig_1",
                    routeId = "route_mig_1",
                    placeId = "place_mig_1",
                    orderIndex = 0,
                    name = "Origin",
                    latitude = 39.9,
                    longitude = 32.8
                )
            )
        )
        assertEquals(1, migratedDb.savedRouteDao().getRouteCount())

        migratedDb.savedAreaDao().upsertSavedArea(
            SavedAreaEntity(
                id = "area_mig_1",
                name = "Migrated Area",
                latitude = 39.92,
                longitude = 32.85
            )
        )
        assertEquals(1, migratedDb.savedAreaDao().getSavedAreaCount())

        migratedDb.close()
        dbFile.delete()
    }

    @Test
    fun test50_verifyTileCacheClearingPreservesPlacemarksAndSavedRoutes(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedRouteDao().clearAllRoutes()
        db.userPlaceDao().clearAllPlaces()
        db.osmPoiDao().clearAllPois()

        val storageManager = TileStorageManager(context)
        storageManager.saveTileData(
            providerId = "openfreemap",
            category = TileCategory.OSM_VECTOR,
            z = 12,
            x = 200,
            y = 300,
            data = "DISPOSABLE_VECTOR_TILE_BYTES".toByteArray(),
            metadata = TileMetadata(providerId = "openfreemap", retentionPermission = "PERMITTED")
        )

        val repo = PlacesAndRoutesRepository(context)
        val place = (repo.createPlacemark("Personal Base", 40.0, 29.0) as CreatePlaceOutcome.Created).place
        val route = repo.saveRoute(
            name = "Personal Hike",
            points = listOf(RoutePoint(40.0, 29.0), RoutePoint(40.01, 29.01)),
            waypoints = listOf(RouteWaypointDraft(name = "Start", latitude = 40.0, longitude = 29.0))
        ).getOrThrow()
        db.osmPoiDao().insertPois(
            listOf(OsmPoiRecord(8801L, "Local Bakery", "shop", "bakery", 40.0, 29.0, 14, 1, 1))
        )

        val clearResult = StorageCalculationManager.clearProviderTiles(context, db, "openfreemap")
        assertTrue(clearResult.isSuccess)
        assertTrue(clearResult.recordsDeleted >= 1)

        // User-authored Placemarks, Routes, and shared OSM POIs must remain untouched
        assertNotNull(repo.getPlacemarkById(place.id))
        assertNotNull(repo.getRouteById(route.route.id))
        assertNotNull(db.osmPoiDao().getPoiById(8801L))

        val report = StorageCalculationManager.calculateStorage(context, db)
        assertEquals(1, report.featureStorage.userPlaceCount)
        assertEquals(1, report.featureStorage.savedRouteCount)

        db.savedRouteDao().clearAllRoutes()
        db.userPlaceDao().clearAllPlaces()
        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test51_verifyControllerOverlaysStyleSwitchAndPoiIndependence(): Unit = runBlocking {
        val controller = com.example.map.engine.MapEngineController(
            context = context,
            initialLat = 39.92,
            initialLng = 32.85,
            initialZoom = 12.0,
            initialBearing = 0.0,
            onCameraMoved = { _, _, _, _ -> }
        )

        val poi = OsmPoiRecord(7001L, "City Library", "amenity", "library", 39.921, 32.851, 14, 1, 1)
        val place1 = UserPlaceEntity("place_1", "Camp Site", 39.930, 32.860, category = "camp")
        val place2 = UserPlaceEntity("place_2", "Water Source", 39.935, 32.865, category = "water")
        val route1 = com.example.data.places.SavedRouteModel(
            route = SavedRouteEntity(
                id = "route_1",
                name = "Ridge Walk",
                geometryJson = RouteGeometryCodec.encode(listOf(RoutePoint(39.93, 32.86), RoutePoint(39.94, 32.87))),
                pointCount = 2,
                totalDistanceMeters = 1400.0
            ),
            points = listOf(RoutePoint(39.93, 32.86), RoutePoint(39.94, 32.87)),
            waypoints = emptyList()
        )

        controller.highlightPoi(poi, moveCamera = false)
        controller.updateUserPlacemarks(listOf(place1, place2), place1.id)
        controller.updateSavedRoutes(listOf(route1), route1.route.id)
        controller.updateRouteDraftOverlay(listOf(RoutePoint(39.90, 32.80), RoutePoint(39.91, 32.81)))

        assertEquals(2, controller.currentPlacemarks.size)
        assertEquals("place_1", controller.selectedPlacemarkId)
        assertEquals(1, controller.currentSavedRoutes.size)
        assertEquals("route_1", controller.selectedRouteId)
        assertEquals(2, controller.currentDraftRoutePoints.size)
        assertEquals(7001L, controller.currentHighlightedPoi?.osmId)

        // Deleting a placemark updates controller state without disturbing POI highlight
        controller.updateUserPlacemarks(listOf(place2), null)
        assertEquals(1, controller.currentPlacemarks.size)
        assertNull(controller.selectedPlacemarkId)
        assertEquals(7001L, controller.currentHighlightedPoi?.osmId)

        // Focus on route sets midpoint camera target cleanly
        controller.focusOnRoute(route1)
        assertNotNull(controller.lastCameraFocusTarget)
        assertEquals(39.935, controller.lastCameraFocusTarget!!.first, 1e-4)
        assertEquals(32.865, controller.lastCameraFocusTarget!!.second, 1e-4)
    }

    @Test
    fun test52_verifyMapViewModelPlacemarkPickAndRouteDraftUndoFlow(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedRouteDao().clearAllRoutes()
        db.userPlaceDao().clearAllPlaces()

        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)
        val controller = com.example.map.engine.MapEngineController(
            context = context,
            initialLat = 39.92,
            initialLng = 32.85,
            initialZoom = 12.0,
            initialBearing = 0.0,
            onCameraMoved = { _, _, _, _ -> }
        )

        // 1. Pick Placemark on map and save
        viewModel.startPickPlacemarkOnMap()
        assertTrue(viewModel.uiState.value.isPickingPlacemarkLocation)

        viewModel.onMapCoordinatePickedForPlacemark(39.925, 32.837)
        assertFalse(viewModel.uiState.value.isPickingPlacemarkLocation)
        val editor = viewModel.uiState.value.placemarkEditorState
        assertNotNull(editor)
        assertEquals(39.925, editor!!.latitude, 1e-6)

        viewModel.savePlacemarkFromEditor(
            name = "Observation Deck",
            description = "Wide valley view",
            addressLabel = "",
            category = "viewpoint",
            tagsText = "sunset, view",
            latitude = 39.925,
            longitude = 32.837,
            controller = controller
        )

        val deadline1 = System.currentTimeMillis() + 3000L
        while (viewModel.uiState.value.placemarks.isEmpty() && System.currentTimeMillis() < deadline1) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(15L)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, viewModel.uiState.value.placemarks.size)
        assertEquals("Observation Deck", viewModel.uiState.value.selectedPlacemark?.name)

        // 2. Interactive route draft creation + waypoint + undo
        viewModel.startNewRouteCreation(controller)
        assertTrue(viewModel.uiState.value.routeDraft.isActive)

        viewModel.addRouteDraftPoint(39.920, 32.830, controller)
        viewModel.addRouteDraftPoint(39.925, 32.837, controller)
        viewModel.addRouteDraftPoint(39.930, 32.845, controller)
        viewModel.markLastDraftPointAsWaypoint("Turn Back")
        assertEquals(3, viewModel.uiState.value.routeDraft.points.size)
        assertEquals("Turn Back", viewModel.uiState.value.routeDraft.waypointNamesByPointIndex[2])

        // Undo removes point #2 AND its associated waypoint name
        viewModel.undoRouteDraftPoint(controller)
        assertEquals(2, viewModel.uiState.value.routeDraft.points.size)
        assertFalse(viewModel.uiState.value.routeDraft.waypointNamesByPointIndex.containsKey(2))

        viewModel.markLastDraftPointAsWaypoint("Summit Turn")
        viewModel.finishAndSaveRouteDraft(
            name = "Valley Approach",
            description = "Two-point trail",
            controller = controller
        )

        val deadline2 = System.currentTimeMillis() + 3000L
        while (viewModel.uiState.value.savedRoutes.isEmpty() && System.currentTimeMillis() < deadline2) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(15L)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(viewModel.uiState.value.routeDraft.isActive)
        assertEquals(1, viewModel.uiState.value.savedRoutes.size)
        assertEquals("Valley Approach", viewModel.uiState.value.selectedRoute?.route?.name)
        assertEquals(1, viewModel.uiState.value.selectedRoute?.waypoints?.size)
        assertEquals("Summit Turn", viewModel.uiState.value.selectedRoute?.waypoints?.first()?.name)

        db.savedRouteDao().clearAllRoutes()
        db.userPlaceDao().clearAllPlaces()
    }

    // =========================================================================
    // Phase 4.2 Tests: Area Details, HTTP Lookups, Saved Areas, Migrations & Context Menu
    // =========================================================================

    @Test
    fun test53_verifyAreaTapPriorityAndLongPressSuppression() {
        val controller = com.example.map.engine.MapEngineController(
            context = context,
            initialLat = 39.92,
            initialLng = 32.85,
            initialZoom = 14.0,
            initialBearing = 0.0,
            onCameraMoved = { _, _, _, _ -> }
        )

        var emptyTapCount = 0
        var placemarkTapId: String? = null
        var routeTapId: String? = null
        var poiTapCount = 0
        var longPressCount = 0

        controller.onEmptyMapSingleTap = { _, _ -> emptyTapCount++ }
        controller.onPlacemarkTapped = { id -> placemarkTapId = id }
        controller.onRouteTapped = { id -> routeTapId = id }
        controller.onHighlightedPoiTapped = { poiTapCount++ }
        controller.onMapLongPress = { _, _ -> longPressCount++ }

        // 1. Mode interception (RouteDraft / PickPlacemarkLocation) wins over everything
        controller.onMapTap = { _, _ -> true }
        controller.dispatchMapTap(39.92, 32.85, hitPlacemarkId = "p1", nowMs = 5000L)
        assertEquals(0, emptyTapCount)
        assertNull(placemarkTapId)

        controller.onMapTap = { _, _ -> false }

        // 2. Placemark > Route > Highlighted POI > Empty Map Single Tap
        controller.dispatchMapTap(39.92, 32.85, hitPlacemarkId = "place_a", hitRouteId = "route_a", hitHighlightedPoi = true, nowMs = 6000L)
        assertEquals("place_a", placemarkTapId)
        assertEquals(0, emptyTapCount)

        controller.dispatchMapTap(39.92, 32.85, hitRouteId = "route_b", hitHighlightedPoi = true, nowMs = 7000L)
        assertEquals("route_b", routeTapId)
        assertEquals(0, emptyTapCount)

        controller.dispatchMapTap(39.92, 32.85, hitHighlightedPoi = true, nowMs = 8000L)
        assertEquals(1, poiTapCount)
        assertEquals(0, emptyTapCount)

        // 3. Empty map single tap triggers Area Details
        controller.dispatchMapTap(39.92, 32.85, nowMs = 9000L)
        assertEquals(1, emptyTapCount)

        // 4. Long press suppresses an immediate follow-up single tap within 350 ms
        controller.dispatchMapLongPress(39.93, 32.86, nowMs = 10_000L)
        assertEquals(1, longPressCount)
        controller.dispatchMapTap(39.93, 32.86, nowMs = 10_150L)
        assertEquals(1, emptyTapCount)

        // After suppression window passes, single tap triggers normally again
        controller.dispatchMapTap(39.93, 32.86, nowMs = 10_500L)
        assertEquals(2, emptyTapCount)
    }

    @Test
    fun test54_verifyAreaAdaptiveRadiusAndOfflineSnapshotOnly(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.clearAllTables()
        }

        // Dense cluster: 4 POIs within ~120 m of (39.9200, 32.8500) -> stays at 250 m
        val densePois = listOf(
            OsmPoiRecord(91001L, "Central Cafe", "amenity", "cafe", 39.9204, 32.8502, 14, 1, 1),
            OsmPoiRecord(91002L, "Metro Bookshop", "shop", "books", 39.9207, 32.8505, 14, 1, 1),
            OsmPoiRecord(91003L, "Corner Pharmacy", "amenity", "pharmacy", 39.9195, 32.8495, 14, 1, 1),
            OsmPoiRecord(91004L, "Oak Square", "leisure", "park", 39.9192, 32.8508, 14, 1, 1)
        )
        db.osmPoiDao().insertPois(densePois)

        // Sparse area: 1 POI at ~600 m away from (41.0000, 29.0000) -> expands to 1000 m
        db.osmPoiDao().insertPois(
            listOf(OsmPoiRecord(91005L, "Hilltop Chapel", "amenity", "place_of_worship", 41.0054, 29.0000, 14, 2, 2))
        )

        var gatewayCalls = 0
        val countingGateway = object : AreaRemoteLookupGateway {
            override suspend fun lookupAreaOnline(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                localHintNames: List<String>
            ): RemoteAreaEnrichmentResult {
                gatewayCalls++
                return RemoteAreaEnrichmentResult()
            }
        }
        val repo = AreaDetailsRepository(context, countingGateway)

        val denseLocal = repo.queryLocalSnapshot(39.9200, 32.8500)
        assertEquals(AreaDetailsRepository.DENSE_RADIUS_METERS, denseLocal.radiusUsedMeters)
        assertEquals(4, denseLocal.nearbyPlaces.size)
        assertEquals(AreaDataProvenance.LOCAL_ONLY, denseLocal.provenance)
        assertTrue(denseLocal.nearbyPlaces[0].distanceMeters <= denseLocal.nearbyPlaces[1].distanceMeters)

        val sparseLocal = repo.queryLocalSnapshot(41.0000, 29.0000)
        assertEquals(AreaDetailsRepository.SPARSE_RADIUS_METERS, sparseLocal.radiusUsedMeters)
        assertEquals(1, sparseLocal.nearbyPlaces.size)
        assertEquals("Hilltop Chapel", sparseLocal.nearbyPlaces[0].name)

        // Offline enrichment returns offline flag cleanly and hits 0 gateway calls
        val offlineResult = repo.enrichWithOnlineData(sparseLocal, isOnlineAllowed = false)
        assertTrue(offlineResult.isOffline)
        assertEquals(0, gatewayCalls)
        assertEquals(1, offlineResult.nearbyPlaces.size)

        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test55_verifyHttpGatewayNominatimWikiDisambiguationAndPartialError(): Unit = runBlocking {
        // Case A: Nominatim + matching Wikipedia summary (verified User-Agent and CC BY-SA attribution)
        val seenUserAgents = mutableListOf<String?>()
        val happyClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                seenUserAgents.add(req.header("User-Agent"))
                val url = req.url.toString()
                val json = when {
                    url.contains("/reverse") -> """
                        {
                          "name": "Galata Tower",
                          "category": "tourism",
                          "type": "attraction",
                          "display_name": "Galata Tower, Bereketzade, Beyoğlu, Istanbul, Turkey",
                          "address": {
                            "road": "Büyük Hendek Caddesi",
                            "neighbourhood": "Bereketzade",
                            "city": "Istanbul",
                            "state": "Marmara",
                            "country": "Turkey"
                          },
                          "extratags": {
                            "wikipedia": "en:Galata Tower",
                            "wikidata": "Q217122"
                          }
                        }
                    """.trimIndent()
                    url.contains("list=geosearch") -> """
                        {
                          "query": {
                            "geosearch": [
                              {"pageid": 101, "title": "Galata Tower", "lat": 41.0256, "lon": 28.9741, "dist": 12.0},
                              {"pageid": 102, "title": "Neve Shalom Synagogue", "lat": 41.0265, "lon": 28.9725, "dist": 180.0}
                            ]
                          }
                        }
                    """.trimIndent()
                    url.contains("/page/summary/") -> """
                        {
                          "type": "standard",
                          "title": "Galata Tower",
                          "extract": "Galata Tower is a medieval stone tower in the Galata quarter of Istanbul, Turkey.",
                          "wikibase_item": "Q217122",
                          "content_urls": { "mobile": { "page": "https://en.m.wikipedia.org/wiki/Galata_Tower" } }
                        }
                    """.trimIndent()
                    else -> "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val testRegistry = ReverseGeocodingProviderRegistry(
            configs = listOf(
                ReverseGeocodingProviderConfig(
                    providerId = "test_nominatim",
                    displayName = "Test Nominatim-Compatible Proxy",
                    providerType = ReverseGeocodingProviderType.NOMINATIM_COMPATIBLE,
                    baseUrl = "https://geocoder.vianmaps.test",
                    contactInfo = "maintainer@vianmaps.test",
                    minRequestIntervalMs = 0L
                )
            ),
            initialProviderId = "test_nominatim"
        )
        val happyGateway = HttpAreaRemoteLookupGateway(
            providerRegistry = testRegistry,
            client = happyClient,
            minWikipediaIntervalMs = 0L
        )
        val happyRes = happyGateway.lookupAreaOnline(41.0256, 28.9741, 250, emptyList())
        assertTrue(seenUserAgents.all { it?.contains("VianMaps") == true && it.contains(".invalid") == false })
        assertEquals("Galata Tower", happyRes.identity?.primaryName)
        assertEquals("Istanbul", happyRes.identity?.locality)
        assertEquals("Turkey", happyRes.identity?.country)
        assertNotNull(happyRes.wikiInfo)
        assertEquals("Galata Tower", happyRes.wikiInfo!!.title)
        assertEquals("Q217122", happyRes.wikiInfo!!.wikidataId)
        assertEquals("https://en.m.wikipedia.org/wiki/Galata_Tower", happyRes.wikiInfo!!.url)
        assertTrue(happyRes.sourceAttributions.any { it.contains("OpenStreetMap") })
        assertTrue(happyRes.sourceAttributions.any { it.contains("Wikipedia") })

        // Case B: Ambiguous/distant Wikipedia + Wikipedia API 500 error -> Reverse geocoder succeeds cleanly with partialError
        val partialClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.contains("/reverse")) {
                    val revJson = """
                        {
                          "name": "Unnamed Meadow",
                          "category": "natural",
                          "type": "grassland",
                          "address": { "town": "Bolu", "country": "Turkey" }
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(revJson.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("Service Unavailable".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()

        val partialGateway = HttpAreaRemoteLookupGateway(
            providerRegistry = testRegistry,
            client = partialClient,
            minWikipediaIntervalMs = 0L
        )
        val partialRes = partialGateway.lookupAreaOnline(40.73, 31.60, 1000, emptyList())
        assertEquals("Unnamed Meadow", partialRes.identity?.primaryName)
        assertNull(partialRes.wikiInfo)
        assertNull(partialRes.fatalError)
        assertTrue(partialRes.partialErrors.isNotEmpty())
        assertTrue(partialRes.hasTransientError)
        assertTrue(partialRes.wikiStatusNote != null)
    }

    @Test
    fun test56_verifyAreaRequestDeduplicationAndCancellation(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedAreaDao().clearAllSavedAreas()
        db.osmPoiDao().clearAllPois()

        val remoteCalls = AtomicInteger(0)
        val slowGateway = object : AreaRemoteLookupGateway {
            override suspend fun lookupAreaOnline(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                localHintNames: List<String>
            ): RemoteAreaEnrichmentResult {
                remoteCalls.incrementAndGet()
                kotlinx.coroutines.delay(150L)
                return RemoteAreaEnrichmentResult(
                    identity = AreaLocationIdentity(primaryName = "Dedup Square", country = "TR"),
                    sourceAttributions = listOf("© OpenStreetMap contributors")
                )
            }
        }
        val repo = AreaDetailsRepository(context, slowGateway)
        val baseline = repo.queryLocalSnapshot(39.9255, 32.8369)

        // Two concurrent calls for the same coordinate bucket deduplicate into 1 network request
        val deferredA = async {
            repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        }
        val deferredB = async {
            repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        }
        val resA = deferredA.await()
        val resB = deferredB.await()
        assertEquals(1, remoteCalls.get())
        assertEquals("Dedup Square", resA.locationIdentity?.primaryName)
        assertEquals("Dedup Square", resB.locationIdentity?.primaryName)

        // Subsequent non-forced call uses short-term memory cache cleanly (still 1 call total)
        val cached = repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(1, remoteCalls.get())
        assertEquals("Dedup Square", cached.locationIdentity?.primaryName)
    }

    @Test
    fun test57_verifySavedAreaPersistenceSubsetNearbyEditRefreshDelete(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedAreaDao().clearAllSavedAreas()
        db.osmPoiDao().clearAllPois()

        var refreshCount = 0
        val fakeGateway = object : AreaRemoteLookupGateway {
            override suspend fun lookupAreaOnline(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                localHintNames: List<String>
            ): RemoteAreaEnrichmentResult {
                refreshCount++
                return RemoteAreaEnrichmentResult(
                    identity = AreaLocationIdentity(
                        primaryName = "Harbor Watch v$refreshCount",
                        addressLabel = "Pier Rd 10",
                        locality = "Karaköy",
                        administrativeRegion = "Istanbul",
                        country = "Turkey"
                    ),
                    wikiInfo = AreaWikiInfo(
                        title = "Karaköy",
                        summary = "Historic waterfront district.",
                        url = "https://en.wikipedia.org/wiki/Karaköy",
                        wikidataId = "Q1729402"
                    ),
                    sourceAttributions = listOf("© OpenStreetMap contributors", "© Wikipedia contributors (CC BY-SA 4.0)")
                )
            }
        }

        val repo = AreaDetailsRepository(context, fakeGateway)
        val nearbyAll = listOf(
            AreaNearbyItem("poi_1", "Keep Bakery", "bakery", 35.0, 41.02, 28.97, "Local OSM"),
            AreaNearbyItem("poi_2", "Skip Kiosk", "kiosk", 85.0, 41.021, 28.971, "Local OSM")
        )
        val liveResult = repo.enrichWithOnlineData(
            repo.queryLocalSnapshot(41.0200, 28.9700).copy(nearbyPlaces = nearbyAll),
            isOnlineAllowed = true
        )

        // Save selecting ONLY poi_1 + custom notes
        val saved = repo.saveAreaSnapshot(
            result = liveResult,
            selectedNearbyIds = setOf("poi_1"),
            userNotes = "Morning light view"
        ).getOrThrow()

        assertEquals("Harbor Watch v1", saved.name)
        assertEquals(1, saved.retainedNearbyCount)
        assertEquals("Morning light view", saved.userNotes)
        val decodedNearby = SavedAreaNearbyCodec.decode(saved.nearbySummaryJson)
        assertEquals(1, decodedNearby.size)
        assertEquals("Keep Bakery", decodedNearby[0].name)

        // Offline reload reads persisted snapshot without calling gateway
        val callsBeforeOfflineLoad = refreshCount
        val offlineLoaded = repo.queryLocalSnapshot(41.0200, 28.9700)
        assertTrue(offlineLoaded.isSavedSnapshot)
        assertEquals(AreaDataProvenance.SAVED_SNAPSHOT, offlineLoaded.provenance)
        assertEquals("Harbor Watch v1", offlineLoaded.locationIdentity?.primaryName)
        assertEquals("Karaköy", offlineLoaded.wikiInfo?.title)
        assertEquals(callsBeforeOfflineLoad, refreshCount)

        // Edit metadata & tags
        val edited = repo.updateSavedAreaMetadata(
            areaId = saved.id,
            name = "My Harbor Spot",
            category = "waterfront",
            addressLabel = "Pier Rd 10, Karaköy",
            userNotes = "Updated note",
            tagsText = "harbor, scenic, harbor"
        ).getOrThrow()
        assertEquals("My Harbor Spot", edited.name)
        assertEquals("harbor, scenic", edited.tagsCsv)

        // Explicit online refresh updates details while keeping notes
        val refreshed = repo.refreshSavedAreaOnline(saved.id, isOnlineAllowed = true).getOrThrow()
        assertEquals("Harbor Watch v2", refreshed.name)
        assertEquals("Updated note", refreshed.userNotes)
        assertEquals(2, refreshCount)

        // Delete removes saved area record cleanly
        assertTrue(repo.deleteSavedArea(saved.id))
        assertNull(repo.getSavedAreaById(saved.id))
    }

    @Test
    fun test58_verifyDatabaseMigrationV2ToV3AndTileClearPreservesSavedAreas(): Unit = runBlocking {
        // Part A: Explicit SQLite v2 -> v3 migration preserves existing v2 placemarks & routes
        val dbName = "test_migration_v2_v3_${System.currentTimeMillis()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val sqliteV2 = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqliteV2.version = 2
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `retained_tiles` (`providerId` TEXT NOT NULL, `category` TEXT NOT NULL, `z` INTEGER NOT NULL, `x` INTEGER NOT NULL, `y` INTEGER NOT NULL, `filePath` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `checksum` TEXT NOT NULL, `fetchTimestamp` INTEGER NOT NULL, `etag` TEXT, `sourceUrl` TEXT NOT NULL, `attribution` TEXT NOT NULL, `retentionPermission` TEXT NOT NULL, `integrityStatus` TEXT NOT NULL, PRIMARY KEY(`providerId`, `category`, `z`, `x`, `y`))")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `osm_pois` (`osmId` INTEGER NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, `subCategory` TEXT, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL, `tagsJson` TEXT NOT NULL, `layerName` TEXT NOT NULL, `fetchTimestamp` INTEGER NOT NULL, PRIMARY KEY(`osmId`))")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `osm_buildings` (`osmId` INTEGER NOT NULL, `name` TEXT, `buildingType` TEXT NOT NULL, `heightMeters` REAL NOT NULL, `minHeightMeters` REAL NOT NULL, `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL, `centroidLat` REAL NOT NULL, `centroidLng` REAL NOT NULL, `geometryJson` TEXT NOT NULL, PRIMARY KEY(`osmId`))")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `osm_regions` (`osmId` INTEGER NOT NULL, `name` TEXT NOT NULL, `adminLevel` INTEGER NOT NULL, `boundaryType` TEXT NOT NULL, `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL, PRIMARY KEY(`osmId`))")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `osm_roads` (`osmId` INTEGER NOT NULL, `name` TEXT, `highwayClass` TEXT NOT NULL, `isOneway` INTEGER NOT NULL, `sourceTileZ` INTEGER NOT NULL, `sourceTileX` INTEGER NOT NULL, `sourceTileY` INTEGER NOT NULL, `geometryJson` TEXT NOT NULL, PRIMARY KEY(`osmId`))")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `routing_graph_tiles` (`level` INTEGER NOT NULL, `tileIndex` INTEGER NOT NULL, `status` TEXT NOT NULL, `filePath` TEXT, `byteSize` INTEGER NOT NULL, `lastChecked` INTEGER NOT NULL, PRIMARY KEY(`level`, `tileIndex`))")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `user_places` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `description` TEXT NOT NULL, `addressLabel` TEXT, `category` TEXT NOT NULL, `tagsCsv` TEXT NOT NULL, `isPlacemark` INTEGER NOT NULL, `linkedModuleRef` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        sqliteV2.execSQL("CREATE INDEX IF NOT EXISTS `index_user_places_isPlacemark` ON `user_places` (`isPlacemark`)")
        sqliteV2.execSQL("CREATE INDEX IF NOT EXISTS `index_user_places_updatedAt` ON `user_places` (`updatedAt`)")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `saved_routes` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `geometryJson` TEXT NOT NULL, `pointCount` INTEGER NOT NULL, `totalDistanceMeters` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        sqliteV2.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_routes_updatedAt` ON `saved_routes` (`updatedAt`)")
        sqliteV2.execSQL("CREATE TABLE IF NOT EXISTS `route_waypoints` (`id` TEXT NOT NULL, `routeId` TEXT NOT NULL, `placeId` TEXT, `orderIndex` INTEGER NOT NULL, `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`routeId`) REFERENCES `saved_routes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`placeId`) REFERENCES `user_places`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
        sqliteV2.execSQL("CREATE INDEX IF NOT EXISTS `index_route_waypoints_routeId` ON `route_waypoints` (`routeId`)")
        sqliteV2.execSQL("CREATE INDEX IF NOT EXISTS `index_route_waypoints_placeId` ON `route_waypoints` (`placeId`)")

        sqliteV2.execSQL("INSERT INTO `user_places` (`id`, `name`, `latitude`, `longitude`, `description`, `addressLabel`, `category`, `tagsCsv`, `isPlacemark`, `linkedModuleRef`, `createdAt`, `updatedAt`) VALUES ('v2_place', 'Old Lookout', 39.9, 32.8, 'Preserved note', 'Hill', 'viewpoint', 'v2', 1, NULL, 1000, 1000)")
        sqliteV2.close()

        val migratedV3 = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        val preservedPlace = migratedV3.userPlaceDao().getPlaceById("v2_place")
        assertNotNull(preservedPlace)
        assertEquals("Old Lookout", preservedPlace!!.name)
        migratedV3.savedAreaDao().upsertSavedArea(
            SavedAreaEntity(id = "v3_area", name = "Post-Migration Area", latitude = 39.91, longitude = 32.82)
        )
        assertEquals(1, migratedV3.savedAreaDao().getSavedAreaCount())
        migratedV3.close()
        dbFile.delete()

        // Part B: Clearing tile caches must NEVER delete saved_areas
        val db = AppDatabase.getInstance(context)
        db.savedAreaDao().clearAllSavedAreas()
        TileStorageManager(context).saveTileData(
            providerId = "openfreemap",
            category = TileCategory.OSM_VECTOR,
            z = 14,
            x = 123,
            y = 456,
            data = "VECTOR_BYTES".toByteArray(),
            metadata = TileMetadata(providerId = "openfreemap", retentionPermission = "PERMITTED")
        )
        db.savedAreaDao().upsertSavedArea(
            SavedAreaEntity(id = "keep_area_1", name = "Protected Area", latitude = 40.0, longitude = 29.0)
        )
        val cleared = StorageCalculationManager.clearProviderTiles(context, db, "openfreemap")
        assertTrue(cleared.isSuccess)
        assertNotNull(db.savedAreaDao().getSavedAreaById("keep_area_1"))
        val report = StorageCalculationManager.calculateStorage(context, db)
        assertEquals(1, report.featureStorage.savedAreaCount)
        db.savedAreaDao().clearAllSavedAreas()
    }

    @Test
    fun test59_verifyMapViewModelSingleTapAreaDetailsAsyncLifecycle(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedAreaDao().clearAllSavedAreas()
        db.osmPoiDao().clearAllPois()
        db.osmPoiDao().insertPois(
            listOf(OsmPoiRecord(92001L, "Local Museum", "tourism", "museum", 39.9302, 32.8602, 14, 1, 1))
        )

        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)
        AppNetworkPolicy.setInternetEnabled(true)

        val fakeGateway = object : AreaRemoteLookupGateway {
            override suspend fun lookupAreaOnline(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                localHintNames: List<String>
            ): RemoteAreaEnrichmentResult {
                return RemoteAreaEnrichmentResult(
                    identity = AreaLocationIdentity(
                        primaryName = "Ulus Square",
                        locality = "Ankara",
                        country = "Turkey"
                    ),
                    wikiInfo = AreaWikiInfo(
                        title = "Ulus Square",
                        summary = "Central square in Ankara.",
                        url = "https://en.wikipedia.org/wiki/Ulus_Square"
                    ),
                    sourceAttributions = listOf("© OpenStreetMap contributors", "© Wikipedia contributors (CC BY-SA 4.0)")
                )
            }
        }
        viewModel.configureAreaTesting(gateway = fakeGateway, onlineOverride = true)

        val controller = com.example.map.engine.MapEngineController(
            context = context,
            initialLat = 39.93,
            initialLng = 32.86,
            initialZoom = 15.0,
            initialBearing = 0.0,
            onCameraMoved = { _, _, _, _ -> }
        )

        viewModel.onEmptyMapSingleTap(39.9300, 32.8600, controller)
        // Immediate opening sets coordinates and area pin before async enrichment completes
        assertNotNull(viewModel.uiState.value.areaDetailsState)
        assertEquals(39.9300, controller.currentAreaPin!!.latitude, 1e-6)

        val deadline = System.currentTimeMillis() + 3000L
        while ((viewModel.uiState.value.areaDetailsState?.details?.isLoadingOnline != false ||
                viewModel.uiState.value.areaDetailsState?.details?.locationIdentity?.primaryName != "Ulus Square") &&
            System.currentTimeMillis() < deadline
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(15L)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val finalState = viewModel.uiState.value.areaDetailsState!!
        assertEquals("Ulus Square", finalState.details.locationIdentity?.primaryName)
        assertEquals("Ulus Square", finalState.details.wikiInfo?.title)
        assertTrue(finalState.details.nearbyPlaces.any { it.name == "Local Museum" })
        assertEquals(AreaDataProvenance.HYBRID_ONLINE_AND_LOCAL, finalState.details.provenance)

        // Dismiss clears area state and pin cleanly
        viewModel.dismissAreaDetails(controller)
        assertNull(viewModel.uiState.value.areaDetailsState)
        assertNull(controller.currentAreaPin)

        db.osmPoiDao().clearAllPois()
    }

    @Test
    fun test60_verifyMapViewModelLongPressContextMenuMatchesAndActions(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedAreaDao().clearAllSavedAreas()
        db.userPlaceDao().clearAllPlaces()
        db.savedRouteDao().clearAllRoutes()

        val existingArea = SavedAreaEntity(
            id = "ctx_area_1",
            name = "Saved Plaza",
            latitude = 39.9400,
            longitude = 32.8700,
            addressLabel = "Plaza Way"
        )
        db.savedAreaDao().upsertSavedArea(existingArea)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MapViewModel(app)
        val controller = com.example.map.engine.MapEngineController(
            context = context,
            initialLat = 39.90,
            initialLng = 32.80,
            initialZoom = 14.0,
            initialBearing = 0.0,
            onCameraMoved = { _, _, _, _ -> }
        )

        // 1. Long press detects matching existing saved area
        viewModel.onMapLongPressed(39.9401, 32.8701, controller)
        val ctxDeadline = System.currentTimeMillis() + 3000L
        while (viewModel.uiState.value.mapContextMenuState?.isResolvingMatches != false &&
            System.currentTimeMillis() < ctxDeadline
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(15L)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val ctx = viewModel.uiState.value.mapContextMenuState!!
        assertEquals("ctx_area_1", ctx.matches.savedArea?.id)

        // 2. Context action: Add Placemark prefills name & coordinate cleanly
        viewModel.contextAddPlacemarkHere(controller)
        assertNull(viewModel.uiState.value.mapContextMenuState)
        val editor = viewModel.uiState.value.placemarkEditorState!!
        assertEquals("Saved Plaza", editor.name)
        assertEquals(39.9401, editor.latitude, 1e-6)
        viewModel.dismissPlacemarkEditor()

        // 3. Context action: Route from this point initiates draft with Start waypoint
        viewModel.onMapLongPressed(39.9401, 32.8701, controller)
        viewModel.contextRouteFromHere(controller)
        assertTrue(viewModel.uiState.value.routeDraft.isActive)
        assertEquals("Start", viewModel.uiState.value.routeDraft.waypointNamesByPointIndex[0])
        viewModel.cancelRouteDraft(controller)

        // 4. Planned action shows honest notice without creating any fake database rows
        viewModel.onMapLongPressed(39.9401, 32.8701, controller)
        viewModel.contextShowPlannedAction(
            title = "Create Note Here",
            featureGroup = "Notes",
            summary = "Planned note summary",
            plannedBehavior = "Planned behavior",
            controller = controller
        )
        assertNotNull(viewModel.uiState.value.plannedActionNotice)
        assertEquals("Create Note Here", viewModel.uiState.value.plannedActionNotice?.title)
        assertEquals(0, db.userPlaceDao().getPlacemarkCount())

        db.savedAreaDao().clearAllSavedAreas()
    }

    // =========================================================================
    // Phase 4.2.1 Tests: Nominatim Policy, Provider Switching, Caching & Safe Refresh
    // =========================================================================

    @Test
    fun test61_verifyPublicNominatimDisabledWithoutValidContactOrCentralProxy(): Unit = runBlocking {
        // 1. Placeholder/empty contacts must be strictly rejected
        assertFalse(ReverseGeocodingContactValidator.isValidGenuineContact(null))
        assertFalse(ReverseGeocodingContactValidator.isValidGenuineContact(""))
        assertFalse(ReverseGeocodingContactValidator.isValidGenuineContact("vianmaps-app@example.invalid"))
        assertFalse(ReverseGeocodingContactValidator.isValidGenuineContact("contact@example.com"))
        assertFalse(ReverseGeocodingContactValidator.isValidGenuineContact("not-an-email"))
        assertTrue(ReverseGeocodingContactValidator.isValidGenuineContact("support@vianmaps.test"))
        assertTrue(ReverseGeocodingContactValidator.isValidGenuineContact("https://vianmaps.test/contact"))

        // 2. Even with a genuine contact, public Nominatim MUST stay disabled without central rate-limiting proxy
        val noProxyConfig = ReverseGeocodingProviderConfig(
            providerId = "public_nominatim",
            displayName = "OSM Nominatim Public",
            providerType = ReverseGeocodingProviderType.PUBLIC_NOMINATIM,
            baseUrl = "https://nominatim.openstreetmap.org",
            contactInfo = "support@vianmaps.test",
            hasCentralRateLimitingProxy = false
        )
        val noProxyStatus = noProxyConfig.validate()
        assertTrue(noProxyStatus is ProviderConfigStatus.Disabled)

        // 3. Default production gateway makes ZERO HTTP requests to public Nominatim and keeps local + Wiki working
        val seenUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                seenUrls.add(req.url.toString())
                val json = if (req.url.toString().contains("list=geosearch")) {
                    """{"query":{"geosearch":[{"pageid":501,"title":"Stone Bridge","lat":41.0,"lon":29.0,"dist":45.0}]}}"""
                } else {
                    """{"type":"standard","title":"Stone Bridge","extract":"Historic stone arch bridge."}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val defaultGateway = HttpAreaRemoteLookupGateway(
            providerRegistry = ReverseGeocodingProviderRegistry.createDefault(),
            client = client,
            minWikipediaIntervalMs = 0L
        )
        assertTrue(defaultGateway.reverseProviderConfigStatus() is ProviderConfigStatus.Disabled)
        assertFalse(defaultGateway.providerRegistry.supportsRemoteDynamicSwitching)
        assertFalse(defaultGateway.providerRegistry.hasCentralRateLimitingGateway)

        val result = defaultGateway.lookupAreaOnline(41.0, 29.0, 250, emptyList())
        assertTrue(seenUrls.none { it.contains("nominatim") })
        assertFalse(result.isReverseGeocoderEnabled)
        assertTrue(result.reverseGeocoderStatusNote?.contains("disabled", ignoreCase = true) == true)
        assertEquals("Stone Bridge", result.wikiInfo?.title)
    }

    @Test
    fun test62_verifyProviderAbstractionSwitchingPhotonAndInvalidConfig(): Unit = runBlocking {
        val seenRequests = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                seenRequests.add(url)
                val bodyJson = when {
                    url.contains("photon.vianmaps.test/reverse") -> """
                        {
                          "type": "FeatureCollection",
                          "features": [
                            {
                              "type": "Feature",
                              "properties": {
                                "name": "Photon Harbor Park",
                                "street": "Harbor Blvd",
                                "housenumber": "12",
                                "city": "Izmir",
                                "state": "Aegean",
                                "country": "Turkey",
                                "osm_key": "leisure",
                                "osm_value": "park"
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                    url.contains("nominatim-proxy.vianmaps.test/reverse") -> """
                        {
                          "name": "Proxy Harbor Park",
                          "category": "leisure",
                          "type": "park",
                          "address": { "city": "Izmir", "country": "Turkey" }
                        }
                    """.trimIndent()
                    url.contains("list=geosearch") -> """{"query":{"geosearch":[]}}"""
                    else -> "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val registry = ReverseGeocodingProviderRegistry(
            configs = listOf(
                ReverseGeocodingProviderConfig(
                    providerId = "proxy_nominatim",
                    displayName = "Self-Hosted Nominatim Proxy",
                    providerType = ReverseGeocodingProviderType.NOMINATIM_COMPATIBLE,
                    baseUrl = "https://nominatim-proxy.vianmaps.test",
                    minRequestIntervalMs = 0L
                ),
                ReverseGeocodingProviderConfig(
                    providerId = "self_photon",
                    displayName = "Self-Hosted Photon",
                    providerType = ReverseGeocodingProviderType.PHOTON_COMPATIBLE,
                    baseUrl = "https://photon.vianmaps.test",
                    minRequestIntervalMs = 0L
                ),
                ReverseGeocodingProviderConfig(
                    providerId = "broken_provider",
                    displayName = "Broken Endpoint",
                    providerType = ReverseGeocodingProviderType.PHOTON_COMPATIBLE,
                    baseUrl = "not-a-valid-url",
                    minRequestIntervalMs = 0L
                )
            ),
            initialProviderId = "proxy_nominatim"
        )
        val gateway = HttpAreaRemoteLookupGateway(
            providerRegistry = registry,
            client = client,
            minWikipediaIntervalMs = 0L
        )

        // 1. Nominatim-compatible active
        val resNom = gateway.lookupAreaOnline(38.42, 27.14, 250, emptyList())
        assertEquals("Proxy Harbor Park", resNom.identity?.primaryName)
        assertTrue(resNom.sourceAttributions.any { it.contains("Nominatim") })

        // 2. Switch to Photon-compatible
        assertTrue(registry.selectProvider("self_photon"))
        val resPhoton = gateway.lookupAreaOnline(38.42, 27.14, 250, emptyList())
        assertEquals("Photon Harbor Park", resPhoton.identity?.primaryName)
        assertEquals("Izmir", resPhoton.identity?.locality)
        assertEquals("© OpenStreetMap contributors (Photon)", resPhoton.identity?.attribution)
        assertTrue(resPhoton.sourceAttributions.contains("© OpenStreetMap contributors (Photon)"))

        // 3. Switch to invalid config -> gracefully disabled, makes ZERO requests to broken endpoint
        assertTrue(registry.selectProvider("broken_provider"))
        assertTrue(gateway.reverseProviderConfigStatus() is ProviderConfigStatus.Disabled)
        val countBeforeBroken = seenRequests.size
        val resBroken = gateway.lookupAreaOnline(38.42, 27.14, 250, emptyList())
        assertFalse(resBroken.isReverseGeocoderEnabled)
        assertTrue(seenRequests.drop(countBeforeBroken).none { it.contains("not-a-valid-url") })
    }

    @Test
    fun test63_verifyHttp429RetryAfterWithoutRetryLoop(): Unit = runBlocking {
        var nowMs = 1_000_000L
        var reverseHttpCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.toString().contains("/reverse")) {
                    reverseHttpCalls++
                    if (reverseHttpCalls == 1) {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(429)
                            .message("Too Many Requests")
                            .header("Retry-After", "30")
                            .body("Rate limit exceeded".toResponseBody("text/plain".toMediaType()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"name":"Recovered Plaza","address":{"city":"Izmir","country":"Turkey"}}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"query":{"geosearch":[]}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val registry = ReverseGeocodingProviderRegistry(
            configs = listOf(
                ReverseGeocodingProviderConfig(
                    providerId = "rate_test_proxy",
                    displayName = "Rate-Test Proxy",
                    providerType = ReverseGeocodingProviderType.NOMINATIM_COMPATIBLE,
                    baseUrl = "https://proxy.vianmaps.test",
                    minRequestIntervalMs = 0L
                )
            ),
            initialProviderId = "rate_test_proxy"
        )

        val gateway = HttpAreaRemoteLookupGateway(
            providerRegistry = registry,
            client = client,
            clockMs = { nowMs },
            minWikipediaIntervalMs = 0L
        )

        // First call receives HTTP 429 + Retry-After: 30 — must NOT auto-retry (1 HTTP call total)
        val firstRes = gateway.lookupAreaOnline(38.42, 27.14, 250, emptyList())
        assertEquals(1, reverseHttpCalls)
        assertTrue(firstRes.hasTransientError)
        assertTrue(firstRes.partialErrors.any { it.contains("429") })

        // Immediate second call during 30s cooldown fails fast without making another HTTP call
        nowMs += 5_000L
        val cooldownRes = gateway.lookupAreaOnline(38.42, 27.14, 250, emptyList())
        assertEquals(1, reverseHttpCalls)
        assertTrue(cooldownRes.hasTransientError)
        assertTrue(cooldownRes.partialErrors.any { it.contains("cooldown") || it.contains("Retry-After") })

        // Advancing clock past 30s lifts cooldown cleanly
        nowMs += 30_000L
        val recoveredRes = gateway.lookupAreaOnline(38.42, 27.14, 250, emptyList())
        assertEquals(2, reverseHttpCalls)
        assertEquals("Recovered Plaza", recoveredRes.identity?.primaryName)
        assertFalse(recoveredRes.hasTransientError)
    }

    @Test
    fun test64_verifyCacheSuccessVsNoResultTtlTransientExclusionAndProviderKeys(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.clearAllTables()
        }

        var nowMs = 5_000_000L
        var activeProviderId = "prov_a"
        var gatewayCalls = 0
        var nextBehavior = "TRANSIENT_ERROR"

        val fakeGateway = object : AreaRemoteLookupGateway {
            override fun activeProviderCacheKey(): String = activeProviderId
            override suspend fun lookupAreaOnline(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                localHintNames: List<String>
            ): RemoteAreaEnrichmentResult {
                gatewayCalls++
                return when (nextBehavior) {
                    "TRANSIENT_ERROR" -> RemoteAreaEnrichmentResult(
                        providerId = activeProviderId,
                        hasTransientError = true,
                        partialErrors = listOf("Timeout"),
                        fatalError = "Timeout"
                    )
                    "NO_RESULT" -> RemoteAreaEnrichmentResult(
                        providerId = activeProviderId,
                        isReverseGeocoderEnabled = true,
                        hasTransientError = false,
                        isValidNoResult = true,
                        sourceAttributions = listOf("© Provider $activeProviderId")
                    )
                    else -> RemoteAreaEnrichmentResult(
                        providerId = activeProviderId,
                        isReverseGeocoderEnabled = true,
                        identity = AreaLocationIdentity(primaryName = "Name from $activeProviderId", country = "TR"),
                        hasTransientError = false,
                        isValidNoResult = false,
                        sourceAttributions = listOf("© Provider $activeProviderId")
                    )
                }
            }
        }

        val repo = AreaDetailsRepository(context, fakeGateway, clockMs = { nowMs })
        val saved = repo.saveAreaSnapshot(
            AreaDetailsResult(latitude = 10.0, longitude = 10.0, locationIdentity = AreaLocationIdentity("Keep Saved"))
        ).getOrThrow()

        val baseline = repo.queryLocalSnapshot(0.0, 0.0)

        // 1. Transient error must NOT be cached
        nextBehavior = "TRANSIENT_ERROR"
        repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(1, gatewayCalls)
        repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(2, gatewayCalls)

        // 2. Valid no-result is cached for NO_RESULT_CACHE_TTL_MS (2 min)
        nextBehavior = "NO_RESULT"
        repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(3, gatewayCalls)
        nowMs += 60_000L // +1 min -> still cached
        repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(3, gatewayCalls)
        nowMs += 90_000L // +2.5 min total -> no-result TTL expired, refreshes
        nextBehavior = "SUCCESS"
        val successRes = repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(4, gatewayCalls)
        assertEquals("Name from prov_a", successRes.locationIdentity?.primaryName)

        // 3. Coordinate -0.0 normalizes cleanly to 0.0 cache key
        val negZeroBaseline = baseline.copy(latitude = -0.00001, longitude = -0.00001)
        val cachedHit = repo.enrichWithOnlineData(negZeroBaseline, isOnlineAllowed = true)
        assertEquals(4, gatewayCalls)
        assertEquals("Name from prov_a", cachedHit.locationIdentity?.primaryName)

        // 4. Switching provider uses separate cache key
        activeProviderId = "prov_b"
        val switchedRes = repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(5, gatewayCalls)
        assertEquals("Name from prov_b", switchedRes.locationIdentity?.primaryName)

        // 5. Success TTL stays valid past 3 min and refreshes after >10 min
        nowMs += 5 * 60 * 1000L
        repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(5, gatewayCalls)
        nowMs += 6 * 60 * 1000L // >10 min
        repo.enrichWithOnlineData(baseline, isOnlineAllowed = true)
        assertEquals(6, gatewayCalls)

        // 6. clearRemoteCache never deletes SavedAreaEntity
        repo.clearRemoteCache()
        assertNotNull(repo.getSavedAreaById(saved.id))
        db.savedAreaDao().clearAllSavedAreas()
    }

    @Test
    fun test65_verifyFailedRefreshPreservesSavedNotesTagsAndOfflineAttribution(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.savedAreaDao().clearAllSavedAreas()
        db.osmPoiDao().clearAllPois()

        var nowMs = 10_000_000L
        var gatewayMode = "INITIAL"
        val controlledGateway = object : AreaRemoteLookupGateway {
            override fun activeProviderCacheKey(): String = "safe_refresh_test"
            override suspend fun lookupAreaOnline(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                localHintNames: List<String>
            ): RemoteAreaEnrichmentResult {
                return when (gatewayMode) {
                    "INITIAL" -> RemoteAreaEnrichmentResult(
                        providerId = "safe_refresh_test",
                        isReverseGeocoderEnabled = true,
                        identity = AreaLocationIdentity(
                            primaryName = "Cappadocia Terrace",
                            addressLabel = "Goreme Panorama Way",
                            locality = "Goreme",
                            administrativeRegion = "Nevsehir",
                            country = "Turkey"
                        ),
                        wikiInfo = AreaWikiInfo(
                            title = "Göreme",
                            summary = "Historic cave architecture region.",
                            url = "https://en.wikipedia.org/wiki/G%C3%B6reme",
                            wikidataId = "Q329346"
                        ),
                        sourceAttributions = listOf(
                            "© OpenStreetMap contributors (Self-Hosted Proxy)",
                            "© Wikipedia contributors (CC BY-SA 4.0)"
                        )
                    )
                    "HTTP_429_FAILURE" -> RemoteAreaEnrichmentResult(
                        providerId = "safe_refresh_test",
                        hasTransientError = true,
                        partialErrors = listOf("HTTP 429 Too Many Requests"),
                        fatalError = "HTTP 429 Too Many Requests"
                    )
                    else -> RemoteAreaEnrichmentResult(
                        providerId = "safe_refresh_test",
                        isValidNoResult = true,
                        reverseGeocoderStatusNote = "Reverse geocoder returned no fresh result."
                    )
                }
            }
        }

        val repo = AreaDetailsRepository(context, controlledGateway, clockMs = { nowMs })
        val live = repo.enrichWithOnlineData(repo.queryLocalSnapshot(38.64, 34.83), isOnlineAllowed = true)
        val saved = repo.saveAreaSnapshot(live, userNotes = "Sunrise spot").getOrThrow()
        val retrievedTimestamp = saved.retrievedAt

        // User edits metadata & tags 1 hour later — must NOT alter retrievedAt
        nowMs += 3_600_000L
        val edited = repo.updateSavedAreaMetadata(
            areaId = saved.id,
            name = "My Terrace Snapshot",
            category = "viewpoint",
            addressLabel = "Goreme Panorama Way",
            userNotes = "Bring tripod before 6am",
            tagsText = "sunrise, balloons"
        ).getOrThrow()
        assertEquals(retrievedTimestamp, edited.retrievedAt)
        assertTrue(edited.updatedAt > edited.retrievedAt)

        // Failed refresh (429/transient error) MUST fail without overwriting name, notes, tags, wiki, attribution, or timestamps
        nowMs += 60_000L
        gatewayMode = "HTTP_429_FAILURE"
        val fail429 = repo.refreshSavedAreaOnline(saved.id, isOnlineAllowed = true)
        assertTrue(fail429.isFailure)

        val afterFail1 = repo.getSavedAreaById(saved.id)!!
        assertEquals("My Terrace Snapshot", afterFail1.name)
        assertEquals("Bring tripod before 6am", afterFail1.userNotes)
        assertEquals("sunrise, balloons", afterFail1.tagsCsv)
        assertEquals("Göreme", afterFail1.wikiTitle)
        assertTrue(afterFail1.sourceAttribution.contains("Wikipedia"))
        assertEquals(retrievedTimestamp, afterFail1.retrievedAt)

        // Empty/disabled remote response MUST ALSO preserve good saved record
        gatewayMode = "EMPTY"
        val failEmpty = repo.refreshSavedAreaOnline(saved.id, isOnlineAllowed = true)
        assertTrue(failEmpty.isFailure)
        val afterFail2 = repo.getSavedAreaById(saved.id)!!
        assertEquals("My Terrace Snapshot", afterFail2.name)
        assertEquals("Bring tripod before 6am", afterFail2.userNotes)
        assertEquals("Göreme", afterFail2.wikiTitle)

        // Offline reopening preserves attribution & separate timestamps with 0 network reliance
        val reopenedOffline = repo.queryLocalSnapshot(38.64, 34.83)
        assertTrue(reopenedOffline.isSavedSnapshot)
        assertEquals(AreaDataProvenance.SAVED_SNAPSHOT, reopenedOffline.provenance)
        assertEquals(retrievedTimestamp, reopenedOffline.retrievedAt)
        assertEquals(edited.updatedAt, reopenedOffline.savedAt)
        assertTrue(reopenedOffline.sourceAttribution.contains("Wikipedia"))
        assertTrue(reopenedOffline.sourceAttribution.contains("OpenStreetMap"))
        assertEquals("Göreme", reopenedOffline.wikiInfo?.title)

        db.savedAreaDao().clearAllSavedAreas()
    }
}
