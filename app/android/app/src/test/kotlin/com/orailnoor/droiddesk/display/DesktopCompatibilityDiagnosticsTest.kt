package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.display.capability.DeviceIdentity
import com.orailnoor.droiddesk.display.capability.DisplayPlacementEvidence
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.capability.PointerAssociationEvidence
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.PresentationContext
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.orailnoor.droiddesk.display.input.DesktopInputPolicy
import com.orailnoor.droiddesk.display.placement.DesktopOpenRequest
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.PlacementPreferenceStore
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import com.orailnoor.droiddesk.display.policy.PresentationPolicy
import com.orailnoor.droiddesk.display.refresh.DisplayRefreshPolicy
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import com.orailnoor.droiddesk.handleDesktopPlacementOverrideCall
import com.orailnoor.droiddesk.runtime.CapabilityEvidence
import com.orailnoor.droiddesk.runtime.DistributionChannel
import com.orailnoor.droiddesk.runtime.DistributionContract
import com.orailnoor.droiddesk.runtime.EvidenceState
import com.orailnoor.droiddesk.runtime.RendererBackend
import com.orailnoor.droiddesk.runtime.RuntimeCapabilities
import com.orailnoor.droiddesk.view.DesktopPresentationPlanner
import com.orailnoor.droiddesk.view.DesktopPresentationSnapshot
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCompatibilityDiagnosticsTest {

    @Test
    fun snapshotPublishesSchema2CompleteDiagnosticsFromReportedPlanWithoutRecomputingPolicy() {
        val phone = DesktopDisplay(
            id = 0,
            key = "phone-conn",
            name = "Phone",
            isDefault = true,
            eligible = true,
            reason = null,
            settingsKey = "default",
        )
        val monitor = DesktopDisplay(
            id = 10,
            key = "hdmi-conn-10",
            name = "Dell U2723Q",
            isDefault = false,
            eligible = true,
            reason = null,
            settingsKey = "external:3:DEL|4:4112|11:Dell U2723Q",
        )
        val host = FakeDiagnosticsHost(listOf(phone, monitor)).apply {
            currentDisplayId = monitor.id
            running = true
        }
        val prefs = FakePlacementPreferenceStore()
        val settingsStore = DisplaySettingsStore(InMemoryDisplayPreferences(), { null }, {})
        var inputPolicyCalls = 0
        var refreshPolicyCalls = 0
        val countingPolicy = PresentationPolicy(
            refreshPolicy = { current, supported ->
                refreshPolicyCalls++
                DisplayRefreshPolicy.resolve(current, supported)
            },
            inputPolicy = { cls, env, caps, mode, route, desk, h, v ->
                inputPolicyCalls++
                DesktopInputPolicy.resolve(cls, env, caps, mode, route, desk, h, v)
            },
        )
        val runtimeCaps = sampleRuntimeCapabilities(
            backend = RendererBackend.PROOT_VIRGL_VTEST,
            rendererState = EvidenceState.SUPPORTED_AND_PROBED,
        )
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            settingsStore = settingsStore,
            presentationPolicy = countingPolicy,
            runtimeCapabilitiesProvider = { runtimeCaps },
            placementPreferenceStore = prefs,
            placementEvidenceProvider = { PresentationFixtures.nx888jHdmiPlacement },
            deviceIdentityProvider = { PresentationFixtures.nx888jIdentity },
        )

        coordinator.open(monitor.id, monitor.key) {}
        val request = host.launches.single()
        val lease = (coordinator.claim(monitor.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(lease, request.requestId, { monitor.id }) {}

        val planner = DesktopPresentationPlanner(
            policy = countingPolicy,
            onClassified = { coordinator.reportPresentationClass(lease, it) },
            onResolved = { plan, input -> coordinator.reportPresentationPlan(lease, plan, input) },
            readSnapshot = { windowSizeFor ->
                DesktopPresentationSnapshot(
                    rawContext = PresentationFixtures.nx888jHdmi.copyWithSnapshot(
                        windowSizePx = windowSizeFor(monitor.id) ?: PixelSize(2160, 1440),
                    ),
                    environment = DisplayEnvironment.OTHER_EXTERNAL,
                    runtimeCapabilities = runtimeCaps,
                    hasInternalTouchscreen = true,
                    touchscreenDeviceIds = setOf(1),
                    horizontalScrollFactor = 64f,
                    verticalScrollFactor = 64f,
                )
            },
            loadSettings = coordinator::loadSettings,
        )
        val plan = checkNotNull(planner.updateMeasuredWindow(PixelSize(2160, 1440)))
        coordinator.reportGeometry(lease, plan.profileKind, plan.renderSize, plan.presentationClass)
        coordinator.onAttached(lease, request.requestId, PixelSize(2160, 1440))

        val inputCallsBeforeSnapshot = inputPolicyCalls
        val refreshCallsBeforeSnapshot = refreshPolicyCalls

        val snapshot = coordinator.snapshot()
        coordinator.snapshot()

        assertEquals(inputCallsBeforeSnapshot, inputPolicyCalls)
        assertEquals(refreshCallsBeforeSnapshot, refreshPolicyCalls)

        assertEquals(2, snapshot["schemaVersion"])
        assertEquals("attached", snapshot["phase"])
        assertEquals(false, snapshot["displayLocalMode"])
        assertEquals(true, snapshot["crossDisplayMovesAllowed"])
        assertEquals("movable", snapshot["placementMode"])
        assertEquals("runtimeEvidence", snapshot["placementSelectionSource"])
        assertEquals("Runtime evidence permits placement across displays", snapshot["placementReason"])
        assertEquals(true, snapshot["automaticMovesAllowed"])
        assertNull(snapshot["placementOverride"])

        assertEquals("externalPointer", snapshot["presentationClass"])
        assertEquals("external:3:DEL|4:4112|11:Dell U2723Q", snapshot["stableDisplayKey"])
        assertEquals(
            mapOf(
                "touchEvidence" to "none",
                "pointerAssociation" to "defaultOrUnassociated",
                "hasMouse" to true,
                "hasTouchpad" to false,
                "hasKeyboard" to true,
                "hasStylus" to false,
            ),
            snapshot["inputCapabilities"],
        )
        assertEquals("phoneRelay", snapshot["externalInputRoute"])
        assertEquals(false, snapshot["rendererPointerCaptureRequested"])
        assertEquals(true, snapshot["relayPointerCaptureRequested"])
        assertEquals(60.0, snapshot["refreshPreferredRate"])
        assertEquals(10, snapshot["refreshPreferredModeId"])
        assertEquals(plan.refresh.reason, snapshot["refreshReason"])
        assertEquals(2160, snapshot["renderWidth"])
        assertEquals(1440, snapshot["renderHeight"])
        assertEquals("monitor", snapshot["profileKind"])

        @Suppress("UNCHECKED_CAST")
        val serializedRuntime = checkNotNull(snapshot["runtimeCapabilities"] as? Map<String, Any?>)
        assertEquals(1, serializedRuntime["schemaVersion"])
        assertEquals("prootVirglVtest", serializedRuntime["rendererBackend"])
        assertEquals(true, serializedRuntime["hardwareAccelerationVerified"])
        assertSchema2InvariantsHold(snapshot)
    }

    @Test
    fun snapshotKeepsRendererDiagnosticsNullWhenStoppedBeforePlanReportOrHeadless() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null, settingsKey = "default")
        val tnt = DesktopDisplay(100003, "tnt-conn", "TNT", isDefault = false, eligible = true, null, settingsKey = "   ")
        val host = FakeDiagnosticsHost(listOf(phone, tnt)).apply {
            currentDisplayId = tnt.id
            running = false
        }
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementPreferenceStore = FakePlacementPreferenceStore(),
            placementEvidenceProvider = { PresentationFixtures.r2TntPlacement },
            deviceIdentityProvider = { PresentationFixtures.r2Identity },
        )

        // 1. Stopped before any session
        val initial = coordinator.snapshot()
        assertEquals(2, initial["schemaVersion"])
        assertNull(initial["presentationClass"])
        assertNull(initial["stableDisplayKey"])
        assertNull(initial["inputCapabilities"])
        assertNull(initial["externalInputRoute"])
        assertNull(initial["rendererPointerCaptureRequested"])
        assertNull(initial["relayPointerCaptureRequested"])
        assertNull(initial["refreshPreferredRate"])
        assertNull(initial["refreshPreferredModeId"])
        assertNull(initial["refreshReason"])
        assertNull(initial["runtimeCapabilities"])
        assertSchema2InvariantsHold(initial)

        // 2. Claimed & attached BEFORE renderer reports a PresentationPlan (and blank settingsKey -> null)
        coordinator.requestOpen(
            DesktopOpenRequest(tnt.id, "proot", "xfce4", startSession = true, expectedEpoch = null),
            startService = { host.running = true },
            completion = {},
        )
        val req = host.launches.single()
        val lease = (coordinator.claim(tnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(lease, req.requestId, { tnt.id }) {}
        coordinator.onAttached(lease, req.requestId, PixelSize(2160, 1440))
        coordinator.sessionStarted(req.sessionGeneration, Result.success(Unit))

        val beforePlan = coordinator.snapshot()
        assertNull(beforePlan["stableDisplayKey"])
        assertNull(beforePlan["presentationClass"])
        assertNull(beforePlan["inputCapabilities"])
        assertNull(beforePlan["externalInputRoute"])
        assertNull(beforePlan["rendererPointerCaptureRequested"])
        assertNull(beforePlan["relayPointerCaptureRequested"])
        assertNull(beforePlan["refreshPreferredRate"])
        assertNull(beforePlan["refreshPreferredModeId"])
        assertNull(beforePlan["refreshReason"])
        assertSchema2InvariantsHold(beforePlan)

        // 3. Report a plan with nullable preferredModeId (4K 120Hz display with no 4K@60Hz mode)
        val policy = PresentationPolicy()
        val noModeSwitchContext = PresentationFixtures.fourK120.copyWithSnapshot(displayId = tnt.id)
        val resolvedPlan = policy.resolve(
            context = noModeSwitchContext,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.TNT,
            settings = null,
            runtime = sampleRuntimeCapabilities(),
            horizontalScrollFactor = 64f,
            verticalScrollFactor = 64f,
        )
        coordinator.reportPresentationPlan(lease, resolvedPlan, noModeSwitchContext.input)

        val withPlan = coordinator.snapshot()
        assertEquals("externalPointer", withPlan["presentationClass"])
        assertEquals("native", withPlan["externalInputRoute"])
        assertEquals(true, withPlan["rendererPointerCaptureRequested"])
        assertEquals(false, withPlan["relayPointerCaptureRequested"])
        assertEquals(60.0, withPlan["refreshPreferredRate"])
        assertNull(withPlan["refreshPreferredModeId"])
        assertNotNull(withPlan["refreshReason"])
        assertSchema2InvariantsHold(withPlan)

        // 4. TNT display disconnects -> sessionHeadless = true hides all renderer-owned diagnostics
        host.displays = listOf(phone)
        host.currentDisplayId = phone.id
        coordinator.onDisplaysChanged()
        val headless = coordinator.snapshot()
        assertEquals(true, headless["sessionHeadless"])
        assertNull(headless["activeDisplayId"])
        assertNull(headless["presentationClass"])
        assertNull(headless["stableDisplayKey"])
        assertNull(headless["inputCapabilities"])
        assertNull(headless["externalInputRoute"])
        assertNull(headless["rendererPointerCaptureRequested"])
        assertNull(headless["relayPointerCaptureRequested"])
        assertNull(headless["refreshPreferredRate"])
        assertNull(headless["refreshPreferredModeId"])
        assertNull(headless["refreshReason"])
        assertSchema2InvariantsHold(headless)

        // 5. Stop clears owner & activePlan completely
        coordinator.stop()
        host.running = false
        val stopped = coordinator.snapshot()
        assertEquals("stopped", stopped["phase"])
        assertNull(stopped["presentationClass"])
        assertNull(stopped["inputCapabilities"])
        assertSchema2InvariantsHold(stopped)
    }

    @Test
    fun snapshotSatisfiesCrossLayerPlacementInvariantsAndAvoidsNonTntCopyLeakage() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val external = DesktopDisplay(7, "hdmi:7", "Monitor", isDefault = false, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt:100003", "TNT", isDefault = false, eligible = true, null)

        // 1. Legacy unprobed bootstrap coordinator never emits safeDefault + automaticMovesAllowed=true
        val unprobed = DesktopDisplayCoordinator(host = FakeDiagnosticsHost(listOf(phone, external)))
        val unprobedSnap = unprobed.snapshot()
        assertEquals(true, unprobedSnap["automaticMovesAllowed"])
        assertFalse(unprobedSnap["placementSelectionSource"] == "safeDefault")
        assertSchema2InvariantsHold(unprobedSnap)

        // 2. Ambiguous unknown OEM -> safeDefault with automaticMovesAllowed = false
        val safeDefault = DesktopDisplayCoordinator(
            host = FakeDiagnosticsHost(listOf(phone, external)),
            placementPreferenceStore = FakePlacementPreferenceStore(),
            placementEvidenceProvider = { PresentationFixtures.ambiguousOemPlacement },
            deviceIdentityProvider = { PresentationFixtures.ambiguousOemIdentity },
        )
        val safeSnap = safeDefault.snapshot()
        assertEquals("movable", safeSnap["placementMode"])
        assertEquals("safeDefault", safeSnap["placementSelectionSource"])
        assertEquals(false, safeSnap["automaticMovesAllowed"])
        assertNull(safeSnap["placementOverride"])
        assertSchema2InvariantsHold(safeSnap)

        // 3. Display-local override on non-TNT device uses generic launcher copy (no "TNT" mention)
        val genericLocal = DesktopDisplayCoordinator(
            host = FakeDiagnosticsHost(listOf(phone, external)).apply { currentDisplayId = external.id },
            settingsStore = DisplaySettingsStore(InMemoryDisplayPreferences(), { null }, {}),
            placementPreferenceStore = FakePlacementPreferenceStore(PlacementMode.DISPLAY_LOCAL),
            placementEvidenceProvider = { PresentationFixtures.nx888jHdmiPlacement },
            deviceIdentityProvider = { PresentationFixtures.nx888jIdentity },
        )
        val genericLocalSnap = genericLocal.snapshot()
        assertEquals("displayLocal", genericLocalSnap["placementMode"])
        assertEquals("userOverride", genericLocalSnap["placementSelectionSource"])
        assertEquals("displayLocal", genericLocalSnap["placementOverride"])
        assertSchema2InvariantsHold(genericLocalSnap)

        val autoOpenErr = assertThrows(DesktopDisplayException::class.java) {
            genericLocal.setAutoOpenOnMonitor(true)
        }
        assertFalse(autoOpenErr.message!!.contains("TNT"))
        var moveErr: Throwable? = null
        genericLocal.open(external.id, external.key) { moveErr = it.exceptionOrNull() }
        assertFalse(moveErr!!.message!!.contains("TNT"))

        // 4. R2 TNT environment preserves TNT-specific copy
        val r2TntCoordinator = DesktopDisplayCoordinator(
            host = FakeDiagnosticsHost(listOf(phone, tnt)).apply { currentDisplayId = tnt.id },
            settingsStore = DisplaySettingsStore(InMemoryDisplayPreferences(), { null }, {}),
            placementStrategy = DisplayLocalRestartAdapter(
                PlacementSelection(
                    mode = PlacementMode.DISPLAY_LOCAL,
                    source = PlacementSelectionSource.DEVICE_QUIRK,
                    allowAutomaticMoves = false,
                    reason = "R2 quirk",
                    tntDisplayIds = setOf(100003),
                ),
            ),
        )
        val tntErr = assertThrows(DesktopDisplayException::class.java) {
            r2TntCoordinator.setAutoOpenOnMonitor(true)
        }
        assertTrue(tntErr.message!!.contains("TNT"))
    }

    @Test
    fun setPlacementOverrideRejectsRunningStartingStoppingAndRestartingSessionsWithoutPersisting() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val tnt = DesktopDisplay(100003, "tnt", "TNT", isDefault = false, eligible = true, null)
        val host = FakeDiagnosticsHost(listOf(phone, tnt)).apply {
            currentDisplayId = phone.id
            running = true
        }
        val store = FakePlacementPreferenceStore()
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementPreferenceStore = store,
            placementEvidenceProvider = { PresentationFixtures.nx888jPhonePlacement },
            deviceIdentityProvider = { PresentationFixtures.nx888jIdentity },
        )

        // 1. Running session
        val runningErr = assertThrows(DesktopDisplayException::class.java) {
            coordinator.setPlacementOverride(PlacementMode.DISPLAY_LOCAL)
        }
        assertEquals("INVALID_ARGUMENT", runningErr.code)
        assertEquals("Stop Linux before changing desktop placement mode", runningErr.message)
        assertEquals(0, store.writes)
        assertNull(store.stored)

        // 2. Starting session (host.running = false, prepareSession(start = true))
        host.running = false
        val startEpoch = coordinator.prepareSession("proot", "xfce4", start = true)
        val startingErr = assertThrows(DesktopDisplayException::class.java) {
            coordinator.setPlacementOverride(PlacementMode.DISPLAY_LOCAL)
        }
        assertEquals("INVALID_ARGUMENT", startingErr.code)
        assertEquals(0, store.writes)
        coordinator.sessionStarted(startEpoch, Result.failure(DesktopDisplayException("SESSION_STOPPED", "abort")))
        coordinator.stop()

        // 3. Stopping session (pendingStops > 0)
        coordinator.startStopping()
        val stoppingErr = assertThrows(DesktopDisplayException::class.java) {
            coordinator.setPlacementOverride(PlacementMode.DISPLAY_LOCAL)
        }
        assertEquals("INVALID_ARGUMENT", stoppingErr.code)
        assertEquals(0, store.writes)
        coordinator.finishStopping()

        // 4. Restarting display-local session during environment-change teardown
        var pendingStop: ((Result<Unit>) -> Unit)? = null
        lateinit var local: DesktopDisplayCoordinator
        val localStore = FakePlacementPreferenceStore(PlacementMode.DISPLAY_LOCAL)
        local = DesktopDisplayCoordinator(
            host = host,
            placementPreferenceStore = localStore,
            placementEvidenceProvider = { PresentationFixtures.r2PhonePlacement },
            deviceIdentityProvider = { PresentationFixtures.r2Identity },
            stopForEnvironmentChange = { completion ->
                local.stop(preserveRestart = true)
                host.running = false
                pendingStop = completion
            },
        )
        host.running = true
        local.requestOpen(DesktopOpenRequest(phone.id, "proot", "xfce4", false, null), {}, {})
        val req = host.launches.last()
        val lease = (local.claim(phone.id) {} as RendererClaim.Accepted).lease
        local.registerActivity(lease, req.requestId, { phone.id }) {}
        local.onAttached(lease, req.requestId, PixelSize(1080, 2340))

        host.currentDisplayId = tnt.id
        local.requestOpen(DesktopOpenRequest(tnt.id, "proot", "xfce4", true, local.sessionGeneration), {}, {})
        assertNotNull(pendingStop)
        assertFalse(host.running)
        assertTrue(local.isRestarting)

        val restartingErr = assertThrows(DesktopDisplayException::class.java) {
            local.setPlacementOverride(PlacementMode.MOVABLE)
        }
        assertEquals("INVALID_ARGUMENT", restartingErr.code)
        assertEquals(0, localStore.writes)
        assertEquals(PlacementMode.DISPLAY_LOCAL, localStore.stored)
    }

    @Test
    fun setPlacementOverrideWhileStoppedPersistsReselectsPublishesAndUpgradesSafeDefaultOnDisplayConnect() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val hdmi = DesktopDisplay(10, "hdmi:10", "HDMI", isDefault = false, eligible = true, null)
        var evidence: DisplayPlacementEvidence = PresentationFixtures.ambiguousOemPlacement
        val host = FakeDiagnosticsHost(listOf(phone)).apply {
            currentDisplayId = phone.id
            running = false
        }
        val store = FakePlacementPreferenceStore()
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementPreferenceStore = store,
            placementEvidenceProvider = { evidence },
            deviceIdentityProvider = { PresentationFixtures.ambiguousOemIdentity },
        )
        val published = mutableListOf<Map<String, Any?>>()
        coordinator.subscribe(published::add)

        // Initial stopped state: safeDefault
        assertEquals("safeDefault", coordinator.snapshot()["placementSelectionSource"])
        assertEquals(false, coordinator.snapshot()["automaticMovesAllowed"])

        // 1. Override to DISPLAY_LOCAL
        val localState = coordinator.setPlacementOverride(PlacementMode.DISPLAY_LOCAL)
        assertEquals(PlacementMode.DISPLAY_LOCAL, store.stored)
        assertEquals("displayLocal", localState["placementMode"])
        assertEquals("userOverride", localState["placementSelectionSource"])
        assertEquals("displayLocal", localState["placementOverride"])
        assertEquals(true, localState["displayLocalMode"])
        assertEquals(false, localState["crossDisplayMovesAllowed"])
        assertEquals(false, localState["automaticMovesAllowed"])
        assertEquals("displayLocal", published.last()["placementMode"])
        assertSchema2InvariantsHold(localState)

        // Next start uses DisplayLocalRestartAdapter (disallows manual open())
        var rejectedOpen: Throwable? = null
        coordinator.open(phone.id, phone.key) { rejectedOpen = it.exceptionOrNull() }
        assertEquals("DISPLAY_NOT_ALLOWED", (rejectedOpen as DesktopDisplayException).code)

        // 2. Storage write failure leaves active strategy untouched
        store.failNextWrite = true
        assertThrows(IllegalStateException::class.java) {
            coordinator.setPlacementOverride(PlacementMode.MOVABLE)
        }
        assertEquals(PlacementMode.DISPLAY_LOCAL, coordinator.placementSelection.mode)
        assertEquals("displayLocal", coordinator.snapshot()["placementOverride"])

        // 3. Override to MOVABLE
        val movableState = coordinator.setPlacementOverride(PlacementMode.MOVABLE)
        assertEquals(PlacementMode.MOVABLE, store.stored)
        assertEquals("movable", movableState["placementMode"])
        assertEquals("userOverride", movableState["placementSelectionSource"])
        assertEquals("movable", movableState["placementOverride"])
        assertEquals(false, movableState["displayLocalMode"])
        assertEquals(true, movableState["crossDisplayMovesAllowed"])
        assertEquals(true, movableState["automaticMovesAllowed"])
        assertSchema2InvariantsHold(movableState)

        // 4. Reset override to null (Automatic) -> returns to safeDefault while evidence is inconclusive
        val autoSafeState = coordinator.setPlacementOverride(null)
        assertNull(store.stored)
        assertEquals("movable", autoSafeState["placementMode"])
        assertEquals("safeDefault", autoSafeState["placementSelectionSource"])
        assertNull(autoSafeState["placementOverride"])
        assertEquals(false, autoSafeState["automaticMovesAllowed"])
        assertSchema2InvariantsHold(autoSafeState)

        // 5. Monitor connects while stopped -> automatically upgrades from safeDefault to runtimeEvidence!
        host.displays = listOf(phone, hdmi)
        evidence = PresentationFixtures.nx888jPhonePlacement
        coordinator.onDisplaysChanged()

        val upgraded = coordinator.snapshot()
        assertEquals("movable", upgraded["placementMode"])
        assertEquals("runtimeEvidence", upgraded["placementSelectionSource"])
        assertEquals(true, upgraded["automaticMovesAllowed"])
        assertNull(upgraded["placementOverride"])
        assertEquals("runtimeEvidence", published.last()["placementSelectionSource"])
        assertSchema2InvariantsHold(upgraded)
    }

    @Test
    fun methodChannelHandlerValidatesArgumentsAndTranslatesOverrideErrors() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val host = FakeDiagnosticsHost(listOf(phone)).apply {
            currentDisplayId = phone.id
            running = false
        }
        val store = FakePlacementPreferenceStore()
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementPreferenceStore = store,
            placementEvidenceProvider = { PresentationFixtures.nx888jPhonePlacement },
            deviceIdentityProvider = { PresentationFixtures.nx888jIdentity },
        )

        // Valid displayLocal, movable, and null (Automatic)
        val localResult = RecordingMethodResult()
        handleDesktopPlacementOverrideCall(mapOf("placementMode" to "displayLocal"), coordinator, localResult)
        assertNull(localResult.errorCode)
        assertEquals("displayLocal", (localResult.value as Map<*, *>)["placementOverride"])

        val movableResult = RecordingMethodResult()
        handleDesktopPlacementOverrideCall(mapOf("placementMode" to "movable"), coordinator, movableResult)
        assertNull(movableResult.errorCode)
        assertEquals("movable", (movableResult.value as Map<*, *>)["placementOverride"])

        val autoResult = RecordingMethodResult()
        handleDesktopPlacementOverrideCall(mapOf("placementMode" to null), coordinator, autoResult)
        assertNull(autoResult.errorCode)
        assertNull((autoResult.value as Map<*, *>)["placementOverride"])

        // Invalid enum, wrong value type, missing key, and non-map arguments -> INVALID_ARGUMENT
        listOf<Any?>(
            mapOf("placementMode" to "invalidMode"),
            mapOf("placementMode" to 99),
            emptyMap<String, Any?>(),
            "displayLocal",
            null,
        ).forEach { badArgs ->
            val badResult = RecordingMethodResult()
            handleDesktopPlacementOverrideCall(badArgs, coordinator, badResult)
            assertEquals("Failed for $badArgs", "INVALID_ARGUMENT", badResult.errorCode)
            assertNull(badResult.value)
        }

        // Save failure -> DISPLAY_SETTINGS_SAVE_FAILED
        store.failNextWrite = true
        val saveFailResult = RecordingMethodResult()
        handleDesktopPlacementOverrideCall(mapOf("placementMode" to "displayLocal"), coordinator, saveFailResult)
        assertEquals("DISPLAY_SETTINGS_SAVE_FAILED", saveFailResult.errorCode)

        // Active provisioning -> INVALID_ARGUMENT without mutating store
        val writesBeforeProv = store.writes
        val provResult = RecordingMethodResult()
        handleDesktopPlacementOverrideCall(
            mapOf("placementMode" to "displayLocal"),
            coordinator,
            provResult,
            isProvisioningActive = { true },
        )
        assertEquals("INVALID_ARGUMENT", provResult.errorCode)
        assertEquals("Stop Linux before changing desktop placement mode", provResult.errorMessage)
        assertEquals(writesBeforeProv, store.writes)

        // Running session -> INVALID_ARGUMENT
        host.running = true
        val runningResult = RecordingMethodResult()
        handleDesktopPlacementOverrideCall(mapOf("placementMode" to "displayLocal"), coordinator, runningResult)
        assertEquals("INVALID_ARGUMENT", runningResult.errorCode)
        assertEquals("Stop Linux before changing desktop placement mode", runningResult.errorMessage)
    }

    @Test
    fun runningSafeDefaultPhoneSessionPromotesToRuntimeEvidenceAndSchedulesAutoOpenWhenMonitorConnects() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null)
        val hdmi = DesktopDisplay(10, "hdmi:10", "HDMI", isDefault = false, eligible = true, null)
        var evidence: DisplayPlacementEvidence = DisplayPlacementEvidence(
            currentDisplayId = phone.id,
            isDefaultDisplay = true,
            supportsSecondaryActivities = true,
            currentDisplayLaunchAllowed = true,
            defaultDisplayLaunchAllowed = true,
            anotherDisplayLaunchAllowed = false,
        )
        val host = FakeDiagnosticsHost(listOf(phone)).apply {
            currentDisplayId = phone.id
            running = false
        }
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            settingsStore = DisplaySettingsStore(InMemoryDisplayPreferences(), { null }, {}),
            placementPreferenceStore = FakePlacementPreferenceStore(),
            placementEvidenceProvider = { evidence },
            deviceIdentityProvider = { PresentationFixtures.nx888jIdentity },
        )

        // Boot on phone before HDMI is plugged in -> SAFE_DEFAULT with automaticMovesAllowed = false
        assertEquals("safeDefault", coordinator.snapshot()["placementSelectionSource"])
        assertEquals(false, coordinator.snapshot()["automaticMovesAllowed"])

        // Start Linux on phone
        coordinator.requestOpen(
            DesktopOpenRequest(phone.id, "proot", "xfce4", startSession = true, expectedEpoch = null),
            startService = { host.running = true },
            completion = {},
        )
        val phoneLaunch = host.launches.single()
        val phoneLease = (coordinator.claim(phone.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(phoneLease, phoneLaunch.requestId, { phone.id }) {}
        coordinator.onAttached(phoneLease, phoneLaunch.requestId, PixelSize(1260, 2800))
        coordinator.sessionStarted(phoneLaunch.sessionGeneration, Result.success(Unit))

        // Connect HDMI while Linux is running: display-local evidence MUST NOT flip adapter mid-session,
        // whereas conclusive movable evidence promotes SAFE_DEFAULT -> RUNTIME_EVIDENCE and schedules auto-open!
        evidence = PresentationFixtures.r2TntPlacement
        coordinator.onDisplaysChanged()
        assertEquals("movable", coordinator.snapshot()["placementMode"])
        assertEquals("safeDefault", coordinator.snapshot()["placementSelectionSource"])

        host.displays = listOf(phone, hdmi)
        evidence = PresentationFixtures.nx888jPhonePlacement
        coordinator.onDisplaysChanged()

        val promoted = coordinator.snapshot()
        assertEquals("movable", promoted["placementMode"])
        assertEquals("runtimeEvidence", promoted["placementSelectionSource"])
        assertEquals(true, promoted["automaticMovesAllowed"])
        assertEquals(true, promoted["autoOpenOnMonitor"])
        assertSchema2InvariantsHold(promoted)

        val autoOpenTimer = checkNotNull(host.pendingTimeout)
        autoOpenTimer()
        assertEquals(hdmi.id, host.launches.last().display.id)
    }

    @Test
    fun snapshotNeverCallsDefaultProfileKindAndClearsStaleClassWhenActivityMovesDisplayBeforeReclaim() {
        val phone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = true, null, settingsKey = "default")
        val hdmi = DesktopDisplay(10, "hdmi:10", "HDMI", isDefault = false, eligible = true, null, settingsKey = "external:hdmi")
        val host = FakeDiagnosticsHost(listOf(phone, hdmi)).apply {
            currentDisplayId = phone.id
            running = true
        }
        val runtimeCaps = sampleRuntimeCapabilities()
        val settingsStore = DisplaySettingsStore(InMemoryDisplayPreferences(), { null }, {})
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            settingsStore = settingsStore,
            runtimeCapabilitiesProvider = { runtimeCaps },
            placementPreferenceStore = FakePlacementPreferenceStore(),
            placementEvidenceProvider = { PresentationFixtures.nx888jPhonePlacement },
            deviceIdentityProvider = { PresentationFixtures.nx888jIdentity },
        )

        var actualDisplayId = phone.id
        coordinator.open(phone.id, phone.key) {}
        val req = host.launches.single()
        val lease = (coordinator.claim(phone.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(lease, req.requestId, { actualDisplayId }) {}

        // Snapshot on default display BEFORE reportPresentationPlan / reportGeometry must NOT invoke host.defaultProfileKind()
        host.defaultProfileKindCalls = 0
        val beforePlan = coordinator.snapshot()
        assertEquals(0, host.defaultProfileKindCalls)
        assertNull(beforePlan["profileKind"])
        assertNull(beforePlan["presentationClass"])

        // DesktopPresentationPlanner applies plan BEFORE notifying onResolved / coordinator subscribers
        val events = mutableListOf<String>()
        coordinator.subscribe { state ->
            if (state["presentationClass"] != null) events += "published:${state["presentationClass"]}"
        }
        val planner = DesktopPresentationPlanner(
            onClassified = { coordinator.reportPresentationClass(lease, it) },
            onResolved = { plan, input ->
                events += "onResolved"
                coordinator.reportPresentationPlan(lease, plan, input)
            },
            readSnapshot = { windowSizeFor ->
                DesktopPresentationSnapshot(
                    rawContext = PresentationFixtures.nx888jPhone.copyWithSnapshot(
                        windowSizePx = windowSizeFor(phone.id) ?: PixelSize(1260, 2800),
                    ),
                    environment = DisplayEnvironment.PHONE,
                    runtimeCapabilities = runtimeCaps,
                    hasInternalTouchscreen = true,
                    touchscreenDeviceIds = setOf(1),
                    horizontalScrollFactor = 64f,
                    verticalScrollFactor = 64f,
                )
            },
            loadSettings = coordinator::loadSettings,
            applyPlan = { _, _ ->
                events += "applyPlan"
                null
            },
        )
        val phonePlan = checkNotNull(planner.updateMeasuredWindow(PixelSize(1260, 2800)))
        assertEquals(listOf("applyPlan", "onResolved", "published:phoneTouch"), events)

        coordinator.reportGeometry(lease, phonePlan.profileKind, phonePlan.renderSize, phonePlan.presentationClass)
        coordinator.onAttached(lease, req.requestId, PixelSize(1260, 2800))
        assertEquals("phoneTouch", coordinator.snapshot()["presentationClass"])
        assertEquals("phone", coordinator.snapshot()["profileKind"])

        // Simulate Activity moving to HDMI display before re-claiming a replacement lease:
        // activeDisplayId reflects HDMI, while phone's stale presentationClass / profileKind MUST NOT leak!
        actualDisplayId = hdmi.id
        val movedBeforeReclaim = coordinator.snapshot()
        assertEquals(0, host.defaultProfileKindCalls)
        assertEquals(hdmi.id, movedBeforeReclaim["activeDisplayId"])
        assertEquals("external:hdmi", movedBeforeReclaim["stableDisplayKey"])
        assertNull(movedBeforeReclaim["presentationClass"])
        assertNull(movedBeforeReclaim["profileKind"])
        assertNull(movedBeforeReclaim["inputCapabilities"])
        assertNull(movedBeforeReclaim["externalInputRoute"])
        assertSchema2InvariantsHold(movedBeforeReclaim)
    }

    @Test
    fun defaultProfileKindResolvesFromPresentationPolicyInsteadOfSmallestScreenWidthDp() {
        val policy = PresentationPolicy()
        assertEquals(
            ProfileKind.PHONE,
            resolveDefaultProfileKind(policy) { PresentationFixtures.foldableNarrowWindow },
        )
        assertEquals(
            ProfileKind.WIDE,
            resolveDefaultProfileKind(policy) {
                PresentationFixtures.foldableNarrowWindow.copyWithSnapshot(windowSizePx = PixelSize(1768, 2208))
            },
        )
        assertEquals(
            ProfileKind.PHONE,
            resolveDefaultProfileKind(policy) {
                throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "missing default display")
            },
        )
    }

    private fun assertSchema2InvariantsHold(map: Map<String, Any?>) {
        assertEquals(2, map["schemaVersion"])
        val local = map["displayLocalMode"] as Boolean
        val crossDisplay = map["crossDisplayMovesAllowed"] as Boolean
        val pMode = map["placementMode"] as? String
        val pSource = map["placementSelectionSource"] as? String
        val pReason = map["placementReason"] as? String
        val autoMoves = checkNotNull(map["automaticMovesAllowed"] as? Boolean)
        val pOverride = map["placementOverride"] as? String

        assertNotNull(pMode)
        assertNotNull(pSource)
        assertTrue(!pReason.isNullOrBlank())
        assertEquals(pSource == "userOverride", pOverride != null)
        if (pOverride != null) assertEquals(pOverride, pMode)
        assertEquals(pMode == "displayLocal", local)
        assertEquals(pMode == "movable", crossDisplay)
        assertFalse(local && autoMoves)
        if (pSource == "safeDefault") {
            assertFalse(local)
            assertFalse(autoMoves)
        }
        val rendererCapture = map["rendererPointerCaptureRequested"] as? Boolean
        val relayCapture = map["relayPointerCaptureRequested"] as? Boolean
        assertFalse(rendererCapture == true && relayCapture == true)
        val modeId = map["refreshPreferredModeId"] as? Int
        if (modeId != null) assertTrue(modeId > 0)
        val rate = map["refreshPreferredRate"] as? Double
        if (rate != null) assertTrue(rate.isFinite() && rate > 0.0)
    }

    private fun sampleRuntimeCapabilities(
        backend: RendererBackend = RendererBackend.SOFTWARE_LLVMPIPE,
        rendererState: EvidenceState = EvidenceState.SUPPORTED_AND_PROBED,
    ): RuntimeCapabilities {
        val ok = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        val unavail = CapabilityEvidence(EvidenceState.UNAVAILABLE)
        return RuntimeCapabilities(
            hostApiLevel = 35,
            abi = "arm64-v8a",
            host = ok,
            localExecution = ok,
            proot = ok,
            x11 = ok,
            audio = ok,
            clipboard = CapabilityEvidence(EvidenceState.PRESENT_UNVERIFIED, "Foreground only"),
            chroot = unavail,
            avfFeature = unavail,
            avfPermission = unavail,
            renderer = CapabilityEvidence(rendererState),
            rendererBackend = backend,
            storage = ok,
            availableBytes = 10L * 1024 * 1024 * 1024,
            requiredBytes = 4L * 1024 * 1024 * 1024,
            distribution = DistributionContract(
                channel = DistributionChannel.SIDELOAD_LOCAL_LINUX,
                targetSdk = 28,
                requiresAppPrivateElfExecution = true,
            ),
        )
    }

    private class FakePlacementPreferenceStore(
        var stored: PlacementMode? = null,
    ) : PlacementPreferenceStore {
        var writes = 0
        var failNextWrite = false

        override fun loadOverride(): PlacementMode? = stored

        override fun saveOverride(mode: PlacementMode?) {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("Simulated SharedPreferences commit failure")
            }
            writes++
            stored = mode
        }
    }

    private class InMemoryDisplayPreferences : DisplayPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun read(key: String): Any? = data[key]
        override fun write(changes: Map<String, Any?>) {
            changes.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
        }
    }

    private class RecordingMethodResult : MethodChannel.Result {
        var value: Any? = null
        var errorCode: String? = null
        var errorMessage: String? = null

        override fun success(result: Any?) {
            value = result
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            this.errorCode = errorCode
            this.errorMessage = errorMessage
        }

        override fun notImplemented() = error("Unexpected notImplemented")
    }

    private class FakeDiagnosticsHost(
        var displays: List<DesktopDisplay>,
    ) : DesktopDisplayHost {
        var currentDisplayId: Int? = displays.firstOrNull()?.id
        var running = false
        var defaultProfileKindCalls = 0
        var pendingTimeout: (() -> Unit)? = null
        val launches = mutableListOf<DesktopLaunchRequest>()

        override fun currentDisplay(): DesktopDisplay? =
            displays.firstOrNull { it.id == currentDisplayId }

        override fun currentDisplayId(): Int? = currentDisplayId

        override fun displays(): List<DesktopDisplay> = displays

        override fun defaultProfileKind(): ProfileKind {
            defaultProfileKindCalls++
            return ProfileKind.PHONE
        }

        override fun sessionRunning(): Boolean = running

        override fun launch(request: DesktopLaunchRequest) {
            launches += request
        }

        override fun focus(requestId: Long): Boolean = false

        override fun cancelLaunch(requestId: Long) = Unit

        override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit {
            pendingTimeout = action
            return { if (pendingTimeout === action) pendingTimeout = null }
        }

        override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
    }
}
