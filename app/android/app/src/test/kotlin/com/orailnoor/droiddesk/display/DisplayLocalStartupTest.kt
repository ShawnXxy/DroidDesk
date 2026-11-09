package com.orailnoor.droiddesk.display

import android.app.Activity
import android.content.pm.ActivityInfo
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
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
    fun displayLocalRequestOpenUsesTheLaunchingDisplayAndCompletesAfterRuntimeStart() {
        val outcomes = mutableListOf<Result<Unit>>()
        var serviceStarts = 0

        coordinator.requestOpen(
            request = openRequest(tnt, startSession = true),
            startService = { serviceStarts++ },
            completion = outcomes::add,
        )

        val launch = host.launches.single()
        assertEquals(tnt.id, launch.display.id)
        assertEquals(coordinator.sessionGeneration, launch.sessionGeneration)
        assertEquals(1, serviceStarts)
        assertTrue(outcomes.isEmpty())

        attach()
        assertTrue(outcomes.isEmpty())
        coordinator.sessionStarted(launch.sessionGeneration, Result.success(Unit))

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isSuccess)
    }

    @Test
    fun movableRequestOpenUsesTheHostsActiveDisplayWithoutAPlacementRestart() {
        val genericHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val outcomes = mutableListOf<Result<Unit>>()
        var serviceStarts = 0

        generic.requestOpen(
            request = openRequest(phone, startSession = false),
            startService = { serviceStarts++ },
            completion = outcomes::add,
        )

        assertEquals(tnt.id, genericHost.launches.single().display.id)
        assertEquals(1, serviceStarts)
        attach(generic, genericHost)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isSuccess)
    }

    @Test
    fun restartTransferCoalescesTheAdmittedEpochAndCompletesEachRequestOnce() {
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
        val initial = mutableListOf<Result<Unit>>()
        local.requestOpen(openRequest(phone, startSession = false), {}, initial::add)
        attach(local, localHost)
        assertTrue(initial.single().isSuccess)
        val admittedEpoch = local.sessionGeneration
        localHost.currentDisplay = tnt.id
        val request = openRequest(
            tnt,
            startSession = true,
            expectedEpoch = admittedEpoch,
            mode = "proot",
            desktopEnv = "plasma",
        )
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()
        var firstServiceStarts = 0
        var secondServiceStarts = 0

        local.requestOpen(request, { firstServiceStarts++ }, first::add)
        local.requestOpen(request, { secondServiceStarts++ }, second::add)

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        stopCompletion!!.invoke(Result.success(Unit))
        val launch = localHost.launches.last()
        assertEquals(tnt, launch.display)
        assertEquals("proot", launch.mode)
        assertEquals("plasma", launch.desktopEnv)
        assertTrue(launch.sessionGeneration > admittedEpoch)
        assertEquals(1, firstServiceStarts)
        assertEquals(0, secondServiceStarts)

        attach(local, localHost)
        local.sessionStarted(launch.sessionGeneration, Result.success(Unit))
        local.sessionStarted(launch.sessionGeneration, Result.success(Unit))

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertTrue(first.single().isSuccess)
        assertTrue(second.single().isSuccess)
    }

    @Test
    fun newerRestartRequestPreservesItsOwnRuntimePayload() {
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
        local.requestOpen(openRequest(phone, startSession = false), {}, {})
        attach(local, localHost)
        val admittedEpoch = local.sessionGeneration
        localHost.currentDisplay = tnt.id
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()

        local.requestOpen(
            openRequest(
                tnt,
                startSession = true,
                expectedEpoch = admittedEpoch,
                mode = "proot",
                desktopEnv = "xfce4",
            ),
            {},
            first::add,
        )
        local.requestOpen(
            openRequest(
                tnt,
                startSession = true,
                expectedEpoch = admittedEpoch,
                mode = "chroot",
                desktopEnv = "plasma",
            ),
            {},
            second::add,
        )

        assertEquals(
            "SESSION_STOPPED",
            (first.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(second.isEmpty())
        stopCompletion!!.invoke(Result.success(Unit))
        val launch = localHost.launches.last()
        assertEquals("chroot", launch.mode)
        assertEquals("plasma", launch.desktopEnv)
        attach(local, localHost)
        local.sessionStarted(launch.sessionGeneration, Result.success(Unit))
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertTrue(second.single().isSuccess)
    }

    @Test
    fun deniedServiceStartClearsOnlyTheOwnedStartupAttempt() {
        val outcomes = mutableListOf<Result<Unit>>()
        host.running = false

        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = { throw SecurityException("background start denied") },
            completion = outcomes::add,
        )

        assertEquals(1, outcomes.size)
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertFalse(coordinator.isStarting)
        assertEquals("error", coordinator.snapshot()["phase"])
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (coordinator.snapshot()["error"] as Map<*, *>)["code"],
        )
        assertTrue(host.launches.isEmpty())

        coordinator.replacePlacementStrategy(movableStrategy())
        assertEquals(PlacementMode.MOVABLE, coordinator.placementSelection.mode)
    }

    @Test
    fun failedDuplicateServiceStartDoesNotClearTheExistingStartupAttempt() {
        val first = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = {},
            completion = first::add,
        )
        val epoch = host.launches.single().sessionGeneration
        attach()
        val second = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = { throw SecurityException("duplicate start denied") },
            completion = second::add,
        )

        assertTrue(first.isEmpty())
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (second.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(coordinator.isStarting)
        coordinator.sessionStarted(epoch, Result.success(Unit))
        assertTrue(first.single().isSuccess)
    }

    @Test
    fun differingPayloadDuplicateCannotReplaceTheAdmittedRuntimeMetadata() {
        val first = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            request = openRequest(
                phone,
                startSession = true,
                mode = "proot",
                desktopEnv = "xfce4",
            ),
            startService = {},
            completion = first::add,
        )
        val launch = host.launches.single()
        attach()
        val second = mutableListOf<Result<Unit>>()
        var duplicateServiceStarts = 0

        coordinator.requestOpen(
            request = openRequest(
                phone,
                startSession = true,
                mode = "chroot",
                desktopEnv = "plasma",
            ),
            startService = { duplicateServiceStarts++ },
            completion = second::add,
        )

        assertEquals(0, duplicateServiceStarts)
        assertEquals(
            "INVALID_ARGUMENT",
            (second.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals("proot", coordinator.sessionMode)
        assertEquals("xfce4", coordinator.desktopEnv)
        assertEquals("proot", launch.mode)
        assertEquals("xfce4", launch.desktopEnv)
        assertTrue(first.isEmpty())
        coordinator.sessionStarted(launch.sessionGeneration, Result.success(Unit))
        assertTrue(first.single().isSuccess)
    }

    @Test
    fun requestOpenRejectsAnUnmatchedLegacyPreparedSession() {
        coordinator.prepareSession("proot", "xfce4", start = true)
        val outcomes = mutableListOf<Result<Unit>>()
        var serviceStarts = 0

        coordinator.requestOpen(
            openRequest(
                tnt,
                startSession = true,
                mode = "chroot",
                desktopEnv = "plasma",
            ),
            { serviceStarts++ },
            outcomes::add,
        )

        assertEquals(0, serviceStarts)
        assertEquals(
            "INVALID_ARGUMENT",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(host.launches.isEmpty())
        assertEquals("proot", coordinator.sessionMode)
        assertEquals("xfce4", coordinator.desktopEnv)
    }

    @Test
    fun synchronousStopDuringPreparationPreventsServiceStart() {
        var stopped = false
        coordinator.subscribe {
            if (!stopped && coordinator.isStarting) {
                stopped = true
                coordinator.stop()
            }
        }
        val outcomes = mutableListOf<Result<Unit>>()
        var serviceStarts = 0

        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = { serviceStarts++ },
            completion = outcomes::add,
        )

        assertEquals(0, serviceStarts)
        assertTrue(coordinator.isStopped)
        assertFalse(coordinator.isStarting)
        assertTrue(host.launches.isEmpty())
        assertEquals(
            "SESSION_STOPPED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun retainedOwnerRetryClearsTheOwnedStartupFailure() {
        coordinator.openActive(target(phone)) {}
        attach()
        val failed = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = { throw SecurityException("background start denied") },
            completion = failed::add,
        )
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (failed.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals("error", coordinator.snapshot()["phase"])
        val retry = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = {},
            completion = retry::add,
        )

        assertNull(coordinator.snapshot()["error"])
        assertEquals("starting", coordinator.snapshot()["phase"])
        assertTrue(retry.isEmpty())
        coordinator.sessionStarted(coordinator.sessionGeneration, Result.success(Unit))
        assertNull(coordinator.snapshot()["error"])
        assertEquals("attached", coordinator.snapshot()["phase"])
        assertTrue(retry.single().isSuccess)
    }

    @Test
    fun runtimeStartFailureRestoresThePreviousTargetForDirectRetry() {
        val first = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            request = openRequest(tnt, startSession = true),
            startService = {},
            completion = first::add,
        )
        val failedEpoch = host.launches.single().sessionGeneration
        attach()
        val failure = IllegalStateException("Linux runtime exited")

        coordinator.sessionStarted(failedEpoch, Result.failure(failure))

        assertSame(failure, first.single().exceptionOrNull())
        assertNull(coordinator.snapshot()["sessionEnvironment"])
        assertNull(coordinator.snapshot()["sessionDisplayId"])
        host.launches.clear()
        val retry = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = {},
            completion = retry::add,
        )
        val retryLaunch = host.launches.single()
        assertEquals(phone.id, retryLaunch.display.id)
        assertTrue(retryLaunch.sessionGeneration > failedEpoch)
        assertNull(coordinator.snapshot()["error"])
        assertEquals("starting", coordinator.snapshot()["phase"])
        attach()
        coordinator.sessionStarted(retryLaunch.sessionGeneration, Result.success(Unit))
        assertTrue(retry.single().isSuccess)
    }

    @Test
    fun reentrantRetryCannotReceiveThePreviousAttemptsResult() {
        val first = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            request = openRequest(tnt, startSession = true),
            startService = {},
            completion = first::add,
        )
        val failedEpoch = host.launches.single().sessionGeneration
        attach()
        val retry = mutableListOf<Result<Unit>>()
        var retried = false
        coordinator.subscribe { state ->
            if (!retried && (state["error"] as? Map<*, *>)?.get("code") == "SESSION_STOPPED") {
                retried = true
                coordinator.requestOpen(
                    request = openRequest(tnt, startSession = true),
                    startService = {},
                    completion = retry::add,
                )
            }
        }
        val failure = IllegalStateException("Linux runtime exited")

        coordinator.sessionStarted(failedEpoch, Result.failure(failure))

        assertSame(failure, first.single().exceptionOrNull())
        assertTrue(retried)
        assertTrue(retry.isEmpty())
        val retryEpoch = coordinator.sessionGeneration
        assertTrue(retryEpoch > failedEpoch)

        coordinator.sessionStarted(
            failedEpoch,
            Result.failure(IllegalStateException("duplicate old completion")),
        )
        assertTrue(retry.isEmpty())

        coordinator.sessionStarted(retryEpoch, Result.success(Unit))
        assertEquals(1, retry.size)
        assertTrue(retry.single().isSuccess)
    }

    @Test
    fun reentrantRetryCannotChangeTheFailureDeliveredToLaterWaiters() {
        val first = mutableListOf<Result<Unit>>()
        val second = mutableListOf<Result<Unit>>()
        val retry = mutableListOf<Result<Unit>>()
        var retried = false
        val request = openRequest(tnt, startSession = true)
        coordinator.requestOpen(
            request = request,
            startService = {},
            completion = { result ->
                first += result
                if (!retried) {
                    retried = true
                    coordinator.requestOpen(request, {}, retry::add)
                }
            },
        )
        val failedEpoch = host.launches.single().sessionGeneration
        attach()
        coordinator.requestOpen(request, {}, second::add)
        val failure = IllegalStateException("Linux runtime exited")

        coordinator.sessionStarted(failedEpoch, Result.failure(failure))

        assertSame(failure, first.single().exceptionOrNull())
        assertSame(failure, second.single().exceptionOrNull())
        assertTrue(retry.isEmpty())
        coordinator.sessionStarted(coordinator.sessionGeneration, Result.success(Unit))
        assertTrue(retry.single().isSuccess)
    }

    @Test
    fun stopFromALaterOldWaiterStillCancelsTheReentrantRetry() {
        val request = openRequest(tnt, startSession = true)
        val retry = mutableListOf<Result<Unit>>()
        var retried = false
        coordinator.requestOpen(
            request,
            {},
            {
                if (!retried) {
                    retried = true
                    coordinator.requestOpen(request, {}, retry::add)
                }
            },
        )
        val failedEpoch = host.launches.single().sessionGeneration
        attach()
        coordinator.requestOpen(request, {}) {
            coordinator.stop()
        }

        coordinator.sessionStarted(
            failedEpoch,
            Result.failure(IllegalStateException("Linux runtime exited")),
        )

        assertTrue(retried)
        assertEquals(
            "SESSION_STOPPED",
            (retry.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun synchronousStopDuringFailedStopRecoveryCancelsTheOpen() {
        val localHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
        }
        val local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocalStrategy,
        )
        local.requestOpen(openRequest(tnt, startSession = false), {}, {})
        attach(local, localHost)
        local.startStopping()
        local.finishStopping(IllegalStateException("runtime refused to stop"))
        localHost.launches.clear()
        var stopped = false
        local.subscribe {
            if (!stopped && !local.isStopped && local.sessionFailure == null) {
                stopped = true
                local.stop()
            }
        }
        val outcomes = mutableListOf<Result<Unit>>()
        var serviceStarts = 0

        local.requestOpen(
            openRequest(tnt, startSession = false),
            { serviceStarts++ },
            outcomes::add,
        )

        assertTrue(stopped)
        assertTrue(local.isStopped)
        assertEquals(0, serviceStarts)
        assertTrue(localHost.launches.isEmpty())
        assertEquals(
            "SESSION_STOPPED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun reentrantDuplicateCannotStealPlacementFromThePreparedOwner() {
        val request = openRequest(tnt, startSession = true)
        val first = mutableListOf<Result<Unit>>()
        val duplicate = mutableListOf<Result<Unit>>()
        var firstServiceStarts = 0
        var duplicateServiceStarts = 0
        var duplicated = false
        coordinator.subscribe {
            if (!duplicated && coordinator.isStarting) {
                duplicated = true
                coordinator.requestOpen(
                    request,
                    { duplicateServiceStarts++ },
                    duplicate::add,
                )
            }
        }

        coordinator.requestOpen(
            request,
            { firstServiceStarts++ },
            first::add,
        )

        assertTrue(duplicated)
        assertEquals(1, firstServiceStarts)
        assertEquals(0, duplicateServiceStarts)
        assertEquals(1, host.launches.size)
        assertTrue(first.isEmpty())
        assertTrue(duplicate.isEmpty())
        val launch = host.launches.single()
        attach()
        coordinator.sessionStarted(launch.sessionGeneration, Result.success(Unit))
        assertTrue(first.single().isSuccess)
        assertTrue(duplicate.single().isSuccess)
    }

    @Test
    fun duplicateDuringPendingPlacementJoinsThePreparedOwner() {
        val request = openRequest(tnt, startSession = true)
        val first = mutableListOf<Result<Unit>>()
        val duplicate = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(request, {}, first::add)
        coordinator.requestOpen(request, {}, duplicate::add)

        assertTrue(first.isEmpty())
        assertTrue(duplicate.isEmpty())
        val launch = host.launches.single()
        attach()
        coordinator.sessionStarted(launch.sessionGeneration, Result.success(Unit))
        assertTrue(first.single().isSuccess)
        assertTrue(duplicate.single().isSuccess)
    }

    @Test
    fun differentTargetCannotJoinThePreparedOwner() {
        val request = openRequest(tnt, startSession = true)
        val first = mutableListOf<Result<Unit>>()
        val different = mutableListOf<Result<Unit>>()
        var differentServiceStarts = 0
        var requested = false
        coordinator.subscribe {
            if (!requested && coordinator.isStarting) {
                requested = true
                coordinator.requestOpen(
                    openRequest(phone, startSession = true),
                    { differentServiceStarts++ },
                    different::add,
                )
            }
        }

        coordinator.requestOpen(request, {}, first::add)

        assertTrue(requested)
        assertEquals(0, differentServiceStarts)
        assertEquals(
            "INVALID_ARGUMENT",
            (different.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(tnt.id, host.launches.single().display.id)
        val launch = host.launches.single()
        attach()
        coordinator.sessionStarted(launch.sessionGeneration, Result.success(Unit))
        assertTrue(first.single().isSuccess)
    }

    @Test
    fun preparedJoinerReceivesTheOwnersPlacementFailureBeforeFallback() {
        val genericHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
            running = false
            oneShotLaunchFailure = DesktopDisplayException(
                "DISPLAY_NOT_ALLOWED",
                "Android denied the external launch",
            )
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val request = openRequest(tnt, startSession = true)
        val first = mutableListOf<Result<Unit>>()
        val duplicate = mutableListOf<Result<Unit>>()
        var duplicated = false
        generic.subscribe {
            if (!duplicated && generic.isStarting) {
                duplicated = true
                generic.requestOpen(request, {}, duplicate::add)
            }
        }

        generic.requestOpen(request, {}, first::add)

        assertEquals(listOf(tnt.id, phone.id), genericHost.launches.map { it.display.id })
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (first.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertSame(first.single().exceptionOrNull(), duplicate.single().exceptionOrNull())
        attach(generic, genericHost)
        generic.sessionStarted(genericHost.launches.last().sessionGeneration, Result.success(Unit))
        assertEquals(1, first.size)
        assertEquals(1, duplicate.size)
    }

    @Test
    fun sameDisplayRetryRestoresTargetBeforeTheNextEnvironmentChange() {
        val localHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
        }
        var stopCalls = 0
        val local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocalStrategy,
            stopForEnvironmentChange = { stopCalls++ },
        )
        val first = mutableListOf<Result<Unit>>()
        local.requestOpen(openRequest(tnt, startSession = true), {}, first::add)
        val failedEpoch = localHost.launches.single().sessionGeneration
        attach(local, localHost)
        local.sessionStarted(
            failedEpoch,
            Result.failure(IllegalStateException("Linux runtime exited")),
        )
        assertTrue(first.single().isFailure)
        val retry = mutableListOf<Result<Unit>>()

        local.requestOpen(openRequest(tnt, startSession = true), {}, retry::add)
        val retryEpoch = local.sessionGeneration
        local.sessionStarted(retryEpoch, Result.success(Unit))

        assertTrue(retry.single().isSuccess)
        assertEquals(tnt.id, local.snapshot()["sessionDisplayId"])
        assertEquals("tnt", local.snapshot()["sessionEnvironment"])
        localHost.currentDisplay = phone.id
        local.requestOpen(openRequest(phone, startSession = false), {}, {})
        assertEquals(1, stopCalls)
        assertTrue(local.isRestarting)
    }

    @Test
    fun changedPayloadRetryRelaunchesTheFocusedDisplayOwner() {
        val first = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            openRequest(
                tnt,
                startSession = true,
                mode = "proot",
                desktopEnv = "xfce4",
            ),
            {},
            first::add,
        )
        val failedLaunch = host.launches.single()
        attach()
        coordinator.sessionStarted(
            failedLaunch.sessionGeneration,
            Result.failure(IllegalStateException("Linux runtime exited")),
        )
        val retry = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            openRequest(
                tnt,
                startSession = true,
                mode = "chroot",
                desktopEnv = "plasma",
            ),
            {},
            retry::add,
        )

        assertEquals(2, host.launches.size)
        assertEquals("chroot", host.launches.last().mode)
        assertEquals("plasma", host.launches.last().desktopEnv)
        assertTrue(retry.isEmpty())
    }

    @Test
    fun restartRuntimeFailureIsClearedByTheNextSuccessfulRetry() {
        val localHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = phone.id
        }
        val stopCompletions = mutableListOf<(Result<Unit>) -> Unit>()
        lateinit var local: DesktopDisplayCoordinator
        local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocalStrategy,
            stopForEnvironmentChange = { completion ->
                local.stop(preserveRestart = true)
                localHost.running = false
                stopCompletions += completion
            },
        )
        local.requestOpen(openRequest(phone, startSession = false), {}, {})
        attach(local, localHost)
        localHost.currentDisplay = tnt.id
        val failed = mutableListOf<Result<Unit>>()
        local.requestOpen(openRequest(tnt, startSession = true), {}, failed::add)
        stopCompletions.removeAt(0)(Result.success(Unit))
        val failedLaunch = localHost.launches.last()
        attach(local, localHost)
        val retry = mutableListOf<Result<Unit>>()
        var retried = false
        local.subscribe {
            if (!retried && !local.isRestarting && local.sessionFailure != null) {
                retried = true
                local.requestOpen(openRequest(tnt, startSession = true), {}, retry::add)
            }
        }
        val failure = IllegalStateException("Linux runtime exited")
        local.sessionStarted(failedLaunch.sessionGeneration, Result.failure(failure))
        assertSame(failure, failed.single().exceptionOrNull())
        assertTrue(retried)
        assertTrue(stopCompletions.isEmpty())
        local.sessionStarted(local.sessionGeneration, Result.success(Unit))

        assertTrue(retry.single().isSuccess)
        assertNull(local.snapshot()["error"])
        assertEquals("attached", local.snapshot()["phase"])
    }

    @Test
    fun requestOpenRetriesAnEnvironmentRestartAfterFailedStop() {
        val localHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
        }
        var stopCalls = 0
        val local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocalStrategy,
            stopForEnvironmentChange = { stopCalls++ },
        )
        local.requestOpen(openRequest(tnt, startSession = false), {}, {})
        attach(local, localHost)
        local.startStopping()
        local.finishStopping(IllegalStateException("runtime refused to stop"))
        localHost.currentDisplay = phone.id
        val outcomes = mutableListOf<Result<Unit>>()

        local.requestOpen(openRequest(phone, startSession = false), {}, outcomes::add)

        assertEquals(1, stopCalls)
        assertTrue(local.isRestarting)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun requestOpenRecoversAnExpectedEpochAfterFailedStopWithoutRestart() {
        val localHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
        }
        val local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = displayLocalStrategy,
        )
        local.requestOpen(openRequest(tnt, startSession = false), {}, {})
        attach(local, localHost)
        local.startStopping()
        local.finishStopping(IllegalStateException("runtime refused to stop"))
        val failedStopEpoch = local.sessionGeneration
        val outcomes = mutableListOf<Result<Unit>>()
        var serviceStarts = 0

        local.requestOpen(
            openRequest(tnt, startSession = false, expectedEpoch = failedStopEpoch),
            { serviceStarts++ },
            outcomes::add,
        )

        assertEquals(1, serviceStarts)
        assertTrue(outcomes.isEmpty())
        attach(local, localHost)
        assertTrue(outcomes.single().isSuccess)
    }

    @Test
    fun terminalPlacementFailureClearsTheOwnedStartupAttempt() {
        host.running = false
        host.launchFailure = DesktopDisplayException(
            "DISPLAY_NOT_ALLOWED",
            "Android denied this launch",
        )
        val outcomes = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(tnt, startSession = true),
            startService = {},
            completion = outcomes::add,
        )

        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertFalse(coordinator.isStarting)
        assertNull(coordinator.snapshot()["sessionEnvironment"])
        assertNull(coordinator.snapshot()["sessionDisplayId"])
        coordinator.replacePlacementStrategy(movableStrategy())
        assertEquals(PlacementMode.MOVABLE, coordinator.placementSelection.mode)
    }

    @Test
    fun terminalDisplayLocalFailureRestoresThePreviousTargetForDirectRetry() {
        host.running = false
        host.launchFailure = DesktopDisplayException(
            "DISPLAY_NOT_ALLOWED",
            "Android denied this launch",
        )
        val first = mutableListOf<Result<Unit>>()
        coordinator.requestOpen(
            request = openRequest(tnt, startSession = true),
            startService = {},
            completion = first::add,
        )
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (first.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        host.launchFailure = null
        host.launches.clear()
        val retry = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(phone, startSession = true),
            startService = {},
            completion = retry::add,
        )

        val launch = host.launches.single()
        assertEquals(phone.id, launch.display.id)
        assertTrue(retry.isEmpty())
        attach()
        coordinator.sessionStarted(launch.sessionGeneration, Result.success(Unit))
        assertTrue(retry.single().isSuccess)
    }

    @Test
    fun fallbackPlacementRetainsTheOwnedStartupAttempt() {
        val genericHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
            running = false
            oneShotLaunchFailure = DesktopDisplayException(
                "DISPLAY_NOT_ALLOWED",
                "Android denied the external launch",
            )
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val outcomes = mutableListOf<Result<Unit>>()

        generic.requestOpen(
            request = openRequest(tnt, startSession = true),
            startService = {},
            completion = outcomes::add,
        )

        assertEquals(listOf(tnt.id, phone.id), genericHost.launches.map { it.display.id })
        assertEquals(1, outcomes.size)
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertTrue(generic.isStarting)
        attach(generic, genericHost)
        generic.sessionStarted(genericHost.launches.last().sessionGeneration, Result.success(Unit))
        assertFalse(generic.isStarting)
    }

    @Test
    fun terminalFallbackFailureClearsTheOwnedStartupAttemptExactlyOnce() {
        val genericHost = LocalStartupHost(listOf(phone, tnt)).apply {
            currentDisplay = tnt.id
            running = false
            oneShotLaunchFailure = DesktopDisplayException(
                "DISPLAY_NOT_ALLOWED",
                "Android denied the external launch",
            )
        }
        val generic = DesktopDisplayCoordinator(
            host = genericHost,
            placementStrategy = movableStrategy(),
        )
        val outcomes = mutableListOf<Result<Unit>>()

        generic.requestOpen(
            request = openRequest(tnt, startSession = true),
            startService = {},
            completion = outcomes::add,
        )
        assertEquals(listOf(tnt.id, phone.id), genericHost.launches.map { it.display.id })
        assertTrue(generic.isStarting)

        genericHost.fireTimers()

        assertFalse(generic.isStarting)
        assertEquals(1, outcomes.size)
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (outcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        generic.replacePlacementStrategy(displayLocalStrategy())
        assertTrue(generic.isDisplayLocal)
    }

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

    private fun target(display: DesktopDisplay) = DesktopStartupTarget(
        display.id, display.key, displayLocalStrategy.environmentFor(display),
    )

    private fun openRequest(
        display: DesktopDisplay,
        startSession: Boolean,
        expectedEpoch: Long? = null,
        mode: String = "termux",
        desktopEnv: String = "xfce4",
    ) = DesktopOpenRequest(
        currentDisplayId = display.id,
        mode = mode,
        desktopEnv = desktopEnv,
        startSession = startSession,
        expectedEpoch = expectedEpoch,
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
    var oneShotLaunchFailure: DesktopDisplayException? = null
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
        oneShotLaunchFailure?.let {
            oneShotLaunchFailure = null
            throw it
        }
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
