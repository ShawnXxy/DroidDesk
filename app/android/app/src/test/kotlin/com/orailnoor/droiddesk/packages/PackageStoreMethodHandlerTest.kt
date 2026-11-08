package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageStoreMethodHandlerTest {

    @Test
    fun newCallsForwardTheExplicitSelectedEnvironment() {
        val store = CapturingStore(EnvironmentKind.PROOT_DEBIAN)
        val manager = manager(store) { EnvironmentKind.PROOT_DEBIAN }
        val handler = PackageStoreMethodHandler(manager)

        handler.query(
            mapOf(
                "environmentKind" to "prootDebian",
                "queryKind" to "catalog",
                "search" to "firefox",
                "limit" to 12,
                "refreshIndex" to false,
            ),
        )

        assertEquals(
            PackageStoreQuery.Catalog("firefox", 12),
            store.lastQuery,
        )
    }

    @Test
    fun malformedQueryAndOperationValuesReturnInvalidArgument() {
        val handler = PackageStoreMethodHandler(
            manager(CapturingStore(EnvironmentKind.TERMUX_NATIVE)) {
                EnvironmentKind.TERMUX_NATIVE
            },
        )

        val queryError = assertThrows(PackageStoreException::class.java) {
            handler.query(
                mapOf(
                    "environmentKind" to "termuxNative",
                    "queryKind" to "mystery",
                ),
            )
        }
        val operationError = assertThrows(PackageStoreException::class.java) {
            handler.execute(
                mapOf(
                    "environmentKind" to "termuxNative",
                    "operationKind" to "install",
                    "entryId" to null,
                ),
                observer = {},
            )
        }

        assertEquals("INVALID_ARGUMENT", queryError.code)
        assertEquals("INVALID_ARGUMENT", operationError.code)
    }

    @Test
    fun handlerErrorsPreserveTypedCodeAndDetails() {
        val expected = PackageStoreException(
            code = "INDEX_REFRESH_FAILED",
            message = "Debian mirror unavailable",
        )
        val store = CapturingStore(EnvironmentKind.PROOT_DEBIAN).apply {
            queryResult = Result.failure(expected)
        }
        val handler = PackageStoreMethodHandler(
            manager(store) { EnvironmentKind.PROOT_DEBIAN },
        )

        val actual = assertThrows(PackageStoreException::class.java) {
            handler.query(
                mapOf(
                    "environmentKind" to "prootDebian",
                    "queryKind" to "updates",
                    "refreshIndex" to true,
                ),
            )
        }

        assertSame(expected, actual)
        assertEquals("INDEX_REFRESH_FAILED", actual.code)
        assertEquals("Debian mirror unavailable", actual.message)
    }

    @Test
    fun invalidCatalogLimitsReturnInvalidArgument() {
        val handler = PackageStoreMethodHandler(
            manager(CapturingStore(EnvironmentKind.TERMUX_NATIVE)) {
                EnvironmentKind.TERMUX_NATIVE
            },
        )

        for (limit in listOf(0, 101)) {
            val error = assertThrows(PackageStoreException::class.java) {
                handler.query(
                    mapOf(
                        "environmentKind" to "termuxNative",
                        "queryKind" to "catalog",
                        "limit" to limit,
                    ),
                )
            }

            assertEquals("INVALID_ARGUMENT", error.code)
        }
    }

    @Test
    fun floatingPointOperationIdsReturnInvalidArgument() {
        val handler = PackageStoreMethodHandler(
            manager(CapturingStore(EnvironmentKind.TERMUX_NATIVE)) {
                EnvironmentKind.TERMUX_NATIVE
            },
        )

        for (operationId in listOf(77.5, 77.5f)) {
            val cancelError = assertThrows(PackageStoreException::class.java) {
                handler.cancel(mapOf("operationId" to operationId))
            }
            val acknowledgeError = assertThrows(PackageStoreException::class.java) {
                handler.acknowledge(mapOf("operationId" to operationId))
            }

            assertEquals("INVALID_ARGUMENT", cancelError.code)
            assertEquals("INVALID_ARGUMENT", acknowledgeError.code)
        }
    }

    @Test
    fun malformedCatalogIntegerRepresentationsReturnInvalidArgument() {
        val handler = PackageStoreMethodHandler(
            manager(CapturingStore(EnvironmentKind.TERMUX_NATIVE)) {
                EnvironmentKind.TERMUX_NATIVE
            },
        )

        for (limit in listOf<Any>(60.5, 60.5f, Int.MAX_VALUE.toLong() + 1)) {
            val error = assertThrows(PackageStoreException::class.java) {
                handler.query(
                    mapOf(
                        "environmentKind" to "termuxNative",
                        "queryKind" to "catalog",
                        "limit" to limit,
                    ),
                )
            }

            assertEquals("INVALID_ARGUMENT", error.code)
        }
        handler.query(
            mapOf(
                "environmentKind" to "termuxNative",
                "queryKind" to "catalog",
                "limit" to 12L,
            ),
        )
    }

    private fun manager(
        store: EnvironmentPackageStore,
        activeKind: () -> EnvironmentKind,
    ) = PackageStoreManager(
        adapters = listOf(store),
        activeKind = activeKind,
        operations = PackageOperationCoordinator(),
    )
}

private class CapturingStore(
    override val kind: EnvironmentKind,
) : EnvironmentPackageStore {
    var lastQuery: PackageStoreQuery? = null
    var queryResult: Result<PackageStoreQueryResult> = Result.success(queryResult(kind))

    override fun query(request: PackageStoreQuery): Result<PackageStoreQueryResult> {
        lastQuery = request
        return queryResult
    }

    override fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult = successfulResult(request)

    override fun cancel(operationId: Long): Boolean = true
}
