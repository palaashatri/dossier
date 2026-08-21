package io.dossier.app.domain.scanner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.dossier.app.data.face.FaceCorrelationSessionPolicy
import io.dossier.app.domain.analysis.OsintPostProcessor
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Durable authorized scan execution.
 *
 * WorkManager owns scheduling/restart semantics, so leaving the scan screen or
 * backgrounding the app no longer aborts analysis just because a Compose scope or
 * activity disappears. If Android terminates the process, WorkManager may restart
 * this unit of work from the beginning; Dossier does not claim stage-level frontier
 * resume until the scanner frontier itself is persisted.
 */
class BackgroundScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val rawInput = inputData.getString(KEY_IDENTITY_JSON)
        if (rawInput == null) {
            ScanSession.markBackgroundFailure("Missing identity input")
            return@coroutineScope Result.failure(errorData("Missing identity input"))
        }
        val input = runCatching { JSON.decodeFromString<IdentityInput>(rawInput) }.getOrNull()
        if (input == null) {
            ScanSession.markBackgroundFailure("Invalid identity input")
            return@coroutineScope Result.failure(errorData("Invalid identity input"))
        }
        val deepResearch = inputData.getBoolean(KEY_DEEP_RESEARCH, false)
        val strongCorrelation = inputData.getBoolean(KEY_STRONG_FACE_CORRELATION, false)

        if (strongCorrelation) FaceCorrelationSessionPolicy.useStrongCorrelation()
        else FaceCorrelationSessionPolicy.useBasicMatching()

        setProgress(workDataOf(KEY_STAGE to STAGE_STARTING))
        val progressRelay = launch {
            ScanSession.progressText
                .distinctUntilChanged()
                .collect { stage ->
                    if (stage.isNotBlank()) {
                        setProgress(workDataOf(KEY_STAGE to stage.take(MAX_STAGE_CHARS)))
                    }
                }
        }

        try {
            ScanSession.executeScan(applicationContext, input, deepResearch)

            setProgress(workDataOf(KEY_STAGE to STAGE_POST_PROCESSING))
            val analysis = OsintPostProcessor.analyze(
                input = input,
                profiles = ScanSession.profileScanResults.value,
                evidence = EvidenceRuntimeCache.collection.value
            )

            val snapshot = ScanSession.buildCase()
            if (snapshot == null) {
                ScanSession.markBackgroundFailure("Scan completed without a persistable result")
                return@coroutineScope Result.failure(errorData("Scan completed without a persistable result"))
            }
            val saved = BackgroundScanResultStore(applicationContext).save(
                workId = id.toString(),
                dossierCase = snapshot,
                analysis = analysis
            )
            if (!saved) {
                ScanSession.markBackgroundFailure("Unable to persist encrypted background result")
                return@coroutineScope Result.failure(errorData("Unable to persist encrypted background result"))
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_COMPLETE))
            Result.success(
                workDataOf(
                    KEY_STAGE to STAGE_COMPLETE,
                    KEY_WORK_ID to id.toString()
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.localizedMessage ?: error.javaClass.simpleName
            ScanSession.markBackgroundFailure(message)
            Result.failure(errorData(message))
        } finally {
            progressRelay.cancelAndJoin()
            FaceCorrelationSessionPolicy.useBasicMatching()
        }
    }

    private fun errorData(message: String): Data = workDataOf(
        KEY_STAGE to STAGE_FAILED,
        KEY_ERROR to message.take(MAX_ERROR_CHARS)
    )

    companion object {
        const val UNIQUE_WORK_NAME = "dossier-authorized-background-scan"
        const val WORK_TAG = "dossier-background-scan"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val KEY_WORK_ID = "work_id"
        const val STAGE_STARTING = "QUEUED_BACKGROUND_SCAN..."
        const val STAGE_POST_PROCESSING = "ANALYZING_BEHAVIOR_AND_NETWORK..."
        const val STAGE_COMPLETE = "BACKGROUND_SCAN_COMPLETE"
        const val STAGE_FAILED = "BACKGROUND_SCAN_FAILED"

        internal const val KEY_IDENTITY_JSON = "identity_json"
        internal const val KEY_DEEP_RESEARCH = "deep_research"
        internal const val KEY_STRONG_FACE_CORRELATION = "strong_face_correlation"
        private const val MAX_STAGE_CHARS = 180
        private const val MAX_ERROR_CHARS = 400
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    }
}

/** Small WorkManager facade used by Compose and ScanSession. */
object BackgroundScanManager {
    data class Status(
        val id: UUID,
        val state: WorkInfo.State,
        val stage: String,
        val error: String?
    ) {
        val running: Boolean
            get() = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING || state == WorkInfo.State.BLOCKED
        val complete: Boolean get() = state == WorkInfo.State.SUCCEEDED
    }

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun enqueue(
        context: Context,
        input: IdentityInput,
        deepResearch: Boolean,
        strongFaceCorrelation: Boolean
    ): UUID {
        val encoded = json.encodeToString(input)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_JSON_BYTES) {
            "Identity input is too large for durable background scheduling"
        }
        val request = OneTimeWorkRequestBuilder<BackgroundScanWorker>()
            .setInputData(
                workDataOf(
                    BackgroundScanWorker.KEY_IDENTITY_JSON to encoded,
                    BackgroundScanWorker.KEY_DEEP_RESEARCH to deepResearch,
                    BackgroundScanWorker.KEY_STRONG_FACE_CORRELATION to strongFaceCorrelation
                )
            )
            .addTag(BackgroundScanWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            BackgroundScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(BackgroundScanWorker.UNIQUE_WORK_NAME)
    }

    fun statusFlow(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(BackgroundScanWorker.UNIQUE_WORK_NAME)

    fun toStatus(info: WorkInfo): Status = Status(
        id = info.id,
        state = info.state,
        stage = info.progress.getString(BackgroundScanWorker.KEY_STAGE)
            ?: info.outputData.getString(BackgroundScanWorker.KEY_STAGE)
            ?: when (info.state) {
                WorkInfo.State.ENQUEUED -> BackgroundScanWorker.STAGE_STARTING
                WorkInfo.State.SUCCEEDED -> BackgroundScanWorker.STAGE_COMPLETE
                WorkInfo.State.FAILED -> BackgroundScanWorker.STAGE_FAILED
                WorkInfo.State.CANCELLED -> "BACKGROUND_SCAN_CANCELLED"
                else -> "BACKGROUND_SCAN_RUNNING"
            },
        error = info.outputData.getString(BackgroundScanWorker.KEY_ERROR)
    )

    fun latestResult(context: Context): BackgroundScanResultStore.Snapshot? =
        BackgroundScanResultStore(context.applicationContext).load()

    fun clearLatestResult(context: Context): Boolean =
        BackgroundScanResultStore(context.applicationContext).clear()

    private const val MAX_INPUT_JSON_BYTES = 8_000
}
