package com.orailnoor.droiddesk.service

import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DesktopDisplayHost
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidDeskServiceTest {
    @Test
    fun returnNotificationUsesSnapshotCapabilitiesInsteadOfPlacementMode() {
        val returnSpec = DesktopReturnNotificationSpec(
            contentTitle = "Linux session preserved",
            actionLabel = "Return to phone",
            action = MainActivity.ACTION_RETURN_TO_PHONE,
        )
        val openSpec = DesktopReturnNotificationSpec(
            contentTitle = "Desktop placement needs attention",
            actionLabel = "Open",
            action = MainActivity.ACTION_OPEN_DESKTOP,
        )

        assertEquals(
            returnSpec,
            desktopReturnNotificationSpec(
                snapshot = mapOf(
                    "crossDisplayMovesAllowed" to true,
                    "sessionRunning" to true,
                ),
                runtimeRunning = false,
            ),
        )
        assertEquals(
            returnSpec,
            desktopReturnNotificationSpec(
                snapshot = mapOf(
                    "crossDisplayMovesAllowed" to true,
                    "sessionRunning" to false,
                ),
                runtimeRunning = true,
            ),
        )
        assertEquals(
            openSpec,
            desktopReturnNotificationSpec(
                snapshot = mapOf(
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to true,
                ),
                runtimeRunning = true,
            ),
        )
        assertEquals(
            openSpec,
            desktopReturnNotificationSpec(
                snapshot = mapOf(
                    "crossDisplayMovesAllowed" to true,
                    "sessionRunning" to false,
                ),
                runtimeRunning = false,
            ),
        )
    }

    @Test
    fun returnNotificationReportsRuntimeStatusFailureAndUsesSnapshot() {
        val failure = IllegalStateException("unknown persisted environment")
        val reported = mutableListOf<RuntimeException>()

        val running = notificationRuntimeRunning(
            snapshot = mapOf("sessionRunning" to true),
            readRuntimeRunning = { throw failure },
            onFailure = reported::add,
        )

        assertTrue(running)
        assertEquals(listOf(failure), reported)
    }

    @Test
    fun synchronousServiceStartFailureIsDeliveredOnce() {
        val host = FailureHost()
        val coordinator = DesktopDisplayCoordinator(host = host)
        val messages = mutableListOf<String>()
        val notification = DesktopStartupNotification(coordinator, messages::add)
        val outcomes = mutableListOf<Result<Unit>>()

        coordinator.requestOpen(
            request = openRequest(host.phone),
            startService = { throw SecurityException("background start denied") },
            completion = outcomes::add,
        )
        notification.close()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isFailure)
        assertEquals(1, messages.count { it.contains("Android denied starting the Linux service") })
    }

    @Test
    fun synchronousPlacementFailureIsDeliveredOnce() {
        val host = FailureHost().apply {
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
            request = openRequest(host.phone),
            startService = {},
            completion = outcomes::add,
        )
        notification.close()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isFailure)
        assertEquals(1, messages.count { it.contains("Android denied placement") })
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

    private fun openRequest(display: DesktopDisplay) = DesktopOpenRequest(
        currentDisplayId = display.id,
        mode = "proot",
        desktopEnv = "xfce4",
        startSession = true,
        expectedEpoch = null,
    )
}

private class FailureHost : DesktopDisplayHost {
    val phone = DesktopDisplay(0, "phone", "Phone", true, true, null)
    var launchFailure: DesktopDisplayException? = null

    override fun currentDisplay() = phone
    override fun displays() = listOf(phone)
    override fun defaultProfileKind() = ProfileKind.PHONE
    override fun sessionRunning() = false
    override fun launch(request: DesktopLaunchRequest) {
        launchFailure?.let { throw it }
    }
    override fun focus(requestId: Long) = false
    override fun cancelLaunch(requestId: Long) = Unit
    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
    override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
}
