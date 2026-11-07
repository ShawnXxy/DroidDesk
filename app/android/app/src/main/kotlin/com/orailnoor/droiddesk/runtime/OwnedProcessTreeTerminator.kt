package com.orailnoor.droiddesk.runtime

internal class OwnedProcessTreeTerminator(
    private val descendantsOf: (Int) -> List<Int>,
    private val identityOf: (Int) -> String?,
    private val signal: (Int, Int) -> Unit,
    private val elapsedRealtime: () -> Long,
    private val sleep: (Long) -> Unit,
) {
    fun terminate(rootPid: Int, gracePeriodMs: Long) {
        val owned = (descendantsOf(rootPid).asReversed() + rootPid)
            .distinct()
            .mapNotNull { pid ->
                identityOf(pid)?.let { identity -> OwnedProcess(pid, identity) }
            }
        owned.forEach { process ->
            if (isAlive(process)) signal(process.pid, SIGTERM)
        }

        val deadline = elapsedRealtime() + gracePeriodMs.coerceAtLeast(0)
        while (owned.any(::isAlive) && elapsedRealtime() < deadline) {
            sleep(POLL_INTERVAL_MS)
        }

        owned.forEach { process ->
            if (isAlive(process)) signal(process.pid, SIGKILL)
        }
    }

    private fun isAlive(process: OwnedProcess): Boolean {
        val currentIdentity = identityOf(process.pid) ?: return false
        return process.identity == currentIdentity
    }

    private data class OwnedProcess(
        val pid: Int,
        val identity: String,
    )

    private companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
        const val POLL_INTERVAL_MS = 50L
    }
}
