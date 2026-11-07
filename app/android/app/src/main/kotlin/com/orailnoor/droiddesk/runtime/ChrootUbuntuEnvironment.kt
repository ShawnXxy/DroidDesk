package com.orailnoor.droiddesk.runtime

import android.content.Context
import com.orailnoor.droiddesk.display.DisplayProfile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ChrootUbuntuEnvironment(
    port: LegacyRuntimePort,
) : LegacyEnvironmentAdapter(
    kind = EnvironmentKind.CHROOT_UBUNTU,
    distro = "ubuntu-chroot",
    port = port,
) {
    constructor(context: Context, runtime: ChrootRuntime) : this(
        object : LegacyRuntimePort {
            private val filesDir = context.applicationContext.filesDir

            override val installed: Boolean
                get() = runtime.isRootfsReady() && runtime.isDesktopInstalled()
            override val running: Boolean get() = runtime.isRunning()
            override val desktop: String get() = if (runtime.isDesktopInstalled()) "xfce4" else ""
            override val rootfsPath: String get() = runtime.getRootfsPath()
            override val rootfsSizeBytes: Long get() = runtime.getRootfsSizeMB() * 1024L * 1024L
            override val paths: EnvironmentPaths
                get() {
                    val rootfs = File(runtime.getRootfsPath())
                    val home = File(rootfs, "root")
                    return EnvironmentPaths(
                        home = home,
                        applicationDirectories = listOf(
                            File(rootfs, "usr/share/applications"),
                            File(home, ".local/share/applications"),
                        ),
                        packageStatusFiles = listOf(File(rootfs, "var/lib/dpkg/status")),
                        snapshotMode = "chroot",
                        guestHome = "/root",
                    )
                }

            override fun provision(
                desktop: String,
                onProgress: (Double, String) -> Unit,
                onLog: (String) -> Unit,
            ): Result<Unit> {
                if (!runtime.hasRoot()) {
                    return Result.failure(IllegalStateException("Root access is required"))
                }
                if (!runtime.isRootfsReady()) {
                    return Result.failure(IllegalStateException("Ubuntu rootfs is not ready"))
                }
                val latch = CountDownLatch(1)
                var failure: Throwable? = null
                runtime.installDesktopEnvironment(
                    desktopEnv = desktop,
                    onProgress = { value, message ->
                        onProgress(value, message)
                        if (value >= 1.0 || value < 0) {
                            if (value < 0) failure = IllegalStateException(message)
                            latch.countDown()
                        }
                    },
                    onLog = onLog,
                )
                if (!latch.await(30, TimeUnit.MINUTES)) {
                    return Result.failure(IllegalStateException("Chroot desktop installation timed out"))
                }
                return failure?.let(Result.Companion::failure) ?: Result.success(Unit)
            }

            override fun start(desktop: String, profile: DisplayProfile?): Result<Unit> =
                runCatching {
                    runtime.startSession(desktop, profile)
                    check(runtime.isRunning()) { "Chroot Linux session did not start" }
                }

            override fun stop(): Result<Unit> = runCatching { runtime.stopSession() }

            override fun executeShell(
                command: String,
                onOutput: ((String) -> Unit)?,
                interactive: Boolean,
                stdin: Boolean,
            ): ExecutionResult {
                if (stdin) {
                    return ExecutionResult(
                        1,
                        "Error: Interactive stdin is unavailable for the rooted chroot terminal",
                    )
                }
                val wasRunning = runtime.isRunning()
                if (!wasRunning) runtime.ensureMounts()
                return try {
                    val output = runtime.executeCommand(command, onOutput)
                    ExecutionResult(if (output.startsWith("Error:")) 1 else 0, output)
                } finally {
                    if (!wasRunning) runtime.unmountAll()
                }
            }

            override fun executeDesktop(command: String): Result<Unit> =
                runtime.executeDesktopCommand(command)

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
