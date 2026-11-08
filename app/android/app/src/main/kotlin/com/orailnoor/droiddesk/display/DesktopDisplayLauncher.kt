package com.orailnoor.droiddesk.display

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.DesktopPlacementPort
import com.orailnoor.droiddesk.display.placement.DesktopPlacementStrategy
import com.orailnoor.droiddesk.display.placement.DisplayLocalRestartAdapter
import com.orailnoor.droiddesk.display.placement.MovableSecondaryDisplayAdapter
import com.orailnoor.droiddesk.display.placement.PlacementSelection
import com.orailnoor.droiddesk.display.placement.PlacementSelectionSource
import com.orailnoor.droiddesk.view.DesktopActivity
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ProotDebianSessionRegistry
import com.orailnoor.droiddesk.service.DroidDeskService
import java.lang.ref.WeakReference

internal fun <T> activityDisplayForSdk(
    sdkInt: Int,
    api30Display: () -> T?,
    decorDisplay: () -> T?,
): T? =
    if (sdkInt >= Build.VERSION_CODES.R) {
        api30Display() ?: decorDisplay()
    } else {
        decorDisplay()
    }

internal fun resolveLaunchDisplay(
    request: DesktopDisplay,
    repositoryDisplay: DesktopDisplay?,
    boundDisplay: DesktopDisplay?,
): DesktopDisplay =
    repositoryDisplay?.takeIf {
        it.id == request.id && it.key == request.key
    } ?: boundDisplay?.takeIf {
        it.id == request.id && it.key == request.key
    } ?: throw DesktopDisplayException(
        "DISPLAY_UNAVAILABLE",
        "The selected display is no longer launchable",
    )

internal fun shouldReuseBoundActivity(
    targetDisplayId: Int,
    boundDisplayId: Int?,
): Boolean = targetDisplayId == boundDisplayId

internal fun translateAndroidDesktopLaunchFailure(error: Throwable): DesktopDisplayException =
    when (error) {
        is SecurityException -> DesktopDisplayException(
            "DISPLAY_NOT_ALLOWED",
            "Android denied activity placement on this display",
        )
        is IllegalArgumentException -> DesktopDisplayException(
            "DISPLAY_UNAVAILABLE",
            "The display disconnected during launch",
        )
        is ActivityNotFoundException -> DesktopDisplayException(
            "DISPLAY_NOT_ALLOWED",
            "The native desktop activity could not be opened",
        )
        else -> throw error
    }

internal class BoundDisplayConnectionKeys {
    private var activity: WeakReference<Any>? = null
    private var display: WeakReference<Any>? = null
    private var displayId: Int? = null
    private var sequence = 0L

    fun key(activity: Any, display: Any, displayId: Int): String {
        if (
            this.activity?.get() !== activity ||
            this.display?.get() !== display ||
            this.displayId != displayId
        ) {
            this.activity = WeakReference(activity)
            this.display = WeakReference(display)
            this.displayId = displayId
            sequence++
        }
        return "bound:$displayId:$sequence"
    }

    fun clear(activity: Any) {
        if (this.activity?.get() !== activity) return
        this.activity = null
        display = null
        displayId = null
    }
}

internal class AndroidDesktopPlacementPort(
    resolveLaunch: (DesktopLaunchRequest) -> DesktopLaunchRequest,
    launchLocal: (DesktopLaunchRequest) -> Unit,
    launchOnDisplay: (DesktopLaunchRequest) -> Unit,
    focusLocal: (Long) -> Boolean,
    focusTask: (Long) -> Boolean,
    cancelLocal: (Long) -> Unit,
    cancelTask: (Long) -> Unit,
) : DesktopPlacementPort {
    private val resolveLaunchAction = resolveLaunch
    private val launchLocalAction = launchLocal
    private val launchOnDisplayAction = launchOnDisplay
    private val focusLocalAction = focusLocal
    private val focusTaskAction = focusTask
    private val cancelLocalAction = cancelLocal
    private val cancelTaskAction = cancelTask

    override fun launchLocal(request: DesktopLaunchRequest) {
        launchLocalAction(resolveLaunchAction(request))
    }

    override fun launchOnDisplay(request: DesktopLaunchRequest) {
        launchOnDisplayAction(resolveLaunchAction(request))
    }

    override fun focusLocal(requestId: Long): Boolean = focusLocalAction(requestId)

    override fun focusTask(requestId: Long): Boolean = focusTaskAction(requestId)

    override fun cancelLocal(requestId: Long) {
        cancelLocalAction(requestId)
    }

    override fun cancelTask(requestId: Long) {
        cancelTaskAction(requestId)
    }
}

class DesktopDisplayLauncher {
    companion object {
        fun localIntent(activity: Activity, request: DesktopLaunchRequest): Intent =
            Intent(activity, DesktopActivity::class.java).apply {
                putExtra("displayId", request.display.id)
                putExtra("displayKey", request.display.key)
                putExtra("displayRequestId", request.requestId)
                putExtra("sessionGeneration", request.sessionGeneration)
                putExtra("mode", request.mode)
                putExtra("de", request.desktopEnv)
            }

        fun intent(context: Context, displayId: Int, displayKey: String, requestId: Long): Intent =
            Intent(context, DesktopActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                data = Uri.parse("droiddesk://desktop/$requestId")
                putExtra("displayId", displayId)
                putExtra("displayKey", displayKey)
                putExtra("displayRequestId", requestId)
            }
    }
}

internal class AndroidDesktopDisplayHost(
    private val context: Context,
    private val repository: DisplayRepository,
    private val placementStrategy: DesktopPlacementStrategy,
) : DesktopDisplayHost, DesktopPlacementPort {
    constructor(
        context: Context,
        repository: DisplayRepository,
        environmentPolicy: DisplayEnvironmentPolicy,
    ) : this(
        context,
        repository,
        environmentPolicy.desktopPlacementStrategy(),
    )

    private val handler = Handler(Looper.getMainLooper())
    private val activities = context.getSystemService(ActivityManager::class.java)
    private var boundActivity: WeakReference<Activity>? = null
    private var localDesktop: WeakReference<DesktopActivity>? = null
    private val boundDisplayConnectionKeys = BoundDisplayConnectionKeys()
    private val placementPort = AndroidDesktopPlacementPort(
        resolveLaunch = ::resolveLaunchRequest,
        launchLocal = ::launchLocalActivity,
        launchOnDisplay = ::launchOnDisplayActivity,
        focusLocal = ::focusLocalActivity,
        focusTask = ::focusTaskActivity,
        cancelLocal = ::cancelLocalActivity,
        cancelTask = ::cancelTaskActivities,
    )

    override fun bindActivity(activity: Activity) {
        boundActivity = WeakReference(activity)
        if (activity is DesktopActivity) localDesktop = WeakReference(activity)
    }

    override fun unbindActivity(activity: Activity) {
        if (boundActivity?.get() === activity) {
            boundDisplayConnectionKeys.clear(activity)
            boundActivity = null
        }
        if (localDesktop?.get() === activity) localDesktop = null
    }

    override fun currentDisplay(): DesktopDisplay? =
        boundActivity?.get()?.takeUnless { it.isDestroyed || it.isFinishing }?.let { activity ->
            val display = activityDisplayForSdk(
                Build.VERSION.SDK_INT,
                api30Display = { activity.display },
                decorDisplay = { activity.window.decorView.display },
            ) ?: return@let null
            val product = displayProductInfoForSdk(Build.VERSION.SDK_INT) {
                display.deviceProductInfo
            }
            val mode = display.mode
            DesktopDisplay(
                id = display.displayId,
                key = boundDisplayConnectionKeys.key(activity, display, display.displayId),
                name = display.name,
                isDefault = display.displayId == android.view.Display.DEFAULT_DISPLAY,
                eligible = true,
                reason = null,
                settingsKey = stableDisplaySettingsKey(
                    isDefault = display.displayId == android.view.Display.DEFAULT_DISPLAY,
                    name = display.name,
                    manufacturerPnpId = product?.manufacturerPnpId,
                    productId = product?.productId,
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                ),
            )
        }

    override fun displays() = repository.snapshot()

    override fun defaultProfileKind(): ProfileKind {
        val configuration = context.resources.configuration
        return DisplayProfilePolicy.classify(
            true, configuration.smallestScreenWidthDp,
            configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK,
        )
    }

    override fun sessionRunning() =
        ProotDebianSessionRegistry.isRunning() ||
            ChrootRuntime(context).isRunning() ||
            LinuxRuntime(context).isRunning()

    override fun launch(request: DesktopLaunchRequest) {
        try {
            placementStrategy.launch(this, request)
            Log.i("DesktopDisplay", "Launch request=${request.requestId} display=${request.display.id}")
        } catch (error: Throwable) {
            when (error) {
                is SecurityException ->
                    Log.w("DesktopDisplay", "Android denied desktop placement", error)
                is IllegalArgumentException ->
                    Log.w("DesktopDisplay", "Display disappeared during launch", error)
                is ActivityNotFoundException ->
                    Log.e("DesktopDisplay", "Native desktop activity is unavailable", error)
                else -> throw error
            }
            throw translateAndroidDesktopLaunchFailure(error)
        }
    }

    override fun focus(requestId: Long): Boolean =
        placementStrategy.focus(this, requestId)

    override fun cancelLaunch(requestId: Long) {
        placementStrategy.cancel(this, requestId)
    }

    override fun launchLocal(request: DesktopLaunchRequest) {
        placementPort.launchLocal(request)
    }

    override fun launchOnDisplay(request: DesktopLaunchRequest) {
        placementPort.launchOnDisplay(request)
    }

    override fun focusLocal(requestId: Long): Boolean =
        placementPort.focusLocal(requestId)

    override fun focusTask(requestId: Long): Boolean =
        placementPort.focusTask(requestId)

    override fun cancelLocal(requestId: Long) {
        placementPort.cancelLocal(requestId)
    }

    override fun cancelTask(requestId: Long) {
        placementPort.cancelTask(requestId)
    }

    private fun resolveLaunchRequest(request: DesktopLaunchRequest): DesktopLaunchRequest {
        val boundDisplay = currentDisplay()
        val repositoryDisplay = repository.snapshot().firstOrNull {
            it.key == request.display.key
        }
        val current = resolveLaunchDisplay(request.display, repositoryDisplay, boundDisplay)
        val currentActivityDisplay = boundDisplay?.id
        if (current.id != request.display.id || (!current.eligible && current.id != currentActivityDisplay)) {
            throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display is no longer launchable")
        }
        return request.copy(display = current)
    }

    private fun launchLocalActivity(request: DesktopLaunchRequest) {
        val localActivity = boundActivity?.get()?.takeUnless { it.isDestroyed || it.isFinishing }
            ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app's current window is unavailable")
        if (localActivity.window.decorView.display?.displayId != request.display.id) {
            throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app moved before desktop startup")
        }
        localActivity.startActivity(DesktopDisplayLauncher.localIntent(localActivity, request))
    }

    private fun launchOnDisplayActivity(request: DesktopLaunchRequest) {
        val intent = DesktopDisplayLauncher.intent(
            context,
            request.display.id,
            request.display.key,
            request.requestId,
        ).apply {
            putExtra("sessionGeneration", request.sessionGeneration)
            putExtra("mode", request.mode)
            putExtra("de", request.desktopEnv)
        }
        val activity = boundActivity?.get()?.takeUnless { it.isDestroyed || it.isFinishing }
        val boundDisplayId = currentDisplay()?.id
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(request.display.id)
            .toBundle()
        if (shouldReuseBoundActivity(request.display.id, boundDisplayId)) {
            checkNotNull(activity).startActivity(intent, options)
        } else {
            context.startActivity(intent, options)
        }
    }

    private fun focusLocalActivity(requestId: Long): Boolean {
        val desktop = localDesktop?.get()?.takeUnless { it.isDestroyed || it.isFinishing } ?: return false
        if (desktop.intent.getLongExtra("displayRequestId", -1) != requestId) return false
        val activity = boundActivity?.get()?.takeUnless { it.isDestroyed || it.isFinishing } ?: return false
        if (activity !== desktop) {
            try {
                activity.startActivity(Intent(desktop.intent).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                })
            } catch (error: SecurityException) {
                Log.w("DesktopDisplay", "Android denied focusing the local desktop", error)
                return false
            } catch (error: ActivityNotFoundException) {
                Log.e("DesktopDisplay", "The local desktop activity is unavailable", error)
                return false
            }
        }
        return true
    }

    private fun focusTaskActivity(requestId: Long): Boolean {
        val task = activities.appTasks.firstOrNull {
            it.taskInfo.baseIntent?.data == Uri.parse("droiddesk://desktop/$requestId")
        } ?: return false
        task.moveToFront()
        return true
    }

    private fun cancelLocalActivity(requestId: Long) {
        localDesktop?.get()?.takeIf {
            it.intent.getLongExtra("displayRequestId", -1) == requestId
        }?.finish()
    }

    private fun cancelTaskActivities(requestId: Long) {
        activities.appTasks.filter {
            it.taskInfo.baseIntent?.data == Uri.parse("droiddesk://desktop/$requestId")
        }.forEach { it.finishAndRemoveTask() }
    }

    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit {
        val timeout = Runnable(action)
        handler.postDelayed(timeout, delayMillis)
        return { handler.removeCallbacks(timeout) }
    }

    override fun notifyReturnRequired(error: DesktopDisplayException) {
        Log.e("DesktopDisplay", "Desktop recovery requires user action: ${error.code}: ${error.message}")
        DroidDeskService.notifyReturnRequired(context, error.message ?: "Phone placement was not confirmed")
    }
}

private fun DisplayEnvironmentPolicy.desktopPlacementStrategy(): DesktopPlacementStrategy {
    val mode = if (displayLocalMode) PlacementMode.DISPLAY_LOCAL else PlacementMode.MOVABLE
    val selection = PlacementSelection(
        mode = mode,
        source = if (displayLocalMode) {
            PlacementSelectionSource.DEVICE_QUIRK
        } else {
            PlacementSelectionSource.SAFE_DEFAULT
        },
        allowAutomaticMoves = allowCrossDisplayMoves,
        reason = "Legacy display environment policy selected ${mode.wireName} placement",
    )
    return if (displayLocalMode) {
        DisplayLocalRestartAdapter(selection)
    } else {
        MovableSecondaryDisplayAdapter(selection)
    }
}
