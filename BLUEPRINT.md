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

## 5. Ledger of Changes
- 2026-11-06: Initialized BLUEPRINT.md.
- 2026-11-06: Completed Phase 2.3 UI redesign execution.
- 2026-11-06: Completed Phase 2.4 (4-button restoration, 45% translucency, 48dp bubbles, in-canvas popovers).
- 2026-11-06: Completed Phase 2.5: Foldable top coordinate panel with chevron indicator, expanded decimal coordinate & camera telemetry readouts, and placeholder Sensor Panel button.
- 2026-11-06: Completed Phase 2.6: Blank Base Map Fix & Attribution Banner Removal.
- 2026-11-07: Completed Phase 3.0 (MapView Lifecycle Decoupling, Hardware Telemetry & Progressive Tile Retention Foundation).
- 2026-11-08: Completed Phase 3.1 (Complete Hybrid Map, Visible-Tile Acquisition & Offline Pipeline):
  - Created genuine MapLibre composite Hybrid map in `MapEngineController.kt` (`applyHybridRasterComposition`) injecting satellite raster source/layer (`satellite-raster-source`) beneath OSM vector roads, bridges, and labels while hiding solid landcover/landuse fills and setting water opacity to 0.35.
  - Implemented Room Database (`AppDatabase.kt`) with `RetainedTileEntity`, `OsmPoiRecord`, `OsmBuildingRecord`, `OsmRegionRecord`, `OsmRoadRecord`, and `RoutingGraphTileRecord`.
  - Created `MvtParser.kt` providing pure Kotlin gzip decompression, protobuf decoding, zigzag delta geometry decoding, and tile-to-lat/lng coordinate conversion.
  - Refactored `OsmOfflineDataRepository.kt` to persist all decoded OSM entities to Room and provide persistent offline POI search across restarts.
  - Refactored `TileStorageManager.kt` with atomic writes, CRC32 checksum verification, Room metadata sync, and native MapLibre `OfflineManager.putResourceWithUrl` resource injection.
  - Created `ValhallaTileCalculator.kt` with official Valhalla 3-level hierarchy math and bounding-box intersection calculations, and updated `RoutingGraphCoverageIndex.kt` to honestly report `NOT_CONFIGURED` or `MISSING_COVERAGE` when binary `.gph` files are absent.
  - Built `VisibleTileCoordinator.kt` connecting live MapLibre camera viewport to tile acquisition with 750ms debouncing, antimeridian handling, OpenFreeMap TOS limits (max 4 parallel connections, 3,000 tiles / 50MB session caps), and instant pause on `AppNetworkPolicy` Internet OFF.
  - Updated `ExampleUnitTest.kt` with 13 comprehensive unit tests across coordinate math, viewport enumeration, antimeridian crossing, Room search, deduplication, and Valhalla indices (13/13 passing).

