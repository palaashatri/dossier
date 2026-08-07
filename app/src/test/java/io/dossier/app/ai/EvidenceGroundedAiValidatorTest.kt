package io.dossier.app.ai

import io.dossier.app.domain.ai.AiAnalysisClaim
import io.dossier.app.domain.ai.AiAnalysisResult
import io.dossier.app.domain.ai.AiClaimConfidence
import io.dossier.app.domain.ai.EvidenceGroundedAiValidator
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
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
}
