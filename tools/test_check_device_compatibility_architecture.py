import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPT = Path(__file__).with_name("check_device_compatibility_architecture.py")

ANDROID_KOTLIN = "app/android/app/src/main/kotlin/com/orailnoor/droiddesk"
TERMUX_X11 = "app/android/app/src/main/java/com/termux/x11"


class DeviceCompatibilityArchitectureGuardTest(unittest.TestCase):
    def run_guard(self, files: dict[str, str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SCRIPT), str(root)],
                text=True,
                capture_output=True,
                check=False,
            )

    def assert_fails_with(
        self,
        files: dict[str, str],
        expected_fragment: str,
        expected_path_line: str | None = None,
    ) -> None:
        result = self.run_guard(files)
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn(expected_fragment, output)
        if expected_path_line is not None:
            self.assertIn(expected_path_line, output)

    def test_real_repository_passes(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(REPO_ROOT)],
            text=True,
            capture_output=True,
            check=False,
        )
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn("Device compatibility architecture: OK", output)
        desktop_src = (REPO_ROOT / f"{ANDROID_KOTLIN}/view/DesktopActivity.kt").read_text(encoding="utf-8")
        main_src = (REPO_ROOT / f"{ANDROID_KOTLIN}/MainActivity.kt").read_text(encoding="utf-8")
        cosmetic_real = self.run_guard(
            {
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": desktop_src.replace(
                    "COLLAPSED_FADE_DELAY_MS = 3_000L", "COLLAPSED_FADE_DELAY_MS = 250L", 1
                ),
                f"{ANDROID_KOTLIN}/MainActivity.kt": main_src.replace(
                    "postDelayed(logFlushRunnable, 150)", "postDelayed(logFlushRunnable, 250)", 1
                ),
            }
        )
        self.assertEqual(0, cosmetic_real.returncode, cosmetic_real.stdout + cosmetic_real.stderr)

    def test_minimal_compliant_tree_and_comments_pass(self) -> None:
        result = self.run_guard(
            {
                f"{ANDROID_KOTLIN}/display/placement/DeviceQuirkCatalog.kt": (
                    'class DeviceQuirkCatalog {\n'
                    '    companion object {\n'
                    '        const val R2_DEVICE = "darwin"\n'
                    '        const val TNT_DISPLAY_ID = 100003\n'
                    '    }\n'
                    '}\n'
                ),
                f"{ANDROID_KOTLIN}/display/placement/DesktopPlacementSelector.kt": (
                    "fun currentDeviceIdentity() =\n"
                    "    DeviceIdentity(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL)\n"
                ),
                f"{ANDROID_KOTLIN}/MainActivity.kt": (
                    "// darwin 100003 smallestScreenWidthDp DisplayEnvironmentPolicy\n"
                    "/* outer /* nested */ isDisplayLocal postDelayed(runnable, 250) */\n"
                    'val docsUrl = "https://example.com/docs"\n'
                    'val digest = "99c0b25ca100003f"\n'
                    'val label = "mode: ${docsUrl.ifEmpty { "smallestScreenWidthDp" }}"\n'
                    'val raw = """hello"""" // smallestScreenWidthDp\n'
                    'private val logFlushRunnable = Runnable { flushLogBuffer() }\n'
                    'private fun flushLogBuffer() { logBuffer.setLength(0) }\n'
                    'fun flushLogs() = mainHandler.postDelayed(logFlushRunnable, 250)\n'
                    'fun getDeviceInfo() = mapOf("model" to Build.MODEL, "brand" to Build.BRAND)\n'
                ),
                f"{ANDROID_KOTLIN}/display/DisplayRepository.kt": (
                    'const val DOC = "Build.MODEL"\n'
                ),
                f"{ANDROID_KOTLIN}/display/DesktopDisplayCoordinator.kt": (
                    "class DesktopDisplayCoordinator(\n"
                    "    val selector: DesktopPlacementSelector = DesktopPlacementSelector(DeviceQuirkCatalog()),\n"
                    ") {\n"
                    "    val isDisplayLocal = selection.mode == PlacementMode.DISPLAY_LOCAL\n"
                    "}\n"
                ),
                f"{ANDROID_KOTLIN}/display/policy/PresentationPolicy.kt": (
                    "val input = DesktopInputPolicy.resolve()\n"
                    "val size = DisplayProfilePolicy.renderSize(bounds, policy)\n"
                ),
                f"{ANDROID_KOTLIN}/display/input/DesktopInputPolicy.kt": (
                    "fun resolve() = externalInputRoute()\n"
                ),
                f"{ANDROID_KOTLIN}/display/refresh/DisplayRefreshPolicy.kt": (
                    "fun resolve() = supportedModes.filter { kotlin.math.abs(it.refreshRate - 60f) <= 0.5f }\n"
                ),
                f"{ANDROID_KOTLIN}/display/capability/AndroidPresentationEvidenceProbe.kt": (
                    "fun modes() = display.supportedModes.map { it.toEvidence() }\n"
                ),
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": (
                    "class DesktopActivity {\n"
                    "    companion object { private const val FADE_DELAY_MS = 250L; private const val TIMEOUT_MS = 250L * 4 }\n"
                                        "    private val fadeLambda: () -> Unit = { hideOverlay() }\n"
                    "    private val gate = DesktopReadinessGate {}\n"
                    "    private fun hideOverlay() {}\n"
                    "    private fun fadeCollapsedControl() = hideOverlay()\n"
                    "    private val fadeTyped: Runnable = Runnable { hideOverlay() }\n"
                    "    private val fadeObj: Runnable = object : Runnable { override fun run() { hideOverlay() } }\n"
                    "    fun launchSession() {}\n"
                    "    val refreshRateLimit = 60\n"
                    "    fun timeoutOk(delayMillis: Long) = handler.postDelayed(timeoutTask, delayMillis)\n"
                    "    fun namedFade() = collapsedFadeHandler.postDelayed(::fadeCollapsedControl, delayMillis = 250L)\n"
                    "    fun typedFade(a: Int) = when (a) { 0 -> collapsedFadeHandler.removeCallbacks(fadeTyped); else -> { collapsedFadeHandler.postDelayed(fadeTyped, 250); collapsedFadeHandler.postDelayed(fadeObj, 250); collapsedFadeHandler.postDelayed(fadeLambda, 250) } }\n"
                    "    fun timeoutConst() = handler.postDelayed(timeoutTask, TIMEOUT_MS)\n"
                    "    fun fadeUi() = collapsedFadeHandler.postDelayed(::fadeCollapsedControl, 250)\n"
                    "    fun fadeConst() = collapsedFadeHandler.postDelayed(::fadeCollapsedControl, FADE_DELAY_MS)\n"
                    "    fun animUi() = collapsedFadeHandler.postDelayed({ bar.animate().alpha(0f).start() }, 250)\n"
                    "    fun modes() = target?.supportedModes?.mapNotNull { it?.toEvidenceOrNull() }\n"
                    "    fun refresh(request: RefreshRequest) {\n"
                    "        var calls = 0\n"
                    "        applyRefreshRequest(request) { rate, modeId ->\n"
                    "            calls++\n"
                    "            preferredRefreshRate = rate\n"
                    "            window.attributes.preferredDisplayModeId = modeId\n"
                    "            logApplied()\n"
                    "        }\n"
                    "    }\n"
                    "}\n"
                ),
                f"{TERMUX_X11}/input/TouchInputHandler.java": (
                    "public class TouchInputHandler { private ActivePointerTracker tracker; }\n"
                ),
                f"{TERMUX_X11}/LorieView.java": (
                    "public class LorieView {\n"
                    "    Object modes(android.view.Display d) { float r = d.getMode().getRefreshRate(); return d.getSupportedModes(); }\n"
                    "    void non250Loop(android.os.Handler h, Runnable r) { for (long d : new long[] {4 * 250, 1000}) h.postDelayed(r, d); }\n"
                    "}\n"
                ),
                "app/lib/screens/display_settings_screen.dart": (
                    "// smartisan nx888j pq85a02 100_003\n"
                    "final sep = r'\\'; // don't use smallestScreenWidthDp\n"
                    "const title = 'Displays and scaling';\n"
                ),
            }
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_oem_identity_leakage_tokens_and_build_reads_fail(self) -> None:
        for relative, snippet in (
            (f"{ANDROID_KOTLIN}/MainActivity.kt", 'val isR2 = "darwin"'),
            (f"{ANDROID_KOTLIN}/view/DesktopActivity.kt", 'DesktopReadinessGate {}; val vendor = "Smartisan"'),
            ("app/lib/models/desktop_display.dart", "const model = 'NX888J';"),
            ("app/lib/state/app_state.dart", "const dev = 'pq85a02';"),
            (f"{ANDROID_KOTLIN}/display/DesktopDisplayCoordinator.kt", "val tnt = 100003"),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "val tnt = 100_003"),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "val m = Build.MODEL"),
            (f"{ANDROID_KOTLIN}/view/DesktopActivity.kt", "DesktopReadinessGate {}; val d = Build.DEVICE"),
            (f"{ANDROID_KOTLIN}/x11/X11InputController.kt", "val b = Build.BRAND"),
            (f"{TERMUX_X11}/LorieView.java", "String h = Build.HARDWARE;"),
            (f"{ANDROID_KOTLIN}/service/DroidDeskService.kt", "val m = Build.MANUFACTURER"),
            (f"{ANDROID_KOTLIN}/MainActivity.kt", 'val u = "https://a.test"; val d = Build.DEVICE'),
            (f"{ANDROID_KOTLIN}/MainActivity.kt", 'val b = Build.BRAND.lowercase() == "nubia"'),
            (f"{ANDROID_KOTLIN}/MainActivity.kt", "fun check() = when (Build.MODEL) { else -> Unit }"),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "import android.os.Build as DeviceBuild\nval m = DeviceBuild.MODEL"),
            (f"{ANDROID_KOTLIN}/MainActivity.kt", "import android.os.Build.*\nval m = MODEL"),
            (f"{TERMUX_X11}/LorieView.java", "import static android.os.Build.*;\nclass LorieView { String m = MODEL; }"),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "val tnt = 1_00_003"),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "val tnt = 0x186a3"),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                'val p = "${x ?: "foo/*"}"\nval r2 = "darwin"\n',
            ),
        ):
            with self.subTest(relative=relative, snippet=snippet):
                self.assert_fails_with({relative: snippet}, "oem identity leakage")

    def test_display_local_branching_leakage_fails(self) -> None:
        self.assert_fails_with(
            {f"{ANDROID_KOTLIN}/display/DisplayEnvironmentPolicy.kt": ""},
            "display-local branching leakage",
        )
        for relative, snippet in (
            ("app/lib/services/platform_bridge.dart", "DisplayEnvironmentPolicy"),
            (f"{ANDROID_KOTLIN}/MainActivity.kt", "if (coordinator.isDisplayLocal) return"),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; val local = mode == PlacementMode.DISPLAY_LOCAL",
            ),
            (
                f"{ANDROID_KOTLIN}/view/ExternalInputRelayActivity.kt",
                "val adapter = DisplayLocalRestartAdapter(selection)",
            ),
            (
                f"{ANDROID_KOTLIN}/service/DroidDeskService.kt",
                "val adapter = MovableSecondaryDisplayAdapter(selection)",
            ),
            (
                f"{ANDROID_KOTLIN}/service/DesktopStartupNotification.kt",
                "lateinit var controller: DisplayLocalSessionController<Unit>",
            ),
            (
                f"{ANDROID_KOTLIN}/x11/X11InputController.kt",
                "fun check() = coordinator.isDisplayLocal",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "val local = DISPLAY_LOCAL",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                'fun msg() = "$isDisplayLocal"',
            ),
            (
                f"{ANDROID_KOTLIN}/service/DesktopStartupNotification.kt",
                'fun check(s: Map<String, Any?>) = s["displayLocalMode"] == true',
            ),
        ):
            with self.subTest(relative=relative, snippet=snippet):
                self.assert_fails_with(
                    {relative: snippet},
                    "display-local branching leakage",
                )

    def test_misplaced_input_or_placement_policy_fails(self) -> None:
        for relative, snippet in (
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; val d = DesktopInputPolicy\n    .resolve(ctx)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/ExternalInputRelayActivity.kt",
                "val r = externalInputRoute(\n    pref,\n)",
            ),
            (
                f"{ANDROID_KOTLIN}/x11/X11InputController.kt",
                "val ref = DesktopInputPolicy::resolve",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "val s = DesktopPlacementSelector(quirks)",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "val q = DeviceQuirkCatalog()",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "val store = AndroidPlacementPreferenceStore(this)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; val c = DisplayProfilePolicy.classify(ctx)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; val p = DisplayProfilePolicy.resolve(ctx)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; val s = DisplayProfilePolicy.renderSize(bounds, policy)",
            ),
        ):
            with self.subTest(relative=relative, snippet=snippet):
                self.assert_fails_with(
                    {relative: snippet},
                    "misplaced input/placement policy",
                )

    def test_fixed_pointer_array_or_missing_tracker_fails(self) -> None:
        for relative, snippet in (
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "class TouchInputHandler { ActivePointerTracker t; int[] ids = new int[10]; }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] p = new boolean[10]; }",
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "class TouchInputHandler { ActivePointerTracker t; void f() { mPointers[0] = 1; } }",
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "class TouchInputHandler { ActivePointerTracker t; static final int MAX_POINTERS = 10; }",
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "class TouchInputHandler { ActivePointerTracker t; boolean bad(int pointerId) { return pointerId >= 10; } }",
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "class TouchInputHandler { ActivePointerTracker t; boolean ok(int pointerId) { return pointerId < 10; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void f(int pointerId) { active[pointerId] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "public class TouchInputHandler { void onTouch() {} }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "public final class InputEventSender { void sendTouchEvent() {} }",
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                'public class TouchInputHandler { String fake = "ActivePointerTracker"; }',
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "import com.termux.x11.input.ActivePointerTracker;\npublic class TouchInputHandler {}",
            ),
            (
                f"{TERMUX_X11}/input/TouchInputHandler.java",
                "",
            ),
        ):
            with self.subTest(relative=relative, snippet=snippet):
                self.assert_fails_with({relative: snippet}, "fixed pointer array")

    def test_startup_250ms_delay_or_missing_readiness_gate_fails(self) -> None:
        for relative, snippet in (
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity {\n"
                "    val gate = DesktopReadinessGate {}\n"
                "    fun start() {\n"
                "        handler.postDelayed(\n"
                "            { launchSession() },\n"
                "            250L,\n"
                "        )\n"
                "    }\n"
                "}\n",
            ),
            (
                f"{ANDROID_KOTLIN}/display/DesktopDisplayCoordinator.kt",
                "fun waitStart() = handler.postDelayed(runnable, 250)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun waitStart() = handler.postDelayed(runnable, delayMillis = 250L)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun sneakStart() = collapsedFadeHandler.postDelayed({ launchSession() }, 250)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun t() = timeoutHandler.postDelayed(runnable, 250)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun fadeUi() = handler.postDelayed(runnable, 250)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; val fadeTask = Runnable { launchSession() }; fun f() = collapsedFadeHandler.postDelayed(fadeTask, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity {\n    val g = DesktopReadinessGate {}\n    val fadeTask =\n        Runnable { launchSession() }\n    fun f() = collapsedFadeHandler.postDelayed(fadeTask, 250)\n}",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() = collapsedFadeHandler.postDelayed(unresolvedFadeTask, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() = collapsedFadeHandler.postDelayed({ server.start() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; val t = Runnable { launchSession() }; fun f() = collapsedFadeHandler.postDelayed((t), 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() = collapsedFadeHandler.postDelayed({ revealDesktop(true) }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() = collapsedFadeHandler.postDelayed({ bar.animate().withEndAction(::launchSession).start() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity {\n    val g = DesktopReadinessGate {}\n    fun fadeTask() = hideOverlay().also {\n        launchSession()\n    }\n    fun f() = collapsedFadeHandler.postDelayed(::fadeTask, 250)\n}",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() = collapsedFadeHandler.postDelayed({ bar.animate().setDuration(server.start()) }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; val fadeTask = Runnable { configure() }; fun configure() { launchSession() }; fun f() = collapsedFadeHandler.postDelayed(fadeTask, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun openLater() = mainHandler.postDelayed({ requestResolvedDesktopOpen(target) }, 250)",
            ),
            (
                f"{ANDROID_KOTLIN}/service/DroidDeskService.kt",
                "fun startLater() = handler.postDelayed({ startDesktop() }, 250)",
            ),
            (
                f"{ANDROID_KOTLIN}/service/DroidDeskService.kt",
                "fun waitStart() = handler.postDelayed(runnable, 250)",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { companion object { private const val STARTUP_DELAY_MS = 250L }; val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS) }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { private static final long STARTUP_DELAY_MS = 250L; void s(android.os.Handler h, Runnable r) { h.postDelayed(r, STARTUP_DELAY_MS); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { companion object { private const val STARTUP_DELAY_MS = 250 }; val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS.toLong()) }",
            ),
            (
                f"{TERMUX_X11}/MainActivity.java",
                "class MainActivity { void c(android.os.Handler h) { for (long delay : new long[] {250, 500}) h.postDelayed(() -> refresh(), delay); } }",
            ),
            (
                f"{TERMUX_X11}/MainActivity.java",
                "class MainActivity { void c(android.os.Handler h) { for (Long delay : java.util.List.of(0xFAL, 500L)) h.postDelayed(() -> refresh(), delay); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun c() { for (interval in listOf(250L, 500L)) handler.postDelayed({ launchSession() }, interval) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private var fadeTask: Runnable = Runnable {}; fun sneak() { fadeTask = Runnable { launchSession() }; collapsedFadeHandler.postDelayed(fadeTask, 250) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { fun onCreate() {} }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                'class DesktopActivity { val fake = "DesktopReadinessGate" }',
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "import com.orailnoor.droiddesk.display.readiness.DesktopReadinessGate\nclass DesktopActivity",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "",
            ),
        ):
            with self.subTest(relative=relative, snippet=snippet):
                self.assert_fails_with({relative: snippet}, "250 ms startup delay")

    def test_direct_refresh_mode_selection_fails(self) -> None:
        for relative, snippet in (
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; val m = display.supportedModes.firstOrNull { it.refreshRate == 60f }",
            ),
            (
                f"{ANDROID_KOTLIN}/display/DesktopDisplayCoordinator.kt",
                "val modes = display.supportedModes.filter { it.width == 1920 }",
            ),
            (
                f"{ANDROID_KOTLIN}/display/capability/AndroidPresentationEvidenceProbe.kt",
                "val m = display.supportedModes.minBy { it.modeId }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "val near60 = kotlin.math.abs(mode.refreshRate - 59.94f) < 0.5f",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "window.attributes.preferredDisplayModeId = 2",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun force() { window.attributes.preferredDisplayModeId = 1 }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity {\n"
                "    val gate = DesktopReadinessGate {}\n"
                "    fun bypass(req: RefreshRequest) {\n"
                "        applyRefreshRequest(req) { r, _ -> preferredRefreshRate = r }\n"
                "        window.attributes.preferredDisplayModeId = 2\n"
                "    }\n"
                "}\n",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun spoof() { fun applyRefreshRequest(x: Int) { window.attributes.preferredDisplayModeId = 2 } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun forceRate() { window.attributes.preferredRefreshRate = 60f }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun pick() { display.supportedModes.forEach { println(it) } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { Object pick(android.view.Display d) { return d.getSupportedModes()[0]; } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void pick(android.view.Display d) { for (android.view.Display.Mode m : d.getSupportedModes()) {} } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void pick() { for (android.view.Display.Mode m : getDisplay().getSupportedModes()) {} } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { Object stream() { return java.util.Arrays.stream(getDisplay().getSupportedModes()).findFirst(); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun pick() { val modes = display.supportedModes; modes.firstOrNull { it.physicalWidth == 1920 } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun pick(requestedRate: Float) = display.supportedModes.asSequence().first { it.refreshRate == requestedRate }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun pick() = display.supportedModes.let { it.firstOrNull { m -> m.physicalWidth == 1920 } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun pick() {\n    val modes = display\n        .supportedModes\n    modes.firstOrNull { it.physicalWidth == 1920 }\n}",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun shadow(req: RefreshRequest) { applyRefreshRequest(req) { rate, modeId -> preferredRefreshRate = rate; listOf(2).forEach { modeId -> window.attributes.preferredDisplayModeId = modeId } } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun shadowVal(req: RefreshRequest) { applyRefreshRequest(req) { rate, modeId -> val modeId = 2; preferredRefreshRate = rate; window.attributes.preferredDisplayModeId = modeId } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun shadowIt(req: RefreshRequest) { applyRefreshRequest(req) { rate, it -> preferredRefreshRate = rate; listOf(2).forEach { window.attributes.preferredDisplayModeId = it } } }",
            ),
            (
                f"{ANDROID_KOTLIN}/display/capability/AndroidPresentationEvidenceProbe.kt",
                "fun pick() = display.supportedModes.map { it.toEvidence() }.first { it.width == 1920 }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { boolean is60(android.view.Display.Mode m) { return m.getRefreshRate() == 60f; } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun shift(req: RefreshRequest) { applyRefreshRequest(req) { rate, modeId -> preferredRefreshRate = rate; preferredDisplayModeId = modeId + 1 } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun compound() { window.attributes.preferredDisplayModeId += 1 }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun scaleRate() { window.attributes.preferredRefreshRate *= 2f }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "DesktopReadinessGate {}; fun incMode() { window.attributes.preferredDisplayModeId++ }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity {\n"
                "    val gate = DesktopReadinessGate {}\n"
                "    fun multilineBypass(req: RefreshRequest) {\n"
                "        applyRefreshRequest(req) { rate, modeId ->\n"
                "            preferredRefreshRate = rate\n"
                "            preferredDisplayModeId = modeId\n"
                + ("            // comment line\n" * 40)
                + "                .plus(1)\n"
                "        }\n"
                "    }\n"
                "}\n",
            ),
        ):
            with self.subTest(relative=relative, snippet=snippet):
                self.assert_fails_with(
                    {relative: snippet},
                    "direct refresh-mode selection",
                )

    def test_smallest_screen_width_dp_fails_with_exact_line_after_block_comment(
        self,
    ) -> None:
        self.assert_fails_with(
            {
                f"{ANDROID_KOTLIN}/display/DisplayProfile.kt": (
                    "/*\n"
                    " * Multi-line header comment\n"
                    " */\n"
                    "fun isWide(config: Configuration) = config.smallestScreenWidthDp >= 600\n"
                ),
            },
            "smallestScreenWidthDp profile classification",
            expected_path_line=f"{ANDROID_KOTLIN}/display/DisplayProfile.kt:4:",
        )
        self.assert_fails_with(
            {
                f"{ANDROID_KOTLIN}/display/DisplayProfile.kt": (
                    'fun log(c: Configuration) = "${c.smallestScreenWidthDp}"\n'
                ),
            },
            "smallestScreenWidthDp profile classification",
        )


if __name__ == "__main__":
    unittest.main()
