package com.orailnoor.droiddesk.display.input

import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.termux.x11.input.TouchInputHandler

data class InputPolicyDecision(
    val defaultInputMode: Int,
    val allowDirectTouch: Boolean,
    val externalInputRoute: ExternalInputRoute,
    val rendererPointerCapture: Boolean,
    val relayPointerCapture: Boolean,
    val horizontalScrollScale: Float,
    val verticalScrollScale: Float,
    val reason: String,
)

object DesktopInputPolicy {
    fun resolve(
        presentationClass: PresentationClass,
        environment: DisplayEnvironment,
        capabilities: InputCapabilities,
        savedInputMode: Int?,
        routePreference: ExternalInputRoutePreference,
        desktopUiMode: Boolean,
        horizontalScrollFactor: Float,
        verticalScrollFactor: Float,
    ): InputPolicyDecision {
        val allowDirectTouch = capabilities.touch == TouchEvidence.CURRENT_DISPLAY
        val defaultInputMode =
            if (presentationClass != PresentationClass.EXTERNAL_POINTER && allowDirectTouch) {
                TouchInputHandler.InputMode.TOUCH
            } else {
                TouchInputHandler.InputMode.TRACKPAD
            }
        val inputMode = when (savedInputMode) {
            TouchInputHandler.InputMode.TRACKPAD,
            TouchInputHandler.InputMode.SIMULATED_TOUCH,
            -> savedInputMode

            TouchInputHandler.InputMode.TOUCH -> {
                if (allowDirectTouch) savedInputMode else defaultInputMode
            }

            else -> defaultInputMode
        }
        val route = externalInputRoute(
            environment = environment,
            desktopUiMode = desktopUiMode,
            pointerAssociation = capabilities.pointerAssociation,
            preference = routePreference,
        )
        val hasPointer = capabilities.hasMouse || capabilities.hasTouchpad
        val rendererPointerCapture =
            environment != DisplayEnvironment.PHONE &&
                hasPointer &&
                route == ExternalInputRoute.NATIVE &&
                inputMode != TouchInputHandler.InputMode.TOUCH
        val relayPointerCapture = hasPointer && route == ExternalInputRoute.PHONE_RELAY
        val modeSource = when {
            savedInputMode == inputMode -> "saved"
            savedInputMode == TouchInputHandler.InputMode.TOUCH -> "fallback"
            else -> "default"
        }
        val captureOwner = when {
            rendererPointerCapture -> "renderer"
            relayPointerCapture -> "relay"
            else -> "none"
        }

        return InputPolicyDecision(
            defaultInputMode = inputMode,
            allowDirectTouch = allowDirectTouch,
            externalInputRoute = route,
            rendererPointerCapture = rendererPointerCapture,
            relayPointerCapture = relayPointerCapture,
            horizontalScrollScale = -horizontalScrollFactor,
            verticalScrollScale = -verticalScrollFactor,
            reason =
                "$modeSource mode $inputMode; directTouch=$allowDirectTouch; " +
                    "route=${route.name}; pointerCapture=$captureOwner",
        )
    }
}
