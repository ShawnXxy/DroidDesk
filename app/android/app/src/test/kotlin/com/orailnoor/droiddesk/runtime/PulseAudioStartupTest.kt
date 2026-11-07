package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseAudioStartupTest {
    @Test
    fun productionPackageKeepsTheDefaultPulseAudioPort() {
        assertEquals(4713, pulseAudioTcpPort("com.orailnoor.droiddesk"))
    }

    @Test
    fun packageVariantsUseAStableNonDefaultPulseAudioPort() {
        val first = pulseAudioTcpPort("com.orailnoor.droiddesk.monitoracceptance")
        val second = pulseAudioTcpPort("com.orailnoor.droiddesk.monitoracceptance")

        assertEquals(first, second)
        assertTrue(first in 4714..5713)
    }

    @Test
    fun unreadyAAudioDaemonFallsBackToPlainPulseAudio() {
        val calls = mutableListOf<String>()
        var readinessChecks = 0

        val ready = startPulseAudioDaemonWithFallback(
            startAAudio = {
                calls += "start-aaudio"
                true
            },
            isReady = {
                readinessChecks++
                calls += "ready-$readinessChecks"
                readinessChecks == 2
            },
            stop = { calls += "stop" },
            startFallback = {
                calls += "start-fallback"
                true
            },
        )

        assertTrue(ready)
        assertEquals(
            listOf(
                "start-aaudio",
                "ready-1",
                "stop",
                "start-fallback",
                "ready-2",
            ),
            calls,
        )
    }

    @Test
    fun readyAAudioDaemonDoesNotStartFallback() {
        val calls = mutableListOf<String>()

        val ready = startPulseAudioDaemonWithFallback(
            startAAudio = {
                calls += "start-aaudio"
                true
            },
            isReady = {
                calls += "ready"
                true
            },
            stop = { calls += "stop" },
            startFallback = {
                calls += "start-fallback"
                true
            },
        )

        assertTrue(ready)
        assertEquals(listOf("start-aaudio", "ready"), calls)
    }
}
