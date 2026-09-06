package io.dossier.app.evidence

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceRecordTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun legacyHighConfidenceFindingDoesNotBecomeVerifiedEvidence() {
        val evidence = Finding(
            type = FindingType.Email,
            value = "sample@example.test",
            sourceUrl = "https://example.test/profile",
            evidenceSnippet = "Publicly listed contact",
            confidence = 0.99f,
            risk = RiskLevel.Medium,
            remediation = "Review visibility"
        ).toEvidence()

        assertEquals(EvidenceState.Observed, evidence.state)
        assertEquals(EvidenceReliability.Unknown, evidence.reliability)
        assertNull(evidence.retrievedAtEpochMillis)
        assertNull(evidence.parserVersion)
        assertFalse(evidence.historical)
    }

    @Test
    fun searchEvidenceRemainsCandidateEvidence() {
        val evidence = Finding(
            type = FindingType.PublicSearchEvidence,
            value = "Indexed page candidate",
            sourceUrl = "https://example.test/indexed",
            evidenceSnippet = "Search result only",
            confidence = 0.95f,
            risk = RiskLevel.Low,
            remediation = "Verify source directly"
        ).toEvidence()

        assertEquals(EvidenceState.Candidate, evidence.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, evidence.reliability)
    }

    @Test
    fun fullProvenanceRoundTripsThroughSerialization() {
        val evidence = Evidence(
            id = "E-001",
            kind = EvidenceKind.Profile,
            value = "sample_user",
            sourceUrl = "https://example.test/sample_user",
            snippet = "Direct profile observation",
            confidence = 0.8f,
            risk = RiskLevel.Low,
            signals = listOf("explicit cross-link"),
            providerId = "example",
            retrievedAtEpochMillis = 1_700_000_000_000L,
            observedAtEpochMillis = 1_699_000_000_000L,
            state = EvidenceState.Probable,
            reliability = EvidenceReliability.DirectPublicProfile,
            contentHashSha256 = "a".repeat(64),
            parserVersion = "example-v2",
            historical = true
        )

        val decoded = json.decodeFromString<Evidence>(json.encodeToString(evidence))
        assertEquals(evidence, decoded)
        assertTrue(decoded.historical)
        assertEquals("example", decoded.providerId)
    }
}
