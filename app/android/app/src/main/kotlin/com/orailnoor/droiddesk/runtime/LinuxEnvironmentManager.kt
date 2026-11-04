package com.orailnoor.droiddesk.runtime

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
        val result = adapter(request.kind).provision(request, onProgress, onLog)
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
        for (candidate in adaptersInOrder) {
            if (!candidate.status(currentCapabilities).running) continue
            val stopped = candidate.stop()
            if (stopped.isFailure) return stopped
        }
        return Result.success(Unit)
    }

    fun execute(request: ExecutionRequest): ExecutionResult =
        adapter(request.kind).execute(request)

    private fun selectedKindOrNull(): EnvironmentKind? {
        val persisted = selectionStore.read() ?: return null
        return EnvironmentKind.fromWire(persisted)
            ?: throw IllegalStateException("Unknown persisted Linux environment: $persisted")
    }

    private fun adapter(kind: EnvironmentKind): EnvironmentAdapter =
        adaptersByKind[kind]
            ?: throw IllegalStateException("Linux environment adapter is unavailable: ${kind.wireValue}")
}
