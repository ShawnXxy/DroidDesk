package com.orailnoor.droiddesk.display.placement

import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class DisplayConnectionIdentity(
    val displayId: Int,
    val connectionIdentity: String,
    val generation: Long,
    val connectionKey: String,
)

internal class DisplayConnectionIdentityPolicy {
    private val generations = mutableMapOf<Int, Long>()

    fun resolve(
        displayId: Int,
        platformDescription: String,
        fallbackIdentity: String,
    ): DisplayConnectionIdentity {
        val fieldStart = platformDescription.lastIndexOf(UNIQUE_ID_FIELD_NAME)
        val identityField = platformDescription
            .takeIf { fieldStart >= 0 }
            ?.substring(fieldStart)
        val platformIdentity = identityField
            ?.let(UNIQUE_ID_FIELD_PATTERN::find)
            ?.takeIf { it.range.first == 0 }
            ?.groupValues
            ?.get(1)
            ?.takeUnless { it.isBlank() || it == NULL_IDENTITY }
        val connectionIdentity = platformIdentity ?: fallbackIdentity
        val generation = generations[displayId] ?: 0L
        val identityBytes =
            "$displayId\u0000$generation\u0000$connectionIdentity"
                .toByteArray(StandardCharsets.UTF_8)
        val connectionKey = "bound:$displayId:${UUID.nameUUIDFromBytes(identityBytes)}"

        return DisplayConnectionIdentity(
            displayId = displayId,
            connectionIdentity = connectionIdentity,
            generation = generation,
            connectionKey = connectionKey,
        )
    }

    fun displayAdded(@Suppress("UNUSED_PARAMETER") displayId: Int) = Unit

    fun displayChanged(@Suppress("UNUSED_PARAMETER") displayId: Int) = Unit

    fun displayRemoved(displayId: Int) {
        generations[displayId] = (generations[displayId] ?: 0L) + 1L
    }

    private companion object {
        const val UNIQUE_ID_FIELD_NAME = ", uniqueId"
        const val NULL_IDENTITY = "null"
        val UNIQUE_ID_FIELD_PATTERN = Regex("^, uniqueId \"([^\"]*)\", app ")
    }
}
