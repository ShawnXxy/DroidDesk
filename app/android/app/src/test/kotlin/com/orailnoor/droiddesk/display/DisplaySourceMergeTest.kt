package com.orailnoor.droiddesk.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplaySourceMergeTest {
    @Test
    fun presentationOnlyDisplaysAreIncludedWithoutDuplicatingPrimaryDisplays() {
        val merged = mergeDisplaysById(
            primary = listOf(FakeDisplay(0), FakeDisplay(1)),
            presentations = listOf(FakeDisplay(1), FakeDisplay(2)),
            idOf = FakeDisplay::id,
        )

        assertEquals(listOf(0, 1, 2), merged.map(FakeDisplay::id))
    }

    @Test
    fun stableSettingsIdentitySurvivesConnectionKeyRegeneration() {
        val source = MutableDisplaySource(
            listOf(
                DisplayCandidate(
                    id = 5,
                    name = "RTK FHD",
                    isDefault = false,
                    settingsKey = "local:rtk-fhd",
                ),
            ),
        )
        val repository = DisplayRepository(source)
        repository.start {}
        val first = repository.snapshot().single()

        source.replace(emptyList(), removedId = 5)
        source.replace(
            listOf(
                DisplayCandidate(
                    id = 5,
                    name = "RTK FHD",
                    isDefault = false,
                    settingsKey = "local:rtk-fhd",
                ),
            ),
        )
        val second = repository.snapshot().single()

        assertNotEquals(first.key, second.key)
        assertEquals(first.settingsKey, second.settingsKey)
    }

    @Test
    fun monitorProductEvidenceCreatesStableDistinctSettingsKeys() {
        val firstConnection = stableDisplaySettingsKey(
            isDefault = false,
            name = "RTK FHD",
            manufacturerPnpId = "RTK",
            productId = "447",
            width = 2160,
            height = 1440,
        )
        val secondConnection = stableDisplaySettingsKey(
            isDefault = false,
            name = "RTK FHD",
            manufacturerPnpId = "RTK",
            productId = "447",
            width = 1920,
            height = 1080,
        )
        val otherMonitor = stableDisplaySettingsKey(
            isDefault = false,
            name = "RTK FHD",
            manufacturerPnpId = "RTK",
            productId = "448",
            width = 2160,
            height = 1440,
        )

        assertEquals(firstConnection, secondConnection)
        assertNotEquals(firstConnection, otherMonitor)
    }

    @Test
    fun android11DoesNotReadApi31MonitorProductInfo() {
        var productInfoRead = false

        val productInfo = displayProductInfoForSdk(30) {
            productInfoRead = true
            "product"
        }

        assertNull(productInfo)
        assertFalse(productInfoRead)
    }

    private data class FakeDisplay(val id: Int)

    private class MutableDisplaySource(
        private var displays: List<DisplayCandidate>,
    ) : DesktopDisplaySource {
        private var listener: ((Int?) -> Unit)? = null

        override fun snapshot(): List<DisplayCandidate> = displays

        override fun start(onChanged: (removedId: Int?) -> Unit) {
            listener = onChanged
        }

        override fun close() {
            listener = null
        }

        fun replace(next: List<DisplayCandidate>, removedId: Int? = null) {
            displays = next
            listener?.invoke(removedId)
        }
    }
}
