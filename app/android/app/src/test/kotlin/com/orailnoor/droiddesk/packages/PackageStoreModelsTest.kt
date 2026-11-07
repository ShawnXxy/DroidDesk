package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageStoreModelsTest {

    @Test
    fun targetRequiresReasonWhenStoreIsUnavailable() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageStoreTarget(
                environmentKind = EnvironmentKind.PROOT_DEBIAN,
                displayName = "Debian PRoot",
                availability = PackageStoreAvailability.ENVIRONMENT_NOT_INSTALLED,
                reason = null,
                capabilities = capabilities(),
            )
        }

        val ready = PackageStoreTarget(
            environmentKind = EnvironmentKind.PROOT_DEBIAN,
            displayName = "Debian PRoot",
            availability = PackageStoreAvailability.READY,
            reason = null,
            capabilities = capabilities(),
        )

        assertNull(ready.reason)
    }

    @Test
    fun entryRejectsBlankOpaqueIdAndNegativeVersionsAreNotSynthesized() {
        assertThrows(IllegalArgumentException::class.java) {
            entry(entryId = " ")
        }

        val entry = entry(
            availableVersion = null,
            installedVersion = null,
        )

        assertNull(entry.availableVersion)
        assertNull(entry.installedVersion)
    }

    @Test
    fun operationRequiresEntryForInstallRemoveAndUpgrade() {
        listOf(
            PackageOperationKind.INSTALL,
            PackageOperationKind.REMOVE,
            PackageOperationKind.UPGRADE,
        ).forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) {
                PackageOperationRequest(kind = kind)
            }
        }
    }

    @Test
    fun cleanCacheAndUpgradeAllRejectEntryId() {
        listOf(
            PackageOperationKind.CLEAN_CACHE,
            PackageOperationKind.UPGRADE_ALL,
        ).forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) {
                PackageOperationRequest(kind = kind, entryId = "app.firefox")
            }
        }
    }

    @Test
    fun progressIsNullableOrBetweenZeroAndOne() {
        assertNull(event(progress = null).progress)
        assertEquals(0.0, event(progress = 0.0).progress)
        assertEquals(1.0, event(progress = 1.0).progress)

        assertThrows(IllegalArgumentException::class.java) {
            event(progress = -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            event(progress = 1.01)
        }
    }

    @Test
    fun failedResultRequiresTypedError() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageOperationResult(
                operationId = 7,
                environmentKind = EnvironmentKind.PROOT_DEBIAN,
                kind = PackageOperationKind.INSTALL,
                entryId = "app.firefox",
                outcome = PackageOperationOutcome.FAILED,
                message = "Install failed",
                entry = null,
                bytesFreed = null,
                warnings = emptyList(),
                error = null,
            )
        }
    }

    private fun capabilities() = PackageStoreCapabilities(
        browse = true,
        arbitraryRepositoryPackages = false,
        remove = true,
        updateOne = true,
        updateAll = true,
        cleanCache = true,
    )

    private fun entry(
        entryId: String = "app.firefox",
        availableVersion: String? = "128.9.0esr",
        installedVersion: String? = null,
    ) = PackageStoreEntry(
        entryId = entryId,
        displayName = "Firefox ESR",
        summary = "Web browser",
        category = "Internet",
        iconNames = listOf("firefox-esr"),
        gui = true,
        supportLevel = PackageSupportLevel.SUPPORTED,
        state = PackageEntryState.AVAILABLE,
        availableVersion = availableVersion,
        installedVersion = installedVersion,
        removable = true,
        limitations = emptyList(),
        sourceLabel = "Debian Bookworm",
    )

    private fun event(progress: Double?) = PackageOperationEvent(
        operationId = 7,
        environmentKind = EnvironmentKind.PROOT_DEBIAN,
        phase = PackageOperationPhase.INSTALLING,
        progress = progress,
        message = "Installing Firefox",
        logChunk = null,
        cancellable = true,
    )
}
