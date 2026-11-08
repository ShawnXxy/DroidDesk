package com.orailnoor.droiddesk.display

enum class DisplayEnvironment(val wireName: String) {
    PHONE("phone"), TNT("tnt"), OTHER_EXTERNAL("otherExternal"),
}

data class DesktopStartupTarget(
    val displayId: Int,
    val displayKey: String,
    val environment: DisplayEnvironment,
)
