package io.dossier.app

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.risk.RiskScorer
import org.junit.Assert.assertEquals
import org.junit.Test

class RiskScorerTest {

    @Test
    fun testRiskScoring() {
        val scorer = RiskScorer()
        
        assertEquals(RiskLevel.Low, scorer.score(emptyList()))

        val f1 = Finding(FindingType.UsernameReuse, "test", null, null, 1.0f, RiskLevel.Medium, "Fix it")
        assertEquals(RiskLevel.Medium, scorer.score(listOf(f1)))

        val f2 = Finding(FindingType.Email, "test", null, null, 1.0f, RiskLevel.High, "Fix it")
        assertEquals(RiskLevel.High, scorer.score(listOf(f1, f2)))

        val f3 = Finding(FindingType.Phone, "test", null, null, 1.0f, RiskLevel.Critical, "Fix it")
        assertEquals(RiskLevel.Critical, scorer.score(listOf(f1, f2, f3)))
    }
}
