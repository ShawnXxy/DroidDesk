package com.orailnoor.droiddesk.runtime

import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EnvironmentSelectionGateTest {

    @Test
    fun packageOperationBlocksEnvironmentTransitionUntilItFinishes() {
        var activeKind = EnvironmentKind.PROOT_DEBIAN
        val gate = EnvironmentSelectionGate { activeKind }
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            gate.withActivePackageTarget(EnvironmentKind.PROOT_DEBIAN) {
                admitted.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        admitted.await(5, TimeUnit.SECONDS)

        assertThrows(EnvironmentSelectionBusyException::class.java) {
            gate.withSelectionTransition {
                activeKind = EnvironmentKind.TERMUX_NATIVE
            }
        }

        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun environmentTransitionBlocksPackageAdmission() {
        val gate = EnvironmentSelectionGate { EnvironmentKind.PROOT_DEBIAN }
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            gate.withSelectionTransition {
                admitted.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        admitted.await(5, TimeUnit.SECONDS)

        assertThrows(EnvironmentSelectionBusyException::class.java) {
            gate.withActivePackageTarget(EnvironmentKind.PROOT_DEBIAN) {}
        }

        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun staleExpectedTargetIsRejectedAtAdmission() {
        val gate = EnvironmentSelectionGate { EnvironmentKind.PROOT_DEBIAN }

        assertThrows(EnvironmentTargetChangedException::class.java) {
            gate.withActivePackageTarget(EnvironmentKind.TERMUX_NATIVE) {}
        }
    }

    @Test
    fun acquiredTransitionBlocksPackagesUntilClosed() {
        val gate = EnvironmentSelectionGate { EnvironmentKind.PROOT_DEBIAN }
        val admission = gate.acquireSelectionTransition()

        assertThrows(EnvironmentSelectionBusyException::class.java) {
            gate.withActivePackageTarget(EnvironmentKind.PROOT_DEBIAN) {}
        }

        admission.close()
        gate.withActivePackageTarget(EnvironmentKind.PROOT_DEBIAN) {}
    }
}
