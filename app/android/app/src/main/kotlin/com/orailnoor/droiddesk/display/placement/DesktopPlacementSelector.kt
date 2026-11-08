package com.orailnoor.droiddesk.display.placement

import com.orailnoor.droiddesk.display.capability.DeviceIdentity
import com.orailnoor.droiddesk.display.capability.DisplayPlacementEvidence
import com.orailnoor.droiddesk.display.capability.PlacementMode

enum class PlacementSelectionSource(val wireName: String) {
    USER_OVERRIDE("userOverride"),
    RUNTIME_EVIDENCE("runtimeEvidence"),
    DEVICE_QUIRK("deviceQuirk"),
    SAFE_DEFAULT("safeDefault"),
}

data class PlacementSelection(
    val mode: PlacementMode,
    val source: PlacementSelectionSource,
    val allowAutomaticMoves: Boolean,
    val reason: String,
    val tntDisplayIds: Set<Int> = emptySet(),
)

class DesktopPlacementSelector(
    private val quirks: DeviceQuirkCatalog,
) {
    fun select(
        evidence: DisplayPlacementEvidence,
        identity: DeviceIdentity,
        userOverride: PlacementMode?,
    ): PlacementSelection {
        val quirk = quirks.resolve(identity)
        val tntDisplayIds = quirk?.tntDisplayIds.orEmpty()

        if (userOverride != null) {
            return PlacementSelection(
                mode = userOverride,
                source = PlacementSelectionSource.USER_OVERRIDE,
                allowAutomaticMoves = userOverride == PlacementMode.MOVABLE,
                reason = "The user selected ${userOverride.wireName} placement",
                tntDisplayIds = tntDisplayIds,
            )
        }

        if (evidence.canMoveAcrossDisplays()) {
            return PlacementSelection(
                mode = PlacementMode.MOVABLE,
                source = PlacementSelectionSource.RUNTIME_EVIDENCE,
                allowAutomaticMoves = true,
                reason = "Runtime evidence permits placement across displays",
                tntDisplayIds = tntDisplayIds,
            )
        }

        if (evidence.isCurrentOnlyExternalPlacement()) {
            return PlacementSelection(
                mode = PlacementMode.DISPLAY_LOCAL,
                source = PlacementSelectionSource.RUNTIME_EVIDENCE,
                allowAutomaticMoves = false,
                reason = "Runtime evidence permits placement only on the current external display",
                tntDisplayIds = tntDisplayIds,
            )
        }

        if (quirk != null) {
            return PlacementSelection(
                mode = quirk.mode,
                source = PlacementSelectionSource.DEVICE_QUIRK,
                allowAutomaticMoves = false,
                reason = "An isolated device quirk selected ${quirk.mode.wireName} placement",
                tntDisplayIds = tntDisplayIds,
            )
        }

        return PlacementSelection(
            mode = PlacementMode.MOVABLE,
            source = PlacementSelectionSource.SAFE_DEFAULT,
            allowAutomaticMoves = false,
            reason = "Placement evidence is inconclusive; automatic moves are disabled",
        )
    }

    private fun DisplayPlacementEvidence.canMoveAcrossDisplays(): Boolean {
        if (!supportsSecondaryActivities || !currentDisplayLaunchAllowed) return false
        return if (isDefaultDisplay) {
            anotherDisplayLaunchAllowed
        } else {
            defaultDisplayLaunchAllowed || anotherDisplayLaunchAllowed
        }
    }

    private fun DisplayPlacementEvidence.isCurrentOnlyExternalPlacement(): Boolean =
        !isDefaultDisplay &&
            currentDisplayLaunchAllowed &&
            !defaultDisplayLaunchAllowed &&
            !anotherDisplayLaunchAllowed
}
