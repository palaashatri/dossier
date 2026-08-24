package io.dossier.app.ai

import io.dossier.app.domain.ai.AiAnalysisClaim
import io.dossier.app.domain.ai.AiAnalysisResult
import io.dossier.app.domain.ai.AiRemediationLink
import io.dossier.app.domain.ai.AiRemediationLinkState
import io.dossier.app.domain.ai.AiClaimConfidence
import io.dossier.app.domain.ai.EvidenceGroundedAiValidator
import io.dossier.app.domain.case.RemediationRecord
import io.dossier.app.domain.case.RemediationStatus
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGroundedAiValidatorTest {
    private val evidence = listOf(
        Evidence(id = "E1", kind = EvidenceKind.Username, value = "rare_handle"),
        Evidence(id = "E2", kind = EvidenceKind.Profile, value = "https://example.test/rare_handle"),
        Evidence(id = "E9", kind = EvidenceKind.Profile, value = "conflicting profile")
    )

    @Test
    fun rejectsClaimWithHallucinatedEvidenceId() {
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                listOf(
                    AiAnalysisClaim(
                        claim = "Two accounts are related",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E404"),
                        reasoningSummary = "The model says so"
                    )
                )
            ),
            evidence
        )

        assertTrue(validated.acceptedClaims.isEmpty())
        assertEquals(1, validated.rejectedClaims.size)
        assertTrue(validated.rejectedClaims.first().reasons.any { it.contains("Unknown supporting") })
    }

    @Test
    fun rejectsUncitedFactualClaim() {
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                listOf(
                    AiAnalysisClaim(
                        claim = "Exposure exists",
                        confidence = AiClaimConfidence.MEDIUM,
                        supportingEvidence = emptyList(),
                        reasoningSummary = "No citation"
                    )
                )
            ),
            evidence
        )
        assertTrue(validated.acceptedClaims.isEmpty())
    }

    @Test
    fun highClaimWithContradictionIsDowngradedToConflicting() {
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                listOf(
                    AiAnalysisClaim(
                        claim = "These accounts may belong to the same identity",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E1", "E2"),
                        contradictingEvidence = listOf("E9"),
                        reasoningSummary = "Shared handle but contradictory profile evidence exists"
                    )
                )
            ),
            evidence
        )

        assertEquals(1, validated.acceptedClaims.size)
        assertEquals(AiClaimConfidence.CONFLICTING, validated.acceptedClaims.first().confidence)
    }

    @Test
    fun validCitedClaimPasses() {
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                listOf(
                    AiAnalysisClaim(
                        claim = "A public profile reuses the supplied handle",
                        confidence = AiClaimConfidence.MEDIUM,
                        supportingEvidence = listOf("E1", "E2"),
                        reasoningSummary = "The profile URL and username evidence share the same handle"
                    )
                )
            ),
            evidence
        )

        assertEquals(1, validated.acceptedClaims.size)
        assertTrue(validated.rejectedClaims.isEmpty())
    }

    @Test
    fun rejectedOrUnavailableEvidenceCannotSupportAClaim() {
        val rejected = evidence + Evidence(
            id = "ER",
            kind = EvidenceKind.Email,
            value = "rejected@example.test",
            state = EvidenceState.Rejected
        ) + Evidence(
            id = "EU",
            kind = EvidenceKind.Email,
            value = "unavailable@example.test",
            state = EvidenceState.Unavailable
        )
        val result = AiAnalysisResult(
            claims = listOf(
                AiAnalysisClaim(
                    claim = "A rejected email is exposed",
                    confidence = AiClaimConfidence.HIGH,
                    supportingEvidence = listOf("ER"),
                    reasoningSummary = "The cited record says so"
                ),
                AiAnalysisClaim(
                    claim = "An unavailable email is exposed",
                    confidence = AiClaimConfidence.HIGH,
                    supportingEvidence = listOf("EU"),
                    reasoningSummary = "The cited record says so"
                ),
                AiAnalysisClaim(
                    claim = "A profile has a rejected contradiction",
                    confidence = AiClaimConfidence.HIGH,
                    supportingEvidence = listOf("E1"),
                    contradictingEvidence = listOf("ER"),
                    reasoningSummary = "The cited contradiction says so"
                )
            )
        )

        val validated = EvidenceGroundedAiValidator.validate(result, rejected)

        assertTrue(validated.acceptedClaims.isEmpty())
        assertEquals(3, validated.rejectedClaims.size)
        assertTrue(validated.rejectedClaims.all { claim ->
            claim.reasons.any { reason -> reason.contains("rejected or unavailable") }
        })
    }

    @Test
    fun observedOrCandidateSupportIsAcceptedButQualifiedAsUnresolved() {
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "A candidate profile may be related",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E2"),
                        reasoningSummary = "The candidate requires manual verification"
                    )
                )
            ),
            evidence
        )

        assertEquals(1, validated.acceptedClaims.size)
        assertEquals(AiClaimConfidence.UNRESOLVED, validated.acceptedClaims.single().confidence)
    }

    @Test
    fun rejectsAbsoluteOwnershipAndSamePersonAttributionEvenWithProfileEvidence() {
        val verifiedProfile = Evidence(
            id = "EV",
            kind = EvidenceKind.Profile,
            value = "https://example.test/alice",
            state = EvidenceState.Verified
        )
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "Alice owns this profile",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E2"),
                        reasoningSummary = "The profile is publicly visible"
                    ),
                    AiAnalysisClaim(
                        claim = "These accounts are the same person",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("EV"),
                        reasoningSummary = "The profile evidence is verified"
                    ),
                    AiAnalysisClaim(
                        claim = "The profile page lists a public username",
                        confidence = AiClaimConfidence.MEDIUM,
                        supportingEvidence = listOf("E2"),
                        reasoningSummary = "The page is a candidate observation"
                    )
                )
            ),
            evidence + verifiedProfile
        )

        assertEquals(1, validated.acceptedClaims.size)
        assertEquals("The profile page lists a public username", validated.acceptedClaims.single().claim)
        assertEquals(2, validated.rejectedClaims.size)
        assertTrue(validated.rejectedClaims.all { rejected ->
            rejected.reason == "identity_attribution_requires_confirmation"
        })
    }

    @Test
    fun expandedOwnershipVerbsRejectUnqualifiedIdentityAttributionButQualifiedHypothesisStaysUnresolved() {
        val aliceEvidence = Evidence(
            id = "EA",
            kind = EvidenceKind.Profile,
            value = "https://example.test/alice",
            snippet = "Alice public profile",
            state = EvidenceState.Observed
        )
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "Alice uses this account",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("EA"),
                        reasoningSummary = "The page is visible"
                    ),
                    AiAnalysisClaim(
                        claim = "The account operates this profile",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("EA"),
                        reasoningSummary = "The page is visible"
                    ),
                    AiAnalysisClaim(
                        claim = "The account belongs-to Alice",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("EA"),
                        reasoningSummary = "The page is visible"
                    ),
                    AiAnalysisClaim(
                        claim = "The profile is account-of Alice",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("EA"),
                        reasoningSummary = "The page is visible"
                    ),
                    AiAnalysisClaim(
                        claim = "Alice may use this account",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("EA"),
                        reasoningSummary = "The candidate remains unresolved"
                    )
                )
            ),
            listOf(aliceEvidence)
        )

        assertEquals(1, validated.acceptedClaims.size)
        assertEquals(AiClaimConfidence.UNRESOLVED, validated.acceptedClaims.single().confidence)
        assertEquals(4, validated.rejectedClaims.size)
        assertTrue(validated.rejectedClaims.all { rejected ->
            rejected.reason == "identity_attribution_requires_confirmation"
        })
    }

    @Test
    fun qualifierInAnotherSentenceOrClauseCannotBlessAbsoluteAttribution() {
        val attributionEvidence = Evidence(
            id = "ATTRIBUTION",
            kind = EvidenceKind.Profile,
            value = "https://example.test/jane",
            snippet = "Jane public profile",
            state = EvidenceState.Verified
        )
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "This account belongs to Jane; another candidate may be related",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf(attributionEvidence.id),
                        reasoningSummary = "The profile is publicly visible"
                    ),
                    AiAnalysisClaim(
                        claim = "This account belongs to Jane",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf(attributionEvidence.id),
                        reasoningSummary = "Another candidate may be related in a later sentence"
                    )
                )
            ),
            listOf(attributionEvidence)
        )

        assertTrue(validated.acceptedClaims.isEmpty())
        assertEquals(2, validated.rejectedClaims.size)
        assertTrue(validated.rejectedClaims.all { rejected ->
            rejected.reason == "identity_attribution_requires_confirmation"
        })
    }

    @Test
    fun explicitPhoneUsernameEmailAndUrlMustMatchCitedEvidenceExactly() {
        val cited = listOf(
            Evidence(
                id = "EMAIL",
                kind = EvidenceKind.Email,
                value = "alice@example.com"
            ),
            Evidence(
                id = "URL",
                kind = EvidenceKind.Profile,
                value = "https://example.test:443/profile"
            ),
            Evidence(
                id = "PHONE",
                kind = EvidenceKind.Phone,
                value = "+1 555 111 2222"
            ),
            Evidence(
                id = "HANDLE",
                kind = EvidenceKind.Username,
                value = "rare_handle",
                snippet = "@rare_handle"
            )
        )
        val exact = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "alice@example.com and https://example.test/profile were observed",
                        confidence = AiClaimConfidence.MEDIUM,
                        supportingEvidence = cited.map(Evidence::id),
                        reasoningSummary = "phone +1 555 111 2222 and username @rare_handle are recorded"
                    )
                )
            ),
            cited
        )
        assertEquals(1, exact.acceptedClaims.size)

        val prefix = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "alice@example.com was observed",
                        confidence = AiClaimConfidence.MEDIUM,
                        supportingEvidence = listOf("EMAIL_PREFIX"),
                        reasoningSummary = "The cited email is present"
                    ),
                    AiAnalysisClaim(
                        claim = "https://evil.example was observed",
                        confidence = AiClaimConfidence.MEDIUM,
                        supportingEvidence = listOf("URL_PREFIX"),
                        reasoningSummary = "The cited URL is present"
                    )
                )
            ),
            listOf(
                Evidence(
                    id = "EMAIL_PREFIX",
                    kind = EvidenceKind.Email,
                    value = "alice@example.com.au"
                ),
                Evidence(
                    id = "URL_PREFIX",
                    kind = EvidenceKind.Profile,
                    value = "https://evil.example.com"
                )
            )
        )
        assertEquals(0, prefix.acceptedClaims.size)
        assertEquals(2, prefix.rejectedClaims.size)
        assertTrue(prefix.rejectedClaims.all { rejected ->
            rejected.reasons.any { reason -> reason.startsWith("Unsupported identifier") }
        })
    }

    @Test
    fun probableEvidenceCannotPreserveHighConfidence() {
        val probable = Evidence(
            id = "EP",
            kind = EvidenceKind.Profile,
            value = "https://example.test/probable",
            state = EvidenceState.Probable
        )
        val validated = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "https://example.test/probable was observed",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("EP"),
                        reasoningSummary = "The profile was reported"
                    )
                )
            ),
            listOf(probable)
        )

        assertEquals(1, validated.acceptedClaims.size)
        assertEquals(AiClaimConfidence.UNRESOLVED, validated.acceptedClaims.single().confidence)
    }

    @Test
    fun remediationCompletionClaimsAreRejectedButProspectiveAdviceIsAllowed() {
        val result = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "The provider removed this data",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E1"),
                        reasoningSummary = "The provider confirmed deletion"
                    ),
                    AiAnalysisClaim(
                        claim = "Request deletion from the provider",
                        confidence = AiClaimConfidence.MEDIUM,
                        supportingEvidence = listOf("E1"),
                        reasoningSummary = "Submit a request through the official channel",
                        recommendedAction = "Open the provider request form"
                    )
                )
            ),
            evidence
        )

        assertEquals(1, result.acceptedClaims.size)
        assertEquals("Request deletion from the provider", result.acceptedClaims.single().claim)
        assertEquals(1, result.rejectedClaims.size)
        assertEquals(
            "remediation_outcome_requires_verification",
            result.rejectedClaims.single().reason
        )
    }

    @Test
    fun verifiedRemediationLinkCanAuthorizeOutcomeForItsCitedEvidenceOnly() {
        val verified = AiRemediationLink(
            record = RemediationRecord(
                remediationId = "remediation-e1",
                findingKey = "Username|rare_handle|",
                action = "Request removal",
                status = RemediationStatus.Completed,
                createdAtUtc = "2026-08-24T00:00:00Z",
                updatedAtUtc = "2026-08-24T00:00:01Z",
                verifiedByScanId = "scan-after-remediation"
            ),
            evidenceId = "E1",
            effective = true,
            state = AiRemediationLinkState.Effective
        )

        val accepted = EvidenceGroundedAiValidator.validate(
            result = AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "The provider confirmed removal of this data",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E1"),
                        reasoningSummary = "A later verification scan is linked to the cited evidence."
                    )
                )
            ),
            evidence = evidence,
            remediationLinks = listOf(verified)
        )

        assertEquals(1, accepted.acceptedClaims.size)
        assertTrue(accepted.rejectedClaims.isEmpty())

        val mismatched = EvidenceGroundedAiValidator.validate(
            result = AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "The provider confirmed removal of this data",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E2"),
                        reasoningSummary = "The model cites a different profile."
                    )
                )
            ),
            evidence = evidence,
            remediationLinks = listOf(verified)
        )

        assertTrue(mismatched.acceptedClaims.isEmpty())
        assertEquals(
            "remediation_outcome_requires_verification",
            mismatched.rejectedClaims.single().reason
        )
    }

    @Test
    fun completedRemediationWithoutVerificationScanRemainsFailClosed() {
        val unverified = AiRemediationLink(
            record = RemediationRecord(
                remediationId = "remediation-e1-unverified",
                findingKey = "Username|rare_handle|",
                action = "Request removal",
                status = RemediationStatus.Completed,
                createdAtUtc = "2026-08-24T00:00:00Z",
                updatedAtUtc = "2026-08-24T00:00:01Z"
            ),
            evidenceId = "E1",
            effective = true,
            state = AiRemediationLinkState.Effective
        )

        val result = EvidenceGroundedAiValidator.validate(
            result = AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "The provider confirmed removal of this data",
                        confidence = AiClaimConfidence.HIGH,
                        supportingEvidence = listOf("E1"),
                        reasoningSummary = "Only a workflow status is available."
                    )
                )
            ),
            evidence = evidence,
            remediationLinks = listOf(unverified)
        )

        assertTrue(result.acceptedClaims.isEmpty())
        assertEquals(
            "remediation_outcome_requires_verification",
            result.rejectedClaims.single().reason
        )
    }
}
