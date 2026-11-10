package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.MovableSecondaryDisplayAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlacementDiagnosticsTest {
    @Test
    fun movableDiagnosticsForwardTheSelectedStrategy() {
        val selection = PlacementSelection(
            mode = PlacementMode.MOVABLE,
            source = PlacementSelectionSource.SAFE_DEFAULT,
            allowAutomaticMoves = true,
            reason = "diagnostics test",
        )
        val external = display(7, isDefault = false)
        val coordinator = DesktopDisplayCoordinator(
            host = DiagnosticsHost(external),
            placementStrategy = MovableSecondaryDisplayAdapter(selection),
        )

        val diagnostics: DesktopPlacementDiagnostics =
            coordinator.placementDiagnostics(external.id, installed = true)

        assertSame(selection, diagnostics.selection)
        assertTrue(diagnostics.allowsManualMoves)
        assertTrue(diagnostics.allowsAutomaticMoves)
        assertNull(diagnostics.environmentRestartWarning)
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, diagnostics.environment)
        assertTrue(diagnostics.shouldAutoLaunchCurrentDisplay)
        val environment: DisplayEnvironment? = coordinator.environmentForDisplay(external.id)
        val currentEnvironment: DisplayEnvironment? = coordinator.environmentForCurrentDisplay()
        val allowsManualMoves: Boolean = coordinator.allowsManualMoves
        val restartWarning: String? = coordinator.environmentRestartWarning
        val autoLaunch: Boolean =
            coordinator.shouldAutoLaunchCurrentDisplay(external.id, installed = true)
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, environment)
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, currentEnvironment)
        assertTrue(allowsManualMoves)
        assertNull(restartWarning)
        assertTrue(autoLaunch)
    }

    @Test
    fun displayLocalDiagnosticsPreserveTntRestartBehavior() {
        val selection = PlacementSelection(
            mode = PlacementMode.DISPLAY_LOCAL,
            source = PlacementSelectionSource.DEVICE_QUIRK,
            allowAutomaticMoves = false,
            reason = "diagnostics test",
            tntDisplayIds = setOf(100003),
        )
        val tnt = display(100003, isDefault = false)
        val coordinator = DesktopDisplayCoordinator(
            host = DiagnosticsHost(tnt),
            placementStrategy = DisplayLocalRestartAdapter(selection),
        )

        val diagnostics: DesktopPlacementDiagnostics =
            coordinator.placementDiagnostics(tnt.id, installed = true)

        assertSame(selection, diagnostics.selection)
        assertFalse(diagnostics.allowsManualMoves)
        assertFalse(diagnostics.allowsAutomaticMoves)
        assertEquals(
            "Opening DroidDesk in another display restarts Linux/XFCE. Save unsaved work first.",
            diagnostics.environmentRestartWarning,
        )
        assertEquals(DisplayEnvironment.TNT, diagnostics.environment)
        assertFalse(diagnostics.shouldAutoLaunchCurrentDisplay)
        val currentEnvironment: DisplayEnvironment? = coordinator.environmentForCurrentDisplay()
        val allowsManualMoves: Boolean = coordinator.allowsManualMoves
        val restartWarning: String? = coordinator.environmentRestartWarning
        assertEquals(DisplayEnvironment.TNT, currentEnvironment)
        assertFalse(allowsManualMoves)
        assertEquals(
            "Opening DroidDesk in another display restarts Linux/XFCE. Save unsaved work first.",
            restartWarning,
        )
    }

    private fun display(id: Int, isDefault: Boolean) =
        DesktopDisplay(id, "display:$id", "Display $id", isDefault, true, null)
}

private class DiagnosticsHost(
    private val current: DesktopDisplay,
) : DesktopDisplayHost {
    override fun currentDisplay() = current
    override fun displays() = listOf(current)
    override fun defaultProfileKind() = ProfileKind.PHONE
    override fun sessionRunning() = false
    override fun launch(request: DesktopLaunchRequest) = Unit
    override fun focus(requestId: Long) = false
    override fun cancelLaunch(requestId: Long) = Unit
    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
    override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
}
