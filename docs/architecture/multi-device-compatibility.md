# Multi-Device Compatibility Architecture

## Status

Re-evaluated against `origin/xixia` commit `e8884c2` on 2026-11-08.

The capability-driven target remains valid. The latest code adds three foundations
that this specification must preserve rather than replace:

- merged Android and presentation-display discovery with transient connection keys;
- stable per-display settings identity plus a phone-side external-input relay;
- a reusable quality workflow that already gates personal releases.

## Goal

Replace device-by-device compatibility patches with a capability-driven architecture that preserves the verified Smartisan R2/TNT and Nubia NX888J behaviors while giving unknown Android devices a safe, testable fallback.

## Problem Statement

The current implementation contains two different desktop-placement behaviors:

- Standard Android secondary-display placement, verified on Nubia NX888J.
- Display-local launcher behavior that requires a Linux/X11/XFCE restart when switching between phone and TNT, verified on Smartisan R2.

Those are real behavioral variants, but the code currently selects them through `Build.DEVICE == "darwin"` and exposes `isDisplayLocal` throughout activities, the display coordinator, the Android display host, the foreground service, and notifications. Display scaling, refresh-rate selection, pointer capture, and startup readiness also contain fixed constants or timing assumptions derived from the two tested devices.

Since the original analysis, `DisplayEnvironmentPolicy.kt` has also accumulated
external-input routing, relay lifecycle helpers, and generic external auto-launch
rules. Those additions solve real device problems, but make the module shallower:
placement, input routing, OEM quirks, and startup policy now change together.

The refactor must turn these variants into explicit capabilities, policies, and adapters. Adding another device must primarily add evidence fixtures or an isolated quirk entry, not branches throughout the application.

## Global Constraints

- Preserve one APK, one Linux session, and one active X11 view.
- Preserve the current runtime modes: Native Termux, Debian PRoot, and rooted Ubuntu chroot.
- Preserve ARM64-only support, `minSdk = 28`, and `targetSdk = 28`.
- Preserve the current XFCE behavior; desktop-environment selection is outside this refactor.
- Preserve R2 phone/TNT restart behavior, including headless state after TNT disconnect.
- Preserve generic Android secondary-display behavior, including dynamic display IDs and moving the view without restarting Linux.
- Preserve merged primary/presentation display discovery and the distinction between transient connection keys and stable `DesktopDisplay.settingsKey` values.
- Preserve `ExternalInputRoutePreference`, `ExternalInputRelayActivity`, `ExternalDesktopInputBridge`, and automatic generic-external startup until their behavior is moved behind dedicated interfaces.
- Preserve the existing package-store architecture guard when extending the quality workflow.
- Do not add a new third-party runtime dependency.
- Do not require hardware acceleration.
- Do not rely on fixed delays for correctness.
- Do not reference an OEM model, codename, or fixed OEM display ID outside the isolated device-quirk module and test fixtures.
- Unknown devices must receive a safe default and a user-selectable placement override.
- Existing display settings must migrate without silently discarding user choices.
- User-directed delivery exception for this refactor: implementation is merged incrementally through reviewed PRs into `ref/v1` before physical-device validation. No implementation PR targets `xixia`.
- After every planned PR is merged into `ref/v1`, stop and notify the user. Do not create a `ref/v1 -> xixia` PR; R2 phone/TNT and NX888J phone/HDMI validation happens next under user coordination.
- When physical validation starts, use one identical APK for all four paths. Keep R2/Nubia wireless ADB enabled only for the designated validation and review cycle, use paired wireless debugging on a trusted private network, keep both devices off untrusted networks while it is enabled, and disable wireless ADB immediately after the final changes are merged.

## Non-Goals

- Simultaneous Linux sessions on multiple displays.
- Seamless preservation of unsaved Linux application memory across a display-local environment restart.
- GNOME, Phosh, Wayland, or another desktop environment.
- Raising the Android target SDK.
- Replacing Termux:X11 rendering.
- Automatically changing Android media volume or display-speaker volume.

## Required Invariants

1. A Linux session has exactly one active renderer owner.
2. A stale renderer, display request, readiness signal, or session generation cannot mutate the current session.
3. Generic secondary-display placement never restarts Linux merely because the active Android Activity changes display.
4. Display-local placement restarts Linux only after an explicit launch from a different display environment.
5. Disconnecting a display never silently destroys a running Linux session.
6. A failed placement or restart reports the original failure and leaves truthful session metadata.
7. Render geometry and input transforms derive from the same measured content bounds.
8. A display-mode request never changes physical resolution merely to obtain 60 Hz.
9. Pointer IDs are treated as sparse Android identifiers, not array indexes with an assumed maximum of nine.
10. Release publication cannot proceed unless Android unit tests, Flutter tests, and architecture guards pass.

## Architecture

### 1. Capability evidence

Introduce immutable evidence types with no Android framework dependencies:

- `DisplayModeEvidence`
- `TouchEvidence`
- `PointerAssociationEvidence`
- `InputCapabilities`
- `DisplayPlacementEvidence`
- `PresentationContext`
- `PresentationClass`

`AndroidPresentationEvidenceProbe` is the Android adapter that reads `Display`, public display flags and modes, `WindowMetrics`, `Configuration`, `InputManager`, package-manager features, and placement permission checks. Tests construct evidence directly without Android devices. A density value of zero means unknown and activates the documented window-size fallback.

Touch evidence is scoped to the presentation display:

- `CURRENT_DISPLAY`: Android associates a touchscreen with the active display, or a direct-touch event has been observed on that view.
- `NONE`: no touchscreen evidence exists for the active display.
- `UNKNOWN_ASSOCIATION`: touch exists but Android cannot prove which display owns it.

An external display is classified as touch-capable only for `CURRENT_DISPLAY`. A phone touchscreen must not make an unrelated HDMI display look touch-capable.

Pointer-association evidence preserves the behavior already implemented by the
phone relay:

- `NO_EXTERNAL_POINTER`
- `TARGET_DISPLAY`
- `DEFAULT_OR_UNASSOCIATED`
- `OTHER_DISPLAY`
- `UNKNOWN`

The Android adapter may use the associated-display API when available. On older
platforms it reports `UNKNOWN`; policy, not the probe, decides whether the relay
is the safe fallback.

The evidence records facts only. It must not decide policy.

### 2. Placement strategy seam

Create a real `DesktopPlacementStrategy` seam with two adapters:

- `MovableSecondaryDisplayAdapter`
- `DisplayLocalRestartAdapter`

`DesktopPlacementSelector` chooses an adapter using this order:

1. Explicit user override.
2. Conclusive runtime placement evidence.
3. An isolated `DeviceQuirkCatalog` fallback.
4. Safe generic default: movable secondary-display behavior with automatic moves disabled until the user selects an override or a later runtime probe becomes conclusive.

Only `DeviceQuirkCatalog` may reference `darwin`, Smartisan identity fields, or TNT display ID `100003`. The catalog returns quirk metadata together with placement mode so the selected adapter can preserve the R2-specific `TNT` environment label without spreading the fixed display ID.

The selected adapter owns:

- Whether manual cross-display moves are permitted.
- How an Activity is launched or focused.
- Whether a target-environment change requires a Linux restart.
- Whether placement failure may fall back to the phone.
- How display disconnect is represented.
- Display-specific orientation and restart-warning behavior.
- Whether an installed desktop should auto-open when the app itself launches on the current external display.

Callers receive placement state and operations through the coordinator interface; they do not branch on `isDisplayLocal`.

### 3. Presentation policy

`PresentationPolicy` is a pure deep module:

```kotlin
fun classify(context: PresentationContext): PresentationClass

fun resolve(
    context: PresentationContext,
    presentationClass: PresentationClass,
    settings: DisplaySettings?,
    runtime: RuntimeCapabilities,
): PresentationPlan
```

`PresentationPlan` contains:

- `profileKind`
- `presentationClass`
- `renderPolicy`
- `renderSize`
- `dpi`
- `cursorPx`
- `dockPx`
- `autoMaximize`
- `inputPolicy`
- `refreshRequest`

The policy uses the current window content bounds, density, font scale, input capabilities, current/supported display modes, and runtime renderer evidence. It does not use a device model.

`ProfileKind` remains the XFCE layout concept (`PHONE`, `WIDE`, `MONITOR`). `PresentationClass` becomes the compatibility and preference concept:

- `PHONE_TOUCH`
- `LARGE_TOUCH`
- `EXTERNAL_POINTER`
- `EXTERNAL_TOUCH`

### 4. Settings identity and migration

Display connection keys remain transient request identities. Persisted settings use:

```kotlin
data class DisplaySettingsKey(
    val presentationClass: PresentationClass,
    val stableDisplayKey: String?,
)
```

`stableDisplayKey` reuses the current `DesktopDisplay.settingsKey` contract:

- the default display uses `default`;
- API 31+ external displays prefer manufacturer PnP ID, product ID, and name;
- older/unknown displays fall back to the normalized display name;
- simultaneous identical monitors keep one stable product key while additional
  connections receive a connection-scoped suffix to prevent settings overwrite.

Resolution is intentionally excluded so mode changes do not discard settings.
Loading follows:

1. Exact stable-display override.
2. Presentation-class default.
3. Migrated schema-v1 profile.
4. `PresentationPolicy` default.

Schema-v1 migration maps:

- `phone` to `PHONE_TOUCH`
- `wide` to `LARGE_TOUCH`
- `monitor` to both `EXTERNAL_POINTER` and `EXTERNAL_TOUCH`

The migration is idempotent and keeps schema-v1 keys until schema-v2 is committed successfully. Existing `display.<settingsKey>.inputRoute` values remain a lazy fallback and migrate when the corresponding display identity and presentation class are next observed.

### 5. Refresh policy

`DisplayRefreshPolicy` is pure. For a 60 Hz request:

- Select a mode ID only when width and height exactly match the current physical mode.
- Accept fractional rates within 0.5 Hz, including 59.94 Hz.
- If no exact-resolution mode exists, request only a frame rate and leave mode ID unset.
- Never request a rate higher than the display can provide.
- Permit a future user preference without changing the interface.

### 6. Input policy

`DesktopInputPolicy` resolves:

- Default input mode.
- Pointer-capture eligibility.
- Direct-touch availability.
- Touchpad and mouse routing.
- Scroll scaling.
- Native renderer input versus phone-relay routing.

Pointer capture depends on active input capabilities and user mode, not `displayId != 0`.

`ActivePointerTracker` stores sparse pointer IDs and emits ended pointer IDs without assuming a fixed range.

The policy returns separate renderer and relay capture decisions. Direct touch may
remain native while mouse/touchpad events use the phone relay; these channels must
not be collapsed into one boolean.

### 7. Event-driven readiness

`DesktopReadinessGate` replaces the 250 ms startup delay. It starts the Linux desktop only after the current generation has observed:

- Renderer claimed.
- Surface ready.
- X11 connected.
- Non-zero geometry committed.

Signals may arrive in any order, may repeat, and may belong to a stale generation. Completion fires exactly once for the current generation.

Timeouts remain failure guards, not ordering mechanisms.

### 8. Runtime adapter selection

API level may order candidates but cannot be the only evidence for host-audio compatibility.

`HostAudioAdapter` attempts bounded candidate library paths, records the first verified path, and reuses it until the application version changes or the probe fails. `RuntimeCapabilityProbe` receives per-capability timeout budgets rather than one shared one-second deadline.

### 9. Diagnostics

The native snapshot schema exposes:

- Selected placement mode and selection source.
- Relevant placement evidence.
- Presentation class and stable display-settings identity.
- Input capabilities, pointer-association evidence, selected external-input route,
  and renderer/relay pointer-capture decisions.
- Refresh request and selected mode ID.
- Render size and UI profile.
- Runtime capability evidence.

Diagnostics must explain a fallback without exposing sensitive device data beyond the existing model, brand, API level, and public display/input characteristics.

The display settings UI also exposes a placement preference with three values:

- Automatic
- Move the existing desktop view
- Restart Linux on the current display

The explicit values map to the selector's user override and Automatic removes it.

The override is editable only while the Linux session is stopped. Saving it rebuilds the selected placement adapter for the next desktop start; a running session never changes adapter semantics in place.

## Compatibility Matrix

| Scenario | Expected placement | Presentation class | Key assertions |
|---|---|---|---|
| R2 phone, API 30 | Display-local | `PHONE_TOUCH` | Phone environment, no cross-display move |
| R2 TNT, display 100003 | Display-local | `EXTERNAL_POINTER` | Landscape, explicit environment restart, no phone fallback |
| R2 TNT unplug | Display-local | Preserved | Session remains running and headless |
| NX888J phone, API 36 | Movable | `PHONE_TOUCH` | Existing session resumes |
| NX888J HDMI, dynamic ID | Movable | `EXTERNAL_POINTER` | View moves without Linux restart |
| External touch monitor | Movable | `EXTERNAL_TOUCH` | No forced pointer capture, direct touch available |
| Foldable narrow window | Movable | `PHONE_TOUCH` | Classification uses current window bounds |
| 4K 120 Hz without 4K 60 Hz | Either | Capability-derived | No resolution-changing mode request |
| Sparse pointer IDs 0, 12, 31 | Either | Any touch class | No array bounds failure; ended pointers emitted correctly |
| Unknown OEM with ambiguous placement | User/default selected | Capability-derived | Safe default, diagnostic reason, override available |

## Rollout

1. Add pure evidence types and tests without changing behavior.
2. Introduce selector and adapters while retaining the current R2 quirk.
3. Move profile, refresh, and input decisions into pure policies.
4. Migrate settings and Flutter state.
5. Replace fixed readiness timing.
6. Add adaptive runtime probes and diagnostics.
7. Extend the existing quality workflow with compatibility architecture guards.
8. Re-run the R2 and NX888J physical acceptance matrices before removing compatibility shims.

## Exit Criteria

- Main production code references `darwin` and `100003` only in `DeviceQuirkCatalog`.
- No caller outside the placement module branches on display-local mode.
- No correctness path depends on the 250 ms startup delay.
- Pointer IDs greater than nine pass unit tests.
- Profile, refresh, input, placement, readiness, and settings migration have pure unit-test matrices.
- Personal release waits for Android tests, Flutter tests, and architecture guards.
- The existing package-store architecture guard remains active.
- R2 phone/TNT and NX888J phone/HDMI acceptance pass on the same commit.
