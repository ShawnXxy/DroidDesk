package com.orailnoor.droiddesk.packages

import android.content.Context
import com.orailnoor.droiddesk.runtime.EnvironmentKind

internal interface PackageStoreHistory {
    fun tracked(kind: EnvironmentKind): Set<String>
    fun track(kind: EnvironmentKind, entryId: String)
    fun untrack(kind: EnvironmentKind, entryId: String)
}

internal interface PackageStoreHistoryStorage {
    fun readLegacyNativePackages(): Set<String>
    fun readTracked(kind: EnvironmentKind): Set<String>
    fun writeTracked(kind: EnvironmentKind, entryIds: Set<String>)
    fun legacyMigrationComplete(): Boolean
    fun markLegacyMigrationComplete()
}

internal class DefaultPackageStoreHistory(
    private val storage: PackageStoreHistoryStorage,
) : PackageStoreHistory {
    init {
        migrateLegacyNativePackages()
    }

    override fun tracked(kind: EnvironmentKind): Set<String> =
        storage.readTracked(kind).toSet()

    override fun track(kind: EnvironmentKind, entryId: String) {
        require(entryId.isNotBlank()) { "Tracked package store entry ID must not be blank" }
        storage.writeTracked(kind, tracked(kind) + entryId)
    }

    override fun untrack(kind: EnvironmentKind, entryId: String) {
        storage.writeTracked(kind, tracked(kind) - entryId)
    }

    private fun migrateLegacyNativePackages() {
        if (storage.legacyMigrationComplete()) return
        val migrated = storage.readLegacyNativePackages()
            .filter(PACKAGE_NAME::matches)
            .mapTo(mutableSetOf()) { "repo.$it" }
        if (migrated.isNotEmpty()) {
            storage.writeTracked(
                EnvironmentKind.TERMUX_NATIVE,
                storage.readTracked(EnvironmentKind.TERMUX_NATIVE) + migrated,
            )
        }
        storage.markLegacyMigrationComplete()
    }

    private companion object {
        val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]{0,127}")
    }
}

internal class AndroidPackageStoreHistory(
    context: Context,
) : PackageStoreHistory by DefaultPackageStoreHistory(
    AndroidPackageStoreHistoryStorage(context.applicationContext),
)

private class AndroidPackageStoreHistoryStorage(
    context: Context,
) : PackageStoreHistoryStorage {
    private val legacy = context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
    private val current = context.getSharedPreferences("package_store_v2", Context.MODE_PRIVATE)

    override fun readLegacyNativePackages(): Set<String> =
        legacy.getStringSet("installed", emptySet())?.toSet().orEmpty()

    override fun readTracked(kind: EnvironmentKind): Set<String> =
        current.getStringSet(key(kind), emptySet())?.toSet().orEmpty()

    override fun writeTracked(kind: EnvironmentKind, entryIds: Set<String>) {
        check(current.edit().putStringSet(key(kind), entryIds).commit()) {
            "Could not persist package store history for ${kind.wireValue}"
        }
    }

    override fun legacyMigrationComplete(): Boolean =
        current.getBoolean(KEY_LEGACY_MIGRATED, false)

    override fun markLegacyMigrationComplete() {
        check(current.edit().putBoolean(KEY_LEGACY_MIGRATED, true).commit()) {
            "Could not persist package store history migration"
        }
    }

    private fun key(kind: EnvironmentKind): String = "tracked_${kind.wireValue}"

    private companion object {
        const val KEY_LEGACY_MIGRATED = "legacy_native_migrated"
    }
}
