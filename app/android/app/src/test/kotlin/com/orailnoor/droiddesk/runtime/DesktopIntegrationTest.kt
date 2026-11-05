package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopIntegrationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun prootSnapshotIncludesAndRestoresBoundHomeDirectories() {
        val snapshots = temporary.newFolder("snapshots")
        val rootfsHome = temporary.newFolder("rootfs-home")
        val sharedHome = temporary.newFolder("shared-home")
        val downloads = File(sharedHome, "Downloads").apply { mkdirs() }
        val documents = File(sharedHome, "Documents").apply { mkdirs() }
        val pictures = File(sharedHome, "Pictures").apply { mkdirs() }
        File(rootfsHome, "notes.txt").writeText("rootfs")
        File(rootfsHome, "Downloads/stale-placeholder.txt").apply {
            parentFile!!.mkdirs()
            writeText("stale")
        }
        File(downloads, "archive.zip").writeText("download")
        File(documents, "draft.txt").writeText("document")
        File(pictures, "photo.jpg").writeText("picture")
        val paths = EnvironmentPaths(
            home = rootfsHome,
            applicationDirectories = emptyList(),
            packageStatusFiles = emptyList(),
            snapshotMode = "proot-debian",
            homeBindSources = mapOf(
                "Downloads" to downloads,
                "Documents" to documents,
                "Pictures" to pictures,
            ),
        )
        val integration = DesktopIntegration(snapshots)
        val snapshot = integration.createSnapshot(paths)

        File(rootfsHome, "notes.txt").writeText("changed")
        File(downloads, "archive.zip").delete()
        File(downloads, "new.txt").writeText("remove me")

        val packages = integration.restoreSnapshot(snapshot.getValue("name") as String, paths)

        assertTrue(packages.isEmpty())
        assertEquals("rootfs", File(rootfsHome, "notes.txt").readText())
        assertEquals("download", File(downloads, "archive.zip").readText())
        assertEquals("document", File(documents, "draft.txt").readText())
        assertEquals("picture", File(pictures, "photo.jpg").readText())
        assertFalse(File(downloads, "new.txt").exists())
        assertFalse(File(downloads, "stale-placeholder.txt").exists())
    }

    @Test
    fun legacyProotSnapshotDoesNotErasePreviouslyExcludedBindSources() {
        val snapshots = temporary.newFolder("legacy-snapshots")
        val rootfsHome = temporary.newFolder("legacy-rootfs-home")
        val downloads = temporary.newFolder("legacy-downloads")
        File(downloads, "keep.txt").writeText("preserve")
        val archive = File(snapshots, "DroidDesk-20261105-120000.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("snapshot.properties"))
            zip.write("version=1\nmode=proot-debian\ncreated=1\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("packages.txt"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("home/notes.txt"))
            zip.write("legacy".toByteArray())
            zip.closeEntry()
        }
        val paths = EnvironmentPaths(
            home = rootfsHome,
            applicationDirectories = emptyList(),
            packageStatusFiles = emptyList(),
            snapshotMode = "proot-debian",
            homeBindSources = mapOf("Downloads" to downloads),
        )

        DesktopIntegration(snapshots).restoreSnapshot(archive.name, paths)

        assertEquals("legacy", File(rootfsHome, "notes.txt").readText())
        assertEquals("preserve", File(downloads, "keep.txt").readText())
    }
}
