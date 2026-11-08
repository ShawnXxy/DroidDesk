package com.orailnoor.droiddesk.display.input

import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.capability.PointerAssociationEvidence

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
    desktopUiMode: Boolean,
    pointerAssociation: PointerAssociationEvidence,
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
    return when (pointerAssociation) {
        PointerAssociationEvidence.DEFAULT_OR_UNASSOCIATED,
        PointerAssociationEvidence.UNKNOWN,
        -> ExternalInputRoute.PHONE_RELAY

        PointerAssociationEvidence.NO_EXTERNAL_POINTER,
        PointerAssociationEvidence.TARGET_DISPLAY,
        PointerAssociationEvidence.OTHER_DISPLAY,
        -> ExternalInputRoute.NATIVE
    }
}

fun externalInputRoute(
    environment: DisplayEnvironment,
    targetDisplayId: Int,
    desktopUiMode: Boolean,
    pointerDisplayIds: List<Int?>?,
    preference: ExternalInputRoutePreference = ExternalInputRoutePreference.AUTOMATIC,
): ExternalInputRoute {
    val pointerAssociation = when {
        pointerDisplayIds == null -> PointerAssociationEvidence.UNKNOWN
        pointerDisplayIds.isEmpty() -> PointerAssociationEvidence.NO_EXTERNAL_POINTER
        targetDisplayId in pointerDisplayIds -> PointerAssociationEvidence.TARGET_DISPLAY
        pointerDisplayIds.any { it == null || it == android.view.Display.DEFAULT_DISPLAY } -> {
            PointerAssociationEvidence.DEFAULT_OR_UNASSOCIATED
        }

        else -> PointerAssociationEvidence.OTHER_DISPLAY
    }
    return externalInputRoute(
        environment = environment,
        desktopUiMode = desktopUiMode,
        pointerAssociation = pointerAssociation,
        preference = preference,
    )
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

fun shouldFinishInputRelay(
    hasRendererLease: Boolean,
    ownsLorieView: Boolean,
): Boolean = hasRendererLease && ownsLorieView
