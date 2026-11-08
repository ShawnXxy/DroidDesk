# Multi-Device Compatibility Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace model-specific Android compatibility branches with capability evidence, explicit placement adapters, pure presentation policies, event-driven readiness, and release gates that preserve R2/TNT and NX888J behavior on the same commit.

**Architecture:** Gather immutable platform evidence, select one of two real desktop-placement adapters at a single seam, and resolve render/input/refresh behavior through pure policy modules. Keep OEM identity in one quirk catalog, migrate persisted display settings to capability-oriented keys, and make the existing coordinator and activities orchestrate these modules rather than own their implementation.

**Tech Stack:** Kotlin/JVM, Java, Android framework APIs, Flutter/Dart, JUnit 4, GitHub Actions, Python 3 standard library.

**Spec:** `docs/architecture/multi-device-compatibility.md`

**Re-evaluated baseline:** `origin/xixia` at `e8884c2` on 2026-11-08.

**Execution plan:** `docs/plans/multi-device-compatibility-pr-execution.md` decomposes these 11 engineering milestones into P00-P22 review-sized PR units. Use that file for implementation sequencing and PR workflow.

## Global Constraints

- Preserve one APK, one Linux session, and one active X11 view.
- Preserve Native Termux, Debian PRoot, and rooted Ubuntu chroot runtime modes.
- Preserve ARM64-only support, `minSdk = 28`, and `targetSdk = 28`.
- Preserve XFCE behavior; do not add another desktop environment.
- Preserve R2 phone/TNT restart behavior and headless state after TNT disconnect.
- Preserve generic secondary-display behavior and dynamic display IDs without restarting Linux.
- Preserve `DisplayRepository` primary/presentation source merging, transient connection keys, and stable `DesktopDisplay.settingsKey` behavior.
- Preserve `ExternalInputRoutePreference`, `ExternalInputRelayActivity`, `ExternalDesktopInputBridge`, and generic-external phone-relay behavior while moving policy behind dedicated interfaces.
- Preserve the existing reusable quality workflow, personal-release `needs: quality` gate, and package-store architecture guard.
- Add no third-party runtime dependency.
- Do not require hardware acceleration.
- Do not depend on fixed delays for correctness.
- Keep OEM identities and fixed OEM display IDs inside `DeviceQuirkCatalog` and test fixtures only.
- Give unknown devices a safe default and a user-selectable placement override.
- Migrate existing display settings without silently discarding user choices.
- User-directed exception for this refactor: merge independently reviewed micro-PRs into `ref/v1` before physical-device validation.
- Never target `xixia` from a task PR and do not create a `ref/v1 -> xixia` PR after the tasks finish.
- Every commit must be independently reviewed before commit and include `Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>`.
- When physical validation starts after P22, use one identical APK for R2 phone/TNT and NX888J phone/HDMI and keep wireless ADB enabled through validation and later review.

## Review Focus

- A secondary display ID disappears and is immediately reused for a different connection: the old request key must not attach to the replacement. Task 3 owns this test.
- An external display supports touch and a mouse at the same time: direct touch must remain available and pointer capture must not be forced merely because the display is external. Task 5 owns this test.
- Android cannot report which display owns an external pointer: the existing phone relay must remain the safe generic-external fallback without affecting R2/TNT native input. Task 5 owns this test.
- A 4K 120 Hz display has no 4K 60 Hz mode but does expose lower-resolution 60 Hz modes: DroidDesk must not change physical resolution. Task 4 owns this test.
- Surface, X11, geometry, and renderer signals arrive slowly, repeatedly, and out of order: desktop startup must occur exactly once without a fixed delay. Task 8 owns this test.
- An unknown OEM exposes ambiguous placement evidence: DroidDesk must choose the safe default, disable automatic moves, explain the decision, and honor a user override. Task 2 owns this test.

## Current Baseline Assessment

| Task | Status at `e8884c2` | Plan impact |
|---|---|---|
| 1. Capability evidence | Not implemented | Still required |
| 2. Placement selection | Not implemented | Still required |
| 3. Placement adapters | Not implemented | More urgent because placement and relay policy now share `DisplayEnvironmentPolicy.kt` |
| 4. Refresh policy | Not implemented | Unsafe nearest-size 60 Hz selection remains |
| 5. Input policy | Partially implemented | Preserve and deepen the new phone-relay adapter; sparse pointer IDs and policy separation remain |
| 6. Presentation policy | Not implemented | `smallestScreenWidthDp` and fixed profile heuristics remain |
| 7. Settings identity | Partially implemented | Reuse current stable `settingsKey`; remove the old fingerprint proposal |
| 8. Readiness gate | Not implemented | The 250 ms ordering delay remains |
| 9. Runtime adaptation | Partially implemented | Preserve AAudio-to-plain-PulseAudio fallback; library-path and timeout adaptation remain |
| 10. Diagnostics | Not implemented | Snapshot remains schema 1 with leaked booleans |
| 11. Quality/device gate | Partially implemented | Reusable quality and release gating already exist; add compatibility guard and physical gate only |

---

## Planned File Map

### Capability evidence

- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/capability/PresentationEvidence.kt`: framework-free evidence and enum types.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/capability/AndroidPresentationEvidenceProbe.kt`: Android adapter that gathers evidence.
- Create `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/support/PresentationFixtures.kt`: named R2, NX888J, and synthetic scenario fixtures.

### Placement seam

- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DeviceQuirkCatalog.kt`: the only production OEM identity registry.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementSelector.kt`: selection rules and selection-source diagnostics.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/PlacementPreferenceStore.kt`: explicit user override.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementStrategy.kt`: adapter interface and execution port.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/MovableSecondaryDisplayAdapter.kt`: generic Android adapter.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DisplayLocalRestartAdapter.kt`: R2/TNT-style adapter.
- Modify `DisplayRepository.kt`, `DesktopDisplayLauncher.kt`, `DesktopDisplayCoordinator.kt`, `DisplayLocalSessionController.kt`, `MainActivity.kt`, `DesktopActivity.kt`, `DroidDeskService.kt`, and `DesktopStartupNotification.kt`.
- Delete `DisplayEnvironmentPolicy.kt` only after all replacement tests pass.

### Presentation policies

- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/refresh/DisplayRefreshPolicy.kt`.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/DesktopInputPolicy.kt`.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/ExternalInputPolicy.kt`.
- Create `app/android/app/src/main/java/com/termux/x11/input/ActivePointerTracker.java`.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/policy/PresentationPolicy.kt`.
- Modify `DisplayProfile.kt`, `DesktopActivity.kt`, `X11InputController.kt`, `TouchInputHandler.java`, `InputEventSender.java`, and `X11RenderGeometry.java`.

### Settings and Flutter contract

- Modify `DisplaySettingsStore.kt`, `DesktopDisplayCoordinator.kt`, `MainActivity.kt`, `app/lib/models/desktop_display.dart`, `app/lib/services/platform_bridge.dart`, `app/lib/state/app_state.dart`, and `app/lib/screens/display_settings_screen.dart`.

### Readiness and runtime

- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/readiness/DesktopReadinessGate.kt`.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/ProbeTimeouts.kt`.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/HostAudioAdapter.kt`.
- Create `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/HostAudioPreferenceStore.kt`.
- Modify `DesktopActivity.kt`, `LorieView.java`, `RuntimeCapabilityProbe.kt`, and `LinuxRuntime.kt`.

### Diagnostics and quality gates

- Create `tools/check_device_compatibility_architecture.py`.
- Modify `.github/workflows/quality.yml`.
- Create `docs/testing/multi-device-compatibility-acceptance.md`.
- Update `docs/testing/debian-proot-r2-acceptance.md` and `docs/testing/debian-proot-nx888j-acceptance.md` after final physical validation.

---

### Task 1: Add framework-free presentation evidence

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/capability/PresentationEvidence.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/capability/PresentationEvidenceTest.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/support/PresentationFixtures.kt`

**Interfaces:**
- Produces:

```kotlin
enum class PresentationClass(val wireName: String) {
    PHONE_TOUCH("phoneTouch"),
    LARGE_TOUCH("largeTouch"),
    EXTERNAL_POINTER("externalPointer"),
    EXTERNAL_TOUCH("externalTouch"),
}

enum class PlacementMode(val wireName: String) {
    MOVABLE("movable"),
    DISPLAY_LOCAL("displayLocal"),
}

data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val device: String,
    val model: String,
)

data class DisplayModeEvidence(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

enum class TouchEvidence {
    NONE,
    CURRENT_DISPLAY,
    UNKNOWN_ASSOCIATION,
}

enum class PointerAssociationEvidence {
    NO_EXTERNAL_POINTER,
    TARGET_DISPLAY,
    DEFAULT_OR_UNASSOCIATED,
    OTHER_DISPLAY,
    UNKNOWN,
}

data class InputCapabilities(
    val touch: TouchEvidence,
    val pointerAssociation: PointerAssociationEvidence,
    val hasMouse: Boolean,
    val hasTouchpad: Boolean,
    val hasKeyboard: Boolean,
    val hasStylus: Boolean,
)

data class DisplayPlacementEvidence(
    val currentDisplayId: Int,
    val isDefaultDisplay: Boolean,
    val supportsSecondaryActivities: Boolean,
    val currentDisplayLaunchAllowed: Boolean,
    val defaultDisplayLaunchAllowed: Boolean,
    val anotherDisplayLaunchAllowed: Boolean,
)

data class PresentationContext(
    val displayId: Int,
    val displayName: String,
    val displayFlags: Int,
    val isDefaultDisplay: Boolean,
    val windowSizePx: PixelSize,
    val densityDpi: Int,
    val fontScale: Float,
    val isDesktopUiMode: Boolean,
    val currentMode: DisplayModeEvidence?,
    val supportedModes: List<DisplayModeEvidence>,
    val input: InputCapabilities,
)
```

- `densityDpi == 0` means unknown. Negative density values are invalid.
- `fontScale` must be positive.
- `PresentationFixtures` produces named `PresentationContext`, `DisplayPlacementEvidence`, and `DeviceIdentity` values for R2 phone, R2 TNT, NX888J phone, NX888J HDMI, external touch, foldable narrow-window, 4K120, sparse-pointer, and ambiguous-OEM scenarios.

- [ ] **Step 1: Prepare the local toolchain and record the clean baseline**

```powershell
Push-Location app
flutter config --jdk-dir $env:JAVA_HOME
flutter pub get --enforce-lockfile
flutter test
flutter build apk --debug --no-pub --target-platform android-arm64
Pop-Location
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --no-daemon --console=plain
```

Expected: the existing Flutter and Android tests pass before refactoring. The Flutter build generates the worktree-local Android configuration required by direct Gradle commands.

- [ ] **Step 2: Write the failing evidence tests**

Add tests named:

- `displayModesRequirePositiveDimensionsAndRefreshRate`
- `presentationContextRequiresPositiveWindowBoundsAndNonNegativeDensity`
- `r2AndNubiaFixturesUseDifferentPlacementEvidence`
- `externalTouchFixtureCarriesCurrentDisplayTouchAndPointerCapabilities`
- `phoneTouchDoesNotMarkUnrelatedHdmiDisplayAsTouchCapable`
- `unknownPointerAssociationFixturePreservesRelayFallbackEvidence`

Assert exact R2 values `1080 x 2340`, API-independent identity `device = "darwin"`, TNT display `100003`, NX888J phone `1260 x 2800`, and NX888J HDMI `2160 x 1440`.

- [ ] **Step 3: Run the tests and verify RED**

Run:

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*PresentationEvidenceTest" --no-daemon --console=plain
```

Expected: compilation fails because the evidence types do not exist.

- [ ] **Step 4: Implement the evidence types and fixture builders**

Add constructor validation for positive sizes, non-negative density, positive font scale, positive refresh rates, and unique positive mode IDs. Keep this file free of Android framework imports.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the Step 3 command.

Expected: all `PresentationEvidenceTest` tests pass.

- [ ] **Step 6: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record the focused test result, and leave the changes uncommitted for the final shared-APK device gate.

---

### Task 2: Select placement mode from evidence, override, or isolated quirks

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/capability/AndroidPresentationEvidenceProbe.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DeviceQuirkCatalog.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementSelector.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/PlacementPreferenceStore.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementSelectorTest.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/placement/PlacementPreferenceStoreTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayRepository.kt`

**Interfaces:**
- Consumes: Task 1 evidence types.
- Produces:

```kotlin
class AndroidPresentationEvidenceProbe(
    context: Context,
) {
    fun placementEvidence(currentDisplayId: Int): DisplayPlacementEvidence
    fun presentationContext(
        displayId: Int,
        activeWindowSize: PixelSize? = null,
    ): PresentationContext
}

enum class PlacementSelectionSource(val wireName: String) {
    USER_OVERRIDE("userOverride"),
    RUNTIME_EVIDENCE("runtimeEvidence"),
    DEVICE_QUIRK("deviceQuirk"),
    SAFE_DEFAULT("safeDefault"),
}

data class PlacementSelection(
    val mode: PlacementMode,
    val source: PlacementSelectionSource,
    val allowAutomaticMoves: Boolean,
    val reason: String,
    val tntDisplayIds: Set<Int> = emptySet(),
)

class DesktopPlacementSelector(
    private val quirks: DeviceQuirkCatalog,
) {
    fun select(
        evidence: DisplayPlacementEvidence,
        identity: DeviceIdentity,
        userOverride: PlacementMode?,
    ): PlacementSelection
}

interface PlacementPreferenceStore {
    fun loadOverride(): PlacementMode?
    fun saveOverride(mode: PlacementMode?)
}
```

- Selection rules:
  - User override wins and enables automatic moves only for `MOVABLE`.
  - Conclusive cross-display placement evidence selects `MOVABLE` with automatic moves enabled.
  - Current-only non-default placement selects `DISPLAY_LOCAL`.
  - R2 `device = "darwin"` selects `DISPLAY_LOCAL` through `DeviceQuirkCatalog`.
  - The R2 quirk returns `tntDisplayIds == setOf(100003)`; other selections return an empty set.
  - Ambiguous unknown evidence selects `MOVABLE`, disables automatic moves, and reports `SAFE_DEFAULT`.

- [ ] **Step 1: Write the failing selector tests**

Add tests named:

- `userOverrideWinsOverRuntimeAndQuirkEvidence`
- `crossDisplayPermissionSelectsMovableAdapter`
- `currentOnlyExternalPlacementSelectsDisplayLocalAdapter`
- `r2FallsBackToIsolatedDeviceQuirk`
- `r2QuirkCarriesTheTntEnvironmentDisplayId`
- `ambiguousUnknownDeviceUsesSafeMovableDefaultWithoutAutomaticMoves`
- `placementOverrideRoundTripsAndInvalidValuesAreCleared`

For the Review Focus case, assert `source == SAFE_DEFAULT`, `mode == MOVABLE`, `allowAutomaticMoves == false`, and a non-empty reason.

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DesktopPlacementSelectorTest" --tests "*PlacementPreferenceStoreTest" --no-daemon --console=plain
```

Expected: compilation fails because selector and preference interfaces do not exist.

- [ ] **Step 3: Implement the selector, quirk catalog, and preference store**

`DeviceQuirkCatalog` must be the only production file in this task containing `"darwin"` or `100003`. Store the user override in `droiddesk_placement_v1`; an absent value means automatic selection. Implement:

```kotlin
class AndroidPlacementPreferenceStore(context: Context) : PlacementPreferenceStore
```

Quirk metadata is resolved independently from the placement override. A user who selects `MOVABLE` on R2 still receives the TNT environment label for display `100003`, while the explicit mode controls behavior.

- [ ] **Step 4: Implement Android placement evidence collection**

`AndroidPresentationEvidenceProbe` reads package-manager secondary-display support and calls the existing `isActivityStartAllowedOnDisplay` logic for the current, default, and one other eligible display. Refactor `DisplayRepository` to share that permission helper instead of duplicating it.

Reuse `DisplayRepository`'s merged primary/presentation inventory and stable `settingsKey`; do not create a second display inventory or fingerprint algorithm.

For input evidence, use the input device's associated display ID when the platform exposes one. Treat unassociated touch as `UNKNOWN_ASSOCIATION`; do not classify a non-default display as touch-capable from that value alone. Collapse external pointer placement to `PointerAssociationEvidence` using this precedence: target display, default/unassociated, other display, no pointer, unknown.

For an inactive display, `presentationContext` uses `createDisplayContext(display)` resources and the current physical mode as its window-size fallback. An active `DesktopActivity` always supplies measured content bounds through `activeWindowSize`.

- [ ] **Step 5: Run focused and existing display tests**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DesktopPlacementSelectorTest" --tests "*PlacementPreferenceStoreTest" --tests "*DesktopDisplayLauncherPolicyTest" --tests "*DisplayLocalStartupTest" --no-daemon --console=plain
```

Expected: all selected tests pass; production behavior is unchanged.

- [ ] **Step 6: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record selector plus existing display-source tests, and keep the diff uncommitted.

---

### Task 3: Replace display-local branches with placement adapters

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementStrategy.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/MovableSecondaryDisplayAdapter.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DisplayLocalRestartAdapter.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayEnvironment.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/ExternalInputPolicy.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementStrategyTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayLauncher.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayCoordinator.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayLocalSessionController.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/service/DroidDeskService.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/service/DesktopStartupNotification.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DisplayLocalStartupTest.kt`
- Delete after migration: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayEnvironmentPolicy.kt`

**Interfaces:**
- Consumes: `PlacementSelection` from Task 2.
- Produces:

```kotlin
interface DesktopPlacementPort {
    fun launchLocal(request: DesktopLaunchRequest)
    fun launchOnDisplay(request: DesktopLaunchRequest)
    fun focusLocal(requestId: Long): Boolean
    fun focusTask(requestId: Long): Boolean
    fun cancelLocal(requestId: Long)
    fun cancelTask(requestId: Long)
}

interface DesktopPlacementStrategy {
    val selection: PlacementSelection
    val allowsManualMoves: Boolean
    val allowsAutomaticMoves: Boolean
    val environmentRestartWarning: String?

    fun environmentFor(display: DesktopDisplay): DisplayEnvironment
    fun requestedOrientation(
        display: DesktopDisplay,
        originalOrientation: Int,
    ): Int
    fun shouldAutoLaunchCurrentDisplay(
        display: DesktopDisplay,
        installed: Boolean,
    ): Boolean
    fun launch(port: DesktopPlacementPort, request: DesktopLaunchRequest)
    fun focus(port: DesktopPlacementPort, requestId: Long): Boolean
    fun cancel(port: DesktopPlacementPort, requestId: Long)
    fun requiresSessionRestart(
        active: DesktopStartupTarget?,
        next: DesktopStartupTarget,
    ): Boolean
    fun fallbackTarget(
        displays: List<DesktopDisplay>,
        previous: DesktopDisplay?,
        failed: DesktopDisplay,
    ): DesktopDisplay?
}

data class DesktopOpenRequest(
    val currentDisplayId: Int,
    val mode: String,
    val desktopEnv: String,
    val startSession: Boolean,
    val expectedEpoch: Long?,
)
```

- `MovableSecondaryDisplayAdapter` uses `ActivityOptions.setLaunchDisplayId`, permits manual moves, permits automatic moves only when `selection.allowAutomaticMoves`, never requires an environment restart, and may fall back to the previous/default display.
- `DisplayLocalRestartAdapter` launches locally in the current task, rejects manual and automatic moves, requires restart when the target environment differs, and never falls back to another display after placement failure.
- Move `DisplayEnvironment` and `DesktopStartupTarget` from `DisplayEnvironmentPolicy.kt` into `DisplayEnvironment.kt`. `DisplayLocalRestartAdapter.environmentFor` returns `TNT` only when `display.id` is in `selection.tntDisplayIds`; other non-default displays return `OTHER_EXTERNAL`.
- `DisplayLocalRestartAdapter.requestedOrientation` returns landscape only for `selection.tntDisplayIds`. Its `environmentRestartWarning` preserves the current save-work warning. The movable adapter returns the original orientation and no restart warning.
- `MovableSecondaryDisplayAdapter.shouldAutoLaunchCurrentDisplay` preserves the current installed generic-external auto-launch behavior. The display-local adapter returns false because launcher context owns its startup.

- [ ] **Step 1: Write failing adapter contract tests**

Add tests named:

- `movableAdapterLaunchesWithDisplayPlacementAndCanFallbackToPhone`
- `displayLocalAdapterLaunchesLocallyAndNeverFallsBack`
- `displayLocalAdapterRestartsOnlyWhenEnvironmentChanges`
- `tntQuirkRequestsLandscapeAndOtherExternalPreservesOrientation`
- `onlyDisplayLocalAdapterPublishesRestartWarning`
- `onlyMovableGenericExternalAutoLaunchesAnInstalledDesktop`
- `placementStrategyCanBeReplacedOnlyWhileStopped`
- `staleDisplayKeyCannotAttachAfterDisplayIdReuse`
- `disconnectKeepsDisplayLocalSessionHeadless`
- `genericDynamicDisplayIdDoesNotRestartLinux`

The display-ID reuse test must remove a display with key `old`, add a replacement with the same numeric ID and key `new`, and assert the old request is rejected.

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DesktopPlacementStrategyTest" --no-daemon --console=plain
```

Expected: compilation fails because the strategy and port do not exist.

- [ ] **Step 3: Move Android launch/focus/cancel mechanics behind `DesktopPlacementPort`**

Refactor `AndroidDesktopDisplayHost` so it exposes the port operations and delegates all placement variation to the selected strategy. Preserve existing SecurityException, IllegalArgumentException, and ActivityNotFoundException translations.

- [ ] **Step 4: Make `DesktopDisplayCoordinator` depend on `DesktopPlacementStrategy`**

Replace `environmentPolicy`, `isDisplayLocal`, and `allowCrossDisplayMoves` branches with strategy calls. Add:

```kotlin
val placementSelection: PlacementSelection
fun requestOpen(
    request: DesktopOpenRequest,
    startService: () -> Unit,
    completion: (Result<Unit>) -> Unit,
)
fun replacePlacementStrategy(strategy: DesktopPlacementStrategy)
```

`DesktopOpenRequest` carries current display ID, runtime mode, desktop environment, start-session flag, and optional expected epoch. Move the current `MainActivity.requestDesktop` placement branching into this coordinator method.

`replacePlacementStrategy` requires a stopped Linux session and no pending placement. It resets adapter-owned transient state and republishes the snapshot.

- [ ] **Step 5: Remove placement-mode branching from callers**

`MainActivity`, `DesktopActivity`, `DroidDeskService`, and `DesktopStartupNotification` may read a diagnostic placement mode or call a coordinator operation, but may not choose behavior with `if (displayLocal)`.

Move `externalInputRoute`, `usesRendererPointerCapture`, `shouldCaptureRelayedPointer`, `shouldFinishInputRelay`, and their enums unchanged into `display/input/ExternalInputPolicy.kt`. Move `shouldAutoLaunchExternalDesktop` into the placement adapter contract. Preserve `ExternalInputRelayPolicyTest`, `DesktopInputRoutingTest`, `ExternalInputRelayActivity`, and `ExternalDesktopInputBridge`.

- [ ] **Step 6: Update existing lifecycle tests to use strategy fixtures**

Rename R2-specific lifecycle test methods where the behavior is adapter-generic. Keep one selector test that maps R2 identity to the display-local adapter. Remove all direct `DisplayEnvironmentPolicy.forDevice("darwin")` calls from lifecycle tests.

- [ ] **Step 7: Run the display test suite**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "com.orailnoor.droiddesk.display.*" --no-daemon --console=plain
```

Expected: all display tests pass, including dynamic IDs, headless disconnect, fallback, and stale-key rejection.

- [ ] **Step 8: Delete `DisplayEnvironmentPolicy.kt` and verify no leaked identity**

```powershell
rg -n 'Build\.DEVICE|device == "darwin"|displayId == 100003|isDisplayLocal|displayLocalMode' app\android\app\src\main
```

Expected: OEM identity appears only in `DeviceQuirkCatalog`; no caller-owned display-local branch remains.

- [ ] **Step 9: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record the complete display and relay-policy suite result, and keep the placement migration uncommitted.

---

### Task 4: Extract a resolution-safe refresh policy

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/refresh/DisplayRefreshPolicy.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/refresh/DisplayRefreshPolicyTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`

**Interfaces:**
- Consumes: `DisplayModeEvidence` from Task 1.
- Produces:

```kotlin
data class RefreshRequest(
    val preferredRefreshRate: Float?,
    val preferredModeId: Int?,
    val reason: String,
)

object DisplayRefreshPolicy {
    fun resolve(
        current: DisplayModeEvidence?,
        supported: List<DisplayModeEvidence>,
        targetFps: Float = 60f,
    ): RefreshRequest
}
```

- Exact rules:
  - A mode ID is eligible only when width and height exactly equal the current mode.
  - Rates within `0.5f` of the target are equivalent.
  - If no exact-resolution target mode exists, set `preferredModeId = null`.
  - Set `preferredRefreshRate` to the lower of target FPS and the current display's maximum exact-resolution rate.
  - Empty or invalid mode evidence returns no request and an explanatory reason.

- [ ] **Step 1: Write failing refresh tests**

Add tests named:

- `selectsExactResolutionSixtyHertzMode`
- `acceptsFiftyNinePointNineFourHertz`
- `doesNotSelectLowerResolutionSixtyHertzModeForFourKOneTwenty`
- `doesNotRequestRateAboveDisplayCapability`
- `missingModeEvidenceReturnsNoRequest`

For the Review Focus case, provide current `3840 x 2160 @ 120`, supported `1920 x 1080 @ 60`, and assert `preferredModeId == null`.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DisplayRefreshPolicyTest" --no-daemon --console=plain
```

- [ ] **Step 3: Implement the pure policy and replace `applyProotRefreshPolicy` mode selection**

Keep PRoot as the only current caller. `DesktopActivity` converts Android `Display.Mode` values to evidence, applies the returned rate/mode, and logs the reason.

- [ ] **Step 4: Run focused tests and the Android unit suite**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --no-daemon --console=plain
```

Expected: all tests pass.

- [ ] **Step 5: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record refresh-policy and full Android test results, and keep the diff uncommitted.

---

### Task 5: Make input behavior capability-driven and pointer IDs sparse

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/DesktopInputPolicy.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/ExternalInputPolicy.kt`
- Create: `app/android/app/src/main/java/com/termux/x11/input/ActivePointerTracker.java`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/input/DesktopInputPolicyTest.kt`
- Create: `app/android/app/src/test/java/com/termux/x11/input/ActivePointerTrackerTest.java`
- Modify: `app/android/app/src/main/java/com/termux/x11/input/InputEventSender.java`
- Modify: `app/android/app/src/main/java/com/termux/x11/input/TouchInputHandler.java`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/x11/X11InputController.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/x11/ExternalDesktopInputBridge.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/ExternalInputRelayActivity.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/ExternalInputRelayPolicyTest.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/view/DesktopInputRoutingTest.kt`

**Interfaces:**
- Consumes: `PresentationClass` and `InputCapabilities`.
- Produces:

```kotlin
data class InputPolicyDecision(
    val defaultInputMode: Int,
    val allowDirectTouch: Boolean,
    val externalInputRoute: ExternalInputRoute,
    val rendererPointerCapture: Boolean,
    val relayPointerCapture: Boolean,
    val horizontalScrollScale: Float,
    val verticalScrollScale: Float,
    val reason: String,
)

object DesktopInputPolicy {
    fun resolve(
        presentationClass: PresentationClass,
        environment: DisplayEnvironment,
        capabilities: InputCapabilities,
        savedInputMode: Int?,
        routePreference: ExternalInputRoutePreference,
        desktopUiMode: Boolean,
        horizontalScrollFactor: Float,
        verticalScrollFactor: Float,
    ): InputPolicyDecision
}
```

```java
final class ActivePointerTracker {
    Set<Integer> update(Set<Integer> observedPointerIds);
    void begin(int pointerId);
    void end(int pointerId);
    Set<Integer> clear();
}
```

`update` returns IDs that were active before the update but are absent now.

- [ ] **Step 1: Write failing input-policy tests**

Add tests named:

- `externalPointerDefaultsToTrackpadAndRequestsCapture`
- `externalTouchWithMouseKeepsDirectTouchAndDoesNotForceCapture`
- `unknownTouchAssociationDoesNotMarkExternalDisplayAsDirectTouch`
- `unknownPointerAssociationUsesPhoneRelayOnGenericExternal`
- `targetAssociatedPointerUsesNativeRendererInput`
- `desktopUiModeKeepsNativeExternalInput`
- `explicitNativeAndPhoneRelayPreferencesOverrideAutomatic`
- `phoneAndTntAlwaysKeepNativeInput`
- `rendererAndRelayPointerCaptureAreMutuallyExclusive`
- `phoneTouchNeverRequestsPointerCapture`
- `savedSupportedModeWinsOverDefault`
- `unsupportedDirectTouchFallsBackToTrackpad`
- `scrollScalesUseAndroidHorizontalAndVerticalFactors`

- [ ] **Step 2: Write failing sparse-pointer tests**

Use pointer IDs `0`, `12`, and `31`. Assert updates do not throw, absent IDs are returned exactly once, and `clear` returns all remaining active IDs before clearing them.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DesktopInputPolicyTest" --tests "*ActivePointerTrackerTest" --no-daemon --console=plain
```

- [ ] **Step 4: Implement `ActivePointerTracker` and replace the ten-element array**

Do not clamp pointer IDs into a range. `InputEventSender` tracks the IDs reported by `MotionEvent` and emits `XI_TouchEnd` only for IDs returned by the tracker.

- [ ] **Step 5: Implement `DesktopInputPolicy` and preserve both input adapters**

Replace the current `externalInputRoute(...)` call and `pointerCapture.put(displayId != DEFAULT_DISPLAY && ...)` expression with one policy decision. Re-evaluate it when input devices change, the saved mode or route changes, the renderer moves, or direct touch is observed.

Use `PointerAssociationEvidence.UNKNOWN` as the pre-API-37 generic-external fallback that selects `PHONE_RELAY`, matching current behavior. Keep the existing desktop-UI and user-override rules. Allow `X11InputController` to promote touch evidence to `CURRENT_DISPLAY` after an actual direct-touch event without changing the separate mouse/touchpad relay decision.

Drive `ExternalDesktopInputBridge.attach/detach`, `ExternalInputRelayActivity.open/finishIfOpen`, renderer pointer capture, and relay pointer capture from `InputPolicyDecision`. Do not remove the relay adapter.

- [ ] **Step 6: Normalize wheel scaling**

Replace hard-coded `-100` wheel multipliers with the policy's horizontal and vertical scales derived from Android's matching scroll factors. Preserve sign and horizontal scrolling.

- [ ] **Step 7: Run input and full Android tests**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --no-daemon --console=plain
```

Expected: sparse IDs pass and existing Termux:X11 input behavior remains covered.

- [ ] **Step 8: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record sparse-pointer, relay-policy, routing, and full Android test results, and keep the diff uncommitted.

---

### Task 6: Replace heuristic profile branches with a pure presentation policy

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/policy/PresentationPolicy.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/policy/PresentationPolicyTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayProfile.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/XfceDisplayProfile.kt`
- Modify: `app/android/app/src/main/java/com/termux/x11/X11RenderGeometry.java`
- Create: `app/android/app/src/test/java/com/termux/x11/X11RenderGeometryTest.java`

**Interfaces:**
- Consumes: Task 1 evidence, Task 4 `RefreshRequest`, Task 5 `InputPolicyDecision`, and `RuntimeCapabilities`.
- Produces:

```kotlin
data class PresentationPlan(
    val profileKind: ProfileKind,
    val presentationClass: PresentationClass,
    val settings: DisplaySettings,
    val renderSize: PixelSize,
    val dpi: Int,
    val cursorPx: Int,
    val dockPx: Int,
    val autoMaximize: Boolean,
    val input: InputPolicyDecision,
    val refresh: RefreshRequest,
)

class PresentationPolicy(
    private val refreshPolicy: (
        DisplayModeEvidence?,
        List<DisplayModeEvidence>,
    ) -> RefreshRequest,
    private val inputPolicy: (
        PresentationClass,
        DisplayEnvironment,
        InputCapabilities,
        Int?,
        ExternalInputRoutePreference,
        Boolean,
        Float,
        Float,
    ) -> InputPolicyDecision,
) {
    fun classify(context: PresentationContext): PresentationClass

    fun resolve(
        context: PresentationContext,
        presentationClass: PresentationClass,
        environment: DisplayEnvironment,
        settings: DisplaySettings?,
        runtime: RuntimeCapabilities,
        horizontalScrollFactor: Float,
        verticalScrollFactor: Float,
    ): PresentationPlan
}
```

- Classification rules:
  - If the display is non-default, classify it as `EXTERNAL_TOUCH` only when `touch == CURRENT_DISPLAY`; otherwise classify it as `EXTERNAL_POINTER`.
  - If the display is default, compute current window width in dp as `widthPx * 160 / densityDpi` when density is known.
  - A default touch window narrower than `600 dp` is `PHONE_TOUCH`; a default touch window at least `600 dp` wide is `LARGE_TOUCH`.
  - When density is unknown, use `windowSizePx.width < 1200` as the phone fallback.
  - `ProfileKind` maps touch phone to `PHONE`, large touch to `WIDE`, and external classes to `MONITOR`.
- Automatic scale rules:
  - `PHONE_TOUCH` keeps legacy `140 DPI`, `32 px` cursor, `88 px` dock, and legacy rendering.
  - Other classes compute `rawPercent = densityDpi / 160f * fontScale * 100`, clamp to `100..200`, and select the nearest supported UI scale.
  - If density is unavailable, use current window dimensions as the existing fallback.
- Render rules:
  - Preserve an explicit saved render policy.
  - For a new external profile using software rendering and content larger than `2560 x 1440`, default to `CAP_1440`.
  - Otherwise preserve native rendering outside `PHONE_TOUCH`.

- [ ] **Step 1: Write failing presentation-policy tests**

Add tests named:

- `r2PhonePreservesLegacyPhoneProfile`
- `nubiaPhoneUsesCurrentWindowInsteadOfDeviceSmallestWidth`
- `foldableNarrowWindowClassifiesAsPhoneTouch`
- `expandedTabletWindowClassifiesAsLargeTouch`
- `externalTouchAndPointerDisplaysUseDifferentPresentationClasses`
- `softwareRenderedFourKDefaultsToCap1440WithoutOverridingSavedNative`
- `densityAndFontScaleChooseNearestSupportedScale`
- `renderAndInputGeometryUseTheSameContentSize`

- [ ] **Step 2: Write failing geometry tests**

Cover widths not divisible by eight and the existing `1366 x 768` compatibility case. Give the compatibility rule a named constant and assert no other `1360`-wide height is expanded to `1366`.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*PresentationPolicyTest" --tests "*X11RenderGeometryTest" --no-daemon --console=plain
```

- [ ] **Step 4: Implement the pure policy**

Move classification, automatic scaling, render-size selection, and profile construction out of `DesktopActivity` and `DisplayProfilePolicy`. Retain only value types and low-level helpers in `DisplayProfile.kt`.

- [ ] **Step 5: Integrate one measured context in `DesktopActivity`**

Build `PresentationContext` from the measured content bounds and evidence probe. Call `classify`, load the current legacy settings through the mapped `ProfileKind`, then call `resolve` with that exact class. Feed the returned plan to rendering, input, XFCE profile application, and diagnostics. Task 7 replaces the temporary legacy lookup with `DisplaySettingsKey`.

- [ ] **Step 6: Run Android tests**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --no-daemon --console=plain
```

Expected: all tests pass with no direct `smallestScreenWidthDp` classification in `DesktopActivity`.

- [ ] **Step 7: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record presentation-policy and geometry results, and keep the diff uncommitted.

---

### Task 7: Migrate display preferences onto presentation class and stable display identity

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplaySettingsStore.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DisplaySettingsStoreTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayRepository.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DisplaySourceMergeTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayCoordinator.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt`
- Modify: `app/lib/models/desktop_display.dart`
- Modify: `app/lib/services/platform_bridge.dart`
- Modify: `app/lib/state/app_state.dart`
- Modify: `app/lib/screens/display_settings_screen.dart`
- Modify: `app/test/models/desktop_display_test.dart`
- Create: `app/test/screens/display_settings_scope_test.dart`

**Interfaces:**
- Consumes: `PresentationClass` and the existing `DesktopDisplay.settingsKey`.
- Produces:

```kotlin
enum class DisplaySettingsScope(val wireName: String) {
    CLASS_DEFAULT("classDefault"),
    THIS_DISPLAY("thisDisplay"),
}

data class DisplaySettingsKey(
    val presentationClass: PresentationClass,
    val stableDisplayKey: String?,
)

fun DisplaySettingsStore.load(key: DisplaySettingsKey): DisplaySettings?
fun DisplaySettingsStore.save(
    key: DisplaySettingsKey,
    scope: DisplaySettingsScope,
    settings: DisplaySettings,
)
```

- Keep the current `stableDisplaySettingsKey` behavior:
  - default display key `default`;
  - API 31+ manufacturer PnP ID, product ID, and name;
  - older/unknown display name fallback;
  - connection-scoped suffixes for additional simultaneous identical monitors;
  - no resolution in the identity.
- Load precedence: exact stable-display override, presentation-class default, schema-v1 fallback, policy default.
- Schema-v1 migration:
  - `phone.*` -> `PHONE_TOUCH` class default;
  - `wide.*` -> `LARGE_TOUCH` class default;
  - `monitor.*` -> both external class defaults;
  - existing `display.<settingsKey>.inputRoute` remains a lazy fallback and migrates when that display/class is next observed.
- Migration is idempotent and commits schema-v2 values atomically before removing any legacy value.

- [ ] **Step 1: Extend existing stable-identity tests**

Keep all `DisplaySourceMergeTest` cases and add:

- `stableSettingsKeyIsIndependentOfDisplayMode`
- `connectionKeyChangesDoNotChangeTheStableSettingsKey`
- `simultaneousIdenticalMonitorsCannotOverwriteEachOther`
- `presentationEvidenceUsesTheRepositorySettingsKey`

- [ ] **Step 2: Write failing settings migration tests**

Add tests named:

- `migratesPhoneWideAndMonitorSettingsToSchemaTwo`
- `monitorMigrationSeedsPointerAndTouchClassDefaults`
- `legacyPerDisplayInputRouteRemainsReadable`
- `legacyPerDisplayInputRouteMigratesWhenClassIsKnown`
- `exactDisplayOverrideWinsOverClassDefault`
- `classDefaultSurvivesConnectionIdChange`
- `failedSchemaTwoCommitLeavesSchemaOneReadable`
- `migrationCanRunTwiceWithoutChangingValues`

- [ ] **Step 3: Run native focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DisplaySourceMergeTest" --tests "*DisplaySettingsStoreTest" --no-daemon --console=plain
```

- [ ] **Step 4: Implement schema-v2 storage on the existing identity**

Do not introduce a second fingerprint or persist numeric Android display IDs. Extend `DisplayPreferences` only as needed for atomic migration and legacy-key enumeration.

- [ ] **Step 5: Extend the native wire contract**

Add `presentationClass`, `stableDisplayKey`, and `settingsScope` to settings payloads. Keep transient `displayKey` for selecting the current connection and keep `inputRoute` in `DisplaySettings`.

When loading a selected but inactive display, call `AndroidPresentationEvidenceProbe.presentationContext(display.id)`, classify it through `PresentationPolicy.classify`, and build `DisplaySettingsKey` from that class plus `display.settingsKey`. For the active renderer, use the measured class from the current `PresentationPlan`.

- [ ] **Step 6: Update Flutter models and settings UI**

The screen must:

- show the presentation class;
- offer `This display` and `All displays of this type`;
- default to `This display` when a stable identity is available;
- preserve the existing external-input-route control;
- remove the statement that render, scale, and input-mode settings always apply to all monitors;
- explain class-default fallback when exact display identity is unavailable.

- [ ] **Step 7: Run Flutter tests and verify GREEN**

```powershell
Push-Location app
flutter test test\models\desktop_display_test.dart test\screens\display_settings_scope_test.dart test\screens\display_settings_input_route_test.dart
Pop-Location
```

Expected: payload parsing, scope selection, stable identity, input-route preservation, and copy pass.

- [ ] **Step 8: Run full native and Flutter tests**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --no-daemon --console=plain
Push-Location app
flutter test
Pop-Location
```

- [ ] **Step 9: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record display-source, settings migration, and Flutter results, and keep the diff uncommitted.

---

### Task 8: Replace the fixed startup delay with an event-driven readiness gate

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/readiness/DesktopReadinessGate.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/readiness/DesktopReadinessGateTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/java/com/termux/x11/LorieView.java`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/x11/X11ServiceClient.kt`

**Interfaces:**
- Produces:

```kotlin
enum class ReadinessSignal {
    RENDERER_CLAIMED,
    SURFACE_READY,
    X11_CONNECTED,
    GEOMETRY_COMMITTED,
}

class DesktopReadinessGate(
    private val onReady: (generation: Long) -> Unit,
) {
    fun reset(generation: Long)
    fun signal(generation: Long, signal: ReadinessSignal)
    fun cancel(generation: Long)
}
```

- Completion fires exactly once after all four signals for the current generation.
- Duplicate and stale signals are ignored.
- `reset` invalidates every prior generation.

- [ ] **Step 1: Write failing readiness tests**

Add tests named:

- `signalsMayArriveInAnyOrder`
- `duplicateSignalsCompleteOnce`
- `staleGenerationCannotCompleteCurrentGate`
- `resetCancelsPartiallyReadyGeneration`
- `slowSignalsDoNotRequireTimer`

For Review Focus, deliver all permutations of the four signals and assert one completion per generation.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DesktopReadinessGateTest" --no-daemon --console=plain
```

- [ ] **Step 3: Implement the gate**

Use a generation plus `EnumSet<ReadinessSignal>`. Do not use a Handler, sleep, or timeout inside this module.

- [ ] **Step 4: Wire readiness signals**

- `RENDERER_CLAIMED`: after the coordinator lease is current.
- `SURFACE_READY`: on valid `surfaceChanged`.
- `X11_CONNECTED`: after the X11 FD attaches successfully.
- `GEOMETRY_COMMITTED`: after non-zero render size and content insets are applied.

Replace the `postDelayed(..., 250)` startup path with `onReady`.

- [ ] **Step 5: Run readiness, session-transfer, and display tests**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*DesktopReadinessGateTest" --tests "*SessionStartTransferTest" --tests "*DisplayLocalStartupTest" --no-daemon --console=plain
```

Expected: all pass and `rg -n '\}, 250\)' DesktopActivity.kt` returns no match.

- [ ] **Step 6: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record readiness, session-transfer, display, and relay tests, and keep the diff uncommitted.

---

### Task 9: Make runtime probes and host audio adaptive

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/ProbeTimeouts.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/HostAudioAdapter.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/HostAudioPreferenceStore.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/HostAudioAdapterTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/RuntimeCapabilityProbe.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/RuntimeCapabilityProbeTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/LinuxRuntime.kt`
- Replace or rewrite: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/LinuxRuntimeAudioTest.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/PulseAudioStartupTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class ProbeTimeouts(
    val localExecutionMs: Long = 1_000,
    val prootMs: Long = 2_000,
    val x11Ms: Long = 1_500,
    val audioMs: Long = 2_500,
    val clipboardMs: Long = 1_000,
    val chrootMs: Long = 5_000,
    val rendererMs: Long = 2_000,
)

interface HostAudioPort {
    fun startWithLibraryPath(libraryPath: String): Result<Unit>
    fun stopAfterFailedStart(): Result<Unit>
}

data class VerifiedAudioPath(
    val appVersionCode: Long,
    val libraryPath: String,
)

interface HostAudioPreferenceStore {
    fun load(): VerifiedAudioPath?
    fun save(value: VerifiedAudioPath)
    fun clear()
}

class HostAudioAdapter(
    private val port: HostAudioPort,
    private val preferences: HostAudioPreferenceStore,
) {
    fun start(
        sdkInt: Int,
        appVersionCode: Long,
        prefixLibraryPath: String,
    ): Result<String>
}
```

- Candidate order:
  - API 30 and older: `/system/lib64:$prefix`, then `$prefix`.
  - API 31 and newer: `$prefix`, then `/system/lib64:$prefix`.
- A cached candidate is tried first only when the cached app version matches.
- Every failed candidate performs bounded cleanup before the next candidate.
- Success returns the verified library path.

- [ ] **Step 1: Write failing host-audio tests**

Add tests named:

- `androidElevenPrefersSystemPathButFallsBackToPrefix`
- `newAndroidPrefersPrefixButCanFallBackToSystemPath`
- `cachedVerifiedPathRunsFirstForSameAppVersion`
- `appUpgradeInvalidatesCachedPath`
- `failedCandidateIsCleanedBeforeFallback`
- `allFailuresPreserveSuppressedEvidence`

- [ ] **Step 2: Write failing per-probe timeout tests**

Assert a slow chroot probe does not consume the audio budget, each timed-out capability names its own budget, and completed probes are preserved.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "*HostAudioAdapterTest" --tests "*RuntimeCapabilityProbeTest" --no-daemon --console=plain
```

- [ ] **Step 4: Implement adaptive audio selection**

Move host PulseAudio process start/cleanup behind `HostAudioPort`. Remove `hostPulseAudioLibraryPath` as the final decision function; it may remain only as candidate ordering logic.

Preserve `pulseAudioTcpPort` and `startPulseAudioDaemonWithFallback`: each library-path candidate still tries AAudio first, cleans up, and then tries the plain PulseAudio compatibility daemon before the adapter advances to another library path.

- [ ] **Step 5: Implement per-capability timeout budgets**

Launch all probes concurrently. Track each future's start deadline independently so one slow probe cannot consume another probe's budget. Log cache refresh failures instead of silently retaining stale evidence.

- [ ] **Step 6: Run runtime and full Android tests**

```powershell
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --tests "com.orailnoor.droiddesk.runtime.*" --no-daemon --console=plain
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --no-daemon --console=plain
```

- [ ] **Step 7: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record host-audio, PulseAudio fallback, runtime-probe, and full Android results, and keep the diff uncommitted.

---

### Task 10: Publish compatibility diagnostics through the native and Flutter state

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayCoordinator.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt`
- Modify: `app/lib/models/desktop_display.dart`
- Modify: `app/lib/services/platform_bridge.dart`
- Modify: `app/lib/state/app_state.dart`
- Modify: `app/lib/screens/display_settings_screen.dart`
- Modify: `app/test/models/desktop_display_test.dart`
- Create: `app/test/screens/display_compatibility_diagnostics_test.dart`

**Interfaces:**
- Consumes: placement selection, presentation plan, input decision, refresh request, and runtime capability evidence.
- Produces native snapshot schema version 2 with:

```text
placementMode
placementSelectionSource
placementReason
automaticMovesAllowed
placementOverride
presentationClass
stableDisplayKey
inputCapabilities
externalInputRoute
rendererPointerCaptureRequested
relayPointerCaptureRequested
refreshPreferredRate
refreshPreferredModeId
refreshReason
renderWidth
renderHeight
profileKind
runtimeCapabilities
```

- `inputCapabilities` is a map containing `touchEvidence`, `pointerAssociation`, `hasMouse`, `hasTouchpad`, `hasKeyboard`, and `hasStylus`.
- `runtimeCapabilities` reuses the existing `RuntimeCapabilities.toMap()` payload rather than creating a second evidence vocabulary.
- Flutter produces typed equivalents, a read-only `Compatibility details` section, and a placement preference control.
- The placement preference control has `Automatic`, `Move existing desktop view`, and `Restart Linux on current display`. It invokes:

```dart
static Future<DesktopDisplayState> setDesktopPlacementOverride(
  DesktopPlacementMode? mode,
)
```

`null` means Automatic.

- [ ] **Step 1: Write failing Dart parsing tests**

Test a complete schema-v2 payload, nullable refresh mode, runtime-capability parsing, unknown enum rejection, and inconsistent placement state rejection.

- [ ] **Step 2: Write failing diagnostics widget tests**

Assert the screen shows:

- `Movable`, `Display-local restart`, or `Safe default`.
- Selection source and reason.
- Presentation class.
- Touch/mouse/keyboard capabilities.
- Pointer association, selected external-input route, and which surface owns pointer capture.
- Requested refresh rate and whether a physical mode ID is used.
- A visible warning when the safe default disabled automatic moves.
- The three placement preference choices and the selected override.
- The placement preference control is disabled while a Linux session is running.

- [ ] **Step 3: Run focused Flutter tests and verify RED**

```powershell
Push-Location app
flutter test test\models\desktop_display_test.dart test\screens\display_compatibility_diagnostics_test.dart
Pop-Location
```

- [ ] **Step 4: Publish schema-v2 native state**

Build the map from existing selected module outputs; do not recompute policy inside serialization.

- [ ] **Step 5: Implement typed Dart models and UI**

Remove user-facing text that assumes every display-local environment is TNT. Keep an R2-specific label only when the diagnostic environment is explicitly TNT.

Add the MethodChannel handler and `AppState` operation for changing the override. Reject the operation with `INVALID_ARGUMENT` while a Linux session is running. After saving, run the selector again and call `replacePlacementStrategy`; the next desktop start uses the new adapter.

- [ ] **Step 6: Run all Flutter and Android tests**

```powershell
Push-Location app
flutter test
Pop-Location
& .\app\android\gradlew.bat -p .\app\android testDebugUnitTest --no-daemon --console=plain
```

- [ ] **Step 7: Prepare the uncommitted review checkpoint**

Run `git diff --check`, record schema-v2 parsing and diagnostics widget results, and keep the diff uncommitted.

---

### Task 11: Extend the existing quality gate and prepare the physical-test handoff

**Files:**
- Create: `tools/check_device_compatibility_architecture.py`
- Create: `tools/test_check_device_compatibility_architecture.py`
- Modify: `.github/workflows/quality.yml`
- Create: `docs/testing/multi-device-compatibility-acceptance.md`

**Interfaces:**
- The compatibility guard rejects OEM identity leakage, display-local branching leakage, misplaced input/placement policy, the fixed pointer array, the 250 ms startup delay, direct refresh-mode selection, and `smallestScreenWidthDp` profile classification.
- The existing Quality workflow retains Flutter tests, the debug APK build, Android tests, and the package-store guard, then runs the new compatibility guard.
- The physical-test document defines the later R2 phone/TNT and NX888J phone/HDMI matrix but does not execute it during P00-P22.

- [ ] **Step 1: Implement and verify the compatibility architecture guard**

Run its `unittest` fixtures and the script against the repository.

- [ ] **Step 2: Extend the reusable Quality workflow**

Keep all existing verification and add the compatibility guard commands.

- [ ] **Step 3: Create the post-`ref/v1` acceptance checklist**

Record the required one-APK SHA-256 evidence, placement diagnostics, presentation class, input route, refresh request, lifecycle, audio, clipboard, and cleanup checks for all four device paths.

- [ ] **Step 4: Finish through the P22 PR workflow**

Use the tracked P00-P22 execution plan: independent pre-commit review, PR to `ref/v1`, Quality, GitHub Copilot Balance review, review-fix loop, and merge commit.

- [ ] **Step 5: Stop after all P00-P22 PRs merge**

Do not open `ref/v1 -> xixia`. Notify the user with the final `ref/v1` SHA and acceptance checklist so physical testing can begin.

---

## `ref/v1` Completion Checklist

- [ ] Android unit tests pass on every affected PR and P22.
- [ ] Flutter tests pass on every affected PR and P22.
- [ ] The debug ARM64 APK builds in Quality.
- [ ] The compatibility and package-store architecture guards pass.
- [ ] Unknown/ambiguous placement fixtures report safe default and support override.
- [ ] External touch does not force pointer capture.
- [ ] 4K120 fixtures never select a lower-resolution 60 Hz mode.
- [ ] Sparse pointer IDs pass.
- [ ] No production model condition exists outside `DeviceQuirkCatalog`.
- [ ] Every P00-P22 PR targets and merges into `ref/v1` with a merge commit.
- [ ] No `ref/v1 -> xixia` PR exists.
- [ ] The user has been notified that R2 phone/TNT and NX888J phone/HDMI testing is ready to begin.
