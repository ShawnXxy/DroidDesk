package com.orailnoor.droiddesk.runtime

import android.content.Context

internal class LinuxEnvironmentManager(
    adapters: List<EnvironmentAdapter>,
    private val capabilities: () -> RuntimeCapabilities,
    private val selectionStore: EnvironmentSelectionStore,
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
        selectedKindOrNull()?.let { return it }
        val detected = capabilities()
        return when {
            detected.chroot.state == EvidenceState.SUPPORTED_AND_PROBED &&
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
        val kind = selectedKindOrNull() ?: recommendedKind()
        return adapter(kind).status(capabilities())
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

    fun activeKind(): EnvironmentKind = selectedKindOrNull() ?: recommendedKind()

    private fun selectedKindOrNull(): EnvironmentKind? {
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
    chrootRuntime: ChrootRuntime = ChrootRuntime(context.applicationContext),
): LinuxEnvironmentManager {
    val probe = RuntimeCapabilityProbe(
        AndroidRuntimeCapabilitySource(context.applicationContext, linuxRuntime, chrootRuntime),
    )
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
        capabilities = probe::probe,
        selectionStore = SharedPreferencesEnvironmentSelectionStore(context.applicationContext),
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
