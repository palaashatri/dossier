package io.dossier.app.identity

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.identity.CorrelationFeature
import io.dossier.app.domain.identity.EntityResolverV2
import io.dossier.app.domain.identity.ResolutionBand
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityResolverV2Test {
    private fun profile(
        username: String,
        url: String,
        verified: Boolean,
        displayName: String? = null,
        bio: String? = null,
        links: List<String> = emptyList(),
        findings: List<Finding> = emptyList()
    ) = ProfileScanResult(
        candidate = UsernameCandidate(
            username = username,
            platform = Platform.GitHub,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = if (verified) 0.9f else 0.5f
        ),
        exists = true,
        httpStatus = 200,
        displayName = displayName,
        bio = bio,
        links = links,
        extractedText = listOfNotNull(displayName, bio).joinToString(" "),
        findings = findings,
        confidenceSignals = emptyList(),
        verified = verified,
        verificationStatus = if (verified) "verified" else "review"
    )

    @Test
    fun exactUsernameAloneNeverConfirmsIdentity() {
        val input = IdentityInput(fullName = "", primaryUsername = "common_handle")
        val result = EntityResolverV2.resolve(
            input,
            profile("common_handle", "https://github.com/common_handle", verified = false)
        )

        assertTrue(result.band == ResolutionBand.Low || result.band == ResolutionBand.Unresolved)
        assertTrue(result.supporting.any { it.feature == CorrelationFeature.ExactSuppliedUsername })
    }

    @Test
    fun userSuppliedExactProfileIsConfirmed() {
        val url = "https://github.com/sample_user"
        val input = IdentityInput(
            fullName = "Sample User",
            primaryUsername = "sample_user",
            profileUrls = listOf(url)
        )
        val result = EntityResolverV2.resolve(
            input,
            profile("sample_user", url, verified = true, displayName = "Sample User")
        )

        assertEquals(ResolutionBand.Confirmed, result.band)
        assertTrue(result.supporting.any { it.feature == CorrelationFeature.UserSuppliedProfile })
    }

    @Test
    fun independentSignalsCanProduceHighConfidenceBand() {
        val input = IdentityInput(
            fullName = "Sample User",
            primaryUsername = "rare_handle_7281",
            organizations = listOf("Example Labs"),
            profileUrls = listOf("https://sample.example.test/about")
        )
        val result = EntityResolverV2.resolve(
            input,
            profile(
                username = "rare_handle_7281",
                url = "https://github.com/rare_handle_7281",
                verified = true,
                displayName = "Sample User",
                bio = "Engineer at Example Labs",
                links = listOf("https://sample.example.test/about")
            )
        )

        assertEquals(ResolutionBand.High, result.band)
        assertTrue(result.supporting.size >= 3)
    }

    @Test
    fun strongNameContradictionPreventsHighMergeWhenSupportIsWeak() {
        val input = IdentityInput(
            fullName = "Alice Example",
            primaryUsername = "shared_handle"
        )
        val result = EntityResolverV2.resolve(
            input,
            profile(
                username = "shared_handle",
                url = "https://github.com/shared_handle",
                verified = false,
                displayName = "Robert Different"
            )
        )

        assertEquals(ResolutionBand.Conflicting, result.band)
        assertTrue(result.contradicting.any { it.feature == CorrelationFeature.ConflictingDisplayName })
    }

    @Test
    fun resolverCitesOnlyUniqueExactLedgerIdsForSupportAndContradiction() {
        val url = "https://github.com/shared_handle"
        val input = IdentityInput(
            fullName = "Alice Example",
            primaryUsername = "shared_handle"
        )
        val result = EntityResolverV2.resolve(
            input,
            profile(
                username = "shared_handle",
                url = url,
                verified = false,
                displayName = "Robert Different"
            ),
            evidence = listOf(
                Evidence(
                    id = "profile:$url",
                    kind = EvidenceKind.Profile,
                    value = url,
                    sourceUrl = url
                )
            )
        )

        assertEquals(ResolutionBand.Conflicting, result.band)
        assertTrue(
            result.supporting
                .flatMap { it.evidenceIds }
                .contains("profile:$url")
        )
        assertTrue(
            result.contradicting
                .single { it.feature == CorrelationFeature.ConflictingDisplayName }
                .evidenceIds
                .contains("profile:$url")
        )
    }

    @Test
    fun resolverDoesNotSynthesizeProvenanceFromUrlOrValue() {
        val url = "https://github.com/common_handle"
        val input = IdentityInput(fullName = "", primaryUsername = "common_handle")
        val result = EntityResolverV2.resolve(
            input,
            profile("common_handle", url, verified = true),
            evidence = listOf(
                Evidence(
                    id = "unrelated-id",
                    kind = EvidenceKind.Profile,
                    value = url,
                    sourceUrl = url
                )
            )
        )

        assertTrue(result.supporting.all { it.evidenceIds.isEmpty() })
    }

    @Test
    fun resolverTreatsDuplicateCanonicalEvidenceIdsAsAmbiguous() {
        val url = "https://github.com/duplicate"
        val finding = Finding(
            type = FindingType.Email,
            value = "duplicate@example.test",
            sourceUrl = url,
            evidenceSnippet = "public contact",
            confidence = 0.9f,
            risk = RiskLevel.High,
            remediation = "Review"
        )
        val input = IdentityInput(
            fullName = "Duplicate Example",
            primaryUsername = "duplicate",
            emails = listOf(finding.value)
        )
        val result = EntityResolverV2.resolve(
            input,
            profile(
                username = "duplicate",
                url = url,
                verified = true,
                displayName = "Duplicate Example",
                findings = listOf(finding)
            ),
            evidence = listOf(
                Evidence(id = "profile:$url", kind = EvidenceKind.Profile, value = url, sourceUrl = url),
                finding.toEvidence(),
                finding.toEvidence().copy(value = "same visible value")
            )
        )

        assertTrue(
            result.supporting
                .single { it.feature == CorrelationFeature.ExactPublicEmail }
                .evidenceIds
                .isEmpty()
        )
    }
}
