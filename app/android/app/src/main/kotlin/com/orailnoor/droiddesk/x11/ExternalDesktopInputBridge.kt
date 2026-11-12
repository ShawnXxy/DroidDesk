package com.orailnoor.droiddesk.x11

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference

internal interface ExternalDesktopInputTarget {
    fun dispatchExternalKeyEvent(event: KeyEvent): Boolean; fun dispatchExternalMotionEvent(view: View, event: MotionEvent): Boolean
}

internal object ExternalDesktopInputBridge {
    private var controller = WeakReference<ExternalDesktopInputTarget>(null)
    var onCaptureChanged: (() -> Unit)? = null
    var relayPointerCapture: Boolean = false; private set

    fun attach(value: ExternalDesktopInputTarget, pointerCapture: Boolean = true) {
        val changed = controller.get() !== value || relayPointerCapture != pointerCapture
        controller = WeakReference(value); relayPointerCapture = pointerCapture; if (changed) onCaptureChanged?.invoke()
    }

    fun detach(value: ExternalDesktopInputTarget): Boolean {
        val current = controller.get(); if (current != null && current !== value) return false
        controller.clear(); val hadCapture = relayPointerCapture.also { relayPointerCapture = false }
        if (current === value || hadCapture) onCaptureChanged?.invoke(); return current === value
    }

    fun shouldCapturePointer(): Boolean = controller.get() != null && relayPointerCapture

    fun isAttached(): Boolean = controller.get() != null

    fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller.get()?.dispatchExternalKeyEvent(event) == true

    fun dispatchMotionEvent(view: View, event: MotionEvent): Boolean =
        controller.get()?.dispatchExternalMotionEvent(view, event) == true
}
