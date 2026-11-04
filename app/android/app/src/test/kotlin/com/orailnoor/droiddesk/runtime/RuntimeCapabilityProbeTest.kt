package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun failedLocalExecutionOrInsufficientStorageBlocksProot() {
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

        assertFalse(noExec.prootCandidate)
        assertEquals(EvidenceState.FAILED_SELF_TEST, noExec.localExecution.state)
        assertFalse(noSpace.prootCandidate)
        assertEquals(EvidenceState.BLOCKED_BY_POLICY, noSpace.storage.state)
        assertEquals(GIB, noSpace.requiredBytes)
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
    fun aSlowProbeTimesOutInsteadOfBlockingCapabilityCollection() {
        val started = System.nanoTime()
        val capabilities = RuntimeCapabilityProbe(
            source = FakeCapabilitySource(
                prootProbe = {
                    Thread.sleep(250)
                    CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                },
            ),
            requiredBytes = GIB,
            timeoutMillis = 25,
        ).probe()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(EvidenceState.FAILED_SELF_TEST, capabilities.proot.state)
        assertTrue(capabilities.proot.reason!!.contains("timed out"))
        assertTrue("probe took ${elapsedMillis}ms", elapsedMillis < 200)
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
        private val prootProbe: () -> CapabilityEvidence = {
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        },
    ) : RuntimeCapabilitySource {
        override fun probeLocalExecution(): CapabilityEvidence = localExecution

        override fun probeProot(): CapabilityEvidence = prootProbe()

        override fun probeX11(): CapabilityEvidence = x11

        override fun probeAudio(): CapabilityEvidence = audio

        override fun probeClipboard(): CapabilityEvidence = clipboard

        override fun probeChroot(): CapabilityEvidence = chroot

        override fun probeRenderer(): RendererProbe = renderer

        override fun hasAvfFeature(): Boolean = avfFeaturePresent

        override fun hasVmPermissions(): Boolean = avfPermissionsGranted
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
