package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceIdPolicyTest {
    private val finding = Finding(
        type = FindingType.Email,
        value = "jane@example.test",
        sourceUrl = "https://example.test/jane/contact",
        evidenceSnippet = "Public contact page",
        confidence = 0.9f,
        risk = RiskLevel.High,
        remediation = "Remove public contact detail"
    )

    @Test
    fun currentFindingIdIsDeterministicAndDoesNotContainRawValueOrUrl() {
        val first = finding.toEvidence().id
        val second = finding.toEvidence().id

        assertEquals(first, second)
        assertTrue(first.startsWith("ev2:"))
        assertFalse(first.contains("jane", ignoreCase = true))
        assertFalse(first.contains("example.test", ignoreCase = true))
        assertEquals(36, first.length)
    }

    @Test
    fun legacyRawIdMigratesToSameCurrentId() {
        val legacy = EvidenceIdPolicy.legacyFindingId(finding)
        assertTrue(legacy.contains("jane@example.test"))
        assertEquals(finding.toEvidence().id, EvidenceIdPolicy.migrate(legacy))
        assertEquals(finding.toEvidence().id, EvidenceIdPolicy.migrate(finding.toEvidence().id))
    }

    @Test
    fun differentFindingProducesDifferentOpaqueId() {
        val changed = finding.copy(value = "other@example.test")
        assertNotEquals(finding.toEvidence().id, changed.toEvidence().id)
    }
}
