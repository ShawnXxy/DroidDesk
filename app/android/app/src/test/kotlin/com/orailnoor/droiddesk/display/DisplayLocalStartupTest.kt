package com.orailnoor.droiddesk.display

import android.app.Activity
import android.content.pm.ActivityInfo
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopPlacementStrategy
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.MovableSecondaryDisplayAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import org.junit.Assert.*
import org.junit.Test

class DisplayLocalStartupTest {
    private val phone = DesktopDisplay(0, "phone", "Phone", true, true, null)
    private val tnt = DesktopDisplay(100003, "tnt", "TNT", false, true, null)
    private val displayLocalStrategy = displayLocalStrategy()
    private val host = LocalStartupHost(listOf(phone, tnt))
    private val coordinator = DesktopDisplayCoordinator(
        host = host,
        placementStrategy = displayLocalStrategy,
    )

    @Test
    fun movableStrategySelectionAndLegacyOpenStayTruthful() {
        val movable = movableStrategy()
        val genericHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movable,
        )

        generic.openActive {}

        assertEquals(movable.selection, generic.placementSelection)
        assertFalse(generic.isDisplayLocal)
        assertEquals(listOf(tnt.id), genericHost.launches.map { it.display.id })
    }

    @Test
    fun displayLocalStrategySelectionAndLegacyOpenStayTruthful() {
        val displayLocal = displayLocalStrategy()
        val localHost = LocalStartupHost(listOf(phone, tnt))
        val local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocal,
        )

        local.openActive(target(tnt)) {}
        attach(local, localHost)

        assertEquals(displayLocal.selection, local.placementSelection)
        assertTrue(local.isDisplayLocal)
        assertEquals(listOf(tnt.id), localHost.launches.map { it.display.id })
        assertEquals("tnt", local.snapshot()["sessionEnvironment"])
        assertEquals(false, local.snapshot()["crossDisplayMovesAllowed"])
    }

    @Test
    fun placementStrategyCanBeReplacedOnlyWhileFullyStopped() {
        val genericHost = LocalStartupHost(listOf(phone))
        val movable = movableStrategy()
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movable,
        )

        val runningFailure = assertThrows(DesktopDisplayException::class.java) {
            generic.replacePlacementStrategy(displayLocalStrategy())
        }
        assertEquals("INVALID_ARGUMENT", runningFailure.code)
        assertEquals(movable.selection, generic.placementSelection)

        generic.stop()
        genericHost.running = false
        val displayLocal = displayLocalStrategy()
        generic.replacePlacementStrategy(displayLocal)

        assertEquals(displayLocal.selection, generic.placementSelection)
        assertTrue(generic.isDisplayLocal)
    }

    @Test
    fun freshCoordinatorWithoutARunningSessionAllowsStrategyReplacement() {
        val stoppedHost = LocalStartupHost(listOf(phone)).apply {
            running = false
        }
        val fresh = DesktopDisplayCoordinator(
            host = stoppedHost,
            placementStrategy = movableStrategy(),
        )
        val displayLocal = displayLocalStrategy()

        fresh.replacePlacementStrategy(displayLocal)

        assertEquals(displayLocal.selection, fresh.placementSelection)
    }

    @Test
    fun pendingSessionStartDoesNotPermitStrategyReplacement() {
        val stoppedHost = LocalStartupHost(listOf(phone)).apply {
            running = false
        }
        val movable = movableStrategy()
        val starting = DesktopDisplayCoordinator(
            host = stoppedHost,
            placementStrategy = movable,
        )
        starting.prepareSession("termux", "xfce4", start = true)

        val failure = assertThrows(DesktopDisplayException::class.java) {
            starting.replacePlacementStrategy(displayLocalStrategy())
        }

        assertEquals("INVALID_ARGUMENT", failure.code)
        assertEquals(movable.selection, starting.placementSelection)
    }

    @Test
    fun startupTargetUsesTheSelectedPlacementEnvironment() {
        val local = DesktopDisplayCoordinator(
            host = LocalStartupHost(listOf(phone, tnt)),
            placementStrategy = displayLocalStrategy,
        )

        assertEquals(DisplayEnvironment.TNT, local.startupTarget(tnt.id).environment)
    }

    @Test
    fun guardedMovableSelectionAllowsManualButNotAutomaticMoves() {
        val guarded = movableStrategy(allowAutomaticMoves = false)
        val generic = DesktopDisplayCoordinator(
            host = LocalStartupHost(listOf(phone, tnt)),
            placementStrategy = guarded,
        )

        val state = generic.snapshot()
        assertEquals(guarded.selection, generic.placementSelection)
        assertEquals(true, state["crossDisplayMovesAllowed"])
        assertEquals(false, state["autoOpenOnMonitor"])
    }

    @Test
    fun movingStrategyReplacementFailsWithoutMutatingThePendingPlacement() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val movable = movableStrategy()
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movable,
        )

        generic.open(tnt.id, tnt.key) {}
        val launch = genericHost.launches.single()
        genericHost.running = false

        val failure = assertThrows(DesktopDisplayException::class.java) {
            generic.replacePlacementStrategy(displayLocalStrategy())
        }

        assertEquals("INVALID_ARGUMENT", failure.code)
        assertEquals(movable.selection, generic.placementSelection)
        assertTrue(generic.canAttach(
            launch.requestId,
            launch.display.id,
            launch.display.key,
            launch.sessionGeneration,
        ))
    }

    @Test
    fun activeRendererStrategyReplacementFailsWithoutReleasingOwnership() {
        val genericHost = LocalStartupHost(listOf(phone))
        val movable = movableStrategy()
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movable,
        )
        val lease = generic.claim(phone.id) {}
        genericHost.running = false

        val failure = assertThrows(DesktopDisplayException::class.java) {
            generic.replacePlacementStrategy(displayLocalStrategy())
        }

        assertEquals("INVALID_ARGUMENT", failure.code)
        assertTrue(generic.isCurrent(lease))
        assertEquals(movable.selection, generic.placementSelection)
    }

    @Test
    fun failedStopDoesNotPermitStrategyReplacement() {
        val localHost = LocalStartupHost(listOf(phone))
        val displayLocal = displayLocalStrategy()
        val local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocal,
        )

        local.startStopping()
        local.finishStopping(IllegalStateException("runtime refused to stop"))
        localHost.running = false

        val failure = assertThrows(DesktopDisplayException::class.java) {
            local.replacePlacementStrategy(movableStrategy())
        }

        assertEquals("INVALID_ARGUMENT", failure.code)
        assertEquals(displayLocal.selection, local.placementSelection)
    }

    @Test
    fun teardownInProgressDoesNotPermitStrategyReplacement() {
        val localHost = LocalStartupHost(listOf(phone))
        val displayLocal = displayLocalStrategy()
        val local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocal,
        )

        local.startStopping()
        localHost.running = false

        val failure = assertThrows(DesktopDisplayException::class.java) {
            local.replacePlacementStrategy(movableStrategy())
        }

        assertEquals("INVALID_ARGUMENT", failure.code)
        assertEquals(displayLocal.selection, local.placementSelection)
    }

    @Test
    fun successfulStrategyReplacementReappliesBoundActivityOrientation() {
        val genericHost = LocalStartupHost(listOf(phone)).apply {
            currentDisplay = phone.id
            running = false
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val replacement = RecordingPlacementStrategy(displayLocalStrategy())
        assertTrue(generic.bindActivity(OrientationActivity()))

        generic.replacePlacementStrategy(replacement)

        assertEquals(1, replacement.orientationRequests)
    }

    @Test
    fun rejectedStrategyReplacementDoesNotReapplyBoundActivityOrientation() {
        val genericHost = LocalStartupHost(listOf(phone)).apply {
            currentDisplay = phone.id
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val replacement = RecordingPlacementStrategy(displayLocalStrategy())
        assertTrue(generic.bindActivity(OrientationActivity()))

        assertThrows(DesktopDisplayException::class.java) {
            generic.replacePlacementStrategy(replacement)
        }

        assertEquals(0, replacement.orientationRequests)
    }

    @Test
    fun hostlessStrategyReplacementDefersOrientationWithoutPartialFailure() {
        val replacement = RecordingPlacementStrategy(displayLocalStrategy())
        val hostless = DesktopDisplayCoordinator(
            placementStrategy = movableStrategy(),
        )
        assertTrue(hostless.bindActivity(OrientationActivity()))

        hostless.replacePlacementStrategy(replacement)

        assertEquals(replacement.selection, hostless.placementSelection)
        assertEquals(0, replacement.orientationRequests)
    }

    @Test
    fun strategyReplacementReappliesEachBoundActivitysOwnDisplayOrientation() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val phoneActivity = OrientationActivity(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        val tntActivity = OrientationActivity(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        genericHost.currentDisplay = phone.id
        assertTrue(generic.bindActivity(phoneActivity))
        genericHost.currentDisplay = tnt.id
        assertTrue(generic.bindActivity(tntActivity))
        genericHost.running = false
        val replacement = RecordingPlacementStrategy(displayLocalStrategy())

        generic.replacePlacementStrategy(replacement)

        assertEquals(setOf(phone.id, tnt.id), replacement.orientationDisplays.toSet())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, phoneActivity.requestedOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, tntActivity.requestedOrientation)
    }

    @Test
    fun tntStartupUsesTheLaunchingDisplayRatherThanThePhoneFallback() {
        coordinator.openActive(target(tnt)) {}
        assertEquals(listOf(100003), host.launches.map { it.display.id })
    }

    @Test
    fun r2PlacementTimeoutRemainsOnTntWithoutAttemptingPhoneRecovery() {
        var failure: Throwable? = null
        coordinator.openActive(target(tnt)) { failure = it.exceptionOrNull() }
        host.fireTimers()
        assertEquals("PLACEMENT_TIMEOUT", (failure as DesktopDisplayException).code)
        assertEquals(listOf(100003), host.launches.map { it.display.id })
        assertEquals("error", coordinator.snapshot()["phase"])
    }

    @Test
    fun r2LaunchDenialDoesNotAttemptPhoneRecovery() {
        val denied = DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Android denied this launch")
        host.launchFailure = denied
        var failure: Throwable? = null
        coordinator.openActive(target(tnt)) { failure = it.exceptionOrNull() }
        assertSame(denied, failure)
        assertEquals(listOf(100003), host.launches.map { it.display.id })
        assertEquals("error", coordinator.snapshot()["phase"])
    }

    @Test
    fun r2WrongDisplayAttachmentDoesNotAttemptPhoneRecovery() {
        var failure: Throwable? = null
        coordinator.openActive(target(tnt)) { failure = it.exceptionOrNull() }
        coordinator.rejectAttachment(host.launches.single().requestId)
        assertEquals("DISPLAY_NOT_ALLOWED", (failure as DesktopDisplayException).code)
        assertEquals(listOf(100003), host.launches.map { it.display.id })
        assertEquals("error", coordinator.snapshot()["phase"])
    }

    @Test
    fun displayLocalPendingDisconnectDoesNotAttemptPhoneRecovery() {
        coordinator.openActive(target(tnt)) {}
        host.connected = listOf(phone)

        coordinator.onDisplayRemoved(tnt.id)

        assertEquals(listOf(tnt.id), host.launches.map { it.display.id })
    }

    @Test
    fun movablePendingDisconnectRecoversToPhone() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.open(tnt.id, tnt.key) {}
        genericHost.connected = listOf(phone)

        generic.onDisplayRemoved(tnt.id)

        assertEquals(listOf(tnt.id, phone.id), genericHost.launches.map { it.display.id })
    }

    @Test
    fun genericPlacementTimeoutStillRecoversToPhone() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(host = genericHost)
        generic.open(tnt.id, tnt.key) {}
        genericHost.fireTimers()
        assertEquals(listOf(100003, 0), genericHost.launches.map { it.display.id })
    }

    @Test
    fun aRecycledCurrentDisplayKeyCannotFallBackToPhone() {
        host.connected = listOf(phone, tnt.copy(key = "replacement"))
        var failure: Throwable? = null
        coordinator.openActive(target(tnt)) { failure = it.exceptionOrNull() }
        assertEquals("DISPLAY_UNAVAILABLE", (failure as DesktopDisplayException).code)
        assertTrue(host.launches.isEmpty())
    }

    @Test
    fun displayLocalStartupRequiresAnExplicitCurrentTarget() {
        var failure: Throwable? = null
        coordinator.openActive { failure = it.exceptionOrNull() }
        assertEquals("DISPLAY_UNAVAILABLE", (failure as DesktopDisplayException).code)
        assertTrue(host.launches.isEmpty())
    }

    @Test
    fun displayChangesCannotAutomaticallyMoveTheR2Session() {
        coordinator.openActive(target(phone)) {}
        attach()
        coordinator.onDisplaysChanged()
        host.fireTimers()
        assertEquals(listOf(0), host.launches.map { it.display.id })
        assertEquals(false, coordinator.snapshot()["autoOpenOnMonitor"])
        assertEquals(false, coordinator.snapshot()["crossDisplayMovesAllowed"])
    }

    @Test
    fun manualCrossDisplayRoutesRejectR2WithoutLaunching() {
        var failures = 0
        coordinator.open(100003, "tnt") {
            assertEquals("DISPLAY_NOT_ALLOWED", (it.exceptionOrNull() as DesktopDisplayException).code)
            failures++
        }
        coordinator.moveToPhone {
            assertEquals("DISPLAY_NOT_ALLOWED", (it.exceptionOrNull() as DesktopDisplayException).code)
            failures++
        }
        assertEquals(2, failures)
        assertTrue(host.launches.isEmpty())
    }

    @Test
    fun unplugRetainsTheSessionEnvironmentWithoutAnInvalidAttachedState() {
        coordinator.openActive(target(tnt)) {}
        attach()
        host.connected = listOf(phone)
        coordinator.onDisplaysChanged()
        val state = coordinator.snapshot()
        assertEquals(listOf(100003), host.launches.map { it.display.id })
        assertEquals("hidden", state["phase"])
        assertNull(state["activeDisplayId"])
        assertEquals(true, state["sessionHeadless"])
        assertEquals("tnt", state["sessionEnvironment"])
        assertEquals(100003, state["sessionDisplayId"])
        coordinator.stop()
        assertNull(coordinator.snapshot()["sessionEnvironment"])
        assertEquals(false, coordinator.snapshot()["sessionHeadless"])
    }

    @Test
    fun passivePhoneMoveDoesNotRestartAHeadlessTntSession() {
        var actualDisplay = tnt.id
        coordinator.openActive(target(tnt)) {}
        attach { actualDisplay }
        actualDisplay = phone.id
        host.connected = listOf(phone)
        coordinator.observePassiveActivityEnvironment(phone.id, coordinator.sessionGeneration)
        val state = coordinator.snapshot()
        assertEquals(listOf(tnt.id), host.launches.map { it.display.id })
        assertEquals("tnt", state["sessionEnvironment"])
        assertEquals(true, state["sessionHeadless"])
        assertEquals("hidden", state["phase"])
    }

    @Test
    fun headlessSnapshotSuppressesThePassivelyMovedPhoneOwner() {
        var actualDisplay = tnt.id
        coordinator.openActive(target(tnt)) {}
        attach { actualDisplay }
        actualDisplay = phone.id
        host.connected = listOf(phone)
        val state = coordinator.snapshot()
        assertEquals(true, state["sessionHeadless"])
        assertNull(state["activeDisplayId"])
        assertNull(state["activeDisplayKey"])
        assertNull(state["profileKind"])
    }

    @Test
    fun genericStartupAndAutomaticOpeningAreUnaffected() {
        val genericHost = LocalStartupHost(listOf(phone))
        val generic = DesktopDisplayCoordinator(host = genericHost)
        generic.openActive {}
        assertEquals(listOf(0), genericHost.launches.map { it.display.id })
        assertEquals(true, generic.snapshot()["autoOpenOnMonitor"])
        assertEquals(true, generic.snapshot()["crossDisplayMovesAllowed"])
    }

    @Test
    fun genericStartupCanReuseItsCurrentExternalDisplayWithoutCrossDisplayPermission() {
        val currentExternal = tnt.copy(
            eligible = false,
            reason = "Android denied cross-display placement",
        )
        val genericHost = LocalStartupHost(listOf(phone, currentExternal)).apply {
            currentDisplay = currentExternal.id
        }
        val generic = DesktopDisplayCoordinator(host = genericHost)

        generic.openActive {}

        val request = genericHost.launches.single()
        assertEquals(currentExternal.id, request.display.id)
        assertTrue(generic.canAttach(
            request.requestId,
            currentExternal.id,
            currentExternal.key,
            request.sessionGeneration,
        ))
    }

    @Test
    fun failedTeardownKeepsTruthfulRuntimeAndEnvironmentMetadataUntilRetrySucceeds() {
        coordinator.openActive(target(tnt)) {}
        attach()
        coordinator.startStopping()
        coordinator.finishStopping(IllegalStateException("runtime refused to stop"))
        assertTrue(coordinator.isStopped)
        assertEquals(true, coordinator.snapshot()["sessionRunning"])
        assertEquals("tnt", coordinator.snapshot()["sessionEnvironment"])
        assertEquals("error", coordinator.snapshot()["phase"])
        coordinator.startStopping()
        coordinator.finishStopping()
        assertEquals(false, coordinator.snapshot()["sessionRunning"])
        assertNull(coordinator.snapshot()["sessionEnvironment"])
    }

    @Test
    fun launchReadinessWaitsForRuntimeAndUserStopCancelsItExactlyOnce() {
        val epoch = coordinator.prepareSession("termux", "xfce4", true)
        val results = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(epoch, results::add)
        assertTrue(results.isEmpty())
        coordinator.stop()
        coordinator.sessionStarted(epoch, Result.success(Unit))
        assertEquals(1, results.size)
        assertTrue(results.single().isFailure)
    }

    @Test
    fun launchReadinessReportsRuntimeFailureRatherThanPlacementSuccess() {
        val epoch = coordinator.prepareSession("termux", "xfce4", true)
        val results = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(epoch, results::add)
        val failure = IllegalStateException("XFCE exited")
        coordinator.sessionStarted(epoch, Result.failure(failure))
        assertEquals(1, results.size)
        assertSame(failure, results.single().exceptionOrNull())
    }

    private fun target(display: DesktopDisplay) = DesktopStartupTarget(
        display.id, display.key, displayLocalStrategy.environmentFor(display),
    )

    private fun attach(
        coordinator: DesktopDisplayCoordinator = this.coordinator,
        host: LocalStartupHost = this.host,
        actualDisplay: () -> Int = { host.launches.last().display.id },
    ) {
        val request = host.launches.last()
        val lease = coordinator.claim(request.display.id) {}
        coordinator.registerActivity(lease, request.requestId, actualDisplay) {}
        coordinator.reportGeometry(lease, ProfileKind.MONITOR, PixelSize(1920, 1080))
        coordinator.onAttached(lease, request.requestId, PixelSize(1920, 1080))
    }

    private fun movableStrategy(
        allowAutomaticMoves: Boolean = true,
    ) = MovableSecondaryDisplayAdapter(
        PlacementSelection(
            mode = PlacementMode.MOVABLE,
            source = PlacementSelectionSource.RUNTIME_EVIDENCE,
            allowAutomaticMoves = allowAutomaticMoves,
            reason = "test movable placement",
        ),
    )

    private fun displayLocalStrategy() = DisplayLocalRestartAdapter(
        PlacementSelection(
            mode = PlacementMode.DISPLAY_LOCAL,
            source = PlacementSelectionSource.DEVICE_QUIRK,
            allowAutomaticMoves = false,
            reason = "test display-local placement",
            tntDisplayIds = setOf(tnt.id),
        ),
    )
}

private class LocalStartupHost(var connected: List<DesktopDisplay>) : DesktopDisplayHost {
    val launches = mutableListOf<DesktopLaunchRequest>()
    var launchFailure: DesktopDisplayException? = null
    var currentDisplay: Int? = null
    var running = true
    private val timers = mutableListOf<() -> Unit>()
    override fun currentDisplay() = currentDisplay?.let { id ->
        connected.firstOrNull { it.id == id }?.copy(eligible = true, reason = null)
    }
    override fun displays() = connected
    override fun defaultProfileKind() = ProfileKind.PHONE
    override fun sessionRunning() = running
    override fun launch(request: DesktopLaunchRequest) {
        launches += request
        launchFailure?.let { throw it }
    }
    override fun focus(requestId: Long) = true
    override fun cancelLaunch(requestId: Long) {}
    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit {
        timers += action
        return { timers.remove(action); Unit }
    }
    override fun notifyReturnRequired(error: DesktopDisplayException) {}
    fun fireTimers() { timers.toList().forEach { it() } }
}

private class RecordingPlacementStrategy(
    private val delegate: DesktopPlacementStrategy,
) : DesktopPlacementStrategy by delegate {
    val orientationDisplays = mutableListOf<Int>()
    val orientationRequests get() = orientationDisplays.size

    override fun requestedOrientation(
        display: DesktopDisplay,
        originalOrientation: Int,
    ): Int {
        orientationDisplays += display.id
        return delegate.requestedOrientation(display, originalOrientation)
    }
}

private class OrientationActivity(
    initialOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
) : Activity() {
    private var orientation = initialOrientation

    override fun getRequestedOrientation() = orientation

    override fun setRequestedOrientation(requestedOrientation: Int) {
        orientation = requestedOrientation
    }
}
