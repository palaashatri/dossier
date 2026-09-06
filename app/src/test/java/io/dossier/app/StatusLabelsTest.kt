package io.dossier.app

import io.dossier.app.domain.analysis.PresenceState
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import io.dossier.app.ui.labels.userFacingLabel
import io.dossier.app.ui.labels.userFacingStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StatusLabelsTest {

    @Test
    fun presenceLabelsUseReadableCopyWithoutChangingDomainTokens() {
        assertEquals("Exists", PresenceState.Exists.userFacingLabel())
        assertEquals("Suspicious similarity", PresenceState.SuspiciousSimilarity.userFacingLabel())
        assertEquals("No match", PresenceState.NoMatch.userFacingLabel())
        assertEquals("Unavailable", PresenceState.Unavailable.userFacingLabel())
        assertFalse(PresenceState.SuspiciousSimilarity.userFacingLabel().contains("SuspiciousSimilarity"))
        assertEquals("SuspiciousSimilarity", PresenceState.SuspiciousSimilarity.name)
    }

    @Test
    fun profileLabelsPreserveExistingReviewSemantics() {
        assertEquals("VERIFIED", profile(exists = true, verified = true).userFacingStatusLabel())
        assertEquals("REVIEW", profile(exists = true, verified = false).userFacingStatusLabel())
        assertEquals(
            "UNAVAILABLE",
            profile(exists = false, verified = false, verificationStatus = "Provider unverifiable").userFacingStatusLabel()
        )
        assertEquals("NOT FOUND", profile(exists = false, verified = false).userFacingStatusLabel())
    }

    private fun profile(
        exists: Boolean,
        verified: Boolean,
        verificationStatus: String? = null
    ): ProfileScanResult = ProfileScanResult(
        candidate = UsernameCandidate(
            username = "jane_example",
            platform = Platform.GitHub,
            url = "https://github.com/jane_example",
            matchType = UsernameMatchType.Exact,
            confidence = 0.9f
        ),
        exists = exists,
        httpStatus = null,
        displayName = null,
        bio = null,
        links = emptyList(),
        extractedText = "",
        findings = emptyList<Finding>(),
        confidenceSignals = emptyList(),
        verified = verified,
        verificationStatus = verificationStatus
    )
}
