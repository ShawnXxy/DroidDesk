package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind

internal class PackageStoreMethodHandler(
    private val manager: PackageStoreManager,
) {
    fun query(arguments: Map<*, *>): Map<String, Any?> {
        val environment = parseEnvironment(arguments["environmentKind"])
        val request = try {
            when (val queryKind = arguments["queryKind"]) {
                "catalog" -> PackageStoreQuery.Catalog(
                    search = optionalString(arguments, "search", ""),
                    limit = optionalInt(arguments, "limit", 60),
                )
                "installed" -> PackageStoreQuery.Installed
                "updates" -> PackageStoreQuery.Updates(
                    refreshIndex = optionalBoolean(arguments, "refreshIndex", false),
                )
                "storage" -> PackageStoreQuery.Storage
                "operationState" -> PackageStoreQuery.OperationState
                else -> invalid("Unknown package store query: $queryKind")
            }
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "Invalid package store query")
        }
        return manager.query(environment, request)
            .getOrElse { throw it }
            .toWireMap()
    }

    fun execute(
        arguments: Map<*, *>,
        observer: (PackageOperationEvent) -> Unit,
    ): Map<String, Any?> {
        val environment = parseEnvironment(arguments["environmentKind"])
        val operationKind = when (val value = arguments["operationKind"]) {
            "install" -> PackageOperationKind.INSTALL
            "remove" -> PackageOperationKind.REMOVE
            "upgrade" -> PackageOperationKind.UPGRADE
            "upgradeAll" -> PackageOperationKind.UPGRADE_ALL
            "cleanCache" -> PackageOperationKind.CLEAN_CACHE
            else -> invalid("Unknown package store operation: $value")
        }
        val entryId = optionalNullableString(arguments, "entryId")
        val request = try {
            PackageOperationRequest(
                kind = operationKind,
                entryId = entryId,
                acceptExperimentalRisk = optionalBoolean(
                    arguments,
                    "acceptExperimentalRisk",
                    false,
                ),
            )
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "Invalid package store operation")
        }
        return manager.execute(environment, request, observer).toWireMap()
    }

    fun cancel(arguments: Map<*, *>): Boolean {
        return manager.cancel(operationId(arguments))
    }

    fun acknowledge(arguments: Map<*, *>): Boolean =
        manager.acknowledgeResult(operationId(arguments))

    private fun parseEnvironment(value: Any?): EnvironmentKind {
        if (value !is String) invalid("environmentKind must be a string")
        return EnvironmentKind.fromWire(value)
            ?: invalid("Unknown environmentKind: $value")
    }

    private fun operationId(arguments: Map<*, *>): Long {
        val operationId = when (val value = arguments["operationId"]) {
            is Int -> value.toLong()
            is Long -> value
            else -> invalid("operationId must be a positive integer")
        }
        if (operationId <= 0) {
            invalid("operationId must be a positive integer")
        }
        return operationId
    }

    private fun optionalString(
        arguments: Map<*, *>,
        key: String,
        default: String,
    ): String {
        val value = arguments[key] ?: return default
        if (value !is String) invalid("$key must be a string")
        return value
    }

    private fun optionalNullableString(
        arguments: Map<*, *>,
        key: String,
    ): String? {
        val value = arguments[key] ?: return null
        if (value !is String) invalid("$key must be a string or null")
        return value
    }

    private fun optionalInt(
        arguments: Map<*, *>,
        key: String,
        default: Int,
    ): Int {
        val value = arguments[key] ?: return default
        return when (value) {
            is Int -> value
            is Long -> {
                if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    invalid("$key must be an integer")
                }
                value.toInt()
            }
            else -> invalid("$key must be an integer")
        }
    }

    private fun optionalBoolean(
        arguments: Map<*, *>,
        key: String,
        default: Boolean,
    ): Boolean {
        val value = arguments[key] ?: return default
        if (value !is Boolean) invalid("$key must be a boolean")
        return value
    }

    private fun invalid(message: String): Nothing =
        throw PackageStoreException("INVALID_ARGUMENT", message)
}
