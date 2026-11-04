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
