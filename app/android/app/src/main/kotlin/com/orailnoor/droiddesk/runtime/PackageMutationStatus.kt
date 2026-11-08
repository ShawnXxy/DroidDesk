package com.orailnoor.droiddesk.runtime

internal data class PackageMutationStatus(
    val succeeded: Boolean,
    val repairFailed: Boolean = false,
)
