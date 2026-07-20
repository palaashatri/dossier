package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureEngineTest {

    private fun f(type: FindingType, risk: RiskLevel, value: String = "v") = Finding(
        type = type, value = value, sourceUrl = null, evidenceSnippet = null,
        confidence = 0.9f, risk = risk, remediation = ""
    )

    @Test
    fun mapsFindingsToDimensions() {
        val findings = listOf(
            f(FindingType.Email, RiskLevel.Critical, "a@b.com"),
            f(FindingType.Organization, RiskLevel.High, "Acme"),
            f(FindingType.ImageConsistency, RiskLevel.Medium, "face")
        )
        val result = ExposureEngine().score(findings)
        val contact = result.dimensions.first { it.dimension == ExposureDimension.Contact }
        assertEquals(100, contact.score)
        val image = result.dimensions.first { it.dimension == ExposureDimension.Image }
        assertEquals(50, image.score)
        assertTrue(result.topFindings.isNotEmpty())
    }

    @Test
    fun emptyFindingsYieldsZero() {
        val result = ExposureEngine().score(emptyList())
        assertEquals(0, result.overall)
        assertEquals(0, result.topFindings.size)
    }

    @Test
    fun breachLoadsContactAndIdentity() {
        val result = ExposureEngine().score(
            emptyList(),
            listOf(BreachDigest(email = "a@b.com", breachCount = 2, sources = listOf("X")))
        )
        val contact = result.dimensions.first { it.dimension == ExposureDimension.Contact }
        assertEquals(80, contact.score)
        val identity = result.dimensions.first { it.dimension == ExposureDimension.Identity }
        assertEquals(80, identity.score)
    }
}
