package com.orailnoor.droiddesk.view

import com.orailnoor.droiddesk.display.RendererClaim
import com.orailnoor.droiddesk.display.RendererLease
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopActivityBindingTest {
    @Test
    fun acceptedRendererClaimContinuesWithExtractedLeaseWithoutFinishing() {
        val calls = mutableListOf<String>()
        val lease = RendererLease(generation = 4, displayId = 9)

        continueAfterRendererClaim(
            claim = RendererClaim.Accepted(lease),
            finishActivity = { calls += "finish" },
            continueWithLease = { calls += "continue:$it" },
        )

        assertEquals(listOf("continue:$lease"), calls)
    }

    @Test
    fun rejectedRendererClaimFinishesWithoutContinuingRendererSetup() {
        val calls = mutableListOf<String>()

        continueAfterRendererClaim(
            claim = RendererClaim.Rejected,
            finishActivity = { calls += "finish" },
            continueWithLease = { calls += "continue" },
        )

        assertEquals(listOf("finish"), calls)
    }

    @Test
    fun rejectedBindingRunsRejectionAndStopsLifecycleContinuation() {
        val calls = mutableListOf<String>()

        continueAfterActivityBinding(
            bindActivity = {
                calls += "bind"
                false
            },
            rejectAttachment = { calls += "reject" },
            finishActivity = { calls += "finish" },
            continueLifecycle = { calls += "continue" },
        )

        assertEquals(listOf("bind", "reject", "finish"), calls)
    }

    @Test
    fun acceptedBindingContinuesWithoutRunningRejection() {
        val calls = mutableListOf<String>()

        continueAfterActivityBinding(
            bindActivity = {
                calls += "bind"
                true
            },
            rejectAttachment = { calls += "reject" },
            finishActivity = { calls += "finish" },
            continueLifecycle = { calls += "continue" },
        )

        assertEquals(listOf("bind", "continue"), calls)
    }
}
