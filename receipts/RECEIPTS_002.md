# Receipts Log: Vian Maps Native Android Application (File 002)

This file series is the project's permanent audit trail across sessions. Each file is strictly capped at 500 lines.

---

### Entry 2026-11-08T20:34:00Z — Phase 3.2: Implement Agreed Settings Entry & More Settings Workspace
* **Summary**: Implement agreed 3-item Settings popup, direct long-press More Settings entry, honest Backup & Restore placeholder, and direct back navigation to map.
* **Exact files touched**:
  - `app/src/main/java/com/example/ui/screens/MapViewModel.kt` (updated `MapUiState`, `ActiveModal.MoreSettings`, `ActiveModal.BackupRestore`, `openSettingsPopup()`, `closeSettingsPopup()`)
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt` (implemented 3-item `SettingsPopupContent`, `MoreSettingsWorkspace`, `BackupRestoreContent`, removed obsolete `QuickSettingsContent` and `SettingsScreenContent`)
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt` (wired `BottomNavigationBar` callbacks, updated `BackHandler` so Log Keeper, More Settings, and Backup & Restore return directly to map, and preserved MapView lifecycle intact)
  - `app/src/main/java/com/example/ui/components/BottomNavigationBar.kt` (updated KDoc and inline comments for 3-item popup and direct long-press)
  - `app/src/test/java/com/example/ExampleUnitTest.kt` (configured `@Config(sdk = [36])`, added unit tests `test14_verifySettingsPopupAndModalTransitions` and `test15_verifyDirectLongPressToMoreSettings`)
  - `BLUEPRINT.md` (documented Phase 3.2 completion and ledger update)
  - `receipts/RECEIPTS_001.md` (closed at 465 lines per 500-line limit mandate)
  - `receipts/RECEIPTS_002.md` (created new receipts ledger file)
* **What was actually done**:
  - Replaced the obsolete 2-item Quick Settings popup (which had non-functional "Share Map Image" and "Display & Gesture Calibration") with a floating 3-entry Settings popup:
    1. Backup & Restore (with "Planned" badge tag and honest architectural explanation).
    2. Log Keeper (opens full-screen Log Keeper workspace).
    3. More Settings (opens full-screen More Settings workspace).
  - Wired short tap on Tune / Settings to open the 3-entry popup anchored above the bottom-left navigation bar with zero window-dimming scrim.
  - Wired long-press on Tune / Settings to directly open the full-screen More Settings workspace without displaying the popup.
  - Implemented the full-screen `MoreSettingsWorkspace` featuring clean, categorized sections (Map Display & Projection, Units & Coordinate Formats, Offline Storage & Visible Tiles, Network Policy & Device Privacy, and About Vian Maps) without fake or non-functional toggles.
  - Implemented `BackupRestoreContent` with an honest statement explaining that encrypted local archive export/import is scheduled for a future milestone, confirming zero simulated operations are active and that data remains locally in Room.
  - Fixed back navigation: Back from Log Keeper now returns directly to the map instead of returning to a settings screen; Back from More Settings and Backup & Restore also returns directly to the map; Back while the compact popup is open cleanly dismisses it.
  - Maintained complete MapView lifecycle integrity: left MapView instantiation, AndroidView factory/release, and lifecycle observer in `MainMapScreen.kt` completely untouched.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: 15/15 tests passed (BUILD SUCCESSFUL in 11s, 100% success rate).
  - Verified container build via `compile_applet` tool: Build succeeded.
  - Verified debug APK generation via `gradle assembleDebug`: BUILD SUCCESSFUL in 4s (`app-debug.apk` generated).
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: Future milestones will incrementally implement real settings controls in More Settings and local file archive export in Backup & Restore.

---

### Entry 2026-11-09T18:05:00Z — Phase 3.3: Top Panel Width Tightening, 4-Axis Crosshair Compass & Bottom Popup Vertical Spacing Refinement
* **Summary**: Tighten top coordinate panel width with 58dp end margin constraint, convert compass indicator to Option A minimalist 4-axis cross reticle, and reduce vertical padding across all bottom popup menus.
* **Exact files touched**:
  - `app/src/main/java/com/example/ui/components/MapTelemetryOverlays.kt` (converted compass needle canvas to 4-axis crosshair reticle: Sky Blue North arm, slate S/E/W arms, center pivot dot)
  - `app/src/main/java/com/example/ui/components/TopCoordinateInfoPanel.kt` (tightened `formatDms` spacing, reduced coordinate font size to `11.5.sp`, reduced internal padding to `horizontal = 6.dp, vertical = 3.dp`, chevron icon `size = 14.dp`)
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt` (added `end = 58.dp` constraint to top cluster column, eliminating any compass collision on narrow screens)
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt` (reduced outer `FloatingPopupDialog` padding to `10.dp`; reduced item row vertical padding in `SettingsPopupContent` to `4.dp` with `3.dp` spacers; reduced `MapsPopupContent` style item padding to `5.dp`; reduced `PlacesPopupContent` item padding to `3.dp`; reduced `ToolsPopupContent` item padding to `3.dp` and list gap to `2.dp`)
  - `BLUEPRINT.md` (documented Phase 3.3 in development phases and ledger)
  - `receipts/RECEIPTS_002.md` (appended Phase 3.3 audit entry)
* **What was actually done**:
  - Converted the compass pointer from an arrow/wedge needle to a minimalist 4-axis crosshair reticle (`+`) where the North arm is highlighted in bold Sky Blue and the South, East, and West arms are rendered in subtle neutral slate, retaining live bearing rotation and click-to-reset-North.
  - Reduced the width footprint of the top coordinate panel by tightening DMS degree/minute/second spacing, scaling down font size to 11.5sp, tightening internal padding to 6dp horizontal and 3dp vertical, and applying an explicit 58dp end padding buffer to the top cluster column in `MainMapScreen.kt`.
  - Compressed the vertical height of bottom popup cards by reducing outer card padding to 10dp and lowering internal item vertical padding to 3–5dp across Settings, Maps, Places, and Tools menus, eliminating excessive whitespace.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: 15/15 tests passed (BUILD SUCCESSFUL in 43s, 100% success rate).
  - Verified container applet build via `compile_applet` tool: Build succeeded.
  - Verified debug APK generation via `gradle assembleDebug`: BUILD SUCCESSFUL in 2s (`app-debug.apk` up-to-date).
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: None. Ready for on-device manual QA.

---

### Entry 2026-11-09T21:35:00Z — Phase 3.4: Complete Main Map UI, Map Catalogue & Saved Map Compositions
* **Summary**: Implement Map Catalogue subpage, saved map compositions and layer builder, per-source offline storage audit, reworked quick-choice map popup, and More Settings navigation.
* **Exact files touched**:
  - `app/src/main/java/com/example/map/composition/MapComposition.kt` (created model for saved compositions, default starter compositions [Satellite Hybrid, Everyday Street, Explore & Boundaries, High-Contrast Navigation, Muted Positron], and JSON list serialization/deserialization)
  - `app/src/main/java/com/example/data/storage/StorageCalculationManager.kt` (created storage audit manager calculating exact on-disk byte footprint for tile files by provider, Room SQLite `map_app_master.db` + WAL/SHM file size, OSM feature counts, routing graph files, and safe per-provider cache clearing)
  - `app/src/main/java/com/example/data/db/AppDatabase.kt` (added `getTileCountByProvider`, `getBytesByProvider`, and `deleteTilesByProvider` queries to `RetainedTileDao`)
  - `app/src/main/java/com/example/data/preferences/AppPreferencesRepository.kt` (added DataStore keys and persistence functions for `activeCompositionId` and `savedCompositionsJson`)
  - `app/src/main/java/com/example/map/engine/MapEngineController.kt` (added `applyComposition` and `applyCompositionOverlays` for instant dynamic toggling of roads, labels, POIs, boundaries, and buildings layers)
  - `app/src/main/java/com/example/ui/screens/MapViewModel.kt` (added `activeComposition`, `savedCompositions`, `storageReport`, and composition management functions: select, save, delete, duplicate, toggle pin, refresh storage, clear provider cache)
  - `app/src/main/java/com/example/ui/components/MapCatalogueWorkspace.kt` (created full-screen More Settings subpage with Saved Compositions list, Composition Builder, Supported Sources Catalogue with licensing/attribution, and Offline Storage Accounting)
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt` (reworked `MapsPopupContent` to show only pinned quick choices with direct link to Map Catalogue, updated `MoreSettingsWorkspace` with navigation card and corrected default style to Satellite Hybrid, made helper cards internal)
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt` (wired BackHandler so Map Catalogue returns to More Settings, wired all composition and storage callbacks while preserving MapView lifecycle completely untouched)
  - `app/src/test/java/com/example/ExampleUnitTest.kt` (added unit tests 16 through 20 covering MapComposition serialization, MapCatalogue backstack, composition switching, pin toggling, storage formatting, and safe cache clearing)
  - `BLUEPRINT.md` (documented Phase 3.4 in development phases and ledger)
  - `receipts/RECEIPTS_002.md` (appended Phase 3.4 entry)
* **What was actually done**:
  - Main map popup reworked: Removed obsolete non-functional "Layers" and "Custom" tabs; popup now displays only user-saved pinned map setups (quick-choices) with active checkmark, compact height, `LazyColumn` scrolling, and a direct navigation action button to "Map Catalogue & Setups".
  - Created full-screen Map Catalogue workspace accessible via More Settings, featuring 4 cohesive sections:
    1. Saved Map Compositions: lists user compositions with active indicators, pin/unpin toggles for popup quick choices, activate buttons, builder edit buttons, duplication, and deletion with confirmation dialog.
    2. Map Composition Builder: customizable setup name/description, base map selection, overlay feature toggles (Roads, Labels, POIs, Boundaries, Buildings), satellite opacity slider, compatibility explanations, save/reset actions.
    3. Supported Sources & Licensing Directory: complete transparent catalog of operational providers (ESRI World Imagery, OpenFreeMap Liberty/Bright/Positron, OpenStreetMap Carto), clear distinction between base maps and overlays, attribution, and honest notice for planned elevation DEM contours.
    4. Offline Storage Accounting & Cache Management: exact byte and tile footprint breakdown by provider, Room SQLite database file length and feature counts, routing graph status, storage recalculate button, and safe per-provider cache clearing without touching shared SQLite features.
  - Back navigation hierarchy: Map Catalogue Back button returns to More Settings; More Settings Back button returns directly to the map surface.
  - Complete MapView safety: MapView instance, lifecycle observer keys, and local Room offline feature stores remained completely isolated and unharmed.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: 20/20 tests passed (BUILD SUCCESSFUL in 23s, 100% success rate).
  - Verified container applet build via `compile_applet` tool: Build succeeded in 10s.
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: None. Ready for on-device manual QA.

---

### Entry 2026-11-09T21:48:00Z — Phase 3.5: Reduce Padding Above and Below Item Cards in Bottom Bar Popups
* **Summary**: Tighten vertical padding and inter-item spacers in bottom-bar popups (Settings, Maps, Places, Tools).
* **Exact files touched**:
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt` (reduced header spacer to 4dp and inter-item spacers to 2dp in `SettingsPopupContent`; reduced internal vertical padding to 2.5dp across Settings and Maps cards; reduced external item margin to 1dp and divider spacers to 4dp in `MapsPopupContent`; reduced `PlaceItemRow` and `ToolsPopupContent` item padding to 2dp and header spacers to 3dp)
  - `receipts/RECEIPTS_002.md` (appended Phase 3.5 entry)
* **What was actually done**:
  - In `SettingsPopupContent`: reduced header spacer from 10dp to 4dp; tightened item internal vertical padding from 4dp to 2.5dp; reduced inter-card spacers from 3dp to 2dp.
  - In `MapsPopupContent`: reduced subtitle spacers from 6dp to 3dp; tightened card external vertical margin from 2dp to 1dp; reduced card internal vertical padding from 5dp to 2.5dp; reduced divider spacers from 8dp/6dp to 4dp; reduced Catalogue button padding from 6dp to 4dp.
  - In `PlacesPopupContent`: reduced header spacer and button spacer to 3dp; reduced `PlaceItemRow` internal vertical padding from 3dp to 2dp.
  - In `ToolsPopupContent`: reduced header spacer to 3dp; reduced list item spacing to 1.5dp; reduced item internal vertical padding from 3dp to 2dp.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: 20/20 tests passed (BUILD SUCCESSFUL in 15s).
  - Verified container applet build via `compile_applet` tool: Build succeeded in 42s.
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: None. Ready for on-device manual QA.

---

### Entry 2026-11-10T17:31:00Z — Phase 3.6: Focused Storage Provider Identity Fix
* **Summary**: Establish canonical, stable storage provider IDs (`openfreemap`, `esri_satellite`, `openaerialmap`, `maplibre_demo`, `osm_standard`), decouple visual styles from underlying providers, implement safe idempotent legacy storage migration, and ensure isolated cache clearing.
* **Exact files touched**:
  - `app/src/main/java/com/example/map/source/MapSourceRepository.kt` (added `StorageProviderDefinition`, `MapStorageProviders`, `resolveCanonicalProviderId`, updated `MapStyleDefinition` with `storageProviderId` and `rasterStorageProviderId`, and wired canonical IDs across all 7 map styles)
  - `app/src/main/java/com/example/map/acquisition/VisibleTileCoordinator.kt` (updated `processVisibleCoverage` to acquire tiles under canonical `styleDef.storageProviderId`)
  - `app/src/main/java/com/example/data/db/AppDatabase.kt` (added `getAllProviderIds()` and `getTilesByProvider()` queries to `RetainedTileDao`)
  - `app/src/main/java/com/example/data/storage/StorageCalculationManager.kt` (implemented `migrateLegacyStorage` for on-disk directories and Room metadata, updated `calculateStorage` to audit canonical data providers, and updated `clearProviderTiles` to purge canonical and legacy folders while preserving shared OSM feature records and routing graphs)
  - `app/src/main/java/com/example/ui/components/MapCatalogueWorkspace.kt` (updated Section 3 to display canonical storage provider identities and Section 4 to show accurate provider tile accounting)
  - `app/src/test/java/com/example/ExampleUnitTest.kt` (added tests 21-24 covering canonical mappings, multi-style OpenFreeMap sharing, legacy migration, and safe cache clearing; all 24 passed)
  - `BLUEPRINT.md` (updated Phase 3.6 in development phases and ledger)
  - `receipts/RECEIPTS_002.md` (appended Phase 3.6 audit entry)
* **What was actually done**:
  - Traced complete provider ID flow and resolved inconsistencies where `VisibleTileCoordinator` used sanitized display names (e.g. `hybrid_(satellite_+_osm)`), `StorageCalculationManager` queried by visual style IDs (e.g. `openfreemap_liberty`), and `TileStorageManager` indexed by provider ID.
  - Established canonical, stable storage provider IDs: `"openfreemap"` (OSM vector tiles), `"esri_satellite"` (satellite raster imagery), `"openaerialmap"` (contributed aerial imagery), `"maplibre_demo"` (diagnostic tiles), and `"osm_standard"` (fallback raster tiles).
  - Decoupled visual styles from data providers: Liberty, Bright, Positron, and the vector layer of Hybrid all route to `"openfreemap"`. Hybrid satellite imagery routes to `"esri_satellite"`.
  - Implemented safe, idempotent legacy storage migration in `StorageCalculationManager.migrateLegacyStorage`: recursively moves on-disk tiles from legacy directories to canonical directories, updates non-canonical Room metadata primary keys without duplicate conflicts, and purges empty legacy directories.
  - Made provider-cache clearing surgical: `clearProviderTiles` purges only the selected provider's tile directory and Room records, leaving other providers, shared OSM feature tables (`osm_pois`, `osm_buildings`, `osm_roads`, `osm_regions`), and routing graph files completely untouched.
  - Maintained complete MapView lifecycle integrity: left MapView instantiation, AndroidView factory/release, and lifecycle observer in `MainMapScreen.kt` completely untouched.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: 24/24 tests passed (BUILD SUCCESSFUL in 19s, 100% success rate).
  - Verified container build via `compile_applet` tool: Build succeeded in 19s.
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: None. Ready for on-device manual QA.
