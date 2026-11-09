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
    private val environmentPolicy: DisplayEnvironmentPolicy,
) : DesktopDisplayHost {
    private val handler = Handler(Looper.getMainLooper())
    private val activities = context.getSystemService(ActivityManager::class.java)
    private var boundActivity: WeakReference<Activity>? = null
    private var localDesktop: WeakReference<DesktopActivity>? = null

    override fun bindActivity(activity: Activity): Boolean {
        boundActivity = WeakReference(activity)
        if (activity is DesktopActivity) localDesktop = WeakReference(activity)
        return true
    }

    override fun unbindActivity(activity: Activity) {
        if (boundActivity?.get() === activity) boundActivity = null
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
                key = "bound:${display.displayId}",
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
        val activity = boundActivity?.get()?.takeUnless { it.isDestroyed || it.isFinishing }
        val boundDisplay = currentDisplay()
        val repositoryDisplay = repository.snapshot().firstOrNull {
            it.key == request.display.key
        }
        val current = resolveLaunchDisplay(request.display, repositoryDisplay, boundDisplay)
        val currentActivityDisplay = boundDisplay?.id
        if (current.id != request.display.id || (!current.eligible && current.id != currentActivityDisplay)) {
            throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The selected display is no longer launchable")
        }
        try {
            if (environmentPolicy.displayLocalMode) {
                val localActivity = activity
                    ?: throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app's current window is unavailable")
                if (localActivity.window.decorView.display?.displayId != current.id) {
                    throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The app moved before desktop startup")
                }
                localActivity.startActivity(DesktopDisplayLauncher.localIntent(localActivity, request))
            } else {
                val intent = DesktopDisplayLauncher.intent(context, current.id, current.key, request.requestId).apply {
                    putExtra("sessionGeneration", request.sessionGeneration)
                    putExtra("mode", request.mode)
                    putExtra("de", request.desktopEnv)
                }
                if (currentActivityDisplay == current.id) {
                    checkNotNull(activity).startActivity(
                        intent,
                        ActivityOptions.makeBasic().setLaunchDisplayId(current.id).toBundle(),
                    )
                } else {
                    context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(current.id).toBundle())
                }
            }
            Log.i("DesktopDisplay", "Launch request=${request.requestId} display=${current.id}")
        } catch (error: SecurityException) {
            Log.w("DesktopDisplay", "Android denied desktop placement", error)
            throw DesktopDisplayException("DISPLAY_NOT_ALLOWED", "Android denied activity placement on this display")
        } catch (error: IllegalArgumentException) {
            Log.w("DesktopDisplay", "Display disappeared during launch", error)
            throw DesktopDisplayException("DISPLAY_UNAVAILABLE", "The display disconnected during launch")
        } catch (error: ActivityNotFoundException) {
            Log.e("DesktopDisplay", "Native desktop activity is unavailable", error)
            throw DesktopDisplayException("DISPLAY_NOT_ALLOWED", "The native desktop activity could not be opened")
        }
    }

    override fun focus(requestId: Long): Boolean {
        if (environmentPolicy.displayLocalMode) {
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
        val task = activities.appTasks.firstOrNull {
            it.taskInfo.baseIntent?.data == Uri.parse("droiddesk://desktop/$requestId")
        } ?: return false
        task.moveToFront()
        return true
    }

    override fun cancelLaunch(requestId: Long) {
        if (environmentPolicy.displayLocalMode) {
            localDesktop?.get()?.takeIf {
                it.intent.getLongExtra("displayRequestId", -1) == requestId
            }?.finish()
            return
        }
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
