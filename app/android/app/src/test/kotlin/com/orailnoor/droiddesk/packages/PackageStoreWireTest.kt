package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageStoreWireTest {

    @Test
    fun queryResultUsesSchemaOneAndExactWireValues() {
        val result = PackageStoreQueryResult(
            target = target(),
            entries = listOf(entry()),
            storage = null,
            operation = null,
            refreshedAtEpochMs = null,
        )

        val wire = result.toWireMap()
        val target = wire["target"] as Map<*, *>
        val entry = (wire["entries"] as List<*>).single() as Map<*, *>

        assertEquals(1, wire["schemaVersion"])
        assertEquals("prootDebian", target["environmentKind"])
        assertEquals("ready", target["availability"])
        assertEquals("experimental", entry["supportLevel"])
        assertEquals("updateAvailable", entry["state"])
        assertNull(wire["storage"])
        assertNull(wire["operation"])
        assertNull(wire["completedResult"])
        assertNull(wire["refreshedAtEpochMs"])
    }

    @Test
    fun operationEventPreservesNullableProgressAndLogChunk() {
        val wire = PackageOperationEvent(
            operationId = 9,
            environmentKind = EnvironmentKind.TERMUX_NATIVE,
            phase = PackageOperationPhase.PREPARING,
            progress = null,
            message = "Preparing",
            logChunk = null,
            cancellable = false,
        ).toWireMap()

        assertEquals(1, wire["schemaVersion"])
        assertEquals(9L, wire["operationId"])
        assertEquals("termuxNative", wire["environmentKind"])
        assertEquals("preparing", wire["phase"])
        assertNull(wire["progress"])
        assertNull(wire["logChunk"])
    }

    @Test
    fun operationResultPreservesStableErrorCodeAndWarnings() {
        val wire = PackageOperationResult(
            operationId = 10,
            environmentKind = EnvironmentKind.PROOT_DEBIAN,
            kind = PackageOperationKind.INSTALL,
            entryId = "app.firefox",
            outcome = PackageOperationOutcome.FAILED,
            message = "Verification failed",
            entry = null,
            bytesFreed = null,
            warnings = listOf(
                PackageStoreWarning(
                    code = "DESKTOP_INTEGRATION_LIMITED",
                    message = "Open the XFCE Applications menu manually",
                ),
            ),
            error = PackageStoreError(
                code = "VERIFY_FAILED",
                message = "firefox-esr --version failed",
                logTail = "exit 1",
            ),
        ).toWireMap()

        val error = wire["error"] as Map<*, *>
        val warning = (wire["warnings"] as List<*>).single() as Map<*, *>

        assertEquals("failed", wire["outcome"])
        assertEquals("VERIFY_FAILED", error["code"])
        assertEquals("DESKTOP_INTEGRATION_LIMITED", warning["code"])
    }

    private fun target() = PackageStoreTarget(
        environmentKind = EnvironmentKind.PROOT_DEBIAN,
        displayName = "Debian PRoot",
        availability = PackageStoreAvailability.READY,
        reason = null,
        capabilities = PackageStoreCapabilities(
            browse = true,
            arbitraryRepositoryPackages = false,
            remove = true,
            updateOne = true,
            updateAll = true,
            cleanCache = true,
        ),
    )

    private fun entry() = PackageStoreEntry(
        entryId = "app.firefox",
        displayName = "Firefox ESR",
        summary = "Web browser",
        category = "Internet",
        iconNames = listOf("firefox-esr"),
        gui = true,
        supportLevel = PackageSupportLevel.EXPERIMENTAL,
        state = PackageEntryState.UPDATE_AVAILABLE,
        availableVersion = "129.0esr",
        installedVersion = "128.9.0esr",
        removable = true,
        limitations = listOf("Sandbox validation pending"),
        sourceLabel = "Debian Bookworm",
    )
}
