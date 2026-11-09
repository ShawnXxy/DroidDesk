package com.orailnoor.droiddesk.display

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDisplayBindingTest {
    @Test
    fun missingHostAcceptsActivityBinding() {
        assertTrue(DesktopDisplayCoordinator().bindActivity(Activity()))
    }

    @Test
    fun hostAcceptanceIsForwarded() {
        val coordinator = DesktopDisplayCoordinator(host = BindingHost(accepted = true))

        assertTrue(coordinator.bindActivity(Activity()))
    }

    @Test
    fun hostRejectionReturnsBeforeDisplayLocalOrientationWork() {
        val coordinator = DesktopDisplayCoordinator(
            host = BindingHost(accepted = false),
            environmentPolicy = DisplayEnvironmentPolicy.forDevice("darwin"),
        )

        assertFalse(coordinator.bindActivity(Activity()))
    }
}

private class BindingHost(
    private val accepted: Boolean,
) : DesktopDisplayHost {
    override fun bindActivity(activity: Activity): Boolean = accepted
    override fun displays() = emptyList<DesktopDisplay>()
    override fun defaultProfileKind() = ProfileKind.PHONE
    override fun sessionRunning() = false
    override fun launch(request: DesktopLaunchRequest) = Unit
    override fun focus(requestId: Long) = false
    override fun cancelLaunch(requestId: Long) = Unit
    override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}
    override fun notifyReturnRequired(error: DesktopDisplayException) = Unit
}
