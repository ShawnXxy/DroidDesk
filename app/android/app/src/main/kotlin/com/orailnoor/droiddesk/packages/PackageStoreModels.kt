package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind

enum class PackageStoreAvailability(val wireValue: String) {
    READY("ready"),
    ENVIRONMENT_NOT_INSTALLED("environmentNotInstalled"),
    UNSUPPORTED_ENVIRONMENT("unsupportedEnvironment"),
    TEMPORARILY_UNAVAILABLE("temporarilyUnavailable"),
}

data class PackageStoreCapabilities(
    val browse: Boolean,
    val arbitraryRepositoryPackages: Boolean,
    val remove: Boolean,
    val updateOne: Boolean,
    val updateAll: Boolean,
    val cleanCache: Boolean,
)

data class PackageStoreTarget(
    val environmentKind: EnvironmentKind,
    val displayName: String,
    val availability: PackageStoreAvailability,
    val reason: String?,
    val capabilities: PackageStoreCapabilities,
) {
    init {
        require(displayName.isNotBlank()) { "Package store display name must not be blank" }
        if (availability != PackageStoreAvailability.READY) {
            require(!reason.isNullOrBlank()) {
                "Unavailable package store targets require a reason"
            }
        }
    }
}

enum class PackageSupportLevel(val wireValue: String) {
    SUPPORTED("supported"),
    LIMITED("limited"),
    EXPERIMENTAL("experimental"),
    REPOSITORY("repository"),
}

enum class PackageEntryState(val wireValue: String) {
    AVAILABLE("available"),
    INSTALLED("installed"),
    UPDATE_AVAILABLE("updateAvailable"),
    UNAVAILABLE("unavailable"),
}

data class PackageStoreEntry(
    val entryId: String,
    val displayName: String,
    val summary: String,
    val category: String,
    val iconNames: List<String>,
    val gui: Boolean,
    val supportLevel: PackageSupportLevel,
    val state: PackageEntryState,
    val availableVersion: String?,
    val installedVersion: String?,
    val removable: Boolean,
    val limitations: List<String>,
    val sourceLabel: String,
) {
    init {
        require(entryId.isNotBlank()) { "Package store entry ID must not be blank" }
        require(displayName.isNotBlank()) { "Package store display name must not be blank" }
        require(summary.isNotBlank()) { "Package store summary must not be blank" }
        require(category.isNotBlank()) { "Package store category must not be blank" }
        require(sourceLabel.isNotBlank()) { "Package store source label must not be blank" }
        require(iconNames.none(String::isBlank)) { "Package store icon names must not be blank" }
        require(limitations.none(String::isBlank)) { "Package store limitations must not be blank" }
        require(availableVersion == null || availableVersion.isNotBlank()) {
            "Available version must be null or non-blank"
        }
        require(installedVersion == null || installedVersion.isNotBlank()) {
            "Installed version must be null or non-blank"
        }
        if (state == PackageEntryState.INSTALLED) {
            require(installedVersion != null) { "Installed entries require an installed version" }
        }
        if (state == PackageEntryState.UPDATE_AVAILABLE) {
            require(installedVersion != null && availableVersion != null) {
                "Update entries require installed and available versions"
            }
        }
    }
}

sealed interface PackageStoreQuery {
    data class Catalog(
        val search: String,
        val limit: Int = 60,
    ) : PackageStoreQuery {
        init {
            require(limit in 1..100) { "Catalog limit must be between 1 and 100" }
        }
    }

    data object Installed : PackageStoreQuery

    data class Updates(
        val refreshIndex: Boolean,
    ) : PackageStoreQuery

    data object Storage : PackageStoreQuery

    data object OperationState : PackageStoreQuery
}

data class PackageStoreStorage(
    val available: Boolean,
    val systemBytes: Long,
    val homeBytes: Long,
    val cacheBytes: Long,
    val packageCount: Int,
    val deviceFreeBytes: Long,
    val deviceTotalBytes: Long,
) {
    init {
        require(systemBytes >= 0) { "System bytes must not be negative" }
        require(homeBytes >= 0) { "Home bytes must not be negative" }
        require(cacheBytes >= 0) { "Cache bytes must not be negative" }
        require(packageCount >= 0) { "Package count must not be negative" }
        require(deviceFreeBytes >= 0) { "Device free bytes must not be negative" }
        require(deviceTotalBytes >= 0) { "Device total bytes must not be negative" }
        require(deviceFreeBytes <= deviceTotalBytes) {
            "Device free bytes must not exceed device total bytes"
        }
    }
}

data class PackageStoreQueryResult(
    val schemaVersion: Int = PACKAGE_STORE_SCHEMA_VERSION,
    val target: PackageStoreTarget,
    val entries: List<PackageStoreEntry> = emptyList(),
    val storage: PackageStoreStorage? = null,
    val operation: PackageOperationSnapshot? = null,
    val completedResult: PackageOperationResult? = null,
    val refreshedAtEpochMs: Long? = null,
) {
    init {
        require(schemaVersion == PACKAGE_STORE_SCHEMA_VERSION) {
            "Unsupported package store schema: $schemaVersion"
        }
        require(refreshedAtEpochMs == null || refreshedAtEpochMs >= 0) {
            "Refresh timestamp must not be negative"
        }
    }
}

enum class PackageOperationKind(val wireValue: String) {
    INSTALL("install"),
    REMOVE("remove"),
    UPGRADE("upgrade"),
    UPGRADE_ALL("upgradeAll"),
    CLEAN_CACHE("cleanCache"),
}

data class PackageStoreOperation(
    val operationId: Long,
    val expectedEnvironmentKind: EnvironmentKind,
    val kind: PackageOperationKind,
    val entryId: String?,
    val acceptExperimentalRisk: Boolean = false,
) {
    init {
        require(operationId > 0) { "Operation ID must be positive" }
        validateOperationEntry(kind, entryId)
    }
}

enum class PackageOperationPhase(val wireValue: String) {
    PREPARING("preparing"),
    REPAIRING("repairing"),
    REFRESHING_INDEX("refreshingIndex"),
    DOWNLOADING("downloading"),
    INSTALLING("installing"),
    CONFIGURING("configuring"),
    INTEGRATING_DESKTOP("integratingDesktop"),
    VERIFYING("verifying"),
    CLEANING_UP("cleaningUp"),
}

data class PackageOperationEvent(
    val schemaVersion: Int = PACKAGE_STORE_SCHEMA_VERSION,
    val operationId: Long,
    val environmentKind: EnvironmentKind,
    val phase: PackageOperationPhase,
    val progress: Double?,
    val message: String,
    val logChunk: String?,
    val cancellable: Boolean,
) {
    init {
        validateSchema(schemaVersion)
        require(operationId > 0) { "Operation ID must be positive" }
        validateProgress(progress)
        require(message.isNotBlank()) { "Operation event message must not be blank" }
    }
}

data class PackageOperationSnapshot(
    val operationId: Long,
    val environmentKind: EnvironmentKind,
    val kind: PackageOperationKind,
    val entryId: String?,
    val phase: PackageOperationPhase,
    val progress: Double?,
    val message: String,
    val cancellable: Boolean,
    val logTail: String,
) {
    init {
        require(operationId > 0) { "Operation ID must be positive" }
        validateOperationEntry(kind, entryId)
        validateProgress(progress)
        require(message.isNotBlank()) { "Operation snapshot message must not be blank" }
    }
}

enum class PackageOperationOutcome(val wireValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    REJECTED("rejected"),
}

data class PackageStoreError(
    val code: String,
    val message: String,
    val logTail: String?,
) {
    init {
        require(code.isNotBlank()) { "Package store error code must not be blank" }
        require(message.isNotBlank()) { "Package store error message must not be blank" }
    }
}

data class PackageStoreWarning(
    val code: String,
    val message: String,
) {
    init {
        require(code.isNotBlank()) { "Package store warning code must not be blank" }
        require(message.isNotBlank()) { "Package store warning message must not be blank" }
    }
}

data class PackageOperationResult(
    val schemaVersion: Int = PACKAGE_STORE_SCHEMA_VERSION,
    val operationId: Long,
    val environmentKind: EnvironmentKind,
    val kind: PackageOperationKind,
    val entryId: String?,
    val outcome: PackageOperationOutcome,
    val message: String,
    val entry: PackageStoreEntry?,
    val bytesFreed: Long?,
    val warnings: List<PackageStoreWarning>,
    val error: PackageStoreError?,
) {
    init {
        validateSchema(schemaVersion)
        require(operationId > 0) { "Operation ID must be positive" }
        validateOperationEntry(kind, entryId)
        require(message.isNotBlank()) { "Operation result message must not be blank" }
        require(bytesFreed == null || bytesFreed >= 0) { "Bytes freed must not be negative" }
        if (outcome == PackageOperationOutcome.SUCCEEDED) {
            require(error == null) { "Successful package operation must not contain an error" }
        } else {
            require(error != null) { "Non-successful package operation requires an error" }
        }
    }
}

data class PackageOperationRequest(
    val kind: PackageOperationKind,
    val entryId: String? = null,
    val acceptExperimentalRisk: Boolean = false,
) {
    init {
        validateOperationEntry(kind, entryId)
    }
}

internal const val PACKAGE_STORE_SCHEMA_VERSION = 1

private fun validateSchema(schemaVersion: Int) {
    require(schemaVersion == PACKAGE_STORE_SCHEMA_VERSION) {
        "Unsupported package store schema: $schemaVersion"
    }
}

private fun validateProgress(progress: Double?) {
    require(progress == null || progress in 0.0..1.0) {
        "Package operation progress must be null or between 0 and 1"
    }
}

private fun validateOperationEntry(kind: PackageOperationKind, entryId: String?) {
    when (kind) {
        PackageOperationKind.INSTALL,
        PackageOperationKind.REMOVE,
        PackageOperationKind.UPGRADE,
        -> require(!entryId.isNullOrBlank()) { "$kind requires an entry ID" }

        PackageOperationKind.UPGRADE_ALL,
        PackageOperationKind.CLEAN_CACHE,
        -> require(entryId == null) { "$kind does not accept an entry ID" }
    }
}
