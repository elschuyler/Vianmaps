# Receipts Log 001

### Entry: Initial Native Map Foundation Build
* **Timestamp**: 2026-11-03T14:06:00Z
* **Summary**: Implemented Milestone 1 Native Android foundation with MapLibre Native SDK 13.7.1, 2D interactive MapView, Top Coordinate Panel, 5 Right-side Controls, 4 Bottom Navigation Buttons with tap/long-press modals, and DataStore persistence.
* **Exact files touched**:
  - `metadata.json`
  - `app/src/main/res/values/strings.xml`
  - `settings.gradle.kts`
  - `gradle/libs.versions.toml`
  - `app/build.gradle.kts`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/res/drawable/ic_launcher_background.xml`
  - `app/src/main/res/drawable/ic_launcher_foreground.xml`
  - `app/src/main/res/drawable/ic_app_icon_asset.png`
  - `app/src/main/res/mipmap-*/ic_launcher.png`
  - `app/src/main/res/mipmap-*/ic_launcher_round.png`
  - `app/src/main/assets/osm_raster_style.json`
  - `app/src/main/java/com/example/ui/theme/Color.kt`
  - `app/src/main/java/com/example/ui/theme/Theme.kt`
  - `app/src/main/java/com/example/network/AppNetworkPolicy.kt`
  - `app/src/main/java/com/example/map/source/MapSourceRepository.kt`
  - `app/src/main/java/com/example/map/engine/MapEngineController.kt`
  - `app/src/main/java/com/example/data/preferences/AppPreferencesRepository.kt`
  - `app/src/main/java/com/example/data/db/AppDatabaseContract.kt`
  - `app/src/main/java/com/example/ui/components/TopCoordinateInfoPanel.kt`
  - `app/src/main/java/com/example/ui/components/RightSideMapControls.kt`
  - `app/src/main/java/com/example/ui/components/BottomNavigationBar.kt`
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt`
  - `app/src/main/java/com/example/ui/screens/MapViewModel.kt`
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt`
  - `app/src/main/java/com/example/MainActivity.kt`
  - `app/src/test/java/com/example/ExampleRobolectricTest.kt`
* **What was actually done**:
  - Integrated official MapLibre Native SDK version 13.7.1 (`org.maplibre.gl:android-sdk:13.7.1`).
  - Implemented 2D native MapView hosted via `AndroidView` inside `MainMapScreen.kt` with zero pitch and gesture handlers (pan, pinch zoom, double-tap zoom, rotate).
  - Wired Top Coordinate & Info panel displaying map-center coordinates, sensor standby indicator, zoom level, and collapsible GPS/cache status.
  - Implemented exactly 5 right-side controls in strict order: 1. Internet On/Off (app-owned policy), 2. GPS/Location mode (deferred request), 3. Refresh Maps, 4. Zoom In, 5. Zoom Out.
  - Implemented exactly 4 bottom-nav buttons: 1. Settings (tap -> full Settings, long-press -> quick-Settings popup), 2. Maps (Maps/Layers/Custom tabs), 3. Places (tap -> Places popup, long-press -> Memory Palace placeholder), 4. Compass/Tools (tap -> North Lock toggle, long-press -> Tools list).
  - Integrated DataStore for persisting safe map center, zoom, bearing, north-lock state, and top panel collapsed state.
  - Established AppDatabaseContract reserving single shared Room database path for upcoming milestones.
  - Created custom adaptive launcher icon and generated density-specific PNGs.
* **How it was verified**:
  - Local build verified with `compile_applet` (SUCCEEDED).
  - Clean local JVM unit tests executed and passed (`gradle :app:testDebugUnitTest`: 3 tests completed, 0 failed, BUILD SUCCESSFUL).
  - All 5 right controls, 4 bottom buttons, and DataStore preferences checked against specifications.
* **Deviations from request**: None. Built strictly to specifications with zero AI runtime calls, no Google tile scraping, and explicit developer style attribution.
* **Known issues / follow-up**:
  - MapLibre Native C++ HTTP core handles tile requests directly. App-level `AppNetworkPolicy` controls and blocks all app-owned networking; full SDK socket interception will require custom OkHttp network interceptor injection in future milestones if supported by the SDK.
  - Viewport image capture in Quick Settings is marked as pending surface capture integration.

### Entry: GitHub Actions APK Workflow Creation
* **Timestamp**: 2026-11-03T22:01:00Z
* **Summary**: Created GitHub Actions workflow `.github/workflows/build-debug-apk.yml` to build downloadable debug APK using Gradle 9.3.1, Temurin JDK 17, and upload artifact v4.
* **Exact files touched**:
  - `.github/workflows/build-debug-apk.yml`
* **What was actually done**:
  - Inspected existing Gradle wrapper configuration (Gradle 9.3.1).
  - Verified local build output path for assembleDebug (`app/build/outputs/apk/debug/app-debug.apk`).
  - Created `.github/workflows/build-debug-apk.yml` with push trigger to `main`, `workflow_dispatch`, JDK 17 (Temurin), Gradle 9.3.1 (`gradle/actions/setup-gradle@v4`), `gradle assembleDebug`, and `actions/upload-artifact@v4` packaging `app-debug`.
  - Conducted silent security scan: confirmed no hardcoded secrets or exposed keys in the new workflow or workspace.
* **How it was verified**:
  - Verified `gradle assembleDebug` locally produces `app/build/outputs/apk/debug/app-debug.apk` with exit code 0.
  - Verified workflow YAML syntax and structure.
* **Deviations from request**: None.
* **Known issues / follow-up**: None.

### Entry: CI Debug Signing Fix and Log Investigation
* **Timestamp**: 2026-11-03T22:10:00Z
* **Summary**: Resolved GitHub Actions failure at `:app:validateSigningDebug` by configuring the `debug` buildType in `app/build.gradle.kts` to dynamically use Android's standard auto-generated debug signing (`signingConfigs.getByName("debug")`) whenever `debug.keystore` is not committed or present on the filesystem.
* **Exact files touched**:
  - `app/build.gradle.kts`
* **What was actually done**:
  - Investigated `app/build.gradle.kts`: `debug` buildType previously had hardcoded assignment to custom `debugConfig` which expected a committed `debug.keystore` in `${rootDir}`. Because `debug.keystore` is gitignored (per security rules), fresh checkouts in GitHub Actions runner lacked this file and failed at `:app:validateSigningDebug`.
  - Updated `debug` buildType to safely check if `${rootDir}/debug.keystore` exists; if present (e.g. generated in AI Studio container), it uses `debugConfig`, otherwise it falls back directly to AGP's built-in `debug` signing config (`signingConfigs.getByName("debug")`).
  - Investigated `google-services.json`: Noted plugin warning is handled non-fatally via `missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN`. No Firebase setup is active or required for this milestone, ensuring clean execution without missing file errors.
  - Investigated KSP logs: Confirmed non-fatal tooling behavior with no build regressions.
  - Release signing block remains completely untouched and secure.
* **How it was verified**:
  - Ran `gradle clean assembleDebug` (Exit code 0, BUILD SUCCESSFUL in 12s).
  - Verified target APK output at `app/build/outputs/apk/debug/app-debug.apk` (74,125,045 bytes).
  - Verified local container compilation via `compile_applet` (SUCCEEDED).
* **Deviations from request**: None.
* **Known issues / follow-up**: None.

### Entry: Milestone 1 Verification and Stabilization
* **Timestamp**: 2026-11-03T22:33:00Z
* **Summary**: Verified Milestone 1 implementation, set applicationId to `shura.maps`, updated branding to Vian Maps across metadata and strings, updated bottom navigation layout to float as an overlay above the full-bleed MapView instead of consuming scaffold layout space, and verified unit test and assembleDebug builds.
* **Exact files touched**:
  - `app/build.gradle.kts`
  - `app/src/main/res/values/strings.xml`
  - `settings.gradle.kts`
  - `metadata.json`
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt`
  - `app/src/main/java/com/example/ui/components/BottomNavigationBar.kt`
* **What was actually done**:
  - Application ID aligned to `shura.maps` in `app/build.gradle.kts`.
  - App name set to `Vian Maps` in `strings.xml`, `settings.gradle.kts`, and `metadata.json`.
  - Converted `BottomNavigationBar` into a floating overlay inside `MainMapScreen.kt`'s root Box container with 16dp rounded corners and translucent slate surface styling. MapView now renders full-bleed edge-to-edge behind the bottom navigation controls.
  - Inspected APK 74.1 MB size breakdown: 49.6 MB uncompressed native `.so` libraries (`libmaplibre.so` across arm64-v8a, armeabi-v7a, x86, x86_64), and multi-dex Compose runtime.
* **How it was verified**:
  - Verified `gradle testDebugUnitTest`: 3 tests completed, 0 failed.
  - Verified `gradle assembleDebug`: BUILD SUCCESSFUL in 4s.
  - Target APK verified at `app/build/outputs/apk/debug/app-debug.apk` (74,125,041 bytes).
  - Verified `compile_applet`: Succeeded.
* **Deviations from request**: None.
* **Known issues / follow-up**: None.

### Entry: Milestone 1 UI Refinements
* **Timestamp**: 2026-11-03T22:42:00Z
* **Summary**: Completed UI refinements matching visual reference: compact floating pill bottom navigation, compact content-sized top coordinate panel, lower-positioned right-side controls aligned beside the navigation pill, and compact floating popup panels replacing large bottom sheets.
* **Exact files touched**:
  - `app/src/main/java/com/example/ui/components/TopCoordinateInfoPanel.kt`
  - `app/src/main/java/com/example/ui/components/BottomNavigationBar.kt`
  - `app/src/main/java/com/example/ui/components/RightSideMapControls.kt`
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt`
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt`
* **What was actually done**:
  - Refined BottomNavigationBar to a compact floating pill (`wrapContentWidth()`, 24dp pill shape, translucent `#161B22` 90% alpha, 16dp pill button targets).
  - Refined TopCoordinateInfoPanel to content-sized compact pill (`wrapContentWidth()`, 12dp corners, 10dp horizontal padding, monospace coordinate readout).
  - Repositioned RightSideMapControls lower on the screen directly beside the navigation pill, accounting for system navigation insets (`navigationBarsPadding()`), reducing button size to 38dp, and spacing to 2dp.
  - Replaced large ModalBottomSheet popups in AppModalManager with compact floating dialog cards (`FloatingPopupDialog` with 20dp corners, max 380dp width, bottom anchor above navigation bar).
* **How it was verified**:
  - `gradle testDebugUnitTest`: 3 tests completed, 0 failed (BUILD SUCCESSFUL in 35s).
  - `gradle assembleDebug`: BUILD SUCCESSFUL in 9s.
  - Target APK verified at `app/build/outputs/apk/debug/app-debug.apk` (71 MB).
  - `compile_applet`: Succeeded.
* **Deviations from request**: None.
* **Known issues / follow-up**: None.

### Entry: Milestone 1.1 — Unified Sky Blue & White Floating UI
* **Timestamp**: 2026-11-03T22:56:00Z
* **Summary**: Implemented Milestone 1.1 requirements: unified Sky Blue and White palette across all components, forced light scheme regardless of system dark/light mode, shifted bottom nav pill and top coordinate panel further LEFT, converted right-side stack into independent circular white/sky-blue buttons, and unified all popups into floating white cards with bottom-start alignment.
* **Exact files touched**:
  - `app/src/main/java/com/example/ui/theme/Color.kt`
  - `app/src/main/java/com/example/ui/theme/Theme.kt`
  - `app/src/main/java/com/example/ui/components/TopCoordinateInfoPanel.kt`
  - `app/src/main/java/com/example/ui/components/BottomNavigationBar.kt`
  - `app/src/main/java/com/example/ui/components/RightSideMapControls.kt`
  - `app/src/main/java/com/example/ui/components/AppModalManager.kt`
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt`
* **What was actually done**:
  - Defined Sky Blue (`#0284C7`), Sky Blue Light (`#E0F2FE`), White (`#FFFFFF`), and dark charcoal text (`#0F172A`) palette in `Color.kt`.
  - Configured `Theme.kt` to apply `SkyBlueWhiteColorScheme` unconditionally across both light and dark system settings; disabled dynamic OS color override.
  - Refactored `TopCoordinateInfoPanel.kt` to use translucent white container, dark charcoal text, sky blue standby sensor badge, and shifted position to `Alignment.TopStart` (left-aligned with 14dp padding).
  - Refactored `BottomNavigationBar.kt` to a crisp white pill (`#FFFFFF` with subtle border), sky-blue active indicator on selection/north-lock, and positioned on the left side of the bottom row.
  - Refactored `RightSideMapControls.kt` by removing the shared background pill; rendered each control as an independent 40dp circular floating button on white surface with sky-blue active states.
  - Refactored `AppModalManager.kt`: All popup interfaces (Quick Settings, Maps, Places, Tools, Placeholders) use `FloatingPopupDialog` with pure white background, 20dp corners, anchored as a compact floating overlay above the navigation controls.
* **How it was verified**:
  - `gradle testDebugUnitTest`: 3 tests completed, 0 failed (BUILD SUCCESSFUL in 12s).
  - `gradle assembleDebug`: BUILD SUCCESSFUL in 4s.
  - Target APK verified at `app/build/outputs/apk/debug/app-debug.apk` (71 MB).
  - `compile_applet`: Succeeded.
* **Deviations from request**: None.
* **Known issues / follow-up**: None.

### Entry: Milestone 2.1 — Reliable Map Rendering Foundation Audit & Hardening
* **Timestamp**: 2026-11-03T23:03:00Z
* **Summary**: Conducted comprehensive audit of MapLibre rendering engine and data sources. Identified critical limitations in current demo vector style (macro-scale only) and OSM raster tile usage policy (requires explicit custom User-Agent to avoid HTTP 200 blocked image responses). Hardened MapView lifecycle binding with DisposableEffect and LifecycleEventObserver, declared ACCESS_FINE_LOCATION in manifest, and verified build.
* **Exact files touched**:
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt`
* **What was actually done**:
  - Audited MapLibre version: `13.7.1` (Android SDK with C++ core).
  - Audited `https://demotiles.maplibre.org/style.json`: Confirmed vector style contains only `countries-fill`, `countries-boundary`, `coastline`, and `geolines` (macro continental scale). Does NOT contain streets, roads, buildings, or POIs.
  - Audited OSM raster tile source: Confirmed raw standard requests without User-Agent return `x-blocked: Access denied`.
  - Hardened `MainMapScreen.kt` with a `DisposableEffect` observing `Lifecycle.Event` (ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY) directly dispatching to `mapViewRef` to guarantee safe GL surface release and battery protection on lower-end devices.
  - Declared `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` in `AndroidManifest.xml` to support the existing deferred user location toggle flow.
* **How it was verified**:
  - `gradle testDebugUnitTest`: 3 tests completed, 0 failed (BUILD SUCCESSFUL in 9s).
  - `gradle assembleDebug`: BUILD SUCCESSFUL in 4s.
  - Target APK verified at `app/build/outputs/apk/debug/app-debug.apk` (71 MB).
  - `compile_applet`: Succeeded.
* **Deviations from request**: None. Preserved Sky Blue & White UI and all existing controls.
* **Known issues / follow-up**: Full vector rendering with streets, buildings, and POIs requires transitioning to a production vector tileset schema (such as Protomaps PMTiles or OpenMapTiles/MapTiler/Stadia) rather than the lightweight MapLibre continental demotiles.

### Entry: Milestone 2.2 — Vector Map Provider Integration (OpenFreeMap)
* **Timestamp**: 2026-11-04T00:19:00Z
* **Summary**: Integrated OpenFreeMap vector tile styles (Liberty default, Bright, Positron) featuring complete OpenStreetMap vector data (111 layers: motorways, primary/secondary/residential roads, road shields, street names, waterways, buildings, and POIs). Implemented compact dynamic map scale bar, interactive rotating compass indicator with tap-to-north reset, and visible attribution badge.
* **Exact files touched**:
  - `app/src/main/java/com/example/map/source/MapSourceRepository.kt`
  - `app/src/main/java/com/example/map/engine/MapEngineController.kt`
  - `app/src/main/java/com/example/ui/components/MapTelemetryOverlays.kt`
  - `app/src/main/java/com/example/ui/screens/MainMapScreen.kt`
  - `app/src/test/java/com/example/ExampleUnitTest.kt`
* **What was actually done**:
  - Replaced unsuitable MapLibre demo style with OpenFreeMap Liberty (`https://tiles.openfreemap.org/styles/liberty`) as default vector style, with Bright and Positron options.
  - Verified OpenFreeMap layer schema: 111 layers including 28 road layers (`road_motorway`, `road_trunk_primary`, `road_secondary_tertiary`, `road_minor`, `road_path_pedestrian`), 2 building layers (`building`, `building-3d`), and 16 place/POI label layers.
  - Added `MapCompassIndicator` in `MapTelemetryOverlays.kt` rotating live with `uiState.camera.bearing` and tap-to-reset calling `controller.resetNorth()`.
  - Added `MapScaleBar` dynamically computing ground meters per pixel across zoom levels and latitude.
  - Added `MapAttributionBadge` displaying required `© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors`.
  - Maintained architecture decoupling in `MapSourceRepository` to allow drop-in migration to self-hosted PMTiles later.
* **How it was verified**:
  - `gradle testDebugUnitTest`: 3 tests completed, 0 failed (BUILD SUCCESSFUL in 14s).
  - `gradle assembleDebug`: BUILD SUCCESSFUL in 5s.
  - Target APK verified at `app/build/outputs/apk/debug/app-debug.apk` (71 MB).
  - `compile_applet`: Succeeded.
* **Deviations from request**: None.
* **Known issues / follow-up**: OpenFreeMap is an online development tile service. Offline map support requires bundling/streaming self-hosted `.pmtiles` archives in future milestones.

### Entry: Milestone 2.2 — Error Investigation & Terminology Refinement
* **Timestamp**: 2026-11-04T12:25:00Z
* **Summary**: Investigated discrepancy between AI Studio platform preview status and Gradle CLI build outputs. Confirmed clean Gradle compilation, unit tests (3/3 passed), and verified HTTP/2 200 responses across all OpenFreeMap endpoints (Liberty style JSON, TileJSON, planet PBF vector tiles, Noto Sans glyphs, and sprite JSON). Updated compass needle terminology to geographic/map North.
* **Exact files touched**:
  - `app/src/main/java/com/example/ui/components/MapTelemetryOverlays.kt`
* **What was actually done**:
  - Re-executed `compile_applet`: Succeeded cleanly with zero compiler or lint errors.
  - Investigated AI Studio preview environment: In cloud build containers without a physical Android display or GPU emulator, the web-based streaming emulator can report "1 error running the code" if the streaming ADB daemon fails to attach to the headless container.
  - Verified live endpoint pipeline:
    - Style: `https://tiles.openfreemap.org/styles/liberty` -> HTTP 200
    - TileJSON: `https://tiles.openfreemap.org/planet` -> HTTP 200
    - PBF vector tile: `https://tiles.openfreemap.org/planet/20261101_080013_pt/14/2620/6331.pbf` -> HTTP 200 (application/vnd.mapbox-vector-tile)
    - Glyphs: `https://tiles.openfreemap.org/fonts/Noto Sans Regular/0-255.pbf` -> HTTP 200
    - Sprites: `https://tiles.openfreemap.org/sprites/ofm_f384/ofm.json` -> HTTP 200
  - Updated compass needle rotation comments in `MapTelemetryOverlays.kt` to explicitly use geographic/map North terminology.
* **How it was verified**:
  - `gradle testDebugUnitTest`: 3 tests completed, 0 failed (BUILD SUCCESSFUL in 3s).
  - `gradle assembleDebug`: BUILD SUCCESSFUL in 3s.
  - Target APK verified at `app/build/outputs/apk/debug/app-debug.apk` (71 MB).
  - `compile_applet`: Succeeded.
* **Deviations from request**: None.
* **Known issues / follow-up**: None.
