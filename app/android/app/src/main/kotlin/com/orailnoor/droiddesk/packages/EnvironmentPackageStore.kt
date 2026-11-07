package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind

internal interface EnvironmentPackageStore {
    val kind: EnvironmentKind

    fun query(request: PackageStoreQuery): Result<PackageStoreQueryResult>

    fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult

    fun cancel(operationId: Long): Boolean
}
