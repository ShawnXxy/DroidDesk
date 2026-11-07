package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind

internal class PackageOperationCoordinator(
    firstOperationId: Long = 1,
) {
    private val lock = Any()
    private var nextOperationId = firstOperationId
    private var nextSubscriberId = 1L
    private var active: ActiveOperation? = null
    private var completedResult: PackageOperationResult? = null
    private val eventSubscribers = mutableMapOf<Long, (PackageOperationEvent) -> Unit>()
    private val resultSubscribers = mutableMapOf<Long, (PackageOperationResult) -> Unit>()

    init {
        require(firstOperationId > 0) { "First operation ID must be positive" }
    }

    fun execute(
        environmentKind: EnvironmentKind,
        request: PackageOperationRequest,
        store: EnvironmentPackageStore,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        var operation: PackageStoreOperation? = null
        var rejectionDelivery:
            Pair<PackageOperationResult, List<(PackageOperationResult) -> Unit>>? = null
        synchronized(lock) {
            val operationId = allocateOperationId()
            if (active != null) {
                val result = rejectedResult(
                    operationId = operationId,
                    environmentKind = environmentKind,
                    request = request,
                    code = "OPERATION_IN_PROGRESS",
                    message = "Another package operation is already running",
                )
                rejectionDelivery = result to resultSubscribers.values.toList()
            } else {
                operation = PackageStoreOperation(
                    operationId = operationId,
                    expectedEnvironmentKind = environmentKind,
                    kind = request.kind,
                    entryId = request.entryId,
                    acceptExperimentalRisk = request.acceptExperimentalRisk,
                )
                active = ActiveOperation(
                    operation = operation,
                    store = store,
                    snapshot = PackageOperationSnapshot(
                        operationId = operationId,
                        environmentKind = environmentKind,
                        kind = request.kind,
                        entryId = request.entryId,
                        phase = PackageOperationPhase.PREPARING,
                        progress = null,
                        message = "Preparing package operation",
                        cancellable = true,
                        logTail = "",
                    ),
                )
            }
        }
        rejectionDelivery?.let { (result, subscribers) ->
            subscribers.forEach { subscriber ->
                runCatching { subscriber(result) }
            }
            return result
        }
        val admittedOperation = checkNotNull(operation)

        val guardedObserver: (PackageOperationEvent) -> Unit = { event ->
            val delivery = synchronized(lock) {
                val current = active
                if (
                    current == null ||
                    current.operation.operationId != admittedOperation.operationId ||
                    event.operationId != admittedOperation.operationId ||
                    event.environmentKind != environmentKind
                ) {
                    false to emptyList()
                } else {
                    current.snapshot = PackageOperationSnapshot(
                        operationId = admittedOperation.operationId,
                        environmentKind = environmentKind,
                        kind = admittedOperation.kind,
                        entryId = admittedOperation.entryId,
                        phase = event.phase,
                        progress = event.progress,
                        message = event.message,
                        cancellable = event.cancellable,
                        logTail = appendLog(current.snapshot.logTail, event.logChunk),
                    )
                    true to eventSubscribers.values.toList()
                }
            }
            if (delivery.first) {
                runCatching { observer(event) }
                delivery.second.forEach { subscriber ->
                    runCatching { subscriber(event) }
                }
            }
        }

        val result = try {
            store.execute(admittedOperation, guardedObserver).validatedFor(admittedOperation)
        } catch (error: Throwable) {
            failedResult(
                operation = admittedOperation,
                code = "INTERNAL_ERROR",
                message = error.message ?: "Package operation failed",
            )
        }
        val subscribers = synchronized(lock) {
            if (active?.operation?.operationId == admittedOperation.operationId) {
                active = null
            }
            completedResult = result
            resultSubscribers.values.toList()
        }
        subscribers.forEach { subscriber ->
            runCatching { subscriber(result) }
        }
        return result
    }

    fun reject(
        environmentKind: EnvironmentKind,
        request: PackageOperationRequest,
        code: String,
        message: String,
    ): PackageOperationResult {
        val delivery = synchronized(lock) {
            val result = rejectedResult(
                operationId = allocateOperationId(),
                environmentKind = environmentKind,
                request = request,
                code = code,
                message = message,
            )
            if (active == null) completedResult = result
            result to resultSubscribers.values.toList()
        }
        delivery.second.forEach { subscriber ->
            runCatching { subscriber(delivery.first) }
        }
        return delivery.first
    }

    fun cancel(operationId: Long): Boolean {
        return synchronized(lock) {
            val current = active?.takeIf {
                it.operation.operationId == operationId && it.snapshot.cancellable
            } ?: return@synchronized false
            current.store.cancel(operationId)
        }
    }

    fun snapshot(): PackageOperationSnapshot? = synchronized(lock) {
        active?.snapshot
    }

    fun completedResult(): PackageOperationResult? = synchronized(lock) {
        completedResult
    }

    fun acknowledgeResult(operationId: Long): Boolean = synchronized(lock) {
        if (completedResult?.operationId != operationId) return@synchronized false
        completedResult = null
        true
    }

    fun subscribe(
        onEvent: (PackageOperationEvent) -> Unit,
        onResult: (PackageOperationResult) -> Unit,
    ): Long = synchronized(lock) {
        val subscriberId = nextSubscriberId++
        eventSubscribers[subscriberId] = onEvent
        resultSubscribers[subscriberId] = onResult
        subscriberId
    }

    fun unsubscribe(subscriberId: Long) {
        synchronized(lock) {
            eventSubscribers.remove(subscriberId)
            resultSubscribers.remove(subscriberId)
        }
    }

    private fun allocateOperationId(): Long {
        val operationId = nextOperationId
        check(operationId > 0) { "Package operation ID overflow" }
        nextOperationId++
        return operationId
    }

    private fun PackageOperationResult.validatedFor(
        operation: PackageStoreOperation,
    ): PackageOperationResult {
        if (
            operationId == operation.operationId &&
            environmentKind == operation.expectedEnvironmentKind &&
            kind == operation.kind &&
            entryId == operation.entryId
        ) {
            return this
        }
        return failedResult(
            operation = operation,
            code = "INTERNAL_ERROR",
            message = "Package store adapter returned a mismatched operation result",
        )
    }

    private fun failedResult(
        operation: PackageStoreOperation,
        code: String,
        message: String,
    ) = PackageOperationResult(
        operationId = operation.operationId,
        environmentKind = operation.expectedEnvironmentKind,
        kind = operation.kind,
        entryId = operation.entryId,
        outcome = PackageOperationOutcome.FAILED,
        message = message,
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(
            code = code,
            message = message,
            logTail = snapshot()?.logTail,
        ),
    )

    private fun rejectedResult(
        operationId: Long,
        environmentKind: EnvironmentKind,
        request: PackageOperationRequest,
        code: String,
        message: String,
    ) = PackageOperationResult(
        operationId = operationId,
        environmentKind = environmentKind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = PackageOperationOutcome.REJECTED,
        message = message,
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(
            code = code,
            message = message,
            logTail = null,
        ),
    )

    private fun appendLog(current: String, chunk: String?): String {
        if (chunk.isNullOrEmpty()) return current
        return (current + chunk).takeLast(MAX_LOG_TAIL)
    }

    private data class ActiveOperation(
        val operation: PackageStoreOperation,
        val store: EnvironmentPackageStore,
        var snapshot: PackageOperationSnapshot,
    )

    private companion object {
        const val MAX_LOG_TAIL = 12_000
    }
}
