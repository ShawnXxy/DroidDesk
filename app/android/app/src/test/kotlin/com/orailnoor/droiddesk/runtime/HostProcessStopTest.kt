package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProcessStopTest {

    @Test
    fun alreadyStoppedProcessNeedsNoSignal() {
        val signals = mutableListOf<Boolean>()

        stopHostProcessWithFallback(
            processName = "pulseaudio",
            isAlive = { false },
            terminate = { force -> signals += force },
            awaitExit = { true },
        )

        assertTrue(signals.isEmpty())
    }

    @Test
    fun gracefulExitAvoidsForceKill() {
        val signals = mutableListOf<Boolean>()
        var alive = true

        stopHostProcessWithFallback(
            processName = "pulseaudio",
            isAlive = { alive },
            terminate = { force ->
                signals += force
                alive = false
            },
            awaitExit = { !alive },
        )

        assertEquals(listOf(false), signals)
    }

    @Test
    fun forceKillIsOnlyUsedAfterGracefulTimeout() {
        val signals = mutableListOf<Boolean>()
        var alive = true

        stopHostProcessWithFallback(
            processName = "pulseaudio",
            isAlive = { alive },
            terminate = { force ->
                signals += force
                if (force) alive = false
            },
            awaitExit = { !alive },
        )

        assertEquals(listOf(false, true), signals)
    }

    @Test
    fun forceKillTimeoutIsReported() {
        val error = runCatching {
            stopHostProcessWithFallback(
                processName = "pulseaudio",
                isAlive = { true },
                terminate = {},
                awaitExit = { false },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("pulseaudio"))
    }
}
