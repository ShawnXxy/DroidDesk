package com.orailnoor.droiddesk.display

import android.os.Looper
import android.content.Context
import android.os.Build
import android.app.Activity
import android.content.Intent
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopPlacementPort
import com.orailnoor.droiddesk.display.placement.DesktopPlacementStrategy
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.MovableSecondaryDisplayAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import com.orailnoor.droiddesk.display.startup.*
import com.orailnoor.droiddesk.service.DesktopStopReason
import com.orailnoor.droiddesk.service.DroidDeskService
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class RendererLease(val generation: Long, val displayId: Int)

sealed interface RendererClaim {
    data class Accepted(val lease: RendererLease) : RendererClaim
    data object Rejected : RendererClaim
}

data class DesktopLaunchRequest(
    val display: DesktopDisplay,
    val requestId: Long,
    val sessionGeneration: Long,
    val mode: String,
    val desktopEnv: String,
)

interface DesktopDisplayHost {
    fun currentDisplay(): DesktopDisplay? = null
    fun currentDisplayId(): Int? = currentDisplay()?.id
    fun bindActivity(activity: Activity): Boolean = true
    fun unbindActivity(activity: Activity) {}
    fun displays(): List<DesktopDisplay>
    fun defaultProfileKind(): ProfileKind
    fun sessionRunning(): Boolean
    fun launch(request: DesktopLaunchRequest)
    fun focus(requestId: Long): Boolean
    fun cancelLaunch(requestId: Long)
    fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit
    fun notifyReturnRequired(error: DesktopDisplayException)
}

private class ReplaceableDesktopPlacementStrategy(
    initial: DesktopPlacementStrategy,
) : DesktopPlacementStrategy {
    private var delegate = initial

    fun replace(strategy: DesktopPlacementStrategy) {
        delegate = strategy
    }

    override val selection get() = delegate.selection
    override val allowsManualMoves get() = delegate.allowsManualMoves
    override val allowsAutomaticMoves get() = delegate.allowsAutomaticMoves
    override val environmentRestartWarning get() = delegate.environmentRestartWarning
    override fun environmentFor(display: DesktopDisplay) = delegate.environmentFor(display)
    override fun requestedOrientation(display: DesktopDisplay, originalOrientation: Int) =
        delegate.requestedOrientation(display, originalOrientation)
    override fun shouldAutoLaunchCurrentDisplay(display: DesktopDisplay, installed: Boolean) =
        delegate.shouldAutoLaunchCurrentDisplay(display, installed)
    override fun launch(port: DesktopPlacementPort, request: DesktopLaunchRequest) =
        delegate.launch(port, request)
    override fun focus(port: DesktopPlacementPort, requestId: Long) =
        delegate.focus(port, requestId)
    override fun cancel(port: DesktopPlacementPort, requestId: Long) =
        delegate.cancel(port, requestId)
    override fun requiresSessionRestart(active: DesktopStartupTarget?, next: DesktopStartupTarget) =
        delegate.requiresSessionRestart(active, next)
    override fun fallbackTarget(
        displays: List<DesktopDisplay>,
        previous: DesktopDisplay?,
        failed: DesktopDisplay,
    ) = delegate.fallbackTarget(displays, previous, failed)
}

private fun DisplayEnvironmentPolicy.legacyPlacementStrategy(): DesktopPlacementStrategy {
    val mode = if (displayLocalMode) PlacementMode.DISPLAY_LOCAL else PlacementMode.MOVABLE
    val selection = PlacementSelection(
        mode = mode,
        source = if (displayLocalMode) {
            PlacementSelectionSource.DEVICE_QUIRK
        } else {
            PlacementSelectionSource.SAFE_DEFAULT
        },
        allowAutomaticMoves = allowCrossDisplayMoves,
        reason = "Legacy display environment policy selected ${mode.wireName} placement",
    )
    val adapter = if (displayLocalMode) {
        DisplayLocalRestartAdapter(selection)
    } else {
        MovableSecondaryDisplayAdapter(selection)
    }
    return object : DesktopPlacementStrategy by adapter {
        override fun environmentFor(display: DesktopDisplay) =
            this@legacyPlacementStrategy.environmentFor(display.id)

        override fun requestedOrientation(display: DesktopDisplay, originalOrientation: Int) =
            requestedOrientationFor(display.id, originalOrientation)
    }
}

class DesktopDisplayCoordinator(
    private val assertMainThread: () -> Unit = {},
    private var host: DesktopDisplayHost? = null,
    private var settingsStore: DisplaySettingsStore? = null,
    environmentPolicy: DisplayEnvironmentPolicy = DisplayEnvironmentPolicy.forDevice(""),
    placementStrategy: DesktopPlacementStrategy? = null,
    private var stopForEnvironmentChange: (((Result<Unit>) -> Unit) -> Unit)? = null,
) {
    private var strategyWasProvided = placementStrategy != null
    private val placementStrategy = ReplaceableDesktopPlacementStrategy(
        placementStrategy ?: environmentPolicy.legacyPlacementStrategy(),
    )
    private data class Owner(
        val lease: RendererLease,
        val detach: () -> Unit,
        var requestId: Long? = null,
        var finish: (() -> Unit)? = null,
        var actualDisplay: () -> Int = { lease.displayId },
        var launchIdentity: StartupIdentity? = null,
    )
    private data class Move(
        val request: DesktopLaunchRequest,
        val completion: (Result<Unit>) -> Unit,
        val previous: Owner?,
        val previousTarget: DesktopDisplay?,
        val recovery: Boolean,
        var cancelTimeout: () -> Unit = {},
    )
    private data class MoveRetirement(val move: Move, val incoming: Owner?, val previous: Owner?)
    private data class Continuation(val startup: StartupSnapshot, val owner: Owner?, val move: Move?,
        val requestSequence: Long, val generation: Long)
    private data class BoundActivityDisplay(
        val displayId: Int?,
        val display: DesktopDisplay?,
    )
    private data class RestartRequest(
        val target: DesktopStartupTarget,
        val mode: String,
        val desktopEnv: String,
    )
    private var owner: Owner? = null
    private var generation = 0L
    private var repository: DisplayRepository? = null
    private val boundActivities = WeakHashMap<Activity, BoundActivityDisplay>()
    private val originalActivityOrientations = WeakHashMap<Activity, Int>()
    private val listeners = mutableSetOf<(Map<String, Any?>) -> Unit>()
    private val sessionStartListeners = mutableSetOf<(Result<Unit>) -> Unit>()
    private val startupResultDeliveries = ArrayDeque<() -> Unit>()
    private val startupResultFinalizers = ArrayDeque<() -> Unit>()
    private var deliveringStartupResults = false
    private var appliedKind: ProfileKind? = null
    private var renderSize: PixelSize? = null
    private var profileRefresher: (((Result<Unit>) -> Unit) -> Unit)? = null
    private var move: Move? = null
    private var requestSequence = 0L
    private var lastTarget: DesktopDisplay? = null
    private var sessionTarget: DesktopStartupTarget? = null
    private val startupMachine = StartupAttemptMachine<(Result<Unit>) -> Unit>(1L, StartupSpec("termux", "xfce4"))
    private var acceptedLaunchIdentity: StartupIdentity? = null
    private var sessionRestarting = false
    private var restartStartService: () -> Unit = {}
    private val restartController = DisplayLocalSessionController(object : DisplayLocalSessionHost<RestartRequest> {
        override fun sessionEpoch() = publishedSessionGeneration
        override fun isSessionCurrent(epoch: Long) = this@DesktopDisplayCoordinator.isSessionCurrent(epoch)
        override fun validateTarget(target: RestartRequest) {
            validateRestartTarget(target.target)
        }
        override fun restartingChanged(restarting: Boolean) {
            sessionRestarting = restarting
            publish()
        }
        override fun stopForEnvironmentChange(completion: (Result<Unit>) -> Unit) {
            val stop = this@DesktopDisplayCoordinator.stopForEnvironmentChange
            if (stop == null) {
                completion(Result.failure(DesktopDisplayException(
                    "SESSION_RESTART_FAILED",
                    "Linux restart coordination is unavailable",
                )))
            } else {
                stop(completion)
            }
        }
        override fun startOnTarget(target: RestartRequest, completion: (Result<Unit>) -> Unit) {
            try {
                val epoch = prepareSession(target.mode, target.desktopEnv, true)
                restartStartService()
                openActive(target.target) { result ->
                    if (result.isFailure) completion(result) else awaitSessionReady(epoch, completion)
                }
            } catch (failure: DesktopDisplayException) {
                completion(Result.failure(failure))
            } catch (failure: SecurityException) {
                android.util.Log.e("DesktopDisplay", "Android denied the restarted Linux service", failure)
                completion(Result.failure(DesktopDisplayException(
                    "DISPLAY_NOT_ALLOWED",
                    "Android denied restarting the Linux service",
                )))
            }
        }
    }, assertMainThread)
    private var acceptedRequest: Long? = null
    private var phase = "hidden"
    private var error: DesktopDisplayException? = null
    private var pendingStops = 0
    private var stoppingTarget: DesktopStartupTarget? = null
    private var stopFailed = false
    private var cancelAutomaticTimer: (() -> Unit)? = null
    private var automaticTargetKey: String? = null
    private var automaticSequence = 0L
    private var automaticEpoch = 0L
    private val automaticAttempts = mutableSetOf<String>()
    private val automaticSuppressed = mutableSetOf<String>()
    val isStarting: Boolean get() = startupMachine.snapshot().let {
        !isStopped && it.baseIdentity != null && it.terminalResult == null }
    val isRestarting: Boolean get() = sessionRestarting
    val sessionFailure: DesktopDisplayException? get() = error
    val hasRunningSession: Boolean get() = (!isStopped || stopFailed) && host?.sessionRunning() == true
    val placementSelection: PlacementSelection get() = placementStrategy.selection
    val isDisplayLocal: Boolean get() = placementSelection.mode == PlacementMode.DISPLAY_LOCAL
    var sessionMode = "termux"
        private set
    var desktopEnv = "xfce4"
        private set
    @Volatile var isStopped = false
        private set
    private val observerSessionGeneration = ThreadLocal<Long?>()
    @Volatile private var publishedSessionGeneration = 1L
    var sessionGeneration: Long
        get() = observerSessionGeneration.get() ?: publishedSessionGeneration
        private set(value) { publishedSessionGeneration = value }

    fun beginSession(): Long {
        assertMainThread()
        if (pendingStops > 0) throw DesktopDisplayException("SESSION_STOPPED", "Desktop teardown is still finishing")
        val replaced = startupFailure("The Linux startup was replaced")
        val retirement = reserveMoveRetirement()
        cancelAutomaticOpening()
        val previousFailure = startupMachine.snapshot().terminalFailure
        val transition = startupMachine.reserveEpoch(replaced)
        val reservedEpoch = transition.snapshot.epoch
        sessionGeneration = reservedEpoch
        val deliverResults = queueStartupResult(transition.settlement)
        if (isStopped) {
            isStopped = false
            phase = "hidden"
            error = null
        } else if (previousFailure != null && error === previousFailure) {
            error = null
            phase = if (owner == null) "hidden" else "attached"
        }
        val continuation = captureContinuation()
        retirement?.let(::detachMoveRetirement)
        if (!isStopped && isCurrent(continuation)) publish()
        finishStartupResultDelivery(deliverResults) {
            retirement?.let { finishMoveRetirement(it, replaced) }
        }
        return reservedEpoch
    }

    fun isSessionCurrent(value: Long): Boolean = !isStopped && publishedSessionGeneration == value

    fun prepareSession(mode: String, desktop: String, start: Boolean, expectedEpoch: Long? = null): Long {
        assertMainThread()
        if (expectedEpoch != null && !isSessionCurrent(expectedEpoch)) {
            throw DesktopDisplayException("SESSION_STOPPED", "The deferred desktop action belongs to a stopped session")
        }
        require(mode == "termux" || mode == "proot" || mode == "chroot") {
            "Invalid Linux runtime"
        }
        if (pendingStops > 0) throw DesktopDisplayException("SESSION_STOPPED", "Desktop teardown is still finishing")
        val before = startupMachine.snapshot()
        val superseded = startupFailure("The Linux startup was replaced")
        val transition = try {
            if (start) startupMachine.prepareStart(
                StartupSpec(mode, desktop), superseded, expectedEpoch,
            ) else startupMachine.prepareNoStart(StartupSpec(mode, desktop), expectedEpoch)
        } catch (failure: IllegalStateException) {
            throw DesktopDisplayException("INVALID_ARGUMENT",
                failure.message ?: "The Linux startup configuration is incompatible")
        }
        val prepared = checkNotNull(transition.prepare).identity
        val incompatibleMove = move?.let { !sameStartupAttempt(prepared, startupIdentity(it.request)) } == true
        val retirement = if (start || incompatibleMove) reserveMoveRetirement() else null
        if (transition.snapshot.epoch != before.epoch) cancelAutomaticOpening()
        sessionGeneration = transition.snapshot.epoch
        val deliverResults = queueStartupResult(transition.settlement)
        val previousFailure = before.terminalFailure
        if (isStopped) {
            isStopped = false
            phase = "hidden"
            error = null
        } else if (previousFailure != null && error === previousFailure &&
            transition.snapshot.terminalFailure !== previousFailure) {
            error = null
            phase = if (owner == null) "hidden" else "attached"
        }
        sessionMode = mode
        desktopEnv = desktop
        val continuation = captureContinuation()
        retirement?.let(::detachMoveRetirement)
        if (!isStopped && isCurrent(continuation)) publish()
        finishStartupResultDelivery(deliverResults) {
            retirement?.let { finishMoveRetirement(it, superseded) }
            if (!isStopped && isCurrent(continuation) && start) profileRefresher?.invoke {}
        }
        return prepared.epoch
    }

    fun awaitSessionReady(epoch: Long, completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        if (!isSessionCurrent(epoch)) {
            completion(startupFailure("The Linux session was stopped"))
            return
        }
        when (val decision = startupMachine.await(epoch, completion)) {
            is AwaitDecision.Queued -> Unit
            is AwaitDecision.Immediate -> completion(if (decision.result.isSuccess && !hasRunningSession)
                startupFailure("Linux is not running") else decision.result)
            AwaitDecision.NoAttempt -> completion(if (hasRunningSession) Result.success(Unit) else
                Result.failure(sessionFailure ?: DesktopDisplayException(
                    "SESSION_STOPPED", "Linux is not running")))
            AwaitDecision.StaleEpoch -> completion(startupFailure("The Linux session was stopped"))
        }
    }

    fun consumeSessionStart(lease: RendererLease): Boolean {
        assertMainThread()
        val current = owner?.takeIf { it.lease == lease } ?: return false
        val renderer = rendererStartup(current) ?: return false
        if (startupMachine.reserveClaim(renderer)) return true
        val snapshot = startupMachine.snapshot()
        if (snapshot.claimState == "reserved" &&
            snapshot.claimGeneration == lease.generation) {
            val selected = snapshot.selectedIdentity
            if (!sameStartupAttempt(renderer.launchIdentity, selected)) return false
            rearmRendererClaim(current, checkNotNull(selected))
            return true
        }
        val pendingPrevious = move?.previous?.lease?.generation
        return pendingPrevious == lease.generation && snapshot.selectedIdentity?.takeIf {
            sameStartupAttempt(renderer.launchIdentity, it)
        }?.let {
            startupMachine.reserveClaim(RendererStartup(lease.generation, it))
        } == true
    }

    fun beginSessionStart(lease: RendererLease): Long? {
        assertMainThread()
        val current = owner?.takeIf { it.lease == lease } ?: return null
        val renderer = rendererStartup(current) ?: return null
        val selected = startupMachine.snapshot().selectedIdentity
        val pendingPrevious = move?.previous?.lease?.generation?.takeIf {
            it == lease.generation && sameStartupAttempt(renderer.launchIdentity, selected)
        }
        if (pendingPrevious != null && selected != null) {
            rearmRendererClaim(current, selected)
        }
        startupMachine.beginClaim(renderer, pendingPrevious)?.let { return it }
        return selected?.takeIf { pendingPrevious == lease.generation }?.let {
            startupMachine.beginClaim(RendererStartup(lease.generation, it), pendingPrevious)
        }
    }

    fun sessionStarted(epoch: Long, result: Result<Unit>) {
        assertMainThread()
        val failure = result.exceptionOrNull()?.let {
            it as? DesktopDisplayException ?: DesktopDisplayException(
                "SESSION_STOPPED", it.message ?: "Linux session failed to start") }
        val settlement = startupMachine.settle(
            epoch,
            result,
            failure = failure,
            notifyObservers = true,
        ) ?: return
        val deliverResults = queueStartupResult(settlement) {
            publish()
            if (result.isSuccess && isSessionCurrent(epoch)) refreshAutomaticOpening()
        }
        if (failure != null) {
            error = failure
            if (!isStopped) phase = "error"
        }
        finishStartupResultDelivery(deliverResults)
    }

    fun startStopping(preserveRestart: Boolean = false) {
        assertMainThread()
        if (pendingStops == 0) stoppingTarget = sessionTarget
        pendingStops++
        stop(preserveRestart)
    }

    fun finishStopping(failure: Throwable? = null) {
        assertMainThread()
        check(pendingStops > 0)
        pendingStops--
        stopFailed = failure != null
        if (failure != null) {
            sessionTarget = stoppingTarget
            phase = "error"
            reportError(DesktopDisplayException("STOP_FAILED", failure.message ?: "Linux teardown failed"))
        } else if (pendingStops == 0) {
            stoppingTarget = null
            sessionTarget = null
            phase = "stopped"
            error = null
        }
        publish()
    }

    // The cleanup belongs to this lease and runs before the next owner attaches.
    fun claim(displayId: Int, detachPrevious: () -> Unit): RendererClaim {
        assertMainThread()
        if (isStopped) return RendererClaim.Rejected
        val previous = owner
        val pending = move
        val captured = startupMachine.snapshot()
        val capturedRequestSequence = requestSequence
        val capturedGeneration = generation
        previous?.let(::clearOwner)
        previous?.detach?.invoke()
        if (pending?.previous !== previous) previous?.finish?.invoke()
        if (isStopped ||
            !sameContinuation(
                captured, null, pending, capturedRequestSequence, capturedGeneration)) {
            return RendererClaim.Rejected
        }
        val lease = RendererLease(++generation, displayId)
        val request = move?.request?.takeIf { it.display.id == displayId }
        var launchIdentity = request?.let(::startupIdentity)
            ?: previous?.launchIdentity?.takeIf { previous.lease.displayId == displayId }
            ?: acceptedLaunchIdentity?.takeIf { lastTarget?.id == displayId }
        if (launchIdentity == null && host == null && isStarting) {
            launchIdentity = startupMachine.selectTarget(
                DesktopStartupTarget(displayId, "hostless:$displayId",
                    if (displayId == 0) DisplayEnvironment.PHONE
                    else DisplayEnvironment.OTHER_EXTERNAL),
            )
        }
        owner = Owner(lease, detachPrevious, launchIdentity = launchIdentity)
        publish()
        return RendererClaim.Accepted(lease)
    }

    fun isCurrent(lease: RendererLease): Boolean = owner?.lease == lease && !isStopped

    fun registerActivity(
        lease: RendererLease,
        requestId: Long,
        actualDisplay: () -> Int = { lease.displayId },
        finish: () -> Unit,
    ) {
        assertMainThread()
        owner?.takeIf { it.lease == lease }?.let {
            it.requestId = requestId
            it.actualDisplay = actualDisplay
            it.finish = finish
        }
    }

    fun release(lease: RendererLease) {
        assertMainThread()
        val current = owner ?: return
        if (current.lease != lease) return
        val pending = move
        val capturedRequestSequence = requestSequence
        val capturedGeneration = generation
        clearOwner(current)
        val captured = startupMachine.snapshot()
        if (pending == null && !isStopped) phase = "hidden"
        current.detach()
        if (sameContinuation(
                captured, null, pending, capturedRequestSequence, capturedGeneration)) publish()
    }

    fun stop(preserveRestart: Boolean = false) {
        assertMainThread()
        val wasStopped = isStopped
        val beforeCancel = startupMachine.snapshot()
        if (!preserveRestart) {
            cancelPendingRestart()
            if (wasStopped || isStopped || !sameAttemptContext(beforeCancel)) return
        } else if (wasStopped) return
        val stopped = startupFailure("The Linux session was stopped")
        val retirement = reserveMoveRetirement()
        val transition = startupMachine.invalidate(stopped)
        sessionGeneration = transition.snapshot.epoch
        val deliverResults = queueStartupResult(transition.settlement)
        cancelAutomaticOpening()
        isStopped = true
        generation++
        stopFailed = false
        val previous = owner
        previous?.let(::clearOwner)
        lastTarget = null
        sessionTarget = null
        acceptedRequest = null
        acceptedLaunchIdentity = null
        phase = "stopped"
        error = null
        val continuation = captureContinuation()
        retirement?.let(::detachMoveRetirement)
        previous?.detach?.invoke()
        if (isStopped && isCurrent(continuation)) publish()
        finishStartupResultDelivery(deliverResults) {
            previous?.finish?.invoke()
            retirement?.let { finishMoveRetirement(it, stopped) }
        }
    }

    fun initialize(context: Context) {
        assertMainThread()
        if (host != null) return
        if (!strategyWasProvided) {
            placementStrategy.replace(
                DisplayEnvironmentPolicy.forDevice(Build.DEVICE).legacyPlacementStrategy(),
            )
        }
        settingsStore = DisplaySettingsStore(context)
        val discovery = DisplayRepository(context)
        repository = discovery
        host = AndroidDesktopDisplayHost(context.applicationContext, discovery, placementStrategy)
        val app = context.applicationContext
        stopForEnvironmentChange = { completion ->
            DroidDeskService.stopDesktop(app, DesktopStopReason.ENVIRONMENT_CHANGE, completion)
        }
        restartStartService = {
            app.startForegroundService(Intent(app, DroidDeskService::class.java))
        }
        discovery.start { onDisplaysChanged() }
        refreshAutomaticOpening()
    }

    fun bindActivity(activity: Activity): Boolean {
        assertMainThread()
        if (host?.bindActivity(activity) == false) return false
        val display = host?.currentDisplay()
        val displayId = display?.id ?: host?.currentDisplayId()
        boundActivities[activity] = BoundActivityDisplay(displayId, display)
        if (!placementStrategy.allowsManualMoves) {
            applyActivityOrientation(
                activity,
                displayId ?: activity.windowManager.defaultDisplay.displayId,
            )
        }
        return true
    }

    fun unbindActivity(activity: Activity) {
        assertMainThread()
        host?.unbindActivity(activity)
        boundActivities.remove(activity)
        originalActivityOrientations.remove(activity)
    }

    private fun applyActivityOrientation(activity: Activity, displayId: Int) {
        val original = originalActivityOrientations.getOrPut(activity) { activity.requestedOrientation }
        val display = boundActivities[activity]?.display?.takeIf { it.id == displayId }
            ?: displays().firstOrNull { it.id == displayId }
            ?: return
        val requested = placementStrategy.requestedOrientation(display, original)
        if (activity.requestedOrientation != requested) {
            android.util.Log.i("DesktopDisplay", "Window orientation=$requested display=$displayId")
            activity.requestedOrientation = requested
        }
    }

    fun cancelPendingRestart() {
        assertMainThread()
        restartController.cancelPendingRestart()
    }

    fun restartForEnvironmentIfNeeded(
        target: DesktopStartupTarget,
        expectedEpoch: Long,
        completion: (Result<Unit>) -> Unit,
    ): Boolean {
        assertMainThread()
        if (!sessionRestarting && !placementStrategy.requiresSessionRestart(sessionTarget, target)) return false
        val epoch = if (stopFailed && expectedEpoch == publishedSessionGeneration) beginSession() else expectedEpoch
        restartController.requestRestart(
            RestartRequest(target, sessionMode, desktopEnv),
            epoch,
        ) { result ->
            result.exceptionOrNull()?.let {
                if ((it as? DesktopDisplayException)?.code != "SESSION_STOPPED") {
                    reportError(it as? DesktopDisplayException ?: DesktopDisplayException(
                        "SESSION_RESTART_FAILED", it.message ?: "Linux could not restart on this display",
                    ))
                }
            }
            completion(result)
        }
        return true
    }

    private fun validateRestartTarget(target: DesktopStartupTarget) {
        val actual = host?.currentDisplayId()
        if (actual != target.displayId || startupTarget(actual) != target) {
            throw DesktopDisplayException(
                "DISPLAY_UNAVAILABLE",
                "The app moved before Linux could restart",
            )
        }
    }

    fun replacePlacementStrategy(strategy: DesktopPlacementStrategy) {
        assertMainThread()
        if (!isFullyStoppedForStrategyChange()) {
            throw DesktopDisplayException(
                "INVALID_ARGUMENT",
                "Desktop placement can change only while Linux is fully stopped",
            )
        }
        placementStrategy.replace(strategy)
        strategyWasProvided = true
        cancelAutomaticOpening()
        automaticAttempts.clear()
        automaticSuppressed.clear()
        automaticEpoch = publishedSessionGeneration
        reapplyActivityOrientations()
        publish()
    }

    private fun isFullyStoppedForStrategyChange(): Boolean {
        val hostRunning = host?.sessionRunning() == true
        return !hostRunning &&
            !isStarting &&
            !stopFailed &&
            pendingStops == 0 &&
            owner == null &&
            move == null &&
            !sessionRestarting &&
            !restartController.hasPendingRestart
    }

    private fun reapplyActivityOrientations() =
        host?.let { platform ->
            boundActivities.entries.toList().forEach { (activity, boundDisplay) ->
                val displayId = boundDisplay.displayId
                    ?: activity.window.decorView.display?.displayId
                    ?: platform.currentDisplayId()
                    ?: activity.windowManager.defaultDisplay.displayId
                applyActivityOrientation(activity, displayId)
            }
        }

    fun observeActivityEnvironment(activity: Activity, expectedEpoch: Long) {
        assertMainThread()
        val displayId = activity.window.decorView.display?.displayId ?: return
        val display = displays().firstOrNull { it.id == displayId }
            ?: boundActivities[activity]?.display?.takeIf { it.id == displayId }
        boundActivities[activity] = BoundActivityDisplay(displayId, display)
        if (!isDisplayLocal) return
        applyActivityOrientation(activity, displayId)
        observePassiveActivityEnvironment(displayId, expectedEpoch)
    }

    internal fun observePassiveActivityEnvironment(displayId: Int, expectedEpoch: Long) {
        assertMainThread()
        if (!isDisplayLocal || !isSessionCurrent(expectedEpoch)) return
        // OEM disconnect/layout callbacks are passive; only explicit open paths may restart Linux.
        if (sessionTarget?.displayId != displayId) publish()
    }

    fun subscribe(listener: (Map<String, Any?>) -> Unit): () -> Unit {
        assertMainThread()
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun subscribeSessionStart(listener: (Result<Unit>) -> Unit): () -> Unit {
        assertMainThread()
        sessionStartListeners.add(listener)
        return { sessionStartListeners.remove(listener) }
    }

    fun displays(): List<DesktopDisplay> {
        val platform = checkNotNull(host) { "Display discovery is not initialized" }
        val discovered = platform.displays()
        val current = platform.currentDisplay()
        return if (current != null && discovered.none { it.id == current.id }) {
            discovered + current
        } else {
            discovered
        }
    }

    fun snapshot(): Map<String, Any?> {
        assertMainThread()
        val displays = displays()
        val active = owner?.actualDisplay?.invoke()?.let { id -> displays.firstOrNull { it.id == id } }
        val running = (!isStopped || stopFailed) && checkNotNull(host).sessionRunning()
        val headless = isDisplayLocal && running && sessionTarget != null &&
            displays.none { it.id == sessionTarget?.displayId && it.key == sessionTarget?.displayKey && it.eligible }
        val exposedActive = active.takeUnless { headless }
        return mapOf(
            "schemaVersion" to 1,
            "autoOpenOnMonitor" to automaticOpeningEnabled(),
            "phase" to when {
                sessionRestarting -> "restarting"
                headless -> "hidden"
                isDisplayLocal && isStarting && error == null -> "starting"
                else -> phase
            },
            "displayLocalMode" to isDisplayLocal,
            "crossDisplayMovesAllowed" to placementStrategy.allowsManualMoves,
            "sessionRunning" to running,
            "sessionRestarting" to sessionRestarting,
            "sessionEnvironment" to sessionTarget?.environment?.wireName,
            "sessionDisplayId" to sessionTarget?.displayId,
            "sessionHeadless" to headless,
            "activeDisplayId" to exposedActive?.id, "activeDisplayKey" to exposedActive?.key,
            "profileKind" to exposedActive?.let { kindFor(it).wireName },
            "renderWidth" to renderSize?.width, "renderHeight" to renderSize?.height,
            "displays" to displays.map(DesktopDisplay::toMap),
            "error" to error?.let { mapOf("code" to it.code, "message" to it.message) },
        )
    }

    fun settingsFor(displayKey: String): Map<String, Any?> {
        assertMainThread()
        val display = displays().firstOrNull { it.key == displayKey }
            ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected connection is no longer available")
        val settings = checkNotNull(settingsStore).load(kindFor(display), display.settingsKey)
            ?: DisplayProfilePolicy.defaults(kindFor(display))
        return mapOf(
            "displayKey" to display.key, "renderPolicy" to settings.renderPolicy.wireName,
            "uiScalePercent" to settings.uiScalePercent, "inputMode" to settings.inputMode,
            "inputRoute" to settings.inputRoute.wireName,
        )
    }

    fun loadSettings(kind: ProfileKind, displayId: Int? = null): DisplaySettings? {
        val displaySettingsKey = displayId?.let { id ->
            displays().firstOrNull { it.id == id }?.settingsKey
        }
        return checkNotNull(settingsStore).load(kind, displaySettingsKey)
    }

    fun setAutoOpenOnMonitor(enabled: Boolean) {
        assertMainThread()
        if (!placementStrategy.allowsManualMoves) {
            val failure = DesktopDisplayException("DISPLAY_NOT_ALLOWED", "The phone or TNT launcher selects the display environment")
            reportError(failure)
            throw failure
        }
        checkNotNull(settingsStore).saveAutoOpenOnMonitor(enabled)
        cancelAutomaticOpening()
        if (enabled) {
            automaticAttempts.clear()
            automaticSuppressed.clear()
        }
        publish()
        refreshAutomaticOpening()
    }

    fun setProfileRefresher(lease: RendererLease, refresh: ((Result<Unit>) -> Unit) -> Unit) {
        assertMainThread()
        if (isCurrent(lease)) profileRefresher = refresh
    }

    fun saveProfileSettings(
        kind: ProfileKind,
        settings: DisplaySettings,
        completion: (Result<Unit>) -> Unit = {},
    ) {
        assertMainThread()
        checkNotNull(settingsStore).save(kind, settings)
        applySavedProfile(kind, completion)
    }

    private fun applySavedProfile(
        kind: ProfileKind,
        completion: (Result<Unit>) -> Unit,
    ) {
        if (appliedKind == kind && owner != null) {
            val refresh = profileRefresher
            if (refresh == null) completion(Result.failure(DesktopDisplayException(
                "PROFILE_APPLY_FAILED", "Display settings were saved but the active view cannot apply them",
            ))) else {
                val completed = AtomicBoolean(false)
                val lease = owner?.lease
                val cancelTimeout = checkNotNull(host).scheduleTimeout(10_000L) {
                    if (completed.compareAndSet(false, true)) {
                        val failure = DesktopDisplayException(
                            "PROFILE_APPLY_FAILED", "Display settings were saved but application was not confirmed; retry the profile",
                        )
                        if (lease != null && isCurrent(lease)) reportError(failure)
                        completion(Result.failure(failure))
                    }
                }
                refresh { result ->
                    if (completed.compareAndSet(false, true)) {
                        cancelTimeout()
                        completion(result)
                    }
                }
            }
        } else {
            completion(Result.success(Unit))
        }
        publish()
    }

    fun saveSettingsFor(displayKey: String, settings: DisplaySettings, completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        val display = displays().firstOrNull { it.key == displayKey }
            ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display connection is unavailable")
        val kind = kindFor(display)
        checkNotNull(settingsStore).save(kind, settings, display.settingsKey)
        applySavedProfile(kind, completion)
    }

    fun profileApplied(lease: RendererLease, outcome: Result<Unit>) {
        assertMainThread()
        if (!isCurrent(lease)) return
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            reportError(DesktopDisplayException(
                "PROFILE_APPLY_FAILED", failure.message ?: "XFCE settings could not be applied",
            ))
        } else if (error?.code == "PROFILE_APPLY_FAILED") {
            error = null
            phase = if (move != null) "moving" else "attached"
            publish()
        }
    }

    fun reportGeometry(lease: RendererLease, kind: ProfileKind, size: PixelSize) {
        assertMainThread()
        if (!isCurrent(lease)) return
        if (appliedKind == kind && renderSize == size) return
        appliedKind = kind
        renderSize = size
        publish()
    }

    private fun kindFor(display: DesktopDisplay): ProfileKind {
        val currentKind = appliedKind
        if (owner?.lease?.displayId == display.id && currentKind != null) return currentKind
        return if (display.isDefault) checkNotNull(host).defaultProfileKind() else ProfileKind.MONITOR
    }

    fun open(displayId: Int, displayKey: String, completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        if (!placementStrategy.allowsManualMoves) {
            rejectCrossDisplayMove(completion)
            return
        }
        val target = displays().firstOrNull { it.id == displayId && it.key == displayKey }
        if (target == null) {
            val failure = DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display connection changed")
            reportError(failure)
            completion(Result.failure(failure))
            return
        }
        cancelAutomaticOpening()
        if (target.isDefault) suppressAutomaticOpening()
        openTarget(target, completion, recovery = false)
    }

    fun openActive(completion: (Result<Unit>) -> Unit) = openActive(null, completion)

    fun startupTarget(displayId: Int): DesktopStartupTarget {
        assertMainThread()
        val display = displays().firstOrNull { it.id == displayId && canLaunch(it) }
            ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app's current display is not available")
        return DesktopStartupTarget(display.id, display.key, placementStrategy.environmentFor(display))
    }

    fun openActive(preferredTarget: DesktopStartupTarget?, completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        val current = displays()
        if (!placementStrategy.allowsManualMoves) {
            val target = preferredTarget?.let { preferred ->
                current.firstOrNull {
                    it.id == preferred.displayId && it.key == preferred.displayKey && it.eligible
                }
            }
            if (target == null) {
                val failure = DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app's current display changed or is unavailable")
                reportError(failure)
                completion(Result.failure(failure))
            } else {
                openTarget(target, completion, recovery = false)
            }
            return
        }
        val actual = owner?.actualDisplay?.invoke() ?: host?.currentDisplayId()
        val target = current.firstOrNull { it.id == actual }
            ?: current.firstOrNull { it.key == lastTarget?.key }
            ?: current.firstOrNull { it.isDefault }
        if (target == null) {
            completion(Result.failure(DesktopDisplayException("DISPLAY_UNAVAILABLE", "No phone display is available")))
        } else {
            openTarget(target, completion, recovery = false)
        }
    }

    fun moveToPhone(completion: (Result<Unit>) -> Unit) {
        assertMainThread()
        if (!placementStrategy.allowsManualMoves) {
            rejectCrossDisplayMove(completion)
            return
        }
        suppressAutomaticOpening()
        val phone = displays().firstOrNull { it.isDefault }
        if (phone == null) {
            completion(Result.failure(DesktopDisplayException("DISPLAY_UNAVAILABLE", "The phone display is unavailable")))
        } else {
            openTarget(phone, completion, recovery = false)
        }
    }

    private fun rejectCrossDisplayMove(completion: (Result<Unit>) -> Unit) {
        val failure = DesktopDisplayException(
            "DISPLAY_NOT_ALLOWED", "Open DroidDesk from the phone or TNT launcher to select its display",
        )
        reportError(failure)
        completion(Result.failure(failure))
    }

    private fun openTarget(target: DesktopDisplay, completion: (Result<Unit>) -> Unit, recovery: Boolean) {
        val platform = checkNotNull(host)
        val failure = when {
            isStopped || (!isStarting && !platform.sessionRunning()) ->
                DesktopDisplayException("SESSION_STOPPED", "Start the Linux desktop before moving its view")
            move != null -> DesktopDisplayException("INVALID_ARGUMENT", "A desktop move is already in progress")
            !canLaunch(target) -> DesktopDisplayException("DISPLAY_NOT_ALLOWED", target.reason ?: "Android denied this display")
            else -> null
        }
        if (failure != null) {
            if (move == null) reportError(failure)
            completion(Result.failure(failure))
            return
        }
        val selectedTarget =
            DesktopStartupTarget(target.id, target.key, placementStrategy.environmentFor(target))
        val snapshot = startupMachine.snapshot()
        val launchIdentity = startupMachine.selectTarget(selectedTarget)
            ?: StartupIdentity(snapshot.epoch, snapshot.spec, selectedTarget)
        val selectedSnapshot = startupMachine.snapshot()
        val currentOwner = owner
        val currentMove = move
        val currentRequestSequence = requestSequence
        val currentGeneration = generation
        val requestId = currentOwner?.requestId
        if (currentOwner?.lease?.displayId == target.id) {
            val actualDisplay = currentOwner.actualDisplay()
            if (isStopped ||
                !sameContinuation(
                    selectedSnapshot, currentOwner, currentMove,
                    currentRequestSequence, currentGeneration)) {
                completion(startupFailure("The desktop open was replaced"))
                return
            }
            if (actualDisplay == target.id && requestId != null &&
                currentOwner.launchIdentity == launchIdentity) {
                val focused = platform.focus(requestId)
                if (isStopped ||
                    !sameContinuation(
                        selectedSnapshot, currentOwner, currentMove,
                        currentRequestSequence, currentGeneration)) {
                    completion(startupFailure("The desktop open was replaced"))
                    return
                }
                if (focused) {
                    currentOwner.launchIdentity = launchIdentity
                    acceptedLaunchIdentity = launchIdentity
                    rearmRendererClaim(currentOwner, launchIdentity)
                    completion(Result.success(Unit))
                    return
                }
            }
        }
        val request = DesktopLaunchRequest(target, ++requestSequence, launchIdentity.epoch,
            launchIdentity.spec.mode, launchIdentity.spec.desktopEnv)
        if (!placementStrategy.allowsManualMoves && sessionTarget == null) {
            sessionTarget = selectedTarget
        }
        val pending = Move(request, completion, currentOwner, lastTarget, recovery)
        move = pending
        phase = "moving"
        if (!recovery) error = null
        pending.cancelTimeout = platform.scheduleTimeout(10_000L) {
            if (move?.request?.requestId == request.requestId) {
                failMove(DesktopDisplayException("PLACEMENT_TIMEOUT", "Android did not confirm desktop placement"), !recovery)
            }
        }
        publish()
        if (move !== pending || !isSessionCurrent(request.sessionGeneration)) return
        try {
            platform.launch(request)
        } catch (failure: DesktopDisplayException) {
            if (move === pending && isSessionCurrent(request.sessionGeneration)) {
                failMove(failure, !recovery)
            }
        }
    }

    fun canAttach(requestId: Long, displayId: Int, displayKey: String, epoch: Long): Boolean {
        assertMainThread()
        if (!isSessionCurrent(epoch)) return false
        val target = displays().firstOrNull { it.id == displayId && it.key == displayKey } ?: return false
        if (!canLaunch(target)) return false
        val pending = move
        return if (pending != null) {
            pending.request.requestId == requestId && pending.request.display.key == target.key
        } else {
            acceptedRequest == requestId && lastTarget?.key == target.key
        }
    }

    fun rejectAttachment(requestId: Long) {
        assertMainThread()
        if (move?.request?.requestId == requestId) {
            failMove(DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Android opened the activity on a different display"), true)
        }
    }

    fun onAttached(lease: RendererLease, requestId: Long, measured: PixelSize) {
        assertMainThread()
        if (!isCurrent(lease) || measured.width <= 0 || measured.height <= 0) return
        val pending = move
        if (pending == null) {
            if (requestId == acceptedRequest) {
                owner?.takeIf { it.lease == lease }?.launchIdentity = acceptedLaunchIdentity
                phase = "attached"
                publish()
                refreshAutomaticOpening()
            }
            return
        }
        val target = pending.request.display
        val currentOwner = owner?.takeIf { it.lease == lease } ?: return
        val captured = captureContinuation()
        val actualDisplay = currentOwner.actualDisplay()
        val available = displays().any { it.id == target.id && it.key == target.key && canLaunch(it) }
        if (pending.request.requestId != requestId || lease.displayId != target.id ||
            actualDisplay != target.id || !available || isStopped || !isCurrent(captured)) return
        move = null
        lastTarget = target
        sessionTarget = DesktopStartupTarget(
            target.id,
            target.key,
            placementStrategy.environmentFor(target),
        )
        acceptedRequest = requestId
        acceptedLaunchIdentity = startupIdentity(pending.request)
        currentOwner.launchIdentity = acceptedLaunchIdentity
        phase = "attached"
        if (!pending.recovery) error = null
        val previous = pending.previous?.takeIf { it.lease != lease }
        val continuation = captureContinuation()
        pending.cancelTimeout()
        if (isCurrent(continuation)) publish()
        previous?.finish?.invoke()
        pending.completion(Result.success(Unit))
        if (!isStopped && isCurrent(continuation)) refreshAutomaticOpening()
    }

    fun onDisplayRemoved(displayId: Int) {
        assertMainThread()
        if (move?.request?.display?.id == displayId) {
            val message = if (placementStrategy.allowsManualMoves) {
                "The selected monitor disconnected"
            } else {
                "The startup display disconnected"
            }
            failMove(
                DesktopDisplayException("DISPLAY_UNAVAILABLE", message),
                placementStrategy.allowsManualMoves,
            )
        } else if (!isStopped && move == null && lastTarget?.id == displayId) {
            val failed = lastTarget ?: return
            val fallback = placementStrategy.fallbackTarget(displays(), null, failed)
            if (fallback != null) {
                openTarget(fallback, {}, recovery = true)
            } else {
                publish()
            }
        } else if (!placementStrategy.allowsManualMoves) {
            publish()
        }
    }

    fun onDisplaysChanged() {
        assertMainThread()
        val current = displays()
        val pending = move
        val pendingDisplay = pending?.request?.display?.let { target ->
            current.firstOrNull { it.id == target.id && it.key == target.key }
        }
        if (pending != null && pendingDisplay == null) {
            onDisplayRemoved(pending.request.display.id)
        } else if (pendingDisplay != null && !canLaunch(pendingDisplay)) {
            failMove(DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Display placement permission changed"), true)
        } else if (pending == null) {
            lastTarget?.takeIf { target -> current.none { it.key == target.key } }?.let { onDisplayRemoved(it.id) }
        }
        profileRefresher?.invoke {}
        publish()
        refreshAutomaticOpening()
    }

    private fun canLaunch(display: DesktopDisplay): Boolean =
        display.eligible || host?.currentDisplayId() == display.id

    private fun automaticOpeningEnabled(): Boolean =
        placementStrategy.allowsAutomaticMoves && (settingsStore?.loadAutoOpenOnMonitor() ?: true)

    private fun cancelAutomaticOpening() {
        automaticSequence++
        cancelAutomaticTimer?.invoke()
        cancelAutomaticTimer = null
        automaticTargetKey = null
    }

    private fun suppressAutomaticOpening() {
        cancelAutomaticOpening()
        automaticSuppressed += displays().filterNot { it.isDefault }.map { it.key }
    }

    private fun automaticTarget(current: List<DesktopDisplay>): DesktopDisplay? {
        val platform = host ?: return null
        if (isStopped || isStarting || move != null || !automaticOpeningEnabled() || !platform.sessionRunning()) {
            return null
        }
        val actual = owner?.actualDisplay?.invoke()
        if (current.any { it.id == actual && !it.isDefault }) return null
        return current.filter { !it.isDefault && it.eligible }.singleOrNull()?.takeIf {
            it.key !in automaticAttempts && it.key !in automaticSuppressed
        }
    }

    private fun refreshAutomaticOpening() {
        val platform = host ?: return
        val current = platform.displays()
        val connections = current.filterNot { it.isDefault }.map { it.key }.toSet()
        automaticAttempts.retainAll(connections)
        automaticSuppressed.retainAll(connections)
        if (automaticEpoch != publishedSessionGeneration) {
            automaticEpoch = publishedSessionGeneration
            automaticAttempts.clear()
        }
        val target = automaticTarget(current)
        if (target == null) {
            cancelAutomaticOpening()
            return
        }
        if (automaticTargetKey == target.key) return
        cancelAutomaticOpening()
        automaticTargetKey = target.key
        val sequence = automaticSequence
        val epoch = publishedSessionGeneration
        cancelAutomaticTimer = platform.scheduleTimeout(500L) {
            if (sequence != automaticSequence || !isSessionCurrent(epoch)) return@scheduleTimeout
            cancelAutomaticTimer = null
            automaticTargetKey = null
            val latest = automaticTarget(platform.displays())
            if (latest?.key != target.key) {
                refreshAutomaticOpening()
                return@scheduleTimeout
            }
            automaticAttempts += latest.key
            openTarget(latest, {}, recovery = false)
        }
    }

    fun onActivityMoved(lease: RendererLease, displayId: Int) {
        assertMainThread()
        if (!placementStrategy.allowsManualMoves) return
        if (!isCurrent(lease) || lease.displayId == displayId || move != null) return
        val target = displays().firstOrNull { it.id == displayId && it.eligible } ?: return
        openTarget(target, {}, recovery = true)
    }

    private fun failMove(failure: DesktopDisplayException, recover: Boolean) {
        val result = Result.failure<Unit>(failure)
        val retirement = reserveMoveRetirement() ?: return
        val pending = retirement.move
        val failedIdentity = startupIdentity(pending.request)
        fun finishRetirement(discarded: Owner? = null) {
            finishStartupResultDelivery(false) {
                discarded?.finish?.invoke()
                finishMoveRetirement(retirement, result)
            }
        }

        var continuation = captureContinuation()
        detachMoveRetirement(retirement)
        if (!isCurrent(continuation)) return finishRetirement()

        val retained = owner
        var actual = retained?.actualDisplay?.invoke()
        if (!isCurrent(continuation)) return finishRetirement()
        var available = displays()
        if (!isCurrent(continuation)) return finishRetirement()
        actual = retained?.actualDisplay?.invoke()
        if (!isCurrent(continuation)) return finishRetirement()
        val retainedTarget = retained?.launchIdentity?.target
        val retainedDisplay = retained?.takeIf { actual == it.lease.displayId }?.let {
            available.firstOrNull { display ->
                display.id == actual &&
                    (retainedTarget == null ||
                        retainedTarget.displayId == display.id &&
                        retainedTarget.displayKey == display.key) &&
                    canLaunch(display)
            }
        }
        if (!isCurrent(continuation)) return finishRetirement()
        actual = retained?.actualDisplay?.invoke()
        if (!isCurrent(continuation)) return finishRetirement()
        available = displays()
        if (!isCurrent(continuation)) return finishRetirement()
        val stableRetainedDisplay = retainedDisplay?.let { display ->
            available.firstOrNull {
                it.id == display.id && it.key == display.key && canLaunch(it)
            }
        }
        if (!isCurrent(continuation)) return finishRetirement()
        actual = retained?.actualDisplay?.invoke()
        if (!isCurrent(continuation)) return finishRetirement()
        val selected = continuation.startup.selectedIdentity
        val compatible = stableRetainedDisplay != null &&
            actual == retained?.lease?.displayId &&
            (selected == null || retained?.launchIdentity?.let {
                sameStartupAttempt(it, selected)
            } == true)
        var discarded = retained?.takeIf { !compatible }
        if (discarded != null) {
            clearOwner(discarded)
            continuation = captureContinuation()
            discarded.detach()
            if (!isCurrent(continuation)) return finishRetirement(discarded)
            available = displays()
            if (!isCurrent(continuation)) return finishRetirement(discarded)
        }

        val settledSuccess = continuation.startup.terminalResult?.isSuccess == true
        val matchesSelected = compatible &&
            (settledSuccess || selected == null || retained?.launchIdentity == selected)
        var fallback = if (matchesSelected) null else placementStrategy.fallbackTarget(
            available,
            pending.previousTarget,
            pending.request.display,
        )
        if (!isCurrent(continuation)) return finishRetirement(discarded)
        var recoverToFallback = recover && !pending.recovery &&
            fallback != null && fallback.key != pending.request.display.key
        if (retained != null && discarded == null && !matchesSelected && !recoverToFallback) {
            discarded = retained
            clearOwner(retained)
            continuation = captureContinuation()
            retained.detach()
            if (!isCurrent(continuation)) return finishRetirement(retained)
            available = displays()
            if (!isCurrent(continuation)) return finishRetirement(retained)
            fallback = placementStrategy.fallbackTarget(
                available,
                pending.previousTarget,
                pending.request.display,
            )
            if (!isCurrent(continuation)) return finishRetirement(retained)
            recoverToFallback = fallback?.let {
                recover && !pending.recovery && it.key != pending.request.display.key
            } == true
        }

        val terminal = !matchesSelected && !recoverToFallback
        val settlement = if (terminal &&
            continuation.startup.selectedIdentity == failedIdentity) {
            startupMachine.settle(
                pending.request.sessionGeneration,
                result,
                failure,
            )
        } else {
            null
        }
        val deliverResults = queueStartupResult(settlement)
        error = failure
        phase = "error"
        continuation = captureContinuation()
        if (isCurrent(continuation)) publish()
        finishStartupResultDelivery(deliverResults) {
            val publishedFailure = error === failure
            if (publishedFailure) {
                error = null
                if (phase == "error") phase = if (owner == null) "hidden" else "attached"
            }
            val currentBeforeFinish = isCurrent(continuation)
            discarded?.finish?.invoke()
            finishMoveRetirement(retirement, result)
            val ownerOnlyRelease = currentBeforeFinish && continuation.owner != null &&
                !isStopped && owner == null && move === continuation.move &&
                requestSequence == continuation.requestSequence &&
                generation == continuation.generation &&
                sameAttemptContext(continuation.startup)
            if (isStopped || (!isCurrent(continuation) && !ownerOnlyRelease)) {
                return@finishStartupResultDelivery
            }
            if (matchesSelected && !ownerOnlyRelease) {
                if (publishedFailure && error == null) { error = failure; phase = "error" }
                return@finishStartupResultDelivery
            }
            available = displays()
            val expectedOwner = if (ownerOnlyRelease) null else continuation.owner
            if (isStopped || owner !== expectedOwner || move !== continuation.move ||
                requestSequence != continuation.requestSequence ||
                generation != continuation.generation ||
                !sameAttemptContext(continuation.startup)) return@finishStartupResultDelivery
            fallback = placementStrategy.fallbackTarget(
                available,
                pending.previousTarget,
                pending.request.display,
            )
            recoverToFallback = fallback?.let {
                recover && !pending.recovery && it.key != pending.request.display.key
            } == true
            if (publishedFailure && error == null) { error = failure; phase = "error" }
            if (recoverToFallback) {
                openTarget(checkNotNull(fallback), {}, recovery = true)
            } else if (ownerOnlyRelease) {
                val lateSettlement = startupMachine.settle(
                    pending.request.sessionGeneration, result, failure,
                )
                finishStartupResultDelivery(queueStartupResult(lateSettlement)) {
                    checkNotNull(host).notifyReturnRequired(failure)
                }
            } else {
                checkNotNull(host).notifyReturnRequired(failure)
            }
        }
    }

    fun reportError(failure: DesktopDisplayException) {
        assertMainThread()
        error = failure
        if (!isStopped) phase = "error"
        publish()
    }

    private fun releaseSessionStartClaim(lease: RendererLease?) {
        if (lease != null) startupMachine.releaseClaim(lease.generation)
    }

    private fun startupFailure(message: String) =
        Result.failure<Unit>(DesktopDisplayException("SESSION_STOPPED", message))

    private fun sameAttemptContext(expected: StartupSnapshot): Boolean {
        val current = startupMachine.snapshot()
        return current.epoch == expected.epoch &&
            current.spec == expected.spec &&
            current.reservedEpoch == expected.reservedEpoch &&
            current.baseIdentity == expected.baseIdentity &&
            current.selectedIdentity == expected.selectedIdentity &&
            current.terminalResult == expected.terminalResult &&
            current.terminalFailure === expected.terminalFailure
    }

    private fun sameContinuation(
        expected: StartupSnapshot,
        expectedOwner: Owner?,
        expectedMove: Move?,
        expectedRequestSequence: Long,
        expectedGeneration: Long,
    ) = owner === expectedOwner &&
        move === expectedMove &&
        requestSequence == expectedRequestSequence &&
        generation == expectedGeneration &&
        sameAttemptContext(expected)

    private fun captureContinuation() = Continuation(
        startupMachine.snapshot(), owner, move, requestSequence, generation)

    private fun isCurrent(value: Continuation) = sameContinuation(
        value.startup, value.owner, value.move, value.requestSequence, value.generation,
    )

    private fun startupIdentity(request: DesktopLaunchRequest) = StartupIdentity(
        request.sessionGeneration, StartupSpec(request.mode, request.desktopEnv),
        DesktopStartupTarget(request.display.id, request.display.key,
            placementStrategy.environmentFor(request.display)),
    )

    private fun sameStartupAttempt(
        expected: StartupIdentity,
        actual: StartupIdentity?,
    ) = actual != null && expected.epoch == actual.epoch && expected.spec == actual.spec

    private fun rendererStartup(value: Owner): RendererStartup? =
        value.launchIdentity?.let { RendererStartup(value.lease.generation, it) }

    private fun rearmRendererClaim(value: Owner, identity: StartupIdentity) {
        val snapshot = startupMachine.snapshot()
        if (snapshot.claimState != "reserved" ||
            snapshot.claimGeneration != value.lease.generation) return
        check(startupMachine.releaseClaim(value.lease.generation))
        check(startupMachine.reserveClaim(RendererStartup(value.lease.generation, identity)))
    }

    private fun clearOwner(value: Owner) {
        if (owner !== value) return
        owner = null
        profileRefresher = null
        releaseSessionStartClaim(value.lease)
        appliedKind = null
        renderSize = null
    }

    private fun reserveMoveRetirement(): MoveRetirement? {
        val pending = move ?: return null
        move = null
        val identity = startupIdentity(pending.request)
        val incoming = owner?.takeIf { it.lease != pending.previous?.lease &&
            (it.requestId == pending.request.requestId || it.launchIdentity == identity) }
        incoming?.let(::clearOwner)
        val previous = pending.previous?.takeIf {
            it.lease != incoming?.lease && it.lease != owner?.lease }
        phase = if (owner == null) "hidden" else "attached"
        return MoveRetirement(pending, incoming, previous)
    }

    private fun detachMoveRetirement(retirement: MoveRetirement) {
        retirement.move.cancelTimeout()
        host?.cancelLaunch(retirement.move.request.requestId)
        retirement.incoming?.detach?.invoke()
    }

    private fun finishMoveRetirement(retirement: MoveRetirement, result: Result<Unit>) {
        retirement.incoming?.finish?.invoke()
        retirement.previous?.finish?.invoke()
        retirement.move.completion(result)
    }

    private fun queueStartupResult(
        settlement: StartupSettlement<(Result<Unit>) -> Unit>?,
        afterDelivery: () -> Unit = {},
    ): Boolean {
        settlement ?: return false
        val observers = if (settlement.notifyObservers) sessionStartListeners.toList() else emptyList()
        startupResultDeliveries.addLast {
            val previousEpoch = observerSessionGeneration.get()
            observerSessionGeneration.set(settlement.identity.epoch)
            try { observers.forEach { it(settlement.result) } } finally {
                if (previousEpoch == null) observerSessionGeneration.remove() else
                    observerSessionGeneration.set(previousEpoch)
            }
            settlement.waiters.forEach { it(settlement.result) }
            afterDelivery()
        }
        if (deliveringStartupResults) return false
        deliveringStartupResults = true
        return true
    }

    private fun finishStartupResultDelivery(deliverResults: Boolean, afterDelivery: () -> Unit = {}) {
        if (!deliverResults) {
            if (deliveringStartupResults) startupResultFinalizers.addLast(afterDelivery)
            else afterDelivery()
            return
        }
        startupResultFinalizers.addLast(afterDelivery)
        try {
            while (startupResultDeliveries.isNotEmpty() || startupResultFinalizers.isNotEmpty()) {
                while (startupResultDeliveries.isNotEmpty()) startupResultDeliveries.removeFirst().invoke()
                if (startupResultFinalizers.isNotEmpty()) startupResultFinalizers.removeFirst().invoke()
            }
        } finally { deliveringStartupResults = false }
    }

    private fun publish() {
        if (host == null || listeners.isEmpty()) return
        val state = snapshot()
        listeners.toList().forEach { it(state) }
    }

    companion object {
        val shared: DesktopDisplayCoordinator by lazy {
            DesktopDisplayCoordinator(assertMainThread = {
                check(Looper.myLooper() == Looper.getMainLooper()) { "Display transitions require the main thread" }
            })
        }
    }
}
