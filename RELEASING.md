# Releasing the personal DroidDesk fork

`xixia` is the default branch and contains the personal improvements.
`main` mirrors `orailnoor/DroidDesk` and is not a personal release branch.
Only merge upstream changes into `xixia` when they are wanted in the personal app.

The app built from this branch checks
`https://api.github.com/repos/ShawnXxy/DroidDesk/releases/latest` at most once
every six hours. It ignores draft releases and pre-releases.

## One-time signing setup

Personal releases must keep using the same key as the personal APK installed on
nubia. The workflow verifies this certificate SHA-256 before publishing:

```text
ee1bb74cf921797ce317d84955cc087db7cd32644c6d28d58d247fb284b04aa5
```

In this fork's **Settings > Secrets and variables > Actions**, configure these
repository secrets using a trusted local process, not chat or committed files:

| Secret | Value |
| --- | --- |
| `DROIDDESK_KEYSTORE_BASE64` | Base64-encoded personal release keystore |
| `DROIDDESK_KEYSTORE_PASSWORD` | Keystore password |
| `DROIDDESK_KEY_ALIAS` | Alias of the personal signing key |
| `DROIDDESK_KEY_PASSWORD` | Key password |

The original keystore and Windows-user-encrypted credentials remain under
`%USERPROFILE%\.droiddesk\`. Keep them safe; the encrypted credential file can
only be decrypted by the same Windows account on the same machine. GitHub
Secrets are not a downloadable backup. Never replace this key for an ordinary
update, commit it, or add it to release assets.

Actions uses its short-lived `GITHUB_TOKEN` with `contents: write` to create the
tag and release. No personal access token is required. Only trusted changes
should enter `xixia`: code running in the release job can access signing secrets.

## Publish a personal version

Every push to `xixia`, including a pull request merge, automatically builds,
signs, and publishes a personal release. The workflow chooses a version by
incrementing both the patch version and Android build number beyond the higher
values in `app/pubspec.yaml` and the latest published personal release. If the
next tag or release already exists after an interrupted run, it keeps
incrementing until it finds an unused version. Release runs share a FIFO
concurrency queue so merges and manual releases wait instead of replacing an
already pending run. GitHub does not guarantee that queued runs start in push
order, so an automatic run also verifies that its commit is still the current
`xixia` head; a stale queued run exits without replacing a newer release.

To publish a specific version manually, open
[Personal APK release](https://github.com/ShawnXxy/DroidDesk/actions/workflows/personal-release.yml),
choose **Run workflow** on `xixia`, and enter a version such as `1.3.2+7`.
Both the three-part version name and Android build number must exceed the source
version and latest published personal release. The workflow passes the selected
values to Flutter without committing a version bump. Tags use the version name,
such as `v1.3.2`; existing tags and releases are never overwritten.

The job builds an ARM64 Release APK using Flutter 3.44.0 and Java 17. Missing
signing secrets, an unexpected signing certificate, a wrong package/version,
or a debuggable APK blocks publication. It tags the exact dispatched `xixia`
commit, uploads `DroidDesk-v<version>-xixia.apk`, `SHA256SUMS`, and
`release-manifest.json` into a draft,
then publishes it as the latest stable release only after uploads succeed.
The manifest records the source commit, version, APK hash, and signing
certificate; later runs use it to enforce increasing Android build numbers.

If publication fails after creating the tag or draft, inspect that run and the
draft before retrying. The workflow does not delete or overwrite them; resolve
the incomplete release explicitly or choose a new version. If publication
completed but only the final reporting step failed, an automatic retry detects
the release for that source commit and exits without publishing a duplicate.

## Installing and updating

Download the APK from [this fork's Releases](https://github.com/ShawnXxy/DroidDesk/releases).
It can replace the existing personal nubia release because it uses the same
package and signing key. The earlier locally built `1.3.1+6` still checks the
upstream update feed: install the first fork-feed APK manually once, without
uninstalling or clearing app data. Later personal versions offer updates from
this fork in the app.

Upstream APKs and debug-signed test builds use different keys and cannot replace
the personal release in place. The separate Monitor Test package is unaffected.
