package com.orailnoor.droiddesk.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ProotDebianPackageStoreOperationTest {

    @Test
    fun pythonBaselineCannotBeRemoved() {
        val port = TransactionProotPort().apply {
            installed.putAll(
                mapOf(
                    "python3" to "3.11.2",
                    "python3-pip" to "23.0.1",
                    "python3-venv" to "3.11.2",
                ),
            )
        }
        val store = ProotDebianPackageStore(port)

        val result = store.execute(
            operation(PackageOperationKind.REMOVE, "tool.python"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, result.outcome)
        assertEquals("REMOVE_PROTECTED", result.error!!.code)
        assertTrue(port.rootCalls.isEmpty())
    }

    @Test
    fun cancelTerminatesOnlyTheOwnedProcessGroup() {
        val port = TransactionProotPort().apply { blockFirstConfigure = true }
        val runner = PackageTransactionRunner(port)
        val result = AtomicReference<PackageOperationResult>()

        val worker = startBlockedInstall(runner, port, result)
        assertTrue(runner.cancel(31))
        worker.join(5_000)

        assertEquals(PackageOperationOutcome.CANCELLED, result.get().outcome)
        assertTrue(port.rootCalls.none { call ->
            call.executable == "killall" ||
                call.executable == "rm" ||
                call.arguments.any { it.contains("lock") }
        })
    }

    @Test
    fun cancelWaitsFiveSecondsBeforeOwnedKillFallback() {
        val port = TransactionProotPort().apply { blockFirstConfigure = true }
        val runner = PackageTransactionRunner(port)
        val result = AtomicReference<PackageOperationResult>()

        val worker = startBlockedInstall(runner, port, result)
        assertTrue(runner.cancel(31))
        worker.join(5_000)

        assertEquals(listOf(5_000L), port.cancelGracePeriods)
    }

    @Test
    fun cancelDuringConfigureRunsBoundedRepair() {
        val port = TransactionProotPort().apply { blockFirstConfigure = true }
        val runner = PackageTransactionRunner(port)
        val result = AtomicReference<PackageOperationResult>()

        val worker = startBlockedInstall(runner, port, result)
        assertTrue(runner.cancel(31))
        worker.join(5_000)

        val configureCalls = port.rootCalls.filter {
            it.matches("dpkg", "--configure", "-a")
        }
        assertTrue(configureCalls.size >= 2)
        assertEquals(90_000L, configureCalls.last().timeoutMs)
    }

    @Test
    fun failedCancelCleanupReturnsCancelCleanupFailed() {
        val port = TransactionProotPort().apply {
            blockFirstConfigure = true
            failCancellationCleanup = true
        }
        val runner = PackageTransactionRunner(port)
        val result = AtomicReference<PackageOperationResult>()

        val worker = startBlockedInstall(runner, port, result)
        assertTrue(runner.cancel(31))
        worker.join(5_000)

        assertEquals(PackageOperationOutcome.FAILED, result.get().outcome)
        assertEquals("CANCEL_CLEANUP_FAILED", result.get().error!!.code)
    }

    @Test
    fun nextInstallSucceedsAfterCancelledInstall() {
        val port = TransactionProotPort().apply { blockFirstConfigure = true }
        val runner = PackageTransactionRunner(port)
        val first = AtomicReference<PackageOperationResult>()

        val worker = startBlockedInstall(runner, port, first)
        assertTrue(runner.cancel(31))
        worker.join(5_000)
        port.blockFirstConfigure = false
        port.cancellationRequested = false

        val second = runner.run(
            operation(
                PackageOperationKind.INSTALL,
                "app.firefox",
                operationId = 32,
            ),
            ProotPackageRecipes.require("app.firefox"),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.CANCELLED, first.get().outcome)
        assertEquals(PackageOperationOutcome.SUCCEEDED, second.outcome)
    }

    @Test
    fun staleOperationIdCannotCancelTheCurrentOperation() {
        val port = TransactionProotPort().apply { blockFirstConfigure = true }
        val runner = PackageTransactionRunner(port)
        val result = AtomicReference<PackageOperationResult>()

        val worker = startBlockedInstall(runner, port, result)

        assertFalse(runner.cancel(30))
        assertTrue(port.cancelGracePeriods.isEmpty())
        assertTrue(runner.cancel(31))
        worker.join(5_000)
    }

    private fun startBlockedInstall(
        runner: PackageTransactionRunner,
        port: TransactionProotPort,
        result: AtomicReference<PackageOperationResult>,
    ): Thread {
        val worker = thread {
            result.set(
                runner.run(
                    operation(PackageOperationKind.INSTALL, "app.firefox"),
                    ProotPackageRecipes.require("app.firefox"),
                    observer = {},
                ),
            )
        }
        assertTrue(port.blockingConfigureStarted.await(5, TimeUnit.SECONDS))
        return worker
    }
}
