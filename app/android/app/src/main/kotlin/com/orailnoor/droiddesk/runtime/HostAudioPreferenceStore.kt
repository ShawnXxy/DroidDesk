package com.orailnoor.droiddesk.runtime

import android.content.Context

data class VerifiedAudioPath(
    val appVersionCode: Long,
    val libraryPath: String,
)
interface HostAudioPreferenceStore {
    fun load(): VerifiedAudioPath?
    fun save(value: VerifiedAudioPath)
    fun clear()
}
internal class SharedPreferencesHostAudioPreferenceStore(
    context: Context,
) : HostAudioPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): VerifiedAudioPath? {
        val hasVersion = preferences.contains(KEY_APP_VERSION_CODE)
        val hasPath = preferences.contains(KEY_LIBRARY_PATH)
        val libraryPath = preferences.getString(KEY_LIBRARY_PATH, null)
        if (!hasVersion || !hasPath || libraryPath.isNullOrBlank()) {
            if (hasVersion || hasPath) clear()
            return null
        }
        return VerifiedAudioPath(
            appVersionCode = preferences.getLong(KEY_APP_VERSION_CODE, 0L),
            libraryPath = libraryPath,
        )
    }

    override fun save(value: VerifiedAudioPath) {
        preferences.edit()
            .putLong(KEY_APP_VERSION_CODE, value.appVersionCode)
            .putString(KEY_LIBRARY_PATH, value.libraryPath)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_APP_VERSION_CODE)
            .remove(KEY_LIBRARY_PATH)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "host_audio_v1"
        const val KEY_APP_VERSION_CODE = "app_version_code"
        const val KEY_LIBRARY_PATH = "library_path"
    }
}
