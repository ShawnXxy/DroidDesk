package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.AndroidDirectorySizer
import com.orailnoor.droiddesk.runtime.DirectorySizer
import com.orailnoor.droiddesk.runtime.EnvironmentKind
import java.io.File

internal class TermuxNativePackageStore(
    private val port: TermuxPackagePort,
    private val history: PackageStoreHistory,
    private val directorySizer: DirectorySizer = AndroidDirectorySizer,
) : EnvironmentPackageStore {
    override val kind: EnvironmentKind = EnvironmentKind.TERMUX_NATIVE
    private val operationLock = Any()
    private var activeOperationId: Long? = null
    private var cancellationRequested = false
    private var currentPhaseCancellable = false

    override fun query(request: PackageStoreQuery): Result<PackageStoreQueryResult> =
        runCatching {
            val target = target()
            if (!port.ready) {
                return@runCatching PackageStoreQueryResult(target = target)
            }
            when (request) {
                is PackageStoreQuery.Catalog -> PackageStoreQueryResult(
                    target = target,
                    entries = catalog(request),
                )
                PackageStoreQuery.Installed -> PackageStoreQueryResult(
                    target = target,
                    entries = installedEntries(),
                )
                is PackageStoreQuery.Updates -> PackageStoreQueryResult(
                    target = target,
                    entries = updateEntries(request.refreshIndex),
                    refreshedAtEpochMs = if (request.refreshIndex) {
                        System.currentTimeMillis()
                    } else {
                        null
                    },
                )
                PackageStoreQuery.Storage -> PackageStoreQueryResult(
                    target = target,
                    storage = storage(),
                )
                PackageStoreQuery.OperationState -> PackageStoreQueryResult(target = target)
            }
        }

    override fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        if (!port.ready) {
            return rejected(
                request,
                "STORE_UNAVAILABLE",
                "Termux Native is not installed",
            )
        }
        val admitted = port.beginOperation(request.operationId) { output ->
            emit(
                request,
                observer,
                PackageOperationPhase.PREPARING,
                null,
                "Preparing package operation",
                output,
                true,
            )
        }
        if (!admitted) {
            return rejected(
                request,
                "PACKAGE_MANAGER_BUSY",
                "Another Native package manager process is running",
            )
        }
        synchronized(operationLock) {
            activeOperationId = request.operationId
            cancellationRequested = false
            currentPhaseCancellable = true
        }
        return try {
            val result = when (request.kind) {
                PackageOperationKind.INSTALL -> install(request, observer)
                PackageOperationKind.REMOVE -> remove(request, observer)
                PackageOperationKind.UPGRADE -> upgrade(request, observer)
                PackageOperationKind.UPGRADE_ALL -> upgradeAll(request, observer)
                PackageOperationKind.CLEAN_CACHE -> cleanCache(request, observer)
            }
            val shouldCancel = synchronized(operationLock) {
                currentPhaseCancellable = false
                cancellationRequested
            }
            if (shouldCancel) {
                emit(
                    request,
                    observer,
                    PackageOperationPhase.CLEANING_UP,
                    null,
                    "Repairing Native package state after cancellation",
                    null,
                    false,
                )
                if (port.repairAfterCancellation(outputObserver(
                        request,
                        observer,
                        PackageOperationPhase.CLEANING_UP,
                    ))
                ) {
                    cancelled(request)
                } else {
                    failed(
                        request,
                        "CANCEL_CLEANUP_FAILED",
                        "Native package state could not be repaired after cancellation",
                    )
                }
            } else {
                result
            }
        } catch (error: Throwable) {
            failed(
                request,
                "INTERNAL_ERROR",
                error.message ?: "Native package operation failed",
            )
        } finally {
            port.finishOperation(request.operationId)
            synchronized(operationLock) {
                if (activeOperationId == request.operationId) {
                    activeOperationId = null
                    cancellationRequested = false
                    currentPhaseCancellable = false
                }
            }
        }
    }

    override fun cancel(operationId: Long): Boolean {
        return synchronized(operationLock) {
            if (activeOperationId != operationId || !currentPhaseCancellable) {
                return@synchronized false
            }
            cancellationRequested = true
            currentPhaseCancellable = false
            port.cancelOwnedProcess(operationId)
        }
    }

    private fun catalog(request: PackageStoreQuery.Catalog): List<PackageStoreEntry> {
        val installed = installedRecords()
        if (request.search.isBlank()) {
            return RECIPES.map { recipe ->
                recipe.toEntry(installed)
            }.take(request.limit)
        }
        val query = request.search.trim().take(60)
        if (!SEARCH_TEXT.matches(query)) return emptyList()
        ensurePackageIndex()
        val output = port.searchPackages(query)
        if (output.exitCode != 0) return emptyList()
        return output.output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(" - ")
            if (separator <= 0) return@mapNotNull null
            val packageName = line.substring(0, separator).substringBefore(':').trim()
            if (!PACKAGE_NAME.matches(packageName)) return@mapNotNull null
            val description = line.substring(separator + 3).trim()
            repositoryEntry(
                packageName = packageName,
                description = description,
                record = installed[packageName],
            )
        }.distinctBy(PackageStoreEntry::entryId).take(request.limit).toList()
    }

    private fun installedEntries(): List<PackageStoreEntry> {
        val installed = installedRecords()
        val curated = RECIPES.mapNotNull { recipe ->
            recipe.takeIf { candidate ->
                candidate.packageNames.all(installed::containsKey)
            }?.toEntry(installed)
        }
        val curatedPackages = RECIPES.flatMapTo(mutableSetOf()) { it.packageNames }
        val repository = history.tracked(kind).mapNotNull { entryId ->
            val packageName = entryId.removePrefix(REPOSITORY_PREFIX)
            if (
                entryId == "$REPOSITORY_PREFIX$packageName" &&
                packageName !in curatedPackages &&
                PACKAGE_NAME.matches(packageName)
            ) {
                installed[packageName]?.let { record ->
                    repositoryEntry(packageName, record.description, record)
                }
            } else {
                null
            }
        }
        return (curated + repository).sortedBy { it.displayName.lowercase() }
    }

    private fun updateEntries(refreshIndex: Boolean): List<PackageStoreEntry> {
        val installed = installedRecords()
        val upgradable = upgradableRecords(refreshIndex)
        val curated = RECIPES.mapNotNull { recipe ->
            if (
                recipe.packageNames.any(PROTECTED_PACKAGES::contains) ||
                !recipe.packageNames.all(installed::containsKey) ||
                recipe.packageNames.none(upgradable::containsKey)
            ) {
                return@mapNotNull null
            }
            recipe.toEntry(installed, upgradable)
        }
        val curatedPackages = RECIPES.flatMapTo(mutableSetOf()) { it.packageNames }
        val repository = history.tracked(kind).mapNotNull { entryId ->
            val packageName = entryId.removePrefix(REPOSITORY_PREFIX)
            if (
                entryId != "$REPOSITORY_PREFIX$packageName" ||
                packageName in curatedPackages ||
                packageName in PROTECTED_PACKAGES ||
                !PACKAGE_NAME.matches(packageName)
            ) {
                return@mapNotNull null
            }
            val current = installed[packageName] ?: return@mapNotNull null
            val available = upgradable[packageName] ?: return@mapNotNull null
            repositoryEntry(packageName, current.description, current, available)
        }
        return (curated + repository).sortedBy { it.displayName.lowercase() }
    }

    private fun install(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        val entryId = requireNotNull(request.entryId)
        val recipe = RECIPES_BY_ID[entryId]
        val repositoryPackage = repositoryPackage(entryId)
        if (recipe == null && repositoryPackage == null) {
            return rejected(request, "INVALID_ENTRY", "Unknown Native package entry: $entryId")
        }
        if (repositoryPackage in PROTECTED_PACKAGES) {
            return rejected(
                request,
                "REMOVE_PROTECTED",
                "This package is required by DroidDesk",
            )
        }
        val specialRecipe = recipe?.takeIf { it.legacyId != null }
            ?: repositoryPackage?.let { packageName ->
                RECIPES.firstOrNull {
                    it.legacyId != null && packageName in it.packageNames
                }
            }
        val progress = progressObserver(request, observer, PackageOperationPhase.INSTALLING)
        val output = outputObserver(request, observer, PackageOperationPhase.INSTALLING)
        enterPhase(request, observer, PackageOperationPhase.INSTALLING)
        val packages = recipe?.packageNames ?: listOf(requireNotNull(repositoryPackage))
        val ok = if (specialRecipe != null) {
            port.installCurated(requireNotNull(specialRecipe.legacyId), progress, output)
        } else {
            port.installPackages(packages, progress, output)
        }
        if (!ok) return failed(request, "INSTALL_FAILED", "Could not install $entryId")
        port.refreshDesktopMenus(output)
        val installed = installedRecords()
        val entry = if (recipe != null) {
            recipe.takeIf { candidate ->
                candidate.packageNames.all(installed::containsKey)
            }?.toEntry(installed)
        } else {
            val packageName = requireNotNull(repositoryPackage)
            installed[packageName]?.let { record ->
                repositoryEntry(packageName, record.description, record)
            }
        } ?: return failed(request, "VERIFY_FAILED", "Installed package could not be verified")
        if (repositoryPackage != null) history.track(kind, entryId)
        return succeeded(request, "$entryId installed", entry = entry)
    }

    private fun remove(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        val entryId = requireNotNull(request.entryId)
        val recipe = RECIPES_BY_ID[entryId]
        val repositoryPackage = repositoryPackage(entryId)
        val packages = recipe?.packageNames ?: repositoryPackage?.let(::listOf)
            ?: return rejected(request, "INVALID_ENTRY", "Unknown Native package entry: $entryId")
        if (packages.any(PROTECTED_PACKAGES::contains)) {
            return rejected(
                request,
                "REMOVE_PROTECTED",
                "This package is required by DroidDesk",
            )
        }
        val progress = progressObserver(request, observer, PackageOperationPhase.CONFIGURING)
        val output = outputObserver(request, observer, PackageOperationPhase.CONFIGURING)
        enterPhase(request, observer, PackageOperationPhase.CONFIGURING)
        val mutation = port.removePackages(packages, progress, output)
        if (!mutation.succeeded) {
            return failed(
                request,
                if (mutation.repairFailed) "PACKAGE_REPAIR_FAILED" else "REMOVE_FAILED",
                if (mutation.repairFailed) {
                    "Native package state could not be repaired after removal"
                } else {
                    "Could not remove $entryId"
                },
            )
        }
        port.refreshDesktopMenus(output)
        if (packages.any(installedRecords()::containsKey)) {
            return failed(request, "VERIFY_FAILED", "Removed package is still installed")
        }
        if (repositoryPackage != null) history.untrack(kind, entryId)
        return succeeded(request, "$entryId removed")
    }

    private fun upgrade(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        val entryId = requireNotNull(request.entryId)
        val recipe = RECIPES_BY_ID[entryId]
        val repositoryPackage = repositoryPackage(entryId)
        val packages = recipe?.packageNames ?: repositoryPackage?.let(::listOf)
            ?: return rejected(request, "INVALID_ENTRY", "Unknown Native package entry: $entryId")
        if (packages.any(PROTECTED_PACKAGES::contains)) {
            return rejected(request, "INVALID_ENTRY", "Protected packages cannot be upgraded")
        }
        val installedBefore = installedRecords()
        val progress = progressObserver(request, observer, PackageOperationPhase.INSTALLING)
        val output = outputObserver(request, observer, PackageOperationPhase.INSTALLING)
        enterPhase(request, observer, PackageOperationPhase.INSTALLING)
        val mutation = port.upgradePackages(packages, progress, output)
        if (!mutation.succeeded) {
            return failed(
                request,
                if (mutation.repairFailed) "PACKAGE_REPAIR_FAILED" else "UPGRADE_FAILED",
                if (mutation.repairFailed) {
                    "Native package state could not be repaired after upgrade"
                } else {
                    "Could not upgrade $entryId"
                },
            )
        }
        port.refreshDesktopMenus(output)
        val installed = installedRecords()
        if (
            !packages.all(installed::containsKey) ||
            packages.none { packageName ->
                installedBefore[packageName]?.version != installed[packageName]?.version
            }
        ) {
            return failed(request, "VERIFY_FAILED", "Upgraded package could not be verified")
        }
        val entry = recipe?.let { currentRecipe ->
            currentRecipe.toEntry(installed)
        } ?: installed[repositoryPackage]?.let { record ->
            repositoryEntry(requireNotNull(repositoryPackage), record.description, record)
        } ?: return failed(request, "VERIFY_FAILED", "Upgraded package could not be verified")
        return succeeded(request, "$entryId upgraded", entry = entry)
    }

    private fun upgradeAll(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        val updates = try {
            updateEntries(refreshIndex = true)
        } catch (error: PackageStoreException) {
            return failed(request, error.code, error.message ?: "Could not check updates")
        }
        if (updates.isEmpty()) return succeeded(request, "Everything is up to date")
        val packages = updates.flatMapTo(sortedSetOf()) { entry ->
            RECIPES_BY_ID[entry.entryId]?.packageNames
                ?: listOfNotNull(repositoryPackage(entry.entryId))
        }.filterNot(PROTECTED_PACKAGES::contains)
        if (packages.isEmpty()) return succeeded(request, "Everything is up to date")
        val progress = progressObserver(request, observer, PackageOperationPhase.INSTALLING)
        val output = outputObserver(request, observer, PackageOperationPhase.INSTALLING)
        enterPhase(request, observer, PackageOperationPhase.INSTALLING)
        val mutation = port.upgradePackages(packages, progress, output)
        if (!mutation.succeeded) {
            return failed(
                request,
                if (mutation.repairFailed) "PACKAGE_REPAIR_FAILED" else "UPGRADE_FAILED",
                if (mutation.repairFailed) {
                    "Native package state could not be repaired after upgrade"
                } else {
                    "Could not upgrade Native packages"
                },
            )
        }
        port.refreshDesktopMenus(output)
        return succeeded(request, "Native packages updated")
    }

    private fun cleanCache(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        val before = cacheBytes()
        val output = outputObserver(request, observer, PackageOperationPhase.CLEANING_UP)
        enterPhase(request, observer, PackageOperationPhase.CLEANING_UP)
        if (!port.cleanCache(output)) {
            return failed(request, "INTERNAL_ERROR", "Could not clean the Native package cache")
        }
        val freed = (before - cacheBytes()).coerceAtLeast(0)
        return succeeded(request, "Native package cache cleaned", bytesFreed = freed)
    }

    private fun target(): PackageStoreTarget = if (port.ready) {
        PackageStoreTarget(
            environmentKind = kind,
            displayName = "Termux Native",
            availability = PackageStoreAvailability.READY,
            reason = null,
            capabilities = NATIVE_CAPABILITIES,
        )
    } else {
        PackageStoreTarget(
            environmentKind = kind,
            displayName = "Termux Native",
            availability = PackageStoreAvailability.ENVIRONMENT_NOT_INSTALLED,
            reason = "Termux Native is not installed",
            capabilities = NATIVE_CAPABILITIES,
        )
    }

    private fun storage(): PackageStoreStorage {
        val cache = cacheBytes()
        val system = (directorySizer.bytes(port.prefixDir) - cache).coerceAtLeast(0)
        val home = directorySizer.bytes(port.homeDir)
        return PackageStoreStorage(
            available = port.ready,
            systemBytes = system,
            homeBytes = home,
            cacheBytes = cache,
            packageCount = installedRecords().size,
            deviceFreeBytes = port.prefixDir.usableSpace.coerceAtLeast(0),
            deviceTotalBytes = port.prefixDir.totalSpace.coerceAtLeast(0),
        )
    }

    private fun cacheBytes(): Long =
        directorySizer.bytes(File(port.prefixDir, "var/cache/apt")) +
            directorySizer.bytes(File(port.prefixDir, "var/lib/apt/lists"))

    private fun installedRecords(): Map<String, InstalledRecord> {
        val status = File(port.prefixDir, "var/lib/dpkg/status")
        if (!status.isFile) return emptyMap()
        return status.readText().split(Regex("\\n\\s*\\n")).mapNotNull { paragraph ->
            val fields = mutableMapOf<String, String>()
            var currentKey: String? = null
            paragraph.lineSequence().forEach { line ->
                if (line.startsWith(" ") && currentKey != null) {
                    fields[currentKey!!] = fields[currentKey!!].orEmpty() + " " + line.trim()
                } else {
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        currentKey = line.substring(0, separator)
                        fields[currentKey!!] = line.substring(separator + 1).trim()
                    }
                }
            }
            if (fields["Status"] != "install ok installed") return@mapNotNull null
            val name = fields["Package"]?.substringBefore(':') ?: return@mapNotNull null
            name to InstalledRecord(
                version = fields["Version"].orEmpty(),
                description = fields["Description"].orEmpty().substringBefore(" . ").trim(),
                section = fields["Section"].orEmpty(),
            )
        }.toMap()
    }

    private fun ensurePackageIndex() {
        val lists = File(port.prefixDir, "var/lib/apt/lists")
        val hasIndex = lists.listFiles()?.any { entry ->
            entry.isFile && entry.name != "lock" && entry.length() > 0
        } == true
        if (!hasIndex) upgradableRecords(refreshIndex = true)
    }

    private fun upgradableRecords(refreshIndex: Boolean): Map<String, String> {
        val result = port.upgradablePackages(refreshIndex)
        if (result.exitCode != 0) {
            throw PackageStoreException(
                "INDEX_REFRESH_FAILED",
                result.output.ifBlank { "Could not refresh Native package indexes" },
            )
        }
        return result.output.lineSequence().mapNotNull { line ->
            if (!line.contains("[upgradable from:")) return@mapNotNull null
            val packageName = line.substringBefore('/').trim()
            if (!PACKAGE_NAME.matches(packageName)) return@mapNotNull null
            val availableVersion = line.substringAfter('/').trim().split(Regex("\\s+"))
                .getOrNull(1)
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            packageName to availableVersion
        }.toMap()
    }

    private fun repositoryEntry(
        packageName: String,
        description: String,
        record: InstalledRecord?,
        availableVersion: String? = null,
    ) = PackageStoreEntry(
        entryId = "$REPOSITORY_PREFIX$packageName",
        displayName = displayName(packageName),
        summary = description.ifBlank { "Linux package" },
        category = categoryFor(packageName, description),
        iconNames = desktopIconNames(packageName),
        gui = desktopIconNames(packageName).isNotEmpty(),
        supportLevel = PackageSupportLevel.REPOSITORY,
        state = when {
            record != null && availableVersion != null -> PackageEntryState.UPDATE_AVAILABLE
            record != null -> PackageEntryState.INSTALLED
            else -> PackageEntryState.AVAILABLE
        },
        availableVersion = availableVersion,
        installedVersion = record?.version,
        removable = packageName !in PROTECTED_PACKAGES,
        limitations = emptyList(),
        sourceLabel = "Termux repositories",
    )

    private fun desktopIconNames(packageName: String): List<String> {
        val info = File(port.prefixDir, "var/lib/dpkg/info")
        val listFile = listOf(
            File(info, "$packageName.list"),
            File(info, "$packageName:aarch64.list"),
        ).firstOrNull(File::isFile) ?: return emptyList()
        return listFile.readLines().filter {
            it.contains("/share/applications/") && it.endsWith(".desktop")
        }.map { File(it).nameWithoutExtension }.distinct()
    }

    private fun repositoryPackage(entryId: String): String? {
        if (!entryId.startsWith(REPOSITORY_PREFIX)) return null
        val packageName = entryId.removePrefix(REPOSITORY_PREFIX)
        return packageName.takeIf(PACKAGE_NAME::matches)
    }

    private fun progressObserver(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
        phase: PackageOperationPhase,
    ): (Double, String) -> Unit = { progress, message ->
        emit(
            request,
            observer,
            phase,
            progress.coerceIn(0.0, 1.0),
            message,
            null,
            phaseCancellable(phase),
        )
    }

    private fun outputObserver(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
        phase: PackageOperationPhase,
    ): (String) -> Unit = { output ->
        emit(
            request,
            observer,
            phase,
            null,
            "Running package operation",
            output,
            phaseCancellable(phase),
        )
    }

    private fun phaseCancellable(phase: PackageOperationPhase): Boolean =
        phase !in NON_CANCELLABLE_PHASES

    private fun enterPhase(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
        phase: PackageOperationPhase,
    ) {
        emit(
            request,
            observer,
            phase,
            progress = null,
            message = "Running package operation",
            logChunk = null,
            cancellable = phaseCancellable(phase),
        )
    }

    private fun emit(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
        phase: PackageOperationPhase,
        progress: Double?,
        message: String,
        logChunk: String?,
        cancellable: Boolean,
    ) {
        synchronized(operationLock) {
            if (activeOperationId == request.operationId) {
                currentPhaseCancellable = cancellable && !cancellationRequested
            }
        }
        observer(
            PackageOperationEvent(
                operationId = request.operationId,
                environmentKind = kind,
                phase = phase,
                progress = progress,
                message = message,
                logChunk = logChunk,
                cancellable = cancellable,
            ),
        )
    }

    private fun succeeded(
        request: PackageStoreOperation,
        message: String,
        entry: PackageStoreEntry? = null,
        bytesFreed: Long? = null,
    ) = PackageOperationResult(
        operationId = request.operationId,
        environmentKind = kind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = PackageOperationOutcome.SUCCEEDED,
        message = message,
        entry = entry,
        bytesFreed = bytesFreed,
        warnings = emptyList(),
        error = null,
    )

    private fun failed(
        request: PackageStoreOperation,
        code: String,
        message: String,
    ) = PackageOperationResult(
        operationId = request.operationId,
        environmentKind = kind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = PackageOperationOutcome.FAILED,
        message = message,
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(code, message, null),
    )

    private fun cancelled(
        request: PackageStoreOperation,
    ) = PackageOperationResult(
        operationId = request.operationId,
        environmentKind = kind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = PackageOperationOutcome.CANCELLED,
        message = "Native package operation cancelled",
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(
            code = "CANCELLED",
            message = "Native package operation cancelled",
            logTail = null,
        ),
    )

    private fun rejected(
        request: PackageStoreOperation,
        code: String,
        message: String,
    ) = PackageOperationResult(
        operationId = request.operationId,
        environmentKind = kind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = PackageOperationOutcome.REJECTED,
        message = message,
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(code, message, null),
    )

    private data class InstalledRecord(
        val version: String,
        val description: String,
        val section: String,
    )

    private data class NativeRecipe(
        val entryId: String,
        val legacyId: String?,
        val primaryPackage: String,
        val packageNames: List<String>,
        val displayName: String,
        val summary: String,
        val category: String,
        val iconNames: List<String>,
        val gui: Boolean,
    ) {
        fun toEntry(
            installed: Map<String, InstalledRecord>,
            upgradable: Map<String, String> = emptyMap(),
        ): PackageStoreEntry {
            val installedRequired = packageNames.count(installed::containsKey)
            val fullyInstalled = installedRequired == packageNames.size
            val partiallyInstalled = installedRequired in 1 until packageNames.size
            val upgradablePackages = packageNames.filter(upgradable::containsKey)
            val actionableUpdate = fullyInstalled &&
                packageNames.none(PROTECTED_PACKAGES::contains) &&
                upgradablePackages.isNotEmpty()
            val availableVersion = if (actionableUpdate) {
                upgradable[primaryPackage]
                    ?: upgradable.getValue(upgradablePackages.first())
            } else {
                null
            }
            return PackageStoreEntry(
                entryId = entryId,
                displayName = displayName,
                summary = summary,
                category = category,
                iconNames = iconNames,
                gui = gui,
                supportLevel = PackageSupportLevel.SUPPORTED,
                state = when {
                    actionableUpdate -> PackageEntryState.UPDATE_AVAILABLE
                    fullyInstalled -> PackageEntryState.INSTALLED
                    partiallyInstalled -> PackageEntryState.UNAVAILABLE
                    else -> PackageEntryState.AVAILABLE
                },
                availableVersion = availableVersion,
                installedVersion = installed[primaryPackage]?.version,
                removable = packageNames.none(PROTECTED_PACKAGES::contains),
                limitations = if (partiallyInstalled) {
                    listOf("The recipe installation is incomplete.")
                } else {
                    emptyList()
                },
                sourceLabel = "Termux repositories",
            )
        }
    }

    private companion object {
        const val REPOSITORY_PREFIX = "repo."
        val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]{0,127}")
        val SEARCH_TEXT = Regex("[A-Za-z0-9+._ -]*")
        val NON_CANCELLABLE_PHASES = setOf(
            PackageOperationPhase.CONFIGURING,
            PackageOperationPhase.VERIFYING,
            PackageOperationPhase.INTEGRATING_DESKTOP,
            PackageOperationPhase.CLEANING_UP,
        )
        val NATIVE_CAPABILITIES = PackageStoreCapabilities(
            browse = true,
            arbitraryRepositoryPackages = true,
            remove = true,
            updateOne = true,
            updateAll = true,
            cleanCache = true,
        )
        val PROTECTED_PACKAGES = setOf(
            "apt",
            "bash",
            "coreutils",
            "dpkg",
            "termux-tools",
            "termux-am",
            "glibc-repo",
            "x11-repo",
            "tur-repo",
            "pulseaudio",
            "dbus",
            "python",
            "xfce4",
            "xfce4-session",
            "xfce4-panel",
            "xfdesktop",
            "xfwm4",
            "xfconf",
            "thunar",
            "xorg-xrandr",
        )
        val RECIPES = listOf(
            NativeRecipe(
                "app.firefox",
                "firefox",
                "firefox",
                listOf("firefox"),
                "Firefox",
                "Fast, private desktop web browser.",
                "Internet",
                listOf("firefox"),
                true,
            ),
            NativeRecipe(
                "app.code-oss",
                "code_oss",
                "code-oss",
                listOf("code-oss"),
                "Code OSS",
                "Desktop code editor and IDE.",
                "Development",
                listOf("vscode", "code-oss"),
                true,
            ),
            NativeRecipe(
                "app.libreoffice",
                null,
                "libreoffice",
                listOf("libreoffice"),
                "LibreOffice",
                "Documents, spreadsheets, and presentations.",
                "Office",
                listOf("libreoffice-startcenter", "libreoffice-main"),
                true,
            ),
            NativeRecipe(
                "app.gimp",
                null,
                "gimp",
                listOf("gimp"),
                "GIMP",
                "Image editing and design tools.",
                "Graphics",
                listOf("gimp"),
                true,
            ),
            NativeRecipe(
                "app.blender",
                null,
                "blender",
                listOf("blender"),
                "Blender",
                "Open-source 3D creation suite.",
                "Graphics",
                listOf("blender"),
                true,
            ),
            NativeRecipe(
                "app.vlc",
                null,
                "vlc",
                listOf("vlc"),
                "VLC",
                "Audio and video player.",
                "Multimedia",
                listOf("vlc"),
                true,
            ),
            NativeRecipe(
                "tool.nodejs",
                "nodejs",
                "nodejs",
                listOf("nodejs", "npm"),
                "Node.js + npm",
                "JavaScript runtime and package manager.",
                "Development",
                emptyList(),
                false,
            ),
            NativeRecipe(
                "tool.python",
                null,
                "python",
                listOf("python"),
                "Python",
                "Programming language and tools.",
                "Development",
                listOf("python"),
                false,
            ),
            NativeRecipe(
                "tool.imagemagick",
                "imagemagick",
                "imagemagick",
                listOf("imagemagick"),
                "ImageMagick",
                "Image conversion and processing toolkit.",
                "Graphics",
                listOf("display-im6.q16"),
                false,
            ),
        )
        val RECIPES_BY_ID = RECIPES.associateBy(NativeRecipe::entryId)

        fun displayName(packageName: String): String =
            packageName.split('-', '_').joinToString(" ") { word ->
                word.replaceFirstChar(Char::uppercase)
            }

        fun categoryFor(name: String, description: String): String {
            val text = "$name $description".lowercase()
            return when {
                listOf("browser", "firefox", "chromium").any(text::contains) -> "Internet"
                listOf("editor", "compiler", "git", "python", "nodejs", "ide")
                    .any(text::contains) -> "Development"
                listOf("image", "photo", "graphics", "gimp", "blender")
                    .any(text::contains) -> "Graphics"
                listOf("audio", "video", "media", "music", "vlc")
                    .any(text::contains) -> "Multimedia"
                listOf("office", "document", "spreadsheet", "pdf")
                    .any(text::contains) -> "Office"
                else -> "System"
            }
        }
    }
}
