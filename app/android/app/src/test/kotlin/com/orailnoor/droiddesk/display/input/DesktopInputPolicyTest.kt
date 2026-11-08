package com.orailnoor.droiddesk.display.input

import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PointerAssociationEvidence
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.termux.x11.input.TouchInputHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopInputPolicyTest {
    @Test
    fun externalPointerDefaultsToTrackpadAndRequestsCapture() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            capabilities = pointerCapabilities(PointerAssociationEvidence.TARGET_DISPLAY),
        )

        assertEquals(TouchInputHandler.InputMode.TRACKPAD, decision.defaultInputMode)
        assertFalse(decision.allowDirectTouch)
        assertEquals(ExternalInputRoute.NATIVE, decision.externalInputRoute)
        assertTrue(decision.rendererPointerCapture)
        assertFalse(decision.relayPointerCapture)
    }

    @Test
    fun externalTouchWithMouseKeepsDirectTouchAndDoesNotForceCapture() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_TOUCH,
            capabilities = pointerCapabilities(
                association = PointerAssociationEvidence.TARGET_DISPLAY,
                touch = TouchEvidence.CURRENT_DISPLAY,
            ),
        )

        assertEquals(TouchInputHandler.InputMode.TOUCH, decision.defaultInputMode)
        assertTrue(decision.allowDirectTouch)
        assertEquals(ExternalInputRoute.NATIVE, decision.externalInputRoute)
        assertFalse(decision.rendererPointerCapture)
        assertFalse(decision.relayPointerCapture)
    }

    @Test
    fun unknownTouchAssociationDoesNotMarkExternalDisplayAsDirectTouch() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_TOUCH,
            capabilities = pointerCapabilities(
                association = PointerAssociationEvidence.TARGET_DISPLAY,
                touch = TouchEvidence.UNKNOWN_ASSOCIATION,
            ),
        )

        assertEquals(TouchInputHandler.InputMode.TRACKPAD, decision.defaultInputMode)
        assertFalse(decision.allowDirectTouch)
    }

    @Test
    fun unknownPointerAssociationUsesPhoneRelayOnGenericExternal() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            capabilities = pointerCapabilities(PointerAssociationEvidence.UNKNOWN),
        )

        assertEquals(ExternalInputRoute.PHONE_RELAY, decision.externalInputRoute)
        assertFalse(decision.rendererPointerCapture)
        assertTrue(decision.relayPointerCapture)
    }

    @Test
    fun targetAssociatedPointerUsesNativeRendererInput() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            capabilities = pointerCapabilities(PointerAssociationEvidence.TARGET_DISPLAY),
        )

        assertEquals(ExternalInputRoute.NATIVE, decision.externalInputRoute)
        assertTrue(decision.rendererPointerCapture)
        assertFalse(decision.relayPointerCapture)
    }

    @Test
    fun desktopUiModeKeepsNativeExternalInput() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            capabilities = pointerCapabilities(PointerAssociationEvidence.UNKNOWN),
            desktopUiMode = true,
        )

        assertEquals(ExternalInputRoute.NATIVE, decision.externalInputRoute)
        assertTrue(decision.rendererPointerCapture)
        assertFalse(decision.relayPointerCapture)
    }

    @Test
    fun explicitNativeAndPhoneRelayPreferencesOverrideAutomatic() {
        val nativeDecision = resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            capabilities = pointerCapabilities(PointerAssociationEvidence.UNKNOWN),
            routePreference = ExternalInputRoutePreference.NATIVE,
        )
        val relayDecision = resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            capabilities = pointerCapabilities(PointerAssociationEvidence.TARGET_DISPLAY),
            routePreference = ExternalInputRoutePreference.PHONE_RELAY,
            desktopUiMode = true,
        )

        assertEquals(ExternalInputRoute.NATIVE, nativeDecision.externalInputRoute)
        assertTrue(nativeDecision.rendererPointerCapture)
        assertFalse(nativeDecision.relayPointerCapture)
        assertEquals(ExternalInputRoute.PHONE_RELAY, relayDecision.externalInputRoute)
        assertFalse(relayDecision.rendererPointerCapture)
        assertTrue(relayDecision.relayPointerCapture)
    }

    @Test
    fun phoneAndTntAlwaysKeepNativeInput() {
        for (environment in listOf(DisplayEnvironment.PHONE, DisplayEnvironment.TNT)) {
            val decision = resolve(
                presentationClass = PresentationClass.EXTERNAL_POINTER,
                capabilities = pointerCapabilities(PointerAssociationEvidence.UNKNOWN),
                environment = environment,
                routePreference = ExternalInputRoutePreference.PHONE_RELAY,
            )

            assertEquals(ExternalInputRoute.NATIVE, decision.externalInputRoute)
        }
    }

    @Test
    fun rendererAndRelayPointerCaptureAreMutuallyExclusive() {
        val decisions = listOf(
            resolve(
                presentationClass = PresentationClass.EXTERNAL_POINTER,
                capabilities = pointerCapabilities(PointerAssociationEvidence.TARGET_DISPLAY),
            ),
            resolve(
                presentationClass = PresentationClass.EXTERNAL_POINTER,
                capabilities = pointerCapabilities(PointerAssociationEvidence.UNKNOWN),
            ),
            resolve(
                presentationClass = PresentationClass.EXTERNAL_POINTER,
                capabilities = pointerCapabilities(
                    association = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
                    hasMouse = false,
                ),
            ),
        )

        decisions.forEach { decision ->
            assertFalse(decision.rendererPointerCapture && decision.relayPointerCapture)
        }
    }

    @Test
    fun directTouchAndRelayedPointerRemainIndependent() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_TOUCH,
            capabilities = pointerCapabilities(
                association = PointerAssociationEvidence.UNKNOWN,
                touch = TouchEvidence.CURRENT_DISPLAY,
            ),
        )

        assertEquals(TouchInputHandler.InputMode.TOUCH, decision.defaultInputMode)
        assertTrue(decision.allowDirectTouch)
        assertEquals(ExternalInputRoute.PHONE_RELAY, decision.externalInputRoute)
        assertFalse(decision.rendererPointerCapture)
        assertTrue(decision.relayPointerCapture)
    }

    @Test
    fun phoneTouchNeverRequestsPointerCapture() {
        val directTouchDecision = resolve(
            presentationClass = PresentationClass.PHONE_TOUCH,
            capabilities = pointerCapabilities(
                association = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
                touch = TouchEvidence.CURRENT_DISPLAY,
            ),
            environment = DisplayEnvironment.PHONE,
        )

        assertEquals(TouchInputHandler.InputMode.TOUCH, directTouchDecision.defaultInputMode)
        assertTrue(directTouchDecision.allowDirectTouch)
        assertFalse(directTouchDecision.rendererPointerCapture)
        assertFalse(directTouchDecision.relayPointerCapture)

        for (
            savedInputMode in listOf(
                TouchInputHandler.InputMode.TRACKPAD,
                TouchInputHandler.InputMode.SIMULATED_TOUCH,
            )
        ) {
            val savedModeDecision = resolve(
                presentationClass = PresentationClass.PHONE_TOUCH,
                capabilities = pointerCapabilities(
                    association = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
                    touch = TouchEvidence.CURRENT_DISPLAY,
                ),
                environment = DisplayEnvironment.PHONE,
                savedInputMode = savedInputMode,
            )

            assertEquals(savedInputMode, savedModeDecision.defaultInputMode)
            assertFalse(savedModeDecision.rendererPointerCapture)
            assertFalse(savedModeDecision.relayPointerCapture)
        }
    }

    @Test
    fun savedSupportedModeWinsOverDefault() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_TOUCH,
            capabilities = pointerCapabilities(
                association = PointerAssociationEvidence.TARGET_DISPLAY,
                touch = TouchEvidence.CURRENT_DISPLAY,
            ),
            savedInputMode = TouchInputHandler.InputMode.SIMULATED_TOUCH,
        )

        assertEquals(TouchInputHandler.InputMode.SIMULATED_TOUCH, decision.defaultInputMode)
        assertTrue(decision.allowDirectTouch)
        assertTrue(decision.rendererPointerCapture)
    }

    @Test
    fun unsupportedDirectTouchFallsBackToTrackpad() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_TOUCH,
            capabilities = pointerCapabilities(
                association = PointerAssociationEvidence.TARGET_DISPLAY,
                touch = TouchEvidence.NONE,
            ),
            savedInputMode = TouchInputHandler.InputMode.TOUCH,
        )

        assertEquals(TouchInputHandler.InputMode.TRACKPAD, decision.defaultInputMode)
        assertFalse(decision.allowDirectTouch)
        assertTrue(decision.rendererPointerCapture)
    }

    @Test
    fun scrollScalesUseAndroidHorizontalAndVerticalFactors() {
        val decision = resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            capabilities = pointerCapabilities(PointerAssociationEvidence.TARGET_DISPLAY),
            horizontalScrollFactor = 32.5f,
            verticalScrollFactor = 64f,
        )

        assertEquals(-32.5f, decision.horizontalScrollScale, 0f)
        assertEquals(-64f, decision.verticalScrollScale, 0f)
        assertTrue(decision.reason.isNotBlank())
    }

    private fun resolve(
        presentationClass: PresentationClass,
        capabilities: InputCapabilities,
        environment: DisplayEnvironment = DisplayEnvironment.OTHER_EXTERNAL,
        savedInputMode: Int? = null,
        routePreference: ExternalInputRoutePreference = ExternalInputRoutePreference.AUTOMATIC,
        desktopUiMode: Boolean = false,
        horizontalScrollFactor: Float = 48f,
        verticalScrollFactor: Float = 72f,
    ) = DesktopInputPolicy.resolve(
        presentationClass = presentationClass,
        environment = environment,
        capabilities = capabilities,
        savedInputMode = savedInputMode,
        routePreference = routePreference,
        desktopUiMode = desktopUiMode,
        horizontalScrollFactor = horizontalScrollFactor,
        verticalScrollFactor = verticalScrollFactor,
    )

    private fun pointerCapabilities(
        association: PointerAssociationEvidence,
        touch: TouchEvidence = TouchEvidence.NONE,
        hasMouse: Boolean = true,
        hasTouchpad: Boolean = false,
    ) = InputCapabilities(
        touch = touch,
        pointerAssociation = association,
        hasMouse = hasMouse,
        hasTouchpad = hasTouchpad,
        hasKeyboard = true,
        hasStylus = false,
    )
}
