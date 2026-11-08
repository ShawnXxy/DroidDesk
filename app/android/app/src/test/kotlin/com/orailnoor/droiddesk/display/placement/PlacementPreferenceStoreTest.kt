package com.orailnoor.droiddesk.display.placement

import com.orailnoor.droiddesk.display.capability.PlacementMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PlacementPreferenceStoreTest {
    @Test
    fun placementOverrideRoundTripsAndAutomaticRemovesStoredValue() {
        val preferences = MemoryPlacementPreferenceStorage()
        val store = AndroidPlacementPreferenceStore(preferences)

        assertNull(store.loadOverride())

        store.saveOverride(PlacementMode.MOVABLE)
        assertEquals("movable", preferences.value)
        assertEquals(PlacementMode.MOVABLE, store.loadOverride())

        store.saveOverride(PlacementMode.DISPLAY_LOCAL)
        assertEquals("displayLocal", preferences.value)
        assertEquals(PlacementMode.DISPLAY_LOCAL, store.loadOverride())

        store.saveOverride(null)
        assertNull(preferences.value)
        assertNull(store.loadOverride())
    }

    @Test
    fun invalidStoredPlacementValueIsCleared() {
        val preferences = MemoryPlacementPreferenceStorage(initialValue = 42)
        val store = AndroidPlacementPreferenceStore(preferences)

        assertNull(store.loadOverride())

        assertNull(preferences.value)
        assertEquals(1, preferences.writeCount)
    }

    @Test
    fun failedSaveRestoresPreviousOverrideAndRethrowsOriginalFailure() {
        val failure = IllegalStateException("Could not persist placement preference")
        val preferences = MemoryPlacementPreferenceStorage(
            initialValue = PlacementMode.DISPLAY_LOCAL.wireName,
        ).apply {
            failNextWriteAfterMutation = failure
        }
        val store = AndroidPlacementPreferenceStore(preferences)

        val thrown = assertThrows(IllegalStateException::class.java) {
            store.saveOverride(PlacementMode.MOVABLE)
        }

        assertSame(failure, thrown)
        assertEquals(PlacementMode.DISPLAY_LOCAL.wireName, preferences.value)
        assertEquals(2, preferences.writeCount)
    }

    private class MemoryPlacementPreferenceStorage(
        initialValue: Any? = null,
    ) : PlacementPreferenceStorage {
        var value: Any? = initialValue
        var writeCount: Int = 0
        var failNextWriteAfterMutation: IllegalStateException? = null

        override fun read(): Any? = value

        override fun write(value: String?) {
            writeCount += 1
            this.value = value
            failNextWriteAfterMutation?.let { failure ->
                failNextWriteAfterMutation = null
                throw failure
            }
        }
    }
}
