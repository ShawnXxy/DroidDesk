package com.orailnoor.droiddesk.runtime

internal class OwnedProcessTreeTerminator(
    private val descendantsOf: (Int) -> List<Int>,
    private val identityOf: (Int) -> String?,
    private val signal: (Int, Int) -> Unit,
    private val elapsedRealtime: () -> Long,
    private val sleep: (Long) -> Unit,
    private val membersOfGroup: (Int) -> List<Int> = { emptyList() },
) {
    fun terminate(rootPid: Int, gracePeriodMs: Long) {
        val owned = linkedMapOf<Int, OwnedProcess>()
        fun captureNewProcesses(): List<OwnedProcess> {
            return (
                descendantsOf(rootPid).asReversed() +
                    membersOfGroup(rootPid).asReversed() +
                    rootPid
                )
                .distinct()
                .mapNotNull { pid ->
                    if (owned.containsKey(pid)) return@mapNotNull null
                    identityOf(pid)?.let { identity -> OwnedProcess(pid, identity) }
                }
                .onEach { process -> owned[process.pid] = process }
        }
        captureNewProcesses().forEach { process ->
            if (isAlive(process)) signal(process.pid, SIGTERM)
        }

        val deadline = elapsedRealtime() + gracePeriodMs.coerceAtLeast(0)
        while (elapsedRealtime() < deadline) {
            val discovered = captureNewProcesses()
            discovered.forEach { process ->
                if (isAlive(process)) signal(process.pid, SIGTERM)
            }
            if (owned.values.none(::isAlive) && discovered.isEmpty()) break
            sleep(POLL_INTERVAL_MS)
        }

        captureNewProcesses()
        owned.values.forEach { process ->
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
