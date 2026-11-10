package com.orailnoor.droiddesk.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartTransferTest {
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
