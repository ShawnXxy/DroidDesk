package com.orailnoor.droiddesk.display.capability

import com.orailnoor.droiddesk.display.PixelSize
import java.util.Collections

enum class PresentationClass(val wireName: String) {
    PHONE_TOUCH("phoneTouch"),
    LARGE_TOUCH("largeTouch"),
    EXTERNAL_POINTER("externalPointer"),
    EXTERNAL_TOUCH("externalTouch"),
}

enum class PlacementMode(val wireName: String) {
    MOVABLE("movable"),
    DISPLAY_LOCAL("displayLocal"),
}

data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val device: String,
    val model: String,
)

data class DisplayModeEvidence(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
) {
    init {
        require(modeId > 0) { "Display mode ID must be positive" }
        require(width > 0 && height > 0) { "Display mode dimensions must be positive" }
        require(refreshRate.isFinite() && refreshRate > 0f) {
            "Display mode refresh rate must be positive and finite"
        }
    }
}

enum class TouchEvidence {
    NONE,
    CURRENT_DISPLAY,
    UNKNOWN_ASSOCIATION,
}

enum class PointerAssociationEvidence {
    NO_EXTERNAL_POINTER,
    TARGET_DISPLAY,
    DEFAULT_OR_UNASSOCIATED,
    OTHER_DISPLAY,
    UNKNOWN,
}

data class InputCapabilities(
    val touch: TouchEvidence,
    val pointerAssociation: PointerAssociationEvidence,
    val hasMouse: Boolean,
    val hasTouchpad: Boolean,
    val hasKeyboard: Boolean,
    val hasStylus: Boolean,
)

data class DisplayPlacementEvidence(
    val currentDisplayId: Int,
    val isDefaultDisplay: Boolean,
    val supportsSecondaryActivities: Boolean,
    val currentDisplayLaunchAllowed: Boolean,
    val defaultDisplayLaunchAllowed: Boolean,
    val anotherDisplayLaunchAllowed: Boolean,
)

@ConsistentCopyVisibility
data class PresentationContext private constructor(
    val displayId: Int,
    val displayName: String,
    val displayFlags: Int,
    val isDefaultDisplay: Boolean,
    val windowSizePx: PixelSize,
    val densityDpi: Int,
    val fontScale: Float,
    val isDesktopUiMode: Boolean,
    val currentMode: DisplayModeEvidence?,
    val supportedModes: List<DisplayModeEvidence>,
    val input: InputCapabilities,
) {
    init {
        require(windowSizePx.width > 0 && windowSizePx.height > 0) {
            "Presentation window bounds must be positive"
        }
        require(densityDpi >= 0) { "Presentation density must not be negative" }
        require(fontScale.isFinite() && fontScale > 0f) {
            "Presentation font scale must be positive and finite"
        }
        require(supportedModes.map(DisplayModeEvidence::modeId).distinct().size == supportedModes.size) {
            "Supported display mode IDs must be unique"
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun copy(
        displayId: Int = this.displayId,
        displayName: String = this.displayName,
        displayFlags: Int = this.displayFlags,
        isDefaultDisplay: Boolean = this.isDefaultDisplay,
        windowSizePx: PixelSize = this.windowSizePx,
        densityDpi: Int = this.densityDpi,
        fontScale: Float = this.fontScale,
        isDesktopUiMode: Boolean = this.isDesktopUiMode,
        currentMode: DisplayModeEvidence? = this.currentMode,
        supportedModes: List<DisplayModeEvidence> = this.supportedModes,
        input: InputCapabilities = this.input,
        snapshotCopy: Unit = Unit,
    ) = Companion.invoke(
        displayId = displayId,
        displayName = displayName,
        displayFlags = displayFlags,
        isDefaultDisplay = isDefaultDisplay,
        windowSizePx = windowSizePx,
        densityDpi = densityDpi,
        fontScale = fontScale,
        isDesktopUiMode = isDesktopUiMode,
        currentMode = currentMode,
        supportedModes = supportedModes,
        input = input,
    )

    companion object {
        operator fun invoke(
            displayId: Int,
            displayName: String,
            displayFlags: Int,
            isDefaultDisplay: Boolean,
            windowSizePx: PixelSize,
            densityDpi: Int,
            fontScale: Float,
            isDesktopUiMode: Boolean,
            currentMode: DisplayModeEvidence?,
            supportedModes: List<DisplayModeEvidence>,
            input: InputCapabilities,
        ) = PresentationContext(
            displayId = displayId,
            displayName = displayName,
            displayFlags = displayFlags,
            isDefaultDisplay = isDefaultDisplay,
            windowSizePx = windowSizePx,
            densityDpi = densityDpi,
            fontScale = fontScale,
            isDesktopUiMode = isDesktopUiMode,
            currentMode = currentMode,
            supportedModes = Collections.unmodifiableList(ArrayList(supportedModes)),
            input = input,
        )
    }
}
