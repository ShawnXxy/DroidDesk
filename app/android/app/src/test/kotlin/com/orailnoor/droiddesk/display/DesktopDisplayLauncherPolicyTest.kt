package com.orailnoor.droiddesk.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDisplayLauncherPolicyTest {

    @Test
    fun android29DoesNotCallTheApi30DisplayAccessor() {
        var api30AccessorCalled = false

        val display = activityDisplayForSdk(
            sdkInt = 29,
            api30Display = {
                api30AccessorCalled = true
                "activity"
            },
            decorDisplay = { "decor" },
        )

        assertEquals("decor", display)
        assertFalse(api30AccessorCalled)
    }

    @Test
    fun android30PrefersTheActivityDisplayAndFallsBackToDecor() {
        assertEquals(
            "activity",
            activityDisplayForSdk(30, { "activity" }, { "decor" }),
        )
        assertEquals(
            "decor",
            activityDisplayForSdk<String>(30, { null }, { "decor" }),
        )
    }

    @Test
    fun repositoryDisplayKeepsItsStableConnectionKey() {
        val request = display(id = 0, key = "repository")
        val repository = display(id = 0, key = "repository")
        val bound = display(id = 0, key = "bound:0")

        assertSame(repository, resolveLaunchDisplay(request, repository, bound))
    }

    @Test
    fun currentExternalDisplayReusesOnlyTheMatchingBoundConnection() {
        val request = display(id = 30, key = "bound:30:4")
        val bound = display(id = 30, key = "bound:30:4")

        assertSame(bound, resolveLaunchDisplay(request, null, bound))
        val error = assertThrows(DesktopDisplayException::class.java) {
            resolveLaunchDisplay(request, null, bound.copy(key = "bound:30:5"))
        }
        assertEquals("DISPLAY_UNAVAILABLE", error.code)
        assertTrue(error.message!!.contains("launchable"))
    }

    @Test
    fun boundConnectionKeyChangesWhenAndroidReusesTheDisplayId() {
        val keys = BoundDisplayConnectionKeys()
        val activity = Any()
        val originalDisplay = Any()
        val originalKey = keys.key(activity, originalDisplay, displayId = 7)

        assertEquals(originalKey, keys.key(activity, originalDisplay, displayId = 7))
        val replacementKey = keys.key(activity, Any(), displayId = 7)
        assertNotEquals(originalKey, replacementKey)

        val error = assertThrows(DesktopDisplayException::class.java) {
            resolveLaunchDisplay(
                request = display(id = 7, key = originalKey),
                repositoryDisplay = null,
                boundDisplay = display(id = 7, key = replacementKey),
            )
        }
        assertEquals("DISPLAY_UNAVAILABLE", error.code)
    }

    @Test
    fun staleConnectionKeyCannotAttachAfterDisplayIdReuse() {
        val request = display(id = 7, key = "old-connection")
        val replacement = display(id = 7, key = "new-connection")

        val error = assertThrows(DesktopDisplayException::class.java) {
            resolveLaunchDisplay(request, replacement, replacement.copy(key = "bound:7"))
        }

        assertEquals("DISPLAY_UNAVAILABLE", error.code)
    }

    @Test
    fun disappearingDisplayCannotLaunchWithoutRepositoryOrBoundWindow() {
        val error = assertThrows(DesktopDisplayException::class.java) {
            resolveLaunchDisplay(display(id = 8, key = "disconnected"), null, null)
        }

        assertEquals("DISPLAY_UNAVAILABLE", error.code)
        assertTrue(error.message!!.contains("launchable"))
    }

    @Test
    fun displayLaunchReusesOnlyTheActivityAlreadyOnItsTargetDisplay() {
        assertTrue(shouldReuseBoundActivity(targetDisplayId = 30, boundDisplayId = 30))
        assertFalse(shouldReuseBoundActivity(targetDisplayId = 30, boundDisplayId = 0))
        assertFalse(shouldReuseBoundActivity(targetDisplayId = 30, boundDisplayId = null))
    }

    private fun display(id: Int, key: String) = DesktopDisplay(
        id = id,
        key = key,
        name = "Display $id",
        isDefault = id == 0,
        eligible = true,
        reason = null,
    )
}
