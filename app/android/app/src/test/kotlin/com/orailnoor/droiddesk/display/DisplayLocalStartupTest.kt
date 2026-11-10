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
    fun beginAndPrepareUseTheReservedEpochAndRequestedLaunchSpec() {
        val initialEpoch = coordinator.sessionGeneration

        val reservedEpoch = coordinator.beginSession()
        val preparedEpoch = coordinator.prepareSession(
            "chroot",
            "plasma",
            start = true,
            expectedEpoch = reservedEpoch,
        )
        coordinator.openActive(target(tnt)) {}

        val request = host.launches.single()
        assertTrue(reservedEpoch > initialEpoch)
        assertEquals(reservedEpoch, preparedEpoch)
        assertEquals(reservedEpoch, request.sessionGeneration)
        assertEquals("chroot", request.mode)
        assertEquals("plasma", request.desktopEnv)
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
                coordinator.prepareSession(
                    "chroot",
                    "plasma",
                    start = true,
                    expectedEpoch = nestedEpoch,
                )
            }
        }

        val outerEpoch = coordinator.beginSession()

        assertTrue(checkNotNull(nestedEpoch) > outerEpoch)
        assertFalse(coordinator.isSessionCurrent(outerEpoch))
        assertEquals(nestedEpoch, coordinator.sessionGeneration)
    }

    @Test
    fun noStartRetryAdvancesPastASettledStartupFailure() {
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.sessionStarted(
            failedEpoch,
            Result.failure(IllegalStateException("runtime exited")),
        )

        val recoveredEpoch = coordinator.prepareSession("proot", "xfce4", start = false)
        val repeatedEpoch = coordinator.prepareSession("proot", "xfce4", start = false)
        val readiness = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(recoveredEpoch, readiness::add)

        assertTrue(recoveredEpoch > failedEpoch)
        assertEquals(recoveredEpoch, repeatedEpoch)
        assertNull(coordinator.sessionFailure)
        assertTrue(readiness.single().isSuccess)
    }

    @Test
    fun noStartRetryConsumesAnExplicitReservationAfterFailure() {
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.sessionStarted(
            failedEpoch,
            Result.failure(IllegalStateException("runtime exited")),
        )
        val reservedEpoch = coordinator.beginSession()

        val recoveredEpoch = coordinator.prepareSession(
            "proot",
            "xfce4",
            start = false,
            expectedEpoch = reservedEpoch,
        )

        assertTrue(reservedEpoch > failedEpoch)
        assertEquals(reservedEpoch, recoveredEpoch)
        assertNull(coordinator.sessionFailure)
        assertNotEquals("error", coordinator.snapshot()["phase"])
    }

    @Test
    fun incompatibleNoStartSpecUsesATypedCoordinatorFailure() {
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.sessionStarted(epoch, Result.success(Unit))

        val failure = assertThrows(DesktopDisplayException::class.java) {
            coordinator.prepareSession("chroot", "plasma", start = false)
        }

        assertEquals("INVALID_ARGUMENT", failure.code)
    }

    @Test
    fun readinessQueuesCompletesImmediatelyAndRejectsAStaleEpoch() {
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val queued = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(epoch, queued::add)
        assertTrue(queued.isEmpty())

        coordinator.sessionStarted(epoch, Result.success(Unit))
        val immediate = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(epoch, immediate::add)
        val nextEpoch = coordinator.beginSession()
        val stale = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(epoch, stale::add)

        assertTrue(queued.single().isSuccess)
        assertTrue(immediate.single().isSuccess)
        assertTrue(nextEpoch > epoch)
        assertEquals(
            "SESSION_STOPPED",
            (stale.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun sessionStartObserversIgnoreStaleAndDuplicateResults() {
        val observed = mutableListOf<Result<Unit>>()
        coordinator.subscribeSessionStart(observed::add)
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)

        coordinator.sessionStarted(epoch - 1, Result.failure(IllegalStateException("stale")))
        coordinator.sessionStarted(epoch, Result.success(Unit))
        coordinator.sessionStarted(epoch, Result.failure(IllegalStateException("duplicate")))

        assertEquals(1, observed.size)
        assertTrue(observed.single().isSuccess)
        assertNull(coordinator.sessionFailure)
    }

    @Test
    fun sameSpecNoStartRecoveryStillRefreshesAutomaticOpening() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.awaitSessionReady(epoch) {
            generic.prepareSession("proot", "xfce4", start = false)
        }

        generic.sessionStarted(epoch, Result.success(Unit))
        genericHost.fireTimers()

        assertEquals(listOf(tnt.id), genericHost.launches.map { it.display.id })
    }

    @Test
    fun reservingANewEpochRetiresThePendingAutomaticOpeningTimer() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.onDisplaysChanged()
        val reservedEpoch = generic.beginSession()
        generic.prepareSession(
            "termux",
            "xfce4",
            start = false,
            expectedEpoch = reservedEpoch,
        )

        generic.onDisplaysChanged()
        genericHost.fireTimers()

        assertEquals(listOf(tnt.id), genericHost.launches.map { it.display.id })
    }

    @Test
    fun preparingANewStartRetiresThePendingAutomaticOpeningTimer() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.onDisplaysChanged()

        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.sessionStarted(epoch, Result.success(Unit))
        genericHost.fireTimers()

        assertEquals(listOf(tnt.id), genericHost.launches.map { it.display.id })
    }

    @Test
    fun observersSeeTheFailedEpochBeforeStateSubscribersRecoverTheSession() {
        val failedEpoch = coordinator.prepareSession("proot", "xfce4", true)
        val observedEpochs = mutableListOf<Long>()
        coordinator.subscribeSessionStart { observedEpochs += coordinator.sessionGeneration }
        var recoveredEpoch: Long? = null
        var recovered = false
        coordinator.subscribe {
            if (!recovered && coordinator.sessionFailure != null) {
                recovered = true
                recoveredEpoch = coordinator.prepareSession("proot", "xfce4", false)
            }
        }

        coordinator.sessionStarted(
            failedEpoch,
            Result.failure(IllegalStateException("runtime exited")),
        )

        assertEquals(listOf(failedEpoch), observedEpochs)
        assertTrue(checkNotNull(recoveredEpoch) > failedEpoch)
    }

    @Test
    fun sessionStartObserverCanReservePrepareAwaitAndLaunchAReplacement() {
        val originalEpoch = coordinator.prepareSession("proot", "xfce4", true)
        val readiness = mutableListOf<Result<Unit>>()
        var replacementEpoch: Long? = null
        coordinator.subscribeSessionStart {
            val reservedEpoch = coordinator.beginSession()
            replacementEpoch = coordinator.prepareSession(
                "chroot",
                "plasma",
                start = true,
                expectedEpoch = reservedEpoch,
            )
            coordinator.awaitSessionReady(checkNotNull(replacementEpoch), readiness::add)
            coordinator.openActive(target(tnt)) {}
        }

        coordinator.sessionStarted(originalEpoch, Result.success(Unit))

        assertTrue(readiness.isEmpty())
        assertEquals(
            replacementEpoch,
            host.launches.single().sessionGeneration,
        )
    }

    @Test
    fun waiterRegistrationDoesNotSuppressReplacementStartupUpdates() {
        val originalEpoch = coordinator.prepareSession("proot", "xfce4", true)
        val lease = (coordinator.claim(phone.id) {} as RendererClaim.Accepted).lease
        var refreshes = 0
        coordinator.setProfileRefresher(lease) { completion ->
            refreshes++
            completion(Result.success(Unit))
        }
        val readiness = mutableListOf<Result<Unit>>()
        coordinator.awaitSessionReady(originalEpoch) {
            coordinator.awaitSessionReady(coordinator.sessionGeneration, readiness::add)
        }
        var publications = 0
        val unsubscribe = coordinator.subscribe { publications++ }

        val replacementEpoch = coordinator.prepareSession("chroot", "plasma", true)
        unsubscribe()

        assertTrue(readiness.isEmpty())
        assertTrue(coordinator.isSessionCurrent(replacementEpoch))
        assertEquals(1, refreshes)
        assertEquals(1, publications)
    }

    @Test
    fun observerRegisteredByReentrantPublicationDoesNotReceiveTheOldResult() {
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val observed = mutableListOf<Result<Unit>>()
        var replacementEpoch: Long? = null
        var replaced = false
        coordinator.subscribe {
            if (!replaced && !coordinator.isStarting) {
                replaced = true
                replacementEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
                coordinator.subscribeSessionStart(observed::add)
            }
        }

        coordinator.sessionStarted(epoch, Result.success(Unit))

        assertTrue(observed.isEmpty())
        val failure = IllegalStateException("replacement failed")
        coordinator.sessionStarted(
            checkNotNull(replacementEpoch),
            Result.failure(failure),
        )
        assertSame(failure, observed.single().exceptionOrNull())
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

    @Test
    fun cachedReadinessSuccessRequiresTheRuntimeToStillBeRunning() {
        val epoch = coordinator.prepareSession("termux", "xfce4", true)
        coordinator.sessionStarted(epoch, Result.success(Unit))
        host.running = false
        val results = mutableListOf<Result<Unit>>()

        coordinator.awaitSessionReady(epoch, results::add)

        assertEquals(
            "SESSION_STOPPED",
            (results.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun failedStopCannotReportReadinessForItsCurrentEpoch() {
        coordinator.startStopping()
        coordinator.finishStopping(IllegalStateException("runtime refused to stop"))
        val results = mutableListOf<Result<Unit>>()

        coordinator.awaitSessionReady(coordinator.sessionGeneration, results::add)

        assertEquals(
            "SESSION_STOPPED",
            (results.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun pendingMoveAllowsTheRetainedRendererToConsumeAndBeginStartup() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        val retained = attach(generic, genericHost) { phone.id }

        generic.open(tnt.id, tnt.key) {}

        assertTrue(generic.consumeSessionStart(retained))
        assertEquals(epoch, generic.beginSessionStart(retained))
    }

    @Test
    fun pendingMoveCannotTransferRetainedClaimAcrossEpochAndSpec() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        val retained = attach(generic, genericHost) { phone.id }
        generic.prepareSession("chroot", "plasma", start = true)
        generic.open(tnt.id, tnt.key) {}

        val consumed = generic.consumeSessionStart(retained)
        val begun = generic.beginSessionStart(retained)

        assertFalse(consumed)
        assertNull(begun)
    }

    @Test
    fun consumeRebindsRetainedReservationAcrossSameAttemptTargets() {
        val hdmi = DesktopDisplay(200004, "hdmi", "HDMI", false, true, null)
        val genericHost = LocalStartupHost(listOf(phone, tnt, hdmi))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(allowAutomaticMoves = false),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        val retained = attach(generic, genericHost) { phone.id }
        generic.open(tnt.id, tnt.key) {}
        assertTrue(generic.consumeSessionStart(retained))
        genericHost.fireTimers()
        generic.open(hdmi.id, hdmi.key) {}

        assertTrue(generic.consumeSessionStart(retained))
        assertEquals(epoch, generic.beginSessionStart(retained))
    }

    @Test
    fun delayedBeginRebindsRetainedReservationAcrossSameAttemptTargets() {
        val hdmi = DesktopDisplay(200004, "hdmi", "HDMI", false, true, null)
        val genericHost = LocalStartupHost(listOf(phone, tnt, hdmi))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(allowAutomaticMoves = false),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        val retained = attach(generic, genericHost) { phone.id }
        generic.open(tnt.id, tnt.key) {}
        assertTrue(generic.consumeSessionStart(retained))
        genericHost.fireTimers()
        generic.open(hdmi.id, hdmi.key) {}

        assertEquals(epoch, generic.beginSessionStart(retained))
    }

    @Test
    fun focusReuseRearmsTheRetainedRendererAndCompletesReadiness() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        val readiness = mutableListOf<Result<Unit>>()
        generic.awaitSessionReady(epoch, readiness::add)
        generic.openActive {}
        val phoneRequest = genericHost.launches.single()
        val retained = attach(generic, genericHost) { phone.id }
        generic.open(tnt.id, tnt.key) {}
        assertTrue(generic.consumeSessionStart(retained))

        genericHost.fireTimers()
        generic.openActive {}

        assertEquals(listOf(phoneRequest.requestId), genericHost.focusRequests)
        assertEquals(epoch, generic.beginSessionStart(retained))
        generic.sessionStarted(epoch, Result.success(Unit))
        assertTrue(readiness.single().isSuccess)
    }

    @Test
    fun waiterOnlyRevisionDuringFocusDoesNotInvalidateReuse() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        attach(generic, genericHost) { phone.id }
        val readiness = mutableListOf<Result<Unit>>()
        val placement = mutableListOf<Result<Unit>>()
        genericHost.focusAction = {
            generic.awaitSessionReady(epoch, readiness::add)
        }

        generic.openActive(placement::add)

        assertTrue(placement.single().isSuccess)
        assertEquals(1, genericHost.launches.size)
        generic.sessionStarted(epoch, Result.success(Unit))
        assertTrue(readiness.single().isSuccess)
    }

    @Test
    fun focusReplacementCannotCompleteTheStaleReuseAsSuccess() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        attach(generic, genericHost) { phone.id }
        var replacementEpoch: Long? = null
        val placement = mutableListOf<Result<Unit>>()
        genericHost.focusAction = {
            replacementEpoch = generic.prepareSession("chroot", "plasma", start = true)
        }

        generic.openActive(placement::add)

        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(replacementEpoch, generic.sessionGeneration)
        assertEquals(1, genericHost.launches.size)
    }

    @Test
    fun moveAbaDuringClaimCleanupRejectsTheOuterClaim() {
        val genericHost = LocalStartupHost(listOf(phone))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        attach(
            coordinator = generic,
            host = genericHost,
            detach = {
                genericHost.launchFailure =
                    DesktopDisplayException("DISPLAY_NOT_ALLOWED", "nested launch failed")
                generic.openActive {}
                genericHost.launchFailure = null
            },
        )

        val outer = generic.claim(tnt.id) {}

        assertSame(RendererClaim.Rejected, outer)
    }

    @Test
    fun focusMoveAbaCannotCompleteStaleReuseAsSuccess() {
        val genericHost = LocalStartupHost(listOf(phone))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        generic.openActive {}
        attach(generic, genericHost) { phone.id }
        val nested = mutableListOf<Result<Unit>>()
        val placement = mutableListOf<Result<Unit>>()
        genericHost.focusAction = {
            genericHost.focusAction = null
            genericHost.focusResult = false
            genericHost.launchFailure =
                DesktopDisplayException("DISPLAY_NOT_ALLOWED", "nested launch failed")
            generic.openActive(nested::add)
            genericHost.launchFailure = null
            genericHost.focusResult = true
        }

        generic.openActive(placement::add)

        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (nested.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(2, genericHost.launches.size)
    }

    @Test
    fun changedLaunchIdentityCannotTransferAnUnbegunRendererClaim() {
        val originalEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        coordinator.openActive(target(tnt)) {}
        val request = host.launches.single()
        val first = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(first, request.requestId) {}
        coordinator.onAttached(first, request.requestId, PixelSize(1920, 1080))
        assertTrue(coordinator.consumeSessionStart(first))

        val replacementEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
        val replacement = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(replacement, request.requestId) {}

        assertTrue(replacementEpoch > originalEpoch)
        assertFalse(coordinator.consumeSessionStart(replacement))
        assertNull(coordinator.beginSessionStart(first))
        assertNull(coordinator.beginSessionStart(replacement))
    }

    @Test
    fun beginSessionRetiresThePendingMoveExactlyOnce() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val originalEpoch = generic.prepareSession("proot", "xfce4", start = true)
        val placement = mutableListOf<Result<Unit>>()
        var detached = 0
        var finished = 0
        generic.open(tnt.id, tnt.key, placement::add)
        val request = genericHost.launches.single()
        val incoming = (generic.claim(tnt.id) { detached++ } as RendererClaim.Accepted).lease
        generic.registerActivity(incoming, request.requestId, { tnt.id }) { finished++ }

        val replacementEpoch = generic.beginSession()
        genericHost.fireTimers()

        assertTrue(replacementEpoch > originalEpoch)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(1, detached)
        assertEquals(1, finished)
        assertEquals(listOf(request.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun prepareSessionRetiresThePendingMoveExactlyOnce() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        val placement = mutableListOf<Result<Unit>>()
        var detached = 0
        var finished = 0
        generic.open(tnt.id, tnt.key, placement::add)
        val request = genericHost.launches.single()
        val incoming = (generic.claim(tnt.id) { detached++ } as RendererClaim.Accepted).lease
        generic.registerActivity(incoming, request.requestId, { tnt.id }) { finished++ }

        val preparedEpoch = generic.prepareSession("proot", "xfce4", start = true)
        genericHost.fireTimers()

        assertEquals(preparedEpoch, generic.sessionGeneration)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(1, detached)
        assertEquals(1, finished)
        assertEquals(listOf(request.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun reservedNoStartSpecChangeRetiresTheIncompatibleMoveExactlyOnce() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val reservedEpoch = generic.beginSession()
        val placement = mutableListOf<Result<Unit>>()
        generic.open(tnt.id, tnt.key, placement::add)
        val request = genericHost.launches.single()

        val preparedEpoch = generic.prepareSession(
            "chroot",
            "plasma",
            start = false,
            expectedEpoch = reservedEpoch,
        )
        genericHost.fireTimers()

        assertEquals(reservedEpoch, preparedEpoch)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(listOf(request.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun sameEpochNoStartSpecChangeRetiresTheIncompatibleMoveExactlyOnce() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val originalEpoch = generic.sessionGeneration
        val placement = mutableListOf<Result<Unit>>()
        generic.open(tnt.id, tnt.key, placement::add)
        val request = genericHost.launches.single()

        val preparedEpoch = generic.prepareSession("chroot", "plasma", start = false)
        genericHost.fireTimers()

        assertEquals(originalEpoch, preparedEpoch)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(listOf(request.requestId), genericHost.cancelledRequests)
    }

    @Test
    fun moveRetirementDetachesBeforePublishingReadinessFinishingAndCompletion() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val events = mutableListOf<String>()
        val epoch = generic.prepareSession("proot", "xfce4", start = true)
        generic.awaitSessionReady(epoch) { events += "readiness" }
        generic.openActive {}
        val phoneRequest = genericHost.launches.single()
        val previous = (generic.claim(phone.id) {} as RendererClaim.Accepted).lease
        generic.registerActivity(previous, phoneRequest.requestId, { phone.id }) {
            events += "previous-finish"
        }
        generic.onAttached(previous, phoneRequest.requestId, PixelSize(1920, 1080))
        generic.open(tnt.id, tnt.key) { events += "completion" }
        val pendingRequest = genericHost.launches.last()
        val incoming = (generic.claim(tnt.id) { events += "detach" } as RendererClaim.Accepted).lease
        generic.registerActivity(incoming, pendingRequest.requestId, { tnt.id }) {
            events += "incoming-finish"
        }
        val unsubscribe = generic.subscribe { events += "publish" }
        events.clear()

        generic.prepareSession("chroot", "plasma", start = true)
        unsubscribe()

        assertEquals(1, events.count { it == "detach" })
        assertEquals(1, events.count { it == "readiness" })
        assertEquals(1, events.count { it == "previous-finish" })
        assertEquals(1, events.count { it == "incoming-finish" })
        assertEquals(1, events.count { it == "completion" })
        val detach = events.indexOf("detach")
        listOf("publish", "readiness", "previous-finish", "incoming-finish", "completion").forEach {
            assertTrue("$it must follow input detach: $events", events.indexOf(it) > detach)
        }
    }

    @Test
    fun launchReplacementRetiresTheStaleMoveExactlyOnce() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        val placement = mutableListOf<Result<Unit>>()
        var replacementEpoch: Long? = null
        genericHost.launchAction = { request ->
            if (request.display.id == tnt.id) {
                genericHost.launchAction = null
                replacementEpoch = generic.prepareSession("chroot", "plasma", start = true)
            }
        }

        generic.open(tnt.id, tnt.key, placement::add)
        genericHost.fireTimers()

        assertEquals(1, placement.size)
        assertEquals(
            "SESSION_STOPPED",
            (placement.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(replacementEpoch, generic.sessionGeneration)
        assertEquals(1, genericHost.launches.size)
        assertEquals(
            listOf(genericHost.launches.single().requestId),
            genericHost.cancelledRequests,
        )
    }

    @Test
    fun staleLaunchFailureCannotRetireAReentrantReplacementMove() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        val original = mutableListOf<Result<Unit>>()
        val replacement = mutableListOf<Result<Unit>>()
        var replacementEpoch: Long? = null
        val staleFailure = DesktopDisplayException("DISPLAY_NOT_ALLOWED", "stale launch failed")
        genericHost.launchAction = { request ->
            if (request.display.id == tnt.id) {
                genericHost.launchAction = null
                replacementEpoch = generic.prepareSession("chroot", "plasma", start = true)
                generic.open(phone.id, phone.key, replacement::add)
                throw staleFailure
            }
        }

        generic.open(tnt.id, tnt.key, original::add)

        val replacementRequest = genericHost.launches.last()
        assertEquals(
            "SESSION_STOPPED",
            (original.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(replacement.isEmpty())
        assertEquals(replacementEpoch, replacementRequest.sessionGeneration)
        assertTrue(generic.canAttach(
            replacementRequest.requestId,
            phone.id,
            phone.key,
            checkNotNull(replacementEpoch),
        ))
    }

    @Test
    fun onAttachedCommitsBeforeCallbacksAndKeepsTheNestedReplacement() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        generic.prepareSession("proot", "xfce4", start = true)
        val events = mutableListOf<String>()
        var replacementEpoch: Long? = null
        var pendingRequest: DesktopLaunchRequest? = null
        generic.openActive {}
        val phoneRequest = genericHost.launches.single()
        val previous = (generic.claim(phone.id) {} as RendererClaim.Accepted).lease
        generic.registerActivity(previous, phoneRequest.requestId, { phone.id }) {
            events += "finish"
        }
        generic.onAttached(previous, phoneRequest.requestId, PixelSize(1920, 1080))
        val placement = mutableListOf<Result<Unit>>()
        generic.open(tnt.id, tnt.key) { result ->
            placement += result
            events += "completion"
            val committed = checkNotNull(pendingRequest)
            assertEquals("attached", generic.snapshot()["phase"])
            assertTrue(generic.canAttach(
                committed.requestId,
                tnt.id,
                tnt.key,
                generic.sessionGeneration,
            ))
            replacementEpoch = generic.prepareSession("chroot", "plasma", start = true)
        }
        pendingRequest = genericHost.launches.last()
        val incoming = (generic.claim(tnt.id) {} as RendererClaim.Accepted).lease
        generic.registerActivity(incoming, checkNotNull(pendingRequest).requestId, { tnt.id }) {}
        val unsubscribe = generic.subscribe { events += "publish" }
        events.clear()

        generic.onAttached(
            incoming,
            checkNotNull(pendingRequest).requestId,
            PixelSize(1920, 1080),
        )
        unsubscribe()

        assertTrue(events.contains("publish"))
        assertEquals(1, events.count { it == "finish" })
        assertEquals(1, events.count { it == "completion" })
        assertTrue(events.indexOf("publish") < events.indexOf("finish"))
        assertTrue(events.indexOf("finish") < events.indexOf("completion"))
        assertTrue(placement.single().isSuccess)
        assertEquals(replacementEpoch, generic.sessionGeneration)
        assertTrue(generic.isStarting)
    }

    @Test
    fun nestedStartupResultCohortsDrainBeforeMoveCompletion() {
        val originalEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val events = mutableListOf<String>()
        val firstFailure = DesktopDisplayException("SESSION_STOPPED", "first nested failed")
        val deepFailure = DesktopDisplayException("SESSION_STOPPED", "deep nested failed")
        var stage = 0
        coordinator.subscribeSessionStart { result ->
            when (stage) {
                0 -> {
                    assertTrue(result.isSuccess)
                    events += "original-observer"
                    stage = 1
                    val nestedEpoch = coordinator.prepareSession("chroot", "plasma", start = true)
                    coordinator.awaitSessionReady(nestedEpoch) {
                        assertSame(firstFailure, it.exceptionOrNull())
                        events += "first-readiness"
                    }
                    coordinator.openActive(target(tnt)) {
                        assertEquals(
                            "SESSION_STOPPED",
                            (it.exceptionOrNull() as DesktopDisplayException).code,
                        )
                        events += "move-completion"
                    }
                    coordinator.sessionStarted(nestedEpoch, Result.failure(firstFailure))
                    coordinator.prepareSession("termux", "mate", start = true)
                }
                1 -> {
                    assertSame(firstFailure, result.exceptionOrNull())
                    events += "first-observer"
                    stage = 2
                    val deepEpoch = coordinator.prepareSession("proot", "lxqt", start = true)
                    coordinator.awaitSessionReady(deepEpoch) {
                        assertSame(deepFailure, it.exceptionOrNull())
                        events += "deep-readiness"
                    }
                    coordinator.sessionStarted(deepEpoch, Result.failure(deepFailure))
                }
                else -> {
                    assertSame(deepFailure, result.exceptionOrNull())
                    events += "deep-observer"
                }
            }
        }

        coordinator.sessionStarted(originalEpoch, Result.success(Unit))

        assertEquals(1, events.count { it == "deep-observer" })
        assertEquals(1, events.count { it == "deep-readiness" })
        assertEquals(1, events.count { it == "move-completion" })
        assertTrue(events.indexOf("deep-observer") < events.indexOf("move-completion"))
        assertTrue(events.indexOf("deep-readiness") < events.indexOf("move-completion"))
    }

    @Test
    fun moveCompletionCreatedSettlementsDrainBeforeDeliveryReturns() {
        val genericHost = LocalStartupHost(listOf(phone, tnt))
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val originalEpoch = generic.prepareSession("proot", "xfce4", start = true)
        val events = mutableListOf<String>()
        generic.awaitSessionReady(originalEpoch) { events += "original-readiness" }
        generic.open(tnt.id, tnt.key) {
            assertEquals(
                "SESSION_STOPPED",
                (it.exceptionOrNull() as DesktopDisplayException).code,
            )
            events += "move-completion"
            val nestedEpoch = generic.prepareSession("termux", "mate", start = true)
            generic.awaitSessionReady(nestedEpoch) { result ->
                assertEquals(
                    "SESSION_STOPPED",
                    (result.exceptionOrNull() as DesktopDisplayException).code,
                )
                events += "nested-readiness"
            }
            generic.prepareSession("chroot", "plasma", start = true)
        }

        generic.prepareSession("chroot", "mate", start = true)

        assertEquals(
            listOf("original-readiness", "move-completion", "nested-readiness"),
            events,
        )
    }

    @Test
    fun reentrantClaimRetirementFinishesThePreviousOwnerExactlyOnce() {
        var pendingRequestId = 0L
        var finished = 0
        coordinator.openActive(target(phone)) {}
        val phoneRequest = host.launches.single()
        val previous = (coordinator.claim(phone.id) {
            val nested = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
            coordinator.registerActivity(nested, pendingRequestId, { tnt.id }) {}
            coordinator.rejectAttachment(pendingRequestId)
        } as RendererClaim.Accepted).lease
        coordinator.registerActivity(previous, phoneRequest.requestId, { phone.id }) {
            finished++
        }
        coordinator.onAttached(previous, phoneRequest.requestId, PixelSize(1920, 1080))
        coordinator.openActive(target(tnt)) {}
        pendingRequestId = host.launches.last().requestId

        val replacement = coordinator.claim(tnt.id) {}

        assertSame(RendererClaim.Rejected, replacement)
        assertEquals(1, finished)
    }

    @Test
    fun alreadyStoppedRestartCancellationCannotStopItsCallbackReplacement() {
        val localHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = phone.id
        }
        lateinit var local: DesktopDisplayCoordinator
        local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocalStrategy,
            stopForEnvironmentChange = {
                local.stop(preserveRestart = true)
                localHost.running = false
            },
        )
        local.openActive(target(phone)) {}
        attach(local, localHost)
        var replacementEpoch: Long? = null
        val restart = mutableListOf<Result<Unit>>()
        localHost.currentDisplay = tnt.id
        assertTrue(local.restartForEnvironmentIfNeeded(
            target(tnt),
            local.sessionGeneration,
        ) {
            restart += it
            replacementEpoch = local.prepareSession("chroot", "plasma", start = true)
        })
        assertTrue(local.isStopped)

        local.stop()

        assertTrue(restart.single().isFailure)
        assertEquals(replacementEpoch, local.sessionGeneration)
        assertFalse(local.isStopped)
        assertTrue(local.isStarting)
        assertEquals("starting", local.snapshot()["phase"])
    }

    private fun target(display: DesktopDisplay) = DesktopStartupTarget(
        display.id, display.key, displayLocalStrategy.environmentFor(display),
    )

    private fun attach(
        coordinator: DesktopDisplayCoordinator = this.coordinator,
        host: LocalStartupHost = this.host,
        detach: () -> Unit = {},
        actualDisplay: () -> Int = { host.launches.last().display.id },
    ): RendererLease {
        val request = host.launches.last()
        val lease = (coordinator.claim(request.display.id, detach) as RendererClaim.Accepted).lease
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
    var launchAction: ((DesktopLaunchRequest) -> Unit)? = null
    var focusAction: (() -> Unit)? = null
    var focusResult = true
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
        launchAction?.invoke(request)
        launchFailure?.let { throw it }
    }
    override fun focus(requestId: Long): Boolean {
        focusRequests += requestId
        focusAction?.invoke()
        return focusResult
    }
    override fun cancelLaunch(requestId: Long) {
        cancelledRequests += requestId
    }
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
