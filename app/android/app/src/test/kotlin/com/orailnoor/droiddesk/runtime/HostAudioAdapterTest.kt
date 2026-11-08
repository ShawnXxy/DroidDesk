package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class HostAudioAdapterTest {
    @Test
    fun androidElevenPrefersSystemPathButFallsBackToPrefix() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val port = RecordingHostAudioPort(
            starts = mapOf(
                systemPath to Result.failure(IllegalStateException("system path failed")),
                prefix to Result.success(Unit),
            ),
        )
        val preferences = InMemoryHostAudioPreferenceStore()

        val result = HostAudioAdapter(port, preferences).start(
            sdkInt = 30,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        assertEquals(prefix, result.getOrThrow())
        assertEquals(
            listOf("start:$systemPath", "cleanup", "start:$prefix"),
            port.calls,
        )
        assertEquals(VerifiedAudioPath(6L, prefix), preferences.value)
    }

    @Test
    fun newAndroidPrefersPrefixButCanFallBackToSystemPath() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val port = RecordingHostAudioPort(
            starts = mapOf(
                prefix to Result.failure(IllegalStateException("prefix failed")),
                systemPath to Result.success(Unit),
            ),
        )
        val preferences = InMemoryHostAudioPreferenceStore()

        val result = HostAudioAdapter(port, preferences).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        assertEquals(systemPath, result.getOrThrow())
        assertEquals(
            listOf("start:$prefix", "cleanup", "start:$systemPath"),
            port.calls,
        )
        assertEquals(VerifiedAudioPath(6L, systemPath), preferences.value)
    }

    @Test
    fun cachedVerifiedPathRunsFirstForSameAppVersion() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val port = RecordingHostAudioPort(
            starts = mapOf(
                prefix to Result.failure(IllegalStateException("prefix should not run")),
                systemPath to Result.success(Unit),
            ),
        )
        val preferences = InMemoryHostAudioPreferenceStore(
            value = VerifiedAudioPath(6L, systemPath),
        )

        val result = HostAudioAdapter(port, preferences).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        assertEquals(systemPath, result.getOrThrow())
        assertEquals(listOf("start:$systemPath"), port.calls)
        assertEquals(VerifiedAudioPath(6L, systemPath), preferences.value)
    }

    @Test
    fun appUpgradeInvalidatesCachedPath() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val port = RecordingHostAudioPort(
            starts = mapOf(
                prefix to Result.success(Unit),
                systemPath to Result.failure(IllegalStateException("stale path should not run")),
            ),
        )
        val preferences = InMemoryHostAudioPreferenceStore(
            value = VerifiedAudioPath(5L, systemPath),
        )

        val result = HostAudioAdapter(port, preferences).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        assertEquals(prefix, result.getOrThrow())
        assertEquals(listOf("start:$prefix"), port.calls)
        assertEquals(1, preferences.clearCount)
        assertEquals(VerifiedAudioPath(6L, prefix), preferences.value)
    }

    @Test
    fun failedCandidateIsCleanedBeforeFallback() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val port = RecordingHostAudioPort(
            starts = mapOf(
                systemPath to Result.failure(IllegalStateException("cached path failed")),
                prefix to Result.success(Unit),
            ),
        )
        val preferences = InMemoryHostAudioPreferenceStore(
            value = VerifiedAudioPath(6L, systemPath),
        )

        val result = HostAudioAdapter(port, preferences).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        assertEquals(prefix, result.getOrThrow())
        assertEquals(
            listOf("start:$systemPath", "cleanup", "start:$prefix"),
            port.calls,
        )
        assertEquals(1, preferences.clearCount)
        assertEquals(VerifiedAudioPath(6L, prefix), preferences.value)
    }

    @Test
    fun allFailuresPreserveSuppressedEvidence() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val port = RecordingHostAudioPort(
            starts = mapOf(
                prefix to Result.failure(IllegalStateException("prefix failed")),
                systemPath to Result.failure(IllegalStateException("system path failed")),
            ),
            cleanups = listOf(
                Result.success(Unit),
                Result.failure(IllegalStateException("cleanup failed")),
            ),
        )
        val preferences = InMemoryHostAudioPreferenceStore()

        val result = HostAudioAdapter(port, preferences).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        val failure = result.exceptionOrNull()!!
        assertEquals("prefix failed", failure.message)
        assertEquals(
            listOf("system path failed", "cleanup failed"),
            failure.suppressed.map { it.message },
        )
        assertEquals(
            listOf(
                "start:$prefix",
                "cleanup",
                "start:$systemPath",
                "cleanup",
            ),
            port.calls,
        )
    }

    @Test
    fun preferenceFailureIsReturnedWithoutStartingAudio() {
        val port = RecordingHostAudioPort(starts = emptyMap())
        val preferences = object : HostAudioPreferenceStore {
            override fun load(): VerifiedAudioPath? =
                throw IllegalStateException("cache read failed")

            override fun save(value: VerifiedAudioPath) = Unit

            override fun clear() = Unit
        }

        val result = HostAudioAdapter(port, preferences).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = "/data/user/0/com.example/files/usr/lib",
        )

        assertEquals("cache read failed", result.exceptionOrNull()?.message)
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun terminalCleanupFailureDoesNotAdvanceToNextCandidate() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val cleanupFailure = HostAudioCleanupException(
            libraryPath = prefix,
            cause = IllegalStateException("AAudio failed"),
        )
        val port = RecordingHostAudioPort(
            starts = mapOf(
                prefix to Result.failure(cleanupFailure),
                systemPath to Result.success(Unit),
            ),
        )

        val result = HostAudioAdapter(
            port = port,
            preferences = InMemoryHostAudioPreferenceStore(),
        ).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        assertEquals(cleanupFailure, result.exceptionOrNull())
        assertEquals(listOf("start:$prefix"), port.calls)
    }

    @Test
    fun terminalCleanupFailurePreservesEarlierCandidateEvidence() {
        val prefix = "/data/user/0/com.example/files/usr/lib"
        val systemPath = "/system/lib64:$prefix"
        val firstFailure = IllegalStateException("prefix failed")
        val cleanupFailure = HostAudioCleanupException(
            libraryPath = systemPath,
            cause = IllegalStateException("fallback cleanup failed"),
        )
        val port = RecordingHostAudioPort(
            starts = mapOf(
                prefix to Result.failure(firstFailure),
                systemPath to Result.failure(cleanupFailure),
            ),
        )

        val result = HostAudioAdapter(
            port = port,
            preferences = InMemoryHostAudioPreferenceStore(),
        ).start(
            sdkInt = 31,
            appVersionCode = 6L,
            prefixLibraryPath = prefix,
        )

        assertEquals(cleanupFailure, result.exceptionOrNull())
        assertEquals(listOf("prefix failed"), cleanupFailure.suppressed.map { it.message })
        assertEquals(
            listOf("start:$prefix", "cleanup", "start:$systemPath"),
            port.calls,
        )
    }
}

private class RecordingHostAudioPort(
    private val starts: Map<String, Result<Unit>>,
    private val cleanups: List<Result<Unit>> = emptyList(),
) : HostAudioPort {
    val calls = mutableListOf<String>()
    private var cleanupIndex = 0

    override fun startWithLibraryPath(libraryPath: String): Result<Unit> {
        calls += "start:$libraryPath"
        return starts.getValue(libraryPath)
    }

    override fun stopAfterFailedStart(): Result<Unit> {
        calls += "cleanup"
        return cleanups.getOrElse(cleanupIndex++) { Result.success(Unit) }
    }
}

private class InMemoryHostAudioPreferenceStore(
    var value: VerifiedAudioPath? = null,
) : HostAudioPreferenceStore {
    var clearCount = 0

    override fun load(): VerifiedAudioPath? = value

    override fun save(value: VerifiedAudioPath) {
        this.value = value
    }

    override fun clear() {
        clearCount++
        value = null
    }
}
