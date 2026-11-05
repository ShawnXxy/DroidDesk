package com.orailnoor.droiddesk.x11

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class X11ServerEnvironmentTest {

    @Test
    fun prootXkbRootIsUsedWhenNativePackagesAreAbsent() {
        val filesDir = Files.createTempDirectory("droiddesk-xkb").toFile()
        val proot = File(
            filesDir,
            "usr/var/lib/proot-distro/containers/droiddesk-debian/rootfs/usr/share/X11/xkb",
        ).apply { mkdirs() }

        assertEquals(proot.canonicalFile, findXkbConfigRoot(filesDir)?.canonicalFile)
    }

    @Test
    fun nativeXkbRootKeepsPrecedence() {
        val filesDir = Files.createTempDirectory("droiddesk-xkb").toFile()
        val native = File(filesDir, "usr/share/X11/xkb").apply { mkdirs() }
        File(
            filesDir,
            "usr/var/lib/proot-distro/containers/droiddesk-debian/rootfs/usr/share/X11/xkb",
        ).mkdirs()

        assertEquals(native.canonicalFile, findXkbConfigRoot(filesDir)?.canonicalFile)
    }
}
