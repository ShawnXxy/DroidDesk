package com.orailnoor.droiddesk

import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import com.orailnoor.droiddesk.service.ServiceStartGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCallerPlacementPolicyTest {
    @Test
    fun callerOpenRequestsPreserveExactPayloadServiceStarterAndCompletion() {
        val scenarios = listOf(
            OpenScenario("phone", 0, "termux", "xfce4", true, null),
            OpenScenario("tnt", 73, "proot", "plasma", false, 11),
            OpenScenario("manual", 8, "chroot", "xfce4", true, 19),
            OpenScenario("external-auto", 44, "proot", "mate", false, null),
            OpenScenario("runtime-start", 3, "proot", "xfce4", true, 23),
            OpenScenario("running-session-item", 3, "proot", "xfce4", false, null),
        )

        scenarios.forEach { scenario ->
            var capturedRequest: DesktopOpenRequest? = null
            var capturedStartService: (() -> Unit)? = null
            var capturedCompletion: ((Result<Unit>) -> Unit)? = null
            var serviceStarts = 0
            val outcomes = mutableListOf<Result<Unit>>()

            requestDesktopThroughCoordinator(
                currentDisplayId = scenario.displayId,
                mode = scenario.mode,
                desktopEnv = scenario.desktopEnv,
                startSession = scenario.startSession,
                expectedEpoch = scenario.expectedEpoch,
                startService = { serviceStarts++ },
                requestOpen = { request, startService, completion ->
                    capturedRequest = request
                    capturedStartService = startService
                    capturedCompletion = completion
                },
                completion = outcomes::add,
            )

            assertEquals(
                scenario.name,
                DesktopOpenRequest(
                    currentDisplayId = scenario.displayId,
                    mode = scenario.mode,
                    desktopEnv = scenario.desktopEnv,
                    startSession = scenario.startSession,
                    expectedEpoch = scenario.expectedEpoch,
                ),
                capturedRequest,
            )
            capturedStartService!!.invoke()
            assertEquals(scenario.name, 1, serviceStarts)

            val failure = IllegalStateException("${scenario.name} failed")
            capturedCompletion!!.invoke(Result.failure(failure))
            assertSame(scenario.name, failure, outcomes.single().exceptionOrNull())
        }
    }

    @Test
    fun preparedRuntimeOpenCoalescesEarlyStartAndRestartsServiceAfterTeardown() {
        val gate = ServiceStartGate()
        var serviceStarts = 0
        var capturedStartService: (() -> Unit)? = null
        gate.start { serviceStarts++ }

        requestDesktopThroughCoordinator(
            currentDisplayId = 44,
            mode = "proot",
            desktopEnv = "xfce4",
            startSession = true,
            expectedEpoch = 29,
            startService = { gate.start { serviceStarts++ } },
            requestOpen = { _, startService, _ ->
                capturedStartService = startService
            },
            completion = {},
        )

        capturedStartService!!.invoke()
        assertEquals(1, serviceStarts)
        gate.reset()
        capturedStartService!!.invoke()
        assertEquals(2, serviceStarts)
    }

    @Test
    fun repeatedOpenGateDefersProvisioningStartingAndRestartingRequests() {
        assertTrue(shouldDeferRepeatedOpen(provisioning = true, starting = false, restarting = false))
        assertTrue(shouldDeferRepeatedOpen(provisioning = false, starting = true, restarting = false))
        assertTrue(shouldDeferRepeatedOpen(provisioning = false, starting = false, restarting = true))
        assertFalse(shouldDeferRepeatedOpen(provisioning = false, starting = false, restarting = false))
    }

    @Test
    fun scopedAndroidCallersDelegatePlacementAndOpeningToCoordinator() {
        val sources = SCOPED_CALLERS.associateWith(::readSource)
        val forbidden = listOf(
            Regex("""\bBuild\.DEVICE\b"""),
            Regex("""\b100003\b"""),
            Regex("""\bdisplayLocalMode\b"""),
            Regex("""\.isDisplayLocal\b"""),
            Regex("""\bDisplayEnvironmentPolicy\b"""),
            Regex("""\brestartForEnvironmentIfNeeded\s*\("""),
            Regex("""\bopenActive\s*\("""),
            Regex("""\bstartupTarget\s*\("""),
        )

        sources.forEach { (path, source) ->
            forbidden.forEach { pattern ->
                assertFalse("$path must not contain ${pattern.pattern}", pattern.containsMatchIn(source))
            }
        }

        val mainActivity = sources.getValue(MAIN_ACTIVITY)
        assertTrue(
            "MainActivity must construct DesktopOpenRequest",
            mainActivity.contains("DesktopOpenRequest("),
        )
        assertTrue(
            "MainActivity must delegate startup to requestOpen",
            mainActivity.contains("coordinator::requestOpen"),
        )
        assertFalse(
            "MainActivity must not use the legacy external auto-launch policy",
            mainActivity.contains("shouldAutoLaunchExternalDesktop"),
        )
        assertFalse(
            "Linux and folder launches must use the shared request path",
            mainActivity.contains("launchLocalDesktopItem"),
        )
        assertTrue(
            "Linux and folder launches must share the coordinator-backed item path",
            mainActivity.contains("launchDesktopItemAfterOpen"),
        )
        assertTrue(
            "Pre-attach requests must defer until the decor view can report its display",
            mainActivity.contains("if (!window.decorView.isAttachedToWindow)"),
        )
        assertTrue(
            "Desktop item status must be read away from the method-channel thread",
            mainActivity.contains("thread(name = \"desktop-item-status\")"),
        )
    }

    private fun readSource(relativePath: String): String {
        val userDirectory = System.getProperty("user.dir")
            ?: error("user.dir is unavailable")
        var root: File? = File(userDirectory).canonicalFile
        while (root != null) {
            listOf(
                File(root, relativePath),
                File(root, "app/android/app/$relativePath"),
            ).firstOrNull(File::isFile)?.let { return it.readText() }
            root = root.parentFile
        }
        error("Could not locate $relativePath")
    }

    private companion object {
        const val MAIN_ACTIVITY =
            "src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt"
        val SCOPED_CALLERS = listOf(
            MAIN_ACTIVITY,
            "src/main/kotlin/com/orailnoor/droiddesk/service/DroidDeskService.kt",
        )
    }

    private data class OpenScenario(
        val name: String,
        val displayId: Int,
        val mode: String,
        val desktopEnv: String,
        val startSession: Boolean,
        val expectedEpoch: Long?,
    )
}
