package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.EnvironmentSelectionBusyException
import com.orailnoor.droiddesk.runtime.EnvironmentTargetChangedException

internal class PackageStoreException(
    val code: String,
    message: String,
) : IllegalStateException(message)

internal class PackageStoreManager(
    adapters: List<EnvironmentPackageStore>,
    private val activeKind: () -> EnvironmentKind,
    private val operations: PackageOperationCoordinator,
    private val operationAdmission: (
        (EnvironmentKind, () -> PackageOperationResult) -> PackageOperationResult
    )? = null,
) {
    private val adaptersByKind = adapters.associateBy(EnvironmentPackageStore::kind)

    init {
        require(adaptersByKind.size == adapters.size) {
            "Each Linux environment kind may have only one package store adapter"
        }
    }

    fun query(
        expectedKind: EnvironmentKind,
        request: PackageStoreQuery,
    ): Result<PackageStoreQueryResult> {
        val currentKind = activeKind()
        if (expectedKind != currentKind) {
            return Result.failure(
                PackageStoreException(
                    code = "TARGET_CHANGED",
                    message = "Package store target changed to ${currentKind.wireValue}",
                ),
            )
        }
        val adapter = adaptersByKind[expectedKind]
            ?: return Result.failure(
                PackageStoreException(
                    code = "UNSUPPORTED_ENVIRONMENT",
                    message = "Package store is unavailable for ${expectedKind.wireValue}",
                ),
            )
        return adapter.query(request).mapCatching { result ->
            check(result.target.environmentKind == expectedKind) {
                "Package store adapter returned the wrong environment target"
            }
            if (request is PackageStoreQuery.OperationState) {
                result.copy(
                    operation = operations.snapshot(),
                    completedResult = operations.completedResult(),
                )
            } else {
                result
            }
        }
    }

    fun execute(
        expectedKind: EnvironmentKind,
        request: PackageOperationRequest,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult = try {
        operationAdmission?.invoke(expectedKind) {
            executeForCurrentTarget(expectedKind, request, observer)
        } ?: executeForCurrentTarget(expectedKind, request, observer)
    } catch (error: EnvironmentTargetChangedException) {
        operations.reject(
            environmentKind = expectedKind,
            request = request,
            code = "TARGET_CHANGED",
            message = "Package store target changed to ${error.currentKind.wireValue}",
        )
    } catch (_: EnvironmentSelectionBusyException) {
        operations.reject(
            environmentKind = expectedKind,
            request = request,
            code = "OPERATION_IN_PROGRESS",
            message = "A Linux environment transition or package operation is already running",
        )
    }

    private fun executeForCurrentTarget(
        expectedKind: EnvironmentKind,
        request: PackageOperationRequest,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        val currentKind = activeKind()
        if (expectedKind != currentKind) {
            return operations.reject(
                environmentKind = expectedKind,
                request = request,
                code = "TARGET_CHANGED",
                message = "Package store target changed to ${currentKind.wireValue}",
            )
        }
        val adapter = adaptersByKind[expectedKind]
            ?: return operations.reject(
                environmentKind = expectedKind,
                request = request,
                code = "UNSUPPORTED_ENVIRONMENT",
                message = "Package store is unavailable for ${expectedKind.wireValue}",
            )
        return operations.execute(
            environmentKind = expectedKind,
            request = request,
            store = adapter,
            observer = observer,
        )
    }

    fun cancel(operationId: Long): Boolean = operations.cancel(operationId)

    fun currentOperation(): PackageOperationSnapshot? = operations.snapshot()

    fun acknowledgeResult(operationId: Long): Boolean =
        operations.acknowledgeResult(operationId)

    fun subscribe(
        onEvent: (PackageOperationEvent) -> Unit,
        onResult: (PackageOperationResult) -> Unit,
    ): Long = operations.subscribe(onEvent, onResult)

    fun unsubscribe(subscriberId: Long) = operations.unsubscribe(subscriberId)
}
