package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplayProfilePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotDebianSessionTest {

    @Test
    fun startRefusesMissingOrRepairNeededEnvironment() {
        val host = FakeSessionHost(ready = false)
        val runner = FakeProcessRunner()
        val session = ProotDebianSessionController(host, runner)

        val result = session.start(DisplayProfilePolicy.phoneDefaultProfile())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not ready"))
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun startUsesDroiddeskDbusXfceEnvironmentAndSoftwarePolicy() {
        val host = FakeSessionHost()
        val runner = FakeProcessRunner()
        val session = ProotDebianSessionController(host, runner)
        val profile = DisplayProfilePolicy.phoneDefaultProfile()

        val result = session.start(profile)

        assertTrue(result.isSuccess)
        assertEquals(listOf("prepare", "profile"), host.calls.take(2))
        assertTrue(runner.startedCommand.contains("DISPLAY=:0"))
        assertTrue(runner.startedCommand.contains("PULSE_SERVER=tcp:127.0.0.1"))
        assertTrue(runner.startedCommand.contains("PULSE_COOKIE=/tmp/pulse/cookie"))
        assertTrue(runner.startedCommand.contains("XDG_RUNTIME_DIR=/tmp/runtime-droiddesk"))
        assertEquals(
            listOf("dbus-run-session", "--", "startxfce4"),
            runner.startedCommand.takeLast(3),
        )
        assertEquals("/home/droiddesk", host.profileHome)
        assertEquals(60, host.policy!!.maxRefreshHz)
        assertFalse(host.policy!!.compositorEnabled)
    }

    @Test
    fun secondStartIsIdempotent() {
        val runner = FakeProcessRunner()
        val session = ProotDebianSessionController(FakeSessionHost(), runner)
        val profile = DisplayProfilePolicy.phoneDefaultProfile()

        assertTrue(session.start(profile).isSuccess)
        assertTrue(session.start(profile).isSuccess)

        assertEquals(1, runner.calls.count { it.startsWith("start:") })
    }

    @Test
    fun stopTerminatesDescendantsBeforeRootProcess() {
        val runner = FakeProcessRunner(descendants = listOf(11, 12))
        val host = FakeSessionHost()
        val session = ProotDebianSessionController(host, runner)
        session.start(DisplayProfilePolicy.phoneDefaultProfile()).getOrThrow()

        val result = session.stop()

        assertTrue(result.isSuccess)
        assertTrue(host.calls.contains("stop"))
        assertEquals(
            listOf(
                "term:12",
                "term:11",
                "term:10",
                "kill:12",
                "kill:11",
                "kill:10",
            ),
            runner.calls.filter { it.startsWith("term:") || it.startsWith("kill:") },
        )
    }

    @Test
    fun deadRootProcessReportsExitMetadataAndCannotRemainRunning() {
        val runner = FakeProcessRunner()
        val session = ProotDebianSessionController(FakeSessionHost(), runner)
        session.start(DisplayProfilePolicy.phoneDefaultProfile()).getOrThrow()
        runner.root.alive = false
        runner.exit = EnvironmentExitMetadata("LOW_MEMORY", "Android reclaimed the process")

        assertFalse(session.isRunning())
        assertTrue(session.lastError!!.contains("LOW_MEMORY"))
    }

    @Test
    fun missingDesktopChildrenAfterReadyStopsTheSession() {
        val host = FakeSessionHost()
        val runner = FakeProcessRunner()
        val session = ProotDebianSessionController(host, runner)
        session.start(DisplayProfilePolicy.phoneDefaultProfile()).getOrThrow()
        assertTrue(session.waitForDesktopReady(1_000))
        host.desktopProcessesAlive = false

        assertFalse(session.isRunning())
        assertTrue(session.lastError!!.contains("desktop process"))
    }

    @Test
    fun restartAfterDesktopFailureStopsTheOldProcessTreeFirst() {
        val host = FakeSessionHost()
        val runner = FakeProcessRunner(descendants = listOf(11))
        val session = ProotDebianSessionController(host, runner)
        val profile = DisplayProfilePolicy.phoneDefaultProfile()
        session.start(profile).getOrThrow()
        assertTrue(session.waitForDesktopReady(1_000))
        host.desktopProcessesAlive = false
        assertFalse(session.isRunning())
        host.desktopProcessesAlive = true

        val result = session.start(profile)

        assertTrue(result.isSuccess)
        assertEquals(2, runner.calls.count { it.startsWith("start:") })
        assertTrue(host.calls.contains("stop"))
        val firstStop = runner.calls.indexOf("term:10")
        val secondStart = runner.calls.indexOfLast { it.startsWith("start:") }
        assertTrue(firstStop in 0 until secondStart)
    }

    @Test
    fun recreatedControllerDoesNotInventARunningSession() {
        val host = FakeSessionHost().apply { desktopProcessesAlive = true }
        val session = ProotDebianSessionController(host, FakeProcessRunner())

        assertFalse(session.isRunning())
    }

    private class FakeSessionHost(
        private val ready: Boolean = true,
    ) : ProotDesktopSessionHost {
        var desktopProcessesAlive = true
        var profileHome: String? = null
        var policy: ProotDesktopPolicy? = null
        val calls = mutableListOf<String>()

        override fun isDesktopReady(): Boolean = ready

        override fun prepareHostSession(): Result<Unit> {
            calls += "prepare"
            return Result.success(Unit)
        }

        override fun stopHostSession(): Result<Unit> {
            calls += "stop"
            return Result.success(Unit)
        }

        override fun launchCommand(profile: DisplayProfile): List<String> = listOf(
            "env",
            "DISPLAY=:0",
            "PULSE_SERVER=tcp:127.0.0.1",
            "PULSE_COOKIE=/tmp/pulse/cookie",
            "XDG_RUNTIME_DIR=/tmp/runtime-droiddesk",
            "proot-distro",
            "login",
            "droiddesk-debian",
            "--user",
            "droiddesk",
            "--",
            "dbus-run-session",
            "--",
            "startxfce4",
        )

        override fun applyProfile(
            profile: DisplayProfile,
            guestHome: String,
            policy: ProotDesktopPolicy,
        ): Result<Unit> {
            calls += "profile"
            profileHome = guestHome
            this.policy = policy
            return Result.success(Unit)
        }

        override fun waitForDesktopReady(timeoutMs: Long): Boolean = desktopProcessesAlive

        override fun desktopProcessesAlive(): Boolean = desktopProcessesAlive
    }

    private class FakeProcessRunner(
        private val descendants: List<Int> = emptyList(),
    ) : EnvironmentProcessRunner {
        val calls = mutableListOf<String>()
        val root = FakeProcess(10)
        var exit: EnvironmentExitMetadata? = null
        var startedCommand: List<String> = emptyList()

        override fun start(
            command: List<String>,
            onOutput: (String) -> Unit,
        ): EnvironmentProcess {
            root.alive = true
            startedCommand = command
            calls += "start:${command.joinToString(" ")}"
            return root
        }

        override fun descendants(process: EnvironmentProcess): List<Int> = descendants

        override fun terminate(pid: Int, force: Boolean) {
            calls += "${if (force) "kill" else "term"}:$pid"
            if (pid == root.pid) root.alive = false
        }

        override fun exitMetadata(process: EnvironmentProcess): EnvironmentExitMetadata? = exit
    }

    private class FakeProcess(
        override val pid: Int,
    ) : EnvironmentProcess {
        var alive = true
        override fun isAlive(): Boolean = alive
    }
}
