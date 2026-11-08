package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.view.forwardRelayMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalInputRelayPolicyTest {
    @Test
    fun genericExternalWithoutAssociationEvidenceUsesThePhoneRelay() {
        assertTrue(
            externalInputRoute(
                environment = DisplayEnvironment.OTHER_EXTERNAL,
                targetDisplayId = 5,
                desktopUiMode = false,
                pointerDisplayIds = null,
            ) == ExternalInputRoute.PHONE_RELAY,
        )
    }

    @Test
    fun desktopModeExternalKeepsNativeInput() {
        assertTrue(
            externalInputRoute(
                environment = DisplayEnvironment.OTHER_EXTERNAL,
                targetDisplayId = 5,
                desktopUiMode = true,
                pointerDisplayIds = null,
            ) == ExternalInputRoute.NATIVE,
        )
    }

    @Test
    fun pointerAssociatedWithTargetDisplayKeepsNativeInput() {
        assertTrue(
            externalInputRoute(
                environment = DisplayEnvironment.OTHER_EXTERNAL,
                targetDisplayId = 5,
                desktopUiMode = false,
                pointerDisplayIds = listOf(5),
            ) == ExternalInputRoute.NATIVE,
        )
    }

    @Test
    fun unassociatedPointerUsesThePhoneRelay() {
        assertTrue(
            externalInputRoute(
                environment = DisplayEnvironment.OTHER_EXTERNAL,
                targetDisplayId = 5,
                desktopUiMode = false,
                pointerDisplayIds = listOf(null),
            ) == ExternalInputRoute.PHONE_RELAY,
        )
    }

    @Test
    fun externalWithoutPointerHardwareKeepsNativeInput() {
        assertTrue(
            externalInputRoute(
                environment = DisplayEnvironment.OTHER_EXTERNAL,
                targetDisplayId = 5,
                desktopUiMode = false,
                pointerDisplayIds = emptyList(),
            ) == ExternalInputRoute.NATIVE,
        )
    }

    @Test
    fun nativeOverrideKeepsAndroidInputOnGenericExternalDisplays() {
        assertTrue(
            externalInputRoute(
                environment = DisplayEnvironment.OTHER_EXTERNAL,
                targetDisplayId = 5,
                desktopUiMode = false,
                pointerDisplayIds = null,
                preference = ExternalInputRoutePreference.NATIVE,
            ) == ExternalInputRoute.NATIVE,
        )
    }

    @Test
    fun phoneRelayOverrideWinsOverAutomaticDesktopEvidence() {
        assertTrue(
            externalInputRoute(
                environment = DisplayEnvironment.OTHER_EXTERNAL,
                targetDisplayId = 5,
                desktopUiMode = true,
                pointerDisplayIds = listOf(5),
                preference = ExternalInputRoutePreference.PHONE_RELAY,
            ) == ExternalInputRoute.PHONE_RELAY,
        )
    }

    @Test
    fun phoneAndTntKeepTheirNativeInputPaths() {
        for (environment in listOf(DisplayEnvironment.PHONE, DisplayEnvironment.TNT)) {
            assertTrue(
                externalInputRoute(
                    environment = environment,
                    targetDisplayId = 0,
                    desktopUiMode = false,
                    pointerDisplayIds = null,
                    preference = ExternalInputRoutePreference.PHONE_RELAY,
                ) == ExternalInputRoute.NATIVE,
            )
        }
    }

    @Test
    fun installedGenericExternalLaunchesOpenTheDesktopAutomatically() {
        assertTrue(
            shouldAutoLaunchExternalDesktop(
                DisplayEnvironment.OTHER_EXTERNAL,
                installed = true,
            ),
        )
        assertFalse(
            shouldAutoLaunchExternalDesktop(
                DisplayEnvironment.OTHER_EXTERNAL,
                installed = false,
            ),
        )
        assertFalse(
            shouldAutoLaunchExternalDesktop(
                DisplayEnvironment.TNT,
                installed = true,
            ),
        )
    }

    @Test
    fun focusedPhoneSurfaceCapturesTheRelayedPointer() {
        assertTrue(
            shouldCaptureRelayedPointer(
                relayAttached = true,
                hasFocus = true,
            ),
        )
        assertFalse(
            shouldCaptureRelayedPointer(
                relayAttached = true,
                hasFocus = false,
            ),
        )
        assertFalse(
            shouldCaptureRelayedPointer(
                relayAttached = false,
                hasFocus = true,
            ),
        )
    }

    @Test
    fun phoneRelayOwnsPointerCaptureInsteadOfTheExternalRenderer() {
        assertFalse(usesRendererPointerCapture(ExternalInputRoute.PHONE_RELAY))
        assertTrue(usesRendererPointerCapture(ExternalInputRoute.NATIVE))
    }

    @Test
    fun onlyTheRendererOwnerFinishesThePhoneRelay() {
        assertFalse(
            shouldFinishInputRelay(
                hasRendererLease = false,
                ownsLorieView = false,
            ),
        )
        assertFalse(
            shouldFinishInputRelay(
                hasRendererLease = true,
                ownsLorieView = false,
            ),
        )
        assertTrue(
            shouldFinishInputRelay(
                hasRendererLease = true,
                ownsLorieView = true,
            ),
        )
    }

    @Test
    fun capturedPointerForwardsOnlyExternalAttachedInput() {
        var forwarded = 0

        assertTrue(
            forwardRelayMotion(isExternal = true, bridgeAttached = true) {
                forwarded++
                true
            },
        )
        assertFalse(
            forwardRelayMotion(isExternal = false, bridgeAttached = true) {
                forwarded++
                true
            },
        )
        assertFalse(
            forwardRelayMotion(isExternal = true, bridgeAttached = false) {
                forwarded++
                true
            },
        )
        assertEquals(1, forwarded)
    }
}
