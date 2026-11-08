package com.orailnoor.droiddesk.display

import android.content.pm.ActivityInfo

class DisplayEnvironmentPolicy private constructor(val displayLocalMode: Boolean) {
    val allowCrossDisplayMoves: Boolean get() = !displayLocalMode

    fun environmentFor(displayId: Int): DisplayEnvironment = when {
        displayId == 0 -> DisplayEnvironment.PHONE
        displayLocalMode && displayId == 100003 -> DisplayEnvironment.TNT
        else -> DisplayEnvironment.OTHER_EXTERNAL
    }

    fun requestedOrientationFor(displayId: Int, originalOrientation: Int): Int =
        if (environmentFor(displayId) == DisplayEnvironment.TNT) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else originalOrientation

    companion object {
        const val RESTART_WARNING =
            "Opening DroidDesk in another display restarts Linux/XFCE. Save unsaved work first."
        fun forDevice(device: String) = DisplayEnvironmentPolicy(device == "darwin")
    }
}
