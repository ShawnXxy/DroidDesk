package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotDebianEnvironmentTest {

    @Test
    fun desktopInstallUsesFastMirrorWithOfficialFallback() {
        val command = debianDesktopInstallCommand(listOf("xfce4", "curl"))

        assertTrue(command.contains("http://mirrors.ustc.edu.cn"))
        assertTrue(command.contains("if ! apt-get update"))
        assertTrue(command.contains("if [ ! -f \"${'$'}backup_dir/debian.sources\" ]"))
        assertTrue(command.contains("https://deb.debian.org"))
        assertTrue(command.contains("s|https://mirrors.ustc.edu.cn|https://deb.debian.org|g"))
        assertTrue(command.contains("cp -f \"${'$'}backup_dir/debian.sources\""))
        assertTrue(command.contains("apt-get install -y --no-install-recommends xfce4 curl"))
    }

    @Test
    fun sessionLauncherSharesItsDbusAddressWithLaterCommands() {
        val launcher = prootSessionLauncherScript()
        val command = prootBusAwareCommand("xfconf-query --version")

        assertTrue(launcher.contains("DBUS_SESSION_BUS_ADDRESS"))
        assertTrue(launcher.contains(PROOT_SESSION_BUS_FILE))
        assertTrue(command.contains("export DBUS_SESSION_BUS_ADDRESS"))
        assertTrue(command.endsWith("xfconf-query --version"))
    }

    @Test
    fun readyMarkerDrivesInstalledStatusAndDebianPaths() {
        val host = FakeEnvironmentHost()
        val environment = ProotDebianEnvironment(host)

        val status = environment.status(capabilities())

        assertTrue(status.installed)
        assertEquals(EnvironmentKind.PROOT_DEBIAN, status.kind)
        assertEquals("debian-bookworm", status.distro)
        assertEquals(host.rootfs.absolutePath, status.rootfsPath)
        assertEquals("/home/droiddesk", environment.paths().guestHome)
        assertEquals(mapOf("code_oss" to true), environment.optionalAppsStatus())
    }

    @Test
    fun shellRunsAsRootAndDesktopCommandRunsAsDroiddesk() {
        val host = FakeEnvironmentHost()
        val environment = ProotDebianEnvironment(host)

        val shell = environment.execute(
            ExecutionRequest.Shell(
                EnvironmentKind.PROOT_DEBIAN,
                "apt-get update",
                interactive = true,
            ),
        )
        val stdin = environment.execute(
            ExecutionRequest.Shell(
                EnvironmentKind.PROOT_DEBIAN,
                "yes",
                interactive = true,
                stdin = true,
            ),
        )
        val desktop = environment.execute(
            ExecutionRequest.Desktop(EnvironmentKind.PROOT_DEBIAN, "thunar"),
        )

        assertEquals(0, shell.exitCode)
        assertEquals(0, stdin.exitCode)
        assertEquals(0, desktop.exitCode)
        assertEquals(
            listOf(
                "execute:root:apt-get update:true:false",
                "execute:root:yes:true:true",
                "execute:droiddesk:thunar:false:false",
            ),
            host.calls,
        )
    }

    private class FakeEnvironmentHost : ProotDebianHost {
        val calls = mutableListOf<String>()
        val rootfs: File = Files.createTempDirectory("proot-rootfs").toFile()
        override fun availableBytes(): Long = Long.MAX_VALUE
        override fun ensureToolchain(
            onProgress: (Double, String) -> Unit,
            onLog: (String) -> Unit,
        ) = Result.success(ProotToolchainVersions("proot", "proot-distro"))
        override fun containerState() = ProotContainerState.ACTIVE_READY
        override fun discardContainer(name: String) = Result.success(Unit)
        override fun installContainer(
            imageReference: String,
            name: String,
            onLog: (String) -> Unit,
        ) = Result.success(identity())
        override fun runRoot(container: String, command: String, onLog: (String) -> Unit) =
            Result.success(Unit)
        override fun installDesktopPackages(
            container: String,
            packages: List<String>,
            onLog: (String) -> Unit,
        ) = Result.success(Unit)
        override fun configureDesktopUser(container: String, onLog: (String) -> Unit) =
            Result.success(Unit)
        override fun verify(container: String) =
            Result.success(ProotVerification("arm64", true, emptyList()))
        override fun identity(container: String) = Result.success(identity())
        override fun activate(container: String) = Result.success(Unit)
        override fun writeReadyMarker(marker: ProotReadyMarker) = Result.success(Unit)
        override fun readReadyMarker() = ProotReadyMarker(
            ProotDebianProvisioner.READY_SCHEMA,
            "bookworm",
            ProotDebianProvisioner.IMAGE_REFERENCE,
            "sha256:image",
            ProotDebianProvisioner.PACKAGE_SET_VERSION,
            "proot",
            "proot-distro",
        )
        override fun isRunning() = false
        override fun rootfsPath() = rootfs
        override fun rootfsSizeBytes() = 1024L
        override fun optionalAppsStatus() = mapOf("code_oss" to true)
        override fun executeActive(
            user: String,
            command: String,
            onOutput: ((String) -> Unit)?,
            interactive: Boolean,
            stdin: Boolean,
        ): ExecutionResult {
            calls += "execute:$user:$command:$interactive:$stdin"
            return ExecutionResult(0, "ok")
        }
    }

    private companion object {
        fun identity() = ProotImageIdentity(
            ProotDebianProvisioner.IMAGE_REFERENCE,
            "bookworm",
            "sha256:image",
        )

        fun capabilities(): RuntimeCapabilities {
            val supported = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
            return RuntimeCapabilities(
                36,
                "arm64-v8a",
                supported,
                supported,
                supported,
                supported,
                supported,
                supported,
                CapabilityEvidence(EvidenceState.UNAVAILABLE),
                CapabilityEvidence(EvidenceState.UNAVAILABLE),
                CapabilityEvidence(EvidenceState.UNAVAILABLE),
                supported,
                RendererBackend.SOFTWARE_LLVMPIPE,
                supported,
                Long.MAX_VALUE,
                1,
                DistributionContract(DistributionChannel.SIDELOAD_LOCAL_LINUX, 28, true),
            )
        }
    }
}
