package com.orailnoor.droiddesk.runtime

import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplaySettings
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RenderPolicy
import com.orailnoor.droiddesk.display.policy.PresentationPolicy
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import com.termux.x11.input.TouchInputHandler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XfceDisplayProfileTest {
    private val policy = PresentationPolicy()

    @Test
    fun stageAndApplyScriptsUseAutoMaximizeInsteadOfHardcodingPhoneKind() {
        val runtime = CapabilityEvidenceFixtures.softwareRuntime()
        val phonePlan = policy.resolve(
            context = PresentationFixtures.r2Phone,
            presentationClass = policy.classify(PresentationFixtures.r2Phone),
            environment = DisplayEnvironment.PHONE,
            settings = null,
            runtime = runtime,
            horizontalScrollFactor = 64f,
            verticalScrollFactor = 64f,
        )
        val phoneStage = XfceDisplayProfile.buildStageScript(phonePlan.toDisplayProfile())
        val phoneApply = XfceDisplayProfile.buildApplyScript(phonePlan.toDisplayProfile())
        assertTrue(phoneStage.contains("printf '%s\\n' 'phone'"))
        assertTrue(phoneApply.contains("elif [ 'phone' = 'phone' ]; then"))
        assertTrue(phoneApply.contains("/panels/panel-2/icon-size uint 60"))

        val forcedAutoMaximizeMonitor = DisplayProfile(
            kind = ProfileKind.MONITOR,
            settings = DisplaySettings(
                renderPolicy = RenderPolicy.NATIVE,
                uiScalePercent = 150,
                inputMode = TouchInputHandler.InputMode.TRACKPAD,
            ),
            dpi = 144,
            cursorPx = 36,
            dockPx = 72,
            autoMaximize = true,
        )
        val forcedStage = XfceDisplayProfile.buildStageScript(forcedAutoMaximizeMonitor)
        val forcedApply = XfceDisplayProfile.buildApplyScript(forcedAutoMaximizeMonitor)
        assertTrue(forcedStage.contains("printf '%s\\n' 'phone'"))
        assertTrue(forcedApply.contains("elif [ 'phone' = 'phone' ]; then"))
        assertTrue(forcedApply.contains("/panels/panel-2/icon-size uint 48"))

        val windowedPhone = phonePlan.toDisplayProfile().copy(autoMaximize = false)
        val windowedStage = XfceDisplayProfile.buildStageScript(windowedPhone)
        val windowedApply = XfceDisplayProfile.buildApplyScript(windowedPhone)
        assertFalse(windowedStage.contains("printf '%s\\n' 'phone'"))
        assertFalse(windowedApply.contains("elif [ 'phone' = 'phone' ]; then"))
        assertTrue(windowedApply.contains("elif [ 'windowed' = 'phone' ]; then"))
    }

    @Test
    fun sameUiDetectsPhoneDefaultVersusExplicitScaleIconSizeAndAutoMaximizeChanges() {
        val automaticPhone = DisplayProfile(
            kind = ProfileKind.PHONE,
            settings = DisplaySettings(
                renderPolicy = RenderPolicy.LEGACY_PHONE,
                uiScalePercent = null,
                inputMode = TouchInputHandler.InputMode.TOUCH,
            ),
            dpi = 144,
            cursorPx = 36,
            dockPx = 88,
            autoMaximize = true,
        )
        val explicitPhoneSameDock = automaticPhone.copy(
            settings = automaticPhone.settings.copy(uiScalePercent = 150),
        )
        val nonUiChangedPhone = automaticPhone.copy(
            settings = automaticPhone.settings.copy(
                renderPolicy = RenderPolicy.NATIVE,
                inputMode = TouchInputHandler.InputMode.TRACKPAD,
            ),
        )

        assertFalse(XfceDisplayProfile.sameUi(automaticPhone, explicitPhoneSameDock))
        assertFalse(XfceDisplayProfile.sameUi(automaticPhone, automaticPhone.copy(autoMaximize = false)))
        assertTrue(XfceDisplayProfile.sameUi(automaticPhone, nonUiChangedPhone))
        assertTrue(XfceDisplayProfile.buildApplyScript(automaticPhone).contains("/panels/panel-2/icon-size uint 60"))
        assertTrue(XfceDisplayProfile.buildApplyScript(explicitPhoneSameDock).contains("/panels/panel-2/icon-size uint 59"))
    }

    private object CapabilityEvidenceFixtures {
        fun softwareRuntime() = RuntimeCapabilities(
            hostApiLevel = 34,
            abi = "arm64-v8a",
            host = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            localExecution = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            proot = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            x11 = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            audio = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            clipboard = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            chroot = CapabilityEvidence(EvidenceState.UNAVAILABLE, "Not rooted"),
            avfFeature = CapabilityEvidence(EvidenceState.UNAVAILABLE, "Absent"),
            avfPermission = CapabilityEvidence(EvidenceState.UNAVAILABLE, "Absent"),
            renderer = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
            rendererBackend = RendererBackend.SOFTWARE_LLVMPIPE,
            storage = CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED),
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
