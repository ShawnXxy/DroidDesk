package com.orailnoor.droiddesk.display

interface DisplayLocalSessionHost<T> {
    fun sessionEpoch(): Long
    fun isSessionCurrent(epoch: Long): Boolean
    fun sameRequest(first: T, second: T): Boolean = first == second
    fun validateTarget(target: T)
    fun restartingChanged(restarting: Boolean)
    fun stopForEnvironmentChange(completion: (Result<Unit>) -> Unit)
    fun startOnTarget(target: T, completion: (Result<Unit>) -> Unit)
}

class DisplayLocalSessionController<T>(
    private val host: DisplayLocalSessionHost<T>,
    private val assertMainThread: () -> Unit = {},
) {
    private class Request<T>(
        val target: T,
        val epoch: Long,
        completion: (Result<Unit>) -> Unit,
    ) {
        val completions = mutableListOf(completion)
        fun complete(result: Result<Unit>) {
            val callbacks = completions.toList()
            completions.clear()
            callbacks.forEach { it(result) }
        }
    }

    private class Operation<T>(var request: Request<T>) {
        var starting = false
        var next: Request<T>? = null
    }

    private var operation: Operation<T>? = null
    val hasPendingRestart: Boolean get() = operation != null

    fun requestRestart(
        target: T,
        expectedEpoch: Long,
        completion: (Result<Unit>) -> Unit,
    ) {
        assertMainThread()
        val active = operation
        if (!host.isSessionCurrent(expectedEpoch) &&
            !(active != null && !active.starting &&
                (active.request.epoch == expectedEpoch || host.sessionEpoch() == expectedEpoch))) {
            completion(Result.failure(cancelled("The environment change belongs to a stopped session")))
            return
        }
        try {
            host.validateTarget(target)
        } catch (error: DesktopDisplayException) {
            completion(Result.failure(error))
            return
        }
        if (active?.starting == true && host.sameRequest(active.request.target, target)) {
            val superseded = active.next
            active.next = null
            active.request.completions += completion
            superseded?.complete(Result.failure(cancelled(
                "A newer display environment superseded this restart",
            )))
            return
        }
        val latest = active?.next ?: active?.request
        if (latest != null && host.sameRequest(latest.target, target)) {
            latest.completions += completion
            return
        }
        val request = Request(target, expectedEpoch, completion)
        if (active == null) {
            val admitted = Operation(request)
            operation = admitted
            host.restartingChanged(true)
            if (operation === admitted) stop(admitted)
        } else {
            if (active.starting) active.next = request else active.request = request
            latest?.complete(Result.failure(cancelled("A newer display environment superseded this restart")))
        }
    }

    fun cancelPendingRestart() {
        assertMainThread()
        val active = operation ?: return
        operation = null
        host.restartingChanged(false)
        val result = Result.failure<Unit>(cancelled("The Linux session was stopped"))
        active.request.complete(result)
        active.next?.complete(result)
    }

    private fun stop(active: Operation<T>) {
        var completed = false
        host.stopForEnvironmentChange { result ->
            assertMainThread()
            if (operation !== active || completed) return@stopForEnvironmentChange
            completed = true
            if (result.isFailure) {
                finish(active, result)
                return@stopForEnvironmentChange
            }
            try {
                host.validateTarget(active.request.target)
            } catch (error: DesktopDisplayException) {
                finish(active, Result.failure(error))
                return@stopForEnvironmentChange
            }
            active.starting = true
            var startCompleted = false
            host.startOnTarget(active.request.target) { started ->
                assertMainThread()
                if (operation !== active || startCompleted) return@startOnTarget
                startCompleted = true
                active.starting = false
                val next = active.next
                if (next != null) {
                    active.next = null
                    active.request = next
                    stop(active)
                } else {
                    finish(active, started)
                }
            }
        }
    }

    private fun finish(active: Operation<T>, result: Result<Unit>) {
        if (operation !== active) return
        operation = null
        host.restartingChanged(false)
        active.request.complete(result)
        active.next?.complete(result)
    }

    private fun cancelled(message: String) = DesktopDisplayException("SESSION_STOPPED", message)
}
