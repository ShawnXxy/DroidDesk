package com.orailnoor.droiddesk.display.capability

import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationEvidenceTest {
    @Test
    fun displayModesRequirePositiveDimensionsAndRefreshRate() {
        val valid = DisplayModeEvidence(
            modeId = 1,
            width = 1920,
            height = 1080,
            refreshRate = 60f,
        )

        assertThrows(IllegalArgumentException::class.java) { valid.copy(modeId = 0) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(width = 0) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(height = -1) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(refreshRate = 0f) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(refreshRate = Float.NaN) }
    }

    @Test
    fun presentationContextRequiresPositiveWindowBoundsAndNonNegativeDensity() {
        assertThrows(IllegalArgumentException::class.java) {
            presentationContext(windowSizePx = PixelSize(0, 1080))
        }
        assertThrows(IllegalArgumentException::class.java) {
            presentationContext(windowSizePx = PixelSize(1920, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            presentationContext(densityDpi = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            presentationContext(fontScale = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            presentationContext(fontScale = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            presentationContext(
                supportedModes = listOf(
                    DisplayModeEvidence(1, 1920, 1080, 60f),
                    DisplayModeEvidence(1, 1920, 1080, 90f),
                ),
            )
        }

        assertEquals(0, presentationContext(densityDpi = 0).densityDpi)
    }

    @Test
    fun presentationContextSnapshotsSupportedModes() {
        val originalMode = DisplayModeEvidence(1, 1920, 1080, 60f)
        val source = mutableListOf(originalMode)
        val context = presentationContext(supportedModes = source)

        source += DisplayModeEvidence(2, 1920, 1080, 90f)

        assertEquals(listOf(originalMode), context.supportedModes)
        assertThrows(UnsupportedOperationException::class.java) {
            (context.supportedModes as MutableList).add(
                DisplayModeEvidence(2, 1920, 1080, 90f),
            )
        }

        val replacementMode = DisplayModeEvidence(3, 2560, 1440, 60f)
        val replacement = mutableListOf(replacementMode)
        val copy = context.copy(
            currentMode = replacementMode,
            supportedModes = replacement,
        )

        replacement += DisplayModeEvidence(4, 2560, 1440, 120f)

        assertEquals(listOf(replacementMode), copy.supportedModes)
    }

    @Test
    fun r2AndNubiaFixturesUseDifferentPlacementEvidence() {
        assertEquals("darwin", PresentationFixtures.r2Identity.device)
        assertEquals(PixelSize(1080, 2340), PresentationFixtures.r2Phone.windowSizePx)
        assertEquals(100003, PresentationFixtures.r2Tnt.displayId)
        assertEquals(PixelSize(1260, 2800), PresentationFixtures.nx888jPhone.windowSizePx)
        assertEquals(PixelSize(2160, 1440), PresentationFixtures.nx888jHdmi.windowSizePx)

        assertFalse(PresentationFixtures.r2TntPlacement.anotherDisplayLaunchAllowed)
        assertTrue(PresentationFixtures.nx888jHdmiPlacement.anotherDisplayLaunchAllowed)
        assertNotEquals(
            PresentationFixtures.r2TntPlacement,
            PresentationFixtures.nx888jHdmiPlacement,
        )
    }

    @Test
    fun externalTouchFixtureCarriesCurrentDisplayTouchAndPointerCapabilities() {
        val input = PresentationFixtures.externalTouch.input

        assertEquals(TouchEvidence.CURRENT_DISPLAY, input.touch)
        assertEquals(PointerAssociationEvidence.TARGET_DISPLAY, input.pointerAssociation)
        assertTrue(input.hasMouse)
        assertTrue(input.hasKeyboard)
    }

    @Test
    fun phoneTouchDoesNotMarkUnrelatedHdmiDisplayAsTouchCapable() {
        assertEquals(TouchEvidence.CURRENT_DISPLAY, PresentationFixtures.nx888jPhone.input.touch)
        assertEquals(TouchEvidence.NONE, PresentationFixtures.nx888jHdmi.input.touch)
        assertFalse(PresentationFixtures.nx888jHdmi.isDefaultDisplay)
    }

    @Test
    fun unknownPointerAssociationFixturePreservesRelayFallbackEvidence() {
        val fixture = PresentationFixtures.unknownPointerAssociation

        assertEquals(PointerAssociationEvidence.UNKNOWN, fixture.input.pointerAssociation)
        assertTrue(fixture.input.hasMouse)
        assertTrue(fixture.input.hasTouchpad)
        assertFalse(fixture.isDefaultDisplay)
    }

    @Test
    fun foldableNarrowWindowFixtureUsesCurrentWindowBounds() {
        val fixture = PresentationFixtures.foldableNarrowWindow

        assertTrue(fixture.isDefaultDisplay)
        assertEquals(PixelSize(840, 2200), fixture.windowSizePx)
        assertEquals(TouchEvidence.CURRENT_DISPLAY, fixture.input.touch)
    }

    @Test
    fun fourK120FixtureHasNoResolutionMatchingSixtyHertzMode() {
        val fixture = PresentationFixtures.fourK120
        val current = fixture.currentMode!!

        assertEquals(3840, current.width)
        assertEquals(2160, current.height)
        assertEquals(120f, current.refreshRate)
        assertTrue(
            fixture.supportedModes.any {
                it.width == 1920 && it.height == 1080 && abs(it.refreshRate - 60f) < 0.5f
            },
        )
        assertFalse(
            fixture.supportedModes.any {
                it.width == 3840 && it.height == 2160 && abs(it.refreshRate - 60f) < 0.5f
            },
        )
    }

    @Test
    fun futurePolicyFixturesPreserveSparsePointerAndAmbiguousOemEvidence() {
        assertEquals(setOf(0, 12, 31), PresentationFixtures.sparsePointerIds)
        assertEquals(
            PointerAssociationEvidence.UNKNOWN,
            PresentationFixtures.ambiguousOem.input.pointerAssociation,
        )
        assertFalse(PresentationFixtures.ambiguousOemPlacement.currentDisplayLaunchAllowed)
        assertFalse(PresentationFixtures.ambiguousOemPlacement.anotherDisplayLaunchAllowed)
    }

    private fun presentationContext(
        windowSizePx: PixelSize = PixelSize(1920, 1080),
        densityDpi: Int = 320,
        fontScale: Float = 1f,
        supportedModes: List<DisplayModeEvidence> = listOf(
            DisplayModeEvidence(1, 1920, 1080, 60f),
        ),
    ) = PresentationContext(
        displayId = 0,
        displayName = "Test display",
        displayFlags = 0,
        isDefaultDisplay = true,
        windowSizePx = windowSizePx,
        densityDpi = densityDpi,
        fontScale = fontScale,
        isDesktopUiMode = false,
        currentMode = supportedModes.firstOrNull(),
        supportedModes = supportedModes,
        input = InputCapabilities(
            touch = TouchEvidence.CURRENT_DISPLAY,
            pointerAssociation = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
            hasMouse = false,
            hasTouchpad = false,
            hasKeyboard = false,
            hasStylus = false,
        ),
    )
}
