package com.orailnoor.droiddesk

import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DesktopDisplayHost
import com.orailnoor.droiddesk.display.DesktopLaunchReply
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.InFlightDesktopOpenSpec
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RendererClaim
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.MovableSecondaryDisplayAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import com.orailnoor.droiddesk.service.ProvisioningNotificationState
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
        val readyStart = scopes.getValue("ready runtime start")
        assertTrue(
            "coordinator transitions must short-circuit via inFlightOpenSpec before runtime-running status probes",
            homeLaunch.indexOf("coordinator.inFlightOpenSpec()") in 0 until
                homeLaunch.indexOf("if (readDesktopRuntimeStatus("),
        )
        assertTrue(
            "startLinux must coalesce matching in-flight opens via inFlightOpenSpec before probing status",
            readyStart.indexOf("displays.inFlightOpenSpec()") in 0 until
                readyStart.indexOf("readDesktopRuntimeStatus("),
        )
        assertTrue(
            "startLinux ready branch must avoid starting a duplicate session while restarting",
            readyStart.substringAfter("if (ready)").contains("!status.running && !displays.isRestarting"),
        )
    }

    @Test
    fun inFlightOpenSpecCoalescesIdenticalTransitionsAndForwardsChangedTargetsStatelessly() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val hdmi = DesktopDisplay(2, "hdmi:2", "HDMI", isDefault = false, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt", "TNT", isDefault = false, eligible = true, null)

        val movableHost = FakeCoordinatorHost(listOf(phone, hdmi)).apply {
            currentDisplayId = hdmi.id
            running = false
        }
        val movable = DesktopDisplayCoordinator(
            host = movableHost,
            placementStrategy = MovableSecondaryDisplayAdapter(
                PlacementSelection(
                    mode = PlacementMode.MOVABLE,
                    source = PlacementSelectionSource.RUNTIME_EVIDENCE,
                    allowAutomaticMoves = false,
                    reason = "movable test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
        )
        assertNull(movable.inFlightOpenSpec())

        var movableServiceStarts = 0
        val firstStart = mutableListOf<Result<Unit>>()
        val conflictingStart = mutableListOf<Result<Unit>>()
        val coalescedStart = mutableListOf<Result<Unit>>()
        val reservedStartEpoch = movable.beginSession()
        assertNull(movable.inFlightOpenSpec())

        forwardResolvedDesktopOpen(
            currentDisplayId = hdmi.id,
            mode = "proot",
            desktopEnv = "xfce4",
            startSession = true,
            expectedEpoch = reservedStartEpoch,
            startService = { movableServiceStarts++ },
            isRestarting = { movable.isRestarting },
            requestOpen = movable::requestOpen,
            completion = firstStart::add,
        )
        val startingSpec = checkNotNull(movable.inFlightOpenSpec())
        assertEquals(
            InFlightDesktopOpenSpec("proot", "xfce4", startSession = true, expectedEpoch = null),
            startingSpec,
        )

        // Different desktop/mode in ready branch forwards changed spec and is rejected without corrupting active open
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = "chroot",
            desktopEnv = "plasma",
            startSession = true,
            expectedEpoch = null,
            startService = { movableServiceStarts++ },
            isRestarting = { movable.isRestarting },
            requestOpen = movable::requestOpen,
            completion = conflictingStart::add,
        )
        assertEquals(
            "INVALID_ARGUMENT",
            (conflictingStart.single().exceptionOrNull() as DesktopDisplayException).code,
        )

        // Subsequent caller on phone display reads inFlightOpenSpec() and coalesces statelessly onto HDMI startup
        val postRejectSpec = checkNotNull(movable.inFlightOpenSpec())
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = postRejectSpec.mode,
            desktopEnv = postRejectSpec.desktopEnv,
            startSession = postRejectSpec.startSession,
            expectedEpoch = postRejectSpec.expectedEpoch,
            startService = { movableServiceStarts++ },
            isRestarting = { movable.isRestarting },
            requestOpen = movable::requestOpen,
            completion = coalescedStart::add,
        )
        assertEquals(1, movableServiceStarts)
        assertEquals(1, movableHost.launches.size)
        movableHost.running = true
        movableHost.attachLatest(movable)
        movable.sessionStarted(movableHost.launches.single().sessionGeneration, Result.success(Unit))
        assertTrue(firstStart.single().isSuccess)
        assertTrue(coalescedStart.single().isSuccess)
        assertNull(movable.inFlightOpenSpec())

        // Running move coalesces statelessly via inFlightOpenSpec() without restarting service
        movableHost.focusResult = false
        movableHost.currentDisplayId = phone.id
        val firstMove = mutableListOf<Result<Unit>>()
        val coalescedMove = mutableListOf<Result<Unit>>()
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = movable.sessionMode,
            desktopEnv = movable.desktopEnv,
            startSession = false,
            expectedEpoch = movable.sessionGeneration,
            startService = { movableServiceStarts++ },
            isRestarting = { movable.isRestarting },
            requestOpen = movable::requestOpen,
            completion = firstMove::add,
        )
        val movingSpec = checkNotNull(movable.inFlightOpenSpec())
        assertEquals(
            InFlightDesktopOpenSpec("proot", "xfce4", startSession = false, expectedEpoch = movable.sessionGeneration),
            movingSpec,
        )
        forwardResolvedDesktopOpen(
            currentDisplayId = hdmi.id,
            mode = movingSpec.mode,
            desktopEnv = movingSpec.desktopEnv,
            startSession = movingSpec.startSession,
            expectedEpoch = movingSpec.expectedEpoch,
            startService = { movableServiceStarts++ },
            isRestarting = { movable.isRestarting },
            requestOpen = movable::requestOpen,
            completion = coalescedMove::add,
        )
        assertEquals(2, movableHost.launches.size)
        movableHost.attachLatest(movable)
        assertTrue(firstMove.single().isSuccess)
        assertTrue(coalescedMove.single().isSuccess)
        assertEquals(1, movableServiceStarts)
        assertNull(movable.inFlightOpenSpec())

        // Display-local restart: coalesces same display across teardown & starting, supersedes on display/environment change
        val localHost = FakeCoordinatorHost(listOf(phone, tnt)).apply {
            currentDisplayId = phone.id
            running = true
        }
        var stopCompletion: ((Result<Unit>) -> Unit)? = null
        var localStops = 0
        lateinit var local: DesktopDisplayCoordinator
        local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "display-local test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
            stopForEnvironmentChange = { completion ->
                localStops++
                local.stop(preserveRestart = true)
                localHost.running = false
                stopCompletion = completion
            },
        )
        local.requestOpen(DesktopOpenRequest(phone.id, "termux", "xfce4", false, null), {}, {})
        localHost.attachLatest(local)

        localHost.currentDisplayId = tnt.id
        var restartServiceStarts = 0
        val tntFirst = mutableListOf<Result<Unit>>()
        val tntCoalesced = mutableListOf<Result<Unit>>()
        val tntChangedDesktop = mutableListOf<Result<Unit>>()
        val phoneSuperseded = mutableListOf<Result<Unit>>()
        val phoneCoalesced = mutableListOf<Result<Unit>>()

        forwardResolvedDesktopOpen(
            currentDisplayId = tnt.id,
            mode = "proot",
            desktopEnv = "xfce4",
            startSession = true,
            expectedEpoch = local.sessionGeneration,
            startService = { restartServiceStarts++ },
            isRestarting = { local.isRestarting },
            requestOpen = local::requestOpen,
            completion = tntFirst::add,
        )
        val teardownSpec = checkNotNull(local.inFlightOpenSpec())
        assertEquals(
            InFlightDesktopOpenSpec("proot", "xfce4", startSession = false, expectedEpoch = local.sessionGeneration),
            teardownSpec,
        )
        forwardResolvedDesktopOpen(
            currentDisplayId = tnt.id,
            mode = teardownSpec.mode,
            desktopEnv = teardownSpec.desktopEnv,
            startSession = teardownSpec.startSession,
            expectedEpoch = teardownSpec.expectedEpoch,
            startService = { restartServiceStarts++ },
            isRestarting = { local.isRestarting },
            requestOpen = local::requestOpen,
            completion = tntCoalesced::add,
        )
        assertTrue(tntFirst.isEmpty())
        assertTrue(tntCoalesced.isEmpty())

        // startLinux ready branch for a different desktop ("plasma") during restart forwards !running && !isRestarting = false
        forwardResolvedDesktopOpen(
            currentDisplayId = tnt.id,
            mode = "proot",
            desktopEnv = "plasma",
            startSession = false,
            expectedEpoch = local.sessionGeneration.takeIf { local.isRestarting },
            startService = { restartServiceStarts++ },
            isRestarting = { local.isRestarting },
            requestOpen = local::requestOpen,
            completion = tntChangedDesktop::add,
        )
        assertEquals("SESSION_STOPPED", (tntFirst.single().exceptionOrNull() as DesktopDisplayException).code)
        assertEquals("SESSION_STOPPED", (tntCoalesced.single().exceptionOrNull() as DesktopDisplayException).code)
        assertEquals("plasma", local.inFlightOpenSpec()?.desktopEnv)

        // Caller on changed display (phone) forwards phone.id with inFlightOpenSpec() and supersedes TNT restart
        localHost.currentDisplayId = phone.id
        val changedDisplaySpec = checkNotNull(local.inFlightOpenSpec())
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = changedDisplaySpec.mode,
            desktopEnv = changedDisplaySpec.desktopEnv,
            startSession = changedDisplaySpec.startSession,
            expectedEpoch = changedDisplaySpec.expectedEpoch,
            startService = { restartServiceStarts++ },
            isRestarting = { local.isRestarting },
            requestOpen = local::requestOpen,
            completion = phoneSuperseded::add,
        )
        assertEquals("SESSION_STOPPED", (tntChangedDesktop.single().exceptionOrNull() as DesktopDisplayException).code)

        stopCompletion!!.invoke(Result.success(Unit))
        assertEquals(1, localStops)
        val startingRestartSpec = checkNotNull(local.inFlightOpenSpec())
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = startingRestartSpec.mode,
            desktopEnv = startingRestartSpec.desktopEnv,
            startSession = startingRestartSpec.startSession,
            expectedEpoch = startingRestartSpec.expectedEpoch,
            startService = { restartServiceStarts++ },
            isRestarting = { local.isRestarting },
            requestOpen = local::requestOpen,
            completion = phoneCoalesced::add,
        )
        assertEquals(1, restartServiceStarts)
        assertEquals(phone.id, localHost.launches.last().display.id)
        assertEquals("plasma", localHost.launches.last().desktopEnv)
        localHost.running = true
        localHost.attachLatest(local)
        local.sessionStarted(localHost.launches.last().sessionGeneration, Result.success(Unit))
        assertTrue(phoneSuperseded.single().isSuccess)
        assertTrue(phoneCoalesced.single().isSuccess)
        assertNull(local.inFlightOpenSpec())
    }

    @Test
    fun deferredResolvedOpenUsesUserStopGenerationAcrossPreserveRestartAndUserStop() {
        // 1. Deferred during preserveRestart teardown (isStopped=true, isRestarting=true, userStopGeneration unchanged)
        //    retries on attachment both while still in teardown (17L) and after advancing to startingRestart (18L).
        var attachDuringTeardown: (() -> Unit)? = null
        val teardownRetries = mutableListOf<Long?>()
        var restarting = true
        var stopped = true
        var generation = 17L
        var stopGen = 3L

        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = 17L,
                sessionGeneration = generation,
                isStopped = stopped,
                isSessionCurrent = { !stopped && it == generation },
                deferUntilAttached = { attachDuringTeardown = it },
                retry = teardownRetries::add,
                cancel = { error("preserveRestart teardown must retry on attachment") },
                userStopGeneration = stopGen,
                currentUserStopGeneration = { stopGen },
                isRestarting = { restarting },
                currentSessionGeneration = { generation },
            ),
        )
        attachDuringTeardown!!.invoke()
        assertEquals(listOf<Long?>(17L), teardownRetries)

        var attachDuringStartingRestart: (() -> Unit)? = null
        val startingRetries = mutableListOf<Long?>()
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = 17L,
                sessionGeneration = generation,
                isStopped = stopped,
                isSessionCurrent = { !stopped && it == generation },
                deferUntilAttached = { attachDuringStartingRestart = it },
                retry = startingRetries::add,
                cancel = { error("active restart start must retry on attachment") },
                userStopGeneration = stopGen,
                currentUserStopGeneration = { stopGen },
                isRestarting = { restarting },
                currentSessionGeneration = { generation },
            ),
        )
        stopped = false
        generation = 18L
        attachDuringStartingRestart!!.invoke()
        assertEquals(listOf<Long?>(18L), startingRetries)

        // 2. Restart finishes completely before attachment with userStopGeneration unchanged -> retries with null epoch
        var attachAfterFinishedRestart: (() -> Unit)? = null
        val finishedRetries = mutableListOf<Long?>()
        stopped = true
        restarting = true
        generation = 21L
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = 21L,
                sessionGeneration = generation,
                isStopped = stopped,
                isSessionCurrent = { !stopped && it == generation },
                deferUntilAttached = { attachAfterFinishedRestart = it },
                retry = finishedRetries::add,
                cancel = { error("completed preserveRestart must retry on attachment") },
                userStopGeneration = stopGen,
                currentUserStopGeneration = { stopGen },
                isRestarting = { restarting },
                currentSessionGeneration = { generation },
            ),
        )
        restarting = false
        stopped = false
        generation = 22L
        attachAfterFinishedRestart!!.invoke()
        assertEquals(listOf<Long?>(null), finishedRetries)

        // 3. End-to-end against DesktopDisplayCoordinator: when inFlightOpenSpec() (startSession=false) is deferred
        //    during preserveRestart teardown and the pending restart aborts before decorView attaches (either via
        //    validateRestartTarget leaving isStopped=true or launch failure in startOnTarget leaving isStopped=false,
        //    hasRunningSession=false), attachment retries with start=true and launches cleanly on the attached display.
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt", "TNT", isDefault = false, eligible = true, null)
        val localHost = FakeCoordinatorHost(listOf(phone, tnt)).apply {
            currentDisplayId = phone.id
            running = true
        }
        var stopCompletion: ((Result<Unit>) -> Unit)? = null
        lateinit var local: DesktopDisplayCoordinator
        local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "deferred restart abort test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
            stopForEnvironmentChange = { completion ->
                local.stop(preserveRestart = true)
                localHost.running = false
                stopCompletion = completion
            },
        )
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", false, null), {}, {})
        localHost.attachLatest(local)

        // 3a. validateRestartTarget aborts TNT restart when unattached MainActivity binds on Phone (isStopped=true)
        localHost.currentDisplayId = tnt.id
        local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", true, local.sessionGeneration), {}, {})
        val inFlightTeardown = checkNotNull(local.inFlightOpenSpec())
        assertFalse(inFlightTeardown.startSession)
        val wasRestartingA = local.isRestarting
        val initialStopGenA = local.userStopGeneration
        var pendingPhoneAttach: (() -> Unit)? = null
        val phoneReplyOutcomes = mutableListOf<String>()
        val phoneReply = userStopLaunchReply(
            object : MethodChannel.Result {
                override fun success(result: Any?) {
                    phoneReplyOutcomes += "success:$result"
                }
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    phoneReplyOutcomes += "error:$errorCode"
                }
                override fun notImplemented() = error("unexpected notImplemented")
            },
            local,
        )
        var phoneServiceStarts = 0
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = inFlightTeardown.expectedEpoch,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { pendingPhoneAttach = it },
                retry = { deferredEpoch ->
                    forwardResolvedDesktopOpen(
                        currentDisplayId = phone.id,
                        mode = inFlightTeardown.mode,
                        desktopEnv = inFlightTeardown.desktopEnv,
                        startSession = resolveDeferredOpenStart(
                            inFlightTeardown.startSession,
                            wasRestartingA,
                            local.isRestarting,
                            local.hasRunningSession,
                        ),
                        expectedEpoch = deferredEpoch,
                        startService = {
                            phoneServiceStarts++
                            localHost.running = true
                        },
                        isRestarting = { local.isRestarting },
                        requestOpen = local::requestOpen,
                    ) { outcome ->
                        outcome.exceptionOrNull()?.let { err ->
                            phoneReply.error((err as? DesktopDisplayException)?.code ?: "DISPLAY_UNAVAILABLE", err.message, null)
                        } ?: phoneReply.success(true)
                    }
                },
                cancel = { error("aborted preserveRestart without user Stop must recover on attachment") },
                userStopGeneration = initialStopGenA,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { local.isRestarting },
                currentSessionGeneration = { local.sessionGeneration },
            ),
        )
        localHost.currentDisplayId = phone.id
        stopCompletion!!.invoke(Result.success(Unit))
        assertFalse(local.isRestarting)
        assertTrue(local.isStopped)
        assertFalse(local.hasRunningSession)
        // Aborted preserveRestart published phase="stopped" without advancing userStopGeneration -> reply MUST stay open!
        assertTrue(phoneReplyOutcomes.isEmpty())
        pendingPhoneAttach!!.invoke()
        assertEquals(1, phoneServiceStarts)
        localHost.attachLatest(local)
        local.sessionStarted(localHost.launches.last().sessionGeneration, Result.success(Unit))
        assertEquals(listOf("success:true"), phoneReplyOutcomes)

        // Attached caller on TNT when validateRestartTarget aborts receives DISPLAY_UNAVAILABLE (not clobbered by SESSION_STOPPED)
        localHost.currentDisplayId = tnt.id
        val attachedAbortCodes = mutableListOf<String>()
        val attachedAbortReply = userStopLaunchReply(
            object : MethodChannel.Result {
                override fun success(result: Any?) = error("aborted restart must not succeed")
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    attachedAbortCodes += errorCode
                }
                override fun notImplemented() = error("unexpected notImplemented")
            },
            local,
        )
        local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", true, local.sessionGeneration), {}) { outcome ->
            val err = outcome.exceptionOrNull() as DesktopDisplayException
            attachedAbortReply.error(err.code, err.message, null)
        }
        localHost.currentDisplayId = phone.id
        stopCompletion!!.invoke(Result.success(Unit))
        assertEquals(listOf("DISPLAY_UNAVAILABLE"), attachedAbortCodes)
        // Recover back onto phone before scenario 3b
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", true, null), { localHost.running = true }, {})
        localHost.attachLatest(local)
        local.sessionStarted(localHost.launches.last().sessionGeneration, Result.success(Unit))

        // 3b. startOnTarget fails before decorView attaches (isStopped=false, isRestarting=false, hasRunningSession=false)
        localHost.currentDisplayId = tnt.id
        local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", true, local.sessionGeneration), {}, {})
        val inFlightLaunchAbort = checkNotNull(local.inFlightOpenSpec())
        val wasRestartingB = local.isRestarting
        val initialStopGenB = local.userStopGeneration
        var pendingTntAttach: (() -> Unit)? = null
        val tntRecoveredOutcomes = mutableListOf<Result<Unit>>()
        var tntServiceStarts = 0
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = inFlightLaunchAbort.expectedEpoch,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { pendingTntAttach = it },
                retry = { deferredEpoch ->
                    forwardResolvedDesktopOpen(
                        currentDisplayId = tnt.id,
                        mode = inFlightLaunchAbort.mode,
                        desktopEnv = inFlightLaunchAbort.desktopEnv,
                        startSession = resolveDeferredOpenStart(
                            inFlightLaunchAbort.startSession,
                            wasRestartingB,
                            local.isRestarting,
                            local.hasRunningSession,
                        ),
                        expectedEpoch = deferredEpoch,
                        startService = {
                            tntServiceStarts++
                            localHost.running = true
                        },
                        isRestarting = { local.isRestarting },
                        requestOpen = local::requestOpen,
                        completion = tntRecoveredOutcomes::add,
                    )
                },
                cancel = { error("failed startOnTarget without user Stop must recover on attachment") },
                userStopGeneration = initialStopGenB,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { local.isRestarting },
                currentSessionGeneration = { local.sessionGeneration },
            ),
        )
        localHost.launchFailure = DesktopDisplayException("DISPLAY_UNAVAILABLE", "decorView unattached")
        stopCompletion!!.invoke(Result.success(Unit))
        localHost.launchFailure = null
        assertFalse(local.isRestarting)
        assertFalse(local.isStopped)
        assertFalse(local.hasRunningSession)
        pendingTntAttach!!.invoke()
        assertEquals(1, tntServiceStarts)
        localHost.attachLatest(local)
        local.sessionStarted(localHost.launches.last().sessionGeneration, Result.success(Unit))
        assertTrue(tntRecoveredOutcomes.single().isSuccess)
        assertFalse(resolveDeferredOpenStart(start = true, wasRestarting = true, isRestarting = false, hasRunningSession = true))

        // 3c. Failed stop (isStopped=true, stopFailed=true, hasRunningSession=true) deferred from onCreate
        //     recovers on attachment via coordinator.requestOpen instead of cancelling with SESSION_STOPPED
        local.startStopping()
        local.finishStopping(IllegalStateException("teardown failed"))
        assertTrue(local.isStopped)
        assertTrue(local.hasRunningSession)
        val failedStopEpoch = local.sessionGeneration
        val failedStopGen = local.userStopGeneration
        var pendingFailedStopAttach: (() -> Unit)? = null
        val failedStopRecovered = mutableListOf<Result<Unit>>()
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = failedStopEpoch,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { pendingFailedStopAttach = it },
                retry = { deferredEpoch ->
                    forwardResolvedDesktopOpen(
                        currentDisplayId = tnt.id,
                        mode = local.sessionMode,
                        desktopEnv = local.desktopEnv,
                        startSession = false,
                        expectedEpoch = deferredEpoch,
                        startService = {},
                        isRestarting = { local.isRestarting },
                        requestOpen = local::requestOpen,
                        completion = failedStopRecovered::add,
                    )
                },
                cancel = { error("failed-stop running session without new user Stop must recover on attachment") },
                userStopGeneration = failedStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { local.isRestarting },
                currentSessionGeneration = { local.sessionGeneration },
                hasRunningSession = { local.hasRunningSession },
            ),
        )
        pendingFailedStopAttach!!.invoke()
        localHost.attachLatest(local)
        assertTrue(failedStopRecovered.single().isSuccess)

        // 3d. Non-restarting deferred start=true when startup finishes before decorView attaches reuses running session
        assertFalse(resolveDeferredOpenStart(start = true, wasRestarting = false, isRestarting = false, hasRunningSession = true))
        assertTrue(resolveDeferredOpenStart(start = true, wasRestarting = false, isRestarting = true, hasRunningSession = true))
        assertTrue(resolveDeferredOpenStart(start = true, wasRestarting = false, isRestarting = false, hasRunningSession = false))
        local.stop()
        localHost.running = false
        var nonRestartFinishAttach: (() -> Unit)? = null
        val nonRestartFinishOutcomes = mutableListOf<Result<Unit>>()
        var nonRestartExtraServiceStarts = 0
        local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", true, null), { localHost.running = true }, {})
        val startingInFlight = checkNotNull(local.inFlightOpenSpec())
        assertTrue(startingInFlight.startSession)
        val startingGen = local.sessionGeneration
        val startingStopGen = local.userStopGeneration
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = startingInFlight.expectedEpoch,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { nonRestartFinishAttach = it },
                retry = { deferredEpoch ->
                    forwardResolvedDesktopOpen(
                        currentDisplayId = tnt.id,
                        mode = startingInFlight.mode,
                        desktopEnv = startingInFlight.desktopEnv,
                        startSession = resolveDeferredOpenStart(
                            startingInFlight.startSession,
                            wasRestarting = false,
                            isRestarting = local.isRestarting,
                            hasRunningSession = local.hasRunningSession,
                        ),
                        expectedEpoch = deferredEpoch,
                        startService = { nonRestartExtraServiceStarts++ },
                        isRestarting = { local.isRestarting },
                        requestOpen = local::requestOpen,
                        completion = nonRestartFinishOutcomes::add,
                    )
                },
                cancel = { error("deferred open must focus running session if startup finishes before attach") },
                userStopGeneration = startingStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { local.isRestarting },
                currentSessionGeneration = { local.sessionGeneration },
                hasRunningSession = { local.hasRunningSession },
                hasInFlightOpen = { local.inFlightOpenSpec() != null },
            ),
        )
        // Startup finishes before decorView attaches
        localHost.attachLatest(local)
        local.sessionStarted(startingGen, Result.success(Unit))
        nonRestartFinishAttach!!.invoke()
        assertEquals(0, nonRestartExtraServiceStarts)
        assertEquals(startingGen, local.sessionGeneration)
        assertEquals(startingGen, localHost.launches.last().sessionGeneration)
        localHost.attachLatest(local)
        assertTrue(nonRestartFinishOutcomes.single().isSuccess)

        // 3e. Same-frame dual attachment: Listener 1 bumps sessionGeneration via requestOpen (recording admittedFromEpoch),
        //     and Listener 2 (both admitted expectedEpoch and cold null expectedEpoch) coalesces instead of cancelling!
        val freshHost = FakeCoordinatorHost(listOf(phone, tnt)).apply {
            currentDisplayId = tnt.id
            running = false
        }
        val freshLocal = DesktopDisplayCoordinator(
            host = freshHost,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "fresh dual attach test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
        )
        assertEquals(1L, freshLocal.sessionGeneration)
        var freshListener1: (() -> Unit)? = null
        var freshListener2: (() -> Unit)? = null
        var freshServiceStarts = 0
        val freshOutcomes = mutableListOf<Result<Unit>>()
        listOf(1, 2).forEach { index ->
            assertTrue(
                deferResolvedDesktopOpenIfNeeded(
                    isAttached = false,
                    expectedEpoch = null,
                    sessionGeneration = freshLocal.sessionGeneration,
                    isStopped = freshLocal.isStopped,
                    isSessionCurrent = freshLocal::isSessionCurrent,
                    deferUntilAttached = { if (index == 1) freshListener1 = it else freshListener2 = it },
                    retry = { deferredEpoch ->
                        forwardResolvedDesktopOpen(
                            currentDisplayId = tnt.id,
                            mode = "proot",
                            desktopEnv = "xfce4",
                            startSession = resolveDeferredOpenStart(true, false, freshLocal.isRestarting, freshLocal.hasRunningSession),
                            expectedEpoch = deferredEpoch,
                            startService = { freshServiceStarts++; freshHost.running = true },
                            isRestarting = { freshLocal.isRestarting },
                            requestOpen = freshLocal::requestOpen,
                            completion = freshOutcomes::add,
                        )
                    },
                    cancel = { error("same-frame Listener $index must coalesce instead of cancelling") },
                    userStopGeneration = freshLocal.userStopGeneration,
                    currentUserStopGeneration = { freshLocal.userStopGeneration },
                    isRestarting = { freshLocal.isRestarting },
                    currentSessionGeneration = { freshLocal.sessionGeneration },
                    hasRunningSession = { freshLocal.hasRunningSession },
                    hasInFlightOpen = { freshLocal.inFlightOpenSpec() != null },
                ),
            )
        }
        freshListener1!!.invoke()
        assertEquals(2L, freshLocal.sessionGeneration)
        freshListener2!!.invoke()
        assertEquals(1, freshServiceStarts)
        assertEquals(1, freshHost.launches.size)
        freshHost.attachLatest(freshLocal)
        freshLocal.sessionStarted(freshHost.launches.single().sessionGeneration, Result.success(Unit))
        assertEquals(2, freshOutcomes.size)
        assertTrue(freshOutcomes.all { it.isSuccess })

        local.stop()
        localHost.running = false
        val dualStopGen = local.userStopGeneration
        var dualColdEarlyAttach: (() -> Unit)? = null
        var dualColdAttach: (() -> Unit)? = null
        var dualAdmittedAttach: (() -> Unit)? = null
        val dualOutcomes = mutableListOf<Pair<String, Result<Unit>>>()
        var dualServiceStarts = 0

        // Cold caller deferred BEFORE beginSession() (deferredEpoch = null while stopped)
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = null,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { dualColdEarlyAttach = it },
                retry = { deferredEpoch ->
                    assertNull(deferredEpoch)
                    forwardResolvedDesktopOpen(
                        currentDisplayId = tnt.id,
                        mode = "proot",
                        desktopEnv = "xfce4",
                        startSession = resolveDeferredOpenStart(true, false, local.isRestarting, local.hasRunningSession),
                        expectedEpoch = deferredEpoch,
                        startService = { dualServiceStarts++; localHost.running = true },
                        isRestarting = { local.isRestarting },
                        requestOpen = local::requestOpen,
                        completion = { dualOutcomes += "cold-early" to it },
                    )
                },
                cancel = { error("cold caller must start open on attach") },
                userStopGeneration = dualStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { local.isRestarting },
                currentSessionGeneration = { local.sessionGeneration },
                hasRunningSession = { local.hasRunningSession },
                hasInFlightOpen = { local.inFlightOpenSpec() != null },
            ),
        )
        // Admitted caller (e.g. home-launch) reserves epoch, and cold caller (e.g. maybeAutoStartDesktop) also defers
        val reservedDualEpoch = local.beginSession()
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = null,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { dualColdAttach = it },
                retry = { deferredEpoch ->
                    assertNull(deferredEpoch)
                    forwardResolvedDesktopOpen(
                        currentDisplayId = tnt.id,
                        mode = "proot",
                        desktopEnv = "xfce4",
                        startSession = resolveDeferredOpenStart(true, false, local.isRestarting, local.hasRunningSession),
                        expectedEpoch = deferredEpoch,
                        startService = { dualServiceStarts++; localHost.running = true },
                        isRestarting = { local.isRestarting },
                        requestOpen = local::requestOpen,
                        completion = { dualOutcomes += "cold-same-epoch" to it },
                    )
                },
                cancel = { error("cold Listener 3 must coalesce onto active open") },
                userStopGeneration = dualStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { local.isRestarting },
                currentSessionGeneration = { local.sessionGeneration },
                hasRunningSession = { local.hasRunningSession },
                hasInFlightOpen = { local.inFlightOpenSpec() != null },
            ),
        )
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = reservedDualEpoch,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { dualAdmittedAttach = it },
                retry = { deferredEpoch ->
                    assertEquals(reservedDualEpoch, deferredEpoch)
                    forwardResolvedDesktopOpen(
                        currentDisplayId = tnt.id,
                        mode = "proot",
                        desktopEnv = "xfce4",
                        startSession = resolveDeferredOpenStart(true, false, local.isRestarting, local.hasRunningSession),
                        expectedEpoch = deferredEpoch,
                        startService = { dualServiceStarts++; localHost.running = true },
                        isRestarting = { local.isRestarting },
                        requestOpen = local::requestOpen,
                        completion = { dualOutcomes += "admitted" to it },
                    )
                },
                cancel = { error("Listener 2 must coalesce via admittedFromEpoch instead of cancelling") },
                userStopGeneration = dualStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { local.isRestarting },
                currentSessionGeneration = { local.sessionGeneration },
                hasRunningSession = { local.hasRunningSession },
                hasInFlightOpen = { local.inFlightOpenSpec() != null },
            ),
        )
        val launchesBeforeDual = localHost.launches.size
        dualColdEarlyAttach!!.invoke()
        assertTrue(local.sessionGeneration > reservedDualEpoch)
        dualAdmittedAttach!!.invoke()
        dualColdAttach!!.invoke()
        assertEquals(1, dualServiceStarts)
        assertEquals(launchesBeforeDual + 1, localHost.launches.size)
        localHost.attachLatest(local)
        local.sessionStarted(localHost.launches.last().sessionGeneration, Result.success(Unit))
        assertEquals(3, dualOutcomes.size)
        assertTrue(dualOutcomes.all { it.second.isSuccess })

        // Active provisioning cancels superseded deferred opens (including stopped null-epoch cold opens
        // and completed/aborted wasRestarting opens) while allowing provisioning's own current-epoch handoff!
        local.stop()
        localHost.running = false
        val preProvStopGen = local.userStopGeneration
        var coldStoppedBeforeProvisioningAttach: (() -> Unit)? = null
        var coldStoppedCancelled = 0
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = null,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped, // true -> deferredEpoch == null
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { coldStoppedBeforeProvisioningAttach = it },
                retry = { error("stopped cold open must not retry once provisioning starts") },
                cancel = { coldStoppedCancelled++ },
                userStopGeneration = preProvStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isProvisioningActive = { true },
            ),
        )
        val activeProvEpoch = local.beginSession()
        coldStoppedBeforeProvisioningAttach!!.invoke()
        assertEquals(1, coldStoppedCancelled)
        assertTrue(local.isSessionCurrent(activeProvEpoch))

        var restartBeforeProvisioningAttach: (() -> Unit)? = null
        var restartBeforeProvisioningCancelled = 0
        var wasRestartingFlag = true
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = activeProvEpoch - 1,
                sessionGeneration = activeProvEpoch - 1,
                isStopped = true,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { restartBeforeProvisioningAttach = it },
                retry = { error("aborted preserveRestart open must not retry once provisioning starts") },
                cancel = { restartBeforeProvisioningCancelled++ },
                userStopGeneration = preProvStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isRestarting = { wasRestartingFlag },
                currentSessionGeneration = { local.sessionGeneration },
                isProvisioningActive = { local.isSessionCurrent(activeProvEpoch) },
            ),
        )
        wasRestartingFlag = false
        restartBeforeProvisioningAttach!!.invoke()
        assertEquals(1, restartBeforeProvisioningCancelled)
        assertTrue(local.isSessionCurrent(activeProvEpoch))

        var ownProvisioningHandoffAttach: (() -> Unit)? = null
        val ownProvisioningRetries = mutableListOf<Long?>()
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = activeProvEpoch,
                sessionGeneration = local.sessionGeneration,
                isStopped = local.isStopped,
                isSessionCurrent = local::isSessionCurrent,
                deferUntilAttached = { ownProvisioningHandoffAttach = it },
                retry = ownProvisioningRetries::add,
                cancel = { error("provisioning's own current-epoch handoff must retry on attach") },
                userStopGeneration = preProvStopGen,
                currentUserStopGeneration = { local.userStopGeneration },
                isProvisioningActive = { local.isSessionCurrent(activeProvEpoch) },
            ),
        )
        ownProvisioningHandoffAttach!!.invoke()
        assertEquals(listOf<Long?>(activeProvEpoch), ownProvisioningRetries)

        var attachDuringProvisioning: (() -> Unit)? = null
        var provisioningCancelled = 0
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = null,
                sessionGeneration = 40L,
                isStopped = false,
                isSessionCurrent = { false },
                deferUntilAttached = { attachDuringProvisioning = it },
                retry = { error("active provisioning must not retry stale pre-provisioning open") },
                cancel = { provisioningCancelled++ },
                userStopGeneration = 1L,
                currentUserStopGeneration = { 1L },
                hasInFlightOpen = { true },
                isProvisioningActive = { true },
            ),
        )
        attachDuringProvisioning!!.invoke()
        assertEquals(1, provisioningCancelled)

        // 4. Real user Stop increments userStopGeneration -> ALWAYS cancels deferred open and userStopLaunchReply
        //    with SESSION_STOPPED once, even when expectedEpoch was null or when a new session/restart begins!
        val stopReplyErrors = mutableListOf<String>()
        val stopReply = userStopLaunchReply(
            object : MethodChannel.Result {
                override fun success(result: Any?) = error("stopped launch must not succeed")
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    stopReplyErrors += errorCode
                }
                override fun notImplemented() = error("unexpected notImplemented")
            },
            local,
        )
        local.stop()
        localHost.running = false
        assertEquals(listOf("SESSION_STOPPED"), stopReplyErrors)
        stopReply.error("DESKTOP_LAUNCH_FAILED", "ignored duplicate", null)
        assertEquals(listOf("SESSION_STOPPED"), stopReplyErrors)

        var attachAfterUserStop: (() -> Unit)? = null
        val cancelledOutcomes = mutableListOf<Result<Unit>>()
        restarting = true
        stopped = true
        generation = 25L
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = null,
                sessionGeneration = generation,
                isStopped = true,
                isSessionCurrent = { !stopped && it == generation },
                deferUntilAttached = { attachAfterUserStop = it },
                retry = { error("user Stop must cancel deferred open even if a new restart begins") },
                cancel = {
                    cancelledOutcomes += Result.failure(
                        DesktopDisplayException("SESSION_STOPPED", "The session was stopped before launch"),
                    )
                },
                userStopGeneration = stopGen,
                currentUserStopGeneration = { stopGen },
                isRestarting = { restarting },
                currentSessionGeneration = { generation },
            ),
        )
        stopGen++
        restarting = true
        stopped = false
        generation = 30L
        attachAfterUserStop!!.invoke()
        assertEquals(
            "SESSION_STOPPED",
            (cancelledOutcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
    }

    @Test
    fun provisioningHandoffSettlesNotificationAcrossImmediateNonStartingUnavailableAndDeferredPaths() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val host = FakeCoordinatorHost(listOf(phone)).apply {
            currentDisplayId = phone.id
            running = false
        }
        val coordinator = DesktopDisplayCoordinator(host = host)
        val published = mutableListOf<String?>()
        val provisioning = ProvisioningNotificationState(published::add)

        // 1. Immediate start handoff clears provisioning once startService succeeds
        val startEpoch = coordinator.beginSession()
        var serviceStarts = 0
        provisioning.start(startEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent)
        val startCallbacks = provisioningOpenCallbacks(
            epoch = startEpoch,
            startForegroundService = { serviceStarts++ },
            completeProvisioning = { provisioning.complete(it, coordinator::isSessionCurrent) },
            failProvisioning = { epoch, err -> provisioning.fail(epoch, err, coordinator::isSessionCurrent) },
        )
        val immediateStartOutcomes = mutableListOf<Result<Unit>>()
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = "proot",
            desktopEnv = "xfce4",
            startSession = true,
            expectedEpoch = startEpoch,
            startService = startCallbacks.startService,
            isRestarting = { coordinator.isRestarting },
            requestOpen = coordinator::requestOpen,
        ) { outcome ->
            startCallbacks.onCompletion(outcome)
            immediateStartOutcomes += outcome
        }
        assertEquals(1, serviceStarts)
        assertNull(provisioning.text)
        host.running = true
        host.attachLatest(coordinator)
        coordinator.sessionStarted(startEpoch, Result.success(Unit))
        assertTrue(immediateStartOutcomes.single().isSuccess)
        assertNull(provisioning.text)

        // 2. Non-starting join/move (startSession = false, startService not invoked) still clears provisioning on completion
        val noStartEpoch = coordinator.beginSession()
        provisioning.start(noStartEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent)
        val noStartCallbacks = provisioningOpenCallbacks(
            epoch = noStartEpoch,
            startForegroundService = { serviceStarts++ },
            completeProvisioning = { provisioning.complete(it, coordinator::isSessionCurrent) },
            failProvisioning = { epoch, err -> provisioning.fail(epoch, err, coordinator::isSessionCurrent) },
        )
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = "proot",
            desktopEnv = "xfce4",
            startSession = false,
            expectedEpoch = noStartEpoch,
            startService = noStartCallbacks.startService,
            isRestarting = { false },
            requestOpen = coordinator::requestOpen,
        ) { outcome ->
            noStartCallbacks.onCompletion(outcome)
        }
        host.attachLatest(coordinator)
        assertEquals(1, serviceStarts)
        assertNull(provisioning.text)

        // 3. Immediate service-start failure marks provisioning failed without clearing first
        coordinator.stop()
        host.running = false
        val deniedEpoch = coordinator.beginSession()
        provisioning.start(deniedEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent)
        val deniedCallbacks = provisioningOpenCallbacks(
            epoch = deniedEpoch,
            startForegroundService = { throw SecurityException("background start denied") },
            completeProvisioning = { provisioning.complete(it, coordinator::isSessionCurrent) },
            failProvisioning = { epoch, err -> provisioning.fail(epoch, err, coordinator::isSessionCurrent) },
        )
        val deniedOutcomes = mutableListOf<Result<Unit>>()
        forwardResolvedDesktopOpen(
            currentDisplayId = phone.id,
            mode = "proot",
            desktopEnv = "xfce4",
            startSession = true,
            expectedEpoch = deniedEpoch,
            startService = deniedCallbacks.startService,
            isRestarting = { coordinator.isRestarting },
            requestOpen = coordinator::requestOpen,
        ) { outcome ->
            deniedCallbacks.onCompletion(outcome)
            deniedOutcomes += outcome
        }
        assertEquals(
            "DISPLAY_NOT_ALLOWED",
            (deniedOutcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(
            "Linux desktop failed to start: Android denied starting the Linux service",
            provisioning.text,
        )

        // 4. DISPLAY_UNAVAILABLE (null displayId) invokes onCompletion and marks provisioning failed
        val unavailableEpoch = coordinator.beginSession()
        provisioning.start(unavailableEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent)
        val unavailableCallbacks = provisioningOpenCallbacks(
            epoch = unavailableEpoch,
            startForegroundService = { error("service must not start without a display") },
            completeProvisioning = { provisioning.complete(it, coordinator::isSessionCurrent) },
            failProvisioning = { epoch, err -> provisioning.fail(epoch, err, coordinator::isSessionCurrent) },
        )
        val unavailableOutcomes = mutableListOf<Result<Unit>>()
        forwardResolvedDesktopOpen(
            currentDisplayId = null,
            mode = "proot",
            desktopEnv = "xfce4",
            startSession = true,
            expectedEpoch = unavailableEpoch,
            startService = unavailableCallbacks.startService,
            isRestarting = { false },
            requestOpen = { _, _, _ -> error("requestOpen must not run without a display") },
        ) { outcome ->
            unavailableCallbacks.onCompletion(outcome)
            unavailableOutcomes += outcome
        }
        assertEquals(
            "DISPLAY_UNAVAILABLE",
            (unavailableOutcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertEquals(
            "Linux desktop failed to start: The app window has no current display",
            provisioning.text,
        )

        // 5. Deferred-attachment cancel clears provisioning via cancel -> onCompletion
        val cancelledEpoch = coordinator.beginSession()
        val cancelledInitialStopGen = coordinator.userStopGeneration
        provisioning.start(cancelledEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent)
        val cancelledCallbacks = provisioningOpenCallbacks(
            epoch = cancelledEpoch,
            startForegroundService = { error("cancelled open must not start service") },
            completeProvisioning = { provisioning.complete(it, coordinator::isSessionCurrent) },
            failProvisioning = { epoch, err -> provisioning.fail(epoch, err, coordinator::isSessionCurrent) },
        )
        var pendingAttach: (() -> Unit)? = null
        val cancelledOutcomes = mutableListOf<Result<Unit>>()
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = cancelledEpoch,
                sessionGeneration = coordinator.sessionGeneration,
                isStopped = coordinator.isStopped,
                isSessionCurrent = coordinator::isSessionCurrent,
                deferUntilAttached = { pendingAttach = it },
                retry = { error("stopped epoch must not retry") },
                cancel = {
                    val failure = Result.failure<Unit>(
                        DesktopDisplayException("SESSION_STOPPED", "The session was stopped before launch"),
                    )
                    cancelledCallbacks.onCompletion(failure)
                    cancelledOutcomes += failure
                },
                userStopGeneration = cancelledInitialStopGen,
                currentUserStopGeneration = { coordinator.userStopGeneration },
            ),
        )
        coordinator.stop()
        pendingAttach!!.invoke()
        assertEquals(
            "SESSION_STOPPED",
            (cancelledOutcomes.single().exceptionOrNull() as DesktopDisplayException).code,
        )
        assertNull(provisioning.text)

        // 6. Deferred-attachment retry preserves startService and onCompletion across attachment
        val retryEpoch = coordinator.beginSession()
        val retryInitialStopGen = coordinator.userStopGeneration
        provisioning.start(retryEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent)
        var retryServiceStarts = 0
        val retryCallbacks = provisioningOpenCallbacks(
            epoch = retryEpoch,
            startForegroundService = { retryServiceStarts++ },
            completeProvisioning = { provisioning.complete(it, coordinator::isSessionCurrent) },
            failProvisioning = { epoch, err -> provisioning.fail(epoch, err, coordinator::isSessionCurrent) },
        )
        var retryAttach: (() -> Unit)? = null
        val retryOutcomes = mutableListOf<Result<Unit>>()
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = retryEpoch,
                sessionGeneration = coordinator.sessionGeneration,
                isStopped = coordinator.isStopped,
                isSessionCurrent = coordinator::isSessionCurrent,
                deferUntilAttached = { retryAttach = it },
                retry = { deferredEpoch ->
                    forwardResolvedDesktopOpen(
                        currentDisplayId = phone.id,
                        mode = "chroot",
                        desktopEnv = "xfce4",
                        startSession = true,
                        expectedEpoch = deferredEpoch,
                        startService = retryCallbacks.startService,
                        isRestarting = { coordinator.isRestarting },
                        requestOpen = coordinator::requestOpen,
                    ) { outcome ->
                        retryCallbacks.onCompletion(outcome)
                        retryOutcomes += outcome
                    }
                },
                cancel = { error("current epoch must retry") },
                userStopGeneration = retryInitialStopGen,
                currentUserStopGeneration = { coordinator.userStopGeneration },
            ),
        )
        assertEquals("Preparing Linux desktop", provisioning.text)
        retryAttach!!.invoke()
        assertEquals(1, retryServiceStarts)
        assertNull(provisioning.text)
        host.running = true
        host.attachLatest(coordinator)
        coordinator.sessionStarted(retryEpoch, Result.success(Unit))
        assertTrue(retryOutcomes.single().isSuccess)

        // 7. Activity teardown (onDestroy / isFinishing) settles deferred open once with DISPLAY_UNAVAILABLE
        coordinator.stop()
        host.running = false
        val destroyedEpoch = coordinator.beginSession()
        provisioning.start(destroyedEpoch, "Preparing Linux desktop", coordinator::isSessionCurrent)
        val destroyedCallbacks = provisioningOpenCallbacks(
            epoch = destroyedEpoch,
            startForegroundService = { error("destroyed window must not start service") },
            completeProvisioning = { provisioning.complete(it, coordinator::isSessionCurrent) },
            failProvisioning = { epoch, err -> provisioning.fail(epoch, err, coordinator::isSessionCurrent) },
        )
        val destroyReplyCodes = mutableListOf<String>()
        val destroyReply = userStopLaunchReply(
            object : MethodChannel.Result {
                override fun success(result: Any?) = error("destroyed window must not succeed")
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    destroyReplyCodes += errorCode
                }
                override fun notImplemented() = error("unexpected notImplemented")
            },
            coordinator,
        )
        val pendingCancellations = mutableListOf<() -> Unit>()
        assertTrue(
            deferResolvedDesktopOpenIfNeeded(
                isAttached = false,
                expectedEpoch = destroyedEpoch,
                sessionGeneration = coordinator.sessionGeneration,
                isStopped = coordinator.isStopped,
                isSessionCurrent = coordinator::isSessionCurrent,
                deferUntilAttached = {
                    pendingCancellations += {
                        val failure = DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app window was destroyed before launch")
                        destroyedCallbacks.onCompletion(Result.failure(failure))
                        destroyReply.error(failure.code, failure.message, null)
                    }
                },
                retry = { error("destroyed window must detach listener and never retry") },
                cancel = { error("destroyed window settles via abortOnDestroy") },
                userStopGeneration = coordinator.userStopGeneration,
                currentUserStopGeneration = { coordinator.userStopGeneration },
            ),
        )
        pendingCancellations.toList().also { pendingCancellations.clear() }.forEach { it() }
        assertEquals(listOf("DISPLAY_UNAVAILABLE"), destroyReplyCodes)
        assertEquals(
            "Linux desktop failed to start: The app window was destroyed before launch",
            provisioning.text,
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
            "home async status must delegate to requestResolvedDesktopOpen",
            homeAsyncScope.contains("requestResolvedDesktopOpen("),
        )
        assertTrue(
            "auto provisioning must delegate to requestResolvedDesktopOpen",
            autoSetupScope.contains("requestResolvedDesktopOpen("),
        )
        assertTrue(
            "runtime provisioning must reserve an epoch via beginSession and delegate to requestResolvedDesktopOpen",
            runtimeProvisionScope.contains("displays.beginSession()") &&
                runtimeProvisionScope.contains("requestResolvedDesktopOpen("),
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
        val serviceOnCreate = functionBody(serviceSource, "override fun onCreate()")
        val serviceOnStartCommand = functionBody(serviceSource, "override fun onStartCommand(")
        assertFalse(
            "DroidDeskService.onCreate must not latch startGate during stop-only creation",
            serviceOnCreate.contains("startGate.start"),
        )
        assertTrue(
            "DroidDeskService.onStartCommand must latch startGate only after the ACTION_STOP branch",
            serviceOnStartCommand.indexOf("ACTION_STOP") < serviceOnStartCommand.indexOf("startGate.start"),
        )
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

    @Test
    fun admitDesktopItemLaunchCoalescesInFlightRejectsProvisioningAndPreservesRunningEpoch() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt", "TNT", isDefault = false, eligible = true, null)
        val host = FakeCoordinatorHost(listOf(phone, tnt)).apply {
            currentDisplayId = phone.id
            running = false
        }
        val coordinator = DesktopDisplayCoordinator(host = host)

        // 1. Cold/stopped session calls beginSession() once per admission
        var coldBegins = 0
        val coldAdmission = admitDesktopItemLaunch(
            inFlight = coordinator.inFlightOpenSpec(),
            isStopped = coordinator.isStopped,
            hasRunningSession = coordinator.hasRunningSession,
            sessionGeneration = coordinator.sessionGeneration,
            beginSession = {
                coldBegins++
                coordinator.beginSession()
            },
        ) as DesktopItemAdmission.Epoch
        assertEquals(1, coldBegins)
        assertEquals(coordinator.sessionGeneration, coldAdmission.expectedEpoch)

        // 2. In-flight open returns DesktopItemAdmission.InFlight without calling beginSession()
        coordinator.requestOpen(
            DesktopOpenRequest(phone.id, "proot", "xfce4", true, coldAdmission.expectedEpoch),
            { host.running = true },
            {},
        )
        val expectedInFlight = checkNotNull(coordinator.inFlightOpenSpec())
        val inFlightAdmission = admitDesktopItemLaunch(
            inFlight = coordinator.inFlightOpenSpec(),
            isStopped = coordinator.isStopped,
            hasRunningSession = coordinator.hasRunningSession,
            sessionGeneration = coordinator.sessionGeneration,
            beginSession = { error("in-flight open must not call beginSession") },
        )
        assertEquals(DesktopItemAdmission.InFlight(expectedInFlight), inFlightAdmission)

        // 3. Running session preserves sessionGeneration across concurrent item launches without beginSession()
        host.attachLatest(coordinator)
        coordinator.sessionStarted(coldAdmission.expectedEpoch, Result.success(Unit))
        assertNull(coordinator.inFlightOpenSpec())
        val runningEpoch = coordinator.sessionGeneration
        val itemA = admitDesktopItemLaunch(
            inFlight = coordinator.inFlightOpenSpec(),
            isStopped = coordinator.isStopped,
            hasRunningSession = coordinator.hasRunningSession,
            sessionGeneration = coordinator.sessionGeneration,
            beginSession = { error("running session must not call beginSession") },
        ) as DesktopItemAdmission.Epoch
        val itemB = admitDesktopItemLaunch(
            inFlight = coordinator.inFlightOpenSpec(),
            isStopped = coordinator.isStopped,
            hasRunningSession = coordinator.hasRunningSession,
            sessionGeneration = coordinator.sessionGeneration,
            beginSession = { error("concurrent running item launch must not call beginSession") },
        ) as DesktopItemAdmission.Epoch
        assertEquals(runningEpoch, itemA.expectedEpoch)
        assertEquals(runningEpoch, itemB.expectedEpoch)
        assertTrue(coordinator.isSessionCurrent(itemA.expectedEpoch))

        // 4. Active provisioning rejects with INVALID_ARGUMENT without calling beginSession()
        val provisioningError = org.junit.Assert.assertThrows(DesktopDisplayException::class.java) {
            admitDesktopItemLaunch(
                inFlight = null,
                isStopped = false,
                hasRunningSession = false,
                sessionGeneration = coordinator.sessionGeneration,
                beginSession = { error("active provisioning must not call beginSession") },
                provisioningInFlight = true,
            )
        }
        assertEquals("INVALID_ARGUMENT", provisioningError.code)

        // 5. Stopped session (and failed-stop session where isStopped=true, hasRunningSession=true) calls beginSession()
        coordinator.startStopping()
        coordinator.finishStopping(IllegalStateException("teardown failed"))
        assertTrue(coordinator.isStopped)
        assertTrue(coordinator.hasRunningSession)
        var failedStopBegins = 0
        val recoveredAdmission = admitDesktopItemLaunch(
            inFlight = coordinator.inFlightOpenSpec(),
            isStopped = coordinator.isStopped,
            hasRunningSession = coordinator.hasRunningSession,
            sessionGeneration = coordinator.sessionGeneration,
            beginSession = {
                failedStopBegins++
                coordinator.beginSession()
            },
        ) as DesktopItemAdmission.Epoch
        assertEquals(1, failedStopBegins)
        assertTrue(recoveredAdmission.expectedEpoch > runningEpoch)

        coordinator.stop()
        host.running = false
        var stoppedBegins = 0
        val postStopAdmission = admitDesktopItemLaunch(
            inFlight = coordinator.inFlightOpenSpec(),
            isStopped = coordinator.isStopped,
            hasRunningSession = coordinator.hasRunningSession,
            sessionGeneration = coordinator.sessionGeneration,
            beginSession = {
                stoppedBegins++
                coordinator.beginSession()
            },
        ) as DesktopItemAdmission.Epoch
        assertEquals(1, stoppedBegins)
        assertTrue(postStopAdmission.expectedEpoch > recoveredAdmission.expectedEpoch)
    }

    @Test
    fun resolveDesktopItemStatusHandlesUserStopProvisioningInFlightRunningAndColdStates() {
        data class FakeStatus(val running: Boolean, val desktop: String = "xfce4")

        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt", "TNT", isDefault = false, eligible = true, null)
        val localHost = FakeCoordinatorHost(listOf(phone, tnt)).apply {
            currentDisplayId = phone.id
            running = true
        }
        var stopCompletion: ((Result<Unit>) -> Unit)? = null
        lateinit var local: DesktopDisplayCoordinator
        local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "item status test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
            stopForEnvironmentChange = { completion ->
                local.stop(preserveRestart = true)
                localHost.running = false
                stopCompletion = completion
            },
        )
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", false, null), {}, {})
        localHost.attachLatest(local)

        // 1. Running status -> startSession = false and preserves current expectedEpoch
        val runningEpoch = local.sessionGeneration
        val runningStopGen = local.userStopGeneration
        var readyOutcome: Triple<FakeStatus, Boolean, Long?>? = null
        resolveDesktopItemStatus(
            expectedEpoch = runningEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.success(FakeStatus(running = true)),
            isStatusRunning = FakeStatus::running,
            sessionLive = isDesktopItemSessionLive(
                runningStopGen,
                local.userStopGeneration,
                local.isStopped,
                local.isRestarting,
            ),
            hasRunningSession = local.hasRunningSession,
            isRestarting = local.isRestarting,
            inFlightOpen = local::inFlightOpenSpec,
            onInFlight = { error("running idle session has no in-flight open") },
            onReady = { status, startSession, epoch ->
                readyOutcome = Triple(status, startSession, epoch)
            },
            onFailure = { error("unexpected failure: $it") },
        )
        assertEquals(Triple(FakeStatus(running = true), false, runningEpoch), readyOutcome)

        // 2. Mid-status inFlightOpenSpec() (e.g. display-local preserveRestart teardown) joins in-flight spec
        //    even if runtime status threw IOException during teardown!
        localHost.currentDisplayId = tnt.id
        local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", false, runningEpoch), {}, {})
        assertTrue(local.isRestarting)
        assertTrue(local.isStopped)
        val joinedSpecs = mutableListOf<InFlightDesktopOpenSpec>()
        resolveDesktopItemStatus(
            expectedEpoch = runningEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.failure<FakeStatus>(IOException("teardown closed runtime")),
            isStatusRunning = FakeStatus::running,
            sessionLive = isDesktopItemSessionLive(
                runningStopGen,
                local.userStopGeneration,
                local.isStopped,
                local.isRestarting,
            ),
            hasRunningSession = local.hasRunningSession,
            isRestarting = local.isRestarting,
            inFlightOpen = local::inFlightOpenSpec,
            onInFlight = { joinedSpecs += it },
            onReady = { _, _, _ -> error("superseded restart epoch must join in-flight open") },
            onFailure = { error("active preserveRestart must coalesce rather than fail: $it") },
        )
        assertEquals(listOf(checkNotNull(local.inFlightOpenSpec())), joinedSpecs)

        // Complete the restart; an item whose status read started before restart (running = false snapshot)
        // sees hasRunningSession == true and passes startSession = false with null epoch!
        // Complete the restart; items whose status probe ran before/during teardown (both stale snapshot and
        // failed teardown IOException) rejoin the recovered session using coordinator's current mode, desktop, and epoch!
        stopCompletion!!.invoke(Result.success(Unit))
        localHost.running = true
        localHost.attachLatest(local)
        val recoveredTntEpoch = localHost.launches.last().sessionGeneration
        local.sessionStarted(recoveredTntEpoch, Result.success(Unit))
        val expectedRecoveredSpec = InFlightDesktopOpenSpec(
            local.sessionMode,
            local.desktopEnv,
            startSession = false,
            expectedEpoch = recoveredTntEpoch,
        )
        val postRestartRejoined = mutableListOf<InFlightDesktopOpenSpec>()
        listOf(
            Result.success(FakeStatus(running = false, desktop = "stale-de")),
            Result.failure(IOException("teardown closed runtime during status")),
        ).forEach { staleProbeResult ->
            resolveDesktopItemStatus(
                expectedEpoch = runningEpoch,
                isSessionCurrent = local::isSessionCurrent,
                status = staleProbeResult,
                isStatusRunning = FakeStatus::running,
                sessionLive = isDesktopItemSessionLive(
                    runningStopGen,
                    local.userStopGeneration,
                    local.isStopped,
                    local.isRestarting,
                ),
                hasRunningSession = local.hasRunningSession,
                isRestarting = local.isRestarting,
                inFlightOpen = local::inFlightOpenSpec,
                currentRunningOpen = {
                    InFlightDesktopOpenSpec(local.sessionMode, local.desktopEnv, false, local.sessionGeneration)
                },
                onInFlight = { postRestartRejoined += it },
                onReady = { _, _, _ -> error("stale epoch after completed restart must rejoin recovered session") },
                onFailure = { error("stale teardown probe failure after completed restart must rejoin: $it") },
            )
        }
        assertEquals(listOf(expectedRecoveredSpec, expectedRecoveredSpec), postRestartRejoined)

        // Genuine status failure on a CURRENT running epoch still forwards the probe error to onFailure!
        val currentRunningProbeError = IOException("runtime socket broken")
        val currentRunningProbeFailures = mutableListOf<Throwable>()
        resolveDesktopItemStatus<FakeStatus>(
            expectedEpoch = recoveredTntEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.failure(currentRunningProbeError),
            isStatusRunning = FakeStatus::running,
            sessionLive = true,
            hasRunningSession = local.hasRunningSession,
            isRestarting = local.isRestarting,
            inFlightOpen = local::inFlightOpenSpec,
            currentRunningOpen = {
                InFlightDesktopOpenSpec(local.sessionMode, local.desktopEnv, false, local.sessionGeneration)
            },
            onInFlight = { error("current-epoch probe error must not mask failure") },
            onReady = { _, _, _ -> error("failed probe must not call onReady") },
            onFailure = { currentRunningProbeFailures += it },
        )
        assertSame(currentRunningProbeError, currentRunningProbeFailures.single())

        // 2b. Failed preserveRestart during status() (hasRunningSession == false, inFlightOpenSpec() == null,
        //     sessionFailure == DISPLAY_UNAVAILABLE) rejects superseded epoch with SESSION_STOPPED instead of onReady!
        val beforeFailedRestartEpoch = local.sessionGeneration
        val beforeFailedRestartStopGen = local.userStopGeneration
        localHost.currentDisplayId = phone.id
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", false, beforeFailedRestartEpoch), {}, {})
        localHost.launchFailure = DesktopDisplayException("DISPLAY_UNAVAILABLE", "decorView detached")
        stopCompletion!!.invoke(Result.success(Unit))
        localHost.launchFailure = null
        assertFalse(local.isStopped)
        assertFalse(local.isRestarting)
        assertFalse(local.hasRunningSession)
        assertEquals("DISPLAY_UNAVAILABLE", local.sessionFailure?.code)
        val failedRestartItemErrors = mutableListOf<Throwable>()
        resolveDesktopItemStatus(
            expectedEpoch = beforeFailedRestartEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.success(FakeStatus(running = true)),
            isStatusRunning = FakeStatus::running,
            sessionLive = isDesktopItemSessionLive(
                beforeFailedRestartStopGen,
                local.userStopGeneration,
                local.isStopped,
                local.isRestarting,
            ),
            hasRunningSession = local.hasRunningSession,
            isRestarting = local.isRestarting,
            inFlightOpen = local::inFlightOpenSpec,
            onInFlight = { error("failed restart has no in-flight open") },
            onReady = { _, _, _ -> error("superseded failed-restart item must not open or clobber sessionFailure") },
            onFailure = { failedRestartItemErrors += it },
        )
        assertEquals("SESSION_STOPPED", (failedRestartItemErrors.single() as DesktopDisplayException).code)
        assertEquals("DISPLAY_UNAVAILABLE", local.sessionFailure?.code)

        // 3. User Stop -> SESSION_STOPPED even if another restart/open is active
        local.stop()
        localHost.running = false
        assertFalse(
            isDesktopItemSessionLive(
                runningStopGen,
                local.userStopGeneration,
                isStopped = false,
                isRestarting = true,
            ),
        )
        val userStopFailures = mutableListOf<Throwable>()
        resolveDesktopItemStatus(
            expectedEpoch = runningEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.success(FakeStatus(running = true)),
            isStatusRunning = FakeStatus::running,
            sessionLive = isDesktopItemSessionLive(
                runningStopGen,
                local.userStopGeneration,
                local.isStopped,
                local.isRestarting,
            ),
            inFlightOpen = { error("user-stopped launch must not inspect inFlightOpenSpec") },
            onInFlight = { error("user-stopped launch must not join in-flight open") },
            onReady = { _, _, _ -> error("user-stopped launch must not open") },
            onFailure = { userStopFailures += it },
        )
        assertEquals("SESSION_STOPPED", (userStopFailures.single() as DesktopDisplayException).code)

        // 4. Provisioning supersede -> SESSION_STOPPED
        val coldEpoch = local.beginSession()
        val coldStopGen = local.userStopGeneration
        val provisioningFailures = mutableListOf<Throwable>()
        resolveDesktopItemStatus(
            expectedEpoch = coldEpoch,
            isSessionCurrent = { false },
            status = Result.success(FakeStatus(running = false)),
            isStatusRunning = FakeStatus::running,
            sessionLive = true,
            provisioningInFlight = true,
            inFlightOpen = { error("provisioning supersede must reject before inFlightOpen") },
            onInFlight = { error("provisioning supersede must not join") },
            onReady = { _, _, _ -> error("provisioning supersede must not open") },
            onFailure = { provisioningFailures += it },
        )
        assertEquals("SESSION_STOPPED", (provisioningFailures.single() as DesktopDisplayException).code)

        // 5. Cold status -> startSession = true with current expectedEpoch, and forwards probe errors when idle
        var coldOutcome: Triple<FakeStatus, Boolean, Long?>? = null
        resolveDesktopItemStatus(
            expectedEpoch = coldEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.success(FakeStatus(running = false)),
            isStatusRunning = FakeStatus::running,
            sessionLive = isDesktopItemSessionLive(
                coldStopGen,
                local.userStopGeneration,
                local.isStopped,
                local.isRestarting,
            ),
            hasRunningSession = local.hasRunningSession,
            isRestarting = local.isRestarting,
            inFlightOpen = local::inFlightOpenSpec,
            onInFlight = { error("cold idle session has no in-flight open") },
            onReady = { status, startSession, epoch ->
                coldOutcome = Triple(status, startSession, epoch)
            },
            onFailure = { error("unexpected cold failure: $it") },
        )
        assertEquals(Triple(FakeStatus(running = false), true, coldEpoch), coldOutcome)

        // 5b. Superseded cold epoch when no session is running and no open is in flight (e.g. handleHomeLaunch
        //     reserved a newer beginSession() epoch or provisioning failed) rejects with SESSION_STOPPED!
        val newerColdEpoch = local.beginSession()
        assertTrue(newerColdEpoch > coldEpoch)
        val supersededColdFailures = mutableListOf<Throwable>()
        resolveDesktopItemStatus(
            expectedEpoch = coldEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.success(FakeStatus(running = false)),
            isStatusRunning = FakeStatus::running,
            sessionLive = isDesktopItemSessionLive(
                coldStopGen,
                local.userStopGeneration,
                local.isStopped,
                local.isRestarting,
            ),
            hasRunningSession = local.hasRunningSession,
            isRestarting = local.isRestarting,
            inFlightOpen = local::inFlightOpenSpec,
            onInFlight = { error("no open in flight") },
            onReady = { _, _, _ -> error("superseded cold epoch must not steal newer admission") },
            onFailure = { supersededColdFailures += it },
        )
        assertEquals("SESSION_STOPPED", (supersededColdFailures.single() as DesktopDisplayException).code)
        assertTrue(local.isSessionCurrent(newerColdEpoch))

        val probeError = IOException("status marker unreadable")
        val probeFailures = mutableListOf<Throwable>()
        resolveDesktopItemStatus<FakeStatus>(
            expectedEpoch = newerColdEpoch,
            isSessionCurrent = local::isSessionCurrent,
            status = Result.failure(probeError),
            isStatusRunning = FakeStatus::running,
            sessionLive = true,
            inFlightOpen = { null },
            onInFlight = { error("unexpected in-flight") },
            onReady = { _, _, _ -> error("failed status must not call onReady") },
            onFailure = { probeFailures += it },
        )
        assertSame(probeError, probeFailures.single())
    }

    @Test
    fun runDesktopItemAfterOpenGuardsOpenEpochRejoinsPreserveRestartAndRejectsStaleEpochs() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt", "TNT", isDefault = false, eligible = true, null)
        val localHost = FakeCoordinatorHost(listOf(phone, tnt)).apply {
            currentDisplayId = phone.id
            running = true
        }
        var stopCompletion: ((Result<Unit>) -> Unit)? = null
        lateinit var local: DesktopDisplayCoordinator
        local = DesktopDisplayCoordinator(
            host = localHost,
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "post-open epoch guard test",
                    tntDisplayIds = setOf(100003),
                ),
            ),
            stopForEnvironmentChange = { completion ->
                local.stop(preserveRestart = true)
                localHost.running = false
                stopCompletion = completion
            },
        )
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", false, null), {}, {})
        localHost.attachLatest(local)

        fun recordingResult(events: MutableList<String>): MethodChannel.Result =
            userStopLaunchReply(
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        events += "success:$result"
                    }
                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        events += "error:$errorCode"
                    }
                    override fun notImplemented() = error("unexpected notImplemented")
                },
                local,
            )

        // 1. openEpoch current -> executes command once and succeeds; non-zero exit -> DESKTOP_LAUNCH_FAILED
        val okEvents = mutableListOf<String>()
        var okExecutions = 0
        runDesktopItemAfterOpen(
            openEpoch = local.sessionGeneration,
            stopGeneration = local.userStopGeneration,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { it() },
            waitUntilReady = { true },
            executeOnSession = { it() },
            executeCommand = {
                okExecutions++
                Result.success(0 to "")
            },
            postToMain = { it() },
            onRejoin = { error("current epoch must not rejoin: $it") },
            result = recordingResult(okEvents),
        )
        assertEquals(1, okExecutions)
        assertEquals(listOf("success:true"), okEvents)

        val failExitEvents = mutableListOf<String>()
        runDesktopItemAfterOpen(
            openEpoch = local.sessionGeneration,
            stopGeneration = local.userStopGeneration,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { it() },
            waitUntilReady = { true },
            executeOnSession = { it() },
            executeCommand = { Result.success(1 to "gtk-launch failed") },
            postToMain = { it() },
            onRejoin = { error("failed command on current epoch must not rejoin") },
            result = recordingResult(failExitEvents),
        )
        assertEquals(listOf("error:DESKTOP_LAUNCH_FAILED"), failExitEvents)

        // 2. Mid-readiness preserveRestart (waitUntilReady returns false during teardown) AND queued pre-executeSession
        //    preserveRestart -> 0 executions on torn-down epoch, rejoins via inFlightOpenSpec() on main thread,
        //    and executes once after restart completes on the new openEpoch!
        val restartEvents = mutableListOf<String>()
        val restartReply = recordingResult(restartEvents)
        val restartMainQueue = ArrayDeque<() -> Unit>()
        var readinessTask: (() -> Unit)? = null
        val restartExecutedEpochs = mutableListOf<Long>()
        val initialPhoneEpoch = local.sessionGeneration
        val initialStopGen = local.userStopGeneration

        fun launchOnCurrentOrRejoin(spec: InFlightDesktopOpenSpec) {
            forwardResolvedDesktopOpen(
                currentDisplayId = localHost.currentDisplayId,
                mode = spec.mode,
                desktopEnv = spec.desktopEnv,
                startSession = spec.startSession,
                expectedEpoch = spec.expectedEpoch,
                startService = { localHost.running = true },
                isRestarting = { local.isRestarting },
                requestOpen = local::requestOpen,
            ) { outcome ->
                outcome.exceptionOrNull()?.let { err ->
                    restartReply.error((err as? DesktopDisplayException)?.code ?: "DISPLAY_UNAVAILABLE", err.message, null)
                    return@forwardResolvedDesktopOpen
                }
                val newOpenEpoch = local.sessionGeneration
                runDesktopItemAfterOpen(
                    openEpoch = newOpenEpoch,
                    stopGeneration = initialStopGen,
                    coordinator = local,
                    isProvisioningActive = { false },
                    spawnReadiness = { it() },
                    waitUntilReady = { true },
                    executeOnSession = { it() },
                    executeCommand = {
                        restartExecutedEpochs += local.sessionGeneration
                        Result.success(0 to "")
                    },
                    postToMain = { it() },
                    onRejoin = ::launchOnCurrentOrRejoin,
                    result = restartReply,
                )
            }
        }

        runDesktopItemAfterOpen(
            openEpoch = initialPhoneEpoch,
            stopGeneration = initialStopGen,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { readinessTask = it },
            waitUntilReady = {
                // Mid-readiness: user docks to TNT -> preserveRestart tears down Phone session!
                localHost.currentDisplayId = tnt.id
                local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", false, initialPhoneEpoch), {}, {})
                false
            },
            executeOnSession = { error("torn-down epoch must not enter executeOnSession") },
            executeCommand = { error("torn-down epoch must not execute command") },
            postToMain = restartMainQueue::addLast,
            onRejoin = ::launchOnCurrentOrRejoin,
            result = restartReply,
        )
        readinessTask!!.invoke()
        assertTrue(restartExecutedEpochs.isEmpty())
        assertTrue(restartEvents.isEmpty())
        assertTrue(restartMainQueue.isNotEmpty())

        // Drain mainHandler -> rejoins TNT preserveRestart once even if both subscribe and readiness posted
        var rejoinCalls = 0
        while (restartMainQueue.isNotEmpty()) restartMainQueue.removeFirst().invoke()
        assertTrue(restartExecutedEpochs.isEmpty())
        assertTrue(restartEvents.isEmpty())

        // Complete TNT restart -> rejoined launch captures new TNT openEpoch and executes once!
        stopCompletion!!.invoke(Result.success(Unit))
        localHost.running = true
        localHost.attachLatest(local)
        val tntEpoch = localHost.launches.last().sessionGeneration
        local.sessionStarted(tntEpoch, Result.success(Unit))
        assertEquals(listOf(tntEpoch), restartExecutedEpochs)
        assertEquals(listOf("success:true"), restartEvents)

        // 2b. preserveRestart starts AND calls sessionStarted BEFORE waitUntilReady returns (isRestarting == false,
        //     inFlightOpenSpec() == null when readiness unblocks) -> still rejoins the restarted session once!
        val settledRestartEvents = mutableListOf<String>()
        val settledRestartReply = recordingResult(settledRestartEvents)
        val settledRestartMainQueue = ArrayDeque<() -> Unit>()
        var settledReadinessTask: (() -> Unit)? = null
        val settledRestartExecutedEpochs = mutableListOf<Long>()
        val settledRestartStopGen = local.userStopGeneration

        fun rejoinCompletedRestart(spec: InFlightDesktopOpenSpec) {
            rejoinCalls++
            forwardResolvedDesktopOpen(
                currentDisplayId = localHost.currentDisplayId,
                mode = spec.mode,
                desktopEnv = spec.desktopEnv,
                startSession = spec.startSession,
                expectedEpoch = spec.expectedEpoch,
                startService = { localHost.running = true },
                isRestarting = { local.isRestarting },
                requestOpen = local::requestOpen,
            ) { outcome ->
                assertTrue(outcome.isSuccess)
                runDesktopItemAfterOpen(
                    openEpoch = local.sessionGeneration,
                    stopGeneration = settledRestartStopGen,
                    coordinator = local,
                    isProvisioningActive = { false },
                    spawnReadiness = { it() },
                    waitUntilReady = { true },
                    executeOnSession = { it() },
                    executeCommand = {
                        settledRestartExecutedEpochs += local.sessionGeneration
                        Result.success(0 to "")
                    },
                    postToMain = { it() },
                    onRejoin = { error("unexpected second rejoin: $it") },
                    result = settledRestartReply,
                )
            }
        }

        runDesktopItemAfterOpen(
            openEpoch = tntEpoch,
            stopGeneration = settledRestartStopGen,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { settledReadinessTask = it },
            waitUntilReady = {
                // Mid-readiness: preserveRestart back to Phone starts AND completely settles before waitUntilReady returns!
                localHost.currentDisplayId = phone.id
                local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", false, tntEpoch), {}, {})
                stopCompletion!!.invoke(Result.success(Unit))
                localHost.running = true
                localHost.attachLatest(local)
                local.sessionStarted(localHost.launches.last().sessionGeneration, Result.success(Unit))
                assertFalse(local.isRestarting)
                assertNull(local.inFlightOpenSpec())
                true
            },
            executeOnSession = { error("stale TNT readiness must not execute directly without rejoining Phone") },
            executeCommand = { error("stale TNT readiness must not execute command") },
            postToMain = settledRestartMainQueue::addLast,
            onRejoin = ::rejoinCompletedRestart,
            result = settledRestartReply,
        )
        settledReadinessTask!!.invoke()
        while (settledRestartMainQueue.isNotEmpty()) settledRestartMainQueue.removeFirst().invoke()
        assertEquals(1, rejoinCalls)
        localHost.attachLatest(local)
        val fastPhoneEpoch = local.sessionGeneration
        assertEquals(listOf(fastPhoneEpoch), settledRestartExecutedEpochs)
        assertEquals(listOf("success:true"), settledRestartEvents)

        // Move back to TNT so scenario 2c starts from TNT just like before
        localHost.currentDisplayId = tnt.id
        local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", false, fastPhoneEpoch), {}, {})
        stopCompletion!!.invoke(Result.success(Unit))
        localHost.running = true
        localHost.attachLatest(local)
        val tntEpochForPreExec = localHost.launches.last().sessionGeneration
        local.sessionStarted(tntEpochForPreExec, Result.success(Unit))

        // 2c. preserveRestart starts AFTER waitUntilReady passed, while queued on executeOnSession before executeCommand!
        val queuedSessionTasks = ArrayDeque<() -> Unit>()
        val queuedMainTasks = ArrayDeque<() -> Unit>()
        val preExecRestartEvents = mutableListOf<String>()
        val preExecRejoinedSpecs = mutableListOf<InFlightDesktopOpenSpec>()
        runDesktopItemAfterOpen(
            openEpoch = tntEpochForPreExec,
            stopGeneration = local.userStopGeneration,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { it() },
            waitUntilReady = { true },
            executeOnSession = queuedSessionTasks::addLast,
            executeCommand = { error("queued executeOnSession must abort before executeCommand on preserveRestart") },
            postToMain = queuedMainTasks::addLast,
            onRejoin = { preExecRejoinedSpecs += it },
            result = recordingResult(preExecRestartEvents),
        )
        assertEquals(1, queuedSessionTasks.size)
        localHost.currentDisplayId = phone.id
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", false, tntEpochForPreExec), {}, {})
        assertTrue(local.isRestarting)
        queuedSessionTasks.removeFirst().invoke()
        while (queuedMainTasks.isNotEmpty()) queuedMainTasks.removeFirst().invoke()
        assertEquals(listOf(checkNotNull(local.inFlightOpenSpec())), preExecRejoinedSpecs)
        assertTrue(preExecRestartEvents.isEmpty())

        // Settle the phone restart before next scenario
        stopCompletion!!.invoke(Result.success(Unit))
        localHost.running = true
        localHost.attachLatest(local)
        val settledPhoneEpoch = localHost.launches.last().sessionGeneration
        local.sessionStarted(settledPhoneEpoch, Result.success(Unit))

        // 3. Non-restart cold startup replacement (isRestarting == false, inFlightOpenSpec() != null with startSession = true)
        //    -> does NOT rejoin and does NOT execute; fails cleanly with SESSION_STOPPED!
        val replacedSessionQueue = ArrayDeque<() -> Unit>()
        val replacedMainQueue = ArrayDeque<() -> Unit>()
        val replacedEvents = mutableListOf<String>()
        runDesktopItemAfterOpen(
            openEpoch = settledPhoneEpoch,
            stopGeneration = local.userStopGeneration,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { it() },
            waitUntilReady = { true },
            executeOnSession = replacedSessionQueue::addLast,
            executeCommand = { error("replaced epoch without preserveRestart must not execute") },
            postToMain = replacedMainQueue::addLast,
            onRejoin = { error("non-restart cold startup must not rejoin: $it") },
            result = recordingResult(replacedEvents),
        )
        // Cold non-restart startup replaces settledPhoneEpoch without userStopGeneration advancing
        val coldReplacementEpoch = local.beginSession()
        localHost.running = false
        local.requestOpen(
            DesktopOpenRequest(phone.id, "proot", "xfce4", true, coldReplacementEpoch),
            { localHost.running = true },
            {},
        )
        assertFalse(local.isRestarting)
        assertEquals(true, local.inFlightOpenSpec()?.startSession)
        replacedSessionQueue.removeFirst().invoke()
        while (replacedMainQueue.isNotEmpty()) replacedMainQueue.removeFirst().invoke()
        assertEquals(listOf("error:SESSION_STOPPED"), replacedEvents)

        // Finish cold replacement so we have a clean running session for scenarios 4 & 5
        localHost.attachLatest(local)
        local.sessionStarted(coldReplacementEpoch, Result.success(Unit))

        // 4. Provisioning starts mid-readiness -> 0 executions, fails with SESSION_STOPPED
        var provisioningActive = false
        val provEvents = mutableListOf<String>()
        val provMainQueue = ArrayDeque<() -> Unit>()
        runDesktopItemAfterOpen(
            openEpoch = coldReplacementEpoch,
            stopGeneration = local.userStopGeneration,
            coordinator = local,
            isProvisioningActive = { provisioningActive },
            spawnReadiness = { it() },
            waitUntilReady = {
                local.beginSession()
                provisioningActive = true
                true
            },
            executeOnSession = { error("provisioning supersede must not enter executeOnSession") },
            executeCommand = { error("provisioning supersede must not execute command") },
            postToMain = provMainQueue::addLast,
            onRejoin = { error("provisioning supersede must not rejoin") },
            result = recordingResult(provEvents),
        )
        provMainQueue.removeFirst().invoke()
        assertEquals(listOf("error:SESSION_STOPPED"), provEvents)

        // 5. User Stop mid-readiness -> 0 executions, fails with SESSION_STOPPED even if new restart starts later
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", true, local.sessionGeneration), { localHost.running = true }, {})
        val preStopEpoch = local.sessionGeneration
        localHost.attachLatest(local)
        local.sessionStarted(preStopEpoch, Result.success(Unit))
        val preStopGen = local.userStopGeneration
        val stopEvents = mutableListOf<String>()
        val stopMainQueue = ArrayDeque<() -> Unit>()
        runDesktopItemAfterOpen(
            openEpoch = preStopEpoch,
            stopGeneration = preStopGen,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { it() },
            waitUntilReady = {
                local.stop()
                localHost.running = false
                true
            },
            executeOnSession = { error("user-stopped session must not enter executeOnSession") },
            executeCommand = { error("user-stopped session must not execute command") },
            postToMain = stopMainQueue::addLast,
            onRejoin = { error("user-stopped session must never rejoin") },
            result = recordingResult(stopEvents),
        )
        if (stopMainQueue.isNotEmpty()) stopMainQueue.removeFirst().invoke()
        assertEquals(listOf("error:SESSION_STOPPED"), stopEvents)

        // 6. Real background readiness poller blocked in sleep is interrupted immediately on coordinator invalidation!
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", true, null), { localHost.running = true }, {})
        val bgEpoch = local.sessionGeneration
        localHost.attachLatest(local)
        local.sessionStarted(bgEpoch, Result.success(Unit))
        val enteredSleep = java.util.concurrent.CountDownLatch(1)
        val exitedReadiness = java.util.concurrent.CountDownLatch(1)
        val bgMainQueue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        val bgEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        var bgThread: Thread? = null
        runDesktopItemAfterOpen(
            openEpoch = bgEpoch,
            stopGeneration = local.userStopGeneration,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { block ->
                bgThread = Thread {
                    try {
                        block()
                    } finally {
                        exitedReadiness.countDown()
                    }
                }.apply { start() }
            },
            waitUntilReady = {
                enteredSleep.countDown()
                Thread.sleep(10_000)
                true
            },
            executeOnSession = { error("interrupted readiness poller must not execute session") },
            executeCommand = { error("interrupted readiness poller must not execute command") },
            postToMain = bgMainQueue::add,
            onRejoin = { error("stopped background readiness must not rejoin") },
            result = recordingResult(bgEvents),
        )
        assertTrue(enteredSleep.await(2, java.util.concurrent.TimeUnit.SECONDS))
        local.stop()
        localHost.running = false
        assertTrue("background readiness thread must be interrupted promptly", exitedReadiness.await(2, java.util.concurrent.TimeUnit.SECONDS))
        bgThread!!.join(2_000)
        while (bgMainQueue.isNotEmpty()) bgMainQueue.poll()?.invoke()
        assertEquals(listOf("error:SESSION_STOPPED"), bgEvents.toList())

        // 7. Interruption DURING an active readiness probe (Process.waitFor inside awaitDesktopProbe, not Thread.sleep)
        //    destroys the probe process and terminates the readiness loop without running another probe iteration!
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", true, null), { localHost.running = true }, {})
        val probeInterruptEpoch = local.sessionGeneration
        localHost.attachLatest(local)
        local.sessionStarted(probeInterruptEpoch, Result.success(Unit))
        val enteredProbeWait = java.util.concurrent.CountDownLatch(1)
        val exitedProbeReadiness = java.util.concurrent.CountDownLatch(1)
        val probeDestroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        val probeIterations = java.util.concurrent.atomic.AtomicInteger(0)
        val probeMainQueue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        val probeEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        var probeThread: Thread? = null
        runDesktopItemAfterOpen(
            openEpoch = probeInterruptEpoch,
            stopGeneration = local.userStopGeneration,
            coordinator = local,
            isProvisioningActive = { false },
            spawnReadiness = { block ->
                probeThread = Thread {
                    try {
                        block()
                    } finally {
                        exitedProbeReadiness.countDown()
                    }
                }.apply { start() }
            },
            waitUntilReady = {
                val deadline = System.currentTimeMillis() + 10_000L
                var ready = false
                while (System.currentTimeMillis() < deadline && !ready) {
                    ready = com.orailnoor.droiddesk.runtime.awaitDesktopProbe {
                        probeIterations.incrementAndGet()
                        object : Process() {
                            override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
                            override fun getInputStream() = java.io.InputStream.nullInputStream()
                            override fun getErrorStream() = java.io.InputStream.nullInputStream()
                            override fun waitFor(): Int {
                                enteredProbeWait.countDown()
                                Thread.sleep(10_000)
                                return 1
                            }
                            override fun exitValue(): Int = 1
                            override fun destroy() {
                                probeDestroyed.set(true)
                            }
                            override fun destroyForcibly(): Process {
                                probeDestroyed.set(true)
                                return this
                            }
                        }
                    }
                    if (!ready) Thread.sleep(150)
                }
                ready
            },
            executeOnSession = { error("probe-interrupted readiness must not enter executeOnSession") },
            executeCommand = { error("probe-interrupted readiness must not execute command") },
            postToMain = probeMainQueue::add,
            onRejoin = { error("stopped probe-interrupted readiness must not rejoin") },
            result = recordingResult(probeEvents),
        )
        assertTrue(enteredProbeWait.await(2, java.util.concurrent.TimeUnit.SECONDS))
        local.stop()
        localHost.running = false
        assertTrue("readiness interrupted during Process.waitFor must exit promptly", exitedProbeReadiness.await(2, java.util.concurrent.TimeUnit.SECONDS))
        probeThread!!.join(2_000)
        assertTrue(probeDestroyed.get())
        assertEquals(1, probeIterations.get())
        while (probeMainQueue.isNotEmpty()) probeMainQueue.poll()?.invoke()
        assertEquals(listOf("error:SESSION_STOPPED"), probeEvents.toList())
    }

    @Test
    fun productionSourcesEliminateAllLegacyPlacementAndOpenHelpersAndStayStateless() {
        val mainSource = readSource(MAIN_ACTIVITY)
        val serviceSource = readSource(DROID_DESK_SERVICE)
        val forbidden = listOf(
            Regex("""\bBuild\.DEVICE\b"""),
            Regex("""\b100003\b"""),
            Regex("""\bdisplayLocalMode\b"""),
            Regex("""\.isDisplayLocal\b"""),
            Regex("""\bstartupTarget\s*\("""),
            Regex("""\brestartForEnvironmentIfNeeded\s*\("""),
            Regex("""\bopenActive\s*\("""),
            Regex("""\bprepareSession\s*\("""),
            Regex("""\bopenDesktop\s*\("""),
            Regex("""\brequestDesktop\s*\("""),
            Regex("""\blaunchPreparedDesktop\s*\("""),
            Regex("""\blaunchLocalDesktopItem\s*\("""),
            Regex("""\bsessionStarted\b"""),
            Regex("""\bActiveDesktopOpenTracker\b"""),
            Regex("""\bopenTracker\b"""),
            Regex("""\bactiveOpenFor\b"""),
        )

        mapOf(
            "MainActivity.kt" to mainSource,
            "DroidDeskService.kt" to serviceSource,
        ).forEach { (fileName, source) ->
            forbidden.forEach { pattern ->
                assertFalse(
                    "$fileName must not contain ${pattern.pattern}",
                    pattern.containsMatchIn(source),
                )
            }
        }

        val onCreateBody = functionBody(mainSource, "override fun onCreate(savedInstanceState: Bundle?)")
        val externalAutoBody = functionBody(mainSource, "private fun maybeAutoStartExternalDesktop()")
        val prefAutoBody = functionBody(mainSource, "private fun maybeAutoStartDesktop(launchIntent: Intent)")
        val onDestroyBody = functionBody(mainSource, "override fun onDestroy()")
        val homeLaunchBody = functionBody(mainSource, "private fun handleHomeLaunch(homeIntent: Intent)")
        val autoSetupBody = functionBody(mainSource, "private fun runAutoChrootSetup()")
        val itemLaunchScope = sourceBetween(
            mainSource,
            "\"launchDesktopItem\" -> {",
            "// ── In-app updates ──",
        )
        val itemAfterOpen = functionBody(mainSource, "private fun launchDesktopItemAfterOpen(")
        val postOpenHelper = functionBody(mainSource, "internal fun runDesktopItemAfterOpen(")
        val startLinuxScope = sourceBetween(
            mainSource,
            "\"startLinux\" -> {",
            "\"launchDesktopActivity\" -> {",
        )
        val manualLaunchScope = sourceBetween(
            mainSource,
            "\"launchDesktopActivity\" -> {",
            "\"stopLinux\" -> {",
        )
        val resolvedOpen = functionBody(mainSource, "private fun requestResolvedDesktopOpen(")
        val companionBody = functionBody(mainSource, "companion object")

        assertTrue(companionBody.contains("provisioningEpoch"))
        assertTrue(onCreateBody.contains("!DesktopDisplayCoordinator.shared.allowsManualMoves"))
        assertTrue(
            "onCreate must skip handleHomeLaunch and maybeAutoStartDesktop when autoSetup is active",
            Regex("""if\s*\(\s*!intent\.getBooleanExtra\("autoSetup",\s*false\)\s*\)\s*\{\s*handleHomeLaunch\(intent\);\s*maybeAutoStartDesktop\(intent\)\s*\}""")
                .containsMatchIn(onCreateBody),
        )
        assertTrue(onDestroyBody.contains("pendingAttachCancellations"))
        assertTrue(homeLaunchBody.contains("if (isProvisioningActive(coordinator)) return"))
        assertTrue(externalAutoBody.contains("if (isProvisioningActive(coordinator)) return"))
        assertTrue(prefAutoBody.contains("if (isProvisioningActive("))
        assertTrue(startLinuxScope.contains("isProvisioningActive(displays)"))
        assertTrue(manualLaunchScope.contains("isProvisioningActive(displays)"))
        assertTrue(startLinuxScope.contains("userStopLaunchReply(result, displays)"))
        assertFalse(startLinuxScope.contains("DesktopLaunchReply("))
        assertTrue(itemLaunchScope.contains("userStopLaunchReply(result, displays)"))
        assertFalse(itemLaunchScope.contains("DesktopLaunchReply("))
        assertTrue(itemLaunchScope.contains("admitDesktopItemLaunch("))
        assertTrue(itemLaunchScope.contains("resolveDesktopItemStatus("))
        assertTrue(itemLaunchScope.contains("isProvisioningActive(displays)"))
        assertTrue(itemLaunchScope.contains("displays.inFlightOpenSpec()"))
        assertTrue(itemLaunchScope.contains("displays.userStopGeneration"))
        assertTrue(itemLaunchScope.contains("launchDesktopItemAfterOpen("))
        assertTrue(itemAfterOpen.contains("requestResolvedDesktopOpen("))
        assertTrue(itemAfterOpen.contains("val openEpoch = coordinator.sessionGeneration"))
        assertTrue(itemAfterOpen.contains("runDesktopItemAfterOpen("))
        assertTrue(itemAfterOpen.contains("DroidDeskService::executeSession"))
        assertTrue(postOpenHelper.contains("DesktopItemReadinessGate("))
        assertTrue(postOpenHelper.contains("coordinator.isSessionCurrent(openEpoch)"))
        assertTrue(postOpenHelper.contains("coordinator.inFlightOpenSpec()"))
        assertTrue(autoSetupBody.contains("inFlightOpenSpec()"))
        assertTrue(autoSetupBody.contains("isProvisioningActive(coordinator)"))
        assertTrue(autoSetupBody.contains("provisioningOpenCallbacks("))
        assertTrue(startLinuxScope.contains("provisioningOpenCallbacks("))
        assertTrue(resolvedOpen.contains("isFinishing || isDestroyed"))
        assertTrue(resolvedOpen.contains("hasRunningSession = { coordinator.hasRunningSession }"))
        assertTrue(resolvedOpen.contains("hasInFlightOpen = { coordinator.inFlightOpenSpec() != null }"))
        assertTrue(resolvedOpen.contains("isProvisioningActive = { isProvisioningActive(coordinator) }"))
        assertTrue(resolvedOpen.contains("coordinator.userStopGeneration"))
        assertTrue(resolvedOpen.contains("startService = startService"))
        assertTrue(resolvedOpen.contains("onCompletion = onCompletion"))
    }

    private class FakeCoordinatorHost(
        private val availableDisplays: List<DesktopDisplay>,
    ) : DesktopDisplayHost {
        var currentDisplayId: Int? = availableDisplays.firstOrNull()?.id
        var running = false
        var focusResult = true
        var captureTimeouts = false
        var launchFailure: DesktopDisplayException? = null
        val launches = mutableListOf<DesktopLaunchRequest>()
        private val timeouts = mutableListOf<() -> Unit>()

        override fun currentDisplay() = availableDisplays.firstOrNull { it.id == currentDisplayId }
        override fun currentDisplayId() = currentDisplayId
        override fun displays() = availableDisplays
        override fun defaultProfileKind() = ProfileKind.PHONE
        override fun sessionRunning() = running
        override fun launch(request: DesktopLaunchRequest) {
            launchFailure?.let { throw it }
            launches += request
        }
        override fun focus(requestId: Long) = focusResult
        override fun cancelLaunch(requestId: Long) = Unit
        override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit {
            if (!captureTimeouts) return {}
            timeouts += action
            return { timeouts.remove(action) }
        }
        override fun notifyReturnRequired(error: DesktopDisplayException) = Unit

        fun attachLatest(coordinator: DesktopDisplayCoordinator) {
            val request = launches.last()
            val claim = coordinator.claim(request.display.id) {}
            check(claim is RendererClaim.Accepted) { "Expected accepted renderer claim for $request" }
            coordinator.onAttached(claim.lease, request.requestId, PixelSize(1920, 1080))
        }
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
