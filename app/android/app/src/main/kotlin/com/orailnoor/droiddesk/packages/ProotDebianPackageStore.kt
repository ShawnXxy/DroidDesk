package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import java.io.File

internal class ProotDebianPackageStore(
    private val port: ProotPackagePort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transactions: PackageTransactionRunner = PackageTransactionRunner(port),
    private val sandboxProbe: SandboxCapabilityProbe = UnavailableSandboxCapabilityProbe,
) : EnvironmentPackageStore {
    override val kind: EnvironmentKind = EnvironmentKind.PROOT_DEBIAN
    private var lastIndexRefreshEpochMs: Long? = null
    private val sandboxResults = mutableMapOf<String, SandboxProbeResult>()

    override fun query(request: PackageStoreQuery): Result<PackageStoreQueryResult> =
        runCatching {
            val target = target()
            if (target.availability != PackageStoreAvailability.READY) {
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
                is PackageStoreQuery.Updates -> {
                    if (request.refreshIndex && indexRefreshRequired()) {
                        refreshIndex()
                    }
                    PackageStoreQueryResult(
                        target = target,
                        entries = updateEntries(),
                        refreshedAtEpochMs = lastIndexRefreshEpochMs,
                    )
                }
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
        val recipe = request.entryId?.let(ProotPackageRecipes::find)
        if (
            request.kind in ENTRY_OPERATIONS &&
            recipe == null
        ) {
            return rejected(
                request,
                "INVALID_ENTRY",
                "Unknown Debian PRoot package entry: ${request.entryId}",
            )
        }
        val target = target()
        if (target.availability != PackageStoreAvailability.READY) {
            return rejected(
                request,
                "STORE_UNAVAILABLE",
                target.reason ?: "Debian PRoot package store is unavailable",
            )
        }
        if (recipe?.supportLevel == PackageSupportLevel.EXPERIMENTAL) {
            val probe = sandboxResult(recipe)
            val error = ExperimentalRecipeGate.validateInstall(
                recipe,
                probe,
                request.acceptExperimentalRisk,
            )
            if (error != null) {
                return rejected(request, error.code, error.message)
            }
        }
        if (request.kind == PackageOperationKind.REMOVE && recipe?.removable == false) {
            return rejected(
                request,
                "REMOVE_PROTECTED",
                "${recipe.displayName} is required by the Debian PRoot baseline",
            )
        }
        val result = transactions.run(request, recipe, observer)
        if (
            request.kind == PackageOperationKind.CLEAN_CACHE &&
            result.outcome == PackageOperationOutcome.SUCCEEDED
        ) {
            lastIndexRefreshEpochMs = null
        }
        return result
    }

    override fun cancel(operationId: Long): Boolean = transactions.cancel(operationId)

    private fun catalog(request: PackageStoreQuery.Catalog): List<PackageStoreEntry> {
        val installed = port.installedPackages()
        return ProotPackageRecipes.search(request.search)
            .map { it.toEntry(installed) }
            .take(request.limit)
    }

    private fun installedEntries(): List<PackageStoreEntry> {
        val installed = port.installedPackages()
        return ProotPackageRecipes.all.mapNotNull { recipe ->
            recipe.takeIf { current ->
                current.packageNames.all(installed::containsKey)
            }?.toEntry(installed)
        }
    }

    private fun updateEntries(): List<PackageStoreEntry> {
        val installed = port.installedPackages()
        val result = port.runRoot(
            executable = "apt",
            arguments = listOf("list", "--upgradable"),
            environment = PACKAGE_ENVIRONMENT,
            timeoutMs = READ_TIMEOUT_MS,
            onOutput = {},
        )
        if (result.exitCode != 0) {
            throw PackageStoreException(
                code = "INDEX_REFRESH_FAILED",
                message = result.output.ifBlank { "Could not read Debian package updates" },
            )
        }
        val available = parseUpgradable(result.output)
        return ProotPackageRecipes.all.mapNotNull { recipe ->
            if (!recipe.packageNames.all(installed::containsKey)) return@mapNotNull null
            val updatedPackages = recipe.packageNames.filter(available::containsKey)
            if (updatedPackages.isEmpty()) return@mapNotNull null
            val availableVersion = available[recipe.primaryPackage]
                ?: available.getValue(updatedPackages.first())
            recipe.toEntry(installed, availableVersion)
        }
    }

    private fun refreshIndex() {
        val result = port.runRoot(
            executable = "apt-get",
            arguments = listOf("update"),
            environment = PACKAGE_ENVIRONMENT,
            timeoutMs = INDEX_TIMEOUT_MS,
            onOutput = {},
        )
        if (result.exitCode != 0) {
            throw PackageStoreException(
                code = "INDEX_REFRESH_FAILED",
                message = result.output.ifBlank { "Could not refresh Debian package indexes" },
            )
        }
        lastIndexRefreshEpochMs = clock()
    }

    private fun indexRefreshRequired(): Boolean {
        val refreshedAt = lastIndexRefreshEpochMs ?: return true
        return clock() - refreshedAt >= INDEX_TTL_MS
    }

    private fun storage(): PackageStoreStorage {
        val home = port.directoryBytes(File(port.rootfsDir, "home/droiddesk"))
        val cache = port.directoryBytes(File(port.rootfsDir, "var/cache/apt")) +
            port.directoryBytes(File(port.rootfsDir, "var/lib/apt/lists"))
        val totalRootfs = port.directoryBytes(port.rootfsDir)
        return PackageStoreStorage(
            available = true,
            systemBytes = (totalRootfs - home - cache).coerceAtLeast(0),
            homeBytes = home,
            cacheBytes = cache,
            packageCount = port.installedPackages().size,
            deviceFreeBytes = port.availableBytes().coerceAtLeast(0),
            deviceTotalBytes = port.rootfsDir.totalSpace.coerceAtLeast(
                port.availableBytes().coerceAtLeast(0),
            ),
        )
    }

    private fun target(): PackageStoreTarget {
        if (port.readyMarker() == null || !port.rootfsDir.isDirectory) {
            return unavailableTarget(
                PackageStoreAvailability.ENVIRONMENT_NOT_INSTALLED,
                "Debian PRoot is not installed",
            )
        }
        val architecture = port.architecture()
        if (architecture != "arm64") {
            return unavailableTarget(
                PackageStoreAvailability.TEMPORARILY_UNAVAILABLE,
                "Unsupported Debian architecture: ${architecture ?: "unknown"}",
            )
        }
        return PackageStoreTarget(
            environmentKind = kind,
            displayName = "Debian PRoot",
            availability = PackageStoreAvailability.READY,
            reason = null,
            capabilities = READY_CAPABILITIES,
        )
    }

    private fun unavailableTarget(
        availability: PackageStoreAvailability,
        reason: String,
    ) = PackageStoreTarget(
        environmentKind = kind,
        displayName = "Debian PRoot",
        availability = availability,
        reason = reason,
        capabilities = UNAVAILABLE_CAPABILITIES,
    )

    private fun PackageRecipe.toEntry(
        installed: Map<String, String>,
        availableVersion: String? = null,
    ): PackageStoreEntry {
        val installedRequired = packageNames.count(installed::containsKey)
        val fullyInstalled = installedRequired == packageNames.size
        val partiallyInstalled = installedRequired in 1 until packageNames.size
        val installedVersion = installed[primaryPackage]
        val dynamicLimitations = if (partiallyInstalled) {
            limitations + "The recipe installation is incomplete."
        } else {
            limitations
        }
        if (
            supportLevel == PackageSupportLevel.EXPERIMENTAL &&
            !fullyInstalled &&
            !partiallyInstalled
        ) {
            return ExperimentalRecipeGate.toEntry(this, sandboxResult(this))
        }
        return PackageStoreEntry(
            entryId = entryId,
            displayName = displayName,
            summary = summary,
            category = category,
            iconNames = iconNames,
            gui = gui,
            supportLevel = supportLevel,
            state = when {
                fullyInstalled && availableVersion != null ->
                    PackageEntryState.UPDATE_AVAILABLE
                fullyInstalled -> PackageEntryState.INSTALLED
                partiallyInstalled -> PackageEntryState.UNAVAILABLE
                else -> PackageEntryState.AVAILABLE
            },
            availableVersion = availableVersion,
            installedVersion = installedVersion,
            removable = removable,
            limitations = dynamicLimitations,
            sourceLabel = sourceLabel(source),
        )
    }

    private fun sandboxResult(recipe: PackageRecipe): SandboxProbeResult =
        sandboxResults.getOrPut(recipe.entryId) { sandboxProbe.probe(recipe) }

    private fun parseUpgradable(output: String): Map<String, String> =
        output.lineSequence().mapNotNull { line ->
            if (!line.contains("[upgradable from:")) return@mapNotNull null
            val packageName = line.substringBefore('/').trim()
            if (!PACKAGE_NAME.matches(packageName)) return@mapNotNull null
            val availableVersion = line.substringAfter('/').trim().split(Regex("\\s+"))
                .getOrNull(1)
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            packageName to availableVersion
        }.toMap()

    private fun rejected(
        request: PackageStoreOperation,
        code: String,
        message: String,
    ) = result(request, PackageOperationOutcome.REJECTED, code, message)

    private fun result(
        request: PackageStoreOperation,
        outcome: PackageOperationOutcome,
        code: String,
        message: String,
    ) = PackageOperationResult(
        operationId = request.operationId,
        environmentKind = kind,
        kind = request.kind,
        entryId = request.entryId,
        outcome = outcome,
        message = message,
        entry = null,
        bytesFreed = null,
        warnings = emptyList(),
        error = PackageStoreError(code, message, null),
    )

    private companion object {
        const val READ_TIMEOUT_MS = 30_000L
        const val INDEX_TIMEOUT_MS = 120_000L
        const val INDEX_TTL_MS = 6 * 60 * 60 * 1000L
        val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]*")
        val ENTRY_OPERATIONS = setOf(
            PackageOperationKind.INSTALL,
            PackageOperationKind.REMOVE,
            PackageOperationKind.UPGRADE,
        )
        val PACKAGE_ENVIRONMENT = mapOf(
            "DEBIAN_FRONTEND" to "noninteractive",
            "TZ" to "Etc/UTC",
        )
        val READY_CAPABILITIES = PackageStoreCapabilities(
            browse = true,
            arbitraryRepositoryPackages = false,
            remove = true,
            updateOne = true,
            updateAll = true,
            cleanCache = true,
        )
        val UNAVAILABLE_CAPABILITIES = PackageStoreCapabilities(
            browse = false,
            arbitraryRepositoryPackages = false,
            remove = false,
            updateOne = false,
            updateAll = false,
            cleanCache = false,
        )

        fun sourceLabel(source: PackageRecipeSource): String = when (source) {
            PackageRecipeSource.DebianBookworm -> "Debian Bookworm"
            is PackageRecipeSource.SignedAptRepository -> source.sourceId
            is PackageRecipeSource.PinnedDeb -> "Official pinned ARM64 package"
        }
    }
}
