package com.orailnoor.droiddesk.runtime

import android.content.Context

internal class LinuxEnvironmentManager(
    adapters: List<EnvironmentAdapter>,
    private val capabilities: () -> RuntimeCapabilities,
    private val selectionStore: EnvironmentSelectionStore,
    private val legacySelection: (RuntimeCapabilities) -> EnvironmentKind? = { null },
) {
    private val adaptersInOrder = adapters.toList()
    private val adaptersByKind = adapters.associateBy(EnvironmentAdapter::kind)

    init {
        require(adaptersByKind.isNotEmpty()) { "At least one Linux environment adapter is required" }
        require(adaptersByKind.size == adaptersInOrder.size) {
            "Each Linux environment kind may have only one adapter"
        }
    }

    fun recommendedKind(): EnvironmentKind {
        persistedKindOrNull()?.let { return it }
        val detected = capabilities()
        val migrated = legacySelection(detected)?.takeIf(adaptersByKind::containsKey)
        if (migrated != null) {
            selectionStore.write(migrated)
            return migrated
        }
        return recommendedKind(detected)
    }

    private fun recommendedKind(detected: RuntimeCapabilities): EnvironmentKind {
        return when {
            detected.chroot.state == EvidenceState.SUPPORTED_AND_PROBED &&
                !detected.x11.blocksProvisioning &&
                adaptersByKind.containsKey(EnvironmentKind.CHROOT_UBUNTU) ->
                EnvironmentKind.CHROOT_UBUNTU
            detected.prootCandidate && adaptersByKind.containsKey(EnvironmentKind.PROOT_DEBIAN) ->
                EnvironmentKind.PROOT_DEBIAN
            adaptersByKind.containsKey(EnvironmentKind.TERMUX_NATIVE) ->
                EnvironmentKind.TERMUX_NATIVE
            else -> adaptersInOrder.first().kind
        }
    }

    fun status(): EnvironmentStatus {
        val detected = capabilities()
        val kind = selectedKindOrNull(detected) ?: recommendedKind(detected)
        return adapter(kind).status(detected)
    }

    fun provision(
        request: ProvisionRequest,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Result<Unit> {
        val result = runCatching {
            adapter(request.kind).provision(request, onProgress, onLog).getOrThrow()
        }
        if (result.isSuccess) selectionStore.write(request.kind)
        return result
    }

    fun start(request: StartRequest): Result<Unit> {
        val currentCapabilities = capabilities()
        for (candidate in adaptersInOrder) {
            val status = candidate.status(currentCapabilities)
            if (status.running && candidate.kind != request.kind) {
                val stopped = candidate.stop()
                if (stopped.isFailure) return stopped
            }
        }
        val result = adapter(request.kind).start(request)
        if (result.isSuccess) selectionStore.write(request.kind)
        return result
    }

    fun stop(): Result<Unit> {
        val currentCapabilities = capabilities()
        val selectedKind = selectedKindOrNull()
        for (candidate in adaptersInOrder) {
            if (candidate.kind != selectedKind && !candidate.status(currentCapabilities).running) {
                continue
            }
            val stopped = candidate.stop()
            if (stopped.isFailure) return stopped
        }
        return Result.success(Unit)
    }

    fun execute(request: ExecutionRequest): ExecutionResult =
        adapter(request.kind).execute(request)

    fun optionalAppsStatus(kind: EnvironmentKind = activeKind()): Map<String, Boolean> =
        adapter(kind).optionalAppsStatus()

    fun installOptionalApp(
        appId: String,
        kind: EnvironmentKind = activeKind(),
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Result<Unit> = adapter(kind).installOptionalApp(appId, onProgress, onLog)

    fun waitForDesktopReady(
        desktop: String,
        timeoutMs: Long = 45_000,
        kind: EnvironmentKind = activeKind(),
    ): Boolean = adapter(kind).waitForDesktopReady(desktop, timeoutMs)

    fun applyDisplayProfile(
        profile: com.orailnoor.droiddesk.display.DisplayProfile,
        kind: EnvironmentKind = activeKind(),
        onComplete: (Result<Unit>) -> Unit,
    ) = adapter(kind).applyDisplayProfile(profile, onComplete)

    fun cancelDisplayProfile(kind: EnvironmentKind = activeKind()) =
        adapter(kind).cancelDisplayProfile()

    fun readClipboard(kind: EnvironmentKind = activeKind()): String? =
        adapter(kind).readClipboard()

    fun writeClipboard(value: String, kind: EnvironmentKind = activeKind()): Boolean =
        adapter(kind).writeClipboard(value)

    fun paths(kind: EnvironmentKind = activeKind()): EnvironmentPaths =
        adapter(kind).paths()

    fun activeKind(): EnvironmentKind {
        return persistedKindOrNull() ?: recommendedKind()
    }

    private fun selectedKindOrNull(
        detected: RuntimeCapabilities? = null,
    ): EnvironmentKind? {
        persistedKindOrNull()?.let { return it }
        val migrated = legacySelection(detected ?: capabilities())
            ?.takeIf(adaptersByKind::containsKey)
            ?: return null
        selectionStore.write(migrated)
        return migrated
    }

    private fun persistedKindOrNull(): EnvironmentKind? {
        val persisted = selectionStore.read() ?: return null
        return EnvironmentKind.fromWire(persisted)
            ?: throw IllegalStateException("Unknown persisted Linux environment: $persisted")
    }

    private fun adapter(kind: EnvironmentKind): EnvironmentAdapter =
        adaptersByKind[kind]
            ?: throw IllegalStateException("Linux environment adapter is unavailable: ${kind.wireValue}")
}

internal class SharedPreferencesEnvironmentSelectionStore(
    context: Context,
) : EnvironmentSelectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "linux_environment",
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString(KEY_SELECTED, null)

    override fun write(kind: EnvironmentKind) {
        preferences.edit().putString(KEY_SELECTED, kind.wireValue).apply()
    }

    private companion object {
        const val KEY_SELECTED = "selected_environment"
    }
}

internal fun createLinuxEnvironmentManager(
    context: Context,
    linuxRuntime: LinuxRuntime = LinuxRuntime(context.applicationContext),
    chrootRuntime: ChrootRuntime = ChrootRuntime(context.applicationContext, linuxRuntime),
    capabilities: (() -> RuntimeCapabilities)? = null,
): LinuxEnvironmentManager {
    val probe = RuntimeCapabilityProbe(
        AndroidRuntimeCapabilitySource(context.applicationContext, linuxRuntime, chrootRuntime),
    )
    val capabilityProvider = capabilities ?: RuntimeCapabilityCache(
        probe::probe,
        probe.pending(),
    )::current
    return LinuxEnvironmentManager(
        adapters = listOf(
            TermuxNativeEnvironment(context.applicationContext, linuxRuntime),
            AndroidProotDebianHost(context.applicationContext, linuxRuntime).let { host ->
                ProotDebianEnvironment(
                    host = host,
                    sessionController = ProotDebianSessionRegistry.getOrCreate(
                        host,
                        AndroidEnvironmentProcessRunner(context.applicationContext),
                    ),
                )
            },
            ChrootUbuntuEnvironment(context.applicationContext, chrootRuntime),
        ),
        capabilities = capabilityProvider,
        selectionStore = SharedPreferencesEnvironmentSelectionStore(context.applicationContext),
        legacySelection = { detected ->
            when {
                detected.chroot.state == EvidenceState.SUPPORTED_AND_PROBED &&
                    !detected.x11.blocksProvisioning &&
                    chrootRuntime.isRootfsReady() &&
                    chrootRuntime.isDesktopInstalled() ->
                    EnvironmentKind.CHROOT_UBUNTU
                linuxRuntime.isBootstrapped() && linuxRuntime.getInstalledDE().isNotEmpty() ->
                    EnvironmentKind.TERMUX_NATIVE
                else -> null
            }
        },
    )
}

internal object ProotDebianSessionRegistry {
    @Volatile private var controller: ProotDebianSessionController? = null

    fun getOrCreate(
        host: ProotDesktopSessionHost,
        runner: EnvironmentProcessRunner,
    ): ProotDebianSessionController =
        controller ?: synchronized(this) {
            controller ?: ProotDebianSessionController(host, runner).also {
                controller = it
            }
        }

    fun isRunning(): Boolean = controller?.isRunning() == true
}
