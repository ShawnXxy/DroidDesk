package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.display.DisplayProfile
import java.io.File

enum class EnvironmentKind(val wireValue: String) {
    TERMUX_NATIVE("termuxNative"),
    PROOT_DEBIAN("prootDebian"),
    CHROOT_UBUNTU("chrootUbuntu");

    companion object {
        fun fromWire(value: String): EnvironmentKind? =
            entries.firstOrNull { it.wireValue == value }

        fun fromSessionMode(value: String): EnvironmentKind = when (value) {
            "chroot" -> CHROOT_UBUNTU
            "proot" -> PROOT_DEBIAN
            else -> TERMUX_NATIVE
        }
    }

    val sessionMode: String
        get() = when (this) {
            TERMUX_NATIVE -> "termux"
            PROOT_DEBIAN -> "proot"
            CHROOT_UBUNTU -> "chroot"
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
    val rootfsPath: String = "",
) {
    fun toLegacyMap(): Map<String, Any?> = mapOf(
        "schemaVersion" to schemaVersion,
        "environmentKind" to kind.wireValue,
        "isBootstrapped" to installed,
        "isRunning" to running,
        "hasRoot" to (
            capabilities.chroot.state == EvidenceState.SUPPORTED_AND_PROBED ||
                capabilities.chroot.state == EvidenceState.PRESENT_UNVERIFIED
            ),
        "distro" to distro,
        "installedDE" to desktop,
        "rootfsPath" to rootfsPath,
        "rootfsSizeMB" to rootfsSizeBytes / (1024L * 1024L),
        "capabilities" to capabilities.toMap(),
        "lastError" to lastError,
    )
}

data class EnvironmentPaths(
    val home: File,
    val applicationDirectories: List<File>,
    val packageStatusFiles: List<File>,
    val snapshotMode: String,
    val guestHome: String = home.absolutePath,
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

    fun optionalAppsStatus(): Map<String, Boolean> = emptyMap()

    fun installOptionalApp(
        appId: String,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Optional apps are unavailable"))

    fun waitForDesktopReady(desktop: String, timeoutMs: Long): Boolean = false

    fun applyDisplayProfile(
        profile: DisplayProfile,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        onComplete(Result.failure(UnsupportedOperationException("Display profiles are unavailable")))
    }

    fun cancelDisplayProfile() = Unit

    fun readClipboard(): String? = null

    fun writeClipboard(value: String): Boolean = false

    fun paths(): EnvironmentPaths =
        throw UnsupportedOperationException("Environment paths are unavailable")
}
