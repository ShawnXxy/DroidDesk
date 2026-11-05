package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.display.DisplayProfilePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinuxEnvironmentManagerTest {

    @Test
    fun status_reports_selected_adapter_without_querying_other_adapters() {
        val native = FakeAdapter(EnvironmentKind.TERMUX_NATIVE)
        val proot = FakeAdapter(EnvironmentKind.PROOT_DEBIAN)
        val chroot = FakeAdapter(EnvironmentKind.CHROOT_UBUNTU)
        val manager = manager(
            adapters = listOf(native, proot, chroot),
            selection = FakeSelectionStore(EnvironmentKind.TERMUX_NATIVE.wireValue),
        )

        val status = manager.status()

        assertEquals(EnvironmentKind.TERMUX_NATIVE, status.kind)
        assertEquals(1, native.statusCalls)
        assertEquals(0, proot.statusCalls)
        assertEquals(0, chroot.statusCalls)
    }

    @Test
    fun start_stops_a_different_running_adapter_first() {
        val calls = mutableListOf<String>()
        val native = FakeAdapter(EnvironmentKind.TERMUX_NATIVE, calls)
        val proot = FakeAdapter(EnvironmentKind.PROOT_DEBIAN, calls).apply {
            currentStatus = status(kind, installed = true, running = true)
        }
        val chroot = FakeAdapter(EnvironmentKind.CHROOT_UBUNTU, calls).apply {
            currentStatus = status(kind, installed = true)
        }
        val manager = manager(listOf(native, proot, chroot))

        val result = manager.start(
            StartRequest(
                kind = EnvironmentKind.CHROOT_UBUNTU,
                desktop = "xfce4",
                displayProfile = DisplayProfilePolicy.phoneDefaultProfile(),
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "status:TERMUX_NATIVE",
                "status:PROOT_DEBIAN",
                "stop:PROOT_DEBIAN",
                "status:CHROOT_UBUNTU",
                "start:CHROOT_UBUNTU",
            ),
            calls,
        )
    }

    @Test
    fun failed_stop_prevents_new_adapter_start() {
        val calls = mutableListOf<String>()
        val proot = FakeAdapter(EnvironmentKind.PROOT_DEBIAN, calls).apply {
            currentStatus = status(kind, installed = true, running = true)
            stopResult = Result.failure(IllegalStateException("session refused to stop"))
        }
        val chroot = FakeAdapter(EnvironmentKind.CHROOT_UBUNTU, calls).apply {
            currentStatus = status(kind, installed = true)
        }
        val manager = manager(listOf(proot, chroot))

        val result = manager.start(
            StartRequest(EnvironmentKind.CHROOT_UBUNTU, "xfce4", null),
        )

        assertTrue(result.isFailure)
        assertEquals("session refused to stop", result.exceptionOrNull()!!.message)
        assertFalse(calls.contains("start:CHROOT_UBUNTU"))
    }

    @Test
    fun stop_cleans_selected_adapter_after_its_root_process_has_exited() {
        val calls = mutableListOf<String>()
        val proot = FakeAdapter(EnvironmentKind.PROOT_DEBIAN, calls).apply {
            currentStatus = status(kind, installed = true, running = false)
        }
        val manager = manager(
            adapters = listOf(proot),
            selection = FakeSelectionStore(EnvironmentKind.PROOT_DEBIAN.wireValue),
        )

        val result = manager.stop()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("stop:PROOT_DEBIAN"),
            calls,
        )
    }

    @Test
    fun unknown_persisted_kind_does_not_fall_back_silently() {
        val manager = manager(
            adapters = listOf(FakeAdapter(EnvironmentKind.TERMUX_NATIVE)),
            selection = FakeSelectionStore("mystery-runtime"),
        )

        try {
            manager.status()
            fail("Expected an invalid persisted environment to fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("mystery-runtime"))
        }
    }

    @Test
    fun provision_forwards_progress_and_preserves_adapter_error() {
        val expected = IllegalStateException("mirror unavailable")
        val proot = FakeAdapter(EnvironmentKind.PROOT_DEBIAN).apply {
            provisionResult = Result.failure(expected)
            provisionProgress = 0.5 to "Downloading Debian"
            provisionLog = "network failed\n"
        }
        val progress = mutableListOf<Pair<Double, String>>()
        val logs = mutableListOf<String>()
        val selection = FakeSelectionStore()
        val manager = manager(listOf(proot), selection = selection)

        val result = manager.provision(
            request = ProvisionRequest(EnvironmentKind.PROOT_DEBIAN, "xfce4"),
            onProgress = { value, message -> progress += value to message },
            onLog = logs::add,
        )

        assertSame(expected, result.exceptionOrNull())
        assertEquals(listOf(0.5 to "Downloading Debian"), progress)
        assertEquals(listOf("network failed\n"), logs)
        assertEquals(null, selection.value)
    }

    @Test
    fun provision_returns_failure_when_requested_adapter_is_unavailable() {
        val manager = manager(
            adapters = listOf(FakeAdapter(EnvironmentKind.TERMUX_NATIVE)),
        )

        val result = manager.provision(
            ProvisionRequest(EnvironmentKind.PROOT_DEBIAN, "xfce4"),
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains(
                "Linux environment adapter is unavailable: prootDebian",
            ),
        )
    }

    @Test
    fun proot_is_not_recommended_when_a_required_probe_failed() {
        val capabilities = capabilities(
            proot = CapabilityEvidence(
                EvidenceState.FAILED_SELF_TEST,
                "ptrace self-test failed",
            ),
        )
        val manager = manager(
            adapters = listOf(
                FakeAdapter(EnvironmentKind.TERMUX_NATIVE),
                FakeAdapter(EnvironmentKind.PROOT_DEBIAN),
            ),
            capabilities = capabilities,
        )

        assertEquals(EnvironmentKind.TERMUX_NATIVE, manager.recommendedKind())
    }

    @Test
    fun existing_user_selection_wins_over_a_new_recommendation() {
        val manager = manager(
            adapters = listOf(
                FakeAdapter(EnvironmentKind.TERMUX_NATIVE),
                FakeAdapter(EnvironmentKind.PROOT_DEBIAN),
            ),
            capabilities = capabilities(),
            selection = FakeSelectionStore(EnvironmentKind.TERMUX_NATIVE.wireValue),
        )

        assertEquals(EnvironmentKind.TERMUX_NATIVE, manager.recommendedKind())
    }

    @Test
    fun legacy_native_install_is_persisted_before_a_new_debian_recommendation() {
        val selection = FakeSelectionStore()
        val manager = manager(
            adapters = listOf(
                FakeAdapter(EnvironmentKind.TERMUX_NATIVE),
                FakeAdapter(EnvironmentKind.PROOT_DEBIAN),
            ),
            capabilities = capabilities(),
            selection = selection,
            legacySelection = { EnvironmentKind.TERMUX_NATIVE },
        )

        assertEquals(EnvironmentKind.TERMUX_NATIVE, manager.recommendedKind())
        assertEquals(EnvironmentKind.TERMUX_NATIVE.wireValue, selection.value)
    }

    private fun manager(
        adapters: List<EnvironmentAdapter>,
        capabilities: RuntimeCapabilities = capabilities(),
        selection: FakeSelectionStore = FakeSelectionStore(),
        legacySelection: (RuntimeCapabilities) -> EnvironmentKind? = { null },
    ) = LinuxEnvironmentManager(
        adapters = adapters,
        capabilities = { capabilities },
        selectionStore = selection,
        legacySelection = legacySelection,
    )

    private class FakeSelectionStore(
        var value: String? = null,
    ) : EnvironmentSelectionStore {
        override fun read(): String? = value

        override fun write(kind: EnvironmentKind) {
            value = kind.wireValue
        }
    }

    private class FakeAdapter(
        override val kind: EnvironmentKind,
        private val calls: MutableList<String> = mutableListOf(),
    ) : EnvironmentAdapter {
        var statusCalls = 0
        var currentStatus = status(kind, installed = true)
        var provisionResult: Result<Unit> = Result.success(Unit)
        var provisionProgress: Pair<Double, String>? = null
        var provisionLog: String? = null
        var stopResult: Result<Unit> = Result.success(Unit)

        override fun status(capabilities: RuntimeCapabilities): EnvironmentStatus {
            statusCalls++
            calls += "status:$kind"
            return currentStatus.copy(capabilities = capabilities)
        }

        override fun provision(
            request: ProvisionRequest,
            onProgress: (Double, String) -> Unit,
            onLog: (String) -> Unit,
        ): Result<Unit> {
            provisionProgress?.let { onProgress(it.first, it.second) }
            provisionLog?.let(onLog)
            return provisionResult
        }

        override fun start(request: StartRequest): Result<Unit> {
            calls += "start:$kind"
            return Result.success(Unit)
        }

        override fun stop(): Result<Unit> {
            calls += "stop:$kind"
            return stopResult
        }

        override fun execute(request: ExecutionRequest): ExecutionResult =
            ExecutionResult(0, "")
    }

    private companion object {
        fun status(
            kind: EnvironmentKind,
            installed: Boolean,
            running: Boolean = false,
        ) = EnvironmentStatus(
            schemaVersion = 1,
            kind = kind,
            installed = installed,
            running = running,
            provisioning = false,
            repairNeeded = false,
            distro = when (kind) {
                EnvironmentKind.TERMUX_NATIVE -> "termux-native"
                EnvironmentKind.PROOT_DEBIAN -> "debian"
                EnvironmentKind.CHROOT_UBUNTU -> "ubuntu"
            },
            desktop = if (installed) "xfce4" else "",
            rootfsSizeBytes = 0,
            capabilities = capabilities(),
            lastError = null,
        )

        fun capabilities(
            proot: CapabilityEvidence =
                CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
        ): RuntimeCapabilities {
            val supported = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
            return RuntimeCapabilities(
                hostApiLevel = 36,
                abi = "arm64-v8a",
                host = supported,
                localExecution = supported,
                proot = proot,
                x11 = supported,
                audio = supported,
                clipboard = supported,
                chroot = CapabilityEvidence(EvidenceState.UNAVAILABLE),
                avfFeature = CapabilityEvidence(EvidenceState.UNAVAILABLE),
                avfPermission = CapabilityEvidence(EvidenceState.UNAVAILABLE),
                renderer = supported,
                rendererBackend = RendererBackend.SOFTWARE_LLVMPIPE,
                storage = supported,
                availableBytes = 64L * 1024L * 1024L * 1024L,
                requiredBytes = 6L * 1024L * 1024L * 1024L,
                distribution = DistributionContract(
                    DistributionChannel.SIDELOAD_LOCAL_LINUX,
                    28,
                    true,
                ),
            )
        }
    }
}
