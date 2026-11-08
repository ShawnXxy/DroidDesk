package com.orailnoor.droiddesk.display.placement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DisplayConnectionIdentityTest {
    @Test
    fun platformUniqueIdKeepsConnectionKeyStableAcrossWrappers() {
        val policy = DisplayConnectionIdentityPolicy()

        val first = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi-1080p",
        )
        val replacementWrapper = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi-4k",
        )

        assertEquals("local:hdmi-a", first.connectionIdentity)
        assertEquals(first, replacementWrapper)
        assertEquals(
            "bound:7:0317d493-21f9-37c5-9f64-2b5707be08bb",
            first.connectionKey,
        )
    }

    @Test
    fun absentPlatformUniqueIdsUseFallbackIdentity() {
        val policy = DisplayConnectionIdentityPolicy()
        val absentPlatformIds = listOf(null, "", "   ", "null")

        absentPlatformIds.forEach { platformUniqueId ->
            val identity = policy.resolve(
                displayId = 7,
                platformUniqueId = platformUniqueId,
                fallbackIdentity = "settings:hdmi",
            )

            assertEquals("settings:hdmi", identity.connectionIdentity)
            assertEquals(0L, identity.generation)
        }
    }

    @Test
    fun platformUniqueIdIsPreservedVerbatim() {
        val policy = DisplayConnectionIdentityPolicy()
        val platformUniqueId =
            "virtual:com.example:Panel, uniqueId \"nested\", app 1920 x 1080, type external"

        val identity = policy.resolve(
            displayId = 7,
            platformUniqueId = platformUniqueId,
            fallbackIdentity = "settings:virtual",
        )

        assertEquals(platformUniqueId, identity.connectionIdentity)
    }

    @Test
    fun differentPlatformUniqueIdChangesKeyWithoutAdvancingGeneration() {
        val policy = DisplayConnectionIdentityPolicy()

        val first = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi",
        )
        val replacement = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-b",
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals(0L, first.generation)
        assertEquals(0L, replacement.generation)
        assertNotEquals(first.connectionIdentity, replacement.connectionIdentity)
        assertNotEquals(first.connectionKey, replacement.connectionKey)
    }

    @Test
    fun differentFallbackIdentityChangesKeyWithoutAdvancingGeneration() {
        val policy = DisplayConnectionIdentityPolicy()

        val first = policy.resolve(
            displayId = 7,
            platformUniqueId = null,
            fallbackIdentity = "settings:hdmi-a",
        )
        val replacement = policy.resolve(
            displayId = 7,
            platformUniqueId = null,
            fallbackIdentity = "settings:hdmi-b",
        )

        assertEquals(0L, first.generation)
        assertEquals(0L, replacement.generation)
        assertNotEquals(first.connectionIdentity, replacement.connectionIdentity)
        assertNotEquals(first.connectionKey, replacement.connectionKey)
    }

    @Test
    fun sameDisplayIdRemovalAndReaddAdvancesGenerationAndKey() {
        val policy = DisplayConnectionIdentityPolicy()
        val original = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi",
        )

        policy.displayRemoved(7)
        policy.displayAdded(7)
        val replacement = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
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
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi-a",
        )
        val secondDisplay = policy.resolve(
            displayId = 8,
            platformUniqueId = "local:hdmi-b",
            fallbackIdentity = "settings:hdmi-b",
        )

        policy.displayRemoved(7)
        val firstReplacement = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi-a",
        )
        val secondUnchanged = policy.resolve(
            displayId = 8,
            platformUniqueId = "local:hdmi-b",
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
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi",
        )

        policy.displayAdded(7)
        policy.displayChanged(7)
        val afterDelayedCallbacks = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
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
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi",
        )
        val secondLaunch = policy.resolve(
            displayId = 7,
            platformUniqueId = "local:hdmi-a",
            fallbackIdentity = "settings:hdmi",
        )

        assertEquals(firstLaunch, secondLaunch)
    }
}
