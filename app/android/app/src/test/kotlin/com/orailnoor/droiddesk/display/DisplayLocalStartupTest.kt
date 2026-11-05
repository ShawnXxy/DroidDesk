package com.orailnoor.droiddesk.display

import org.junit.Assert.*
import org.junit.Test

class DisplayLocalStartupTest {
    private val phone = DesktopDisplay(0, "phone", "Phone", true, true, null)
    private val tnt = DesktopDisplay(100003, "tnt", "TNT", false, true, null)
    private val host = LocalStartupHost(listOf(phone, tnt))
    private val coordinator = DesktopDisplayCoordinator(
        host = host,
        environmentPolicy = DisplayEnvironmentPolicy.forDevice("darwin"),
    )

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
        display.id, display.key, DisplayEnvironmentPolicy.forDevice("darwin").environmentFor(display.id),
    )

    private fun attach(actualDisplay: () -> Int = { host.launches.last().display.id }) {
        val request = host.launches.last()
        val lease = coordinator.claim(request.display.id) {}
        coordinator.registerActivity(lease, request.requestId, actualDisplay) {}
        coordinator.reportGeometry(lease, ProfileKind.MONITOR, PixelSize(1920, 1080))
        coordinator.onAttached(lease, request.requestId, PixelSize(1920, 1080))
    }
}

private class LocalStartupHost(var connected: List<DesktopDisplay>) : DesktopDisplayHost {
    val launches = mutableListOf<DesktopLaunchRequest>()
    var launchFailure: DesktopDisplayException? = null
    var currentDisplay: Int? = null
    private val timers = mutableListOf<() -> Unit>()
    override fun currentDisplay() = currentDisplay?.let { id ->
        connected.firstOrNull { it.id == id }?.copy(eligible = true, reason = null)
    }
    override fun displays() = connected
    override fun defaultProfileKind() = ProfileKind.PHONE
    override fun sessionRunning() = true
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
