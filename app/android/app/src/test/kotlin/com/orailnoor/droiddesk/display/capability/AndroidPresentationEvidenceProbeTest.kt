package com.orailnoor.droiddesk.display.capability

import android.content.Context
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DisplayRepository
import com.orailnoor.droiddesk.display.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPresentationEvidenceProbeTest {
    @Test
    fun productionConstructorRequiresTheCanonicalDisplayRepository() {
        val factory: (Context, DisplayRepository) -> AndroidPresentationEvidenceProbe =
            ::AndroidPresentationEvidenceProbe

        assertNotNull(factory)
    }

    @Test
    fun inactiveDisplayUsesDisplayResourcesAndCurrentPhysicalModeBounds() {
        val currentMode = DisplayModeEvidence(7, 2560, 1440, 60f)
        val platform = FakeAndroidPresentationEvidencePlatform(
            displaySnapshots = mapOf(
                7 to AndroidPresentationDisplaySnapshot(
                    displayFlags = 0x40,
                    densityDpi = 240,
                    fontScale = 1.15f,
                    isDesktopUiMode = true,
                    currentMode = currentMode,
                    supportedModes = listOf(
                        currentMode,
                        DisplayModeEvidence(8, 2560, 1440, 90f),
                    ),
                ),
            ),
        )
        val probe = probe(
            displays = listOf(display(7, "Conference display")),
            platform = platform,
        )

        val context = probe.presentationContext(displayId = 7)

        assertEquals(7, context.displayId)
        assertEquals("Conference display", context.displayName)
        assertEquals(0x40, context.displayFlags)
        assertFalse(context.isDefaultDisplay)
        assertEquals(PixelSize(2560, 1440), context.windowSizePx)
        assertEquals(240, context.densityDpi)
        assertEquals(1.15f, context.fontScale)
        assertTrue(context.isDesktopUiMode)
        assertEquals(currentMode, context.currentMode)
        assertEquals(listOf(7, 8), context.supportedModes.map(DisplayModeEvidence::modeId))
    }

    @Test
    fun activeWindowSizeOverridesThePhysicalModeFallback() {
        val platform = FakeAndroidPresentationEvidencePlatform(
            displaySnapshots = mapOf(
                7 to displaySnapshot(width = 2560, height = 1440),
            ),
        )
        val probe = probe(
            displays = listOf(display(7, "Active display")),
            platform = platform,
        )

        val context = probe.presentationContext(
            displayId = 7,
            activeWindowSize = PixelSize(1280, 720),
        )

        assertEquals(PixelSize(1280, 720), context.windowSizePx)
    }

    @Test
    fun placementEvidenceChecksCurrentDefaultAndOneOtherEligibleDisplay() {
        val platform = FakeAndroidPresentationEvidencePlatform(
            supportsSecondaryActivities = true,
            launchPermissions = mapOf(
                0 to true,
                7 to false,
                9 to true,
                10 to false,
            ),
        )
        val probe = probe(
            displays = listOf(
                display(0, "Phone", isDefault = true),
                display(7, "Current"),
                display(10, "Ineligible", eligible = false),
                display(9, "Other eligible"),
            ),
            platform = platform,
        )

        val evidence = probe.placementEvidence(currentDisplayId = 7)

        assertEquals(7, evidence.currentDisplayId)
        assertFalse(evidence.isDefaultDisplay)
        assertTrue(evidence.supportsSecondaryActivities)
        assertFalse(evidence.currentDisplayLaunchAllowed)
        assertTrue(evidence.defaultDisplayLaunchAllowed)
        assertTrue(evidence.anotherDisplayLaunchAllowed)
    }

    @Test
    fun unassociatedTouchRemainsUnknownForAnExternalDisplay() {
        val platform = FakeAndroidPresentationEvidencePlatform(
            displaySnapshots = mapOf(7 to displaySnapshot()),
            inputDevices = listOf(
                AndroidInputDeviceSnapshot(
                    isExternal = false,
                    hasTouchscreen = true,
                    associationKnown = true,
                    associatedDisplayId = null,
                ),
            ),
        )
        val probe = probe(
            displays = listOf(display(7, "HDMI display")),
            platform = platform,
        )

        val input = probe.presentationContext(7).input

        assertEquals(TouchEvidence.UNKNOWN_ASSOCIATION, input.touch)
    }

    @Test
    fun associatedTouchIsScopedToTheTargetDisplay() {
        val platform = FakeAndroidPresentationEvidencePlatform(
            displaySnapshots = mapOf(
                7 to displaySnapshot(),
                9 to displaySnapshot(),
            ),
            inputDevices = listOf(
                AndroidInputDeviceSnapshot(
                    isExternal = true,
                    hasTouchscreen = true,
                    associationKnown = true,
                    associatedDisplayId = 7,
                ),
            ),
        )
        val probe = probe(
            displays = listOf(
                display(7, "Touch display"),
                display(9, "Other display"),
            ),
            platform = platform,
        )

        assertEquals(
            TouchEvidence.CURRENT_DISPLAY,
            probe.presentationContext(7).input.touch,
        )
        assertEquals(
            TouchEvidence.NONE,
            probe.presentationContext(9).input.touch,
        )
    }

    @Test
    fun pointerAssociationUsesTheDocumentedPrecedence() {
        val target = 7
        val cases = listOf(
            listOf(
                pointer(associatedDisplayId = 9),
                pointer(associatedDisplayId = null),
                pointer(associatedDisplayId = target),
            ) to PointerAssociationEvidence.TARGET_DISPLAY,
            listOf(
                pointer(associatedDisplayId = 9),
                pointer(associatedDisplayId = null),
            ) to PointerAssociationEvidence.DEFAULT_OR_UNASSOCIATED,
            listOf(pointer(associatedDisplayId = 9)) to PointerAssociationEvidence.OTHER_DISPLAY,
            emptyList<AndroidInputDeviceSnapshot>() to
                PointerAssociationEvidence.NO_EXTERNAL_POINTER,
            listOf(pointer(associationKnown = false)) to PointerAssociationEvidence.UNKNOWN,
        )

        cases.forEach { (devices, expected) ->
            val platform = FakeAndroidPresentationEvidencePlatform(
                displaySnapshots = mapOf(target to displaySnapshot()),
                inputDevices = devices,
            )
            val probe = probe(
                displays = listOf(display(target, "Pointer display")),
                platform = platform,
            )

            assertEquals(expected, probe.presentationContext(target).input.pointerAssociation)
        }
    }

    private fun probe(
        displays: List<DesktopDisplay>,
        platform: AndroidPresentationEvidencePlatform,
    ) = AndroidPresentationEvidenceProbe(
        displayInventory = { displays },
        platform = platform,
    )

    private fun display(
        id: Int,
        name: String,
        isDefault: Boolean = false,
        eligible: Boolean = true,
    ) = DesktopDisplay(
        id = id,
        key = "connection:$id",
        name = name,
        isDefault = isDefault,
        eligible = eligible,
        reason = if (eligible) null else "not eligible",
        settingsKey = if (isDefault) "default" else "stable:$id",
    )

    private fun displaySnapshot(
        width: Int = 1920,
        height: Int = 1080,
    ): AndroidPresentationDisplaySnapshot {
        val mode = DisplayModeEvidence(1, width, height, 60f)
        return AndroidPresentationDisplaySnapshot(
            displayFlags = 0,
            densityDpi = 320,
            fontScale = 1f,
            isDesktopUiMode = false,
            currentMode = mode,
            supportedModes = listOf(mode),
        )
    }

    private fun pointer(
        associatedDisplayId: Int? = null,
        associationKnown: Boolean = true,
    ) = AndroidInputDeviceSnapshot(
        isExternal = true,
        hasMouse = true,
        associationKnown = associationKnown,
        associatedDisplayId = associatedDisplayId,
    )

    private class FakeAndroidPresentationEvidencePlatform(
        override val supportsSecondaryActivities: Boolean = true,
        private val launchPermissions: Map<Int, Boolean> = emptyMap(),
        private val displaySnapshots: Map<Int, AndroidPresentationDisplaySnapshot> = emptyMap(),
        private val inputDevices: List<AndroidInputDeviceSnapshot> = emptyList(),
    ) : AndroidPresentationEvidencePlatform {
        override fun isActivityStartAllowedOnDisplay(displayId: Int): Boolean =
            launchPermissions[displayId] ?: false

        override fun displaySnapshot(displayId: Int): AndroidPresentationDisplaySnapshot? =
            displaySnapshots[displayId]

        override fun inputDevices(): List<AndroidInputDeviceSnapshot> = inputDevices
    }
}
