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

    @After
    fun cleanUp() {
        createdWorkIds.forEach { id ->
            runCatching { workManager.cancelWorkById(id).result.get(10, TimeUnit.SECONDS) }
        }
        ownerIds.forEach { id -> BackgroundScanManager.finishOwner(context, id) }
        ScanResumeStore(context).clearDetailed()
        BackgroundScanResultStore(context).clear()
        runCatching { workManager.pruneWork().result.get(10, TimeUnit.SECONDS) }
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
    }
}
