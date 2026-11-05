# Debian PRoot Acceptance — Nubia NX888J

Date: 2026-11-04

## Device and build

| Field | Result |
|---|---|
| Model | Nubia NX888J |
| Device | PQ85A02 |
| Android | 16 / API 36 |
| ABI | `arm64-v8a` |
| Root | Unavailable (`su` is not installed) |
| Hardware VM device | `/dev/kvm` unavailable |
| Test package | `com.orailnoor.droiddesk.monitoracceptance` |
| Test isolation | Installed beside the production DroidDesk package |

This acceptance pass validates the non-root Debian PRoot path. It does not
validate AVF/Gunyah or rooted chroot operation.

## Provisioning

| Check | Result |
|---|---|
| PRoot | `5.1.107.102` |
| PRoot-Distro | `5.10.3` |
| Debian image | Pinned ARM64 digest `sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3` |
| Registry recovery | Docker Hub was unreachable; the same digest succeeded from `public.ecr.aws/docker/library/debian` |
| Debian packages | USTC mirror preferred with official Debian fallback |
| Final successful pass | Approximately 6 minutes from restart to ready |
| Ready marker | Package set version 5 |
| Architecture | `dpkg --print-architecture` returned `arm64` |

The mirror comparison downloaded 764,600 bytes from `deb.debian.org` in 20
seconds versus 1 MiB from USTC in approximately 0.46 seconds on the same
connection.

The base XFCE rootfs previously measured approximately 501 MiB in `/usr`, 34
MiB in `/var`, and 1.4 MiB in `/etc`. After installing official VS Code,
`/usr` measured approximately 1.44 GiB. Setup enforces a 2 GiB transaction
minimum and recommends 6 GiB free for downloads, staging, rollback, upgrades,
and safety margin; neither number is the final base install size.

## Desktop and interaction

| Check | Result |
|---|---|
| XFCE first frame | Passed |
| Embedded X11 | Passed at `DISPLAY=:0` |
| Phone viewport | Passed at 360 × 800 logical / 1260 × 2800 physical |
| Background and return | Same XFCE session resumed through **Return to Desktop** |
| Android render-rate request | `mActiveRenderFrameRate=60.000004` while `DesktopActivity` was focused |
| Audio | Debian `pactl -s tcp:127.0.0.1 info` connected to the host PulseAudio AAudio/OpenSL ES path |
| Android → X11 clipboard | Android Notes token was read unchanged by Debian `xclip -selection clipboard -o` |
| X11 → Android clipboard | Debian `xclip` token pasted unchanged into Android Notes |

Clipboard acceptance covers foreground plain text. Rich text, images,
background clipboard access, and clipboard-manager interoperability were not
tested.

## External display

The final build was also validated on the connected `RTK FHD` HDMI display:

| Field | Result |
|---|---|
| Android display | `displayId=25` |
| Mode | 2160 × 1440 at 60 Hz |
| Placement | Generic secondary-display `ActivityOptions` path |
| X11 surface | 2160 × 1440 |
| Input | External pointer capture enabled; mouse and keyboard passed |
| VS Code | Welcome window rendered and accepted mouse/keyboard input |
| Stop/recovery | Closing the external view kept the Linux session running; phone home exposed **Return to Desktop** and **Stop Server** |

Two external-display defects were found and fixed:

1. Android kept the physical mouse cursor on display 0. DroidDesk now enables
   pointer capture automatically for a non-default display and releases it
   when that view closes.
2. A concurrent display-profile command could make the guest `pgrep` status
   query return busy, incorrectly marking the live XFCE session as failed.
   Required desktop processes are now probed through an independent host
   process instead of the shared command channel.

Android routed media to the `Smartisan TNT go` USB audio device. Native browser
audio and Linux stereo PCM both played, but the output was quiet on this
hardware path. Browser and Linux levels were comparable, so this is the
Android/dock/display output gain rather than PulseAudio attenuation. DroidDesk
does not override the user's Android media or display-speaker volume.

## Official Microsoft VS Code

| Check | Result |
|---|---|
| Package | `code` ARM64 |
| Version | `1.145.0-1793748906` |
| CLI | `1.145.0`, commit `a717861c07586a53d83b80d742cd3f59b20a6273`, `arm64` |
| GUI | Welcome window rendered in XFCE |
| Launch flags | `--no-sandbox --disable-gpu --disable-dev-shm-usage` |
| Download | Approximately 233 MiB and 12 minutes from `packages.microsoft.com` |
| Process-group RSS | 776,932 KiB (approximately 759 MiB) during the acceptance run |

The Electron system-DBus and udev warnings observed at first launch did not
prevent the GUI from opening.

## Lifecycle cleanup

After **Stop Server**, the following were verified absent:

- `proot` and the PRoot shared-memory helper;
- `xfce4-session`, `xfce4-panel`, `xfdesktop`, and `xfwm4`;
- the session `dbus-daemon`;
- host `pulseaudio`;
- DroidDesk and X11 foreground services.

The final cleanup fix avoids routing daemon-termination commands into a
concurrent PRoot status process. Selected-environment cleanup is also executed
when the desktop root process has already exited.

## Performance and thermal observations

- PRoot overhead is most visible during archive extraction, package
  configuration, and operations over many small files.
- Papirus was replaced by Adwaita because theme extraction added roughly 208
  MiB and icon-cache generation was disproportionately slow under PRoot.
- With VS Code open and idle, a short sample reported maximum CPU 32.5°C,
  maximum GPU 32.4°C, and skin 31.2°C.
- These values are not a long-duration thermal soak and must not be used as a
  sustained-load guarantee.

## Known limits and remaining acceptance

- Software rendering is the guaranteed PRoot renderer; hardware acceleration
  is not claimed.
- PRoot does not provide real root, `systemd`, Docker, cgroups, kernel modules,
  `iptables`, or privileged mounts.
- VS Code installation is validated manually through Debian `apt`; the product
  optional-app UI does not install it yet.
- Rootfs backup/import and GUI `.deb` import are not implemented.
- Long-duration thermal and battery soak testing remains follow-up work.
