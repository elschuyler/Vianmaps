# Blueprints: Vian Maps Native Android Application

## 1. Architecture Overview
- **App Module**: Single activity architecture (`MainActivity.kt`) hosting MapLibre Native MapView inside Jetpack Compose `MainMapScreen.kt`.
- **UI Framework**: Modern Jetpack Compose, Material Design 3.
- **Mapping Engine**: MapLibre Native SDK (C++ OpenGL core with Android bindings) using OpenFreeMap vector styles (Liberty, Bright, Positron) and local/remote style JSONs.
- **State Management**: MVVM via `MapViewModel`, exposing `StateFlow<MapUiState>` with lifecycle-aware collection.
- **Persistence Layer**: DataStore for preferences and UI coordinates; Room database reserved contract for future offline/geodata caching.
- **Diagnostics & Error Catching**: In-memory ring buffer (250 entries) `DiagnosticRepository`, `GlobalErrorCatcher`, `NetworkDiagnosticsMonitor`, and full-screen Log Keeper.

## 2. Component Hierarchy
- `MainMapScreen.kt`
  - `MapView` (AndroidView - full bleed surface)
  - `WindowInsets.statusBars` protection strip
  - Top-Left: `TopCoordinateInfoPanel.kt` (DMS coordinates + standby sensor indicator)
  - Top-Left: `MapSteppedScaleTile.kt` (Ratio + bracket scale ruler)
  - Top-Right: `MapCompassIndicator.kt` (Live bearing needle + tap to reset north)
  - Bottom-Left (Floating): `BottomNavigationBar.kt` (Compact 5-icon grey-translucent floating pill)
  - Bottom-Right (Floating): `RightSideMapControls.kt` (Individual circular grey-translucent bubbles for Telemetry + Zoom In/Out)
  - Bottom-Left above nav bar: `MapAttributionBadge.kt` (OpenFreeMap / OSM attribution)
  - Modal Layer: `AppModalManager.kt` (Settings, Quick Settings, Maps/Layers, Places, Tools, Log Keeper)

## 3. UI Redesign Specification (Phase 2.3)
- **Bottom Navigation Container**:
  - Compact width (`wrapContentWidth()`), anchored at `Alignment.BottomStart` with `8.dp` left margin and `8.dp` bottom margin (taking `navigationBarsPadding()` into account).
  - All 4 corners rounded (`RoundedCornerShape(16.dp)`).
  - Material: Dark slate translucent (`Color(0xCC1E293B)`) with subtle outline border (`Color(0x33FFFFFF)`).
  - 5 Navigation icons: Map Sources/Settings, Layers, Placemarks/Flag, Location Reticle, Compass/Lock.
- **Right-side Control Stack**:
  - Individual circular floating bubbles (`CircleShape`, 44dp visual with 48dp touch target).
  - Grey-translucent backplate (`Color(0xCC1E293B)`) with subtle outline border (`Color(0x33FFFFFF)`).
  - Anchored at `Alignment.BottomEnd` with `8.dp` right margin and `8.dp` bottom margin (taking `navigationBarsPadding()` into account).
  - Stacked vertically:
    - Top: Internet Toggle (Wifi / WifiOff)
    - Middle-Top: GPS Toggle (GpsFixed / GpsOff)
    - Middle: Refresh Maps
    - Spacer (8dp)
    - Lower: Zoom In (`+`)
    - Bottom: Zoom Out (`-`) — directly horizontally adjacent to the bottom navigation bar.

## 4. Development Phases
- [x] Phase 1: Native Android & MapLibre SDK Foundation, 2D gestures, DataStore.
- [x] Phase 1.1: Unified Sky Blue & White design system, floating popups.
- [x] Phase 2.1: Lifecycle hardening, OpenGL surface release, location permission handling.
- [x] Phase 2.2: Vector map integration (OpenFreeMap Liberty, Bright, Positron), compass, scale bar.
- [x] Phase 2.2.1: DiagnosticRepository, GlobalErrorCatcher, NetworkDiagnosticsMonitor, Log Keeper, Android 15 status bar.
- [x] Phase 2.2.2: Reference screenshot alignment (top DMS card, stepped scale ruler).
- [x] Phase 2.3: Redesign bottom bar to compact left-anchored grey-translucent pill, individual circular floating bubbles for right-side controls, and lowering Zoom (-/+) beside bottom bar.
- [x] Phase 2.4: Restore 4 original Milestone 1 buttons (Tune, Map, PinDrop, Explore/Tools) with tap & long-press, increase bar translucency (45%) and snugness, enlarge right-side bubbles to 48dp with sharper glyphs, and convert popups to in-canvas anchored popovers above the bar without dark scrim.
- [x] Phase 2.5: Foldable top coordinate panel with chevron indicator, expanded decimal coordinate & camera telemetry readouts, and placeholder Sensor Panel button.
- [x] Phase 2.6: Fix Blank MapLibre Base Map & Remove Attribution Banner (Correct MapView lifecycle ownership and host lifecycle sync, pre-register listeners before getMapAsync, add MapEngineStatus model, sync MapLibre.setConnected with network policy, add MapLibre Demo Tiles diagnostic style, and remove wide attribution banner while preserving native attribution control).
- [x] Phase 3.0: MapView Lifecycle Decoupling, Hardware Telemetry & Tile-by-Tile Offline Foundation (Eliminated recomposition disposal by remembering single MapView instance, keyed DisposableEffect strictly to lifecycleOwner, tagged destruction reasons, added device hardware diagnostic logging in MainActivity, enlarged ambient cache to 500 MB via OfflineManager, migrated legacy maplibre_demo preference to OpenFreeMap Liberty default, implemented TileStorageManager for progressive XYZ tile persistence, OsmOfflineDataRepository for canonical OSM feature deduplication across tile buffers, RoutingGraphCoverageIndex for Valhalla 3-level graph hierarchy mapping, and defined HYBRID_SATELLITE_OSM style foundation).
- [x] Phase 3.1: Complete Hybrid Map Composition, Live Visible-Tile Acquisition & Offline Room Pipeline (Delivered genuine MapLibre composite Hybrid style with satellite raster imagery beneath vector overlays and fill layer transparency adjustments; built Room Database with 6 entities/DAOs; built zero-dependency pure Kotlin MvtParser for vector tile decompression and geometry normalization; connected VisibleTileCoordinator to live viewport with debouncing, antimeridian handling, and OpenFreeMap TOS limits enforcement; integrated MapLibre C++ OfflineManager putResourceWithUrl; implemented official Valhalla tile hierarchy calculator and coverage reporting; passed all 13 unit tests and verified debug APK assembly).
- [x] Phase 3.2: Implement Agreed Settings Entry & More Settings Workspace (Replaced obsolete Quick Settings with 3-entry Settings popup [Backup & Restore, Log Keeper, More Settings] anchored above bottom-left nav bar; implemented full-screen More Settings workspace with clean categorized foundation; implemented honest Backup & Restore milestone planned view; wired direct long-press on Tune to open More Settings; updated BackHandler so Log Keeper, More Settings, and Backup & Restore return directly to map; preserved MapView lifecycle intact; expanded unit tests to 15/15 passing).
- [x] Phase 3.3: Top Panel Width Tightening, 4-Axis Crosshair Compass & Bottom Popup Vertical Spacing Refinement (Tightened DMS coordinate formatting and reduced top panel padding/font size with 58dp end margin constraint preventing compass collision; converted compass indicator to minimalist 4-axis cross reticle [Sky Blue North arm, slate S/E/W arms, center pivot]; reduced vertical padding and item spacing across all bottom popups [Settings, Maps, Places, Tools] and card shell; verified 15/15 unit tests passing and clean APK assembly).
- [x] Phase 3.4: Complete Main Map UI, Map Catalogue & Saved Map Compositions (Implemented Map Catalogue subpage under More Settings with 4 sections: Saved Compositions management with pin/unpin quick choices, Map Composition Builder for custom overlay combinations and satellite opacity, Supported Sources & Styles Directory with genuine attribution and licensing, and Offline Storage Accounting with exact on-disk byte metrics and safe per-provider cache clearing; reworked bottom-bar Maps popup to display only pinned quick-choices with direct link to Map Catalogue; wired BackHandler to return from Map Catalogue to More Settings and preserved MapView lifecycle intact; expanded unit test suite to 20/20 passing).
- [x] Phase 3.5: Reduce Padding Above and Below Item Cards in Bottom Bar Popups (Tightened header-to-list spacers, inter-item card spacing, and card internal vertical padding across Settings, Maps, Places, and Tools popups).
- [x] Phase 3.6: Focused Storage Provider Identity Fix (Separated visual map styles from underlying data providers with canonical lowercase IDs [openfreemap, esri_satellite, openaerialmap, maplibre_demo, osm_standard]; updated VisibleTileCoordinator, TileStorageManager, StorageCalculationManager, and MapCatalogueWorkspace; implemented idempotent legacy directory/Room record migration; ensured per-provider clearing strictly targets that provider without touching shared OSM features or other providers; expanded unit tests to 24/24 passing).
- [x] Phase 3.6.1: Migration Safety & Acquisition Consistency (Hardened filesystem and Room legacy migration to be strictly loss-safe with CRC32 verification, temporary file writes, and conflict preservation; introduced StorageMigrationReport and ProviderClearResult models for complete observability; updated UI to display migration health notice; added honest acquisition status and retention policy indicators for vector vs raster providers; expanded unit tests to 32/32 passing).
- [x] Phase 3.6.2: Migration Integrity & Storage Truthfulness (Eliminated maxOf illusion from storage accounting with transparent reconciliation of physical files vs Room records; verified physical file CRC32 during Room migration before legacy deletion; decoupled vector vs raster retention permissions with enforcement in TileStorageManager; hardened cache clearing with verified file and directory deletions; expanded unit tests to 40/40 passing).
- [x] Phase 3.7: Stability, UX & Completeness Pass (Fast-path style load guard in `MapEngineController` preventing redundant shader recompilation and GL churn; coordinated single style load invocation via ViewModel and `LaunchedEffect` in `MainMapScreen`; eliminated deprecated `TRIM_MEMORY_RUNNING_LOW` warnings; rendered `PlacesPopupContent` rows fully interactive with navigation chevrons leading to `MemoryPalaceWorkspace`; expanded `MemoryPalaceContent` from a 4-line stub into a comprehensive 4-tab spatial records workspace [Overview, Placemarks, Routes, Notes] adhering to local-first zero-cloud architecture; added explicit offline truthfulness distinguishing saved GPS route tracks from an offline routing graph engine across `StorageCalculationManager`, `MapCatalogueWorkspace`, and `AppModalManager`; added descriptive "Future Milestone" badges to `ToolPlaceholderContent`; verified 40/40 passing unit tests and clean applet compilation).

## 5. Ledger of Changes
- 2026-11-06: Initialized BLUEPRINT.md.
- 2026-11-06: Completed Phase 2.3 UI redesign execution.
- 2026-11-06: Completed Phase 2.4 (4-button restoration, 45% translucency, 48dp bubbles, in-canvas popovers).
- 2026-11-06: Completed Phase 2.5: Foldable top coordinate panel with chevron indicator, expanded decimal coordinate & camera telemetry readouts, and placeholder Sensor Panel button.
- 2026-11-06: Completed Phase 2.6: Blank Base Map Fix & Attribution Banner Removal.
- 2026-11-07: Completed Phase 3.0 (MapView Lifecycle Decoupling, Hardware Telemetry & Progressive Tile Retention Foundation).
- 2026-11-08: Completed Phase 3.1 (Complete Hybrid Map, Visible-Tile Acquisition & Offline Pipeline).
- 2026-11-08: Completed Phase 3.2 (Settings Entry, 3-Item Popup & More Settings Workspace).
- 2026-11-09: Completed Phase 3.3 (Top Panel Width, Cross Compass & Popup Spacing).
- 2026-11-09: Completed Phase 3.4 (Main Map UI, Map Catalogue & Saved Map Compositions).
- 2026-11-09: Completed Phase 3.5 (Bottom Bar Popup Item Card Spacing Refinement).
- 2026-11-10: Completed Phase 3.6 (Focused Storage Provider Identity Fix).
- 2026-11-10: Completed Phase 3.6.1 (Migration Safety & Acquisition Consistency).
- 2026-11-11: Completed Phase 3.6.2 (Migration Integrity & Storage Truthfulness).
- 2026-11-11: Completed Phase 3.7 (Stability, UX & Completeness Pass):
  - In `MapEngineController.loadStyle`: Added fast-path guard (`activeStyle != null && activeStyle.isFullyLoaded && currentStyleDefinition?.id == styleDef.id`) that reapplies composition overlays, notifies `STYLE_READY`, and settles tiles without triggering expensive style URI reloading and OpenGL shader recompilations.
  - In `MainMapScreen.kt`: Simplified `onSelectStyle` to invoke `viewModel.selectStyle(style)`, allowing `LaunchedEffect(uiState.currentStyle.id)` to coordinate the map engine loading as a single source of truth without duplicate loads; added `@Suppress("DEPRECATION")` to `onTrimMemory` callback.
  - In `VisibleTileCoordinator.kt`: Simplified `isAntimeridianCrossed` condition to eliminate redundant null check compiler warning.
  - In `AppModalManager.kt`:
    * Upgraded `PlacesPopupContent` items (Placemarks, Routes, Notes) from static inert cards to interactive clickable rows with forward navigation chevrons, directly routing into the Memory Palace workspace.
    * Replaced 4-line `MemoryPalaceContent` placeholder with a structured, multi-tab spatial inventory workspace (`Overview`, `Placemarks`, `Routes`, `Notes`) featuring local Room SQLite statistics, zero-cloud architectural guarantees, and clear distinctions between GPS route tracks and routing engine graphs.
    * Updated `MoreSettingsWorkspace` to state "Standard System Display" and "Routing Engine: Graph index prepared (Engine planned)".
    * Upgraded `ToolPlaceholderContent` with a clean "Future Milestone" badge and concrete architectural notes regarding local SQLite POI search, sensor compass navigation, and geodetic measurements.
  - In `StorageCalculationManager.kt` and `MapCatalogueWorkspace.kt`: Explicitly documented in storage reports and catalogue UI that Valhalla 3-level graph tiles provide spatial index coverage while full turn-by-turn routing calculation requires an active routing engine planned for a future milestone.
  - Verified 40/40 unit tests passing (100% success rate) and verified applet compilation.




