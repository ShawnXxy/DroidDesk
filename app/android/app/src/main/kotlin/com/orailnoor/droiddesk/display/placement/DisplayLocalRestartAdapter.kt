package com.orailnoor.droiddesk.display.placement

import android.content.pm.ActivityInfo
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.DesktopStartupTarget
import com.orailnoor.droiddesk.display.DisplayEnvironment

class DisplayLocalRestartAdapter(
    override val selection: PlacementSelection,
) : DesktopPlacementStrategy {
    override val allowsManualMoves = false
    override val allowsAutomaticMoves = false
    override val environmentRestartWarning =
        "Opening DroidDesk in another display restarts Linux/XFCE. Save unsaved work first."

    override fun environmentFor(display: DesktopDisplay): DisplayEnvironment = when {
        display.isDefault -> DisplayEnvironment.PHONE
        display.id in selection.tntDisplayIds -> DisplayEnvironment.TNT
        else -> DisplayEnvironment.OTHER_EXTERNAL
    }

    override fun requestedOrientation(
        display: DesktopDisplay,
        originalOrientation: Int,
    ): Int =
        if (display.id in selection.tntDisplayIds) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            originalOrientation
        }

    override fun shouldAutoLaunchCurrentDisplay(
        display: DesktopDisplay,
        installed: Boolean,
    ): Boolean = false

    override fun launch(port: DesktopPlacementPort, request: DesktopLaunchRequest) {
        port.launchLocal(request)
    }

    override fun focus(port: DesktopPlacementPort, requestId: Long): Boolean =
        port.focusLocal(requestId)

    override fun cancel(port: DesktopPlacementPort, requestId: Long) {
        port.cancelLocal(requestId)
    }

    override fun requiresSessionRestart(
        active: DesktopStartupTarget?,
        next: DesktopStartupTarget,
    ): Boolean = active != null && active.environment != next.environment

    override fun fallbackTarget(
        displays: List<DesktopDisplay>,
        previous: DesktopDisplay?,
        failed: DesktopDisplay,
    ): DesktopDisplay? = null
}
