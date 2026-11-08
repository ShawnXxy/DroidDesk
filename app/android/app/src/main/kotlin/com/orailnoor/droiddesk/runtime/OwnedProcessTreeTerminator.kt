package com.orailnoor.droiddesk.runtime

internal class OwnedProcessTreeTerminator(
    private val descendantsOf: (Int) -> List<Int>,
    private val identityOf: (Int) -> String?,
    private val signal: (Int, Int) -> Unit,
    private val elapsedRealtime: () -> Long,
    private val sleep: (Long) -> Unit,
    private val membersOfGroup: (Int) -> List<Int> = { emptyList() },
    private val isOwned: (Int) -> Boolean = { true },
) {
    fun awaitGroupExit(groupId: Int, timeoutMs: Long): Boolean {
        val deadline = elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        while (true) {
            val hasOwnedMembers = membersOfGroup(groupId).any { pid ->
                isOwned(pid) && identityOf(pid) != null
            }
            if (!hasOwnedMembers) return true
            if (elapsedRealtime() >= deadline) return false
            sleep(POLL_INTERVAL_MS)
        }
    }

    fun terminate(rootPid: Int, gracePeriodMs: Long): Boolean {
        val rootIdentity = identityOf(rootPid) ?: return false
        val owned = linkedMapOf<Int, OwnedProcess>()
        fun captureNewProcesses(): List<OwnedProcess> {
            val descendants = if (identityOf(rootPid) == rootIdentity) {
                descendantsOf(rootPid).asReversed()
                    .takeIf { identityOf(rootPid) == rootIdentity }
                    .orEmpty()
            } else {
                emptyList()
            }
            val root = if (identityOf(rootPid) == rootIdentity) listOf(rootPid) else emptyList()
            return capture(
                descendants + membersOfGroup(rootPid).asReversed() + root,
                owned,
            )
        }
        return terminateCaptured(owned, ::captureNewProcesses, gracePeriodMs)
    }

    fun terminateGroup(groupId: Int, gracePeriodMs: Long): Boolean {
        val owned = linkedMapOf<Int, OwnedProcess>()
        fun captureNewProcesses(): List<OwnedProcess> =
            capture(membersOfGroup(groupId).asReversed(), owned)
        return terminateCaptured(owned, ::captureNewProcesses, gracePeriodMs)
    }

    private fun capture(
        candidates: List<Int>,
        owned: MutableMap<Int, OwnedProcess>,
    ): List<OwnedProcess> =
        candidates
            .distinct()
            .mapNotNull { pid ->
                val identity = identityOf(pid) ?: return@mapNotNull null
                val captured = owned[pid]
                if (captured?.identity == identity) return@mapNotNull null
                if (captured != null) owned.remove(pid)
                if (!isOwned(pid)) return@mapNotNull null
                if (identityOf(pid) != identity) return@mapNotNull null
                OwnedProcess(pid, identity)
            }
            .onEach { process -> owned[process.pid] = process }

    private fun terminateCaptured(
        owned: MutableMap<Int, OwnedProcess>,
        captureNewProcesses: () -> List<OwnedProcess>,
        gracePeriodMs: Long,
    ): Boolean {
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

        val forceKillDeadline = elapsedRealtime() + FORCE_KILL_SETTLE_MS
        while (elapsedRealtime() < forceKillDeadline) {
            val discovered = captureNewProcesses()
            discovered.forEach { process ->
                if (isAlive(process)) signal(process.pid, SIGKILL)
            }
            if (owned.values.none(::isAlive) && discovered.isEmpty()) break
            sleep(POLL_INTERVAL_MS)
        }
        return owned.values.none(::isAlive) && captureNewProcesses().isEmpty()
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
        const val FORCE_KILL_SETTLE_MS = 1_000L
    }
}
