package com.orailnoor.droiddesk

import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DesktopDisplayHost
import com.orailnoor.droiddesk.display.DesktopLaunchReply
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.MovableSecondaryDisplayAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun deferredResolvedOpenRejectsAStoppedAdmissionInsteadOfOpeningFresh() {
        var deferred: (() -> Unit)? = null
        val retries = mutableListOf<Long?>()
        var cancellations = 0
        var current = true

        val wasDeferred = deferResolvedDesktopOpenIfNeeded(
            isAttached = false,
            expectedEpoch = null,
            sessionGeneration = 41,
            isStopped = false,
            isSessionCurrent = { current && it == 41L },
            deferUntilAttached = { deferred = it },
            retry = retries::add,
            cancel = { cancellations++ },
        )

        assertTrue(wasDeferred)
        assertTrue(retries.isEmpty())
        assertEquals(0, cancellations)
        current = false
        deferred!!.invoke()
        assertTrue(retries.isEmpty())
        assertEquals(1, cancellations)
    }

    @Test
    fun deferredResolvedOpenCarriesTheActiveEpochWhenStillCurrent() {
        var deferred: (() -> Unit)? = null
        val retries = mutableListOf<Long?>()

        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = null,
                sessionGeneration = 41,
                isStopped = false,
                isSessionCurrent = { it == 41L },
                deferUntilAttached = { deferred = it },
                retry = retries::add,
                cancel = { error("current admission must not cancel") },
            ),
        )
        deferred!!.invoke()
        assertEquals(listOf(41L), retries)
        assertFalse(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = true,
                expectedEpoch = null,
                sessionGeneration = 42,
                isStopped = true,
                isSessionCurrent = { false },
                deferUntilAttached = { error("attached opens must not defer") },
                retry = { error("attached opens continue directly") },
                cancel = { error("attached opens continue directly") },
            ),
        )
    }

    @Test
    fun deferredResolvedOpenWithoutAnAdmissionRetriesFreshAfterAttachment() {
        var deferred: (() -> Unit)? = null
        val retries = mutableListOf<Long?>()
        var cancellations = 0

        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = null,
                sessionGeneration = 41,
                isStopped = true,
                isSessionCurrent = { false },
                deferUntilAttached = { deferred = it },
                retry = retries::add,
                cancel = { cancellations++ },
            ),
        )
        assertTrue(retries.isEmpty())
        assertEquals(0, cancellations)
        deferred!!.invoke()
        assertEquals(listOf<Long?>(null), retries)
        assertEquals(0, cancellations)
    }

    @Test
    fun knownLocalDisplayStillWaitsForDecorAttachment() {
        assertTrue(
            shouldDeferResolvedDesktopOpen(
                displayId = 73,
                isAttached = false,
                allowsManualMoves = false,
            ),
        )
        assertFalse(
            shouldDeferResolvedDesktopOpen(
                displayId = 73,
                isAttached = false,
                allowsManualMoves = true,
            ),
        )
        assertTrue(
            shouldDeferResolvedDesktopOpen(
                displayId = null,
                isAttached = false,
                allowsManualMoves = true,
            ),
        )
        assertFalse(
            shouldDeferResolvedDesktopOpen(
                displayId = null,
                isAttached = true,
                allowsManualMoves = false,
            ),
        )
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
    fun provisioningBeginFailureSettlesCoordinatorAndLaunchReplyOnce() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val coordinator = DesktopDisplayCoordinator(
            host = object : DesktopDisplayHost {
                override fun currentDisplay() = phone
                override fun displays() = listOf(phone)
                override fun defaultProfileKind() = ProfileKind.PHONE
                override fun sessionRunning() = false
                override fun launch(request: DesktopLaunchRequest) = Unit
                override fun focus(requestId: Long) = false
                override fun cancelLaunch(requestId: Long) = Unit
                override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
                override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
            },
        )
        val errors = mutableListOf<Pair<String, String?>>()
        val result = object : MethodChannel.Result {
            override fun success(result: Any?) = error("unexpected success: $result")
            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                errors += errorCode to errorMessage
            }
            override fun notImplemented() = error("unexpected notImplemented")
        }
        val reply = DesktopLaunchReply(result, coordinator)
        val epoch = coordinator.prepareSession("proot", "xfce4", start = true)
        var settlements = 0

        settleProvisioningStartFailure(
            epoch = epoch,
            error = SecurityException("background start denied"),
            isSessionCurrent = coordinator::isSessionCurrent,
            settleSession = { targetEpoch, outcome ->
                settlements++
                coordinator.sessionStarted(targetEpoch, outcome)
            },
            reportFailure = { failure -> reply.error(failure.code, failure.message, null) },
        )
        coordinator.stop()

        assertEquals(1, settlements)
        assertEquals(
            listOf("DISPLAY_NOT_ALLOWED" to "Android denied starting the Linux service"),
            errors,
        )

        val staleErrors = mutableListOf<String>()
        var staleSettlements = 0
        settleProvisioningStartFailure(
            epoch = epoch,
            error = DesktopDisplayException("SESSION_STOPPED", "session stopped"),
            isSessionCurrent = coordinator::isSessionCurrent,
            settleSession = { _, _ -> staleSettlements++ },
            reportFailure = { staleErrors += it.code },
        )
        assertEquals(0, staleSettlements)
        assertEquals(listOf("SESSION_STOPPED"), staleErrors)
    }

    @Test
    fun asyncAdmissionCarriesEpochAndRejectsBeforeDuringAndAfterStopOrReplacement() {
        var currentEpoch = 47L
        var stopped = false
        val admission = AsyncDesktopAdmission(47) { !stopped && it == currentEpoch }

        assertEquals(47L, admission.expectedEpoch)
        assertTrue(admission.current().isSuccess)

        val stoppedDuringCross = admission.cross {
            stopped = true
            "running"
        }
        assertEquals(
            "SESSION_STOPPED",
            (stoppedDuringCross.exceptionOrNull() as DesktopDisplayException).code,
        )

        var callsWhenAlreadyStopped = 0
        val alreadyStopped = admission.cross {
            callsWhenAlreadyStopped++
            "running"
        }
        assertEquals(0, callsWhenAlreadyStopped)
        assertEquals(
            "SESSION_STOPPED",
            (alreadyStopped.exceptionOrNull() as DesktopDisplayException).code,
        )

        stopped = false
        val abortedWithTeardownIo = admission.cross<String> {
            stopped = true
            throw IOException("broken pipe during stop")
        }
        assertEquals(
            "SESSION_STOPPED",
            (abortedWithTeardownIo.exceptionOrNull() as DesktopDisplayException).code,
        )

        stopped = false
        val replacedDuringCross = admission.cross {
            currentEpoch = 48L
            "running"
        }
        assertEquals(
            "SESSION_STOPPED",
            (replacedDuringCross.exceptionOrNull() as DesktopDisplayException).code,
        )

        var callsWhenReplaced = 0
        val alreadyReplaced = admission.cross {
            callsWhenReplaced++
            "running"
        }
        assertEquals(0, callsWhenReplaced)
        assertEquals(
            "SESSION_STOPPED",
            (alreadyReplaced.exceptionOrNull() as DesktopDisplayException).code,
        )

        currentEpoch = 47L
        val crossed = admission.cross { "installed" }
        assertEquals("installed", crossed.getOrThrow())
        currentEpoch = 49L
        assertEquals(
            "SESSION_STOPPED",
            (admission.current().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun runtimeStatusReadDeliversFailureOnceWithoutFallback() {
        val ioFailure = IOException("runtime status unavailable")
        var asyncReads = 0
        val admission = AsyncDesktopAdmission(53) { it == 53L }

        val asyncOutcome = admission.cross<String> {
            asyncReads++
            throw ioFailure
        }

        assertEquals(1, asyncReads)
        assertSame(ioFailure, asyncOutcome.exceptionOrNull())

        val syncFailures = mutableListOf<Exception>()
        var syncReads = 0
        assertNull(
            readDesktopRuntimeStatus<String>(
                readStatus = {
                    syncReads++
                    throw ioFailure
                },
                onFailure = syncFailures::add,
            ),
        )
        val runtimeFailure = IllegalStateException("corrupt environment selection")
        assertNull(
            readDesktopRuntimeStatus<String>(
                readStatus = {
                    syncReads++
                    throw runtimeFailure
                },
                onFailure = syncFailures::add,
            ),
        )
        val healthy = readDesktopRuntimeStatus(
            readStatus = {
                syncReads++
                "ready"
            },
            onFailure = syncFailures::add,
        )

        assertEquals("ready", healthy)
        assertEquals(3, syncReads)
        assertEquals(2, syncFailures.size)
        assertSame(ioFailure, syncFailures[0])
        assertSame(runtimeFailure, syncFailures[1])
    }

    @Test
    fun displayLocalExternalAutoLaunchShortCircuitsBeforeRuntimeStatus() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val presentationExternal = DesktopDisplay(
            2,
            "hdmi:2",
            "Presentation External",
            isDefault = false,
            eligible = true,
            null,
        )
        val tnt = DesktopDisplay(100003, "tnt", "TNT", isDefault = false, eligible = true, null)
        val host = object : DesktopDisplayHost {
            override fun currentDisplay() = presentationExternal
            override fun displays() = listOf(phone, tnt)
            override fun defaultProfileKind() = ProfileKind.PHONE
            override fun sessionRunning() = false
            override fun launch(request: DesktopLaunchRequest) = Unit
            override fun focus(requestId: Long) = false
            override fun cancelLaunch(requestId: Long) = Unit
            override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
            override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
        }
        val localCoordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "display-local test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
        )
        var statusReads = 0

        listOf(0, 2, 100003).forEach { displayId ->
            assertNull(
                readEligibleExternalAutoStartStatus(
                    diagnostics = localCoordinator.placementDiagnostics(displayId, installed = true),
                    readStatus = {
                        statusReads++
                        error("display-local auto-launch must short-circuit before status")
                    },
                    shouldAutoLaunch = { true },
                    onFailure = { error("unexpected failure: $it") },
                ),
            )
        }
        assertEquals(0, statusReads)

        val movableCoordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = MovableSecondaryDisplayAdapter(
                PlacementSelection(
                    mode = PlacementMode.MOVABLE,
                    source = PlacementSelectionSource.RUNTIME_EVIDENCE,
                    allowAutomaticMoves = true,
                    reason = "movable test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
        )
        listOf(0, 100003).forEach { ineligibleDisplayId ->
            assertNull(
                readEligibleExternalAutoStartStatus(
                    diagnostics = movableCoordinator.placementDiagnostics(
                        ineligibleDisplayId,
                        installed = true,
                    ),
                    readStatus = {
                        statusReads++
                        error("ineligible display $ineligibleDisplayId must short-circuit before status")
                    },
                    shouldAutoLaunch = { true },
                    onFailure = { error("unexpected failure: $it") },
                ),
            )
        }
        assertEquals(0, statusReads)

        val failures = mutableListOf<Exception>()
        val statusFailure = IOException("marker read failed")
        assertNull(
            readEligibleExternalAutoStartStatus<Boolean>(
                diagnostics = movableCoordinator.placementDiagnostics(2, installed = true),
                readStatus = {
                    statusReads++
                    throw statusFailure
                },
                shouldAutoLaunch = { it },
                onFailure = failures::add,
            ),
        )
        assertEquals(1, statusReads)
        assertEquals(listOf(statusFailure), failures)

        assertNull(
            readEligibleExternalAutoStartStatus(
                diagnostics = movableCoordinator.placementDiagnostics(2, installed = true),
                readStatus = {
                    statusReads++
                    false
                },
                shouldAutoLaunch = { movableCoordinator.shouldAutoLaunchCurrentDisplay(2, it) },
                onFailure = { error("unexpected failure: $it") },
            ),
        )
        assertEquals(2, statusReads)

        val eligibleStatus = readEligibleExternalAutoStartStatus(
            diagnostics = movableCoordinator.placementDiagnostics(2, installed = true),
            readStatus = {
                statusReads++
                true
            },
            shouldAutoLaunch = { movableCoordinator.shouldAutoLaunchCurrentDisplay(2, it) },
            onFailure = { error("unexpected failure: $it") },
        )
        assertEquals(3, statusReads)
        assertEquals(true, eligibleStatus)
    }

    @Test
    fun migratedSynchronousCallersDelegatePlacementAndOpeningToCoordinator() {
        val source = readSource(MAIN_ACTIVITY)
        val runtimeStatusScope = sourceBetween(
            source,
            "\"getRuntimeStatus\" -> {",
            "// ── Device Info ──",
        )
        val scopes = mapOf(
            "external auto-open" to functionBody(source, "private fun maybeAutoStartExternalDesktop()"),
            "preference auto-open" to functionBody(source, "private fun maybeAutoStartDesktop(launchIntent: Intent)"),
            "running phone and notification open" to sourceBetween(
                source,
                "if (readDesktopRuntimeStatus(",
                "val epoch = try",
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
            Regex("""\benvironmentManager\.status\s*\("""),
        )

        (scopes + ("runtime status query" to runtimeStatusScope)).forEach { (name, body) ->
            forbidden.forEach { pattern ->
                assertFalse("$name must not contain ${pattern.pattern}", pattern.containsMatchIn(body))
            }
        }
        scopes.filterKeys { it != "resolved open adapter" }.forEach { (name, body) ->
            assertTrue("$name must use the resolved requestOpen path", body.contains("requestResolvedDesktopOpen("))
        }
        assertTrue(
            "external auto-open must short-circuit through merged placement diagnostics before status",
            scopes.getValue("external auto-open").indexOf("placementDiagnostics(displayId") <
                scopes.getValue("external auto-open").indexOf("environmentManager::status"),
        )
        assertTrue(
            "runtime status query must guard status reads",
            runtimeStatusScope.contains("readDesktopRuntimeStatus("),
        )
        assertTrue(
            "resolved open adapter must delegate to coordinator requestOpen",
            scopes.getValue("resolved open adapter").contains("coordinator::requestOpen"),
        )
        assertTrue(
            "resolved open adapter must defer until the activity can report its display",
            scopes.getValue("resolved open adapter").contains("deferResolvedDesktopOpenIfNeeded("),
        )
        val homeLaunch = functionBody(source, "private fun handleHomeLaunch(homeIntent: Intent)")
        assertTrue(
            "coordinator transitions must retain the legacy recovery path before runtime-running opens",
            homeLaunch.indexOf("if (coordinator.isStarting || coordinator.isRestarting)") <
                homeLaunch.indexOf("if (readDesktopRuntimeStatus("),
        )
    }

    @Test
    fun asyncAdmissionCallersGuardEveryBoundaryAndRepostWithoutPlacementBranching() {
        val mainSource = readSource(MAIN_ACTIVITY)
        val serviceSource = readSource(DROID_DESK_SERVICE)
        val homeAsyncScope = functionBody(mainSource, "private fun handleHomeLaunch(homeIntent: Intent)")
            .let { body -> body.substring(body.indexOf("val epoch = try")) }
        val autoSetupScope = functionBody(mainSource, "private fun runAutoChrootSetup()")
        val runtimeProvisionScope = sourceBetween(
            mainSource,
            "\"startLinux\" -> {",
            "\"launchDesktopActivity\" -> {",
        ).let { body -> body.substring(body.indexOf("val epoch = try")) }
        val mainScopes = mapOf(
            "home async status" to homeAsyncScope,
            "auto provisioning" to autoSetupScope,
            "runtime provisioning" to runtimeProvisionScope,
            "foreground service gate" to functionBody(mainSource, "private fun startForegroundService()"),
        )
        val forbiddenPlacement = listOf(
            Regex("""\bBuild\.DEVICE\b"""),
            Regex("""\b100003\b"""),
            Regex("""\bdisplayLocalMode\b"""),
            Regex("""\.isDisplayLocal\b"""),
            Regex("""\bDisplayEnvironmentPolicy\b"""),
            Regex("""\bPlacementMode\b"""),
            Regex("""\bplacementSelection\b"""),
        )

        mainScopes.forEach { (name, body) ->
            forbiddenPlacement.forEach { pattern ->
                assertFalse("$name must not contain ${pattern.pattern}", pattern.containsMatchIn(body))
            }
        }
        listOf("home async status", "auto provisioning", "runtime provisioning").forEach { name ->
            val scope = mainScopes.getValue(name)
            assertTrue(
                "$name must construct AsyncDesktopAdmission with reserved epoch",
                scope.contains("AsyncDesktopAdmission(epoch,"),
            )
            assertTrue(
                "$name must cross async runtime/provision boundaries through admission.cross",
                scope.contains("admission.cross"),
            )
            val uiReposts = callBodies(scope, "runOnUiThread")
            assertTrue("$name must contain UI reposts", uiReposts.isNotEmpty())
            uiReposts.forEachIndexed { index, repost ->
                assertTrue(
                    "$name runOnUiThread[$index] must validate admission.current()",
                    repost.contains("admission.current()"),
                )
            }
        }
        assertFalse(
            "home async status must not call environmentManager.status() directly",
            Regex("""\benvironmentManager\.status\s*\(""").containsMatchIn(homeAsyncScope),
        )
        listOf("hasRoot", "isRootfsReady", "isDesktopInstalled", "isRunning").forEach { probe ->
            assertFalse(
                "auto provisioning must not call chrootRuntime.$probe() directly",
                Regex("""\bchrootRuntime\.$probe\s*\(""").containsMatchIn(autoSetupScope),
            )
        }
        listOf("dlLatch", "exLatch", "inLatch").forEach { latch ->
            val afterAwait = autoSetupScope.substringAfter("$latch.await()").lineSequence().take(2).joinToString("\n")
            assertTrue(
                "auto provisioning must validate admission immediately after $latch.await()",
                afterAwait.contains("admission.current().getOrThrow()"),
            )
        }
        assertTrue(
            "home async status must preserve requestDesktop handoff for P11b2b",
            homeAsyncScope.contains("requestDesktop("),
        )
        assertTrue(
            "auto provisioning must preserve requestDesktop handoff for P11b2b",
            autoSetupScope.contains("requestDesktop("),
        )
        assertTrue(
            "runtime provisioning must preserve launchPreparedDesktop handoff for P11b2b",
            runtimeProvisionScope.contains("launchPreparedDesktop(epoch, startReply)"),
        )
        assertTrue(
            "foreground service helper must delegate to DroidDeskService.ensureStarted",
            mainScopes.getValue("foreground service gate").contains("DroidDeskService.ensureStarted(this)"),
        )
        assertFalse(
            "MainActivity must not call platform startForegroundService(intent) directly",
            Regex("""\bstartForegroundService\s*\(\s*intent\s*\)""").containsMatchIn(mainSource),
        )
        assertFalse(
            "MainActivity must not call platform startService(intent) directly",
            Regex("""\bstartService\s*\(\s*intent\s*\)""").containsMatchIn(mainSource),
        )
        listOf("auto provisioning", "runtime provisioning").forEach { name ->
            val scope = mainScopes.getValue(name)
            listOf(
                "DroidDeskService.beginProvisioning(",
                "DroidDeskService.updateProvisioning(",
                "DroidDeskService.completeProvisioning(",
                "DroidDeskService.failProvisioning(",
            ).forEach { call ->
                assertTrue("$name must delegate provisioning lifecycle via $call", scope.contains(call))
            }
        }
        assertTrue(
            "runtime provisioning must settle coordinator and reply if beginProvisioning throws",
            runtimeProvisionScope.contains("settleProvisioningStartFailure("),
        )
        listOf(
            "fun ensureStarted(",
            "fun beginProvisioning(",
            "fun updateProvisioning(",
            "fun completeProvisioning(",
            "fun failProvisioning(",
        ).forEach { api ->
            assertTrue("DroidDeskService must expose $api", serviceSource.contains(api))
        }
        forbiddenPlacement.forEach { pattern ->
            assertFalse(
                "DroidDeskService must not contain ${pattern.pattern}",
                pattern.containsMatchIn(serviceSource),
            )
        }
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

    private fun callBodies(source: String, callName: String): List<String> {
        val bodies = mutableListOf<String>()
        val pattern = Regex("""\b${Regex.escape(callName)}\s*\{""")
        var searchFrom = 0
        while (true) {
            val match = pattern.find(source, searchFrom) ?: break
            val openingBrace = match.range.last
            var depth = 0
            var end = -1
            for (index in openingBrace until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            require(end >= openingBrace) { "Unterminated $callName block" }
            bodies += source.substring(openingBrace, end + 1)
            searchFrom = end + 1
        }
        return bodies
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
