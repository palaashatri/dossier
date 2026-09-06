package io.dossier.app.domain.scanner

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.EffectiveCaseProjection
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanSessionDraftCorrectionTest {

    private val input = IdentityInput(fullName = "Draft correction subject")
    private val finding = Finding(
        type = FindingType.Profile,
        value = "profile evidence",
        sourceUrl = "https://evidence.example.test/profile",
        evidenceSnippet = "Direct public profile evidence",
        confidence = 0.8f,
        risk = RiskLevel.Medium,
        remediation = "Review the source manually."
    )

    @Before
    fun setUp() {
        ScanSession.restoreFromCase(
            DossierCase(
                createdAt = "2026-08-25 00:00",
                subjectName = input.fullName,
                input = input,
                findings = listOf(finding),
                evidenceRecords = listOf(finding.toEvidence())
            )
        )
    }

    @After
    fun tearDown() {
        EvidenceRuntimeCache.clear()
        ScanSession.restoreFromCase(
            DossierCase(
                createdAt = "2026-08-25 00:00",
                subjectName = "empty",
                input = IdentityInput(fullName = "empty")
            )
        )
    }

    @Test
    fun draftCorrectionIsIncludedOnExplicitSaveInputAndKeepsRawEvidence() {
        val correction = UserCorrection(
            correctionId = "draft-ignore",
            evidenceId = finding.toEvidence().id,
            decision = UserCorrectionDecision.IgnoreEvidence,
            createdAtUtc = "2026-08-25T00:01:00Z"
        )

        assertTrue(ScanSession.recordDraftCorrection(correction))

        val built = requireNotNull(ScanSession.buildCase())
        assertEquals(listOf(correction), built.userCorrections)
        assertEquals(listOf(finding), built.findings)
        assertEquals(EvidenceState.Observed, built.evidenceRecords.single().state)
        assertTrue(EffectiveCaseProjection.from(built).findings.isEmpty())

        ScanSession.restoreFromCase(built)
        assertEquals(listOf(correction), ScanSession.userCorrections.value)
        assertEquals(listOf(finding), ScanSession.findings.value)
        assertEquals(EvidenceState.Observed, EvidenceRuntimeCache.collection.value.evidence.single().state)
    }

    @Test
    fun draftCorrectionsReplaceSameTargetAndRejectUnboundedGrowth() {
        repeat(ScanSession.MAX_DRAFT_CORRECTIONS) { index ->
            assertTrue(
                ScanSession.recordDraftCorrection(
                    UserCorrection(
                        correctionId = "draft-$index",
                        evidenceId = "evidence-$index",
                        decision = UserCorrectionDecision.Unsure,
                        createdAtUtc = "2026-08-25T00:${index.toString().padStart(2, '0')}:00Z"
                    )
                )
            )
        }
        assertFalse(
            ScanSession.recordDraftCorrection(
                UserCorrection(
                    correctionId = "draft-overflow",
                    evidenceId = "evidence-overflow",
                    decision = UserCorrectionDecision.IgnoreEvidence,
                    createdAtUtc = "2026-08-25T01:00:00Z"
                )
            )
        )
        assertEquals(ScanSession.MAX_DRAFT_CORRECTIONS, ScanSession.userCorrections.value.size)

        assertTrue(
            ScanSession.recordDraftCorrection(
                UserCorrection(
                    correctionId = "draft-replacement",
                    evidenceId = "evidence-0",
                    decision = UserCorrectionDecision.ThisIsNotMe,
                    createdAtUtc = "2026-08-25T02:00:00Z"
                )
            )
        )
        assertEquals(ScanSession.MAX_DRAFT_CORRECTIONS, ScanSession.userCorrections.value.size)
        assertEquals(
            UserCorrectionDecision.ThisIsNotMe,
            ScanSession.userCorrections.value.first { it.evidenceId == "evidence-0" }.decision
        )
        assertFalse(
            ScanSession.recordDraftCorrection(
                UserCorrection(
                    correctionId = "invalid-target",
                    decision = UserCorrectionDecision.Unsure,
                    createdAtUtc = "2026-08-25T03:00:00Z"
                )
            )
        )
    }
}
