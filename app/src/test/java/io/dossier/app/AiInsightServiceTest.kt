package io.dossier.app

import io.dossier.app.data.ai.AiInsightService
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class AiInsightServiceTest {

    @Test
    fun buildPrompt_includesFindingsAndManualVerificationRequest() {
        val prompt = AiInsightService.buildDossierSummaryPrompt(
            input = IdentityInput(fullName = "Jane Doe"),
            profileResults = emptyList(),
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "jane@example.com",
                    sourceUrl = "https://example.com",
                    evidenceSnippet = "Contact jane@example.com",
                    confidence = 0.9f,
                    risk = RiskLevel.High,
                    remediation = "Remove it"
                )
            )
        )

        assertTrue(prompt.contains("Jane Doe"))
        assertTrue(prompt.contains("Email"))
        assertTrue(prompt.contains("jane@example.com"))
        assertTrue(prompt.contains("manually verified", ignoreCase = true))
    }

    @Test
    fun baselineSummary_mentionsLocalAnalysisAndFindings() {
        val summary = AiInsightService.buildBaselineSummary(
            input = IdentityInput(fullName = "Jane Doe"),
            profileResults = emptyList(),
            findings = listOf(
                Finding(
                    type = FindingType.PublicImageEvidence,
                    value = "Jane Doe avatar",
                    sourceUrl = "https://example.com/photo",
                    evidenceSnippet = null,
                    confidence = 0.7f,
                    risk = RiskLevel.Medium,
                    remediation = "Review public image results."
                )
            )
        )

        assertTrue(summary.contains("Local baseline analysis"))
        assertTrue(summary.contains("PublicImageEvidence"))
        assertTrue(summary.contains("Review public image results."))
    }
}
