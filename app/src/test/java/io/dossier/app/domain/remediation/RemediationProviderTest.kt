package io.dossier.app.domain.remediation

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
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
}
