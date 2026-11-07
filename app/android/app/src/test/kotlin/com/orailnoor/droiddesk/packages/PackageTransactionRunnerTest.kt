package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.ExecutionResult
import com.orailnoor.droiddesk.runtime.ProotReadyMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PackageTransactionRunnerTest {

    @Test
    fun installRepairsBeforeRefreshingAndInstalling() {
        val port = TransactionProotPort()
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        val repair = port.rootCalls.indexOfFirst { it.matches("dpkg", "--configure", "-a") }
        val refresh = port.rootCalls.indexOfFirst { it.matches("apt-get", "update") }
        val install = port.rootCalls.indexOfFirst {
            it.executable == "apt-get" &&
                it.arguments.containsAll(
                    listOf("install", "-y", "--no-install-recommends", "firefox-esr"),
                )
        }
        assertTrue(repair >= 0)
        assertTrue(refresh > repair)
        assertTrue(install > refresh)
    }

    @Test
    fun brokenDependenciesRunFixInstallOnlyWhenAuditRequiresIt() {
        val brokenPort = TransactionProotPort().apply {
            auditOutput = "The following packages are only half configured"
        }
        val healthyPort = TransactionProotPort()

        PackageTransactionRunner(brokenPort).run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )
        PackageTransactionRunner(healthyPort).run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertTrue(
            brokenPort.rootCalls.any {
                it.matches("apt-get", "-f", "install", "-y")
            },
        )
        assertFalse(
            healthyPort.rootCalls.any {
                it.matches("apt-get", "-f", "install", "-y")
            },
        )
    }

    @Test
    fun failedConfigureRunsFixBrokenAndRetriesConfigure() {
        val port = TransactionProotPort().apply { failConfigureCalls = 1 }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        val firstConfigure = port.rootCalls.indexOfFirst {
            it.matches("dpkg", "--configure", "-a")
        }
        val fixBroken = port.rootCalls.indexOfFirst {
            it.matches("apt-get", "-f", "install", "-y")
        }
        val retriedConfigure = port.rootCalls.indexOfLast {
            it.matches("dpkg", "--configure", "-a")
        }
        assertTrue(fixBroken > firstConfigure)
        assertTrue(retriedConfigure > fixBroken)
    }

    @Test
    fun liveForeignPackageManagerReturnsBusyWithoutDeletingLocks() {
        val port = TransactionProotPort().apply { foreignManager = "dpkg" }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, result.outcome)
        assertEquals("PACKAGE_MANAGER_BUSY", result.error!!.code)
        assertTrue(port.rootCalls.none { call ->
            call.executable == "rm" ||
                call.arguments.any { it.contains("lock") || it.contains("killall") }
        })
        assertTrue(port.rootCalls.none { it.executable == "apt-get" })
    }

    @Test
    fun removeNeverRunsAutoremove() {
        val port = TransactionProotPort().apply {
            installed["firefox-esr"] = "128.9.0esr"
        }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.REMOVE, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertTrue(
            port.rootCalls.any {
                it.matches("apt-get", "remove", "-y", "firefox-esr")
            },
        )
        assertTrue(port.rootCalls.none { it.arguments.contains("autoremove") })
    }

    @Test
    fun upgradeAllUsesOnlyStoreManagedRecipePackages() {
        val port = TransactionProotPort().apply {
            installed.putAll(
                mapOf(
                    "firefox-esr" to "128.9.0esr",
                    "nodejs" to "18.20.4",
                    "npm" to "9.2.0",
                    "curl" to "8.0",
                ),
            )
        }

        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.UPGRADE_ALL),
            recipe = null,
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        val upgrade = port.rootCalls.single {
            it.executable == "apt-get" && it.arguments.contains("--only-upgrade")
        }
        assertTrue(upgrade.arguments.containsAll(listOf("firefox-esr", "nodejs", "npm")))
        assertFalse(upgrade.arguments.contains("curl"))
    }

    @Test
    fun upgradeAllExcludesExperimentalRecipes() {
        val port = TransactionProotPort().apply {
            installed.putAll(
                mapOf(
                    "firefox-esr" to "128.9.0esr",
                    "chromium" to "130.0",
                    "code" to "1.145.2",
                ),
            )
        }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.UPGRADE_ALL),
            recipe = null,
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        val upgrade = port.rootCalls.single {
            it.executable == "apt-get" && it.arguments.contains("--only-upgrade")
        }
        assertTrue(upgrade.arguments.contains("firefox-esr"))
        assertFalse(upgrade.arguments.contains("chromium"))
        assertFalse(upgrade.arguments.contains("code"))
    }

    @Test
    fun removePhaseNeverAdvertisesCancellation() {
        val port = TransactionProotPort().apply {
            installed["firefox-esr"] = "128.9.0esr"
        }
        val events = mutableListOf<PackageOperationEvent>()
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.REMOVE, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = events::add,
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertTrue(
            events.filter { it.phase == PackageOperationPhase.INSTALLING }
                .all { !it.cancellable },
        )
    }

    @Test
    fun removeIsNonCancellableBeforeTheCommandProducesOutput() {
        val port = TransactionProotPort().apply {
            installed["firefox-esr"] = "128.9.0esr"
            blockRemoveBeforeOutput = true
        }
        val runner = PackageTransactionRunner(port)
        val result = java.util.concurrent.atomic.AtomicReference<PackageOperationResult>()
        val worker = kotlin.concurrent.thread {
            result.set(
                runner.run(
                    operation(PackageOperationKind.REMOVE, "app.firefox"),
                    ProotPackageRecipes.require("app.firefox"),
                    observer = {},
                ),
            )
        }
        assertTrue(port.removeStarted.await(5, TimeUnit.SECONDS))

        assertFalse(runner.cancel(31))
        port.releaseRemove.countDown()
        worker.join(5_000)

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.get().outcome)
        assertTrue(port.cancelGracePeriods.isEmpty())
    }

    @Test
    fun cancellationBetweenCommandsSkipsTheNextMutationCommand() {
        val port = TransactionProotPort()
        lateinit var runner: PackageTransactionRunner
        port.onInstalledPackages = {
            port.onInstalledPackages = null
            assertTrue(runner.cancel(31))
        }
        runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(result.toString(), PackageOperationOutcome.CANCELLED, result.outcome)
        assertTrue(
            port.rootCalls.none {
                it.executable == "apt-get" &&
                    it.arguments.contains("--no-install-recommends")
            },
        )
    }

    @Test
    fun missingRecipeExecutableFailsVerification() {
        val port = TransactionProotPort().apply {
            missingExecutables += "firefox-esr"
        }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.FAILED, result.outcome)
        assertEquals("VERIFY_FAILED", result.error!!.code)
    }

    @Test
    fun wrongPackageArchitectureFailsVerification() {
        val port = TransactionProotPort().apply { packageArchitecture = "amd64" }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.FAILED, result.outcome)
        assertEquals("VERIFY_FAILED", result.error!!.code)
    }

    @Test
    fun singleUpgradeRequiresThePrimaryVersionToChange() {
        val port = TransactionProotPort().apply {
            installed["firefox-esr"] = "128.9.0esr"
            preserveVersionsOnUpgrade = true
        }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.UPGRADE, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.FAILED, result.outcome)
        assertEquals("VERIFY_FAILED", result.error!!.code)
    }

    @Test
    fun singleUpgradeAcceptsASecondaryPackageVersionChange() {
        val port = TransactionProotPort().apply {
            installed["nodejs"] = "2.0"
            installed["npm"] = "1.0"
        }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.UPGRADE, "tool.nodejs"),
            ProotPackageRecipes.require("tool.nodejs"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertEquals("2.0", port.installed["nodejs"])
        assertEquals("2.0", port.installed["npm"])
    }

    @Test
    fun verificationFailureReturnsVerifyFailedInsteadOfSuccess() {
        val port = TransactionProotPort().apply { mutatePackageState = false }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.FAILED, result.outcome)
        assertEquals("VERIFY_FAILED", result.error!!.code)
    }

    @Test
    fun desktopRefreshLimitationPreservesInstalledResultWithWarning() {
        val port = TransactionProotPort().apply { desktopRefreshSucceeds = false }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertEquals(
            listOf("DESKTOP_INTEGRATION_LIMITED"),
            result.warnings.map(PackageStoreWarning::code),
        )
    }

    @Test
    fun successfulDesktopIntegrationRefreshesTheDesktopAndPanel() {
        val port = TransactionProotPort()
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertTrue(port.desktopCalls.any { it.matches("xfdesktop", "--reload") })
        assertTrue(port.desktopCalls.any { it.matches("xfce4-panel", "-r") })
    }

    @Test
    fun missingDesktopEntryReturnsVerifyFailed() {
        val port = TransactionProotPort().apply {
            createDesktopEntriesOnInstall = false
        }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.INSTALL, "app.firefox"),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.FAILED, result.outcome)
        assertEquals("VERIFY_FAILED", result.error!!.code)
    }

    @Test
    fun cleanCacheReportsBytesFreed() {
        val port = TransactionProotPort()
        File(port.rootfsDir, "var/cache/apt/archives/package.deb").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(17))
        }
        File(port.rootfsDir, "var/lib/apt/lists/index").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(19))
        }
        val runner = PackageTransactionRunner(port)

        val result = runner.run(
            operation(PackageOperationKind.CLEAN_CACHE),
            recipe = null,
            observer = {},
        )

        assertEquals(PackageOperationOutcome.SUCCEEDED, result.outcome)
        assertEquals(36L, result.bytesFreed)
        assertTrue(
            port.rootCalls.any {
                it.executable == "find" &&
                    it.arguments.firstOrNull() == "/var/lib/apt/lists"
            },
        )
    }
}

internal data class PackagePortCall(
    val executable: String,
    val arguments: List<String>,
    val timeoutMs: Long,
) {
    fun matches(expectedExecutable: String, vararg expectedArguments: String): Boolean =
        executable == expectedExecutable && arguments == expectedArguments.toList()
}

internal class TransactionProotPort : ProotPackagePort {
    override val rootfsDir: File = Files.createTempDirectory("proot-transaction").toFile()
    val installed = linkedMapOf<String, String>()
    val rootCalls = mutableListOf<PackagePortCall>()
    val desktopCalls = mutableListOf<PackagePortCall>()
    val cancelGracePeriods = mutableListOf<Long>()
    var foreignManager: String? = null
    var auditOutput = ""
    var mutatePackageState = true
    var createDesktopEntriesOnInstall = true
    var desktopRefreshSucceeds = true
    var blockFirstConfigure = false
    var failCancellationCleanup = false
    var failConfigureCalls = 0
    var packageArchitecture = "arm64"
    var preserveVersionsOnUpgrade = false
    var blockRemoveBeforeOutput = false
    val missingExecutables = mutableSetOf<String>()
    var onInstalledPackages: (() -> Unit)? = null
    val removeStarted = CountDownLatch(1)
    val releaseRemove = CountDownLatch(1)
    var configureCalls = 0
    val blockingConfigureStarted = CountDownLatch(1)
    val releaseBlockingConfigure = CountDownLatch(1)
    @Volatile var cancellationRequested = false

    override fun readyMarker(): ProotReadyMarker = ProotReadyMarker(
        schema = 1,
        suite = "bookworm",
        imageReference = "debian@sha256:test",
        imageDigest = "sha256:test",
        packageSetVersion = 5,
        prootVersion = "5.1.107.102",
        prootDistroVersion = "5.10.3",
    )

    override fun architecture(): String = "arm64"

    override fun availableBytes(): Long = 8L * 1024L * 1024L * 1024L

    override fun installedPackages(): Map<String, String> {
        onInstalledPackages?.invoke()
        return installed.toMap()
    }

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
        val call = PackagePortCall(executable, arguments.toList(), timeoutMs)
        rootCalls += call
        if (executable == "pgrep") {
            return if (arguments.lastOrNull() == foreignManager) {
                ExecutionResult(0, "123")
            } else {
                ExecutionResult(1, "")
            }
        }
        if (call.matches("dpkg", "--configure", "-a")) {
            configureCalls++
            if (blockFirstConfigure && configureCalls == 1) {
                blockingConfigureStarted.countDown()
                check(releaseBlockingConfigure.await(5, TimeUnit.SECONDS)) {
                    "Blocking configure was not released"
                }
                return ExecutionResult(1, "cancelled")
            }
            if (cancellationRequested && failCancellationCleanup && configureCalls >= 2) {
                return ExecutionResult(1, "cleanup failed")
            }
            if (configureCalls <= failConfigureCalls) {
                return ExecutionResult(1, "dependency problems prevent configuration")
            }
            return ExecutionResult(0, "")
        }
        if (executable == "dpkg-query") {
            val packageName = arguments.last()
            val version = installed[packageName] ?: return ExecutionResult(1, "not installed")
            return ExecutionResult(0, "$packageArchitecture\t$version")
        }
        if (executable == "which") {
            val command = arguments.single()
            return if (command in missingExecutables) {
                ExecutionResult(1, "")
            } else {
                ExecutionResult(0, "/usr/bin/$command")
            }
        }
        if (call.matches("dpkg", "--audit")) {
            return ExecutionResult(0, auditOutput)
        }
        if (call.matches("apt-get", "update")) {
            return ExecutionResult(0, "")
        }
        if (executable == "apt-get" && arguments.contains("remove")) {
            if (blockRemoveBeforeOutput) {
                removeStarted.countDown()
                releaseRemove.await(5, TimeUnit.SECONDS)
            }
            onOutput("Removing Debian packages")
            if (mutatePackageState) packageArguments(arguments).forEach(installed::remove)
            return ExecutionResult(0, "")
        }
        if (executable == "apt-get" && arguments.contains("install")) {
            val packages = packageArguments(arguments)
            if (mutatePackageState) {
                packages.forEach { packageName ->
                    if (!(preserveVersionsOnUpgrade && packageName in installed)) {
                        installed[packageName] = "2.0"
                    }
                }
                if (createDesktopEntriesOnInstall) createDesktopEntries(packages)
            }
            return ExecutionResult(0, "")
        }
        if (call.matches("apt-get", "clean")) {
            File(rootfsDir, "var/cache/apt/archives")
                .listFiles()
                ?.forEach(File::deleteRecursively)
            return ExecutionResult(0, "")
        }
        if (executable == "find" && arguments.firstOrNull() == "/var/lib/apt/lists") {
            File(rootfsDir, "var/lib/apt/lists")
                .listFiles()
                ?.forEach(File::deleteRecursively)
            return ExecutionResult(0, "")
        }
        return ExecutionResult(0, "")
    }

    override fun runDesktop(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit,
    ): ExecutionResult {
        desktopCalls += PackagePortCall(executable, arguments.toList(), timeoutMs)
        return if (desktopRefreshSucceeds) {
            ExecutionResult(0, "")
        } else {
            ExecutionResult(1, "desktop session unavailable")
        }
    }

    override fun cancelOwnedProcess(
        operationId: Long,
        gracePeriodMs: Long,
    ): Boolean {
        cancelGracePeriods += gracePeriodMs
        cancellationRequested = true
        releaseBlockingConfigure.countDown()
        return true
    }

    private fun packageArguments(arguments: List<String>): List<String> {
        val reserved = setOf(
            "install",
            "remove",
            "-y",
            "-f",
            "--no-install-recommends",
            "--only-upgrade",
        )
        return arguments.filter { it !in reserved && !it.startsWith("-o") }
    }

    private fun createDesktopEntries(packages: List<String>) {
        val icons = mapOf(
            "firefox-esr" to "firefox-esr",
            "libreoffice" to "libreoffice-startcenter",
            "gimp" to "gimp",
            "vlc" to "vlc",
        )
        packages.forEach { packageName ->
            val icon = icons[packageName] ?: return@forEach
            File(rootfsDir, "usr/share/applications/$icon.desktop").apply {
                parentFile!!.mkdirs()
                writeText("[Desktop Entry]\nName=$icon\nExec=$packageName\n")
            }
        }
    }
}

internal fun operation(
    kind: PackageOperationKind,
    entryId: String? = null,
    operationId: Long = 31,
) = PackageStoreOperation(
    operationId = operationId,
    expectedEnvironmentKind = com.orailnoor.droiddesk.runtime.EnvironmentKind.PROOT_DEBIAN,
    kind = kind,
    entryId = entryId,
)
