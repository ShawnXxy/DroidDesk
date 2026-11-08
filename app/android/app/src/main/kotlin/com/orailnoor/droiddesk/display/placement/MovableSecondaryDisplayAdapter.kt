package com.orailnoor.droiddesk.display.placement

import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.DesktopStartupTarget
import com.orailnoor.droiddesk.display.DisplayEnvironment

class MovableSecondaryDisplayAdapter(
    override val selection: PlacementSelection,
) : DesktopPlacementStrategy {
    override val allowsManualMoves = true
    override val allowsAutomaticMoves = selection.allowAutomaticMoves
    override val environmentRestartWarning: String? = null

    override fun environmentFor(display: DesktopDisplay): DisplayEnvironment = when {
        display.isDefault -> DisplayEnvironment.PHONE
        display.id in selection.tntDisplayIds -> DisplayEnvironment.TNT
        else -> DisplayEnvironment.OTHER_EXTERNAL
    }

    override fun requestedOrientation(
        display: DesktopDisplay,
        originalOrientation: Int,
    ): Int = originalOrientation

    override fun shouldAutoLaunchCurrentDisplay(
        display: DesktopDisplay,
        installed: Boolean,
    ): Boolean = installed && environmentFor(display) == DisplayEnvironment.OTHER_EXTERNAL

    override fun launch(port: DesktopPlacementPort, request: DesktopLaunchRequest) {
        port.launchOnDisplay(request)
    }

    override fun focus(port: DesktopPlacementPort, requestId: Long): Boolean =
        port.focusTask(requestId)

    override fun cancel(port: DesktopPlacementPort, requestId: Long) {
        port.cancelTask(requestId)
    }

    override fun requiresSessionRestart(
        active: DesktopStartupTarget?,
        next: DesktopStartupTarget,
    ): Boolean = false

    override fun fallbackTarget(
        displays: List<DesktopDisplay>,
        previous: DesktopDisplay?,
        failed: DesktopDisplay,
    ): DesktopDisplay? {
        val previousKey = previous?.key?.takeUnless { it == failed.key }
        return displays.firstOrNull {
            it.eligible && it.key == previousKey
        } ?: displays.firstOrNull {
            it.eligible && it.isDefault && it.key != failed.key
        }
    }
}
