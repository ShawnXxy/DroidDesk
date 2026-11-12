package com.orailnoor.droiddesk.display

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.orailnoor.droiddesk.display.capability.PresentationClass
import com.orailnoor.droiddesk.display.input.ExternalInputRoutePreference

enum class DisplaySettingsScope(val wireName: String) {
    CLASS_DEFAULT("classDefault"), THIS_DISPLAY("thisDisplay");
    companion object { fun fromWire(value: String?): DisplaySettingsScope? = entries.firstOrNull { it.wireName == value } }
}
data class DisplaySettingsKey(val presentationClass: PresentationClass, val stableDisplayKey: String? = null)
data class ScopedDisplaySettingsUpdate(
    val displayKey: String, val settings: DisplaySettings, val settingsScope: DisplaySettingsScope? = null,
    val presentationClass: PresentationClass? = null, val stableDisplayKey: String? = null,
)
fun parseScopedDisplaySettingsUpdate(value: Any?): ScopedDisplaySettingsUpdate {
    val base = parseDisplaySettingsUpdate(value); val args = value as Map<*, *>
    val scope = args["settingsScope"]?.let { DisplaySettingsScope.fromWire(it as? String) ?: throw IllegalArgumentException("Invalid display settings scope") }
    val cls = args["presentationClass"]?.let { raw -> PresentationClass.entries.firstOrNull { it.wireName == raw as? String } ?: throw IllegalArgumentException("Invalid presentation class") }
    val rawStable = args["stableDisplayKey"]; require(rawStable == null || rawStable is String) { "Invalid stableDisplayKey" }
    return ScopedDisplaySettingsUpdate(base.displayKey, base.settings, scope, cls, (rawStable as? String)?.takeIf { it.isNotBlank() })
}

interface DisplayPreferences {
    fun read(key: String): Any?
    fun write(changes: Map<String, Any?>)
}

class DisplaySettingsStore(
    private val preferences: DisplayPreferences,
    private val legacyInputMode: () -> Int?,
    private val diagnostic: (String) -> Unit,
) {
    private constructor(preferences: AndroidDisplayPreferences) : this(
        preferences,
        preferences::legacyInputMode,
        { Log.w("DesktopDisplay", it) },
    )

    constructor(context: Context) : this(AndroidDisplayPreferences(context))

    fun loadAutoOpenOnMonitor(): Boolean {
        ensureSchema()
        val value = preferences.read("autoOpenOnMonitor")
        if (value == null) return true
        if (value is Boolean) return value
        diagnostic("Invalid autoOpenOnMonitor; restoring Enabled")
        preferences.write(mapOf("autoOpenOnMonitor" to true))
        return true
    }

    fun saveAutoOpenOnMonitor(enabled: Boolean) {
        ensureSchema()
        val previous = preferences.read("autoOpenOnMonitor")
        try {
            preferences.write(mapOf("autoOpenOnMonitor" to enabled))
        } catch (failure: IllegalStateException) {
            try {
                preferences.write(mapOf("autoOpenOnMonitor" to previous))
            } catch (rollbackFailure: IllegalStateException) {
                if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    fun hasExactOverride(key: DisplaySettingsKey): Boolean {
        ensureSchema(); val cls = key.presentationClass; val stableKey = key.stableDisplayKey?.takeIf { it.isNotBlank() } ?: return false
        val legacy = if (kindFor(cls) == ProfileKind.MONITOR) migrateOrReadLegacyRoute(cls, stableKey) { baseSettings(cls) ?: defaultSettingsFor(cls) } else null
        return hasRecord(displayPrefix(cls, stableKey)) || (legacy != null && legacy != ExternalInputRoutePreference.AUTOMATIC)
    }
    fun resolveScope(key: DisplaySettingsKey): DisplaySettingsScope {
        key.stableDisplayKey?.takeIf { it.isNotBlank() } ?: return DisplaySettingsScope.CLASS_DEFAULT
        return if (hasExactOverride(key) || baseSettings(key.presentationClass) == null) DisplaySettingsScope.THIS_DISPLAY else DisplaySettingsScope.CLASS_DEFAULT
    }
    fun load(key: DisplaySettingsKey): DisplaySettings? {
        ensureSchema(); val cls = key.presentationClass; val kind = kindFor(cls); val stableKey = key.stableDisplayKey?.takeIf { it.isNotBlank() }
        val base by lazy { baseSettings(cls) }
        val legacyRoute = if (stableKey != null && kind == ProfileKind.MONITOR) migrateOrReadLegacyRoute(cls, stableKey) { base ?: defaultSettingsFor(cls) } else null
        if (stableKey != null) displayPrefix(cls, stableKey).takeIf(::hasRecord)?.let { return readEntry(kind, it, repair = true) }
        return base?.let { if (legacyRoute != null) it.copy(inputRoute = legacyRoute) else it } ?: legacyRoute?.let { defaultSettingsFor(cls).copy(inputRoute = it) }
    }
    fun save(key: DisplaySettingsKey, scope: DisplaySettingsScope, settings: DisplaySettings) {
        val cls = key.presentationClass; val stableKey = key.stableDisplayKey?.takeIf { it.isNotBlank() }
        require(scope != DisplaySettingsScope.THIS_DISPLAY || stableKey != null) { "Exact display settings require a stable display key" }
        migrateSchema(allowFallback = false); val changes = mutableMapOf<String, Any?>()
        if (stableKey != null && kindFor(cls) == ProfileKind.MONITOR) {
            val dPrefix = displayPrefix(cls, stableKey); val legacy = if (scope == DisplaySettingsScope.CLASS_DEFAULT && !hasRecord(dPrefix)) ExternalInputRoutePreference.fromWire(preferences.read("display.$stableKey.inputRoute") as? String) else null
            changes["display.$stableKey.inputRoute"] = null
            if (legacy != null && legacy != ExternalInputRoutePreference.AUTOMATIC) changes.putAll(recordMap(dPrefix, (baseSettings(cls) ?: defaultSettingsFor(cls)).copy(inputRoute = legacy)))
        }
        val prefix = if (scope == DisplaySettingsScope.CLASS_DEFAULT) classPrefix(cls) else displayPrefix(cls, checkNotNull(stableKey))
        changes.putAll(recordMap(prefix, settings)); if (cls == PresentationClass.PHONE_TOUCH) changes["phone.inputMigrated"] = true
        writeWithRollback(changes)
    }
    fun load(kind: ProfileKind, displaySettingsKey: String? = null): DisplaySettings? =
        load(DisplaySettingsKey(classesFor(kind).first(), displaySettingsKey ?: if (kind != ProfileKind.MONITOR) "default" else null))
    private fun baseSettings(cls: PresentationClass): DisplaySettings? {
        val kind = kindFor(cls); val fb = if (cls == PresentationClass.PHONE_TOUCH && preferences.read("phone.inputMigrated") != true && preferences.read("phone.inputMode") == null) legacyInputMode() else null
        return readEntry(kind, classPrefix(cls), repair = true) ?: readEntry(kind, kind.wireName, fb, repair = false)
    }

    private fun readEntry(kind: ProfileKind, prefix: String, fallbackInput: Int? = null, repair: Boolean): DisplaySettings? {
        val renderValue = preferences.read("$prefix.renderPolicy")
        val scaleValue = preferences.read("$prefix.uiScalePercent")
        val inputValue = preferences.read("$prefix.inputMode") ?: fallbackInput
        val routeKey = "$prefix.inputRoute"
        val routeValue = preferences.read(routeKey)
        if (renderValue == null && scaleValue == null && inputValue == null) {
            return null
        }

        val defaults = DisplayProfilePolicy.defaults(kind)
        val repairs = mutableMapOf<String, Any?>()
        val render = RenderPolicy.fromWire(renderValue as? String) ?: defaults.renderPolicy.also {
            if (renderValue != null) {
                diagnostic("Invalid $prefix.renderPolicy; restoring ${it.wireName}")
                repairs["$prefix.renderPolicy"] = it.wireName
            }
        }
        val scale = (scaleValue as? Int)?.takeIf { it in DisplaySettings.UI_SCALES }
        if (scaleValue != null && scale == null) {
            diagnostic("Invalid $prefix.uiScalePercent; restoring Automatic")
            repairs["$prefix.uiScalePercent"] = null
        }
        val input = (inputValue as? Int)?.takeIf { it in 1..3 } ?: defaults.inputMode.also {
            if (inputValue != null) {
                diagnostic("Invalid $prefix.inputMode; restoring Trackpad")
                if (preferences.read("$prefix.inputMode") != null) repairs["$prefix.inputMode"] = it
            }
        }
        val route = ExternalInputRoutePreference.fromWire(routeValue as? String)
            ?: ExternalInputRoutePreference.AUTOMATIC.also {
                if (routeValue != null) {
                    val invalidRouteKey = checkNotNull(routeKey)
                    diagnostic("Invalid $invalidRouteKey; restoring Automatic")
                    repairs[invalidRouteKey] = null
                }
            }
        if (repair && repairs.isNotEmpty()) writeSafely(repairs)
        return DisplaySettings(render, scale, input, route)
    }

    fun save(kind: ProfileKind, settings: DisplaySettings, displaySettingsKey: String? = null) {
        if (kind != ProfileKind.MONITOR) {
            val key = DisplaySettingsKey(classesFor(kind).first(), displaySettingsKey ?: "default")
            return save(key, if (displaySettingsKey != null || hasExactOverride(key)) DisplaySettingsScope.THIS_DISPLAY else DisplaySettingsScope.CLASS_DEFAULT, settings)
        }
        migrateSchema(allowFallback = false)
        val stableKey = displaySettingsKey?.takeIf { it.isNotBlank() }; val base = settings.copy(inputRoute = ExternalInputRoutePreference.AUTOMATIC)
        val changes = mutableMapOf<String, Any?>().also { if (stableKey != null) it["display.$stableKey.inputRoute"] = null }
        classesFor(ProfileKind.MONITOR).forEach { cls ->
            changes.putAll(recordMap(classPrefix(cls), base))
            if (stableKey != null && (settings.inputRoute != ExternalInputRoutePreference.AUTOMATIC || hasRecord(displayPrefix(cls, stableKey)))) changes.putAll(recordMap(displayPrefix(cls, stableKey), settings))
        }
        writeWithRollback(changes)
    }
    internal fun defaultSettingsFor(cls: PresentationClass) = DisplaySettings(if (cls == PresentationClass.PHONE_TOUCH) RenderPolicy.LEGACY_PHONE else RenderPolicy.NATIVE, null, if (cls == PresentationClass.EXTERNAL_POINTER) 1 else 3)
    private fun ensureSchema() = migrateSchema(allowFallback = true)
    internal fun migrateSchema(allowFallback: Boolean = false) {
        val version = preferences.read("schemaVersion")
        if (version != null && version != 1 && version != 2) diagnostic("Invalid display schemaVersion; restoring version 2")
        val v2Changes = mutableMapOf<String, Any?>()
        ProfileKind.entries.forEach { kind ->
            val legacyMode = if (kind == ProfileKind.PHONE && preferences.read("phone.inputMigrated") != true &&
                preferences.read("phone.inputMode") == null && preferences.read("${classPrefix(PresentationClass.PHONE_TOUCH)}.inputMode") == null
            ) legacyInputMode() else null
            readEntry(kind, kind.wireName, legacyMode, repair = false)?.copy(inputRoute = ExternalInputRoutePreference.AUTOMATIC)?.let { seed ->
                classesFor(kind).filterNot { hasRecord(classPrefix(it)) }.forEach { v2Changes.putAll(recordMap(classPrefix(it), seed)) }
            }
        }
        if (version != 2 || preferences.read("phone.inputMigrated") != true || v2Changes.isNotEmpty()) {
            v2Changes["phone.inputMigrated"] = true; v2Changes["schemaVersion"] = 2
            try { writeWithRollback(v2Changes) } catch (failure: IllegalStateException) {
                if (!allowFallback) throw failure
                diagnostic("Could not commit display schema v2; keeping schema v1 readable"); return
            }
        }
        val removals = ProfileKind.entries.flatMap { listOf("${it.wireName}.renderPolicy", "${it.wireName}.uiScalePercent", "${it.wireName}.inputMode") }
            .filter { preferences.read(it) != null }.associateWith<String, Any?> { null }
        if (removals.isNotEmpty()) try { writeWithRollback(removals) } catch (failure: IllegalStateException) {
            if (!allowFallback) throw failure
            diagnostic("Could not remove migrated schema v1 keys")
        }
    }

    private fun migrateOrReadLegacyRoute(cls: PresentationClass, stableKey: String, seedBase: () -> DisplaySettings): ExternalInputRoutePreference? {
        val legacyKey = "display.$stableKey.inputRoute"; val raw = preferences.read(legacyKey) ?: return null
        val parsed = ExternalInputRoutePreference.fromWire(raw as? String) ?: ExternalInputRoutePreference.AUTOMATIC.also { diagnostic("Invalid $legacyKey; restoring Automatic") }
        if (preferences.read("schemaVersion") != 2) return parsed
        val dPrefix = displayPrefix(cls, stableKey); val changes = mutableMapOf<String, Any?>(legacyKey to null)
        if (!hasRecord(dPrefix) && parsed != ExternalInputRoutePreference.AUTOMATIC) changes.putAll(recordMap(dPrefix, seedBase().copy(inputRoute = parsed)))
        writeSafely(changes)
        return parsed.takeIf { preferences.read(legacyKey) != null }
    }

    private fun hasRecord(prefix: String) = preferences.read("$prefix.renderPolicy") != null || preferences.read("$prefix.uiScalePercent") != null || preferences.read("$prefix.inputMode") != null
    private fun recordMap(prefix: String, s: DisplaySettings): MutableMap<String, Any?> = mutableMapOf("$prefix.renderPolicy" to s.renderPolicy.wireName, "$prefix.uiScalePercent" to s.uiScalePercent, "$prefix.inputMode" to s.inputMode, "$prefix.inputRoute" to s.inputRoute.wireName)
    private fun writeSafely(changes: Map<String, Any?>) = try { writeWithRollback(changes) } catch (_: IllegalStateException) { diagnostic("Could not persist display preference repair") }
    private fun writeWithRollback(changes: Map<String, Any?>) {
        val previous = changes.keys.associateWith(preferences::read)
        try { preferences.write(changes) } catch (failure: IllegalStateException) {
            val dirty = previous.filter { (key, oldValue) -> preferences.read(key) != oldValue }
            if (dirty.isNotEmpty()) try { preferences.write(dirty) } catch (rollbackFailure: IllegalStateException) { if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }
    private fun classPrefix(cls: PresentationClass) = "v2.class.${cls.wireName}"
    private fun displayPrefix(cls: PresentationClass, key: String) = "v2.display.${cls.wireName}.$key"
    private fun kindFor(cls: PresentationClass) = if (cls == PresentationClass.PHONE_TOUCH) ProfileKind.PHONE else if (cls == PresentationClass.LARGE_TOUCH) ProfileKind.WIDE else ProfileKind.MONITOR
    private fun classesFor(kind: ProfileKind) = when (kind) { ProfileKind.PHONE -> listOf(PresentationClass.PHONE_TOUCH); ProfileKind.WIDE -> listOf(PresentationClass.LARGE_TOUCH); ProfileKind.MONITOR -> listOf(PresentationClass.EXTERNAL_POINTER, PresentationClass.EXTERNAL_TOUCH) }
}

private class AndroidDisplayPreferences(context: Context) : DisplayPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(
        "droiddesk_display_v1", Context.MODE_PRIVATE,
    )
    private val legacy = context.applicationContext.getSharedPreferences("desktop_input", Context.MODE_PRIVATE)

    fun legacyInputMode(): Int? {
        val value = legacy.all["mode"]
        if (value != null && value !is Int) {
            Log.w("DesktopDisplay", "Invalid legacy phone inputMode; restoring Trackpad")
        }
        return value as? Int
    }

    override fun read(key: String): Any? = preferences.all[key]

    override fun write(changes: Map<String, Any?>) {
        val editor: SharedPreferences.Editor = preferences.edit()
        changes.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Unsupported display preference type for $key")
            }
        }
        check(editor.commit()) { "Could not persist display preferences" }
    }
}
