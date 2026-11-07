package com.orailnoor.droiddesk.runtime

internal enum class CommandInputState {
    READY,
    STARTING,
    NOT_INTERACTIVE,
    ABSENT,
}

internal data class CommandInput<T>(
    val state: CommandInputState,
    val process: T? = null,
)

internal data class CommandCancellation<T>(
    val owned: Boolean,
    val process: T?,
)

internal class CommandOwnership<T> {
    internal class Lease internal constructor(internal val token: Any)

    private data class Owner<T>(
        val token: Any,
        val acceptsInput: Boolean,
        var commandActive: Boolean,
        var commandCancelled: Boolean = false,
        var process: T? = null,
    )

    private val lock = Any()
    private var owner: Owner<T>? = null

    fun reserve(acceptsInput: Boolean): Lease? = synchronized(lock) {
        if (owner != null) return@synchronized null
        val token = Any()
        owner = Owner(token, acceptsInput, commandActive = true)
        Lease(token)
    }

    fun reserveLane(acceptsInput: Boolean): Lease? = synchronized(lock) {
        if (owner != null) return@synchronized null
        val token = Any()
        owner = Owner(token, acceptsInput, commandActive = false)
        Lease(token)
    }

    fun beginCommand(lease: Lease): Boolean = synchronized(lock) {
        val current = owner
        if (current?.token !== lease.token || current.commandActive) {
            return@synchronized false
        }
        current.commandActive = true
        current.commandCancelled = false
        true
    }

    fun attach(lease: Lease, process: T): Boolean = synchronized(lock) {
        val current = owner
        if (
            current?.token !== lease.token ||
            !current.commandActive ||
            current.commandCancelled ||
            current.process != null
        ) {
            return@synchronized false
        }
        current.process = process
        true
    }

    fun finishCommand(lease: Lease) {
        synchronized(lock) {
            val current = owner
            if (current?.token !== lease.token) return
            current.process = null
            current.commandActive = false
            current.commandCancelled = false
        }
    }

    fun input(): CommandInput<T> = synchronized(lock) {
        val current = owner ?: return@synchronized CommandInput(CommandInputState.ABSENT)
        if (!current.acceptsInput) {
            return@synchronized CommandInput(CommandInputState.NOT_INTERACTIVE)
        }
        val process = current.process
            ?: return@synchronized CommandInput(CommandInputState.STARTING)
        CommandInput(CommandInputState.READY, process)
    }

    fun release(lease: Lease) {
        synchronized(lock) {
            if (owner?.token === lease.token) owner = null
        }
    }

    fun cancel(): T? = synchronized(lock) {
        owner?.process.also { owner = null }
    }

    fun cancelProcess(lease: Lease): CommandCancellation<T> = synchronized(lock) {
        val current = owner
        if (current?.token !== lease.token || !current.commandActive) {
            return@synchronized CommandCancellation(owned = false, process = null)
        }
        current.commandCancelled = true
        CommandCancellation(
            owned = true,
            process = current.process.also { current.process = null },
        )
    }

    fun isBusy(): Boolean = synchronized(lock) { owner != null }
}
