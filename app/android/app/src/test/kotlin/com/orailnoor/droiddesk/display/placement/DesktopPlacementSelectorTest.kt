package com.orailnoor.droiddesk.display.placement

import android.content.pm.ActivityInfo
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlacementSelectorTest {
    private val selector = DesktopPlacementSelector(DeviceQuirkCatalog())

    @Test
    fun bootstrapStrategyPreservesLegacyR2AndGenericBehavior() {
        val r2: DesktopPlacementStrategy = bootstrapPlacementStrategy(
            PresentationFixtures.r2Identity,
            DeviceQuirkCatalog(),
        )
        val generic: DesktopPlacementStrategy = bootstrapPlacementStrategy(
            PresentationFixtures.ambiguousOemIdentity,
            DeviceQuirkCatalog(),
        )
        val external = DesktopDisplay(7, "display:7", "External", false, true, null)
        val tnt = external.copy(id = 100003, key = "display:100003")

        assertEquals(PlacementMode.DISPLAY_LOCAL, r2.selection.mode)
        assertEquals(PlacementSelectionSource.DEVICE_QUIRK, r2.selection.source)
        assertFalse(r2.allowsManualMoves)
        assertFalse(r2.allowsAutomaticMoves)
        assertEquals(DisplayEnvironment.TNT, r2.environmentFor(tnt))
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            r2.requestedOrientation(tnt, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        )
        assertFalse(r2.shouldAutoLaunchCurrentDisplay(tnt, installed = true))

        assertEquals(PlacementMode.MOVABLE, generic.selection.mode)
        assertEquals(PlacementSelectionSource.SAFE_DEFAULT, generic.selection.source)
        assertTrue(generic.allowsManualMoves)
        assertTrue(generic.allowsAutomaticMoves)
        assertNull(generic.environmentRestartWarning)
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, generic.environmentFor(external))
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            generic.requestedOrientation(external, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        )
        assertTrue(generic.shouldAutoLaunchCurrentDisplay(external, installed = true))
    }

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
