package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySettingsStoreTest {
    @Test
    fun wireParserPreservesTheExternalInputRouteOverride() {
        val update = parseDisplaySettingsUpdate(
            mapOf(
                "displayKey" to "monitor-5",
                "renderPolicy" to "native",
                "uiScalePercent" to 150,
                "inputMode" to 1,
                "inputRoute" to "phoneRelay",
            ),
        )

        assertEquals("monitor-5", update.displayKey)
        assertEquals(ExternalInputRoutePreference.PHONE_RELAY, update.settings.inputRoute)
    }

    @Test
    fun inputRouteOverrideIsScopedToTheStableDisplayIdentity() {
        val preferences = MemoryDisplayPreferences()
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})
        val settings = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1440,
            uiScalePercent = 150,
            inputMode = 2,
            inputRoute = ExternalInputRoutePreference.PHONE_RELAY,
        )

        store.save(ProfileKind.MONITOR, settings, displaySettingsKey = "local:monitor-a")

        val monitorA = store.load(ProfileKind.MONITOR, displaySettingsKey = "local:monitor-a")
        val monitorB = store.load(ProfileKind.MONITOR, displaySettingsKey = "local:monitor-b")
        assertEquals(ExternalInputRoutePreference.PHONE_RELAY, monitorA?.inputRoute)
        assertEquals(ExternalInputRoutePreference.AUTOMATIC, monitorB?.inputRoute)
        assertEquals(RenderPolicy.CAP_1440, monitorB?.renderPolicy)
        assertEquals(150, monitorB?.uiScalePercent)
        assertEquals(2, monitorB?.inputMode)
    }

    private class MemoryDisplayPreferences : DisplayPreferences {
        private val values = mutableMapOf<String, Any>()

        override fun read(key: String): Any? = values[key]

        override fun write(changes: Map<String, Any?>) {
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}
