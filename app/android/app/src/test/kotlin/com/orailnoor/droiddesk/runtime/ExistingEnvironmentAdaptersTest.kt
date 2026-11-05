package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplayProfilePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExistingEnvironmentAdaptersTest {

    @Test
    fun nativeAdapterMapsStatusAndProvisioning() {
        val port = FakeLegacyRuntimePort(
            installed = true,
            running = true,
            desktop = "xfce4",
            rootfsPath = "",
            rootfsSizeBytes = 0,
        )
        val adapter = TermuxNativeEnvironment(port)
        val progress = mutableListOf<Pair<Double, String>>()
        val logs = mutableListOf<String>()

        val status = adapter.status(capabilities())
        val provisioned = adapter.provision(
            ProvisionRequest(EnvironmentKind.TERMUX_NATIVE, "xfce4"),
            { value, message -> progress += value to message },
            logs::add,
        )

        assertEquals(EnvironmentKind.TERMUX_NATIVE, status.kind)
        assertEquals("termux-native", status.distro)
        assertEquals("", status.rootfsPath)
        assertTrue(status.installed)
        assertTrue(status.running)
        assertTrue(provisioned.isSuccess)
        assertEquals(listOf(0.5 to "installing"), progress)
        assertEquals(listOf("native log"), logs)
        assertEquals(listOf("provision:xfce4"), port.calls)
    }

    @Test
    fun chrootAdapterMapsRootfsAndOptionalApps() {
        val port = FakeLegacyRuntimePort(
            installed = true,
            running = false,
            desktop = "xfce4",
            rootfsPath = "/data/user/0/app/files/rootfs",
            rootfsSizeBytes = 4_294_967_296,
            optionalApps = mapOf("firefox" to true, "code_oss" to false),
        )
        val adapter = ChrootUbuntuEnvironment(port)

        val status = adapter.status(capabilities())

        assertEquals(EnvironmentKind.CHROOT_UBUNTU, status.kind)
        assertEquals("ubuntu-chroot", status.distro)
        assertEquals("/data/user/0/app/files/rootfs", status.rootfsPath)
        assertEquals(4_294_967_296, status.rootfsSizeBytes)
        assertEquals(mapOf("firefox" to true, "code_oss" to false), adapter.optionalAppsStatus())
        assertTrue(adapter.installOptionalApp("code_oss", { _, _ -> }, {}).isSuccess)
        assertEquals(listOf("optional:code_oss"), port.calls)
    }

    @Test
    fun adaptersDelegateSessionProfileClipboardAndCommands() {
        val port = FakeLegacyRuntimePort(installed = true, desktop = "xfce4")
        val adapter = TermuxNativeEnvironment(port)
        val profile = DisplayProfilePolicy.phoneDefaultProfile()
        var profileResult: Result<Unit>? = null

        assertTrue(adapter.start(StartRequest(EnvironmentKind.TERMUX_NATIVE, "xfce4", profile)).isSuccess)
        assertTrue(adapter.waitForDesktopReady("xfce4", 1234))
        adapter.applyDisplayProfile(profile) { profileResult = it }
        assertTrue(profileResult!!.isSuccess)
        assertEquals("clipboard", adapter.readClipboard())
        assertTrue(adapter.writeClipboard("new value"))
        assertEquals(
            ExecutionResult(0, "shell output"),
            adapter.execute(ExecutionRequest.Shell(EnvironmentKind.TERMUX_NATIVE, "uname -m")),
        )
        assertEquals(
            ExecutionResult(0, ""),
            adapter.execute(ExecutionRequest.Desktop(EnvironmentKind.TERMUX_NATIVE, "thunar")),
        )
        adapter.cancelDisplayProfile()
        assertTrue(adapter.stop().isSuccess)

        assertEquals(
            listOf(
                "start:xfce4",
                "ready:xfce4:1234",
                "profile:phone",
                "readClipboard",
                "writeClipboard:new value",
                "shell:uname -m:false:false",
                "desktop:thunar",
                "cancelProfile",
                "stop",
            ),
            port.calls,
        )
    }

    @Test
    fun rootedCapabilityRecommendsChrootAdapter() {
        val supported = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        val manager = LinuxEnvironmentManager(
            adapters = listOf(
                TermuxNativeEnvironment(FakeLegacyRuntimePort(installed = true)),
                ChrootUbuntuEnvironment(FakeLegacyRuntimePort(installed = true)),
            ),
            capabilities = {
                capabilities().copy(chroot = supported)
            },
            selectionStore = MemorySelectionStore(),
        )

        assertEquals(EnvironmentKind.CHROOT_UBUNTU, manager.recommendedKind())
    }

    @Test
    fun failedX11ProbeFallsBackFromOtherwiseFunctionalChroot() {
        val manager = LinuxEnvironmentManager(
            adapters = listOf(
                TermuxNativeEnvironment(FakeLegacyRuntimePort(installed = true)),
                ChrootUbuntuEnvironment(FakeLegacyRuntimePort(installed = true)),
            ),
            capabilities = {
                capabilities().copy(
                    chroot = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
                    x11 = CapabilityEvidence(
                        EvidenceState.FAILED_SELF_TEST,
                        "X11 connection failed",
                    ),
                )
            },
            selectionStore = MemorySelectionStore(),
        )

        assertEquals(EnvironmentKind.TERMUX_NATIVE, manager.recommendedKind())
    }

    @Test
    fun existingNativeSelectionWinsEvenWhenRootAppears() {
        val manager = LinuxEnvironmentManager(
            adapters = listOf(
                TermuxNativeEnvironment(FakeLegacyRuntimePort(installed = true)),
                ChrootUbuntuEnvironment(FakeLegacyRuntimePort(installed = true)),
            ),
            capabilities = {
                capabilities().copy(
                    chroot = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
                )
            },
            selectionStore = MemorySelectionStore(EnvironmentKind.TERMUX_NATIVE.wireValue),
        )

        assertEquals(EnvironmentKind.TERMUX_NATIVE, manager.recommendedKind())
    }

    private class MemorySelectionStore(
        private var value: String? = null,
    ) : EnvironmentSelectionStore {
        override fun read(): String? = value
        override fun write(kind: EnvironmentKind) {
            value = kind.wireValue
        }
    }

    private class FakeLegacyRuntimePort(
        override var installed: Boolean = false,
        override var running: Boolean = false,
        override var desktop: String = "",
        override var rootfsPath: String = "",
        override var rootfsSizeBytes: Long = 0,
        override var optionalApps: Map<String, Boolean> = emptyMap(),
    ) : LegacyRuntimePort {
        val calls = mutableListOf<String>()

        override val paths = EnvironmentPaths(
            home = File("/home"),
            applicationDirectories = listOf(File("/apps")),
            packageStatusFiles = listOf(File("/status")),
            snapshotMode = "test",
        )

        override fun provision(
            desktop: String,
            onProgress: (Double, String) -> Unit,
            onLog: (String) -> Unit,
        ): Result<Unit> {
            calls += "provision:$desktop"
            onProgress(0.5, "installing")
            onLog("native log")
            return Result.success(Unit)
        }

        override fun start(desktop: String, profile: DisplayProfile?): Result<Unit> {
            calls += "start:$desktop"
            return Result.success(Unit)
        }

        override fun stop(): Result<Unit> {
            calls += "stop"
            return Result.success(Unit)
        }

        override fun executeShell(
            command: String,
            onOutput: ((String) -> Unit)?,
            interactive: Boolean,
            stdin: Boolean,
        ): ExecutionResult {
            calls += "shell:$command:$interactive:$stdin"
            onOutput?.invoke("shell output")
            return ExecutionResult(0, "shell output")
        }

        override fun executeDesktop(command: String): Result<Unit> {
            calls += "desktop:$command"
            return Result.success(Unit)
        }

        override fun installOptionalApp(
            appId: String,
            onProgress: (Double, String) -> Unit,
            onLog: (String) -> Unit,
        ): Result<Unit> {
            calls += "optional:$appId"
            return Result.success(Unit)
        }

        override fun waitForDesktopReady(desktop: String, timeoutMs: Long): Boolean {
            calls += "ready:$desktop:$timeoutMs"
            return true
        }

        override fun applyDisplayProfile(
            profile: DisplayProfile,
            onComplete: (Result<Unit>) -> Unit,
        ) {
            calls += "profile:${profile.kind.wireName}"
            onComplete(Result.success(Unit))
        }

        override fun cancelDisplayProfile() {
            calls += "cancelProfile"
        }

        override fun readClipboard(): String? {
            calls += "readClipboard"
            return "clipboard"
        }

        override fun writeClipboard(value: String): Boolean {
            calls += "writeClipboard:$value"
            return true
        }
    }

    private companion object {
        fun capabilities(): RuntimeCapabilities {
            val supported = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
            return RuntimeCapabilities(
                hostApiLevel = 36,
                abi = "arm64-v8a",
                host = supported,
                localExecution = supported,
                proot = supported,
                x11 = supported,
                audio = supported,
                clipboard = supported,
                chroot = CapabilityEvidence(EvidenceState.UNAVAILABLE),
                avfFeature = CapabilityEvidence(EvidenceState.UNAVAILABLE),
                avfPermission = CapabilityEvidence(EvidenceState.UNAVAILABLE),
                renderer = supported,
                rendererBackend = RendererBackend.SOFTWARE_LLVMPIPE,
                storage = supported,
                availableBytes = Long.MAX_VALUE,
                requiredBytes = 1,
                distribution = DistributionContract(
                    DistributionChannel.SIDELOAD_LOCAL_LINUX,
                    28,
                    true,
                ),
            )
        }
    }
}
