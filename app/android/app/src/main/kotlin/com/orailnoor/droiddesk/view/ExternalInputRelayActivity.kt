package com.orailnoor.droiddesk.view

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.orailnoor.droiddesk.x11.ExternalDesktopInputBridge
import java.lang.ref.WeakReference

internal fun forwardRelayMotion(
    isExternal: Boolean,
    bridgeAttached: Boolean,
    forward: () -> Boolean,
): Boolean {
    if (!isExternal || !bridgeAttached) return false
    return forward()
}

internal fun shouldLaunchInputRelay(
    hasActivity: Boolean,
    isFinishing: Boolean,
    isDestroyed: Boolean,
    hasWindowFocus: Boolean,
): Boolean = !hasActivity || isFinishing || isDestroyed || !hasWindowFocus

internal class RelayPointerCaptureController(
    private val shouldCapture: () -> Boolean, private val hasWindowFocus: () -> Boolean, private val hasPointerCapture: () -> Boolean,
    private val requestPointerCapture: () -> Unit, private val releasePointerCapture: () -> Unit, private val scheduleRetry: () -> Unit, private val cancelRetry: () -> Unit,
) {
    private var disposed = false
    fun sync() { cancelRetry(); if (disposed || !shouldCapture()) { if (hasPointerCapture()) releasePointerCapture() } else if (hasWindowFocus() && !hasPointerCapture()) requestPointerCapture() }
    fun onWindowFocusChanged(hasFocus: Boolean) = if (hasFocus) sync() else cancelRetry()
    fun onPointerCaptureChanged(hasCapture: Boolean) { cancelRetry(); if (disposed || !shouldCapture()) { if (hasCapture || hasPointerCapture()) releasePointerCapture() } else if (!hasCapture) scheduleRetry() }
    fun dispose() { disposed = true; cancelRetry(); if (hasPointerCapture()) releasePointerCapture() }
}

class ExternalInputRelayActivity : Activity() {
    private var captureController: RelayPointerCaptureController? = null
    private val retryCapture = Runnable { captureController?.sync() }

    companion object {
        private var current = WeakReference<ExternalInputRelayActivity>(null)

        fun open(context: Context) {
            val activity = current.get()
            if (!shouldLaunchInputRelay(
                    hasActivity = activity != null,
                    isFinishing = activity?.isFinishing == true,
                    isDestroyed = activity?.isDestroyed == true,
                    hasWindowFocus = activity?.hasWindowFocus() == true,
                )
            ) { activity?.captureController?.sync(); return }
            val intent = Intent(context, ExternalInputRelayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = Display.DEFAULT_DISPLAY
            }
            context.startActivity(intent, options.toBundle())
        }

        fun finishIfOpen() {
            current.get()?.let { ExternalDesktopInputBridge.onCaptureChanged = null; it.captureController?.dispose(); it.finishAndRemoveTask() }
            current.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ExternalDesktopInputBridge.isAttached()) { finishAndRemoveTask(); return }
        current = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val relayView = TextView(this).apply {
            setBackgroundColor(Color.rgb(12, 14, 18))
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            text = "External desktop input is active\n\n" +
                "Use the connected mouse, touchpad, and keyboard to control Linux."
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
        relayView.setOnCapturedPointerListener { view, event ->
            forwardRelayMotion(
                isExternal = event.device?.isExternal == true,
                bridgeAttached = ExternalDesktopInputBridge.isAttached(),
            ) {
                ExternalDesktopInputBridge.dispatchMotionEvent(view, event)
                true
            }
        }
        setContentView(relayView)
        val decor = window.decorView
        captureController = RelayPointerCaptureController(ExternalDesktopInputBridge::shouldCapturePointer, ::hasWindowFocus, decor::hasPointerCapture, decor::requestPointerCapture, decor::releasePointerCapture, { decor.postDelayed(retryCapture, 100) }, { decor.removeCallbacks(retryCapture) }).also { ExternalDesktopInputBridge.onCaptureChanged = it::sync; it.sync() }
    }

    override fun onResume() { super.onResume(); if (!ExternalDesktopInputBridge.isAttached()) finishAndRemoveTask() else maintainPointerCapture() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        captureController?.onWindowFocusChanged(hasFocus)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        captureController?.onPointerCaptureChanged(hasCapture)
    }

    private fun maintainPointerCapture() { captureController?.sync() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.device?.isExternal == true &&
            ExternalDesktopInputBridge.dispatchKeyEvent(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (forwardRelayMotion(
                isExternal = event.device?.isExternal == true,
                bridgeAttached = ExternalDesktopInputBridge.isAttached(),
            ) {
            maintainPointerCapture()
            ExternalDesktopInputBridge.dispatchMotionEvent(window.decorView, event)
            true
        }) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (forwardRelayMotion(
                isExternal = event.device?.isExternal == true,
                bridgeAttached = ExternalDesktopInputBridge.isAttached(),
            ) {
            maintainPointerCapture()
            ExternalDesktopInputBridge.dispatchMotionEvent(window.decorView, event)
            true
        }) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        captureController?.dispose()
        if (current.get() === this) { current.clear(); ExternalDesktopInputBridge.onCaptureChanged = null }
        super.onDestroy()
    }
}
