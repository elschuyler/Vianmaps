package com.example

import com.example.map.source.MapSourceRepository
import com.example.network.AppNetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM Unit Tests for Vian Maps foundation logic.
 */
class ExampleUnitTest {

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun verifyAppNetworkPolicyEnforcementAndDiagnostics() {
    AppNetworkPolicy.setInternetEnabled(true)
    assertTrue(AppNetworkPolicy.isNetworkAllowed())

    AppNetworkPolicy.setInternetEnabled(false)
    assertFalse(AppNetworkPolicy.isNetworkAllowed())

    val diagnostics = AppNetworkPolicy.diagnosticsLog.value
    assertTrue(diagnostics.isNotEmpty())
    assertTrue(diagnostics.last().contains("OFFLINE"))
  }

  @Test
  fun verifyMapSourceRepositoryAttributionAndLicensing() {
    val defaultStyle = MapSourceRepository.defaultStyle
    assertNotNull(defaultStyle)
    assertEquals("openfreemap_liberty", defaultStyle.id)
    assertEquals("OpenFreeMap", defaultStyle.providerName)
    assertTrue(defaultStyle.attribution.contains("OpenFreeMap"))
    assertTrue(defaultStyle.attribution.contains("OpenMapTiles"))
    assertTrue(defaultStyle.attribution.contains("OpenStreetMap"))
    assertTrue(defaultStyle.licenseNotes.isNotEmpty())

    val osmStyle = MapSourceRepository.getStyleById("osm_raster")
    assertEquals("osm_raster", osmStyle.id)
    assertTrue(osmStyle.attribution.contains("OpenStreetMap"))

    val brightStyle = MapSourceRepository.getStyleById("openfreemap_bright")
    assertEquals("openfreemap_bright", brightStyle.id)
    assertTrue(brightStyle.attribution.contains("OpenFreeMap"))

    val demoStyle = MapSourceRepository.getStyleById("maplibre_demo")
    assertEquals("maplibre_demo", demoStyle.id)
    assertEquals("MapLibre", demoStyle.providerName)
    assertTrue(demoStyle.isTemporaryDevelopmentStyle)
    assertTrue(demoStyle.styleUri.contains("demotiles.maplibre.org"))

    val hybridStyle = MapSourceRepository.getStyleById("hybrid_satellite_osm")
    assertEquals("hybrid_satellite_osm", hybridStyle.id)
    assertTrue(hybridStyle.displayName.contains("Hybrid"))
    assertTrue(hybridStyle.attribution.contains("OpenStreetMap"))
  }

  @Test
  fun verifyTileCoordinateConversion() {
    // San Francisco (37.7749, -122.4194) at zoom 12
    val sfCoord = com.example.data.tile.TileCoord.fromLatLng(37.7749, -122.4194, 12)
    assertEquals(12, sfCoord.z)
    assertEquals(655, sfCoord.x)
    assertEquals(1583, sfCoord.y)
    assertEquals("12/655/1583", sfCoord.toString())
  }

  @Test
  fun verifyOsmFeatureDeduplication() {
    val repo = com.example.data.osm.OsmOfflineDataRepository()
    val tileA = com.example.data.tile.TileCoord(14, 2620, 6331)
    val tileB = com.example.data.tile.TileCoord(14, 2621, 6331)

    // Feature appears in both adjacent tiles due to MVT tile boundary buffer
    val featureTileA = com.example.data.osm.OsmPoiEntity(
        osmId = 1001L,
        name = "Central Station",
        category = "railway_station",
        latitude = 39.5,
        longitude = 31.8,
        sourceTile = tileA
    )
    val featureTileB = com.example.data.osm.OsmPoiEntity(
        osmId = 1001L,
        name = "Central Station",
        category = "railway_station",
        latitude = 39.5,
        longitude = 31.8,
        sourceTile = tileB
    )

    val addedA = repo.savePoiFeatures(listOf(featureTileA))
    assertEquals(1, addedA)
    assertEquals(1, repo.getPoiCount())

    // Second ingestion with same OSM ID should be skipped (deduplicated)
    val addedB = repo.savePoiFeatures(listOf(featureTileB))
    assertEquals(0, addedB)
    assertEquals(1, repo.getPoiCount())

    val searchResults = repo.searchPois("Station")
    assertEquals(1, searchResults.size)
    assertEquals("Central Station", searchResults.first().name)
  }

  @Test
  fun verifyRoutingGraphHierarchyAndCoverage() {
    val index = com.example.data.routing.RoutingGraphCoverageIndex()
    val tiles = com.example.data.routing.RoutingGraphCoverageIndex.getRequiredGraphTiles(39.5, 31.8)
    assertEquals(3, tiles.size)

    val l0 = tiles[0]
    val l1 = tiles[1]
    val l2 = tiles[2]
    assertEquals(0, l0.level)
    assertEquals(1, l1.level)
    assertEquals(2, l2.level)

    assertFalse(index.isCompleteGraphAvailable(39.5, 31.8))
    index.markTileAvailable(l0, 1024L)
    index.markTileAvailable(l1, 2048L)
    assertFalse(index.isCompleteGraphAvailable(39.5, 31.8))
    index.markTileAvailable(l2, 4096L)
    assertTrue(index.isCompleteGraphAvailable(39.5, 31.8))
    assertEquals(3, index.getRetainedGraphTileCount())
  }

  @Test
  fun verifyMapEngineStatusModel() {
    val statuses = com.example.map.engine.MapEngineStatus.values()
    assertEquals(8, statuses.size)
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.INITIALIZING))
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.WAITING_FOR_MAP_VIEW))
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.LOADING_STYLE))
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.STYLE_READY))
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.RENDERING))
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.FULLY_RENDERED))
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.FAILED))
    assertTrue(statuses.contains(com.example.map.engine.MapEngineStatus.RETRYING))
  }

  @Test
  fun verifyDiagnosticRepositoryRingBufferCapacityAndRotation() {
    val repo = com.example.diagnostics.DiagnosticRepository
    repo.clear()
    assertEquals(0, repo.getSnapshot().size)

    // Add 260 entries (buffer capacity is 250)
    for (i in 1..260) {
      repo.record(com.example.diagnostics.DiagnosticRepository.Severity.INFO, "TestComponent", "Message #$i")
    }

    val snapshot = repo.getSnapshot()
    assertEquals(250, snapshot.size)
    // Oldest 10 entries (1..10) dropped, newest (260) must be present
    assertTrue(snapshot.any { it.description == "Message #260" })
    assertFalse(snapshot.any { it.description == "Message #1" })
  }

  @Test
  fun verifyDiagnosticRepositorySeverityFiltering() {
    val repo = com.example.diagnostics.DiagnosticRepository
    repo.clear()

    repo.record(com.example.diagnostics.DiagnosticRepository.Severity.INFO, "CompA", "Info message")
    repo.record(com.example.diagnostics.DiagnosticRepository.Severity.WARNING, "CompB", "Warning message")
    repo.record(com.example.diagnostics.DiagnosticRepository.Severity.ERROR, "CompC", "Error message")

    val all = repo.getFiltered(null as com.example.diagnostics.DiagnosticRepository.Severity?)
    assertEquals(3, all.size)

    val errs = repo.getFiltered(com.example.diagnostics.DiagnosticRepository.Severity.ERROR)
    assertEquals(1, errs.size)
    assertEquals("Error message", errs.first().description)

    val warns = repo.getFiltered(com.example.diagnostics.DiagnosticRepository.Severity.WARNING)
    assertEquals(1, warns.size)
    assertEquals("Warning message", warns.first().description)
  }

  @Test
  fun verifyDiagnosticRepositorySanitization() {
    val repo = com.example.diagnostics.DiagnosticRepository
    repo.clear()

    // Test secret / API key / token sanitization
    repo.record(
        com.example.diagnostics.DiagnosticRepository.Severity.INFO,
        "Network",
        "Request failed with key=AIzaSyA1234567890abcdef and token=secret_token_abc"
    )

    val lastEntry = repo.getSnapshot().last()
    assertFalse(lastEntry.description.contains("AIzaSy"))
    assertFalse(lastEntry.description.contains("secret_token_abc"))
    assertTrue(lastEntry.description.contains("[REDACTED]"))

    // Test coordinate sanitization
    val sanitizedCoord = repo.sanitize("User location at 37.7749295, -122.4194155 precision")
    assertFalse(sanitizedCoord.contains("37.7749295"))
    assertTrue(sanitizedCoord.contains("[LAT/LNG_REDACTED]"))
  }

  @Test
  fun verifyDiagnosticRepositoryExportReport() {
    val repo = com.example.diagnostics.DiagnosticRepository
    repo.clear()

    repo.record(com.example.diagnostics.DiagnosticRepository.Severity.INFO, "Engine", "MapLibre initialized")
    repo.record(com.example.diagnostics.DiagnosticRepository.Severity.ERROR, "Network", "Connection timed out")

    val report = repo.exportDiagnosticReport(
        currentStage = "Style Loaded",
        lastError = null,
        isDeviceOnline = true,
        connectionType = "Wi-Fi"
    )

    assertTrue(report.contains("VIAN MAPS — SYSTEM DIAGNOSTIC REPORT"))
    assertTrue(report.contains("Engine Stage: Style Loaded"))
    assertTrue(report.contains("Device Online: true (Wi-Fi)"))
    assertTrue(report.contains("Buffer Capacity: 250"))
    assertTrue(report.contains("[INFO] [Engine] MapLibre initialized"))
    assertTrue(report.contains("[ERROR] [Network] Connection timed out"))
  }
}
