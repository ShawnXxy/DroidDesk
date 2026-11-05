package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class CommandOwnershipTest {
    @Test
    fun onlyOneConcurrentCallerCanReserveCommandOwnership() {
        val ownership = CommandOwnership<String>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val reservations = List(2) {
            pool.submit<CommandOwnership.Lease?> {
                start.await()
                ownership.reserve(acceptsInput = false)
            }
        }

        start.countDown()
        val acquired = reservations.map { it.get() }
        pool.shutdownNow()

        assertEquals(1, acquired.count { it != null })
        assertTrue(ownership.isBusy())
    }

    @Test
    fun cancelledStartupCannotAttachOrClearANewerOwner() {
        val ownership = CommandOwnership<String>()
        val retired = ownership.reserve(acceptsInput = true)!!

        assertNull(ownership.cancel())
        assertFalse(ownership.attach(retired, "retired"))

        val current = ownership.reserve(acceptsInput = true)!!
        assertTrue(ownership.attach(current, "current"))
        ownership.release(retired)

        assertEquals(CommandInputState.READY, ownership.input().state)
        assertEquals("current", ownership.input().process)
    }

    @Test
    fun inputDistinguishesStartupAndNonInteractiveCommands() {
        val ownership = CommandOwnership<String>()
        val interactive = ownership.reserve(acceptsInput = true)!!
        assertEquals(CommandInputState.STARTING, ownership.input().state)
        ownership.release(interactive)

        val nonInteractive = ownership.reserve(acceptsInput = false)!!
        assertTrue(ownership.attach(nonInteractive, "profile"))
        assertEquals(CommandInputState.NOT_INTERACTIVE, ownership.input().state)
    }
}
