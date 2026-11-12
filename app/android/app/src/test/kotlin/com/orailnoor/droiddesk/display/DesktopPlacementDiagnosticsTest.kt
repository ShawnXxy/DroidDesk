package com.orailnoor.droiddesk.display

import android.app.Activity
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

    @Test
    fun syntheticHandoffDiagnosticsResolveEnvironmentBeforeDisplayEnumerationCatchesUp() {
        val phone = display(0, isDefault = true)
        val syntheticTnt = DesktopDisplay(
            id = 100003,
            key = "bound:100003:tnt",
            name = "TNT Synthetic",
            isDefault = false,
            eligible = true,
            reason = null,
        )
        var current: DesktopDisplay? = syntheticTnt
        var enumerated: List<DesktopDisplay> = listOf(phone)
        var launched: DesktopLaunchRequest? = null
        val host = object : DesktopDisplayHost {
            override fun currentDisplay() = current
            override fun displays() = enumerated
            override fun defaultProfileKind() = ProfileKind.PHONE
            override fun sessionRunning() = true
            override fun launch(request: DesktopLaunchRequest) {
                launched = request
            }
            override fun focus(requestId: Long) = false
            override fun cancelLaunch(requestId: Long) = Unit
            override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
            override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
        }
        val selection = PlacementSelection(
            mode = PlacementMode.DISPLAY_LOCAL,
            source = PlacementSelectionSource.DEVICE_QUIRK,
            allowAutomaticMoves = false,
            reason = "synthetic handoff diagnostics test",
            tntDisplayIds = setOf(100003),
        )
        val coordinator = DesktopDisplayCoordinator(
            host = host,
            placementStrategy = DisplayLocalRestartAdapter(selection),
        )

        coordinator.requestOpen(
            request = com.orailnoor.droiddesk.display.placement.DesktopOpenRequest(
                currentDisplayId = syntheticTnt.id,
                mode = "proot",
                desktopEnv = "xfce4",
                startSession = true,
                expectedEpoch = null,
            ),
            startService = {},
            completion = {},
        )
        val launchRequest = checkNotNull(launched)

        val bound = object : Activity() {
            private var orientation = 0
            override fun getRequestedOrientation() = orientation
            override fun setRequestedOrientation(requestedOrientation: Int) {
                orientation = requestedOrientation
            }
        }
        assertTrue(coordinator.bindActivity(bound))

        // Simulate MainActivity on phone rebinding currentDisplay() before DisplayManager enumerates 100003
        // while DesktopActivity is connecting to X11 and calling environmentForDisplay(lease.displayId).
        current = phone
        assertEquals(DisplayEnvironment.TNT, coordinator.environmentForDisplay(syntheticTnt.id))

        coordinator.unbindActivity(bound)
        assertEquals(DisplayEnvironment.TNT, coordinator.environmentForDisplay(syntheticTnt.id))

        // Restore currentDisplay for attachment, complete onAttached, then rebind currentDisplay to phone again
        // (e.g., before SDK 37+ input-device listener or profile refresh re-runs attachDesktopInput).
        current = syntheticTnt
        val lease = (coordinator.claim(syntheticTnt.id) {} as RendererClaim.Accepted).lease
        coordinator.registerActivity(lease, launchRequest.requestId, { syntheticTnt.id }) {}
        coordinator.onAttached(lease, launchRequest.requestId, PixelSize(1920, 1080))

        current = phone
        assertEquals(DisplayEnvironment.TNT, coordinator.environmentForDisplay(syntheticTnt.id))
        assertNull(coordinator.environmentForDisplay(999))

        // Movable coordinator: disconnected lastTarget preserves environment fallback without re-enabling auto-launch
        val ineligiblePhone = DesktopDisplay(0, "phone", "Phone", isDefault = true, eligible = false, "disabled")
        val external = DesktopDisplay(42, "hdmi:42", "HDMI", isDefault = false, eligible = true, null)
        var movableDisplays: List<DesktopDisplay> = listOf(ineligiblePhone, external)
        var movableLaunch: DesktopLaunchRequest? = null
        val movableCoordinator = DesktopDisplayCoordinator(
            host = object : DesktopDisplayHost {
                override fun currentDisplay() = ineligiblePhone
                override fun displays() = movableDisplays
                override fun defaultProfileKind() = ProfileKind.PHONE
                override fun sessionRunning() = true
                override fun launch(request: DesktopLaunchRequest) {
                    movableLaunch = request
                }
                override fun focus(requestId: Long) = false
                override fun cancelLaunch(requestId: Long) = Unit
                override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
                override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
            },
            placementStrategy = MovableSecondaryDisplayAdapter(
                PlacementSelection(
                    mode = PlacementMode.MOVABLE,
                    source = PlacementSelectionSource.RUNTIME_EVIDENCE,
                    allowAutomaticMoves = true,
                    reason = "movable disconnected target diagnostics test",
                ),
            ),
        )
        movableCoordinator.open(external.id, external.key) {}
        val extReq = checkNotNull(movableLaunch)
        val extLease = (movableCoordinator.claim(external.id) {} as RendererClaim.Accepted).lease
        movableCoordinator.registerActivity(extLease, extReq.requestId, { external.id }) {}
        movableCoordinator.onAttached(extLease, extReq.requestId, PixelSize(1920, 1080))

        movableDisplays = listOf(ineligiblePhone)
        val staleDiagnostics = movableCoordinator.placementDiagnostics(external.id, installed = true)
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, staleDiagnostics.environment)
        assertFalse(staleDiagnostics.shouldAutoLaunchCurrentDisplay)
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
