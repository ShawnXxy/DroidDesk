package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.ExecutionResult
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.PackageMutationStatus
import java.io.File

internal interface TermuxPackagePort {
    val prefixDir: File
    val homeDir: File
    val ready: Boolean

    fun beginOperation(operationId: Long, onOutput: (String) -> Unit): Boolean
    fun finishOperation(operationId: Long)
    fun searchPackages(query: String): ExecutionResult
    fun upgradablePackages(refreshIndex: Boolean): ExecutionResult

    fun installCurated(
        legacyId: String,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): Boolean

    fun installPackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): Boolean

    fun removePackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): PackageMutationStatus

    fun upgradePackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): PackageMutationStatus

    fun refreshDesktopMenus(onOutput: (String) -> Unit): Boolean
    fun cleanCache(onOutput: (String) -> Unit): Boolean
    fun cancelOwnedProcess(operationId: Long): Boolean
    fun repairAfterCancellation(onOutput: (String) -> Unit): Boolean
}

internal class AndroidTermuxPackagePort(
    private val runtime: LinuxRuntime,
) : TermuxPackagePort {
    override val prefixDir: File
        get() = runtime.packageStorePrefixDir
    override val homeDir: File
        get() = runtime.packageStoreHomeDir
    override val ready: Boolean
        get() = runtime.isBootstrapped()

    override fun beginOperation(
        operationId: Long,
        onOutput: (String) -> Unit,
    ): Boolean = runtime.beginStorePackageOperation(operationId, onOutput)

    override fun finishOperation(operationId: Long) =
        runtime.finishStorePackageOperation(operationId)

    override fun searchPackages(query: String): ExecutionResult =
        runtime.searchStorePackageRecords(query)

    override fun upgradablePackages(refreshIndex: Boolean): ExecutionResult =
        runtime.upgradableStorePackageRecords(refreshIndex)

    override fun installCurated(
        legacyId: String,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): Boolean = runtime.installCuratedStorePackage(legacyId, onProgress, onOutput)

    override fun installPackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): Boolean = runtime.installStorePackages(packages, onProgress, onOutput)

    override fun removePackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): PackageMutationStatus = runtime.removeStorePackages(packages, onProgress, onOutput)

    override fun upgradePackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): PackageMutationStatus = runtime.upgradeStorePackages(packages, onProgress, onOutput)

    override fun refreshDesktopMenus(onOutput: (String) -> Unit): Boolean =
        runtime.refreshStoreDesktopMenus(onOutput)

    override fun cleanCache(onOutput: (String) -> Unit): Boolean =
        runtime.cleanStorePackageCache(onOutput)

    override fun cancelOwnedProcess(operationId: Long): Boolean =
        runtime.cancelPackageOperation(operationId)

    override fun repairAfterCancellation(onOutput: (String) -> Unit): Boolean =
        runtime.repairStorePackageStateAfterCancellation(onOutput)
}
