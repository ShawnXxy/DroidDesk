package com.orailnoor.droiddesk.view

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplaySettings
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RenderPolicy
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PointerAssociationEvidence
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.PresentationContext
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.orailnoor.droiddesk.display.input.DesktopInputPolicy
import com.orailnoor.droiddesk.display.input.ExternalInputRoute
import com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference
import com.orailnoor.droiddesk.display.input.InputPolicyDecision
import com.orailnoor.droiddesk.display.policy.PresentationPlan
import com.orailnoor.droiddesk.display.policy.PresentationPolicy
import com.orailnoor.droiddesk.display.refresh.DisplayRefreshPolicy
import com.orailnoor.droiddesk.display.refresh.RefreshRequest
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import com.orailnoor.droiddesk.runtime.CapabilityEvidence
import com.orailnoor.droiddesk.runtime.DistributionChannel
import com.orailnoor.droiddesk.runtime.DistributionContract
import com.orailnoor.droiddesk.runtime.EvidenceState
import com.orailnoor.droiddesk.runtime.RendererBackend
import com.orailnoor.droiddesk.runtime.RuntimeCapabilities
import com.orailnoor.droiddesk.x11.ExternalDesktopInputBridge
import com.orailnoor.droiddesk.x11.ExternalDesktopInputTarget
import com.termux.x11.input.TouchInputHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPresentationPlanTest {
    private val targets = mutableListOf<ExternalDesktopInputTarget>()

    @After
    fun tearDown() {
        targets.forEach(ExternalDesktopInputBridge::detach)
        targets.clear()
    }

    @Test
    fun measuredWindowBoundsReplaceSmallestScreenWidthAndFeedSinglePlanToAllConsumers() {
        val harness = PlannerHarness(
            baseContext = PresentationFixtures.foldableNarrowWindow.copyWithSnapshot(
                windowSizePx = PixelSize(1768, 2208),
                input = unknownTouchInput(hasMouse = true),
            ),
            environment = DisplayEnvironment.PHONE,
        )

        // Split-screen narrow window (840px @ 420dpi = 320dp) on a foldable whose full screen is 673dp.
        val narrowContent = PixelSize(840, 2200)
        val narrowPlan = checkNotNull(harness.planner.updateMeasuredWindow(narrowContent))

        assertEquals(1, harness.classifyCalls)
        assertEquals(1, harness.settingsLoads.size)
        assertEquals(ProfileKind.PHONE to 0, harness.settingsLoads.single())
        assertEquals(1, harness.resolveInputCalls)
        assertEquals(1, harness.resolveRefreshCalls)
        assertEquals(PresentationClass.PHONE_TOUCH, narrowPlan.presentationClass)
        assertEquals(ProfileKind.PHONE, narrowPlan.profileKind)
        assertEquals(PixelSize(560, 1466), narrowPlan.renderSize)
        assertEquals(narrowContent, harness.appliedContents.single())
        assertSame(narrowPlan, harness.appliedPlans.single())
        assertEquals(narrowPlan.renderSize, harness.appliedRenderSizes.single())
        assertSame(narrowPlan.input, harness.appliedDecisions.single())
        assertSame(narrowPlan.refresh, harness.appliedRefreshes.single())
        assertEquals(narrowPlan.toDisplayProfile(), harness.appliedProfiles.single())
        assertEquals(88, harness.appliedDockHeights.single())
        assertEquals(TouchInputHandler.InputMode.TOUCH, narrowPlan.input.defaultInputMode)
        assertTrue(narrowPlan.input.allowDirectTouch)

        // Input-device refresh on the same display reuses the measured 840x2200 window instead of physical 1768x2208 mode.
        val refreshedNarrow = checkNotNull(harness.planner.refresh())
        assertEquals(PresentationClass.PHONE_TOUCH, refreshedNarrow.presentationClass)
        assertEquals(PixelSize(560, 1466), refreshedNarrow.renderSize)
        assertEquals(narrowContent, harness.appliedContents.last())

        // Expanding the window to 1768x2208 reclassifies to LARGE_TOUCH (WIDE) and feeds all consumers from the new plan.
        val expandedContent = PixelSize(1768, 2208)
        val expandedPlan = checkNotNull(harness.planner.updateMeasuredWindow(expandedContent))

        assertEquals(PresentationClass.LARGE_TOUCH, expandedPlan.presentationClass)
        assertEquals(ProfileKind.WIDE, expandedPlan.profileKind)
        assertEquals(ProfileKind.WIDE to 0, harness.settingsLoads.last())
        assertEquals(RenderPolicy.NATIVE, expandedPlan.settings.renderPolicy)
        assertEquals(expandedContent, expandedPlan.renderSize)
        assertEquals(expandedContent, harness.appliedContents.last())
        assertFalse(expandedPlan.autoMaximize)
        assertEquals(96, harness.appliedDockHeights.last())
    }

    @Test
    fun nubiaAndR2PhoneWindowsPreserveLegacyPhoneProfileAndExactModeRefresh() {
        val nubiaHarness = PlannerHarness(
            baseContext = PresentationFixtures.nx888jPhone.copyWithSnapshot(
                input = unknownTouchInput(hasMouse = false),
            ),
            environment = DisplayEnvironment.PHONE,
        )
        val nubiaContent = PixelSize(1216, 2688)
        val nubiaPlan = checkNotNull(nubiaHarness.planner.updateMeasuredWindow(nubiaContent))

        assertEquals(PresentationClass.PHONE_TOUCH, nubiaPlan.presentationClass)
        assertEquals(ProfileKind.PHONE, nubiaPlan.profileKind)
        assertEquals(PixelSize(808, 1792), nubiaPlan.renderSize)
        assertEquals(nubiaContent, nubiaHarness.appliedContents.single())
        assertEquals(60f, nubiaPlan.refresh.preferredRefreshRate)
        assertEquals(1, nubiaPlan.refresh.preferredModeId)
        assertEquals(140, nubiaPlan.dpi)
        assertEquals(32, nubiaPlan.cursorPx)
        assertEquals(88, nubiaPlan.dockPx)
        assertTrue(nubiaPlan.autoMaximize)

        val r2Harness = PlannerHarness(
            baseContext = PresentationFixtures.r2Phone.copyWithSnapshot(
                input = unknownTouchInput(hasMouse = false),
            ),
            environment = DisplayEnvironment.PHONE,
        )
        val r2Plan = checkNotNull(r2Harness.planner.updateMeasuredWindow(PixelSize(1080, 2340)))

        assertEquals(PixelSize(720, 1560), r2Plan.renderSize)
        assertEquals(60f, r2Plan.refresh.preferredRefreshRate)
        assertEquals(1, r2Plan.refresh.preferredModeId)
        assertEquals(TouchInputHandler.InputMode.TOUCH, r2Plan.input.defaultInputMode)
        assertTrue(r2Plan.autoMaximize)
    }

    @Test
    fun externalTouchPromotionReclassifiesPlanInSinglePassAndClearsAcrossDisplayMove() {
        val harness = PlannerHarness(
            baseContext = PresentationFixtures.externalTouch.copyWithSnapshot(
                input = InputCapabilities(
                    touch = TouchEvidence.UNKNOWN_ASSOCIATION,
                    pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
                    hasMouse = true,
                    hasTouchpad = false,
                    hasKeyboard = true,
                    hasStylus = false,
                ),
            ),
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            touchscreenDeviceIds = setOf(1, 41),
        )

        val initial = checkNotNull(harness.planner.updateMeasuredWindow(PixelSize(1920, 1080)))
        assertEquals(PresentationClass.EXTERNAL_POINTER, initial.presentationClass)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, initial.input.defaultInputMode)
        assertTrue(initial.input.rendererPointerCapture)

        val classifyBeforePromotion = harness.classifyCalls
        assertTrue(harness.planner.promoteDirectTouch(displayId = 20, deviceId = 41))
        val promoted = checkNotNull(harness.planner.refresh())

        assertEquals(classifyBeforePromotion + 1, harness.classifyCalls)
        assertEquals(PresentationClass.EXTERNAL_TOUCH, promoted.presentationClass)
        assertEquals(TouchInputHandler.InputMode.TOUCH, promoted.settings.inputMode)
        assertEquals(TouchInputHandler.InputMode.TOUCH, promoted.input.defaultInputMode)
        assertTrue(promoted.input.allowDirectTouch)
        assertFalse(promoted.input.rendererPointerCapture)
        assertEquals(PixelSize(1920, 1080), harness.appliedContents.last())

        // Moving to another external display clears both promoted touch and prior display's measured bounds.
        harness.baseContext = PresentationFixtures.r2Tnt.copyWithSnapshot(
            displayId = 22,
            input = InputCapabilities(
                touch = TouchEvidence.UNKNOWN_ASSOCIATION,
                pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
                hasMouse = true,
                hasTouchpad = false,
                hasKeyboard = true,
                hasStylus = false,
            ),
        )
        val movedUnmeasured = checkNotNull(harness.planner.refresh())
        assertEquals(PresentationClass.EXTERNAL_POINTER, movedUnmeasured.presentationClass)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, movedUnmeasured.input.defaultInputMode)
        assertFalse(movedUnmeasured.input.allowDirectTouch)
        assertNull(harness.appliedContents.last())
        assertEquals(PixelSize(2160, 1440), movedUnmeasured.renderSize)
    }

    @Test
    fun softwareFourKUsesRuntimeCapabilitiesAndUnchangedGeometrySkipsDuplicateRelayLaunch() {
        val harness = PlannerHarness(
            baseContext = PresentationFixtures.fourK120.copyWithSnapshot(
                input = InputCapabilities(
                    touch = TouchEvidence.NONE,
                    pointerAssociation = PointerAssociationEvidence.UNKNOWN,
                    hasMouse = true,
                    hasTouchpad = false,
                    hasKeyboard = true,
                    hasStylus = false,
                ),
            ),
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            backend = RendererBackend.SOFTWARE_LLVMPIPE,
        )

        val firstPlan = checkNotNull(harness.planner.updateMeasuredWindow(PixelSize(3840, 2160)))
        assertEquals(RenderPolicy.CAP_1440, firstPlan.settings.renderPolicy)
        assertEquals(PixelSize(2560, 1440), firstPlan.renderSize)
        assertEquals(60f, firstPlan.refresh.preferredRefreshRate)
        assertNull(firstPlan.refresh.preferredModeId)
        assertEquals(ExternalInputRoute.PHONE_RELAY, firstPlan.input.externalInputRoute)
        assertTrue(ExternalDesktopInputBridge.isAttached())
        assertEquals(listOf("open"), harness.relayEvents)

        // Non-positive transient window frame is rejected without clobbering measuredContentSize or invoking applyPlan.
        val plansBeforeInvalid = harness.appliedPlans.size
        assertNull(harness.planner.updateMeasuredWindow(PixelSize(0, 2160)))
        assertEquals(PixelSize(3840, 2160), harness.planner.measuredContentSize)
        assertEquals(plansBeforeInvalid, harness.appliedPlans.size)

        // Repeated geometry pass with identical input decision keeps bridge attached without re-launching relay Activity.
        checkNotNull(harness.planner.updateMeasuredWindow(PixelSize(3840, 2160)))
        assertEquals(listOf("open"), harness.relayEvents)

        // Measured-window settings save (forceRelaySync = true) re-opens relay even when InputPolicyDecision is unchanged.
        checkNotNull(harness.planner.updateMeasuredWindow(PixelSize(3840, 2160), forceRelaySync = true))
        assertEquals(listOf("open", "open"), harness.relayEvents)

        // Explicit input/settings refresh also syncs relay, and hardware backend switches default 4K policy to NATIVE.
        harness.backend = RendererBackend.NATIVE_TURNIP_KGSL
        val hardwarePlan = checkNotNull(harness.planner.refresh())
        assertEquals(RenderPolicy.NATIVE, hardwarePlan.settings.renderPolicy)
        assertEquals(PixelSize(3840, 2160), hardwarePlan.renderSize)
        assertEquals(listOf("open", "open", "open"), harness.relayEvents)
    }

    private fun unknownTouchInput(hasMouse: Boolean) = InputCapabilities(
        touch = TouchEvidence.UNKNOWN_ASSOCIATION,
        pointerAssociation = if (hasMouse) PointerAssociationEvidence.UNKNOWN else PointerAssociationEvidence.NO_EXTERNAL_POINTER,
        hasMouse = hasMouse,
        hasTouchpad = false,
        hasKeyboard = false,
        hasStylus = false,
    )

    private inner class PlannerHarness(
        var baseContext: PresentationContext,
        var environment: DisplayEnvironment,
        var backend: RendererBackend = RendererBackend.SOFTWARE_LLVMPIPE,
        var hasInternalTouchscreen: Boolean = true,
        var touchscreenDeviceIds: Set<Int> = setOf(1),
        val savedByKind: MutableMap<ProfileKind, DisplaySettings> = mutableMapOf(),
    ) {
        val target = object : ExternalDesktopInputTarget {
            override fun dispatchExternalKeyEvent(event: KeyEvent): Boolean = false
            override fun dispatchExternalMotionEvent(view: View, event: MotionEvent): Boolean = false
        }.also(targets::add)

        var classifyCalls = 0
        var resolveInputCalls = 0
        var resolveRefreshCalls = 0
        val settingsLoads = mutableListOf<Pair<ProfileKind, Int>>()
        val appliedPlans = mutableListOf<PresentationPlan>()
        val appliedContents = mutableListOf<PixelSize?>()
        val appliedRenderSizes = mutableListOf<PixelSize>()
        val appliedDecisions = mutableListOf<InputPolicyDecision>()
        val appliedRefreshes = mutableListOf<RefreshRequest>()
        val appliedProfiles = mutableListOf<DisplayProfile>()
        val appliedDockHeights = mutableListOf<Int>()
        val relayEvents = mutableListOf<String>()

        private val countingPolicy = PresentationPolicy(
            refreshPolicy = { current, supported ->
                resolveRefreshCalls++
                DisplayRefreshPolicy.resolve(current, supported)
            },
            inputPolicy = { cls, env, caps, mode, route, desk, h, v ->
                resolveInputCalls++
                DesktopInputPolicy.resolve(cls, env, caps, mode, route, desk, h, v)
            },
        )

        val planner = DesktopPresentationPlanner(
            policy = countingPolicy,
            onClassified = { classifyCalls++ },
            readSnapshot = { windowSizeForDisplay ->
                val activeSize = windowSizeForDisplay(baseContext.displayId)
                val context = if (activeSize != null) {
                    baseContext.copyWithSnapshot(windowSizePx = activeSize)
                } else {
                    baseContext
                }
                DesktopPresentationSnapshot(
                    rawContext = context,
                    environment = environment,
                    runtimeCapabilities = runtimeCapabilities(backend),
                    hasInternalTouchscreen = hasInternalTouchscreen,
                    touchscreenDeviceIds = touchscreenDeviceIds,
                    horizontalScrollFactor = 48f,
                    verticalScrollFactor = 64f,
                )
            },
            loadSettings = { kind, displayId ->
                settingsLoads += kind to displayId
                savedByKind[kind]
            },
            applyPlan = { plan, contentSize ->
                appliedPlans += plan
                appliedContents += contentSize
                appliedRenderSizes += plan.renderSize
                appliedDecisions += plan.input
                appliedRefreshes += plan.refresh
                appliedProfiles += plan.toDisplayProfile()
                appliedDockHeights += plan.dockPx
                target
            },
            openRelay = { relayEvents += "open" },
            closeRelay = { relayEvents += "close" },
        )
    }

    private fun runtimeCapabilities(backend: RendererBackend): RuntimeCapabilities {
        val probed = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        return RuntimeCapabilities(
            hostApiLevel = 34,
            abi = "arm64-v8a",
            host = probed,
            localExecution = probed,
            proot = probed,
            x11 = probed,
            audio = probed,
            clipboard = probed,
            chroot = CapabilityEvidence(EvidenceState.UNAVAILABLE, "Not rooted"),
            avfFeature = CapabilityEvidence(EvidenceState.UNAVAILABLE, "Absent"),
            avfPermission = CapabilityEvidence(EvidenceState.UNAVAILABLE, "Absent"),
            renderer = probed,
            rendererBackend = backend,
            storage = probed,
            availableBytes = 10L * 1024 * 1024 * 1024,
            requiredBytes = 2L * 1024 * 1024 * 1024,
            distribution = DistributionContract(
                channel = DistributionChannel.SIDELOAD_LOCAL_LINUX,
                targetSdk = 28,
                requiresAppPrivateElfExecution = true,
            ),
        )
    }
}
