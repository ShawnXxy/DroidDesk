package com.orailnoor.droiddesk.service

import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DesktopDisplayHost
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RendererClaim
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidDeskServiceTest {
    @Test
    fun returnNotificationUsesCoordinatorCapabilitiesInsteadOfPlacementMode() {
        assertEquals(
            DesktopReturnNotificationSpec(
                contentTitle = "Linux session preserved",
                actionLabel = "Return to phone",
                action = MainActivity.ACTION_RETURN_TO_PHONE,
            ),
            desktopReturnNotificationSpec(
                snapshot = mapOf(
                    "crossDisplayMovesAllowed" to true,
                    "sessionRunning" to true,
                    "sessionHeadless" to false,
                ),
                runtimeRunning = true,
            ),
        )
        assertEquals(
            DesktopReturnNotificationSpec(
                contentTitle = "Desktop placement needs attention",
                actionLabel = "Open",
                action = MainActivity.ACTION_OPEN_DESKTOP,
            ),
            desktopReturnNotificationSpec(
                snapshot = mapOf(
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to true,
                    "sessionHeadless" to true,
                ),
                runtimeRunning = true,
            ),
        )
    }

    @Test
    fun returnNotificationFallsBackToSnapshotWhenRuntimeStatusFails() {
        val failure = IllegalStateException("unknown persisted environment")
        var reported: RuntimeException? = null

        val running = notificationRuntimeRunning(
            snapshot = mapOf("sessionRunning" to true),
            readRuntimeRunning = { throw failure },
            onFailure = { reported = it },
        )

        assertTrue(running)
        assertSame(failure, reported)
    }

    @Test
    fun serviceStartGateAllowsRetryAfterDenial() {
        val gate = ServiceStartGate()
        val denial = SecurityException("background start denied")
        var attempts = 0

        val failure = runCatching {
            gate.start {
                attempts++
                throw denial
            }
        }.exceptionOrNull()
        gate.start { attempts++ }

        assertSame(denial, failure)
        assertEquals(2, attempts)
    }

    @Test
    fun serviceStartDenialPublishesOneFailureAndNeverClaimsRunning() {
        val host = NotificationHost()
        val coordinator = DesktopDisplayCoordinator(host = host)
        val messages = mutableListOf<String>()
        val notification = DesktopStartupNotification(coordinator, messages::add)
        val outcomes = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(host.phone, startSession = true),
            startService = { throw SecurityException("background start denied") },
            completion = outcomes::add,
        )
        notification.close()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isFailure)
        assertEquals(1, messages.count { it.contains("Android denied starting the Linux service") })
    }

    @Test
    fun placementFailurePublishesOneFailureAndNeverClaimsRunning() {
        val host = NotificationHost().apply {
            launchFailure = DesktopDisplayException(
                "DISPLAY_NOT_ALLOWED",
                "Android denied placement",
            )
        }
        val coordinator = DesktopDisplayCoordinator(host = host)
        val messages = mutableListOf<String>()
        val notification = DesktopStartupNotification(coordinator, messages::add)
        val outcomes = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(host.phone, startSession = true),
            startService = {},
            completion = outcomes::add,
        )
        notification.close()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isFailure)
        assertEquals(1, messages.count { it.contains("Android denied placement") })
    }

    @Test
    fun restartFailurePublishesOneFailureWithoutAFalseRunningState() {
        val host = NotificationHost().apply { running = true }
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = displayLocalStrategy(host.tnt.id),
            stopForEnvironmentChange = { completion ->
                completion(Result.failure(IllegalStateException("environment stop rejected")))
            },
        )
        coordinator.requestOpen(openRequest(host.phone, startSession = false), {}, {})
        attach(coordinator, host)
        host.current = host.tnt
        val messages = mutableListOf<String>()
        val notification = DesktopStartupNotification(coordinator, messages::add)
        val outcomes = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(host.tnt, startSession = true),
            startService = {},
            completion = outcomes::add,
        )
        notification.close()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isFailure)
        assertEquals(1, messages.count { it.contains("environment stop rejected") })
    }

    @Test
    fun rejectedStopAdmissionDoesNotChangeCoordinatorState() {
        val calls = mutableListOf<String>()
        val rejection = IllegalStateException("Package operation is active")

        val result = prepareDesktopStop(
            acquireAdmission = {
                calls += "admission"
                throw rejection
            },
            startStopping = { calls += "coordinator" },
        )

        assertSame(rejection, result.exceptionOrNull())
        assertEquals(listOf("admission"), calls)
    }

    @Test
    fun admittedStopChangesCoordinatorAfterAdmission() {
        val calls = mutableListOf<String>()
        val admission = AutoCloseable { calls += "release" }

        val result = prepareDesktopStop(
            acquireAdmission = {
                calls += "admission"
                admission
            },
            startStopping = { calls += "coordinator" },
        )

        assertSame(admission, result.getOrThrow())
        assertEquals(listOf("admission", "coordinator"), calls)
        result.getOrThrow().close()
        assertEquals(listOf("admission", "coordinator", "release"), calls)
    }

    @Test
    fun rejectedEnvironmentStopLeavesBridgeAndDisplayRunning() {
        val calls = mutableListOf<String>()
        val rejection = IllegalStateException("Package operation is active")

        val failure = performDesktopTeardown(
            stopEnvironment = {
                calls += "environment"
                throw rejection
            },
            stopLegacyBridge = { calls += "bridge" },
            stopDisplayService = { calls += "display" },
            onFailure = { _, _ -> },
        )

        assertSame(rejection, failure)
        assertEquals(listOf("environment"), calls)
    }

    @Test
    fun admittedEnvironmentStopRunsAllRemainingCleanup() {
        val calls = mutableListOf<String>()
        val bridgeFailure = IllegalStateException("Bridge cleanup failed")

        val failure = performDesktopTeardown(
            stopEnvironment = { calls += "environment" },
            stopLegacyBridge = {
                calls += "bridge"
                throw bridgeFailure
            },
            stopDisplayService = { calls += "display" },
            onFailure = { _, _ -> },
        )

        assertSame(bridgeFailure, failure)
        assertEquals(listOf("environment", "bridge", "display"), calls)
    }

    private fun openRequest(
        display: DesktopDisplay,
        startSession: Boolean,
    ) = DesktopOpenRequest(
        currentDisplayId = display.id,
        mode = "proot",
        desktopEnv = "xfce4",
        startSession = startSession,
        expectedEpoch = null,
    )

    private fun attach(
        coordinator: DesktopDisplayCoordinator,
        host: NotificationHost,
    ) {
        val request = host.launches.last()
        val lease = (coordinator.claim(request.display.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(lease, request.requestId) {}
        coordinator.onAttached(lease, request.requestId, PixelSize(1920, 1080))
    }

    private fun displayLocalStrategy(tntDisplayId: Int) = DisplayLocalRestartAdapter(
        PlacementSelection(
            mode = PlacementMode.DISPLAY_LOCAL,
            source = PlacementSelectionSource.DEVICE_QUIRK,
            allowAutomaticMoves = false,
            reason = "service notification test",
            tntDisplayIds = setOf(tntDisplayId),
        ),
    )
}

private class NotificationHost : DesktopDisplayHost {
    val phone = DesktopDisplay(0, "phone", "Phone", true, true, null)
    val tnt = DesktopDisplay(73, "tnt", "TNT", false, true, null)
    val launches = mutableListOf<DesktopLaunchRequest>()
    var current = phone
    var running = false
    var launchFailure: DesktopDisplayException? = null

    override fun currentDisplay() = current
    override fun displays() = listOf(phone, tnt)
    override fun defaultProfileKind() = ProfileKind.PHONE
    override fun sessionRunning() = running
    override fun launch(request: DesktopLaunchRequest) {
        launchFailure?.let { throw it }
        launches += request
    }
    override fun focus(requestId: Long) = false
    override fun cancelLaunch(requestId: Long) = Unit
    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
    override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
}
