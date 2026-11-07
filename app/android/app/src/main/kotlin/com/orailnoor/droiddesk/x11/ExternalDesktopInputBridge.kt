package com.orailnoor.droiddesk.x11

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference

internal object ExternalDesktopInputBridge {
    private var controller = WeakReference<X11InputController>(null)

    fun attach(value: X11InputController) {
        controller = WeakReference(value)
    }

    fun detach(value: X11InputController) {
        if (controller.get() === value) controller.clear()
    }

    fun isAttached(): Boolean = controller.get() != null

    fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller.get()?.dispatchExternalKeyEvent(event) == true

    fun dispatchMotionEvent(view: View, event: MotionEvent): Boolean =
        controller.get()?.dispatchExternalMotionEvent(view, event) == true
}
