package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Shared desktop conveniences that are independent of the active Linux mode. */
class DesktopIntegration private constructor(
    private val context: Context?,
    private val snapshotsDir: File,
) {
    companion object {
        private const val TAG = "DesktopIntegration"
    }

    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, "snapshots"),
    )

    internal constructor(snapshotsDir: File) : this(null, snapshotsDir)

    init {
        snapshotsDir.mkdirs()
    }

    fun search(
        query: String,
        paths: EnvironmentPaths,
        limit: Int = 80,
    ): List<Map<String, String>> {
        val needle = query.trim().lowercase()
        val results = mutableListOf<Map<String, String>>()
        AndroidAppBridge.listApps(checkNotNull(context)).forEach { app ->
            if (needle.isEmpty() || app.values.any { it.lowercase().contains(needle) }) {
                results += app + mapOf("kind" to "android", "id" to app.getValue("package"))
            }
        }

        paths.applicationDirectories.filter(File::isDirectory).flatMap { directory ->
            directory.walkTopDown().maxDepth(2).filter { it.isFile && it.extension == "desktop" }.toList()
        }.filterNot { desktopFile ->
            desktopFile.parentFile?.name in setOf("droiddesk-android", "droiddesk-tools")
        }.distinctBy { it.name }.forEach { desktopFile ->
            val fields = desktopFields(desktopFile)
            if (fields["NoDisplay"].equals("true", ignoreCase = true)) return@forEach
            val name = fields["Name"] ?: return@forEach
            val haystack = "$name ${fields["Comment"].orEmpty()} ${fields["Keywords"].orEmpty()}".lowercase()
            if (needle.isEmpty() || haystack.contains(needle)) {
                results += mapOf(
                    "label" to name,
                    "subtitle" to fields["Comment"].orEmpty(),
                    "source" to "Linux",
                    "kind" to "linux",
                    "id" to desktopFile.nameWithoutExtension,
                )
            }
        }

        val shortcuts = listOf(
            mapOf("label" to "Home folder", "subtitle" to "Your Linux files", "source" to "Files", "kind" to "folder", "id" to "home"),
            mapOf("label" to "Downloads", "subtitle" to "Downloaded files", "source" to "Files", "kind" to "folder", "id" to "Downloads"),
            mapOf("label" to "Documents", "subtitle" to "Documents folder", "source" to "Files", "kind" to "folder", "id" to "Documents"),
            mapOf("label" to "Pictures", "subtitle" to "Pictures folder", "source" to "Files", "kind" to "folder", "id" to "Pictures"),
            mapOf("label" to "Wi-Fi settings", "subtitle" to "Manage Android networks", "source" to "Settings", "kind" to "setting", "id" to "wifi"),
            mapOf("label" to "Bluetooth settings", "subtitle" to "Manage Android devices", "source" to "Settings", "kind" to "setting", "id" to "bluetooth"),
            mapOf("label" to "Display settings", "subtitle" to "Brightness and screen", "source" to "Settings", "kind" to "setting", "id" to "display"),
            mapOf("label" to "Sound settings", "subtitle" to "Volume and audio", "source" to "Settings", "kind" to "setting", "id" to "sound"),
            mapOf("label" to "Hotspot settings", "subtitle" to "Share mobile internet", "source" to "Settings", "kind" to "setting", "id" to "hotspot"),
        )
        results += shortcuts.filter { item ->
            needle.isEmpty() || item.values.any { it.lowercase().contains(needle) }
        }
        return results.distinctBy { "${it["kind"]}:${it["id"]}" }
            .sortedWith(compareBy<Map<String, String>> { it["label"]?.lowercase() }.thenBy { it["source"] })
            .take(limit)
    }

    fun createSnapshot(paths: EnvironmentPaths): Map<String, Any> {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val output = File(snapshotsDir, "DroidDesk-$stamp.zip")
        val home = paths.home
        val homeBindSources = validatedHomeBindSources(paths)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            zip.putNextEntry(ZipEntry("snapshot.properties"))
            zip.write(
                "version=2\nmode=${paths.snapshotMode}\ncreated=${System.currentTimeMillis()}\n"
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("packages.txt"))
            zip.write(installedPackages(paths).joinToString("\n", postfix = "\n").toByteArray())
            zip.closeEntry()
            if (home.isDirectory) {
                addDirectory(zip, home, "home", homeBindSources.keys)
            }
            homeBindSources.forEach { (name, source) ->
                if (source.isDirectory) addDirectory(zip, source, "home/$name")
            }
        }
        return snapshotInfo(output)
    }

    fun listSnapshots(): List<Map<String, Any>> = snapshotsDir.listFiles { file -> file.extension == "zip" }
        ?.sortedByDescending(File::lastModified)
        ?.map(::snapshotInfo)
        .orEmpty()

    fun restoreSnapshot(name: String, paths: EnvironmentPaths): List<String> {
        val archive = safeSnapshot(name)
        val home = paths.home
        val homeBindSources = validatedHomeBindSources(paths)
        val metadata = readSnapshotMetadata(archive)
        require(metadata.mode == paths.snapshotMode) {
            "This snapshot belongs to a different Linux runtime mode"
        }
        val restoredHomeBindSources =
            if (metadata.version >= 2) homeBindSources else emptyMap()
        val restoreRoots = (
            listOf(home) + restoredHomeBindSources.values
            ).distinctBy { it.canonicalPath }
            .map { root -> RestoreRoot(root, File(root.parentFile, "${root.name}.before-restore")) }
        val preparedRoots = mutableListOf<RestoreRoot>()
        val packages = mutableListOf<String>()
        try {
            restoreRoots.forEach { restoreRoot ->
                if (restoreRoot.rollback.exists()) restoreRoot.rollback.deleteRecursively()
                if (restoreRoot.root.exists() && !restoreRoot.root.renameTo(restoreRoot.rollback)) {
                    error("Could not preserve ${restoreRoot.root.absolutePath}")
                }
                restoreRoot.root.mkdirs()
                preparedRoots += restoreRoot
            }
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "snapshot.properties" -> zip.readBytes()
                        entry.name == "packages.txt" -> {
                            packages += zip.readBytes().toString(Charsets.UTF_8)
                                .lineSequence().filter(::safePackageName)
                        }
                        entry.name.startsWith("home/") -> {
                            val relative = entry.name.removePrefix("home/")
                            if (relative.isNotEmpty()) {
                                val target = restoreTarget(
                                    relative,
                                    home,
                                    restoredHomeBindSources,
                                )
                                if (entry.isDirectory) {
                                    target.mkdirs()
                                } else {
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { zip.copyTo(it) }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            preparedRoots.forEach { it.rollback.deleteRecursively() }
            return packages.distinct()
        } catch (error: Throwable) {
            preparedRoots.asReversed().forEach { restoreRoot ->
                restoreRoot.root.deleteRecursively()
                if (restoreRoot.rollback.exists()) {
                    restoreRoot.rollback.renameTo(restoreRoot.root)
                }
            }
            throw error
        }
    }

    fun deleteSnapshot(name: String): Boolean = safeSnapshot(name).delete()

    fun missingPackages(packages: List<String>, paths: EnvironmentPaths): List<String> {
        val installed = installedPackages(paths).toSet()
        return packages.filter(::safePackageName).distinct().filterNot(installed::contains)
    }

    private fun installedPackages(paths: EnvironmentPaths): List<String> {
        val status = paths.packageStatusFiles.firstOrNull(File::isFile) ?: return emptyList()
        if (!status.isFile) return emptyList()
        return status.readText().split("\n\n").mapNotNull { block ->
            val fields = block.lineSequence().mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1).trim()
            }.toMap()
            fields["Package"]?.takeIf { fields["Status"] == "install ok installed" && safePackageName(it) }
        }.sorted()
    }

    private fun addDirectory(
        zip: ZipOutputStream,
        root: File,
        prefix: String,
        excludedTopLevel: Set<String> = emptySet(),
    ) {
        root.walkTopDown().onEnter { directory ->
            directory == root ||
                directory.relativeTo(root).path.substringBefore(File.separator) !in excludedTopLevel
        }.forEach { file ->
            if (file == root || file.isSymbolicLink()) return@forEach
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (relative.substringBefore('/') in excludedTopLevel) return@forEach
            val name = "$prefix/$relative${if (file.isDirectory) "/" else ""}"
            var entryOpen = false
            runCatching {
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                entryOpen = true
                if (file.isFile) FileInputStream(file).use { it.copyTo(zip) }
            }.onFailure { Log.w(TAG, "Skipping snapshot entry $relative", it) }
                .also { if (entryOpen) runCatching { zip.closeEntry() } }
        }
    }

    private fun validatedHomeBindSources(paths: EnvironmentPaths): Map<String, File> =
        paths.homeBindSources.onEach { (name, source) ->
            require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..") {
                "Invalid home bind path: $name"
            }
            require(source.canonicalPath != paths.home.canonicalPath) {
                "A home bind source cannot replace the entire Linux home"
            }
        }

    private fun restoreTarget(
        relative: String,
        home: File,
        homeBindSources: Map<String, File>,
    ): File {
        val normalized = relative.replace('\\', '/')
        val topLevel = normalized.substringBefore('/')
        val bindSource = homeBindSources[topLevel]
        val root = bindSource ?: home
        val nested = if (bindSource == null) normalized else normalized.substringAfter('/', "")
        val target = if (nested.isEmpty()) root else File(root, nested)
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        check(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Snapshot entry escapes its Linux home root"
        }
        return target
    }

    private fun readSnapshotMetadata(archive: File): SnapshotMetadata {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "snapshot.properties") {
                    val properties = zip.readBytes().toString(Charsets.UTF_8)
                        .lineSequence()
                        .mapNotNull { line ->
                            val split = line.indexOf('=')
                            if (split <= 0) null
                            else line.substring(0, split) to line.substring(split + 1)
                        }
                        .toMap()
                    val version = properties["version"]?.toIntOrNull()
                        ?: error("Snapshot version is missing")
                    val mode = properties["mode"]
                        ?: error("Snapshot runtime mode is missing")
                    return SnapshotMetadata(version, mode)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        error("Snapshot metadata is missing")
    }

    private data class RestoreRoot(val root: File, val rollback: File)
    private data class SnapshotMetadata(val version: Int, val mode: String)

    private fun File.isSymbolicLink(): Boolean = runCatching {
        canonicalFile != absoluteFile
    }.getOrDefault(true)

    private fun desktopFields(file: File): Map<String, String> = runCatching {
        file.useLines { lines ->
            lines.takeWhile { !it.startsWith("[Desktop Action") }.mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun snapshotInfo(file: File): Map<String, Any> = mapOf(
        "name" to file.name,
        "sizeBytes" to file.length(),
        "created" to file.lastModified(),
    )

    private fun safeSnapshot(name: String): File {
        require(name.matches(Regex("DroidDesk-[0-9]{8}-[0-9]{6}\\.zip")))
        return File(snapshotsDir, name).also { require(it.isFile) }
    }

    private fun safePackageName(value: String): Boolean =
        value.matches(Regex("[a-z0-9][a-z0-9+.-]{0,127}"))
}
