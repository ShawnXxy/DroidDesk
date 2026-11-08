package com.orailnoor.droiddesk.runtime

interface HostAudioPort {
    fun startWithLibraryPath(libraryPath: String): Result<Unit>
    fun stopAfterFailedStart(): Result<Unit>
}
internal class HostAudioCleanupException(
    val libraryPath: String,
    cause: Throwable,
) : IllegalStateException(
    "Could not clean up failed host-audio candidate: $libraryPath",
    cause,
)
class HostAudioAdapter(
    private val port: HostAudioPort,
    private val preferences: HostAudioPreferenceStore,
) {
    fun start(
        sdkInt: Int,
        appVersionCode: Long,
        prefixLibraryPath: String,
    ): Result<String> = runCatching {
        val systemLibraryPath = "/system/lib64:$prefixLibraryPath"
        val preferredPath = hostPulseAudioLibraryPath(sdkInt, prefixLibraryPath)
        val orderedCandidates = listOf(
            preferredPath,
            if (preferredPath == prefixLibraryPath) systemLibraryPath else prefixLibraryPath,
        )
        val cached = preferences.load()
        if (cached != null && cached.appVersionCode != appVersionCode) {
            preferences.clear()
        }
        val candidates = buildList {
            if (cached?.appVersionCode == appVersionCode) add(cached.libraryPath)
            addAll(orderedCandidates)
        }.distinct()
        var firstFailure: Throwable? = null
        candidates.forEach { libraryPath ->
            val failure = port.startWithLibraryPath(libraryPath).exceptionOrNull()
            if (failure == null) {
                preferences.save(VerifiedAudioPath(appVersionCode, libraryPath))
                return@runCatching libraryPath
            }
            if (
                cached?.appVersionCode == appVersionCode &&
                cached.libraryPath == libraryPath
            ) {
                preferences.clear()
            }
            if (failure is HostAudioCleanupException) {
                if (firstFailure != null && firstFailure !== failure) {
                    failure.addSuppressed(firstFailure)
                }
                throw failure
            }
            val primaryFailure = firstFailure ?: failure.also {
                firstFailure = it
            }
            if (failure !== primaryFailure) {
                primaryFailure.addSuppressed(failure)
            }
            val cleanupFailure = port.stopAfterFailedStart().exceptionOrNull()
            if (cleanupFailure != null && cleanupFailure !== primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure)
                throw primaryFailure
            }
        }
        throw firstFailure ?: IllegalStateException("No host-audio candidates")
    }
}
