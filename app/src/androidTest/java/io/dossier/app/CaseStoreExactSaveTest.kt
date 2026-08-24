package io.dossier.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.case.CaseAnalysisUpdateResult
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.RemediationRecord
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSession
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CaseStoreExactSaveTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = CaseStore(context)

    @Before
    fun resetRuntimeState() {
        store.clear()
        EvidenceRuntimeCache.clear()
        MediaIntelligenceSession.clear()
    }

    @After
    fun clearRuntimeState() {
        store.clear()
        EvidenceRuntimeCache.clear()
        MediaIntelligenceSession.clear()
    }

    @Test
    fun exactSaveAndLoadedCaseMutationsDoNotGraftAnotherSubjectsSessionState() {
        val targetInput = IdentityInput(
            fullName = "Alice Target",
            primaryUsername = "alice-target"
        )
        val target = DossierCase(
            caseId = "exact-case-target",
            createdAt = "2026-08-24 00:00",
            subjectName = "Alice Target",
            input = targetInput,
            aiSummary = "before",
            aiSummaryNeedsRefresh = true
        )
        assertTrue(store.save(target))

        val foreignEvidence = Evidence(
            id = "foreign:bob-email",
            kind = EvidenceKind.Email,
            value = "bob-foreign@example.test",
            sourceUrl = "https://foreign.example.test/bob"
        )
        EvidenceRuntimeCache.replace(EvidenceCollection(evidence = listOf(foreignEvidence)))
        val foreignToken = MediaIntelligenceSession.bindTo(
            IdentityInput(fullName = "Bob Foreign", primaryUsername = "bob-foreign")
        )
        MediaIntelligenceSession.recordImage(
            foreignToken,
            ReverseImageLookupResult(
                gps = null,
                extractedText = "Bob Foreign",
                labels = emptyList(),
                faceDetected = false,
                faceWarning = null,
                resolvedLocation = null,
                mapsUrl = null,
                webEvidence = emptyList()
            )
        )

        assertTrue(
            store.saveExactCase(
                target.copy(
                    aiSummary = "after exact refresh",
                    aiSummaryNeedsRefresh = false
                )
            )
        )
        assertTrue(
            store.recordCorrection(
                target.caseId,
                UserCorrection(
                    correctionId = "target-correction",
                    entityId = "person:alice-target",
                    decision = UserCorrectionDecision.Unsure,
                    createdAtUtc = "2026-08-24T00:01:00Z"
                )
            )
        )
        assertTrue(
            store.upsertRemediation(
                target.caseId,
                RemediationRecord(
                    remediationId = "target-remediation",
                    findingKey = "Email|alice-target@example.test|https://alice.example.test",
                    action = "Review the saved target case.",
                    createdAtUtc = "2026-08-24T00:02:00Z",
                    updatedAtUtc = "2026-08-24T00:02:00Z"
                )
            )
        )

        val loaded = requireNotNull(store.load(target.caseId))
        assertEquals(targetInput, loaded.input)
        assertTrue("Foreign process evidence must not be attached", loaded.evidenceRecords.isEmpty())
        assertTrue("Foreign process media must not be attached", loaded.mediaIntelligence.isEmpty)
        assertTrue(loaded.userCorrections.any { it.correctionId == "target-correction" })
        assertTrue(loaded.remediationRecords.any { it.remediationId == "target-remediation" })
    }

    @Test
    fun initialSaveAttachesOnlyMediaBoundToTheCaseInput() {
        val targetInput = IdentityInput(fullName = "Bound Media Target", primaryUsername = "bound-target")
        val target = DossierCase(
            caseId = "bound-media-case",
            createdAt = "2026-08-24 00:00",
            subjectName = "Bound Media Target",
            input = targetInput
        )
        val token = MediaIntelligenceSession.bindTo(targetInput)
        MediaIntelligenceSession.recordImage(
            token,
            ReverseImageLookupResult(
                gps = null,
                extractedText = "bound evidence",
                labels = emptyList(),
                faceDetected = false,
                faceWarning = null,
                resolvedLocation = null,
                mapsUrl = null,
                webEvidence = emptyList()
            )
        )

        assertTrue(store.save(target))
        assertTrue(requireNotNull(store.load(target.caseId)).mediaIntelligence.imageResults.isNotEmpty())

        val foreign = IdentityInput(fullName = "Other Subject", primaryUsername = "other")
        MediaIntelligenceSession.bindTo(foreign)
        val otherCase = target.copy(caseId = "unbound-media-case", input = targetInput)
        assertTrue(store.save(otherCase))
        assertTrue(requireNotNull(store.load(otherCase.caseId)).mediaIntelligence.isEmpty)
    }

    @Test
    fun delayedAnalysisCannotOverwriteConcurrentCorrectionAndRemediation() {
        val target = DossierCase(
            caseId = "analysis-cas-race",
            createdAt = "2026-08-24 00:00",
            subjectName = "Race Target",
            input = IdentityInput(fullName = "Race Target"),
            aiSummary = "previous summary",
            aiSummaryNeedsRefresh = false
        )
        assertTrue(store.save(target))
        val analysisSnapshot = requireNotNull(store.load(target.caseId))
        val mutationStarted = CountDownLatch(1)
        val mutationFinished = CountDownLatch(1)
        val mutationSucceeded = AtomicBoolean(false)
        val mutationThread = Thread {
            mutationStarted.await()
            val correction = store.recordCorrection(
                target.caseId,
                UserCorrection(
                    correctionId = "race-correction",
                    entityId = "person:race-target",
                    decision = UserCorrectionDecision.ThisIsNotMe,
                    createdAtUtc = "2026-08-24T00:01:00Z"
                )
            )
            val remediation = store.upsertRemediation(
                target.caseId,
                RemediationRecord(
                    remediationId = "race-remediation",
                    findingKey = "Email|race@example.test|https://race.example.test",
                    action = "Review the corrected race case.",
                    createdAtUtc = "2026-08-24T00:02:00Z",
                    updatedAtUtc = "2026-08-24T00:02:00Z"
                )
            )
            mutationSucceeded.set(correction && remediation)
            mutationFinished.countDown()
        }
        mutationThread.start()
        mutationStarted.countDown()
        assertTrue(mutationFinished.await(10, TimeUnit.SECONDS))
        mutationThread.join(10_000)
        assertTrue(mutationSucceeded.get())

        assertEquals(
            CaseAnalysisUpdateResult.Conflict,
            store.saveAnalysisIfUnchanged(analysisSnapshot, "delayed summary")
        )
        val preserved = requireNotNull(store.load(target.caseId))
        assertTrue(preserved.userCorrections.any { it.correctionId == "race-correction" })
        assertTrue(preserved.remediationRecords.any { it.remediationId == "race-remediation" })
        assertEquals(null, preserved.aiSummary)
        assertTrue(preserved.aiSummaryNeedsRefresh)
    }

    @Test
    fun backupRecoveryRestoresLastGoodEncryptedCase() {
        val target = DossierCase(
            caseId = "backup-recovery-case",
            createdAt = "2026-08-24 00:00",
            subjectName = "Backup Target",
            input = IdentityInput(fullName = "Backup Target"),
            aiSummary = "last good summary"
        )
        assertTrue(store.save(target))
        val directory = File(context.filesDir, "dossier_cases")
        val encrypted = File(directory, "${target.caseId}.dcase")
        val backup = File(directory, "${target.caseId}.dcase.bak")
        assertTrue(encrypted.exists())
        encrypted.copyTo(backup, overwrite = true)
        assertTrue(encrypted.delete())

        val recovered = requireNotNull(store.load(target.caseId))
        assertEquals("last good summary", recovered.aiSummary)
        assertTrue(encrypted.exists())
    }

    @Test
    fun legacyPlaintextDeleteFailureReturnsFalseAndKeepsEncryptedRecovery() {
        val target = DossierCase(
            caseId = "legacy-delete-failure-case",
            createdAt = "2026-08-24 00:00",
            subjectName = "Legacy Target",
            input = IdentityInput(fullName = "Legacy Target"),
            aiSummary = "before"
        )
        assertTrue(store.save(target))
        val legacy = File(context.filesDir, "dossier_cases/${target.caseId}.json")
        assertTrue(legacy.mkdirs())
        val retainedPlaintext = File(legacy, "retained-plaintext.json")
        retainedPlaintext.writeText("legacy case material")

        assertFalse(
            store.saveExactCase(
                target.copy(aiSummary = "after", aiSummaryNeedsRefresh = false)
            )
        )
        assertTrue(legacy.exists())
        assertEquals("after", requireNotNull(store.load(target.caseId)).aiSummary)

        assertTrue(retainedPlaintext.delete())
        assertTrue(legacy.delete())
    }

}
