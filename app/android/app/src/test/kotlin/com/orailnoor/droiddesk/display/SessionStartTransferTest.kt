package com.orailnoor.droiddesk.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartTransferTest {
    @Test
    fun rendererReplacementTransfersConsumedSessionStart() {
        val coordinator = DesktopDisplayCoordinator()
        val epoch = coordinator.prepareSession("proot", "xfce4", true)
        val first = coordinator.claim(0) {}
        assertTrue(coordinator.consumeSessionStart(first))

        val replacement = coordinator.claim(0) {}

        assertTrue(coordinator.consumeSessionStart(replacement))
        assertNull(coordinator.beginSessionStart(first))
        assertEquals(epoch, coordinator.beginSessionStart(replacement))
    }
}
