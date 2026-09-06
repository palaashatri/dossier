package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import io.dossier.app.domain.model.ProfileScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveCaseProjectionTest {

    @Test
    fun ignoredFindingDisappearsFromDisplayedRiskButRawEvidenceRemains() {
        val finding = Finding(
            type = FindingType.Email,
            value = "jane@example.test",
            sourceUrl = "https://example.test/contact",
            evidenceSnippet = "Public contact page",
            confidence = 0.95f,
            risk = RiskLevel.High,
            remediation = "Review the public contact detail."
        )
        val evidence = finding.toEvidence()
        val raw = DossierCase(
            caseId = "projection-ignore",
            createdAt = "2026-08-24 00:00",
            subjectName = "Jane Example",
            input = IdentityInput(fullName = "Jane Example"),
            findings = listOf(finding),
            evidenceRecords = listOf(evidence),
            riskLevel = RiskLevel.High
        ).copy(
            userCorrections = listOf(
                UserCorrection(
                    correctionId = "ignore-email",
                    evidenceId = evidence.id,
                    decision = UserCorrectionDecision.IgnoreEvidence,
                    createdAtUtc = "2026-08-24T00:01:00Z"
                )
            )
        )

        val projection = EffectiveCaseProjection.from(raw)
        val presentation = projection.presentationCase()

        assertTrue(projection.findings.isEmpty())
        assertEquals(RiskLevel.Low, projection.riskLevel)
        assertEquals(0, projection.exposure?.overall)
        assertTrue(presentation.findings.isEmpty())
        assertEquals(RiskLevel.Low, presentation.riskLevel)
        assertEquals(0, presentation.exposure?.overall)
        assertEquals(listOf(evidence), projection.rawCase.evidenceRecords)
        assertEquals(listOf(finding), projection.rawCase.findings)
        assertEquals(listOf(evidence), presentation.evidenceRecords)
        assertNotSame(raw, presentation)
    }

    @Test
    fun notMeEntityRemovesProfileClaimButRetainsConflictForAudit() {
        val profileUrl = "https://example.test/jane"
        val finding = Finding(
            type = FindingType.Profile,
            value = profileUrl,
            sourceUrl = profileUrl,
            evidenceSnippet = "Profile page",
            confidence = 0.95f,
            risk = RiskLevel.High,
            remediation = "Review account attribution."
        )
        val evidence = Evidence(
            id = EvidenceIdPolicy.findingId(finding),
            kind = EvidenceKind.Profile,
            value = profileUrl,
            sourceUrl = profileUrl,
            state = io.dossier.app.domain.evidence.EvidenceState.Verified
        )
        val entity = DossierEntity(
            id = "profile:jane",
            type = EntityType.Profile,
            label = profileUrl,
            confidence = 0.95f,
            sourceUrls = listOf(profileUrl),
            evidenceIds = listOf(evidence.id)
        )
        val profile = ProfileScanResult(
            candidate = UsernameCandidate(
                username = "jane",
                platform = Platform.Website,
                url = profileUrl,
                matchType = UsernameMatchType.Exact,
                confidence = 0.95f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Jane Example",
            bio = null,
            links = emptyList(),
            extractedText = "Public profile",
            findings = listOf(finding),
            confidenceSignals = emptyList(),
            verified = true
        )
        val raw = DossierCase(
            caseId = "projection-not-me",
            createdAt = "2026-08-24 00:00",
            subjectName = "Jane Example",
            input = IdentityInput(fullName = "Jane Example"),
            findings = listOf(finding),
            evidenceRecords = listOf(evidence),
            profileResults = listOf(profile),
            entityGraph = EntityGraph(entities = listOf(entity)),
            riskLevel = RiskLevel.High
        ).copy(
            userCorrections = listOf(
                UserCorrection(
                    correctionId = "reject-profile",
                    entityId = entity.id,
                    decision = UserCorrectionDecision.ThisIsNotMe,
                    createdAtUtc = "2026-08-24T00:01:00Z"
                )
            )
        )

        val projection = EffectiveCaseProjection.from(raw)
        val presentation = projection.presentationCase()

        assertTrue("Rejected profile must not keep a finding", projection.findings.isEmpty())
        assertTrue("Rejected profile must not remain in accepted profile results", projection.profileResults.isEmpty())
        assertEquals(RiskLevel.Low, projection.riskLevel)
        assertEquals(0, projection.exposure?.overall)
        assertEquals(GraphNodeState.Conflicting, projection.entityGraph.entity(entity.id)?.state)
        assertTrue("Contradiction must stay inspectable", projection.rejectedEntityIds.contains(entity.id))
        assertEquals(listOf(evidence), projection.rawCase.evidenceRecords)
        assertEquals(listOf(finding), projection.rawCase.findings)
        assertEquals(listOf(profile), projection.rawCase.profileResults)
        assertEquals(listOf(evidence), presentation.evidenceRecords)
        assertEquals(GraphNodeState.Conflicting, presentation.entityGraph.entity(entity.id)?.state)
    }
}
