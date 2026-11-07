package com.orailnoor.droiddesk.display

import android.content.pm.ActivityInfo

enum class DisplayEnvironment(val wireName: String) {
    PHONE("phone"), TNT("tnt"), OTHER_EXTERNAL("otherExternal"),
}

enum class ExternalInputRoute {
    NATIVE,
    PHONE_RELAY,
}

enum class ExternalInputRoutePreference(val wireName: String) {
    AUTOMATIC("automatic"),
    NATIVE("native"),
    PHONE_RELAY("phoneRelay");

    companion object {
        fun fromWire(value: String?): ExternalInputRoutePreference? =
            entries.firstOrNull { it.wireName == value }
    }
}

fun externalInputRoute(
    environment: DisplayEnvironment,
    targetDisplayId: Int,
    desktopUiMode: Boolean,
    pointerDisplayIds: List<Int?>?,
    preference: ExternalInputRoutePreference = ExternalInputRoutePreference.AUTOMATIC,
): ExternalInputRoute {
    if (environment != DisplayEnvironment.OTHER_EXTERNAL) {
        return ExternalInputRoute.NATIVE
    }
    when (preference) {
        ExternalInputRoutePreference.NATIVE -> return ExternalInputRoute.NATIVE
        ExternalInputRoutePreference.PHONE_RELAY -> return ExternalInputRoute.PHONE_RELAY
        ExternalInputRoutePreference.AUTOMATIC -> Unit
    }
    if (desktopUiMode) return ExternalInputRoute.NATIVE
    val associations = pointerDisplayIds ?: return ExternalInputRoute.PHONE_RELAY
    if (associations.isEmpty() || targetDisplayId in associations) {
        return ExternalInputRoute.NATIVE
    }
    return if (associations.any { it == null || it == android.view.Display.DEFAULT_DISPLAY }) {
        ExternalInputRoute.PHONE_RELAY
    } else {
        ExternalInputRoute.NATIVE
    }
}

fun shouldAutoLaunchExternalDesktop(
    environment: DisplayEnvironment,
    installed: Boolean,
): Boolean = installed && environment == DisplayEnvironment.OTHER_EXTERNAL

fun shouldCaptureRelayedPointer(
    relayAttached: Boolean,
    hasFocus: Boolean,
): Boolean = relayAttached && hasFocus

fun usesRendererPointerCapture(route: ExternalInputRoute): Boolean =
    route == ExternalInputRoute.NATIVE

data class DesktopStartupTarget(
    val displayId: Int,
    val displayKey: String,
    val environment: DisplayEnvironment,
)

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
