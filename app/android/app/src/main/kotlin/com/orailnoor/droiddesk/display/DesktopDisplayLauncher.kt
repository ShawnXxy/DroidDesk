package com.orailnoor.droiddesk.display

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import android.view.Display
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.placement.AcceptedDisplayHandoffAdmission
import com.orailnoor.droiddesk.display.placement.AcceptedDisplayHandoffEvidence
import com.orailnoor.droiddesk.display.placement.AcceptedDisplayHandoffEvidenceSnapshot
import com.orailnoor.droiddesk.display.placement.AcceptedDisplayHandoffRegistry
import com.orailnoor.droiddesk.display.placement.DesktopPlacementPort
import com.orailnoor.droiddesk.display.placement.DesktopPlacementStrategy
import com.orailnoor.droiddesk.display.placement.DisplayConnectionIdentity
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

internal fun shouldReuseBoundActivity(targetDisplayId: Int, boundDisplayId: Int?): Boolean =
    targetDisplayId == boundDisplayId

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

internal fun acceptsSyntheticDisplayHandoff(
    registry: AcceptedDisplayHandoffRegistry, owner: Any,
    displayId: Int, connectionKey: String,
    currentEvidence: AcceptedDisplayHandoffEvidence?,
    previousEvidence: AcceptedDisplayHandoffEvidence?,
): Boolean =
    registry.admit(
        owner = owner,
        displayId = displayId,
        connectionKey = connectionKey,
        evidence = AcceptedDisplayHandoffEvidenceSnapshot(
            current = currentEvidence?.takeIf { it.displayId == displayId },
            previous = previousEvidence?.takeIf { it.displayId == displayId },
        ),
    ) is AcceptedDisplayHandoffAdmission.Accepted

internal fun resolveObservedDisplayConnection(
    registry: AcceptedDisplayHandoffRegistry, owner: Any,
    requestedDisplayId: Int, requestedConnectionKey: String,
    evidence: AcceptedDisplayHandoffEvidence,
): DisplayConnectionIdentity? {
    if (evidence.displayId != requestedDisplayId) return registry.resolve(evidence)
    return (
        registry.admit(
            owner,
            requestedDisplayId,
            requestedConnectionKey,
            AcceptedDisplayHandoffEvidenceSnapshot(evidence, null),
        )
            as? AcceptedDisplayHandoffAdmission.Accepted
        )?.connection
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
    private data class BoundDisplayEvidence(
        val display: Display,
        val settingsKey: String,
    ) {
        val connectionEvidence = AcceptedDisplayHandoffEvidence(
            displayId = display.displayId,
            fallbackIdentity = settingsKey,
        )
    }

    private data class SyntheticHandoffRequest(
        val displayId: Int,
        val connectionKey: String,
    )

    constructor(
        context: Context,
        repository: DisplayRepository,
        environmentPolicy: DisplayEnvironmentPolicy,
    ) : this(context, repository, environmentPolicy.desktopPlacementStrategy())

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val activities = context.getSystemService(ActivityManager::class.java)
    private val handoffs = AcceptedDisplayHandoffRegistry()
    private var boundActivity: WeakReference<Activity>? = null
    private var localDesktop: WeakReference<DesktopActivity>? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = handoffs.displayAdded(displayId)
        override fun onDisplayChanged(displayId: Int) = handoffs.displayChanged(displayId)
        override fun onDisplayRemoved(displayId: Int) = handoffs.displayRemoved(displayId)
    }

    init {
        displayManager.registerDisplayListener(displayListener, handler)
    }

    override fun bindActivity(activity: Activity): Boolean {
        val previousEvidence = activeBoundActivity()
            ?.let(::boundDisplayEvidence)
            ?.connectionEvidence
        if (activity is DesktopActivity) {
            syntheticHandoffRequest(activity)?.let { request ->
                val accepted = acceptsSyntheticDisplayHandoff(
                    registry = handoffs,
                    owner = activity,
                    displayId = request.displayId,
                    connectionKey = request.connectionKey,
                    currentEvidence = boundDisplayEvidence(activity)?.connectionEvidence,
                    previousEvidence = previousEvidence,
                )
                if (!accepted) {
                    handoffs.unbind(activity)
                    if (boundActivity?.get() === activity) boundActivity = null
                    if (localDesktop?.get() === activity) localDesktop = null
                    Log.w("DesktopDisplay", "Rejecting invalid synthetic display binding")
                    return false
                }
            }
        }
        boundActivity = WeakReference(activity)
        if (activity is DesktopActivity) localDesktop = WeakReference(activity)
        return true
    }

    override fun unbindActivity(activity: Activity) {
        if (boundActivity?.get() === activity) boundActivity = null
        if (localDesktop?.get() === activity) localDesktop = null
        handoffs.unbind(activity)
    }

    override fun currentDisplay(): DesktopDisplay? {
        val activity = activeBoundActivity() ?: return null
        val evidence = boundDisplayEvidence(activity) ?: return null
        val connection = connectionFor(activity, evidence) ?: return null
        val display = evidence.display
        return DesktopDisplay(
            id = display.displayId,
            key = connection.connectionKey,
            name = display.name,
            isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            eligible = true,
            reason = null,
            settingsKey = evidence.settingsKey,
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
        } catch (error: SecurityException) {
            Log.w("DesktopDisplay", "Android denied desktop placement", error)
            throw translateAndroidDesktopLaunchFailure(error)
        } catch (error: IllegalArgumentException) {
            Log.w("DesktopDisplay", "Display disappeared during launch", error)
            throw translateAndroidDesktopLaunchFailure(error)
        } catch (error: ActivityNotFoundException) {
            Log.e("DesktopDisplay", "Native desktop activity is unavailable", error)
            throw translateAndroidDesktopLaunchFailure(error)
        }
    }

    override fun focus(requestId: Long): Boolean =
        placementStrategy.focus(this, requestId)

    override fun cancelLaunch(requestId: Long) = placementStrategy.cancel(this, requestId)

    override fun launchLocal(request: DesktopLaunchRequest) =
        launchLocalActivity(resolveLaunchRequest(request))

    override fun launchOnDisplay(request: DesktopLaunchRequest) =
        launchOnDisplayActivity(resolveLaunchRequest(request))

    override fun focusLocal(requestId: Long): Boolean = focusLocalActivity(requestId)

    override fun focusTask(requestId: Long): Boolean = focusTaskActivity(requestId)

    override fun cancelLocal(requestId: Long) = cancelLocalActivity(requestId)

    override fun cancelTask(requestId: Long) = cancelTaskActivities(requestId)

    private fun activeBoundActivity(): Activity? =
        boundActivity?.get()?.takeUnless { it.isDestroyed || it.isFinishing }

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

    private fun boundDisplayEvidence(activity: Activity): BoundDisplayEvidence? {
        val display = activityDisplayForSdk(
            Build.VERSION.SDK_INT,
            api30Display = { activity.display },
            decorDisplay = { activity.window.decorView.display },
        ) ?: return null
        val productIdentity = displayProductIdentityForSdk(Build.VERSION.SDK_INT, display)
        val mode = display.mode
        return BoundDisplayEvidence(
            display = display,
            settingsKey = stableDisplaySettingsKey(
                isDefault = display.displayId == Display.DEFAULT_DISPLAY,
                name = display.name,
                manufacturerPnpId = productIdentity?.manufacturerPnpId,
                productId = productIdentity?.productId,
                width = mode.physicalWidth,
                height = mode.physicalHeight,
            ),
        )
    }

    private fun syntheticHandoffRequest(activity: Activity): SyntheticHandoffRequest? {
        if (activity !is DesktopActivity) return null
        val displayId = activity.intent.getIntExtra("displayId", Display.INVALID_DISPLAY)
        val connectionKey = activity.intent.getStringExtra("displayKey")
        return if (
            displayId != Display.INVALID_DISPLAY &&
            connectionKey?.startsWith("bound:$displayId:") == true
        ) {
            SyntheticHandoffRequest(displayId, connectionKey)
        } else {
            null
        }
    }

    private fun connectionFor(
        activity: Activity,
        evidence: BoundDisplayEvidence,
    ): DisplayConnectionIdentity? {
        val request = syntheticHandoffRequest(activity)
            ?: return handoffs.resolve(evidence.connectionEvidence)
        return resolveObservedDisplayConnection(
            registry = handoffs,
            owner = activity,
            requestedDisplayId = request.displayId,
            requestedConnectionKey = request.connectionKey,
            evidence = evidence.connectionEvidence,
        )
    }

    private fun launchLocalActivity(request: DesktopLaunchRequest) {
        val localActivity = activeBoundActivity()
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
        val activity = activeBoundActivity()
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
        val activity = activeBoundActivity() ?: return false
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
