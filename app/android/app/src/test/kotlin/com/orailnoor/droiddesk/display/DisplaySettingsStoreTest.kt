package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.display.capability.DisplayModeEvidence
import com.orailnoor.droiddesk.display.capability.InputCapabilities
import com.orailnoor.droiddesk.display.capability.PointerAssociationEvidence
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.capability.PresentationContext
import com.orailnoor.droiddesk.display.capability.TouchEvidence
import com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun migratesPhoneWideAndMonitorSettingsToSchemaTwo() {
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 1,
                "phone.renderPolicy" to "native",
                "phone.uiScalePercent" to 125,
                "phone.inputMode" to 2,
                "wide.renderPolicy" to "cap1440",
                "wide.uiScalePercent" to 150,
                "wide.inputMode" to 3,
                "monitor.renderPolicy" to "cap1080",
                "monitor.uiScalePercent" to 175,
                "monitor.inputMode" to 1,
            ),
        )
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})

        val phone = store.load(DisplaySettingsKey(PresentationClass.PHONE_TOUCH, "default"))
        val largeTouch = store.load(DisplaySettingsKey(PresentationClass.LARGE_TOUCH, "default"))
        val externalPointer = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:1"))

        assertEquals(2, preferences.read("schemaVersion"))
        assertEquals(
            DisplaySettings(RenderPolicy.NATIVE, 125, 2, ExternalInputRoutePreference.AUTOMATIC),
            phone,
        )
        assertEquals(
            DisplaySettings(RenderPolicy.CAP_1440, 150, 3, ExternalInputRoutePreference.AUTOMATIC),
            largeTouch,
        )
        assertEquals(
            DisplaySettings(RenderPolicy.CAP_1080, 175, 1, ExternalInputRoutePreference.AUTOMATIC),
            externalPointer,
        )
        assertNull(preferences.read("phone.renderPolicy"))
        assertNull(preferences.read("wide.renderPolicy"))
        assertNull(preferences.read("monitor.renderPolicy"))
    }

    @Test
    fun monitorMigrationSeedsPointerAndTouchClassDefaults() {
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 1,
                "monitor.renderPolicy" to "cap1440",
                "monitor.uiScalePercent" to 150,
                "monitor.inputMode" to 2,
            ),
        )
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})
        val migrated = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1440,
            uiScalePercent = 150,
            inputMode = 2,
            inputRoute = ExternalInputRoutePreference.AUTOMATIC,
        )

        assertEquals(
            migrated,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:pointer-a")),
        )
        assertEquals(
            migrated,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, "external:touch-a")),
        )

        val updatedTouchDefault = DisplaySettings(
            renderPolicy = RenderPolicy.NATIVE,
            uiScalePercent = 125,
            inputMode = 3,
            inputRoute = ExternalInputRoutePreference.AUTOMATIC,
        )
        store.save(
            key = DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, null),
            scope = DisplaySettingsScope.CLASS_DEFAULT,
            settings = updatedTouchDefault,
        )

        assertEquals(
            migrated,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:pointer-a")),
        )
        assertEquals(
            updatedTouchDefault,
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, "external:touch-a")),
        )
    }

    @Test
    fun legacyPerDisplayInputRouteRemainsReadable() {
        val stableKey = "external:3:RTK|3:447|7:RTK FHD"
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 1,
                "display.$stableKey.inputRoute" to "phoneRelay",
            ),
        )
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})

        assertTrue(store.loadAutoOpenOnMonitor())
        assertEquals("phoneRelay", preferences.read("display.$stableKey.inputRoute"))

        val loaded = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, stableKey))

        assertEquals(ExternalInputRoutePreference.PHONE_RELAY, loaded?.inputRoute)
        assertNull(store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:other")))
    }

    @Test
    fun legacyPerDisplayInputRouteMigratesWhenClassIsKnown() {
        val stableKey = "external:3:WAC|4:1001|12:Wacom Cintiq"
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 1,
                "monitor.renderPolicy" to "cap1440",
                "monitor.uiScalePercent" to 150,
                "monitor.inputMode" to 2,
                "display.$stableKey.inputRoute" to "phoneRelay",
            ),
        )
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})

        store.load(DisplaySettingsKey(PresentationClass.PHONE_TOUCH, "default"))
        assertEquals("phoneRelay", preferences.read("display.$stableKey.inputRoute"))

        val firstLoad = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, stableKey))
        assertNull(preferences.read("display.$stableKey.inputRoute"))
        assertEquals(
            DisplaySettingsScope.THIS_DISPLAY,
            store.resolveScope(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, stableKey)),
        )
        val secondLoad = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, stableKey))

        val expected = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1440,
            uiScalePercent = 150,
            inputMode = 2,
            inputRoute = ExternalInputRoutePreference.PHONE_RELAY,
        )
        assertEquals(expected, firstLoad)
        assertEquals(expected, secondLoad)
    }

    @Test
    fun pointerLookupPreservesLegacyInputRouteForSubsequentTouchPromotion() {
        val promotedKey = "external:3:PRT|4:2020|14:Portable Touch"
        val overriddenKey = "external:3:WAC|4:1001|12:Wacom Cintiq"
        val existingTouchOverride = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1080,
            uiScalePercent = 125,
            inputMode = 2,
            inputRoute = ExternalInputRoutePreference.NATIVE,
        )
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 2,
                "phone.inputMigrated" to true,
                "display.$promotedKey.inputRoute" to "phoneRelay",
                "display.$overriddenKey.inputRoute" to "phoneRelay",
                "v2.display.externalTouch.$overriddenKey.renderPolicy" to existingTouchOverride.renderPolicy.wireName,
                "v2.display.externalTouch.$overriddenKey.uiScalePercent" to checkNotNull(existingTouchOverride.uiScalePercent),
                "v2.display.externalTouch.$overriddenKey.inputMode" to existingTouchOverride.inputMode,
                "v2.display.externalTouch.$overriddenKey.inputRoute" to existingTouchOverride.inputRoute.wireName,
            ),
        )
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})

        val initialPointer = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, promotedKey))
        val promotedTouch = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, promotedKey))

        assertNull(preferences.read("display.$promotedKey.inputRoute"))
        assertEquals(
            DisplaySettings(RenderPolicy.NATIVE, null, 1, ExternalInputRoutePreference.PHONE_RELAY),
            initialPointer,
        )
        assertEquals(
            DisplaySettings(RenderPolicy.NATIVE, null, 3, ExternalInputRoutePreference.PHONE_RELAY),
            promotedTouch,
        )

        val overriddenPointer = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, overriddenKey))
        val preservedTouch = store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, overriddenKey))
        assertNull(preferences.read("display.$overriddenKey.inputRoute"))
        assertEquals(
            DisplaySettings(RenderPolicy.NATIVE, null, 1, ExternalInputRoutePreference.PHONE_RELAY),
            overriddenPointer,
        )
        assertEquals(existingTouchOverride, preservedTouch)
    }

    @Test
    fun savingBeforeLazyRouteMigrationCleansUpLegacyKeyWithoutResurrection() {
        val stableKey = "external:3:RTK|3:447|7:RTK FHD"
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 1,
                "display.$stableKey.inputRoute" to "phoneRelay",
            ),
        )
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})
        val saved = DisplaySettings(
            renderPolicy = RenderPolicy.NATIVE,
            uiScalePercent = 125,
            inputMode = 1,
            inputRoute = ExternalInputRoutePreference.AUTOMATIC,
        )

        val classOnlyKey = DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, "external:legacy-touch")
        preferences.write(mapOf("display.external:legacy-touch.inputRoute" to "phoneRelay"))
        assertTrue(store.hasExactOverride(classOnlyKey))
        val unmigratedClassKey = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:legacy-ptr")
        preferences.write(mapOf("display.external:legacy-ptr.inputRoute" to "phoneRelay"))
        store.save(unmigratedClassKey, DisplaySettingsScope.CLASS_DEFAULT, saved)
        assertEquals(
            ExternalInputRoutePreference.PHONE_RELAY,
            store.load(unmigratedClassKey)?.inputRoute,
        )

        store.save(
            key = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, stableKey),
            scope = DisplaySettingsScope.THIS_DISPLAY,
            settings = saved,
        )

        assertNull(preferences.read("display.$stableKey.inputRoute"))
        assertEquals(saved, store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, stableKey)))
        assertEquals(
            DisplaySettings(RenderPolicy.NATIVE, null, 3, ExternalInputRoutePreference.PHONE_RELAY),
            store.load(DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, stableKey)),
        )

        val phoneExact = DisplaySettings(RenderPolicy.NATIVE, 125, 2)
        store.save(
            DisplaySettingsKey(PresentationClass.PHONE_TOUCH, "default"),
            DisplaySettingsScope.THIS_DISPLAY,
            phoneExact,
        )
        assertEquals(phoneExact, store.load(ProfileKind.PHONE))
        store.save(ProfileKind.PHONE, phoneExact.copy(inputMode = 3))
        assertEquals(
            phoneExact.copy(inputMode = 3),
            store.load(DisplaySettingsKey(PresentationClass.PHONE_TOUCH, "default")),
        )
    }

    @Test
    fun exactDisplayOverrideWinsOverClassDefault() {
        val preferences = MemoryDisplayPreferences()
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})
        val dellKey = DisplaySettingsKey(
            PresentationClass.EXTERNAL_POINTER,
            "external:3:DEL|5:42313|12:DELL U2723QE",
        )
        val lgKey = DisplaySettingsKey(
            PresentationClass.EXTERNAL_POINTER,
            "external:3:GSM|4:5999|6:LG HDR",
        )
        val exactSettings = DisplaySettings(
            renderPolicy = RenderPolicy.NATIVE,
            uiScalePercent = null,
            inputMode = 1,
            inputRoute = ExternalInputRoutePreference.AUTOMATIC,
        )
        val classDefault = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1440,
            uiScalePercent = 150,
            inputMode = 2,
            inputRoute = ExternalInputRoutePreference.PHONE_RELAY,
        )

        store.save(dellKey, DisplaySettingsScope.THIS_DISPLAY, exactSettings)
        store.save(dellKey, DisplaySettingsScope.CLASS_DEFAULT, classDefault)

        assertEquals(exactSettings, store.load(dellKey))
        assertEquals(classDefault, store.load(lgKey))
    }

    @Test
    fun classDefaultSurvivesConnectionIdChange() {
        val preferences = MemoryDisplayPreferences()
        val store = DisplaySettingsStore(preferences, legacyInputMode = { null }, diagnostic = {})
        val classDefault = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1080,
            uiScalePercent = 125,
            inputMode = 2,
            inputRoute = ExternalInputRoutePreference.NATIVE,
        )

        store.save(
            key = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, null),
            scope = DisplaySettingsScope.CLASS_DEFAULT,
            settings = classDefault,
        )

        val firstConnection = DisplaySettingsKey(
            PresentationClass.EXTERNAL_POINTER,
            "external:3:RTK|3:447|7:RTK FHD|connection:conn-1",
        )
        val secondConnection = DisplaySettingsKey(
            PresentationClass.EXTERNAL_POINTER,
            "external:3:RTK|3:447|7:RTK FHD|connection:conn-2",
        )
        assertEquals(classDefault, store.load(firstConnection))
        assertEquals(classDefault, store.load(secondConnection))
    }

    @Test
    fun failedSchemaTwoCommitLeavesSchemaOneReadable() {
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 1,
                "phone.renderPolicy" to "native",
                "phone.uiScalePercent" to 125,
                "phone.inputMode" to 2,
                "monitor.renderPolicy" to "cap1440",
                "monitor.uiScalePercent" to 150,
                "monitor.inputMode" to 1,
                "display.external:mon-1.inputRoute" to "phoneRelay",
            ),
        )
        preferences.failWhenWritingKey = "schemaVersion"
        preferences.mutateFirstEntryBeforeFailure = true
        val diagnostics = mutableListOf<String>()
        val store = DisplaySettingsStore(
            preferences = preferences,
            legacyInputMode = { null },
            diagnostic = diagnostics::add,
        )

        val phoneWhileFailing = store.load(DisplaySettingsKey(PresentationClass.PHONE_TOUCH, "default"))
        val monitorWhileFailing = store.load(
            DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:mon-1"),
        )
        assertEquals(
            DisplaySettingsScope.CLASS_DEFAULT,
            store.resolveScope(DisplaySettingsKey(PresentationClass.PHONE_TOUCH, "default")),
        )
        assertEquals(
            DisplaySettingsScope.THIS_DISPLAY,
            store.resolveScope(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:mon-1")),
        )

        assertEquals(1, preferences.read("schemaVersion"))
        assertEquals("native", preferences.read("phone.renderPolicy"))
        assertEquals("cap1440", preferences.read("monitor.renderPolicy"))
        assertEquals("phoneRelay", preferences.read("display.external:mon-1.inputRoute"))
        assertEquals(
            DisplaySettings(RenderPolicy.NATIVE, 125, 2, ExternalInputRoutePreference.AUTOMATIC),
            phoneWhileFailing,
        )
        assertEquals(
            DisplaySettings(RenderPolicy.CAP_1440, 150, 1, ExternalInputRoutePreference.PHONE_RELAY),
            monitorWhileFailing,
        )
        assertTrue(diagnostics.isNotEmpty())

        preferences.failWhenWritingKey = null
        preferences.mutateFirstEntryBeforeFailure = false
        val monitorAfterRecovery = store.load(
            DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:mon-1"),
        )
        assertEquals(2, preferences.read("schemaVersion"))
        assertEquals(
            DisplaySettings(RenderPolicy.CAP_1440, 150, 1, ExternalInputRoutePreference.PHONE_RELAY),
            monitorAfterRecovery,
        )
    }

    @Test
    fun migrationCanRunTwiceWithoutChangingValues() {
        val preferences = MemoryDisplayPreferences(
            mapOf(
                "schemaVersion" to 1,
                "wide.renderPolicy" to "cap1440",
                "wide.uiScalePercent" to 150,
                "wide.inputMode" to 3,
                "monitor.renderPolicy" to "cap1080",
                "monitor.uiScalePercent" to 125,
                "monitor.inputMode" to 1,
                "display.external:mon-1.inputRoute" to "phoneRelay",
            ),
        )
        val firstStore = DisplaySettingsStore(preferences, legacyInputMode = { 2 }, diagnostic = {})
        val phoneKey = DisplaySettingsKey(PresentationClass.PHONE_TOUCH, "default")
        val wideKey = DisplaySettingsKey(PresentationClass.LARGE_TOUCH, "default")
        val monitorKey = DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, "external:mon-1")
        val overrideKey = DisplaySettingsKey(PresentationClass.EXTERNAL_TOUCH, "external:touch-1")
        val touchOverride = DisplaySettings(
            renderPolicy = RenderPolicy.NATIVE,
            uiScalePercent = 175,
            inputMode = 3,
            inputRoute = ExternalInputRoutePreference.NATIVE,
        )

        val firstPhone = firstStore.load(phoneKey)
        val firstWide = firstStore.load(wideKey)
        val firstMonitor = firstStore.load(monitorKey)
        firstStore.save(overrideKey, DisplaySettingsScope.THIS_DISPLAY, touchOverride)
        val snapshotAfterFirstMigration = preferences.snapshot()

        val secondStore = DisplaySettingsStore(preferences, legacyInputMode = { 3 }, diagnostic = {})
        secondStore.migrateSchema()

        assertEquals(snapshotAfterFirstMigration, preferences.snapshot())
        assertEquals(firstPhone, secondStore.load(phoneKey))
        assertEquals(firstWide, secondStore.load(wideKey))
        assertEquals(firstMonitor, secondStore.load(monitorKey))
        assertEquals(touchOverride, secondStore.load(overrideKey))
    }

    @Test
    fun scopedWireParserReadsAndValidatesPayload() {
        val explicit = parseScopedDisplaySettingsUpdate(
            mapOf(
                "displayKey" to "conn-6",
                "presentationClass" to "externalTouch",
                "stableDisplayKey" to "external:3:WAC|4:1001|12:Wacom Cintiq",
                "settingsScope" to "classDefault",
                "renderPolicy" to "native",
                "uiScalePercent" to null,
                "inputMode" to 3,
                "inputRoute" to "native",
            ),
        )
        assertEquals("conn-6", explicit.displayKey)
        assertEquals(PresentationClass.EXTERNAL_TOUCH, explicit.presentationClass)
        assertEquals("external:3:WAC|4:1001|12:Wacom Cintiq", explicit.stableDisplayKey)
        assertEquals(DisplaySettingsScope.CLASS_DEFAULT, explicit.settingsScope)
        assertEquals(ExternalInputRoutePreference.NATIVE, explicit.settings.inputRoute)

        val legacyPayload = parseScopedDisplaySettingsUpdate(
            mapOf(
                "displayKey" to "conn-6",
                "renderPolicy" to "cap1440",
                "uiScalePercent" to 150,
                "inputMode" to 1,
                "inputRoute" to "automatic",
            ),
        )
        assertNull(legacyPayload.settingsScope)
        assertNull(legacyPayload.presentationClass)
        assertNull(legacyPayload.stableDisplayKey)

        assertThrows(IllegalArgumentException::class.java) {
            parseScopedDisplaySettingsUpdate(
                mapOf(
                    "displayKey" to "conn-6",
                    "settingsScope" to "everywhere",
                    "renderPolicy" to "native",
                    "uiScalePercent" to null,
                    "inputMode" to 1,
                    "inputRoute" to "automatic",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseScopedDisplaySettingsUpdate(
                mapOf(
                    "displayKey" to "conn-6",
                    "presentationClass" to "tv",
                    "renderPolicy" to "native",
                    "uiScalePercent" to null,
                    "inputMode" to 1,
                    "inputRoute" to "automatic",
                ),
            )
        }
    }

    @Test
    fun coordinatorSettingsPayloadUsesEvidenceClassActivePlanAndScope() {
        val defaultDisplay = DesktopDisplay(
            id = 0,
            key = "conn-default",
            name = "Built-in",
            isDefault = true,
            eligible = true,
            reason = null,
            settingsKey = "default",
        )
        val touchMonitor = DesktopDisplay(
            id = 6,
            key = "conn-touch",
            name = "Wacom Cintiq",
            isDefault = false,
            eligible = true,
            reason = null,
            settingsKey = "external:3:WAC|4:1001|12:Wacom Cintiq",
        )
        val anonymousMonitor = DesktopDisplay(
            id = 7,
            key = "conn-anon",
            name = "Anonymous",
            isDefault = false,
            eligible = true,
            reason = null,
            settingsKey = "",
        )
        val store = DisplaySettingsStore(MemoryDisplayPreferences(), legacyInputMode = { null }, diagnostic = {})
        val contexts = mapOf(
            0 to presentationContext(
                displayId = 0,
                isDefault = true,
                width = 2200,
                height = 1800,
                densityDpi = 320,
                touch = TouchEvidence.CURRENT_DISPLAY,
            ),
            6 to presentationContext(
                displayId = 6,
                isDefault = false,
                width = 1920,
                height = 1080,
                densityDpi = 160,
                touch = TouchEvidence.CURRENT_DISPLAY,
            ),
            7 to presentationContext(
                displayId = 7,
                isDefault = false,
                width = 1920,
                height = 1080,
                densityDpi = 160,
                touch = TouchEvidence.NONE,
            ),
        )
        val coordinator = DesktopDisplayCoordinator(
            host = object : DesktopDisplayHost {
                override fun currentDisplay() = defaultDisplay
                override fun displays() = listOf(defaultDisplay, touchMonitor, anonymousMonitor)
                override fun defaultProfileKind() = ProfileKind.WIDE
                override fun sessionRunning() = true
                override fun launch(request: DesktopLaunchRequest) = Unit
                override fun focus(requestId: Long) = false
                override fun cancelLaunch(requestId: Long) = Unit
                override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
                override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
            },
            settingsStore = store,
            presentationContextProvider = contexts::get,
        )

        val inactiveDefault = coordinator.settingsFor(defaultDisplay.key)
        assertEquals("largeTouch", inactiveDefault["presentationClass"])
        assertEquals("default", inactiveDefault["stableDisplayKey"])
        assertEquals("thisDisplay", inactiveDefault["settingsScope"])
        assertEquals(3, inactiveDefault["inputMode"])

        val lease = (coordinator.claim(defaultDisplay.id) {} as RendererClaim.Accepted).lease
        var refreshes = 0
        coordinator.setProfileRefresher(lease) { done ->
            refreshes++
            done(Result.success(Unit))
        }
        coordinator.saveSettingsFor(
            displayKey = defaultDisplay.key,
            settings = DisplaySettings(RenderPolicy.NATIVE, 125, 2),
            scope = DisplaySettingsScope.THIS_DISPLAY,
        ) { result -> assertTrue(result.isSuccess) }
        assertEquals(0, refreshes)
        coordinator.saveProfileSettings(ProfileKind.WIDE, DisplaySettings(RenderPolicy.NATIVE, 125, 3))
        assertEquals(
            3,
            store.load(DisplaySettingsKey(PresentationClass.LARGE_TOUCH, "default"))?.inputMode,
        )

        coordinator.reportGeometry(lease, ProfileKind.PHONE, PixelSize(720, 1560))

        val activeSplitDefault = coordinator.settingsFor(defaultDisplay.key)
        assertEquals("phoneTouch", activeSplitDefault["presentationClass"])

        val initialTouchMonitor = coordinator.settingsFor(touchMonitor.key)
        assertEquals("externalTouch", initialTouchMonitor["presentationClass"])
        assertEquals(touchMonitor.settingsKey, initialTouchMonitor["stableDisplayKey"])
        assertEquals("thisDisplay", initialTouchMonitor["settingsScope"])
        assertEquals(3, initialTouchMonitor["inputMode"])

        val classSettings = DisplaySettings(
            renderPolicy = RenderPolicy.CAP_1440,
            uiScalePercent = 150,
            inputMode = 3,
            inputRoute = ExternalInputRoutePreference.PHONE_RELAY,
        )
        coordinator.saveSettingsFor(
            displayKey = touchMonitor.key,
            settings = classSettings,
            scope = DisplaySettingsScope.CLASS_DEFAULT,
        ) { result -> assertTrue(result.isSuccess) }
        assertEquals(0, refreshes)

        val afterClassSave = coordinator.settingsFor(touchMonitor.key)
        assertEquals("classDefault", afterClassSave["settingsScope"])
        assertEquals("cap1440", afterClassSave["renderPolicy"])
        assertEquals(150, afterClassSave["uiScalePercent"])
        assertEquals("phoneRelay", afterClassSave["inputRoute"])

        val exactTouchSettings = DisplaySettings(
            renderPolicy = RenderPolicy.NATIVE,
            uiScalePercent = 125,
            inputMode = 3,
            inputRoute = ExternalInputRoutePreference.NATIVE,
        )
        coordinator.saveSettingsFor(
            displayKey = touchMonitor.key,
            settings = exactTouchSettings,
            scope = DisplaySettingsScope.THIS_DISPLAY,
        ) { result -> assertTrue(result.isSuccess) }
        val afterExactSave = coordinator.settingsFor(touchMonitor.key)
        assertEquals("thisDisplay", afterExactSave["settingsScope"])
        assertEquals("native", afterExactSave["renderPolicy"])
        assertEquals(125, afterExactSave["uiScalePercent"])
        assertEquals("native", afterExactSave["inputRoute"])

        coordinator.saveSettingsFor(
            displayKey = defaultDisplay.key,
            settings = DisplaySettings(RenderPolicy.NATIVE, 125, 3),
            scope = DisplaySettingsScope.THIS_DISPLAY,
        ) { result -> assertTrue(result.isSuccess) }
        assertEquals(1, refreshes)

        val anonymousPayload = coordinator.settingsFor(anonymousMonitor.key)
        assertEquals("externalPointer", anonymousPayload["presentationClass"])
        assertNull(anonymousPayload["stableDisplayKey"])
        assertEquals("classDefault", anonymousPayload["settingsScope"])
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.saveSettingsFor(
                displayKey = anonymousMonitor.key,
                settings = DisplaySettings(RenderPolicy.NATIVE, null, 1),
                scope = DisplaySettingsScope.THIS_DISPLAY,
            ) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.saveSettingsFor(
                ScopedDisplaySettingsUpdate(
                    displayKey = touchMonitor.key,
                    settings = exactTouchSettings,
                    settingsScope = DisplaySettingsScope.THIS_DISPLAY,
                    stableDisplayKey = "external:other-monitor",
                ),
            ) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.saveSettingsFor(
                ScopedDisplaySettingsUpdate(
                    displayKey = touchMonitor.key,
                    settings = exactTouchSettings,
                    settingsScope = DisplaySettingsScope.CLASS_DEFAULT,
                    presentationClass = PresentationClass.PHONE_TOUCH,
                ),
            ) {}
        }

        val anonLease = (coordinator.claim(anonymousMonitor.id) {} as RendererClaim.Accepted).lease
        val storeWithoutTouchDefault = DisplaySettingsStore(MemoryDisplayPreferences(), legacyInputMode = { null }, diagnostic = {})
        val promotedTouchMonitor = DesktopDisplay(9, "conn-9", "Promoted Touch", isDefault = false, eligible = true, reason = null, settingsKey = "external:promoted")
        val syntheticTnt = DesktopDisplay(100003, "bound:100003:tnt", "TNT", isDefault = false, eligible = true, reason = null, settingsKey = "external:tnt")
        var activeHostCurrent: DesktopDisplay? = syntheticTnt
        var enumeratedDisplays: List<DesktopDisplay> = listOf(defaultDisplay, promotedTouchMonitor)
        val tntStore = DisplaySettingsStore(MemoryDisplayPreferences(), legacyInputMode = { null }, diagnostic = {})
        val tntExact = DisplaySettings(RenderPolicy.CAP_1440, 150, 1, ExternalInputRoutePreference.PHONE_RELAY)
        tntStore.save(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, syntheticTnt.settingsKey), DisplaySettingsScope.THIS_DISPLAY, tntExact)
        val tntCoordinator = DesktopDisplayCoordinator(
            host = object : DesktopDisplayHost {
                override fun currentDisplay() = activeHostCurrent
                override fun displays() = enumeratedDisplays
                override fun defaultProfileKind() = ProfileKind.PHONE
                override fun sessionRunning() = true
                override fun launch(request: DesktopLaunchRequest) = Unit
                override fun focus(requestId: Long) = false
                override fun cancelLaunch(requestId: Long) = Unit
                override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
                override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
            },
            settingsStore = tntStore,
            presentationContextProvider = { id ->
                if (id == promotedTouchMonitor.id) presentationContext(id, false, 1920, 1080, 160, TouchEvidence.UNKNOWN_ASSOCIATION) else null
            },
        )
        val tntActivity = android.app.Activity()
        assertTrue(tntCoordinator.bindActivity(tntActivity))
        val tntLease = (tntCoordinator.claim(syntheticTnt.id) {} as RendererClaim.Accepted).lease
        activeHostCurrent = defaultDisplay
        assertEquals(tntExact, tntCoordinator.loadSettings(ProfileKind.MONITOR, syntheticTnt.id))
        tntCoordinator.saveProfileSettings(ProfileKind.MONITOR, tntExact.copy(inputMode = 2))
        assertEquals(2, tntStore.load(DisplaySettingsKey(PresentationClass.EXTERNAL_POINTER, syntheticTnt.settingsKey))?.inputMode)
        tntCoordinator.release(tntLease)

        val promotedCoordinator = DesktopDisplayCoordinator(
            host = object : DesktopDisplayHost {
                override fun currentDisplay() = defaultDisplay
                override fun displays() = listOf(defaultDisplay, promotedTouchMonitor)
                override fun defaultProfileKind() = ProfileKind.PHONE
                override fun sessionRunning() = true
                override fun launch(request: DesktopLaunchRequest) = Unit
                override fun focus(requestId: Long) = false
                override fun cancelLaunch(requestId: Long) = Unit
                override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
                override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
            },
            settingsStore = storeWithoutTouchDefault,
            presentationContextProvider = { id ->
                presentationContext(id, id == 0, 1920, 1080, 160, TouchEvidence.UNKNOWN_ASSOCIATION)
            },
        )
        val promotedLease = (promotedCoordinator.claim(promotedTouchMonitor.id) {} as RendererClaim.Accepted).lease
        promotedCoordinator.reportGeometry(promotedLease, ProfileKind.MONITOR, PixelSize(1920, 1080), PresentationClass.EXTERNAL_TOUCH)
        assertEquals(3, promotedCoordinator.settingsFor(promotedTouchMonitor.key)["inputMode"])

        coordinator.reportGeometry(
            anonLease,
            ProfileKind.MONITOR,
            PixelSize(1920, 1080),
            PresentationClass.EXTERNAL_TOUCH,
        )
        assertEquals(classSettings, coordinator.loadSettings(ProfileKind.MONITOR, anonymousMonitor.id))
        assertEquals(125, coordinator.loadSettings(ProfileKind.PHONE)?.uiScalePercent)
        coordinator.saveProfileSettings(ProfileKind.PHONE, DisplaySettings(RenderPolicy.NATIVE, 150, 2))
        assertEquals(150, coordinator.loadSettings(ProfileKind.PHONE)?.uiScalePercent)
        coordinator.saveProfileSettings(ProfileKind.WIDE, DisplaySettings(RenderPolicy.NATIVE, 175, 3))
        assertEquals(175, coordinator.loadSettings(ProfileKind.WIDE)?.uiScalePercent)
        assertEquals(175, coordinator.settingsFor(defaultDisplay.key)["uiScalePercent"])
    }

    private fun presentationContext(
        displayId: Int,
        isDefault: Boolean,
        width: Int,
        height: Int,
        densityDpi: Int,
        touch: TouchEvidence,
    ): PresentationContext {
        val mode = DisplayModeEvidence(1, width, height, 60f)
        return PresentationContext(
            displayId = displayId,
            displayName = "Display $displayId",
            displayFlags = 0,
            isDefaultDisplay = isDefault,
            windowSizePx = PixelSize(width, height),
            densityDpi = densityDpi,
            fontScale = 1f,
            isDesktopUiMode = !isDefault,
            currentMode = mode,
            supportedModes = listOf(mode),
            input = InputCapabilities(
                touch = touch,
                pointerAssociation = PointerAssociationEvidence.NO_EXTERNAL_POINTER,
                hasMouse = !isDefault,
                hasTouchpad = false,
                hasKeyboard = false,
                hasStylus = false,
            ),
        )
    }

    private class MemoryDisplayPreferences(
        initial: Map<String, Any> = emptyMap(),
    ) : DisplayPreferences {
        private val values = initial.toMutableMap()
        var failWhenWritingKey: String? = null
        var mutateFirstEntryBeforeFailure: Boolean = false

        override fun read(key: String): Any? = values[key]

        override fun write(changes: Map<String, Any?>) {
            val failKey = failWhenWritingKey
            if (failKey != null && changes.containsKey(failKey)) {
                if (mutateFirstEntryBeforeFailure) {
                    changes.entries.firstOrNull { it.key != failKey }?.let { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
                throw IllegalStateException("Simulated preference commit failure on $failKey")
            }
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        fun snapshot(): Map<String, Any> = values.toMap()
    }
}
