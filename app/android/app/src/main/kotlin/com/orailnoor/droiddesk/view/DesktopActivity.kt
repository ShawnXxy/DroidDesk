package com.orailnoor.droiddesk.view

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.util.Log
import android.widget.Toast
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.view.Gravity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Matrix
import android.hardware.input.InputManager
import com.orailnoor.droiddesk.R
import com.orailnoor.droiddesk.service.DroidDeskService
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.StartRequest
import com.orailnoor.droiddesk.runtime.createLinuxEnvironmentManager
import com.orailnoor.droiddesk.runtime.XfceMobileProfile
import com.orailnoor.droiddesk.runtime.XfceDisplayProfile
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController
import com.orailnoor.droiddesk.x11.ExternalDesktopInputBridge
import com.orailnoor.droiddesk.x11.ExternalDesktopInputTarget
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.*
import com.orailnoor.droiddesk.display.input.*
import com.orailnoor.droiddesk.display.RendererLease
import com.orailnoor.droiddesk.display.RendererClaim
import com.orailnoor.droiddesk.display.DisplayProfile
import com.orailnoor.droiddesk.display.DisplayProfilePolicy
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RenderPolicy
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.PixelInsets
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.display.capability.*
import com.orailnoor.droiddesk.display.refresh.DisplayRefreshPolicy
import com.orailnoor.droiddesk.display.refresh.RefreshRequest

internal fun displayModeEvidenceOrNull(
    modeId: Int,
    width: Int,
    height: Int,
    refreshRate: Float,
): DisplayModeEvidence? {
    if (modeId <= 0 || width <= 0 || height <= 0 || !refreshRate.isFinite() || refreshRate <= 0f) {
        return null
    }
    return DisplayModeEvidence(
        modeId = modeId,
        width = width,
        height = height,
        refreshRate = refreshRate,
    )
}

internal fun applyRefreshRequest(
    request: RefreshRequest,
    updateWindowRefresh: (preferredRefreshRate: Float, preferredDisplayModeId: Int) -> Unit,
) {
    if (request.preferredRefreshRate == null && request.preferredModeId == null) {
        return
    }
    updateWindowRefresh(
        request.preferredRefreshRate ?: 0f,
        request.preferredModeId ?: 0,
    )
}

internal fun sanitizeScrollFactor(factor: Float, fallback: Float = 64f): Float = if (factor.isFinite() && factor > 0f) factor else fallback

internal fun fallbackInputCapabilities(hasTouchscreen: Boolean, hasExternalPointer: Boolean, hasMouse: Boolean, hasTouchpad: Boolean, hasKeyboard: Boolean, hasStylus: Boolean) = InputCapabilities(
    if (hasTouchscreen) TouchEvidence.UNKNOWN_ASSOCIATION else TouchEvidence.NONE,
    if (hasExternalPointer) PointerAssociationEvidence.UNKNOWN else PointerAssociationEvidence.NO_EXTERNAL_POINTER, hasMouse, hasTouchpad, hasKeyboard, hasStylus,
)

internal fun effectiveInputCapabilities(isDefaultDisplay: Boolean, hasInternalTouchscreen: Boolean, directTouchObserved: Boolean, rawInput: InputCapabilities): InputCapabilities =
    if (directTouchObserved || (isDefaultDisplay && hasInternalTouchscreen && rawInput.touch == TouchEvidence.UNKNOWN_ASSOCIATION)) rawInput.copy(touch = TouchEvidence.CURRENT_DISPLAY) else rawInput

internal fun resolvePresentationClass(isDefaultDisplay: Boolean, profileKind: ProfileKind, touch: TouchEvidence): PresentationClass = when {
    !isDefaultDisplay -> if (touch == TouchEvidence.CURRENT_DISPLAY) PresentationClass.EXTERNAL_TOUCH else PresentationClass.EXTERNAL_POINTER
    profileKind == ProfileKind.PHONE -> PresentationClass.PHONE_TOUCH
    profileKind == ProfileKind.WIDE || touch == TouchEvidence.CURRENT_DISPLAY -> PresentationClass.LARGE_TOUCH
    else -> PresentationClass.EXTERNAL_POINTER
}

internal data class DesktopInputContext(
    val displayId: Int, val environment: DisplayEnvironment, val profileKind: ProfileKind, val desktopUiMode: Boolean, val savedSettings: DisplaySettings?,
    val rawInput: InputCapabilities, val hasInternalTouchscreen: Boolean, val hasExternalTouchscreen: Boolean, val horizontalScrollFactor: Float, val verticalScrollFactor: Float,
)

internal class DesktopInputRouter(
    private val readContext: () -> DesktopInputContext?, private val applyRenderer: (InputPolicyDecision) -> ExternalDesktopInputTarget?,
    private val openRelay: () -> Unit, private val closeRelay: () -> Unit,
) {
    private var promotedTouchDisplayId: Int? = null
    fun refresh(clearUnbackedTouch: Boolean = false): InputPolicyDecision? {
        val ctx = readContext() ?: return null
        val isDefault = ctx.displayId == android.view.Display.DEFAULT_DISPLAY
        if (promotedTouchDisplayId != ctx.displayId || (clearUnbackedTouch && (ctx.rawInput.touch == TouchEvidence.NONE || (!isDefault && !ctx.hasExternalTouchscreen && ctx.rawInput.touch != TouchEvidence.CURRENT_DISPLAY)))) promotedTouchDisplayId = null
        val caps = effectiveInputCapabilities(isDefault, ctx.hasInternalTouchscreen, promotedTouchDisplayId == ctx.displayId, ctx.rawInput)
        val decision = DesktopInputPolicy.resolve(resolvePresentationClass(isDefault, ctx.profileKind, caps.touch), ctx.environment, caps, ctx.savedSettings?.inputMode, ctx.savedSettings?.inputRoute ?: ExternalInputRoutePreference.AUTOMATIC, ctx.desktopUiMode, sanitizeScrollFactor(ctx.horizontalScrollFactor), sanitizeScrollFactor(ctx.verticalScrollFactor))
        applyRenderer(decision)?.let { if (decision.externalInputRoute == ExternalInputRoute.PHONE_RELAY) { ExternalDesktopInputBridge.attach(it, decision.relayPointerCapture); openRelay() } else { ExternalDesktopInputBridge.detach(it); closeRelay() } }
        return decision
    }
    fun promoteDirectTouch(displayId: Int): Boolean = (promotedTouchDisplayId != displayId).also { if (it) promotedTouchDisplayId = displayId }
    fun release(target: ExternalDesktopInputTarget?, finishRelay: Boolean = false) { promotedTouchDisplayId = null; if ((target != null && ExternalDesktopInputBridge.detach(target)) || finishRelay) closeRelay() }
}

internal fun continueAfterActivityBinding(
    bindActivity: () -> Boolean,
    rejectAttachment: () -> Unit,
    finishActivity: () -> Unit,
    continueLifecycle: () -> Unit,
) {
    if (bindActivity()) {
        continueLifecycle()
        return
    }
    rejectAttachment()
    finishActivity()
}

internal fun continueAfterRendererClaim(
    claim: RendererClaim,
    finishActivity: () -> Unit,
    continueWithLease: (RendererLease) -> Unit,
) {
    when (claim) {
        is RendererClaim.Accepted -> continueWithLease(claim.lease)
        RendererClaim.Rejected -> finishActivity()
    }
}

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private var connectionRequested = false
    private var shouldStartSession = false
    private var sessionStartPending = false
    private var sessionMode = "termux"
    private var desktopEnv = "xfce4"
    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var environmentManager: com.orailnoor.droiddesk.runtime.LinuxEnvironmentManager
    private lateinit var placeholder: FrameLayout
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private val displayCoordinator = DesktopDisplayCoordinator.shared
    private val evidenceProbe by lazy { AndroidPresentationEvidenceProbe(applicationContext, DisplayRepository(applicationContext)) }
    private var currentInputContext: DesktopInputContext? = null
    private val inputRouter = DesktopInputRouter({ currentInputContext }, { d -> lorieView?.reloadPreferences(TermuxMainActivity.getPrefs().apply { pointerCapture.put(d.rendererPointerCapture) }); inputController?.applyDecision(d); inputModeButton?.let(::showInputMode); inputController }, { ExternalInputRelayActivity.open(this) }, ExternalInputRelayActivity::finishIfOpen)
    private var attachingDesktopInput = false
    private var policyOnlyRefresh = false
    private var rendererLease: RendererLease? = null
    private val rendererCleanup = RendererCleanup()
    private var inputDeviceListenerRegistered = false
    private var pendingDeviceRemoved = false
    private val inputDeviceHandler = Handler(Looper.getMainLooper())
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = scheduleDesktopInputRefresh()
        override fun onInputDeviceRemoved(deviceId: Int) = scheduleDesktopInputRefresh(true)
        override fun onInputDeviceChanged(deviceId: Int) = scheduleDesktopInputRefresh()
    }
    private val refreshDesktopInput = Runnable {
        if (!rendererCleanup.isReleased && isCurrentRenderer()) { if (inputController != null) refreshInputPolicyOnly() else attachDesktopInput() }
    }
    private var displayRequestId = 0L
    private var sessionEpoch = 0L
    private var readinessThread: Thread? = null
    private var awaitingSessionStart = false
    private var currentProfile: DisplayProfile? = null
    private var desktopReady = false
    private var appliedUiProfile: DisplayProfile? = null
    private var applyingUiProfile: DisplayProfile? = null
    private var failedUiProfile: DisplayProfile? = null
    private var explicitProfileRetry = false
    private var profileRevision = 0L
    private val profileCompletions = mutableListOf<(Result<Unit>) -> Unit>()
    private var currentInsets = PixelInsets(0, 0, 0, 0)
    private var lastWindowInsets: WindowInsets? = null
    private var viewportYScale = 1f
    private var geometryQueued = false
    private var geometryListener: View.OnLayoutChangeListener? = null
    private val geometryUpdate = Runnable {
        geometryQueued = false
        applyWindowGeometry()
    }
    private var inputModeButton: Button? = null
    private var controlOverlay: LinearLayout? = null
    private var collapsedControl: View? = null
    private val collapsedFadeHandler = Handler(Looper.getMainLooper())
    private val fadeCollapsedControl = Runnable {
        collapsedControl?.animate()?.alpha(COLLAPSED_IDLE_ALPHA)?.setDuration(400)?.start()
    }
    private var extraKeysBar: ExtraKeysBar? = null
    private var logoAnimator: FloatingLogoAnimator? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var loadingOverlay: FrameLayout? = null
    private var loadingStatus: TextView? = null
    private var loadingEstimate: TextView? = null
    private var desktopRevealed = false
    private val loadingMessageHandler = Handler(Looper.getMainLooper())
    private var loadingMessageIndex = 0
    private var loadingStartedAt = 0L
    private var estimatedLoadingSeconds = 30
    private val loadingMessages = listOf(
        "Waking up your portable Linux workspace",
        "Connecting Android to your Linux desktop",
        "Starting the desktop engine and services",
        "Loading your apps, icons, and shortcuts",
        "Polishing your panels, wallpaper, and workspace",
        "Almost ready for your next big idea",
    )
    private val loadingMessageTicker = object : Runnable {
        override fun run() {
            val status = loadingStatus ?: return
            val ticker = this
            status.animate().alpha(0f).setDuration(180).withEndAction {
                loadingMessageIndex = (loadingMessageIndex + 1) % loadingMessages.size
                status.text = loadingMessages[loadingMessageIndex]
                status.animate().alpha(1f).setDuration(260).withEndAction {
                    if (!desktopRevealed) {
                        loadingMessageHandler.postDelayed(ticker, 5_000)
                    }
                }.start()
            }.start()
        }
    }
    private val loadingEstimateTicker = object : Runnable {
        override fun run() {
            val estimate = loadingEstimate ?: return
            val elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - loadingStartedAt) / 1_000).toInt()
            val remaining = (estimatedLoadingSeconds - elapsedSeconds).coerceAtLeast(0)
            estimate.text = if (remaining > 0) {
                "About $remaining seconds remaining"
            } else {
                "Finishing up…"
            }
            estimate.contentDescription = estimate.text
            if (!desktopRevealed) loadingMessageHandler.postDelayed(this, 1_000)
        }
    }

    companion object {
        private const val TAG = "DesktopActivity"
        private const val COLLAPSED_IDLE_ALPHA = 0.4f
        private const val COLLAPSED_FADE_DELAY_MS = 3_000L
        private var current: java.lang.ref.WeakReference<DesktopActivity>? = null

        fun isOpen(): Boolean = current?.get()?.isFinishing == false

        /** Closes the desktop screen, e.g. when the session is stopped from the notification. */
        fun finishIfOpen() {
            Handler(Looper.getMainLooper()).post {
                DesktopDisplayCoordinator.shared.stop()
                current?.get()?.finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayCoordinator.initialize(applicationContext)
        val processRuntime = com.orailnoor.droiddesk.runtime
            .ProcessLinuxEnvironmentRegistry.get(applicationContext)
        linuxRuntime = processRuntime.linuxRuntime
        chrootRuntime = processRuntime.chrootRuntime
        environmentManager = processRuntime.environmentManager
        displayRequestId = intent.getLongExtra("displayRequestId", 0L)
        sessionEpoch = intent.getLongExtra("sessionGeneration", 0L)
        val displayKey = intent.getStringExtra("displayKey")
        val actualDisplay = windowManager.defaultDisplay.displayId
        if (displayKey == null || !displayCoordinator.canAttach(displayRequestId, actualDisplay, displayKey, sessionEpoch)) {
            displayCoordinator.rejectAttachment(displayRequestId)
            finish()
            return
        }
        continueAfterActivityBinding(
            bindActivity = { displayCoordinator.bindActivity(this) },
            rejectAttachment = { displayCoordinator.rejectAttachment(displayRequestId) },
            finishActivity = { finish() },
            continueLifecycle = {
                current = java.lang.ref.WeakReference(this)
                window.decorView.post {
                    if (!isDestroyed && !isFinishing) {
                        displayCoordinator.observeActivityEnvironment(this, sessionEpoch)
                    }
                }
                sessionMode = intent.getStringExtra("mode") ?: displayCoordinator.sessionMode
                desktopEnv = intent.getStringExtra("de") ?: displayCoordinator.desktopEnv
                if (sessionMode == "proot") {
                    applyProotRefreshPolicy()
                }
                estimatedLoadingSeconds = if (shouldStartSession) 30 else 10

                requestWindowFeature(Window.FEATURE_NO_TITLE)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                placeholder = FrameLayout(this)
                placeholder.setBackgroundColor(Color.BLACK)
                setContentView(placeholder)
                showLoadingOverlay()
                // Some Android/LineageOS builds throw from PhoneWindow.getInsetsController
                // until a decor view has been created by setContentView().
                enableImmersiveMode()

                Log.i(TAG, "DesktopActivity created mode=$sessionMode startSession=$shouldStartSession")
                setupLorieView()
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun applyProotRefreshPolicy() {
        val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display ?: windowManager.defaultDisplay
        } else {
            windowManager.defaultDisplay
        }
        val currentEvidence = activeDisplay?.mode?.toEvidenceOrNull()
        val supportedEvidence = activeDisplay?.supportedModes
            ?.mapNotNull { it?.toEvidenceOrNull() }
            .orEmpty()
        val request = DisplayRefreshPolicy.resolve(currentEvidence, supportedEvidence)
        applyRefreshRequest(request) { rate, modeId ->
            window.attributes = window.attributes.apply {
                preferredRefreshRate = rate
                preferredDisplayModeId = modeId
            }
        }
        Log.i(TAG, "PRoot refresh policy: ${request.reason}")
    }

    private fun android.view.Display.Mode.toEvidenceOrNull(): DisplayModeEvidence? =
        displayModeEvidenceOrNull(modeId, physicalWidth, physicalHeight, refreshRate)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (isCurrentRenderer()) {
            enableImmersiveMode()
            inputController?.reacquireFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (displayCoordinator.isStopped) {
            finish()
            return
        }
        continueAfterActivityBinding(
            bindActivity = { displayCoordinator.bindActivity(this) },
            rejectAttachment = { displayCoordinator.rejectAttachment(displayRequestId) },
            finishActivity = { finish() },
            continueLifecycle = {
                window.decorView.post {
                    if (!isDestroyed && !isFinishing) {
                        displayCoordinator.observeActivityEnvironment(this, sessionEpoch)
                    }
                }
                enableImmersiveMode()
                scheduleGeometry()
                // Restored windows can retain focus without another focus callback.
                if (hasWindowFocus() && isCurrentRenderer()) inputController?.reacquireFocus()
            },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleGeometry()
        if (inputController != null && isCurrentRenderer()) scheduleDesktopInputRefresh(false)
    }

    override fun onPause() {
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun setupLorieView() {
        Log.i(TAG, "Setting up LorieView")
        if (displayCoordinator.isStopped) {
            finish()
            return
        }
        val claim = displayCoordinator.claim(windowManager.defaultDisplay.displayId) {
            detachRenderer()
        }
        var acceptedLease: RendererLease? = null
        continueAfterRendererClaim(
            claim = claim,
            finishActivity = { finish() },
            continueWithLease = { acceptedLease = it },
        )
        val lease = acceptedLease ?: return
        rendererLease = lease
        lorieView = TermuxMainActivity.getInstance().attachLorieView(this, lease.generation)
        displayCoordinator.registerActivity(
            lease, displayRequestId,
            actualDisplay = { lorieView?.display?.displayId ?: windowManager.defaultDisplay.displayId },
        ) { if (displayCoordinator.allowsManualMoves) finishAndRemoveTask() else finish() }
        displayCoordinator.setProfileRefresher(lease) { completion ->
            failedUiProfile = null
            explicitProfileRetry = true
            profileCompletions += completion
            scheduleGeometry()
            placeholder.post {
                if (!isDestroyed && !isFinishing && isCurrentRenderer()) {
                    attachDesktopInput()
                }
            }
        }

        // Keep Android overlay controls above the X11 SurfaceView.
        lorieView!!.setZOrderOnTop(false)
        placeholder.setBackgroundColor(Color.BLACK)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        placeholder.addView(lorieView, params)
        geometryListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleGeometry()
        }.also { lorieView?.addOnLayoutChangeListener(it) }
        placeholder.setOnApplyWindowInsetsListener { view, insets ->
            lastWindowInsets = insets
            updateExtraKeysBar(insets)
            scheduleGeometry()
            view.onApplyWindowInsets(insets)
        }
        placeholder.requestApplyInsets()
        scheduleGeometry()
        loadingOverlay?.bringToFront()
        Log.i(TAG, "LorieView added to placeholder")

        // Start X server only after the Surface is actually created/changed.
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!isCurrentRenderer()) return
                scheduleGeometry()
                Log.i(TAG, "LorieView surfaceChanged ${width}x${height}")
                synchronized(this@DesktopActivity) {
                    if (!connectionRequested) {
                        connectionRequested = true
                        connectToX11Service()
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceDestroyed")
            }
        }.also { lorieView!!.holder.addCallback(it) }
    }

    private fun connectToX11Service() {
        x11ServiceClient = X11ServiceClient(
            context = this,
            onConnected = { serverPid, connectionFd, logcatFd ->
                if (!isCurrentRenderer()) {
                    connectionFd.close()
                    logcatFd?.close()
                    return@X11ServiceClient
                }
                try {
                    if (!LorieView.connected() || X11ServiceClient.connectedServerPid != serverPid) {
                        LorieView.connect(connectionFd.detachFd())
                        logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                        X11ServiceClient.connectedServerPid = serverPid
                        lorieView?.onXConnectionChanged()
                        Log.i(TAG, "LorieView connected to X11 service pid=$serverPid")
                    } else {
                        connectionFd.close()
                        logcatFd?.close()
                        Log.i(TAG, "LorieView reused live X11 service pid=$serverPid")
                    }

                    attachDesktopInput()
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    showX11Error("Failed to attach LorieView to the X11 service", error)
                }
            },
            onError = ::showX11Error,
        ).also { it.connect() }
    }

    private fun refreshInputPolicyOnly() { policyOnlyRefresh = true; try { attachDesktopInput() } finally { policyOnlyRefresh = false } }

    private fun attachDesktopInput() {
        val lease = rendererLease ?: return
        if (!isCurrentRenderer()) return
        lorieView?.display?.displayId?.takeIf { it != lease.displayId }?.let { displayCoordinator.observeActivityEnvironment(this, sessionEpoch); displayCoordinator.onActivityMoved(lease, it); return }
        val policyOnly = policyOnlyRefresh
        val clearUnbackedTouch = pendingDeviceRemoved.also { pendingDeviceRemoved = false }
        if (!policyOnly) { updateInputDeviceListener(); attachingDesktopInput = true; try { applyWindowGeometry() } finally { attachingDesktopInput = false } }
        val environment = displayCoordinator.environmentForDisplay(lease.displayId) ?: return
        val profileKind = currentProfileKind()
        val devs = android.view.InputDevice.getDeviceIds().asSequence().mapNotNull { android.view.InputDevice.getDevice(it) }.toList()
        val rawInput = runCatching { evidenceProbe.presentationContext(lease.displayId, PixelSize(1, 1)).input }.getOrElse {
            fallbackInputCapabilities(devs.any { it.supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) }, devs.any { it.isExternal && (it.supportsSource(android.view.InputDevice.SOURCE_MOUSE) || it.supportsSource(android.view.InputDevice.SOURCE_TOUCHPAD)) }, devs.any { it.supportsSource(android.view.InputDevice.SOURCE_MOUSE) }, devs.any { it.supportsSource(android.view.InputDevice.SOURCE_TOUCHPAD) }, devs.any { !it.isVirtual && it.supportsSource(android.view.InputDevice.SOURCE_KEYBOARD) && it.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC }, devs.any { it.supportsSource(android.view.InputDevice.SOURCE_STYLUS) })
        }
        val vc = android.view.ViewConfiguration.get(this); val fb = sanitizeScrollFactor(64f * resources.displayMetrics.density)
        currentInputContext = DesktopInputContext(lease.displayId, environment, profileKind, resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK, displayCoordinator.loadSettings(profileKind, lease.displayId), rawInput, devs.any { !it.isExternal && it.supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) }, devs.any { it.isExternal && it.supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) }, sanitizeScrollFactor(vc.scaledHorizontalScrollFactor, fb), sanitizeScrollFactor(vc.scaledVerticalScrollFactor, fb))
        if (inputController == null) {
            inputController = X11InputController(
                lorieView!!, lease.generation, 1,
                onModeChanged = { mode ->
                    val kind = currentProfileKind()
                    val saved = displayCoordinator.loadSettings(kind, lease.displayId)
                        ?: DisplayProfilePolicy.defaults(kind)
                    displayCoordinator.saveProfileSettings(kind, saved.copy(inputMode = mode))
                    refreshInputPolicyOnly()
                },
                onThreeFingerTap = ::toggleKeyboard,
                onViewportChanged = { _, _, transform ->
                    val matrix = FloatArray(9)
                    transform.getValues(matrix)
                    val scale = kotlin.math.abs(matrix[Matrix.MSCALE_Y])
                    if (scale > 0f) viewportYScale = 1f / scale
                    updateOverlayOffsets()
                    scheduleGeometry()
                },
                onDirectTouchObserved = { if (inputRouter.promoteDirectTouch(lease.displayId)) refreshInputPolicyOnly() },
            )
        }
        inputRouter.refresh(clearUnbackedTouch)
        if (policyOnly) return
        addDesktopControls()
        lorieView?.requestFocus()
        inputController?.reacquireFocus()
        attachingDesktopInput = true; try { applyWindowGeometry() } finally { attachingDesktopInput = false }
    }

    private fun updateInputDeviceListener() {
        if (inputDeviceListenerRegistered) return
        getSystemService(InputManager::class.java).registerInputDeviceListener(
            inputDeviceListener,
            inputDeviceHandler,
        )
        inputDeviceListenerRegistered = true
    }

    private fun scheduleDesktopInputRefresh(removed: Boolean = false) {
        if (!inputDeviceListenerRegistered || rendererCleanup.isReleased) return
        pendingDeviceRemoved = pendingDeviceRemoved || removed
        inputDeviceHandler.removeCallbacks(refreshDesktopInput)
        inputDeviceHandler.post(refreshDesktopInput)
    }

    private fun unregisterInputDeviceListener() {
        pendingDeviceRemoved = false
        inputDeviceHandler.removeCallbacks(refreshDesktopInput)
        if (!inputDeviceListenerRegistered) return
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(
            inputDeviceListener,
        )
        inputDeviceListenerRegistered = false
    }

    private fun currentProfileKind(): ProfileKind = DisplayProfilePolicy.classify(
        (lorieView?.display?.displayId ?: windowManager.defaultDisplay.displayId) == android.view.Display.DEFAULT_DISPLAY,
        resources.configuration.smallestScreenWidthDp,
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK,
    )

    private fun scheduleGeometry() {
        if (geometryQueued || !::placeholder.isInitialized || rendererCleanup.isReleased) return
        geometryQueued = true
        placeholder.postOnAnimation(geometryUpdate)
    }

    private fun remainingWindowInsets(view: View): PixelInsets? {
        if (view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = lastWindowInsets ?: view.rootWindowInsets ?: return null
            val raw = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            val bounds = windowManager.currentWindowMetrics.bounds
            view.getLocationInWindow(location)
            return DisplayProfilePolicy.remainingInsets(
                PixelSize(view.width, view.height), PixelSize(bounds.width(), bounds.height()),
                location[0], location[1], PixelInsets(raw.left, raw.top, raw.right, raw.bottom),
            )
        }
        val visible = Rect()
        view.getWindowVisibleDisplayFrame(visible)
        if (visible.width() <= 0 || visible.height() <= 0) return null
        view.getLocationOnScreen(location)
        val clipped = PixelInsets(
            (visible.left - location[0]).coerceIn(0, view.width),
            (visible.top - location[1]).coerceIn(0, view.height),
            (location[0] + view.width - visible.right).coerceIn(0, view.width),
            (location[1] + view.height - visible.bottom).coerceIn(0, view.height),
        )
        val cutout = (lastWindowInsets ?: view.rootWindowInsets)?.displayCutout ?: return clipped
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return null
        view.getLocationInWindow(location)
        val safe = DisplayProfilePolicy.remainingInsets(
            PixelSize(view.width, view.height), PixelSize(decor.width, decor.height),
            location[0], location[1],
            PixelInsets(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight, cutout.safeInsetBottom),
        )
        return PixelInsets(
            maxOf(clipped.left, safe.left), maxOf(clipped.top, safe.top),
            maxOf(clipped.right, safe.right), maxOf(clipped.bottom, safe.bottom),
        )
    }

    private fun applyWindowGeometry() {
        if (!isCurrentRenderer()) return
        val view = lorieView ?: return
        val lease = rendererLease ?: return
        val actualDisplay = view.display?.displayId ?: return
        if (actualDisplay != lease.displayId) {
            displayCoordinator.observeActivityEnvironment(this, sessionEpoch)
            displayCoordinator.onActivityMoved(lease, actualDisplay)
            return
        }
        val remaining = remainingWindowInsets(view) ?: return
        lastWindowInsets?.let(::updateExtraKeysBar)
        val keysHeight = extraKeysBar?.takeIf { it.visibility == View.VISIBLE }?.height ?: 0
        currentInsets = remaining.copy(bottom = remaining.bottom + keysHeight)
        val content = PixelSize(
            view.width - currentInsets.left - currentInsets.right,
            view.height - currentInsets.top - currentInsets.bottom,
        )
        if (content.width <= 0 || content.height <= 0) return
        val kind = currentProfileKind()
        val displayId = view.display?.displayId ?: lease.displayId
        val profile = DisplayProfilePolicy.resolve(
            kind,
            content,
            displayCoordinator.loadSettings(kind, displayId),
        )
        val render = DisplayProfilePolicy.renderSize(content, profile.settings.renderPolicy) ?: return
        applyDisplayProfile(profile)
        view.setDesktopRenderSize(render.width, render.height)
        view.setContentInsets(currentInsets.left, currentInsets.top, currentInsets.right, currentInsets.bottom)
        val appliedRender = view.renderedSize ?: return
        displayCoordinator.reportGeometry(lease, kind, PixelSize(appliedRender.x, appliedRender.y))
        if (inputController != null && LorieView.connected()) {
            if (displayCoordinator.consumeSessionStart(lease)) {
                shouldStartSession = true
                sessionStartPending = true
                view.postDelayed({
                    if (!isCurrentRenderer()) return@postDelayed
                    val startEpoch = displayCoordinator.beginSessionStart(lease)
                        ?: return@postDelayed
                    startDesktopSessionIfRequested(startEpoch)
                }, 250)
            } else if (!sessionStartPending && !desktopReady && !desktopRevealed) {
                if (displayCoordinator.isStarting) {
                    awaitPendingSessionStart()
                } else {
                    waitForDesktopAndReveal()
                }
            }
            displayCoordinator.onAttached(lease, displayRequestId, content)
        }
        updateOverlayOffsets()
        val retry = explicitProfileRetry
        explicitProfileRetry = false
        applyLiveProfile(force = retry)
    }

    fun applyDisplayProfile(profile: DisplayProfile) {
        if (currentProfile == profile) return
        currentProfile = profile
        TermuxMainActivity.getPrefs().apply {
            displayResolutionMode.put(when (profile.settings.renderPolicy) {
                RenderPolicy.NATIVE -> "native"
                RenderPolicy.LEGACY_PHONE -> "scaled"
                else -> "exact"
            })
            displayStretch.put(profile.kind == ProfileKind.PHONE && profile.settings.renderPolicy == RenderPolicy.LEGACY_PHONE)
            scaleTouchpad.put(true)
        }
        if (!attachingDesktopInput && inputController != null) refreshInputPolicyOnly()
        inputModeButton?.let(::showInputMode)
    }

    private fun applyLiveProfile(force: Boolean = false) {
        if ((!desktopReady && !force) || !isCurrentRenderer()) return
        val profile = currentProfile ?: return
        val lease = rendererLease ?: return
        if (XfceDisplayProfile.sameUi(failedUiProfile, profile)) {
            val completions = profileCompletions.toList()
            profileCompletions.clear()
            completions.forEach { it(Result.failure(DesktopDisplayException(
                "PROFILE_APPLY_FAILED", "The profile could not be applied. Retry from display settings.",
            ))) }
            return
        }
        if (!force && applyingUiProfile == null && XfceDisplayProfile.sameUi(appliedUiProfile, profile)) {
            val completions = profileCompletions.toList()
            profileCompletions.clear()
            completions.forEach { it(Result.success(Unit)) }
            return
        }
        if (XfceDisplayProfile.sameUi(applyingUiProfile, profile)) return
        applyingUiProfile = profile
        val revision = ++profileRevision
        val completion: (Result<Unit>) -> Unit = { outcome ->
            if (isCurrentRenderer() && profileRevision == revision) {
                applyingUiProfile = null
                if (outcome.isSuccess) {
                    desktopReady = true
                    appliedUiProfile = profile
                    failedUiProfile = null
                } else {
                    failedUiProfile = profile
                }
                displayCoordinator.profileApplied(lease, outcome)
                val completions = profileCompletions.toList()
                profileCompletions.clear()
                completions.forEach { it(outcome) }
            }
        }
        environmentManager.applyDisplayProfile(
            profile,
            kind = EnvironmentKind.fromSessionMode(sessionMode),
            onComplete = completion,
        )
    }

    private fun updateOverlayOffsets() {
        val profile = currentProfile ?: return
        val bottom = currentInsets.bottom + (profile.dockPx * viewportYScale).toInt() +
            (8 * resources.displayMetrics.density).toInt()
        for (control in listOfNotNull(controlOverlay, collapsedControl)) {
            val params = control.layoutParams as? FrameLayout.LayoutParams ?: continue
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                control.layoutParams = params
            }
        }
    }

    private fun addDesktopControls() {
        if (controlOverlay != null) return
        val density = resources.displayMetrics.density

        // Frosted, translucent controls: one rounded pill when open, an AssistiveTouch-style
        // square when collapsed.
        fun frosted(radius: Float) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.argb(200, 22, 26, 34))
            setStroke((1 * density).toInt(), Color.argb(40, 255, 255, 255))
        }
        fun pressRipple() = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            null,
            GradientDrawable().apply { cornerRadius = 20 * density; setColor(Color.WHITE) },
        )
        fun iconButton(icon: Int, label: String) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = label
            background = pressRipple()
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }

        val buttonHeight = (44 * density).toInt()
        val keyboardButton = iconButton(R.drawable.ic_dd_keyboard, "Show keyboard").apply {
            setOnClickListener { showKeyboard() }
        }
        inputModeButton = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 13f
            setTextColor(Color.WHITE)
            background = pressRipple()
            stateListAnimator = null
            compoundDrawablePadding = (6 * density).toInt()
            setPadding((12 * density).toInt(), 0, (14 * density).toInt(), 0)
            showInputMode(this)
            setOnClickListener {
                try {
                    inputController?.nextMode()
                    showInputMode(this)
                    Toast.makeText(this@DesktopActivity, "Input mode: $text", Toast.LENGTH_SHORT).show()
                } catch (error: IllegalStateException) {
                    Log.e("DesktopDisplay", "Could not save the input profile", error)
                    Toast.makeText(this@DesktopActivity, "Could not save input mode. Try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
        val hideButton = iconButton(R.drawable.ic_dd_close, "Hide desktop controls").apply {
            setOnClickListener { setControlsCollapsed(true) }
        }

        controlOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = frosted(26 * density)
            elevation = 10 * density
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(keyboardButton, LinearLayout.LayoutParams(buttonHeight, buttonHeight))
            addView(inputModeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, buttonHeight,
            ))
            addView(hideButton, LinearLayout.LayoutParams(buttonHeight, buttonHeight))
            // Start collapsed so the bar never covers windows until the user asks for it.
            visibility = View.INVISIBLE
        }

        // AssistiveTouch-style handle: fades out when idle and lights up while touched.
        collapsedControl = View(this).apply {
            contentDescription = "Show desktop controls"
            background = assistiveTouchDrawable(density)
            elevation = 10 * density
            alpha = COLLAPSED_IDLE_ALPHA
            if (!desktopRevealed) visibility = View.INVISIBLE
        }

        // Sit just above XFCE's bottom dock, which is scaled up with the X11 display.
        val dockHeight = ((currentProfile?.dockPx ?: XfceMobileProfile.DOCK_SIZE_PX) * viewportYScale).toInt()
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = (8 * density).toInt()
            bottomMargin = dockHeight + (8 * density).toInt()
        }
        val collapsedSize = (52 * density).toInt()
        val collapsedParams = FrameLayout.LayoutParams(
            collapsedSize,
            collapsedSize,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = overlayParams.rightMargin
            bottomMargin = overlayParams.bottomMargin
        }

        placeholder.addView(controlOverlay, overlayParams)
        placeholder.addView(collapsedControl, collapsedParams)
        collapsedControl?.setOnTouchListener(assistiveTouchListener(collapsedControl!!))
        collapsedControl?.bringToFront()
        addExtraKeysBar()
        updateOverlayOffsets()
    }

    /** Esc/Tab/Ctrl/Alt/arrow row that rides on top of the soft keyboard while it is open. */
    private fun addExtraKeysBar() {
        val bar = ExtraKeysBar(this) { lorieView }.apply { visibility = View.GONE }
        extraKeysBar = bar
        placeholder.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleGeometry() }
        // Typing on the soft keyboard releases one-shot Ctrl/Alt (see LorieView's InputConnection).
        TermuxMainActivity.getInstance().apply {
            mExtraKeys = object : TermuxMainActivity.DummyExtraKeys() {
                override fun unsetSpecialKeys() {
                    runOnUiThread { extraKeysBar?.onKeyTyped() }
                }
            }
            useTermuxEKBarBehaviour = true
        }
        placeholder.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun updateExtraKeysBar(insets: WindowInsets) {
        val bar = extraKeysBar ?: return
        val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (insets.isVisible(WindowInsets.Type.ime())) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
        } else {
            insets.systemWindowInsetBottom
        }
        if (imeBottom > 0) {
            val params = bar.layoutParams as FrameLayout.LayoutParams
            val bottom = lorieView?.let(::remainingWindowInsets)?.bottom ?: 0
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                bar.layoutParams = params
            }
            if (bar.visibility != View.VISIBLE) {
                bar.visibility = View.VISIBLE
                bar.bringToFront()
            }
        } else if (bar.visibility == View.VISIBLE) {
            bar.releaseAll()
            bar.visibility = View.GONE
        }
    }

    private fun dragListener(target: View, onTap: (() -> Unit)? = null): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false
        val threshold = resources.displayMetrics.density * 6

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold) {
                        dragged = true
                    }
                    target.x = (startX + dx).coerceIn(0f, (placeholder.width - target.width).coerceAtLeast(0).toFloat())
                    target.y = (startY + dy).coerceIn(0f, (placeholder.height - target.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    /** iOS AssistiveTouch look: dark translucent rounded square with nested white circles. */
    private fun assistiveTouchDrawable(density: Float): LayerDrawable {
        fun circle(alpha: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alpha, 255, 255, 255))
        }
        val square = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(Color.argb(170, 30, 30, 30))
        }
        return LayerDrawable(arrayOf(square, circle(45), circle(90), circle(240))).apply {
            setLayerInset(1, (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setLayerInset(2, (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setLayerInset(3, (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
    }

    /** Mouse icon for trackpad mode, finger icon for the touch modes, plus the mode name. */
    private fun showInputMode(button: Button) {
        val label = inputController?.modeLabel() ?: "Trackpad"
        val icon = if (label == "Trackpad") R.drawable.ic_dd_mouse else R.drawable.ic_dd_touch
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    /** Lights the ☰ handle up while touched, snaps it to the nearest side after a drag, then fades it. */
    private fun assistiveTouchListener(handle: View): View.OnTouchListener {
        val drag = dragListener(handle) { setControlsCollapsed(false) }
        var downX = 0f
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = handle.x
                    collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
                    handle.animate().cancel()
                    handle.alpha = 1f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (handle.x != downX) {
                        val margin = 8 * resources.displayMetrics.density
                        val maxX = (placeholder.width - handle.width).toFloat()
                        val edgeX = if (handle.x + handle.width / 2f < placeholder.width / 2f) margin else maxX - margin
                        handle.animate().x(edgeX.coerceIn(0f, maxX.coerceAtLeast(0f))).setDuration(180).start()
                    }
                    scheduleCollapsedFade()
                }
            }
            drag.onTouch(view, event)
        }
    }

    private fun scheduleCollapsedFade() {
        collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        collapsedFadeHandler.postDelayed(fadeCollapsedControl, COLLAPSED_FADE_DELAY_MS)
    }

    private fun setControlsCollapsed(collapsed: Boolean) {
        val from = if (collapsed) controlOverlay else collapsedControl
        val to = if (collapsed) collapsedControl else controlOverlay
        to?.x = from?.x ?: 0f
        to?.y = from?.y ?: 0f
        from?.visibility = View.INVISIBLE
        to?.visibility = View.VISIBLE
        to?.bringToFront()
        // The expanded bar is wider than the ☰ handle; once laid out, keep it fully on screen.
        to?.post { keepOnScreen(to) }
        if (collapsed) {
            collapsedControl?.alpha = 1f
            scheduleCollapsedFade()
        } else {
            collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        }
    }

    private fun keepOnScreen(view: View) {
        val maxX = (placeholder.width - view.width).coerceAtLeast(0).toFloat()
        val maxY = (placeholder.height - view.height).coerceAtLeast(0).toFloat()
        view.x = view.x.coerceIn(0f, maxX)
        view.y = view.y.coerceIn(0f, maxY)
    }

    /** Three-finger tap: open the keyboard, or close it when it is already showing. */
    private fun toggleKeyboard() {
        val imeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            extraKeysBar?.visibility == View.VISIBLE
        }
        if (imeVisible) hideKeyboard() else showKeyboard()
    }

    private fun hideKeyboard() {
        val view = lorieView ?: return
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard() {
        val view = lorieView ?: return
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        inputMethod.restartInput(view)
        view.post {
            inputMethod.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startDesktopSessionIfRequested(startEpoch: Long) {
        shouldStartSession = false
        desktopReady = false
        appliedUiProfile = null
        applyingUiProfile = null
        failedUiProfile = null
        profileRevision++
        readinessThread?.interrupt()
        readinessThread = null
        if (desktopRevealed) {
            desktopRevealed = false
            controlOverlay?.visibility = View.INVISIBLE
            collapsedControl?.visibility = View.INVISIBLE
            loadingOverlay?.apply { alpha = 1f; visibility = View.VISIBLE; bringToFront() }
            logoAnimator?.start()
            loadingStartedAt = android.os.SystemClock.elapsedRealtime()
            loadingMessageHandler.postDelayed(loadingMessageTicker, 5_000)
            loadingMessageHandler.postDelayed(loadingEstimateTicker, 1_000)
        }
        val epoch = startEpoch
        val coordinator = displayCoordinator
        val rooted = chrootRuntime
        val mode = sessionMode
        val environmentKind = EnvironmentKind.fromSessionMode(mode)
        val desktop = desktopEnv
        val initialProfile = currentProfile ?: DisplayProfilePolicy.phoneDefaultProfile()
        val app = applicationContext
        val activity = java.lang.ref.WeakReference(this)
        DroidDeskService.executeSession {
            if (!coordinator.isSessionCurrent(epoch)) return@executeSession
            Log.i(TAG, "Starting Linux desktop session after X server connection")
            try {
                val started = environmentManager.start(
                    StartRequest(environmentKind, desktop, initialProfile),
                )
                if (started.isFailure) throw started.exceptionOrNull()!!
                if (environmentKind == EnvironmentKind.CHROOT_UBUNTU) {
                    if (coordinator.isSessionCurrent(epoch) && rooted.isRunning()) {
                        try {
                            DroidDeskService.ensureLegacyIntentBridge(app, rooted)
                        } catch (error: Exception) {
                            Log.e(TAG, "Legacy Android launcher bridge could not start", error)
                            Handler(Looper.getMainLooper()).post {
                                if (coordinator.isSessionCurrent(epoch)) coordinator.reportError(DesktopDisplayException(
                                    "ANDROID_BRIDGE_FAILED", "Linux is running, but the Android launcher bridge failed: ${error.message}",
                                ))
                            }
                        }
                    }
                }
                val running = environmentManager.status().running
                val outcome = if (running) Result.success(Unit) else Result.failure(
                    IllegalStateException("Linux session did not start; check the setup log"),
                )
                Handler(Looper.getMainLooper()).post {
                    coordinator.sessionStarted(epoch, outcome)
                    activity.get()?.takeIf { it.isCurrentRenderer() }?.let {
                        it.sessionStartPending = false
                        it.waitForDesktopAndReveal()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Desktop session failed", error)
                Handler(Looper.getMainLooper()).post {
                    coordinator.sessionStarted(epoch, Result.failure(error))
                    activity.get()?.takeIf { it.isCurrentRenderer() }?.let {
                        it.sessionStartPending = false
                        it.revealDesktop(false)
                    }
                }
            }
        }
    }

    private fun waitForDesktopAndReveal() {
        if (readinessThread != null) return
        readinessThread = Thread({
            try {
                val ready = environmentManager.waitForDesktopReady(
                    desktopEnv,
                    kind = EnvironmentKind.fromSessionMode(sessionMode),
                )
                revealDesktop(ready)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                val finished = Thread.currentThread()
                Handler(Looper.getMainLooper()).post {
                    if (readinessThread === finished) readinessThread = null
                }
            }
        }, "LinuxDesktopReady").also { it.start() }
    }

    private fun awaitPendingSessionStart() {
        if (awaitingSessionStart) return
        awaitingSessionStart = true
        val epoch = sessionEpoch
        displayCoordinator.awaitSessionReady(epoch) { outcome ->
            if (!isCurrentRenderer()) return@awaitSessionReady
            awaitingSessionStart = false
            if (outcome.isSuccess) {
                waitForDesktopAndReveal()
            } else {
                revealDesktop(false)
            }
        }
    }

    /** Follows the theme picked in the app (Flutter stores it as flutter.theme_mode). */
    private fun isLightTheme(): Boolean {
        val mode = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            .getString("flutter.theme_mode", "dark")
        return when (mode) {
            "light" -> true
            "system" -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    }

    private fun showLoadingOverlay() {
        val density = resources.displayMetrics.density
        val light = isLightTheme()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        runCatching {
            assets.open("flutter_assets/assets/icons/logo.png").use { stream ->
                ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeStream(stream))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    logoAnimator = FloatingLogoAnimator(this).also { it.start() }
                    content.addView(
                        this,
                        LinearLayout.LayoutParams((112 * density).toInt(), (112 * density).toInt()).apply {
                            bottomMargin = (26 * density).toInt()
                        },
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Loading logo unavailable", it) }

        content.addView(TextView(this).apply {
            text = "DroidDesk"
            textSize = 28f
            setTextColor(if (light) Color.rgb(28, 28, 30) else Color.WHITE)
            gravity = Gravity.CENTER
        })
        content.addView(LoadingSpinnerView(
            this,
            arcColor = Color.rgb(10, 132, 255),
            trackColor = if (light) Color.argb(40, 10, 132, 255) else Color.argb(50, 255, 255, 255),
        ), LinearLayout.LayoutParams(
            (44 * density).toInt(),
            (44 * density).toInt(),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (22 * density).toInt()
        })
        loadingStatus = TextView(this).apply {
            text = loadingMessages.first()
            textSize = 14f
            setTextColor(if (light) Color.rgb(58, 58, 60) else Color.rgb(190, 198, 215))
            gravity = Gravity.CENTER
        }.also { status ->
            content.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (14 * density).toInt() })
        }
        loadingEstimate = TextView(this).apply {
            text = "About $estimatedLoadingSeconds seconds remaining"
            textSize = 12f
            setTextColor(if (light) Color.rgb(110, 110, 115) else Color.rgb(130, 143, 164))
            gravity = Gravity.CENTER
            contentDescription = text
        }.also { estimate ->
            content.addView(estimate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() })
        }

        loadingOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (light) {
                    intArrayOf(Color.rgb(242, 242, 247), Color.rgb(237, 237, 242))
                } else {
                    intArrayOf(Color.rgb(12, 18, 32), Color.rgb(4, 7, 14))
                },
            )
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }.also { overlay ->
            placeholder.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        loadingStartedAt = android.os.SystemClock.elapsedRealtime()
        loadingMessageHandler.postDelayed(loadingMessageTicker, 5_000)
        loadingMessageHandler.postDelayed(loadingEstimateTicker, 1_000)
    }

    private fun revealDesktop(ready: Boolean) {
        runOnUiThread {
            if (!isCurrentRenderer() || isFinishing || isDestroyed) return@runOnUiThread
            desktopReady = ready
            if (!ready) {
                val outcome = Result.failure<Unit>(IllegalStateException(
                    "Desktop services are not ready. Check the session log and retry the display profile.",
                ))
                rendererLease?.let { displayCoordinator.profileApplied(it, outcome) }
                val completions = profileCompletions.toList()
                profileCompletions.clear()
                completions.forEach { it(outcome) }
                finishReveal()
            } else {
                failedUiProfile = null
                if (!desktopRevealed) profileCompletions += { if (isCurrentRenderer()) finishReveal() }
                applyWindowGeometry()
                applyLiveProfile()
            }
        }
    }

    private fun finishReveal() {
        if (!isCurrentRenderer() || desktopRevealed || isFinishing || isDestroyed) return
        desktopRevealed = true
        loadingMessageHandler.removeCallbacks(loadingMessageTicker)
        loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
        Log.i(TAG, "Desktop revealed ready=$desktopReady")
        loadingOverlay?.animate()
            ?.alpha(0f)
            ?.setDuration(350)
            ?.withEndAction {
                if (isCurrentRenderer()) {
                    loadingOverlay?.visibility = View.GONE
                    logoAnimator?.stop()
                    collapsedControl?.visibility = View.VISIBLE
                    collapsedControl?.bringToFront()
                    lorieView?.requestFocus()
                }
            }
            ?.start()
    }

    private fun showX11Error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        Toast.makeText(this, "X11 Error: $message", Toast.LENGTH_LONG).show()
    }

    private fun isCurrentRenderer(): Boolean = rendererLease?.let {
        displayCoordinator.isCurrent(it) && TermuxMainActivity.getInstance().isLorieViewOwner(it.generation)
    } == true

    private fun detachRenderer() {
        if (rendererCleanup.isReleased) return
        val ownedLease = rendererLease
        val ownsLorieView = ownedLease != null &&
            TermuxMainActivity.getInstance().isLorieViewOwner(ownedLease.generation)
        rendererCleanup.run(
            releaseOwnedInput = { releaseRendererInput(ownsLorieView) },
            completeTeardown = { completeRendererTeardown(ownedLease, ownsLorieView) },
        )
    }

    private fun releaseRendererInput(ownsLorieView: Boolean) {
        extraKeysBar?.releaseAll()
        extraKeysBar = null
        TermuxMainActivity.getInstance().apply {
            if (ownsLorieView) {
                mExtraKeys = null
                useTermuxEKBarBehaviour = false
            }
        }
        currentInputContext = null; inputRouter.release(inputController)
        inputController?.dispose()
        inputController = null
    }

    private fun completeRendererTeardown(
        ownedLease: RendererLease?,
        ownsLorieView: Boolean,
    ) {
        unregisterInputDeviceListener()
        sessionStartPending = false
        awaitingSessionStart = false
        profileRevision++
        desktopReady = false
        if (ownsLorieView) {
            environmentManager.cancelDisplayProfile(EnvironmentKind.fromSessionMode(sessionMode))
        }
        val pendingProfiles = profileCompletions.toList()
        profileCompletions.clear()
        val cancelled = Result.failure<Unit>(DesktopDisplayException(
            if (displayCoordinator.isStopped) "SESSION_STOPPED" else "PROFILE_APPLY_FAILED",
            "Settings were saved but the view closed before profile application was confirmed",
        ))
        pendingProfiles.forEach { it(cancelled) }
        if (::placeholder.isInitialized) placeholder.removeCallbacks(geometryUpdate)
        geometryQueued = false
        geometryListener?.let { lorieView?.removeOnLayoutChangeListener(it) }
        geometryListener = null
        if (::placeholder.isInitialized) placeholder.setOnApplyWindowInsetsListener(null)
        collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        loadingMessageHandler.removeCallbacks(loadingMessageTicker)
        loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
        surfaceCallback?.let { callback -> lorieView?.holder?.removeCallback(callback) }
        surfaceCallback = null
        if (shouldFinishInputRelay(ownedLease != null, ownsLorieView)) {
            ExternalInputRelayActivity.finishIfOpen()
        }
        ownedLease?.let { TermuxMainActivity.getInstance().releaseLorieView(it.generation) }
        (lorieView?.parent as? ViewGroup)?.removeView(lorieView)
        lorieView = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        readinessThread?.interrupt()
        readinessThread = null
        logoAnimator?.stop()
    }

    override fun onDestroy() {
        displayCoordinator.unbindActivity(this)
        rendererLease?.let(displayCoordinator::release)
        detachRenderer()
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
