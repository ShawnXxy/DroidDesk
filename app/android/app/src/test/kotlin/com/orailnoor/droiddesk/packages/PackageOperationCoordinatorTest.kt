package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PackageOperationCoordinatorTest {

    @Test
    fun processSubscriberReceivesEventsAndCompletion() {
        val coordinator = PackageOperationCoordinator()
        val events = mutableListOf<PackageOperationEvent>()
        val results = mutableListOf<PackageOperationResult>()
        val subscriberId = coordinator.subscribe(events::add, results::add)
        val store = object : FakeStore(EnvironmentKind.PROOT_DEBIAN) {
            override fun execute(
                request: PackageStoreOperation,
                observer: (PackageOperationEvent) -> Unit,
            ): PackageOperationResult {
                observer(
                    PackageOperationEvent(
                        operationId = request.operationId,
                        environmentKind = request.expectedEnvironmentKind,
                        phase = PackageOperationPhase.INSTALLING,
                        progress = 0.5,
                        message = "Installing",
                        logChunk = null,
                        cancellable = true,
                    ),
                )
                return successfulResult(request)
            }
        }

        coordinator.execute(
            environmentKind = EnvironmentKind.PROOT_DEBIAN,
            request = PackageOperationRequest(
                kind = PackageOperationKind.INSTALL,
                entryId = "app.firefox",
            ),
            store = store,
            observer = {},
        )
        coordinator.unsubscribe(subscriberId)

        assertEquals(1, events.size)
        assertEquals(1, results.size)
        assertEquals(events.single().operationId, results.single().operationId)
    }

    @Test
    fun completedResultRemainsAvailableUntilAcknowledged() {
        val coordinator = PackageOperationCoordinator()
        val result = coordinator.execute(
            environmentKind = EnvironmentKind.PROOT_DEBIAN,
            request = PackageOperationRequest(
                kind = PackageOperationKind.INSTALL,
                entryId = "app.firefox",
            ),
            store = FakeStore(EnvironmentKind.PROOT_DEBIAN),
            observer = {},
        )

        assertEquals(result, coordinator.completedResult())
        assertTrue(coordinator.acknowledgeResult(result.operationId))
        assertEquals(null, coordinator.completedResult())
    }

    @Test
    fun admissionRejectionIsPublishedAndRetainedUntilAcknowledged() {
        val coordinator = PackageOperationCoordinator()
        val results = mutableListOf<PackageOperationResult>()
        coordinator.subscribe(onEvent = {}, onResult = results::add)

        val result = coordinator.reject(
            environmentKind = EnvironmentKind.PROOT_DEBIAN,
            request = installRequest(),
            code = "TARGET_CHANGED",
            message = "Package store target changed",
        )

        assertEquals(listOf(result), results)
        assertEquals(result, coordinator.completedResult())
        assertTrue(coordinator.acknowledgeResult(result.operationId))
        assertEquals(null, coordinator.completedResult())
    }

    @Test
    fun onlyOneMutationRunsAcrossAllEnvironments() {
        var activeKind = EnvironmentKind.TERMUX_NATIVE
        val native = BlockingStore(EnvironmentKind.TERMUX_NATIVE)
        val proot = FakeStore(EnvironmentKind.PROOT_DEBIAN)
        val manager = manager(listOf(native, proot)) { activeKind }
        val firstResult = AtomicReference<PackageOperationResult>()

        val worker = thread {
            firstResult.set(
                manager.execute(
                    EnvironmentKind.TERMUX_NATIVE,
                    installRequest(),
                    observer = {},
                ),
            )
        }
        assertTrue(native.started.await(5, TimeUnit.SECONDS))
        activeKind = EnvironmentKind.PROOT_DEBIAN

        val rejected = manager.execute(
            EnvironmentKind.PROOT_DEBIAN,
            installRequest(),
            observer = {},
        )

        assertEquals(PackageOperationOutcome.REJECTED, rejected.outcome)
        assertEquals("OPERATION_IN_PROGRESS", rejected.error!!.code)
        assertEquals(0, proot.executeCalls)
        native.release.countDown()
        worker.join(5_000)
        assertEquals(PackageOperationOutcome.SUCCEEDED, firstResult.get().outcome)
    }

    @Test
    fun operationIdsIncreaseAndEventsCarryTheCapturedTarget() {
        var activeKind = EnvironmentKind.TERMUX_NATIVE
        val native = EventStore(EnvironmentKind.TERMUX_NATIVE)
        val proot = EventStore(EnvironmentKind.PROOT_DEBIAN)
        val manager = manager(listOf(native, proot)) { activeKind }
        val events = mutableListOf<PackageOperationEvent>()

        val first = manager.execute(
            EnvironmentKind.TERMUX_NATIVE,
            installRequest(),
            observer = { event: PackageOperationEvent -> events += event },
        )
        activeKind = EnvironmentKind.PROOT_DEBIAN
        val second = manager.execute(
            EnvironmentKind.PROOT_DEBIAN,
            installRequest(),
            observer = { event: PackageOperationEvent -> events += event },
        )

        assertEquals(1L, first.operationId)
        assertEquals(2L, second.operationId)
        assertEquals(
            listOf(EnvironmentKind.TERMUX_NATIVE, EnvironmentKind.PROOT_DEBIAN),
            events.map(PackageOperationEvent::environmentKind),
        )
        assertEquals(listOf(1L, 2L), events.map(PackageOperationEvent::operationId))
    }

    @Test
    fun eventsAfterCompletionAreIgnored() {
        val store = FakeStore(EnvironmentKind.TERMUX_NATIVE)
        val manager = manager(listOf(store)) { EnvironmentKind.TERMUX_NATIVE }
        val events = mutableListOf<PackageOperationEvent>()

        val result = manager.execute(
            EnvironmentKind.TERMUX_NATIVE,
            installRequest(),
            observer = { event: PackageOperationEvent -> events += event },
        )
        store.capturedObserver!!.invoke(
            operationEvent(
                result.operationId,
                EnvironmentKind.TERMUX_NATIVE,
                logChunk = "late",
            ),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun cancelRoutesOnlyToTheMatchingCurrentOperation() {
        val store = BlockingStore(EnvironmentKind.TERMUX_NATIVE)
        val manager = manager(listOf(store)) { EnvironmentKind.TERMUX_NATIVE }

        val worker = thread {
            manager.execute(
                EnvironmentKind.TERMUX_NATIVE,
                installRequest(),
                observer = {},
            )
        }
        assertTrue(store.started.await(5, TimeUnit.SECONDS))
        val operationId = manager.currentOperation()!!.operationId

        assertFalse(manager.cancel(operationId + 1))
        assertTrue(manager.cancel(operationId))
        assertEquals(listOf(operationId), store.cancelledOperationIds)
        store.release.countDown()
        worker.join(5_000)
    }

    @Test
    fun logTailKeepsTheNewestTwelveThousandCharacters() {
        val log = "a".repeat(1_000) + "b".repeat(12_000)
        val store = BlockingStore(
            EnvironmentKind.TERMUX_NATIVE,
            firstEventLog = log,
        )
        val manager = manager(listOf(store)) { EnvironmentKind.TERMUX_NATIVE }

        val worker = thread {
            manager.execute(
                EnvironmentKind.TERMUX_NATIVE,
                installRequest(),
                observer = {},
            )
        }
        assertTrue(store.started.await(5, TimeUnit.SECONDS))

        val snapshot = manager.currentOperation()

        assertNotNull(snapshot)
        assertEquals(12_000, snapshot!!.logTail.length)
        assertEquals("b".repeat(12_000), snapshot.logTail)
        store.release.countDown()
        worker.join(5_000)
    }

    @Test
    fun operationStateSurvivesASecondManagerQuery() {
        val store = BlockingStore(EnvironmentKind.TERMUX_NATIVE)
        val manager = manager(listOf(store)) { EnvironmentKind.TERMUX_NATIVE }

        val worker = thread {
            manager.execute(
                EnvironmentKind.TERMUX_NATIVE,
                installRequest(),
                observer = {},
            )
        }
        assertTrue(store.started.await(5, TimeUnit.SECONDS))

        val first = manager.query(
            EnvironmentKind.TERMUX_NATIVE,
            PackageStoreQuery.OperationState,
        ).getOrThrow()
        val second = manager.query(
            EnvironmentKind.TERMUX_NATIVE,
            PackageStoreQuery.OperationState,
        ).getOrThrow()

        assertNotNull(first.operation)
        assertEquals(first.operation!!.operationId, second.operation!!.operationId)
        store.release.countDown()
        worker.join(5_000)
    }

    @Test
    fun cancellationCannotReachANewerOperation() {
        val first = DelayedCancelStore(EnvironmentKind.TERMUX_NATIVE)
        val manager = manager(listOf(first)) { EnvironmentKind.TERMUX_NATIVE }
        val firstWorker = thread {
            manager.execute(
                EnvironmentKind.TERMUX_NATIVE,
                installRequest(),
                observer = {},
            )
        }
        assertTrue(first.started.await(5, TimeUnit.SECONDS))
        val firstId = manager.currentOperation()!!.operationId
        val cancelWorker = thread { manager.cancel(firstId) }
        assertTrue(first.cancelEntered.await(5, TimeUnit.SECONDS))
        first.releaseOperation.countDown()

        val secondResult = AtomicReference<PackageOperationResult>()
        val secondWorker = thread {
            secondResult.set(
                manager.execute(
                    EnvironmentKind.TERMUX_NATIVE,
                    installRequest(),
                    observer = {},
                ),
            )
        }
        Thread.sleep(100)
        first.releaseCancel.countDown()
        cancelWorker.join(5_000)
        firstWorker.join(5_000)
        secondWorker.join(5_000)

        assertEquals(listOf(firstId), first.cancelledOperationIds)
        assertTrue(
            secondResult.get().outcome in setOf(
                PackageOperationOutcome.SUCCEEDED,
                PackageOperationOutcome.REJECTED,
            ),
        )
    }

    @Test
    fun cancellationDoesNotHoldTheCoordinatorLockWhileTheStoreWaits() {
        val store = DelayedCancelStore(EnvironmentKind.TERMUX_NATIVE)
        val coordinator = PackageOperationCoordinator()
        val worker = thread {
            coordinator.execute(
                environmentKind = EnvironmentKind.TERMUX_NATIVE,
                request = installRequest(),
                store = store,
                observer = {},
            )
        }
        assertTrue(store.started.await(5, TimeUnit.SECONDS))
        val operationId = coordinator.snapshot()!!.operationId
        val cancelWorker = thread { coordinator.cancel(operationId) }
        assertTrue(store.cancelEntered.await(5, TimeUnit.SECONDS))

        val subscribed = CountDownLatch(1)
        val subscribeWorker = thread {
            coordinator.subscribe(onEvent = {}, onResult = {})
            subscribed.countDown()
        }

        assertTrue(subscribed.await(1, TimeUnit.SECONDS))
        store.releaseCancel.countDown()
        store.releaseOperation.countDown()
        subscribeWorker.join(5_000)
        cancelWorker.join(5_000)
        worker.join(5_000)
    }

    private fun manager(
        stores: List<EnvironmentPackageStore>,
        activeKind: () -> EnvironmentKind,
    ) = PackageStoreManager(
        adapters = stores,
        activeKind = activeKind,
        operations = PackageOperationCoordinator(),
    )

    private fun installRequest() = PackageOperationRequest(
        kind = PackageOperationKind.INSTALL,
        entryId = "app.firefox",
    )
}

private class EventStore(
    kind: EnvironmentKind,
) : FakeStore(kind) {
    override fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        executeCalls++
        observer(operationEvent(request.operationId, request.expectedEnvironmentKind))
        return successfulResult(request)
    }
}

private class BlockingStore(
    kind: EnvironmentKind,
    private val firstEventLog: String? = null,
) : FakeStore(kind) {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        executeCalls++
        capturedObserver = observer
        observer(
            operationEvent(
                request.operationId,
                request.expectedEnvironmentKind,
                logChunk = firstEventLog,
            ),
        )
        started.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Test operation was not released" }
        return successfulResult(request)
    }
}

private class DelayedCancelStore(
    kind: EnvironmentKind,
) : FakeStore(kind) {
    val started = CountDownLatch(1)
    val cancelEntered = CountDownLatch(1)
    val releaseOperation = CountDownLatch(1)
    val releaseCancel = CountDownLatch(1)

    override fun execute(
        request: PackageStoreOperation,
        observer: (PackageOperationEvent) -> Unit,
    ): PackageOperationResult {
        executeCalls++
        started.countDown()
        releaseOperation.await(5, TimeUnit.SECONDS)
        return successfulResult(request)
    }

    override fun cancel(operationId: Long): Boolean {
        cancelEntered.countDown()
        releaseCancel.await(5, TimeUnit.SECONDS)
        return super.cancel(operationId)
    }
}

private fun operationEvent(
    operationId: Long,
    environmentKind: EnvironmentKind,
    logChunk: String? = null,
) = PackageOperationEvent(
    operationId = operationId,
    environmentKind = environmentKind,
    phase = PackageOperationPhase.INSTALLING,
    progress = 0.5,
    message = "Installing",
    logChunk = logChunk,
    cancellable = true,
)
