package com.orailnoor.droiddesk.display.placement

import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlacementSelectorTest {
    private val selector = DesktopPlacementSelector(DeviceQuirkCatalog())

    @Test
    fun userOverrideWinsOverRuntimeAndQuirkEvidence() {
        val selection = selector.select(
            evidence = PresentationFixtures.r2TntPlacement,
            identity = PresentationFixtures.r2Identity,
            userOverride = PlacementMode.MOVABLE,
        )

        assertEquals(PlacementMode.MOVABLE, selection.mode)
        assertEquals(PlacementSelectionSource.USER_OVERRIDE, selection.source)
        assertTrue(selection.allowAutomaticMoves)
    }

    @Test
    fun crossDisplayPermissionSelectsMovableAdapter() {
        val selection = selector.select(
            evidence = PresentationFixtures.r2TntPlacement.copy(
                defaultDisplayLaunchAllowed = true,
            ),
            identity = PresentationFixtures.r2Identity,
            userOverride = null,
        )

        assertEquals(PlacementMode.MOVABLE, selection.mode)
        assertEquals(PlacementSelectionSource.RUNTIME_EVIDENCE, selection.source)
        assertTrue(selection.allowAutomaticMoves)
    }

    @Test
    fun currentOnlyExternalPlacementSelectsDisplayLocalAdapter() {
        val selection = selector.select(
            evidence = PresentationFixtures.r2TntPlacement,
            identity = PresentationFixtures.ambiguousOemIdentity,
            userOverride = null,
        )

        assertEquals(PlacementMode.DISPLAY_LOCAL, selection.mode)
        assertEquals(PlacementSelectionSource.RUNTIME_EVIDENCE, selection.source)
        assertFalse(selection.allowAutomaticMoves)
    }

    @Test
    fun r2FallsBackToIsolatedDeviceQuirk() {
        val selection = selector.select(
            evidence = PresentationFixtures.ambiguousOemPlacement,
            identity = PresentationFixtures.r2Identity,
            userOverride = null,
        )

        assertEquals(PlacementMode.DISPLAY_LOCAL, selection.mode)
        assertEquals(PlacementSelectionSource.DEVICE_QUIRK, selection.source)
        assertFalse(selection.allowAutomaticMoves)
    }

    @Test
    fun r2QuirkCarriesTheTntEnvironmentDisplayId() {
        val selection = selector.select(
            evidence = PresentationFixtures.ambiguousOemPlacement,
            identity = PresentationFixtures.r2Identity,
            userOverride = PlacementMode.MOVABLE,
        )

        assertEquals(PlacementSelectionSource.USER_OVERRIDE, selection.source)
        assertEquals(PlacementMode.MOVABLE, selection.mode)
        assertEquals(setOf(100003), selection.tntDisplayIds)
    }

    @Test
    fun ambiguousUnknownDeviceUsesSafeMovableDefaultWithoutAutomaticMoves() {
        val selection = selector.select(
            evidence = PresentationFixtures.ambiguousOemPlacement,
            identity = PresentationFixtures.ambiguousOemIdentity,
            userOverride = null,
        )

        assertEquals(PlacementMode.MOVABLE, selection.mode)
        assertEquals(PlacementSelectionSource.SAFE_DEFAULT, selection.source)
        assertFalse(selection.allowAutomaticMoves)
        assertTrue(selection.reason.isNotBlank())
        assertTrue(selection.tntDisplayIds.isEmpty())
    }
}
