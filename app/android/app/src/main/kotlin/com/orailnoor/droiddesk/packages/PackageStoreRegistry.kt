package com.orailnoor.droiddesk.packages

import android.content.Context
import com.orailnoor.droiddesk.runtime.AndroidProotDebianHost
import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.LinuxEnvironmentManager
import com.orailnoor.droiddesk.runtime.LinuxRuntime

internal class PackageStoreRegistry {
    private val lock = Any()
    @Volatile private var manager: PackageStoreManager? = null

    fun getOrCreate(factory: () -> PackageStoreManager): PackageStoreManager {
        manager?.let { return it }
        return synchronized(lock) {
            manager ?: factory().also { manager = it }
        }
    }
}

internal object ProcessPackageStoreRegistry {
    private val registry = PackageStoreRegistry()

    fun getOrCreate(
        context: Context,
        environmentManager: LinuxEnvironmentManager,
        linuxRuntime: LinuxRuntime,
    ): PackageStoreManager = registry.getOrCreate {
        val appContext = context.applicationContext
        val prootHost = AndroidProotDebianHost(appContext, linuxRuntime)
        PackageStoreManager(
            adapters = listOf(
                TermuxNativePackageStore(
                    AndroidTermuxPackagePort(linuxRuntime),
                    AndroidPackageStoreHistory(appContext),
                ),
                ProotDebianPackageStore(AndroidProotPackagePort(prootHost)),
                UnsupportedEnvironmentPackageStore(EnvironmentKind.CHROOT_UBUNTU),
            ),
            activeKind = environmentManager::activeKind,
            operations = PackageOperationCoordinator(),
            operationAdmission = environmentManager::withActivePackageTarget,
        )
    }
}

private class UnsupportedEnvironmentPackageStore(
    override val kind: EnvironmentKind,
) : EnvironmentPackageStore {
    override fun query(request: PackageStoreQuery): Result<PackageStoreQueryResult> =
        Result.success(
            PackageStoreQueryResult(
                target = PackageStoreTarget(
                    environmentKind = kind,
                    displayName = "Ubuntu chroot",
                    availability = PackageStoreAvailability.UNSUPPORTED_ENVIRONMENT,
                    reason = "The App Store does not support Ubuntu chroot yet",
                    capabilities = PackageStoreCapabilities(
                        browse = false,
                        arbitraryRepositoryPackages = false,
                        remove = false,
                        updateOne = false,
                        updateAll = false,
                        cleanCache = false,
                    ),
                ),
            ),
        )

    override fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult = PackageOperationResult(
        operationId = request.operationId,
        environmentKind = kind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = PackageOperationOutcome.REJECTED,
        message = "The App Store does not support Ubuntu chroot yet",
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(
            code = "UNSUPPORTED_ENVIRONMENT",
            message = "The App Store does not support Ubuntu chroot yet",
            logTail = null,
        ),
    )

    override fun cancel(operationId: Long): Boolean = false
}
