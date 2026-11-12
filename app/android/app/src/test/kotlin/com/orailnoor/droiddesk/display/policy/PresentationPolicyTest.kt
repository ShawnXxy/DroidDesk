package com.orailnoor.droiddesk.display.policy

import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplaySettings
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RenderPolicy
import com.orailnoor.droiddesk.display.capability.DisplayModeEvidence
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.orailnoor.droiddesk.display.input.DesktopInputPolicy
import com.orailnoor.droiddesk.display.input.ExternalInputRoute
import com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import com.orailnoor.droiddesk.runtime.CapabilityEvidence
import com.orailnoor.droiddesk.runtime.DistributionChannel
import com.orailnoor.droiddesk.runtime.DistributionContract
import com.orailnoor.droiddesk.runtime.EvidenceState
import com.orailnoor.droiddesk.runtime.RendererBackend
import com.orailnoor.droiddesk.runtime.RuntimeCapabilities
import com.termux.x11.input.TouchInputHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationPolicyTest {
    private val policy = PresentationPolicy()

    @Test
    fun r2PhonePreservesLegacyPhoneProfile() {
        val context = PresentationFixtures.r2Phone
        val presentationClass = policy.classify(context)

        val plan = policy.resolve(
            context = context,
            presentationClass = presentationClass,
            environment = DisplayEnvironment.PHONE,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 64f,
            verticalScrollFactor = 48f,
        )

        assertEquals(PresentationClass.PHONE_TOUCH, presentationClass)
        assertEquals(ProfileKind.PHONE, plan.profileKind)
        assertEquals(PresentationClass.PHONE_TOUCH, plan.presentationClass)
        assertEquals(
            DisplaySettings(
                renderPolicy = RenderPolicy.LEGACY_PHONE,
                uiScalePercent = null,
                inputMode = TouchInputHandler.InputMode.TOUCH,
                inputRoute = ExternalInputRoutePreference.AUTOMATIC,
            ),
            plan.settings,
        )
        assertEquals(PixelSize(720, 1560), plan.renderSize)
        assertEquals(140, plan.dpi)
        assertEquals(32, plan.cursorPx)
        assertEquals(88, plan.dockPx)
        assertTrue(plan.autoMaximize)
        assertTrue(plan.input.allowDirectTouch)
        assertEquals(ExternalInputRoute.NATIVE, plan.input.externalInputRoute)
        assertEquals(-64f, plan.input.horizontalScrollScale)
        assertEquals(-48f, plan.input.verticalScrollScale)
        assertEquals(60f, plan.refresh.preferredRefreshRate)
        assertEquals(1, plan.refresh.preferredModeId)
        assertEquals(
            DisplayProfile(
                kind = ProfileKind.PHONE,
                settings = plan.settings,
                dpi = 140,
                cursorPx = 32,
                dockPx = 88,
                autoMaximize = true,
            ),
            plan.toDisplayProfile(),
        )
    }

    @Test
    fun nubiaPhoneUsesCurrentWindowInsteadOfDeviceSmallestWidth() {
        val fullPhone = PresentationFixtures.nx888jPhone
        val insetPhone = fullPhone.copyWithSnapshot(
            windowSizePx = PixelSize(1216, 2688),
            densityDpi = 560,
        )

        assertEquals(PresentationClass.PHONE_TOUCH, policy.classify(fullPhone))
        assertEquals(PresentationClass.PHONE_TOUCH, policy.classify(insetPhone))

        val plan = policy.resolve(
            context = insetPhone,
            presentationClass = PresentationClass.PHONE_TOUCH,
            environment = DisplayEnvironment.PHONE,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        assertEquals(ProfileKind.PHONE, plan.profileKind)
        assertEquals(RenderPolicy.LEGACY_PHONE, plan.settings.renderPolicy)
        assertEquals(PixelSize(808, 1792), plan.renderSize)
        assertEquals(140, plan.dpi)
        assertTrue(plan.autoMaximize)
    }

    @Test
    fun foldableNarrowWindowClassifiesAsPhoneTouch() {
        val context = PresentationFixtures.foldableNarrowWindow
        val presentationClass = policy.classify(context)

        val plan = policy.resolve(
            context = context,
            presentationClass = presentationClass,
            environment = DisplayEnvironment.PHONE,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        assertEquals(PresentationClass.PHONE_TOUCH, presentationClass)
        assertEquals(ProfileKind.PHONE, plan.profileKind)
        assertEquals(RenderPolicy.LEGACY_PHONE, plan.settings.renderPolicy)
        assertEquals(PixelSize(560, 1466), plan.renderSize)
        assertEquals(140, plan.dpi)
        assertTrue(plan.autoMaximize)
    }

    @Test
    fun expandedTabletWindowClassifiesAsLargeTouch() {
        val unfolded = PresentationFixtures.foldableNarrowWindow.copyWithSnapshot(
            windowSizePx = PixelSize(1768, 2208),
            densityDpi = 420,
            isDesktopUiMode = true,
        )
        val exactBoundary = unfolded.copyWithSnapshot(
            windowSizePx = PixelSize(1200, 1920),
            densityDpi = 320,
        )
        val belowBoundary = unfolded.copyWithSnapshot(
            windowSizePx = PixelSize(1199, 1920),
            densityDpi = 320,
        )
        val unknownDensityPhone = unfolded.copyWithSnapshot(
            windowSizePx = PixelSize(1199, 1920),
            densityDpi = 0,
        )
        val unknownDensityTablet = unfolded.copyWithSnapshot(
            windowSizePx = PixelSize(1200, 1920),
            densityDpi = 0,
        )

        assertEquals(PresentationClass.LARGE_TOUCH, policy.classify(unfolded))
        assertEquals(PresentationClass.LARGE_TOUCH, policy.classify(exactBoundary))
        assertEquals(PresentationClass.PHONE_TOUCH, policy.classify(belowBoundary))
        assertEquals(PresentationClass.PHONE_TOUCH, policy.classify(unknownDensityPhone))
        assertEquals(PresentationClass.LARGE_TOUCH, policy.classify(unknownDensityTablet))

        val plan = policy.resolve(
            context = unfolded,
            presentationClass = PresentationClass.LARGE_TOUCH,
            environment = DisplayEnvironment.PHONE,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        assertEquals(ProfileKind.WIDE, plan.profileKind)
        assertEquals(RenderPolicy.NATIVE, plan.settings.renderPolicy)
        assertEquals(PixelSize(1768, 2208), plan.renderSize)
        assertEquals(192, plan.dpi)
        assertEquals(48, plan.cursorPx)
        assertEquals(96, plan.dockPx)
        assertFalse(plan.autoMaximize)
        assertEquals(TouchInputHandler.InputMode.TOUCH, plan.input.defaultInputMode)
    }

    @Test
    fun externalTouchAndPointerDisplaysUseDifferentPresentationClasses() {
        val tnt = PresentationFixtures.r2Tnt
        val hdmi = PresentationFixtures.nx888jHdmi
        val touchMonitor = PresentationFixtures.externalTouch
        val unknownTouchMonitor = touchMonitor.copyWithSnapshot(
            input = touchMonitor.input.copy(touch = TouchEvidence.UNKNOWN_ASSOCIATION),
        )

        assertEquals(PresentationClass.EXTERNAL_POINTER, policy.classify(tnt))
        assertEquals(PresentationClass.EXTERNAL_POINTER, policy.classify(hdmi))
        assertEquals(PresentationClass.EXTERNAL_TOUCH, policy.classify(touchMonitor))
        assertEquals(PresentationClass.EXTERNAL_POINTER, policy.classify(unknownTouchMonitor))

        val tntPlan = policy.resolve(
            context = tnt,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.TNT,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val hdmiPlan = policy.resolve(
            context = hdmi,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val touchPlan = policy.resolve(
            context = touchMonitor,
            presentationClass = PresentationClass.EXTERNAL_TOUCH,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        assertEquals(ProfileKind.MONITOR, tntPlan.profileKind)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, tntPlan.input.defaultInputMode)
        assertEquals(ExternalInputRoute.NATIVE, tntPlan.input.externalInputRoute)
        assertTrue(tntPlan.input.rendererPointerCapture)

        assertEquals(ProfileKind.MONITOR, hdmiPlan.profileKind)
        assertEquals(ExternalInputRoute.PHONE_RELAY, hdmiPlan.input.externalInputRoute)
        assertTrue(hdmiPlan.input.relayPointerCapture)

        assertEquals(ProfileKind.MONITOR, touchPlan.profileKind)
        assertEquals(TouchInputHandler.InputMode.TOUCH, touchPlan.input.defaultInputMode)
        assertTrue(touchPlan.input.allowDirectTouch)
        assertFalse(touchPlan.input.rendererPointerCapture)

        val invalidTouchSavedOnPointerPlan = policy.resolve(
            context = hdmi,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = DisplaySettings(
                renderPolicy = RenderPolicy.NATIVE,
                uiScalePercent = null,
                inputMode = TouchInputHandler.InputMode.TOUCH,
            ),
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, invalidTouchSavedOnPointerPlan.input.defaultInputMode)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, invalidTouchSavedOnPointerPlan.settings.inputMode)
    }

    @Test
    fun softwareRenderedFourKDefaultsToCap1440WithoutOverridingSavedNative() {
        val fourK = PresentationFixtures.fourK120
        val portraitFourK = fourK.copyWithSnapshot(
            windowSizePx = PixelSize(2160, 3840),
            currentMode = DisplayModeEvidence(121, 2160, 3840, 60f),
            supportedModes = listOf(DisplayModeEvidence(121, 2160, 3840, 60f)),
        )
        val ultrawide = fourK.copyWithSnapshot(
            windowSizePx = PixelSize(3440, 1440),
            currentMode = DisplayModeEvidence(122, 3440, 1440, 60f),
            supportedModes = listOf(DisplayModeEvidence(122, 3440, 1440, 60f)),
        )
        val sixteenByTen = fourK.copyWithSnapshot(
            windowSizePx = PixelSize(2560, 1600),
            currentMode = DisplayModeEvidence(123, 2560, 1600, 60f),
            supportedModes = listOf(DisplayModeEvidence(123, 2560, 1600, 60f)),
        )
        val softwareRuntime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE)
        val hardwareRuntime = runtimeCapabilities(RendererBackend.NATIVE_TURNIP_KGSL)

        val defaultSoftwarePlan = policy.resolve(
            context = fourK,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = softwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val externalTouchSoftwarePlan = policy.resolve(
            context = fourK.copyWithSnapshot(input = PresentationFixtures.externalTouch.input),
            presentationClass = PresentationClass.EXTERNAL_TOUCH,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = softwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val largeTouchSoftwarePlan = policy.resolve(
            context = fourK.copyWithSnapshot(
                isDefaultDisplay = true,
                input = PresentationFixtures.r2Phone.input,
            ),
            presentationClass = PresentationClass.LARGE_TOUCH,
            environment = DisplayEnvironment.PHONE,
            settings = null,
            runtime = softwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val portraitSoftwarePlan = policy.resolve(
            context = portraitFourK,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = softwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val ultrawideSoftwarePlan = policy.resolve(
            context = ultrawide,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = softwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val sixteenByTenSoftwarePlan = policy.resolve(
            context = sixteenByTen,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = softwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val savedNativePlan = policy.resolve(
            context = fourK,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = DisplaySettings(
                renderPolicy = RenderPolicy.NATIVE,
                uiScalePercent = null,
                inputMode = TouchInputHandler.InputMode.TRACKPAD,
                inputRoute = ExternalInputRoutePreference.PHONE_RELAY,
            ),
            runtime = softwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val hardwarePlan = policy.resolve(
            context = fourK,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = hardwareRuntime,
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        assertEquals(RenderPolicy.CAP_1440, defaultSoftwarePlan.settings.renderPolicy)
        assertEquals(PixelSize(2560, 1440), defaultSoftwarePlan.renderSize)
        assertEquals(60f, defaultSoftwarePlan.refresh.preferredRefreshRate)
        assertNull(defaultSoftwarePlan.refresh.preferredModeId)

        assertEquals(RenderPolicy.CAP_1440, externalTouchSoftwarePlan.settings.renderPolicy)
        assertEquals(PixelSize(2560, 1440), externalTouchSoftwarePlan.renderSize)

        assertEquals(RenderPolicy.NATIVE, largeTouchSoftwarePlan.settings.renderPolicy)
        assertEquals(PixelSize(3840, 2160), largeTouchSoftwarePlan.renderSize)

        assertEquals(RenderPolicy.CAP_1440, portraitSoftwarePlan.settings.renderPolicy)
        assertEquals(PixelSize(1440, 2560), portraitSoftwarePlan.renderSize)

        assertEquals(RenderPolicy.CAP_1440, ultrawideSoftwarePlan.settings.renderPolicy)
        assertEquals(RenderPolicy.CAP_1440, sixteenByTenSoftwarePlan.settings.renderPolicy)

        assertEquals(RenderPolicy.NATIVE, savedNativePlan.settings.renderPolicy)
        assertEquals(ExternalInputRoutePreference.PHONE_RELAY, savedNativePlan.settings.inputRoute)
        assertEquals(PixelSize(3840, 2160), savedNativePlan.renderSize)

        assertEquals(RenderPolicy.NATIVE, hardwarePlan.settings.renderPolicy)
        assertEquals(PixelSize(3840, 2160), hardwarePlan.renderSize)
    }

    @Test
    fun densityAndFontScaleChooseNearestSupportedScale() {
        fun resolveExternal(
            densityDpi: Int,
            fontScale: Float = 1f,
            windowSize: PixelSize = PixelSize(1920, 1080),
            settings: DisplaySettings? = null,
            presentationClass: PresentationClass = PresentationClass.EXTERNAL_POINTER,
        ) = policy.resolve(
            context = PresentationFixtures.externalTouch.copyWithSnapshot(
                windowSizePx = windowSize,
                densityDpi = densityDpi,
                fontScale = fontScale,
            ),
            presentationClass = presentationClass,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = settings,
            runtime = runtimeCapabilities(RendererBackend.NATIVE_TURNIP_KGSL),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        val mdpiDefault = resolveExternal(densityDpi = 160, fontScale = 1.0f)
        val largeFont = resolveExternal(densityDpi = 160, fontScale = 1.3f)
        val midpoint = resolveExternal(densityDpi = 220, fontScale = 1.0f)
        val hdpiBoosted = resolveExternal(densityDpi = 240, fontScale = 1.15f)
        val clampedHigh = resolveExternal(densityDpi = 320, fontScale = 1.3f)
        val clampedLow = resolveExternal(densityDpi = 120, fontScale = 0.85f)
        val fallback1080 = resolveExternal(densityDpi = 0, windowSize = PixelSize(1920, 1080))
        val fallback1440 = resolveExternal(densityDpi = 0, windowSize = PixelSize(2560, 1440))
        val fallback4k = resolveExternal(densityDpi = 0, windowSize = PixelSize(3840, 2160))
        val fallbackWide = resolveExternal(
            densityDpi = 0,
            windowSize = PixelSize(1280, 800),
            presentationClass = PresentationClass.LARGE_TOUCH,
        )
        val explicitPhoneScale = policy.resolve(
            context = PresentationFixtures.r2Phone,
            presentationClass = PresentationClass.PHONE_TOUCH,
            environment = DisplayEnvironment.PHONE,
            settings = DisplaySettings(
                renderPolicy = RenderPolicy.LEGACY_PHONE,
                uiScalePercent = 150,
                inputMode = TouchInputHandler.InputMode.TOUCH,
            ),
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        assertNull(mdpiDefault.settings.uiScalePercent)
        assertEquals(96, mdpiDefault.dpi)
        assertEquals(24, mdpiDefault.cursorPx)
        assertEquals(48, mdpiDefault.dockPx)

        assertEquals(120, largeFont.dpi)
        assertEquals(30, largeFont.cursorPx)
        assertEquals(60, largeFont.dockPx)

        assertEquals(144, midpoint.dpi)
        assertEquals(36, midpoint.cursorPx)
        assertEquals(72, midpoint.dockPx)

        assertEquals(168, hdpiBoosted.dpi)
        assertEquals(42, hdpiBoosted.cursorPx)
        assertEquals(84, hdpiBoosted.dockPx)

        assertEquals(192, clampedHigh.dpi)
        assertEquals(48, clampedHigh.cursorPx)
        assertEquals(96, clampedHigh.dockPx)

        assertEquals(96, clampedLow.dpi)
        assertEquals(24, clampedLow.cursorPx)
        assertEquals(48, clampedLow.dockPx)

        assertEquals(96, fallback1080.dpi)
        assertEquals(144, fallback1440.dpi)
        assertEquals(192, fallback4k.dpi)
        assertEquals(144, fallbackWide.dpi)

        assertEquals(150, explicitPhoneScale.settings.uiScalePercent)
        assertEquals(144, explicitPhoneScale.dpi)
        assertEquals(36, explicitPhoneScale.cursorPx)
        assertEquals(72, explicitPhoneScale.dockPx)
        assertTrue(explicitPhoneScale.autoMaximize)
    }

    @Test
    fun renderAndInputGeometryUseTheSameContentSize() {
        val observedInputClasses = mutableListOf<PresentationClass>()
        val trackingPolicy = PresentationPolicy(
            inputPolicy = { presentationClass, environment, capabilities, savedInputMode, routePreference, desktopUiMode, hScroll, vScroll ->
                observedInputClasses += presentationClass
                DesktopInputPolicy.resolve(
                    presentationClass = presentationClass,
                    environment = environment,
                    capabilities = capabilities,
                    savedInputMode = savedInputMode,
                    routePreference = routePreference,
                    desktopUiMode = desktopUiMode,
                    horizontalScrollFactor = hScroll,
                    verticalScrollFactor = vScroll,
                )
            },
        )
        val insetExternal = PresentationFixtures.fourK120.copyWithSnapshot(
            windowSizePx = PixelSize(1915, 1032),
        )
        val splitTabletWindow = PresentationFixtures.r2Phone.copyWithSnapshot(
            windowSizePx = PixelSize(1180, 1000),
            densityDpi = 320,
            currentMode = DisplayModeEvidence(40, 2560, 1600, 120f),
            supportedModes = listOf(DisplayModeEvidence(40, 2560, 1600, 120f)),
        )
        val compat1366 = PresentationFixtures.nx888jHdmi.copyWithSnapshot(
            windowSizePx = PixelSize(1366, 768),
            currentMode = DisplayModeEvidence(30, 1366, 768, 60f),
            supportedModes = listOf(DisplayModeEvidence(30, 1366, 768, 60f)),
        )
        val nonCompat1366 = compat1366.copyWithSnapshot(
            windowSizePx = PixelSize(1366, 900),
            currentMode = DisplayModeEvidence(31, 1366, 900, 60f),
            supportedModes = listOf(DisplayModeEvidence(31, 1366, 900, 60f)),
        )

        val externalClass = trackingPolicy.classify(insetExternal)
        val plan = trackingPolicy.resolve(
            context = insetExternal,
            presentationClass = externalClass,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 64f,
            verticalScrollFactor = 48f,
        )
        val splitClass = trackingPolicy.classify(splitTabletWindow)
        val splitPlan = trackingPolicy.resolve(
            context = splitTabletWindow,
            presentationClass = splitClass,
            environment = DisplayEnvironment.PHONE,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 32f,
            verticalScrollFactor = 24f,
        )
        val compatPlan = policy.resolve(
            context = compat1366,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )
        val nonCompatPlan = policy.resolve(
            context = nonCompat1366,
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            settings = null,
            runtime = runtimeCapabilities(RendererBackend.SOFTWARE_LLVMPIPE),
            horizontalScrollFactor = 50f,
            verticalScrollFactor = 50f,
        )

        assertEquals(PresentationClass.EXTERNAL_POINTER, externalClass)
        assertEquals(PresentationClass.PHONE_TOUCH, splitClass)
        assertEquals(
            listOf(PresentationClass.EXTERNAL_POINTER, PresentationClass.PHONE_TOUCH),
            observedInputClasses,
        )
        assertEquals(RenderPolicy.NATIVE, plan.settings.renderPolicy)
        assertEquals(PixelSize(1912, 1032), plan.renderSize)
        assertEquals(-64f, plan.input.horizontalScrollScale)
        assertEquals(-48f, plan.input.verticalScrollScale)
        assertEquals(60f, plan.refresh.preferredRefreshRate)
        assertNull(plan.refresh.preferredModeId)

        assertEquals(RenderPolicy.LEGACY_PHONE, splitPlan.settings.renderPolicy)
        assertEquals(PixelSize(784, 666), splitPlan.renderSize)
        assertEquals(-32f, splitPlan.input.horizontalScrollScale)
        assertEquals(-24f, splitPlan.input.verticalScrollScale)

        assertEquals(PixelSize(1366, 768), compatPlan.renderSize)
        assertEquals(PixelSize(1360, 900), nonCompatPlan.renderSize)
    }

    private fun runtimeCapabilities(backend: RendererBackend): RuntimeCapabilities {
        val probed = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        val rendererState = if (backend == RendererBackend.UNKNOWN) {
            CapabilityEvidence(EvidenceState.PRESENT_UNVERIFIED, "Unverified")
        } else {
            probed
        }
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
            renderer = rendererState,
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
