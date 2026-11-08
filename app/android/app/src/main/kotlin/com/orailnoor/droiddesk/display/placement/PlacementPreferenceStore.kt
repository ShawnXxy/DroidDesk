package com.orailnoor.droiddesk.display.placement

import android.content.Context
import com.orailnoor.droiddesk.display.capability.PlacementMode

interface PlacementPreferenceStore {
    fun loadOverride(): PlacementMode?
    fun saveOverride(mode: PlacementMode?)
}

internal interface PlacementPreferenceStorage {
    fun read(): Any?
    fun write(value: String?)
}

class AndroidPlacementPreferenceStore internal constructor(
    private val storage: PlacementPreferenceStorage,
) : PlacementPreferenceStore {
    constructor(context: Context) : this(AndroidPlacementPreferenceStorage(context))

    override fun loadOverride(): PlacementMode? {
        val storedValue = storage.read() ?: return null
        val mode = (storedValue as? String)?.let { wireName ->
            PlacementMode.entries.firstOrNull { it.wireName == wireName }
        }
        if (mode != null) return mode

        storage.write(null)
        return null
    }

    override fun saveOverride(mode: PlacementMode?) {
        val previousValue = storage.read() as? String
        try {
            storage.write(mode?.wireName)
        } catch (failure: IllegalStateException) {
            try {
                storage.write(previousValue)
            } catch (rollbackFailure: IllegalStateException) {
                if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }
}

private class AndroidPlacementPreferenceStorage(context: Context) : PlacementPreferenceStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): Any? = preferences.all[KEY_OVERRIDE]

    override fun write(value: String?) {
        val editor = preferences.edit()
        if (value == null) {
            editor.remove(KEY_OVERRIDE)
        } else {
            editor.putString(KEY_OVERRIDE, value)
        }
        check(editor.commit()) { "Could not persist placement preference" }
    }

    private companion object {
        const val PREFERENCES_NAME = "droiddesk_placement_v1"
        const val KEY_OVERRIDE = "override"
    }
}
