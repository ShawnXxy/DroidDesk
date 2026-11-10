package com.orailnoor.droiddesk

import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCallerPlacementPolicyTest {
    @Test
    fun resolvedPhoneCurrentAndManualOpensForwardExactRequestsAndCompletion() {
        val scenarios = listOf(
            OpenScenario("phone", 0, "termux", "xfce4", false, 7),
            OpenScenario("current-display", 73, "proot", "plasma", true, null),
            OpenScenario("manual", 8, "chroot", "xfce4", true, 19),
        )

        scenarios.forEach { scenario ->
            var capturedRequest: DesktopOpenRequest? = null
            var capturedCompletion: ((Result<Unit>) -> Unit)? = null
            val outcomes = mutableListOf<Result<Unit>>()

            forwardResolvedDesktopOpen(
                currentDisplayId = scenario.displayId,
                mode = scenario.mode,
                desktopEnv = scenario.desktopEnv,
                startSession = scenario.startSession,
                expectedEpoch = scenario.expectedEpoch,
                startService = {},
                isRestarting = { false },
                requestOpen = { request, _, completion ->
                    capturedRequest = request
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
            val failure = IllegalStateException("${scenario.name} failed")
            capturedCompletion!!.invoke(Result.failure(failure))
            assertSame(scenario.name, failure, outcomes.single().exceptionOrNull())
        }
    }

    @Test
    fun resolvedOpenStartsServiceOnceOnlyWhenStartingTheSession() {
        val scenarios = listOf(
            ServiceStartScenario(startSession = true, restarting = false, expectedStarts = 1),
            ServiceStartScenario(startSession = false, restarting = false, expectedStarts = 0),
            ServiceStartScenario(startSession = false, restarting = true, expectedStarts = 1),
        )

        scenarios.forEach { scenario ->
            var serviceStarts = 0
            var restarting = false

            forwardResolvedDesktopOpen(
                currentDisplayId = 3,
                mode = "proot",
                desktopEnv = "xfce4",
                startSession = scenario.startSession,
                expectedEpoch = 23,
                startService = { serviceStarts++ },
                isRestarting = { restarting },
                requestOpen = { _, startService, completion ->
                    restarting = scenario.restarting
                    startService()
                    completion(Result.success(Unit))
                },
                completion = {},
            )

            assertEquals(scenario.toString(), scenario.expectedStarts, serviceStarts)
        }
    }

    @Test
    fun resolvedOpenDefersUntilTheActivityDisplayIsAttached() {
        var deferred: (() -> Unit)? = null
        var opens = 0

        val wasDeferred = deferResolvedDesktopOpenIfNeeded(
            isAttached = false,
            deferUntilAttached = { deferred = it },
            retry = { opens++ },
        )

        assertTrue(wasDeferred)
        assertEquals(0, opens)
        deferred!!.invoke()
        assertEquals(1, opens)
        assertFalse(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = true,
                deferUntilAttached = { error("attached opens must not defer") },
                retry = { opens++ },
            ),
        )
        assertEquals(1, opens)
    }

    @Test
    fun resolvedOpenDeliversImmediateFailureOnce() {
        val failure = IllegalStateException("placement rejected")
        val outcomes = mutableListOf<Result<Unit>>()

        forwardResolvedDesktopOpen(
            currentDisplayId = 0,
            mode = "termux",
            desktopEnv = "xfce4",
            startSession = true,
            expectedEpoch = null,
            startService = {},
            isRestarting = { false },
            requestOpen = { _, _, completion -> completion(Result.failure(failure)) },
            completion = outcomes::add,
        )

        assertEquals(1, outcomes.size)
        assertSame(failure, outcomes.single().exceptionOrNull())
    }

    @Test
    fun migratedSynchronousCallersDelegatePlacementAndOpeningToCoordinator() {
        val source = readSource(MAIN_ACTIVITY)
        val scopes = mapOf(
            "external auto-open" to functionBody(source, "private fun maybeAutoStartExternalDesktop()"),
            "preference auto-open" to functionBody(source, "private fun maybeAutoStartDesktop(launchIntent: Intent)"),
            "running phone and notification open" to sourceBetween(
                source,
                "if (environmentManager.status().running) {",
                "if (coordinator.isStarting || coordinator.isRestarting)",
            ),
            "resolved open adapter" to functionBody(source, "private fun requestResolvedDesktopOpen("),
            "ready runtime start" to sourceBetween(
                source,
                "\"startLinux\" -> {",
                "val epoch = try",
            ),
            "manual activity launch" to sourceBetween(
                source,
                "\"launchDesktopActivity\" -> {",
                "\"stopLinux\" -> {",
            ),
        )
        val forbidden = listOf(
            Regex("""\bBuild\.DEVICE\b"""),
            Regex("""\b100003\b"""),
            Regex("""\bdisplayLocalMode\b"""),
            Regex("""\.isDisplayLocal\b"""),
            Regex("""\bDisplayEnvironmentPolicy\b"""),
            Regex("""\brequestDesktop\s*\("""),
            Regex("""\blaunchPreparedDesktop\s*\("""),
            Regex("""\bbeginSession\s*\("""),
            Regex("""\bprepareSession\s*\("""),
            Regex("""\bawaitSessionReady\s*\("""),
            Regex("""\bsessionStarted\s*\("""),
            Regex("""\bstartForegroundService\s*\("""),
            Regex("""\brestartForEnvironmentIfNeeded\s*\("""),
            Regex("""\bopenActive\s*\("""),
            Regex("""\bstartupTarget\s*\("""),
        )

        scopes.forEach { (name, body) ->
            forbidden.forEach { pattern ->
                assertFalse("$name must not contain ${pattern.pattern}", pattern.containsMatchIn(body))
            }
        }
        scopes.filterKeys { it != "resolved open adapter" }.forEach { (name, body) ->
            assertTrue("$name must use the resolved requestOpen path", body.contains("requestResolvedDesktopOpen("))
        }
        assertTrue(
            "resolved open adapter must delegate to coordinator requestOpen",
            scopes.getValue("resolved open adapter").contains("coordinator::requestOpen"),
        )
        assertTrue(
            "resolved open adapter must defer until the activity can report its display",
            scopes.getValue("resolved open adapter").contains("deferResolvedDesktopOpenIfNeeded("),
        )
    }

    @Test
    fun serviceRecoveryActionUsesSnapshotDiagnosticsWithoutSelectingPlacement() {
        val source = readSource(DROID_DESK_SERVICE)
        val spec = functionBody(source, "internal fun desktopReturnNotificationSpec(")
        val notification = functionBody(source, "fun notifyReturnRequired(context: Context, message: String)")
        val forbidden = listOf(
            Regex("""\bdisplayLocalMode\b"""),
            Regex("""\.isDisplayLocal\b"""),
            Regex("""\bplacementSelection\b"""),
            Regex("""\bPlacementMode\b"""),
        )

        forbidden.forEach { pattern ->
            assertFalse(
                "service action logic must not contain ${pattern.pattern}",
                pattern.containsMatchIn(spec) || pattern.containsMatchIn(notification),
            )
        }
        assertTrue(spec.contains("\"crossDisplayMovesAllowed\""))
        assertTrue(spec.contains("\"sessionRunning\""))
        assertTrue(notification.contains("coordinator.snapshot()"))
        assertTrue(notification.contains("desktopReturnNotificationSpec("))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Could not find $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Could not find body for $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated body for $signature")
    }

    private fun sourceBetween(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "Could not find $startMarker" }
        val end = source.indexOf(endMarker, start)
        require(end >= 0) { "Could not find $endMarker after $startMarker" }
        return source.substring(start, end)
    }

    private fun readSource(relativePath: String): String {
        val userDirectory = System.getProperty("user.dir") ?: error("user.dir is unavailable")
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

    private data class OpenScenario(
        val name: String,
        val displayId: Int,
        val mode: String,
        val desktopEnv: String,
        val startSession: Boolean,
        val expectedEpoch: Long?,
    )

    private data class ServiceStartScenario(
        val startSession: Boolean,
        val restarting: Boolean,
        val expectedStarts: Int,
    )

    private companion object {
        const val MAIN_ACTIVITY = "src/main/kotlin/com/orailnoor/droiddesk/MainActivity.kt"
        const val DROID_DESK_SERVICE =
            "src/main/kotlin/com/orailnoor/droiddesk/service/DroidDeskService.kt"
    }
}
