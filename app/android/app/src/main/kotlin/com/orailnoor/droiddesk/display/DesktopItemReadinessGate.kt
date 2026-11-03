package com.orailnoor.droiddesk.display

internal class DesktopItemReadinessGate(
    private val isSessionCurrent: () -> Boolean,
    private val waitUntilReady: () -> Boolean,
) {
    fun await(admitCommand: () -> Unit): Result<Unit> {
        if (!isSessionCurrent()) return Result.failure(stopped())
        val ready = try {
            waitUntilReady()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.failure(error)
        } catch (error: Exception) {
            return Result.failure(error)
        }
        if (!ready) {
            return Result.failure(DesktopDisplayException(
                "DESKTOP_NOT_READY", "The Linux desktop did not become ready",
            ))
        }
        if (!isSessionCurrent()) return Result.failure(stopped())
        admitCommand()
        return Result.success(Unit)
    }

    private fun stopped() = DesktopDisplayException(
        "SESSION_STOPPED", "Desktop launch was cancelled",
    )
}
