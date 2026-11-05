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

internal class CommandOwnership<T> {
    internal class Lease internal constructor(internal val token: Any)

    private data class Owner<T>(
        val token: Any,
        val acceptsInput: Boolean,
        var process: T? = null,
    )

    private val lock = Any()
    private var owner: Owner<T>? = null

    fun reserve(acceptsInput: Boolean): Lease? = synchronized(lock) {
        if (owner != null) return@synchronized null
        val token = Any()
        owner = Owner(token, acceptsInput)
        Lease(token)
    }

    fun attach(lease: Lease, process: T): Boolean = synchronized(lock) {
        val current = owner
        if (current?.token !== lease.token) return@synchronized false
        current.process = process
        true
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

    fun isBusy(): Boolean = synchronized(lock) { owner != null }
}
