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
    fun legacyPreparationReservesItsAttemptBeforeNestedPublication() {
        var nested = false
        var published: Triple<Long, String, String>? = null
        var nestedEpoch: Long? = null
        coordinator.subscribe {
            if (!nested && coordinator.isStarting) {
                nested = true
                published = Triple(
                    coordinator.sessionGeneration,
                    coordinator.sessionMode,
                    coordinator.desktopEnv,
                )
                nestedEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
            }
        }

        val outerEpoch = coordinator.prepareSession("proot", "xfce4", start = true)

        assertEquals(Triple(outerEpoch, "proot", "xfce4"), published)
        assertTrue(checkNotNull(nestedEpoch) > outerEpoch)
        assertEquals(nestedEpoch, coordinator.sessionGeneration)
        assertEquals("chroot", coordinator.sessionMode)
        assertEquals("plasma", coordinator.desktopEnv)

        coordinator.sessionStarted(
            outerEpoch,
            Result.failure(IllegalStateException("late outer result")),
        )
        assertTrue(coordinator.isStarting)
        assertNull(coordinator.sessionFailure)
    }

    @Test
    fun synchronousStopDuringLegacyPreparationKeepsTheAttemptStopped() {
        var stopped = false
        coordinator.subscribe {
            if (!stopped && coordinator.isStarting) {
                stopped = true
                coordinator.stop()
            }
        }

        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)

        assertTrue(stopped)
        assertTrue(coordinator.isStopped)
        assertFalse(coordinator.isStarting)
        assertFalse(coordinator.isSessionCurrent(epoch))
        coordinator.sessionStarted(epoch, Result.success(Unit))
        assertTrue(coordinator.isStopped)
        assertFalse(coordinator.isStarting)
    }

    @Test
    fun explicitlyReservedEpochRemainsCurrentForTheFirstStartupAttempt() {
        val reservedEpoch = coordinator.beginSession()

        val preparedEpoch = coordinator.prepareSession(
            "proot",
            "xfce4",
            start = true,
            expectedEpoch = reservedEpoch,
        )

        assertEquals(reservedEpoch, preparedEpoch)
        assertTrue(coordinator.isSessionCurrent(reservedEpoch))
    }

    @Test
    fun retainedRendererMakesTheNextReservedEpochUnique() {
        coordinator.openActive(target(tnt)) {}
        val retainedRequest = host.launches.single()
        attach()
        host.running = false

        val reservedEpoch = coordinator.beginSession()
        val preparedEpoch = coordinator.prepareSession(
            "termux",
            "xfce4",
            start = true,
            expectedEpoch = reservedEpoch,
        )

        assertTrue(reservedEpoch > retainedRequest.sessionGeneration)
        assertEquals(reservedEpoch, preparedEpoch)
        coordinator.openActive(target(tnt)) {}
        assertEquals(2, host.launches.size)
        assertEquals(reservedEpoch, host.launches.last().sessionGeneration)
    }

    @Test
    fun startFalseRelaunchesARetainedRendererFromThePreviousEpoch() {
        coordinator.openActive(target(tnt)) {}
        val retainedRequest = host.launches.single()
        attach()

        val reservedEpoch = coordinator.beginSession()
        coordinator.prepareSession(
            "termux",
            "xfce4",
            start = false,
            expectedEpoch = reservedEpoch,
        )
        coordinator.openActive(target(tnt)) {}

        assertTrue(reservedEpoch > retainedRequest.sessionGeneration)
        assertEquals(2, host.launches.size)
        assertEquals(reservedEpoch, host.launches.last().sessionGeneration)
    }

    @Test
    fun beginSessionReturnsItsOwnReservationAcrossNestedPublication() {
        coordinator.openActive(target(tnt)) {}
        attach()
        var nested = false
        var nestedEpoch: Long? = null
        coordinator.subscribe {
            if (!nested) {
                nested = true
                nestedEpoch = coordinator.beginSession()
                coordinator.prepareSession("chroot", "plasma", true, expectedEpoch = nestedEpoch)
            }
        }

        val outerEpoch = coordinator.beginSession()

        assertTrue(checkNotNull(nestedEpoch) > outerEpoch)
        assertFalse(coordinator.isSessionCurrent(outerEpoch))
        assertEquals(nestedEpoch, coordinator.sessionGeneration)
    }

    @Test
    fun failedAttemptMakesTheNextReservedEpochConsumable() {
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.sessionStarted(failedEpoch, Result.failure(IllegalStateException("runtime exited")))

        val reservedEpoch = coordinator.beginSession()
        val preparedEpoch = coordinator.prepareSession(
            "chroot",
            "plasma",
            start = true,
            expectedEpoch = reservedEpoch,
        )

        assertTrue(reservedEpoch > failedEpoch)
        assertEquals(reservedEpoch, preparedEpoch)
        assertTrue(coordinator.isSessionCurrent(reservedEpoch))
    }

    @Test
    fun noStartRetryAdvancesPastASettledStartupFailure() {
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.sessionStarted(failedEpoch, Result.failure(IllegalStateException("runtime exited")))

        val reusedEpoch = coordinator.prepareSession("proot", "xfce4", start = false)
        val repeatedEpoch = coordinator.prepareSession("proot", "xfce4", start = false)
        val readiness = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(reusedEpoch, readiness::add)

        assertTrue(reusedEpoch > failedEpoch)
        assertEquals(reusedEpoch, repeatedEpoch)
        assertTrue(readiness.single().isSuccess)
    }

    @Test
    fun noStartRetryConsumesAnExplicitReservationAfterFailure() {
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.sessionStarted(failedEpoch, Result.failure(IllegalStateException("runtime exited")))
        val reservedEpoch = coordinator.beginSession()

        val reusedEpoch = coordinator.prepareSession(
            "proot",
            "xfce4",
            start = false,
            expectedEpoch = reservedEpoch,
        )

        assertEquals(reservedEpoch, reusedEpoch)
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
        val lease = (generic.claim(phone.id) {} as RendererClaim.Accepted).lease
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
    fun pendingRestartBlocksStrategyReplacementWithoutMutationUntilTargetValidationSettlesIt() {
        val localHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = phone.id
        }
        var stopCompletion: ((Result<Unit>) -> Unit)? = null
        lateinit var local: DesktopDisplayCoordinator
        local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocalStrategy,
            stopForEnvironmentChange = { completion ->
                local.stop(preserveRestart = true)
                localHost.running = false
                stopCompletion = completion
            },
        )
        val activity = OrientationActivity()
        assertTrue(local.bindActivity(activity))
        local.openActive(target(phone)) {}
        attach(local, localHost)
        val before = local.snapshot()
        val restart = mutableListOf<Result<Unit>>()

        localHost.currentDisplay = tnt.id
        assertTrue(local.restartForEnvironmentIfNeeded(
            target(tnt),
            local.sessionGeneration,
            restart::add,
        ))

        assertTrue(local.isStopped)
        assertTrue(local.isRestarting)
        val replacement = RecordingPlacementStrategy(movableStrategy())
        val failure = assertThrows(DesktopDisplayException::class.java) {
            local.replacePlacementStrategy(replacement)
        }
        assertEquals("INVALID_ARGUMENT", failure.code)
        assertEquals(displayLocalStrategy.selection, local.placementSelection)
        assertEquals(before["crossDisplayMovesAllowed"], local.snapshot()["crossDisplayMovesAllowed"])
        assertEquals(before["autoOpenOnMonitor"], local.snapshot()["autoOpenOnMonitor"])
        assertEquals(0, replacement.orientationRequests)

        localHost.currentDisplay = phone.id
        stopCompletion!!.invoke(Result.success(Unit))

        assertEquals(
            "DISPLAY_UNAVAILABLE",
            (restart.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertFalse(local.isRestarting)
        assertEquals(listOf(phone.id), localHost.launches.map { it.display.id })

        local.replacePlacementStrategy(replacement)

        assertEquals(replacement.selection, local.placementSelection)
        assertEquals(1, replacement.orientationRequests)
        assertEquals(true, local.snapshot()["crossDisplayMovesAllowed"])
        assertEquals(true, local.snapshot()["autoOpenOnMonitor"])
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
    fun strategyReplacementUsesRetainedMetadataWhenBoundDisplayLeavesDiscovery() {
        val genericHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        assertTrue(generic.bindActivity(OrientationActivity()))
        genericHost.connected = listOf(phone)
        genericHost.currentDisplay = phone.id
        genericHost.running = false
        val replacement = RecordingPlacementStrategy(movableStrategy())

        generic.replacePlacementStrategy(replacement)

        assertEquals(listOf(tnt.id), replacement.orientationDisplays)
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
    fun rendererReplacementTransfersConsumedClaimForTheSameLaunch() {
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.openActive(target(tnt)) {}
        val request = host.launches.single()
        val first = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(first, request.requestId) {}
        assertTrue(coordinator.consumeSessionStart(first))

        val replacement = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(replacement, request.requestId) {}

        assertTrue(coordinator.consumeSessionStart(replacement))
        assertNull(coordinator.beginSessionStart(first))
        assertEquals(epoch, coordinator.beginSessionStart(replacement))
    }

    @Test
    fun changedPayloadReplacementCannotTransferAnOldLaunchClaim() {
        coordinator.prepareSession("proot", "xfce4", start = true)
        val superseded = mutableListOf<Result<Unit>>()
        coordinator.openActive(target(tnt), superseded::add)
        val request = host.launches.single()
        val first = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(first, request.requestId) {}
        assertTrue(coordinator.consumeSessionStart(first))

        val retryEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
        val replacement = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(replacement, request.requestId) {}

        assertEquals(
            "SESSION_STOPPED",
            (superseded.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(retryEpoch > request.sessionGeneration)
        assertFalse(coordinator.consumeSessionStart(replacement))
        assertNull(coordinator.beginSessionStart(first))
        assertNull(coordinator.beginSessionStart(replacement))
        coordinator.openActive(target(tnt)) {}
        assertEquals(2, host.launches.size)
        assertEquals(retryEpoch, host.launches.last().sessionGeneration)
    }

    @Test
    fun samePayloadNewAttemptRelaunchesTheRetainedRenderer() {
        coordinator.openActive(target(tnt)) {}
        val retainedRequest = host.launches.single()
        val retained = attach()

        val retryEpoch = coordinator.prepareSession("termux", "xfce4", start = true)

        assertTrue(retryEpoch > retainedRequest.sessionGeneration)
        assertFalse(coordinator.consumeSessionStart(retained))
        coordinator.openActive(target(tnt)) {}
        assertEquals(2, host.launches.size)
        assertEquals(retryEpoch, host.launches.last().sessionGeneration)
    }

    @Test
    fun changedPayloadAttemptRelaunchesTheRetainedRenderer() {
        coordinator.openActive(target(tnt)) {}
        val retained = attach()

        val retryEpoch = coordinator.prepareSession("chroot", "plasma", start = true)

        assertFalse(coordinator.consumeSessionStart(retained))
        coordinator.openActive(target(tnt)) {}
        assertEquals(2, host.launches.size)
        assertEquals(retryEpoch, host.launches.last().sessionGeneration)
        assertEquals("chroot", host.launches.last().mode)
        assertEquals("plasma", host.launches.last().desktopEnv)
    }

    @Test
    fun newStartupCancelsADifferentPendingMoveBeforeItsReentrantCompletion() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val firstEpoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        val retained = attach(generic, genericHost) { phone.id }
        generic.sessionStarted(firstEpoch, Result.success(Unit))
        val placement = mutableListOf<Result<Unit>>()
        var nestedEpoch: Long? = null
        generic.open(tnt.id, tnt.key) { result ->
            placement += result
            nestedEpoch = generic.prepareSession("termux", "xfce4", start = true)
        }
        val pendingRequest = genericHost.launches.last()

        val replacementEpoch = generic.prepareSession("chroot", "plasma", start = true)
        genericHost.fireTimers()

        assertEquals(1, placement.size)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(listOf(pendingRequest.requestId), genericHost.cancelledRequests)
        assertTrue(checkNotNull(nestedEpoch) > replacementEpoch)
        assertEquals(nestedEpoch, generic.sessionGeneration)
        assertTrue(generic.isCurrent(retained))
    }

    @Test
    fun reservedEpochCancelsThePendingMoveBeforeNoStartPreparation() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val placement = mutableListOf<Result<Unit>>()
        generic.open(tnt.id, tnt.key, placement::add)
        val pendingRequest = genericHost.launches.single()

        val reservedEpoch = generic.beginSession()
        val preparedEpoch = generic.prepareSession(
            "termux",
            "xfce4",
            start = false,
            expectedEpoch = reservedEpoch,
        )
        genericHost.fireTimers()

        assertEquals(reservedEpoch, preparedEpoch)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(listOf(pendingRequest.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun noStartRetryCancelsTheMoveInvalidatedByItsNewEpoch() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val failedEpoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.sessionStarted(failedEpoch, Result.failure(IllegalStateException("runtime exited")))
        val placement = mutableListOf<Result<Unit>>()
        generic.open(tnt.id, tnt.key, placement::add)
        val pendingRequest = genericHost.launches.single()

        val retryEpoch = generic.prepareSession("proot", "xfce4", start = false)
        genericHost.fireTimers()

        assertTrue(retryEpoch > failedEpoch)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(listOf(pendingRequest.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun startupReplacementRetiresAClaimedMoveAndItsPreviousOwnerExactlyOnce() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        var previousFinished = 0
        var intermediateFinished = 0
        var incomingFinished = 0
        generic.openActive {}
        val phoneRequest = genericHost.launches.single()
        val previous = (generic.claim(phone.id) {} as RendererClaim.Accepted).lease
        generic.registerActivity(previous, phoneRequest.requestId, { phone.id }) {
            previousFinished++
        }
        generic.onAttached(previous, phoneRequest.requestId, PixelSize(1920, 1080))
        val placement = mutableListOf<Result<Unit>>()
        generic.open(tnt.id, tnt.key, placement::add)
        val pendingRequest = genericHost.launches.last()
        val intermediate = (generic.claim(tnt.id) {} as RendererClaim.Accepted).lease
        generic.registerActivity(intermediate, pendingRequest.requestId, { tnt.id }) {
            intermediateFinished++
        }
        val incoming = (generic.claim(tnt.id) {} as RendererClaim.Accepted).lease
        generic.registerActivity(incoming, pendingRequest.requestId, { tnt.id }) {
            incomingFinished++
        }

        generic.prepareSession("chroot", "plasma", start = true)
        genericHost.fireTimers()

        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(1, incomingFinished)
        assertEquals(1, intermediateFinished)
        assertEquals(1, previousFinished)
        assertFalse(generic.isCurrent(incoming))
        assertEquals(listOf(pendingRequest.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun reentrantRetirementRecognizesAClaimBeforeActivityRegistration() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        var previousFinished = 0
        generic.openActive {}
        val phoneRequest = genericHost.launches.single()
        val previous = (generic.claim(phone.id) {} as RendererClaim.Accepted).lease
        generic.registerActivity(previous, phoneRequest.requestId, { phone.id }) {
            previousFinished++
        }
        generic.onAttached(previous, phoneRequest.requestId, PixelSize(1920, 1080))
        val placement = mutableListOf<Result<Unit>>()
        generic.open(tnt.id, tnt.key, placement::add)
        val pendingRequest = genericHost.launches.last()
        var replacementEpoch: Long? = null
        var reentered = false
        generic.subscribe {
            if (!reentered) {
                reentered = true
                replacementEpoch = generic.prepareSession("chroot", "plasma", start = true)
            }
        }

        var claimReturned = false
        var registrationReturned = false
        var incomingDetached = 0
        val incoming = (generic.claim(tnt.id) {
            assertTrue(claimReturned)
            assertTrue(registrationReturned)
            incomingDetached++
        } as RendererClaim.Accepted).lease
        claimReturned = true
        generic.registerActivity(incoming, pendingRequest.requestId, { tnt.id }) {}
        registrationReturned = true
        generic.reportGeometry(incoming, ProfileKind.MONITOR, PixelSize(1920, 1080))
        genericHost.fireTimers()

        assertTrue(checkNotNull(replacementEpoch) > pendingRequest.sessionGeneration)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertFalse(generic.isCurrent(incoming))
        assertEquals(1, incomingDetached)
        assertEquals(1, previousFinished)
        assertEquals(listOf(pendingRequest.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun moveFailureDoesNotRecoverAfterItsCompletionReservesANewerEpoch() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        attach(generic, genericHost) { phone.id }
        var reservedEpoch: Long? = null
        generic.open(tnt.id, tnt.key) {
            reservedEpoch = generic.beginSession()
        }

        genericHost.fireTimers()

        assertTrue(checkNotNull(reservedEpoch) > epoch)
        assertEquals(reservedEpoch, generic.sessionGeneration)
        assertEquals(listOf(phone.id, tnt.id), genericHost.launches.map { it.display.id })
    }

    @Test
    fun reentrantPreviousOwnerCleanupCannotInstallAStaleClaim() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        var replacementEpoch: Long? = null
        var previousDetached = false
        generic.openActive {}
        val phoneRequest = genericHost.launches.single()
        val previous = (generic.claim(phone.id) {
            previousDetached = true
            replacementEpoch = generic.prepareSession("chroot", "plasma", start = true)
        } as RendererClaim.Accepted).lease
        generic.registerActivity(previous, phoneRequest.requestId, { phone.id }) {}
        generic.onAttached(previous, phoneRequest.requestId, PixelSize(1920, 1080))
        generic.open(tnt.id, tnt.key) {}

        var incomingDetached = 0
        val incoming = generic.claim(tnt.id) {
            incomingDetached++
        }

        assertTrue(previousDetached)
        assertNotNull(replacementEpoch)
        assertSame(RendererClaim.Rejected, incoming)
        assertEquals(0, incomingDetached)
        genericHost.fireTimers()
        assertEquals(0, incomingDetached)
    }

    @Test
    fun reentrantClaimWinsWithoutBeingOverwrittenByTheOuterClaim() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        var nested: RendererLease? = null
        generic.claim(phone.id) {
            nested = (generic.claim(phone.id) {} as RendererClaim.Accepted).lease
        }
        var outerDetached = 0

        val outer = generic.claim(tnt.id) { outerDetached++ }

        assertTrue(generic.isCurrent(checkNotNull(nested)))
        assertSame(RendererClaim.Rejected, outer)
        assertEquals(0, outerDetached)
        genericHost.fireTimers()
        assertEquals(0, outerDetached)
    }

    @Test
    fun stopDuringPreviousOwnerCleanupRejectsInsteadOfThrowing() {
        val generic = DesktopDisplayCoordinator(
            host = LocalStartupHost(listOf(phone, tnt)),
            placementStrategy = movableStrategy(),
        )
        generic.claim(phone.id) { generic.stop() }

        val replacement = generic.claim(tnt.id) {}

        assertSame(RendererClaim.Rejected, replacement)
        assertTrue(generic.isStopped)
    }

    @Test
    fun rejectedOuterClaimPreservesNestedOwnerGeometry() {
        val generic = DesktopDisplayCoordinator(
            host = LocalStartupHost(listOf(phone, tnt)),
            placementStrategy = movableStrategy(),
        )
        lateinit var nested: RendererLease
        generic.claim(phone.id) {
            nested = (generic.claim(phone.id) {} as RendererClaim.Accepted).lease
            generic.reportGeometry(nested, ProfileKind.PHONE, PixelSize(1280, 720))
        }

        val outer = generic.claim(tnt.id) {}

        assertSame(RendererClaim.Rejected, outer)
        assertTrue(generic.isCurrent(nested))
        assertEquals(1280, generic.snapshot()["renderWidth"])
        assertEquals(720, generic.snapshot()["renderHeight"])
    }

    @Test
    fun fallbackFocusRestoresTheActiveAttemptsRendererIdentity() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        val phoneRequest = genericHost.launches.single()
        val retained = attach(generic, genericHost) { phone.id }
        generic.open(tnt.id, tnt.key) {}

        genericHost.fireTimers()

        assertEquals(listOf(phoneRequest.requestId), genericHost.focusRequests)
        assertTrue(generic.consumeSessionStart(retained))
        assertEquals(epoch, generic.beginSessionStart(retained))
    }

    @Test
    fun terminalPlacementFailureSettlesObserversBeforeReentrantRetry() {
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val readiness = mutableListOf<Result<Unit>>()
        val observed = mutableListOf<Result<Unit>>()
        val placement = mutableListOf<Result<Unit>>()
        val retryReadiness = mutableListOf<Result<Unit>>()
        var retryEpoch: Long? = null
        coordinator.awaitSessionReady(epoch, readiness::add)
        coordinator.subscribeSessionStart(observed::add)
        coordinator.openActive(target(tnt)) { result ->
            placement += result
            retryEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
            coordinator.awaitSessionReady(checkNotNull(retryEpoch), retryReadiness::add)
            coordinator.sessionStarted(checkNotNull(retryEpoch), Result.success(Unit))
        }

        host.fireTimers()
        host.fireTimers()

        val placementFailure = placement.single().exceptionOrNull()
        assertEquals("PLACEMENT_TIMEOUT", (placementFailure as DesktopDisplayException).code)
        assertSame(placementFailure, readiness.single().exceptionOrNull())
        assertTrue(checkNotNull(retryEpoch) > epoch)
        assertEquals(retryEpoch, coordinator.sessionGeneration)
        assertTrue(retryReadiness.single().isSuccess)
        assertEquals(2, observed.size)
        assertSame(placementFailure, observed.first().exceptionOrNull())
        assertTrue(observed.last().isSuccess)
    }

    @Test
    fun runtimeFailureQueuesItsObserversBeforeReentrantStatePublication() {
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val observed = mutableListOf<Result<Unit>>()
        var retryEpoch: Long? = null
        var reentered = false
        coordinator.subscribeSessionStart(observed::add)
        coordinator.subscribe {
            if (!reentered && coordinator.sessionFailure != null) {
                reentered = true
                retryEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
                coordinator.sessionStarted(checkNotNull(retryEpoch), Result.success(Unit))
            }
        }
        val failure = IllegalStateException("runtime exited")

        coordinator.sessionStarted(epoch, Result.failure(failure))

        assertTrue(checkNotNull(retryEpoch) > epoch)
        assertEquals(2, observed.size)
        assertSame(failure, observed.first().exceptionOrNull())
        assertTrue(observed.last().isSuccess)
    }

    @Test
    fun claimedTerminalFailureSettlesBeforeReentrantStatePublication() {
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val readiness = mutableListOf<Result<Unit>>()
        val observed = mutableListOf<Result<Unit>>()
        val placement = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(epoch, readiness::add)
        coordinator.subscribeSessionStart(observed::add)
        coordinator.openActive(target(tnt), placement::add)
        val request = host.launches.single()
        val incoming = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(incoming, request.requestId, { tnt.id }) {}
        var retryEpoch: Long? = null
        var reentered = false
        coordinator.subscribe {
            if (!reentered) {
                reentered = true
                retryEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
                coordinator.sessionStarted(checkNotNull(retryEpoch), Result.success(Unit))
            }
        }

        host.fireTimers()

        val placementFailure = placement.single().exceptionOrNull()
        assertEquals("PLACEMENT_TIMEOUT", (placementFailure as DesktopDisplayException).code)
        assertSame(placementFailure, readiness.single().exceptionOrNull())
        assertTrue(checkNotNull(retryEpoch) > epoch)
        assertEquals(2, observed.size)
        assertSame(placementFailure, observed.first().exceptionOrNull())
        assertTrue(observed.last().isSuccess)
    }

    @Test
    fun failedRelaunchSettlesTheAttemptWithoutRemovingTheRetainedRenderer() {
        coordinator.openActive(target(tnt)) {}
        val retained = attach()
        val failure = DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Android denied the relaunch")
        host.launchFailure = failure
        val failedEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
        val readiness = mutableListOf<Result<Unit>>()
        val placement = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(failedEpoch, readiness::add)

        coordinator.openActive(target(tnt), placement::add)

        assertSame(failure, placement.single().exceptionOrNull())
        assertSame(failure, readiness.single().exceptionOrNull())
        assertFalse(coordinator.isStarting)
        assertTrue(coordinator.isCurrent(retained))
        assertEquals("error", coordinator.snapshot()["phase"])
        host.launchFailure = null
        assertTrue(coordinator.prepareSession("chroot", "plasma", start = true) > failedEpoch)
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
    ): RendererLease {
        val request = host.launches.last()
        val lease = (coordinator.claim(request.display.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(lease, request.requestId, actualDisplay) {}
        coordinator.reportGeometry(lease, ProfileKind.MONITOR, PixelSize(1920, 1080))
        coordinator.onAttached(lease, request.requestId, PixelSize(1920, 1080))
        return lease
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
    val focusRequests = mutableListOf<Long>()
    val cancelledRequests = mutableListOf<Long>()
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
    override fun focus(requestId: Long): Boolean {
        focusRequests += requestId
        return true
    }
    override fun cancelLaunch(requestId: Long) {
        cancelledRequests += requestId
    }
    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit {
        if (delayMillis == 0L) timers.add(0, action) else timers += action
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
