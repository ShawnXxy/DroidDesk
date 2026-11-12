package com.orailnoor.droiddesk.display.policy

import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplayProfilePolicy
import com.orailnoor.droiddesk.display.DisplaySettings
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RenderPolicy
import com.orailnoor.droiddesk.display.capability.DisplayModeEvidence
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.PresentationContext
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.orailnoor.droiddesk.display.exceedsCap1440
import com.orailnoor.droiddesk.display.fallbackUiScalePercent
import com.orailnoor.droiddesk.display.input.DesktopInputPolicy
import com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference
import com.orailnoor.droiddesk.display.input.InputPolicyDecision
import com.orailnoor.droiddesk.display.refresh.DisplayRefreshPolicy
import com.orailnoor.droiddesk.display.refresh.RefreshRequest
import com.orailnoor.droiddesk.display.scaledDisplayProfile
import com.orailnoor.droiddesk.runtime.RuntimeCapabilities
import com.termux.x11.X11RenderGeometry
import kotlin.math.abs

data class PresentationPlan(
    val profileKind: ProfileKind,
    val presentationClass: PresentationClass,
    val settings: DisplaySettings,
    val renderSize: PixelSize,
    val dpi: Int,
    val cursorPx: Int,
    val dockPx: Int,
    val autoMaximize: Boolean,
    val input: InputPolicyDecision,
    val refresh: RefreshRequest,
) {
    fun toDisplayProfile(): DisplayProfile = DisplayProfile(
        kind = profileKind,
        settings = settings,
        dpi = dpi,
        cursorPx = cursorPx,
        dockPx = dockPx,
        autoMaximize = autoMaximize,
    )
}

class PresentationPolicy @JvmOverloads constructor(
    private val refreshPolicy: (
        DisplayModeEvidence?,
        List<DisplayModeEvidence>,
    ) -> RefreshRequest = DisplayRefreshPolicy::resolve,
    private val inputPolicy: (
        PresentationClass,
        DisplayEnvironment,
        InputCapabilities,
        Int?,
        ExternalInputRoutePreference,
        Boolean,
        Float,
        Float,
    ) -> InputPolicyDecision = DesktopInputPolicy::resolve,
) {
    fun classify(context: PresentationContext): PresentationClass {
        if (!context.isDefaultDisplay) {
            return if (context.input.touch == TouchEvidence.CURRENT_DISPLAY) {
                PresentationClass.EXTERNAL_TOUCH
            } else {
                PresentationClass.EXTERNAL_POINTER
            }
        }
        if (context.densityDpi <= 0) {
            return if (context.windowSizePx.width < UNKNOWN_DENSITY_PHONE_MAX_WIDTH_PX) {
                PresentationClass.PHONE_TOUCH
            } else {
                PresentationClass.LARGE_TOUCH
            }
        }
        val widthDp = context.windowSizePx.width * STANDARD_DENSITY_DPI.toDouble() / context.densityDpi
        return if (widthDp < LARGE_TOUCH_MIN_WIDTH_DP) {
            PresentationClass.PHONE_TOUCH
        } else {
            PresentationClass.LARGE_TOUCH
        }
    }

    fun profileKind(presentationClass: PresentationClass): ProfileKind = when (presentationClass) {
        PresentationClass.PHONE_TOUCH -> ProfileKind.PHONE
        PresentationClass.LARGE_TOUCH -> ProfileKind.WIDE
        PresentationClass.EXTERNAL_POINTER, PresentationClass.EXTERNAL_TOUCH -> ProfileKind.MONITOR
    }

    fun resolve(
        context: PresentationContext,
        presentationClass: PresentationClass,
        environment: DisplayEnvironment,
        settings: DisplaySettings?,
        runtime: RuntimeCapabilities,
        horizontalScrollFactor: Float,
        verticalScrollFactor: Float,
    ): PresentationPlan {
        val content = context.windowSizePx
        val kind = profileKind(presentationClass)
        val routePreference = settings?.inputRoute ?: ExternalInputRoutePreference.AUTOMATIC
        val inputDecision = inputPolicy(
            presentationClass,
            environment,
            context.input,
            settings?.inputMode,
            routePreference,
            context.isDesktopUiMode,
            horizontalScrollFactor,
            verticalScrollFactor,
        )
        val renderPolicy = settings?.renderPolicy
            ?: defaultRenderPolicy(presentationClass, content, runtime)
        val resolvedSettings = DisplaySettings(
            renderPolicy = renderPolicy,
            uiScalePercent = settings?.uiScalePercent,
            inputMode = inputDecision.defaultInputMode,
            inputRoute = routePreference,
        )
        val scaled = checkNotNull(DisplayProfilePolicy.renderSize(content, renderPolicy)) {
            "Presentation window bounds must be positive"
        }
        val renderSize = PixelSize(
            X11RenderGeometry.alignedWidth(scaled.width, scaled.height),
            scaled.height,
        )
        val profile = resolveProfile(kind, presentationClass, context, resolvedSettings)
        return PresentationPlan(
            profileKind = kind,
            presentationClass = presentationClass,
            settings = resolvedSettings,
            renderSize = renderSize,
            dpi = profile.dpi,
            cursorPx = profile.cursorPx,
            dockPx = profile.dockPx,
            autoMaximize = profile.autoMaximize,
            input = inputDecision,
            refresh = refreshPolicy(context.currentMode, context.supportedModes),
        )
    }

    private fun defaultRenderPolicy(
        presentationClass: PresentationClass,
        content: PixelSize,
        runtime: RuntimeCapabilities,
    ): RenderPolicy = when (presentationClass) {
        PresentationClass.PHONE_TOUCH -> RenderPolicy.LEGACY_PHONE
        PresentationClass.LARGE_TOUCH -> RenderPolicy.NATIVE
        PresentationClass.EXTERNAL_POINTER, PresentationClass.EXTERNAL_TOUCH ->
            if (!runtime.hardwareAccelerationVerified && exceedsCap1440(content)) {
                RenderPolicy.CAP_1440
            } else {
                RenderPolicy.NATIVE
            }
    }

    private fun resolveProfile(
        kind: ProfileKind,
        presentationClass: PresentationClass,
        context: PresentationContext,
        settings: DisplaySettings,
    ): DisplayProfile {
        if (presentationClass == PresentationClass.PHONE_TOUCH && settings.uiScalePercent == null) {
            return DisplayProfilePolicy.phoneDefaultProfile().copy(settings = settings)
        }
        val percent = settings.uiScalePercent ?: automaticUiScalePercent(kind, context)
        return scaledDisplayProfile(kind, settings, percent)
    }

    private fun automaticUiScalePercent(kind: ProfileKind, context: PresentationContext): Int {
        if (context.densityDpi <= 0) {
            return fallbackUiScalePercent(kind, context.windowSizePx)
        }
        val fontScale = context.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val clamped = (context.densityDpi / STANDARD_DENSITY_DPI * fontScale * 100f)
            .coerceIn(MIN_UI_SCALE_PERCENT, MAX_UI_SCALE_PERCENT)
        return DisplaySettings.UI_SCALES.minWith(
            compareBy<Int> { abs(it - clamped) }.thenByDescending { it },
        )
    }

    companion object {
        private const val STANDARD_DENSITY_DPI = 160f
        private const val LARGE_TOUCH_MIN_WIDTH_DP = 600.0
        private const val UNKNOWN_DENSITY_PHONE_MAX_WIDTH_PX = 1200
        private const val MIN_UI_SCALE_PERCENT = 100f
        private const val MAX_UI_SCALE_PERCENT = 200f
    }
}
