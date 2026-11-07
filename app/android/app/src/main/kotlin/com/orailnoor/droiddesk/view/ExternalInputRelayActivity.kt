package com.orailnoor.droiddesk.view

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.orailnoor.droiddesk.x11.ExternalDesktopInputBridge
import java.lang.ref.WeakReference

class ExternalInputRelayActivity : Activity() {
    companion object {
        private var current = WeakReference<ExternalInputRelayActivity>(null)

        fun open(context: Context) {
            val activity = current.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) return
            val intent = Intent(context, ExternalInputRelayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = Display.DEFAULT_DISPLAY
            }
            context.startActivity(intent, options.toBundle())
        }

        fun finishIfOpen() {
            current.get()?.finishAndRemoveTask()
            current.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            TextView(this).apply {
                setBackgroundColor(Color.rgb(12, 14, 18))
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                text = "External desktop input is active\n\n" +
                    "Use the connected mouse, touchpad, and keyboard to control Linux."
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            },
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) maintainPointerCapture()
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        if (!hasCapture) window.decorView.postDelayed(::maintainPointerCapture, 100)
    }

    private fun maintainPointerCapture() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            hasWindowFocus() &&
            !window.decorView.hasPointerCapture()
        ) {
            window.decorView.requestPointerCapture()
        }
    }

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
        if (event.device?.isExternal == true && ExternalDesktopInputBridge.isAttached()) {
            maintainPointerCapture()
            ExternalDesktopInputBridge.dispatchMotionEvent(window.decorView, event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.device?.isExternal == true && ExternalDesktopInputBridge.isAttached()) {
            maintainPointerCapture()
            ExternalDesktopInputBridge.dispatchMotionEvent(window.decorView, event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        if (current.get() === this) current.clear()
        super.onDestroy()
    }
}
