package com.orailnoor.droiddesk.display.placement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DisplayConnectionIdentityTest {
    @Test
    fun platformIdentityKeepsConnectionKeyStableAcrossDisplayWrappers() {
        val policy = DisplayConnectionIdentityPolicy()

        val first = policy.resolve(
            displayId = 7,
            platformDescription =
                "Display id 7: DisplayInfo{\"HDMI\", uniqueId \"local:hdmi-a\", app 1920 x 1080}",
            fallbackIdentity = "settings:hdmi-1080p",
        )
        val replacementWrapper = policy.resolve(
            displayId = 7,
            platformDescription =
                "Display id 7: DisplayInfo{\"HDMI\", uniqueId \"local:hdmi-a\", app 3840 x 2160}",
            fallbackIdentity = "settings:hdmi-4k",
        )

        assertEquals("local:hdmi-a", first.connectionIdentity)
        assertEquals(first.connectionIdentity, replacementWrapper.connectionIdentity)
        assertEquals(first.connectionKey, replacementWrapper.connectionKey)
        assertEquals(
            "bound:7:0317d493-21f9-37c5-9f64-2b5707be08bb",
            first.connectionKey,
        )
    }

    @Test
    fun fallbackIdentityIsStableWhenPlatformIdentityIsUnavailable() {
        val policy = DisplayConnectionIdentityPolicy()

        val first = policy.resolve(
            displayId = 7,
            platformDescription = "Display id 7: unavailable",
            fallbackIdentity = "settings:hdmi",
        )
        val replacementWrapper = policy.resolve(
            displayId = 7,
            platformDescription = "Display id 7: DisplayInfo{\"HDMI\"}",
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals("settings:hdmi", first.connectionIdentity)
        assertEquals(first, replacementWrapper)
    }

    @Test
    fun nullPlatformIdentityUsesTheCurrentFallbackIdentity() {
        val policy = DisplayConnectionIdentityPolicy()
        val nullPlatformIdentity =
            "Display id 7: DisplayInfo{\"HDMI\", uniqueId \"null\", app 1920 x 1080}"

        val first = policy.resolve(
            displayId = 7,
            platformDescription = nullPlatformIdentity,
            fallbackIdentity = "settings:hdmi-a",
        )
        val replacement = policy.resolve(
            displayId = 7,
            platformDescription = nullPlatformIdentity,
            fallbackIdentity = "settings:hdmi-b",
        )

        assertEquals("settings:hdmi-a", first.connectionIdentity)
        assertEquals("settings:hdmi-b", replacement.connectionIdentity)
        assertEquals(0L, first.generation)
        assertEquals(0L, replacement.generation)
        assertNotEquals(first.connectionKey, replacement.connectionKey)
    }

    @Test
    fun displayNameIdentityTokenCannotOverrideNullPlatformIdentity() {
        val policy = DisplayConnectionIdentityPolicy()

        val identity = policy.resolve(
            displayId = 7,
            platformDescription =
                "DisplayInfo{\"Panel uniqueId \"alias\", uniqueId \"null\", app 1920 x 1080}",
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals("settings:hdmi", identity.connectionIdentity)
    }

    @Test
    fun displayNameIdentityTokenCannotHidePlatformIdentityChanges() {
        val policy = DisplayConnectionIdentityPolicy()

        val first = policy.resolve(
            displayId = 7,
            platformDescription =
                "DisplayInfo{\"Panel uniqueId \"alias\", uniqueId \"local:hdmi-a\", app 1920 x 1080}",
            fallbackIdentity = "settings:hdmi",
        )
        val replacement = policy.resolve(
            displayId = 7,
            platformDescription =
                "DisplayInfo{\"Panel uniqueId \"alias\", uniqueId \"local:hdmi-b\", app 1920 x 1080}",
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals("local:hdmi-a", first.connectionIdentity)
        assertEquals("local:hdmi-b", replacement.connectionIdentity)
        assertEquals(0L, first.generation)
        assertEquals(0L, replacement.generation)
        assertNotEquals(first.connectionKey, replacement.connectionKey)
    }

    @Test
    fun finalPlatformIdentityFieldWinsOverCompleteDisplayNameSpoof() {
        val policy = DisplayConnectionIdentityPolicy()
        val cases = listOf(
            Triple(
                "DisplayInfo{\"Panel, uniqueId \"spoof\", app fake\", uniqueId \"local:hdmi-a\", app 1920 x 1080}",
                "settings:valid",
                "local:hdmi-a",
            ),
            Triple(
                "DisplayInfo{\"Panel, uniqueId \"spoof\", app fake\", uniqueId \"null\", app 1920 x 1080}",
                "settings:null",
                "settings:null",
            ),
            Triple(
                "DisplayInfo{\"Panel, uniqueId \"spoof\", app fake\", uniqueId local:hdmi-a, app 1920 x 1080}",
                "settings:malformed",
                "settings:malformed",
            ),
        )

        cases.forEach { (description, fallbackIdentity, expectedIdentity) ->
            val identity = policy.resolve(
                displayId = 7,
                platformDescription = description,
                fallbackIdentity = fallbackIdentity,
            )

            assertEquals(expectedIdentity, identity.connectionIdentity)
        }
    }

    @Test
    fun malformedPlatformIdentityFieldsUseFallbackIdentity() {
        val policy = DisplayConnectionIdentityPolicy()
        val malformedDescriptions = listOf(
            "DisplayInfo{\"HDMI\", uniqueId \"local:hdmi-a\"garbage, app 1920 x 1080}",
            "DisplayInfo{\"HDMI\", uniqueId \"local:hdmi-a, app 1920 x 1080, owner \"com.example\"}",
            "DisplayInfo{\"Panel, uniqueId \"alias\"\", uniqueId local:hdmi-a, app 1920 x 1080}",
        )

        malformedDescriptions.forEachIndexed { index, description ->
            val fallbackIdentity = "settings:hdmi-$index"

            val identity = policy.resolve(
                displayId = 7,
                platformDescription = description,
                fallbackIdentity = fallbackIdentity,
            )

            assertEquals(fallbackIdentity, identity.connectionIdentity)
        }
    }

    @Test
    fun differentStableIdentityChangesConnectionKey() {
        val policy = DisplayConnectionIdentityPolicy()

        val first = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi",
        )
        val replacement = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-b"),
            fallbackIdentity = "settings:hdmi",
        )

        assertNotEquals(first.connectionIdentity, replacement.connectionIdentity)
        assertNotEquals(first.connectionKey, replacement.connectionKey)
    }

    @Test
    fun sameDisplayIdRemovalAndReaddAdvancesGenerationAndKey() {
        val policy = DisplayConnectionIdentityPolicy()
        val original = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi",
        )

        policy.displayRemoved(7)
        policy.displayAdded(7)
        val replacement = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals(0L, original.generation)
        assertEquals(1L, replacement.generation)
        assertNotEquals(original.connectionKey, replacement.connectionKey)
    }

    @Test
    fun removingOneDisplayDoesNotAdvanceAnotherDisplayGeneration() {
        val policy = DisplayConnectionIdentityPolicy()
        val firstDisplay = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi-a",
        )
        val secondDisplay = policy.resolve(
            displayId = 8,
            platformDescription = description("local:hdmi-b"),
            fallbackIdentity = "settings:hdmi-b",
        )

        policy.displayRemoved(7)
        val firstReplacement = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi-a",
        )
        val secondUnchanged = policy.resolve(
            displayId = 8,
            platformDescription = description("local:hdmi-b"),
            fallbackIdentity = "settings:hdmi-b",
        )

        assertEquals(1L, firstReplacement.generation)
        assertNotEquals(firstDisplay.connectionKey, firstReplacement.connectionKey)
        assertEquals(0L, secondUnchanged.generation)
        assertEquals(secondDisplay.connectionKey, secondUnchanged.connectionKey)
    }

    @Test
    fun delayedAddAndChangeCallbacksDoNotAdvanceReplacementGeneration() {
        val policy = DisplayConnectionIdentityPolicy()
        policy.displayRemoved(7)
        val replacement = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi",
        )

        policy.displayAdded(7)
        policy.displayChanged(7)
        val afterDelayedCallbacks = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals(1L, replacement.generation)
        assertEquals(replacement, afterDelayedCallbacks)
    }

    @Test
    fun sequentialLaunchesReuseTheCurrentConnectionKey() {
        val policy = DisplayConnectionIdentityPolicy()

        val firstLaunch = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi",
        )
        val secondLaunch = policy.resolve(
            displayId = 7,
            platformDescription = description("local:hdmi-a"),
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals(firstLaunch, secondLaunch)
    }

    private fun description(connectionIdentity: String) =
        "Display id 7: DisplayInfo{\"HDMI\", uniqueId \"$connectionIdentity\", app 1920 x 1080}"
}
