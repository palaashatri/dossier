package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EvidenceAdapterTest {

    @Test
    fun evidenceToFindingPreservesSharedFields() {
        val evidence = Evidence(
            id = "ev:1",
            kind = EvidenceKind.Email,
            value = "a@b.com",
            sourceUrl = "https://x.com",
            snippet = "found in bio",
            confidence = 0.9f,
            risk = RiskLevel.High,
            signals = listOf("same domain")
        )
        val finding = evidence.toFinding()

        assertEquals(FindingType.Email, finding.type)
        assertEquals("a@b.com", finding.value)
        assertEquals("https://x.com", finding.sourceUrl)
        assertEquals("found in bio", finding.evidenceSnippet)
        assertEquals(0.9f, finding.confidence, 1e-6f)
        assertEquals(RiskLevel.High, finding.risk)
        assertEquals("same domain", finding.remediation)
    }

    @Test
    fun findingToEvidencePreservesSharedFields() {
        val finding = Finding(
            type = FindingType.UsernameReuse,
            value = "palaash_atri",
            sourceUrl = "https://github.com/palaash_atri",
            evidenceSnippet = "dot variant of palaashatri",
            confidence = 0.8f,
            risk = RiskLevel.Medium,
            remediation = "use distinct handles"
        )
        val evidence = finding.toEvidence()

        assertEquals(EvidenceKind.UsernameReuse, evidence.kind)
        assertEquals("palaash_atri", evidence.value)
        assertEquals("https://github.com/palaash_atri", evidence.sourceUrl)
        assertEquals("dot variant of palaashatri", evidence.snippet)
        assertEquals(0.8f, evidence.confidence, 1e-6f)
        assertEquals(RiskLevel.Medium, evidence.risk)
        assertEquals(listOf("use distinct handles"), evidence.signals)
        assertNotNull(evidence.id)
    }

    @Test
    fun everyKindMapsToAFindingType() {
        EvidenceKind.values().forEach { kind ->
            val evidence = Evidence(id = "x", kind = kind, value = "v")
            // Must not throw and must round-trip to a non-null type.
            val finding = evidence.toFinding()
            assertNotNull(finding.type)
        }
    }
}
