package com.orailnoor.droiddesk.service

import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator

internal fun desktopStartupNotificationText(
    snapshot: Map<String, Any?>,
    allowsManualMoves: Boolean = snapshot["crossDisplayMovesAllowed"] == true,
    restarting: Boolean = snapshot["phase"] == "restarting",
    starting: Boolean = snapshot["phase"] == "starting",
    stopped: Boolean = snapshot["phase"] == "stopped",
    errorMessage: String? = (snapshot["error"] as? Map<*, *>)?.get("message") as? String,
    runningOverride: Boolean? = null,
    restartWarning: String? = null,
    lastMessage: String? = null,
    messageEpoch: Long = 0L,
    sessionGeneration: Long = -1L,
): String {
    val phase = snapshot["phase"] as? String
    val running = runningOverride ?: (snapshot["sessionRunning"] == true)
    return when {
        restarting || phase == "restarting" -> "Restarting Linux/XFCE on this display"
        !allowsManualMoves && errorMessage != null ->
            "Linux desktop needs attention: $errorMessage"
        starting || phase == "starting" -> "Linux desktop is starting"
        stopped || phase == "stopped" -> "Linux desktop is stopped"
        !allowsManualMoves && running -> restartWarning
            ?: if (phase == "attached") "Linux desktop is running"
            else "Linux desktop is running in the background"
        messageEpoch == sessionGeneration && lastMessage != null &&
            (allowsManualMoves || running || lastMessage != "Linux desktop is running") ->
            lastMessage
        !allowsManualMoves -> "Linux desktop is not running"
        else -> "Linux desktop is running"
    }
}

internal class DesktopStartupNotification(
    private val coordinator: DesktopDisplayCoordinator,
    private val notify: (String) -> Unit,
) {
    private var latestSnapshot: Map<String, Any?>? = null
    private var latestSnapshotEpoch = 0L
    private var lastMessage: String? = null
    private var messageEpoch = 0L

    private fun isSnapshotCurrent(snapshot: Map<String, Any?>): Boolean {
        val phase = snapshot["phase"] as? String
        val snapError = (snapshot["error"] as? Map<*, *>)?.get("message") as? String
        return snapshot["crossDisplayMovesAllowed"] == coordinator.allowsManualMoves &&
            snapshot["sessionRestarting"] == coordinator.isRestarting &&
            snapError == coordinator.sessionFailure?.message &&
            (phase == "stopped") == (coordinator.isStopped && !coordinator.isRestarting && coordinator.sessionFailure == null) &&
            (phase == "starting") == (!coordinator.allowsManualMoves && coordinator.isStarting && !coordinator.isRestarting && coordinator.sessionFailure == null)
    }

    private fun textFor(runningOverride: Boolean? = null): String {
        val allowsManualMoves = coordinator.allowsManualMoves
        val restarting = coordinator.isRestarting
        val starting = coordinator.isStarting
        val stopped = coordinator.isStopped
        val errorMessage = coordinator.sessionFailure?.message
        val reusable = latestSnapshot?.takeIf {
            latestSnapshotEpoch == coordinator.sessionGeneration && isSnapshotCurrent(it)
        }
        val needsRunning = !allowsManualMoves && !restarting && errorMessage == null && !starting && !stopped
        val running = when {
            !needsRunning -> false
            runningOverride != null -> runningOverride
            reusable != null -> reusable["sessionRunning"] == true
            else -> coordinator.hasRunningSession
        }
        return desktopStartupNotificationText(
            snapshot = reusable ?: emptyMap(),
            allowsManualMoves = allowsManualMoves,
            restarting = restarting,
            starting = starting,
            stopped = stopped,
            errorMessage = errorMessage,
            runningOverride = running,
            restartWarning = coordinator.environmentRestartWarning,
            lastMessage = lastMessage,
            messageEpoch = messageEpoch,
            sessionGeneration = coordinator.sessionGeneration,
        )
    }

    val text: String get() = textFor()
    private var lastNotified: String? = null
    private fun emit(runningOverride: Boolean? = null) {
        val message = textFor(runningOverride)
        if (message != lastNotified) { lastNotified = message; notify(message) }
    }
    private val unsubscribeState = coordinator.subscribe { state ->
        if (isSnapshotCurrent(state)) { latestSnapshot = state; latestSnapshotEpoch = coordinator.sessionGeneration }
        emit()
    }
    private val unsubscribe = coordinator.subscribeSessionStart { result ->
        lastMessage = result.fold(
            onSuccess = { "Linux desktop is running" },
            onFailure = { "Linux desktop failed to start: ${it.message ?: "Check the session log"}" },
        )
        messageEpoch = coordinator.sessionGeneration
        emit(runningOverride = result.isSuccess)
    }

    fun close() { unsubscribe(); unsubscribeState() }
}
