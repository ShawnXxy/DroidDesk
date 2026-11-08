package com.orailnoor.droiddesk.runtime

internal class EnvironmentTargetChangedException(
    val currentKind: EnvironmentKind,
) : IllegalStateException("Active Linux environment changed to ${currentKind.wireValue}")

internal class EnvironmentSelectionBusyException :
    IllegalStateException("A Linux environment transition or package operation is already running")

internal class EnvironmentSelectionGate(
    private val activeKind: () -> EnvironmentKind,
) {
    private val lock = Any()
    private var packageOperationActive = false
    private var selectionTransitionActive = false

    fun <T> withActivePackageTarget(
        expectedKind: EnvironmentKind,
        action: () -> T,
    ): T {
        synchronized(lock) {
            if (selectionTransitionActive || packageOperationActive) {
                throw EnvironmentSelectionBusyException()
            }
            val currentKind = activeKind()
            if (currentKind != expectedKind) {
                throw EnvironmentTargetChangedException(currentKind)
            }
            packageOperationActive = true
        }
        return try {
            action()
        } finally {
            synchronized(lock) {
                packageOperationActive = false
            }
        }
    }

    fun <T> withSelectionTransition(action: () -> T): T {
        val admission = acquireSelectionTransition()
        return try {
            action()
        } finally {
            admission.close()
        }
    }

    fun acquireSelectionTransition(): AutoCloseable {
        synchronized(lock) {
            if (selectionTransitionActive || packageOperationActive) {
                throw EnvironmentSelectionBusyException()
            }
            selectionTransitionActive = true
        }
        var closed = false
        return AutoCloseable {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    selectionTransitionActive = false
                }
            }
        }
    }
}
