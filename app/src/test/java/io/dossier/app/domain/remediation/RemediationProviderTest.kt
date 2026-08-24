package io.dossier.app.domain.remediation

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.data.platform.ProviderCatalogV2
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

    @Test
    fun reviewedResourceCatalogIsSchemaValidAgainstProviderCatalog() {
        val issues = RemediationResourceCatalog.validate(
            RemediationResourceCatalog.entries,
            ProviderCatalogV2.definitions
        )

        assertTrue("catalog issues: $issues", issues.isEmpty())
        assertEquals(8, RemediationResourceCatalog.entries.size)
        assertTrue(RemediationResourceCatalog.entries.all { resource ->
            resource.actionUrl.startsWith("https://") &&
                resource.reviewNote.contains("does not prove removal", ignoreCase = true) &&
                listOf("delete", "remove", "erase").none { token ->
                    resource.actionLabel.contains(token, ignoreCase = true)
                }
        })
    }

    @Test
    fun catalogValidationRejectsDuplicatesUnknownProvidersAndUnsafeLinks() {
        val invalid = listOf(
            ReviewedRemediationResource("github", "", "https://evil.example/action", reviewNote = ""),
            ReviewedRemediationResource("github", "Duplicate", "https://github.com/settings/profile"),
            ReviewedRemediationResource("missing-provider", "Manual", "http://example.com/settings"),
            ReviewedRemediationResource("reddit", "Redirect", "https://www.reddit.com/settings/profile?next=https://evil.example/#x"),
            ReviewedRemediationResource("gitlab", "Credentials", "https://user:pass@gitlab.com/-/profile")
        )

        val issues = RemediationResourceCatalog.validate(invalid, ProviderCatalogV2.definitions)
        val codes = issues.map { it.code }.toSet()

        assertTrue(codes.contains("duplicate-provider"))
        assertTrue(codes.contains("unknown-provider"))
        assertTrue(codes.contains("https-required"))
        assertTrue(codes.contains("blank-label"))
        assertTrue(codes.contains("blank-review-note"))
        assertTrue(codes.contains("host-mismatch"))
        assertTrue(codes.contains("redirect-material-forbidden"))
        assertTrue(codes.contains("userinfo-forbidden"))
    }
}
