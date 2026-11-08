package com.orailnoor.droiddesk.display.support

import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.capability.DeviceIdentity
import com.orailnoor.droiddesk.display.capability.DisplayModeEvidence
import com.orailnoor.droiddesk.display.capability.DisplayPlacementEvidence
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PointerAssociationEvidence
import com.orailnoor.droiddesk.display.capability.PresentationContext
import com.orailnoor.droiddesk.display.capability.TouchEvidence

object PresentationFixtures {
    val r2Identity = DeviceIdentity(
        manufacturer = "smartisan",
        brand = "smartisan",
        device = "darwin",
        model = "DT2002C",
    )

    val r2Phone = context(
        displayId = 0,
        displayName = "Smartisan R2",
        isDefaultDisplay = true,
        windowSizePx = PixelSize(1080, 2340),
        densityDpi = 480,
        currentMode = mode(2, 1080, 2340, 90f),
        supportedModes = listOf(
            mode(1, 1080, 2340, 60f),
            mode(2, 1080, 2340, 90f),
        ),
        input = touchInput(),
    )

    val r2PhonePlacement = placement(
        currentDisplayId = 0,
        isDefaultDisplay = true,
        anotherDisplayLaunchAllowed = false,
    )

    val r2Tnt = context(
        displayId = 100003,
        displayName = "TNT",
        isDefaultDisplay = false,
        windowSizePx = PixelSize(2160, 1440),
        densityDpi = 240,
        isDesktopUiMode = true,
        currentMode = mode(3, 2160, 1440, 60f),
        supportedModes = listOf(mode(3, 2160, 1440, 60f)),
        input = pointerInput(PointerAssociationEvidence.TARGET_DISPLAY),
    )

    val r2TntPlacement = placement(
        currentDisplayId = 100003,
        isDefaultDisplay = false,
        defaultDisplayLaunchAllowed = false,
        anotherDisplayLaunchAllowed = false,
    )

    val nx888jIdentity = DeviceIdentity(
        manufacturer = "nubia",
        brand = "nubia",
        device = "PQ85A02",
        model = "NX888J",
    )

    val nx888jPhone = context(
        displayId = 0,
        displayName = "Nubia NX888J",
        isDefaultDisplay = true,
        windowSizePx = PixelSize(1260, 2800),
        densityDpi = 560,
        currentMode = mode(2, 1260, 2800, 120f),
        supportedModes = listOf(
            mode(1, 1260, 2800, 60f),
            mode(2, 1260, 2800, 120f),
        ),
        input = touchInput(),
    )

    val nx888jPhonePlacement = placement(
        currentDisplayId = 0,
        isDefaultDisplay = true,
    )

    val nx888jHdmi = context(
        displayId = 10,
        displayName = "RTK FHD",
        isDefaultDisplay = false,
        windowSizePx = PixelSize(2160, 1440),
        densityDpi = 240,
        currentMode = mode(10, 2160, 1440, 60f),
        supportedModes = listOf(mode(10, 2160, 1440, 60f)),
        input = pointerInput(PointerAssociationEvidence.DEFAULT_OR_UNASSOCIATED),
    )

    val nx888jHdmiPlacement = placement(
        currentDisplayId = 10,
        isDefaultDisplay = false,
    )

    val externalTouchIdentity = syntheticIdentity("external-touch")

    val externalTouch = context(
        displayId = 20,
        displayName = "External touch",
        isDefaultDisplay = false,
        windowSizePx = PixelSize(1920, 1080),
        densityDpi = 160,
        currentMode = mode(20, 1920, 1080, 60f),
        supportedModes = listOf(mode(20, 1920, 1080, 60f)),
        input = InputCapabilities(
            touch = TouchEvidence.CURRENT_DISPLAY,
            pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
            hasMouse = true,
            hasTouchpad = false,
            hasKeyboard = true,
            hasStylus = false,
        ),
    )

    val externalTouchPlacement = placement(
        currentDisplayId = 20,
        isDefaultDisplay = false,
    )

    val unknownPointerIdentity = syntheticIdentity("unknown-pointer")

    val unknownPointerAssociation = context(
        displayId = 21,
        displayName = "Unknown pointer association",
        isDefaultDisplay = false,
        windowSizePx = PixelSize(1920, 1080),
        densityDpi = 160,
        currentMode = mode(21, 1920, 1080, 60f),
        supportedModes = listOf(mode(21, 1920, 1080, 60f)),
        input = pointerInput(
            association = PointerAssociationEvidence.UNKNOWN,
            hasTouchpad = true,
        ),
    )

    val unknownPointerPlacement = placement(
        currentDisplayId = 21,
        isDefaultDisplay = false,
    )

    val foldableIdentity = syntheticIdentity("foldable")

    val foldableNarrowWindow = context(
        displayId = 0,
        displayName = "Foldable narrow window",
        isDefaultDisplay = true,
        windowSizePx = PixelSize(840, 2200),
        densityDpi = 420,
        currentMode = mode(1, 1768, 2208, 120f),
        supportedModes = listOf(mode(1, 1768, 2208, 120f)),
        input = touchInput(),
    )

    val foldablePlacement = placement(
        currentDisplayId = 0,
        isDefaultDisplay = true,
    )

    val fourK120Identity = syntheticIdentity("4k120")

    val fourK120 = context(
        displayId = 22,
        displayName = "4K 120 Hz",
        isDefaultDisplay = false,
        windowSizePx = PixelSize(3840, 2160),
        densityDpi = 160,
        currentMode = mode(120, 3840, 2160, 120f),
        supportedModes = listOf(
            mode(120, 3840, 2160, 120f),
            mode(60, 1920, 1080, 60f),
        ),
        input = pointerInput(PointerAssociationEvidence.TARGET_DISPLAY),
    )

    val fourK120Placement = placement(
        currentDisplayId = 22,
        isDefaultDisplay = false,
    )

    val sparsePointerIdentity = syntheticIdentity("sparse-pointer")
    val sparsePointerIds = setOf(0, 12, 31)

    val sparsePointer = context(
        displayId = 23,
        displayName = "Sparse pointer IDs",
        isDefaultDisplay = false,
        windowSizePx = PixelSize(1920, 1080),
        densityDpi = 160,
        currentMode = mode(23, 1920, 1080, 60f),
        supportedModes = listOf(mode(23, 1920, 1080, 60f)),
        input = pointerInput(PointerAssociationEvidence.TARGET_DISPLAY),
    )

    val sparsePointerPlacement = placement(
        currentDisplayId = 23,
        isDefaultDisplay = false,
    )

    val ambiguousOemIdentity = syntheticIdentity("ambiguous-oem")

    val ambiguousOem = context(
        displayId = 24,
        displayName = "Ambiguous external display",
        isDefaultDisplay = false,
        windowSizePx = PixelSize(1920, 1080),
        densityDpi = 0,
        currentMode = mode(24, 1920, 1080, 60f),
        supportedModes = listOf(mode(24, 1920, 1080, 60f)),
        input = pointerInput(PointerAssociationEvidence.UNKNOWN),
    )

    val ambiguousOemPlacement = DisplayPlacementEvidence(
        currentDisplayId = 24,
        isDefaultDisplay = false,
        supportsSecondaryActivities = true,
        currentDisplayLaunchAllowed = false,
        defaultDisplayLaunchAllowed = false,
        anotherDisplayLaunchAllowed = false,
    )

    private fun context(
        displayId: Int,
        displayName: String,
        isDefaultDisplay: Boolean,
        windowSizePx: PixelSize,
        densityDpi: Int,
        currentMode: DisplayModeEvidence,
        supportedModes: List<DisplayModeEvidence>,
        input: InputCapabilities,
        isDesktopUiMode: Boolean = false,
    ) = PresentationContext(
        displayId = displayId,
        displayName = displayName,
        displayFlags = 0,
        isDefaultDisplay = isDefaultDisplay,
        windowSizePx = windowSizePx,
        densityDpi = densityDpi,
        fontScale = 1f,
        isDesktopUiMode = isDesktopUiMode,
        currentMode = currentMode,
        supportedModes = supportedModes,
        input = input,
    )

    private fun placement(
        currentDisplayId: Int,
        isDefaultDisplay: Boolean,
        currentDisplayLaunchAllowed: Boolean = true,
        defaultDisplayLaunchAllowed: Boolean = true,
        anotherDisplayLaunchAllowed: Boolean = true,
    ) = DisplayPlacementEvidence(
        currentDisplayId = currentDisplayId,
        isDefaultDisplay = isDefaultDisplay,
        supportsSecondaryActivities = true,
        currentDisplayLaunchAllowed = currentDisplayLaunchAllowed,
        defaultDisplayLaunchAllowed = defaultDisplayLaunchAllowed,
        anotherDisplayLaunchAllowed = anotherDisplayLaunchAllowed,
    )

    private fun touchInput() = InputCapabilities(
        touch = TouchEvidence.CURRENT_DISPLAY,
        pointerAssociation = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
        hasMouse = false,
        hasTouchpad = false,
        hasKeyboard = false,
        hasStylus = false,
    )

    private fun pointerInput(
        association: PointerAssociationEvidence,
        hasTouchpad: Boolean = false,
    ) = InputCapabilities(
        touch = TouchEvidence.NONE,
        pointerAssociation = association,
        hasMouse = true,
        hasTouchpad = hasTouchpad,
        hasKeyboard = true,
        hasStylus = false,
    )

    private fun mode(
        modeId: Int,
        width: Int,
        height: Int,
        refreshRate: Float,
    ) = DisplayModeEvidence(modeId, width, height, refreshRate)

    private fun syntheticIdentity(name: String) = DeviceIdentity(
        manufacturer = "Example",
        brand = "Example",
        device = name,
        model = name,
    )
}
