package com.orailnoor.droiddesk.display.placement

import android.content.ActivityNotFoundException
import com.orailnoor.droiddesk.display.AndroidDesktopPlacementPort
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.translateAndroidDesktopLaunchFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDesktopPlacementPortTest {
    @Test
    fun localLaunchUsesTheResolvedDisplayConnection() {
        val requested = launchRequest(display(id = 7, key = "requested"))
        val resolved = requested.copy(display = display(id = 7, key = "resolved"))
        var launched: DesktopLaunchRequest? = null
        val port = port(
            resolveLaunch = {
                assertSame(requested, it)
                resolved
            },
            launchLocal = { launched = it },
        )

        port.launchLocal(requested)

        assertSame(resolved, launched)
    }

    @Test
    fun displayLaunchUsesTheResolvedDisplayConnection() {
        val requested = launchRequest(display(id = 9, key = "requested"))
        val resolved = requested.copy(display = display(id = 9, key = "resolved"))
        var launched: DesktopLaunchRequest? = null
        val port = port(
            resolveLaunch = { resolved },
            launchOnDisplay = { launched = it },
        )

        port.launchOnDisplay(requested)

        assertSame(resolved, launched)
    }

    @Test
    fun localFocusReturnsTheActivityResultWithoutTouchingTasks() {
        val calls = mutableListOf<String>()
        val port = port(
            focusLocal = {
                calls += "local:$it"
                true
            },
            focusTask = {
                calls += "task:$it"
                false
            },
        )

        assertTrue(port.focusLocal(41))

        assertEquals(listOf("local:41"), calls)
    }

    @Test
    fun taskFocusReturnsTheTaskResultWithoutTouchingTheLocalActivity() {
        val calls = mutableListOf<String>()
        val port = port(
            focusLocal = {
                calls += "local:$it"
                false
            },
            focusTask = {
                calls += "task:$it"
                true
            },
        )

        assertTrue(port.focusTask(42))

        assertEquals(listOf("task:42"), calls)
    }

    @Test
    fun localCancellationFinishesOnlyTheMatchingLocalActivity() {
        val calls = mutableListOf<String>()
        val port = port(
            cancelLocal = { calls += "local:$it" },
            cancelTask = { calls += "task:$it" },
        )

        port.cancelLocal(43)

        assertEquals(listOf("local:43"), calls)
    }

    @Test
    fun taskCancellationRemovesOnlyTheMatchingAndroidTasks() {
        val calls = mutableListOf<String>()
        val port = port(
            cancelLocal = { calls += "local:$it" },
            cancelTask = { calls += "task:$it" },
        )

        port.cancelTask(44)

        assertEquals(listOf("task:44"), calls)
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

    private fun port(
        resolveLaunch: (DesktopLaunchRequest) -> DesktopLaunchRequest = { it },
        launchLocal: (DesktopLaunchRequest) -> Unit = {},
        launchOnDisplay: (DesktopLaunchRequest) -> Unit = {},
        focusLocal: (Long) -> Boolean = { false },
        focusTask: (Long) -> Boolean = { false },
        cancelLocal: (Long) -> Unit = {},
        cancelTask: (Long) -> Unit = {},
    ) = AndroidDesktopPlacementPort(
        resolveLaunch = resolveLaunch,
        launchLocal = launchLocal,
        launchOnDisplay = launchOnDisplay,
        focusLocal = focusLocal,
        focusTask = focusTask,
        cancelLocal = cancelLocal,
        cancelTask = cancelTask,
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
