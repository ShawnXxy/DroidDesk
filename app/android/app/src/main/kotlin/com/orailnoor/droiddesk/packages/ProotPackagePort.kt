package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.ExecutionResult
import com.orailnoor.droiddesk.runtime.AndroidDirectorySizer
import com.orailnoor.droiddesk.runtime.DirectorySizer
import com.orailnoor.droiddesk.runtime.ProotDebianHost
import com.orailnoor.droiddesk.runtime.ProotReadyMarker
import java.io.File

internal interface ProotPackagePort {
    val rootfsDir: File

    fun readyMarker(): ProotReadyMarker?
    fun architecture(): String?
    fun availableBytes(): Long
    fun installedPackages(): Map<String, String>
    fun directoryBytes(directory: File): Long
    fun beginOperation(operationId: Long): Boolean
    fun finishOperation(operationId: Long)

    fun runRoot(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): ExecutionResult

    fun runDesktop(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): ExecutionResult

    fun cancelOwnedProcess(operationId: Long, gracePeriodMs: Long): Boolean
}

internal class AndroidProotPackagePort(
    private val host: ProotDebianHost,
    private val directorySizer: DirectorySizer = AndroidDirectorySizer,
) : ProotPackagePort {
    override val rootfsDir: File
        get() = host.rootfsPath()

    override fun readyMarker(): ProotReadyMarker? = host.readReadyMarker()

    override fun architecture(): String? {
        val configured = File(rootfsDir, "var/lib/dpkg/arch")
            .takeIf(File::isFile)
            ?.readLines()
            ?.map(String::trim)
            ?.firstOrNull { it.isNotEmpty() && it != "all" }
        if (configured != null) return configured
        val status = File(rootfsDir, "var/lib/dpkg/status")
        if (!status.isFile) return null
        val architectures = status.useLines { lines ->
            lines.filter { it.startsWith("Architecture:") }
                .map { it.substringAfter(':').trim() }
                .filter { it.isNotEmpty() && it != "all" }
                .toSet()
        }
        return when {
            "arm64" in architectures -> "arm64"
            architectures.size == 1 -> architectures.single()
            else -> null
        }
    }

    override fun availableBytes(): Long = host.availableBytes()

    override fun installedPackages(): Map<String, String> {
        val status = File(rootfsDir, "var/lib/dpkg/status")
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
            val packageName = fields["Package"]?.substringBefore(':') ?: return@mapNotNull null
            packageName to fields["Version"].orEmpty()
        }.toMap()
    }

    override fun directoryBytes(directory: File): Long =
        directorySizer.bytes(directory)

    override fun runRoot(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): ExecutionResult = host.executeActive(
        user = "root",
        command = boundedCommand(executable, arguments, environment, timeoutMs),
        onOutput = onOutput,
    )

    override fun runDesktop(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): ExecutionResult = host.executeActive(
        user = "droiddesk",
        command = boundedCommand(executable, arguments, environment, timeoutMs),
        onOutput = onOutput,
    )

    override fun beginOperation(operationId: Long): Boolean =
        host.beginActivePackageCommand(operationId)

    override fun finishOperation(operationId: Long) =
        host.finishActivePackageCommand(operationId)

    override fun cancelOwnedProcess(
        operationId: Long,
        gracePeriodMs: Long,
    ): Boolean = host.cancelActivePackageCommand(operationId, gracePeriodMs)

    private fun boundedCommand(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
    ): String {
        require(executable.matches(EXECUTABLE)) { "Invalid package executable: $executable" }
        require(timeoutMs > 0) { "Package command timeout must be positive" }
        environment.keys.forEach { key ->
            require(key.matches(ENVIRONMENT_NAME)) { "Invalid package environment name: $key" }
        }
        val timeoutSeconds = (timeoutMs + 999) / 1_000
        val environmentPrefix = environment.toSortedMap().entries.joinToString(" ") {
            "${it.key}=${shellQuote(it.value)}"
        }
        return buildString {
            append("env")
            if (environmentPrefix.isNotEmpty()) append(" ").append(environmentPrefix)
            append(" timeout --signal=TERM --kill-after=5s ")
            append(timeoutSeconds).append("s ")
            append(shellQuote(executable))
            arguments.forEach { argument -> append(" ").append(shellQuote(argument)) }
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        val EXECUTABLE = Regex("[A-Za-z0-9][A-Za-z0-9+._-]*")
        val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
