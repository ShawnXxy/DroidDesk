package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.display.DisplayProfile

enum class EnvironmentKind(val wireValue: String) {
    TERMUX_NATIVE("termuxNative"),
    PROOT_DEBIAN("prootDebian"),
    CHROOT_UBUNTU("chrootUbuntu");

    companion object {
        fun fromWire(value: String): EnvironmentKind? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class EnvironmentStatus(
    val schemaVersion: Int,
    val kind: EnvironmentKind,
    val installed: Boolean,
    val running: Boolean,
    val provisioning: Boolean,
    val repairNeeded: Boolean,
    val distro: String,
    val desktop: String,
    val rootfsSizeBytes: Long,
    val capabilities: RuntimeCapabilities,
    val lastError: String?,
)

data class ProvisionRequest(
    val kind: EnvironmentKind,
    val desktop: String,
)

data class StartRequest(
    val kind: EnvironmentKind,
    val desktop: String,
    val displayProfile: DisplayProfile?,
)

sealed interface ExecutionRequest {
    val kind: EnvironmentKind

    data class Shell(
        override val kind: EnvironmentKind,
        val command: String,
        val onOutput: ((String) -> Unit)? = null,
    ) : ExecutionRequest

    data class Desktop(
        override val kind: EnvironmentKind,
        val command: String,
    ) : ExecutionRequest
}

data class ExecutionResult(
    val exitCode: Int,
    val output: String,
)

interface EnvironmentSelectionStore {
    fun read(): String?
    fun write(kind: EnvironmentKind)
}

internal interface EnvironmentAdapter {
    val kind: EnvironmentKind

    fun status(capabilities: RuntimeCapabilities): EnvironmentStatus

    fun provision(
        request: ProvisionRequest,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit>

    fun start(request: StartRequest): Result<Unit>

    fun stop(): Result<Unit>

    fun execute(request: ExecutionRequest): ExecutionResult
}
