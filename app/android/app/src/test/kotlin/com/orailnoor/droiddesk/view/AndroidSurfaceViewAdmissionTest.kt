package com.orailnoor.droiddesk.view

import com.orailnoor.droiddesk.display.RendererClaim
import com.orailnoor.droiddesk.display.RendererLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidSurfaceViewAdmissionTest {
    @Test
    fun acceptedClaimPublishesLeaseBeforeRendererAttachmentCanFail() {
        val calls = mutableListOf<String>()
        val lease = RendererLease(generation = 12, displayId = 5)
        val failure = IllegalStateException("renderer setup failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            createViewForRendererClaim(
                claim = RendererClaim.Accepted(lease),
                createPlaceholder = { Any() },
                onAccepted = { calls += "accepted:$it" },
                attachRenderer = {
                    calls += "attach:$it"
                    throw failure
                },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("accepted:$lease", "attach:$lease"), calls)
    }

    @Test
    fun rejectedClaimUsesPlaceholderWithoutAttachingRenderer() {
        val calls = mutableListOf<String>()
        val placeholder = Any()

        val view = createViewForRendererClaim(
            claim = RendererClaim.Rejected,
            createPlaceholder = {
                calls += "placeholder"
                placeholder
            },
            onAccepted = { calls += "accepted:$it" },
            attachRenderer = {
                calls += "attach"
                Any()
            },
        )

        assertSame(placeholder, view)
        assertEquals(listOf("placeholder"), calls)
    }

    @Test
    fun acceptedClaimAttachesRendererWithAcceptedLease() {
        val calls = mutableListOf<String>()
        val lease = RendererLease(generation = 8, displayId = 3)
        val attached = Any()

        val view = createViewForRendererClaim(
            claim = RendererClaim.Accepted(lease),
            createPlaceholder = {
                calls += "placeholder"
                Any()
            },
            onAccepted = { calls += "accepted:$it" },
            attachRenderer = {
                calls += "attach:$it"
                attached
            },
        )

        assertSame(attached, view)
        assertEquals(listOf("accepted:$lease", "attach:$lease"), calls)
    }
}
