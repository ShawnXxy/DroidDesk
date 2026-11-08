package com.orailnoor.droiddesk.display.placement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedDisplayHandoffRegistryTest {
    @Test
    fun fallbackIdentityDefinesConnectionWhenPlatformIdentityIsUnavailable() {
        val connection = AcceptedDisplayHandoffRegistry().resolve(
            AcceptedDisplayHandoffEvidence(
                displayId = 30,
                fallbackIdentity = "external:4:HDMI",
            ),
        )

        assertEquals(30, connection.displayId)
        assertEquals("external:4:HDMI", connection.connectionIdentity)
        assertEquals(0L, connection.generation)
        assertTrue(connection.connectionKey.startsWith("bound:30:"))
    }

    @Test
    fun currentEvidenceAcceptsMatchingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)

        val admission = registry.admit(
            owner = Any(),
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                current = evidence,
                previous = null,
            ),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            admission,
        )
    }

    @Test
    fun previousEvidenceAcceptsMatchingHandoffWhenCurrentEvidenceIsUnavailable() {
        val registry = AcceptedDisplayHandoffRegistry()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)

        val admission = registry.admit(
            owner = Any(),
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                current = null,
                previous = evidence,
            ),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            admission,
        )
    }

    @Test
    fun currentEvidenceRejectsMismatchEvenWhenPreviousEvidenceMatches() {
        val registry = AcceptedDisplayHandoffRegistry()
        val previous = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val current = AcceptedDisplayHandoffEvidence(
            displayId = 31,
            fallbackIdentity = "external:5:HDMI",
        )
        val requested = registry.resolve(previous)

        val admission = registry.admit(
            owner = Any(),
            displayId = 30,
            connectionKey = requested.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                current = current,
                previous = previous,
            ),
        )

        assertEquals(AcceptedDisplayHandoffAdmission.Rejected, admission)
    }

    @Test
    fun missingEvidenceAwaitsConfirmation() {
        val admission = AcceptedDisplayHandoffRegistry().admit(
            owner = Any(),
            displayId = 30,
            connectionKey = "bound:30:pending",
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                current = null,
                previous = null,
            ),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.AwaitingEvidence,
            admission,
        )
    }

    @Test
    fun delayedEvidenceConfirmsPendingHandoffWithoutChangingKey() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                current = null,
                previous = null,
            ),
        )

        val admission = registry.confirm(owner, evidence)

        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            admission,
        )
        val accepted = admission as AcceptedDisplayHandoffAdmission.Accepted
        assertEquals(expected.connectionKey, accepted.connection.connectionKey)
    }

    @Test
    fun equalButDistinctOwnerCannotReplacePendingHandoffWithoutEvidence() {
        val registry = AcceptedDisplayHandoffRegistry()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        val originalOwner = EqualOwner()
        registry.admit(
            owner = originalOwner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        val replacement = registry.admit(
            owner = EqualOwner(),
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(AcceptedDisplayHandoffAdmission.Rejected, replacement)
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.confirm(originalOwner, evidence),
        )
    }

    @Test
    fun equalButDistinctOwnerCannotConfirmPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val originalOwner = EqualOwner()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = originalOwner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(EqualOwner(), evidence),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.confirm(originalOwner, evidence),
        )
    }

    @Test
    fun equalButDistinctOwnerCannotUnbindPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val originalOwner = EqualOwner()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = originalOwner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        registry.unbind(EqualOwner())

        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.confirm(originalOwner, evidence),
        )
    }

    @Test
    fun sameOwnerCannotReplacePendingConnectionKeyWithoutEvidence() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        val replacement = registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = "bound:30:different",
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(AcceptedDisplayHandoffAdmission.Rejected, replacement)
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.confirm(owner, evidence),
        )
    }

    @Test
    fun sameOwnerCanReplacePendingConnectionKeyWithMatchingEvidence() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val originalEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val replacementEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:5:HDMI",
        )
        val original = registry.resolve(originalEvidence)
        val replacement = registry.resolve(replacementEvidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = original.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        val admission = registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = replacement.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                current = replacementEvidence,
                previous = null,
            ),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(replacement),
            admission,
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(owner, originalEvidence),
        )
    }

    @Test
    fun sameOwnerCannotReplacePendingDisplayWithoutEvidence() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        val replacement = registry.admit(
            owner = owner,
            displayId = 31,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(AcceptedDisplayHandoffAdmission.Rejected, replacement)
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.confirm(owner, evidence),
        )
    }

    @Test
    fun sameOwnerDisplayAndKeyRebindIsIdempotentWhileEvidenceIsDelayed() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        val missingEvidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null)

        assertEquals(
            AcceptedDisplayHandoffAdmission.AwaitingEvidence,
            registry.admit(owner, 30, expected.connectionKey, missingEvidence),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.AwaitingEvidence,
            registry.admit(owner, 30, expected.connectionKey, missingEvidence),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.confirm(owner, evidence),
        )
    }

    @Test
    fun differentOwnerRequiresMatchingEvidenceToSupersedePendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val originalOwner = Any()
        val nextOwner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = originalOwner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        val admission = registry.admit(
            owner = nextOwner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                current = evidence,
                previous = null,
            ),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            admission,
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(originalOwner, evidence),
        )
    }

    @Test
    fun unbindInvalidatesPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        registry.unbind(owner)

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(owner, evidence),
        )
    }

    @Test
    fun mismatchedDelayedEvidenceRejectsAndConsumesPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val requestedEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val replacementEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:5:HDMI",
        )
        val requested = registry.resolve(requestedEvidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = requested.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(owner, replacementEvidence),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(owner, requestedEvidence),
        )
    }

    @Test
    fun mismatchedImmediateEvidenceRejectsAndConsumesSameOwnerPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val requestedEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val replacementEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:5:HDMI",
        )
        val requested = registry.resolve(requestedEvidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = requested.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.admit(
                owner = owner,
                displayId = 30,
                connectionKey = requested.connectionKey,
                evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                    current = replacementEvidence,
                    previous = null,
                ),
            ),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(owner, requestedEvidence),
        )
    }

    @Test
    fun mismatchedDifferentDisplayDoesNotConsumeSameOwnerPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val requestedEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val requested = registry.resolve(requestedEvidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = requested.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.admit(
                owner = owner,
                displayId = 31,
                connectionKey = requested.connectionKey,
                evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                    current = requestedEvidence,
                    previous = null,
                ),
            ),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(requested),
            registry.confirm(owner, requestedEvidence),
        )
    }

    @Test
    fun mismatchedDifferentKeyDoesNotConsumeSameOwnerPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val requestedEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val requested = registry.resolve(requestedEvidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = requested.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.admit(
                owner = owner,
                displayId = 30,
                connectionKey = "bound:30:different",
                evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                    current = requestedEvidence,
                    previous = null,
                ),
            ),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(requested),
            registry.confirm(owner, requestedEvidence),
        )
    }

    @Test
    fun equalButDistinctOwnerMismatchDoesNotConsumePendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val originalOwner = EqualOwner()
        val requestedEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val replacementEvidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:5:HDMI",
        )
        val requested = registry.resolve(requestedEvidence)
        registry.admit(
            owner = originalOwner,
            displayId = 30,
            connectionKey = requested.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.admit(
                owner = EqualOwner(),
                displayId = 30,
                connectionKey = requested.connectionKey,
                evidence = AcceptedDisplayHandoffEvidenceSnapshot(
                    current = replacementEvidence,
                    previous = null,
                ),
            ),
        )
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(requested),
            registry.confirm(originalOwner, requestedEvidence),
        )
    }

    @Test
    fun displayRemovalInvalidatesPendingHandoffAndAdvancesGeneration() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val original = registry.resolve(evidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = original.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        registry.displayRemoved(30)

        assertEquals(
            AcceptedDisplayHandoffAdmission.Rejected,
            registry.confirm(owner, evidence),
        )
        val replacement = registry.resolve(evidence)
        assertEquals(1L, replacement.generation)
        assertNotEquals(original.connectionKey, replacement.connectionKey)
    }

    @Test
    fun removingAnotherDisplayPreservesPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = expected.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        registry.displayRemoved(31)

        assertEquals(expected, registry.resolve(evidence))
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.confirm(owner, evidence),
        )
    }

    @Test
    fun displayAddAndChangeKeepGenerationAndPendingHandoff() {
        val registry = AcceptedDisplayHandoffRegistry()
        val owner = Any()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val original = registry.resolve(evidence)
        registry.admit(
            owner = owner,
            displayId = 30,
            connectionKey = original.connectionKey,
            evidence = AcceptedDisplayHandoffEvidenceSnapshot(null, null),
        )

        registry.displayAdded(30)
        registry.displayChanged(30)

        assertEquals(original, registry.resolve(evidence))
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(original),
            registry.confirm(owner, evidence),
        )
    }

    @Test
    fun sequentialOwnersReuseSameLiveConnection() {
        val registry = AcceptedDisplayHandoffRegistry()
        val evidence = AcceptedDisplayHandoffEvidence(
            displayId = 30,
            fallbackIdentity = "external:4:HDMI",
        )
        val expected = registry.resolve(evidence)
        val firstOwner = Any()
        val secondOwner = Any()
        val currentEvidence = AcceptedDisplayHandoffEvidenceSnapshot(
            current = evidence,
            previous = null,
        )

        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.admit(
                owner = firstOwner,
                displayId = 30,
                connectionKey = expected.connectionKey,
                evidence = currentEvidence,
            ),
        )
        registry.unbind(firstOwner)
        assertEquals(
            AcceptedDisplayHandoffAdmission.Accepted(expected),
            registry.admit(
                owner = secondOwner,
                displayId = 30,
                connectionKey = expected.connectionKey,
                evidence = currentEvidence,
            ),
        )
    }

    private class EqualOwner {
        override fun equals(other: Any?): Boolean = other is EqualOwner

        override fun hashCode(): Int = 1
    }
}
