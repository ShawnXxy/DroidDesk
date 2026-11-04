package com.orailnoor.droiddesk.runtime

import android.content.Context
import com.orailnoor.droiddesk.display.DisplayProfile
import java.io.File

internal interface LegacyRuntimePort {
    val installed: Boolean
    val running: Boolean
    val desktop: String
    val rootfsPath: String
    val rootfsSizeBytes: Long
    val optionalApps: Map<String, Boolean>
    val paths: EnvironmentPaths

    fun provision(
        desktop: String,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit>

    fun start(desktop: String, profile: DisplayProfile?): Result<Unit>
    fun stop(): Result<Unit>
    fun executeShell(command: String, onOutput: ((String) -> Unit)?): ExecutionResult
    fun executeDesktop(command: String): Result<Unit>
    fun installOptionalApp(
        appId: String,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit>
    fun waitForDesktopReady(desktop: String, timeoutMs: Long): Boolean
    fun applyDisplayProfile(profile: DisplayProfile, onComplete: (Result<Unit>) -> Unit)
    fun cancelDisplayProfile()
    fun readClipboard(): String?
    fun writeClipboard(value: String): Boolean
}

internal abstract class LegacyEnvironmentAdapter(
    override val kind: EnvironmentKind,
    private val distro: String,
    protected val port: LegacyRuntimePort,
) : EnvironmentAdapter {
    override fun status(capabilities: RuntimeCapabilities): EnvironmentStatus =
        EnvironmentStatus(
            schemaVersion = 1,
            kind = kind,
            installed = port.installed,
            running = port.running,
            provisioning = false,
            repairNeeded = false,
            distro = distro,
            desktop = port.desktop,
            rootfsSizeBytes = port.rootfsSizeBytes,
            capabilities = capabilities,
            lastError = null,
            rootfsPath = port.rootfsPath,
        )

    override fun provision(
        request: ProvisionRequest,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit> = port.provision(request.desktop, onProgress, onLog)

    override fun start(request: StartRequest): Result<Unit> =
        port.start(request.desktop, request.displayProfile)

    override fun stop(): Result<Unit> = port.stop()

    override fun execute(request: ExecutionRequest): ExecutionResult = when (request) {
        is ExecutionRequest.Shell -> port.executeShell(request.command, request.onOutput)
        is ExecutionRequest.Desktop -> {
            val result = port.executeDesktop(request.command)
            ExecutionResult(
                exitCode = if (result.isSuccess) 0 else 1,
                output = result.exceptionOrNull()?.message.orEmpty(),
            )
        }
    }

    override fun optionalAppsStatus(): Map<String, Boolean> = port.optionalApps

    override fun installOptionalApp(
        appId: String,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit> = port.installOptionalApp(appId, onProgress, onLog)

    override fun waitForDesktopReady(desktop: String, timeoutMs: Long): Boolean =
        port.waitForDesktopReady(desktop, timeoutMs)

    override fun applyDisplayProfile(
        profile: DisplayProfile,
        onComplete: (Result<Unit>) -> Unit,
    ) = port.applyDisplayProfile(profile, onComplete)

    override fun cancelDisplayProfile() = port.cancelDisplayProfile()
    override fun readClipboard(): String? = port.readClipboard()
    override fun writeClipboard(value: String): Boolean = port.writeClipboard(value)
    override fun paths(): EnvironmentPaths = port.paths
}

internal class TermuxNativeEnvironment(
    port: LegacyRuntimePort,
) : LegacyEnvironmentAdapter(
    kind = EnvironmentKind.TERMUX_NATIVE,
    distro = "termux-native",
    port = port,
) {
    constructor(context: Context, runtime: LinuxRuntime) : this(
        object : LegacyRuntimePort {
            private val filesDir = context.applicationContext.filesDir

            override val installed: Boolean
                get() = runtime.isBootstrapped() && runtime.getInstalledDE().isNotEmpty()
            override val running: Boolean get() = runtime.isRunning()
            override val desktop: String get() = runtime.getInstalledDE()
            override val rootfsPath: String = ""
            override val rootfsSizeBytes: Long = 0
            override val optionalApps: Map<String, Boolean>
                get() = runtime.getOptionalAppsStatus()
            override val paths: EnvironmentPaths
                get() {
                    val home = File(filesDir, "home")
                    return EnvironmentPaths(
                        home = home,
                        applicationDirectories = listOf(
                            File(filesDir, "usr/share/applications"),
                            File(home, ".local/share/applications"),
                        ),
                        packageStatusFiles = listOf(
                            File(filesDir, "usr/var/lib/dpkg/status"),
                            File(filesDir, "dpkgroot/var/lib/dpkg/status"),
                        ),
                        snapshotMode = "native",
                    )
                }

            override fun provision(
                desktop: String,
                onProgress: (Double, String) -> Unit,
                onLog: (String) -> Unit,
            ): Result<Unit> {
                runtime.setInstallLogSink(onLog)
                return try {
                    runtime.extractBootstrapIfNeeded(context.applicationContext)
                    if (runtime.installDesktopEnvironmentNative(desktop, onProgress)) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("Native desktop installation failed"))
                    }
                } finally {
                    runtime.setInstallLogSink(null)
                }
            }

            override fun start(desktop: String, profile: DisplayProfile?): Result<Unit> =
                runCatching {
                    runtime.startSession(desktop, "x11", profile)
                    check(runtime.isRunning()) { "Native Linux session did not start" }
                }

            override fun stop(): Result<Unit> = runCatching { runtime.stopSession() }

            override fun executeShell(
                command: String,
                onOutput: ((String) -> Unit)?,
            ): ExecutionResult {
                val output = runtime.executeCommand(command, onOutput)
                return ExecutionResult(if (output.startsWith("Error:")) 1 else 0, output)
            }

            override fun executeDesktop(command: String): Result<Unit> =
                runtime.executeDesktopCommand(command)

            override fun installOptionalApp(
                appId: String,
                onProgress: (Double, String) -> Unit,
                onLog: (String) -> Unit,
            ): Result<Unit> {
                runtime.setInstallLogSink(onLog)
                return try {
                    if (runtime.installOptionalApp(appId, onProgress)) Result.success(Unit)
                    else Result.failure(IllegalStateException("Optional app installation failed"))
                } finally {
                    runtime.setInstallLogSink(null)
                }
            }

            override fun waitForDesktopReady(desktop: String, timeoutMs: Long): Boolean =
                runtime.waitForDesktopReady(desktop, timeoutMs)

            override fun applyDisplayProfile(
                profile: DisplayProfile,
                onComplete: (Result<Unit>) -> Unit,
            ) = runtime.applyDisplayProfile(profile, onComplete)

            override fun cancelDisplayProfile() = runtime.cancelDisplayProfile()
            override fun readClipboard(): String? = runtime.readLinuxClipboard()
            override fun writeClipboard(value: String): Boolean = runtime.writeLinuxClipboard(value)
        },
    )
}
