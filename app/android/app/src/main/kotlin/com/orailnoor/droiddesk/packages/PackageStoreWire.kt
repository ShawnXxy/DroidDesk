package com.orailnoor.droiddesk.packages

fun PackageStoreQueryResult.toWireMap(): Map<String, Any?> = mapOf(
    "schemaVersion" to schemaVersion,
    "target" to target.toWireMap(),
    "entries" to entries.map(PackageStoreEntry::toWireMap),
    "storage" to storage?.toWireMap(),
    "operation" to operation?.toWireMap(),
    "completedResult" to completedResult?.toWireMap(),
    "refreshedAtEpochMs" to refreshedAtEpochMs,
)

fun PackageOperationEvent.toWireMap(): Map<String, Any?> = mapOf(
    "schemaVersion" to schemaVersion,
    "operationId" to operationId,
    "environmentKind" to environmentKind.wireValue,
    "phase" to phase.wireValue,
    "progress" to progress,
    "message" to message,
    "logChunk" to logChunk,
    "cancellable" to cancellable,
)

fun PackageOperationResult.toWireMap(): Map<String, Any?> = mapOf(
    "schemaVersion" to schemaVersion,
    "operationId" to operationId,
    "environmentKind" to environmentKind.wireValue,
    "kind" to kind.wireValue,
    "entryId" to entryId,
    "outcome" to outcome.wireValue,
    "message" to message,
    "entry" to entry?.toWireMap(),
    "bytesFreed" to bytesFreed,
    "warnings" to warnings.map(PackageStoreWarning::toWireMap),
    "error" to error?.toWireMap(),
)

private fun PackageStoreTarget.toWireMap(): Map<String, Any?> = mapOf(
    "environmentKind" to environmentKind.wireValue,
    "displayName" to displayName,
    "availability" to availability.wireValue,
    "reason" to reason,
    "capabilities" to capabilities.toWireMap(),
)

private fun PackageStoreCapabilities.toWireMap(): Map<String, Any> = mapOf(
    "browse" to browse,
    "arbitraryRepositoryPackages" to arbitraryRepositoryPackages,
    "remove" to remove,
    "updateOne" to updateOne,
    "updateAll" to updateAll,
    "cleanCache" to cleanCache,
)

private fun PackageStoreEntry.toWireMap(): Map<String, Any?> = mapOf(
    "entryId" to entryId,
    "displayName" to displayName,
    "summary" to summary,
    "category" to category,
    "iconNames" to iconNames,
    "gui" to gui,
    "supportLevel" to supportLevel.wireValue,
    "state" to state.wireValue,
    "availableVersion" to availableVersion,
    "installedVersion" to installedVersion,
    "removable" to removable,
    "limitations" to limitations,
    "sourceLabel" to sourceLabel,
)

private fun PackageStoreStorage.toWireMap(): Map<String, Any> = mapOf(
    "available" to available,
    "systemBytes" to systemBytes,
    "homeBytes" to homeBytes,
    "cacheBytes" to cacheBytes,
    "packageCount" to packageCount,
    "deviceFreeBytes" to deviceFreeBytes,
    "deviceTotalBytes" to deviceTotalBytes,
)

private fun PackageOperationSnapshot.toWireMap(): Map<String, Any?> = mapOf(
    "operationId" to operationId,
    "environmentKind" to environmentKind.wireValue,
    "kind" to kind.wireValue,
    "entryId" to entryId,
    "phase" to phase.wireValue,
    "progress" to progress,
    "message" to message,
    "cancellable" to cancellable,
    "logTail" to logTail,
)

private fun PackageStoreWarning.toWireMap(): Map<String, Any> = mapOf(
    "code" to code,
    "message" to message,
)

private fun PackageStoreError.toWireMap(): Map<String, Any?> = mapOf(
    "code" to code,
    "message" to message,
    "logTail" to logTail,
)
