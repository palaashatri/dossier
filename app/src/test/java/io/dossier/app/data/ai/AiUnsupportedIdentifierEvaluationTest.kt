package io.dossier.app.data.ai

import io.dossier.app.domain.ai.AiAnalysisClaim
import io.dossier.app.domain.ai.AiAnalysisResult
import io.dossier.app.domain.ai.AiConfidence
import io.dossier.app.domain.ai.EvidenceGroundedAiValidator
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiUnsupportedIdentifierEvaluationTest {
    private val evidence = Finding(
        type = FindingType.Email,
        value = "jane@example.test",
        sourceUrl = "https://example.test/contact",
        evidenceSnippet = "Public contact page",
        confidence = 0.9f,
        risk = RiskLevel.High,
        remediation = "Remove public contact detail"
    ).toEvidence()

    @Test
    fun inventedEmailIsRejectedEvenWhenClaimCitesRealEvidenceId() {
        val result = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "The public email is invented@example.test.",
                        confidence = AiConfidence.HIGH,
                        supportingEvidence = listOf(evidence.id),
                        reasoningSummary = "The cited evidence supposedly contains invented@example.test."
                    )
                )
            ),
            listOf(evidence)
        )

        assertTrue(result.acceptedClaims.isEmpty())
        assertEquals("unsupported_identifier_in_claim", result.rejectedClaims.single().reason)
    }

    @Test
    fun inventedUrlIsRejectedEvenWhenClaimCitesRealEvidenceId() {
        val result = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "A profile exists at https://invented.example.test/profile.",
                        confidence = AiConfidence.MEDIUM,
                        supportingEvidence = listOf(evidence.id),
                        reasoningSummary = "This URL does not occur in the cited evidence."
                    )
                )
            ),
            listOf(evidence)
        )

        assertTrue(result.acceptedClaims.isEmpty())
        assertEquals("unsupported_identifier_in_claim", result.rejectedClaims.single().reason)
    }

    @Test
    fun exactIdentifierFromCitedEvidenceRemainsAllowed() {
        val result = EvidenceGroundedAiValidator.validate(
            AiAnalysisResult(
                claims = listOf(
                    AiAnalysisClaim(
                        claim = "The cited public evidence contains jane@example.test.",
                        confidence = AiConfidence.HIGH,
                        supportingEvidence = listOf(evidence.id),
                        reasoningSummary = "The identifier is present in the cited evidence at https://example.test/contact."
                    )
                )
            ),
            listOf(evidence)
        )

        assertEquals(1, result.acceptedClaims.size)
        assertTrue(result.rejectedClaims.isEmpty())
    }
}
