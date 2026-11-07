package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import com.orailnoor.droiddesk.runtime.ExecutionResult
import com.orailnoor.droiddesk.runtime.ProotReadyMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotDebianPackageStoreQueryTest {

    @Test
    fun unreadyRootfsReportsEnvironmentNotInstalled() {
        val port = FakeProotPackagePort().apply { marker = null }
        val store = ProotDebianPackageStore(port)

        val result = store.query(PackageStoreQuery.Catalog("")).getOrThrow()

        assertEquals(
            PackageStoreAvailability.ENVIRONMENT_NOT_INSTALLED,
            result.target.availability,
        )
        assertTrue(result.target.reason!!.contains("not installed", ignoreCase = true))
        assertTrue(result.entries.isEmpty())
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun nonArm64RootfsReportsUnsupportedArchitecture() {
        val port = FakeProotPackagePort().apply { architecture = "amd64" }
        val store = ProotDebianPackageStore(port)

        val result = store.query(PackageStoreQuery.Catalog("")).getOrThrow()

        assertEquals(
            PackageStoreAvailability.TEMPORARILY_UNAVAILABLE,
            result.target.availability,
        )
        assertTrue(result.target.reason!!.contains("amd64"))
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun emptyCatalogReturnsOnlyCuratedRecipes() {
        val store = ProotDebianPackageStore(FakeProotPackagePort())

        val entries = store.query(PackageStoreQuery.Catalog("")).getOrThrow().entries

        assertEquals(
            ProotPackageRecipes.all.map(PackageRecipe::entryId),
            entries.map(PackageStoreEntry::entryId),
        )
        assertTrue(entries.none { it.entryId.startsWith("repo.") })
    }

    @Test
    fun searchMatchesNameSummaryCategoryAndAlias() {
        val store = ProotDebianPackageStore(FakeProotPackagePort())

        val browser = store.query(PackageStoreQuery.Catalog("browser")).getOrThrow()
        val office = store.query(PackageStoreQuery.Catalog("office")).getOrThrow()
        val javascript = store.query(PackageStoreQuery.Catalog("javascript")).getOrThrow()

        assertEquals(
            listOf("app.firefox"),
            browser.entries
                .filter { it.supportLevel == PackageSupportLevel.SUPPORTED }
                .map(PackageStoreEntry::entryId),
        )
        assertEquals(
            listOf("app.libreoffice"),
            office.entries.map(PackageStoreEntry::entryId),
        )
        assertEquals(
            listOf("tool.nodejs"),
            javascript.entries.map(PackageStoreEntry::entryId),
        )
    }

    @Test
    fun craftedRepoOrShellLikeIdIsNeverReturnedOrAccepted() {
        val port = FakeProotPackagePort()
        val store = ProotDebianPackageStore(port)

        val result = store.execute(
            PackageStoreOperation(
                operationId = 15,
                expectedEnvironmentKind = EnvironmentKind.PROOT_DEBIAN,
                kind = PackageOperationKind.INSTALL,
                entryId = "repo.firefox-esr;touch /tmp/pwned",
            ),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, result.outcome)
        assertEquals("INVALID_ENTRY", result.error!!.code)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun installedStateUsesRecipePrimaryAndRequiredPackages() {
        val port = FakeProotPackagePort().apply {
            installed = mapOf(
                "firefox-esr" to "128.9.0esr",
                "nodejs" to "18.20.4",
                "python3" to "3.11.2",
                "python3-pip" to "23.0.1",
                "python3-venv" to "3.11.2",
            )
        }
        val store = ProotDebianPackageStore(port)

        val entries = store.query(PackageStoreQuery.Installed).getOrThrow().entries

        assertEquals(
            listOf("app.firefox", "tool.python"),
            entries.map(PackageStoreEntry::entryId),
        )
        assertEquals(
            listOf("128.9.0esr", "3.11.2"),
            entries.map(PackageStoreEntry::installedVersion),
        )
    }

    @Test
    fun updatesContainOnlyInstalledRecipePackages() {
        val port = FakeProotPackagePort().apply {
            installed = mapOf(
                "firefox-esr" to "128.9.0esr",
                "nodejs" to "18.20.4",
                "npm" to "9.2.0",
            )
            upgradableOutput = """
                firefox-esr/stable-security 128.10.0esr arm64 [upgradable from: 128.9.0esr]
                curl/stable 8.0 arm64 [upgradable from: 7.0]
            """.trimIndent()
        }
        val store = ProotDebianPackageStore(port)

        val entries = store.query(
            PackageStoreQuery.Updates(refreshIndex = true),
        ).getOrThrow().entries

        assertEquals(listOf("app.firefox"), entries.map(PackageStoreEntry::entryId))
        assertEquals("128.10.0esr", entries.single().availableVersion)
        assertTrue(port.calls.contains("root:apt-get:update"))
    }

    @Test
    fun cleaningCacheInvalidatesThePackageIndexRefreshTtl() {
        val port = FakeProotPackagePort()
        val store = ProotDebianPackageStore(port, clock = { 1_000L })

        store.query(PackageStoreQuery.Updates(refreshIndex = true)).getOrThrow()
        val cleaned = store.execute(
            operation(PackageOperationKind.CLEAN_CACHE),
            observer = {},
        )
        store.query(PackageStoreQuery.Updates(refreshIndex = true)).getOrThrow()

        assertEquals(PackageOperationOutcome.SUCCEEDED, cleaned.outcome)
        assertEquals(
            2,
            port.calls.count { it == "root:apt-get:update" },
        )
    }

    @Test
    fun storageUsesTheProotRootfsAndAptCache() {
        val port = FakeProotPackagePort().apply {
            installed = mapOf("firefox-esr" to "128.9.0esr", "curl" to "8.0")
        }
        File(port.rootfsDir, "usr/bin/firefox-esr").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(11))
        }
        File(port.rootfsDir, "home/droiddesk/document").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(13))
        }
        File(port.rootfsDir, "var/cache/apt/archives/firefox.deb").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(17))
        }
        File(port.rootfsDir, "var/lib/apt/lists/security").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(19))
        }
        val store = ProotDebianPackageStore(port)

        val storage = store.query(PackageStoreQuery.Storage).getOrThrow().storage!!

        assertEquals(11, storage.systemBytes)
        assertEquals(13, storage.homeBytes)
        assertEquals(36, storage.cacheBytes)
        assertEquals(2, storage.packageCount)
        assertEquals(port.available, storage.deviceFreeBytes)
    }
}

internal class FakeProotPackagePort : ProotPackagePort {
    override val rootfsDir: File = Files.createTempDirectory("proot-store-rootfs").toFile()
    var marker: ProotReadyMarker? = readyMarkerFixture()
    var architecture: String? = "arm64"
    var available: Long = 8L * 1024L * 1024L * 1024L
    var installed: Map<String, String> = emptyMap()
    var upgradableOutput = ""
    val calls = mutableListOf<String>()

    override fun readyMarker(): ProotReadyMarker? = marker

    override fun architecture(): String? = architecture

    override fun availableBytes(): Long = available

    override fun installedPackages(): Map<String, String> = installed

    override fun directoryBytes(directory: File): Long =
        directory.takeIf(File::isDirectory)
            ?.walkTopDown()
            ?.filter(File::isFile)
            ?.sumOf(File::length)
            ?: 0L

    override fun beginOperation(operationId: Long): Boolean = true

    override fun finishOperation(operationId: Long) = Unit

    override fun runRoot(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): ExecutionResult {
        calls += "root:$executable:${arguments.joinToString(":")}"
        return when {
            executable == "apt-get" && arguments == listOf("update") ->
                ExecutionResult(0, "")
            executable == "apt" && arguments == listOf("list", "--upgradable") ->
                ExecutionResult(0, upgradableOutput)
            else -> ExecutionResult(0, "")
        }
    }

    override fun runDesktop(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): ExecutionResult {
        calls += "desktop:$executable:${arguments.joinToString(":")}"
        return ExecutionResult(0, "")
    }

    override fun cancelOwnedProcess(
        operationId: Long,
        gracePeriodMs: Long,
    ): Boolean {
        calls += "cancel"
        return true
    }
}

private fun readyMarkerFixture() = ProotReadyMarker(
    schema = 1,
    suite = "bookworm",
    imageReference = "debian@sha256:test",
    imageDigest = "sha256:test",
    packageSetVersion = 5,
    prootVersion = "5.1.107.102",
    prootDistroVersion = "5.10.3",
)
