package com.orailnoor.droiddesk.runtime

data class ProbeTimeouts(
    val localExecutionMs: Long = 1_000,
    val prootMs: Long = 2_000,
    val x11Ms: Long = 1_500,
    val audioMs: Long = 2_500,
    val clipboardMs: Long = 1_000,
    val chrootMs: Long = 5_000,
    val rendererMs: Long = 2_000,
)
