package com.orailnoor.droiddesk.display.startup

import com.orailnoor.droiddesk.display.DesktopStartupTarget
import com.orailnoor.droiddesk.display.DisplayEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAttemptMachineTest {
    @Test
    fun reservationAdvancesCurrentEpochAndIsConsumedOnlyOnce() {
        val machine = machine(epoch = 7L)

        val reservation = machine.reserveEpoch(cancelled())

        assertEquals(8L, reservation.snapshot.epoch)
        assertEquals(8L, reservation.snapshot.reservedEpoch)
        val first = machine.prepareStart(
            spec = proot,
            expectedEpoch = 8L,
            supersededResult = replaced(),
        )
        assertEquals(8L, first.prepare?.identity?.epoch)
        assertNull(first.snapshot.reservedEpoch)

        val second = machine.prepareStart(
            spec = chroot,
            expectedEpoch = 8L,
            supersededResult = replaced(),
        )

        assertEquals(9L, second.prepare?.identity?.epoch)
        assertEquals(9L, second.snapshot.epoch)
        assertNull(second.snapshot.reservedEpoch)
        assertEquals(
            first.prepare?.identity,
            second.settlement?.identity,
        )
    }

    @Test
    fun attemptBaseIdentityStaysImmutableWhileTargetSelectionChangesExplicitly() {
        val machine = machine()
        val prepared = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        )
        val base = checkNotNull(prepared.prepare).identity

        assertEquals(base, prepared.snapshot.baseIdentity)
        assertNull(prepared.snapshot.selectedIdentity)
        val tntSelection = machine.selectTarget(tnt)
        val phoneSelection = machine.selectTarget(phone)

        assertEquals(StartupIdentity(base.epoch, proot), base)
        assertEquals(base, machine.snapshot().baseIdentity)
        assertEquals(base.copy(target = tnt), tntSelection)
        assertEquals(base.copy(target = phone), phoneSelection)
        assertEquals(phoneSelection, machine.snapshot().selectedIdentity)
    }

    @Test
    fun reservationInvalidatesUnresolvedAttemptWithExactFifoCohort() {
        val machine = machine()
        val attempt = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val first = Waiter("first")
        val second = Waiter("second")
        assertTrue(machine.await(attempt.epoch, first) is AwaitDecision.Queued)
        assertTrue(machine.await(attempt.epoch, second) is AwaitDecision.Queued)
        val cancelled = cancelled()

        val transition = machine.reserveEpoch(cancelled)

        val settlement = checkNotNull(transition.settlement)
        assertEquals(attempt, settlement.identity)
        assertEquals(cancelled, settlement.result)
        assertEquals(listOf(first, second), settlement.waiters)
        assertFalse(settlement.notifyObservers)
        assertEquals(0, first.deliveries)
        assertEquals(0, second.deliveries)
        assertNull(transition.snapshot.baseIdentity)
        assertEquals(attempt.epoch + 1, transition.snapshot.reservedEpoch)
    }

    @Test
    fun capturedTransitionSurvivesReentrantReplacementWithMonotonicRevision() {
        val machine = machine()
        val original = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val first = Waiter("first")
        val second = Waiter("second")
        machine.await(original.epoch, first)
        machine.await(original.epoch, second)

        val outer = machine.reserveEpoch(cancelled())
        val captured = checkNotNull(outer.settlement)
        val nested = machine.prepareStart(
            spec = chroot,
            expectedEpoch = outer.snapshot.reservedEpoch,
            supersededResult = replaced(),
        )
        val nestedIdentity = checkNotNull(nested.prepare).identity
        val terminal = checkNotNull(
            machine.settle(
                epoch = nestedIdentity.epoch,
                result = Result.success(Unit),
                notifyObservers = true,
            ),
        )

        assertEquals(original.epoch, captured.identity.epoch)
        assertEquals(listOf(first, second), captured.waiters)
        assertTrue(captured.revision < nested.revision)
        assertTrue(nested.revision < terminal.revision)
        assertEquals(nestedIdentity.epoch, terminal.identity.epoch)
        assertEquals(listOf(first, second), captured.waiters)
    }

    @Test
    fun noStartRecoveryAdvancesOnceAndRetiresSettledFailure() {
        val machine = machine()
        val failedIdentity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val failure = IllegalStateException("runtime exited")
        machine.settle(
            epoch = failedIdentity.epoch,
            result = Result.failure(failure),
            failure = failure,
            notifyObservers = true,
        )

        val recovered = machine.prepareNoStart(proot)
        val repeated = machine.prepareNoStart(proot)

        assertEquals(failedIdentity.epoch + 1, recovered.prepare?.identity?.epoch)
        assertEquals(recovered.prepare?.identity?.epoch, repeated.prepare?.identity?.epoch)
        assertFalse(checkNotNull(recovered.prepare).start)
        assertNull(recovered.snapshot.baseIdentity)
        assertNull(recovered.snapshot.terminalResult)
        assertNull(recovered.snapshot.terminalFailure)
        assertEquals(AwaitDecision.NoAttempt, machine.await(recovered.snapshot.epoch, Waiter("late")))
    }

    @Test
    fun noStartRecoveryConsumesExplicitReservationWithoutAdvancingTwice() {
        val machine = machine()
        val failedIdentity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val failure = IllegalStateException("runtime exited")
        machine.settle(
            epoch = failedIdentity.epoch,
            result = Result.failure(failure),
            failure = failure,
            notifyObservers = true,
        )
        val reserved = machine.reserveEpoch(cancelled()).snapshot.reservedEpoch

        val recovered = machine.prepareNoStart(
            spec = chroot,
            expectedEpoch = reserved,
        )

        assertEquals(reserved, recovered.prepare?.identity?.epoch)
        assertEquals(reserved, recovered.snapshot.epoch)
        assertNull(recovered.snapshot.reservedEpoch)
        assertNull(recovered.snapshot.baseIdentity)
    }

    @Test
    fun noStartRejectsAnotherSpecsSettledSuccess() {
        val machine = machine()
        val identity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val result = Result.success(Unit)
        machine.settle(identity.epoch, result)

        assertThrows(IllegalStateException::class.java) {
            machine.prepareNoStart(chroot)
        }
        assertEquals(proot, machine.snapshot().spec)
        assertEquals(identity, machine.snapshot().baseIdentity)
        assertEquals(result, machine.snapshot().terminalResult)
    }

    @Test
    fun staleAndDuplicateSettlementsAreIgnoredWithoutRevisionChanges() {
        val machine = machine()
        val original = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val replacement = machine.prepareStart(
            spec = chroot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val beforeStale = machine.snapshot().revision

        assertNull(machine.settle(original.epoch, Result.success(Unit)))
        assertEquals(beforeStale, machine.snapshot().revision)
        val accepted = machine.settle(replacement.epoch, Result.success(Unit))
        val afterAccepted = machine.snapshot().revision
        assertNull(machine.settle(replacement.epoch, Result.failure(IllegalStateException("duplicate"))))

        assertEquals(replacement, accepted?.identity)
        assertEquals(afterAccepted, machine.snapshot().revision)
    }

    @Test
    fun terminalSettlementPreservesResultFailureCohortAndObserverIntent() {
        val machine = machine()
        val identity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val first = Waiter("first")
        val second = Waiter("second")
        machine.await(identity.epoch, first)
        machine.await(identity.epoch, second)
        val failure = IllegalStateException("runtime exited")
        val result = Result.failure<Unit>(failure)

        val settlement = checkNotNull(
            machine.settle(
                epoch = identity.epoch,
                result = result,
                failure = failure,
                notifyObservers = true,
            ),
        )

        assertEquals(result, settlement.result)
        assertSame(failure, settlement.result.exceptionOrNull())
        assertSame(failure, settlement.failure)
        assertEquals(listOf(first, second), settlement.waiters)
        assertTrue(settlement.notifyObservers)
        assertEquals(result, machine.snapshot().terminalResult)
        assertSame(failure, machine.snapshot().terminalFailure)
    }

    @Test
    fun awaitDistinguishesQueuedImmediateNoAttemptAndStaleEpoch() {
        val machine = machine(epoch = 20L)

        assertEquals(AwaitDecision.NoAttempt, machine.await(20L, Waiter("none")))
        assertEquals(AwaitDecision.StaleEpoch, machine.await(19L, Waiter("stale")))
        val identity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val queued = machine.await(identity.epoch, Waiter("queued"))
        val result = Result.success(Unit)
        machine.settle(identity.epoch, result)
        val immediate = machine.await(identity.epoch, Waiter("immediate"))

        assertTrue(queued is AwaitDecision.Queued)
        assertEquals(result, (immediate as AwaitDecision.Immediate).result)
    }

    @Test
    fun invalidatedEpochNeverReturnsItsCachedSuccess() {
        val machine = machine()
        val identity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        machine.settle(identity.epoch, Result.success(Unit))

        machine.reserveEpoch(cancelled())
        val decision = machine.await(identity.epoch, Waiter("late"))

        assertEquals(AwaitDecision.StaleEpoch, decision)
    }

    @Test
    fun exactLaunchIdentityControlsRendererMatchingAndClaimReservation() {
        val machine = machine()
        val base = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val selected = checkNotNull(machine.selectTarget(tnt))
        val matching = RendererStartup(10L, selected)
        val wrongEpoch = RendererStartup(11L, selected.copy(epoch = selected.epoch + 1))
        val wrongSpec = RendererStartup(12L, selected.copy(spec = chroot))
        val wrongTarget = RendererStartup(13L, selected.copy(target = phone))

        assertFalse(machine.matches(RendererStartup(9L, base)))
        assertTrue(machine.matches(matching))
        assertFalse(machine.matches(wrongEpoch))
        assertFalse(machine.matches(wrongSpec))
        assertFalse(machine.matches(wrongTarget))
        assertFalse(machine.reserveClaim(wrongTarget))
        assertTrue(machine.reserveClaim(matching))
        assertEquals("reserved", machine.snapshot().claimState)
        assertEquals(matching.generation, machine.snapshot().claimGeneration)
    }

    @Test
    fun pendingPreviousRendererMayBeginDuringTargetHandoff() {
        val machine = machine()
        machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        )
        val phoneIdentity = checkNotNull(machine.selectTarget(phone))
        val phoneRenderer = RendererStartup(41L, phoneIdentity)
        assertTrue(machine.reserveClaim(phoneRenderer))
        machine.selectTarget(tnt)

        assertFalse(machine.matches(phoneRenderer))
        assertNull(machine.beginClaim(phoneRenderer))
        val epoch = machine.beginClaim(
            renderer = phoneRenderer,
            pendingPreviousGeneration = phoneRenderer.generation,
        )

        assertEquals(phoneIdentity.epoch, epoch)
        assertEquals("started", machine.snapshot().claimState)
        assertNull(machine.snapshot().claimGeneration)
    }

    @Test
    fun pendingPreviousBeginRequiresTheIdentityThatReservedTheClaim() {
        val machine = machine()
        machine.prepareStart(proot, replaced())
        val phoneIdentity = checkNotNull(machine.selectTarget(phone))
        val phoneRenderer = RendererStartup(41L, phoneIdentity)
        assertTrue(machine.reserveClaim(phoneRenderer))
        machine.selectTarget(tnt)
        val impostor = RendererStartup(41L, phoneIdentity.copy(target = null))

        assertNull(machine.beginClaim(impostor, phoneRenderer.generation))
        assertEquals(phoneIdentity.epoch, machine.beginClaim(phoneRenderer, phoneRenderer.generation))
    }

    @Test
    fun releasingUnbegunReservationRearmsTheSelectedRenderer() {
        val machine = machine()
        machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        )
        val identity = checkNotNull(machine.selectTarget(tnt))
        val first = RendererStartup(51L, identity)
        val replacement = RendererStartup(52L, identity)
        assertTrue(machine.reserveClaim(first))

        assertTrue(machine.releaseClaim(first.generation))
        assertEquals("none", machine.snapshot().claimState)
        assertTrue(machine.reserveClaim(replacement))
        assertEquals(replacement.generation, machine.snapshot().claimGeneration)
    }

    @Test
    fun startedClaimCannotBeReleasedOrRestarted() {
        val machine = machine()
        machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        )
        val identity = checkNotNull(machine.selectTarget(tnt))
        val first = RendererStartup(61L, identity)
        val replacement = RendererStartup(62L, identity)
        assertTrue(machine.reserveClaim(first))
        assertEquals(identity.epoch, machine.beginClaim(first))
        val startedRevision = machine.snapshot().revision

        assertFalse(machine.releaseClaim(first.generation))
        assertFalse(machine.reserveClaim(replacement))
        assertNull(machine.beginClaim(replacement))
        assertEquals(startedRevision, machine.snapshot().revision)
        assertEquals("started", machine.snapshot().claimState)
    }

    @Test
    fun invalidationClearsClaimAndReturnsWaitersWithoutInvokingThem() {
        val machine = machine()
        val identity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val selected = checkNotNull(machine.selectTarget(tnt))
        val renderer = RendererStartup(71L, selected)
        val waiter = Waiter("waiter")
        machine.await(identity.epoch, waiter)
        machine.reserveClaim(renderer)

        val invalidated = machine.invalidate(cancelled())

        assertEquals(listOf(waiter), invalidated.settlement?.waiters)
        assertFalse(checkNotNull(invalidated.settlement).notifyObservers)
        assertEquals(0, waiter.deliveries)
        assertNull(invalidated.snapshot.baseIdentity)
        assertEquals("none", invalidated.snapshot.claimState)
        assertNull(invalidated.snapshot.claimGeneration)
    }

    @Test
    fun invalidationRetiresACancelledReservationEpoch() {
        val machine = machine()
        val reservedEpoch = machine.reserveEpoch(cancelled()).snapshot.reservedEpoch

        val invalidated = machine.invalidate(cancelled())

        assertTrue(invalidated.snapshot.epoch > checkNotNull(reservedEpoch))
        assertNull(invalidated.snapshot.reservedEpoch)
        assertThrows(IllegalStateException::class.java) {
            machine.prepareStart(
                spec = proot,
                expectedEpoch = reservedEpoch,
                supersededResult = replaced(),
            )
        }
    }

    @Test
    fun invalidationRetiresAConsumedNoStartEpoch() {
        val machine = machine()
        val reservedEpoch = checkNotNull(machine.reserveEpoch(cancelled()).snapshot.reservedEpoch)
        machine.prepareNoStart(proot, reservedEpoch)

        val invalidated = machine.invalidate(cancelled())

        assertTrue(invalidated.snapshot.epoch > reservedEpoch)
        assertThrows(IllegalStateException::class.java) {
            machine.prepareStart(proot, replaced(), reservedEpoch)
        }
    }

    @Test
    fun readinessCohortIsDeterministicFifoAndMachineHasNoDeliverySideEffects() {
        val machine = machine()
        val identity = machine.prepareStart(
            spec = proot,
            supersededResult = replaced(),
        ).prepare!!.identity
        val waiters = listOf(Waiter("a"), Waiter("b"), Waiter("c"))
        val revisions = waiters.map {
            (machine.await(identity.epoch, it) as AwaitDecision.Queued).revision
        }

        val settlement = checkNotNull(
            machine.settle(identity.epoch, Result.success(Unit)),
        )

        assertEquals(waiters, settlement.waiters)
        assertTrue(revisions.zipWithNext().all { (left, right) -> left < right })
        assertTrue(waiters.all { it.deliveries == 0 })
    }

    private fun machine(
        epoch: Long = 1L,
        spec: StartupSpec = termux,
    ) = StartupAttemptMachine<Waiter>(
        initialEpoch = epoch,
        initialSpec = spec,
    )

    private fun cancelled() =
        Result.failure<Unit>(IllegalStateException("startup cancelled"))

    private fun replaced() =
        Result.failure<Unit>(IllegalStateException("startup replaced"))

    private data class Waiter(
        val name: String,
        var deliveries: Int = 0,
    )

    private companion object {
        val termux = StartupSpec("termux", "xfce4")
        val proot = StartupSpec("proot", "xfce4")
        val chroot = StartupSpec("chroot", "plasma")
        val phone = DesktopStartupTarget(
            displayId = 0,
            displayKey = "phone",
            environment = DisplayEnvironment.PHONE,
        )
        val tnt = DesktopStartupTarget(
            displayId = 12,
            displayKey = "tnt",
            environment = DisplayEnvironment.TNT,
        )
    }
}
