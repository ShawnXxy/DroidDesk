package com.orailnoor.droiddesk.service

import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator

internal fun desktopStartupNotificationText(
    snapshot: Map<String, Any?>,
    allowsManualMoves: Boolean = snapshot["crossDisplayMovesAllowed"] == true,
    starting: Boolean = snapshot["phase"] == "starting",
    stopped: Boolean = snapshot["phase"] == "stopped",
    restartWarning: String? = null,
    lastMessage: String? = null,
    messageEpoch: Long = 0L,
    sessionGeneration: Long = -1L,
): String {
    val phase = snapshot["phase"] as? String
    val running = snapshot["sessionRunning"] == true
    val errorMessage = (snapshot["error"] as? Map<*, *>)?.get("message") as? String
    return when {
        phase == "restarting" -> "Restarting Linux/XFCE on this display"
        !allowsManualMoves && errorMessage != null ->
            "Linux desktop needs attention: $errorMessage"
        starting || phase == "starting" -> "Linux desktop is starting"
        stopped || phase == "stopped" -> "Linux desktop is stopped"
        !allowsManualMoves && running && phase == "attached" -> "Linux desktop is running"
        !allowsManualMoves && running ->
            restartWarning ?: "Linux desktop is running in the background"
        messageEpoch == sessionGeneration && lastMessage != null &&
            (allowsManualMoves || running || lastMessage != "Linux desktop is running") ->
            lastMessage
        !allowsManualMoves -> "Linux desktop is not running"
        else -> "Linux desktop is running"
    }
}

internal class DesktopStartupNotification(
    private val coordinator: DesktopDisplayCoordinator,
    notify: (String) -> Unit,
) {
    private var lastMessage: String? = null
    private var messageEpoch = 0L
    val text: String
        get() = desktopStartupNotificationText(
            snapshot = coordinator.snapshot(),
            allowsManualMoves = coordinator.allowsManualMoves,
            starting = coordinator.isStarting,
            stopped = coordinator.isStopped,
            restartWarning = coordinator.environmentRestartWarning,
            lastMessage = lastMessage,
            messageEpoch = messageEpoch,
            sessionGeneration = coordinator.sessionGeneration,
        )

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
