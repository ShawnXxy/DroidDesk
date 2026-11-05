package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxRuntimeAudioTest {

    @Test
    fun android11PrefersSystemLibrariesForHostAudioModules() {
        assertEquals(
            "/system/lib64:/data/user/0/com.example/files/usr/lib",
            hostPulseAudioLibraryPath(
                sdkInt = 30,
                prefixLibraryPath = "/data/user/0/com.example/files/usr/lib",
            ),
        )
    }

    @Test
    fun android12AndNewerKeepTheRelocatedPrefixLibraryPath() {
        assertEquals(
            "/data/user/0/com.example/files/usr/lib",
            hostPulseAudioLibraryPath(
                sdkInt = 31,
                prefixLibraryPath = "/data/user/0/com.example/files/usr/lib",
            ),
        )
    }
}
