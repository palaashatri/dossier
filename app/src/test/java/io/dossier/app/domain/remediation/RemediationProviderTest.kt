package io.dossier.app.domain.remediation

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationProviderTest {

    private fun f(type: FindingType, risk: RiskLevel) = Finding(
        type = type, value = "v", sourceUrl = null, evidenceSnippet = "exposed",
        confidence = 0.9f, risk = risk, remediation = ""
    )

    @Test
    fun structuredTipsMapEachFinding() {
        val items = RemediationProvider().getStructuredTips(
            listOf(f(FindingType.Email, RiskLevel.High), f(FindingType.Phone, RiskLevel.Critical))
        )
        assertEquals(2, items.size)
        // Sorted by risk desc → Critical first.
        assertEquals(RiskLevel.Critical, items.first().risk)
        assertTrue(items.all { it.problem.isNotBlank() })
        assertTrue(items.all { it.suggestedFix.isNotBlank() })
        assertTrue(items.all { it.estimatedImpact.isNotBlank() })
        assertTrue(items.all { it.evidence.isNotBlank() })
    }

    @Test
    fun emptyFindingsYieldsEmpty() {
        assertEquals(0, RemediationProvider().getStructuredTips(emptyList()).size)
    }

    @Test
    fun criticalHasHighImpact() {
        val item = RemediationProvider().getStructuredTips(listOf(f(FindingType.Phone, RiskLevel.Critical))).first()
        assertTrue(item.estimatedImpact.contains("High", ignoreCase = true))
    }

    @Test
    fun reviewedProviderResourceUsesKnownOfficialSettingsUrl() {
        val finding = Finding(
            type = FindingType.Profile,
            value = "https://github.com/alice",
            sourceUrl = "https://github.com/alice",
            evidenceSnippet = "profile",
            confidence = 0.9f,
            risk = RiskLevel.High,
            remediation = "Review profile visibility"
        )

        val resource = RemediationProvider().resourceFor(finding)

        assertEquals(RemediationResourceState.ProviderSpecific, resource.state)
        assertEquals("github", resource.providerId)
        assertEquals("https://github.com/settings/profile", resource.actionUrl)
        assertTrue(resource.note.contains("does not prove removal", ignoreCase = true))
    }

    @Test
    fun knownProviderWithoutReviewedEndpointStaysManualAndUsesSourceOnly() {
        val finding = Finding(
            type = FindingType.Profile,
            value = "https://codeberg.org/alice",
            sourceUrl = "https://codeberg.org/alice",
            evidenceSnippet = "profile",
            confidence = 0.9f,
            risk = RiskLevel.Medium,
            remediation = "Review profile visibility"
        )

        val resource = RemediationProvider().resourceFor(finding)

        assertEquals(RemediationResourceState.ManualActionRequired, resource.state)
        assertEquals("codeberg", resource.providerId)
        assertEquals(finding.sourceUrl, resource.actionUrl)
        assertTrue(resource.note.contains("no reviewed", ignoreCase = true))
    }

    @Test
    fun unknownOrMissingSourceIsExplicitlyUnavailableWithoutInventedUrl() {
        val provider = RemediationProvider()
        val unknown = f(FindingType.Profile, RiskLevel.Low).copy(
            sourceUrl = "https://unknown.example/profile"
        )
        val missing = f(FindingType.Email, RiskLevel.Low)

        val unknownResource = provider.resourceFor(unknown)
        val missingResource = provider.resourceFor(missing)

        assertEquals(RemediationResourceState.Unavailable, unknownResource.state)
        assertEquals(RemediationResourceState.Unavailable, missingResource.state)
        assertNull(unknownResource.providerId)
        assertNull(unknownResource.actionUrl)
        assertNull(missingResource.actionUrl)
        assertFalse(unknownResource.note.isBlank())
    }
}
