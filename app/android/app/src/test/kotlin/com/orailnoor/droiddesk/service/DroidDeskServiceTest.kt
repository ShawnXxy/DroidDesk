package com.orailnoor.droiddesk.service

import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DesktopDisplayHost
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val failure = IOException("desktop marker disappeared")
        val reported = mutableListOf<Exception>()

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
    fun serviceStartGateCoalescesDuplicateStartsRetriesAfterFailureAndResets() {
        val gate = ServiceStartGate()
        var attempts = 0
        var platformStarts = 0

        val securityDenied = runCatching {
            gate.start {
                attempts++
                throw SecurityException("background start denied")
            }
        }
        assertTrue(securityDenied.exceptionOrNull() is SecurityException)
        assertEquals(1, attempts)
        assertEquals(0, platformStarts)

        val runtimeDenied = runCatching {
            gate.start {
                attempts++
                throw IllegalStateException("background start failed")
            }
        }
        assertTrue(runtimeDenied.exceptionOrNull() is IllegalStateException)
        assertEquals(2, attempts)
        assertEquals(0, platformStarts)

        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val concurrentStarts = AtomicInteger(0)
        val workers = (1..2).map {
            thread {
                ready.countDown()
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                gate.start {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    concurrentStarts.incrementAndGet()
                }
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        workers.forEach { it.join(5_000) }
        assertEquals(1, concurrentStarts.get())

        gate.start { platformStarts++ }
        assertEquals(0, platformStarts)

        gate.reset()
        gate.start { platformStarts++ }
        assertEquals(1, platformStarts)
    }

    @Test
    fun provisioningAndSubsequentRequestOpenDoNotDuplicatePlatformServiceStart() {
        val gate = ServiceStartGate()
        val host = FailureHost()
        val coordinator = DesktopDisplayCoordinator(host = host)
        val published = mutableListOf<String?>()
        val state = ProvisioningNotificationState(published::add)
        var platformStarts = 0

        val epoch = coordinator.beginSession()
        state.start(epoch, "Preparing Linux desktop", coordinator::isSessionCurrent) {
            gate.start { platformStarts++ }
        }
        state.progress(epoch, "Installing XFCE", coordinator::isSessionCurrent)
        state.complete(epoch, coordinator::isSessionCurrent)

        coordinator.requestOpen(
            request = DesktopOpenRequest(
                currentDisplayId = host.phone.id,
                mode = "chroot",
                desktopEnv = "xfce4",
                startSession = true,
                expectedEpoch = epoch,
            ),
            startService = { gate.start { platformStarts++ } },
            completion = {},
        )

        assertEquals(1, platformStarts)
        assertEquals(
            listOf("Preparing Linux desktop", "Installing XFCE", null),
            published,
        )
    }

    @Test
    fun beginProvisioningServiceFailureIsReportedAndRethrownOnceWithTruthfulState() {
        val gate = ServiceStartGate()
        val host = FailureHost()
        val coordinator = DesktopDisplayCoordinator(host = host)
        val published = mutableListOf<String?>()
        val state = ProvisioningNotificationState(published::add)
        val denial = SecurityException("background start denied")

        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val thrown = runCatching {
            state.start(epoch, "Preparing Linux desktop", coordinator::isSessionCurrent) {
                gate.start { throw denial }
            }
        }.exceptionOrNull()

        assertSame(denial, thrown)
        state.fail(epoch, denial, coordinator::isSessionCurrent)
        assertEquals(
            listOf("Linux desktop failed to start: Android denied starting the Linux service"),
            published,
        )
        assertEquals(
            "Linux desktop failed to start: Android denied starting the Linux service",
            state.activeText(coordinator.sessionGeneration, coordinator.isStopped),
        )

        var retries = 0
        gate.start { retries++ }
        assertEquals(1, retries)

        coordinator.stop()
        val stoppedAttempt = runCatching {
            state.start(epoch, "Preparing Linux desktop", coordinator::isSessionCurrent) {
                error("stopped epoch must not start service")
            }
        }.exceptionOrNull()
        assertEquals(
            "SESSION_STOPPED",
            (stoppedAttempt as DesktopDisplayException).code,
        )
        assertNull(state.text)

        val stoppedDuringStartEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val stoppedMidStart = runCatching {
            state.start(stoppedDuringStartEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent) {
                coordinator.stop()
            }
        }.exceptionOrNull()
        assertEquals(
            "SESSION_STOPPED",
            (stoppedMidStart as DesktopDisplayException).code,
        )
        assertNull(state.text)

        val genericFailureEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val bareRuntimeFailure = IllegalStateException()
        val genericThrown = runCatching {
            state.start(genericFailureEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent) {
                throw bareRuntimeFailure
            }
        }.exceptionOrNull()
        assertSame(bareRuntimeFailure, genericThrown)
        assertEquals(
            "Linux desktop failed to start: Android could not start the Linux service",
            state.activeText(coordinator.sessionGeneration, coordinator.isStopped),
        )
    }

    @Test
    fun provisioningNotificationDedupesStatesAndIgnoresBlankAndStaleEpochs() {
        val published = mutableListOf<String?>()
        val state = ProvisioningNotificationState(published::add)
        val failure = IOException("rootfs download failed")

        state.start(71, "Preparing Linux desktop")
        state.start(71, "Preparing Linux desktop")
        state.progress(71, "Downloading rootfs")
        state.progress(71, "Downloading rootfs")
        state.progress(71, "   ")
        state.progress(71, "")
        state.progress(70, "stale progress")
        state.complete(70)
        state.fail(70, IllegalStateException("stale"))
        state.fail(71, failure)
        state.fail(71, failure)

        assertEquals(
            listOf(
                "Preparing Linux desktop",
                "Downloading rootfs",
                "Linux desktop failed to start: rootfs download failed",
            ),
            published,
        )
        assertEquals("Linux desktop failed to start: rootfs download failed", state.text)
    }

    @Test
    fun provisioningNotificationClearsOnSuccessStopCancelAndCoordinatorTakeover() {
        val host = FailureHost()
        val coordinator = DesktopDisplayCoordinator(host = host)
        val startupMessages = mutableListOf<String>()
        val startupNotification = DesktopStartupNotification(coordinator, startupMessages::add)
        val published = mutableListOf<String?>()
        val state = ProvisioningNotificationState(published::add)

        state.start(81, "Preparing Linux desktop")
        state.complete(81)
        state.progress(81, "stale progress")

        state.start(82, "Preparing cancelled desktop")
        state.fail(82, DesktopDisplayException("SESSION_STOPPED", "session stopped"))

        state.start(83, "Preparing cancelled coroutine")
        state.fail(83, CancellationException("cancelled"))

        state.start(84, "Preparing stopped desktop")
        state.fail(84, IOException("broken pipe"), isSessionCurrent = { false })

        val failedEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        state.start(failedEpoch, "Preparing failing desktop", coordinator::isSessionCurrent)
        state.fail(failedEpoch, IOException("apt failed"), coordinator::isSessionCurrent)
        assertEquals(
            "Linux desktop failed to start: apt failed",
            state.activeText(coordinator.sessionGeneration, coordinator.isStopped),
        )

        val nextEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        assertNull(state.activeText(coordinator.sessionGeneration, coordinator.isStopped))
        assertEquals(
            "Linux desktop is starting",
            state.activeText(coordinator.sessionGeneration, coordinator.isStopped)
                ?: startupNotification.text,
        )

        state.start(nextEpoch, "Preparing next desktop", coordinator::isSessionCurrent)
        val takeoverEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        state.start(takeoverEpoch, "Preparing takeover desktop", coordinator::isSessionCurrent)

        coordinator.stop()
        assertNull(state.activeText(coordinator.sessionGeneration, coordinator.isStopped))
        assertEquals(
            "Linux desktop is stopped",
            state.activeText(coordinator.sessionGeneration, coordinator.isStopped)
                ?: startupNotification.text,
        )

        state.start(99, "Preparing manual clear")
        state.clear()
        state.clear()
        startupNotification.close()

        assertEquals(
            listOf(
                "Preparing Linux desktop",
                null,
                "Preparing cancelled desktop",
                null,
                "Preparing cancelled coroutine",
                null,
                "Preparing stopped desktop",
                null,
                "Preparing failing desktop",
                "Linux desktop failed to start: apt failed",
                null,
                "Preparing next desktop",
                "Preparing takeover desktop",
                null,
                "Preparing manual clear",
                null,
            ),
            published,
        )
        assertNull(state.text)
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
