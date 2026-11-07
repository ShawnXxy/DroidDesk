package com.orailnoor.droiddesk.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DroidDeskServiceTest {
    @Test
    fun rejectedEnvironmentStopLeavesBridgeAndDisplayRunning() {
        val calls = mutableListOf<String>()
        val rejection = IllegalStateException("Package operation is active")

        val failure = performDesktopTeardown(
            stopEnvironment = {
                calls += "environment"
                throw rejection
            },
            stopLegacyBridge = { calls += "bridge" },
            stopDisplayService = { calls += "display" },
            onFailure = { _, _ -> },
        )

        assertSame(rejection, failure)
        assertEquals(listOf("environment"), calls)
    }

    @Test
    fun admittedEnvironmentStopRunsAllRemainingCleanup() {
        val calls = mutableListOf<String>()
        val bridgeFailure = IllegalStateException("Bridge cleanup failed")

        val failure = performDesktopTeardown(
            stopEnvironment = { calls += "environment" },
            stopLegacyBridge = {
                calls += "bridge"
                throw bridgeFailure
            },
            stopDisplayService = { calls += "display" },
            onFailure = { _, _ -> },
        )

        assertSame(bridgeFailure, failure)
        assertEquals(listOf("environment", "bridge", "display"), calls)
    }
}
