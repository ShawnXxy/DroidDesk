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
import android.view.KeyEvent
import android.view.MotionEvent
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.AppUpdater
import com.orailnoor.droiddesk.runtime.DesktopIntegration
import com.orailnoor.droiddesk.runtime.RuntimeCapabilityCache
import com.orailnoor.droiddesk.runtime.RuntimeCapabilityRegistry
import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.ExecutionRequest
import com.orailnoor.droiddesk.runtime.LinuxEnvironmentManager
import com.orailnoor.droiddesk.runtime.ProvisionRequest
import com.orailnoor.droiddesk.runtime.createLinuxEnvironmentManager
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import com.orailnoor.droiddesk.x11.X11ServerService
import com.orailnoor.droiddesk.x11.ExternalDesktopInputBridge
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DesktopItemReadinessGate
import com.orailnoor.droiddesk.display.DesktopLaunchReply
import com.orailnoor.droiddesk.display.DesktopPlacementDiagnostics
import com.orailnoor.droiddesk.display.InFlightDesktopOpenSpec
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import com.orailnoor.droiddesk.display.input.shouldCaptureRelayedPointer
import com.orailnoor.droiddesk.packages.PackageStoreException
import com.orailnoor.droiddesk.packages.PackageStoreMethodHandler
import com.orailnoor.droiddesk.packages.ProcessPackageStoreRegistry
import com.orailnoor.droiddesk.packages.toWireMap

internal fun handleDesktopPlacementOverrideCall(
    arguments: Any?,
    coordinator: DesktopDisplayCoordinator,
    result: MethodChannel.Result,
    isProvisioningActive: () -> Boolean = { false },
    onDisplayFailure: (DesktopDisplayException, MethodChannel.Result) -> Unit = { err, res -> res.error(err.code, err.message, null) },
) {
    val mode = try {
        val map = arguments as? Map<*, *> ?: throw IllegalArgumentException("Invalid desktop placement mode")
        require(map.containsKey("placementMode")) { "placementMode is required" }
        when (val raw = map["placementMode"]) {
            null -> null
            is String -> PlacementMode.entries.firstOrNull { it.wireName == raw } ?: throw IllegalArgumentException("Invalid desktop placement mode")
            else -> throw IllegalArgumentException("Invalid desktop placement mode")
        }
    } catch (error: IllegalArgumentException) {
        result.error("INVALID_ARGUMENT", error.message, null); return
    }
    if (isProvisioningActive()) {
        onDisplayFailure(DesktopDisplayException("INVALID_ARGUMENT", "Stop Linux before changing desktop placement mode"), result); return
    }
    try {
        result.success(coordinator.setPlacementOverride(mode))
    } catch (error: DesktopDisplayException) {
        onDisplayFailure(error, result)
    } catch (error: IllegalStateException) {
        result.error("DISPLAY_SETTINGS_SAVE_FAILED", error.message, null)
    }
}

internal fun forwardResolvedDesktopOpen(
    currentDisplayId: Int?,
    mode: String,
    desktopEnv: String,
    startSession: Boolean,
    expectedEpoch: Long?,
    startService: () -> Unit,
    isRestarting: () -> Boolean,
    requestOpen: (DesktopOpenRequest, () -> Unit, (Result<Unit>) -> Unit) -> Unit,
    completion: (Result<Unit>) -> Unit,
) {
    if (currentDisplayId == null) return completion(Result.failure(DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app window has no current display")))
    requestOpen(
        DesktopOpenRequest(currentDisplayId, mode, desktopEnv, startSession, expectedEpoch),
        { if (startSession || isRestarting()) startService() },
        completion,
    )
}

internal fun deferResolvedDesktopOpenIfNeeded(
    isAttached: Boolean,
    expectedEpoch: Long?,
    sessionGeneration: Long,
    isStopped: Boolean,
    isSessionCurrent: (Long) -> Boolean,
    deferUntilAttached: (() -> Unit) -> Unit,
    retry: (Long?) -> Unit,
    cancel: () -> Unit,
    userStopGeneration: Long = 0L, currentUserStopGeneration: () -> Long = { userStopGeneration },
    isRestarting: () -> Boolean = { false }, currentSessionGeneration: () -> Long = { sessionGeneration },
    hasRunningSession: () -> Boolean = { false },
    hasInFlightOpen: () -> Boolean = { false }, isProvisioningActive: () -> Boolean = { false },
): Boolean {
    if (isAttached) return false
    val wasRestarting = isRestarting()
    val deferredEpoch = expectedEpoch ?: sessionGeneration.takeUnless { isStopped && !wasRestarting && !hasRunningSession() }
    deferUntilAttached {
        val userStopped = currentUserStopGeneration() != userStopGeneration
        when {
            userStopped || (isProvisioningActive() && (expectedEpoch == null || !isSessionCurrent(expectedEpoch))) -> cancel()
            isRestarting() -> retry(if (deferredEpoch == null || isSessionCurrent(deferredEpoch)) deferredEpoch else currentSessionGeneration())
            deferredEpoch == null || isSessionCurrent(deferredEpoch) || (hasRunningSession() && deferredEpoch == currentSessionGeneration()) -> retry(deferredEpoch)
            hasInFlightOpen() || (expectedEpoch == null && hasRunningSession()) -> retry(expectedEpoch)
            wasRestarting -> retry(null)
            else -> cancel()
        }
    }
    return true
}

internal data class ProvisioningOpenCallbacks(val startService: () -> Unit, val onCompletion: (Result<Unit>) -> Unit)
internal fun provisioningOpenCallbacks(epoch: Long, startForegroundService: () -> Unit, completeProvisioning: (Long) -> Unit, failProvisioning: (Long, Throwable) -> Unit) =
    ProvisioningOpenCallbacks({ startForegroundService(); completeProvisioning(epoch) }) { it.exceptionOrNull()?.let { err -> failProvisioning(epoch, err) } ?: completeProvisioning(epoch) }

internal sealed interface DesktopItemAdmission {
    data class InFlight(val open: InFlightDesktopOpenSpec) : DesktopItemAdmission
    data class Epoch(val expectedEpoch: Long) : DesktopItemAdmission
}
internal fun admitDesktopItemLaunch(inFlight: InFlightDesktopOpenSpec?, isStopped: Boolean, hasRunningSession: Boolean, sessionGeneration: Long, beginSession: () -> Long, provisioningInFlight: Boolean = false): DesktopItemAdmission =
    if (inFlight != null) DesktopItemAdmission.InFlight(inFlight)
    else if (provisioningInFlight) throw DesktopDisplayException("INVALID_ARGUMENT", "Linux desktop is still provisioning")
    else DesktopItemAdmission.Epoch(if (!isStopped && hasRunningSession) sessionGeneration else beginSession())

internal fun isDesktopItemSessionLive(initialStopGeneration: Long, currentUserStopGeneration: Long, isStopped: Boolean, isRestarting: Boolean): Boolean =
    currentUserStopGeneration == initialStopGeneration && (!isStopped || isRestarting)

internal fun <S> resolveDesktopItemStatus(
    expectedEpoch: Long, isSessionCurrent: (Long) -> Boolean, status: Result<S>, isStatusRunning: (S) -> Boolean,
    sessionLive: Boolean, hasRunningSession: Boolean = false, isRestarting: Boolean = false, provisioningInFlight: Boolean = false,
    inFlightOpen: () -> InFlightDesktopOpenSpec?, currentRunningOpen: (() -> InFlightDesktopOpenSpec)? = null,
    onInFlight: (InFlightDesktopOpenSpec) -> Unit, onReady: (S, Boolean, Long?) -> Unit, onFailure: (Throwable) -> Unit,
) {
    if (!sessionLive || provisioningInFlight) return onFailure(DesktopDisplayException("SESSION_STOPPED", "The session was stopped before the asynchronous desktop action completed"))
    inFlightOpen()?.let { onInFlight(it); return }
    val currentEpoch = expectedEpoch.takeIf(isSessionCurrent)
    if (currentEpoch == null) {
        if (hasRunningSession && currentRunningOpen != null) return onInFlight(currentRunningOpen())
        if (!hasRunningSession) return onFailure(DesktopDisplayException("SESSION_STOPPED", "The session was stopped before the asynchronous desktop action completed"))
    }
    status.fold({ onReady(it, !isStatusRunning(it) && !hasRunningSession && !isRestarting, currentEpoch) }, onFailure)
}

internal fun runDesktopItemAfterOpen(
    openEpoch: Long, stopGeneration: Long, coordinator: DesktopDisplayCoordinator, isProvisioningActive: () -> Boolean,
    spawnReadiness: (() -> Unit) -> Unit, waitUntilReady: () -> Boolean, executeOnSession: (() -> Unit) -> Unit,
    executeCommand: () -> Result<Pair<Int, String>>, postToMain: (() -> Unit) -> Unit, onRejoin: (InFlightDesktopOpenSpec) -> Unit, result: MethodChannel.Result,
) {
    val settled = java.util.concurrent.atomic.AtomicBoolean(false)
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val readinessThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    var observedPreserveRestart = false; var unsubscribe: () -> Unit = {}
    fun isEpochActive() = coordinator.userStopGeneration == stopGeneration && !isProvisioningActive() && coordinator.isSessionCurrent(openEpoch)
    fun finish(action: () -> Unit): Boolean { if (settled.compareAndSet(false, true)) { unsubscribe(); action() }; return true }
    fun settleOnMain(failure: Throwable? = null, output: Pair<Int, String>? = null): Boolean {
        if (settled.get()) return true
        if (coordinator.userStopGeneration != stopGeneration || isProvisioningActive() || (coordinator.isStopped && !coordinator.isRestarting)) {
            return finish { result.error("SESSION_STOPPED", "Desktop launch was cancelled", null) }
        }
        if (coordinator.isRestarting || !coordinator.isSessionCurrent(openEpoch)) {
            val rejoin = when {
                coordinator.isRestarting -> coordinator.inFlightOpenSpec()
                observedPreserveRestart && coordinator.hasRunningSession ->
                    coordinator.inFlightOpenSpec() ?: InFlightDesktopOpenSpec(coordinator.sessionMode, coordinator.desktopEnv, false, coordinator.sessionGeneration)
                else -> null
            }
            return finish { if (rejoin != null) onRejoin(rejoin) else result.error("SESSION_STOPPED", "Desktop launch was cancelled", null) }
        }
        if (failure != null) return finish { result.error((failure as? DesktopDisplayException)?.code ?: "DESKTOP_LAUNCH_FAILED", failure.message ?: "The Linux desktop did not become ready", null) }
        if (output != null) return finish { if (output.first != 0) result.error("DESKTOP_LAUNCH_FAILED", output.second, null) else result.success(true) }
        if (cancelled.get()) return finish { result.error("SESSION_STOPPED", "Desktop launch was cancelled", null) }
        return false
    }
    unsubscribe = coordinator.subscribe {
        if (settled.get() || isEpochActive()) return@subscribe
        if (coordinator.isRestarting) observedPreserveRestart = true
        cancelled.set(true)
        readinessThread.get()?.takeIf { it !== Thread.currentThread() }?.interrupt()
        postToMain { settleOnMain() }
    }
    if (settleOnMain()) return
    spawnReadiness {
        val currentThread = Thread.currentThread()
        readinessThread.set(currentThread)
        try {
            if (cancelled.get() || settled.get()) { postToMain { settleOnMain() }; return@spawnReadiness }
            val readiness = DesktopItemReadinessGate(
                isSessionCurrent = { !cancelled.get() && !settled.get() && isEpochActive() },
                waitUntilReady = waitUntilReady,
            ).await {
                executeOnSession {
                    if (cancelled.get() || settled.get() || !isEpochActive()) { postToMain { settleOnMain() }; return@executeOnSession }
                    val launched = executeCommand()
                    postToMain { launched.fold({ settleOnMain(output = it) }, { settleOnMain(failure = it) }) }
                }
            }
            readiness.exceptionOrNull()?.let { failure -> postToMain { settleOnMain(failure = failure) } }
        } finally {
            readinessThread.compareAndSet(currentThread, null)
            if (cancelled.get() || settled.get()) Thread.interrupted()
        }
    }
}

internal fun userStopLaunchReply(result: MethodChannel.Result, coordinator: DesktopDisplayCoordinator): MethodChannel.Result {
    val stopGeneration = coordinator.userStopGeneration; val completed = java.util.concurrent.atomic.AtomicBoolean(false); var unsubscribe: () -> Unit = {}
    fun settle(block: () -> Unit) { if (completed.compareAndSet(false, true)) { unsubscribe(); block() } }
    unsubscribe = coordinator.subscribe { if (it["phase"] == "stopped" && coordinator.userStopGeneration != stopGeneration) settle { result.error("SESSION_STOPPED", "The session stopped before launch completed", null) } }
    return object : MethodChannel.Result {
        override fun success(value: Any?) = settle { result.success(value) }
        override fun error(code: String, message: String?, details: Any?) = settle { result.error(code, message, details) }
        override fun notImplemented() = settle { result.notImplemented() }
    }
}

internal fun shouldDeferResolvedDesktopOpen(
    displayId: Int?,
    isAttached: Boolean,
    allowsManualMoves: Boolean,
): Boolean = !isAttached && (displayId == null || !allowsManualMoves)

internal fun resolveDeferredOpenStart(start: Boolean, wasRestarting: Boolean, isRestarting: Boolean, hasRunningSession: Boolean): Boolean =
    if (wasRestarting && !isRestarting) !hasRunningSession else start && (!hasRunningSession || isRestarting)

internal class AsyncDesktopAdmission(
    val expectedEpoch: Long,
    private val isCurrent: (Long) -> Boolean,
) {
    fun current(): Result<Unit> =
        if (isCurrent(expectedEpoch)) Result.success(Unit) else Result.failure(stopped())
    fun <T> cross(operation: () -> T): Result<T> {
        current().exceptionOrNull()?.let { return Result.failure(it) }
        val value = try { operation() } catch (error: Exception) {
            return Result.failure(current().exceptionOrNull() ?: error)
        }
        return current().map { value }
    }
    private fun stopped() = DesktopDisplayException(
        "SESSION_STOPPED", "The session was stopped before the asynchronous desktop action completed")
}

internal fun settleProvisioningStartFailure(
    epoch: Long, error: RuntimeException, isSessionCurrent: (Long) -> Boolean,
    settleSession: (Long, Result<Unit>) -> Unit = { _, _ -> }, reportFailure: (DesktopDisplayException) -> Unit,
) {
    val failure = error as? DesktopDisplayException ?: DesktopDisplayException("DISPLAY_NOT_ALLOWED",
        if (error is SecurityException) "Android denied starting the Linux service"
        else error.message ?: "Android could not start the Linux service")
    if (isSessionCurrent(epoch)) settleSession(epoch, Result.failure(failure))
    reportFailure(failure)
}

internal fun <S> readDesktopRuntimeStatus(
    readStatus: () -> S, onFailure: (Exception) -> Unit,
): S? = try { readStatus() } catch (error: Exception) { onFailure(error); null }

internal fun <S> readEligibleExternalAutoStartStatus(
    diagnostics: DesktopPlacementDiagnostics,
    readStatus: () -> S,
    shouldAutoLaunch: (S) -> Boolean,
    onFailure: (Exception) -> Unit,
): S? {
    if (!diagnostics.allowsManualMoves || !diagnostics.shouldAutoLaunchCurrentDisplay) return null
    return readDesktopRuntimeStatus(readStatus, onFailure)?.takeIf(shouldAutoLaunch)
}

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
        private val provisioningEpoch = java.util.concurrent.atomic.AtomicLong(0L)
    }

    /** Icon lookups may download from the network; cap concurrency so a long list doesn't time out. */
    private val linuxIconExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var desktopIntegration: DesktopIntegration
    private lateinit var runtimeCapabilityCache: RuntimeCapabilityCache
    private lateinit var environmentManager: LinuxEnvironmentManager
    private lateinit var packageStoreHandler: PackageStoreMethodHandler
    private lateinit var packageStoreManager: com.orailnoor.droiddesk.packages.PackageStoreManager
    private var packageStoreSubscriberId: Long? = null
    private var displayStateSubscription: (() -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logBuffer = StringBuffer()
    private var lastLogFlushTime = 0L
    private val logFlushRunnable = Runnable { flushLogBuffer() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.device?.isExternal == true &&
            ExternalDesktopInputBridge.dispatchKeyEvent(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.device?.isExternal == true && ExternalDesktopInputBridge.isAttached()) {
            maintainExternalPointerCapture()
            ExternalDesktopInputBridge.dispatchMotionEvent(window.decorView, event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.device?.isExternal == true && ExternalDesktopInputBridge.isAttached()) {
            maintainExternalPointerCapture()
            ExternalDesktopInputBridge.dispatchMotionEvent(window.decorView, event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        if (!hasCapture) window.decorView.postDelayed(::maintainExternalPointerCapture, 100)
    }

    private fun maintainExternalPointerCapture() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            shouldCaptureRelayedPointer(
                relayAttached = ExternalDesktopInputBridge.isAttached(),
                hasFocus = hasWindowFocus(),
            ) &&
            !window.decorView.hasPointerCapture()
        ) {
            window.decorView.requestPointerCapture()
        }
    }

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
        val processRuntime = com.orailnoor.droiddesk.runtime
            .ProcessLinuxEnvironmentRegistry.get(applicationContext)
        linuxRuntime = processRuntime.linuxRuntime
        chrootRuntime = processRuntime.chrootRuntime
        runtimeCapabilityCache = processRuntime.capabilityCache
        environmentManager = processRuntime.environmentManager
        packageStoreManager = ProcessPackageStoreRegistry.getOrCreate(
            applicationContext,
            environmentManager,
            linuxRuntime,
        )
        packageStoreHandler = PackageStoreMethodHandler(packageStoreManager)
        packageStoreSubscriberId = packageStoreManager.subscribe(
            onEvent = { event ->
                runOnUiThread {
                    val engine = flutterEngine ?: return@runOnUiThread
                    MethodChannel(
                        engine.dartExecutor.binaryMessenger,
                        CHANNEL,
                    ).invokeMethod("onPackageStoreOperationEvent", event.toWireMap())
                }
            },
            onResult = { operationResult ->
                runOnUiThread {
                    val engine = flutterEngine ?: return@runOnUiThread
                    MethodChannel(
                        engine.dartExecutor.binaryMessenger,
                        CHANNEL,
                    ).invokeMethod("onPackageStoreOperationResult", operationResult.toWireMap())
                }
            },
        )
        desktopIntegration = DesktopIntegration(this)
        DesktopDisplayCoordinator.shared.initialize(applicationContext)
        DesktopDisplayCoordinator.shared.bindActivity(this)
        window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!DesktopDisplayCoordinator.shared.allowsManualMoves) {
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
            if (!intent.getBooleanExtra("autoSetup", false)) { handleHomeLaunch(intent); maybeAutoStartDesktop(intent) }
        }
        window.decorView.post {
            if (!isDestroyed && !isFinishing) maybeAutoStartExternalDesktop()
        }
    }

    private fun isProvisioningActive(coordinator: DesktopDisplayCoordinator) = provisioningEpoch.get().let { it > 0L && coordinator.isSessionCurrent(it) }

    private fun maybeAutoStartExternalDesktop() {
        val coordinator = DesktopDisplayCoordinator.shared
        if (isProvisioningActive(coordinator)) return
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: window.decorView.display?.displayId
        } else {
            window.decorView.display?.displayId
        } ?: return
        val status = readEligibleExternalAutoStartStatus(
            coordinator.placementDiagnostics(displayId, installed = true),
            environmentManager::status,
            { coordinator.shouldAutoLaunchCurrentDisplay(displayId, it.installed) },
            ::displayFailure,
        ) ?: return
        requestResolvedDesktopOpen(
            status.kind.sessionMode,
            status.desktop,
            !status.running,
            currentDisplayId = displayId,
        )
    }

    /** Optional "Auto-Start Linux Desktop" setting: open the chroot desktop whenever the app starts. */
    private fun maybeAutoStartDesktop(launchIntent: Intent) {
        // Home presses and the notification's Open action already launch the desktop.
        val isHomeLaunch = launchIntent.action == Intent.ACTION_MAIN && launchIntent.hasCategory(Intent.CATEGORY_HOME)
        if (isHomeLaunch || launchIntent.action == ACTION_OPEN_DESKTOP || launchIntent.action == ACTION_RETURN_TO_PHONE) return
        if (isProvisioningActive(DesktopDisplayCoordinator.shared)) return
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        if (!DesktopDisplayCoordinator.shared.placementDiagnostics().allowsManualMoves) return
        val autoStart = prefs.getBoolean("flutter.autoStartDesktop", false)
        if (autoStart) {
            val status = readDesktopRuntimeStatus(environmentManager::status, ::displayFailure) ?: return
            if (status.installed && status.desktop.isNotEmpty()) requestResolvedDesktopOpen(
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
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    shouldCaptureRelayedPointer(
                        relayAttached = ExternalDesktopInputBridge.isAttached(),
                        hasFocus = hasWindowFocus(),
                    )
                ) {
                    maintainExternalPointerCapture()
                }
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
        pendingAttachCancellations.toList().also { pendingAttachCancellations.clear() }.forEach { it() }
        packageStoreSubscriberId?.let(packageStoreManager::unsubscribe)
        packageStoreSubscriberId = null
        DesktopDisplayCoordinator.shared.unbindActivity(this)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restoreSystemBars()
            window.decorView.post { restoreSystemBars() }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                shouldCaptureRelayedPointer(
                    relayAttached = ExternalDesktopInputBridge.isAttached(),
                    hasFocus = true,
                )
            ) {
                maintainExternalPointerCapture()
            }
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
        val isLocalLauncher = !coordinator.placementDiagnostics().allowsManualMoves &&
            homeIntent.action == Intent.ACTION_MAIN &&
            homeIntent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (!isHomeLaunch && !isLocalLauncher && homeIntent.action != ACTION_OPEN_DESKTOP && homeIntent.action != ACTION_RETURN_TO_PHONE) return
        if (homeIntent.action == ACTION_RETURN_TO_PHONE) {
            coordinator.moveToPhone { outcome -> outcome.exceptionOrNull()?.let { displayFailure(it) } }
            return
        }
        coordinator.inFlightOpenSpec()?.let {
            requestResolvedDesktopOpen(it.mode, it.desktopEnv, it.startSession, expectedEpoch = it.expectedEpoch)
            return
        }
        if (isProvisioningActive(coordinator)) return
        if (readDesktopRuntimeStatus(environmentManager::status, ::displayFailure)?.running ?: return) {
            requestResolvedDesktopOpen(
                coordinator.sessionMode,
                coordinator.desktopEnv,
                start = false,
                expectedEpoch = coordinator.sessionGeneration,
            )
            return
        }
        val epoch = try {
            coordinator.beginSession()
        } catch (error: DesktopDisplayException) {
            displayFailure(error)
            return
        }
        val admission = AsyncDesktopAdmission(epoch, coordinator::isSessionCurrent)

        thread(name = "home-launch") {
            val status = admission.cross(environmentManager::status).getOrElse { failure ->
                runOnUiThread { displayFailure(admission.current().exceptionOrNull() ?: failure) }; return@thread
            }
            if (!status.installed || status.desktop.isEmpty()) {
                Log.i(TAG, "Home launch opened setup because Linux is not ready")
                return@thread
            }

            runOnUiThread {
                admission.current().exceptionOrNull()?.let { displayFailure(it); return@runOnUiThread }
                requestResolvedDesktopOpen(status.kind.sessionMode, status.desktop, !status.running, expectedEpoch = epoch)
            }
        }
    }

    /**
     * Hidden developer/auto-tester path: download, extract, install, and launch
     * the chroot desktop without any Flutter UI interaction.
     */
    private fun runAutoChrootSetup() {
        val coordinator = DesktopDisplayCoordinator.shared
        coordinator.inFlightOpenSpec()?.takeIf { it.mode == "chroot" && it.desktopEnv == "xfce4" }?.let {
            requestResolvedDesktopOpen(it.mode, it.desktopEnv, it.startSession, expectedEpoch = it.expectedEpoch); return
        }
        if (isProvisioningActive(coordinator)) return
        val epoch = try {
            coordinator.beginSession()
        } catch (error: DesktopDisplayException) {
            displayFailure(error)
            return
        }
        provisioningEpoch.set(epoch)
        val admission = AsyncDesktopAdmission(epoch, coordinator::isSessionCurrent)
        thread(name = "auto-chroot-setup") {
            try {
                Log.i(TAG, "Auto-setup: checking root...")
                if (!admission.cross(chrootRuntime::hasRoot).getOrThrow()) {
                    provisioningEpoch.compareAndSet(epoch, 0L)
                    runOnUiThread {
                        if (admission.current().isSuccess)
                            Toast.makeText(this, "Auto-setup requires root", Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                admission.cross { DroidDeskService.beginProvisioning(this, epoch, "Preparing Linux desktop") }.getOrThrow()

                if (!admission.cross(chrootRuntime::isRootfsReady).getOrThrow()) {
                    Log.i(TAG, "Auto-setup: downloading rootfs...")
                    val dlLatch = java.util.concurrent.CountDownLatch(1)
                    var dlOk = false
                    chrootRuntime.downloadRootfs { progress, status ->
                        if (progress >= 0.0) DroidDeskService.updateProvisioning(epoch, status)
                        if (progress >= 1.0 || progress < 0) {
                            dlOk = progress >= 1.0
                            dlLatch.countDown()
                        }
                    }
                    dlLatch.await()
                    admission.current().getOrThrow(); check(dlOk) { "Rootfs download failed" }

                    Log.i(TAG, "Auto-setup: extracting rootfs...")
                    val exLatch = java.util.concurrent.CountDownLatch(1)
                    var exOk = false
                    chrootRuntime.extractRootfs { progress, status ->
                        if (progress >= 0.0) DroidDeskService.updateProvisioning(epoch, status)
                        if (progress >= 1.0 || progress < 0) {
                            exOk = progress >= 1.0
                            exLatch.countDown()
                        }
                    }
                    exLatch.await()
                    admission.current().getOrThrow(); check(exOk) { "Rootfs extraction failed" }
                }

                if (!admission.cross(chrootRuntime::isDesktopInstalled).getOrThrow()) {
                    Log.i(TAG, "Auto-setup: installing desktop environment...")
                    val inLatch = java.util.concurrent.CountDownLatch(1)
                    var inOk = false
                    chrootRuntime.installDesktopEnvironment(
                        desktopEnv = "xfce4",
                        onProgress = { progress, status ->
                            if (progress >= 0.0) DroidDeskService.updateProvisioning(epoch, status)
                            if (progress >= 1.0 || progress < 0) {
                                inOk = progress >= 1.0
                                inLatch.countDown()
                            }
                        },
                        onLog = {}
                    )
                    inLatch.await()
                    admission.current().getOrThrow(); check(inOk) { "Desktop installation failed" }
                }

                Log.i(TAG, "Auto-setup: launching desktop...")
                val startSession = !admission.cross(chrootRuntime::isRunning).getOrThrow()
                runOnUiThread {
                    admission.current().onSuccess {
                        val callbacks = provisioningOpenCallbacks(epoch, ::startForegroundService, { provisioningEpoch.compareAndSet(it, 0L); DroidDeskService.completeProvisioning(it) }, { id, err -> provisioningEpoch.compareAndSet(id, 0L); DroidDeskService.failProvisioning(id, err) })
                        requestResolvedDesktopOpen("chroot", "xfce4", startSession, expectedEpoch = epoch, startService = callbacks.startService, onCompletion = callbacks.onCompletion)
                    }.onFailure { provisioningEpoch.compareAndSet(epoch, 0L); DroidDeskService.failProvisioning(epoch, it) }
                }
            } catch (e: Exception) {
                provisioningEpoch.compareAndSet(epoch, 0L)
                val reported = admission.current().exceptionOrNull() ?: e
                Log.e(TAG, "Auto-setup failed", reported)
                DroidDeskService.failProvisioning(epoch, reported)
                runOnUiThread {
                    if (admission.current().isSuccess && (reported as? DesktopDisplayException)?.code != "SESSION_STOPPED")
                        Toast.makeText(this, "Auto-setup failed: ${reported.message}", Toast.LENGTH_LONG).show()
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
                "setDesktopPlacementOverride" -> handleDesktopPlacementOverrideCall(call.arguments, displays, result, { isProvisioningActive(displays) }, ::displayFailure)
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
                        val update = com.orailnoor.droiddesk.display
                            .parseScopedDisplaySettingsUpdate(call.arguments)
                        displays.saveSettingsFor(update) {
                            replyDisplayState(reply, it)
                        }
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
                    readDesktopRuntimeStatus(environmentManager::status) {
                        displayFailure(it, result)
                    }?.let { result.success(it.toLegacyMap()) }
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    val capabilities = runtimeCapabilityCache.current()
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
                        chrootRuntime.resetRootAccessProbe()
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
                        val error = outcome.exceptionOrNull()
                        if (error != null) {
                            val message = error.message ?: "Linux environment provisioning failed"
                            Log.e(TAG, "Linux environment provisioning failed", error)
                            appendTerminalOutput("\n$message\n")
                            runOnUiThread {
                                MethodChannel(
                                    flutterEngine.dartExecutor.binaryMessenger,
                                    CHANNEL,
                                ).invokeMethod(
                                    "onInstallProgress",
                                    mapOf("progress" to -1.0, "status" to message),
                                )
                                result.success(false)
                            }
                        } else {
                            runOnUiThread { result.success(true) }
                        }
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

                // ── Environment-scoped package store ──
                "queryPackageStore" -> {
                    val arguments = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                    thread(name = "package-store-query") {
                        try {
                            val response = packageStoreHandler.query(arguments)
                            runOnUiThread { result.success(response) }
                        } catch (error: Throwable) {
                            runOnUiThread { packageStoreFailure(error, result) }
                        }
                    }
                }

                "runPackageStoreOperation" -> {
                    val arguments = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                    installThread("package-store-operation") {
                        try {
                            startForegroundService()
                            val response = packageStoreHandler.execute(arguments, observer = {})
                            runOnUiThread { result.success(response) }
                        } catch (error: Throwable) {
                            runOnUiThread { packageStoreFailure(error, result) }
                        }
                    }
                }

                "cancelPackageStoreOperation" -> {
                    val arguments = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                    thread(name = "package-store-cancel") {
                        try {
                            val cancelled = packageStoreHandler.cancel(arguments)
                            runOnUiThread { result.success(cancelled) }
                        } catch (error: Throwable) {
                            runOnUiThread { packageStoreFailure(error, result) }
                        }
                    }
                }

                "acknowledgePackageStoreOperationResult" -> {
                    val arguments = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                    try {
                        result.success(packageStoreHandler.acknowledge(arguments))
                    } catch (error: Throwable) {
                        packageStoreFailure(error, result)
                    }
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
                            val reply = userStopLaunchReply(result, displays)
                            val stopGeneration = displays.userStopGeneration
                            val itemAdmission = try {
                                admitDesktopItemLaunch(displays.inFlightOpenSpec(), displays.isStopped, displays.hasRunningSession, displays.sessionGeneration, displays::beginSession, isProvisioningActive(displays))
                            } catch (error: DesktopDisplayException) { displayFailure(error, reply); return@setMethodCallHandler }
                            when (itemAdmission) {
                                is DesktopItemAdmission.InFlight -> launchDesktopItemAfterOpen(kind, id, reply, itemAdmission.open.mode, itemAdmission.open.desktopEnv, itemAdmission.open.startSession, itemAdmission.open.expectedEpoch, stopGeneration)
                                is DesktopItemAdmission.Epoch -> thread(name = "launch-desktop-item") {
                                    val status = if (displays.userStopGeneration == stopGeneration && !isProvisioningActive(displays)) runCatching(environmentManager::status)
                                    else Result.failure(DesktopDisplayException("SESSION_STOPPED", "The session was stopped before the asynchronous desktop action completed"))
                                    runOnUiThread {
                                        resolveDesktopItemStatus(itemAdmission.expectedEpoch, displays::isSessionCurrent, status, { it.running },
                                            isDesktopItemSessionLive(stopGeneration, displays.userStopGeneration, displays.isStopped, displays.isRestarting), displays.hasRunningSession, displays.isRestarting, isProvisioningActive(displays), displays::inFlightOpenSpec,
                                            { InFlightDesktopOpenSpec(displays.sessionMode, displays.desktopEnv, false, displays.sessionGeneration) },
                                            { launchDesktopItemAfterOpen(kind, id, reply, it.mode, it.desktopEnv, it.startSession, it.expectedEpoch, stopGeneration) },
                                            { ready, startSession, epoch -> launchDesktopItemAfterOpen(kind, id, reply, ready.kind.sessionMode, ready.desktop, startSession, epoch, stopGeneration) },
                                            { displayFailure(it, reply) })
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
                    val startReply = userStopLaunchReply(result, displays)
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val inFlight = displays.inFlightOpenSpec()
                    if (inFlight != null && inFlight.desktopEnv == desktopEnv) {
                        requestResolvedDesktopOpen(inFlight.mode, desktopEnv, inFlight.startSession, startReply, expectedEpoch = inFlight.expectedEpoch)
                        return@setMethodCallHandler
                    }
                    if (isProvisioningActive(displays)) { displayFailure(DesktopDisplayException("INVALID_ARGUMENT", "Linux desktop is still provisioning"), startReply); return@setMethodCallHandler }
                    val status = readDesktopRuntimeStatus(environmentManager::status) {
                        displayFailure(it, startReply)
                    } ?: return@setMethodCallHandler
                    val environmentKind = status.kind
                    val ready = status.installed && status.desktop == desktopEnv
                    if (ready) {
                        requestResolvedDesktopOpen(
                            environmentKind.sessionMode,
                            desktopEnv,
                            !status.running && !displays.isRestarting,
                            startReply,
                            expectedEpoch = displays.sessionGeneration.takeIf { displays.isRestarting },
                        )
                        return@setMethodCallHandler
                    }
                    val epoch = try {
                        displays.beginSession()
                    } catch (error: DesktopDisplayException) {
                        displayFailure(error, startReply)
                        return@setMethodCallHandler
                    }
                    provisioningEpoch.set(epoch)
                    val admission = AsyncDesktopAdmission(epoch, displays::isSessionCurrent)
                    try { DroidDeskService.beginProvisioning(this, epoch, "Preparing Linux desktop") } catch (error: RuntimeException) {
                        provisioningEpoch.compareAndSet(epoch, 0L)
                        settleProvisioningStartFailure(epoch, error, displays::isSessionCurrent) { displayFailure(it, startReply) }
                        return@setMethodCallHandler
                    }
                    installThread("prepare-linux-environment") {
                        try {
                            admission.cross {
                                environmentManager.provision(
                                    ProvisionRequest(environmentKind, desktopEnv),
                                    onProgress = { progress, message ->
                                        if (progress >= 0.0) DroidDeskService.updateProvisioning(epoch, message)
                                        runOnUiThread {
                                            if (admission.current().isSuccess) MethodChannel(
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
                            }.getOrThrow()
                            runOnUiThread {
                                admission.current().onSuccess {
                                    val callbacks = provisioningOpenCallbacks(epoch, ::startForegroundService, { provisioningEpoch.compareAndSet(it, 0L); DroidDeskService.completeProvisioning(it) }, { id, err -> provisioningEpoch.compareAndSet(id, 0L); DroidDeskService.failProvisioning(id, err) })
                                    requestResolvedDesktopOpen(environmentKind.sessionMode, desktopEnv, true, startReply, expectedEpoch = epoch, startService = callbacks.startService, onCompletion = callbacks.onCompletion)
                                }.onFailure { provisioningEpoch.compareAndSet(epoch, 0L); DroidDeskService.failProvisioning(epoch, it); displayFailure(it, startReply) }
                            }
                        } catch (error: Exception) {
                            provisioningEpoch.compareAndSet(epoch, 0L)
                            val currentError = admission.current().exceptionOrNull() ?: error
                            Log.e(TAG, "Linux environment preparation failed", currentError)
                            DroidDeskService.failProvisioning(epoch, currentError)
                            runOnUiThread {
                                val reported = admission.current().exceptionOrNull() ?: currentError
                                startReply.error(
                                    (reported as? DesktopDisplayException)?.code ?: "SESSION_STOPPED",
                                    reported.message,
                                    null,
                                )
                            }
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    if (isProvisioningActive(displays)) { displayFailure(DesktopDisplayException("INVALID_ARGUMENT", "Linux desktop is still provisioning"), result); return@setMethodCallHandler }
                    val status = readDesktopRuntimeStatus(environmentManager::status) {
                        displayFailure(it, result)
                    } ?: return@setMethodCallHandler
                    requestResolvedDesktopOpen(
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
                    val stdin = call.argument<Boolean>("stdin") ?: false
                    Thread {
                        val execution = environmentManager.execute(
                            ExecutionRequest.Shell(
                                environmentManager.activeKind(),
                                command,
                                ::appendTerminalOutput,
                                interactive = true,
                                stdin = stdin,
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

    private fun requestResolvedDesktopOpen(
        mode: String,
        desktop: String,
        start: Boolean,
        result: MethodChannel.Result? = null,
        expectedEpoch: Long? = null,
        currentDisplayId: Int? = null,
        startService: () -> Unit = ::startForegroundService,
        onCompletion: ((Result<Unit>) -> Unit)? = null,
    ) {
        val finish: (Result<Unit>) -> Unit = { outcome ->
            onCompletion?.invoke(outcome)
            outcome.exceptionOrNull()?.let { displayFailure(it, result) } ?: result?.success(true)
        }
        val coordinator = DesktopDisplayCoordinator.shared
        val wasRestarting = coordinator.isRestarting
        val decorView = window.decorView
        val displayId = currentDisplayId ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: decorView.display?.displayId
        } else {
            decorView.display?.displayId
        } ?: if (coordinator.placementDiagnostics().allowsManualMoves) {
            windowManager.defaultDisplay.displayId
        } else {
            null
        }
        val needsAttach = shouldDeferResolvedDesktopOpen(displayId, decorView.isAttachedToWindow, coordinator.placementDiagnostics(displayId).allowsManualMoves)
        if (needsAttach && (isFinishing || isDestroyed)) return finish(Result.failure(DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app window is no longer available")))
        if (needsAttach && deferResolvedDesktopOpenIfNeeded(
                isAttached = decorView.isAttachedToWindow,
                expectedEpoch = expectedEpoch,
                sessionGeneration = coordinator.sessionGeneration,
                isStopped = coordinator.isStopped,
                isSessionCurrent = coordinator::isSessionCurrent,
                deferUntilAttached = { retry ->
                    var abortOnDestroy: () -> Unit = {}
                    val listener = object : android.view.View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: android.view.View) {
                            pendingAttachCancellations.remove(abortOnDestroy); view.removeOnAttachStateChangeListener(this); retry()
                        }

                        override fun onViewDetachedFromWindow(view: android.view.View) = Unit
                    }
                    abortOnDestroy = { decorView.removeOnAttachStateChangeListener(listener); finish(Result.failure(DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app window was destroyed before launch"))) }
                    pendingAttachCancellations += abortOnDestroy; decorView.addOnAttachStateChangeListener(listener)
                },
                retry = { deferredEpoch ->
                    requestResolvedDesktopOpen(
                        mode = mode,
                        desktop = desktop,
                        start = resolveDeferredOpenStart(start, wasRestarting, coordinator.isRestarting, coordinator.hasRunningSession),
                        result = result,
                        expectedEpoch = deferredEpoch,
                        startService = startService,
                        onCompletion = onCompletion,
                    )
                },
                cancel = {
                    finish(Result.failure(DesktopDisplayException("SESSION_STOPPED", "The session was stopped before launch")))
                },
                userStopGeneration = coordinator.userStopGeneration,
                currentUserStopGeneration = { coordinator.userStopGeneration },
                isRestarting = { coordinator.isRestarting },
                currentSessionGeneration = { coordinator.sessionGeneration },
                hasRunningSession = { coordinator.hasRunningSession },
                hasInFlightOpen = { coordinator.inFlightOpenSpec() != null },
                isProvisioningActive = { isProvisioningActive(coordinator) },
            )
        ) return
        forwardResolvedDesktopOpen(
            currentDisplayId = displayId,
            mode = mode,
            desktopEnv = desktop,
            startSession = start,
            expectedEpoch = expectedEpoch,
            startService = startService,
            isRestarting = { coordinator.isRestarting },
            requestOpen = coordinator::requestOpen,
            completion = finish,
        )
    }

    private fun launchDesktopItemAfterOpen(
        kind: String, id: String, result: MethodChannel.Result, mode: String, desktop: String,
        startSession: Boolean, expectedEpoch: Long?, stopGeneration: Long = DesktopDisplayCoordinator.shared.userStopGeneration,
    ) {
        val coordinator = DesktopDisplayCoordinator.shared
        requestResolvedDesktopOpen(mode, desktop, startSession,
            object : MethodChannel.Result {
                override fun success(value: Any?) {
                    val openEpoch = coordinator.sessionGeneration
                    val activeDesktop = coordinator.desktopEnv; val environmentKind = EnvironmentKind.fromSessionMode(coordinator.sessionMode)
                    runDesktopItemAfterOpen(openEpoch, stopGeneration, coordinator, { isProvisioningActive(coordinator) },
                        { block -> thread(name = "local-desktop-item-ready") { block() } },
                        { environmentManager.waitForDesktopReady(activeDesktop, kind = environmentKind) },
                        DroidDeskService::executeSession,
                        { runCatching { environmentManager.execute(ExecutionRequest.Desktop(environmentKind, desktopItemCommand(kind, id, environmentManager.paths(environmentKind)))).let { it.exitCode to it.output } } },
                        { mainHandler.post(it) },
                        { launchDesktopItemAfterOpen(kind, id, result, it.mode, it.desktopEnv, it.startSession, it.expectedEpoch, stopGeneration) },
                        result)
                }
                override fun error(code: String, message: String?, details: Any?) = result.error(code, message, details)
                override fun notImplemented() = result.notImplemented()
            }, expectedEpoch = expectedEpoch)
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

    private fun packageStoreFailure(error: Throwable, result: MethodChannel.Result) {
        Log.e(TAG, "Package store operation failed", error)
        val typed = error as? PackageStoreException
        result.error(
            typed?.code ?: "INTERNAL_ERROR",
            error.message ?: "Package store operation failed",
            null,
        )
    }

    // ── Install wake lock ──

    private val pendingAttachCancellations = mutableListOf<() -> Unit>()
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
        DroidDeskService.ensureStarted(this)
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
