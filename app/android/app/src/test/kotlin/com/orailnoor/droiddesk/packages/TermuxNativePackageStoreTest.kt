package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.ExecutionResult
import com.orailnoor.droiddesk.runtime.DirectorySizer
import com.orailnoor.droiddesk.runtime.PackageMutationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TermuxNativePackageStoreTest {

    @Test
    fun emptyCatalogReturnsCuratedFeaturedEntries() {
        val store = store()

        val entries = store.query(PackageStoreQuery.Catalog("")).getOrThrow().entries

        assertEquals(
            setOf(
                "app.firefox",
                "app.code-oss",
                "app.libreoffice",
                "app.gimp",
                "app.blender",
                "app.vlc",
                "tool.nodejs",
                "tool.python",
                "tool.imagemagick",
            ),
            entries.map(PackageStoreEntry::entryId).toSet(),
        )
        assertTrue(entries.all { it.supportLevel == PackageSupportLevel.SUPPORTED })
    }

    @Test
    fun searchReturnsValidatedRepositoryEntriesWithRepoIds() {
        val port = FakeTermuxPackagePort().apply {
            searchOutput = """
                git - Fast, scalable, distributed revision control system
                bad package - rejected because the name contains whitespace
            """.trimIndent()
        }
        val store = store(port = port)

        val entries = store.query(PackageStoreQuery.Catalog("git")).getOrThrow().entries

        assertEquals(1, entries.size)
        assertEquals("repo.git", entries.single().entryId)
        assertEquals(PackageSupportLevel.REPOSITORY, entries.single().supportLevel)
        assertEquals("Development", entries.single().category)
        assertEquals(listOf(true), port.upgradableRequests)
    }

    @Test
    fun searchReturnsTypedFailureWhenMissingIndexesCannotRefresh() {
        val port = FakeTermuxPackagePort().apply {
            upgradableResult = ExecutionResult(100, "mirror unavailable")
        }
        val store = store(port = port)

        val error = store.query(PackageStoreQuery.Catalog("tree")).exceptionOrNull()

        assertTrue(error is PackageStoreException)
        assertEquals("INDEX_REFRESH_FAILED", (error as PackageStoreException).code)
    }

    @Test
    fun installedUsesDpkgAsTruthAndHistoryAsVisibility() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("git" to "2.48.1"))
        }
        val history = history(
            EnvironmentKind.TERMUX_NATIVE to setOf("repo.git", "repo.missing"),
        )
        val store = store(port, history)

        val entries = store.query(PackageStoreQuery.Installed).getOrThrow().entries

        assertEquals(listOf("repo.git"), entries.map(PackageStoreEntry::entryId))
        assertEquals("2.48.1", entries.single().installedVersion)
    }

    @Test
    fun incompleteCuratedRecipeIsNotReportedAsInstalled() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("nodejs" to "24.0"))
        }
        val store = store(port)

        val catalog = store.query(PackageStoreQuery.Catalog("")).getOrThrow().entries
        val installed = store.query(PackageStoreQuery.Installed).getOrThrow().entries
        val node = catalog.single { it.entryId == "tool.nodejs" }

        assertEquals(PackageEntryState.UNAVAILABLE, node.state)
        assertTrue(node.limitations.any { it.contains("incomplete") })
        assertTrue(installed.none { it.entryId == "tool.nodejs" })
    }

    @Test
    fun installMapsCuratedFirefoxToExistingNativeSpecialHandling() {
        val port = FakeTermuxPackagePort().apply {
            onCuratedInstall = { legacyId ->
                if (legacyId == "firefox") setInstalled(mapOf("firefox" to "137.0"))
            }
        }
        val store = store(port)

        val result = store.execute(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertEquals(listOf("firefox"), port.curatedInstalls)
        assertEquals("137.0", result.entry!!.installedVersion)
    }

    @Test
    fun ordinaryFeaturedRecipeUsesGenericInstaller() {
        val port = FakeTermuxPackagePort()
        val store = store(port)

        val result = store.execute(
            operation(PackageOperationKind.INSTALL, "app.gimp"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertTrue(port.curatedInstalls.isEmpty())
        assertEquals(listOf(listOf("gimp")), port.installedPackageGroups)
    }

    @Test
    fun repositoryNodeAliasUsesCuratedInstaller() {
        val port = FakeTermuxPackagePort().apply {
            onCuratedInstall = { legacyId ->
                if (legacyId == "nodejs") {
                    setInstalled(mapOf("nodejs" to "24.0", "npm" to "11.0"))
                }
            }
        }
        val store = store(port)

        val result = store.execute(
            operation(PackageOperationKind.INSTALL, "repo.nodejs"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertEquals(listOf("nodejs"), port.curatedInstalls)
        assertTrue(port.installedPackageGroups.isEmpty())
    }

    @Test
    fun installRejectsProtectedRepositoryPackage() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("python" to "3.14"))
        }
        val store = store(port)

        val result = store.execute(
            operation(PackageOperationKind.INSTALL, "repo.python"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, result.outcome)
        assertEquals("REMOVE_PROTECTED", result.error!!.code)
        assertTrue(port.installedPackageGroups.isEmpty())
    }

    @Test
    fun removeRejectsProtectedRuntimeAndDesktopPackages() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("xfce4-panel" to "4.20"))
        }
        val store = store(port)

        val result = store.execute(
            operation(PackageOperationKind.REMOVE, "repo.xfce4-panel"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, result.outcome)
        assertEquals("REMOVE_PROTECTED", result.error!!.code)
        assertTrue(port.removedPackages.isEmpty())
    }

    @Test
    fun upgradeAllUsesOnlyTrackedAndCuratedNonProtectedEntries() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(
                mapOf(
                    "git" to "2.48.0",
                    "firefox" to "136.0",
                    "xfce4-panel" to "4.20",
                ),
            )
            upgradableOutput = """
                git/stable 2.48.1 aarch64 [upgradable from: 2.48.0]
                firefox/stable 137.0 aarch64 [upgradable from: 136.0]
                xfce4-panel/stable 4.20.1 aarch64 [upgradable from: 4.20]
            """.trimIndent()
            onUpgrade = {
                setInstalled(
                    mapOf(
                        "git" to "2.48.1",
                        "firefox" to "137.0",
                        "xfce4-panel" to "4.20",
                    ),
                )
                upgradableOutput = ""
            }
        }
        val history = history(
            EnvironmentKind.TERMUX_NATIVE to setOf(
                "repo.git",
                "repo.xfce4-panel",
            ),
        )
        val store = store(port, history)

        val result = store.execute(
            operation(PackageOperationKind.UPGRADE_ALL),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertEquals(listOf(listOf("firefox", "git")), port.upgradedPackages)
    }

    @Test
    fun updatesExcludeProtectedCuratedRecipes() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("python" to "3.14.0"))
            upgradableOutput =
                "python/stable 3.14.1 aarch64 [upgradable from: 3.14.0]"
        }
        val store = store(port)

        val entries = store.query(
            PackageStoreQuery.Updates(refreshIndex = true),
        ).getOrThrow().entries

        assertTrue(entries.none { it.entryId == "tool.python" })
    }

    @Test
    fun secondaryCuratedPackageUpdateIsAdvertisedAndVerified() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("nodejs" to "24.0", "npm" to "11.0"))
            upgradableOutput =
                "npm/stable 11.1 aarch64 [upgradable from: 11.0]"
            onUpgrade = {
                setInstalled(mapOf("nodejs" to "24.0", "npm" to "11.1"))
            }
        }
        val store = store(port)

        val update = store.query(
            PackageStoreQuery.Updates(refreshIndex = true),
        ).getOrThrow().entries.single()
        val result = store.execute(
            operation(PackageOperationKind.UPGRADE, "tool.nodejs"),
            observer = {},
        )

        assertEquals("tool.nodejs", update.entryId)
        assertEquals("11.1", update.availableVersion)
        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertEquals(listOf(listOf("nodejs", "npm")), port.upgradedPackages)
    }

    @Test
    fun storageAndCacheUseTheNativePrefix() {
        val port = FakeTermuxPackagePort()
        File(port.prefixDir, "bin/tool").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(11))
        }
        File(port.homeDir, "document").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(13))
        }
        File(port.prefixDir, "var/cache/apt/archives/package.deb").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(17))
        }
        File(port.prefixDir, "var/lib/apt/lists/index").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(19))
        }
        val store = store(port)

        val storage = store.query(PackageStoreQuery.Storage).getOrThrow().storage!!
        val cleaned = store.execute(
            operation(PackageOperationKind.CLEAN_CACHE),
            observer = {},
        )

        assertEquals(11, storage.systemBytes)
        assertEquals(13, storage.homeBytes)
        assertEquals(36, storage.cacheBytes)
        assertEquals(36L, cleaned.bytesFreed)
        assertEquals(0, store.query(PackageStoreQuery.Storage).getOrThrow().storage!!.cacheBytes)
    }

    @Test
    fun cancelRejectsAnOperationThatIsNotActive() {
        val port = FakeTermuxPackagePort()
        val store = store(port)

        assertFalse(store.cancel(44))
        assertEquals(0, port.cancelCalls)
    }

    @Test
    fun busyNativePackageManagerIsRejectedWithoutDeletingLocks() {
        val port = FakeTermuxPackagePort().apply { beginAllowed = false }
        val store = store(port)

        val result = store.execute(
            operation(PackageOperationKind.CLEAN_CACHE),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, result.outcome)
        assertEquals("PACKAGE_MANAGER_BUSY", result.error!!.code)
        assertEquals(0, port.cleanCacheCalls)
    }

    @Test
    fun failedUpdateRefreshReturnsTypedFailure() {
        val port = FakeTermuxPackagePort().apply {
            upgradableResult = ExecutionResult(100, "mirror unavailable")
        }
        val store = store(port)

        val query = store.query(PackageStoreQuery.Updates(refreshIndex = true))
        val operation = store.execute(
            operation(PackageOperationKind.UPGRADE_ALL),
            observer = {},
        )

        assertEquals("INDEX_REFRESH_FAILED", (query.exceptionOrNull() as PackageStoreException).code)
        assertEquals(PackageOperationOutcome.FAILED, operation.outcome)
        assertEquals("INDEX_REFRESH_FAILED", operation.error!!.code)
    }

    @Test
    fun cancelledNativeInstallRepairsBeforeReturning() {
        val port = FakeTermuxPackagePort().apply {
            installPackagesResult = false
        }
        val store = store(port)

        val resultRef = java.util.concurrent.atomic.AtomicReference<PackageOperationResult>()
        val worker = kotlin.concurrent.thread {
            resultRef.set(
                store.execute(
                    operation(PackageOperationKind.INSTALL, "repo.git"),
                    observer = {},
                ),
            )
        }
        assertTrue(port.installStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(store.cancel(44))
        port.releaseInstall.countDown()
        worker.join(5_000)

        assertEquals(PackageOperationOutcome.CANCELLED, resultRef.get().outcome)
        assertEquals(1, port.repairCalls)
    }

    @Test
    fun nativeRemovalLogsStayNonCancellableAndCancelIsRejected() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("gimp" to "1.0"))
            blockRemove = true
        }
        val store = store(port)
        val events = mutableListOf<PackageOperationEvent>()
        val resultRef = java.util.concurrent.atomic.AtomicReference<PackageOperationResult>()
        val worker = kotlin.concurrent.thread {
            resultRef.set(
                store.execute(
                    operation(PackageOperationKind.REMOVE, "app.gimp"),
                    observer = events::add,
                ),
            )
        }
        assertTrue(port.removeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        assertFalse(store.cancel(44))
        assertEquals(0, port.cancelCalls)
        port.releaseRemove.countDown()
        worker.join(5_000)

        assertEquals(PackageOperationOutcome.SUCCEEDED, resultRef.get().outcome)
        assertTrue(
            events.filter { it.phase == PackageOperationPhase.CONFIGURING }
                .all { !it.cancellable },
        )
    }

    @Test
    fun nativeRemovalSurfacesRepairFailure() {
        val port = FakeTermuxPackagePort().apply {
            setInstalled(mapOf("gimp" to "1.0"))
            removeMutation = PackageMutationStatus(
                succeeded = false,
                repairFailed = true,
            )
        }
        val result = store(port).execute(
            operation(PackageOperationKind.REMOVE, "app.gimp"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.FAILED, result.outcome)
        assertEquals("PACKAGE_REPAIR_FAILED", result.error!!.code)
    }

    private fun store(
        port: FakeTermuxPackagePort = FakeTermuxPackagePort(),
        history: PackageStoreHistory = history(),
    ) = TermuxNativePackageStore(
        port,
        history,
        DirectorySizer { directory ->
            directory.takeIf(File::isDirectory)
                ?.walkTopDown()
                ?.filter(File::isFile)
                ?.sumOf(File::length)
                ?: 0L
        },
    )

    private fun operation(
        kind: PackageOperationKind,
        entryId: String? = null,
    ) = PackageStoreOperation(
        operationId = 44,
        expectedEnvironmentKind = EnvironmentKind.TERMUX_NATIVE,
        kind = kind,
        entryId = entryId,
    )
}

internal class FakeTermuxPackagePort : TermuxPackagePort {
    override val prefixDir: File = Files.createTempDirectory("termux-prefix").toFile()
    override val homeDir: File = Files.createTempDirectory("termux-home").toFile()
    override var ready: Boolean = true
    var beginAllowed = true
    var searchOutput = ""
    var upgradableOutput = ""
    var upgradableResult: ExecutionResult? = null
    val upgradableRequests = mutableListOf<Boolean>()
    val curatedInstalls = mutableListOf<String>()
    val installedPackageGroups = mutableListOf<List<String>>()
    val removedPackages = mutableListOf<List<String>>()
    val upgradedPackages = mutableListOf<List<String>>()
    var cancelCalls = 0
    var cleanCacheCalls = 0
    var repairCalls = 0
    var installPackagesResult = true
    var removeMutation: PackageMutationStatus? = null
    var upgradeMutation: PackageMutationStatus? = null
    val installStarted = java.util.concurrent.CountDownLatch(1)
    val releaseInstall = java.util.concurrent.CountDownLatch(1)
    var blockRemove = false
    val removeStarted = java.util.concurrent.CountDownLatch(1)
    val releaseRemove = java.util.concurrent.CountDownLatch(1)
    var lastCancelledOperationId: Long? = null
    var beginCalls = 0
    var finishCalls = 0
    var onCuratedInstall: (String) -> Unit = {}
    var onUpgrade: (List<String>) -> Unit = {}

    override fun beginOperation(
        operationId: Long,
        onOutput: (String) -> Unit,
    ): Boolean {
        beginCalls++
        return beginAllowed
    }

    override fun finishOperation(operationId: Long) {
        finishCalls++
    }

    override fun searchPackages(query: String): ExecutionResult =
        ExecutionResult(0, searchOutput)

    override fun upgradablePackages(refreshIndex: Boolean): ExecutionResult {
        upgradableRequests += refreshIndex
        return upgradableResult ?: ExecutionResult(0, upgradableOutput)
    }

    override fun installCurated(
        legacyId: String,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): Boolean {
        curatedInstalls += legacyId
        onCuratedInstall(legacyId)
        return true
    }

    override fun installPackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): Boolean {
        installedPackageGroups += packages
        if (!installPackagesResult) {
            installStarted.countDown()
            releaseInstall.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return false
        }
        val current = installedPackages().toMutableMap()
        packages.forEach { current[it] = "1.0" }
        setInstalled(current)
        return true
    }

    override fun removePackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): PackageMutationStatus {
        removedPackages += packages
        if (blockRemove) {
            removeStarted.countDown()
            releaseRemove.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        onOutput("Removing Native packages")
        removeMutation?.let { return it }
        val current = installedPackages().toMutableMap()
        packages.forEach(current::remove)
        setInstalled(current)
        return PackageMutationStatus(succeeded = true)
    }

    override fun upgradePackages(
        packages: List<String>,
        onProgress: (Double, String) -> Unit,
        onOutput: (String) -> Unit,
    ): PackageMutationStatus {
        upgradedPackages += packages
        onUpgrade(packages)
        return upgradeMutation ?: PackageMutationStatus(succeeded = true)
    }

    override fun refreshDesktopMenus(onOutput: (String) -> Unit): Boolean = true

    override fun cleanCache(onOutput: (String) -> Unit): Boolean {
        cleanCacheCalls++
        listOf(
            File(prefixDir, "var/cache/apt/archives"),
            File(prefixDir, "var/lib/apt/lists"),
        ).forEach { directory ->
            directory.listFiles()?.forEach(File::deleteRecursively)
        }
        return true
    }

    override fun cancelOwnedProcess(operationId: Long): Boolean {
        cancelCalls++
        lastCancelledOperationId = operationId
        return true
    }

    override fun repairAfterCancellation(onOutput: (String) -> Unit): Boolean {
        repairCalls++
        return true
    }

    fun setInstalled(packages: Map<String, String>) {
        val status = File(prefixDir, "var/lib/dpkg/status")
        status.parentFile!!.mkdirs()
        status.writeText(
            packages.entries.joinToString("\n\n") { (name, version) ->
                """
                    Package: $name
                    Status: install ok installed
                    Version: $version
                    Section: devel
                    Description: $name package
                """.trimIndent()
            },
        )
    }

    private fun installedPackages(): Map<String, String> {
        val status = File(prefixDir, "var/lib/dpkg/status")
        if (!status.isFile) return emptyMap()
        return status.readText().split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val fields = block.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1).trim()
            }.toMap()
            fields["Package"]?.let { it to fields["Version"].orEmpty() }
        }.toMap()
    }
}

internal fun history(
    vararg entries: Pair<EnvironmentKind, Set<String>>,
): PackageStoreHistory {
    val storage = FakeHistoryStorage().apply {
        entries.forEach { (kind, values) -> tracked[kind] = values }
        migrationComplete = true
    }
    return DefaultPackageStoreHistory(storage)
}
