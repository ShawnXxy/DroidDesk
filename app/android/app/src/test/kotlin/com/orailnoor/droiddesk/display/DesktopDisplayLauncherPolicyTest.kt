package com.orailnoor.droiddesk.display

import com.orailnoor.droiddesk.display.placement.AcceptedDisplayHandoffAdmission
import com.orailnoor.droiddesk.display.placement.AcceptedDisplayHandoffEvidence
import com.orailnoor.droiddesk.display.placement.AcceptedDisplayHandoffRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDisplayLauncherPolicyTest {

    @Test
    fun android28And29DoNotCallTheApi30DisplayAccessor() {
        listOf(28, 29).forEach { sdkInt ->
            var api30AccessorCalled = false

            val display = activityDisplayForSdk(
                sdkInt = sdkInt,
                api30Display = {
                    api30AccessorCalled = true
                    "activity"
                },
                decorDisplay = { "decor" },
            )

            assertEquals("decor", display)
            assertFalse(api30AccessorCalled)
        }
    }

    @Test
    fun android30PrefersTheActivityDisplayAndFallsBackToDecor() {
        assertEquals(
            "activity",
            activityDisplayForSdk(30, { "activity" }, { "decor" }),
        )
        assertEquals(
            "decor",
            activityDisplayForSdk<String>(30, { null }, { "decor" }),
        )
    }

    @Test
    fun repositoryDisplayKeepsItsStableConnectionKey() {
        val request = display(id = 0, key = "repository")
        val repository = display(id = 0, key = "repository")
        val bound = display(id = 0, key = "bound:0")

        assertSame(repository, resolveLaunchDisplay(request, repository, bound))
    }

    @Test
    fun boundDisplayIsUsedOnlyForTheMatchingSyntheticRequest() {
        val request = display(id = 30, key = "bound:30:current")
        val bound = display(id = 30, key = "bound:30:current")

        assertSame(bound, resolveLaunchDisplay(request, null, bound))
        val error = assertThrows(DesktopDisplayException::class.java) {
            resolveLaunchDisplay(request, null, bound.copy(key = "bound:30:replacement"))
        }
        assertEquals("DISPLAY_UNAVAILABLE", error.code)
        assertTrue(error.message!!.contains("launchable"))
    }

    @Test
    fun androidHandoffUsesThePublicStableFallbackIdentity() {
        val handoffs = AcceptedDisplayHandoffRegistry()

        val connection = handoffs.resolve(
            evidence(displayId = 30, fallbackIdentity = "external:4:HDMI"),
        )

        assertEquals("external:4:HDMI", connection.connectionIdentity)
        assertEquals(0L, connection.generation)
        assertTrue(connection.connectionKey.startsWith("bound:30:"))
    }

    @Test
    fun displayEventsAdvanceOnlyTheRemovedConnectionGeneration() {
        val handoffs = AcceptedDisplayHandoffRegistry()
        val evidence = evidence(displayId = 30, fallbackIdentity = "external:4:HDMI")
        val original = handoffs.resolve(evidence)

        handoffs.displayAdded(30)
        handoffs.displayChanged(30)
        assertEquals(original.connectionKey, handoffs.resolve(evidence).connectionKey)

        handoffs.displayRemoved(30)
        val replacement = handoffs.resolve(evidence)
        handoffs.displayAdded(30)
        handoffs.displayChanged(30)

        assertEquals(1L, replacement.generation)
        assertNotEquals(original.connectionKey, replacement.connectionKey)
        assertEquals(replacement.connectionKey, handoffs.resolve(evidence).connectionKey)
    }

    @Test
    fun sequentialLaunchesReuseTheCurrentSyntheticConnection() {
        val handoffs = AcceptedDisplayHandoffRegistry()
        val evidence = evidence(displayId = 30, fallbackIdentity = "external:4:HDMI")
        val connectionKey = handoffs.resolve(evidence).connectionKey

        val first = syntheticDisplayHandoff(
            registry = handoffs,
            owner = Any(),
            displayId = 30,
            connectionKey = connectionKey,
            currentEvidence = evidence,
            previousEvidence = null,
        )
        val second = syntheticDisplayHandoff(
            registry = handoffs,
            owner = Any(),
            displayId = 30,
            connectionKey = connectionKey,
            currentEvidence = evidence,
            previousEvidence = null,
        )

        assertAcceptedConnection(connectionKey, first)
        assertAcceptedConnection(connectionKey, second)
    }

    @Test
    fun removalBetweenOnCreateAndOnResumeRejectsWithoutThrowing() {
        val handoffs = AcceptedDisplayHandoffRegistry()
        val evidence = evidence(displayId = 30, fallbackIdentity = "external:4:HDMI")
        val connectionKey = handoffs.resolve(evidence).connectionKey
        val desktopOwner = Any()

        assertAcceptedConnection(
            connectionKey,
            syntheticDisplayHandoff(
                registry = handoffs,
                owner = desktopOwner,
                displayId = 30,
                connectionKey = connectionKey,
                currentEvidence = null,
                previousEvidence = evidence,
            ),
        )

        handoffs.displayRemoved(30)
        handoffs.displayAdded(30)

        assertSame(
            AcceptedDisplayHandoffAdmission.Rejected,
            syntheticDisplayHandoff(
                registry = handoffs,
                owner = desktopOwner,
                displayId = 30,
                connectionKey = connectionKey,
                currentEvidence = evidence,
                previousEvidence = null,
            ),
        )
    }

    @Test
    fun repositoryAbsentPreRCoordinatorValidatesBindsClaimsAndResumes() {
        val host = RepositoryAbsentHandoffHost()
        val coordinator = DesktopDisplayCoordinator(host = host)
        val target = host.currentDisplay()!!
        var completion: Result<Unit>? = null

        coordinator.open(target.id, target.key) { completion = it }
        val request = host.launches.single()
        assertTrue(coordinator.canAttach(
            requestId = request.requestId,
            displayId = request.display.id,
            displayKey = request.display.key,
            epoch = request.sessionGeneration,
        ))
        assertAcceptedConnection(
            request.display.key,
            host.bindDesktopOnCreateBeforeDecorEvidence(request.display.key),
        )

        val lease = coordinator.claim(request.display.id) {}
        coordinator.registerActivity(lease, request.requestId) {}
        assertAcceptedConnection(
            request.display.key,
            host.bindDesktopOnResumeWithDecorEvidence(request.display.key),
        )
        coordinator.onAttached(lease, request.requestId, PixelSize(1920, 1080))

        assertTrue(completion?.isSuccess == true)
        assertEquals("attached", coordinator.snapshot()["phase"])
    }

    @Test
    fun sameIdReplacementFailsCanAttachBeforeBindOrClaim() {
        val host = RepositoryAbsentHandoffHost()
        val coordinator = DesktopDisplayCoordinator(host = host)
        val target = host.currentDisplay()!!

        coordinator.open(target.id, target.key) {}
        val request = host.launches.single()
        host.replaceDisplayConnection()

        assertFalse(coordinator.canAttach(
            requestId = request.requestId,
            displayId = request.display.id,
            displayKey = request.display.key,
            epoch = request.sessionGeneration,
        ))
    }

    @Test
    fun displayLaunchReusesOnlyTheActivityAlreadyOnItsTargetDisplay() {
        assertTrue(shouldReuseBoundActivity(targetDisplayId = 30, boundDisplayId = 30))
        assertFalse(shouldReuseBoundActivity(targetDisplayId = 30, boundDisplayId = 0))
        assertFalse(shouldReuseBoundActivity(targetDisplayId = 30, boundDisplayId = null))
    }

    private class RepositoryAbsentHandoffHost : DesktopDisplayHost {
        private val displayId = 30
        private val evidence = evidence(
            displayId = displayId,
            fallbackIdentity = "external:4:HDMI",
        )
        private val handoffs = AcceptedDisplayHandoffRegistry()
        private var owner: Any = Any()
        private var displayEvidence: AcceptedDisplayHandoffEvidence? = evidence
        val launches = mutableListOf<DesktopLaunchRequest>()

        override fun currentDisplay(): DesktopDisplay? =
            displayEvidence?.let {
                val connection = handoffs.resolve(it)
                DesktopDisplay(
                    id = displayId,
                    key = connection.connectionKey,
                    name = "Repository-absent HDMI",
                    isDefault = false,
                    eligible = true,
                    reason = null,
                    settingsKey = it.fallbackIdentity,
                )
            }

        override fun displays(): List<DesktopDisplay> = emptyList()

        override fun defaultProfileKind() = ProfileKind.PHONE

        override fun sessionRunning() = true

        override fun launch(request: DesktopLaunchRequest) {
            launches += request
        }

        override fun focus(requestId: Long) = false

        override fun cancelLaunch(requestId: Long) = Unit

        override fun scheduleTimeout(delayMillis: Long, action: () -> Unit): () -> Unit = {}

        override fun notifyReturnRequired(error: DesktopDisplayException) = Unit

        fun bindDesktopOnCreateBeforeDecorEvidence(
            connectionKey: String,
        ): AcceptedDisplayHandoffAdmission {
            val previousEvidence = displayEvidence
            owner = Any()
            displayEvidence = null
            return syntheticDisplayHandoff(
                registry = handoffs,
                owner = owner,
                displayId = displayId,
                connectionKey = connectionKey,
                currentEvidence = null,
                previousEvidence = previousEvidence,
            )
        }

        fun bindDesktopOnResumeWithDecorEvidence(
            connectionKey: String,
        ): AcceptedDisplayHandoffAdmission {
            displayEvidence = evidence
            return syntheticDisplayHandoff(
                registry = handoffs,
                owner = owner,
                displayId = displayId,
                connectionKey = connectionKey,
                currentEvidence = evidence,
                previousEvidence = null,
            )
        }

        fun replaceDisplayConnection() {
            handoffs.displayRemoved(displayId)
            handoffs.displayAdded(displayId)
            owner = Any()
        }
    }

    private fun assertAcceptedConnection(
        expectedConnectionKey: String,
        admission: AcceptedDisplayHandoffAdmission,
    ) {
        assertTrue(admission is AcceptedDisplayHandoffAdmission.Accepted)
        assertEquals(
            expectedConnectionKey,
            (admission as AcceptedDisplayHandoffAdmission.Accepted).connection.connectionKey,
        )
    }

    private companion object {
        fun evidence(
            displayId: Int,
            fallbackIdentity: String,
        ) = AcceptedDisplayHandoffEvidence(
            displayId = displayId,
            fallbackIdentity = fallbackIdentity,
        )
    }

    private fun display(id: Int, key: String) = DesktopDisplay(
        id = id,
        key = key,
        name = "Display $id",
        isDefault = id == 0,
        eligible = true,
        reason = null,
    )
}
