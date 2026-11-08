package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RuntimeCapabilityProbeTest {

    @Test
    fun supportedArm64HostBecomesAProotCandidate() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(),
            requiredBytes = 6L * GIB,
        ).probe()

        assertTrue(capabilities.prootCandidate)
        assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.host.state)
        assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.storage.state)
        assertEquals(DistributionChannel.SIDELOAD_LOCAL_LINUX, capabilities.distribution.channel)
        assertEquals(28, capabilities.distribution.targetSdk)
    }

    @Test
    fun expectedPreinstallEvidenceStillAllowsDebianRecommendation() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                localExecution = CapabilityEvidence(
                    EvidenceState.PRESENT_UNVERIFIED,
                    "The native bootstrap is not installed",
                ),
                x11 = CapabilityEvidence(
                    EvidenceState.PRESENT_UNVERIFIED,
                    "The X11 session is not ready",
                ),
                prootProbe = {
                    CapabilityEvidence(
                        EvidenceState.PRESENT_UNVERIFIED,
                        "PRoot is not installed",
                    )
                },
            ),
            requiredBytes = GIB,
        ).probe()

        assertTrue(capabilities.prootCandidate)
    }

    @Test
    fun defaultStorageGateIsBelowTheSixGibRecommendation() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(availableBytes = 3L * GIB),
        ).probe()

        assertEquals(2L * GIB, capabilities.requiredBytes)
        assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.storage.state)
    }

    @Test
    fun nonArm64OrUnsupportedApiIsRejectedWithTheHostReason() {
        val nonArm64 = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(abi = "x86_64"),
            requiredBytes = GIB,
        ).probe()
        val oldAndroid = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(apiLevel = 27),
            requiredBytes = GIB,
        ).probe()

        assertFalse(nonArm64.prootCandidate)
        assertEquals(EvidenceState.BLOCKED_BY_POLICY, nonArm64.host.state)
        assertTrue(nonArm64.host.reason!!.contains("arm64-v8a"))
        assertFalse(oldAndroid.prootCandidate)
        assertEquals(EvidenceState.BLOCKED_BY_POLICY, oldAndroid.host.state)
        assertTrue(oldAndroid.host.reason!!.contains("API 28"))
    }

    @Test
    fun failedRequiredProbeOrInsufficientStorageBlocksProot() {
        val noExec = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                localExecution = CapabilityEvidence(
                    EvidenceState.FAILED_SELF_TEST,
                    "App-private bash exited 126",
                ),
            ),
            requiredBytes = GIB,
        ).probe()
        val noSpace = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(availableBytes = 512L * MIB),
            requiredBytes = GIB,
        ).probe()
        val noAudio = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                audio = CapabilityEvidence(
                    EvidenceState.FAILED_SELF_TEST,
                    "PulseAudio connection failed",
                ),
            ),
            requiredBytes = GIB,
        ).probe()

        assertFalse(noExec.prootCandidate)
        assertEquals(EvidenceState.FAILED_SELF_TEST, noExec.localExecution.state)
        assertFalse(noSpace.prootCandidate)
        assertEquals(EvidenceState.BLOCKED_BY_POLICY, noSpace.storage.state)
        assertEquals(GIB, noSpace.requiredBytes)
        assertFalse(noAudio.prootCandidate)
    }

    @Test
    fun avfFeatureDoesNotBecomeAnAdapterWithoutBothVmPermissions() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                avfFeaturePresent = true,
                avfPermissionsGranted = false,
            ),
            requiredBytes = GIB,
        ).probe()

        assertEquals(EvidenceState.PRESENT_UNVERIFIED, capabilities.avfFeature.state)
        assertEquals(EvidenceState.BLOCKED_BY_PERMISSION, capabilities.avfPermission.state)
        assertFalse(capabilities.avfAdapterAvailable)
    }

    @Test
    fun gpuNodePresenceDoesNotClaimAccelerationWithoutRendererProbe() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                renderer = RendererProbe(
                    CapabilityEvidence(
                        EvidenceState.PRESENT_UNVERIFIED,
                        "DRM render node exists but no GL context was verified",
                    ),
                    RendererBackend.UNKNOWN,
                ),
            ),
            requiredBytes = GIB,
        ).probe()

        assertEquals(EvidenceState.PRESENT_UNVERIFIED, capabilities.renderer.state)
        assertEquals(RendererBackend.UNKNOWN, capabilities.rendererBackend)
        assertFalse(capabilities.hardwareAccelerationVerified)
    }

    @Test
    fun probeTimeoutDefaultsMatchMilestoneBudgets() {
        val timeouts = ProbeTimeouts()

        assertEquals(1_000L, timeouts.localExecutionMs)
        assertEquals(2_000L, timeouts.prootMs)
        assertEquals(1_500L, timeouts.x11Ms)
        assertEquals(2_500L, timeouts.audioMs)
        assertEquals(1_000L, timeouts.clipboardMs)
        assertEquals(5_000L, timeouts.chrootMs)
        assertEquals(2_000L, timeouts.rendererMs)
    }

    @Test
    fun slowChrootProbeCannotExtendAudioBudget() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                audioProbe = {
                    Thread.sleep(75)
                    CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                },
                chrootProbe = {
                    Thread.sleep(250)
                    CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                },
            ),
            requiredBytes = GIB,
            timeouts = ProbeTimeouts(
                audioMs = 25,
                chrootMs = 150,
            ),
        ).probe()

        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.audio.state)
        assertEquals("PulseAudio probe timed out after 25ms", capabilities.audio.reason)
        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.chroot.state)
        assertEquals("chroot probe timed out after 150ms", capabilities.chroot.reason)
    }

    @Test
    fun slowChrootCannotHideRendererTimeoutWhenCancellationIsDelayed() {
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val timeoutWorkerStarted = CountDownLatch(1)
        val releaseTimeoutWorker = CountDownLatch(1)
        timeoutExecutor.execute {
            timeoutWorkerStarted.countDown()
            try {
                releaseTimeoutWorker.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        timeoutWorkerStarted.await()
        val rendererFinished = CountDownLatch(1)
        val capabilities = try {
            RuntimeCapabilityProbe(
                source = FakeCapabilitySource(
                    chrootProbe = {
                        rendererFinished.await()
                        Thread.sleep(25)
                        CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                    },
                    rendererProbe = {
                        try {
                            Thread.sleep(75)
                            RendererProbe(
                                CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
                                RendererBackend.SOFTWARE_LLVMPIPE,
                            )
                        } finally {
                            rendererFinished.countDown()
                        }
                    },
                ),
                requiredBytes = GIB,
                timeouts = ProbeTimeouts(
                    chrootMs = 2_000,
                    rendererMs = 25,
                ),
                timeoutExecutorFactory = { timeoutExecutor },
            ).probe()
        } finally {
            releaseTimeoutWorker.countDown()
            timeoutExecutor.shutdownNow()
        }

        assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.chroot.state)
        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.renderer.state)
        assertEquals("Renderer probe timed out after 25ms", capabilities.renderer.reason)
    }

    @Test
    fun collectionEnforcesDeadlineWhenTimeoutDispatchAndProbeRemainBlocked() {
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val timeoutWorkerStarted = CountDownLatch(1)
        val releaseTimeoutWorker = CountDownLatch(1)
        timeoutExecutor.execute {
            timeoutWorkerStarted.countDown()
            try {
                releaseTimeoutWorker.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        timeoutWorkerStarted.await()
        val releaseRenderer = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        val probeFuture = caller.submit<RuntimeCapabilities> {
            RuntimeCapabilityProbe(
                source = FakeCapabilitySource(
                    rendererProbe = {
                        releaseRenderer.await()
                        RendererProbe(
                            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
                            RendererBackend.SOFTWARE_LLVMPIPE,
                        )
                    },
                ),
                requiredBytes = GIB,
                timeouts = ProbeTimeouts(rendererMs = 25),
                timeoutExecutorFactory = { timeoutExecutor },
            ).probe()
        }

        try {
            val capabilities = probeFuture.get(500, TimeUnit.MILLISECONDS)

            assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.renderer.state)
            assertEquals("Renderer probe timed out after 25ms", capabilities.renderer.reason)
        } finally {
            releaseRenderer.countDown()
            releaseTimeoutWorker.countDown()
            probeFuture.cancel(true)
            caller.shutdownNow()
            timeoutExecutor.shutdownNow()
        }
    }

    @Test
    fun lateFailureReportsTimeoutWhenCancellationIsDelayed() {
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val timeoutWorkerStarted = CountDownLatch(1)
        val releaseTimeoutWorker = CountDownLatch(1)
        timeoutExecutor.execute {
            timeoutWorkerStarted.countDown()
            try {
                releaseTimeoutWorker.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        timeoutWorkerStarted.await()
        val rendererFinished = CountDownLatch(1)
        val capabilities = try {
            RuntimeCapabilityProbe(
                source = FakeCapabilitySource(
                    chrootProbe = {
                        rendererFinished.await()
                        Thread.sleep(25)
                        CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                    },
                    rendererProbe = {
                        try {
                            Thread.sleep(75)
                            throw IllegalStateException("late renderer failure")
                        } finally {
                            rendererFinished.countDown()
                        }
                    },
                ),
                requiredBytes = GIB,
                timeouts = ProbeTimeouts(
                    chrootMs = 2_000,
                    rendererMs = 25,
                ),
                timeoutExecutorFactory = { timeoutExecutor },
            ).probe()
        } finally {
            releaseTimeoutWorker.countDown()
            timeoutExecutor.shutdownNow()
        }

        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.renderer.state)
        assertEquals("Renderer probe timed out after 25ms", capabilities.renderer.reason)
    }

    @Test
    fun outcomePublishedAfterDeadlineIsATimeoutEvenWhenProbeReturnedEarlier() {
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val timeoutWorkerStarted = CountDownLatch(1)
        val releaseTimeoutWorker = CountDownLatch(1)
        timeoutExecutor.execute {
            timeoutWorkerStarted.countDown()
            try {
                releaseTimeoutWorker.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        timeoutWorkerStarted.await()
        val rendererThread = AtomicReference<Thread>()
        val rendererPublicationBlocked = CountDownLatch(1)
        val releaseRendererPublication = CountDownLatch(1)
        val capabilities = try {
            RuntimeCapabilityProbe(
                source = FakeCapabilitySource(
                    chrootProbe = {
                        rendererPublicationBlocked.await()
                        Thread.sleep(75)
                        releaseRendererPublication.countDown()
                        Thread.sleep(25)
                        CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                    },
                    rendererProbe = {
                        rendererThread.set(Thread.currentThread())
                        RendererProbe(
                            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
                            RendererBackend.SOFTWARE_LLVMPIPE,
                        )
                    },
                ),
                requiredBytes = GIB,
                timeouts = ProbeTimeouts(
                    chrootMs = 2_000,
                    rendererMs = 25,
                ),
                timeoutExecutorFactory = { timeoutExecutor },
                beforeOutcomePublication = {
                    if (Thread.currentThread() === rendererThread.get()) {
                        rendererPublicationBlocked.countDown()
                        releaseRendererPublication.await()
                    }
                },
            ).probe()
        } finally {
            releaseRendererPublication.countDown()
            releaseTimeoutWorker.countDown()
            timeoutExecutor.shutdownNow()
        }

        assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.chroot.state)
        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.renderer.state)
        assertEquals("Renderer probe timed out after 25ms", capabilities.renderer.reason)
    }

    @Test
    fun eachTimedOutCapabilityReportsItsOwnBudget() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                localExecutionProbe = ::slowEvidence,
                prootProbe = ::slowEvidence,
                x11Probe = ::slowEvidence,
                audioProbe = ::slowEvidence,
                clipboardProbe = ::slowEvidence,
                chrootProbe = ::slowEvidence,
                rendererProbe = {
                    Thread.sleep(5_000)
                    RendererProbe(
                        CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
                        RendererBackend.SOFTWARE_LLVMPIPE,
                    )
                },
            ),
            requiredBytes = GIB,
            timeouts = ProbeTimeouts(
                localExecutionMs = 20,
                prootMs = 30,
                x11Ms = 40,
                audioMs = 50,
                clipboardMs = 60,
                chrootMs = 70,
                rendererMs = 80,
            ),
        ).probe()

        assertEquals("App-private ELF probe timed out after 20ms", capabilities.localExecution.reason)
        assertEquals("PRoot probe timed out after 30ms", capabilities.proot.reason)
        assertEquals("X11 probe timed out after 40ms", capabilities.x11.reason)
        assertEquals("PulseAudio probe timed out after 50ms", capabilities.audio.reason)
        assertEquals("Clipboard probe timed out after 60ms", capabilities.clipboard.reason)
        assertEquals("chroot probe timed out after 70ms", capabilities.chroot.reason)
        assertEquals("Renderer probe timed out after 80ms", capabilities.renderer.reason)
    }

    @Test
    fun aSlowProbeTimesOutInsteadOfBlockingCapabilityCollection() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                prootProbe = {
                    Thread.sleep(250)
                    CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                },
            ),
            requiredBytes = GIB,
            timeouts = ProbeTimeouts(prootMs = 25),
        ).probe()

        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.proot.state)
        assertEquals("PRoot probe timed out after 25ms", capabilities.proot.reason)
    }

    @Test
    fun legacyUniformTimeoutConstructorRemainsSourceCompatible() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                prootProbe = {
                    Thread.sleep(100)
                    CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                },
            ),
            timeoutMillis = 25,
        ).probe()

        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.proot.state)
        assertEquals("PRoot probe timed out after 25ms", capabilities.proot.reason)
    }

    @Test
    fun nonExceptionProbeFailureRetainsItsReason() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                prootProbe = {
                    throw AssertionError("probe linkage failed")
                },
            ),
            requiredBytes = GIB,
            timeouts = ProbeTimeouts(prootMs = 2_000),
        ).probe()

        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.proot.state)
        assertEquals("PRoot probe failed: probe linkage failed", capabilities.proot.reason)
    }

    @Test
    fun probeFailureReasonPreservesOuterMessageAndThrowableTypeFallback() {
        val nestedFailure = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                prootProbe = {
                    throw IllegalStateException(
                        "outer probe failure",
                        RuntimeException("inner cause"),
                    )
                },
            ),
            requiredBytes = GIB,
        ).probe()
        val messageLessFailure = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                audioProbe = {
                    throw AssertionError()
                },
            ),
            requiredBytes = GIB,
        ).probe()

        assertEquals(
            "PRoot probe failed: outer probe failure",
            nestedFailure.proot.reason,
        )
        assertEquals(
            "PulseAudio probe failed: java.lang.AssertionError",
            messageLessFailure.audio.reason,
        )
    }

    @Test
    fun blockingTimeoutRegistrationDoesNotDelayConcurrentProbeCollection() {
        val timeoutExecutor = BlockingScheduleExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val uncaughtFailure = AtomicReference<Throwable?>()
        val uncaughtSignal = CountDownLatch(1)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            uncaughtFailure.compareAndSet(null, error)
            uncaughtSignal.countDown()
            previousHandler?.uncaughtException(thread, error)
        }
        val probeFuture = caller.submit<RuntimeCapabilities> {
            RuntimeCapabilityProbe(
                source = FakeCapabilitySource(
                    localExecutionProbe = {
                        timeoutExecutor.scheduleEntered.await()
                        CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                    },
                ),
                requiredBytes = GIB,
                timeouts = ProbeTimeouts(localExecutionMs = 250),
                timeoutExecutorFactory = { timeoutExecutor },
            ).probe()
        }

        try {
            val capabilities = probeFuture.get(500, TimeUnit.MILLISECONDS)

            assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.localExecution.state)
            assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.proot.state)
            assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.audio.state)
            timeoutExecutor.releaseSchedule.countDown()
            assertFalse(
                "uncaught timeout registration failure: ${uncaughtFailure.get()}",
                uncaughtSignal.await(500, TimeUnit.MILLISECONDS),
            )
        } finally {
            timeoutExecutor.releaseSchedule.countDown()
            probeFuture.cancel(true)
            caller.shutdownNow()
            timeoutExecutor.shutdownNow()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun completedLaterProbeIsCollectedAfterAnEarlierProbeConsumesTheDeadline() {
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                localExecutionProbe = {
                    Thread.sleep(100)
                    CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                },
                prootProbe = {
                    CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                },
            ),
            requiredBytes = GIB,
            timeouts = ProbeTimeouts(localExecutionMs = 25),
        ).probe()

        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.localExecution.state)
        assertEquals(EvidenceState.SUPPORTED_AND_PROBED, capabilities.proot.state)
    }

    @Test
    fun capabilityCacheReturnsPendingEvidenceUntilBackgroundRefreshRuns() {
        val probe = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(),
            requiredBytes = GIB,
        )
        val queued = QueuedExecutor()
        val cache = RuntimeCapabilityCache(
            load = probe::probe,
            initial = probe.pending(),
            executor = queued,
            refreshIntervalMillis = Long.MAX_VALUE,
        )

        assertEquals(EvidenceState.PRESENT_UNVERIFIED, cache.current().proot.state)
        queued.runAll()
        assertEquals(EvidenceState.SUPPORTED_AND_PROBED, cache.current().proot.state)
    }

    @Test
    fun capabilityCacheReportsRefreshFailureAndPreservesCachedEvidence() {
        val initial = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(),
            requiredBytes = GIB,
        ).pending()
        val failure = IllegalStateException("refresh failed")
        val queued = QueuedExecutor()
        var reportedFailure: Throwable? = null
        val cache = RuntimeCapabilityCache(
            load = { throw failure },
            initial = initial,
            executor = queued,
            refreshIntervalMillis = Long.MAX_VALUE,
            onRefreshFailure = { reportedFailure = it },
        )

        queued.runAll()

        assertEquals(failure, reportedFailure)
        assertEquals(EvidenceState.PRESENT_UNVERIFIED, cache.current().proot.state)
    }

    private class FakeCapabilitySource(
        override val apiLevel: Int = 36,
        override val abi: String = "arm64-v8a",
        override val targetSdk: Int = 28,
        override val availableBytes: Long = 64L * GIB,
        private val localExecution: CapabilityEvidence =
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
        private val x11: CapabilityEvidence =
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
        private val audio: CapabilityEvidence =
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
        private val clipboard: CapabilityEvidence =
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
        private val chroot: CapabilityEvidence =
            CapabilityEvidence(EvidenceState.UNAVAILABLE),
        private val renderer: RendererProbe = RendererProbe(
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            RendererBackend.SOFTWARE_LLVMPIPE,
        ),
        private val avfFeaturePresent: Boolean = false,
        private val avfPermissionsGranted: Boolean = false,
        private val localExecutionProbe: () -> CapabilityEvidence = { localExecution },
        private val prootProbe: () -> CapabilityEvidence = {
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        },
        private val x11Probe: () -> CapabilityEvidence = { x11 },
        private val audioProbe: () -> CapabilityEvidence = { audio },
        private val clipboardProbe: () -> CapabilityEvidence = { clipboard },
        private val chrootProbe: () -> CapabilityEvidence = { chroot },
        private val rendererProbe: () -> RendererProbe = { renderer },
    ) : RuntimeCapabilitySource {
        override fun probeLocalExecution(): CapabilityEvidence = localExecutionProbe()

        override fun probeProot(): CapabilityEvidence = prootProbe()

        override fun probeX11(): CapabilityEvidence = x11Probe()

        override fun probeAudio(): CapabilityEvidence = audioProbe()

        override fun probeClipboard(): CapabilityEvidence = clipboardProbe()

        override fun probeChroot(): CapabilityEvidence = chrootProbe()

        override fun probeRenderer(): RendererProbe = rendererProbe()

        override fun hasAvfFeature(): Boolean = avfFeaturePresent

        override fun hasVmPermissions(): Boolean = avfPermissionsGranted
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB

        fun slowEvidence(): CapabilityEvidence {
            Thread.sleep(5_000)
            return CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    private class BlockingScheduleExecutor : ScheduledThreadPoolExecutor(1) {
        val scheduleEntered = CountDownLatch(1)
        val releaseSchedule = CountDownLatch(1)

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            scheduleEntered.countDown()
            try {
                releaseSchedule.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return super.schedule(command, delay, unit)
        }
    }
}
