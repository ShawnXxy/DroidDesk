package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxRuntimePackageCommandTest {
    @Test
    fun nativePackageUpgradeIsBounded() {
        assertEquals(
            "timeout --signal=TERM --kill-after=5s 1800s " +
                "env DEBIAN_FRONTEND=noninteractive apt-get " +
                "-o Dpkg::Options::=--force-confdef " +
                "-o Dpkg::Options::=--force-confold " +
                "install --only-upgrade -y nodejs npm",
            nativePackageUpgradeCommand(listOf("nodejs", "npm")),
        )
    }
}
