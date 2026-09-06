package io.dossier.app.domain.scanner

import io.dossier.app.domain.model.FindingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionBreachCheckpointTest {

    @Test
    fun restoredBreachCheckpointReconstructsOnlyOriginalFindingKinds() {
        val checkpoint = BreachStageCheckpoint(
            requestId = "123e4567-e89b-12d3-a456-426614174000",
            planFingerprint = "a".repeat(64),
            ownerId = "223e4567-e89b-42d3-a456-426614174000",
            capturedAtEpochMillis = 1_000L,
            results = listOf(
                BreachStageCheckpointResult(
                    email = "breached@example.com",
                    breachCount = 2,
                    breachTitles = listOf("First", "Second"),
                    sources = listOf("First", "Second"),
                    note = "HTTP 200"
                ),
                BreachStageCheckpointResult(
                    email = "indexed@example.com",
                    breachCount = 0,
                    publicHitCount = 2,
                    publicEvidenceUrls = listOf("https://example.test/one", "https://example.test/two"),
                    sources = listOf("https://example.test/one", "https://example.test/two")
                ),
                BreachStageCheckpointResult(
                    email = "clean@example.com",
                    breachCount = 0,
                    publicHitCount = 0
                )
            )
        )

        val findings = ScanSession.findingsFromBreachCheckpoint(checkpoint)

        assertEquals(2, findings.size)
        assertEquals(FindingType.Email, findings[0].type)
        assertEquals("breached@example.com", findings[0].value)
        assertTrue(findings[0].evidenceSnippet!!.contains("First, Second"))
        assertEquals(FindingType.SensitiveSnippet, findings[1].type)
        assertEquals("https://example.test/one", findings[1].sourceUrl)
        assertTrue(findings[1].evidenceSnippet!!.contains("2 hit(s)"))
        assertNull(findings.singleOrNull { it.value == "clean@example.com" })
    }

    @Test
    fun restoredCheckpointDoesNotClaimFreshProviderErrorText() {
        val checkpoint = BreachStageCheckpoint(
            requestId = "123e4567-e89b-12d3-a456-426614174000",
            planFingerprint = "a".repeat(64),
            ownerId = "223e4567-e89b-42d3-a456-426614174000",
            capturedAtEpochMillis = 1_000L,
            results = listOf(
                BreachStageCheckpointResult(
                    email = "indexed@example.com",
                    breachCount = 0,
                    publicHitCount = 1,
                    publicEvidenceUrls = listOf("https://example.test/one"),
                    sources = listOf("https://example.test/one"),
                    note = null
                )
            )
        )

        val finding = ScanSession.findingsFromBreachCheckpoint(checkpoint).single()

        assertEquals("Public index mentions this email (1 hit(s)).", finding.evidenceSnippet)
        assertTrue(finding.evidenceSnippet!!.contains("1 hit(s)"))
    }
}
