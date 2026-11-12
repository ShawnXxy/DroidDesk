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
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import com.orailnoor.droiddesk.display.placement.MovableSecondaryDisplayAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
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
        val countBeforeGeneric = published.size
        val genericThrown = runCatching {
            state.start(genericFailureEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent) {
                throw bareRuntimeFailure
            }
        }.exceptionOrNull()
        assertSame(bareRuntimeFailure, genericThrown)
        state.fail(genericFailureEpoch, bareRuntimeFailure, coordinator::isSessionCurrent)
        state.fail(genericFailureEpoch, IOException("secondary error"), coordinator::isSessionCurrent)
        assertEquals(
            listOf("Linux desktop failed to start: Android could not start the Linux service"),
            published.drop(countBeforeGeneric),
        )
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
        state.progress(71, "Late worker progress after timeout")
        state.complete(71)

        assertEquals(
            listOf(
                "Preparing Linux desktop",
                "Downloading rootfs",
                "Linux desktop failed to start: rootfs download failed",
            ),
            published,
        )
        assertEquals("Linux desktop failed to start: rootfs download failed", state.text)

        state.start(71, "Retrying Linux desktop")
        state.progress(71, "Extracting rootfs")
        assertEquals("Extracting rootfs", state.text)
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
    fun queuedProvisioningRefreshDoesNotOverwriteRecoveryNotification() {
        val tracker = ServiceNotificationTracker()
        val queuedRefreshes = ArrayDeque<Long>()
        val state = ProvisioningNotificationState {
            queuedRefreshes.addLast(tracker.currentSequence())
        }
        val rendered = mutableListOf<String>()

        fun drainQueuedRefreshes(fallbackText: String) {
            while (queuedRefreshes.isNotEmpty()) {
                val seq = queuedRefreshes.removeFirst()
                tracker.nextUpdate(state.text, fallbackText, seq)?.let { rendered += it }
            }
        }

        state.start(91, "Preparing Linux desktop")
        assertEquals(false, tracker.isForeground())
        tracker.onStartForeground(checkNotNull(state.text))
        assertTrue(tracker.isForeground())
        rendered += checkNotNull(state.text)
        drainQueuedRefreshes("Linux desktop is starting")
        assertEquals(listOf("Preparing Linux desktop"), rendered)

        state.progress(91, "Installing XFCE")
        drainQueuedRefreshes("Linux desktop is starting")
        assertEquals(listOf("Preparing Linux desktop", "Installing XFCE"), rendered)

        // completeProvisioning queues a refresh right before launchPreparedDesktop fails into recovery
        state.complete(91)
        assertEquals(1, queuedRefreshes.size)
        tracker.onRecoveryShown()
        rendered += "RECOVERY:Desktop placement needs attention"

        drainQueuedRefreshes("Linux desktop is starting")
        assertEquals(
            listOf(
                "Preparing Linux desktop",
                "Installing XFCE",
                "RECOVERY:Desktop placement needs attention",
            ),
            rendered,
        )

        // Even a refresh with the current sequence must not overwrite recovery while provisioning is idle
        assertNull(tracker.nextUpdate(null, "Linux desktop is starting", tracker.currentSequence()))

        // Direct coordinator transition or a new provisioning session takes back the notification
        assertEquals(
            "Linux desktop is starting",
            tracker.nextUpdate(null, "Linux desktop is starting"),
        )
        assertNull(tracker.nextUpdate(null, "Linux desktop is starting"))

        tracker.onRecoveryShown()
        state.start(92, "Preparing Linux desktop")
        drainQueuedRefreshes("Linux desktop is starting")
        assertEquals("Preparing Linux desktop", rendered.last())

        // Normal completion without recovery restores the coordinator notification once
        state.complete(92)
        drainQueuedRefreshes("Linux desktop is running")
        assertEquals("Linux desktop is running", rendered.last())
        assertNull(tracker.nextUpdate(null, "Linux desktop is running", tracker.currentSequence()))

        tracker.onStopForeground()
        assertEquals(false, tracker.isForeground())
        assertNull(tracker.nextUpdate(null, "Linux desktop is stopped"))
    }

    @Test
    fun successfulReopenInRunningMovableSessionReclaimsRecoveryNotification() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val external = DesktopDisplay(2, "hdmi:2", "External", isDefault = false, eligible = true, null)
        var running = false
        var lastLaunched: DesktopLaunchRequest? = null
        var failLaunch: DesktopDisplayException? = null
        val rendered = mutableListOf<String>()
        val gate = ServiceStartGate()
        val tracker = ServiceNotificationTracker()

        val host = object : DesktopDisplayHost {
            override fun currentDisplay() = phone
            override fun displays() = listOf(phone, external)
            override fun defaultProfileKind() = ProfileKind.PHONE
            override fun sessionRunning() = running
            override fun launch(request: DesktopLaunchRequest) {
                failLaunch?.let { throw it }
                lastLaunched = request
            }
            override fun focus(requestId: Long) = false
            override fun cancelLaunch(requestId: Long) = Unit
            override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
            override fun notifyReturnRequired(error: DesktopDisplayException) {
                tracker.onRecoveryShown()
                rendered += "RECOVERY:${error.message}"
            }
        }
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = MovableSecondaryDisplayAdapter(
                PlacementSelection(
                    mode = PlacementMode.MOVABLE,
                    source = PlacementSelectionSource.RUNTIME_EVIDENCE,
                    allowAutomaticMoves = false,
                    reason = "movable test",
                ),
            ),
        )
        var startupNotification: DesktopStartupNotification? = null
        startupNotification = DesktopStartupNotification(coordinator) { text ->
            tracker.nextUpdate(null, text)?.let { rendered += it }
        }
        val unsubscribe = coordinator.subscribe { state ->
            if (tracker.shouldReclaimRecovery(state)) {
                startupNotification?.let { notification ->
                    tracker.nextUpdate(null, notification.text)?.let { rendered += it }
                }
            }
        }

        // Initial startup on phone
        coordinator.requestOpen(
            request = DesktopOpenRequest(phone.id, "proot", "xfce4", startSession = true, expectedEpoch = null),
            startService = {
                gate.start {
                    val initial = startupNotification.text
                    tracker.onStartForeground(initial)
                    rendered += initial
                }
            },
            completion = {},
        )
        val initialLaunch = checkNotNull(lastLaunched)
        val initialLease = (coordinator.claim(phone.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(initialLease, initialLaunch.requestId, { phone.id }) {}
        coordinator.onAttached(initialLease, initialLaunch.requestId, PixelSize(1920, 1080))
        assertTrue(coordinator.consumeSessionStart(initialLease))
        val epoch = checkNotNull(coordinator.beginSessionStart(initialLease))
        running = true
        coordinator.sessionStarted(epoch, Result.success(Unit))
        assertEquals("Linux desktop is running", rendered.last())

        // Detach phone view and fail placement on external monitor (no fallback when recover=false on second failure)
        coordinator.release(initialLease)
        failLaunch = DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Android denied placement")
        coordinator.requestOpen(
            request = DesktopOpenRequest(phone.id, "proot", "xfce4", startSession = false, expectedEpoch = null),
            startService = { gate.start { error("gate must coalesce") } },
            completion = {},
        )
        assertEquals("RECOVERY:Android denied placement", rendered.last())

        // Unrelated display refresh must not clobber recovery while unattached
        coordinator.onDisplaysChanged()
        assertEquals("RECOVERY:Android denied placement", rendered.last())

        // Reopen on phone in already-running session (startSession = false, DesktopStartupNotification text unchanged)
        failLaunch = null
        coordinator.requestOpen(
            request = DesktopOpenRequest(phone.id, "proot", "xfce4", startSession = false, expectedEpoch = null),
            startService = { gate.start { error("gate must coalesce") } },
            completion = {},
        )
        // Still moving before attachment is confirmed — recovery stays visible
        assertEquals("RECOVERY:Android denied placement", rendered.last())

        val reopenLaunch = checkNotNull(lastLaunched)
        val reopenLease = (coordinator.claim(phone.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(reopenLease, reopenLaunch.requestId, { phone.id }) {}
        coordinator.onAttached(reopenLease, reopenLaunch.requestId, PixelSize(1920, 1080))

        assertEquals("Linux desktop is running", rendered.last())
        val countAfterReclaim = rendered.size
        coordinator.onDisplaysChanged()
        assertEquals(countAfterReclaim, rendered.size)

        // Move to external monitor, then fail a move back while external fallback also fails,
        // then unplug external so onDisplayRemoved triggers recovery=true attachment (state["error"] != null)
        var availableDisplays = listOf(phone, external)
        val fallbackHost = object : DesktopDisplayHost {
            override fun currentDisplay() = phone
            override fun displays() = availableDisplays
            override fun defaultProfileKind() = ProfileKind.PHONE
            override fun sessionRunning() = true
            override fun launch(request: DesktopLaunchRequest) {
                failLaunch?.let { throw it }
                lastLaunched = request
            }
            override fun focus(requestId: Long) = false
            override fun cancelLaunch(requestId: Long) = Unit
            override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
            override fun notifyReturnRequired(error: DesktopDisplayException) {
                tracker.onRecoveryShown()
                rendered += "RECOVERY:${error.message}"
            }
        }
        val fallbackCoordinator = DesktopDisplayCoordinator(
            host = fallbackHost,
            placementStrategy = MovableSecondaryDisplayAdapter(
                PlacementSelection(
                    mode = PlacementMode.MOVABLE,
                    source = PlacementSelectionSource.RUNTIME_EVIDENCE,
                    allowAutomaticMoves = false,
                    reason = "movable fallback test",
                ),
            ),
        )
        var fallbackStartup: DesktopStartupNotification? = null
        fallbackStartup = DesktopStartupNotification(fallbackCoordinator) { text ->
            tracker.nextUpdate(null, text)?.let { rendered += it }
        }
        val fallbackUnsubscribe = fallbackCoordinator.subscribe { state ->
            if (tracker.shouldReclaimRecovery(state)) {
                fallbackStartup?.let { notification ->
                    tracker.nextUpdate(null, notification.text)?.let { rendered += it }
                }
            }
        }
        fallbackCoordinator.open(external.id, external.key) {}
        val extLaunch = checkNotNull(lastLaunched)
        val extLease = (fallbackCoordinator.claim(external.id) {} as RendererClaim.Accepted).lease
        fallbackCoordinator.registerActivity(extLease, extLaunch.requestId, { external.id }) {}
        fallbackCoordinator.onAttached(extLease, extLaunch.requestId, PixelSize(1920, 1080))

        // Detach external lease and fail move to phone + fallback to external -> notifyReturnRequired with error != null
        fallbackCoordinator.release(extLease)
        failLaunch = DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Move and fallback denied")
        fallbackCoordinator.moveToPhone {}
        assertEquals("RECOVERY:Move and fallback denied", rendered.last())

        // Unplug external monitor -> onDisplayRemoved launches recovery=true onto phone (error stays non-null on attach)
        failLaunch = null
        availableDisplays = listOf(phone)
        fallbackCoordinator.onDisplayRemoved(external.id)
        val recoveryLaunch = checkNotNull(lastLaunched)
        val recoveryLease = (fallbackCoordinator.claim(phone.id) {} as RendererClaim.Accepted).lease
        fallbackCoordinator.registerActivity(recoveryLease, recoveryLaunch.requestId, { phone.id }) {}
        fallbackCoordinator.onAttached(recoveryLease, recoveryLaunch.requestId, PixelSize(1920, 1080))
        assertTrue(fallbackCoordinator.snapshot()["error"] != null)
        assertEquals("Linux desktop is running", rendered.last())

        fallbackUnsubscribe()
        fallbackStartup.close()
        unsubscribe()
        startupNotification.close()
    }

    @Test
    fun nonMovableStartupNotificationUsesCoordinatorDiagnosticsAcrossLifecycle() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt:100003", "TNT", isDefault = false, eligible = true, null)
        var current = tnt
        var running = false
        var sessionRunningReads = 0
        var lastLaunched: DesktopLaunchRequest? = null
        var pendingEnvStop: ((Result<Unit>) -> Unit)? = null
        val host = object : DesktopDisplayHost {
            override fun currentDisplay() = current
            override fun displays() = listOf(phone, tnt)
            override fun defaultProfileKind() = ProfileKind.PHONE
            override fun sessionRunning(): Boolean {
                sessionRunningReads++
                return running
            }
            override fun launch(request: DesktopLaunchRequest) {
                lastLaunched = request
            }
            override fun focus(requestId: Long) = false
            override fun cancelLaunch(requestId: Long) = Unit
            override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
            override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
        }
        val selection = PlacementSelection(
            mode = PlacementMode.DISPLAY_LOCAL,
            source = PlacementSelectionSource.DEVICE_QUIRK,
            allowAutomaticMoves = false,
            reason = "display-local notification test",
            tntDisplayIds = setOf(100003),
        )
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = DisplayLocalRestartAdapter(selection),
            stopForEnvironmentChange = { completion -> pendingEnvStop = completion },
        )
        val messages = mutableListOf<String>()
        val notification = DesktopStartupNotification(coordinator, messages::add)

        assertEquals("Linux desktop is not running", notification.text)

        // Cold-start service failure reports attention once on non-movable placement
        coordinator.requestOpen(
            request = DesktopOpenRequest(tnt.id, "proot", "xfce4", startSession = true, expectedEpoch = null),
            startService = { throw SecurityException("background start denied") },
            completion = {},
        )
        assertEquals(
            "Linux desktop needs attention: Android denied starting the Linux service",
            notification.text,
        )
        assertEquals(1, messages.count { it.contains("Android denied starting the Linux service") })

        // Start and attach on TNT: attached display-local session preserves coordinator restart warning
        // because DesktopActivity stays attached while paused if notification Open is tapped on phone
        coordinator.requestOpen(
            request = DesktopOpenRequest(tnt.id, "proot", "xfce4", startSession = true, expectedEpoch = null),
            startService = {},
            completion = {},
        )
        assertEquals("Linux desktop is starting", notification.text)
        val launch = checkNotNull(lastLaunched)
        val lease = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(lease, launch.requestId, { tnt.id }) {}
        coordinator.onAttached(lease, launch.requestId, PixelSize(1920, 1080))
        assertTrue(coordinator.consumeSessionStart(lease))
        val startEpoch = checkNotNull(coordinator.beginSessionStart(lease))
        var waiterObservedWarning = false
        coordinator.awaitSessionReady(startEpoch) {
            waiterObservedWarning = messages.lastOrNull() == coordinator.environmentRestartWarning
        }
        running = true
        val readsBeforeSessionStarted = sessionRunningReads
        coordinator.sessionStarted(startEpoch, Result.success(Unit))
        assertTrue(waiterObservedWarning)
        assertEquals(readsBeforeSessionStarted + 1, sessionRunningReads)
        val readsAfterSessionStarted = sessionRunningReads
        assertEquals(coordinator.environmentRestartWarning, notification.text)
        assertEquals(readsAfterSessionStarted, sessionRunningReads)

        // Geometry publication must reuse the coordinator's published snapshot without extra sessionRunning() probes
        val readsBeforeGeometry = sessionRunningReads
        coordinator.reportGeometry(lease, ProfileKind.MONITOR, PixelSize(1920, 1080))
        assertEquals(readsBeforeGeometry + 1, sessionRunningReads)

        // Detach renderer while Linux stays running in background -> keeps coordinator restart warning
        coordinator.release(lease)
        assertEquals(coordinator.environmentRestartWarning, notification.text)

        // Switching to phone triggers environment restart -> "Restarting Linux/XFCE on this display"
        current = phone
        coordinator.requestOpen(
            request = DesktopOpenRequest(phone.id, "proot", "xfce4", startSession = true, expectedEpoch = null),
            startService = {},
            completion = {},
        )
        assertEquals("Restarting Linux/XFCE on this display", notification.text)
        coordinator.startStopping(preserveRestart = true)
        running = false
        assertEquals("Restarting Linux/XFCE on this display", notification.text)
        coordinator.finishStopping(null)
        checkNotNull(pendingEnvStop).invoke(Result.success(Unit))
        assertEquals("Restarting Linux/XFCE on this display", notification.text)

        val restartLaunch = checkNotNull(lastLaunched)
        val phoneLease = (coordinator.claim(phone.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(phoneLease, restartLaunch.requestId, { phone.id }) {}
        coordinator.onAttached(phoneLease, restartLaunch.requestId, PixelSize(1080, 2400))
        assertTrue(coordinator.consumeSessionStart(phoneLease))
        val restartEpoch = checkNotNull(coordinator.beginSessionStart(phoneLease))
        running = true
        coordinator.sessionStarted(restartEpoch, Result.success(Unit))
        assertEquals(coordinator.environmentRestartWarning, notification.text)

        // Explicit user stop -> "Linux desktop is stopped"
        coordinator.stop()
        assertEquals("Linux desktop is stopped", notification.text)
        notification.close()
    }

    @Test
    fun synchronousRetryFromEarlierSubscriberDoesNotRestoreStaleErrorNotification() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val host = object : DesktopDisplayHost {
            override fun currentDisplay() = phone
            override fun displays() = listOf(phone)
            override fun defaultProfileKind() = ProfileKind.PHONE
            override fun sessionRunning() = false
            override fun launch(request: DesktopLaunchRequest) = Unit
            override fun focus(requestId: Long) = false
            override fun cancelLaunch(requestId: Long) = Unit
            override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
            override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
        }
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "reentrant retry notification test",
                ),
            ),
        )
        var retried = false
        val unsubscribeRetry = coordinator.subscribe { state ->
            if (!retried && state["phase"] == "error") {
                retried = true
                coordinator.requestOpen(
                    request = DesktopOpenRequest(phone.id, "proot", "xfce4", startSession = true, expectedEpoch = null),
                    startService = {},
                    completion = {},
                )
            }
        }
        val messages = mutableListOf<String>()
        val notification = DesktopStartupNotification(coordinator, messages::add)

        coordinator.reportError(DesktopDisplayException("DISPLAY_UNAVAILABLE", "Transient display glitch"))

        assertEquals("Linux desktop is starting", messages.last())
        assertEquals("Linux desktop is starting", notification.text)
        notification.close()
        unsubscribeRetry()
    }

    @Test
    fun synchronousBeginSessionOnStoppedStillDeliversStoppedToLaterSubscribersWithoutStaleNotification() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val host = object : DesktopDisplayHost {
            override fun currentDisplay() = phone
            override fun displays() = listOf(phone)
            override fun defaultProfileKind() = ProfileKind.PHONE
            override fun sessionRunning() = false
            override fun launch(request: DesktopLaunchRequest) = Unit
            override fun focus(requestId: Long) = false
            override fun cancelLaunch(requestId: Long) = Unit
            override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
            override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
        }
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "reentrant stop delivery test",
                ),
            ),
        )
        var reopened = false
        val unsubscribeReopen = coordinator.subscribe { state ->
            if (!reopened && state["phase"] == "stopped") {
                reopened = true
                coordinator.beginSession()
            }
        }
        val messages = mutableListOf<String>()
        val notification = DesktopStartupNotification(coordinator, messages::add)
        val laterPhases = mutableListOf<String?>()
        val unsubscribeLater = coordinator.subscribe { state ->
            laterPhases += state["phase"] as? String
        }

        coordinator.stop()

        assertTrue("Later subscribers such as userStopLaunchReply must still observe stopped", "stopped" in laterPhases)
        assertEquals("Linux desktop is not running", messages.last())
        assertEquals("Linux desktop is not running", notification.text)
        unsubscribeLater()
        notification.close()
        unsubscribeReopen()
    }

    @Test
    fun startupNotificationTextReflectsNonMovableAndMovableLifecycleStates() {
        // Non-movable states
        assertEquals(
            "Restarting Linux/XFCE on this display",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "restarting",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                ),
                stopped = true,
            ),
        )
        assertEquals(
            "Linux desktop needs attention: Monitor unplugged",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "error",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                    "error" to mapOf("code" to "DISPLAY_UNAVAILABLE", "message" to "Monitor unplugged"),
                ),
            ),
        )
        assertEquals(
            "Linux desktop is starting",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "starting",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                ),
            ),
        )
        assertEquals(
            "Linux desktop is stopped",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "stopped",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                ),
            ),
        )
        assertEquals(
            "Save unsaved work first.",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "attached",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to true,
                ),
                restartWarning = "Save unsaved work first.",
            ),
        )
        assertEquals(
            "Linux desktop is running",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "attached",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to true,
                ),
                restartWarning = null,
            ),
        )
        assertEquals(
            "Linux desktop is not running",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "attached",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                ),
                restartWarning = "Save unsaved work first.",
            ),
        )
        assertEquals(
            "Save unsaved work first.",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "hidden",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to true,
                ),
                restartWarning = "Save unsaved work first.",
            ),
        )
        assertEquals(
            "Linux desktop is running in the background",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "hidden",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to true,
                ),
                restartWarning = null,
            ),
        )
        assertEquals(
            "Linux desktop failed to start: Android denied starting the Linux service",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "hidden",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                ),
                lastMessage = "Linux desktop failed to start: Android denied starting the Linux service",
                messageEpoch = 5L,
                sessionGeneration = 5L,
            ),
        )
        assertEquals(
            "Linux desktop is not running",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "hidden",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                ),
                lastMessage = "Linux desktop is running",
                messageEpoch = 5L,
                sessionGeneration = 5L,
            ),
        )
        assertEquals(
            "Linux desktop is not running",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "hidden",
                    "crossDisplayMovesAllowed" to false,
                    "sessionRunning" to false,
                ),
            ),
        )

        // Movable states: move-failure error stays owned by DroidDeskService recovery notification
        assertEquals(
            "Linux desktop is running",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "error",
                    "crossDisplayMovesAllowed" to true,
                    "sessionRunning" to true,
                    "error" to mapOf("code" to "DISPLAY_NOT_ALLOWED", "message" to "Android denied placement"),
                ),
            ),
        )
        assertEquals(
            "Linux desktop is starting",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "hidden",
                    "crossDisplayMovesAllowed" to true,
                    "sessionRunning" to false,
                ),
                starting = true,
            ),
        )
        assertEquals(
            "Linux desktop failed to start: Android denied starting the Linux service",
            desktopStartupNotificationText(
                snapshot = mapOf(
                    "phase" to "hidden",
                    "crossDisplayMovesAllowed" to true,
                    "sessionRunning" to false,
                ),
                lastMessage = "Linux desktop failed to start: Android denied starting the Linux service",
                messageEpoch = 9L,
                sessionGeneration = 9L,
            ),
        )
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
