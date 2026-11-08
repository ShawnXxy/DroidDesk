package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxRuntimeAudioTest {

    @Test
    fun everyCandidatePathPreservesAAudioFallback() {
        val libraryPath = "/system/lib64:/data/user/0/com.example/files/usr/lib"
        val calls = mutableListOf<String>()
        var readinessChecks = 0

        val result = startPulseAudioCandidateWithFallback(
            libraryPath = libraryPath,
            startAAudio = { path ->
                calls += "start-aaudio:$path"
                Result.success(Unit)
            },
            isReady = { path ->
                readinessChecks++
                calls += "ready-$readinessChecks:$path"
                if (readinessChecks == 2) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("AAudio not ready: $path"))
                }
            },
            stop = { path ->
                calls += "stop:$path"
                Result.success(Unit)
            },
            startFallback = { path ->
                calls += "start-fallback:$path"
                Result.success(Unit)
            },
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "start-aaudio:$libraryPath",
                "ready-1:$libraryPath",
                "stop:$libraryPath",
                "start-fallback:$libraryPath",
                "ready-2:$libraryPath",
            ),
            calls,
        )
    }

    @Test
    fun commandFailureIncludesCandidatePathAndOutput() {
        val libraryPath = "/data/user/0/com.example/files/usr/lib"

        val result = runHostAudioCommand(
            libraryPath = libraryPath,
            operation = "start AAudio daemon",
            body = "pulseaudio --start --load=module-aaudio-sink",
            execute = { "Error: command exited 1: missing libaaudio.so" },
        )

        val failure = result.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains(libraryPath))
        assertTrue(failure.message.orEmpty().contains("missing libaaudio.so"))
    }

    @Test
    fun fallbackFailureIsSuppressedOnAAudioFailure() {
        val libraryPath = "/data/user/0/com.example/files/usr/lib"

        val result = startPulseAudioCandidateWithFallback(
            libraryPath = libraryPath,
            startAAudio = {
                Result.failure(IllegalStateException("AAudio failed: $it"))
            },
            isReady = {
                Result.failure(IllegalStateException("unexpected readiness check: $it"))
            },
            stop = { Result.success(Unit) },
            startFallback = {
                Result.failure(IllegalStateException("fallback failed: $it"))
            },
        )

        val failure = result.exceptionOrNull()!!
        assertEquals("AAudio failed: $libraryPath", failure.message)
        assertEquals(
            listOf("fallback failed: $libraryPath"),
            failure.suppressed.map { it.message },
        )
    }

    @Test
    fun missingProcessProbeIsCleanupFailure() {
        val error = runCatching {
            verifiedHostProcessAlive(
                processName = "pulseaudio",
                probeExecutable = false,
                probeExitCode = null,
                probeOutput = "",
            )
        }.exceptionOrNull()!!

        assertTrue(error.message.orEmpty().contains("probe is unavailable"))
    }

    @Test
    fun indeterminateProcessProbeIsCleanupFailure() {
        val error = runCatching {
            verifiedHostProcessAlive(
                processName = "pulseaudio",
                probeExecutable = true,
                probeExitCode = null,
                probeOutput = "",
            )
        }.exceptionOrNull()!!

        assertTrue(error.message.orEmpty().contains("Could not verify"))
    }

    @Test
    fun nativeSessionAudioUsesAdaptiveStarterAndProvisioningRetry() {
        val calls = mutableListOf<String>()
        var startAttempts = 0

        prepareNativeSessionAudio(
            startAudio = {
                startAttempts++
                calls += "start-$startAttempts"
                if (startAttempts == 1) {
                    Result.failure(IllegalStateException("package missing"))
                } else {
                    Result.success(Unit)
                }
            },
            ensureAudio = {
                calls += "ensure"
                Result.success(Unit)
            },
            onFailure = { calls += "failure:${it.message}" },
        )

        assertEquals(listOf("start-1", "ensure", "start-2"), calls)
    }

    @Test
    fun transientCleanupFailureStillUsesFallbackOnSameCandidate() {
        val libraryPath = "/data/user/0/com.example/files/usr/lib"
        val calls = mutableListOf<String>()
        var cleanupAttempts = 0

        val result = startPulseAudioCandidateWithFallback(
            libraryPath = libraryPath,
            startAAudio = {
                calls += "start-aaudio:$it"
                Result.failure(IllegalStateException("AAudio failed"))
            },
            isReady = {
                calls += "ready:$it"
                Result.success(Unit)
            },
            stop = {
                cleanupAttempts++
                calls += "cleanup-$cleanupAttempts:$it"
                if (cleanupAttempts == 1) {
                    Result.failure(IllegalStateException("transient cleanup failure"))
                } else {
                    Result.success(Unit)
                }
            },
            startFallback = {
                calls += "start-fallback:$it"
                Result.success(Unit)
            },
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "start-aaudio:$libraryPath",
                "cleanup-1:$libraryPath",
                "cleanup-2:$libraryPath",
                "start-fallback:$libraryPath",
                "ready:$libraryPath",
            ),
            calls,
        )
    }

    @Test
    fun commandRunnerPreservesCandidateAndDetailedOutput() {
        val libraryPath = "/data/user/0/com.example/files/usr/lib"

        val result = runHostAudioCommand(
            libraryPath = libraryPath,
            operation = "start AAudio daemon",
            body = "pulseaudio --start --load=module-aaudio-sink",
            execute = { command ->
                assertTrue(command.contains("LD_LIBRARY_PATH='$libraryPath'"))
                "Error: command exited 1: missing libaaudio.so"
            },
        )

        val failure = result.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains(libraryPath))
        assertTrue(failure.message.orEmpty().contains("missing libaaudio.so"))
    }

    @Test
    fun candidateContextIncludesInitialCleanupFailure() {
        val libraryPath = "/data/user/0/com.example/files/usr/lib"
        val cleanupFailure = IllegalStateException("pgrep timed out")

        val result = runHostAudioCandidate(libraryPath) {
            throw cleanupFailure
        }

        val failure = result.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains(libraryPath))
        assertEquals(cleanupFailure, failure.cause)
    }
}
