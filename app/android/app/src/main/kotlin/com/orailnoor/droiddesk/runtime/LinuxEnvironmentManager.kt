package com.orailnoor.droiddesk.runtime

import android.content.Context

internal interface EnvironmentStopAdmission : AutoCloseable {
    fun stop(): Result<Unit>
}

internal class LinuxEnvironmentManager(
    adapters: List<EnvironmentAdapter>,
    private val capabilities: () -> RuntimeCapabilities,
    private val selectionStore: EnvironmentSelectionStore,
    private val legacySelection: (RuntimeCapabilities) -> EnvironmentKind? = { null },
) {
    private val adaptersInOrder = adapters.toList()
    private val adaptersByKind = adapters.associateBy(EnvironmentAdapter::kind)
    private val selectionGate = EnvironmentSelectionGate(::activeKind)

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
    ): Result<Unit> = runCatching {
        selectionGate.withSelectionTransition {
            adapter(request.kind).provision(request, onProgress, onLog).getOrThrow()
            selectionStore.write(request.kind)
        }
    }

    fun start(request: StartRequest): Result<Unit> = runCatching {
        selectionGate.withSelectionTransition {
            val currentCapabilities = capabilities()
            for (candidate in adaptersInOrder) {
                val status = candidate.status(currentCapabilities)
                if (status.running && candidate.kind != request.kind) {
                    candidate.stop().getOrThrow()
                }
            }
            adapter(request.kind).start(request).getOrThrow()
            selectionStore.write(request.kind)
        }
    }

    fun stop(): Result<Unit> = runCatching {
        selectionGate.withSelectionTransition {
            stopAdapters()
        }
    }

    fun acquireStopAdmission(): EnvironmentStopAdmission {
        val transition = selectionGate.acquireSelectionTransition()
        return object : EnvironmentStopAdmission {
            override fun stop(): Result<Unit> = runCatching { stopAdapters() }
            override fun close() = transition.close()
        }
    }

    fun execute(request: ExecutionRequest): ExecutionResult =
        adapter(request.kind).execute(request)

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

    fun <T> withActivePackageTarget(
        expectedKind: EnvironmentKind,
        action: () -> T,
    ): T = selectionGate.withActivePackageTarget(expectedKind, action)

    private fun stopAdapters() {
        val currentCapabilities = capabilities()
        val selectedKind = selectedKindOrNull()
        for (candidate in adaptersInOrder) {
            if (
                candidate.kind != selectedKind &&
                !candidate.status(currentCapabilities).running
            ) {
                continue
            }
            candidate.stop().getOrThrow()
        }
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
    val capabilityProvider = capabilities ?: RuntimeCapabilityRegistry.get(
        context.applicationContext,
        linuxRuntime,
        chrootRuntime,
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
            legacyEnvironmentSelection(
                detected = detected,
                chrootInstalled =
                    chrootRuntime.isRootfsReady() && chrootRuntime.isDesktopInstalled(),
                nativeInstalled =
                    linuxRuntime.isBootstrapped() && linuxRuntime.getInstalledDE().isNotEmpty(),
            )
        },
    )
}

internal data class ProcessLinuxEnvironmentRuntime(
    val linuxRuntime: LinuxRuntime,
    val chrootRuntime: ChrootRuntime,
    val capabilityCache: RuntimeCapabilityCache,
    val environmentManager: LinuxEnvironmentManager,
)

internal object ProcessLinuxEnvironmentRegistry {
    private val lock = Any()
    @Volatile private var runtime: ProcessLinuxEnvironmentRuntime? = null

    fun get(context: Context): ProcessLinuxEnvironmentRuntime {
        runtime?.let { return it }
        return synchronized(lock) {
            runtime ?: create(context.applicationContext).also { runtime = it }
        }
    }

    private fun create(context: Context): ProcessLinuxEnvironmentRuntime {
        val linuxRuntime = LinuxRuntime(context)
        val chrootRuntime = ChrootRuntime(context, linuxRuntime)
        val capabilityCache = RuntimeCapabilityRegistry.get(
            context,
            linuxRuntime,
            chrootRuntime,
        )
        return ProcessLinuxEnvironmentRuntime(
            linuxRuntime = linuxRuntime,
            chrootRuntime = chrootRuntime,
            capabilityCache = capabilityCache,
            environmentManager = createLinuxEnvironmentManager(
                context,
                linuxRuntime,
                chrootRuntime,
                capabilityCache::current,
            ),
        )
    }
}

internal fun legacyEnvironmentSelection(
    detected: RuntimeCapabilities,
    chrootInstalled: Boolean,
    nativeInstalled: Boolean,
): EnvironmentKind? = when {
    chrootInstalled &&
        detected.chroot.state == EvidenceState.SUPPORTED_AND_PROBED &&
        !detected.x11.blocksProvisioning ->
        EnvironmentKind.CHROOT_UBUNTU
    chrootInstalled &&
        detected.chroot.state == EvidenceState.PRESENT_UNVERIFIED &&
        detected.chroot.reason == RuntimeCapabilityProbe.PENDING_REASON ->
        null
    nativeInstalled -> EnvironmentKind.TERMUX_NATIVE
    else -> null
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
