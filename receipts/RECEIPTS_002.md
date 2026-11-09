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
