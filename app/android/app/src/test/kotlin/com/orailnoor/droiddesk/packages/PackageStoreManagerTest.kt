package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.EnvironmentSelectionBusyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageStoreManagerTest {

    @Test
    fun queryRoutesOnlyToTheExplicitActiveEnvironment() {
        val native = FakeStore(EnvironmentKind.TERMUX_NATIVE)
        val proot = FakeStore(EnvironmentKind.PROOT_DEBIAN)
        val manager = manager(
            stores = listOf(native, proot),
            activeKind = { EnvironmentKind.PROOT_DEBIAN },
        )

        val result = manager.query(
            EnvironmentKind.PROOT_DEBIAN,
            PackageStoreQuery.Catalog("firefox"),
        ).getOrThrow()

        assertEquals(EnvironmentKind.PROOT_DEBIAN, result.target.environmentKind)
        assertEquals(0, native.queryCalls)
        assertEquals(1, proot.queryCalls)
    }

    @Test
    fun staleExpectedEnvironmentReturnsTargetChangedWithoutCallingAdapter() {
        val native = FakeStore(EnvironmentKind.TERMUX_NATIVE)
        val proot = FakeStore(EnvironmentKind.PROOT_DEBIAN)
        val manager = manager(
            stores = listOf(native, proot),
            activeKind = { EnvironmentKind.PROOT_DEBIAN },
        )

        val result = manager.execute(
            expectedKind = EnvironmentKind.TERMUX_NATIVE,
            request = PackageOperationRequest(
                kind = PackageOperationKind.INSTALL,
                entryId = "app.firefox",
            ),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, result.outcome)
        assertEquals("TARGET_CHANGED", result.error!!.code)
        assertEquals(0, native.executeCalls)
        assertEquals(0, proot.executeCalls)
    }

    @Test
    fun unsupportedChrootNeverFallsBackToNative() {
        val native = FakeStore(EnvironmentKind.TERMUX_NATIVE)
        val manager = manager(
            stores = listOf(native),
            activeKind = { EnvironmentKind.CHROOT_UBUNTU },
        )

        val error = manager.query(
            EnvironmentKind.CHROOT_UBUNTU,
            PackageStoreQuery.Installed,
        ).exceptionOrNull() as PackageStoreException

        assertEquals("UNSUPPORTED_ENVIRONMENT", error.code)
        assertEquals(0, native.queryCalls)
    }

    @Test
    fun duplicateAdaptersForOneEnvironmentAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            manager(
                stores = listOf(
                    FakeStore(EnvironmentKind.TERMUX_NATIVE),
                    FakeStore(EnvironmentKind.TERMUX_NATIVE),
                ),
                activeKind = { EnvironmentKind.TERMUX_NATIVE },
            )
        }
    }

    @Test
    fun missingAdapterReturnsUnsupportedEnvironment() {
        val manager = manager(
            stores = listOf(FakeStore(EnvironmentKind.PROOT_DEBIAN)),
            activeKind = { EnvironmentKind.TERMUX_NATIVE },
        )

        val queryError = manager.query(
            EnvironmentKind.TERMUX_NATIVE,
            PackageStoreQuery.Storage,
        ).exceptionOrNull() as PackageStoreException
        val operation = manager.execute(
            EnvironmentKind.TERMUX_NATIVE,
            PackageOperationRequest(PackageOperationKind.CLEAN_CACHE),
            observer = {},
        )

        assertEquals("UNSUPPORTED_ENVIRONMENT", queryError.code)
        assertEquals(PackageOperationOutcome.REJECTED, operation.outcome)
        assertEquals("UNSUPPORTED_ENVIRONMENT", operation.error!!.code)
        assertFalse(operation.bytesFreed != null)
    }

    @Test
    fun indexRefreshQueriesUseEnvironmentAdmission() {
        val store = FakeStore(EnvironmentKind.PROOT_DEBIAN)
        var admissions = 0
        val manager = manager(
            stores = listOf(store),
            activeKind = { EnvironmentKind.PROOT_DEBIAN },
            queryAdmission = { _, action ->
                admissions++
                action()
            },
        )

        manager.query(
            EnvironmentKind.PROOT_DEBIAN,
            PackageStoreQuery.Updates(refreshIndex = false),
        ).getOrThrow()
        assertEquals(0, admissions)

        manager.query(
            EnvironmentKind.PROOT_DEBIAN,
            PackageStoreQuery.Updates(refreshIndex = true),
        ).getOrThrow()
        assertEquals(1, admissions)
    }

    @Test
    fun busyIndexRefreshReturnsTypedOperationInProgress() {
        val store = FakeStore(EnvironmentKind.PROOT_DEBIAN)
        val manager = manager(
            stores = listOf(store),
            activeKind = { EnvironmentKind.PROOT_DEBIAN },
            queryAdmission = { _, _ -> throw EnvironmentSelectionBusyException() },
        )

        val error = manager.query(
            EnvironmentKind.PROOT_DEBIAN,
            PackageStoreQuery.Updates(refreshIndex = true),
        ).exceptionOrNull() as PackageStoreException

        assertEquals("OPERATION_IN_PROGRESS", error.code)
        assertEquals(0, store.queryCalls)
    }

    private fun manager(
        stores: List<EnvironmentPackageStore>,
        activeKind: () -> EnvironmentKind,
        queryAdmission: (
            (EnvironmentKind, () -> PackageStoreQueryResult) -> PackageStoreQueryResult
        )? = null,
    ) = PackageStoreManager(
        adapters = stores,
        activeKind = activeKind,
        operations = PackageOperationCoordinator(),
        queryAdmission = queryAdmission,
    )
}

internal open class FakeStore(
    override val kind: EnvironmentKind,
) : EnvironmentPackageStore {
    var queryCalls = 0
    var executeCalls = 0
    val cancelledOperationIds = mutableListOf<Long>()
    var capturedObserver: ((PackageOperationEvent) -> Unit)? = null

    override fun query(request: PackageStoreQuery): Result<PackageStoreQueryResult> {
        queryCalls++
        return Result.success(queryResult(kind))
    }

    override fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        executeCalls++
        capturedObserver = observer
        return successfulResult(request)
    }

    override fun cancel(operationId: Long): Boolean {
        cancelledOperationIds += operationId
        return true
    }
}

internal fun queryResult(
    kind: EnvironmentKind,
    operation: PackageOperationSnapshot? = null,
) = PackageStoreQueryResult(
    target = target(kind),
    operation = operation,
)

internal fun target(kind: EnvironmentKind) = PackageStoreTarget(
    environmentKind = kind,
    displayName = when (kind) {
        EnvironmentKind.TERMUX_NATIVE -> "Termux Native"
        EnvironmentKind.PROOT_DEBIAN -> "Debian PRoot"
        EnvironmentKind.CHROOT_UBUNTU -> "Ubuntu chroot"
    },
    availability = PackageStoreAvailability.READY,
    reason = null,
    capabilities = PackageStoreCapabilities(
        browse = true,
        arbitraryRepositoryPackages = kind == EnvironmentKind.TERMUX_NATIVE,
        remove = true,
        updateOne = true,
        updateAll = true,
        cleanCache = true,
    ),
)

internal fun successfulResult(request: PackageStoreOperation) =
    PackageOperationResult(
        operationId = request.operationId,
        environmentKind = request.expectedEnvironmentKind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = PackageOperationOutcome.SUCCEEDED,
        message = "Complete",
        entry = null,
        bytesFreed = if (request.kind == PackageOperationKind.CLEAN_CACHE) 0 else null,
        warnings = emptyList(),
        error = null,
    )
