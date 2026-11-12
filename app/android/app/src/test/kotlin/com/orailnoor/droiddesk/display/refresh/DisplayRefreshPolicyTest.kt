package com.orailnoor.droiddesk.display.refresh

import com.orailnoor.droiddesk.display.capability.DisplayModeEvidence
import com.orailnoor.droiddesk.display.support.PresentationFixtures
import com.orailnoor.droiddesk.view.applyRefreshRequest
import com.orailnoor.droiddesk.view.displayModeEvidenceOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshPolicyTest {
    @Test
    fun selectsExactResolutionSixtyHertzMode() {
        val fixture = PresentationFixtures.nx888jPhone
        val policyRef: (DisplayModeEvidence?, List<DisplayModeEvidence>) -> RefreshRequest =
            DisplayRefreshPolicy::resolve

        val request = policyRef(fixture.currentMode, fixture.supportedModes)

        assertEquals(60f, request.preferredRefreshRate)
        assertEquals(1, request.preferredModeId)
        assertTrue(request.reason.isNotBlank())
    }

    @Test
    fun acceptsFiftyNinePointNineFourHertz() {
        val cappedTvCurrent = DisplayModeEvidence(1, 3840, 2160, 30f)
        val cappedTvSupported = listOf(
            DisplayModeEvidence(1, 3840, 2160, 30f),
            DisplayModeEvidence(2, 3840, 2160, 59.94f),
        )

        val cappedTvRequest = DisplayRefreshPolicy.resolve(cappedTvCurrent, cappedTvSupported)

        assertEquals(59.94f, cappedTvRequest.preferredRefreshRate)
        assertEquals(2, cappedTvRequest.preferredModeId)
        assertTrue(cappedTvRequest.reason.isNotBlank())

        val hdmi21Current = DisplayModeEvidence(3, 3840, 2160, 120f)
        val hdmi21Supported = listOf(
            DisplayModeEvidence(2, 3840, 2160, 59.94f),
            DisplayModeEvidence(3, 3840, 2160, 120f),
        )

        val hdmi21Request = DisplayRefreshPolicy.resolve(hdmi21Current, hdmi21Supported)

        assertEquals(60f, hdmi21Request.preferredRefreshRate)
        assertEquals(2, hdmi21Request.preferredModeId)

        val boundarySupported = listOf(
            DisplayModeEvidence(2, 3840, 2160, 59.5f),
            DisplayModeEvidence(3, 3840, 2160, 120f),
        )
        val boundaryRequest = DisplayRefreshPolicy.resolve(hdmi21Current, boundarySupported)
        assertEquals(60f, boundaryRequest.preferredRefreshRate)
        assertEquals(2, boundaryRequest.preferredModeId)

        val outsideToleranceSupported = listOf(
            DisplayModeEvidence(2, 3840, 2160, 59.4f),
            DisplayModeEvidence(3, 3840, 2160, 120f),
        )

        val outsideToleranceRequest = DisplayRefreshPolicy.resolve(
            hdmi21Current,
            outsideToleranceSupported,
        )

        assertEquals(60f, outsideToleranceRequest.preferredRefreshRate)
        assertNull(outsideToleranceRequest.preferredModeId)
    }

    @Test
    fun doesNotSelectLowerResolutionSixtyHertzModeForFourKOneTwenty() {
        val reviewFocusRequest = DisplayRefreshPolicy.resolve(
            current = DisplayModeEvidence(120, 3840, 2160, 120f),
            supported = listOf(DisplayModeEvidence(60, 1920, 1080, 60f)),
        )

        assertEquals(60f, reviewFocusRequest.preferredRefreshRate)
        assertNull(reviewFocusRequest.preferredModeId)
        assertTrue(reviewFocusRequest.reason.isNotBlank())

        val fixture = PresentationFixtures.fourK120
        val fixtureRequest = DisplayRefreshPolicy.resolve(
            current = fixture.currentMode,
            supported = fixture.supportedModes,
        )

        assertEquals(60f, fixtureRequest.preferredRefreshRate)
        assertNull(fixtureRequest.preferredModeId)
        assertTrue(fixtureRequest.reason.isNotBlank())

        val partialDimensionMatchRequest = DisplayRefreshPolicy.resolve(
            current = DisplayModeEvidence(120, 3840, 2160, 120f),
            supported = listOf(
                DisplayModeEvidence(61, 3840, 1080, 60f),
                DisplayModeEvidence(62, 1920, 2160, 60f),
            ),
        )
        assertEquals(60f, partialDimensionMatchRequest.preferredRefreshRate)
        assertNull(partialDimensionMatchRequest.preferredModeId)
    }

    @Test
    fun doesNotRequestRateAboveDisplayCapability() {
        val current = DisplayModeEvidence(1, 3840, 2160, 30f)
        val supported = listOf(
            DisplayModeEvidence(1, 3840, 2160, 30f),
            DisplayModeEvidence(2, 1920, 1080, 60f),
        )

        val request = DisplayRefreshPolicy.resolve(
            current = current,
            supported = supported,
            targetFps = 60f,
        )

        assertEquals(30f, request.preferredRefreshRate)
        assertNull(request.preferredModeId)
        assertTrue(request.reason.isNotBlank())
    }

    @Test
    fun missingModeEvidenceReturnsNoRequest() {
        val sampleMode = DisplayModeEvidence(1, 1920, 1080, 60f)

        val missingCurrent = DisplayRefreshPolicy.resolve(
            current = null,
            supported = listOf(sampleMode),
        )
        assertNull(missingCurrent.preferredRefreshRate)
        assertNull(missingCurrent.preferredModeId)
        assertTrue(missingCurrent.reason.isNotBlank())

        val missingSupported = DisplayRefreshPolicy.resolve(
            current = sampleMode,
            supported = emptyList(),
        )
        assertNull(missingSupported.preferredRefreshRate)
        assertNull(missingSupported.preferredModeId)
        assertTrue(missingSupported.reason.isNotBlank())

        for (invalidTarget in listOf(0f, -60f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val invalidTargetRequest = DisplayRefreshPolicy.resolve(
                current = sampleMode,
                supported = listOf(sampleMode),
                targetFps = invalidTarget,
            )
            assertNull(invalidTargetRequest.preferredRefreshRate)
            assertNull(invalidTargetRequest.preferredModeId)
            assertTrue(invalidTargetRequest.reason.isNotBlank())
        }
    }

    @Test
    fun prefersClosestRateAndCurrentModeIdOnTie() {
        val current120 = DisplayModeEvidence(3, 1920, 1080, 120f)
        val fractionalAndExact = listOf(
            DisplayModeEvidence(1, 1920, 1080, 59.94f),
            DisplayModeEvidence(2, 1920, 1080, 60f),
            current120,
        )

        val closestRequest = DisplayRefreshPolicy.resolve(current120, fractionalAndExact)
        assertEquals(60f, closestRequest.preferredRefreshRate)
        assertEquals(2, closestRequest.preferredModeId)

        val active60 = DisplayModeEvidence(2, 1920, 1080, 60f)
        val duplicateRates = listOf(
            DisplayModeEvidence(1, 1920, 1080, 60f),
            active60,
        )

        val tieRequest = DisplayRefreshPolicy.resolve(active60, duplicateRates)
        assertEquals(60f, tieRequest.preferredRefreshRate)
        assertEquals(2, tieRequest.preferredModeId)

        val nonCurrentDuplicates = listOf(
            DisplayModeEvidence(5, 1920, 1080, 60f),
            DisplayModeEvidence(2, 1920, 1080, 60f),
            current120,
        )
        val deterministicRequest = DisplayRefreshPolicy.resolve(current120, nonCurrentDuplicates)
        assertEquals(2, deterministicRequest.preferredModeId)
    }

    @Test
    fun safeModeConversionAndWindowAttributeApplicationClearStaleModeId() {
        assertEquals(
            DisplayModeEvidence(4, 2160, 1440, 60f),
            displayModeEvidenceOrNull(modeId = 4, width = 2160, height = 1440, refreshRate = 60f),
        )
        assertNull(displayModeEvidenceOrNull(modeId = 0, width = 2160, height = 1440, refreshRate = 60f))
        assertNull(displayModeEvidenceOrNull(modeId = 4, width = 0, height = 1440, refreshRate = 60f))
        assertNull(displayModeEvidenceOrNull(modeId = 4, width = 2160, height = -1, refreshRate = 60f))
        assertNull(displayModeEvidenceOrNull(modeId = 4, width = 2160, height = 1440, refreshRate = 0f))
        assertNull(displayModeEvidenceOrNull(modeId = 4, width = 2160, height = 1440, refreshRate = Float.NaN))
        assertNull(
            displayModeEvidenceOrNull(
                modeId = 4,
                width = 2160,
                height = 1440,
                refreshRate = Float.POSITIVE_INFINITY,
            ),
        )

        val appliedUpdates = mutableListOf<Pair<Float, Int>>()
        applyRefreshRequest(
            RefreshRequest(
                preferredRefreshRate = null,
                preferredModeId = null,
                reason = "missing current display mode",
            ),
        ) { rate, modeId -> appliedUpdates += rate to modeId }
        assertTrue(appliedUpdates.isEmpty())

        applyRefreshRequest(
            RefreshRequest(
                preferredRefreshRate = 60f,
                preferredModeId = null,
                reason = "no exact-resolution target mode",
            ),
        ) { rate, modeId -> appliedUpdates += rate to modeId }
        assertEquals(listOf(60f to 0), appliedUpdates)

        applyRefreshRequest(
            RefreshRequest(
                preferredRefreshRate = 60f,
                preferredModeId = 7,
                reason = "selected exact-resolution mode 7",
            ),
        ) { rate, modeId -> appliedUpdates += rate to modeId }
        applyRefreshRequest(
            RefreshRequest(
                preferredRefreshRate = null,
                preferredModeId = 9,
                reason = "mode only",
            ),
        ) { rate, modeId -> appliedUpdates += rate to modeId }
        assertEquals(listOf(60f to 0, 60f to 7, 0f to 9), appliedUpdates)
    }
}
