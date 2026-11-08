package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageStoreHistoryTest {

    @Test
    fun legacyNativePackagesMigrateToEnvironmentScopedRepositoryIds() {
        val storage = FakeHistoryStorage(
            legacy = setOf("git", "firefox"),
        )

        val history = DefaultPackageStoreHistory(storage)

        assertEquals(
            setOf("repo.git", "repo.firefox"),
            history.tracked(EnvironmentKind.TERMUX_NATIVE),
        )
        assertTrue(storage.migrationComplete)
        assertEquals(1, storage.writeCalls)
    }

    @Test
    fun legacyMigrationIsIdempotent() {
        val storage = FakeHistoryStorage(legacy = setOf("git"))

        DefaultPackageStoreHistory(storage)
        DefaultPackageStoreHistory(storage)

        assertEquals(setOf("repo.git"), storage.tracked[EnvironmentKind.TERMUX_NATIVE])
        assertEquals(1, storage.writeCalls)
        assertEquals(1, storage.markCalls)
    }

    @Test
    fun legacyMigrationDoesNotSeedProotHistory() {
        val history = DefaultPackageStoreHistory(
            FakeHistoryStorage(legacy = setOf("git")),
        )

        assertTrue(history.tracked(EnvironmentKind.PROOT_DEBIAN).isEmpty())
    }

    @Test
    fun trackAndUntrackStayScopedToOneEnvironment() {
        val history = DefaultPackageStoreHistory(FakeHistoryStorage())

        history.track(EnvironmentKind.TERMUX_NATIVE, "repo.git")
        history.track(EnvironmentKind.PROOT_DEBIAN, "app.firefox")
        history.untrack(EnvironmentKind.TERMUX_NATIVE, "repo.git")

        assertTrue(history.tracked(EnvironmentKind.TERMUX_NATIVE).isEmpty())
        assertEquals(
            setOf("app.firefox"),
            history.tracked(EnvironmentKind.PROOT_DEBIAN),
        )
    }
}

internal class FakeHistoryStorage(
    private val legacy: Set<String> = emptySet(),
) : PackageStoreHistoryStorage {
    val tracked = mutableMapOf<EnvironmentKind, Set<String>>()
    var migrationComplete = false
    var writeCalls = 0
    var markCalls = 0

    override fun readLegacyNativePackages(): Set<String> = legacy

    override fun readTracked(kind: EnvironmentKind): Set<String> =
        tracked[kind].orEmpty()

    override fun writeTracked(kind: EnvironmentKind, entryIds: Set<String>) {
        writeCalls++
        tracked[kind] = entryIds.toSet()
    }

    override fun legacyMigrationComplete(): Boolean = migrationComplete

    override fun markLegacyMigrationComplete() {
        markCalls++
        migrationComplete = true
    }
}
