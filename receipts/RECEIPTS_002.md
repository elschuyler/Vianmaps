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

---

### Entry 2026-11-10T20:21:00Z — Phase 3.6.1: Migration Safety & Acquisition Consistency
* **Summary**: Harden legacy storage migration against silent data loss using CRC32 checksums and atomic writes; introduce StorageMigrationReport and ProviderClearResult models; wire migration conflict reporting into UI; clarify vector vs. raster acquisition boundaries.
* **Exact files touched**:
  - `app/src/main/java/com/example/data/storage/StorageCalculationManager.kt` (hardened `migrateLegacyStorage` with CRC32 checks, `.migrating.tmp` writes, safe duplicate removal, and conflict preservation; introduced `StorageMigrationReport` and `ProviderClearResult`; updated `SourceStorageReport` with `acquisitionStatus`, `retentionPolicy`, and `isOfflineRetained`)
  - `app/src/main/java/com/example/map/source/MapSourceRepository.kt` (added `retentionPolicyDescription` and `acquisitionPipelineStatus` to `StorageProviderDefinition` across all 5 canonical providers)
  - `app/src/main/java/com/example/map/acquisition/VisibleTileCoordinator.kt` (clarified in KDoc that coordinator strictly retains OpenFreeMap vector tiles, while raster layers are rendered live into MapLibre C++ ambient cache with Esri offline retention restricted by terms)
  - `app/src/main/java/com/example/ui/screens/MapViewModel.kt` (updated `clearProviderCache` to log structured `ProviderClearResult`)
  - `app/src/main/java/com/example/ui/components/MapCatalogueWorkspace.kt` (wired `migrationReport` conflict notice card into Section 4, and displayed acquisition status / retention policy per source)
  - `app/src/test/java/com/example/ExampleUnitTest.kt` (expanded test suite to 32 tests covering safe identical deduplication, content conflict preservation, corrupt target recovery, repeated migration idempotency, Room collision resolution, missing file failure reporting, honest raster acquisition status, and surgical provider clear with shared data preservation; all 32 passed)
  - `BLUEPRINT.md` (updated Phase 3.6.1 in development phases and ledger)
  - `receipts/RECEIPTS_002.md` (appended Phase 3.6.1 entry)
* **What was actually done**:
  - Fixed the legacy file migration vulnerability where existing canonical files could cause legacy files to be deleted without verified safety. Implemented `computeFileCrc32`:
    * When both canonical and legacy files exist with identical CRC32 checksums, legacy duplicate is safely removed.
    * When canonical file is corrupt/0-bytes and legacy file is valid, canonical file is safely replaced via temporary file with verified checksum.
    * When both exist with different valid data (conflict), neither file is deleted or overwritten blindly; conflict is preserved and recorded in `StorageMigrationReport.unresolvedFileConflicts`.
    * When canonical file does not exist, legacy file is copied via `.migrating.tmp`, CRC32-verified, atomically renamed, and verified before legacy deletion.
  - Hardened Room migration: resolves primary key collisions, checks underlying disk file validity, updates canonical entities if target file is recovered, records missing files in `failures`, and returns a structured, observable `StorageMigrationReport`.
  - Clarified vector vs. raster acquisition: accurately documented in `VisibleTileCoordinator.kt`, `MapStorageProviders`, and `MapCatalogueWorkspace.kt` that `VisibleTileCoordinator` retains OpenFreeMap vector tiles only. Raster imagery (Esri World Imagery, OpenAerialMap) is rendered on-the-fly by MapLibre's native C++ OpenGL engine into its ambient cache, with Esri permanent offline retention legally restricted by its Terms of Use.
  - Updated `clearProviderTiles` to return `ProviderClearResult` detailing files, records, bytes deleted, and success state, guaranteeing zero deletion of other providers, shared OSM feature tables, or routing graph files.
  - Maintained complete MapView lifecycle integrity: left MapView instantiation, AndroidView factory/release, and lifecycle observer in `MainMapScreen.kt` completely untouched.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: 32/32 tests passed (BUILD SUCCESSFUL in 21s, 100% success rate).
  - Verified container build via `compile_applet` tool: Build succeeded in 19s.
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: None. Ready for on-device manual QA.
---

### Entry 2026-11-11T20:26:00Z — Phase 3.6.2: Migration Integrity & Storage Truthfulness
* **Summary**: Harden migration loss-safety with stream flush/sync and physical disk CRC32 validation; eliminate maxOf illusion from storage accounting with truthful on-disk and Room reconciliation; decouple vector vs. raster retention permissions and enforce in TileStorageManager; verify file/directory deletions in provider cache clearing.
* **Exact files touched**:
  - `app/src/main/java/com/example/map/source/MapSourceRepository.kt` (added decoupled `vectorRetentionPermitted` and `rasterRetentionPermitted` to `MapStyleDefinition`; configured `HYBRID_SATELLITE_OSM` with permitted vector retention and restricted raster retention; configured across all 7 map styles)
  - `app/src/main/java/com/example/map/acquisition/VisibleTileCoordinator.kt` (wired vector acquisition retention permission to `styleDef.vectorRetentionPermitted`)
  - `app/src/main/java/com/example/data/tile/TileStorageManager.kt` (enforced retention permission and provider default policy gate in `saveTileData`, blocking restricted tiles from disk/Room; flushed and synced write streams before atomic promotion)
  - `app/src/main/java/com/example/data/storage/StorageCalculationManager.kt` (flushed and synced file copy streams before promotion; verified physical file existence and CRC32 against Room `checksum` in `migrateLegacyStorage`; expanded `StorageMigrationReport` with `missingFiles` and `integrityFailures`; eliminated `maxOf` illusion in `calculateStorage` with transparent reconciliation reporting `physicalFileCount`, `physicalByteSize`, `roomRecordCount`, `verifiedRecordCount`, `orphanedRecordCount`, `unindexedFileCount`, and `integrityFailureCount`; hardened `clearProviderTiles` to verify individual file deletions, remove empty directories, and verify Room deletion while preserving shared OSM feature tables and routing graphs)
  - `app/src/main/java/com/example/data/db/AppDatabase.kt` (added `clearAllPois()` query to `OsmPoiDao`)
  - `app/src/main/java/com/example/ui/components/MapCatalogueWorkspace.kt` (updated Section 4 storage cards to display verified tile counts and reconciliation discrepancy notices)
  - `app/src/test/java/com/example/ExampleUnitTest.kt` (added tests 33 to 40 covering physical CRC validation during Room migration, metadata string matching with corrupt disk files, orphaned record and unindexed file reconciliation, integrity failure detection in storage reports, restricted tile retention blocking, hybrid vector retention authorization, truthful provider clear with OSM and routing preservation, and unknown provider clearing; all 40/40 tests passed)
  - `BLUEPRINT.md` (updated Phase 3.6.2 in development phases and ledger)
  - `receipts/RECEIPTS_002.md` (appended Phase 3.6.2 audit entry)
* **What was actually done**:
  - Migration stream flush & sync: In `StorageCalculationManager.migrateLegacyStorage`, file copy streams now call `fos.flush()` and `fos.fd.sync()` before length and CRC32 verification. Temporary files (`.migrating.tmp`) are verified before atomic replacement of the canonical destination.
  - Physical file CRC32 verification in Room migration: Room migration no longer assumes matching string checksums guarantee disk integrity. It computes the actual CRC32 of the physical file on disk:
    * If physical file CRC32 matches `tile.checksum`, canonical record is verified and duplicate legacy row is deleted.
    * If physical file is corrupt or has mismatched CRC32, migration records an `integrityFailure` and `unresolvedDbConflict` and preserves the legacy row.
    * If physical file is missing from disk, it records a `missingFile` error and preserves the legacy row.
  - Truthful storage reconciliation: Replaced `maxOf(diskCount, dbCount)` and `maxOf(diskBytes, dbBytes)` with honest physical file and database reconciliation per provider:
    * Scans actual physical files on disk: `physicalFileCount`, `physicalByteSize`.
    * Queries Room records: `roomRecordCount`.
    * Reconciles: `verifiedRecordCount` (record whose file exists and file CRC32 matches record checksum), `orphanedRecordCount` (Room record with no physical file), `unindexedFileCount` (physical file with no Room record), and `integrityFailureCount` (physical file CRC32 differs from record checksum).
    * Total tile storage is the sum of actual physical bytes on disk.
  - Decoupled retention permissions: Separated `vectorRetentionPermitted` from `rasterRetentionPermitted` on `MapStyleDefinition`. On composite `HYBRID_SATELLITE_OSM`, vector retention is `true` (OpenFreeMap ODbL data is retained) while raster retention is `false` (Esri satellite imagery is restricted to live rendering only).
  - Retention enforcement: In `TileStorageManager.saveTileData`, added pre-retention permission check. If `retentionPermission == "RESTRICTED"` or provider default policy is restricted, retention is blocked and returns `false` without writing to disk or Room.
  - Truthful cache clearing: In `clearProviderTiles`, each file deletion is verified, directory removal is verified, and database deletion is checked. Returns exact deleted counts and bytes, flagging errors if files remain, while guaranteeing zero deletion of other providers, shared OSM feature records (`osm_pois`, `osm_buildings`, `osm_roads`, `osm_regions`), or routing graph files.
  - Maintained complete MapView lifecycle integrity: left MapView instantiation, AndroidView factory/release, and lifecycle observer in `MainMapScreen.kt` completely untouched.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: all 40/40 tests passed (BUILD SUCCESSFUL in 24s, 100% success rate).
  - Verified container build via `compile_applet` tool: Build succeeded.
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: None. Ready for on-device manual QA.
---

### Entry 2026-11-11T22:04:00Z — Phase 3.7: Stability, UX & Completeness Pass
* **Summary**: Eliminate redundant map style initialization and shader churn; simplify style loading to a single ViewModel-coordinated trigger; suppress deprecated memory callback warnings; upgrade Places popup rows to interactive navigation leading to Memory Palace; expand MemoryPalaceContent into a 4-tab local spatial records workspace; clarify offline routing graph index vs turn-by-turn routing calculation; add informative future milestone badges to Tool placeholders.
* **Exact files touched**:
  - `app/src/main/java/com/example/map/engine/MapEngineController.kt` (added fast-path guard in `loadStyle` when active style is fully loaded and IDs match)
  - `app/src/main/java/com/example/map/acquisition/VisibleTileCoordinator.kt` (simplified antimeridian condition to eliminate redundant null check warning)
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt` (suppressed deprecated `TRIM_MEMORY_RUNNING_LOW` warning; removed duplicate `controller.loadStyle` call in `onSelectStyle` callback)
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt` (added forward chevron and click handler to `PlaceItemRow`; made Placemarks, Routes, and Notes clickable to open `MemoryPalaceWorkspace`; expanded `MemoryPalaceContent` into 4-tab workspace [Overview, Placemarks, Routes, Notes]; updated display and routing status in `MoreSettingsWorkspace`; added "Future Milestone" badge and domain-specific architectural notes to `ToolPlaceholderContent`)
  - `app/src/main/java/com/example/data/storage/StorageCalculationManager.kt` (truthfully labeled routing graph tile status as spatial index with engine planned)
  - `app/src/main/java/com/example/ui/components/MapCatalogueWorkspace.kt` (added footnote distinguishing routing graph spatial indexing from a turn-by-turn routing engine)
  - `BLUEPRINT.md` (updated development phases and change ledger for Phase 3.7)
  - `receipts/RECEIPTS_002.md` (appended Phase 3.7 audit entry)
* **What was actually done**:
  - Eliminating duplicate style initialization: In `MapEngineController.loadStyle`, added a fast-path check: if `map.style` exists, is fully loaded, and matches `currentStyleDefinition?.id`, it reapplies overlay visibilities/opacity, notifies `STYLE_READY`, and settles tiles without reloading the style JSON or recreating OpenGL shader pipelines. In `MainMapScreen.kt`, simplified `onSelectStyle` to delegate to `viewModel.selectStyle(style)`, allowing the single `LaunchedEffect(uiState.currentStyle.id)` to coordinate the map engine loading.
  - Compiler warning cleanups: Suppressed deprecation on `ComponentCallbacks2.onTrimMemory` in `MainMapScreen.kt` and eliminated redundant null checks in `VisibleTileCoordinator.kt`.
  - Upgrading Places & Memory Palace UX: Converted `PlacesPopupContent`'s three inert cards into interactive clickable rows with forward navigation chevrons, directly routing into the Memory Palace workspace. Replaced the 4-line placeholder in `MemoryPalaceContent` with a comprehensive 4-tab spatial inventory workspace (`Overview`, `Placemarks`, `Routes`, `Notes`) featuring local Room SQLite statistics, zero-cloud architectural guarantees, and clear distinctions between GPS route tracks and routing engine graphs.
  - Offline routing truthfulness: Explicitly stated across `StorageCalculationManager.kt`, `MapCatalogueWorkspace.kt`, and `AppModalManager.kt` that Valhalla 3-level graph tiles provide spatial index coverage, while full turn-by-turn routing calculation requires an active routing engine planned for a future milestone.
  - Tool placeholders completeness: Enhanced `ToolPlaceholderContent` with a "Future Milestone" badge and truthful descriptions of how offline search uses Room SQLite POIs, navigation uses sensor compass waypoints, and measurement uses WGS84 geodesics.
* **How it was verified**:
  - Verified local JVM unit test suite via `gradle :app:testDebugUnitTest`: all 40/40 tests passed (BUILD SUCCESSFUL in 12s, 100% success rate).
  - Verified container build via `compile_applet` tool: Build succeeded in 10s.
  - On-device claim only: not tested on physical hardware in this turn (verified via JVM unit test suite and compilation).
* **Deviations from request**: None.
* **Known issues / follow-up**: None. Ready for on-device manual QA.

---

### Entry 2026-11-12T22:59:00Z — Phase 4.0: Offline POI Search & Map Highlighting
* **Summary**: Implement local-first offline POI search querying Room `osm_pois` acquired from viewed vector tiles, Compass/Tools Search & Highlight entries, live MapLibre target highlighting across style switches, and Selected POI banner.
* **Exact files touched**:
  - `app/src/main/java/com/example/data/db/AppDatabase.kt` (added `CategoryCountTuple`; updated `OsmPoiDao` with `ESCAPE '\'` LIKE queries across `name`/`category`/`subCategory`, exact/prefix relevance ranking, case-insensitive category filtering, `getPoisByCategoryOnly`, and `getCategoryCounts`; preserved DB version 1)
  - `app/src/main/java/com/example/data/osm/OsmOfflineDataRepository.kt` (updated `searchPois` with blank-query guard, category-only mode, SQL wildcard escaping, result cap, and bounded Unicode fallback matching; added `getCategoryCounts` and `getSamplePois`)
  - `app/src/main/java/com/example/data/osm/MvtParser.kt` (added `isNotBlank()` tag guards, `name:latin` fallback, and fixed `decodeFirstPoint` loop index stride for non-MoveTo commands)
  - `app/src/main/java/com/example/map/acquisition/VisibleTileCoordinator.kt` (clamped acquisition zoom in `processVisibleCoverage` to `0..14` matching OpenFreeMap vector `maxzoom = 14`)
  - `app/src/main/java/com/example/map/engine/MapEngineController.kt` (added `GeoJsonSource` + dual `CircleLayer` POI highlight rendering, camera focus, visibility toggle, clear, style/composition restore, and highlight layer guard in `applyCompositionOverlays`)
  - `app/src/main/java/com/example/ui/screens/MapViewModel.kt` (added `PoiCategoryOption`, `PoiSearchUiState`, `highlightedPoi`, `isPoiHighlightVisible`, `ActiveModal.PoiSearch`, `ActiveModal.PoiHighlight`, debounced `Dispatchers.IO` search with job cancellation, selection/recenter/clear handlers, and resource release on close)
  - `app/src/main/java/com/example/ui/components/PoiSearchModal.kt` (created Offline POI Search screen with local-only notice, search box, dynamic category chips, loading/empty/error/no-match states, and bounded results list)
  - `app/src/main/java/com/example/ui/components/PoiHighlightOverlays.kt` (created `SelectedPoiHighlightBanner` map overlay card and `PoiHighlightPopupContent` for `Tools -> Highlight`)
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt` (wired `ActiveModal.PoiSearch`, `ActiveModal.PoiHighlight`, and live `Search`/`Highlight` handlers inside `ToolsPopupContent`)
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt` (rendered `SelectedPoiHighlightBanner` when appropriate, synced controller highlight state across recomposition, and wired POI callbacks without altering `MapView` instantiation or `DisposableEffect`)
  - `app/src/test/java/com/example/ExampleUnitTest.kt` (added tests 41–45 covering exact/partial/case/Unicode search, category & subCategory filtering, blank/empty DB/limit/wildcard escaping, duplicate ingestion deduplication, and offline ViewModel + `MapEngineController` highlight handoff)
  - `BLUEPRINT.md` (marked Phase 4.0 complete and logged changes)
  - `receipts/RECEIPTS_002.md` (appended Phase 4.0 audit entry)
* **What was actually done**:
  - Wired Search & Highlight inside the Compass/Tools menu (`ToolsPopupContent`) rather than Places popup.
  - Queried local Room `osm_pois` table off the main thread (`Dispatchers.IO`) with 200ms typing debounce, obsolete job cancellation, wildcard escaping, and Unicode case-folding fallback, with zero network calls and zero location permission triggers.
  - Implemented live MapLibre `GeoJsonSource` + concentric `CircleLayer` highlight marker and camera centering upon result selection, automatically restored across style and composition changes without source/layer duplication.
  - Added dismissible `SelectedPoiHighlightBanner` and functional `Tools -> Highlight` inspection/toggle/recenter/clear controls while truthfully noting full polygon/zone drawing remains a future milestone.
* **How it was verified**:
  - Local JVM unit test suite via `gradle :app:testDebugUnitTest`: `TEST-com.example.ExampleUnitTest.xml` reported 45/45 tests passed (`skipped="0"`, `failures="0"`, `errors="0"`, BUILD SUCCESSFUL in 56s).
  - Container applet compilation via `compile_applet`: Build succeeded.
  - On-device verification: Not tested on physical Android hardware in this environment (container has no emulator/ADB; physical-device acceptance steps provided).
* **Deviations from request**: None.
* **Known issues / follow-up**: Physical-device verification of live tile POI ingestion, MapLibre GL circle highlight rendering across styles, and airplane-mode search needs manual verification using the provided acceptance guide. General polygon/area highlight drawing remains planned for a future milestone.

