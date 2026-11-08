package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun doesNotCaptureUnrelatedChildrenAfterRootPidReuse() {
        val identities = mutableMapOf(10 to "root-start", 11 to "unrelated-child")
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
                if (pid == 10) identities[10] = "reused-root"
            },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
            isOwned = { pid -> pid == 10 },
        )

        terminator.terminate(rootPid = 10, gracePeriodMs = 100)

        assertEquals(listOf(10 to 15), signals)
    }

    @Test
    fun doesNotCaptureChildrenWhenRootIdentityChangesDuringEnumeration() {
        val identities = mutableMapOf(10 to "root-start", 11 to "unrelated-child")
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = {
                identities[10] = "reused-root"
                listOf(11)
            },
            identityOf = identities::get,
            signal = { pid, signal -> signals += pid to signal },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
        )

        terminator.terminate(rootPid = 10, gracePeriodMs = 100)

        assertEquals(emptyList<Pair<Int, Int>>(), signals)
    }

    @Test
    fun waitsForForceKilledProcessToExit() {
        val identities = mutableMapOf(
            10 to "root-start",
            11 to "child-start",
        )
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        var forceKillObserved = false
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { listOf(11) },
            identityOf = identities::get,
            signal = { pid, signal ->
                signals += pid to signal
                if (pid == 10 && signal == 15) identities.remove(pid)
                if (pid == 11 && signal == 9) forceKillObserved = true
            },
            elapsedRealtime = { elapsed },
            sleep = { duration ->
                elapsed += duration
                if (forceKillObserved) identities.remove(11)
            },
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
        assertEquals(null, identities[11])
    }

    @Test
    fun waitsForOwnedProcessGroupToBecomeEmpty() {
        val identities = mutableMapOf(11 to "child-start")
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { emptyList() },
            membersOfGroup = { listOf(11) },
            identityOf = identities::get,
            signal = { _, _ -> },
            elapsedRealtime = { elapsed },
            sleep = { duration ->
                elapsed += duration
                identities.remove(11)
            },
            isOwned = { pid -> pid == 11 },
        )

        assertTrue(terminator.awaitGroupExit(groupId = 10, timeoutMs = 100))
        assertEquals(50L, elapsed)
    }

    @Test
    fun terminatesDetachedOwnedGroupMember() {
        val identities = mutableMapOf(11 to "detached-child")
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { emptyList() },
            membersOfGroup = { identities.keys.toList() },
            identityOf = identities::get,
            signal = { pid, signal ->
                signals += pid to signal
                identities.remove(pid)
            },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
            isOwned = { pid -> pid == 11 },
        )

        terminator.terminateGroup(groupId = 0, gracePeriodMs = 100)

        assertEquals(listOf(11 to 15), signals)
    }

    @Test
    fun doesNotCapturePidReusedAfterOwnershipCheck() {
        val identities = mutableMapOf(11 to "owned-child")
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        var firstOwnershipCheck = true
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { emptyList() },
            membersOfGroup = { listOf(11) },
            identityOf = identities::get,
            signal = { pid, signal -> signals += pid to signal },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
            isOwned = {
                if (firstOwnershipCheck) {
                    firstOwnershipCheck = false
                    identities[11] = "reused-unrelated"
                    true
                } else {
                    false
                }
            },
        )

        terminator.terminateGroup(groupId = 0, gracePeriodMs = 100)

        assertEquals(emptyList<Pair<Int, Int>>(), signals)
    }

    @Test
    fun reportsSurvivingOwnedGroupMember() {
        val identities = mutableMapOf(11 to "stuck-child")
        var elapsed = 0L
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { emptyList() },
            membersOfGroup = { listOf(11) },
            identityOf = identities::get,
            signal = { _, _ -> },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
            isOwned = { true },
        )

        assertFalse(terminator.terminateGroup(groupId = 0, gracePeriodMs = 100))
    }

    @Test
    fun terminatesOwnedReplacementWithReusedPid() {
        val identities = mutableMapOf(11 to "owned-child-1")
        val signals = mutableListOf<Pair<Int, Int>>()
        var elapsed = 0L
        var termSignals = 0
        val terminator = OwnedProcessTreeTerminator(
            descendantsOf = { emptyList() },
            membersOfGroup = { identities.keys.toList() },
            identityOf = identities::get,
            signal = { pid, signal ->
                signals += pid to signal
                if (signal == 15) {
                    termSignals++
                    if (termSignals == 1) {
                        identities[pid] = "owned-child-2"
                    } else {
                        identities.remove(pid)
                    }
                }
            },
            elapsedRealtime = { elapsed },
            sleep = { elapsed += it },
            isOwned = { true },
        )

        assertTrue(terminator.terminateGroup(groupId = 0, gracePeriodMs = 100))
        assertEquals(listOf(11 to 15, 11 to 15), signals)
    }

    @Test
    fun zombieProcessDoesNotHaveALiveIdentity() {
        val fields = MutableList(20) { "0" }.apply {
            this[0] = "Z"
            this[19] = "12345"
        }

        assertEquals(null, processIdentityFromStatFields(fields))
    }

    @Test
    fun liveProcessIdentityUsesStartTime() {
        val fields = MutableList(20) { "0" }.apply {
            this[0] = "S"
            this[19] = "12345"
        }

        assertEquals("12345", processIdentityFromStatFields(fields))
    }
}
