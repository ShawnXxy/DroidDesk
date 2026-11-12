package com.orailnoor.droiddesk.display.readiness

import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.PixelSize
import com.orailnoor.droiddesk.display.RendererClaim
import com.orailnoor.droiddesk.display.RendererLease
import com.orailnoor.droiddesk.view.advanceReadyDesktopRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopReadinessGateTest {
    @Test
    fun signalsMayArriveInAnyOrder() {
        val permutations = permutationsOf(ReadinessSignal.entries.toList())
        assertEquals(24, permutations.size)

        val sharedCompletions = mutableListOf<Long>()
        val sharedGate = DesktopReadinessGate(sharedCompletions::add)

        permutations.forEachIndexed { index, order ->
            val generation = (index + 1).toLong()
            val isolatedCompletions = mutableListOf<Long>()
            val isolatedGate = DesktopReadinessGate(isolatedCompletions::add)

            isolatedGate.reset(generation)
            sharedGate.reset(generation)

            order.dropLast(1).forEach { signal ->
                isolatedGate.signal(generation, signal)
                sharedGate.signal(generation, signal)
                assertTrue(
                    "Premature isolated completion for $order",
                    isolatedCompletions.isEmpty(),
                )
                assertEquals(
                    "Premature shared completion for $order",
                    index,
                    sharedCompletions.size,
                )
            }

            isolatedGate.signal(generation, order.last())
            sharedGate.signal(generation, order.last())

            assertEquals(listOf(generation), isolatedCompletions)
            assertEquals(generation, sharedCompletions.last())
            assertEquals(index + 1, sharedCompletions.size)
        }

        assertEquals((1L..24L).toList(), sharedCompletions)
    }

    @Test
    fun duplicateSignalsCompleteOnce() {
        val completions = mutableListOf<Long>()
        lateinit var gate: DesktopReadinessGate
        gate = DesktopReadinessGate { generation ->
            completions += generation
            // Re-entrant duplicate signals inside onReady must never fire a second completion.
            ReadinessSignal.entries.forEach { signal -> gate.signal(generation, signal) }
        }

        gate.reset(7L)
        repeat(5) {
            gate.signal(7L, ReadinessSignal.RENDERER_CLAIMED)
            gate.signal(7L, ReadinessSignal.SURFACE_READY)
            gate.signal(7L, ReadinessSignal.X11_CONNECTED)
        }
        assertTrue(completions.isEmpty())

        repeat(5) {
            gate.signal(7L, ReadinessSignal.GEOMETRY_COMMITTED)
            ReadinessSignal.entries.forEach { signal -> gate.signal(7L, signal) }
        }

        assertEquals(listOf(7L), completions)
    }

    @Test
    fun staleGenerationCannotCompleteCurrentGate() {
        val completions = mutableListOf<Long>()
        val gate = DesktopReadinessGate(completions::add)

        // Signals before any reset are ignored.
        ReadinessSignal.entries.forEach { signal -> gate.signal(1L, signal) }
        assertTrue(completions.isEmpty())

        gate.reset(1L)
        gate.signal(1L, ReadinessSignal.RENDERER_CLAIMED)
        gate.signal(1L, ReadinessSignal.SURFACE_READY)

        gate.reset(2L)
        gate.signal(2L, ReadinessSignal.RENDERER_CLAIMED)
        gate.signal(2L, ReadinessSignal.SURFACE_READY)
        gate.signal(2L, ReadinessSignal.X11_CONNECTED)

        // Stale signals, revocations, and cancellations from generation 1 or future generation 3
        // cannot complete or cancel generation 2.
        ReadinessSignal.entries.forEach { signal ->
            gate.signal(1L, signal)
            gate.signal(3L, signal)
            gate.signal(0L, signal)
            gate.signal(-1L, signal)
        }
        gate.revoke(1L, ReadinessSignal.SURFACE_READY)
        gate.cancel(1L)
        gate.cancel(3L)
        assertTrue(completions.isEmpty())

        gate.signal(2L, ReadinessSignal.GEOMETRY_COMMITTED)
        assertEquals(listOf(2L), completions)
    }

    @Test
    fun resetCancelsPartiallyReadyGeneration() {
        val completions = mutableListOf<Long>()
        val gate = DesktopReadinessGate(completions::add)

        assertThrows(IllegalArgumentException::class.java) {
            gate.reset(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gate.reset(-5L)
        }

        gate.reset(10L)
        gate.signal(10L, ReadinessSignal.RENDERER_CLAIMED)
        gate.signal(10L, ReadinessSignal.SURFACE_READY)
        gate.signal(10L, ReadinessSignal.X11_CONNECTED)

        gate.reset(11L)
        gate.signal(10L, ReadinessSignal.GEOMETRY_COMMITTED)
        // Generation 11 must not inherit the three signals collected by generation 10.
        gate.signal(11L, ReadinessSignal.GEOMETRY_COMMITTED)
        assertTrue(completions.isEmpty())

        gate.signal(11L, ReadinessSignal.RENDERER_CLAIMED)
        gate.signal(11L, ReadinessSignal.SURFACE_READY)
        gate.cancel(11L)
        gate.signal(11L, ReadinessSignal.X11_CONNECTED)
        assertTrue(completions.isEmpty())

        gate.reset(12L)
        ReadinessSignal.entries.forEach { signal -> gate.signal(12L, signal) }
        assertEquals(listOf(12L), completions)
    }

    @Test
    fun slowSignalsDoNotRequireTimer() {
        val events = mutableListOf<String>()
        val gate = DesktopReadinessGate { generation ->
            events += "ready:$generation"
        }

        gate.reset(5L)
        events += "claimed"
        gate.signal(5L, ReadinessSignal.RENDERER_CLAIMED)
        events += "surface-1"
        gate.signal(5L, ReadinessSignal.SURFACE_READY)
        // Surface is destroyed while X11 connection is still slow/pending.
        events += "surface-destroyed"
        gate.revoke(5L, ReadinessSignal.SURFACE_READY)

        events += "x11-connected"
        gate.signal(5L, ReadinessSignal.X11_CONNECTED)
        events += "geometry-committed"
        gate.signal(5L, ReadinessSignal.GEOMETRY_COMMITTED)
        // Even with X11 + geometry ready, Linux must not start against a destroyed surface.
        assertEquals(
            listOf(
                "claimed",
                "surface-1",
                "surface-destroyed",
                "x11-connected",
                "geometry-committed",
            ),
            events,
        )

        events += "surface-2"
        gate.signal(5L, ReadinessSignal.SURFACE_READY)
        assertEquals("ready:5", events.last())

        // Post-completion surface recreation must not re-fire onReady.
        gate.revoke(5L, ReadinessSignal.SURFACE_READY)
        gate.signal(5L, ReadinessSignal.SURFACE_READY)
        assertEquals(1, events.count { it.startsWith("ready:") })
    }

    @Test
    fun gateStartsSessionSynchronouslyOnlyAfterAllSignalsAndTransfersUnreadyClaim() {
        val coordinator = DesktopDisplayCoordinator()
        val epoch = coordinator.prepareSession("proot", "xfce4", true)
        val events = mutableListOf<String>()
        var activeLease: RendererLease? = null
        var transportAndSurfaceReady = false
        var measuredContent: PixelSize? = null

        lateinit var gate: DesktopReadinessGate
        gate = DesktopReadinessGate { generation ->
            val lease = activeLease?.takeIf { it.generation == generation } ?: return@DesktopReadinessGate
            advanceReadyDesktopRenderer(
                lease = lease,
                isCurrentRenderer = { coordinator.isCurrent(it) && activeLease == it },
                isTransportAndSurfaceReady = { transportAndSurfaceReady },
                measuredContent = measuredContent,
                onAttached = { attachedLease, size ->
                    events += "attached:${attachedLease.generation}:${size.width}x${size.height}"
                },
                consumeSessionStart = coordinator::consumeSessionStart,
                beginSessionStart = coordinator::beginSessionStart,
                startSession = { startEpoch -> events += "start:$startEpoch" },
                shouldRevealExistingSession = { true },
                isSessionStarting = { coordinator.isStarting },
                awaitPendingSessionStart = { events += "await" },
                waitForDesktopAndReveal = { events += "reveal" },
            )
        }

        lateinit var firstLease: RendererLease
        firstLease = (coordinator.claim(0) {
            gate.cancel(firstLease.generation)
            events += "detached:${firstLease.generation}"
        } as RendererClaim.Accepted).lease
        activeLease = firstLease
        gate.reset(firstLease.generation)
        gate.signal(firstLease.generation, ReadinessSignal.RENDERER_CLAIMED)
        gate.signal(firstLease.generation, ReadinessSignal.SURFACE_READY)
        gate.signal(firstLease.generation, ReadinessSignal.X11_CONNECTED)
        assertTrue(events.isEmpty())

        // Supersede first renderer before GEOMETRY_COMMITTED arrives.
        val secondLease = (coordinator.claim(0) {} as RendererClaim.Accepted).lease
        activeLease = secondLease
        gate.reset(secondLease.generation)
        // Late geometry from superseded renderer cannot start Linux.
        gate.signal(firstLease.generation, ReadinessSignal.GEOMETRY_COMMITTED)
        assertEquals(listOf("detached:${firstLease.generation}"), events)

        transportAndSurfaceReady = true
        measuredContent = PixelSize(1920, 1080)
        gate.signal(secondLease.generation, ReadinessSignal.GEOMETRY_COMMITTED)
        gate.signal(secondLease.generation, ReadinessSignal.X11_CONNECTED)
        gate.signal(secondLease.generation, ReadinessSignal.SURFACE_READY)
        assertEquals(listOf("detached:${firstLease.generation}"), events)

        gate.signal(secondLease.generation, ReadinessSignal.RENDERER_CLAIMED)
        assertEquals(
            listOf(
                "detached:${firstLease.generation}",
                "attached:${secondLease.generation}:1920x1080",
                "start:$epoch",
            ),
            events,
        )
    }

    @Test
    fun readyRendererRevealsOrAwaitsExistingSessionWithoutStartingSecondSession() {
        val lease = RendererLease(generation = 3L, displayId = 0)
        val events = mutableListOf<String>()
        var starting = true

        advanceReadyDesktopRenderer(
            lease = lease,
            isCurrentRenderer = { it == lease },
            isTransportAndSurfaceReady = { true },
            measuredContent = PixelSize(1280, 720),
            onAttached = { _, size -> events += "attached:${size.width}x${size.height}" },
            consumeSessionStart = { false },
            beginSessionStart = { error("Should not begin session start when unconsumed") },
            startSession = { events += "start:$it" },
            shouldRevealExistingSession = { true },
            isSessionStarting = { starting },
            awaitPendingSessionStart = { events += "await" },
            waitForDesktopAndReveal = { events += "reveal" },
        )

        starting = false
        advanceReadyDesktopRenderer(
            lease = lease,
            isCurrentRenderer = { it == lease },
            isTransportAndSurfaceReady = { true },
            measuredContent = PixelSize(1280, 720),
            onAttached = { _, size -> events += "attached:${size.width}x${size.height}" },
            consumeSessionStart = { false },
            beginSessionStart = { error("Should not begin session start when unconsumed") },
            startSession = { events += "start:$it" },
            shouldRevealExistingSession = { true },
            isSessionStarting = { starting },
            awaitPendingSessionStart = { events += "await" },
            waitForDesktopAndReveal = { events += "reveal" },
        )

        assertEquals(
            listOf(
                "attached:1280x720",
                "await",
                "attached:1280x720",
                "reveal",
            ),
            events,
        )
    }

    @Test
    fun revokingStaleGeometryOrConnectionBeforeFreshSignalPreventsPrematureStart() {
        val completions = mutableListOf<Long>()
        val gate = DesktopReadinessGate(completions::add)

        gate.reset(9L)
        gate.signal(9L, ReadinessSignal.RENDERER_CLAIMED)
        gate.signal(9L, ReadinessSignal.X11_CONNECTED)
        // Portrait geometry was committed before rotation invalidated it.
        gate.signal(9L, ReadinessSignal.GEOMETRY_COMMITTED)

        // Rotation queues a new geometry pass (revoking GEOMETRY_COMMITTED) before surfaceChanged
        // signals SURFACE_READY for the rotated surface.
        gate.revoke(9L, ReadinessSignal.GEOMETRY_COMMITTED)
        gate.signal(9L, ReadinessSignal.SURFACE_READY)
        assertTrue(completions.isEmpty())

        // X11 service temporarily disconnects before landscape geometry finishes.
        gate.revoke(9L, ReadinessSignal.X11_CONNECTED)
        gate.signal(9L, ReadinessSignal.GEOMETRY_COMMITTED)
        assertTrue(completions.isEmpty())

        // Once X11 reconnects while surface + landscape geometry remain valid, gate completes once.
        gate.signal(9L, ReadinessSignal.X11_CONNECTED)
        assertEquals(listOf(9L), completions)
    }

    private fun <T> permutationsOf(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.flatMapIndexed { index, head ->
            val rest = items.filterIndexed { i, _ -> i != index }
            permutationsOf(rest).map { tail -> listOf(head) + tail }
        }
    }
}
