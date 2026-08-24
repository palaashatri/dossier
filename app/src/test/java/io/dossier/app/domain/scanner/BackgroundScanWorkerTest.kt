package io.dossier.app.domain.scanner

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.io.File
import java.util.UUID

class BackgroundScanWorkerTest {

    @After
    fun tearDown() {
        BackgroundScanManager.resetSeams()
    }

    @Test
    fun secureInputDataContainsOnlyOpaqueRequestReference() {
        val requestId = "6f0d7f1b-cb5e-4f14-b7b0-3dcf3c06d9bd"

        val data = BackgroundScanWorker.secureInputData(requestId)
        val serialized = data.toByteArray()
        val decoded = Data.fromByteArray(serialized)

        assertEquals(setOf(BackgroundScanWorker.KEY_REQUEST_ID), data.keyValueMap.keys)
        assertEquals(requestId, data.getString(BackgroundScanWorker.KEY_REQUEST_ID))
        assertEquals(data.keyValueMap, decoded.keyValueMap)
        assertFalse(BackgroundScanWorker.hasLegacyWorkData(data))
        assertFalse(data.hasKeyWithValueOfType("identity_json", String::class.java))
        assertFalse(data.hasKeyWithValueOfType("deep_research", Boolean::class.javaObjectType))
        assertFalse(data.hasKeyWithValueOfType("strong_face_correlation", Boolean::class.javaObjectType))
        assertFalse(serialized.toString(Charsets.UTF_8).contains("identity_json"))
    }

    @Test
    fun secureInputDataWithGenerationContainsBothOpaqueReferences() {
        val requestId = "6f0d7f1b-cb5e-4f14-b7b0-3dcf3c06d9bd"
        val generation = "33333333-3333-4333-8333-333333333333"

        val data = BackgroundScanWorker.secureInputData(requestId, generation)
        val serialized = data.toByteArray()
        val decoded = Data.fromByteArray(serialized)

        assertEquals(
            setOf(BackgroundScanWorker.KEY_REQUEST_ID, BackgroundScanWorker.KEY_GENERATION),
            data.keyValueMap.keys
        )
        assertEquals(requestId, data.getString(BackgroundScanWorker.KEY_REQUEST_ID))
        assertEquals(generation, data.getString(BackgroundScanWorker.KEY_GENERATION))
        assertEquals(data.keyValueMap, decoded.keyValueMap)
        assertFalse(BackgroundScanWorker.hasLegacyWorkData(data))
        assertFalse(serialized.toString(Charsets.UTF_8).contains("identity_json"))
    }

    @Test
    fun legacyPlaintextWorkDataIsRejectedWithoutEchoingItsValue() {
        val secret = "authorized@example.test"
        val legacy = Data.Builder()
            .putString("identity_json", "{\"fullName\":\"$secret\"}")
            .putBoolean("deep_research", false)
            .putBoolean("strong_face_correlation", true)
            .build()

        assertTrue(BackgroundScanWorker.hasLegacyWorkData(legacy))
        val failure = BackgroundScanWorker.failureData(
            BackgroundScanWorker.ERROR_LEGACY_WORK_DATA_UNSUPPORTED
        )
        assertEquals(
            BackgroundScanWorker.ERROR_LEGACY_WORK_DATA_UNSUPPORTED,
            failure.getString(BackgroundScanWorker.KEY_ERROR)
        )
        assertEquals(
            setOf(BackgroundScanWorker.KEY_STAGE, BackgroundScanWorker.KEY_ERROR),
            failure.keyValueMap.keys
        )
        assertFalse(failure.toByteArray().toString(Charsets.UTF_8).contains(secret))
    }

    @Test
    fun arbitraryErrorTextCannotEnterWorkManagerData() {
        val secret = "java.io.IOException: token=do-not-persist"

        assertThrows(IllegalArgumentException::class.java) {
            BackgroundScanWorker.failureData(secret)
        }
    }

    @Test
    fun arbitraryProgressTextCannotEnterWorkManagerData() {
        val secret = "DISCOVERING identity=authorized@example.test"

        val data = BackgroundScanWorker.safeProgressData(secret)

        assertEquals(
            BackgroundScanWorker.STAGE_RUNNING,
            data.getString(BackgroundScanWorker.KEY_STAGE)
        )
        assertFalse(data.toByteArray().toString(Charsets.UTF_8).contains(secret))
    }

    @Test
    fun legacyWorkInfoStageIsSanitizedBeforeUiStatus() {
        val secret = "java.io.IOException token=do-not-render"

        assertEquals(
            BackgroundScanWorker.STAGE_RUNNING,
            BackgroundScanManager.safeStatusStage(secret, null, WorkInfo.State.RUNNING)
        )
        assertEquals(
            BackgroundScanWorker.STAGE_FAILED,
            BackgroundScanManager.safeStatusStage(null, BackgroundScanWorker.STAGE_FAILED, WorkInfo.State.FAILED)
        )
        assertEquals(
            BackgroundScanWorker.STAGE_FAILED,
            BackgroundScanManager.safeStatusStage(
                BackgroundScanWorker.STAGE_STARTING,
                BackgroundScanWorker.STAGE_FAILED,
                WorkInfo.State.FAILED
            )
        )
        assertEquals(
            BackgroundScanWorker.STAGE_FAILED,
            BackgroundScanManager.safeStatusStage(
                BackgroundScanWorker.STAGE_STARTING,
                "unsafe terminal detail",
                WorkInfo.State.FAILED
            )
        )
        assertEquals(
            BackgroundScanWorker.STAGE_CANCELLED,
            BackgroundScanManager.safeStatusStage(null, null, WorkInfo.State.CANCELLED)
        )
    }

    @Test
    fun unrelatedUniqueWorkGenerationIsNeverSelected() {
        val active = "11111111-1111-4111-8111-111111111111"
        val completed = "22222222-2222-4222-8222-222222222222"
        val unrelated = "33333333-3333-4333-8333-333333333333"

        assertEquals(
            null,
            BackgroundScanManager.selectRelevantWorkId(
                activeOwnerId = null,
                completedWorkId = null,
                availableWorkIds = setOf(unrelated)
            )
        )
        assertEquals(
            completed,
            BackgroundScanManager.selectRelevantWorkId(
                activeOwnerId = active,
                completedWorkId = completed,
                availableWorkIds = setOf(completed, unrelated)
            )
        )
        assertEquals(
            active,
            BackgroundScanManager.selectRelevantWorkId(
                activeOwnerId = active,
                completedWorkId = completed,
                availableWorkIds = setOf(active, completed, unrelated)
            )
        )
    }

    @Test
    fun requestReferenceMustBeCanonicalUuid() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundScanWorker.secureInputData("../../identity_json")
        }
    }

    @Test
    fun generationReferenceMustBeCanonicalUuid() {
        val validRequest = "6f0d7f1b-cb5e-4f14-b7b0-3dcf3c06d9bd"
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundScanWorker.secureInputData(validRequest, "invalid-gen-id")
        }
    }

    @Test
    fun buildRequestBindsExactOwnerUuidAndOpaqueGenerationReferences() {
        val requestId = UUID.fromString("6f0d7f1b-cb5e-4f14-b7b0-3dcf3c06d9bd")
        val ownerId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val generation = "33333333-3333-4333-8333-333333333333"

        val request = BackgroundScanManager.buildRequest(
            requestId = requestId.toString(),
            generation = generation,
            ownerUuid = ownerId
        )

        assertEquals(ownerId, request.id)
        assertEquals(
            setOf(BackgroundScanWorker.KEY_REQUEST_ID, BackgroundScanWorker.KEY_GENERATION),
            request.workSpec.input.keyValueMap.keys
        )
        assertEquals(requestId.toString(), request.workSpec.input.getString(BackgroundScanWorker.KEY_REQUEST_ID))
        assertEquals(generation, request.workSpec.input.getString(BackgroundScanWorker.KEY_GENERATION))
    }

    @Test
    fun durableSuccessGuardAcceptsOnlyExactResultAndTerminalLifecycle() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"
        val succeeded = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Succeeded,
            updatedAtEpochMillis = 10L,
            resultReady = true,
            errorCode = null
        )

        assertTrue(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Available(succeeded),
                resultWorkId = owner,
                workerId = owner,
                requestId = request,
                generation = generation
            )
        )
        assertFalse(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Available(succeeded),
                resultWorkId = "44444444-4444-4444-8444-444444444444",
                workerId = owner,
                requestId = request,
                generation = generation
            )
        )
        assertFalse(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Available(succeeded),
                resultWorkId = owner,
                workerId = owner,
                requestId = request,
                generation = "55555555-5555-4555-8555-555555555555"
            )
        )
        assertFalse(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Available(
                    succeeded.copy(
                        phase = ScanLifecyclePhase.Running,
                        resultReady = false
                    )
                ),
                resultWorkId = owner,
                workerId = owner,
                requestId = request,
                generation = generation
            )
        )
    }

    @Test
    fun durableSuccessGuardAcceptsMissingLifecycleOnlyForMatchingResultOwner() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"

        assertTrue(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Missing,
                resultWorkId = owner,
                workerId = owner,
                requestId = request,
                generation = generation
            )
        )
        assertFalse(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Missing,
                resultWorkId = "44444444-4444-4444-8444-444444444444",
                workerId = owner,
                requestId = request,
                generation = generation
            )
        )
        assertFalse(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Invalid(ScanLifecycleStoreInvalidReason.InvalidRecord),
                resultWorkId = owner,
                workerId = owner,
                requestId = request,
                generation = generation
            )
        )
        val cleanupPending = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.CleanupPending,
            updatedAtEpochMillis = 10L,
            resultReady = true,
            errorCode = null
        )
        assertFalse(
            BackgroundScanManager.isDurableSuccess(
                lifecycle = ScanLifecycleReadResult.Available(cleanupPending),
                resultWorkId = owner,
                workerId = owner,
                requestId = request,
                generation = generation
            )
        )
    }

    @Test
    fun fastWorkerTransitionStillAllowsExactPriorResultCleanup() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"
        val priorOwner = "44444444-4444-4444-8444-444444444444"
        val pending = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.EnqueuePending,
            updatedAtEpochMillis = 100L,
            resultReady = false
        )
        val alreadyRunning = pending.copy(
            phase = ScanLifecyclePhase.Running,
            updatedAtEpochMillis = 101L
        )
        val replacement = pending.copy(
            ownerId = "55555555-5555-4555-8555-555555555555",
            generation = "66666666-6666-4666-8666-666666666666"
        )

        assertTrue(
            BackgroundScanManager.shouldClearPriorResult(
                current = alreadyRunning,
                pending = pending,
                priorOwnerId = priorOwner,
                resultOwnerId = priorOwner
            )
        )
        assertFalse(
            BackgroundScanManager.shouldClearPriorResult(
                current = replacement,
                pending = pending,
                priorOwnerId = priorOwner,
                resultOwnerId = priorOwner
            )
        )
        assertFalse(
            BackgroundScanManager.shouldClearPriorResult(
                current = alreadyRunning,
                pending = pending,
                priorOwnerId = priorOwner,
                resultOwnerId = owner
            )
        )
    }

    @Test
    fun uiResultVisibilityRequiresPublishedExactOwnerMarker() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"
        val runningBeforePublication = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Running,
            updatedAtEpochMillis = 100L,
            resultReady = false
        )

        assertFalse(
            BackgroundScanManager.isResultVisible(
                ScanLifecycleReadResult.Available(runningBeforePublication),
                owner
            )
        )
        assertTrue(
            BackgroundScanManager.isResultVisible(
                ScanLifecycleReadResult.Available(runningBeforePublication.copy(resultReady = true)),
                owner
            )
        )
        assertFalse(
            BackgroundScanManager.isResultVisible(
                ScanLifecycleReadResult.Available(runningBeforePublication.copy(resultReady = true)),
                "44444444-4444-4444-8444-444444444444"
            )
        )
        assertTrue(BackgroundScanManager.isResultVisible(ScanLifecycleReadResult.Missing, owner))
        assertFalse(
            BackgroundScanManager.isResultVisible(
                ScanLifecycleReadResult.Invalid(ScanLifecycleStoreInvalidReason.InvalidRecord),
                owner
            )
        )
    }

    @Test
    fun claimRunningTransitionsPendingToRunningWithMatchingOwnerAndGeneration() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"

        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        val record = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.EnqueuePending,
            updatedAtEpochMillis = 100L,
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record))

        BackgroundScanManager.lifecycleStoreProvider = { store }
        BackgroundScanManager.nowEpochMillis = { 101L }
        val context = fakeContext(prefs)

        assertTrue(BackgroundScanManager.claimRunning(context, owner, generation, request))
        val afterClaim = (store.read() as ScanLifecycleReadResult.Available).record
        assertEquals(ScanLifecyclePhase.Running, afterClaim.phase)
        assertEquals(101L, afterClaim.updatedAtEpochMillis)

        // Idempotent re-claim while running succeeds
        assertTrue(BackgroundScanManager.claimRunning(context, owner, generation, request))
    }

    @Test
    fun claimRunningRejectsStaleGenerationOrOwner() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"
        val staleOwner = "55555555-5555-4555-8555-555555555555"
        val staleGeneration = "44444444-4444-4444-8444-444444444444"

        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        val record = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Enqueued,
            updatedAtEpochMillis = 100L,
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record))

        BackgroundScanManager.lifecycleStoreProvider = { store }
        BackgroundScanManager.nowEpochMillis = { 101L }
        val context = fakeContext(prefs)

        // Wrong owner is rejected
        assertFalse(BackgroundScanManager.claimRunning(context, staleOwner, generation, request))
        // Wrong generation is rejected
        assertFalse(BackgroundScanManager.claimRunning(context, owner, staleGeneration, request))
        // Lifecycle is not mutated
        assertEquals(ScanLifecyclePhase.Enqueued, (store.read() as ScanLifecycleReadResult.Available).record.phase)
    }

    @Test
    fun publishResultIfOwnerTransitionsToPublishResultAndSetsResultReady() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"

        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        val record = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Running,
            updatedAtEpochMillis = 100L,
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record))

        BackgroundScanManager.lifecycleStoreProvider = { store }
        BackgroundScanManager.nowEpochMillis = { 102L }
        val context = fakeContext(prefs)

        assertTrue(BackgroundScanManager.publishResultIfOwner(context, owner, generation))
        val published = (store.read() as ScanLifecycleReadResult.Available).record
        assertEquals(ScanLifecyclePhase.Running, published.phase)
        assertTrue(published.resultReady)

        // Succeeded transition advances phase to Succeeded
        assertTrue(BackgroundScanManager.markSucceededIfOwner(context, owner, generation))
        val succeeded = (store.read() as ScanLifecycleReadResult.Available).record
        assertEquals(ScanLifecyclePhase.Succeeded, succeeded.phase)
        assertTrue(succeeded.resultReady)
    }

    @Test
    fun failedEncryptedResultWriteDoesNotPublishLifecycleResultMarker() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"
        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        val record = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Running,
            updatedAtEpochMillis = 100L,
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record))
        BackgroundScanManager.lifecycleStoreProvider = { store }
        BackgroundScanManager.nowEpochMillis = { 101L }

        // JVM Context has no Android Keystore, so the encrypted write fails
        // closed. The worker's ordering contract then skips PublishResult.
        val context = fakeContext(prefs)
        assertFalse(
            BackgroundScanManager.saveResultIfOwner(
                context = context,
                workId = owner,
                dossierCase = io.dossier.app.domain.case.DossierCase(
                    createdAt = "",
                    subjectName = "test",
                    input = io.dossier.app.domain.model.IdentityInput("test")
                ),
                analysis = io.dossier.app.domain.analysis.OsintAnalysisBundle(),
                generation = generation
            )
        )
        val current = (store.read() as ScanLifecycleReadResult.Available).record
        assertFalse(current.resultReady)
        assertEquals(ScanLifecyclePhase.Running, current.phase)
    }

    @Test
    fun staleFinishOwnerDoesNotMutateOrClearReplacementLifecycle() {
        val oldOwner = "11111111-1111-4111-8111-111111111111"
        val oldGeneration = "22222222-2222-4222-8222-222222222222"
        val newOwner = "33333333-3333-4333-8333-333333333333"
        val newRequest = "44444444-4444-4444-8444-444444444444"
        val newGeneration = "55555555-5555-4555-8555-555555555555"

        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 200L })
        val activeRecord = ScanLifecycleRecord(
            ownerId = newOwner,
            requestId = newRequest,
            generation = newGeneration,
            phase = ScanLifecyclePhase.Running,
            updatedAtEpochMillis = 200L,
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(activeRecord))

        BackgroundScanManager.lifecycleStoreProvider = { store }
        BackgroundScanManager.nowEpochMillis = { 205L }
        val context = fakeContext(prefs)

        // Stale worker finishes with failure
        BackgroundScanManager.finishOwner(
            context,
            oldOwner,
            BackgroundScanCompletion.Failed(ScanLifecycleErrors.SCAN_EXECUTION_FAILED),
            oldGeneration
        )

        // Active replacement record is untouched
        val current = (store.read() as ScanLifecycleReadResult.Available).record
        assertEquals(activeRecord, current)
        assertEquals(ScanLifecyclePhase.Running, current.phase)
    }

    @Test
    fun finishOwnerFailedTransitionsToFailedWithSafeCode() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"

        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        val record = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Running,
            updatedAtEpochMillis = 100L,
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record))

        BackgroundScanManager.lifecycleStoreProvider = { store }
        BackgroundScanManager.nowEpochMillis = { 105L }
        val context = fakeContext(prefs)

        BackgroundScanManager.finishOwner(
            context,
            owner,
            BackgroundScanCompletion.Failed(ScanLifecycleErrors.SCAN_EXECUTION_FAILED),
            generation
        )

        val failed = (store.read() as ScanLifecycleReadResult.Available).record
        assertEquals(ScanLifecyclePhase.Failed, failed.phase)
        assertEquals(ScanLifecycleErrors.SCAN_EXECUTION_FAILED, failed.errorCode)
    }

    @Test
    fun clearLatestResultCleansUpTerminalLifecycle() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"

        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        val record = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Succeeded,
            updatedAtEpochMillis = 100L,
            resultReady = true,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record))

        BackgroundScanManager.lifecycleStoreProvider = { store }
        BackgroundScanManager.nowEpochMillis = { 110L }
        val context = fakeContext(prefs)

        BackgroundScanManager.clearLatestResult(context)
        assertEquals(ScanLifecycleReadResult.Missing, store.read())
    }

    @Test
    fun explicitPurgeClearsSensitiveStateWhileCancellationRemainsPending() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"
        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        val cancelling = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.CancelRequested,
            updatedAtEpochMillis = 100L,
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(cancelling))
        BackgroundScanManager.lifecycleStoreProvider = { store }
        val context = fakeContext(prefs)

        assertTrue(BackgroundScanManager.clearLatestResult(context))

        assertEquals(ScanLifecycleReadResult.Available(cancelling), store.read())
    }

    @Test
    fun hasActiveMarkerReflectsActiveLifecyclePhases() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val request = "22222222-2222-4222-8222-222222222222"
        val generation = "33333333-3333-4333-8333-333333333333"

        val prefs = FakePreferences()
        val store = ScanLifecycleStore(prefs, nowEpochMillis = { 100L })
        BackgroundScanManager.lifecycleStoreProvider = { store }
        val context = fakeContext(prefs)

        assertFalse(BackgroundScanManager.hasActiveMarker(context))

        val running = ScanLifecycleRecord(
            ownerId = owner,
            requestId = request,
            generation = generation,
            phase = ScanLifecyclePhase.Running,
            updatedAtEpochMillis = 100L,
            resultReady = false,
            errorCode = null
        )
        store.publish(running)
        assertTrue(BackgroundScanManager.hasActiveMarker(context))
    }

    private fun fakeContext(prefs: SharedPreferences): Context = FakeContext(prefs)

    private class FakeContext(private val prefs: SharedPreferences) : android.content.ContextWrapper(null) {
        private val files = File(
            System.getProperty("java.io.tmpdir"),
            "dossier-background-worker-${UUID.randomUUID()}"
        ).apply(File::mkdirs)

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = files
    }


    private class FakePreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var commitResult: Boolean = true
        var commitCount: Int = 0
        var applyCount: Int = 0

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            (values[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = (values[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (values[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (values[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                pending[key] = values
                removals.remove(key)
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                pending.remove(key)
                removals += key
            }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                commitCount += 1
                if (!commitResult) return false
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
                return true
            }

            override fun apply() {
                applyCount += 1
                error("apply() is forbidden for lifecycle durability")
            }
        }
    }
}
