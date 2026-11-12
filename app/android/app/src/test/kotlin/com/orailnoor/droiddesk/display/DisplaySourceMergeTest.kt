package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.display.capability.AndroidInputDeviceSnapshot
import com.orailnoor.droiddesk.display.capability.AndroidPresentationDisplaySnapshot
import com.orailnoor.droiddesk.display.capability.AndroidPresentationEvidencePlatform
import com.orailnoor.droiddesk.display.capability.AndroidPresentationEvidenceProbe
import com.orailnoor.droiddesk.display.capability.PresentationClass
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
    fun android11DoesNotReadApi31MonitorProductIdentity() {
        var productInfoRead = false

        val productIdentity = selectDisplayProductIdentityForSdk(30) {
            productInfoRead = true
            displayProductIdentity(
                manufacturerPnpId = "RTK",
                productId = "447",
            )
        }

        assertNull(productIdentity)
        assertFalse(productInfoRead)
    }

    @Test
    fun android12MapsMonitorProductInfoToStableIdentity() {
        val productIdentity = selectDisplayProductIdentityForSdk(31) {
            displayProductIdentity(
                manufacturerPnpId = "RTK",
                productId = "447",
            )
        }

        assertEquals(
            DisplayProductIdentity(
                manufacturerPnpId = "RTK",
                productId = "447",
            ),
            productIdentity,
        )
    }

    @Test
    fun stableSettingsKeyIsIndependentOfDisplayMode() {
        val fourKKey = stableDisplaySettingsKey(
            isDefault = false,
            name = "DELL U2723QE",
            manufacturerPnpId = "DEL",
            productId = "42313",
            width = 3840,
            height = 2160,
        )
        val fhdKey = stableDisplaySettingsKey(
            isDefault = false,
            name = "DELL U2723QE",
            manufacturerPnpId = "DEL",
            productId = "42313",
            width = 1920,
            height = 1080,
        )
        val portraitKey = stableDisplaySettingsKey(
            isDefault = false,
            name = "DELL U2723QE",
            manufacturerPnpId = "DEL",
            productId = "42313",
            width = 1080,
            height = 1920,
        )
        val store = DisplaySettingsStore(
            preferences = InMemoryDisplayPreferences(),
            legacyInputMode = { null },
            diagnostic = {},
        )
        val saved = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1440,
            uiScalePercent = 150,
            inputMode = 2,
            inputRoute = com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference.PHONE_RELAY,
        )

        store.save(
            key = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, fourKKey),
            scope = DisplaySettingsScope.THIS_DISPLAY,
            settings = saved,
        )

        assertEquals("external:3:DEL|5:42313|12:DELL U2723QE", fourKKey)
        assertEquals(fourKKey, fhdKey)
        assertEquals(fourKKey, portraitKey)
        assertEquals(
            saved,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, fhdKey)),
        )
    }

    @Test
    fun connectionKeyChangesDoNotChangeTheStableSettingsKey() {
        val productKey = "external:3:RTK|3:447|7:RTK FHD"
        val source = MutableDisplaySource(
            listOf(DisplayCandidate(id = 5, name = "RTK FHD", isDefault = false, settingsKey = productKey)),
        )
        val repository = DisplayRepository(source)
        val store = DisplaySettingsStore(
            preferences = InMemoryDisplayPreferences(),
            legacyInputMode = { null },
            diagnostic = {},
        )
        repository.start {}
        val first = repository.snapshot().single()
        val saved = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1080,
            uiScalePercent = 125,
            inputMode = 1,
            inputRoute = com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference.NATIVE,
        )
        store.save(
            key = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, first.settingsKey),
            scope = DisplaySettingsScope.THIS_DISPLAY,
            settings = saved,
        )

        source.replace(emptyList(), removedId = 5)
        source.replace(
            listOf(DisplayCandidate(id = 9, name = "RTK FHD", isDefault = false, settingsKey = productKey)),
        )
        val second = repository.snapshot().single()

        assertNotEquals(first.key, second.key)
        assertNotEquals(first.id, second.id)
        assertEquals(productKey, first.settingsKey)
        assertEquals(first.settingsKey, second.settingsKey)
        assertEquals(
            saved,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, second.settingsKey)),
        )
    }

    @Test
    fun simultaneousIdenticalMonitorsCannotOverwriteEachOther() {
        val productKey = "external:3:RTK|3:447|7:RTK FHD"
        val source = MutableDisplaySource(
            listOf(
                DisplayCandidate(id = 5, name = "RTK FHD", isDefault = false, settingsKey = productKey),
                DisplayCandidate(id = 6, name = "RTK FHD", isDefault = false, settingsKey = productKey),
            ),
        )
        val repository = DisplayRepository(source)
        val store = DisplaySettingsStore(
            preferences = InMemoryDisplayPreferences(),
            legacyInputMode = { null },
            diagnostic = {},
        )
        val displays = repository.snapshot().associateBy(DesktopDisplay::id)
        val primarySettings = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1440,
            uiScalePercent = 150,
            inputMode = 1,
            inputRoute = com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference.PHONE_RELAY,
        )
        val secondarySettings = DisplaySettings(
            renderPolicy = RenderPolicy.NATIVE,
            uiScalePercent = 125,
            inputMode = 2,
            inputRoute = com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference.NATIVE,
        )

        store.save(
            key = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, displays.getValue(5).settingsKey),
            scope = DisplaySettingsScope.THIS_DISPLAY,
            settings = primarySettings,
        )
        store.save(
            key = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, displays.getValue(6).settingsKey),
            scope = DisplaySettingsScope.THIS_DISPLAY,
            settings = secondarySettings,
        )

        assertEquals(
            primarySettings,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, displays.getValue(5).settingsKey)),
        )
        assertEquals(
            secondarySettings,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, displays.getValue(6).settingsKey)),
        )
    }

    @Test
    fun presentationEvidenceUsesTheRepositorySettingsKey() {
        val productKey = "external:3:WAC|4:1001|12:Wacom Cintiq"
        val source = MutableDisplaySource(
            listOf(
                DisplayCandidate(id = 5, name = "Wacom Cintiq", isDefault = false, settingsKey = productKey),
                DisplayCandidate(id = 6, name = "Wacom Cintiq", isDefault = false, settingsKey = productKey),
            ),
        )
        val repository = DisplayRepository(source)
        val mode = com.orailnoor.droiddesk.display.capability.DisplayModeEvidence(1, 1920, 1080, 60f)
        val probe = AndroidPresentationEvidenceProbe(
            displayRepository = repository,
            platform = object : AndroidPresentationEvidencePlatform {
                override val supportsSecondaryActivities: Boolean = true
                override fun isActivityStartAllowedOnDisplay(displayId: Int): Boolean = true
                override fun displaySnapshot(displayId: Int) = AndroidPresentationDisplaySnapshot(
                    displayFlags = 0,
                    densityDpi = 160,
                    fontScale = 1f,
                    isDesktopUiMode = true,
                    currentMode = mode,
                    supportedModes = listOf(mode),
                )
                override fun inputDevices() = listOf(
                    AndroidInputDeviceSnapshot(
                        isExternal = true,
                        hasTouchscreen = true,
                        associationKnown = true,
                        associatedDisplayId = 6,
                    ),
                )
            },
        )
        val policy = com.orailnoor.droiddesk.display.policy.PresentationPolicy()
        val store = DisplaySettingsStore(
            preferences = InMemoryDisplayPreferences(),
            legacyInputMode = { null },
            diagnostic = {},
        )
        val snapshot = repository.snapshot().associateBy(DesktopDisplay::id)
        val secondClass = policy.classify(probe.presentationContext(6))
        val secondIdentity = probe.displayIdentity(6)
        val saved = DisplaySettings(
            renderPolicy = RenderPolicy.NATIVE,
            uiScalePercent = 125,
            inputMode = 3,
            inputRoute = com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference.NATIVE,
        )

        store.save(
            key = DisplaySettingsKey(secondClass, secondIdentity.settingsKey),
            scope = DisplaySettingsScope.THIS_DISPLAY,
            settings = saved,
        )

        assertEquals(PresentationClass.EXTERNAL_TOUCH, secondClass)
        assertEquals(snapshot.getValue(6).settingsKey, secondIdentity.settingsKey)
        assertNotEquals(productKey, secondIdentity.settingsKey)
        assertNull(store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, snapshot.getValue(5).settingsKey)))
        assertEquals(
            saved,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, secondIdentity.settingsKey)),
        )
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

    private class InMemoryDisplayPreferences : DisplayPreferences {
        private val values = mutableMapOf<String, Any>()

        override fun read(key: String): Any? = values[key]

        override fun write(changes: Map<String, Any?>) {
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}
