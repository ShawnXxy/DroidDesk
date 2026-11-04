package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotDebianProvisionerTest {

    @Test
    fun cleanInstallRepairsConfiguresVerifiesActivatesThenWritesReadyMarker() {
        val host = FakeProvisioningHost(ProotContainerState.ABSENT)
        val provisioner = ProotDebianProvisioner(host, requiredBytes = GIB)

        val result = provisioner.provision({ _, _ -> }, {})

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "toolchain",
                "state",
                "install:droiddesk-debian-staging",
                "run:droiddesk-debian-staging:dpkg --configure -a",
                "run:droiddesk-debian-staging:apt-get install -f -y",
                "packages:droiddesk-debian-staging",
                "configure:droiddesk-debian-staging",
                "verify:droiddesk-debian-staging",
                "activate:droiddesk-debian-staging",
                "marker",
            ),
            host.calls,
        )
        assertEquals(ProotDebianProvisioner.READY_SCHEMA, host.marker!!.schema)
        assertEquals("bookworm", host.marker!!.suite)
        assertEquals("sha256:image", host.marker!!.imageDigest)
        assertEquals(ProotDebianProvisioner.PACKAGE_SET_VERSION, host.marker!!.packageSetVersion)
    }

    @Test
    fun existingMinimalDebianIsUpgradedWithoutDeletingItsUserData() {
        val host = FakeProvisioningHost(ProotContainerState.LEGACY_MINIMAL)
        val provisioner = ProotDebianProvisioner(host, requiredBytes = GIB)

        val result = provisioner.provision({ _, _ -> }, {})

        assertTrue(result.isSuccess)
        assertFalse(host.calls.any { it.startsWith("discard:") || it.startsWith("install:") })
        assertTrue(host.calls.contains("run:debian:dpkg --configure -a"))
        assertTrue(host.calls.contains("activate:debian"))
        assertEquals("legacy-home-token", host.preservedUserData)
    }

    @Test
    fun interruptedStagingIsDiscardedBeforeAReplacementInstall() {
        val host = FakeProvisioningHost(ProotContainerState.STAGING_INTERRUPTED)
        val provisioner = ProotDebianProvisioner(host, requiredBytes = GIB)

        val result = provisioner.provision({ _, _ -> }, {})

        assertTrue(result.isSuccess)
        assertTrue(
            host.calls.indexOf("discard:droiddesk-debian-staging") <
                host.calls.indexOf("install:droiddesk-debian-staging"),
        )
    }

    @Test
    fun interruptedActiveRootfsIsRepairedInPlace() {
        val host = FakeProvisioningHost(ProotContainerState.ACTIVE_INCOMPLETE)
        val provisioner = ProotDebianProvisioner(host, requiredBytes = GIB)

        val result = provisioner.provision({ _, _ -> }, {})

        assertTrue(result.isSuccess)
        assertTrue(host.calls.contains("run:droiddesk-debian:dpkg --configure -a"))
        assertFalse(host.calls.any { it.startsWith("install:") })
    }

    @Test
    fun checksumMismatchStopsBeforeGuestCommandsOrReadyMarker() {
        val host = FakeProvisioningHost(ProotContainerState.ABSENT).apply {
            installResult = Result.failure(IllegalStateException("image checksum mismatch"))
        }
        val provisioner = ProotDebianProvisioner(host, requiredBytes = GIB)

        val result = provisioner.provision({ _, _ -> }, {})

        assertTrue(result.isFailure)
        assertFalse(host.calls.any { it.startsWith("run:") })
        assertFalse(host.calls.contains("marker"))
    }

    @Test
    fun insufficientTransactionStorageStopsBeforeToolchainInstallation() {
        val host = FakeProvisioningHost(ProotContainerState.ABSENT).apply {
            availableBytes = 512L * MIB
        }
        val provisioner = ProotDebianProvisioner(host, requiredBytes = GIB)

        val result = provisioner.provision({ _, _ -> }, {})

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("storage"))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun failedPackageVerificationNeverActivatesOrWritesReadyMarker() {
        val host = FakeProvisioningHost(ProotContainerState.ABSENT).apply {
            verificationResult = Result.failure(
                IllegalStateException("xfce4-session is missing"),
            )
        }
        val provisioner = ProotDebianProvisioner(host, requiredBytes = GIB)

        val result = provisioner.provision({ _, _ -> }, {})

        assertTrue(result.isFailure)
        assertFalse(host.calls.any { it.startsWith("activate:") })
        assertFalse(host.calls.contains("marker"))
    }

    private class FakeProvisioningHost(
        private val initialState: ProotContainerState,
    ) : ProotProvisioningHost {
        val calls = mutableListOf<String>()
        var marker: ProotReadyMarker? = null
        var availableBytes: Long = 64L * GIB
        var installResult: Result<ProotImageIdentity> = Result.success(identity())
        var verificationResult: Result<ProotVerification> = Result.success(
            ProotVerification("arm64", desktopReady = true, missingPackages = emptyList()),
        )
        val preservedUserData = "legacy-home-token"

        override fun availableBytes(): Long = availableBytes

        override fun ensureToolchain(
            onProgress: (Double, String) -> Unit,
            onLog: (String) -> Unit,
        ): Result<ProotToolchainVersions> {
            calls += "toolchain"
            return Result.success(ProotToolchainVersions("5.1.110", "5.10.3"))
        }

        override fun containerState(): ProotContainerState {
            calls += "state"
            return initialState
        }

        override fun discardContainer(name: String): Result<Unit> {
            calls += "discard:$name"
            return Result.success(Unit)
        }

        override fun installContainer(
            imageReference: String,
            name: String,
            onLog: (String) -> Unit,
        ): Result<ProotImageIdentity> {
            calls += "install:$name"
            return installResult
        }

        override fun runRoot(
            container: String,
            command: String,
            onLog: (String) -> Unit,
        ): Result<Unit> {
            calls += "run:$container:$command"
            return Result.success(Unit)
        }

        override fun installDesktopPackages(
            container: String,
            packages: List<String>,
            onLog: (String) -> Unit,
        ): Result<Unit> {
            calls += "packages:$container"
            return Result.success(Unit)
        }

        override fun configureDesktopUser(
            container: String,
            onLog: (String) -> Unit,
        ): Result<Unit> {
            calls += "configure:$container"
            return Result.success(Unit)
        }

        override fun verify(container: String): Result<ProotVerification> {
            calls += "verify:$container"
            return verificationResult
        }

        override fun identity(container: String): Result<ProotImageIdentity> =
            Result.success(identity())

        override fun activate(container: String): Result<Unit> {
            calls += "activate:$container"
            return Result.success(Unit)
        }

        override fun writeReadyMarker(marker: ProotReadyMarker): Result<Unit> {
            calls += "marker"
            this.marker = marker
            return Result.success(Unit)
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB

        fun identity() = ProotImageIdentity(
            reference = ProotDebianProvisioner.IMAGE_REFERENCE,
            suite = "bookworm",
            digest = "sha256:image",
        )
    }
}
