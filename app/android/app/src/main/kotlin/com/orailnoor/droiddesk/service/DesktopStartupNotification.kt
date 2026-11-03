package com.orailnoor.droiddesk.service

import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DisplayEnvironmentPolicy

internal class DesktopStartupNotification(
    private val coordinator: DesktopDisplayCoordinator,
    notify: (String) -> Unit,
) {
    private var lastMessage: String? = null
    private var messageEpoch = 0L
    val text: String
        get() = when {
            coordinator.isRestarting -> "Restarting Linux/XFCE on this display"
            coordinator.isDisplayLocal && coordinator.sessionFailure != null ->
                "Linux desktop needs attention: ${coordinator.sessionFailure?.message}"
            coordinator.isStarting -> "Linux desktop is starting"
            coordinator.isStopped -> "Linux desktop is stopped"
            coordinator.isDisplayLocal && coordinator.hasRunningSession -> DisplayEnvironmentPolicy.RESTART_WARNING
            coordinator.isDisplayLocal -> "Linux desktop is not running"
            messageEpoch == coordinator.sessionGeneration -> checkNotNull(lastMessage)
            else -> "Linux desktop is running"
        }

    private var lastNotified: String? = null
    private val emit = {
        val message = text
        if (message != lastNotified) {
            lastNotified = message
            notify(message)
        }
    }
    private val unsubscribeState = coordinator.subscribe { emit() }
    private val unsubscribe = coordinator.subscribeSessionStart { result ->
        lastMessage = result.fold(
            onSuccess = { "Linux desktop is running" },
            onFailure = { "Linux desktop failed to start: ${it.message ?: "Check the session log"}" },
        )
        messageEpoch = coordinator.sessionGeneration
        emit()
    }

    fun close() {
        unsubscribe()
        unsubscribeState()
    }
}
