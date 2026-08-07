package io.dossier.app.export

import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRedactionTest {
    @Test
    fun shareSafeRedactionRemovesIdentityBearingFields() {
        val prepared = ReportExporter.prepareExport(
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "jane@example.test",
                    sourceUrl = "https://example.test/jane",
                    evidenceSnippet = "Email jane@example.test appeared here",
                    confidence = 0.9f,
                    risk = RiskLevel.High,
                    remediation = "Remove jane@example.test from the page"
                )
            ),
            subjectName = "Jane Example",
            profileSummaries = listOf("Verified https://example.test/jane"),
            aiSummary = "Jane Example likely controls the account.",
            faceMatches = listOf(
                FaceConsistencyMatch(
                    profileUrl = "https://example.test/jane/photo",
                    similarityScore = 0.72f
                )
            ),
            entityGraphSummary = "Jane Example -> example.test",
            breachDigests = listOf("jane@example.test appeared in ExampleBreach"),
            redactionMode = ExportRedactionMode.ShareSafe
        )

        assertTrue(prepared.redacted)
        assertEquals("Redacted subject", prepared.subjectName)
        assertFalse(prepared.findings.single().value.contains("jane", ignoreCase = true))
        assertNull(prepared.findings.single().sourceUrl)
        assertNull(prepared.findings.single().evidenceSnippet)
        assertFalse(prepared.findings.single().remediation.contains("jane@example.test"))
        assertFalse(prepared.profileSummaries.single().contains("example.test"))
        assertFalse(prepared.faceMatches.single().profileUrl.contains("example.test"))
        assertFalse(prepared.breachDigests.single().contains("jane@example.test"))
        assertFalse(prepared.aiSummary.orEmpty().contains("Jane Example"))
        assertFalse(prepared.entityGraphSummary.orEmpty().contains("Jane Example"))
    }

    @Test
    fun noRedactionPreservesEvidence() {
        val finding = Finding(
            type = FindingType.Username,
            value = "sample_user",
            sourceUrl = "https://example.test/sample_user",
            evidenceSnippet = "Public profile",
            confidence = 0.8f,
            risk = RiskLevel.Medium,
            remediation = "Review privacy settings"
        )

        val prepared = ReportExporter.prepareExport(
            findings = listOf(finding),
            subjectName = "Jane Example",
            redactionMode = ExportRedactionMode.None
        )

        assertFalse(prepared.redacted)
        assertEquals("Jane Example", prepared.subjectName)
        assertEquals(finding, prepared.findings.single())
    }
}
