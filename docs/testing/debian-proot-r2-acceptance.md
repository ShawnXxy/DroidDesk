# Debian PRoot Acceptance — Smartisan R2

Date: 2026-11-05

## Device and build

| Field | Result |
|---|---|
| Product / device | Smartisan R2 / `darwin` |
| Android | 11 / API 30 |
| ABI | `arm64-v8a` |
| Internal display | 1080 × 2340; 60 Hz and 90 Hz modes |
| Root | Unavailable (`su` is not installed) |
| Hardware VM device | `/dev/kvm` unavailable |
| Test package | `com.orailnoor.droiddesk.monitoracceptance` |
| Test isolation | Installed beside the production DroidDesk package |

The acceptance package was absent before the run, so this exercised a clean
app-private bootstrap and Debian installation without modifying the production
package.

## Provisioning

| Check | Result |
|---|---|
| PRoot | `5.1.107.102` |
| PRoot-Distro | `5.10.3` |
| Debian image | Pinned ARM64 digest `sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3` |
| Registry recovery | Docker Hub was unreachable; the same digest succeeded from `public.ecr.aws/docker/library/debian` |
| Debian packages | USTC mirror with official Debian fallback |
| Successful install pass | Approximately 6 minutes 15 seconds |
| Ready marker | Schema 1, package set version 5 |
| Distribution | Debian 12 Bookworm |
| Architecture | `dpkg --print-architecture` returned `arm64` |

The first clean attempt exposed a bootstrap relocation defect: `apt-key`
still used the inaccessible Termux shebang
`/data/data/com.termux/files/usr/bin/sh`, so repository signature validation
failed. Bootstrap script relocation now runs before the first `apt-get`
invocation. Retrying the same installation then completed successfully.

## Desktop and interaction

| Check | Result |
|---|---|
| XFCE first frame | Passed |
| Embedded X11 | Passed at `DISPLAY=:0` |
| Background and return | Same XFCE session resumed |
| Host audio | Debian connected to PulseAudio 17 through the app-private `/tmp/pulse/native` socket |
| Shared Documents | Wrote and reread `droiddesk-r2-acceptance.txt` |
| Android → X11 clipboard | Smartisan Notes text was read unchanged by Debian `xclip` |
| X11 → Android clipboard | Debian token `R2_X_TO_ANDROID_1105` pasted into the Android browser address bar |

The R2 initially ignored `preferredRefreshRate=60f` and kept display mode 2
at 90 Hz. DroidDesk now selects the matching 60 Hz display mode ID when one is
available, while retaining the refresh-rate hint as a fallback. The final
focused `DesktopActivity` reported mode ID 1 at `60.000004` Hz.

Clipboard acceptance covers foreground plain text. Smartisan's global “Big
Bang” text-selection UI intercepts long presses, so the Android-side checks
used its native copy action and Android's standard paste key event.

## External display / TNT

The final acceptance build was launched from the Smartisan TNT desktop while
wireless ADB observed the session:

- `MainActivity` and `DesktopActivity` ran on TNT `displayId=100003`;
- the initial freeform window rendered X11 at 1344 × 720;
- maximizing resized the Android window to 2152 × 1342 and X11 to
  2152 × 1301 at 60 Hz;
- external pointer capture was enabled, and mouse plus keyboard input worked;
- Debian audio playback and the official VS Code Welcome window worked;
- Android Back returned to the TNT DroidDesk home while preserving the Linux
  session;
- **Stop Server** from the external UI removed the desktop, audio, X11, VS
  Code, and foreground services with no residue.

## Official Microsoft VS Code

| Check | Result |
|---|---|
| Package | `code` ARM64 from `packages.microsoft.com/repos/code` |
| Version | `1.145.0-1793748906` |
| CLI | `1.145.0`, commit `a717861c07586a53d83b80d742cd3f59b20a6273`, `arm64` |
| GUI | Welcome window rendered in XFCE |
| Launch flags | `--no-sandbox --disable-gpu --disable-dev-shm-usage` |
| Install transaction | 278 MB archives; approximately 98 seconds on this connection |
| Process-tree RSS | 1,436,320 KiB (approximately 1.37 GiB) |

The RSS value is the sum of the Linux process tree and can double-count shared
pages; it is useful as an acceptance comparison rather than unique physical
memory. System-DBus and udev warnings did not prevent the workbench from
reaching its Welcome screen.

After VS Code installation, the rootfs measured:

- `/usr`: 1,684,180 KiB (approximately 1.61 GiB);
- `/var`: 37,439 KiB;
- `/etc`: 1,634 KiB.

## Lifecycle, persistence, and cleanup

- **Stop Server** removed PRoot, the shared-memory helper, XFCE, the session
  `dbus-daemon`, host PulseAudio, VS Code, and both foreground services.
- A warm restart restored XFCE; at the 15.6-second sample all required desktop
  processes were healthy.
- The installed VS Code version and shared Documents file remained available
  after stop and restart.
- The temporary USB stay-awake setting was restored to `0`.

## Performance and thermal observations

With the VS Code Welcome window open, a short sample reported:

- maximum CPU: 34.7°C;
- GPU: 32.0°C;
- skin sensor: 31.7°C;
- battery: 27.5°C.

This is not a sustained thermal soak. Package extraction and configuration
remain the most visible PRoot overhead, while VS Code's Electron process tree
is the dominant memory workload.

## Known limits

- Software rendering is the guaranteed PRoot renderer.
- PRoot does not provide real root, `systemd`, Docker, cgroups, kernel modules,
  `iptables`, or privileged mounts.
- VS Code installation is validated through Debian `apt`; the product
  optional-app UI does not install it yet.
- Rootfs backup/import and GUI `.deb` import are not implemented.
- Long-duration thermal, battery, and pressure-recovery tests remain follow-up
  soak work rather than blockers for the functional acceptance.
