package io.dossier.app.domain.scanner

import android.annotation.SuppressLint
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
import io.dossier.app.domain.analysis.UsernameSurfaceAnalysis
import io.dossier.app.domain.case.AuthorizedScope
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderDiagnosticsRuntime
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.evidence.UsernameSurfaceRuntimeCache
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.Executor

/** Durable WorkManager-owned assessment execution. */
class BackgroundScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        SCAN_EXECUTION_MUTEX.lock()
        return try {
            runOwnedScan()
        } finally {
            SCAN_EXECUTION_MUTEX.unlock()
        }
    }

    private suspend fun runOwnedScan(): Result = coroutineScope {
        ProviderDiagnosticsRuntime.install(applicationContext)
        val workerId = id.toString()

        if (hasLegacyWorkData(inputData)) {
            BackgroundScanManager.finishOwner(
                applicationContext,
                workerId,
                BackgroundScanCompletion.Failed(ERROR_LEGACY_WORK_DATA_UNSUPPORTED)
            )
            return@coroutineScope Result.failure(failureData(ERROR_LEGACY_WORK_DATA_UNSUPPORTED))
        }
        val requestId = inputData.getString(KEY_REQUEST_ID)
        if (requestId.isNullOrBlank()) {
            BackgroundScanManager.finishOwner(
                applicationContext,
                workerId,
                BackgroundScanCompletion.Failed(ERROR_MISSING_REQUEST_REFERENCE)
            )
            return@coroutineScope Result.failure(failureData(ERROR_MISSING_REQUEST_REFERENCE))
        }

        if (!BackgroundScanManager.claimActive(applicationContext, workerId)) {
            return@coroutineScope Result.failure(failureData(ERROR_STALE_WORK_REQUEST))
        }

        var progressRelay: Job? = null
        var completion: BackgroundScanCompletion? = null
        fun terminalFailure(code: String): Result {
            completion = BackgroundScanCompletion.Failed(code)
            return Result.failure(failureData(code))
        }
        try {
            val requestPoint = when (val state = ScanResumeStore(applicationContext).loadRequestDetailed(requestId)) {
                is ResumeReadState.Available -> state.point
                ResumeReadState.Missing -> return@coroutineScope terminalFailure(ERROR_REQUEST_RECORD_MISSING)
                ResumeReadState.Expired -> return@coroutineScope terminalFailure(ERROR_REQUEST_RECORD_EXPIRED)
                is ResumeReadState.Invalid -> return@coroutineScope terminalFailure(ERROR_REQUEST_RECORD_INVALID)
                is ResumeReadState.StorageFailure -> return@coroutineScope terminalFailure(
                    ERROR_REQUEST_STORAGE_UNAVAILABLE
                )
            }
            val input = requestPoint.input
            val deepResearch = requestPoint.deepResearch
            val strongCorrelation = requestPoint.strongFaceCorrelation
            DiscoveryScanPreferences.setMode(requestPoint.scanMode)
            ScanSession.setDeepResearch(deepResearch)
            if (strongCorrelation) FaceCorrelationSessionPolicy.useStrongCorrelation()
            else FaceCorrelationSessionPolicy.useBasicMatching()

            setProgress(workDataOf(KEY_STAGE to STAGE_STARTING))
            progressRelay = launch {
                ScanSession.progressText.collect { stage ->
                    if (stage.isNotBlank() && BackgroundScanManager.isCurrentOwner(applicationContext, workerId)) {
                        setProgress(safeProgressData(stage))
                    }
                }
            }

            ScanSession.executeScan(applicationContext, input, deepResearch)

            if (!BackgroundScanManager.isCurrentOwner(applicationContext, workerId)) {
                return@coroutineScope Result.failure(failureData(ERROR_STALE_WORK_REQUEST))
            }

            setProgress(workDataOf(KEY_STAGE to STAGE_POST_PROCESSING))
            val evidenceCollection = EvidenceRuntimeCache.collection.value
            val baseAnalysis = OsintPostProcessor.analyze(
                input = input,
                profiles = ScanSession.profileScanResults.value,
                evidence = evidenceCollection
            )
            val analysis = baseAnalysis.copy(
                identitySurface = UsernameSurfaceAnalysis.merge(
                    base = baseAnalysis.identitySurface,
                    observations = UsernameSurfaceRuntimeCache.observations.value
                )
            )

            val snapshot = ScanSession.buildCase()?.copy(
                authorizedScope = AuthorizedScope.AuthorizedAssessment,
                evidenceRecords = evidenceCollection.evidence
                    .distinctBy { it.id }
                    .take(MAX_SNAPSHOT_EVIDENCE)
            )
            if (snapshot == null) {
                return@coroutineScope terminalFailure(ERROR_SNAPSHOT_UNAVAILABLE)
            }
            val saved = BackgroundScanManager.saveResultIfOwner(
                context = applicationContext,
                workId = id.toString(),
                dossierCase = snapshot,
                analysis = analysis
            )
            if (!saved) {
                val code = if (BackgroundScanManager.isCurrentOwner(applicationContext, workerId)) {
                    ERROR_RESULT_PERSISTENCE_FAILED
                } else {
                    ERROR_STALE_WORK_REQUEST
                }
                return@coroutineScope terminalFailure(code)
            }
            setProgress(workDataOf(KEY_STAGE to STAGE_COMPLETE))
            completion = BackgroundScanCompletion.Succeeded
            Result.success(
                workDataOf(KEY_STAGE to STAGE_COMPLETE)
            )
        } catch (cancelled: CancellationException) {
            // A WorkManager runtime stop can be rescheduled with this same UUID.
            // Preserve its durable owner/checkpoint so the next attempt can claim it.
            throw cancelled
        } catch (_: Exception) {
            terminalFailure(ERROR_SCAN_EXECUTION_FAILED)
        } finally {
            // Job.cancel is deliberately non-suspending. A cancelled parent must
            // not skip owner/face-policy cleanup while trying to join this relay.
            progressRelay?.cancel()
            FaceCorrelationSessionPolicy.useBasicMatching()
            completion?.let { outcome ->
                BackgroundScanManager.finishOwner(applicationContext, workerId, outcome)
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "dossier-authorized-background-scan"
        const val WORK_TAG = "dossier-background-scan"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val STAGE_STARTING = "QUEUED_BACKGROUND_SCAN..."
        const val STAGE_POST_PROCESSING = "ANALYZING_BEHAVIOR_AND_NETWORK..."
        const val STAGE_COMPLETE = "BACKGROUND_SCAN_COMPLETE"
        const val STAGE_FAILED = "BACKGROUND_SCAN_FAILED"
        const val STAGE_RUNNING = "BACKGROUND_SCAN_RUNNING"

        internal const val KEY_REQUEST_ID = "request_id"
        private const val LEGACY_KEY_IDENTITY_JSON = "identity_json"
        private const val LEGACY_KEY_DEEP_RESEARCH = "deep_research"
        private const val LEGACY_KEY_STRONG_FACE_CORRELATION = "strong_face_correlation"
        private const val LEGACY_KEY_SCAN_MODE = "scan_mode"
        private const val LEGACY_KEY_MODE = "mode"
        private const val MAX_ERROR_CHARS = 120
        private const val MAX_SNAPSHOT_EVIDENCE = 10_000
        private val SCAN_EXECUTION_MUTEX = Mutex()

        internal const val ERROR_LEGACY_WORK_DATA_UNSUPPORTED = "LEGACY_WORK_DATA_UNSUPPORTED"
        internal const val ERROR_MISSING_REQUEST_REFERENCE = "MISSING_SECURE_REQUEST_REFERENCE"
        internal const val ERROR_REQUEST_RECORD_MISSING = "SECURE_REQUEST_RECORD_MISSING"
        internal const val ERROR_REQUEST_RECORD_EXPIRED = "SECURE_REQUEST_RECORD_EXPIRED"
        internal const val ERROR_REQUEST_RECORD_INVALID = "SECURE_REQUEST_RECORD_INVALID"
        internal const val ERROR_REQUEST_STORAGE_UNAVAILABLE = "SECURE_REQUEST_STORAGE_UNAVAILABLE"
        internal const val ERROR_STALE_WORK_REQUEST = "STALE_WORK_REQUEST"
        internal const val ERROR_SNAPSHOT_UNAVAILABLE = "SNAPSHOT_UNAVAILABLE"
        internal const val ERROR_RESULT_PERSISTENCE_FAILED = "RESULT_PERSISTENCE_FAILED"
        internal const val ERROR_SCAN_EXECUTION_FAILED = "SCAN_EXECUTION_FAILED"

        internal val SAFE_ERROR_CODES = setOf(
            ERROR_LEGACY_WORK_DATA_UNSUPPORTED,
            ERROR_MISSING_REQUEST_REFERENCE,
            ERROR_REQUEST_RECORD_MISSING,
            ERROR_REQUEST_RECORD_EXPIRED,
            ERROR_REQUEST_RECORD_INVALID,
            ERROR_REQUEST_STORAGE_UNAVAILABLE,
            ERROR_STALE_WORK_REQUEST,
            ERROR_SNAPSHOT_UNAVAILABLE,
            ERROR_RESULT_PERSISTENCE_FAILED,
            ERROR_SCAN_EXECUTION_FAILED
        )

        private val SAFE_PROGRESS_STAGES = setOf(
            STAGE_STARTING,
            STAGE_POST_PROCESSING,
            STAGE_COMPLETE,
            STAGE_FAILED,
            STAGE_RUNNING,
            "DISCOVERING_USERNAMES...",
            "COMPARING_FACE_CONSISTENCY...",
            "CHECKING_BREACH_EXPOSURE...",
            "BUILDING_ENTITY_GRAPH...",
            "SCORING_RELATIONSHIP_CONFIDENCE...",
            "TRACING_ATTACK_PATHS...",
            "COMPILING_EXPOSURE_LEVELS...",
            "COMPILING_EXPOSURE_SCORES...",
            "GENERATING_AI_SUMMARY...",
            "SCAN_CANCELLED"
        )

        internal fun secureInputData(requestId: String): Data {
            require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
                "Secure request reference must be a canonical UUID"
            }
            return workDataOf(KEY_REQUEST_ID to requestId)
        }

        internal fun hasLegacyWorkData(data: Data): Boolean = listOf(
            LEGACY_KEY_IDENTITY_JSON,
            LEGACY_KEY_DEEP_RESEARCH,
            LEGACY_KEY_STRONG_FACE_CORRELATION,
            LEGACY_KEY_SCAN_MODE,
            LEGACY_KEY_MODE
        ).any { key ->
            data.hasKeyWithValueOfType(key, String::class.java) ||
                data.hasKeyWithValueOfType(key, Boolean::class.javaObjectType)
        }

        internal fun failureData(code: String): Data {
            require(code in SAFE_ERROR_CODES) { "Unsupported background error code" }
            return workDataOf(
                KEY_STAGE to STAGE_FAILED,
                KEY_ERROR to code.take(MAX_ERROR_CHARS)
            )
        }

        internal fun safeProgressData(stage: String): Data {
            val persistedStage = when {
                stage in SAFE_PROGRESS_STAGES -> stage
                stage.startsWith("$STAGE_FAILED:") -> STAGE_FAILED
                else -> STAGE_RUNNING
            }
            return workDataOf(KEY_STAGE to persistedStage)
        }

    }
}

internal sealed interface BackgroundScanCompletion {
    data object Succeeded : BackgroundScanCompletion
    data class Failed(val code: String) : BackgroundScanCompletion
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

    fun enqueue(
        context: Context,
        input: IdentityInput,
        deepResearch: Boolean,
        strongFaceCorrelation: Boolean
    ): UUID {
        val appContext = context.applicationContext
        synchronized(LIFECYCLE_LOCK) {
            val resumeStore = ScanResumeStore(appContext)
            val saved = resumeStore.saveRequestDetailed(input, deepResearch, strongFaceCorrelation)
            val requestId = (saved as? ResumeWriteState.Saved)?.point?.requestId
                ?: throw BackgroundScanSchedulingException(ERROR_REQUEST_PERSISTENCE_FAILED)
            BackgroundScanResultStore(appContext).clear()

            val request = buildRequest(requestId)
            val ownerId = request.id.toString()
            if (!setActiveOwner(appContext, ownerId)) {
                resumeStore.clearRequest(requestId)
                throw BackgroundScanSchedulingException(ERROR_ACTIVE_MARKER_FAILED)
            }
            ScanSession.markBackgroundScheduled(input, deepResearch)
            try {
                val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                    BackgroundScanWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                monitorEnqueue(operation, appContext, ownerId, requestId)
            } catch (_: Exception) {
                clearActiveOwner(appContext, ownerId)
                resumeStore.clearRequest(requestId)
                throw BackgroundScanSchedulingException(ERROR_ENQUEUE_FAILED)
            }
            return request.id
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        synchronized(LIFECYCLE_LOCK) {
            val ownerId = activeOwner(appContext)
            val ownerUuid = ownerId?.let { value ->
                runCatching { UUID.fromString(value) }.getOrNull()
            }
            if (ownerUuid != null) {
                WorkManager.getInstance(appContext).cancelWorkById(ownerUuid)
            } else {
                WorkManager.getInstance(appContext).cancelUniqueWork(BackgroundScanWorker.UNIQUE_WORK_NAME)
            }
            FaceCorrelationSessionPolicy.useBasicMatching()
            ScanSession.markBackgroundCancelled()
            clearActiveOwner(appContext, ownerId)
        }
    }

    fun statusFlow(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(BackgroundScanWorker.UNIQUE_WORK_NAME)

    fun selectRelevantWorkInfo(
        context: Context,
        workInfos: List<WorkInfo>,
        completedWorkId: String?
    ): WorkInfo? = synchronized(LIFECYCLE_LOCK) {
        val ownerId = activeOwner(context.applicationContext)
        workInfos.firstOrNull { it.id.toString() == ownerId }
            ?: workInfos.firstOrNull { it.id.toString() == completedWorkId }
            ?: workInfos.firstOrNull {
                it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.BLOCKED
            }
            ?: workInfos.lastOrNull()
    }

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
            ?.takeIf { it in BackgroundScanWorker.SAFE_ERROR_CODES }
    )

    fun latestResult(context: Context): BackgroundScanResultStore.Snapshot? =
        BackgroundScanResultStore(context.applicationContext).load()

    fun clearLatestResult(context: Context): Boolean = synchronized(LIFECYCLE_LOCK) {
        BackgroundScanResultStore(context.applicationContext).clear()
    }

    fun hasActiveMarker(context: Context): Boolean = synchronized(LIFECYCLE_LOCK) {
        activeOwner(context.applicationContext) != null
    }

    internal fun claimActive(context: Context, workerId: String): Boolean = synchronized(LIFECYCLE_LOCK) {
        activeOwner(context.applicationContext) == workerId
    }

    internal fun isCurrentOwner(context: Context, workerId: String): Boolean = synchronized(LIFECYCLE_LOCK) {
        activeOwner(context.applicationContext) == workerId
    }

    internal fun markFailureIfOwner(context: Context, workerId: String, code: String) {
        synchronized(LIFECYCLE_LOCK) {
            if (activeOwner(context.applicationContext) == workerId) {
                ScanSession.markBackgroundFailure(code)
            }
        }
    }

    internal fun saveResultIfOwner(
        context: Context,
        workId: String,
        dossierCase: io.dossier.app.domain.case.DossierCase,
        analysis: io.dossier.app.domain.analysis.OsintAnalysisBundle
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        if (activeOwner(context.applicationContext) != workId) return@synchronized false
        BackgroundScanResultStore(context.applicationContext).save(workId, dossierCase, analysis)
    }

    internal fun finishOwner(
        context: Context,
        workerId: String,
        completion: BackgroundScanCompletion? = null
    ) {
        synchronized(LIFECYCLE_LOCK) {
            if (activeOwner(context.applicationContext) == workerId) {
                FaceCorrelationSessionPolicy.useBasicMatching()
                when (completion) {
                    BackgroundScanCompletion.Succeeded -> ScanSession.markBackgroundSucceeded()
                    is BackgroundScanCompletion.Failed -> ScanSession.markBackgroundFailure(completion.code)
                    null -> ScanSession.markBackgroundFinished()
                }
                clearActiveOwner(context.applicationContext, workerId)
            }
        }
    }

    internal fun buildRequest(requestId: String) = OneTimeWorkRequestBuilder<BackgroundScanWorker>()
        .setInputData(BackgroundScanWorker.secureInputData(requestId))
        .addTag(BackgroundScanWorker.WORK_TAG)
        .build()

    @SuppressLint("ApplySharedPref", "UseKtx")
    internal fun setActiveOwner(context: Context, ownerId: String): Boolean {
        require(isCanonicalUuid(ownerId)) { "Background owner must be a canonical UUID" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Synchronous commit is intentional: the worker must never start from an
        // owner marker that existed only in memory when power/process loss occurs.
        val committed = prefs
            .edit()
            .putString(KEY_ACTIVE_OWNER, ownerId)
            .commit()
        if (!committed) {
            prefs.edit().remove(KEY_ACTIVE_OWNER).commit()
        }
        return committed
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun clearActiveOwner(context: Context, ownerId: String?) {
        if (ownerId == null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (activeOwner(prefs) == ownerId) {
            // Match owner publication semantics so terminal cleanup is durable.
            prefs.edit().remove(KEY_ACTIVE_OWNER).commit()
        }
    }

    private fun activeOwner(context: Context): String? = activeOwner(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    )

    private fun activeOwner(prefs: android.content.SharedPreferences): String? =
        runCatching { prefs.getString(KEY_ACTIVE_OWNER, null) }
            .getOrNull()
            ?.takeIf(::isCanonicalUuid)

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private const val PREFS = "dossier-background-work"
    private const val KEY_ACTIVE_OWNER = "active_owner"
    private val LIFECYCLE_LOCK = Any()
    private fun monitorEnqueue(
        operation: androidx.work.Operation,
        context: Context,
        ownerId: String,
        requestId: String
    ) {
        operation.result.addListener(
            {
                try {
                    operation.result.get()
                } catch (_: Exception) {
                    synchronized(LIFECYCLE_LOCK) {
                        if (activeOwner(context) == ownerId) {
                            FaceCorrelationSessionPolicy.useBasicMatching()
                            ScanSession.markBackgroundFailure(ERROR_ENQUEUE_FAILED)
                            clearActiveOwner(context, ownerId)
                            ScanResumeStore(context).clearRequest(requestId)
                        }
                    }
                }
            },
            DIRECT_EXECUTOR
        )
    }

    private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    private const val ERROR_REQUEST_PERSISTENCE_FAILED = "SECURE_REQUEST_PERSISTENCE_FAILED"
    private const val ERROR_ACTIVE_MARKER_FAILED = "ACTIVE_MARKER_PERSISTENCE_FAILED"
    private const val ERROR_ENQUEUE_FAILED = "WORK_ENQUEUE_FAILED"
}

internal class BackgroundScanSchedulingException(val code: String) : IllegalStateException(code)
