package com.orailnoor.droiddesk.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import kotlin.concurrent.thread

interface EnvironmentProcess {
    val pid: Int
    fun isAlive(): Boolean
}

data class EnvironmentExitMetadata(
    val reason: String,
    val description: String? = null,
)

interface EnvironmentProcessRunner {
    fun start(command: List<String>, onOutput: (String) -> Unit): EnvironmentProcess
    fun descendants(process: EnvironmentProcess): List<Int>
    fun terminate(pid: Int, force: Boolean)
    fun exitMetadata(process: EnvironmentProcess): EnvironmentExitMetadata?
}

data class ProotDesktopPolicy(
    val maxRefreshHz: Int = 60,
    val compositorEnabled: Boolean = false,
)

interface ProotDesktopSessionHost {
    fun isDesktopReady(): Boolean
    fun prepareHostSession(): Result<Unit> = Result.success(Unit)
    fun stopHostSession(): Result<Unit> = Result.success(Unit)
    fun launchCommand(profile: com.orailnoor.droiddesk.display.DisplayProfile): List<String>
    fun applyProfile(
        profile: com.orailnoor.droiddesk.display.DisplayProfile,
        guestHome: String,
        policy: ProotDesktopPolicy,
    ): Result<Unit>
    fun waitForDesktopReady(timeoutMs: Long): Boolean
    fun desktopProcessesAlive(): Boolean
}

class ProotDebianSessionController(
    private val host: ProotDesktopSessionHost,
    private val runner: EnvironmentProcessRunner,
) {
    private var rootProcess: EnvironmentProcess? = null
    private var desktopConfirmed = false
    private var sessionFailed = false

    var lastError: String? = null
        private set

    fun start(
        profile: com.orailnoor.droiddesk.display.DisplayProfile,
        onOutput: (String) -> Unit = {},
    ): Result<Unit> {
        val existing = rootProcess
        if (existing?.isAlive() == true && !sessionFailed) return Result.success(Unit)
        if (!host.isDesktopReady()) {
            return Result.failure(IllegalStateException("Debian PRoot desktop is not ready"))
        }
        var hostPreparationAttempted = false
        return runCatching {
            if (existing != null) {
                stop().getOrThrow()
            }
            hostPreparationAttempted = true
            host.prepareHostSession().getOrThrow()
            host.applyProfile(
                profile,
                guestHome = "/home/droiddesk",
                policy = ProotDesktopPolicy(),
            ).getOrThrow()
            val process = runner.start(host.launchCommand(profile), onOutput)
            check(process.isAlive()) { "PRoot desktop process exited during startup" }
            rootProcess = process
            desktopConfirmed = false
            sessionFailed = false
            lastError = null
        }.onFailure { startupFailure ->
            if (hostPreparationAttempted) {
                val cleanupFailure = runCatching {
                    host.stopHostSession().getOrThrow()
                }.exceptionOrNull()
                if (cleanupFailure != null && cleanupFailure !== startupFailure) {
                    startupFailure.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    fun isRunning(): Boolean {
        val process = rootProcess ?: return false
        val rootAlive = process.isAlive()
        if (!rootAlive) {
            val exit = runner.exitMetadata(process)
            lastError = buildString {
                append(exit?.reason ?: "PROCESS_EXITED")
                exit?.description?.takeIf(String::isNotBlank)?.let { append(": $it") }
            }
            rootProcess = null
            desktopConfirmed = false
            sessionFailed = true
            return false
        }
        val desktopAlive = !desktopConfirmed || host.desktopProcessesAlive()
        if (!desktopAlive) {
            lastError = "Required XFCE desktop process exited"
            sessionFailed = true
            return false
        }
        return !sessionFailed
    }

    fun waitForDesktopReady(timeoutMs: Long): Boolean {
        if (!isRunning()) return false
        val ready = host.waitForDesktopReady(timeoutMs)
        desktopConfirmed = ready
        if (!ready) {
            lastError = "Required XFCE desktop processes were not ready"
            sessionFailed = true
        }
        return ready
    }

    fun applyProfile(
        profile: com.orailnoor.droiddesk.display.DisplayProfile,
    ): Result<Unit> = host.applyProfile(
        profile,
        guestHome = "/home/droiddesk",
        policy = ProotDesktopPolicy(),
    )

    fun stop(): Result<Unit> = runCatching {
        val process = rootProcess
        if (process != null) {
            val descendants = runner.descendants(process).asReversed()
            descendants.forEach { runner.terminate(it, force = false) }
            runner.terminate(process.pid, force = false)
            descendants.forEach { runner.terminate(it, force = true) }
            runner.terminate(process.pid, force = true)
        }
        host.stopHostSession().getOrThrow()
        rootProcess = null
        desktopConfirmed = false
        sessionFailed = false
    }
}

internal class AndroidEnvironmentProcessRunner(
    context: Context,
) : EnvironmentProcessRunner {
    private val appContext = context.applicationContext

    override fun start(command: List<String>, onOutput: (String) -> Unit): EnvironmentProcess {
        val process = ProcessBuilder(command)
            .directory(appContext.filesDir)
            .redirectErrorStream(true)
            .start()
        val managed = AndroidEnvironmentProcess(process)
        thread(name = "proot-desktop-output", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { onOutput("$it\n") }
            }
        }
        return managed
    }

    override fun descendants(process: EnvironmentProcess): List<Int> {
        val result = mutableListOf<Int>()
        fun collect(pid: Int) {
            val children = runCatching {
                File("/proc/$pid/task/$pid/children").readText()
                    .trim()
                    .split(Regex("\\s+"))
                    .mapNotNull(String::toIntOrNull)
            }.getOrDefault(emptyList())
            children.forEach { child ->
                result += child
                collect(child)
            }
        }
        collect(process.pid)
        return result
    }

    override fun terminate(pid: Int, force: Boolean) {
        android.os.Process.sendSignal(pid, if (force) 9 else 15)
    }

    override fun exitMetadata(process: EnvironmentProcess): EnvironmentExitMetadata? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val exit = activityManager.getHistoricalProcessExitReasons(
            appContext.packageName,
            process.pid,
            1,
        ).firstOrNull() ?: return null
        return EnvironmentExitMetadata(
            reason = "ANDROID_EXIT_${exit.reason}",
            description = exit.description,
        )
    }

    private class AndroidEnvironmentProcess(
        private val process: Process,
    ) : EnvironmentProcess {
        override val pid: Int = inspectPid(process)
        override fun isAlive(): Boolean = process.isAlive

        private companion object {
            fun inspectPid(process: Process): Int {
                var type: Class<*>? = process.javaClass
                while (type != null) {
                    val field = runCatching { type.getDeclaredField("pid") }.getOrNull()
                    if (field != null) {
                        field.isAccessible = true
                        return field.getInt(process)
                    }
                    type = type.superclass
                }
                error("Could not inspect PRoot process PID")
            }
        }
    }
}

internal object UnavailableEnvironmentProcessRunner : EnvironmentProcessRunner {
    override fun start(command: List<String>, onOutput: (String) -> Unit): EnvironmentProcess =
        error("Environment process runner is unavailable")
    override fun descendants(process: EnvironmentProcess): List<Int> = emptyList()
    override fun terminate(pid: Int, force: Boolean) = Unit
    override fun exitMetadata(process: EnvironmentProcess): EnvironmentExitMetadata? = null
}
