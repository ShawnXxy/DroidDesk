package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.ExecutionResult
import java.io.File

internal class PackageTransactionRunner(
    private val port: ProotPackagePort,
    private val sleep: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
) {
    private val stateLock = Any()
    private var activeOperationId: Long? = null
    private var currentPhaseCancellable = false
    @Volatile private var cancelRequested = false

    fun run(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        synchronized(stateLock) {
            if (activeOperationId != null) {
                return result(
                    operation,
                    PackageOperationOutcome.REJECTED,
                    "OPERATION_IN_PROGRESS",
                    "Another Debian package operation is already running",
                )
            }
            activeOperationId = operation.operationId
            currentPhaseCancellable = true
            cancelRequested = false
        }
        if (!port.beginOperation(operation.operationId)) {
            synchronized(stateLock) {
                activeOperationId = null
                currentPhaseCancellable = false
            }
            return result(
                operation,
                PackageOperationOutcome.REJECTED,
                "PACKAGE_MANAGER_BUSY",
                "Another command is using the Linux runtime",
            )
        }
        return try {
            runTransaction(operation, recipe, observer)
        } catch (error: Throwable) {
            result(
                operation,
                PackageOperationOutcome.FAILED,
                "INTERNAL_ERROR",
                error.message ?: "Debian package operation failed",
            )
        } finally {
            port.finishOperation(operation.operationId)
            synchronized(stateLock) {
                if (activeOperationId == operation.operationId) {
                    activeOperationId = null
                    currentPhaseCancellable = false
                    cancelRequested = false
                }
            }
        }
    }

    fun cancel(operationId: Long): Boolean {
        return synchronized(stateLock) {
            if (activeOperationId != operationId || !currentPhaseCancellable) return false
            cancelRequested = true
            currentPhaseCancellable = false
            port.cancelOwnedProcess(operationId, CANCEL_GRACE_MS)
        }
    }

    private fun runTransaction(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        if (foreignPackageManagerRunning(operation, observer)) {
            return result(
                operation,
                PackageOperationOutcome.REJECTED,
                "PACKAGE_MANAGER_BUSY",
                "Another Debian package manager process is running",
            )
        }

        emit(
            operation,
            observer,
            PackageOperationPhase.REPAIRING,
            0.05,
            "Repairing interrupted Debian packages",
            cancellable = true,
        )
        var configured = root(
            operation,
            observer,
            PackageOperationPhase.REPAIRING,
            "dpkg",
            listOf("--configure", "-a"),
            REPAIR_TIMEOUT_MS,
        )
        cancelled(operation, observer)?.let { return it }
        if (configured.exitCode != 0) {
            val fixed = root(
                operation,
                observer,
                PackageOperationPhase.REPAIRING,
                "apt-get",
                listOf("-f", "install", "-y"),
                MUTATION_TIMEOUT_MS,
            )
            cancelled(operation, observer)?.let { return it }
            if (fixed.exitCode != 0) {
                return result(
                    operation,
                    PackageOperationOutcome.FAILED,
                    "PACKAGE_REPAIR_FAILED",
                    fixed.output.ifBlank { "Broken Debian dependencies could not be repaired" },
                )
            }
            configured = root(
                operation,
                observer,
                PackageOperationPhase.REPAIRING,
                "dpkg",
                listOf("--configure", "-a"),
                REPAIR_TIMEOUT_MS,
            )
            cancelled(operation, observer)?.let { return it }
            if (configured.exitCode != 0) {
                return result(
                    operation,
                    PackageOperationOutcome.FAILED,
                    "PACKAGE_REPAIR_FAILED",
                    configured.output.ifBlank { "dpkg configuration failed" },
                )
            }
        }

        val audit = root(
            operation,
            observer,
            PackageOperationPhase.REPAIRING,
            "dpkg",
            listOf("--audit"),
            AUDIT_TIMEOUT_MS,
        )
        cancelled(operation, observer)?.let { return it }
        if (audit.exitCode != 0) {
            return result(
                operation,
                PackageOperationOutcome.FAILED,
                "PACKAGE_REPAIR_FAILED",
                audit.output.ifBlank { "dpkg audit failed" },
            )
        }
        if (audit.output.isNotBlank()) {
            val fixed = root(
                operation,
                observer,
                PackageOperationPhase.REPAIRING,
                "apt-get",
                listOf("-f", "install", "-y"),
                MUTATION_TIMEOUT_MS,
            )
            cancelled(operation, observer)?.let { return it }
            if (fixed.exitCode != 0) {
                return result(
                    operation,
                    PackageOperationOutcome.FAILED,
                    "PACKAGE_REPAIR_FAILED",
                    fixed.output.ifBlank { "Broken Debian dependencies could not be repaired" },
                )
            }
        }

        if (operation.kind in INDEXED_OPERATIONS) {
            emit(
                operation,
                observer,
                PackageOperationPhase.REFRESHING_INDEX,
                0.18,
                "Refreshing Debian package indexes",
                cancellable = true,
            )
            val refreshed = root(
                operation,
                observer,
                PackageOperationPhase.REFRESHING_INDEX,
                "apt-get",
                listOf("update"),
                INDEX_TIMEOUT_MS,
            )
            cancelled(operation, observer)?.let { return it }
            if (refreshed.exitCode != 0) {
                return result(
                    operation,
                    PackageOperationOutcome.FAILED,
                    "INDEX_REFRESH_FAILED",
                    refreshed.output.ifBlank { "Debian package indexes could not be refreshed" },
                )
            }
        }

        val affectedRecipes = affectedRecipes(operation, recipe)
        val versionsBeforeMutation = port.installedPackages()
        val cacheBytesBefore = if (operation.kind == PackageOperationKind.CLEAN_CACHE) {
            cacheBytes()
        } else {
            null
        }
        val mutation = mutate(operation, recipe, affectedRecipes, observer)
        cancelled(operation, observer)?.let { return it }
        if (mutation.exitCode != 0) {
            return result(
                operation,
                PackageOperationOutcome.FAILED,
                mutationErrorCode(operation.kind),
                mutation.output.ifBlank { "Debian package mutation failed" },
            )
        }

        if (operation.kind != PackageOperationKind.CLEAN_CACHE) {
            emit(
                operation,
                observer,
                PackageOperationPhase.CONFIGURING,
                0.78,
                "Configuring Debian packages",
                cancellable = false,
            )
            var finalized = root(
                operation,
                observer,
                PackageOperationPhase.CONFIGURING,
                "dpkg",
                listOf("--configure", "-a"),
                REPAIR_TIMEOUT_MS,
            )
            cancelled(operation, observer)?.let { return it }
            if (finalized.exitCode != 0) {
                val fixed = root(
                    operation,
                    observer,
                    PackageOperationPhase.CONFIGURING,
                    "apt-get",
                    listOf("-f", "install", "-y"),
                    MUTATION_TIMEOUT_MS,
                )
                if (fixed.exitCode != 0) {
                    return result(
                        operation,
                        PackageOperationOutcome.FAILED,
                        "PACKAGE_REPAIR_FAILED",
                        fixed.output.ifBlank {
                            "Broken Debian dependencies could not be repaired"
                        },
                    )
                }
                finalized = root(
                    operation,
                    observer,
                    PackageOperationPhase.CONFIGURING,
                    "dpkg",
                    listOf("--configure", "-a"),
                    REPAIR_TIMEOUT_MS,
                )
                if (finalized.exitCode != 0) {
                    return result(
                        operation,
                        PackageOperationOutcome.FAILED,
                        "PACKAGE_REPAIR_FAILED",
                        finalized.output.ifBlank { "Debian package configuration failed" },
                    )
                }
            }
        }

        emit(
            operation,
            observer,
            PackageOperationPhase.VERIFYING,
            0.9,
            "Verifying Debian package state",
            cancellable = false,
        )
        if (!verify(operation, recipe, affectedRecipes, versionsBeforeMutation)) {
            return result(
                operation,
                PackageOperationOutcome.FAILED,
                "VERIFY_FAILED",
                "The final Debian package state did not match the requested operation",
            )
        }

        if (
            operation.kind != PackageOperationKind.REMOVE &&
            affectedRecipes.filter(PackageRecipe::gui).any { !hasDesktopEntry(it) }
        ) {
            return result(
                operation,
                PackageOperationOutcome.FAILED,
                "VERIFY_FAILED",
                "Installed GUI package did not provide its expected desktop entry",
            )
        }
        val warnings = integrateDesktop(operation, affectedRecipes, observer)
        return PackageOperationResult(
            operationId = operation.operationId,
            environmentKind = EnvironmentKind.PROOT_DEBIAN,
            kind = operation.kind,
            entryId = operation.entryId,
            outcome = PackageOperationOutcome.SUCCEEDED,
            message = successMessage(operation.kind),
            entry = null,
            bytesFreed = cacheBytesBefore?.let { before ->
                (before - cacheBytes()).coerceAtLeast(0)
            },
            warnings = warnings,
            error = null,
        )
    }

    private fun foreignPackageManagerRunning(
        operation: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): Boolean = PACKAGE_MANAGER_PROCESSES.any { processName ->
        val result = root(
            operation,
            observer,
            PackageOperationPhase.PREPARING,
            "pgrep",
            listOf("-x", processName),
            BUSY_CHECK_TIMEOUT_MS,
        )
        result.exitCode == 0 && result.output.isNotBlank()
    }

    private fun affectedRecipes(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
    ): List<PackageRecipe> = when (operation.kind) {
        PackageOperationKind.INSTALL,
        PackageOperationKind.REMOVE,
        PackageOperationKind.UPGRADE,
        -> listOf(requireNotNull(recipe))
        PackageOperationKind.UPGRADE_ALL -> {
            val installed = port.installedPackages()
            ProotPackageRecipes.all.filter { candidate ->
                candidate.supportLevel != PackageSupportLevel.EXPERIMENTAL &&
                    candidate.packageNames.all(installed::containsKey)
            }
        }
        PackageOperationKind.CLEAN_CACHE -> emptyList()
    }

    private fun mutate(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
        affectedRecipes: List<PackageRecipe>,
        observer: (PackageOperationEvent) -> Unit,
    ): ExecutionResult = when (operation.kind) {
        PackageOperationKind.INSTALL,
        PackageOperationKind.UPGRADE,
        -> root(
            operation,
            observer,
            PackageOperationPhase.INSTALLING,
            "apt-get",
            listOf("install", "-y", "--no-install-recommends") +
                requireNotNull(recipe).packageNames,
            MUTATION_TIMEOUT_MS,
        )
        PackageOperationKind.REMOVE -> root(
            operation,
            observer,
            PackageOperationPhase.INSTALLING,
            "apt-get",
            listOf("remove", "-y") + requireNotNull(recipe).packageNames,
            MUTATION_TIMEOUT_MS,
        )
        PackageOperationKind.UPGRADE_ALL -> {
            val packages = affectedRecipes.flatMapTo(sortedSetOf()) { it.packageNames }
            if (packages.isEmpty()) {
                ExecutionResult(0, "")
            } else {
                root(
                    operation,
                    observer,
                    PackageOperationPhase.INSTALLING,
                    "apt-get",
                    listOf("install", "--only-upgrade", "-y") + packages,
                    MUTATION_TIMEOUT_MS,
                )
            }
        }
        PackageOperationKind.CLEAN_CACHE -> {
            val archives = root(
                operation,
                observer,
                PackageOperationPhase.CLEANING_UP,
                "apt-get",
                listOf("clean"),
                REPAIR_TIMEOUT_MS,
            )
            if (archives.exitCode != 0) {
                archives
            } else {
                root(
                    operation,
                    observer,
                    PackageOperationPhase.CLEANING_UP,
                    "find",
                    listOf(
                        "/var/lib/apt/lists",
                        "-mindepth",
                        "1",
                        "-maxdepth",
                        "1",
                        "-exec",
                        "rm",
                        "-rf",
                        "--",
                        "{}",
                        "+",
                    ),
                    REPAIR_TIMEOUT_MS,
                )
            }
        }
    }

    private fun verify(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
        affectedRecipes: List<PackageRecipe>,
        versionsBeforeMutation: Map<String, String>,
    ): Boolean {
        val installed = awaitRequestedPackageState(
            operation,
            recipe,
            affectedRecipes,
            versionsBeforeMutation,
        ) ?: return false
        if (
            operation.kind == PackageOperationKind.REMOVE ||
            operation.kind == PackageOperationKind.CLEAN_CACHE
        ) {
            return true
        }
        if (port.architecture() != "arm64") return false
        val recipesToVerify = when (operation.kind) {
            PackageOperationKind.INSTALL,
            PackageOperationKind.UPGRADE,
            -> listOf(requireNotNull(recipe))
            PackageOperationKind.UPGRADE_ALL -> affectedRecipes
            PackageOperationKind.REMOVE,
            PackageOperationKind.CLEAN_CACHE,
            -> emptyList()
        }
        return verifyRecipesAfterSettling(
            operation,
            recipe,
            affectedRecipes,
            versionsBeforeMutation,
            recipesToVerify,
            installed,
        )
    }

    private fun verifyRecipesAfterSettling(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
        affectedRecipes: List<PackageRecipe>,
        versionsBeforeMutation: Map<String, String>,
        recipesToVerify: List<PackageRecipe>,
        initialInstalled: Map<String, String>,
    ): Boolean {
        var installed = initialInstalled
        repeat(PACKAGE_RECIPE_SETTLE_ATTEMPTS) { attempt ->
            if (
                requestedStateMatches(
                    operation,
                    recipe,
                    affectedRecipes,
                    versionsBeforeMutation,
                    installed,
                ) &&
                recipesToVerify.all { verifyRecipe(it, installed) }
            ) {
                return true
            }
            if (attempt < PACKAGE_RECIPE_SETTLE_ATTEMPTS - 1) {
                sleep(PACKAGE_RECIPE_SETTLE_DELAY_MS)
                installed = port.installedPackages()
            }
        }
        return false
    }

    private fun awaitRequestedPackageState(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
        affectedRecipes: List<PackageRecipe>,
        versionsBeforeMutation: Map<String, String>,
    ): Map<String, String>? {
        repeat(PACKAGE_STATE_SETTLE_ATTEMPTS) { attempt ->
            val installed = port.installedPackages()
            val requestedStateMatches = requestedStateMatches(
                operation,
                recipe,
                affectedRecipes,
                versionsBeforeMutation,
                installed,
            )
            val upgradeAllVersionChanged =
                operation.kind != PackageOperationKind.UPGRADE_ALL ||
                    affectedRecipes.isEmpty() ||
                    affectedRecipes.any { candidate ->
                        candidate.packageNames.any { packageName ->
                            versionsBeforeMutation[packageName] != installed[packageName]
                        }
                    }
            if (
                requestedStateMatches &&
                (
                    upgradeAllVersionChanged ||
                        attempt == PACKAGE_STATE_SETTLE_ATTEMPTS - 1
                    )
            ) {
                return installed
            }
            if (attempt < PACKAGE_STATE_SETTLE_ATTEMPTS - 1) {
                sleep(PACKAGE_STATE_SETTLE_DELAY_MS)
            }
        }
        return null
    }

    private fun requestedStateMatches(
        operation: PackageStoreOperation,
        recipe: PackageRecipe?,
        affectedRecipes: List<PackageRecipe>,
        versionsBeforeMutation: Map<String, String>,
        installed: Map<String, String>,
    ): Boolean =
        when (operation.kind) {
            PackageOperationKind.INSTALL,
            -> requireNotNull(recipe).packageNames.all(installed::containsKey)
            PackageOperationKind.REMOVE ->
                requireNotNull(recipe).packageNames.none(installed::containsKey)
            PackageOperationKind.UPGRADE -> {
                val upgradedRecipe = requireNotNull(recipe)
                upgradedRecipe.packageNames.all(installed::containsKey) &&
                    upgradedRecipe.packageNames.any { packageName ->
                        versionsBeforeMutation[packageName] != installed[packageName]
                    }
            }
            PackageOperationKind.UPGRADE_ALL ->
                affectedRecipes.all { candidate ->
                    candidate.packageNames.all(installed::containsKey)
                }
            PackageOperationKind.CLEAN_CACHE -> true
        }

    private fun verifyRecipe(
        recipe: PackageRecipe,
        installed: Map<String, String>,
    ): Boolean {
        val packagesVerified = recipe.packageNames.all { packageName ->
            val result = port.runRoot(
                executable = "dpkg-query",
                arguments = listOf("-W", "-f=\${Architecture}\\t\${Version}", packageName),
                environment = PACKAGE_ENVIRONMENT,
                timeoutMs = VERIFY_TIMEOUT_MS,
                onOutput = {},
            )
            if (result.exitCode != 0) return@all false
            val fields = result.output.trim().split('\t', limit = 2)
            fields.size == 2 &&
                fields[0] in SUPPORTED_PACKAGE_ARCHITECTURES &&
                fields[1].isNotBlank() &&
                fields[1] == installed[packageName]
        }
        if (!packagesVerified) return false
        return recipe.verificationExecutables.all { executable ->
            val result = port.runRoot(
                executable = "which",
                arguments = listOf(executable),
                environment = PACKAGE_ENVIRONMENT,
                timeoutMs = VERIFY_TIMEOUT_MS,
                onOutput = {},
            )
            result.exitCode == 0 && result.output.isNotBlank()
        }
    }

    private fun integrateDesktop(
        operation: PackageStoreOperation,
        recipes: List<PackageRecipe>,
        observer: (PackageOperationEvent) -> Unit,
    ): List<PackageStoreWarning> {
        val guiRecipes = recipes.filter(PackageRecipe::gui)
        if (guiRecipes.isEmpty()) return emptyList()
        emit(
            operation,
            observer,
            PackageOperationPhase.INTEGRATING_DESKTOP,
            0.96,
            "Refreshing the XFCE Applications menu",
            cancellable = false,
        )
        val database = root(
            operation,
            observer,
            PackageOperationPhase.INTEGRATING_DESKTOP,
            "update-desktop-database",
            listOf("/usr/share/applications"),
            DESKTOP_TIMEOUT_MS,
        )
        val desktop = port.runDesktop(
            executable = "xfdesktop",
            arguments = listOf("--reload"),
            environment = DESKTOP_ENVIRONMENT,
            timeoutMs = DESKTOP_TIMEOUT_MS,
            onOutput = outputObserver(
                operation,
                observer,
                PackageOperationPhase.INTEGRATING_DESKTOP,
            ),
        )
        val panel = port.runDesktop(
            executable = "xfce4-panel",
            arguments = listOf("-r"),
            environment = DESKTOP_ENVIRONMENT,
            timeoutMs = DESKTOP_TIMEOUT_MS,
            onOutput = outputObserver(
                operation,
                observer,
                PackageOperationPhase.INTEGRATING_DESKTOP,
            ),
        )
        if (
            database.exitCode == 0 &&
            desktop.exitCode == 0 &&
            panel.exitCode == 0
        ) {
            return emptyList()
        }
        return listOf(
            PackageStoreWarning(
                code = "DESKTOP_INTEGRATION_LIMITED",
                message = "Open the XFCE Applications menu to refresh installed applications",
            ),
        )
    }

    private fun hasDesktopEntry(recipe: PackageRecipe): Boolean {
        val directories = listOf(
            File(port.rootfsDir, "usr/share/applications"),
            File(port.rootfsDir, "home/droiddesk/.local/share/applications"),
        )
        return directories.any { directory ->
            directory.listFiles()?.any { file ->
                file.extension == "desktop" &&
                    recipe.iconNames.any { iconName -> file.nameWithoutExtension == iconName }
            } == true
        }
    }

    private fun cacheBytes(): Long =
        directoryBytes(File(port.rootfsDir, "var/cache/apt")) +
            directoryBytes(File(port.rootfsDir, "var/lib/apt/lists"))

    private fun cancelled(
        operation: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult? {
        if (!cancelRequested) return null
        emit(
            operation,
            observer,
            PackageOperationPhase.CLEANING_UP,
            null,
            "Repairing Debian package state after cancellation",
            cancellable = false,
        )
        var cleanup = root(
            operation,
            observer,
            PackageOperationPhase.CLEANING_UP,
            "dpkg",
            listOf("--configure", "-a"),
            REPAIR_TIMEOUT_MS,
            allowAfterCancellation = true,
        )
        if (cleanup.exitCode != 0) {
            val fixed = root(
                operation,
                observer,
                PackageOperationPhase.CLEANING_UP,
                "apt-get",
                listOf("-f", "install", "-y"),
                MUTATION_TIMEOUT_MS,
                allowAfterCancellation = true,
            )
            if (fixed.exitCode == 0) {
                cleanup = root(
                    operation,
                    observer,
                    PackageOperationPhase.CLEANING_UP,
                    "dpkg",
                    listOf("--configure", "-a"),
                    REPAIR_TIMEOUT_MS,
                    allowAfterCancellation = true,
                )
            }
        }
        return if (cleanup.exitCode == 0) {
            result(
                operation,
                PackageOperationOutcome.CANCELLED,
                "CANCELLED",
                "Debian package operation cancelled",
            )
        } else {
            result(
                operation,
                PackageOperationOutcome.FAILED,
                "CANCEL_CLEANUP_FAILED",
                cleanup.output.ifBlank {
                    "Debian package state could not be repaired after cancellation"
                },
            )
        }
    }

    private fun root(
        operation: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
        phase: PackageOperationPhase,
        executable: String,
        arguments: List<String>,
        timeoutMs: Long,
        allowAfterCancellation: Boolean = false,
    ): ExecutionResult {
        if (
            !allowAfterCancellation &&
            synchronized(stateLock) { cancelRequested }
        ) {
            return ExecutionResult(CANCELLED_EXIT_CODE, "Package operation cancelled")
        }
        val cancellable = phaseCancellable(operation, phase)
        emit(
            operation,
            observer,
            phase,
            progress = null,
            message = "Running Debian package operation",
            cancellable = cancellable,
        )
        return port.runRoot(
            executable = executable,
            arguments = arguments,
            environment = PACKAGE_ENVIRONMENT,
            timeoutMs = timeoutMs,
            onOutput = outputObserver(
                operation,
                observer,
                phase,
                cancellable,
            ),
        )
    }

    private fun outputObserver(
        operation: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
        phase: PackageOperationPhase,
        cancellable: Boolean = phaseCancellable(operation, phase),
    ): (String) -> Unit = { output ->
        emit(
            operation,
            observer,
            phase,
            null,
            "Running Debian package operation",
            output,
            cancellable = cancellable && !cancelRequested,
        )
    }

    private fun phaseCancellable(
        operation: PackageStoreOperation,
        phase: PackageOperationPhase,
    ): Boolean =
        phase !in NON_CANCELLABLE_PHASES &&
            !(operation.kind == PackageOperationKind.REMOVE &&
                phase == PackageOperationPhase.INSTALLING)

    private fun emit(
        operation: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
        phase: PackageOperationPhase,
        progress: Double?,
        message: String,
        logChunk: String? = null,
        cancellable: Boolean,
    ) {
        synchronized(stateLock) {
            if (activeOperationId == operation.operationId) {
                currentPhaseCancellable = cancellable && !cancelRequested
            }
        }
        observer(
            PackageOperationEvent(
                operationId = operation.operationId,
                environmentKind = EnvironmentKind.PROOT_DEBIAN,
                phase = phase,
                progress = progress,
                message = message,
                logChunk = logChunk,
                cancellable = cancellable,
            ),
        )
    }

    private fun result(
        operation: PackageStoreOperation,
        outcome: PackageOperationOutcome,
        code: String,
        message: String,
    ) = PackageOperationResult(
        operationId = operation.operationId,
        environmentKind = EnvironmentKind.PROOT_DEBIAN,
        kind = operation.kind,
        entryId = operation.entryId,
        outcome = outcome,
        message = message,
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(code, message, null),
    )

    private fun mutationErrorCode(kind: PackageOperationKind): String = when (kind) {
        PackageOperationKind.INSTALL -> "INSTALL_FAILED"
        PackageOperationKind.REMOVE -> "REMOVE_FAILED"
        PackageOperationKind.UPGRADE,
        PackageOperationKind.UPGRADE_ALL,
        -> "UPGRADE_FAILED"
        PackageOperationKind.CLEAN_CACHE -> "INTERNAL_ERROR"
    }

    private fun successMessage(kind: PackageOperationKind): String = when (kind) {
        PackageOperationKind.INSTALL -> "Debian application installed"
        PackageOperationKind.REMOVE -> "Debian application removed"
        PackageOperationKind.UPGRADE -> "Debian application updated"
        PackageOperationKind.UPGRADE_ALL -> "Debian applications updated"
        PackageOperationKind.CLEAN_CACHE -> "Debian package cache cleaned"
    }

    private companion object {
        const val CANCEL_GRACE_MS = 5_000L
        const val CANCELLED_EXIT_CODE = 130
        const val PACKAGE_STATE_SETTLE_ATTEMPTS = 31
        const val PACKAGE_STATE_SETTLE_DELAY_MS = 100L
        const val PACKAGE_RECIPE_SETTLE_ATTEMPTS = 6
        const val PACKAGE_RECIPE_SETTLE_DELAY_MS = 200L
        const val BUSY_CHECK_TIMEOUT_MS = 5_000L
        const val AUDIT_TIMEOUT_MS = 30_000L
        const val VERIFY_TIMEOUT_MS = 30_000L
        const val DESKTOP_TIMEOUT_MS = 30_000L
        const val REPAIR_TIMEOUT_MS = 90_000L
        const val INDEX_TIMEOUT_MS = 120_000L
        const val MUTATION_TIMEOUT_MS = 30 * 60 * 1000L
        val PACKAGE_MANAGER_PROCESSES = listOf("apt", "apt-get", "dpkg")
        val SUPPORTED_PACKAGE_ARCHITECTURES = setOf("arm64", "all")
        val NON_CANCELLABLE_PHASES = setOf(
            PackageOperationPhase.CONFIGURING,
            PackageOperationPhase.VERIFYING,
            PackageOperationPhase.INTEGRATING_DESKTOP,
            PackageOperationPhase.CLEANING_UP,
        )
        val INDEXED_OPERATIONS = setOf(
            PackageOperationKind.INSTALL,
            PackageOperationKind.UPGRADE,
            PackageOperationKind.UPGRADE_ALL,
        )
        val PACKAGE_ENVIRONMENT = mapOf(
            "DEBIAN_FRONTEND" to "noninteractive",
            "TZ" to "Etc/UTC",
        )
        val DESKTOP_ENVIRONMENT = mapOf(
            "DISPLAY" to ":0",
            "XDG_RUNTIME_DIR" to "/tmp/runtime-droiddesk",
        )

        fun directoryBytes(directory: File): Long =
            directory.takeIf(File::isDirectory)
                ?.walkTopDown()
                ?.filter(File::isFile)
                ?.sumOf(File::length)
                ?: 0L
    }
}
