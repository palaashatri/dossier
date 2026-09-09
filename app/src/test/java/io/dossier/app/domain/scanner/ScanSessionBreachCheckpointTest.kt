package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
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

    @Test
    fun breachEmailCollectionIncludesVerifiedDirectEmailsButNotCandidateSnippets() {
        val profileUrl = "https://profile.example.test/jane"
        val profile = ProfileScanResult(
            candidate = UsernameCandidate(
                username = "jane",
                platform = Platform.Website,
                url = profileUrl,
                matchType = io.dossier.app.domain.model.UsernameMatchType.Exact,
                confidence = 0.95f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Jane Example",
            bio = null,
            links = emptyList(),
            extractedText = "Jane Example discovered@example.test",
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "discovered@example.test",
                    sourceUrl = profileUrl,
                    evidenceSnippet = "Email: discovered@example.test",
                    confidence = 0.95f,
                    risk = RiskLevel.High,
                    remediation = "Review email exposure",
                    attribution = FindingAttribution.Verified
                )
            ),
            confidenceSignals = listOf("direct profile"),
            verified = true
        )
        val candidateSnippet = Evidence(
            id = "candidate-email",
            kind = EvidenceKind.Email,
            value = "snippet@example.test",
            sourceUrl = "https://search.example.test",
            state = EvidenceState.Candidate,
            sourceClassification = ExposureSourceClassification.PUBLIC_WEB
        )

        val emails = ScanSession.emailsForBreachChecks(
            input = IdentityInput(fullName = "Jane Example", emails = listOf("input@example.test")),
            profileResults = listOf(profile),
            typedSeedEvidence = EvidenceCollection(evidence = listOf(candidateSnippet))
        )

        assertEquals(
            listOf("input@example.test", "discovered@example.test"),
            emails
        )
        assertTrue(emails.none { it == "snippet@example.test" })
    }
}
