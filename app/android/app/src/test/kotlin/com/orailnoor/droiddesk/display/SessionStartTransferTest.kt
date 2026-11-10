package com.orailnoor.droiddesk.display

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartTransferTest {
    @Test
    fun sessionGenerationKeepsVolatilePublicationForWorkerReads() {
        val field = DesktopDisplayCoordinator::class.java.getDeclaredField(
            "publishedSessionGeneration",
        )

        assertTrue(Modifier.isVolatile(field.modifiers))
    }

    @Test
    fun claimReturnsAcceptedLease() {
        val coordinator = DesktopDisplayCoordinator()

        val claim = coordinator.claim(7) {}
        val lease = (claim as RendererClaim.Accepted).lease

        assertEquals(RendererLease(generation = 1, displayId = 7), lease)
        assertTrue(coordinator.isCurrent(lease))
    }

    @Test
    fun rendererReplacementTransfersConsumedSessionStart() {
        val coordinator = DesktopDisplayCoordinator()
        val epoch = coordinator.prepareSession("proot", "xfce4", true)
        val first = (coordinator.claim(0) {} as RendererClaim.Accepted).lease
        assertTrue(coordinator.consumeSessionStart(first))

        val replacement = (coordinator.claim(0) {} as RendererClaim.Accepted).lease

        assertTrue(coordinator.consumeSessionStart(replacement))
        assertNull(coordinator.beginSessionStart(first))
        assertEquals(epoch, coordinator.beginSessionStart(replacement))
    }

    @Test
    fun replacementCanAwaitStartupAlreadyBegunByRetiredRenderer() {
        val coordinator = DesktopDisplayCoordinator()
        val epoch = coordinator.prepareSession("proot", "xfce4", true)
        val first = (coordinator.claim(0) {} as RendererClaim.Accepted).lease
        assertTrue(coordinator.consumeSessionStart(first))
        assertEquals(epoch, coordinator.beginSessionStart(first))
        coordinator.claim(0) {}
        val outcomes = mutableListOf<Result<Unit>>()

        coordinator.awaitSessionReady(epoch, outcomes::add)
        coordinator.sessionStarted(epoch, Result.success(Unit))

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isSuccess)
    }

    @Test
    fun nestedClaimDuringCleanupRejectsTheOuterClaimWithoutErasingGeometry() {
        val coordinator = DesktopDisplayCoordinator(host = TransferDisplayHost())
        lateinit var nested: RendererLease
        coordinator.claim(0) {
            nested = (coordinator.claim(0) {} as RendererClaim.Accepted).lease
            coordinator.reportGeometry(nested, ProfileKind.PHONE, PixelSize(1280, 720))
        }

        val outer = coordinator.claim(7) {}

        assertSame(RendererClaim.Rejected, outer)
        assertTrue(coordinator.isCurrent(nested))
        assertEquals(1280, coordinator.snapshot()["renderWidth"])
        assertEquals(720, coordinator.snapshot()["renderHeight"])
    }

    @Test
    fun stopDuringClaimCleanupReturnsRejectedInsteadOfThrowing() {
        val coordinator = DesktopDisplayCoordinator()
        coordinator.claim(0) { coordinator.stop() }

        val replacement = coordinator.claim(7) {}

        assertSame(RendererClaim.Rejected, replacement)
        assertTrue(coordinator.isStopped)
    }

    @Test
    fun supersededAttemptCannotCompleteAReentrantReplacement() {
        val coordinator = DesktopDisplayCoordinator()
        val originalEpoch = coordinator.prepareSession("proot", "xfce4", true)
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()
        val replacement = mutableListOf<Result<Unit>>()
        var replacementEpoch: Long? = null
        coordinator.awaitSessionReady(originalEpoch) { result ->
            first += result
            replacementEpoch = coordinator.prepareSession("chroot", "plasma", true)
            coordinator.awaitSessionReady(checkNotNull(replacementEpoch), replacement::add)
        }
        coordinator.awaitSessionReady(originalEpoch, second::add)

        val supersedingEpoch = coordinator.prepareSession("termux", "mate", true)

        assertEquals(
            "SESSION_STOPPED",
            (first.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(
            "SESSION_STOPPED",
            (second.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(checkNotNull(replacementEpoch) > supersedingEpoch)
        coordinator.sessionStarted(supersedingEpoch, Result.success(Unit))
        assertTrue(replacement.isEmpty())

        coordinator.sessionStarted(checkNotNull(replacementEpoch), Result.success(Unit))
        assertTrue(replacement.single().isSuccess)
    }

    @Test
    fun oldWaitersKeepTheirResultWhenLaterWaiterStopsTheRetry() {
        val coordinator = DesktopDisplayCoordinator()
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", true)
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()
        val third = mutableListOf<Result<Unit>>()
        val retry = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(failedEpoch) { result ->
            first += result
            val retryEpoch = coordinator.prepareSession("chroot", "plasma", true)
            coordinator.awaitSessionReady(retryEpoch, retry::add)
        }
        coordinator.awaitSessionReady(failedEpoch) { result ->
            second += result
            coordinator.stop()
        }
        coordinator.awaitSessionReady(failedEpoch, third::add)
        val failure = IllegalStateException("runtime exited")

        coordinator.sessionStarted(failedEpoch, Result.failure(failure))

        assertSame(failure, first.single().exceptionOrNull())
        assertSame(failure, second.single().exceptionOrNull())
        assertSame(failure, third.single().exceptionOrNull())
        assertEquals(
            "SESSION_STOPPED",
            (retry.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        coordinator.sessionStarted(failedEpoch, Result.success(Unit))
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(1, third.size)
        assertEquals(1, retry.size)
    }

    @Test
    fun nestedSettlementCannotOvertakeTheCapturedObserverCohort() {
        val coordinator = DesktopDisplayCoordinator()
        val observed = mutableListOf<String>()
        coordinator.subscribeSessionStart {
            observed += it.exceptionOrNull()?.message ?: "original succeeded"
        }
        val originalEpoch = coordinator.prepareSession("proot", "xfce4", true)
        coordinator.awaitSessionReady(originalEpoch) {
            val replacementEpoch = coordinator.prepareSession("chroot", "plasma", true)
            coordinator.sessionStarted(
                replacementEpoch,
                Result.failure(IllegalStateException("replacement failed")),
            )
        }

        coordinator.sessionStarted(originalEpoch, Result.success(Unit))

        assertEquals(
            listOf("original succeeded", "replacement failed"),
            observed,
        )
    }

    @Test
    fun observersSeeTheSettledEpochBeforeWaitersRecoverTheSession() {
        val coordinator = DesktopDisplayCoordinator()
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", true)
        val observedEpochs = mutableListOf<Long>()
        coordinator.subscribeSessionStart { observedEpochs += coordinator.sessionGeneration }
        var recoveredEpoch: Long? = null
        coordinator.awaitSessionReady(failedEpoch) {
            recoveredEpoch = coordinator.prepareSession("proot", "xfce4", false)
        }

        coordinator.sessionStarted(
            failedEpoch,
            Result.failure(IllegalStateException("runtime exited")),
        )

        assertEquals(listOf(failedEpoch), observedEpochs)
        assertTrue(checkNotNull(recoveredEpoch) > failedEpoch)
    }

    @Test
    fun nestedObserversKeepTheirSettlementEpochAfterAnotherWaiterRecovers() {
        val coordinator = DesktopDisplayCoordinator()
        val observed = mutableListOf<Pair<String, Long>>()
        coordinator.subscribeSessionStart {
            observed += (it.exceptionOrNull()?.message ?: "success") to
                coordinator.sessionGeneration
        }
        val originalEpoch = coordinator.prepareSession("proot", "xfce4", true)
        var failedEpoch: Long? = null
        var recoveredEpoch: Long? = null
        coordinator.awaitSessionReady(originalEpoch) {
            failedEpoch = coordinator.prepareSession("chroot", "plasma", true)
            coordinator.sessionStarted(
                checkNotNull(failedEpoch),
                Result.failure(IllegalStateException("replacement failed")),
            )
        }
        coordinator.awaitSessionReady(originalEpoch) {
            recoveredEpoch = coordinator.prepareSession("chroot", "plasma", false)
        }

        coordinator.sessionStarted(originalEpoch, Result.success(Unit))

        assertEquals(
            listOf(
                "success" to originalEpoch,
                "replacement failed" to checkNotNull(failedEpoch),
            ),
            observed,
        )
        assertTrue(checkNotNull(recoveredEpoch) > checkNotNull(failedEpoch))
    }

    @Test
    fun stopDrainsAnAttemptInvalidatedByANewerReservation() {
        val coordinator = DesktopDisplayCoordinator()
        val epoch = coordinator.prepareSession("proot", "xfce4", true)
        val outcomes = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(epoch, outcomes::add)
        val reservedEpoch = coordinator.beginSession()

        coordinator.stop()

        assertTrue(reservedEpoch > epoch)
        assertEquals(1, outcomes.size)
        assertEquals(
            "SESSION_STOPPED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun restartRequestsForTheSameKeyCoalesceAndCompleteTogether() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()

        controller.requestRestart("tnt", 1, first::add)
        controller.requestRestart("tnt", 1, second::add)

        assertEquals(1, host.stopCalls)
        host.completeStop(Result.success(Unit))
        assertEquals(listOf("tnt"), host.starts)
        host.completeStart(Result.success(Unit))

        assertTrue(first.single().isSuccess)
        assertTrue(second.single().isSuccess)
        assertFalse(controller.hasPendingRestart)
        assertEquals(listOf(true, false), host.restarting)
    }

    @Test
    fun newerRestartSupersedesTheTargetStillWaitingForStopExactlyOnce() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()

        controller.requestRestart("phone", 1, first::add)
        controller.requestRestart("tnt", 1, second::add)

        assertEquals(
            "SESSION_STOPPED",
            (first.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        host.completeStop(Result.success(Unit))
        assertEquals(listOf("tnt"), host.starts)
        host.completeStart(Result.success(Unit))
        host.repeatStop(Result.failure(IllegalStateException("late stop")))

        assertEquals(1, first.size)
        assertTrue(second.single().isSuccess)
        assertFalse(controller.hasPendingRestart)
    }

    @Test
    fun returningToTheStartingTargetCollapsesTheQueuedRestart() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()
        val third = mutableListOf<Result<Unit>>()

        controller.requestRestart("phone", 1, first::add)
        host.completeStop(Result.success(Unit))
        controller.requestRestart("tnt", 1, second::add)
        controller.requestRestart("phone", 1, third::add)
        host.completeStart(Result.success(Unit))

        assertEquals(
            "SESSION_STOPPED",
            (first.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(
            "SESSION_STOPPED",
            (second.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(third.single().isSuccess)
        assertEquals(1, host.stopCalls)
        assertEquals(listOf("phone"), host.starts)
        assertFalse(controller.hasPendingRestart)
    }

    @Test
    fun reentrantCancellationFromSupersededRequestCompletesReturningRequestExactlyOnce() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val first = mutableListOf<Result<Unit>>()
        val superseded = mutableListOf<Result<Unit>>()
        val returning = mutableListOf<Result<Unit>>()

        controller.requestRestart("phone", 1, first::add)
        host.completeStop(Result.success(Unit))
        controller.requestRestart("tnt", 1) { result ->
            superseded += result
            controller.cancelPendingRestart()
        }
        controller.requestRestart("phone", 1, returning::add)
        host.completeStart(Result.success(Unit))

        assertEquals(1, first.size)
        assertEquals(1, superseded.size)
        assertEquals(1, returning.size)
        assertEquals(
            "SESSION_STOPPED",
            (returning.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertFalse(controller.hasPendingRestart)
        assertEquals(listOf(true, false), host.restarting)
    }

    @Test
    fun reentrantRestartFromSupersededRequestKeepsTheNewQueueEntry() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val first = mutableListOf<Result<Unit>>()
        val superseded = mutableListOf<Result<Unit>>()
        val returning = mutableListOf<Result<Unit>>()
        val reentrant = mutableListOf<Result<Unit>>()

        controller.requestRestart("phone", 1, first::add)
        host.completeStop(Result.success(Unit))
        controller.requestRestart("tnt", 1) { result ->
            superseded += result
            controller.requestRestart("tablet", 1, reentrant::add)
        }
        controller.requestRestart("phone", 1, returning::add)
        host.completeStart(Result.success(Unit))

        assertEquals(2, host.stopCalls)
        host.completeStop(Result.success(Unit))
        assertEquals(listOf("phone", "tablet"), host.starts)
        host.completeStart(Result.success(Unit))

        assertEquals(1, first.size)
        assertEquals(1, superseded.size)
        assertEquals(1, returning.size)
        assertEquals(
            "SESSION_STOPPED",
            (returning.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(reentrant.single().isSuccess)
        assertFalse(controller.hasPendingRestart)
    }

    @Test
    fun preservedStopRetainsTheAdmittedEpochButRejectsItAfterRestartBegins() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()
        val stale = mutableListOf<Result<Unit>>()

        controller.requestRestart("phone", 1, first::add)
        host.advanceEpoch()
        controller.requestRestart("tnt", 1, second::add)

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        host.completeStop(Result.success(Unit))
        assertEquals(listOf("tnt"), host.starts)

        controller.requestRestart("tablet", 1, stale::add)

        assertEquals(
            "SESSION_STOPPED",
            (stale.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        host.completeStart(Result.success(Unit))
        assertTrue(second.single().isSuccess)
        assertFalse(controller.hasPendingRestart)
    }

    @Test
    fun restartStopFailureIsReturnedWithoutStartingTheReplacement() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val outcomes = mutableListOf<Result<Unit>>()
        val failure = IllegalStateException("runtime refused to stop")

        controller.requestRestart("tnt", 1, outcomes::add)
        host.completeStop(Result.failure(failure))
        host.repeatStop(Result.success(Unit))

        assertEquals(1, outcomes.size)
        assertSame(failure, outcomes.single().exceptionOrNull())
        assertTrue(host.starts.isEmpty())
        assertFalse(controller.hasPendingRestart)
    }

    @Test
    fun restartStartFailureIsReturnedAfterAConfirmedStop() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val outcomes = mutableListOf<Result<Unit>>()
        val failure = IllegalStateException("runtime refused to start")

        controller.requestRestart("tnt", 1, outcomes::add)
        host.completeStop(Result.success(Unit))
        host.completeStart(Result.failure(failure))
        host.repeatStart(Result.success(Unit))

        assertEquals(1, outcomes.size)
        assertSame(failure, outcomes.single().exceptionOrNull())
        assertFalse(controller.hasPendingRestart)
    }

    @Test
    fun staleRestartEpochIsRejectedBeforeStop() {
        val host = RestartHost(epoch = 2)
        val controller = DisplayLocalSessionController(host)
        val outcomes = mutableListOf<Result<Unit>>()

        controller.requestRestart("tnt", 1, outcomes::add)

        assertEquals(
            "SESSION_STOPPED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(0, host.stopCalls)
        assertFalse(controller.hasPendingRestart)
    }

    @Test
    fun cancelledRestartIgnoresLateStopCallbacks() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val outcomes = mutableListOf<Result<Unit>>()

        controller.requestRestart("tnt", 1, outcomes::add)
        controller.cancelPendingRestart()
        controller.cancelPendingRestart()
        host.repeatStop(Result.success(Unit))

        assertEquals(1, outcomes.size)
        assertEquals(
            "SESSION_STOPPED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(host.starts.isEmpty())
        assertFalse(controller.hasPendingRestart)
        assertEquals(listOf(true, false), host.restarting)
    }

    @Test
    fun cancelledStartingRestartIgnoresLateAndDuplicateStartCallbacks() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val outcomes = mutableListOf<Result<Unit>>()

        controller.requestRestart("tnt", 1, outcomes::add)
        host.completeStop(Result.success(Unit))
        controller.cancelPendingRestart()
        host.completeStart(Result.success(Unit))
        host.repeatStart(Result.failure(IllegalStateException("duplicate start")))

        assertEquals(1, outcomes.size)
        assertEquals(
            "SESSION_STOPPED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(listOf("tnt"), host.starts)
        assertFalse(controller.hasPendingRestart)
        assertEquals(listOf(true, false), host.restarting)
    }

    @Test
    fun duplicateStopAndStartCallbacksDoNotDuplicateWorkOrCompletion() {
        val host = RestartHost()
        val controller = DisplayLocalSessionController(host)
        val outcomes = mutableListOf<Result<Unit>>()

        controller.requestRestart("tnt", 1, outcomes::add)
        host.completeStop(Result.success(Unit))
        host.repeatStop(Result.success(Unit))
        host.completeStart(Result.success(Unit))
        host.repeatStart(Result.failure(IllegalStateException("duplicate start")))

        assertEquals(1, host.stopCalls)
        assertEquals(listOf("tnt"), host.starts)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isSuccess)
        assertFalse(controller.hasPendingRestart)
    }
}

private class TransferDisplayHost : DesktopDisplayHost {
    private val phone = DesktopDisplay(0, "phone", "Phone", true, true, null)

    override fun currentDisplay() = phone
    override fun displays() = listOf(phone)
    override fun defaultProfileKind() = ProfileKind.PHONE
    override fun sessionRunning() = true
    override fun launch(request: DesktopLaunchRequest) {}
    override fun focus(requestId: Long) = true
    override fun cancelLaunch(requestId: Long) {}
    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
    override fun notifyReturnRequired(error: DesktopDisplayException) {}
}

private class RestartHost(
    private var epoch: Long = 1,
) : DisplayLocalSessionHost<String> {
    var stopCalls = 0
    val starts = mutableListOf<String>()
    val restarting = mutableListOf<Boolean>()
    private var stopCompletion: ((Result<Unit>) -> Unit)? = null
    private var lastStopCompletion: ((Result<Unit>) -> Unit)? = null
    private var startCompletion: ((Result<Unit>) -> Unit)? = null
    private var lastStartCompletion: ((Result<Unit>) -> Unit)? = null

    override fun sessionEpoch() = epoch
    override fun isSessionCurrent(epoch: Long) = this.epoch == epoch
    override fun validateTarget(target: String) = Unit
    override fun restartingChanged(restarting: Boolean) {
        this.restarting += restarting
    }

    override fun stopForEnvironmentChange(completion: (Result<Unit>) -> Unit) {
        stopCalls++
        stopCompletion = completion
        lastStopCompletion = completion
    }

    override fun startOnTarget(target: String, completion: (Result<Unit>) -> Unit) {
        starts += target
        startCompletion = completion
        lastStartCompletion = completion
    }

    fun completeStop(result: Result<Unit>) {
        stopCompletion?.also { stopCompletion = null }?.invoke(result)
    }

    fun repeatStop(result: Result<Unit>) {
        lastStopCompletion?.invoke(result)
    }

    fun completeStart(result: Result<Unit>) {
        startCompletion?.also { startCompletion = null }?.invoke(result)
    }

    fun repeatStart(result: Result<Unit>) {
        lastStartCompletion?.invoke(result)
    }

    fun advanceEpoch() {
        epoch++
    }
}
