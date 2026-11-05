package com.orailnoor.droiddesk.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun boundDisplayIsUsedOnlyForTheMatchingSyntheticRequest() {
        val request = display(id = 30, key = "bound:30")
        val bound = display(id = 30, key = "bound:30")

        assertSame(bound, resolveLaunchDisplay(request, null, bound))
        val error = assertThrows(DesktopDisplayException::class.java) {
            resolveLaunchDisplay(request, null, bound.copy(key = "bound:29"))
        }
        assertEquals("DISPLAY_UNAVAILABLE", error.code)
        assertTrue(error.message!!.contains("launchable"))
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
