package com.orailnoor.droiddesk.view

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopActivityBindingTest {
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
