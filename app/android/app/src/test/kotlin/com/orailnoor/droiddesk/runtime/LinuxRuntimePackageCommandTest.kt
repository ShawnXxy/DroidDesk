package com.orailnoor.droiddesk.runtime

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxRuntimePackageCommandTest {
    @Test
    fun nativePackageUpgradeIsBounded() {
        assertEquals(
            "timeout --signal=TERM --kill-after=5s 1800s " +
                "env DEBIAN_FRONTEND=noninteractive apt-get " +
                "-o Dpkg::Options::=--force-confdef " +
                "-o Dpkg::Options::=--force-confold " +
                "install --only-upgrade -y nodejs npm",
            nativePackageUpgradeCommand(listOf("nodejs", "npm")),
        )
    }

    @Test
    fun awaitDesktopProbeDestroysProcessAndPropagatesInterruptionDuringWaitFor() {
        val enteredWait = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val probeAttempts = AtomicInteger(0)
        val caught = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)

        val poller = Thread {
            try {
                while (true) {
                    val ready = awaitDesktopProbe {
                        probeAttempts.incrementAndGet()
                        object : Process() {
                            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                            override fun getInputStream(): InputStream = InputStream.nullInputStream()
                            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                            override fun waitFor(): Int {
                                enteredWait.countDown()
                                Thread.sleep(10_000)
                                return 0
                            }
                            override fun exitValue(): Int = 0
                            override fun destroy() {
                                destroyed.set(true)
                            }
                            override fun destroyForcibly(): Process {
                                destroyed.set(true)
                                return this
                            }
                        }
                    }
                    if (ready) break
                    Thread.sleep(150)
                }
            } catch (error: Throwable) {
                caught.set(error)
            } finally {
                finished.countDown()
            }
        }.apply { start() }

        assertTrue(enteredWait.await(2, TimeUnit.SECONDS))
        poller.interrupt()
        assertTrue("poller interrupted during Process.waitFor must exit promptly", finished.await(2, TimeUnit.SECONDS))
        poller.join(2_000)
        assertTrue(caught.get() is InterruptedException)
        assertTrue(destroyed.get())
        assertEquals(1, probeAttempts.get())

        // Non-interrupt IO failure returns false without throwing so normal polling can retry
        Thread.interrupted()
        assertFalse(awaitDesktopProbe { throw java.io.IOException("bash unavailable") })
    }

    @Test
    fun execInterruptibleRootProbePropagatesInterruptionDuringChrootProbe() {
        val enteredExec = CountDownLatch(1)
        val execCalls = AtomicInteger(0)
        val caught = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)

        val poller = Thread {
            try {
                while (true) {
                    val result = execInterruptibleRootProbe(
                        execBounded = { _, _ ->
                            execCalls.incrementAndGet()
                            enteredExec.countDown()
                            try {
                                Thread.sleep(10_000)
                                ExecutionResult(0, "1234")
                            } catch (_: InterruptedException) {
                                // Mirrors RootShell.execBounded: restores interrupt flag and returns exit 130
                                Thread.currentThread().interrupt()
                                ExecutionResult(130, "")
                            }
                        },
                        command = "chroot /rootfs /usr/bin/pgrep -x xfce4-panel",
                    )
                    if (result?.exitCode == 0) break
                    Thread.sleep(150)
                }
            } catch (error: Throwable) {
                caught.set(error)
            } finally {
                finished.countDown()
            }
        }.apply { start() }

        assertTrue(enteredExec.await(2, TimeUnit.SECONDS))
        poller.interrupt()
        assertTrue("chroot poller interrupted during execBounded must exit promptly", finished.await(2, TimeUnit.SECONDS))
        poller.join(2_000)
        assertTrue(caught.get() is InterruptedException)
        assertEquals(1, execCalls.get())

        Thread.interrupted()
        assertNull(
            execInterruptibleRootProbe(
                execBounded = { _, _ -> throw java.io.IOException("su failed") },
                command = "id",
            ),
        )
        assertThrows(InterruptedException::class.java) {
            execInterruptibleRootProbe(
                execBounded = { _, _ -> ExecutionResult(130, "") },
                command = "id",
            )
        }
    }

    @Test
    fun awaitBoundedProbeProcessDestroysProcessRethrowsInterruptionAndPreservesLiveProotSession() {
        val enteredBoundedWait = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val processStarts = AtomicInteger(0)

        val sessionHost = object : ProotDesktopSessionHost {
            override fun isDesktopReady(): Boolean = true
            override fun prepareHostSession(): Result<Unit> = Result.success(Unit)
            override fun launchCommand(profile: com.orailnoor.droiddesk.display.DisplayProfile): List<String> =
                listOf("startxfce4")
            override fun applyProfile(
                profile: com.orailnoor.droiddesk.display.DisplayProfile,
                guestHome: String,
                policy: ProotDesktopPolicy,
            ): Result<Unit> = Result.success(Unit)
            override fun waitForDesktopReady(timeoutMs: Long): Boolean = true
            override fun desktopProcessesAlive(): Boolean =
                listOf("xfce4-session", "xfce4-panel").all {
                    awaitBoundedProbeProcess(5_000L) {
                        val call = processStarts.incrementAndGet()
                        object : Process() {
                            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                            override fun getInputStream(): InputStream = InputStream.nullInputStream()
                            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                                if (call >= 3) {
                                    enteredBoundedWait.countDown()
                                    Thread.sleep(10_000)
                                }
                                return true
                            }
                            override fun waitFor(): Int = 0
                            override fun exitValue(): Int = 0
                            override fun destroy() {
                                destroyed.set(true)
                            }
                            override fun destroyForcibly(): Process {
                                destroyed.set(true)
                                return this
                            }
                        }
                    }?.exitCode == 0
                }
        }
        val runner = object : EnvironmentProcessRunner {
            override fun start(command: List<String>, onOutput: (String) -> Unit): EnvironmentProcess =
                object : EnvironmentProcess {
                    override val pid: Int = 4242
                    override fun isAlive(): Boolean = true
                }
            override fun descendants(process: EnvironmentProcess): List<Int> = emptyList()
            override fun terminate(pid: Int, force: Boolean) = Unit
            override fun exitMetadata(process: EnvironmentProcess): EnvironmentExitMetadata? = null
        }
        val controller = ProotDebianSessionController(sessionHost, runner)
        assertTrue(controller.start(com.orailnoor.droiddesk.display.DisplayProfilePolicy.phoneDefaultProfile()).isSuccess)
        assertTrue(controller.waitForDesktopReady(1_000L))
        assertTrue(controller.isRunning())
        assertEquals(2, processStarts.get())

        val caught = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        val readinessThread = Thread {
            try {
                controller.waitForDesktopReady(10_000L)
            } catch (error: Throwable) {
                caught.set(error)
            } finally {
                finished.countDown()
            }
        }.apply { start() }

        assertTrue(enteredBoundedWait.await(2, TimeUnit.SECONDS))
        readinessThread.interrupt()
        assertTrue("PRoot readiness interrupted during probeProcess must exit promptly", finished.await(2, TimeUnit.SECONDS))
        readinessThread.join(2_000)
        assertTrue(caught.get() is InterruptedException)
        assertTrue(destroyed.get())
        // First pgrep ("xfce4-session", call #3) was interrupted; second pgrep ("xfce4-panel") never started!
        assertEquals(3, processStarts.get())

        // Live PRoot session remains healthy (sessionFailed was NOT poisoned)!
        Thread.interrupted()
        processStarts.set(0)
        assertTrue(controller.isRunning())
        assertNull(controller.lastError)

        // Pre-interrupted thread throws before starting any process
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                awaitBoundedProbeProcess(1_000L) { error("pre-interrupted thread must not start process") }
            }
        } finally {
            Thread.interrupted()
        }
    }
}
