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
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import io.dossier.app.domain.place.MediaIntelligenceSession
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    fun encryptedCaseRoundTripPreservesImageProvenanceAndClusterGraphEvidence() {
        val candidate = ReverseImageLookupResult.ImageCandidateProvenance(
            id = "imgcandidate:round-trip",
            title = "Public avatar copy",
            imageUrl = "https://images.example.test/avatar-copy.jpg",
            sourcePageUrl = "https://public.example.test/profile",
            source = "Fixture public index",
            acquisitionQuery = "authorized subject avatar",
            comparedImageUrl = "https://images.example.test/avatar-thumb.jpg",
            retrievedAtEpochMillis = 1_787_000_000_000L,
            contentSha256 = "b".repeat(64),
            perceptualHashHex = "0000000000000001",
            comparisonScore = 0.88f,
            exactBytes = false,
            state = ReverseImageLookupResult.ImageCandidateState.Matched,
            clusterId = "imgcluster:round-trip"
        )
        val cluster = ReverseImageLookupResult.ImageCluster(
            id = "imgcluster:round-trip",
            type = ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate,
            representativeCandidateId = candidate.id,
            memberCandidateIds = listOf(candidate.id, "imgcandidate:second")
        )
        val secondCandidate = candidate.copy(
            id = "imgcandidate:second",
            title = "Public avatar near-duplicate",
            imageUrl = "https://images.example.test/avatar-second.jpg",
            contentSha256 = "c".repeat(64),
            comparisonScore = 0.81f,
            state = ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch
        )
        val result = ReverseImageLookupResult(
            gps = null,
            extractedText = null,
            labels = emptyList(),
            faceDetected = false,
            faceWarning = null,
            resolvedLocation = null,
            mapsUrl = null,
            webEvidence = emptyList(),
            visualMatches = emptyList(),
            visualCandidates = listOf(candidate, secondCandidate),
            visualClusters = listOf(cluster),
            visualSearchNote = "Whole-image near-duplicate comparison only; no facial identification."
        )
        val original = DossierCase(
            caseId = "media-provenance-round-trip",
            createdAt = "2026-08-24 00:00",
            subjectName = "Media Provenance Subject",
            input = IdentityInput(fullName = "Media Provenance Subject"),
            mediaIntelligence = MediaIntelligenceSnapshot(imageResults = listOf(result))
        )

        assertTrue(store.save(original))
        val loaded = requireNotNull(store.load(original.caseId))
        val loadedResult = loaded.mediaIntelligence.imageResults.single()

        assertEquals(candidate, loadedResult.visualCandidates.first { it.id == candidate.id })
        assertEquals(secondCandidate, loadedResult.visualCandidates.first { it.id == secondCandidate.id })
        assertEquals(cluster, loadedResult.visualClusters.single())
        assertTrue(loaded.entityGraph.entities.any { it.id == "media-image:${candidate.id}" })
        assertTrue(loaded.entityGraph.edges.any { it.relation == "PERCEPTUAL_NEAR_DUPLICATE" })
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

    @Test
    fun oversizedCaseSaveIsRejectedWithoutReplacingLastGoodEncryptedCase() {
        val target = DossierCase(
            caseId = "bounded-case-save",
            createdAt = "2026-08-24 00:00",
            subjectName = "Bounded Case",
            input = IdentityInput(fullName = "Bounded Case"),
            aiSummary = "last known good"
        )
        assertTrue(store.saveExactCase(target))

        val oversized = target.copy(
            aiSummary = "X".repeat(CaseStore.MAX_PLAINTEXT_BYTES + 1)
        )
        assertFalse(store.saveExactCase(oversized))

        assertEquals("last known good", requireNotNull(store.load(target.caseId)).aiSummary)
        val directory = File(context.filesDir, "dossier_cases")
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".${CaseStore.TEMP_EXTENSION}") })
    }

    @Test
    fun oversizedEncryptedCaseFileFailsClosedBeforeUnboundedRead() {
        val target = DossierCase(
            caseId = "bounded-case-read",
            createdAt = "2026-08-24 00:00",
            subjectName = "Bounded Read",
            input = IdentityInput(fullName = "Bounded Read")
        )
        assertTrue(store.saveExactCase(target))

        val encrypted = File(
            context.filesDir,
            "${CaseStore.CASE_DIRECTORY}/${target.caseId}.${CaseStore.ENCRYPTED_EXTENSION}"
        )
        encrypted.writeBytes(ByteArray((CaseStore.MAX_ENVELOPE_BYTES + 1).toInt()))

        assertEquals(null, store.load(target.caseId))
    }

    @Test
    fun asyncCaseStoreSeamsRoundTripOnProvidedIoDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "case-store-io-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val target = DossierCase(
                caseId = "async-case-store",
                createdAt = "2026-08-24 00:00",
                subjectName = "Async Case",
                input = IdentityInput(fullName = "Async Case")
            )

            assertTrue(store.saveAsync(target, dispatcher))
            assertEquals(target.caseId, store.loadAsync(target.caseId, dispatcher)?.caseId)
            assertEquals(
                listOf(target.caseId),
                store.listEffectiveAsync(dispatcher).map(DossierCase::caseId)
            )
            assertTrue(store.deleteAsync(target.caseId, dispatcher))
            assertEquals(null, store.loadAsync(target.caseId, dispatcher))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

}
