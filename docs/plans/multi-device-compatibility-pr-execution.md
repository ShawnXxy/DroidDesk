# Multi-Device Compatibility Micro-PR Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to coordinate one isolated nested worktree session per PR unit. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the capability-driven multi-device compatibility refactor through small, independently reviewed pull requests merged only into `ref/v1`, while keeping `xixia` unchanged until later physical-device validation.

**Architecture:** The existing 11-task refactor remains the engineering milestone map. This plan decomposes it into one documentation bootstrap PR plus 22 code PRs. Pure modules may run in parallel waves of at most three sessions; every integration change touching `DesktopActivity`, `DesktopDisplayCoordinator`, or `MainActivity` is serialized.

**Tech Stack:** Kotlin/JVM, Java, Android framework APIs, Flutter/Dart, JUnit 4, GitHub Actions, Python 3 standard library, GitHub pull requests.

**Spec:** `docs/architecture/multi-device-compatibility.md`

**Re-evaluated baseline:** `origin/xixia` at `e8884c2` on 2026-11-08.

## Global Constraints

- Create `ref/v1` from the latest `origin/xixia`; never commit implementation work directly to `xixia`.
- Every PR in this plan targets `ref/v1`. Never target `xixia`, another feature branch, or the repository default by omission.
- Every PR starts in a fresh nested worktree session created from the latest fetched `ref/v1`.
- Pure-module work may use at most three concurrent nested sessions. Integration work is strictly serial.
- Use one behavior objective per PR. Target 2-6 production files and fewer than 400 changed production lines; tests and mechanical import moves do not count toward the line target. If a PR exceeds either limit, stop and split it before review.
- Do not mix opportunistic cleanup, formatting, renames, or unrelated review fixes into a PR.
- Before every commit, run an independent code review against the uncommitted diff. Resolve every Critical/Important finding and re-review before committing.
- Each commit must be a minimal usable change and include `Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>`.
- Push only after the local independent review is clean.
- After push, create a PR with base `ref/v1`, wait for Quality and GitHub Copilot Balance code review, inspect every review thread including minimized or suppressed comments, and fix actionable findings.
- Every review-fix commit also requires an independent pre-commit review, a push, passing checks, and another completed GitHub Copilot review cycle.
- Merge approved/clean PRs with a merge commit. Do not squash or rebase-merge.
- If another PR advances `ref/v1`, merge the latest `origin/ref/v1` into the still-open task branch only when GitHub requires an update or reports a conflict. Do not rewrite published history. Re-run local review, checks, and GitHub Copilot review after the update.
- After a PR merges, fetch `origin`, verify `origin/ref/v1` contains the merge, archive the completed nested session, and use that updated branch as the next task base.
- User-directed exception for this refactor: per-PR commits and merges into `ref/v1` occur before physical-device validation. Do not open `ref/v1 -> xixia`; after all PRs merge, notify the user so R2 phone/TNT and NX888J phone/HDMI validation can begin.
- Preserve the current stable `DesktopDisplay.settingsKey`, presentation-display merge, phone-relay input adapter, reusable Quality workflow, personal-release quality dependency, and package-store architecture guard.

## Review Focus

- A PR that touches a hotspot file must not also introduce a second independent policy change.
- A parallel PR must not modify any file modified by another open PR in the same wave.
- Review-fix commits must stay inside the original PR objective; a new objective becomes a new PR.
- A base-branch update invalidates prior verification when it changes the task diff or conflict resolution; repeat both review layers.
- No PR may make `xixia` the base, and completion must not create a `ref/v1 -> xixia` PR.

## Branch and Session Topology

```text
origin/xixia
    |
    +-- ref/v1                     integration branch; merge commits only
          |
          +-- <task branch P00>    nested worktree/session -> PR -> ref/v1
          +-- <task branch P01>    nested worktree/session -> PR -> ref/v1
          +-- ...
          +-- <task branch P22>    nested worktree/session -> PR -> ref/v1
```

The coordinator creates `ref/v1` once:

```powershell
git fetch --prune origin
git branch ref/v1 origin/xixia
git push -u origin ref/v1
```

Before creating each nested session:

```powershell
git fetch --prune origin
git branch -f ref/v1 origin/ref/v1
git rev-parse ref/v1
git rev-parse origin/ref/v1
```

The two hashes must match. A parallel wave creates all sessions only after this check.

## Common PR Lifecycle

Every PR unit below follows this lifecycle:

1. Create a fresh nested project session with `workspace_type: worktree` and `base_branch: ref/v1`.
2. Rename its task branch to a short `ref-v1-pNN-<slug>` intent; the app may add its configured prefix.
3. Give the nested session only its PR-unit brief, the tracked architecture/plan paths, dependency outputs, and these global constraints.
4. Write the failing focused test and verify RED when the task changes behavior.
5. Implement only the PR objective and run focused tests plus the smallest relevant regression suite.
6. Run `git diff --check` and inspect `git diff --stat`.
7. Launch an independent code-review agent inside the nested session against the uncommitted diff. This is a user-directed exception to the usual implementer/reviewer separation because the uncommitted worktree is visible only inside that session.
8. Resolve findings and repeat Step 7 until spec compliance and code quality are clean.
9. Commit the minimal change with the required trailer, push the branch, and create a PR whose base is explicitly `ref/v1`.
10. Wait for Quality and GitHub Copilot Balance code review to complete. Read all review threads and suppressed/minimized comments.
11. For actionable feedback, implement the smallest fix in the same nested session; repeat local tests, independent pre-commit review, commit, push, Quality, and GitHub Copilot Balance review.
12. When checks pass and no unresolved actionable findings remain, merge with `gh pr merge --merge --delete-branch`.
13. Fetch `origin/ref/v1`, verify the merge commit, record the PR and merge SHA in the execution ledger, then archive the nested session.

PR titles use:

```text
ref/v1 PNN: <imperative outcome>
```

PR bodies contain:

```markdown
## Scope
<one behavior objective>

## Base
`ref/v1`

## Dependencies
<merged PR numbers or None>

## Validation
<exact commands and results>

## Independent review
<review status and resolved findings>

## Non-goals
<explicitly excluded adjacent work>
```

## Parallel Waves and Merge Order

| Wave | Concurrent PR units | Merge rule |
|---|---|---|
| 0 | P00 | Must merge first |
| 1 | P01, P02, P03 | Up to three sessions; merge one at a time |
| 2 | P04, P05, P06 | Start only after P01 merges; merge one at a time |
| 3 | P07 | Serial hotspot extraction |
| 4 | P08, P13 | Two pure-policy sessions; merge one at a time |
| 5 | P09 -> P10 -> P11 -> P12 | Strictly serial placement/integration spine |
| 6 | P14, P15 | Two disjoint sessions after P12 and P13 |
| 7 | P16 | Serial `DesktopActivity` integration |
| 8 | P17 | Serial native settings integration |
| 9 | P18, P19 | Flutter settings and readiness integration use disjoint files |
| 10 | P20 | Serial native diagnostics integration |
| 11 | P21 -> P22 | Strictly serial final UI and guard work |

---

### Task P00: Publish tracked engineering references

**Dependencies:** None.

**Files:**
- Create: `docs/architecture/multi-device-compatibility.md`
- Create: `docs/plans/multi-device-compatibility-refactor.md`
- Create: `docs/plans/multi-device-compatibility-pr-execution.md`

**Interfaces:**
- Produces tracked, reviewable copies of the approved architecture, milestone plan, and this PR execution plan for every later nested worktree.

- [ ] Copy the current ignored documents without changing their technical content.
- [ ] Verify all three tracked documents reference baseline `e8884c2`, `ref/v1`, the micro-PR workflow, and the no-PR-to-`xixia` rule.
- [ ] Run Markdown structural checks and `git diff --check`.
- [ ] Follow the Common PR Lifecycle and merge P00 into `ref/v1`.

---

### Task P01: Define framework-free presentation evidence

**Dependencies:** P00.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/capability/PresentationEvidence.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/capability/PresentationEvidenceTest.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/support/PresentationFixtures.kt`

**Interfaces:**
- Produces `PresentationClass`, `PlacementMode`, `DeviceIdentity`, `DisplayModeEvidence`, `TouchEvidence`, `PointerAssociationEvidence`, `InputCapabilities`, `DisplayPlacementEvidence`, and `PresentationContext` exactly as defined in the milestone plan.

- [ ] Add failing validation and fixture tests for R2 phone/TNT, NX888J phone/HDMI, external touch, unknown pointer association, foldable narrow window, and 4K120.
- [ ] Implement immutable framework-free evidence types with constructor validation.
- [ ] Run `*PresentationEvidenceTest`.
- [ ] Follow the Common PR Lifecycle and merge P01.

---

### Task P02: Remove the fixed Android pointer-ID limit

**Dependencies:** P00.

**Files:**
- Create: `app/android/app/src/main/java/com/termux/x11/input/ActivePointerTracker.java`
- Create: `app/android/app/src/test/java/com/termux/x11/input/ActivePointerTrackerTest.java`
- Modify: `app/android/app/src/main/java/com/termux/x11/input/InputEventSender.java`

**Interfaces:**
- Produces `ActivePointerTracker.update(Set<Integer>)`, `begin(int)`, `end(int)`, and `clear(): Set<Integer>`.

- [ ] Write failing tests using sparse IDs `0`, `12`, and `31`.
- [ ] Replace the ten-element pointer array without clamping or renumbering Android pointer IDs.
- [ ] Run `*ActivePointerTrackerTest` and the existing input tests.
- [ ] Follow the Common PR Lifecycle and merge P02.

---

### Task P03: Give runtime probes independent timeout budgets

**Dependencies:** P00.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/ProbeTimeouts.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/RuntimeCapabilityProbe.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/RuntimeCapabilityProbeTest.kt`

**Interfaces:**
- Produces `ProbeTimeouts` with the exact budgets from the milestone plan.

- [ ] Add failing tests proving a slow chroot probe cannot consume the audio budget and each timeout reports its own value.
- [ ] Implement per-future deadlines while preserving concurrent probe execution and completed evidence.
- [ ] Run `*RuntimeCapabilityProbeTest`.
- [ ] Follow the Common PR Lifecycle and merge P03.

---

### Task P04: Adapt host-audio library-path selection

**Dependencies:** P00.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/HostAudioAdapter.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/HostAudioPreferenceStore.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/HostAudioAdapterTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/LinuxRuntime.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/LinuxRuntimeAudioTest.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/runtime/PulseAudioStartupTest.kt`

**Interfaces:**
- Produces `HostAudioPort`, `VerifiedAudioPath`, `HostAudioPreferenceStore`, and `HostAudioAdapter.start(...)`.
- Preserves `pulseAudioTcpPort` and the AAudio-to-plain-PulseAudio daemon fallback for every candidate library path.

- [ ] Add failing candidate-order, cache invalidation, cleanup, and suppressed-error tests.
- [ ] Implement bounded candidate probing and versioned successful-path persistence.
- [ ] Run host-audio, PulseAudio startup, and affected runtime tests.
- [ ] Follow the Common PR Lifecycle and merge P04.

---

### Task P05: Select placement mode from evidence, quirks, and user preference

**Dependencies:** P01.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DeviceQuirkCatalog.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementSelector.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/PlacementPreferenceStore.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementSelectorTest.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/placement/PlacementPreferenceStoreTest.kt`

**Interfaces:**
- Produces `PlacementSelectionSource`, `PlacementSelection`, `DesktopPlacementSelector`, and `AndroidPlacementPreferenceStore`.
- `DeviceQuirkCatalog` is the only production location containing `darwin` or TNT display ID `100003`.

- [ ] Add failing override, conclusive-evidence, R2 quirk, TNT metadata, and safe-default tests.
- [ ] Implement selector precedence and transactional override persistence.
- [ ] Run selector and preference-store tests.
- [ ] Follow the Common PR Lifecycle and merge P05.

---

### Task P06: Gather Android presentation and placement evidence

**Dependencies:** P01.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/capability/AndroidPresentationEvidenceProbe.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/capability/AndroidPresentationEvidenceProbeTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayRepository.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DisplaySourceMergeTest.kt`

**Interfaces:**
- Produces `placementEvidence(currentDisplayId)` and `presentationContext(displayId, activeWindowSize)`.
- Reuses merged display discovery, placement permission checks, and `DesktopDisplay.settingsKey`; it does not create a second inventory or fingerprint.

- [ ] Add failing inactive-display, stable-identity, permission, touch-association, and pointer-association tests.
- [ ] Implement the Android adapter and extract only the permission helper needed by both source and probe.
- [ ] Run evidence-probe, display-source, and launcher-policy tests.
- [ ] Follow the Common PR Lifecycle and merge P06.

---

### Task P07: Separate display environment from external-input policy

**Dependencies:** P00.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayEnvironment.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/ExternalInputPolicy.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayEnvironmentPolicy.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/ExternalInputRelayPolicyTest.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/view/DesktopInputRoutingTest.kt`

**Interfaces:**
- Moves `DisplayEnvironment` and `DesktopStartupTarget` without behavior change.
- Moves `ExternalInputRoute`, `ExternalInputRoutePreference`, routing, relay-capture, renderer-capture, and relay-finish helpers without behavior change.
- Leaves only legacy placement selection/orientation in `DisplayEnvironmentPolicy`.

- [ ] First move existing tests to the new packages and verify they fail to compile.
- [ ] Move definitions and imports without changing logic or copy.
- [ ] Run display, relay-policy, and input-routing tests.
- [ ] Follow the Common PR Lifecycle and merge P07.

---

### Task P08: Define placement strategy adapters

**Dependencies:** P05, P07.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementStrategy.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/MovableSecondaryDisplayAdapter.kt`
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/placement/DisplayLocalRestartAdapter.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/placement/DesktopPlacementStrategyTest.kt`

**Interfaces:**
- Produces `DesktopPlacementPort`, `DesktopPlacementStrategy`, and `DesktopOpenRequest`.
- Adapters own launch/focus/cancel choice, manual/automatic movement, environment mapping, orientation, restart warnings, restart requirements, fallback, disconnect semantics, and current-external auto-launch.

- [ ] Add failing adapter contract tests for movable, display-local, TNT orientation, fallback, restart, warning, and auto-launch behavior.
- [ ] Implement both adapters without wiring Android callers.
- [ ] Run placement strategy and selector tests.
- [ ] Follow the Common PR Lifecycle and merge P08.

---

### Task P09: Route Android display hosting through the placement strategy

**Dependencies:** P06, P08.

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayLauncher.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayLauncherPolicyTest.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/placement/AndroidDesktopPlacementPortTest.kt`

**Interfaces:**
- Makes `AndroidDesktopDisplayHost` implement `DesktopPlacementPort`.
- Delegates launch/focus/cancel variation to `DesktopPlacementStrategy`.

- [ ] Add failing tests for local launch, display launch, focus, cancellation, stale keys, and exception translation.
- [ ] Refactor only host mechanics; do not change coordinator state transitions.
- [ ] Run launcher, source-merge, and placement tests.
- [ ] Follow the Common PR Lifecycle and merge P09.

---

### Task P10: Route coordinator state through the placement strategy

**Dependencies:** P09.

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayCoordinator.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayLocalSessionController.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DisplayLocalStartupTest.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/SessionStartTransferTest.kt`

**Interfaces:**
- Adds `placementSelection`, `requestOpen(DesktopOpenRequest, startService, completion)`, and `replacePlacementStrategy`.
- Replaces coordinator-owned display-local decisions with adapter calls while preserving generations, headless state, truthful failures, and renderer ownership.

- [ ] Rewrite lifecycle tests with strategy fixtures and add stopped-only strategy replacement coverage.
- [ ] Refactor coordinator and restart controller without changing callers.
- [ ] Run the complete display package test suite.
- [ ] Follow the Common PR Lifecycle and merge P10.

---

### Task P11: Remove placement branching from Android callers

**Dependencies:** P10.

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/service/DroidDeskService.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/service/DesktopStartupNotification.kt`
- Delete: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayEnvironmentPolicy.kt`
- Modify: affected display/service/view tests

**Interfaces:**
- Callers use coordinator operations and adapter diagnostics; they no longer branch on `isDisplayLocal`.
- Generic external auto-launch is requested through the selected placement strategy.

- [ ] Add/adjust tests proving callers do not choose placement mode.
- [ ] Move startup request orchestration into `DesktopDisplayCoordinator.requestOpen`.
- [ ] Delete legacy policy after all imports and branches are removed.
- [ ] Run Android unit tests and an architecture grep for leaked identities/booleans.
- [ ] Follow the Common PR Lifecycle and merge P11.

---

### Task P12: Select 60 Hz without changing physical resolution

**Dependencies:** P01, P11.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/refresh/DisplayRefreshPolicy.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/refresh/DisplayRefreshPolicyTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`

**Interfaces:**
- Produces `RefreshRequest` and `DisplayRefreshPolicy.resolve`.

- [ ] Add failing exact-resolution, 59.94 Hz, 4K120, low-capability, and missing-evidence tests.
- [ ] Implement the pure policy and replace direct mode selection in `DesktopActivity`.
- [ ] Run refresh and affected view/display tests.
- [ ] Follow the Common PR Lifecycle and merge P12.

---

### Task P13: Define one desktop input policy

**Dependencies:** P01, P07.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/DesktopInputPolicy.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/input/DesktopInputPolicyTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/input/ExternalInputPolicy.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/ExternalInputRelayPolicyTest.kt`

**Interfaces:**
- Produces `InputPolicyDecision` with input mode, direct-touch support, external route, renderer/relay pointer capture, horizontal/vertical scroll scales, and reason.
- Preserves current desktop-UI, explicit route preference, unknown-association relay, phone, and TNT behavior.

- [ ] Add failing direct-touch, unknown association, target association, desktop UI, explicit override, phone/TNT, capture ownership, and scroll-factor tests.
- [ ] Implement the pure policy without changing Activity/relay behavior.
- [ ] Run input-policy and relay-policy tests.
- [ ] Follow the Common PR Lifecycle and merge P13.

---

### Task P14: Apply input policy to renderer and phone relay

**Dependencies:** P02, P11, P13.

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/ExternalInputRelayActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/x11/ExternalDesktopInputBridge.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/x11/X11InputController.kt`
- Modify: `app/android/app/src/main/java/com/termux/x11/input/TouchInputHandler.java`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/view/DesktopInputRoutingTest.kt`

**Interfaces:**
- `DesktopActivity` computes one decision and drives renderer capture, relay capture, bridge attachment, relay Activity lifecycle, input mode, and scroll scaling from it.

- [ ] Add failing integration tests for native, relay, touch promotion, route override, capture ownership, and input-device refresh.
- [ ] Replace scattered routing/capture decisions with `InputPolicyDecision`.
- [ ] Apply Android horizontal and vertical scroll factors instead of `-100`.
- [ ] Run pointer, input, relay, view, and full Android tests.
- [ ] Follow the Common PR Lifecycle and merge P14.

---

### Task P15: Define presentation policy and isolate X11 geometry rules

**Dependencies:** P01, P12, P13.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/policy/PresentationPolicy.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/policy/PresentationPolicyTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayProfile.kt`
- Modify: `app/android/app/src/main/java/com/termux/x11/X11RenderGeometry.java`
- Create: `app/android/app/src/test/java/com/termux/x11/X11RenderGeometryTest.java`

**Interfaces:**
- Produces `PresentationPlan`, `PresentationPolicy.classify`, and `resolve`.
- Keeps the legacy phone profile while classifying current window capabilities and naming the `1366 x 768` compatibility rule.

- [ ] Add failing R2, Nubia, foldable, large-touch, external-touch, software-4K, density/font-scale, and geometry tests.
- [ ] Implement the pure policy and geometry rule without modifying `DesktopActivity`.
- [ ] Run presentation, refresh, input-policy, and geometry tests.
- [ ] Follow the Common PR Lifecycle and merge P15.

---

### Task P16: Apply the presentation plan to the desktop renderer

**Dependencies:** P14, P15.

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/XfceDisplayProfile.kt`
- Modify: affected presentation/view tests

**Interfaces:**
- Builds one measured `PresentationContext`, classifies once, loads settings for that class, resolves once, and feeds the same plan to rendering, input, refresh diagnostics, XFCE UI, and overlay offsets.

- [ ] Add failing integration tests proving current window bounds replace `smallestScreenWidthDp` and render/input geometry share one content size.
- [ ] Remove profile formulas and classification from `DesktopActivity`.
- [ ] Run presentation, view, display, and full Android tests.
- [ ] Follow the Common PR Lifecycle and merge P16.

---

### Task P17: Migrate native display settings onto presentation class and stable identity

**Dependencies:** P06, P16.

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplaySettingsStore.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DisplaySettingsStoreTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DisplayRepository.kt`
- Modify: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/DisplaySourceMergeTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayCoordinator.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt`

**Interfaces:**
- Produces `DisplaySettingsScope`, `DisplaySettingsKey`, schema-v2 load/save, native settings payloads with `presentationClass`, `stableDisplayKey`, and `settingsScope`.
- Preserves existing `inputRoute` and lazy migration from `display.<settingsKey>.inputRoute`.

- [ ] Add failing schema migration, exact override, class fallback, stable identity, identical monitor, rollback, and idempotence tests.
- [ ] Implement native storage and wire handling without numeric display IDs or a second fingerprint.
- [ ] Run settings, source, coordinator, and full Android tests.
- [ ] Follow the Common PR Lifecycle and merge P17.

---

### Task P18: Add Flutter display-setting scopes

**Dependencies:** P17.

**Files:**
- Modify: `app/lib/models/desktop_display.dart`
- Modify: `app/lib/services/platform_bridge.dart`
- Modify: `app/lib/state/app_state.dart`
- Modify: `app/lib/screens/display_settings_screen.dart`
- Modify: `app/test/models/desktop_display_test.dart`
- Modify: `app/test/screens/display_settings_input_route_test.dart`
- Create: `app/test/screens/display_settings_scope_test.dart`

**Interfaces:**
- Parses and sends presentation class, stable identity, scope, and existing input route.
- UI offers `This display` and `All displays of this type`.

- [ ] Add failing model, state, scope, copy, and input-route preservation tests.
- [ ] Implement Flutter contract and UI without adding diagnostics yet.
- [ ] Run the focused Flutter tests and full `flutter test`.
- [ ] Follow the Common PR Lifecycle and merge P18.

---

### Task P19: Start Linux only after renderer readiness signals

**Dependencies:** P16.

**Files:**
- Create: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/readiness/DesktopReadinessGate.kt`
- Create: `app/android/app/src/test/kotlin/com/orailnoor/droiddesk/display/readiness/DesktopReadinessGateTest.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/view/DesktopActivity.kt`
- Modify: `app/android/app/src/main/java/com/termux/x11/LorieView.java`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/x11/X11ServiceClient.kt`

**Interfaces:**
- Produces generation-scoped `RENDERER_CLAIMED`, `SURFACE_READY`, `X11_CONNECTED`, and `GEOMETRY_COMMITTED` gating.

- [ ] Add failing order-permutation, duplicate, stale-generation, reset, and slow-signal tests.
- [ ] Replace the 250 ms startup delay with the gate.
- [ ] Run readiness, session transfer, display, relay, and full Android tests.
- [ ] Follow the Common PR Lifecycle and merge P19.

---

### Task P20: Publish native compatibility diagnostics and placement override

**Dependencies:** P03, P04, P05, P10, P17, P19.

**Files:**
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/display/DesktopDisplayCoordinator.kt`
- Modify: `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt`
- Create or modify: native schema-v2/placement-override tests

**Interfaces:**
- Snapshot schema 2 exposes placement selection, override, presentation class, stable display key, input evidence/route/capture ownership, refresh request, geometry, profile, and runtime capability evidence.
- `setDesktopPlacementOverride` rejects changes while Linux is running, persists while stopped, reselects the adapter, and publishes the new state.

- [ ] Add failing complete-payload, nullable-field, state-invariant, and stopped-only override tests.
- [ ] Implement serialization from existing decisions without recomputing policy.
- [ ] Run coordinator, MethodChannel, placement, and full Android tests.
- [ ] Follow the Common PR Lifecycle and merge P20.

---

### Task P21: Add Flutter compatibility diagnostics and placement override

**Dependencies:** P18, P20.

**Files:**
- Modify: `app/lib/models/desktop_display.dart`
- Modify: `app/lib/services/platform_bridge.dart`
- Modify: `app/lib/state/app_state.dart`
- Modify: `app/lib/screens/display_settings_screen.dart`
- Modify: `app/test/models/desktop_display_test.dart`
- Create: `app/test/screens/display_compatibility_diagnostics_test.dart`

**Interfaces:**
- Adds typed schema-2 diagnostics and the Automatic/Movable/Display-local placement preference control.

- [ ] Add failing payload, enum, invariant, widget, warning, and running-session-disabled tests.
- [ ] Implement typed models, state operation, and read-only compatibility details.
- [ ] Run focused and full Flutter tests.
- [ ] Follow the Common PR Lifecycle and merge P21.

---

### Task P22: Extend quality with compatibility architecture guards

**Dependencies:** P21 and every prior PR.

**Files:**
- Create: `tools/check_device_compatibility_architecture.py`
- Create: `tools/test_check_device_compatibility_architecture.py`
- Modify: `.github/workflows/quality.yml`
- Create: `docs/testing/multi-device-compatibility-acceptance.md`

**Interfaces:**
- Guard fails on OEM identity leakage, display-local branching leakage, misplaced input/placement policy, fixed pointer array, 250 ms startup delay, direct refresh-mode selection, or `smallestScreenWidthDp` profile classification.
- Existing Flutter tests, debug APK build, Android tests, package-store guard, and personal-release quality dependency remain intact.

- [ ] Write failing temporary-tree tests for every forbidden pattern.
- [ ] Implement the guard and add it to the existing Quality workflow without removing current steps.
- [ ] Add the post-`ref/v1` physical acceptance checklist, including exact APK SHA-256 recording.
- [ ] Run both architecture-guard suites, both guard scripts, Flutter tests, Android tests, and the debug APK build.
- [ ] Follow the Common PR Lifecycle and merge P22.

---

## Completion Handoff

After P22 merges:

1. Fetch and verify the final `origin/ref/v1`.
2. Confirm all P00-P22 PRs are merged, all nested sessions are archived, and no open PR targets `ref/v1`.
3. Confirm no PR exists from `ref/v1` to `xixia`; do not create one.
4. Report the final `ref/v1` SHA, merged PR list, unresolved risks, and the physical acceptance checklist to the user.
5. Stop. The user will coordinate R2 phone/TNT and NX888J phone/HDMI testing before any later integration into `xixia`.
