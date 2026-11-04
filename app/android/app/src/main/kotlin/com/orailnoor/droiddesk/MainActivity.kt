package com.orailnoor.droiddesk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.app.role.RoleManager
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.AppUpdater
import com.orailnoor.droiddesk.runtime.DesktopIntegration
import com.orailnoor.droiddesk.runtime.AndroidRuntimeCapabilitySource
import com.orailnoor.droiddesk.runtime.RuntimeCapabilityProbe
import com.orailnoor.droiddesk.runtime.ChrootUbuntuEnvironment
import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.ExecutionRequest
import com.orailnoor.droiddesk.runtime.LinuxEnvironmentManager
import com.orailnoor.droiddesk.runtime.ProvisionRequest
import com.orailnoor.droiddesk.runtime.SharedPreferencesEnvironmentSelectionStore
import com.orailnoor.droiddesk.runtime.TermuxNativeEnvironment
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DesktopItemReadinessGate
import com.orailnoor.droiddesk.display.DesktopLaunchReply
import com.orailnoor.droiddesk.display.DisplaySettings
import com.orailnoor.droiddesk.display.RenderPolicy

internal object MainActivityLaunchPolicy {
    fun shouldHandleInitialIntent(restored: Boolean, firstActivityInProcess: Boolean): Boolean =
        !restored || firstActivityInProcess
}

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.droiddesk/core"
        private const val TAG = "MainActivity"
        /** Sent by the "Open" notification action: show the desktop, resuming a running session. */
        const val ACTION_OPEN_DESKTOP = "com.orailnoor.droiddesk.action.OPEN_DESKTOP"
        const val ACTION_RETURN_TO_PHONE = "com.orailnoor.droiddesk.action.RETURN_TO_PHONE"
        private val activityCreatedInProcess = java.util.concurrent.atomic.AtomicBoolean(false)
        private val packageOperationRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /** Icon lookups may download from the network; cap concurrency so a long list doesn't time out. */
    private val linuxIconExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var desktopIntegration: DesktopIntegration
    private lateinit var runtimeCapabilityProbe: RuntimeCapabilityProbe
    private lateinit var environmentManager: LinuxEnvironmentManager
    private var displayStateSubscription: (() -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logBuffer = StringBuffer()
    private var lastLogFlushTime = 0L
    private val logFlushRunnable = Runnable { flushLogBuffer() }

    private fun flushLogBuffer() {
        val text = synchronized(logBuffer) {
            val t = logBuffer.toString()
            logBuffer.setLength(0)
            t
        }
        if (text.isNotEmpty()) {
            flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to text))
            }
        }
    }

    private fun appendTerminalOutput(chunk: String) {
        synchronized(logBuffer) {
            logBuffer.append(chunk)
            val now = System.currentTimeMillis()
            if (now - lastLogFlushTime > 150) {
                lastLogFlushTime = now
                mainHandler.post(logFlushRunnable)
            } else {
                mainHandler.removeCallbacks(logFlushRunnable)
                mainHandler.postDelayed(logFlushRunnable, 150)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        linuxRuntime = LinuxRuntime(applicationContext)
        chrootRuntime = ChrootRuntime(applicationContext)
        runtimeCapabilityProbe = RuntimeCapabilityProbe(
            AndroidRuntimeCapabilitySource(applicationContext, linuxRuntime, chrootRuntime),
        )
        environmentManager = LinuxEnvironmentManager(
            adapters = listOf(
                TermuxNativeEnvironment(applicationContext, linuxRuntime),
                ChrootUbuntuEnvironment(applicationContext, chrootRuntime),
            ),
            capabilities = runtimeCapabilityProbe::probe,
            selectionStore = SharedPreferencesEnvironmentSelectionStore(applicationContext),
        )
        desktopIntegration = DesktopIntegration(this)
        DesktopDisplayCoordinator.shared.initialize(applicationContext)
        DesktopDisplayCoordinator.shared.bindActivity(this)
        if (DesktopDisplayCoordinator.shared.isDisplayLocal) {
            window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                DesktopDisplayCoordinator.shared.observeActivityEnvironment(this, DesktopDisplayCoordinator.shared.sessionGeneration)
            }
        }

        val firstActivityInProcess = !activityCreatedInProcess.getAndSet(true)
        if (MainActivityLaunchPolicy.shouldHandleInitialIntent(
                restored = savedInstanceState != null,
                firstActivityInProcess = firstActivityInProcess,
            )) {
            if (intent.getBooleanExtra("autoSetup", false)) {
                runAutoChrootSetup()
            }
            handleHomeLaunch(intent)
            if (!intent.getBooleanExtra("autoSetup", false)) maybeAutoStartDesktop(intent)
        }
    }

    /** Optional "Auto-Start Linux Desktop" setting: open the chroot desktop whenever the app starts. */
    private fun maybeAutoStartDesktop(launchIntent: Intent) {
        // Home presses and the notification's Open action already launch the desktop.
        val isHomeLaunch = launchIntent.action == Intent.ACTION_MAIN && launchIntent.hasCategory(Intent.CATEGORY_HOME)
        if (isHomeLaunch || launchIntent.action == ACTION_OPEN_DESKTOP || launchIntent.action == ACTION_RETURN_TO_PHONE) return
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        if (DesktopDisplayCoordinator.shared.isDisplayLocal) return
        val autoStart = prefs.getBoolean("flutter.autoStartDesktop", false)
        if (autoStart) {
            val status = environmentManager.status()
            if (status.installed && status.desktop.isNotEmpty()) requestDesktop(
                status.kind.sessionMode,
                status.desktop,
                !status.running,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        DesktopDisplayCoordinator.shared.bindActivity(this)
        window.decorView.post {
            if (!isDestroyed && !isFinishing) {
                DesktopDisplayCoordinator.shared.observeActivityEnvironment(this, DesktopDisplayCoordinator.shared.sessionGeneration)
            }
        }
        restoreSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.post {
            if (!isDestroyed && !isFinishing) {
                DesktopDisplayCoordinator.shared.observeActivityEnvironment(this, DesktopDisplayCoordinator.shared.sessionGeneration)
            }
        }
    }

    override fun onDestroy() {
        DesktopDisplayCoordinator.shared.unbindActivity(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restoreSystemBars()
            window.decorView.post { restoreSystemBars() }
        }
    }

    @Suppress("DEPRECATION")
    /**
     * Undo the desktop's immersive mode while keeping the app edge-to-edge: Flutter draws behind
     * the transparent bars and pads with SafeArea. Fitting system windows here would leave the
     * bare (black) window showing through the status bar.
     */
    private fun restoreSystemBars() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        }
        // Resetting the flags above clears the icon colour; re-apply the app theme's choice.
        lightSystemBars?.let(::updateStatusBarIcons)
    }

    /** Last status-bar icon mode requested by Flutter; null until the theme reports it. */
    private var lightSystemBars: Boolean? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeLaunch(intent)
    }

    private fun handleHomeLaunch(homeIntent: Intent) {
        val coordinator = DesktopDisplayCoordinator.shared
        val isHomeLaunch = homeIntent.action == Intent.ACTION_MAIN && homeIntent.hasCategory(Intent.CATEGORY_HOME)
        val isLocalLauncher = coordinator.isDisplayLocal && homeIntent.action == Intent.ACTION_MAIN &&
            homeIntent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (!isHomeLaunch && !isLocalLauncher && homeIntent.action != ACTION_OPEN_DESKTOP && homeIntent.action != ACTION_RETURN_TO_PHONE) return
        if (homeIntent.action == ACTION_RETURN_TO_PHONE) {
            coordinator.moveToPhone { outcome -> outcome.exceptionOrNull()?.let { displayFailure(it) } }
            return
        }
        if (environmentManager.status().running || coordinator.isStarting || coordinator.isRestarting) {
            launchPreparedDesktop(coordinator.sessionGeneration, null)
            return
        }
        val epoch = try {
            coordinator.beginSession()
        } catch (error: DesktopDisplayException) {
            displayFailure(error)
            return
        }

        thread(name = "home-launch") {
            val status = environmentManager.status()
            if (!status.installed || status.desktop.isEmpty()) {
                Log.i(TAG, "Home launch opened setup because Linux is not ready")
                return@thread
            }

            runOnUiThread {
                requestDesktop(
                    status.kind.sessionMode,
                    status.desktop,
                    !status.running,
                    expectedEpoch = epoch,
                )
            }
        }
    }

    /**
     * Hidden developer/auto-tester path: download, extract, install, and launch
     * the chroot desktop without any Flutter UI interaction.
     */
    private fun runAutoChrootSetup() {
        val epoch = try {
            DesktopDisplayCoordinator.shared.beginSession()
        } catch (error: DesktopDisplayException) {
            displayFailure(error)
            return
        }
        thread(name = "auto-chroot-setup") {
            try {
                Log.i(TAG, "Auto-setup: checking root...")
                if (!chrootRuntime.hasRoot()) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Auto-setup requires root", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                startForegroundService()

                if (!chrootRuntime.isRootfsReady()) {
                    Log.i(TAG, "Auto-setup: downloading rootfs...")
                    val dlLatch = java.util.concurrent.CountDownLatch(1)
                    var dlOk = false
                    chrootRuntime.downloadRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            dlOk = progress >= 1.0
                            dlLatch.countDown()
                        }
                    }
                    dlLatch.await()
                    if (!dlOk) throw RuntimeException("Rootfs download failed")

                    Log.i(TAG, "Auto-setup: extracting rootfs...")
                    val exLatch = java.util.concurrent.CountDownLatch(1)
                    var exOk = false
                    chrootRuntime.extractRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            exOk = progress >= 1.0
                            exLatch.countDown()
                        }
                    }
                    exLatch.await()
                    if (!exOk) throw RuntimeException("Rootfs extraction failed")
                }

                if (!chrootRuntime.isDesktopInstalled()) {
                    Log.i(TAG, "Auto-setup: installing desktop environment...")
                    val inLatch = java.util.concurrent.CountDownLatch(1)
                    var inOk = false
                    chrootRuntime.installDesktopEnvironment(
                        desktopEnv = "xfce4",
                        onProgress = { progress, _ ->
                            if (progress >= 1.0 || progress < 0) {
                                inOk = progress >= 1.0
                                inLatch.countDown()
                            }
                        },
                        onLog = {}
                    )
                    inLatch.await()
                    if (!inOk) throw RuntimeException("Desktop installation failed")
                }

                Log.i(TAG, "Auto-setup: launching desktop...")
                runOnUiThread {
                    requestDesktop("chroot", "xfce4", !chrootRuntime.isRunning(), expectedEpoch = epoch)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-setup failed", e)
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Auto-setup failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val displays = DesktopDisplayCoordinator.shared
        displays.initialize(applicationContext)
        displayStateSubscription?.invoke()
        val displayChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        displayStateSubscription = displays.subscribe { state ->
            displayChannel.invokeMethod("onDesktopDisplayStateChanged", state)
        }

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("droiddesk-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getDesktopDisplays" -> result.success(displays.snapshot())
                "setAutoOpenOnMonitor" -> {
                    val enabled = (call.arguments as? Map<*, *>)?.get("enabled") as? Boolean
                    if (enabled == null) {
                        Log.w("DesktopDisplay", "Automatic monitor opening requires a Boolean enabled value")
                        result.error("INVALID_ARGUMENT", "enabled must be a Boolean", null)
                    } else {
                        try {
                            displays.setAutoOpenOnMonitor(enabled)
                            result.success(displays.snapshot())
                        } catch (error: DesktopDisplayException) {
                            displayFailure(error, result)
                        } catch (error: IllegalStateException) {
                            Log.e("DesktopDisplay", "Could not persist automatic monitor opening", error)
                            result.error("DISPLAY_SETTINGS_SAVE_FAILED", error.message, null)
                        }
                    }
                }
                "saveDesktopDisplaySettings" -> {
                    val reply = DesktopLaunchReply(result, displays)
                    try {
                        val arguments = call.arguments as? Map<*, *> ?: throw IllegalArgumentException("Settings are required")
                        val key = arguments["displayKey"] as? String
                        val policy = RenderPolicy.fromWire(arguments["renderPolicy"] as? String)
                        val rawScale = arguments["uiScalePercent"]
                        val mode = arguments["inputMode"] as? Int
                        require(!key.isNullOrEmpty() && policy != null && mode != null) { "Invalid display settings" }
                        require(arguments.containsKey("uiScalePercent") && (rawScale == null || rawScale is Int)) {
                            "uiScalePercent must be an integer or Automatic"
                        }
                        val settings = DisplaySettings(policy, rawScale as? Int, mode)
                        displays.saveSettingsFor(key, settings) { replyDisplayState(reply, it) }
                    } catch (error: DesktopDisplayException) {
                        displayFailure(error, reply)
                    } catch (error: IllegalArgumentException) {
                        Log.w("DesktopDisplay", "Invalid settings payload", error)
                        reply.error("INVALID_ARGUMENT", error.message, null)
                    } catch (error: IllegalStateException) {
                        Log.e("DesktopDisplay", "Could not persist display settings", error)
                        reply.error("PROFILE_APPLY_FAILED", error.message, null)
                    }
                }
                "openDesktopOnDisplay" -> {
                    val arguments = call.arguments as? Map<*, *>
                    val id = arguments?.get("displayId") as? Int
                    val key = arguments?.get("displayKey") as? String
                    if (id == null || id < 0 || key.isNullOrEmpty()) {
                        Log.w("DesktopDisplay", "Invalid display launch arguments")
                        result.error("INVALID_ARGUMENT", "displayId and displayKey are required", null)
                    } else {
                        displays.open(id, key) { replyDisplayState(result, it) }
                    }
                }
                "moveDesktopToPhone" -> displays.moveToPhone { replyDisplayState(result, it) }
                "getDesktopDisplaySettings" -> {
                    try {
                        val key = (call.arguments as? Map<*, *>)?.get("displayKey") as? String
                        require(!key.isNullOrEmpty()) { "displayKey is required" }
                        result.success(displays.settingsFor(key))
                    } catch (error: DesktopDisplayException) {
                        Log.w("DesktopDisplay", error.message, error)
                        result.error(error.code, error.message, null)
                    } catch (error: IllegalArgumentException) {
                        result.error("INVALID_ARGUMENT", error.message, null)
                    }
                }

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    result.success(environmentManager.status().toLegacyMap())
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    val capabilities = runtimeCapabilityProbe.probe()
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "graphicsMode" to capabilities.rendererBackend.wireValue,
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                // ── Root checks ──
                "checkRoot" -> {
                    thread {
                        val ok = chrootRuntime.hasRoot()
                        runOnUiThread { result.success(ok) }
                    }
                }

                "resetRootCache" -> {
                    RootShell(this).resetCache()
                    result.success(true)
                }

                // ── Chroot rootfs management (rooted) ──
                "downloadRootfs" -> {
                    installThread("download-rootfs") {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.downloadRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onDownloadProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "extractRootfs" -> {
                    installThread("extract-rootfs") {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.extractRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onExtractProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "installDesktopEnvironment" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    installThread("install-desktop-chroot") {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.installDesktopEnvironment(
                                desktopEnv,
                                { progress, status ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onInstallProgress",
                                                mapOf("progress" to progress, "status" to status)
                                            )
                                        }
                                    }
                                    if (progress >= 1.0 || progress < 0) {
                                        success = progress >= 1.0
                                        latch.countDown()
                                    }
                                },
                                { logChunk ->
                                    appendTerminalOutput(logChunk)
                                }
                            )
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                // ── Native Termux desktop install (non-root fallback) ──
                "provisionEnvironment" -> {
                    val kindValue = call.argument<String>("kind") ?: ""
                    val environmentKind = EnvironmentKind.fromWire(kindValue)
                    if (environmentKind == null) {
                        result.error("INVALID_ARGUMENT", "Unknown Linux environment: $kindValue", null)
                        return@setMethodCallHandler
                    }
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    installThread("provision-linux-environment") {
                        val outcome = environmentManager.provision(
                            ProvisionRequest(environmentKind, desktopEnv),
                            onProgress = { progress, status ->
                                runOnUiThread {
                                    MethodChannel(
                                        flutterEngine.dartExecutor.binaryMessenger,
                                        CHANNEL,
                                    ).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                                }
                            },
                            onLog = ::appendTerminalOutput,
                        )
                        runOnUiThread { result.success(outcome.isSuccess) }
                    }
                }

                "installDesktopNative" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    installThread("install-desktop-native") {
                        linuxRuntime.setInstallLogSink { chunk ->
                            appendTerminalOutput(chunk)
                        }
                        try {
                            val ok = linuxRuntime.installDesktopEnvironmentNative(
                                desktopEnv,
                            ) { progress, status ->
                                runOnUiThread {
                                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                                }
                            }
                            runOnUiThread { result.success(ok) }
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                    }
                }

                "getOptionalApps" -> {
                    result.success(environmentManager.optionalAppsStatus())
                }

                "installOptionalApp" -> {
                    val appId = call.argument<String>("appId") ?: ""
                    installThread("install-optional-app") {
                        val logSink: (String) -> Unit = { chunk ->
                            appendTerminalOutput(chunk)
                        }
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod(
                                        "onOptionalInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                            }
                        }

                        val outcome = environmentManager.installOptionalApp(
                            appId = appId,
                            onProgress = progressSink,
                            onLog = logSink,
                        )
                        runOnUiThread { result.success(outcome.isSuccess) }
                    }
                }

                // ── Native package store ──
                "searchNativePackages" -> {
                    val query = call.argument<String>("query") ?: ""
                    val limit = call.argument<Int>("limit") ?: 60
                    thread(name = "package-search") {
                        val packages = linuxRuntime.searchNativePackages(query, limit)
                        runOnUiThread { result.success(packages) }
                    }
                }

                "getInstalledNativePackages" -> {
                    thread(name = "installed-packages") {
                        val packages = linuxRuntime.getInstalledNativePackages()
                        runOnUiThread { result.success(packages) }
                    }
                }

                "installNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = false)
                }

                "getUpgradablePackages" -> {
                    val refresh = call.argument<Boolean>("refresh") ?: false
                    thread(name = "upgradable-packages") {
                        val packages = runCatching { linuxRuntime.getUpgradablePackages(refresh) }
                            .getOrDefault(emptyList())
                        runOnUiThread { result.success(packages) }
                    }
                }

                "upgradeAllPackages" -> runPackageOperation(
                    flutterEngine, result, "updates", remove = false,
                ) { progress -> linuxRuntime.upgradeAllPackages(progress) }

                "getStorageInfo" -> {
                    thread(name = "storage-info") {
                        val info = runCatching { linuxRuntime.getStorageInfo() }
                            .getOrDefault(emptyMap())
                        runOnUiThread { result.success(info) }
                    }
                }

                "cleanPackageCache" -> {
                    thread(name = "clean-package-cache") {
                        val freed = runCatching { linuxRuntime.cleanPackageCache() }.getOrDefault(-1L)
                        runOnUiThread { result.success(freed) }
                    }
                }

                "removeNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = true)
                }

                "cancelNativePackageOperation" -> {
                    result.success(linuxRuntime.cancelPackageOperation())
                }

                // ── Android + Linux desktop integration ──
                "searchDesktopItems" -> {
                    val query = call.argument<String>("query") ?: ""
                    thread(name = "desktop-search") {
                        val items = desktopIntegration.search(query, environmentManager.paths())
                        runOnUiThread { result.success(items) }
                    }
                }

                "launchDesktopItem" -> {
                    val kind = call.argument<String>("kind") ?: ""
                    val id = call.argument<String>("id") ?: ""
                    when (kind) {
                        "android" -> result.success(AndroidAppBridge.launchAndroidPackage(this, id))
                        "setting" -> {
                            AndroidAppBridge.launchSystemAction(this, id)
                            result.success(true)
                        }
                        "linux", "folder" -> {
                            val reply = DesktopLaunchReply(result, displays)
                            if (displays.isDisplayLocal) {
                                launchLocalDesktopItem(kind, id, reply)
                                return@setMethodCallHandler
                            }
                            val epoch = try {
                                displays.beginSession()
                            } catch (error: DesktopDisplayException) {
                                displayFailure(error, reply)
                                return@setMethodCallHandler
                            }
                            thread(name = "launch-desktop-item") {
                                if (!displays.isSessionCurrent(epoch)) {
                                    runOnUiThread { reply.error("SESSION_STOPPED", "Desktop launch was cancelled", null) }
                                    return@thread
                                }
                                val status = environmentManager.status()
                                val environmentKind = status.kind
                                if (!status.running) {
                                    runOnUiThread { openDesktop(startSession = true, expectedEpoch = epoch) }
                                    environmentManager.waitForDesktopReady(
                                        status.desktop,
                                        kind = environmentKind,
                                    )
                                }
                                val command = desktopItemCommand(
                                    kind,
                                    id,
                                    environmentManager.paths(environmentKind),
                                )
                                if (!displays.isSessionCurrent(epoch)) {
                                    runOnUiThread { reply.error("SESSION_STOPPED", "Desktop launch was cancelled", null) }
                                    return@thread
                                }
                                val launched = environmentManager.execute(
                                    ExecutionRequest.Desktop(environmentKind, command),
                                )
                                runOnUiThread {
                                    if (!displays.isSessionCurrent(epoch)) {
                                        reply.error("SESSION_STOPPED", "Desktop launch was cancelled", null)
                                    } else if (launched.exitCode != 0) {
                                        reply.error("DESKTOP_LAUNCH_FAILED", launched.output, null)
                                    } else {
                                        if (status.running) openDesktop(startSession = false, expectedEpoch = epoch)
                                        reply.success(true)
                                    }
                                }
                            }
                        }
                        else -> result.success(false)
                    }
                }

                // ── In-app updates ──
                "getAppVersion" -> result.success(AppUpdater.currentVersion(this))
                "canInstallUpdates" -> result.success(AppUpdater.canInstall(this))
                "openInstallPermission" -> {
                    runCatching { AppUpdater.openInstallPermissionSettings(this) }
                    result.success(true)
                }
                "cancelUpdateDownload" -> {
                    AppUpdater.cancel()
                    result.success(true)
                }
                "cleanupUpdates" -> {
                    thread(name = "update-cleanup") { runCatching { AppUpdater.cleanup(this) } }
                    result.success(true)
                }
                "downloadUpdate" -> {
                    val url = call.argument<String>("url").orEmpty()
                    val size = (call.argument<Number>("size") ?: 0).toLong()
                    val tag = call.argument<String>("tag") ?: "latest"
                    installThread("update-download") {
                        val messenger = flutterEngine.dartExecutor.binaryMessenger
                        val outcome: Map<String, Any?> = try {
                            val apk = AppUpdater.download(this, url, size, tag) { done, total ->
                                runOnUiThread {
                                    MethodChannel(messenger, CHANNEL).invokeMethod(
                                        "onUpdateProgress",
                                        mapOf("downloaded" to done, "total" to total),
                                    )
                                }
                            }
                            mapOf("ok" to true, "path" to apk.absolutePath)
                        } catch (error: AppUpdater.UpdateException) {
                            mapOf("ok" to false, "code" to error.code, "message" to error.message)
                        } catch (error: Throwable) {
                            Log.e(TAG, "Update download failed", error)
                            mapOf("ok" to false, "code" to "unknown", "message" to error.message)
                        }
                        runOnUiThread { result.success(outcome) }
                    }
                }
                "installUpdate" -> {
                    val path = call.argument<String>("path").orEmpty()
                    val ok = runCatching { AppUpdater.install(this, java.io.File(path)) }
                        .onFailure { Log.e(TAG, "Could not open the installer", it) }
                        .isSuccess
                    result.success(ok)
                }

                "getAndroidApps" -> result.success(AndroidAppBridge.listApps(this))
                "getLinuxAppIcon" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    val iconNames = call.argument<List<String>>("iconNames").orEmpty()
                    linuxIconExecutor.execute {
                        // The native runtime lives in filesDir/usr; a chroot rootfs is root-owned,
                        // so rooted devices rely on the Papirus fallback.
                        val prefix = java.io.File(filesDir, "usr").takeIf { it.isDirectory }
                        val icon = runCatching {
                            com.orailnoor.droiddesk.runtime.LinuxAppIcons.find(
                                this@MainActivity, prefix, packageName, iconNames,
                            )
                        }.getOrNull()
                        runOnUiThread { result.success(icon) }
                    }
                }
                "getAppIcon" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    thread(name = "app-icon") {
                        val png = runCatching {
                            val size = 96
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                size, size, android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            packageManager.getApplicationIcon(packageName).apply {
                                setBounds(0, 0, size, size)
                                draw(android.graphics.Canvas(bitmap))
                            }
                            java.io.ByteArrayOutputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        }.getOrNull()
                        runOnUiThread { result.success(png) }
                    }
                }
                "getDockPackages" -> result.success(AndroidAppBridge.getDockPackages(this))
                "saveDockPackages" -> {
                    val packages = call.argument<List<String>>("packages").orEmpty()
                    AndroidAppBridge.setDockPackages(this, packages)
                    thread(name = "sync-desktop-dock") {
                        val synced = runCatching {
                            syncAndroidDesktopIntegration()
                            true
                        }.onFailure { Log.e(TAG, "Could not refresh desktop dock", it) }
                            .getOrDefault(false)
                        runOnUiThread { result.success(synced) }
                    }
                }

                "listDesktopSnapshots" -> result.success(desktopIntegration.listSnapshots())
                "createDesktopSnapshot" -> thread(name = "create-desktop-snapshot") {
                    runCatching { desktopIntegration.createSnapshot(environmentManager.paths()) }
                        .onSuccess { value -> runOnUiThread { result.success(value) } }
                        .onFailure { error -> runOnUiThread { result.error("snapshot", error.message, null) } }
                }
                "restoreDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    displays.stop()
                    thread(name = "restore-desktop-snapshot") {
                        runCatching {
                            val environmentKind = environmentManager.activeKind()
                            val paths = environmentManager.paths(environmentKind)
                            environmentManager.stop().getOrThrow()
                            val savedPackages = desktopIntegration.restoreSnapshot(name, paths)
                            val missingPackages = desktopIntegration.missingPackages(savedPackages, paths)
                            if (missingPackages.isNotEmpty()) {
                                val command = "DEBIAN_FRONTEND=noninteractive apt-get " +
                                    "-o Dpkg::Options::=--force-confdef " +
                                    "-o Dpkg::Options::=--force-confold --no-upgrade install -y " +
                                    missingPackages.joinToString(" ")
                                val execution = environmentManager.execute(
                                    ExecutionRequest.Shell(environmentKind, command),
                                )
                                if (execution.exitCode != 0) {
                                    error(execution.output)
                                }
                            }
                            true
                        }.onSuccess { value -> runOnUiThread { result.success(value) } }
                            .onFailure { error -> runOnUiThread { result.error("restore", error.message, null) } }
                    }
                }
                "deleteDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    result.success(runCatching { desktopIntegration.deleteSnapshot(name) }.getOrDefault(false))
                }
                "openAndroidControl" -> {
                    AndroidAppBridge.launchSystemAction(this, call.argument<String>("action") ?: "settings")
                    result.success(true)
                }

                // ── Start Linux session ──
                "startLinux" -> {
                    val startReply = DesktopLaunchReply(result, displays)
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val status = environmentManager.status()
                    val environmentKind = status.kind
                    val ready = status.installed && status.desktop == desktopEnv
                    if (displays.isDisplayLocal && ready) {
                        requestDesktop(
                            environmentKind.sessionMode,
                            desktopEnv,
                            !status.running,
                            startReply,
                        )
                        return@setMethodCallHandler
                    }
                    val epoch = try {
                        displays.prepareSession(
                            environmentKind.sessionMode,
                            desktopEnv,
                            !status.running,
                        )
                    } catch (error: DesktopDisplayException) {
                        displayFailure(error, startReply)
                        return@setMethodCallHandler
                    }
                    startForegroundService()
                    installThread("prepare-linux-environment") {
                        try {
                            if (!ready) {
                                environmentManager.provision(
                                    ProvisionRequest(environmentKind, desktopEnv),
                                    onProgress = { progress, message ->
                                        runOnUiThread {
                                            MethodChannel(
                                                flutterEngine.dartExecutor.binaryMessenger,
                                                CHANNEL,
                                            ).invokeMethod(
                                                "onInstallProgress",
                                                mapOf("progress" to progress, "status" to message),
                                            )
                                        }
                                    },
                                    onLog = ::appendTerminalOutput,
                                ).getOrThrow()
                            }
                            runOnUiThread { launchPreparedDesktop(epoch, startReply) }
                        } catch (error: Exception) {
                            Log.e(TAG, "Linux environment preparation failed", error)
                            runOnUiThread {
                                displays.sessionStarted(epoch, Result.failure(error))
                                startReply.error("SESSION_STOPPED", error.message, null)
                            }
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    val status = environmentManager.status()
                    requestDesktop(
                        status.kind.sessionMode,
                        status.desktop,
                        !status.running,
                        result,
                    )
                }

                "stopLinux" -> {
                    DroidDeskService.stopDesktop(applicationContext) { outcome ->
                        val error = outcome.exceptionOrNull()
                        if (error == null) result.success(true)
                        else result.error("STOP_FAILED", error.message, null)
                    }
                }

                // ── Command execution ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    Thread {
                        val execution = environmentManager.execute(
                            ExecutionRequest.Shell(
                                environmentManager.activeKind(),
                                command,
                                ::appendTerminalOutput,
                            ),
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(execution.output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "requestDefaultLauncher" -> {
                    requestDefaultLauncher()
                    result.success(true)
                }

                "unsetDefaultLauncher" -> {
                    thread(name = "unset-home-role") {
                        val removed = unsetDefaultLauncher()
                        runOnUiThread {
                            if (removed) openHomeChooser() else openHomeSettings()
                            result.success(removed)
                        }
                    }
                }

                "isDefaultLauncher" -> {
                    result.success(isDefaultLauncher())
                }

                "updateStatusBarTheme" -> {
                    val isLight = call.argument<Boolean>("isLightMode") ?: false
                    runOnUiThread {
                        updateStatusBarIcons(isLight)
                    }
                    result.success(true)
                }

                "setupBootstrap" -> {
                    if (chrootRuntime.hasRoot()) {
                        // Nothing to bootstrap for chroot; rootfs handles it
                        result.success(true)
                    } else {
                        installThread("setup-bootstrap") {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            linuxRuntime.setupBootstrap()
                            runOnUiThread { result.success(true) }
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun updateStatusBarIcons(isLightMode: Boolean) {
        lightSystemBars = isLightMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (isLightMode) lightBars else 0,
                lightBars,
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (isLightMode) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun syncAndroidDesktopIntegration() {
        val status = environmentManager.status()
        val paths = environmentManager.paths(status.kind)
        val sessionRoot = status.rootfsPath.takeIf(String::isNotEmpty)?.let { java.io.File(it) }
        val python = if (sessionRoot != null) {
            java.io.File(sessionRoot, "usr/bin/python3")
        } else {
            java.io.File(filesDir, "usr/bin/python3")
        }
        AndroidAppBridge.syncLaunchers(
            context = this,
            homeDir = paths.home,
            python = python,
            sessionRoot = sessionRoot,
        )
        if (status.running) {
            val execution = environmentManager.execute(
                ExecutionRequest.Desktop(
                    status.kind,
                    AndroidAppBridge.xfceDockCommand(this) +
                        " DISPLAY=:0 xfce4-panel -r",
                ),
            )
            check(execution.exitCode == 0) { execution.output }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        displayStateSubscription?.invoke()
        displayStateSubscription = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun openDesktop(startSession: Boolean, expectedEpoch: Long? = null) {
        val status = environmentManager.status()
        requestDesktop(
            status.kind.sessionMode,
            status.desktop,
            startSession,
            expectedEpoch = expectedEpoch,
        )
    }

    private fun requestDesktop(
        mode: String,
        desktop: String,
        start: Boolean,
        result: MethodChannel.Result? = null,
        expectedEpoch: Long? = null,
    ) {
        val coordinator = DesktopDisplayCoordinator.shared
        if (expectedEpoch != null && !coordinator.isSessionCurrent(expectedEpoch)) {
            if (coordinator.isRestarting && expectedEpoch == coordinator.sessionGeneration) {
                launchPreparedDesktop(expectedEpoch, result)
                return
            }
            displayFailure(DesktopDisplayException("SESSION_STOPPED", "The session was stopped before launch"), result)
            return
        }
        if (coordinator.isDisplayLocal && window.decorView.isAttachedToWindow) {
            val target = try {
                coordinator.startupTarget(window.decorView.display?.displayId
                    ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app window has no current display"))
            } catch (error: DesktopDisplayException) {
                displayFailure(error, result)
                return
            }
            if (coordinator.restartForEnvironmentIfNeeded(target, coordinator.sessionGeneration) { outcome ->
                outcome.exceptionOrNull()?.let { displayFailure(it, result) } ?: result?.success(true)
            }) return
        }
        try {
            val epoch = coordinator.prepareSession(mode, desktop, start, expectedEpoch = expectedEpoch)
            startForegroundService()
            launchPreparedDesktop(epoch, result)
        } catch (error: DesktopDisplayException) {
            displayFailure(error, result)
        }
    }

    private fun launchPreparedDesktop(epoch: Long, result: MethodChannel.Result?) {
        val coordinator = DesktopDisplayCoordinator.shared
        if (!coordinator.isSessionCurrent(epoch) && !coordinator.isRestarting) {
            displayFailure(DesktopDisplayException("SESSION_STOPPED", "The Linux session was stopped"), result)
            return
        }
        if (coordinator.isDisplayLocal && !window.decorView.isAttachedToWindow) {
            window.decorView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) {
                    view.removeOnAttachStateChangeListener(this)
                    launchPreparedDesktop(epoch, result)
                }
                override fun onViewDetachedFromWindow(view: android.view.View) {}
            })
            return
        }
        val target = try {
            if (coordinator.isDisplayLocal) {
                coordinator.startupTarget(window.decorView.display?.displayId
                    ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app window has no current display"))
            } else null
        } catch (error: DesktopDisplayException) {
            displayFailure(error, result)
            return
        }
        val completion: (Result<Unit>) -> Unit = { outcome ->
            val error = outcome.exceptionOrNull()
            if (error != null) displayFailure(error, result) else result?.success(true)
        }
        if (target != null && coordinator.restartForEnvironmentIfNeeded(target, epoch, completion)) return
        if (!coordinator.isSessionCurrent(epoch)) {
            completion(Result.failure(DesktopDisplayException("SESSION_STOPPED", "The Linux session was stopped")))
            return
        }
        coordinator.openActive(target) { placed ->
            if (placed.isFailure) completion(placed) else coordinator.awaitSessionReady(epoch, completion)
        }
    }

    private fun launchLocalDesktopItem(kind: String, id: String, result: MethodChannel.Result) {
        val coordinator = DesktopDisplayCoordinator.shared
        val status = environmentManager.status()
        val environmentKind = status.kind
        requestDesktop(status.kind.sessionMode, status.desktop,
            !status.running,
            object : MethodChannel.Result {
                override fun success(value: Any?) {
                    val epoch = coordinator.sessionGeneration
                    thread(name = "local-desktop-item-ready") {
                        val readiness = DesktopItemReadinessGate(
                            isSessionCurrent = { coordinator.isSessionCurrent(epoch) },
                            waitUntilReady = {
                                environmentManager.waitForDesktopReady(
                                    status.desktop,
                                    kind = environmentKind,
                                )
                            },
                        ).await {
                            DroidDeskService.executeSession {
                                if (!coordinator.isSessionCurrent(epoch)) {
                                    mainHandler.post {
                                        result.error("SESSION_STOPPED", "Desktop launch was cancelled", null)
                                    }
                                    return@executeSession
                                }
                                val command = desktopItemCommand(
                                    kind,
                                    id,
                                    environmentManager.paths(environmentKind),
                                )
                                val launched = environmentManager.execute(
                                    ExecutionRequest.Desktop(environmentKind, command),
                                )
                                mainHandler.post {
                                    if (!coordinator.isSessionCurrent(epoch)) {
                                        result.error("SESSION_STOPPED", "Desktop launch was cancelled", null)
                                    } else if (launched.exitCode != 0) {
                                        result.error("DESKTOP_LAUNCH_FAILED", launched.output, null)
                                    } else result.success(true)
                                }
                            }
                        }
                        readiness.exceptionOrNull()?.let { failure ->
                            mainHandler.post {
                                val displayFailure = failure as? DesktopDisplayException
                                result.error(
                                    displayFailure?.code ?: "DESKTOP_LAUNCH_FAILED",
                                    failure.message ?: "The Linux desktop did not become ready",
                                    null,
                                )
                            }
                        }
                    }
                }
                override fun error(code: String, message: String?, details: Any?) = result.error(code, message, details)
                override fun notImplemented() = result.notImplemented()
            })
    }

    private fun desktopItemCommand(
        kind: String,
        id: String,
        paths: com.orailnoor.droiddesk.runtime.EnvironmentPaths,
    ): String {
        val safeId = id.takeIf { it.matches(Regex("[A-Za-z0-9_.+-]+")) }
        if (kind == "linux" && safeId != null) return "DISPLAY=:0 gtk-launch '$safeId' >/dev/null 2>&1 &"
        val folder = when (id) {
            "Downloads", "Documents", "Pictures" -> "/$id"
            else -> ""
        }
        val home = paths.guestHome
        return "DISPLAY=:0 thunar \"$home$folder\" >/dev/null 2>&1 &"
    }

    private fun replyDisplayState(result: MethodChannel.Result, outcome: Result<Unit>) {
        val error = outcome.exceptionOrNull()
        if (error == null) result.success(DesktopDisplayCoordinator.shared.snapshot())
        else displayFailure(error, result)
    }

    private fun displayFailure(error: Throwable, result: MethodChannel.Result? = null) {
        Log.w("DesktopDisplay", "Desktop operation failed", error)
        val code = (error as? DesktopDisplayException)?.code ?: "DISPLAY_UNAVAILABLE"
        if (result != null) result.error(code, error.message, null)
        else Toast.makeText(this, error.message ?: "Desktop placement failed", Toast.LENGTH_LONG).show()
    }

    private fun runPackageOperation(
        flutterEngine: FlutterEngine,
        result: MethodChannel.Result,
        packageName: String,
        remove: Boolean,
        operation: ((progress: (Double, String) -> Unit) -> Boolean)? = null,
    ) {
        if (!packageOperationRunning.compareAndSet(false, true)) {
            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                "onPackageOperationProgress",
                mapOf(
                    "progress" to -1.0,
                    "status" to "Previous package operation is still stopping. Try again.",
                ),
            )
            result.success(false)
            return
        }
        installThread(if (remove) "package-remove" else "package-install") {
            linuxRuntime.beginPackageOperation()
            linuxRuntime.setInstallLogSink { chunk ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationLog",
                        mapOf("text" to chunk),
                    )
                }
            }
            val progress: (Double, String) -> Unit = { value, status ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationProgress",
                        mapOf("progress" to value, "status" to status),
                    )
                }
            }
            try {
                startForegroundService()
                val ok = if (operation != null) {
                    operation(progress)
                } else if (remove) {
                    linuxRuntime.removeStorePackage(packageName, progress)
                } else {
                    linuxRuntime.installStorePackage(packageName, progress)
                }
                runOnUiThread { result.success(ok) }
            } catch (error: Throwable) {
                Log.e(TAG, "Package operation failed for $packageName", error)
                progress(-1.0, error.message ?: "Package operation failed")
                runOnUiThread { result.success(false) }
            } finally {
                linuxRuntime.setInstallLogSink(null)
                linuxRuntime.finishPackageOperation()
                packageOperationRunning.set(false)
            }
        }
    }

    // ── Install wake lock ──

    private val installOperations = java.util.concurrent.atomic.AtomicInteger(0)
    private val installWakeLock by lazy {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DroidDesk::Install")
            .apply { setReferenceCounted(false) }
    }

    /**
     * Runs a long setup/install step on a background thread while keeping the CPU awake and the
     * screen on, so downloads and package installs are not paused when the phone would sleep.
     */
    private fun installThread(name: String, block: () -> Unit) = thread(name = name) {
        if (installOperations.getAndIncrement() == 0) {
            installWakeLock.acquire(2 * 60 * 60 * 1000L)
            runOnUiThread { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        try {
            block()
        } finally {
            if (installOperations.decrementAndGet() == 0) {
                if (installWakeLock.isHeld) installWakeLock.release()
                runOnUiThread { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    // ── Foreground Service ──

    private fun startForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        stopService(intent)
    }

    // ── Battery Optimization ──

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun isDefaultLauncher(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(
                homeIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName == packageName
        }
    }

    private fun requestDefaultLauncher() {
        if (isDefaultLauncher()) {
            Toast.makeText(this, "DroidDesk is already the default launcher", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun unsetDefaultLauncher(): Boolean {
        if (!isDefaultLauncher()) return true

        val rootShell = RootShell(this)
        if (rootShell.hasRoot()) {
            runCatching {
                val userId = android.os.Process.myUid() / 100000
                rootShell.exec(
                    "cmd role remove-role-holder --user $userId " +
                        "android.app.role.HOME $packageName",
                )
                repeat(10) {
                    if (!isDefaultLauncher()) return true
                    Thread.sleep(100)
                }
            }.onFailure { Log.w(TAG, "Could not remove the Home role with root", it) }
        }

        // Older Android versions may still store Home as a preferred activity
        // rather than a RoleManager holder. Apps may clear their own preference.
        runCatching { packageManager.clearPackagePreferredActivities(packageName) }
        return !isDefaultLauncher()
    }

    private fun openHomeChooser() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        startActivity(Intent.createChooser(homeIntent, "Choose your Home app"))
    }

    private fun openHomeSettings() {
        Toast.makeText(
            this,
            "Android requires you to select another default Home app",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // ── Hardware Detection ──

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
