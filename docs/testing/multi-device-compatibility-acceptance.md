# Multi-Device Compatibility Physical Acceptance Checklist (`ref/v1`)

Status: **Pending post-`ref/v1` physical validation handoff** (not executed during P00–P22 implementation).

Specification: `docs/architecture/multi-device-compatibility.md`

Baseline device acceptance records:
- `docs/testing/debian-proot-r2-acceptance.md`
- `docs/testing/debian-proot-nx888j-acceptance.md`

---

## 1. Single-APK Gate and Provenance

All four physical paths MUST be executed with one identical APK built from the final `ref/v1` head after P22 merges. No intermediate rebuild, Gradle re-invocation, or per-device patch is permitted between paths.

| Provenance field | Required check / command | Recorded value |
|---|---|---|
| `ref/v1` commit SHA | `git rev-parse ref/v1` (matches `origin/ref/v1`) | _Pending_ |
| Build command | `flutter build apk --debug --no-pub --target-platform android-arm64` (from `app/`, or signed personal release candidate) | _Pending_ |
| APK path | `app/build/app/outputs/flutter-apk/app-debug.apk` (or candidate path) | _Pending_ |
| Host APK `SHA-256` | `Get-FileHash -Algorithm SHA256 <apk>` / `sha256sum <apk>` | _Pending_ |
| Installed APK `SHA-256` on Smartisan R2 | `adb -s <r2> shell sha256sum "$(adb -s <r2> shell pm path com.orailnoor.droiddesk \| cut -d: -f2)"` matches host `SHA-256` before Phone and TNT passes | _Pending_ |
| Installed APK `SHA-256` on Nubia NX888J | `adb -s <nx888j> shell sha256sum "$(adb -s <nx888j> shell pm path com.orailnoor.droiddesk \| cut -d: -f2)"` matches host `SHA-256` before Phone and HDMI passes | _Pending_ |

---

## 2. Wireless ADB and Network Safety Protocol

- Pair wireless debugging (`adb pair` / `adb connect`) **only** on a trusted private network; keep both Smartisan R2 and Nubia NX888J disconnected from untrusted networks for the entire wireless-debugging window.
- Keep wireless ADB enabled across both devices throughout the designated validation and review cycle rather than disabling and re-enabling ADB after each individual test pass.
- Immediately after final sign-off and merge completion, disable wireless debugging / wireless ADB on **both** Smartisan R2 and Nubia NX888J and record completion below.

| Safety step | Smartisan R2 (`darwin`) | Nubia NX888J (`PQ85A02`) |
|---|---|---|
| Trusted private network verified before enabling wireless ADB | [ ] Pending | [ ] Pending |
| Wireless ADB kept stable across validation/review passes | [ ] Pending | [ ] Pending |
| Wireless ADB disabled immediately after final merge | [ ] Pending | [ ] Pending |

---

## 3. Four-Path Physical Acceptance Matrix

### Path 1 — Smartisan R2 (`darwin`, Android 11 / API 30) — Phone

| Check | Expected schema-2 / runtime behavior | Status | Notes / evidence |
|---|---|---|---|
| Viewport & placement | `1080 × 2340` internal display; `placementMode = displayLocal` (`Effective placement: Display-local restart`), `placementSelectionSource = deviceQuirk` (`Selection source: Device quirk`), `automaticMovesAllowed = false`, `crossDisplayMovesAllowed = false` | [ ] Pending | |
| Presentation & identity | `presentationClass = phoneTouch` (`Active presentation: Phone touch`), `stableDisplayKey = default` (`Display identity: default`), `sessionEnvironment = phone` | [ ] Pending | |
| Legacy phone profile | `profileKind = phone`, `renderPolicy = legacyPhone`, `140 DPI`, `32 px` cursor, `88 px` dock, `autoMaximize = true` | [ ] Pending | |
| Refresh request | `refreshPreferredRate = 60` Hz with exact-resolution `refreshPreferredModeId > 0` (`60 Hz · Mode ID <id>`) matching `1080 × 2340` | [ ] Pending | |
| Event-driven startup | `DesktopReadinessGate` reveals XFCE after renderer claim, surface ready, X11 connection, and non-zero geometry without a fixed 250 ms startup delay | [ ] Pending | |
| Host audio | Cookie-authenticated loopback TCP PulseAudio connects via `HostAudioAdapter` verified library path (`OpenSL_ES_sink` on API 30) and plays stereo PCM | [ ] Pending | |
| Foreground clipboard | Plain-text copy/paste round-trips cleanly `Android -> X11` and `X11 -> Android` while foregrounded | [ ] Pending | |
| Lifecycle & cleanup | Home background/resume preserves running XFCE session; **Stop Server** terminates PRoot, XFCE, `dbus-daemon`, PulseAudio, and foreground services cleanly | [ ] Pending | |

### Path 2 — Smartisan R2 (`darwin`, API 30) — TNT (`displayId = 100003`)

| Check | Expected schema-2 / runtime behavior | Status | Notes / evidence |
|---|---|---|---|
| Placement & environment | `placementMode = displayLocal` (`Display-local restart`), `sessionEnvironment = tnt`, `sessionDisplayId = 100003`, `presentationClass = externalPointer` (`External pointer`), landscape orientation locked | [ ] Pending | |
| Restart warning & switch | Switching from a running phone session displays the explicit environment-restart warning (`Opening DroidDesk in another display restarts Linux/XFCE. Save unsaved work first.`) and cleanly restarts Linux/XFCE onto TNT | [ ] Pending | |
| Input & pointer capture | Physical mouse and keyboard work natively; `externalInputRoute = native` (`Android native`), `rendererPointerCaptureRequested = true`, `relayPointerCaptureRequested = false` (`Pointer capture: Desktop renderer`) | [ ] Pending | |
| TNT disconnect survival | Unplugging TNT does **not** kill the Linux session: `sessionRunning = true`, `sessionHeadless = true`, `phase = hidden`, `activeDisplayId = null` | [ ] Pending | |
| Return to phone & stop | Explicit launch from the phone restarts/attaches cleanly on the phone display; **Stop Server** leaves zero orphan processes or temporary shared/guest PulseAudio cookie residue | [ ] Pending | |

### Path 3 — Nubia NX888J (`PQ85A02`, Android 16 / API 36) — Phone

| Check | Expected schema-2 / runtime behavior | Status | Notes / evidence |
|---|---|---|---|
| Viewport & placement | `1260 × 2800` internal display; with HDMI disconnected, `placementMode = movable`, `placementSelectionSource = safeDefault` (`Effective placement: Safe default`, `Selection source: Safe default`), `automaticMovesAllowed = false`, `crossDisplayMovesAllowed = true` (plus safe-default banner); connecting an eligible external display promotes `placementSelectionSource` to `runtimeEvidence` (`Effective placement: Movable`, `Selection source: Runtime evidence`, `automaticMovesAllowed = true`) | [ ] Pending | |
| Window-bounds classification | Full-screen phone reports `presentationClass = phoneTouch` (`Phone touch`), `stableDisplayKey = default`; split-screen / narrow-window resize re-evaluates from current window content bounds rather than `smallestScreenWidthDp` | [ ] Pending | |
| Refresh request | 60 Hz refresh request (`refreshPreferredRate = 60`) never selects a lower physical resolution mode | [ ] Pending | |
| Adaptive host audio | `HostAudioAdapter` selects and caches a verified host PulseAudio path (`AAudio_sink`), plays stereo PCM over cookie-authenticated loopback TCP | [ ] Pending | |
| Foreground clipboard | Plain-text copy/paste round-trips `Android <-> X11` while foregrounded | [ ] Pending | |
| Lifecycle & cleanup | Background and **Return to Desktop** resume the same XFCE session; **Stop Server** removes all Linux, X11, audio, and foreground-service processes | [ ] Pending | |

### Path 4 — Nubia NX888J (`PQ85A02`, API 36) — HDMI (`RTK FHD` `2160 × 1440`, dynamic `displayId`)

| Check | Expected schema-2 / runtime behavior | Status | Notes / evidence |
|---|---|---|---|
| Placement & presentation | Dynamic external `displayId` (`RTK FHD`, `2160 × 1440`); `placementMode = movable`, `presentationClass = externalPointer` (`External pointer`), stable `stableDisplayKey` independent of dynamic `displayId` | [ ] Pending | |
| Live cross-display move | Moving `Phone -> HDMI` and `HDMI -> Phone` transfers the active X11 view **without** restarting Linux/XFCE (verify open X11 client window / PID survives both moves) | [ ] Pending | |
| External input routing & capture owner | With `Automatic` input route: selects `externalInputRoute = phoneRelay` when `pointerAssociation` is `defaultOrUnassociated` or `unknown` (with desktop UI mode inactive), and `externalInputRoute = native` when `pointerAssociation` is `targetDisplay`, `otherDisplay`, or `noExternalPointer`, when desktop UI mode is active, or when overridden to `Android native`. With an external mouse or touchpad connected (`hasMouse \|\| hasTouchpad`), `Pointer capture` reports `Phone relay` (`relayPointerCaptureRequested = true`, `rendererPointerCaptureRequested = false`) on `phoneRelay` and `Desktop renderer` (`rendererPointerCaptureRequested = true`, `relayPointerCaptureRequested = false`) on `native` unless `Input mode` is `Direct touch`; with no external pointer (`noExternalPointer`), `Pointer capture` reports `None` (`false` / `false`) | [ ] Pending | |
| Scoped settings persistence | `Save settings for` -> `This display` (`thisDisplay`) vs `All displays of this type` (`classDefault`) persists across HDMI unplug and replug when Android assigns a new dynamic `displayId` | [ ] Pending | |
| HDMI unplug fallback | Unplugging HDMI falls back cleanly to the phone without killing the running Linux/XFCE session | [ ] Pending | |

---

## 4. Placement Override and Diagnostics UI Checks

| Check | Expected behavior | Status | Notes / evidence |
|---|---|---|---|
| `Compatibility details` card | Displays live schema-2 `Effective placement`, `Selection source`, `Placement reason`, `Active presentation`, `Display identity`, `Touch capability`, `Input devices`, `Pointer association`, `External input route`, `Pointer capture`, `Refresh request`, and `Runtime renderer` matching native state | [ ] Pending | |
| Running-session lock | While `sessionRunning = true`, the `Placement preference` dropdown (`Automatic`, `Move existing desktop view`, `Restart Linux on current display`) is disabled in UI and native `setDesktopPlacementOverride` rejects mutations with `INVALID_ARGUMENT` | [ ] Pending | |
| Stopped-session override | While Linux is stopped, selecting `Move existing desktop view` (`movable`) or `Restart Linux on current display` (`displayLocal`) persists across app restart, updates `placementSelectionSource = userOverride` (`Selection source: User override`), and governs the next desktop start | [ ] Pending | |
| Restore default before sign-off | Reset `Placement preference` back to `Automatic` (`placementOverride = null`) while Linux is stopped and confirm natural selection is restored (`deviceQuirk` on R2; `safeDefault` on standalone NX888J Phone when HDMI is disconnected, promoting to `runtimeEvidence` when HDMI is connected) | [ ] Pending | |

---

## 5. Post-Validation Documentation Update

After all four physical paths and the override checks pass on the same APK `SHA-256`:

1. Fill in the provenance table, `SHA-256` digests, and pass/fail rows in this checklist (`docs/testing/multi-device-compatibility-acceptance.md`).
2. Update `docs/testing/debian-proot-r2-acceptance.md` and `docs/testing/debian-proot-nx888j-acceptance.md` with the final `ref/v1` commit regression evidence.
3. Confirm wireless ADB is disabled on both Smartisan R2 and Nubia NX888J before opening any `ref/v1 -> xixia` integration PR.
