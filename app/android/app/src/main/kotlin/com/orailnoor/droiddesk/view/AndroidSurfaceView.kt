package com.orailnoor.droiddesk.view

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import io.flutter.plugin.platform.PlatformView
import com.termux.x11.MainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.RendererLease
import com.orailnoor.droiddesk.display.RendererClaim
import android.view.ViewGroup
import com.orailnoor.droiddesk.display.DisplaySettingsStore
import com.orailnoor.droiddesk.display.DisplayProfilePolicy
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.PixelSize

internal fun <T> createViewForRendererClaim(
    claim: RendererClaim,
    createPlaceholder: () -> T,
    onAccepted: (RendererLease) -> Unit,
    attachRenderer: (RendererLease) -> T,
): T = when (claim) {
    is RendererClaim.Accepted -> {
        onAccepted(claim.lease)
        attachRenderer(claim.lease)
    }
    RendererClaim.Rejected -> createPlaceholder()
}

internal class RendererCleanup {
    var isReleased = false
        private set

    fun run(
        releaseOwnedInput: () -> Unit,
        completeTeardown: () -> Unit,
    ) {
        if (isReleased) return
        isReleased = true
        releaseOwnedInput()
        completeTeardown()
    }
}

class AndroidSurfaceView(
    val context: Context,
    id: Int,
    creationParams: Map<String?, Any?>?
) : PlatformView {

    private var lorieView: LorieView? = null
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private val coordinator = DesktopDisplayCoordinator.shared
    private val platformView: View
    private var lease: RendererLease? = null
    private val rendererCleanup = RendererCleanup()

    companion object {
        private const val TAG = "AndroidSurfaceView"
    }

    init {
        coordinator.initialize(context.applicationContext)
        platformView = createViewForRendererClaim(
            claim = coordinator.claim(
                creationParams?.get("displayId") as? Int ?: 0,
                ::releaseRenderer,
            ),
            createPlaceholder = { View(context) },
            onAccepted = { lease = it },
            attachRenderer = { rendererLease ->
                X11InputController.configureDisplayScale()
                MainActivity.getInstance()
                    .attachLorieView(context, rendererLease.generation)
                    .also { view ->
                        lorieView = view
                        view.setBackgroundColor(Color.TRANSPARENT)
                        view.setZOrderMediaOverlay(true)
                    }
            },
        )
        val activeLease = lease
        val activeView = lorieView
        if (activeLease != null && activeView != null) {
            x11ServiceClient = X11ServiceClient(
                context = context,
                onConnected = { serverPid, connectionFd, logcatFd ->
                    if (!coordinator.isCurrent(activeLease)) {
                        connectionFd.close()
                        logcatFd?.close()
                        return@X11ServiceClient
                    }
                    try {
                        if (!LorieView.connected() || X11ServiceClient.connectedServerPid != serverPid) {
                            LorieView.connect(connectionFd.detachFd())
                            logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                            X11ServiceClient.connectedServerPid = serverPid
                            activeView.onXConnectionChanged()
                        } else {
                            connectionFd.close()
                            logcatFd?.close()
                        }
                        val store = DisplaySettingsStore(context)
                        val settings = store.load(ProfileKind.PHONE)
                            ?: DisplayProfilePolicy.defaults(ProfileKind.PHONE)
                        inputController = X11InputController(
                            activeView, activeLease.generation, settings.inputMode,
                            onModeChanged = { mode ->
                                store.save(ProfileKind.PHONE, settings.copy(inputMode = mode))
                            },
                            onViewportChanged = { width, height, _ ->
                                if (width > 0 && height > 0) {
                                    coordinator.reportGeometry(
                                        activeLease,
                                        ProfileKind.PHONE,
                                        PixelSize(width, height),
                                    )
                                }
                            },
                        )
                        Log.i(TAG, "LorieView attached to X11 service pid=$serverPid")
                    } catch (error: Throwable) {
                        connectionFd.close()
                        logcatFd?.close()
                        Log.e(TAG, "Failed to attach LorieView", error)
                    }
                },
                onError = { message, error -> Log.e(TAG, message, error) },
            )
            // Always bind so a stale native connected flag cannot hide a restarted
            // X11 service process.
            x11ServiceClient?.connect()
        }
    }

    override fun getView(): View {
        return platformView
    }

    override fun dispose() {
        lease?.let(coordinator::release)
        releaseRenderer()
    }

    private fun releaseRenderer() {
        rendererCleanup.run(
            releaseOwnedInput = {
                inputController?.dispose()
                inputController = null
            },
            completeTeardown = {
                x11ServiceClient?.disconnect()
                x11ServiceClient = null
                lease?.let { MainActivity.getInstance().releaseLorieView(it.generation) }
                lorieView?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                }
                lorieView = null
            },
        )
    }
}
