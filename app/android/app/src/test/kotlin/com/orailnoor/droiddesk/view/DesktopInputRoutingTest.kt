package com.orailnoor.droiddesk.view

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopInputRoutingTest {
    @Test
    fun observesInputDevicesOnlyForAndroid37ExternalRenderers() {
        assertFalse(shouldObserveInputDeviceChanges(36, 8))
        assertFalse(shouldObserveInputDeviceChanges(37, Display.DEFAULT_DISPLAY))
        assertTrue(shouldObserveInputDeviceChanges(37, 8))
    }
}
