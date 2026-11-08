package com.orailnoor.droiddesk.display.placement

import com.orailnoor.droiddesk.display.capability.DeviceIdentity
import com.orailnoor.droiddesk.display.capability.PlacementMode

internal data class DevicePlacementQuirk(
    val mode: PlacementMode,
    val tntDisplayIds: Set<Int>,
)

class DeviceQuirkCatalog {
    internal fun resolve(identity: DeviceIdentity): DevicePlacementQuirk? =
        if (identity.device.equals(R2_DEVICE, ignoreCase = true)) {
            DevicePlacementQuirk(
                mode = PlacementMode.DISPLAY_LOCAL,
                tntDisplayIds = setOf(TNT_DISPLAY_ID),
            )
        } else {
            null
        }

    private companion object {
        const val R2_DEVICE = "darwin"
        const val TNT_DISPLAY_ID = 100003
    }
}
