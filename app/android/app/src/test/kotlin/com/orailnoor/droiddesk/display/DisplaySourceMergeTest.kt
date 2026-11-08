package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.display.capability.AndroidInputDeviceSnapshot
import com.orailnoor.droiddesk.display.capability.AndroidPresentationDisplaySnapshot
import com.orailnoor.droiddesk.display.capability.AndroidPresentationEvidencePlatform
import com.orailnoor.droiddesk.display.capability.AndroidPresentationEvidenceProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val probe = AndroidPresentationEvidenceProbe(
            displayRepository = repository,
            platform = EmptyAndroidPresentationEvidencePlatform,
        )
        repository.start {}
        val canonicalFirst = repository.snapshot().single()
        val first = probe.displayIdentity(5)

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
        val canonicalSecond = repository.snapshot().single()
        val second = probe.displayIdentity(5)

        assertEquals(canonicalFirst.key, first.key)
        assertEquals(canonicalFirst.settingsKey, first.settingsKey)
        assertEquals(canonicalSecond.key, second.key)
        assertEquals(canonicalSecond.settingsKey, second.settingsKey)
        assertNotEquals(first.key, second.key)
        assertEquals(first.settingsKey, second.settingsKey)
    }

    @Test
    fun android28SkipsTheDisplayPermissionApi() {
        var permissionApiCalled = false

        val allowed = activityStartAllowedOnDisplayForSdk(28) {
            permissionApiCalled = true
            false
        }

        assertTrue(allowed)
        assertFalse(permissionApiCalled)
    }

    @Test
    fun displayPermissionFailuresAreDenied() {
        assertFalse(
            activityStartAllowedOnDisplayForSdk(29) {
                throw SecurityException("permission denied")
            },
        )
        assertFalse(
            activityStartAllowedOnDisplayForSdk(29) {
                throw IllegalArgumentException("display disconnected")
            },
        )
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
    fun identicalMonitorsUseConnectionScopedSettingsKeys() {
        val productKey = "external:3:RTK|3:447|7:RTK FHD"
        val source = MutableDisplaySource(
            listOf(
                DisplayCandidate(
                    id = 5,
                    name = "RTK FHD",
                    isDefault = false,
                    settingsKey = productKey,
                ),
            ),
        )
        val repository = DisplayRepository(source)
        repository.start {}
        val soleMonitor = repository.snapshot().single()

        source.replace(
            listOf(
                source.snapshot().single(),
                DisplayCandidate(
                    id = 6,
                    name = "RTK FHD",
                    isDefault = false,
                    settingsKey = productKey,
                ),
            ),
        )

        val firstSnapshot = repository.snapshot().associateBy(DesktopDisplay::id)
        assertEquals(soleMonitor.settingsKey, firstSnapshot.getValue(5).settingsKey)
        assertNotEquals(
            firstSnapshot.getValue(5).settingsKey,
            firstSnapshot.getValue(6).settingsKey,
        )

        source.replace(listOf(source.snapshot().last()))
        val remaining = repository.snapshot().single()
        assertEquals(firstSnapshot.getValue(6).settingsKey, remaining.settingsKey)
    }

    @Test
    fun identicalMonitorsReconnectWithoutAStalePrimaryAssignment() {
        val productKey = "external:3:RTK|3:447|7:RTK FHD"
        val source = MutableDisplaySource(
            listOf(
                DisplayCandidate(5, "RTK FHD", false, settingsKey = productKey),
                DisplayCandidate(6, "RTK FHD", false, settingsKey = productKey),
            ),
        )
        val repository = DisplayRepository(source)
        repository.start {}
        repository.snapshot()

        source.replace(emptyList(), removedId = 5)
        source.replace(
            listOf(
                DisplayCandidate(7, "RTK FHD", false, settingsKey = productKey),
            ),
        )

        assertEquals(productKey, repository.snapshot().single().settingsKey)
    }

    @Test
    fun reconnectedSoleMonitorRemainsPrimaryWhenItsTwinArrives() {
        val productKey = "external:3:RTK|3:447|7:RTK FHD"
        val source = MutableDisplaySource(
            listOf(
                DisplayCandidate(5, "RTK FHD", false, settingsKey = productKey),
            ),
        )
        val repository = DisplayRepository(source)
        repository.start {}
        repository.snapshot()

        source.replace(emptyList(), removedId = 5)
        source.replace(
            listOf(
                DisplayCandidate(7, "RTK FHD", false, settingsKey = productKey),
            ),
        )
        val reconnected = repository.snapshot().single()

        source.replace(
            listOf(
                source.snapshot().single(),
                DisplayCandidate(8, "RTK FHD", false, settingsKey = productKey),
            ),
        )
        val withTwin = repository.snapshot().associateBy(DesktopDisplay::id)

        assertEquals(productKey, reconnected.settingsKey)
        assertEquals(productKey, withTwin.getValue(7).settingsKey)
        assertNotEquals(productKey, withTwin.getValue(8).settingsKey)
    }

    @Test
    fun monitorWithoutProductEvidenceIgnoresResolutionChanges() {
        val firstMode = stableDisplaySettingsKey(
            isDefault = false,
            name = "HDMI Screen",
            manufacturerPnpId = null,
            productId = null,
            width = 2160,
            height = 1440,
        )
        val secondMode = stableDisplaySettingsKey(
            isDefault = false,
            name = "HDMI Screen",
            manufacturerPnpId = null,
            productId = null,
            width = 1920,
            height = 1080,
        )

        assertEquals(firstMode, secondMode)
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

    private object EmptyAndroidPresentationEvidencePlatform :
        AndroidPresentationEvidencePlatform {
        override val supportsSecondaryActivities: Boolean = true

        override fun isActivityStartAllowedOnDisplay(displayId: Int): Boolean = false

        override fun displaySnapshot(displayId: Int): AndroidPresentationDisplaySnapshot? = null

        override fun inputDevices(): List<AndroidInputDeviceSnapshot> = emptyList()
    }

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
