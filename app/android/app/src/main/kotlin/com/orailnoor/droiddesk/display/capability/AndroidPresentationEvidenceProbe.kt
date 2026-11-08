package com.orailnoor.droiddesk.display.capability

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.InputDevice
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.DisplayRepository
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.isActivityStartAllowedOnDisplay
import java.lang.reflect.Method

internal data class AndroidPresentationDisplaySnapshot(
    val displayFlags: Int,
    val densityDpi: Int,
    val fontScale: Float,
    val isDesktopUiMode: Boolean,
    val currentMode: DisplayModeEvidence?,
    val supportedModes: List<DisplayModeEvidence>,
)

internal data class AndroidInputDeviceSnapshot(
    val isExternal: Boolean,
    val hasTouchscreen: Boolean = false,
    val hasMouse: Boolean = false,
    val hasTouchpad: Boolean = false,
    val hasKeyboard: Boolean = false,
    val hasStylus: Boolean = false,
    val associationKnown: Boolean,
    val associatedDisplayId: Int? = null,
)

internal interface AndroidPresentationEvidencePlatform {
    val supportsSecondaryActivities: Boolean

    fun isActivityStartAllowedOnDisplay(displayId: Int): Boolean
    fun displaySnapshot(displayId: Int): AndroidPresentationDisplaySnapshot?
    fun inputDevices(): List<AndroidInputDeviceSnapshot>
}

class AndroidPresentationEvidenceProbe internal constructor(
    private val displayInventory: () -> List<DesktopDisplay>,
    private val platform: AndroidPresentationEvidencePlatform,
) {
    internal constructor(
        displayRepository: DisplayRepository,
        platform: AndroidPresentationEvidencePlatform,
    ) : this(
        displayInventory = displayRepository::snapshot,
        platform = platform,
    )

    constructor(
        context: Context,
        displayRepository: DisplayRepository,
    ) : this(
        displayRepository = displayRepository,
        platform = AndroidPresentationEvidencePlatformAdapter(context.applicationContext),
    )

    fun placementEvidence(currentDisplayId: Int): DisplayPlacementEvidence {
        val displays = displayInventory()
        val current = requireDisplay(displays, currentDisplayId)
        val default = displays.firstOrNull(DesktopDisplay::isDefault)
        val another = displays.firstOrNull {
            it.id != currentDisplayId && !it.isDefault && it.eligible
        }
        val permissionByDisplayId = mutableMapOf<Int, Boolean>()

        fun launchAllowed(display: DesktopDisplay?): Boolean =
            display?.let {
                permissionByDisplayId.getOrPut(it.id) {
                    platform.isActivityStartAllowedOnDisplay(it.id)
                }
            } ?: false

        return DisplayPlacementEvidence(
            currentDisplayId = currentDisplayId,
            isDefaultDisplay = current.isDefault,
            supportsSecondaryActivities = platform.supportsSecondaryActivities,
            currentDisplayLaunchAllowed = launchAllowed(current),
            defaultDisplayLaunchAllowed = launchAllowed(default),
            anotherDisplayLaunchAllowed = launchAllowed(another),
        )
    }

    fun presentationContext(
        displayId: Int,
        activeWindowSize: PixelSize? = null,
    ): PresentationContext {
        val display = displayIdentity(displayId)
        val snapshot = platform.displaySnapshot(displayId)
            ?: throw displayUnavailable(displayId)
        val windowSize = activeWindowSize ?: snapshot.currentMode?.let {
            PixelSize(it.width, it.height)
        } ?: throw DesktopDisplayException(
            "DISPLAY_UNAVAILABLE",
            "Display $displayId has no current physical mode",
        )

        return PresentationContext(
            displayId = display.id,
            displayName = display.name,
            displayFlags = snapshot.displayFlags,
            isDefaultDisplay = display.isDefault,
            windowSizePx = windowSize,
            densityDpi = snapshot.densityDpi,
            fontScale = snapshot.fontScale,
            isDesktopUiMode = snapshot.isDesktopUiMode,
            currentMode = snapshot.currentMode,
            supportedModes = snapshot.supportedModes,
            input = resolveInputCapabilities(displayId, platform.inputDevices()),
        )
    }

    internal fun displayIdentity(displayId: Int): DesktopDisplay =
        requireDisplay(displayInventory(), displayId)

    private fun requireDisplay(
        displays: List<DesktopDisplay>,
        displayId: Int,
    ): DesktopDisplay =
        displays.firstOrNull { it.id == displayId } ?: throw displayUnavailable(displayId)

    private fun displayUnavailable(displayId: Int) = DesktopDisplayException(
        "DISPLAY_UNAVAILABLE",
        "Display $displayId is no longer available",
    )
}

private fun resolveInputCapabilities(
    targetDisplayId: Int,
    devices: List<AndroidInputDeviceSnapshot>,
): InputCapabilities {
    val touchDevices = devices.filter(AndroidInputDeviceSnapshot::hasTouchscreen)
    val touch = when {
        touchDevices.any {
            it.associationKnown && it.associatedDisplayId == targetDisplayId
        } -> TouchEvidence.CURRENT_DISPLAY
        touchDevices.isEmpty() -> TouchEvidence.NONE
        touchDevices.any {
            !it.associationKnown || it.associatedDisplayId == null
        } -> TouchEvidence.UNKNOWN_ASSOCIATION
        else -> TouchEvidence.NONE
    }

    val externalPointers = devices.filter {
        it.isExternal && (it.hasMouse || it.hasTouchpad)
    }
    val pointerAssociation = when {
        externalPointers.any {
            it.associationKnown && it.associatedDisplayId == targetDisplayId
        } -> PointerAssociationEvidence.TARGET_DISPLAY
        externalPointers.any {
            it.associationKnown &&
                (it.associatedDisplayId == null ||
                    it.associatedDisplayId == Display.DEFAULT_DISPLAY)
        } -> PointerAssociationEvidence.DEFAULT_OR_UNASSOCIATED
        externalPointers.any {
            it.associationKnown && it.associatedDisplayId != targetDisplayId
        } -> PointerAssociationEvidence.OTHER_DISPLAY
        externalPointers.isEmpty() -> PointerAssociationEvidence.NO_EXTERNAL_POINTER
        else -> PointerAssociationEvidence.UNKNOWN
    }

    return InputCapabilities(
        touch = touch,
        pointerAssociation = pointerAssociation,
        hasMouse = devices.any(AndroidInputDeviceSnapshot::hasMouse),
        hasTouchpad = devices.any(AndroidInputDeviceSnapshot::hasTouchpad),
        hasKeyboard = devices.any(AndroidInputDeviceSnapshot::hasKeyboard),
        hasStylus = devices.any(AndroidInputDeviceSnapshot::hasStylus),
    )
}

private class AndroidPresentationEvidencePlatformAdapter(
    private val context: Context,
) : AndroidPresentationEvidencePlatform {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    override val supportsSecondaryActivities: Boolean
        get() = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
        )

    override fun isActivityStartAllowedOnDisplay(displayId: Int): Boolean =
        isActivityStartAllowedOnDisplay(context, displayId)

    override fun displaySnapshot(displayId: Int): AndroidPresentationDisplaySnapshot? {
        val display = displayManager.getDisplay(displayId) ?: return null
        val configuration = context.createDisplayContext(display).resources.configuration
        return AndroidPresentationDisplaySnapshot(
            displayFlags = display.flags,
            densityDpi = configuration.densityDpi,
            fontScale = configuration.fontScale,
            isDesktopUiMode =
                configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                    Configuration.UI_MODE_TYPE_DESK,
            currentMode = display.mode.toEvidence(),
            supportedModes = display.supportedModes.map { it.toEvidence() },
        )
    }

    override fun inputDevices(): List<AndroidInputDeviceSnapshot> {
        val associationMethod = associatedDisplayIdMethod()
        return InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .map { device ->
                val (associationKnown, associatedDisplayId) =
                    associatedDisplay(device, associationMethod)
                AndroidInputDeviceSnapshot(
                    isExternal = device.isExternal,
                    hasTouchscreen = device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN),
                    hasMouse = device.supportsSource(InputDevice.SOURCE_MOUSE),
                    hasTouchpad = device.supportsSource(InputDevice.SOURCE_TOUCHPAD),
                    hasKeyboard = device.supportsSource(InputDevice.SOURCE_KEYBOARD),
                    hasStylus = device.supportsSource(InputDevice.SOURCE_STYLUS),
                    associationKnown = associationKnown,
                    associatedDisplayId = associatedDisplayId,
                )
            }
            .toList()
    }

    private fun associatedDisplayIdMethod(): Method? {
        if (Build.VERSION.SDK_INT < 37) return null
        return try {
            InputDevice::class.java.getMethod("getAssociatedDisplayId")
        } catch (error: ReflectiveOperationException) {
            Log.e(TAG, "Android input display association lookup is unavailable", error)
            null
        }
    }

    private fun associatedDisplay(
        device: InputDevice,
        method: Method?,
    ): Pair<Boolean, Int?> {
        method ?: return false to null
        return try {
            true to (method.invoke(device) as Int).takeUnless {
                it == Display.INVALID_DISPLAY
            }
        } catch (error: ReflectiveOperationException) {
            Log.e(TAG, "Android input display association lookup failed", error)
            false to null
        }
    }

    private fun Display.Mode.toEvidence() = DisplayModeEvidence(
        modeId = modeId,
        width = physicalWidth,
        height = physicalHeight,
        refreshRate = refreshRate,
    )

    private companion object {
        const val TAG = "PresentationEvidence"
    }
}
