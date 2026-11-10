package com.orailnoor.droiddesk.view

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererCleanupTest {
    @Test
    fun ownedInputReleaseCompletesBeforeReentrantCleanupCallbacks() {
        val cleanup = RendererCleanup()
        val calls = mutableListOf<String>()

        cleanup.run(
            releaseOwnedInput = { calls += "release-input" },
            completeTeardown = {
                calls += "callback"
                cleanup.run(
                    releaseOwnedInput = { calls += "nested-release-input" },
                    completeTeardown = { calls += "nested-teardown" },
                )
                calls += "teardown"
            },
        )

        assertEquals(listOf("release-input", "callback", "teardown"), calls)
    }

    @Test
    fun repeatedCleanupReleasesHeldInputAndCompletesTeardownOnce() {
        val cleanup = RendererCleanup()
        var heldInputReleases = 0
        var completedTeardowns = 0

        repeat(2) {
            cleanup.run(
                releaseOwnedInput = { heldInputReleases++ },
                completeTeardown = { completedTeardowns++ },
            )
        }

        assertEquals(1, heldInputReleases)
        assertEquals(1, completedTeardowns)
    }
}
