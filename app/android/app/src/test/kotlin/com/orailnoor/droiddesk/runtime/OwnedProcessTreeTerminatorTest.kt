package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedProcessTreeTerminatorTest {

    @Test
    fun forceKillsCapturedDescendantAfterRootExits() {
        val identities = mutableMapOf(
            10 to "root-start",
            11 to "child-start",
        )
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { listOf(11) },
            identityOf = identities::get,
            signal = { pid, signal ->
                signals += pid to signal
                if (pid == 10 && signal == 15) identities.remove(pid)
            },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
        )

        terminator.terminate(rootPid = 10, gracePeriodMs = 100)

        assertEquals(
            listOf(
                11 to 15,
                10 to 15,
                11 to 9,
            ),
            signals,
        )
    }

    @Test
    fun doesNotSignalAReusedPid() {
        val identities = mutableMapOf(10 to "root-start", 11 to "child-start")
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { listOf(11) },
            identityOf = identities::get,
            signal = { pid, signal ->
                signals += pid to signal
                if (signal == 15) {
                    if (pid == 10) identities.remove(pid)
                    if (pid == 11) identities[pid] = "reused-process"
                }
            },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
        )

        terminator.terminate(rootPid = 10, gracePeriodMs = 100)

        assertEquals(listOf(11 to 15, 10 to 15), signals)
    }

    @Test
    fun skipsPidWhenItsIdentityCannotBeCaptured() {
        val identities = mutableMapOf(10 to "root-start")
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { listOf(11) },
            identityOf = identities::get,
            signal = { pid, signal ->
                signals += pid to signal
                identities.remove(pid)
            },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
        )

        terminator.terminate(rootPid = 10, gracePeriodMs = 100)

        assertEquals(listOf(10 to 15), signals)
    }

    @Test
    fun terminatesGroupChildSpawnedAfterTheInitialSnapshot() {
        val identities = mutableMapOf(10 to "root-start", 11 to "late-child")
        val signals = mutableListOf<Pair<Int, Int>>()
        var groupScans = 0
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { emptyList() },
            membersOfGroup = {
                groupScans++
                if (groupScans == 1) listOf(10) else listOf(11)
            },
            identityOf = identities::get,
            signal = { pid, signal ->
                signals += pid to signal
                identities.remove(pid)
            },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
        )

        terminator.terminate(rootPid = 10, gracePeriodMs = 100)

        assertEquals(listOf(10 to 15, 11 to 15), signals)
    }
}
