package com.orailnoor.droiddesk.x11

import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.graphics.Matrix
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler
import com.termux.x11.input.InputStub
import com.orailnoor.droiddesk.display.input.InputPolicyDecision

internal fun isDirectTouchEvent(source: Int, toolType: Int, isRendererView: Boolean): Boolean =
    isRendererView && toolType == MotionEvent.TOOL_TYPE_FINGER && (source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN &&
        (source and InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD && (source and InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE && (source and InputDevice.SOURCE_MOUSE_RELATIVE) != InputDevice.SOURCE_MOUSE_RELATIVE

internal fun nextSupportedInputMode(currentMode: Int, allowDirectTouch: Boolean): Int = when (currentMode) {
    TouchInputHandler.InputMode.TRACKPAD -> TouchInputHandler.InputMode.SIMULATED_TOUCH
    TouchInputHandler.InputMode.SIMULATED_TOUCH -> if (allowDirectTouch) TouchInputHandler.InputMode.TOUCH else TouchInputHandler.InputMode.TRACKPAD
    else -> TouchInputHandler.InputMode.TRACKPAD
}

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(
    private val lorieView: LorieView,
    private val generation: Long,
    initialMode: Int,
    private val onModeChanged: (Int) -> Unit,
    onThreeFingerTap: () -> Unit = {},
    private val onViewportChanged: (Int, Int, Matrix) -> Unit = { _, _, _ -> },
    private val onDirectTouchObserved: () -> Unit = {},
) : ExternalDesktopInputTarget {
    private fun isCurrent() = MainActivity.getInstance().isLorieViewOwner(generation)
    private val sender = InputEventSender(object : InputStub {
        override fun sendMouseEvent(x: Float, y: Float, button: Int, down: Boolean, relative: Boolean) {
            if (isCurrent()) lorieView.sendMouseEvent(x, y, button, down, relative)
        }
        override fun sendMouseWheelEvent(x: Float, y: Float) {
            if (isCurrent()) lorieView.sendMouseWheelEvent(x, y)
        }
        override fun sendKeyEvent(scan: Int, key: Int, down: Boolean) =
            isCurrent() && lorieView.sendKeyEvent(scan, key, down)
        override fun sendTextEvent(text: ByteArray) {
            if (isCurrent()) lorieView.sendTextEvent(text)
        }
        override fun sendTouchEvent(action: Int, pointer: Int, x: Int, y: Int) {
            if (isCurrent()) lorieView.sendTouchEvent(action, pointer, x, y)
        }
        override fun sendStylusEvent(
            x: Float, y: Float, pressure: Int, tiltX: Int, tiltY: Int, orientation: Int,
            buttons: Int, eraser: Boolean, mouseMode: Boolean,
        ) {
            if (isCurrent()) lorieView.sendStylusEvent(x, y, pressure, tiltX, tiltY, orientation, buttons, eraser, mouseMode)
        }
    })
    private val inputHandler = TouchInputHandler(MainActivity.getInstance(), sender)

    var mode: Int = initialMode; private set
    var allowDirectTouch: Boolean = true; private set

    init {
        setMode(mode)
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent, generation)
        TouchInputHandler.setThreeFingerTapListener { if (isCurrent()) onThreeFingerTap() }
        lorieView.setCallback { width, height, transform ->
            if (isCurrent()) {
                inputHandler.handleInputTransformChanged(width, height, transform)
                onViewportChanged(width, height, transform)
            }
        }
        lorieView.setOnTouchListener(::handleMotionEvent)
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
        lorieView.setOnCapturedPointerListener(::handleMotionEvent)
    }

    fun nextMode(): Int {
        val next = nextSupportedInputMode(mode, allowDirectTouch)
        onModeChanged(next)
        if (mode != next) setMode(next)
        return mode
    }

    fun modeLabel(): String = when (mode) {
        TouchInputHandler.InputMode.SIMULATED_TOUCH -> "Touchscreen"
        TouchInputHandler.InputMode.TOUCH -> "Direct touch"
        else -> "Trackpad"
    }

    fun dispose() {
        if (isCurrent()) {
            if (LorieView.connected()) sender.releaseAll()
            MainActivity.getPrefs().pointerCapture.put(false)
            if (lorieView.hasPointerCapture()) lorieView.releasePointerCapture()
            MainActivity.getInstance().setKeyHandler(null, generation)
            TouchInputHandler.setThreeFingerTapListener(null)
        }
        inputHandler.dispose()
        lorieView.setOnTouchListener(null)
        lorieView.setOnGenericMotionListener(null)
        lorieView.setOnCapturedPointerListener(null)
        lorieView.setCallback(null)
    }

    private fun setMode(newMode: Int) {
        require(newMode in 1..3)
        mode = newMode
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(newMode.toString())
        inputHandler.reloadPreferences(prefs)
    }

    fun applyDecision(decision: InputPolicyDecision) {
        if (!isCurrent()) return
        allowDirectTouch = decision.allowDirectTouch; inputHandler.setScrollScales(decision.horizontalScrollScale, decision.verticalScrollScale)
        MainActivity.getPrefs().pointerCapture.put(decision.rendererPointerCapture); setMode(decision.defaultInputMode)
        if (!decision.rendererPointerCapture && lorieView.hasPointerCapture()) lorieView.releasePointerCapture()
        else if (decision.rendererPointerCapture && lorieView.hasWindowFocus() && !lorieView.hasPointerCapture()) { if (!lorieView.isFocused) lorieView.requestFocus(); lorieView.requestPointerCapture() }
    }

    override fun dispatchExternalKeyEvent(event: KeyEvent): Boolean =
        isCurrent() && inputHandler.sendKeyEvent(event)

    override fun dispatchExternalMotionEvent(view: View, event: MotionEvent): Boolean =
        isCurrent() && inputHandler.handleTouchEvent(lorieView, view, event)

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean {
        if (!isCurrent()) return false
        if (!allowDirectTouch && isDirectTouchEvent(event.source, event.getToolType(event.actionIndex), view === lorieView)) onDirectTouchObserved()
        return inputHandler.handleTouchEvent(lorieView, view, event)
    }

    fun reacquireFocus() {
        if (!isCurrent()) return
        lorieView.requestFocus()
        inputHandler.reloadPreferences(MainActivity.getPrefs())
        if (MainActivity.getPrefs().pointerCapture.get() && !lorieView.hasPointerCapture()) {
            lorieView.requestPointerCapture()
        }
        lorieView.refreshRenderer()
    }

    companion object {
        const val DISPLAY_SCALE_PERCENT = 150

        /** Must run before LorieView is measured so Xwayland starts at the scaled resolution. */
        fun configureDisplayScale() {
            MainActivity.getPrefs().apply {
                displayResolutionMode.put("scaled")
                displayScale.put(DISPLAY_SCALE_PERCENT)
                displayStretch.put(true)
                scaleTouchpad.put(true)
            }
        }
    }
}
