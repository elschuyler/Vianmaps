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
  }
}
