package com.orailnoor.droiddesk.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal fun interface DirectorySizer {
    fun bytes(directory: File): Long
}

internal object AndroidDirectorySizer : DirectorySizer {
    override fun bytes(directory: File): Long {
        if (!directory.isDirectory) return 0
        val process = ProcessBuilder(
            "/system/bin/du",
            "-sk",
            directory.absolutePath,
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val readerFailure = AtomicReference<Throwable?>()
        val reader = thread(name = "droiddesk-directory-size") {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> output.appendLine(line) }
                }
            } catch (error: Throwable) {
                readerFailure.set(error)
            }
        }
        if (!process.waitFor(DIRECTORY_SIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            runCatching { process.inputStream.close() }
            reader.join(1_000)
            throw IllegalStateException(
                "Timed out measuring directory: ${directory.absolutePath}",
            ).also { timeout ->
                readerFailure.get()?.let(timeout::addSuppressed)
            }
        }
        reader.join()
        readerFailure.get()?.let { error ->
            throw IllegalStateException(
                "Could not read directory measurement: ${directory.absolutePath}",
                error,
            )
        }
        val kibibytes = output.lineSequence()
            .mapNotNull { line ->
                line.substringBefore('\t').trim().toLongOrNull()
            }
            .lastOrNull()
            ?: throw IllegalStateException(
                "Could not measure directory: ${directory.absolutePath}",
            )
        return Math.multiplyExact(kibibytes, 1024L)
    }

    private const val DIRECTORY_SIZE_TIMEOUT_SECONDS = 10L
}
