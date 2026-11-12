package com.orailnoor.droiddesk.view

import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import com.orailnoor.droiddesk.display.DisplayEnvironment
import com.orailnoor.droiddesk.display.DisplaySettings
import com.orailnoor.droiddesk.display.ProfileKind
import com.orailnoor.droiddesk.display.RenderPolicy
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PointerAssociationEvidence
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.orailnoor.droiddesk.display.input.DesktopInputPolicy
import com.orailnoor.droiddesk.display.input.ExternalInputRoute
import com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference
import com.orailnoor.droiddesk.display.input.InputPolicyDecision
import com.orailnoor.droiddesk.x11.ExternalDesktopInputBridge
import com.orailnoor.droiddesk.x11.ExternalDesktopInputTarget
import com.orailnoor.droiddesk.x11.isDirectTouchEvent
import com.orailnoor.droiddesk.x11.nextSupportedInputMode
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.InputStrategyInterface
import com.termux.x11.input.InputStub
import com.termux.x11.input.TouchInputHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopInputRoutingTest {
    private val createdTargets = mutableListOf<FakeExternalInputTarget>()

    @After
    fun tearDown() {
        createdTargets.forEach(ExternalDesktopInputBridge::detach)
        createdTargets.clear()
    }

    @Test
    fun nativeAndPhoneRelayRoutesAreDrivenFromSinglePolicyDecision() {
        val harness = RouterHarness(
            snapshot = externalSnapshot(
                displayId = 10,
                pointerAssociation = PointerAssociationEvidence.UNKNOWN,
                horizontalScrollFactor = 36f,
                verticalScrollFactor = 72f,
            ),
        )

        val relayDecision = checkNotNull(harness.router.refresh())

        assertEquals(1, harness.contextReads)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, relayDecision.defaultInputMode)
        assertFalse(relayDecision.allowDirectTouch)
        assertEquals(ExternalInputRoute.PHONE_RELAY, relayDecision.externalInputRoute)
        assertFalse(relayDecision.rendererPointerCapture)
        assertTrue(relayDecision.relayPointerCapture)
        assertEquals(-36f, relayDecision.horizontalScrollScale, 0f)
        assertEquals(-72f, relayDecision.verticalScrollScale, 0f)
        assertEquals(listOf(relayDecision), harness.appliedDecisions)
        assertTrue(ExternalDesktopInputBridge.isAttached())
        assertTrue(ExternalDesktopInputBridge.shouldCapturePointer())
        assertEquals(listOf("open"), harness.relayLifecycle)

        harness.snapshot = externalSnapshot(
            displayId = 10,
            pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
        )
        val nativeDecision = checkNotNull(harness.router.refresh())

        assertEquals(2, harness.contextReads)
        assertEquals(ExternalInputRoute.NATIVE, nativeDecision.externalInputRoute)
        assertTrue(nativeDecision.rendererPointerCapture)
        assertFalse(nativeDecision.relayPointerCapture)
        assertEquals(listOf(relayDecision, nativeDecision), harness.appliedDecisions)
        assertFalse(ExternalDesktopInputBridge.isAttached())
        assertFalse(ExternalDesktopInputBridge.shouldCapturePointer())
        assertEquals(listOf("open", "close"), harness.relayLifecycle)
    }

    @Test
    fun unavailableDisplayContextFallsBackToDeviceCapabilitiesWithoutAbortingAttachment() {
        val fallback = fallbackInputCapabilities(
            hasTouchscreen = true,
            hasExternalPointer = true,
            hasMouse = true,
            hasTouchpad = false,
            hasKeyboard = true,
            hasStylus = false,
        )
        assertEquals(TouchEvidence.UNKNOWN_ASSOCIATION, fallback.touch)
        assertEquals(PointerAssociationEvidence.UNKNOWN, fallback.pointerAssociation)
        assertTrue(fallback.hasMouse)
        assertTrue(fallback.hasKeyboard)
    }

    @Test
    fun defaultDisplayInternalTouchMapsToPhoneAndLargeTouchClasses() {
        val phoneCapabilities = effectiveInputCapabilities(
            isDefaultDisplay = true,
            hasInternalTouchscreen = true,
            directTouchObserved = false,
            rawInput = InputCapabilities(
                touch = TouchEvidence.UNKNOWN_ASSOCIATION,
                pointerAssociation = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
                hasMouse = false,
                hasTouchpad = false,
                hasKeyboard = false,
                hasStylus = false,
            ),
        )
        assertEquals(TouchEvidence.CURRENT_DISPLAY, phoneCapabilities.touch)
        assertEquals(
            PresentationClass.PHONE_TOUCH,
            resolvePresentationClass(true, ProfileKind.PHONE, phoneCapabilities.touch),
        )
        assertEquals(
            PresentationClass.LARGE_TOUCH,
            resolvePresentationClass(true, ProfileKind.WIDE, phoneCapabilities.touch),
        )

        val externalCapabilities = effectiveInputCapabilities(
            isDefaultDisplay = false,
            hasInternalTouchscreen = true,
            directTouchObserved = false,
            rawInput = phoneCapabilities.copy(touch = TouchEvidence.UNKNOWN_ASSOCIATION),
        )
        assertEquals(TouchEvidence.UNKNOWN_ASSOCIATION, externalCapabilities.touch)
        assertEquals(
            PresentationClass.EXTERNAL_POINTER,
            resolvePresentationClass(false, ProfileKind.MONITOR, externalCapabilities.touch),
        )
    }

    @Test
    fun directTouchPromotionUpdatesExternalTouchWithoutCorruptingSavedModeOrMouseRelay() {
        assertTrue(
            isDirectTouchEvent(
                source = InputDevice.SOURCE_TOUCHSCREEN,
                toolType = MotionEvent.TOOL_TYPE_FINGER,
                isRendererView = true,
            ),
        )
        assertFalse(
            isDirectTouchEvent(
                source = InputDevice.SOURCE_TOUCHSCREEN,
                toolType = MotionEvent.TOOL_TYPE_FINGER,
                isRendererView = false,
            ),
        )
        assertFalse(
            isDirectTouchEvent(
                source = InputDevice.SOURCE_MOUSE,
                toolType = MotionEvent.TOOL_TYPE_FINGER,
                isRendererView = true,
            ),
        )
        assertFalse(
            isDirectTouchEvent(
                source = InputDevice.SOURCE_TOUCHPAD,
                toolType = MotionEvent.TOOL_TYPE_FINGER,
                isRendererView = true,
            ),
        )

        // Default mode case: EXTERNAL_POINTER (TRACKPAD + renderer capture) promotes to EXTERNAL_TOUCH (TOUCH, no capture).
        val nativeHarness = RouterHarness(
            snapshot = externalSnapshot(
                displayId = 20,
                pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
                touch = TouchEvidence.UNKNOWN_ASSOCIATION,
            ),
        )
        val initialNative = checkNotNull(nativeHarness.refresh())
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, initialNative.defaultInputMode)
        assertFalse(initialNative.allowDirectTouch)
        assertTrue(initialNative.rendererPointerCapture)
        assertEquals(
            TouchInputHandler.InputMode.TRACKPAD,
            nextSupportedInputMode(TouchInputHandler.InputMode.SIMULATED_TOUCH, initialNative.allowDirectTouch),
        )

        val promotedNative = checkNotNull(nativeHarness.onDirectTouchObserved(20, deviceId = 41))
        assertEquals(TouchInputHandler.InputMode.TOUCH, promotedNative.defaultInputMode)
        assertTrue(promotedNative.allowDirectTouch)
        assertFalse(promotedNative.rendererPointerCapture)
        assertEquals(
            TouchInputHandler.InputMode.TOUCH,
            nextSupportedInputMode(TouchInputHandler.InputMode.SIMULATED_TOUCH, promotedNative.allowDirectTouch),
        )

        // Moving to a different display does not carry over promoted touch from display 20.
        nativeHarness.snapshot = externalSnapshot(
            displayId = 22,
            pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
            touch = TouchEvidence.UNKNOWN_ASSOCIATION,
        )
        val movedDisplayDecision = nativeHarness.refresh()
        assertFalse(movedDisplayDecision.allowDirectTouch)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, movedDisplayDecision.defaultInputMode)
        assertTrue(movedDisplayDecision.rendererPointerCapture)

        // Explicit saved mode + PHONE_RELAY mouse rule: promotion enables direct touch without resetting saved mode or relay.
        val relayHarness = RouterHarness(
            snapshot = externalSnapshot(
                displayId = 21,
                pointerAssociation = PointerAssociationEvidence.UNKNOWN,
                touch = TouchEvidence.UNKNOWN_ASSOCIATION,
                savedSettings = DisplaySettings(
                    renderPolicy = RenderPolicy.NATIVE,
                    uiScalePercent = null,
                    inputMode = TouchInputHandler.InputMode.SIMULATED_TOUCH,
                    inputRoute = ExternalInputRoutePreference.AUTOMATIC,
                ),
            ),
        )
        checkNotNull(relayHarness.refresh())

        val promotedRelay = checkNotNull(relayHarness.onDirectTouchObserved(21, deviceId = 42))
        assertTrue(promotedRelay.allowDirectTouch)
        assertEquals(TouchInputHandler.InputMode.SIMULATED_TOUCH, promotedRelay.defaultInputMode)
        assertEquals(ExternalInputRoute.PHONE_RELAY, promotedRelay.externalInputRoute)
        assertFalse(promotedRelay.rendererPointerCapture)
        assertTrue(promotedRelay.relayPointerCapture)

        // Synthetic/injected direct touch (deviceId <= 0) promotes even without InputManager touchscreen devices and does not re-refresh per frame.
        val syntheticHarness = RouterHarness(
            snapshot = externalSnapshot(
                displayId = 25,
                pointerAssociation = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
                touch = TouchEvidence.NONE,
                hasMouse = false,
                hasInternalTouchscreen = false,
                touchscreenDeviceIds = emptySet(),
            ),
        )
        syntheticHarness.refresh()
        val syntheticPromoted = checkNotNull(syntheticHarness.onDirectTouchObserved(25, deviceId = -1))
        assertTrue(syntheticPromoted.allowDirectTouch)
        assertEquals(TouchInputHandler.InputMode.TOUCH, syntheticPromoted.defaultInputMode)
        val readsAfterPromote = syntheticHarness.contextReads
        syntheticHarness.onDirectTouchObserved(25, deviceId = -1)
        assertEquals(readsAfterPromote, syntheticHarness.contextReads)
    }

    @Test
    fun explicitRouteAndSavedInputModeOverridesAreApplied() {
        val harness = RouterHarness(
            snapshot = externalSnapshot(
                displayId = 12,
                pointerAssociation = PointerAssociationEvidence.UNKNOWN,
                touch = TouchEvidence.NONE,
                savedSettings = DisplaySettings(
                    renderPolicy = RenderPolicy.NATIVE,
                    uiScalePercent = null,
                    inputMode = TouchInputHandler.InputMode.TOUCH,
                    inputRoute = ExternalInputRoutePreference.NATIVE,
                ),
            ),
        )

        // Saved TOUCH falls back to TRACKPAD while direct touch is unavailable; NATIVE route overrides UNKNOWN association.
        val nativeOverride = checkNotNull(harness.refresh())
        assertEquals(ExternalInputRoute.NATIVE, nativeOverride.externalInputRoute)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, nativeOverride.defaultInputMode)
        assertTrue(nativeOverride.rendererPointerCapture)
        assertFalse(nativeOverride.relayPointerCapture)
        assertFalse(ExternalDesktopInputBridge.isAttached())

        // PHONE_RELAY override wins even on desktop UI mode with TARGET_DISPLAY pointer.
        harness.snapshot = externalSnapshot(
            displayId = 12,
            pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
            desktopUiMode = true,
            savedSettings = DisplaySettings(
                renderPolicy = RenderPolicy.NATIVE,
                uiScalePercent = null,
                inputMode = TouchInputHandler.InputMode.SIMULATED_TOUCH,
                inputRoute = ExternalInputRoutePreference.PHONE_RELAY,
            ),
        )
        val relayOverride = checkNotNull(harness.refresh())
        assertEquals(ExternalInputRoute.PHONE_RELAY, relayOverride.externalInputRoute)
        assertEquals(TouchInputHandler.InputMode.SIMULATED_TOUCH, relayOverride.defaultInputMode)
        assertFalse(relayOverride.rendererPointerCapture)
        assertTrue(relayOverride.relayPointerCapture)
        assertTrue(ExternalDesktopInputBridge.shouldCapturePointer())
    }

    @Test
    fun rendererAndRelayCaptureStayMutuallyExclusiveAndRelayCancelsRetriesWhenDisabledOrDetached() {
        var relayFocused = true
        var relayCaptured = false
        var retryPending = false
        var requestCount = 0
        var releaseCount = 0

        val relayCapture = RelayPointerCaptureController(
            shouldCapture = ExternalDesktopInputBridge::shouldCapturePointer,
            hasWindowFocus = { relayFocused },
            hasPointerCapture = { relayCaptured },
            requestPointerCapture = {
                relayCaptured = true
                requestCount++
            },
            releasePointerCapture = {
                relayCaptured = false
                releaseCount++
            },
            scheduleRetry = { retryPending = true },
            cancelRetry = { retryPending = false },
        )
        ExternalDesktopInputBridge.onCaptureChanged = relayCapture::sync

        val harness = RouterHarness(
            snapshot = externalSnapshot(
                displayId = 15,
                pointerAssociation = PointerAssociationEvidence.UNKNOWN,
                hasMouse = true,
            ),
        )
        val initial = checkNotNull(harness.refresh())
        assertFalse(initial.rendererPointerCapture && initial.relayPointerCapture)
        assertTrue(relayCaptured)
        assertEquals(1, requestCount)

        // Simulate transient capture loss while relay capture is still required -> schedules retry.
        relayCaptured = false
        relayCapture.onPointerCaptureChanged(false)
        assertTrue(retryPending)

        // Explicit PHONE_RELAY without pointer hardware keeps bridge open for keyboard but disables relayPointerCapture!
        relayCaptured = true
        harness.snapshot = externalSnapshot(
            displayId = 15,
            pointerAssociation = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
            hasMouse = false,
            savedSettings = DisplaySettings(
                renderPolicy = RenderPolicy.NATIVE,
                uiScalePercent = null,
                inputMode = TouchInputHandler.InputMode.TRACKPAD,
                inputRoute = ExternalInputRoutePreference.PHONE_RELAY,
            ),
        )
        val keyboardOnlyRelay = checkNotNull(harness.refresh())
        assertTrue(ExternalDesktopInputBridge.isAttached())
        assertFalse(keyboardOnlyRelay.rendererPointerCapture)
        assertFalse(keyboardOnlyRelay.relayPointerCapture)
        assertFalse(retryPending)
        assertFalse(relayCaptured)
        assertEquals(1, releaseCount)

        // Capture-lost callback after policy disabled capture must NOT re-schedule retry.
        relayCapture.onPointerCaptureChanged(false)
        assertFalse(retryPending)

        // Stale controller release must not clobber a newer attached controller!
        val staleTarget = newTarget()
        harness.snapshot = externalSnapshot(
            displayId = 15,
            pointerAssociation = PointerAssociationEvidence.UNKNOWN,
            hasMouse = true,
        )
        checkNotNull(harness.refresh())
        assertTrue(ExternalDesktopInputBridge.shouldCapturePointer())
        assertTrue(relayCaptured)

        harness.router.release(staleTarget, finishRelay = false)
        assertTrue(ExternalDesktopInputBridge.shouldCapturePointer())
        assertTrue(relayCaptured)

        // Owning controller release detaches bridge, releases relay capture, and closes relay.
        relayCapture.onPointerCaptureChanged(false)
        assertTrue(retryPending)
        harness.router.release(harness.target, finishRelay = true)
        assertFalse(ExternalDesktopInputBridge.isAttached())
        assertFalse(ExternalDesktopInputBridge.shouldCapturePointer())
        assertFalse(retryPending)
        assertEquals("close", harness.relayLifecycle.last())

        ExternalDesktopInputBridge.onCaptureChanged = null
    }

    @Test
    fun inputDeviceChangesReevaluateRoutingAndClearRemovedExternalTouchscreen() {
        val harness = RouterHarness(
            snapshot = externalSnapshot(
                displayId = 18,
                pointerAssociation = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
                touch = TouchEvidence.UNKNOWN_ASSOCIATION,
                hasMouse = false,
            ),
        )

        val withoutMouse = checkNotNull(harness.refresh())
        assertEquals(ExternalInputRoute.NATIVE, withoutMouse.externalInputRoute)
        assertFalse(withoutMouse.rendererPointerCapture)
        assertFalse(withoutMouse.relayPointerCapture)

        // Connecting a Bluetooth mouse on pre-API-37 switches generic external to PHONE_RELAY.
        harness.snapshot = externalSnapshot(
            displayId = 18,
            pointerAssociation = PointerAssociationEvidence.UNKNOWN,
            touch = TouchEvidence.UNKNOWN_ASSOCIATION,
            hasMouse = true,
            touchscreenDeviceIds = setOf(1, 42),
        )
        val withMouse = harness.refresh()
        assertEquals(ExternalInputRoute.PHONE_RELAY, withMouse.externalInputRoute)
        assertTrue(withMouse.relayPointerCapture)

        // Observing direct touch from device 42 promotes EXTERNAL_TOUCH.
        val promoted = checkNotNull(harness.onDirectTouchObserved(18, deviceId = 42))
        assertTrue(promoted.allowDirectTouch)
        assertEquals(TouchInputHandler.InputMode.TOUCH, promoted.defaultInputMode)

        // Removing an unrelated keyboard/gamepad while touch device 42 stays connected preserves direct touch!
        val afterUnrelatedDeviceRemoved = harness.refresh()
        assertTrue(afterUnrelatedDeviceRemoved.allowDirectTouch)
        assertEquals(TouchInputHandler.InputMode.TOUCH, afterUnrelatedDeviceRemoved.defaultInputMode)

        // Removing touch device 42 itself (while phone internal touch device 1 remains) clears external touch promotion.
        harness.snapshot = externalSnapshot(
            displayId = 18,
            pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
            touch = TouchEvidence.UNKNOWN_ASSOCIATION,
            hasMouse = true,
            hasInternalTouchscreen = true,
            touchscreenDeviceIds = setOf(1),
        )
        val afterTouchRemoved = harness.refresh()
        assertFalse(afterTouchRemoved.allowDirectTouch)
        assertEquals(TouchInputHandler.InputMode.TRACKPAD, afterTouchRemoved.defaultInputMode)
        assertTrue(afterTouchRemoved.rendererPointerCapture)
    }

    @Test
    fun wheelScrollerAppliesAndroidFactorsPreservingDirectionAndGuardsInvalidValues() {
        val events = mutableListOf<Pair<Float, Float>>()
        val sender = InputEventSender(
            object : InputStub {
                override fun sendMouseEvent(x: Float, y: Float, b: Int, d: Boolean, r: Boolean) {}
                override fun sendMouseWheelEvent(deltaX: Float, deltaY: Float) {
                    events += deltaX to deltaY
                }
                override fun sendKeyEvent(s: Int, k: Int, d: Boolean): Boolean = false
                override fun sendTextEvent(utf8Bytes: ByteArray) {}
                override fun sendTouchEvent(a: Int, p: Int, x: Int, y: Int) {}
                override fun sendStylusEvent(
                    x: Float, y: Float, p: Int, tx: Int, ty: Int, o: Int, b: Int, e: Boolean, m: Boolean,
                ) {}
            },
        )
        val scroller = TouchInputHandler.WheelScroller()

        // Default fallback before first policy apply preserves historical -100 multiplier and direction.
        scroller.dispatch(sender, 1f, -2f)
        assertEquals(-100f, events.last().first, 0f)
        assertEquals(200f, events.last().second, 0f)

        val decision = DesktopInputPolicy.resolve(
            presentationClass = PresentationClass.EXTERNAL_POINTER,
            environment = DisplayEnvironment.OTHER_EXTERNAL,
            capabilities = InputCapabilities(
                touch = TouchEvidence.NONE,
                pointerAssociation = PointerAssociationEvidence.TARGET_DISPLAY,
                hasMouse = true,
                hasTouchpad = false,
                hasKeyboard = true,
                hasStylus = false,
            ),
            savedInputMode = null,
            routePreference = ExternalInputRoutePreference.AUTOMATIC,
            desktopUiMode = false,
            horizontalScrollFactor = sanitizeScrollFactor(32.5f),
            verticalScrollFactor = sanitizeScrollFactor(64f),
        )
        scroller.setScales(decision.horizontalScrollScale, decision.verticalScrollScale)

        // Distinct horizontal (-32.5) and vertical (-64) scales verify parameter ordering and sign preservation.
        scroller.dispatch(sender, 2f, 1f)
        assertEquals(-65f, events.last().first, 0f)
        assertEquals(-64f, events.last().second, 0f)

        scroller.dispatch(sender, -2f, -1.5f)
        assertEquals(65f, events.last().first, 0f)
        assertEquals(96f, events.last().second, 0f)

        // Non-finite or zero scroll factors/scales fall back safely.
        assertEquals(64f, sanitizeScrollFactor(0f, 64f), 0f)
        assertEquals(64f, sanitizeScrollFactor(Float.NaN, 64f), 0f)
        scroller.setScales(Float.NaN, 0f)
        scroller.dispatch(sender, 1f, 1f)
        assertEquals(-100f, events.last().first, 0f)
        assertEquals(-100f, events.last().second, 0f)
    }

    @Test
    fun unchangedModePreservesHeldDragStrategyAndModeSwitchReleasesHeldButtons() {
        val mouseButtons = mutableListOf<Pair<Int, Boolean>>()
        val sender = InputEventSender(
            object : InputStub {
                override fun sendMouseEvent(x: Float, y: Float, button: Int, down: Boolean, relative: Boolean) {
                    if (button != InputStub.BUTTON_UNDEFINED) mouseButtons += button to down
                }
                override fun sendMouseWheelEvent(deltaX: Float, deltaY: Float) {}
                override fun sendKeyEvent(s: Int, k: Int, d: Boolean): Boolean = false
                override fun sendTextEvent(utf8Bytes: ByteArray) {}
                override fun sendTouchEvent(a: Int, p: Int, x: Int, y: Int) {}
                override fun sendStylusEvent(
                    x: Float, y: Float, p: Int, tx: Int, ty: Int, o: Int, b: Int, e: Boolean, m: Boolean,
                ) {}
            },
        )
        fun createStrategy(mode: Int): InputStrategyInterface =
            if (mode == TouchInputHandler.InputMode.TOUCH) {
                InputStrategyInterface.NullInputStrategy()
            } else {
                InputStrategyInterface.TrackpadInputStrategy(sender)
            }

        val mainTracker = TouchInputHandler.ModeStrategyTracker(false)
        val touchpadTracker = TouchInputHandler.ModeStrategyTracker(true)

        val initialMain = mainTracker.update(TouchInputHandler.InputMode.TRACKPAD, sender::releaseAll, ::createStrategy)
        val initialTouchpad = touchpadTracker.update(TouchInputHandler.InputMode.TRACKPAD, sender::releaseAll, ::createStrategy)
        assertTrue(initialMain.onPressAndHold(InputStub.BUTTON_LEFT, true))
        assertEquals(listOf(InputStub.BUTTON_LEFT to true), mouseButtons)

        // Policy refresh with unchanged TRACKPAD mode keeps the exact strategy and does NOT release the held button.
        val refreshedMain = mainTracker.update(TouchInputHandler.InputMode.TRACKPAD, sender::releaseAll, ::createStrategy)
        assertSame(initialMain, refreshedMain)
        assertEquals(listOf(InputStub.BUTTON_LEFT to true), mouseButtons)

        // Nested touchpad handler stays in TRACKPAD and preserves its strategy even when main mode switches to TOUCH.
        val refreshedTouchpad = touchpadTracker.update(TouchInputHandler.InputMode.TOUCH, sender::releaseAll, ::createStrategy)
        assertSame(initialTouchpad, refreshedTouchpad)
        assertEquals(listOf(InputStub.BUTTON_LEFT to true), mouseButtons)

        // Switching main handler to TOUCH releases held buttons before replacing the strategy.
        val touchStrategy = mainTracker.update(TouchInputHandler.InputMode.TOUCH, sender::releaseAll, ::createStrategy)
        assertTrue(touchStrategy is InputStrategyInterface.NullInputStrategy)
        assertTrue(mouseButtons.contains(InputStub.BUTTON_LEFT to false))
    }

    private fun newTarget() = FakeExternalInputTarget().also(createdTargets::add)

    private fun externalSnapshot(
        displayId: Int,
        pointerAssociation: PointerAssociationEvidence,
        touch: TouchEvidence = TouchEvidence.NONE,
        hasMouse: Boolean = true,
        hasInternalTouchscreen: Boolean = true,
        touchscreenDeviceIds: Set<Int> = setOf(1, 41, 42),
        desktopUiMode: Boolean = false,
        savedSettings: DisplaySettings? = null,
        horizontalScrollFactor: Float = 48f,
        verticalScrollFactor: Float = 64f,
    ) = DesktopInputContext(
        displayId = displayId,
        environment = DisplayEnvironment.OTHER_EXTERNAL,
        profileKind = ProfileKind.MONITOR,
        desktopUiMode = desktopUiMode,
        savedSettings = savedSettings,
        rawInput = InputCapabilities(
            touch = touch,
            pointerAssociation = pointerAssociation,
            hasMouse = hasMouse,
            hasTouchpad = false,
            hasKeyboard = true,
            hasStylus = false,
        ),
        hasInternalTouchscreen = hasInternalTouchscreen,
        touchscreenDeviceIds = touchscreenDeviceIds,
        horizontalScrollFactor = horizontalScrollFactor,
        verticalScrollFactor = verticalScrollFactor,
    )

    private inner class RouterHarness(var snapshot: DesktopInputContext?) {
        val target = newTarget()
        val appliedDecisions = mutableListOf<InputPolicyDecision>()
        val relayLifecycle = mutableListOf<String>()
        var contextReads = 0

        val router = DesktopInputRouter(
            readContext = {
                contextReads++
                snapshot
            },
            applyRenderer = { decision ->
                appliedDecisions += decision
                target
            },
            openRelay = { relayLifecycle += "open" },
            closeRelay = { relayLifecycle += "close" },
        )

        fun refresh(): InputPolicyDecision = checkNotNull(router.refresh())

        fun onDirectTouchObserved(displayId: Int, deviceId: Int): InputPolicyDecision? =
            if (router.promoteDirectTouch(displayId, deviceId)) router.refresh() else appliedDecisions.lastOrNull()
    }

    private class FakeExternalInputTarget : ExternalDesktopInputTarget {
        override fun dispatchExternalKeyEvent(event: android.view.KeyEvent): Boolean = false
        override fun dispatchExternalMotionEvent(view: android.view.View, event: MotionEvent): Boolean = false
    }
}
