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
        launcher_src = (REPO_ROOT / f"{ANDROID_KOTLIN}/display/DesktopDisplayLauncher.kt").read_text(encoding="utf-8")
        cosmetic_real = self.run_guard(
            {
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": desktop_src.replace(
                    "COLLAPSED_FADE_DELAY_MS = 3_000L", "COLLAPSED_FADE_DELAY_MS = 250L", 1
                ),
                f"{ANDROID_KOTLIN}/MainActivity.kt": main_src.replace(
                    "postDelayed(logFlushRunnable, 150)", "postDelayed(logFlushRunnable, 250)", 1
                ),
                f"{ANDROID_KOTLIN}/display/DesktopDisplayLauncher.kt": "private const val delayMillis = 250L\n" + launcher_src.replace(
                    "class DesktopDisplayLauncher(",
                    "private fun topFadeDuration(): Long { val delayMillis = 250L; return delayMillis }\nclass DesktopDisplayLauncher(",
                    1,
                ).replace(
                    "override fun scheduleTimeout(",
                    "private fun fadeDuration(): Long { val delayMillis = 250L; return delayMillis }\n    override fun scheduleTimeout(",
                    1,
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
                    'fun telemetry(mode: android.view.Display.Mode, timeoutSeconds: Int) {\n'
                    '    val actualRate = mode.refreshRate\n'
                    '    val loggingIntervalSeconds = 60\n'
                    '    require(mode.refreshRate > 0f && timeoutSeconds <= 60)\n'
                    '}\n'
                    'fun getDeviceInfo() = mapOf("model" to Build.MODEL, "brand" to Build.BRAND)\n'
                    'fun handleChannel(call: String, result: io.flutter.plugin.common.MethodChannel.Result) = when (call) {\n'
                    '    "getDeviceInfo" -> {\n'
                    '        result.success(mapOf("model" to Build.MODEL, "brand" to Build.BRAND))\n'
                    '    }\n'
                    '    else -> Unit\n'
                    '}\n'
                ),
                f"{ANDROID_KOTLIN}/display/DisplayRepository.kt": (
                    "import android.os.Build as SdkBuild\n"
                    'const val DOC = "Build.MODEL"\n'
                    "val isAtLeastR = SdkBuild.VERSION.SDK_INT >= SdkBuild.VERSION_CODES.R\n"
                ),
                f"{ANDROID_KOTLIN}/runtime/LinuxRuntime.kt": (
                    "import android.os.Build as `SdkBuild`\n"
                    "class LinuxRuntime {\n"
                    "    val sdk = `SdkBuild`.VERSION.SDK_INT\n"
                    "    private fun hideOverlay() {}\n"
                    "    fun fadeToast(collapsedFadeHandler: android.os.Handler) = collapsedFadeHandler.postDelayed({ hideOverlay() }, 250L)\n"
                    "}\n"
                ),
                f"{ANDROID_KOTLIN}/display/DesktopDisplayCoordinator.kt": (
                    "class DesktopDisplayCoordinator(\n"
                    "    val selector: DesktopPlacementSelector = DesktopPlacementSelector(DeviceQuirkCatalog()),\n"
                    ") {\n"
                    "    val isDisplayLocal = selection.mode == PlacementMode.DISPLAY_LOCAL\n"
                    "    private fun scheduleTimeout(ms: Long) {}\n"
                    "    fun unrelatedLocal() = scheduleTimeout(250L)\n"
                    "}\n"
                ),
                f"{ANDROID_KOTLIN}/display/DesktopDisplayLauncher.kt": (
                    "private const val delayMillis = 250L\n"
                    "fun android.os.Handler.scheduleTimeout(delayMillis: Long = 1000L, r: Runnable) { postDelayed(r, delayMillis) }\n"
                    "class UiHelper { fun scheduleTimeout(ms: Long, r: Runnable) {}; fun f(r: Runnable) { val h = UiHelper(); h.scheduleTimeout(250L, r) } }\n"
                    "class DesktopDisplayLauncher(\n"
                    "    private val onEvent: () -> Unit = {},\n"
                    "    private val extraPaddingForLongConstructorHeader: String = \"ok\",\n"
                    "    private val secondExtraFieldForLongConstructorHeader: Int = 0,\n"
                    ") {\n"
                    "    private fun fadeDuration(): Long { val delayMillis = 250L; return delayMillis }\n"
                    "    private fun android.os.Handler.extTimeout(delayMillis: Long = 1000L, timeout: Runnable) { postDelayed(timeout, delayMillis) }\n"
                    "    fun scheduleTimeout(delayMillis: Long = 1000L, timeout: Runnable) { handler.postDelayed(timeout, delayMillis) }\n"
                    "    private fun retryShadow(timeout: Runnable) { val d = 250L; listOf(1000L).forEach { d -> handler.postDelayed(timeout, d) } }\n"
                    "    private fun overwritten(timeout: Runnable) { var d = 250L; d = 1000L; handler.postDelayed(timeout, d) }\n"
                                    "    private fun safeCondAndMod(ready: Boolean, cfg: Long?, timeout: Runnable) { val d1 = if (ready) 500L else 1000L; val d2 = cfg ?: 1000L; var d3 = 250L; d3 %= 200L; handler.postDelayed(timeout, d1); handler.postDelayed(timeout, d2); handler.postDelayed(timeout, d3) }\n"
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
                f"{ANDROID_KOTLIN}/display/StartupTiming.kt": (
                    "package com.orailnoor.droiddesk.display\n"
                    "internal const val STARTUP_DELAY_MS = 250L\n"
                ),
                f"{ANDROID_KOTLIN}/view/SafeTiming.kt": (
                    "package com.orailnoor.droiddesk.view\n"
                    "internal const val STARTUP_DELAY_MS = 1000L\n"
                ),
                f"{ANDROID_KOTLIN}/view/WildcardShadowOk.kt": (
                    "package com.orailnoor.droiddesk.view\n"
                    "import com.orailnoor.droiddesk.display.*\n"
                    "class WildcardShadowOk { fun ok(handler: android.os.Handler) = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS) }\n"
                ),
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": (
                    "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\n"
                    "import com.orailnoor.droiddesk.display.STARTUP_DELAY_MS\n"
                    "class DesktopActivity {\n"
                    "    companion object { private const val FADE_DELAY_MS = 250L; private const val TIMEOUT_MS = 250L * 4; private const val SAFE_ARITH_MS = 500L + 500L; private const val delayMillis = 1000L }\n"
                    "    class CtorShadowOk(@param:[Suppress(\"UNUSED_PARAMETER\")] val FADE_DELAY_MS: Long = 1000L) { fun ok(handler: android.os.Handler) = handler.postDelayed({ launchSession() }, FADE_DELAY_MS) }\n"
                    "    class OuterDelay250CmpDefaultOk { private val delayMillis = 250L; fun later(flag: Boolean = 1 < 2, delayMillis: Long = 1000L) = handler.postDelayed({ launchSession() }, delayMillis); fun laterExpr(a: Int = 1, b: Int = 2, flag: Boolean = a < b && b > 0, delayMillis: Long = 1000L) { handler.postDelayed({ launchSession() }, delayMillis) } }\n"
                    "    class CtorSelfDefaultOk(delayMillis: Long = delayMillis) { init { handler.postDelayed({ launchSession() }, delayMillis) } }\n"
                    "    class CtorValSelfDefaultOk(val delayMillis: Long = delayMillis) { init { handler.postDelayed({ launchSession() }, delayMillis) } }\n"
                    "    class CtorEarlierParamShadowOk(FADE_DELAY_MS: Long = 1000L, effectiveDelay: Long = FADE_DELAY_MS) { init { handler.postDelayed({ launchSession() }, effectiveDelay) } }\n"
                    "    class CtorCompanionSelfDefaultOk(ownDelay: Long = ownDelay) { companion object { private const val ownDelay = 1000L }; init { handler.postDelayed({ launchSession() }, ownDelay) } }\n"
                    "    val fallbackProfile = ProfilePolicy.phoneDefaultProfile()\n"
                    "    fun safeBangDefaults(p: ProfilePolicy?) = p!!.defaults(ProfileKind.PHONE)\n"
                    "    val defaultSettings = ProfilePolicy.defaults(ProfileKind.PHONE)\n"
                    "    class OtherHelper(val onDone: () -> Unit = {}) {\n"
                    "        val renderSize: (Int, Int) -> Int = { a, b -> a + b }\n"
                    "        fun classify(x: Int) = x\n"
                    "        fun classify(onItem: () -> Unit, onPair: (Int, Int) -> Unit) = 1\n"
                    "        fun resolve(name: String) = name.length\n"
                    "    }\n"
                    "    interface ExtBase\n"
                    "    class ExtTarget @JvmOverloads constructor(val id: Int = 0) :\n"
                    "        ExtBase\n"
                    "    fun ExtTarget.resolve() = 2\n"
                    "    fun ExtBase?.classify() = 3\n"
                    "    class LocalRouter { fun check() = externalInputRoute(); fun externalInputRoute() = 0 }\n"
                    "    class ExtRouter\n"
                    "    fun ExtRouter.externalInputRoute() = 0\n"
                    "    fun routeSafe(r: ExtRouter) = with(r) { externalInputRoute() }\n"
                    "    fun extNoLocalSafe(ext: ExtTarget) = with(ProfilePolicy) { with(ext) { resolve(); classify() }; with(ExtTarget()) { resolve() } }\n"
                    "    fun scopeSafe(other: OtherHelper, ext: ExtTarget) = with(ProfilePolicy) { val resolve = 1; other.classify(1); other::classify; with(other) { classify(\n1,\n); classify({}, { _, _ -> }); resolve(\"phone\"); renderSize(1, 2) }; other.run { classify(1) }; other.apply { classify(1) }; with(ext) { resolve() }; defaults(ProfileKind.PHONE) }\n"
                    "    fun annotatedParamShadowOk(@Suppress(\"UNUSED_PARAMETER\", \"SHADOWING\") FADE_DELAY_MS: Long = 1000L) = handler.postDelayed({ launchSession() }, FADE_DELAY_MS)\n"
                    "    fun importedConstShadowOk(STARTUP_DELAY_MS: Long = 1000L) = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS)\n"
                    "    fun shadowParamSafe(ProfilePolicy: OtherHelper) { val x = ProfilePolicy; x.classify(1); listOf(DisplayProfilePolicy).forEach { ProfilePolicy: DisplayProfilePolicy -> ProfilePolicy.defaults(ProfileKind.PHONE) } }\n"
                    "    fun earlierClobberSafe(collapsedFadeView: android.view.MotionEvent) {}\n"
                    "    private val fadeLambda: () -> Unit = { hideOverlay() }\n"
                    "    private val gate = DesktopReadinessGate {}\n"
                    "    private fun hideOverlay() {}\n"
                    "    private fun android.view.View.fadeOutOverlay() { alpha = 0f }\n"
                    "    private fun fadeCollapsedControl() = hideOverlay()\n"
                    "    lateinit var lateView: android.view.View\n"
                    "    private val fadeTyped: Runnable = Runnable { hideOverlay() }\n"
                    "    private val fadeAlias = fadeTyped\n"
                    "    private val fadeObj: Runnable = object : Runnable { override fun run() { hideOverlay() } }\n"
                    "    fun launchSession() {}\n"
                    "    val refreshRateLimit = 60\n"
                    "    fun telemetry(mode: android.view.Display.Mode) {\n"
                    "        run { val value = mode.refreshRate; println(value) }\n"
                    "        run { val value = 30; require(value <= 60) }\n"
                    "        val outerRate = mode.refreshRate; println(outerRate)\n"
                    "        run { val outerRate = 30; require(outerRate <= 60) }\n"
                    "    }\n"
                    "    fun timeoutOk(delayMillis: Long) = handler.postDelayed(timeoutTask, delayMillis)\n"
                    "    fun tokenThreeArgOk(runnable: Runnable, tok: Any) { handler.postDelayed(runnable, 250, 1000L); handler.postDelayed(1000L, 250) { launchSession() }; handler.postDelayed(token = 250, delayInMillis = 1000L) { launchSession() }; collapsedFadeHandler.postDelayed(fadeTyped, tok, 250L) }\n"
                    "    fun viewKtxExtOk(collapsedFadeView: android.view.View) { collapsedFadeView.postDelayed(250L, ::fadeCollapsedControl); collapsedFadeView.postDelayed(250L, { hideOverlay() }); collapsedFadeView.postDelayed(FADE_DELAY_MS, fadeLambda); collapsedFadeView.postDelayed(250L, collapsedFadeView::fadeOutOverlay); collapsedFadeHandler.postDelayed(250L) { collapsedFadeView.fadeOutOverlay() }; collapsedFadeHandler.apply { postDelayed(250L) { hideOverlay() } }; collapsedFadeHandler.postDelayed(fadeAlias, 250L); collapsedFadeView.postDelayed(250L) { collapsedFadeView.animate().alpha(0f).withEndAction { hideOverlay() }.start() } }\n"
                    "    fun namedFade() = collapsedFadeHandler.postDelayed(::fadeCollapsedControl, delayMillis = 250L)\n"
                    "    fun samFade() = collapsedFadeHandler.postDelayed(Runnable { hideOverlay() }, 250)\n"
                    "    fun objInlineFade() = collapsedFadeHandler.postDelayed(object : Runnable { override fun run() { hideOverlay() } }, 250)\n"
                    "    fun typedFade(a: Int) = when (a) { 0 -> collapsedFadeHandler.removeCallbacks(fadeTyped); else -> { collapsedFadeHandler.postDelayed(fadeTyped, 250); collapsedFadeHandler.postDelayed(fadeObj, 250); collapsedFadeHandler.postDelayed(fadeLambda, 250) } }\n"
                    "    fun timeoutConst() = handler.postDelayed(timeoutTask, TIMEOUT_MS)\n"
                    "    fun non250ArithOk() { handler.postDelayed(timeoutTask, 500L * 2); handler.postDelayed(timeoutTask, 1200L - 200L); handler.postDelayed(timeoutTask, 2000L / 2); handler.postDelayed(timeoutTask, SAFE_ARITH_MS) }\n"
                    "    fun fadeUi() = collapsedFadeHandler.postDelayed(::fadeCollapsedControl, 250)\n"
                    "    fun fadeConst() = collapsedFadeHandler.postDelayed(::fadeCollapsedControl, FADE_DELAY_MS)\n"
                    "    fun animUi() = collapsedFadeHandler.postDelayed({ bar.animate().alpha(0f).\n        start(); hideOverlay() }, 250)\n"
                    "    fun laterClobberSafe(collapsedFadeView: android.view.MotionEvent) {}\n"
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
                    "public class TouchInputHandler {\n"
                    "    private ActivePointerTracker tracker;\n"
                    "    void handle(android.view.MotionEvent e, int[] samples) {\n"
                    "        if (e != null) { int id = e.getPointerId(0); tracker.begin(id); int dense = e.findPointerIndex(id); if (dense >= 0) samples[dense] = 1; }\n"
                    "        else { for (int id = 0; id < samples.length; id++) { samples[id] = 1; } }\n"
                    "        if (e != null) { int slot = 0, pid = e.getPointerId(0); if (slot < samples.length) samples[slot] = pid; }\n"
                    "        if (e != null) { int v = e.getPointerId(0); tracker.begin(v); v = e.findPointerIndex(v); if (v >= 0 && v < samples.length) samples[v] = 1; }\n"
                    "        int index = e != null ? e.findPointerIndex(e.getPointerId(0)) : -1;\n"
                    "        float[] positions = new float[e != null ? e.getPointerCount() : 0];\n"
                    "        if (index >= 0 && index < positions.length) positions[index] = e.getX(index);\n"
                    "    }\n"
                    "}\n"
                ),
                f"{TERMUX_X11}/input/KotlinPointerMapHandler.kt": (
                    "package com.termux.x11.input\n"
                    "import android.util.SparseArray\n"
                    "import java.util.TreeMap\n"
                    "import java.util.concurrent.ConcurrentHashMap\n"
                    "class KotlinPointerMapHandler(\n"
                    "    private val ctorMap: MutableMap<Int, Boolean> = mutableMapOf(),\n"
                    ") {\n"
                    "    private val pointers: MutableMap<Int, android.graphics.PointF> = mutableMapOf()\n"
                    "    private val mPointers = SparseArray<android.graphics.PointF>()\n"
                    "    fun record(e: android.view.MotionEvent, pointerId: Int, pt: android.graphics.PointF) {\n"
                    "        val id = e.getPointerId(0)\n"
                    "        pointers[pointerId] = pt\n"
                    "        pointers[e.getPointerId(0)] = pt\n"
                    "        pointers[0] = pt\n"
                    "        mPointers[id] = pt\n"
                    "        ctorMap[pointerId] = true\n"
                    "        lateSparse[id] = true\n"
                    "        val byFactory = mapOf(pointerId to pt)\n"
                    "        val byHash = hashMapOf<Int, Boolean>()\n"
                    "        val byLinked = linkedMapOf<Int, Int>()\n"
                    "        val bySorted = sortedMapOf<Int, Int>()\n"
                    "        val byEmpty = emptyMap<Int, Int>()\n"
                    "        val byConc = ConcurrentHashMap<Int, Boolean>()\n"
                    "        val sBool = android.util.SparseBooleanArray()\n"
                    "        val sInt = android.util.SparseIntArray(16)\n"
                    "        val sLong = android.util.SparseLongArray()\n"
                    "        byHash[e.getPointerId(0)] = byFactory[pointerId] != null\n"
                    "        byLinked[id] = byEmpty[pointerId] ?: 0\n"
                    "        bySorted[pointerId] = 1\n"
                    "        byConc[id] = true\n"
                    "        sBool[pointerId] = true\n"
                    "        sInt[e.getPointerId(0)] = 1\n"
                    "        sLong[id] = 1L\n"
                    "        val active = mutableMapOf<Int, Boolean>(); val hop1 = active; val hop2 = (hop1); val pointers = hop2; pointers[pointerId] = true\n"
                    "        val thisAlias = this.mPointers; thisAlias[id] = pt\n"
                    "        mutableVar[pointerId] = true\n"
                    "    }\n"
                    "    private var mutableVar = mutableMapOf<Int, Boolean>()\n"
                    "    fun shadowVar() { var mutableVar = 0; mutableVar = 1; val box = object { var mutableVar = 0 }; box.\n        mutableVar = 2 }\n"
                    "    fun lookup(@Suppress(\"UNUSED\") m: Map<Int, Boolean>, tm: TreeMap<Int, Boolean>, pointers: IntArray, pointerId: Int): Any? {\n"
                    "        val unit = \"100$µs\"\n"
                    "        var swapped: Map<Int, Boolean> = emptyMap(); swapped = m; swapped[pointerId]\n"
                    "        val anon = object { private val mPointers = android.util.SparseIntArray(); fun g(id: Int) = this.mPointers[id] }\n"
                    "        this.pointers[pointerId]\n"
                    "        return if (unit.isNotEmpty() && anon.g(pointerId) == 0) m[pointerId] ?: tm[pointerId] else null\n"
                    "    }\n"
                    "    private val lateSparse = SparseArray<Boolean>()\n"
                    "}\n"
                ),
                f"{TERMUX_X11}/LorieView.java": (
                    "import static androidx.core.os.HandlerCompat.postDelayed;\n"
                    "public class LorieView {\n"
                    "    Object modes(android.view.Display d) { float r = d.getMode().getRefreshRate(); return d.getSupportedModes(); }\n"
                    "    void non250Loop(android.os.Handler h, Runnable r) { for (long d : new long[] {4 * 250, 1000}) h.postDelayed(r, d); }\n"
                    "    void schedule(android.os.Handler h, Runnable r, long delay) { delay = 1000L; h.postDelayed(r, delay); }\n"
                    "    void bootOverwriteOk(android.os.Handler h, Runnable r) { schedule(h, r, 250L); }\n"
                    "    void compatOk(android.os.Handler h, Runnable r) { android.os.Handler out; HandlerCompat.postDelayed(out = h, r, 250, 1000L); postDelayed(h, r, 250, 1000L); }\n"
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
            (f"{ANDROID_KOTLIN}/MainActivity.kt", 'fun shouldRestart() = mapOf("model" to Build.MODEL)["model"] == "OtherDevice"'),
            (f"{ANDROID_KOTLIN}/MainActivity.kt", 'fun getDeviceInfo() = mapOf("model" to Build.MODEL)\nfun shouldRestart() = getDeviceInfo()["model"] == "OtherDevice"'),
            (f"{ANDROID_KOTLIN}/MainActivity.kt", 'fun handle(c: String) = when (c) { "getDeviceInfo" -> { val m = mapOf("model" to Build.MODEL); m["model"] == "OtherDevice" } else -> false }'),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "import android.os.Build as DeviceBuild\nval m = DeviceBuild.MODEL"),
            (f"{ANDROID_KOTLIN}/runtime/LinuxRuntime.kt", "import android.os.Build as `SdkBuild`\nval d = `SdkBuild`.DEVICE"),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "typealias SdkBuild = android.os.Build\nval b = SdkBuild.BRAND"),
            (f"{ANDROID_KOTLIN}/display/DisplayRepository.kt", "import android.os.Build.MODEL as DeviceModel\nval m = DeviceModel"),
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
        self.assert_fails_with(
            {
                f"{ANDROID_KOTLIN}/util/UnrelatedExt.kt": "package com.orailnoor.droiddesk.util\nfun Any.classify(a: Boolean, b: Int, c: Boolean) = 0\n",
                f"{ANDROID_KOTLIN}/view/PrivExt.kt": "package com.orailnoor.droiddesk\nprivate fun Any.classify(a: Boolean, b: Int, c: Boolean) = 0\n",
                f"{ANDROID_KOTLIN}/MainActivity.kt": "package com.orailnoor.droiddesk\nimport com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass EmptyHelper\nfun bad(other: EmptyHelper) = with(ProfilePolicy) { with(other) { classify(true, 600, false) } }\n",
            },
            "misplaced input/placement policy",
        )
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
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.input.DesktopInputPolicy as InputPolicy\nval ref = InputPolicy::resolve",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass DesktopActivity { val g = DesktopReadinessGate {}; val ref = ProfilePolicy::classify }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy.classify\nval c = classify(ctx)",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.input.externalInputRoute as route\nval r = route(pref)",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "typealias ProfilePolicy = com.orailnoor.droiddesk.display.DisplayProfilePolicy\nval p = ProfilePolicy.resolve(ctx)",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nfun bad() = with(ProfilePolicy) { classify(ctx) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.input.DesktopInputPolicy as `InputPolicy`\nval ref = `InputPolicy`::resolve",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.input.DesktopInputPolicy as InputPolicy\nfun bad() = (InputPolicy).resolve(ctx)",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "val a = DisplayProfilePolicy\nval b = a\nval c = b\nval d = c.classify(ctx)",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "val policy = DesktopInputPolicy\nfun misplaced(policy: Int) = DesktopInputPolicy::resolve",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass DesktopActivity { val g = DesktopReadinessGate {}; val policy = ProfilePolicy; fun misplaced(policy: Int) = ProfilePolicy::classify }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun misplaced(ProfilePolicy: Int) = DisplayProfilePolicy.classify(ctx) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun beforeShadow() { println(0); DesktopInputPolicy.resolve(ctx); val DesktopInputPolicy = 1 }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun beforeShadow() { println(0); DisplayProfilePolicy.classify(ctx); val DisplayProfilePolicy = 1 } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun typedLocal() { val p: DesktopInputPolicy = obtain(); p.resolve(ctx) }",
            ),
            (
                f"{TERMUX_X11}/PolicyCaller.java",
                "class PolicyCaller { Object bad(Object ctx) { return DesktopInputPolicy.INSTANCE.resolve(ctx); } }",
            ),
            (
                f"{TERMUX_X11}/PolicyCaller.java",
                "class PolicyCaller { Object bad(Object ctx) { return DisplayProfilePolicy.INSTANCE.classify(ctx); } }",
            ),
            (
                f"{TERMUX_X11}/PolicyCaller.java",
                "import static com.orailnoor.droiddesk.display.input.DesktopInputPolicy.INSTANCE;\nclass PolicyCaller { Object bad(Object ctx) { return INSTANCE.resolve(ctx); } }",
            ),
            (
                f"{TERMUX_X11}/PolicyCaller.java",
                "class PolicyCaller { Object bad(Object ctx) { DesktopInputPolicy p = DesktopInputPolicy.INSTANCE; return p.resolve(ctx); } }",
            ),
            (
                f"{TERMUX_X11}/PolicyCaller.java",
                "class PolicyCaller { Object bad(Object pref) { return ExternalInputPolicyKt.externalInputRoute(pref); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass EmptyHelper\nfun fallback(empty: EmptyHelper) = with(ProfilePolicy) { with(empty) { classify(ctx) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass OtherHelper { fun classify() = 1 }\nfun fallbackRender(other: OtherHelper) = with(ProfilePolicy) { with(other) { classify(); renderSize(bounds, policy) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass OtherHelper { fun classify() = 1 }\nfun fallbackArity(other: OtherHelper) = with(ProfilePolicy) { with(other) { classify(); classify(true, 600, false) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass EmptyHelper\nclass OtherHelper { fun classify() = 1 }\nfun bad(other: EmptyHelper) = with(ProfilePolicy) { with(other) { classify(true, 600, false) } }\nfun ok(other: OtherHelper) = other.classify()",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nfun nonCallableVal(ctx: Any) = with(ProfilePolicy) { val resolve = 1; resolve(ctx) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nfun thisBypass(ctx: Any) = with(ProfilePolicy) { fun resolve(x: Any) = 1; this.resolve(ctx) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "import com.orailnoor.droiddesk.display.DisplayProfilePolicy as ProfilePolicy\nclass EmptyHelper\ndata class OtherHelper(val id: Int) { fun classify(a: Boolean, b: Int, c: Boolean) = 0 }\nfun stealBody(empty: EmptyHelper) = with(ProfilePolicy) { with(empty) { classify(true, 600, false) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "class Helper { val classify = 1 }\nfun hideByNonCallableProp(h: Helper) = with(DisplayProfilePolicy) { with(h) { classify(true, 600, false) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "class Helper { val classify: (Int) -> Int = { it } }\nfun hideByWrongArityProp(h: Helper) = with(DisplayProfilePolicy) { with(h) { classify(true, 600, false) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "class Helper { fun zero() = 1; val classify = ::zero }\nfun hideByZeroArityRefProp(h: Helper) = with(DisplayProfilePolicy) { with(h) { classify(true, 600, false) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun localValRefBypass() = with(DisplayProfilePolicy) { val classify = 1; val r = ::classify }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badBang(p: DisplayProfilePolicy?) = p!!.classify(true, 600, false)",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badBangRef(p: DesktopInputPolicy?) = p!!::resolve",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badBangAlias(p: DisplayProfilePolicy?) { val q = p!!; q.classify(true, 600, false) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badBangRun(p: DisplayProfilePolicy?) = p!!.run { classify(true, 600, false) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badBangWith(p: DisplayProfilePolicy?) = with(p!!) { classify(true, 600, false) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badBangLet(p: DisplayProfilePolicy?) = p?.let { it!!.classify(true, 600, false) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badBangFnRef() { val fn: (() -> Int)? = ::externalInputRoute; fn!!() }",
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
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void f(android.view.MotionEvent event) { int id = event.getPointerId(0); active[id] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void f(android.view.MotionEvent event) { int idx = event.getActionIndex(), id = event\n.getPointerId(idx); active[id] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void f(android.view.MotionEvent e) { active[e.getPointerId(e.getActionIndex())] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void f(android.view.MotionEvent e) { for (int id = e.getPointerId(0); id >= 0; id--) { active[id] = true; } } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void f(android.view.MotionEvent e) { int id = e.getPointerId(0); e.findPointerIndex(active[id] ? 1 : 0); } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; int activeId; boolean[] active = new boolean[16]; void a(android.view.MotionEvent e) { activeId = e.getPointerId(0); } void b() { active[activeId] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; int activeId; boolean[] active = new boolean[16]; void a(android.view.MotionEvent e) { this.activeId = e.getPointerId(0); } void b() { active[activeId] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; int activeId; boolean[] active = new boolean[16]; void a(android.view.MotionEvent e, int activeId) { this.activeId = e.getPointerId(0); } void b() { active[this.activeId] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; int activeId; boolean[] active = new boolean[16]; class Inner { int activeId = 0; void a(android.view.MotionEvent e) { InputEventSender.this.activeId = e.getPointerId(0); } } void b() { active[activeId] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void a(android.view.MotionEvent e, boolean c) { int id = c\n ? e.getPointerId(0) : 0; active[id] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void a(android.view.MotionEvent e, boolean reset) { int id = e.getPointerId(0); if (reset) id = 0; active[id] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void a(android.view.MotionEvent e, boolean reset) { int id = e.getPointerId(0); if (reset) {} else id = 0; active[id] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; boolean[] active = new boolean[16]; void chain(android.view.MotionEvent e) { int a0 = e.getPointerId(0); int a1 = a0; int a2 = a1; int a3 = a2; int a4 = a3; int a5 = a4; int a6 = a5; active[a6] = true; } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val pointers = IntArray(16); fun f(pointerId: Int) { pointers[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val active: BooleanArray = BooleanArray(16); fun f(e: android.view.MotionEvent) { active[e.getPointerId(0)] = true } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val slots: Array<Any?> = arrayOfNulls(16); fun f(pointerId: Int) { slots[pointerId] = null } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val slots = arrayOf(1, 2, 3); fun f(pointerId: Int) { slots[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val pointers = mutableMapOf<Int, Int>(); fun f(pointerId: Int) { val pointers = IntArray(16); pointers[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val pointers = mutableMapOf<Int, Int>(); fun f(pointerId: Int) { listOf(IntArray(16)).forEach { pointers -> pointers[pointerId] = 1 } } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val pointers = mutableMapOf<Int, Int>(); fun f(pointerId: Int) { for (pointers in listOf(IntArray(16))) { pointers[pointerId] = 1 } } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val pointers = mutableMapOf<Int, Int>().values.toIntArray(); fun f(pointerId: Int) { pointers[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val pointers = mutableMapOf<Int, Int>(); fun f(pointerId: Int) { if (pointerId < 10) pointers[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val pointers = mutableMapOf<Int, Int>(); fun f(@Suppress(\"UNUSED\") pointers: IntArray, pointerId: Int) { pointers[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { private val pointers = mutableMapOf<Int, Int>(); val l = object { private val pointers = IntArray(16); fun f(e: android.view.MotionEvent) { this.pointers[e.getPointerId(0)] = 1 } } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { val active = mutableMapOf<Int, Boolean>(); fun f(pointerId: Int) { val active = IntArray(16); val pointers = active; pointers[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { fun f(pointerId: Int) { var pointers = mutableMapOf<Int, Int>(); pointers = IntArray(16); pointers[pointerId] = 1 } }",
            ),
            (
                f"{TERMUX_X11}/input/KotlinBadPointers.kt",
                "class KotlinBadPointers { fun f(pointerId: Int) { pointers = IntArray(16); pointers[pointerId] = 1 }; var pointers = mutableMapOf<Int, Int>() }",
            ),
            (
                f"{TERMUX_X11}/input/InputEventSender.java",
                "class InputEventSender { ActivePointerTracker t; java.util.Map<Integer, Integer>[] pointers; void f(int pointerId) { pointers[pointerId] = null; } }",
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
        self.assert_fails_with(
            {
                f"{ANDROID_KOTLIN}/display/DesktopDisplayLauncher.kt": "class DesktopDisplayLauncher { private val tag = 1\nfun scheduleTimeout(delayMillis: Long, action: () -> Unit) { handler.postDelayed(Runnable(action), delayMillis) } }",
                f"{ANDROID_KOTLIN}/display/Bridge.kt": "class Bridge(val p: DesktopDisplayLauncher) { fun forward(ms: Long, a: () -> Unit) = p.scheduleTimeout(ms, a) }",
                f"{ANDROID_KOTLIN}/display/DesktopDisplayCoordinator.kt": "class DesktopDisplayCoordinator { val d = 250L; fun boot(b: Bridge) = b.forward(d) { proceed() }; fun unrelated(b: Boolean) = b }",
            },
            "250 ms startup delay",
        )
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
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, 2_50L) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun schedule(ms: Long) = handler.postDelayed({ launchSession() }, ms); fun boot() = schedule(2_5_0_L) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun schedule(ms: Long) = handler.postDelayed({ launchSession() }, ms); fun boot() = schedule(2_50u.toLong()) }",
            ),
            (
                f"{TERMUX_X11}/MainActivity.java",
                "class MainActivity { void c(android.os.Handler h) { h.postDelayed(() -> refresh(), 0_372L); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() { for (d in listOf(0x0_f_aL)) handler.postDelayed({ launchSession() }, d) } }",
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
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() = collapsedFadeHandler.postDelayed(Runnable { launchSession() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { private object StartupDelays { const val DELAY_MS = 250L }; val g = DesktopReadinessGate {}; fun f() = handler.postDelayed({ launchSession() }, StartupDelays.DELAY_MS) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() { val d =\n    250L\nhandler.postDelayed({ launchSession() }, d) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { companion object { private const val STARTUP_DELAY_MS = 250L }; val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, DesktopActivity.STARTUP_DELAY_MS) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun startLater(delayMillis: Long = 250L, other: Int = 1) { handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { private object StartupDelays { const val DELAY_MS = 250L }; val g = DesktopReadinessGate {}; fun f() { val local = StartupDelays.DELAY_MS; val hint = local; handler.postDelayed({ launchSession() }, local) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun f() { var d: Long = 0L; d = 250L; handler.postDelayed({ launchSession() }, d) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "private const val delayMillis = 250L\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun sneak(delayMillis: Long = delayMillis) { handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun chain(a: Long = 250L, b: Long = a) { handler.postDelayed({ launchSession() }, b) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun tokBad(r: Runnable, tok: Any) { handler.postDelayed(r, tok, 250L) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun ktxBad(tok: Any) { handler.postDelayed(250L, tok) { launchSession() } } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { long lastDelay; void s(android.os.Handler h, Runnable r) { h.postDelayed(r, lastDelay = 250L); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun delegExt(helper: Any) = collapsedFadeHandler.postDelayed({ helper.proceed() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private object Helper { fun go() { launchSession() } }; fun delegObj() = collapsedFadeHandler.postDelayed({ Helper.go() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun go() { launchSession() }; fun delegThis() = collapsedFadeHandler.postDelayed({ this@DesktopActivity.go() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun step(b: () -> Unit) { launchSession() }; fun delegTrail() = collapsedFadeHandler.postDelayed({ step {} }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; val bad = Runnable { launchSession() }; val fadeAlias = bad; fun delegAlias() = collapsedFadeHandler.postDelayed(fadeAlias, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; val bad = Runnable { launchSession() }; fun delegEnd() = collapsedFadeHandler.postDelayed({ bar.animate().withEndAction(bad).start() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun delegRef(h: Any) = collapsedFadeHandler.postDelayed({ bar.animate().withEndAction(h::proceed).start() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun a() = b(); fun b() = c(); fun c() = d(); fun d() = e(); fun e() = f(); fun f() { launchSession() }; fun deep() = collapsedFadeHandler.postDelayed(::a, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun scheduleLater(ms: Long, r: Runnable) { handler.postDelayed(r, ms) }; fun p1(ms: Long, r: Runnable) = scheduleLater(ms, r); fun p2(ms: Long, r: Runnable) = p1(ms, r); fun p3(ms: Long, r: Runnable) = p2(ms, r); fun p4(ms: Long, r: Runnable) = p3(ms, r); fun p5(ms: Long, r: Runnable) = p4(ms, r); fun wrap(r: Runnable) = p5(250L, r) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; class Helper { fun isEmpty(): Boolean { launchSession(); return true } }; fun spoofZero(h: Helper) = collapsedFadeHandler.postDelayed({ h.isEmpty() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun proceed(x: Int) {}; fun decoyLocal(h: Any) = collapsedFadeHandler.postDelayed({ h.proceed(0) }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun android.os.Handler.startLater(delayMillis: Long = 250L) { postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun android.os.Handler.startLater(delayMillis: Long) { postDelayed({ launchSession() }, delayMillis) }; fun boot(handler: android.os.Handler) = handler.startLater(250L) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; val bad = Runnable { launchSession() }; val fadeAlias = this.bad; fun f() = collapsedFadeHandler.postDelayed(fadeAlias, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun bang(helper: Any?) = collapsedFadeHandler.postDelayed({ helper!!.proceed() }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun proceed() {}; fun parenRef(h: Any) = collapsedFadeHandler.postDelayed({ bar.animate().withEndAction((h)::proceed).start() }, 250) }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                'class LorieView { void strTok(android.os.Handler h, Runnable r) { HandlerCompat.postDelayed(h, r, "startup_token", 250L); } }',
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                'class DesktopActivity { val g = DesktopReadinessGate {}; private fun scheduleLater(tag: String, delayMs: Long, r: Runnable) { handler.postDelayed(r, delayMs) }; fun boot(r: Runnable) = scheduleLater("boot", 250L, r) }',
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity(private val a: () -> Unit = {}, private val b: String = \"long_header_padding_value_1\", private val c: String = \"long_header_padding_value_2\") { companion object { private const val STARTUP_DELAY_MS = 250L }; val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, DesktopActivity.STARTUP_DELAY_MS) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "var topDelay = 0L\nclass DesktopActivity(val ctorDelay: Long = 250L) { val g = DesktopReadinessGate {}; fun boot() { topDelay = 250L; handler.postDelayed({ launchSession() }, topDelay) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity(val ctorDelay: Long = 250L) { val g = DesktopReadinessGate {}; fun boot() = handler.postDelayed({ launchSession() }, this.ctorDelay) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun android.view.View.dismiss() {}; fun prior(helper: android.view.View) {}; fun spoof(x: Any) { val helper = x; collapsedFadeHandler.postDelayed({ helper.dismiss() }, 250) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun proceed(x: Int) {}; fun scopeSpoof(h: Any) = collapsedFadeHandler.postDelayed({ h.run { proceed(0) } }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun android.view.View.dismiss() {}; fun letSpoof(h: Any) = collapsedFadeHandler.postDelayed({ h.let { it.dismiss() } }, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; private fun android.view.View.dismiss() {}; fun spoofExt(helper: Any) = collapsedFadeHandler.postDelayed({ helper.dismiss() }, 250); fun other(helper: android.view.View) {} }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "import static androidx.core.os.HandlerCompat.postDelayed;\nclass LorieView { void twoArg(Runnable r) { postDelayed(r, 250); } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void helper(android.os.Handler h, Runnable r, long d) { h.postDelayed(r, d); } void boot(android.os.Handler h, Runnable r) { helper(h, r, 250L); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/runtime/LinuxRuntime.kt",
                "class LinuxRuntime { fun boot(handler: android.os.Handler) = handler.postDelayed({ launchRuntime() }, 250L) }",
            ),
            (
                f"{ANDROID_KOTLIN}/compositor/CompositorService.kt",
                "class CompositorService { fun start(handler: android.os.Handler, r: Runnable) = handler.postDelayed(r, 250) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "private const val delayMillis = 250L\nclass DesktopActivity(delayMillis: Long = delayMillis) { val g = DesktopReadinessGate {}; init { handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "private const val delayMillis = 250L\nclass DesktopActivity(val delayMillis: Long = delayMillis) { val g = DesktopReadinessGate {}; init { handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity(val baseDelay: Long = 250L, delayMillis: Long = baseDelay) { val g = DesktopReadinessGate {}; companion object { private const val baseDelay = 1000L }; init { handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity(baseDelay: Long = 250L, delayMillis: Long = baseDelay) { val g = DesktopReadinessGate {}; companion object { private const val baseDelay = 1000L }; init { handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, 500L / 2) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, 125L * 2) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, 200L + 50L) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, 300L - 50L) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, (100 + 25) * 2L) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, 125L shl 1) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { companion object { private const val STARTUP_DELAY_MS = 500L / 2 }; val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { companion object { private const val HALF_DELAY_MS = 125L }; val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, HALF_DELAY_MS * 2) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { private val delayMillis = 1000L; val g = DesktopReadinessGate {}; fun later(flag: Boolean = 1 < 2, delayMillis: Long = 500L / 2) = handler.postDelayed({ launchSession() }, delayMillis) }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void schedule(android.os.Handler h, Runnable r, long delay, boolean c) { if (c) delay = 1000L; h.postDelayed(r, delay); } void boot(android.os.Handler h, Runnable r) { schedule(h, r, 250L, false); } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void schedule(android.os.Handler h, Runnable r, long delay) { delay = 250L; h.postDelayed(r, delay); } void boot(android.os.Handler h, Runnable r) { schedule(h, r, 1000L); } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void helper(android.os.Handler h, Runnable r, long d) { h.postDelayed(r, d); } void boot(android.os.Handler h, Runnable r) { helper(h, r, 500L / 2); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s(ready: Boolean) { val delayMillis = if (ready) 250L else 1000L; handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity {\n    val g = DesktopReadinessGate {}\n    fun s(ready: Boolean) {\n        val delayMillis = if (ready)\n            250L\n        else\n            1000L\n        handler.postDelayed({ launchSession() }, delayMillis)\n    }\n}",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s(configuredDelay: Long?) { val delayMillis = configuredDelay ?: 250L; handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun getConfiguredDelay(): Long? = null; fun s() = handler.postDelayed({ launchSession() }, getConfiguredDelay() ?: (500L / 2)) }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun nop() {}; fun s(mode: Int) { val delayMillis = when (mode) { 0 -> { nop(); 250L }; else -> 1000L }; handler.postDelayed({ launchSession() }, delayMillis) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { val g = DesktopReadinessGate {}; fun s(ready: Boolean) = handler.postDelayed({ launchSession() }, (if (ready) 125L else 500L) * 2L) }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void boot(android.os.Handler h, Runnable r, boolean ready) { long delay = ready ? 250L : 1000L; h.postDelayed(r, delay); } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void boot(android.os.Handler h, Runnable r) { long delay = 1000L; delay %= 750L; h.postDelayed(r, delay); } }",
            ),
            (
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt",
                "class DesktopActivity { var delay = 1000L; val g = DesktopReadinessGate {}; fun s() { this.delay %= 750L; handler.postDelayed({ launchSession() }, delay) } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { void boot(android.os.Handler h, Runnable r) { long delay = 125L; delay <<= 1; h.postDelayed(r, delay); } }",
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

        for files in (
            {
                f"{ANDROID_KOTLIN}/display/StartupTiming.kt": "package com.orailnoor.droiddesk.display\ninternal const val STARTUP_DELAY_MS = 250L\n",
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": "package com.orailnoor.droiddesk.view\nimport com.orailnoor.droiddesk.display.STARTUP_DELAY_MS\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS) }\n",
            },
            {
                f"{ANDROID_KOTLIN}/view/StartupTiming.kt": "package com.orailnoor.droiddesk.view\ninternal const val STARTUP_DELAY_MS = 250L\n",
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": "package com.orailnoor.droiddesk.view\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS) }\n",
            },
            {
                f"{ANDROID_KOTLIN}/display/StartupTiming.kt": "package com.orailnoor.droiddesk.display\nobject StartupTiming { const val STARTUP_DELAY_MS = 250L }\n",
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": "package com.orailnoor.droiddesk.view\nimport com.orailnoor.droiddesk.display.StartupTiming\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, StartupTiming.STARTUP_DELAY_MS) }\n",
            },
            {
                f"{ANDROID_KOTLIN}/display/StartupTiming.kt": "package com.orailnoor.droiddesk.display\nobject StartupTiming { const val STARTUP_DELAY_MS = 250L }\n",
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": "package com.orailnoor.droiddesk.view\nimport com.orailnoor.droiddesk.display.StartupTiming.STARTUP_DELAY_MS as DELAY\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, DELAY) }\n",
            },
            {
                f"{ANDROID_KOTLIN}/display/StartupTiming.kt": "package com.orailnoor.droiddesk.display\ninternal const val STARTUP_DELAY_MS = 250L\n",
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": "package com.orailnoor.droiddesk.view\nimport com.orailnoor.droiddesk.display.*\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS) }\n",
            },
            {
                f"{ANDROID_KOTLIN}/display/StartupTiming.kt": "package com.orailnoor.droiddesk.display\ninternal const val STARTUP_DELAY_MS = 500L / 2\n",
                f"{ANDROID_KOTLIN}/view/DesktopActivity.kt": "package com.orailnoor.droiddesk.view\nimport com.orailnoor.droiddesk.display.STARTUP_DELAY_MS\nclass DesktopActivity { val g = DesktopReadinessGate {}; fun s() = handler.postDelayed({ launchSession() }, STARTUP_DELAY_MS) }\n",
            },
        ):
            with self.subTest(files=files):
                self.assert_fails_with(files, "250 ms startup delay")

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
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { boolean is60(android.view.Display.Mode m) { return Math.round(\n    m.getRefreshRate()\n) == 60; } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { boolean is60(android.view.Display.Mode m) { return Float.compare(m.getRefreshRate(), 60f) == 0; } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { boolean is60(android.view.Display.Mode m) { return 60 == (int) m.getRefreshRate(); } }",
            ),
            (
                f"{TERMUX_X11}/LorieView.java",
                "class LorieView { boolean is60(android.view.Display.Mode m) { return Integer.compare(Math.round(m.getRefreshRate()), 60) == 0; } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "class MainActivity { var cachedRate = 0f; fun a(m: android.view.Display.Mode) { this.cachedRate = m.refreshRate }; fun b() = cachedRate == 60f }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun bad(mode: android.view.Display.Mode) { val value = mode.refreshRate; run { require(value <= 60f) } }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "fun badRange(mode: android.view.Display.Mode) { val value = mode.refreshRate; require(value in 59.94f..60f) }",
            ),
            (
                f"{ANDROID_KOTLIN}/MainActivity.kt",
                "var topRate = 0f\nfun a(m: android.view.Display.Mode) { topRate = m.refreshRate }\nfun b() = topRate == 60f",
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
