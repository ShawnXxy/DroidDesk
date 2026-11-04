package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotCommandBuilderTest {

    @Test
    fun loginIncludesRelocatedPrefixSharedTmpFoldersAndGuiUser() {
        val root = Files.createTempDirectory("droid desk").toFile()
        val files = File(root, "files").apply { mkdirs() }
        val prefix = File(files, "usr").apply { mkdirs() }
        val home = File(files, "home").apply { mkdirs() }
        val tmp = File(files, "tmp").apply { mkdirs() }
        val downloads = File(home, "Downloads").apply { mkdirs() }
        val documents = File(home, "Documents").apply { mkdirs() }
        val pictures = File(home, "Pictures").apply { mkdirs() }
        val builder = ProotCommandBuilder(
            prootDistro = File(prefix, "bin/proot-distro"),
            prefixDir = prefix,
            hostHome = home,
            tmpDir = tmp,
            packageName = "com.orailnoor.droiddesk",
            approvedHostRoots = listOf(files),
        )

        val command = builder.login(
            container = "droiddesk-debian",
            arguments = listOf("dbus-run-session", "--", "startxfce4"),
            environment = mapOf("DISPLAY" to ":0", "PULSE_SERVER" to "127.0.0.1"),
            binds = listOf(
                ProotBind(downloads, "/home/droiddesk/Downloads"),
                ProotBind(documents, "/home/droiddesk/Documents"),
                ProotBind(pictures, "/home/droiddesk/Pictures"),
            ),
        )

        assertEquals("env", command.first())
        assertTrue(command.contains("TERMUX__PREFIX=${prefix.canonicalPath}"))
        assertTrue(command.contains("TERMUX__HOME=${home.canonicalPath}"))
        assertTrue(command.contains("TERMUX_APP__PACKAGE_NAME=com.orailnoor.droiddesk"))
        assertTrue(command.contains("${tmp.canonicalPath}:/tmp"))
        assertTrue(command.contains("${downloads.canonicalPath}:/home/droiddesk/Downloads"))
        assertTrue(command.contains("${documents.canonicalPath}:/home/droiddesk/Documents"))
        assertTrue(command.contains("${pictures.canonicalPath}:/home/droiddesk/Pictures"))
        assertTrue(command.containsAll(listOf("--user", "droiddesk")))
        assertEquals(
            listOf("dbus-run-session", "--", "startxfce4"),
            command.takeLast(3),
        )
    }

    @Test
    fun pathsWithSpacesAndShellCharactersRemainSingleArguments() {
        val root = Files.createTempDirectory("approved root").toFile()
        val prefix = File(root, "usr").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { mkdirs() }
        val unusual = File(home, "Downloads; touch escaped").apply { mkdirs() }
        val builder = ProotCommandBuilder(
            File(prefix, "bin/proot-distro"),
            prefix,
            home,
            tmp,
            "com.example.app",
            listOf(root),
        )

        val command = builder.login(
            container = "debian",
            arguments = listOf("printf", "%s", "hello world; rm -rf /"),
            environment = emptyMap(),
            binds = listOf(ProotBind(unusual, "/home/droiddesk/Downloads")),
        )

        assertTrue(command.contains("${unusual.canonicalPath}:/home/droiddesk/Downloads"))
        assertEquals("hello world; rm -rf /", command.last())
    }

    @Test
    fun bindOutsideApprovedRootsIsRejected() {
        val root = Files.createTempDirectory("approved").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        val prefix = File(root, "usr").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { mkdirs() }
        val builder = ProotCommandBuilder(
            File(prefix, "bin/proot-distro"),
            prefix,
            home,
            tmp,
            "com.example.app",
            listOf(root),
        )

        try {
            builder.login(
                container = "debian",
                arguments = listOf("true"),
                environment = emptyMap(),
                binds = listOf(ProotBind(outside, "/mnt/outside")),
            )
            throw AssertionError("Expected an unsafe bind to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains(outside.canonicalPath))
        }
    }
}
