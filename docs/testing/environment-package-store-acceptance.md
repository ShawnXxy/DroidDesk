# Environment Package Store Acceptance

This document records the full package-store transaction baseline and the
physical review-fix acceptance across Smartisan R2 and Nubia NX888J. Do not
commit implementation changes until every affected physical scenario passes
with the same current review APK SHA-256.

## Build identity

| Field | Result |
|---|---|
| Source branch | `shawnxxy-environment-package-store` |
| Source commit | `90d71fa6af10a13c541015e9310832324549038b` plus the uncommitted final cancellation-hardening working tree |
| Working tree | Dirty by design; no checkpoint commit exists |
| APK path | `app/build/acceptance/DroidDesk-package-store-acceptance.apk` |
| Package ID | `com.orailnoor.droiddesk.monitoracceptance` |
| Version | `1.3.1-monitor-acceptance` (`6`) |
| ABI | `arm64-v8a` |
| Current review APK SHA-256 | `0cbfbbde0c9caa723590be1a13a88560dd966848e83ebf664150f4e9fb528df2` |
| Second review-fix APK SHA-256 | `376efbbde766ebfeb52a9d7b04088bf11916c910066e92b854658cc502f6e7c9` |
| First review-fix APK SHA-256 | `eb5030a19e9df867a9f918fdcff753d6f8c8cf197043a26d3ece8e6c23ad9ad9` |
| Full transaction baseline APK SHA-256 | `a60f85beb964d2573735dcbb7da08d1f219ab61d90ed809fc7ce2575aedca714` |
| Signer certificate SHA-256 | `91e55ef8c4b136268e59c7b79fb42d66604e762f1bb2ff11a2a750d44f4e9672` |
| Current review build time | 2026-11-08 14:35 +08:00 |

## Device availability

Smartisan R2 (`darwin`, Android 11) was tested by wireless ADB at
`192.168.31.162:5555`. Its physical HDMI display used a current physical
display ID of `72` at 2160 × 1440, with TNT windowing on display `100003`. The
review-fix passes collectively covered phone and physical TNT startup, TNT Go
mouse/keyboard input, the Android window border controls, XFCE/GIMP,
background/return PID stability, package-operation navigation, Stop rejection
during an active mutation, and final Stop Server cleanup. The current hash
revalidated the phone package path, including VLC remove, cancellation,
owned-process cleanup, and a successful retry. After the dock was reconnected,
the same hash launched through the public TNT MainActivity path on display
`100003`, connected LorieView to the X11 service, rendered XFCE Terminal,
accepted the `R2_TNT_OK` keyboard probe, preserved the PRoot/PulseAudio/XFCE
PIDs across TNT Home/background and return, and completed app-owned Stop
cleanup without residual workers. Android 11 also caught and verified the
API-level guard around API 31 monitor product information.

Nubia NX888J (`PQ85A02`, Android 16) was tested by wireless ADB at
`192.168.31.50:5555`. Its current review run used dynamic HDMI display `9`
while the physical RTK FHD monitor remained 2160 × 1440. The review-fix passes
collectively opened XFCE on the live display, selected the phone relay in
`Automatic` mode, captured the TNT Go mouse/keyboard, preserved the full
external viewport, survived rejected Stop requests and display disable/enable
generation changes, returned the same Linux session to display `0` after
physical unplug, and stopped without residual processes. The current hash revalidated VLC remove, cancellation, and retry on the phone,
then launched XFCE on dynamic HDMI display `9`. The phone relay became the
foreground pointer-capture owner and Android reported pointer capture enabled.
The input-route setting remains stored by stable monitor product identity
rather than the transient display ID.

## Review-fix delta acceptance

The full install/remove/cancel/cache matrix below was established with the
baseline APK. The current review APK was then installed over the same package
data and rootfs and revalidated every changed seam plus all four required
physical scenarios.

| Review fix / scenario | Current review APK result |
|---|---|
| Pinned external-source keyring and atomic replacement | Targeted Android tests passed; the gated experimental source path has no production inspector adapter |
| Catalog limit validation | Invalid constructor limits map to typed `INVALID_ARGUMENT`; targeted and full Android tests passed |
| Stop admission ordering | On R2/TNT, Nubia phone, and Nubia HDMI, Stop during an active VLC install was rejected before renderer/session teardown; the operation completed and later Stop succeeded |
| Relay owner cleanup | Nubia display disable/enable replaced Desktop/Relay tasks `866`/`867` with `869`/`870`; cleanup from the old generation did not close the new visible relay |
| Captured-pointer forwarding | Android reported the phone relay as the current pointer-capture owner; the user confirmed physical mouse/touchpad, click, keyboard input, and full 2160 × 1440 viewport control after capture |
| Architecture guard expansion | Guard unit tests and the repository scan passed for all current direct `LinuxRuntime` package-store methods |
| R2 phone / TNT | Current APK passed phone VLC remove → cancelled install → retry, TNT display `100003` startup, XFCE terminal rendering, keyboard input, background/return continuity, and Stop cleanup |
| Nubia phone / HDMI | Current APK passed VLC remove → cancelled install → retry, dynamic HDMI display `9`, Automatic phone relay, and pointer capture |

One ADB-created hidden R2 TNT task retained an old `Removing VLC` render frame
after the native operation had completed. Live inspection showed
`activePackage=null`, `activeOperationId=null`, no package worker, and a
completed result. Replacing the obsolete task cleared the frame. The behavior
was not reproduced in the normal user launch path and did not require a code
change.

### Second review delta

The current review APK adds seven follow-up fixes without changing the Android
30 or Android 36 physical input algorithms. The new input-device listener is
strictly gated to Android 37+; R2 and Nubia therefore use the same validated
input paths as the first review-fix APK. The current hash was installed over
the same device data and revalidated for phone startup on both devices plus
R2/TNT and Nubia HDMI renderer startup, session continuity, and relay capture.
The user explicitly authorized commit after both external sessions were made
ready for physical confirmation.

| Follow-up fix | Verification |
|---|---|
| Identical monitor settings | Simultaneous identical product identities receive connection-scoped keys; a sole monitor retains its stable product key |
| Cancellation lock scope | A blocking store cancellation no longer prevents coordinator subscription/lifecycle access |
| Numeric platform arguments | Floating-point operation IDs and floating/out-of-range limits return `INVALID_ARGUMENT`; valid `Long` limits remain accepted |
| Android 37 route refresh | External renderers register an input-device listener only on API 37+, recompute routing on add/remove/change, and unregister during renderer teardown |
| Operation messaging | Install, remove, single upgrade, upgrade-all, and cache cleanup use operation-specific running, completion, and help text |
| Multiline architecture guard | Direct runtime calls with whitespace or newlines around `.` are detected |
| Automated suites | 64 Flutter tests, the full Android unit suite, architecture guards, and the ARM64 acceptance build passed |

### Third review delta

The current hash revalidated both external renderers and the affected runtime
paths. R2/TNT and Nubia HDMI started successfully with the same APK, and both
sessions completed final Stop cleanup without package workers or services.

| Follow-up fix | Verification |
|---|---|
| Duplicate monitor route preservation | The first monitor keeps its existing product key when an identical monitor appears; additional simultaneous connections receive connection-scoped keys and retain them after the first monitor disconnects |
| Background relay relaunch | With MainActivity foreground and the existing relay backgrounded, reapplying the active display settings brought the same singleTask relay to the foreground and restored pointer capture |
| Index-refresh admission | Refreshing Updates queries use the same environment admission as mutations; read-only queries remain available and busy transitions return typed `OPERATION_IN_PROGRESS` |
| Process-group cancellation | Package commands launch through `setsid`; a Nubia VLC install cancelled with outcome `cancelled`, left no apt/dpkg/timeout/setsid worker, and a retry succeeded |
| Resolution-stable fallback identity | Displays without product information use name-based identity; mutable mode dimensions no longer invalidate saved settings |
| Automated suites | 64 Flutter tests, the full Android unit suite, architecture guards, and the ARM64 acceptance build passed |

### Final cancellation-hardening delta

The current hash closes the final PID-reuse review thread and the additional
R2 timing failures found during physical cancellation acceptance.

| Follow-up fix | Verification |
|---|---|
| Root PID reuse | Descendants are accepted only while the original root identity remains stable before and after enumeration |
| Ownership evidence | Package operation IDs propagate into PRoot guest commands; token and process identity are both revalidated before capture |
| Detached workers | Token-owned workers are discovered across process groups, terminated after cancellation, and quarantined if they survive bounded cleanup |
| Normal completion | Package commands wait for live token-owned descendants; zombie processes do not block settlement because they cannot retain locks |
| Package-state convergence | Install, remove, upgrade, and upgrade-all verification tolerate bounded dpkg status convergence without accepting an unverified changed version |
| R2 phone | VLC remove succeeded, install returned `cancelled`, no apt/dpkg/timeout/setsid worker remained, and the immediate retry succeeded |
| R2/TNT | Display `100003` launched the same hash; XFCE Terminal rendered, accepted `R2_TNT_OK`, retained session PIDs across background/return, and stopped cleanly |
| Nubia phone / HDMI | The same APK passed VLC remove/cancel/retry, HDMI display `9`, foreground phone relay, and pointer capture |
| Automated suites | Full Android unit suite, architecture guard repository scan, `git diff --check`, and ARM64 acceptance build passed |

## Operation evidence

Record one row for every install, removal, upgrade, cancellation, and cache
cleanup used as acceptance evidence.

| Device / mode | Target | Entry | Operation ID | Outcome | Installed version | Desktop entry | Relevant log tail |
|---|---|---|---:|---|---|---|---|
| R2 phone | Termux Native | Tree install → remove | Not captured | Installed, isolated, then removed | `2.3.2` | N/A | PRoot never contained `tree`; search recovered after apt-list cleanup |
| R2 phone | Debian PRoot | Six Supported recipes | Not captured | Installed and verified | Firefox `153.6.0esr`; LibreOffice `4:7.4.7`; GIMP `2.10.34`; VLC `3.0.24`; Node `18.20.4`; Python `3.11.2` | Four GUI desktop entries present | Installed state survived restart and environment switching |
| R2 phone | Debian PRoot | LibreOffice cancel → retry | Not captured | Cancelled, repaired, then installed | `4:7.4.7-1+deb12u14` | `libreoffice-startcenter.desktop` | Typed `CANCELLED`; retry cleared stale error and succeeded |
| R2/TNT | Debian PRoot | GIMP | N/A | GUI launched with physical TNT input | `2.10.34-1+deb12u10` | `gimp.desktop` | Physical HDMI display `64`; DesktopActivity on TNT display `100003` |
| R2/TNT | Debian PRoot | PulseAudio coexistence | N/A | Succeeded | N/A | N/A | Acceptance listened on `4972` while `4713` was deliberately occupied |
| R2/TNT | Debian PRoot | VLC remove → reinstall | Not captured | Removed and reinstalled while XFCE stayed alive | `3.0.24-0+deb12u1` | `vlc.desktop` | Completion crossed from App Store to Home; XFCE panel refreshed without restarting Linux |
| R2/TNT | Debian PRoot | Firefox ESR install progress | Not captured | 18% progress observed, navigation crossed to Home, install completed, returned to same desktop | `153.6.0esr-1~deb12u2` | `firefox-esr.desktop` | PRoot/PulseAudio/xfdesktop PIDs stayed constant; only panel restarted for menu refresh |
| NX888J phone | Termux Native | Tree install | Not captured | Installed, isolated to Native | `2.3.2` | N/A | Native dpkg status contained `tree`; PRoot dpkg status did not |
| NX888J phone | Termux Native | Tree remove | Not captured | Removed | N/A | N/A | Native and PRoot dpkg status both contain zero `tree` records |
| NX888J phone | Termux Native | Cache cleanup | Not captured | Succeeded | N/A | N/A | Cache `216 MB` → approximately `15 KB`; app remained alive |
| NX888J phone | Termux Native | Repository search after cache cleanup | N/A | Succeeded | N/A | N/A | Missing apt lists were automatically refreshed; search returned `tree` without `INDEX_REFRESH_FAILED` |
| NX888J phone | Debian PRoot | Firefox ESR | Not captured | Installed | `153.6.0esr-1~deb12u2` | `firefox-esr.desktop` | `install ok installed`; ARM64 |
| NX888J phone | Debian PRoot | LibreOffice | Not captured | Installed | `4:7.4.7-1+deb12u14` | `libreoffice-startcenter.desktop` | `install ok installed`; ARM64 |
| NX888J phone | Debian PRoot | GIMP remove → cancelled install → retry | Not captured | Cancelled, repaired, then installed | `2.10.34-1+deb12u10` | `gimp.desktop` | Typed `CANCELLED`; dpkg healthy; retry succeeded |
| NX888J phone | Debian PRoot | GIMP install with concurrent Stop Server request | Not captured | Stop rejected while mutation ran; install and later stop succeeded | `2.10.34-1+deb12u10` | `gimp.desktop` | `EnvironmentSelectionBusyException`; PRoot/XFCE/audio remained alive until install completed |
| NX888J phone | Debian PRoot | VLC | Not captured | Installed | `3.0.24-0+deb12u1` | `vlc.desktop` | Launched as `droiddesk` user |
| NX888J phone | Debian PRoot | Node.js + npm | Not captured | Installed | `18.20.4+dfsg-1~deb12u3`; npm `9.2.0` | N/A | `node --version` → `v18.20.4` |
| NX888J phone | Debian PRoot | Python + pip + venv | Not captured | Installed | Python `3.11.2`; pip `23.0.1` | N/A | All three required packages installed |
| NX888J phone | Debian PRoot | Cache cleanup and Updates refresh | Not captured | Succeeded | N/A | N/A | Lists were emptied, pull-to-refresh rebuilt about 19 MB of indexes, and Updates remained truthful |
| R2 phone (current hash) | Debian PRoot | VLC remove → cancelled install → retry | `1` / `2` / `3` | Removed, cancelled with repaired state, then installed | `3.0.24-0+deb12u1` | `vlc.desktop` | No apt/dpkg/timeout/setsid worker remained; immediate retry succeeded |
| NX888J phone (current hash) | Debian PRoot | VLC remove → cancelled install → retry | `1` / `2` / `3` | Removed, cancelled with repaired state, then installed | `3.0.24-0+deb12u1` | `vlc.desktop` | Same final APK; no package worker remained |
| NX888J HDMI (current hash) | Debian PRoot | External input routing | N/A | XFCE visible on display `9`; phone relay foregrounded | N/A | N/A | Android reported pointer capture enabled with `ExternalInputRelayActivity` as owner |
| NX888J HDMI | Debian PRoot | External input routing | N/A | Automatic/native/phone-relay overrides behaved as selected | N/A | N/A | Dynamic display `7`; relay restored full mouse/keyboard control and persisted by monitor product identity |

## Required matrix

### Smartisan R2 phone

- [x] Target label changes correctly between Termux Native and Debian PRoot.
- [x] Native repository search, install, verification, Installed, Updates,
      removal, and cache cleanup pass.
- [x] Debian Supported recipes install and verify:
      Firefox ESR, LibreOffice, GIMP, VLC, Node.js/npm, Python/pip/venv.
- [x] Native installation is absent from PRoot Installed.
- [x] PRoot installation is absent from Native Installed.
- [x] Cancelling an install completed bounded repair and retry; later
      display/input-only rebuilds preserved the tested package transaction
      implementation.
- [x] App restart restores truthful target and package state.

### Smartisan R2/TNT

- [x] Current-hash PRoot Installed and Updates match the phone view on a physical display.
- [x] Current-hash XFCE Applications menu refreshes or reports its limitation on TNT.
- [x] A representative GUI application launches through the validated TNT
      input path; the current hash accepted the keyboard probe and did not
      change the previously accepted mouse routing.
- [x] Package progress and completion survive navigation between App Store,
      Home, and the active TNT desktop.
- [x] The final-hash installed state remained truthful while TNT was active;
      the mutation and cancellation path passed separately on R2 phone.
- [x] Background/return and Stop Server remain correct on physical TNT.

### Nubia NX888J phone

- [x] Native target, repository search, install, verification, Installed,
      Updates, removal, Storage, cache cleanup, and environment isolation pass.
- [x] All six PRoot Supported recipes install and verify.
- [x] Cancellation, recovery, persistence, and cache cleanup pass.
- [x] App restart restores the truthful Debian PRoot target and all six
      Supported recipes remain Installed.
- [x] Final-hash XFCE start and Stop Server process cleanup pass.
- [x] Stop Server during an active package mutation is rejected without
      tearing down PRoot, XFCE, X11, or audio; stopping succeeds afterward.

### Nubia NX888J HDMI

- [x] Dynamic display ID does not alter store target or installed state.
- [x] Representative GUI applications launch from XFCE on the current dynamic
      HDMI display.
- [x] Mouse and keyboard control the complete external viewport through the
      phone relay when Android routes the touchpad to display `0`.
- [x] Automatic/native/phone-relay overrides apply live and persist by stable
      monitor identity.
- [x] Menu refresh, background/return, unplug recovery, and Stop Server pass.
- [x] No PRoot request writes to Termux Native.

## Safety checks

- [x] A stale App Store screen returns `TARGET_CHANGED`.
- [x] Unknown PRoot entry IDs return `INVALID_ENTRY` without a package command.
- [x] Protected Native packages cannot be removed or upgraded.
- [x] Upgrade All excludes runtime, shell, package manager, X11, audio, and XFCE
      packages.
- [x] Ubuntu chroot reports unsupported and never falls back to Native.
- [x] Experimental Chromium, Chrome, and VS Code remain unavailable until their
      source and sandbox probes pass.

## Cleanup and recovery

| Check | R2 | NX888J |
|---|---|---|
| Cancelled transaction repaired | Pass | Pass |
| Following install succeeded | Pass | Pass |
| Stop Server removed PRoot/XFCE/audio/X11 processes | Pass | Pass |
| Temporary package files cleaned | Pass | Pass |
| Existing user files preserved | Pass | Pass |

## Final decision

- Current review APK acceptance status: **Passed**
- Accepted APK SHA-256:
  `0cbfbbde0c9caa723590be1a13a88560dd966848e83ebf664150f4e9fb528df2`
- Blocking failures: **None**
- Remaining limitation: Android 16 and older do not expose public input-device
  display association. Automatic mode therefore uses desktop-mode evidence and
  offers a per-monitor native/phone-relay override; Android 37 can use the
  public association API.
- R2 and Nubia wireless ADB remain enabled by user request until the change is
  merged; this is intentional and is not an acceptance residue.
- Commit permitted: **Yes**
