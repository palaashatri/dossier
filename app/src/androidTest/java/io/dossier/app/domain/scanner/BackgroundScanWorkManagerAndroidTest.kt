package io.dossier.app.domain.scanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.dossier.app.domain.model.IdentityInput
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundScanWorkManagerAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager = WorkManager.getInstance(context)
    private val createdWorkIds = mutableListOf<UUID>()
    private val ownerIds = mutableListOf<String>()

    @Before
    fun setUp() {
        clearLifecyclePreferences()
        ScanResumeStore(context).clearDetailed()
        BackgroundScanResultStore(context).clear()
    }

    @After
    fun cleanUp() {
        createdWorkIds.forEach { id ->
            runCatching { workManager.cancelWorkById(id).result.get(10, TimeUnit.SECONDS) }
        }
        ownerIds.forEach { id -> BackgroundScanManager.finishOwner(context, id) }
        ScanResumeStore(context).clearDetailed()
        BackgroundScanResultStore(context).clear()
        clearLifecyclePreferences()
        runCatching { workManager.pruneWork().result.get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun durableSucceededResultMakesExactWorkRetryIdempotentlySucceed() {
        val owner = UUID.randomUUID()
        val requestId = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        val lifecycle = ScanLifecycleRecord(
            ownerId = owner.toString(),
            requestId = requestId,
            generation = generation,
            phase = ScanLifecyclePhase.Succeeded,
            updatedAtEpochMillis = System.currentTimeMillis(),
            resultReady = true,
            errorCode = null
        )
        assertEquals(
            ScanLifecycleWriteResult.Saved,
            ScanLifecycleStore(context).publish(lifecycle)
        )
        assertTrue(
            BackgroundScanResultStore(context).save(
                workId = owner.toString(),
                dossierCase = io.dossier.app.domain.case.DossierCase(
                    createdAt = "2026-08-24T00:00:00Z",
                    subjectName = "Synthetic Retry Fixture",
                    input = IdentityInput(fullName = "Synthetic Retry Fixture")
                )
            )
        )
        val retry = BackgroundScanManager.buildRequest(
            requestId = requestId,
            generation = generation,
            ownerUuid = owner
        )
        createdWorkIds += retry.id

        workManager.enqueueUniqueWork(TEST_DURABLE_SUCCESS_WORK, ExistingWorkPolicy.REPLACE, retry)
            .result.get(10, TimeUnit.SECONDS)

        val info = waitForFinished(owner)
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(BackgroundScanWorker.STAGE_COMPLETE, info.outputData.getString(BackgroundScanWorker.KEY_STAGE))
        assertEquals(owner, retry.id)
        assertEquals(
            setOf(BackgroundScanWorker.KEY_REQUEST_ID, BackgroundScanWorker.KEY_GENERATION),
            retry.workSpec.input.keyValueMap.keys
        )
    }

    @Test
    fun pendingPreparedGenerationReenqueuesExactUuid() {
        val prepared = ScanResumeStore(context).prepareRequestDetailed(
            input = IdentityInput(fullName = "Synthetic Prepared Fixture"),
            deepResearch = false,
            strongFaceCorrelation = false
        ) as ResumeWriteState.Saved
        val owner = UUID.randomUUID()
        val lifecycle = ScanLifecycleRecord(
            ownerId = owner.toString(),
            requestId = prepared.point.requestId,
            generation = UUID.randomUUID().toString(),
            phase = ScanLifecyclePhase.EnqueuePending,
            updatedAtEpochMillis = System.currentTimeMillis(),
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, ScanLifecycleStore(context).publish(lifecycle))
        assertEquals(null, workManager.getWorkInfoById(owner).get(10, TimeUnit.SECONDS))

        val action = BackgroundScanManager.reconcile(context)

        assertEquals(ScanReconciliationAction.ReenqueueSameUuid(lifecycle), action)
        BackgroundScanManager.executeReconciliation(context, action)
        createdWorkIds += owner
        val recreated = waitForWorkInfoCreated(owner)
        assertEquals(owner, recreated.id)
        assertTrue(
            ScanResumeStore(context).loadRequestDetailed(prepared.point.requestId) is ResumeReadState.Available
        )
        workManager.cancelWorkById(owner).result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun cancellationWaitsForExactWorkInfoTerminalState() {
        val checkpoint = ScanResumeStore(context).saveRequestDetailed(
            input = IdentityInput(fullName = "Synthetic Cancellation Fixture"),
            deepResearch = false,
            strongFaceCorrelation = false
        ) as ResumeWriteState.Saved
        val owner = UUID.randomUUID()
        val generation = UUID.randomUUID().toString()
        val lifecycle = ScanLifecycleRecord(
            ownerId = owner.toString(),
            requestId = checkpoint.point.requestId,
            generation = generation,
            phase = ScanLifecyclePhase.Enqueued,
            updatedAtEpochMillis = System.currentTimeMillis(),
            resultReady = false,
            errorCode = null
        )
        assertEquals(ScanLifecycleWriteResult.Saved, ScanLifecycleStore(context).publish(lifecycle))
        val delayed = OneTimeWorkRequestBuilder<BackgroundScanWorker>()
            .setId(owner)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setInputData(BackgroundScanWorker.secureInputData(checkpoint.point.requestId, generation))
            .addTag(BackgroundScanWorker.WORK_TAG)
            .build()
        createdWorkIds += owner
        workManager.enqueueUniqueWork(TEST_CANCEL_WORK, ExistingWorkPolicy.REPLACE, delayed)
            .result.get(10, TimeUnit.SECONDS)

        BackgroundScanManager.cancel(context)

        val info = waitForFinished(owner)
        assertEquals(WorkInfo.State.CANCELLED, info.state)
        val terminal = waitForLifecyclePhase(ScanLifecyclePhase.Cancelled)
        assertEquals(owner.toString(), terminal.ownerId)
        assertEquals(generation, terminal.generation)
    }

    @Test
    fun persistedWorkSpecContainsOnlyOpaqueRequestReference() {
        val secretSentinel = "identity-seed-${UUID.randomUUID()}@example.invalid"
        val checkpoint = ScanResumeStore(context).saveRequestDetailed(
            input = IdentityInput(
                fullName = "Opaque Transport Test",
                emails = listOf(secretSentinel)
            ),
            deepResearch = true,
            strongFaceCorrelation = true
        ) as ResumeWriteState.Saved
        val checkpointId = checkpoint.point.requestId
        val blockingOwner = UUID.randomUUID().toString()
        val request = BackgroundScanManager.buildRequest(checkpointId)
        createdWorkIds += request.id
        ownerIds += blockingOwner
        assertTrue(BackgroundScanManager.setActiveOwner(context, blockingOwner))

        workManager.enqueueUniqueWork(TEST_OPAQUE_WORK, ExistingWorkPolicy.REPLACE, request)
            .result.get(10, TimeUnit.SECONDS)
        waitForFinished(request.id)

        openWorkDatabase().use { database ->
            database.query(
                "workspec",
                arrayOf("input", "output"),
                "id = ?",
                arrayOf(request.id.toString()),
                null,
                null,
                null
            ).use { cursor ->
                assertTrue("Expected current WorkSpec row", cursor.moveToFirst())
                val inputBytes = cursor.getBlob(0)
                val outputBytes = cursor.getBlob(1)
                val input = Data.fromByteArray(inputBytes)
                val output = Data.fromByteArray(outputBytes)

                assertEquals(setOf(BackgroundScanWorker.KEY_REQUEST_ID), input.keyValueMap.keys)
                assertEquals(checkpointId, input.getString(BackgroundScanWorker.KEY_REQUEST_ID))
                assertTrue(
                    output.keyValueMap.keys.all {
                        it == BackgroundScanWorker.KEY_STAGE || it == BackgroundScanWorker.KEY_ERROR
                    }
                )
                output.getString(BackgroundScanWorker.KEY_ERROR)?.let { code ->
                    assertTrue(code in BackgroundScanWorker.SAFE_ERROR_CODES)
                }
                assertFalse(inputBytes.toString(Charsets.UTF_8).contains(secretSentinel))
                assertFalse(outputBytes.toString(Charsets.UTF_8).contains(secretSentinel))
                assertFalse(input.keyValueMap.containsKey("identity_json"))
                assertFalse(input.keyValueMap.containsKey("deep_research"))
                assertFalse(input.keyValueMap.containsKey("strong_face_correlation"))
            }

            database.query(
                "workprogress",
                arrayOf("progress"),
                "work_spec_id = ?",
                arrayOf(request.id.toString()),
                null,
                null,
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val progressBytes = cursor.getBlob(0)
                    val progress = Data.fromByteArray(progressBytes)
                    assertTrue(progress.keyValueMap.keys.all { it == BackgroundScanWorker.KEY_STAGE })
                    assertFalse(progressBytes.toString(Charsets.UTF_8).contains(secretSentinel))
                }
            }
        }
    }

    @Test
    fun staleOwnerCannotClearReplacementAndOwnedPreflightFailureCleansUp() {
        val firstOwner = UUID.randomUUID().toString()
        val replacementOwner = UUID.randomUUID().toString()
        ownerIds += firstOwner
        ownerIds += replacementOwner
        assertTrue(BackgroundScanManager.setActiveOwner(context, firstOwner))
        assertTrue(BackgroundScanManager.setActiveOwner(context, replacementOwner))

        BackgroundScanManager.finishOwner(context, firstOwner)

        assertFalse(BackgroundScanManager.claimActive(context, firstOwner))
        assertTrue(BackgroundScanManager.claimActive(context, replacementOwner))
        assertTrue(BackgroundScanManager.isCurrentOwner(context, replacementOwner))

        val missingInputRequest = OneTimeWorkRequestBuilder<BackgroundScanWorker>().build()
        createdWorkIds += missingInputRequest.id
        ownerIds += missingInputRequest.id.toString()
        assertTrue(BackgroundScanManager.setActiveOwner(context, missingInputRequest.id.toString()))
        workManager.enqueueUniqueWork(
            TEST_MISSING_WORK,
            ExistingWorkPolicy.REPLACE,
            missingInputRequest
        ).result.get(10, TimeUnit.SECONDS)

        val info = waitForFinished(missingInputRequest.id)

        assertEquals(WorkInfo.State.FAILED, info.state)
        assertEquals(
            BackgroundScanWorker.ERROR_MISSING_REQUEST_REFERENCE,
            info.outputData.getString(BackgroundScanWorker.KEY_ERROR)
        )
        assertFalse(BackgroundScanManager.hasActiveMarker(context))
        assertTrue(
            ScanSession.progressText.value.startsWith(BackgroundScanWorker.STAGE_FAILED)
        )
    }

    private fun waitForFinished(id: UUID): WorkInfo {
        var latest: WorkInfo? = null
        repeat(100) {
            latest = workManager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)
            if (latest?.state?.isFinished == true) return latest!!
            SystemClock.sleep(50)
        }
        assertNotNull("WorkInfo was never created", latest)
        return latest!!
    }

    private fun waitForWorkInfoCreated(id: UUID): WorkInfo {
        repeat(100) {
            val info = workManager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)
            if (info != null) return info
            SystemClock.sleep(25)
        }
        throw AssertionError("Exact WorkInfo row was never recreated for $id")
    }

    private fun waitForLifecyclePhase(phase: ScanLifecyclePhase): ScanLifecycleRecord {
        var latest: ScanLifecycleReadResult = ScanLifecycleReadResult.Missing
        repeat(200) {
            latest = ScanLifecycleStore(context).read()
            val record = (latest as? ScanLifecycleReadResult.Available)?.record
            if (record?.phase == phase) return record
            SystemClock.sleep(25)
        }
        throw AssertionError("Lifecycle never reached $phase; latest=$latest")
    }

    private fun clearLifecyclePreferences() {
        assertTrue(
            context.getSharedPreferences("dossier-background-work", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        )
    }

    private fun openWorkDatabase(): SQLiteDatabase {
        val candidates = listOf(
            File(context.noBackupFilesDir, WORK_DATABASE_NAME),
            context.getDatabasePath(WORK_DATABASE_NAME)
        )
        val databaseFile = candidates.firstOrNull(File::exists)
        assertNotNull("WorkManager database was not found", databaseFile)
        return SQLiteDatabase.openDatabase(
            databaseFile!!.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    private companion object {
        const val WORK_DATABASE_NAME = "androidx.work.workdb"
        const val TEST_OPAQUE_WORK = "dossier-test-opaque-work"
        const val TEST_MISSING_WORK = "dossier-test-missing-work"
        const val TEST_DURABLE_SUCCESS_WORK = "dossier-test-durable-success-work"
        const val TEST_CANCEL_WORK = "dossier-test-cancel-work"
    }
}
