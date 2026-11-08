package com.orailnoor.droiddesk.display.placement

import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.DesktopStartupTarget
import com.orailnoor.droiddesk.display.DisplayEnvironment

interface DesktopPlacementPort {
    fun launchLocal(request: DesktopLaunchRequest)
    fun launchOnDisplay(request: DesktopLaunchRequest)
    fun focusLocal(requestId: Long): Boolean
    fun focusTask(requestId: Long): Boolean
    fun cancelLocal(requestId: Long)
    fun cancelTask(requestId: Long)
}

interface DesktopPlacementStrategy {
    val selection: PlacementSelection
    val allowsManualMoves: Boolean
    val allowsAutomaticMoves: Boolean
    val environmentRestartWarning: String?

    fun environmentFor(display: DesktopDisplay): DisplayEnvironment
    fun requestedOrientation(
        display: DesktopDisplay,
        originalOrientation: Int,
    ): Int
    fun shouldAutoLaunchCurrentDisplay(
        display: DesktopDisplay,
        installed: Boolean,
    ): Boolean
    fun launch(port: DesktopPlacementPort, request: DesktopLaunchRequest)
    fun focus(port: DesktopPlacementPort, requestId: Long): Boolean
    fun cancel(port: DesktopPlacementPort, requestId: Long)
    fun requiresSessionRestart(
        active: DesktopStartupTarget?,
        next: DesktopStartupTarget,
    ): Boolean
    fun fallbackTarget(
        displays: List<DesktopDisplay>,
        previous: DesktopDisplay?,
        failed: DesktopDisplay,
    ): DesktopDisplay?
}

data class DesktopOpenRequest(
    val currentDisplayId: Int,
    val mode: String,
    val desktopEnv: String,
    val startSession: Boolean,
    val expectedEpoch: Long?,
)
