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
        synchronized(lock) {
            if (selectionTransitionActive || packageOperationActive) {
                throw EnvironmentSelectionBusyException()
            }
            selectionTransitionActive = true
        }
        return try {
            action()
        } finally {
            synchronized(lock) {
                selectionTransitionActive = false
            }
        }
    }
}
