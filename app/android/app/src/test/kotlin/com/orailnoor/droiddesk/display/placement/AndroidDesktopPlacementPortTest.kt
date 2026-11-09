package com.orailnoor.droiddesk.display.placement

import android.content.ActivityNotFoundException
import com.orailnoor.droiddesk.display.AndroidDesktopDisplayHost
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.capability.PlacementMode
import com.orailnoor.droiddesk.display.translateAndroidDesktopLaunchFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDesktopPlacementPortTest {
    @Test
    fun androidHostImplementsPlacementPort() {
        assertTrue(
            DesktopPlacementPort::class.java.isAssignableFrom(
                AndroidDesktopDisplayHost::class.java,
            ),
        )
    }

    @Test
    fun displayLocalStrategyRoutesToLocalPortOperations() {
        val port = RecordingPort(localFocus = true)
        val strategy = DisplayLocalRestartAdapter(selection(PlacementMode.DISPLAY_LOCAL))
        val request = launchRequest(display(id = 7, key = "local"))

        strategy.launch(port, request)
        assertTrue(strategy.focus(port, request.requestId))
        strategy.cancel(port, request.requestId)

        assertEquals(
            listOf("launchLocal:7", "focusLocal:41", "cancelLocal:41"),
            port.calls,
        )
    }

    @Test
    fun movableStrategyRoutesToDisplayAndTaskPortOperations() {
        val port = RecordingPort(taskFocus = true)
        val strategy = MovableSecondaryDisplayAdapter(selection(PlacementMode.MOVABLE))
        val request = launchRequest(display(id = 9, key = "display"))

        strategy.launch(port, request)
        assertTrue(strategy.focus(port, request.requestId))
        strategy.cancel(port, request.requestId)

        assertEquals(
            listOf("launchOnDisplay:9", "focusTask:41", "cancelTask:41"),
            port.calls,
        )
    }

    @Test
    fun securityExceptionTranslatesToDisplayNotAllowed() {
        val translated = translateAndroidDesktopLaunchFailure(SecurityException("denied"))

        assertEquals("DISPLAY_NOT_ALLOWED", translated.code)
        assertEquals("Android denied activity placement on this display", translated.message)
    }

    @Test
    fun illegalArgumentExceptionTranslatesToDisplayUnavailable() {
        val translated = translateAndroidDesktopLaunchFailure(IllegalArgumentException("gone"))

        assertEquals("DISPLAY_UNAVAILABLE", translated.code)
        assertEquals("The display disconnected during launch", translated.message)
    }

    @Test
    fun activityNotFoundExceptionTranslatesToDisplayNotAllowed() {
        val translated = translateAndroidDesktopLaunchFailure(
            ActivityNotFoundException("missing"),
        )

        assertEquals("DISPLAY_NOT_ALLOWED", translated.code)
        assertEquals("The native desktop activity could not be opened", translated.message)
    }

    @Test
    fun unrelatedLaunchFailureIsRethrown() {
        val failure = IllegalStateException("unexpected")

        val thrown = assertThrows(IllegalStateException::class.java) {
            translateAndroidDesktopLaunchFailure(failure)
        }

        assertSame(failure, thrown)
    }

    private fun selection(mode: PlacementMode) = PlacementSelection(
        mode = mode,
        source = PlacementSelectionSource.RUNTIME_EVIDENCE,
        allowAutomaticMoves = mode == PlacementMode.MOVABLE,
        reason = "test",
    )

    private fun launchRequest(display: DesktopDisplay) = DesktopLaunchRequest(
        display = display,
        requestId = 41,
        sessionGeneration = 3,
        mode = "termux",
        desktopEnv = "xfce4",
    )

    private fun display(id: Int, key: String) = DesktopDisplay(
        id = id,
        key = key,
        name = "Display $id",
        isDefault = id == 0,
        eligible = true,
        reason = null,
    )
}

private class RecordingPort(
    private val localFocus: Boolean = false,
    private val taskFocus: Boolean = false,
) : DesktopPlacementPort {
    val calls = mutableListOf<String>()

    override fun launchLocal(request: DesktopLaunchRequest) {
        calls += "launchLocal:${request.display.id}"
    }

    override fun launchOnDisplay(request: DesktopLaunchRequest) {
        calls += "launchOnDisplay:${request.display.id}"
    }

    override fun focusLocal(requestId: Long): Boolean {
        calls += "focusLocal:$requestId"
        return localFocus
    }

    override fun focusTask(requestId: Long): Boolean {
        calls += "focusTask:$requestId"
        return taskFocus
    }

    override fun cancelLocal(requestId: Long) {
        calls += "cancelLocal:$requestId"
    }

    override fun cancelTask(requestId: Long) {
        calls += "cancelTask:$requestId"
    }
}
