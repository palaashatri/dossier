package io.dossier.app.domain.scanner

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.dossier.app.data.face.FaceCorrelationSessionPolicy
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.analysis.OsintAnalysisBundle
import io.dossier.app.domain.analysis.OsintPostProcessor
import io.dossier.app.domain.analysis.UsernameSurfaceAnalysis
import io.dossier.app.domain.case.AuthorizedScope
import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanHistoryRuntime
import io.dossier.app.domain.discovery.ProviderDiagnosticsRuntime
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.evidence.UsernameSurfaceRuntimeCache
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        val generation = inputData.getString(KEY_GENERATION)
        if (requestId.isNullOrBlank() || !isCanonicalUuid(requestId)) {
            BackgroundScanManager.finishOwner(
                applicationContext,
                workerId,
                BackgroundScanCompletion.Failed(ERROR_MISSING_REQUEST_REFERENCE),
                generation
            )
            return@coroutineScope Result.failure(failureData(ERROR_MISSING_REQUEST_REFERENCE))
        }
        if (generation != null && !isCanonicalUuid(generation)) {
            BackgroundScanManager.finishOwner(
                applicationContext,
                workerId,
                BackgroundScanCompletion.Failed(ERROR_MISSING_REQUEST_REFERENCE),
                generation
            )
            return@coroutineScope Result.failure(failureData(ERROR_MISSING_REQUEST_REFERENCE))
        }

        // WorkManager can rerun an exact UUID after a process death that
        // happened after durable success but before its own SUCCEEDED row was
        // committed. The result/lifecycle pair is authoritative for this
        // idempotent retry; do not require the checkpoint or claim a terminal
        // generation as Running again.
        if (BackgroundScanManager.isDurableSuccessForWorker(
                context = applicationContext,
                workerId = workerId,
                requestId = requestId,
                generation = generation
            )
        ) {
            FaceCorrelationSessionPolicy.useBasicMatching()
            return@coroutineScope Result.success(workDataOf(KEY_STAGE to STAGE_COMPLETE))
        }

        if (!BackgroundScanManager.claimRunning(applicationContext, workerId, generation, requestId)) {
            return@coroutineScope Result.failure(failureData(ERROR_STALE_WORK_REQUEST))
        }

        var progressRelay: Job? = null
        var completion: BackgroundScanCompletion? = null
        fun terminalFailure(code: String): Result {
            val safeCode = if (code in SAFE_ERROR_CODES) code else ERROR_SCAN_EXECUTION_FAILED
            completion = BackgroundScanCompletion.Failed(safeCode)
            return Result.failure(failureData(safeCode))
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
            val durableScanId = ScanId(requestId)
            DiscoveryScanPreferences.setMode(requestPoint.scanMode)
            ScanSession.setDeepResearch(deepResearch)
            if (strongCorrelation) FaceCorrelationSessionPolicy.useStrongCorrelation()
            else FaceCorrelationSessionPolicy.useBasicMatching()

            // Enqueue-time process state is not durable. Recreated workers must
            // rebuild the same seed-bound session and history identity before
            // executing providers; retries for this request are idempotent.
            ScanSession.markBackgroundScheduled(
                input = input,
                deepResearch = deepResearch,
                scanId = durableScanId
            )
            val historyBound = ScanHistoryRuntime.ensureStarted(
                scanId = durableScanId,
                input = input,
                mode = requestPoint.scanMode,
                directProfileProviderCount = ProviderCatalogV2
                    .legacyProfileDefinitions(requestPoint.scanMode)
                    .size,
                occurredAt = Instant.now()
            )
            if (!historyBound) {
                return@coroutineScope terminalFailure(ERROR_STALE_WORK_REQUEST)
            }

            setProgress(workDataOf(KEY_STAGE to STAGE_STARTING))
            progressRelay = launch {
                ScanSession.progressText.collect { stage ->
                    if (stage.isNotBlank() && BackgroundScanManager.isCurrentOwner(applicationContext, workerId, generation)) {
                        setProgress(safeProgressData(stage))
                    }
                }
            }

            ScanSession.executeScan(applicationContext, input, deepResearch, requestId = requestId)

            if (!BackgroundScanManager.isCurrentOwner(applicationContext, workerId, generation)) {
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

            // The coordinator normally records terminal history when the
            // worker publishes its terminal lifecycle state. Persist the exact
            // completed entry before the encrypted result write so a process
            // death between those two steps cannot erase scan-history truth.
            val completedHistory = ScanHistoryRuntime.finishForSnapshot(
                scanId = durableScanId,
                input = input,
                occurredAt = Instant.now(),
                profileResultCount = ScanSession.profileScanResults.value.size,
                findingCount = ScanSession.findings.value.size,
                breachRecordCount = ScanSession.breachDigests.value.sumOf { it.breachCount },
                graphEntityCount = ScanSession.entityGraph.value.entities.size,
                graphRelationshipCount = ScanSession.entityGraph.value.edges.size
            )
            val snapshot = ScanSession.buildCase()?.let { built ->
                attachLatestScanHistory(built, completedHistory)
            }?.copy(
                authorizedScope = AuthorizedScope.AuthorizedAssessment,
                evidenceRecords = evidenceCollection.evidence
                    .distinctBy { it.id }
                    .take(MAX_SNAPSHOT_EVIDENCE)
            )
            if (snapshot == null) {
                return@coroutineScope terminalFailure(ERROR_SNAPSHOT_UNAVAILABLE)
            }

            // Persist the encrypted result before publishing the lifecycle
            // marker. A marker is an assertion that this exact generation's
            // result exists; publishing it first would leave a durable
            // success claim after a power loss between the two writes.
            val saved = BackgroundScanManager.saveResultIfOwner(
                context = applicationContext,
                workId = id.toString(),
                dossierCase = snapshot,
                analysis = analysis,
                generation = generation
            )
            if (!saved) {
                val code = if (BackgroundScanManager.isCurrentOwner(applicationContext, workerId, generation)) {
                    ERROR_RESULT_PERSISTENCE_FAILED
                } else {
                    ERROR_STALE_WORK_REQUEST
                }
                return@coroutineScope terminalFailure(code)
            }

            val published = BackgroundScanManager.publishResultIfOwner(
                context = applicationContext,
                workerId = workerId,
                generation = generation
            )
            if (!published) {
                val code = if (BackgroundScanManager.isCurrentOwner(applicationContext, workerId, generation)) {
                    ERROR_RESULT_PERSISTENCE_FAILED
                } else {
                    ERROR_STALE_WORK_REQUEST
                }
                return@coroutineScope terminalFailure(code)
            }

            val markedSucceeded = BackgroundScanManager.markSucceededIfOwner(
                context = applicationContext,
                workerId = workerId,
                generation = generation
            )
            if (!markedSucceeded) {
                val code = if (BackgroundScanManager.isCurrentOwner(applicationContext, workerId, generation)) {
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
            throw cancelled
        } catch (_: Exception) {
            terminalFailure(ERROR_SCAN_EXECUTION_FAILED)
        } finally {
            progressRelay?.cancel()
            FaceCorrelationSessionPolicy.useBasicMatching()
            completion?.let { outcome ->
                BackgroundScanManager.finishOwner(applicationContext, workerId, outcome, generation)
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
        const val STAGE_CANCELLED = "BACKGROUND_SCAN_CANCELLED"

        internal const val KEY_REQUEST_ID = "request_id"
        internal const val KEY_GENERATION = "generation"
        private const val LEGACY_KEY_IDENTITY_JSON = "identity_json"
        private const val LEGACY_KEY_DEEP_RESEARCH = "deep_research"
        private const val LEGACY_KEY_STRONG_FACE_CORRELATION = "strong_face_correlation"
        private const val LEGACY_KEY_SCAN_MODE = "scan_mode"
        private const val LEGACY_KEY_MODE = "mode"
        private const val MAX_ERROR_CHARS = 120
        private const val MAX_SNAPSHOT_EVIDENCE = 10_000
        private val SCAN_EXECUTION_MUTEX = Mutex()

        internal const val ERROR_LEGACY_WORK_DATA_UNSUPPORTED = ScanLifecycleErrors.LEGACY_WORK_DATA_UNSUPPORTED
        internal const val ERROR_MISSING_REQUEST_REFERENCE = ScanLifecycleErrors.MISSING_SECURE_REQUEST_REFERENCE
        internal const val ERROR_REQUEST_RECORD_MISSING = ScanLifecycleErrors.SECURE_REQUEST_RECORD_MISSING
        internal const val ERROR_REQUEST_RECORD_EXPIRED = ScanLifecycleErrors.SECURE_REQUEST_RECORD_EXPIRED
        internal const val ERROR_REQUEST_RECORD_INVALID = ScanLifecycleErrors.SECURE_REQUEST_RECORD_INVALID
        internal const val ERROR_REQUEST_STORAGE_UNAVAILABLE = ScanLifecycleErrors.SECURE_REQUEST_STORAGE_UNAVAILABLE
        internal const val ERROR_STALE_WORK_REQUEST = ScanLifecycleErrors.STALE_WORK_REQUEST
        internal const val ERROR_SNAPSHOT_UNAVAILABLE = ScanLifecycleErrors.SNAPSHOT_UNAVAILABLE
        internal const val ERROR_RESULT_PERSISTENCE_FAILED = ScanLifecycleErrors.RESULT_PERSISTENCE_FAILED
        internal const val ERROR_SCAN_EXECUTION_FAILED = ScanLifecycleErrors.SCAN_EXECUTION_FAILED
        internal const val ERROR_REQUEST_PERSISTENCE_FAILED = ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
        internal const val ERROR_ACTIVE_MARKER_FAILED = ScanLifecycleErrors.ACTIVE_MARKER_PERSISTENCE_FAILED
        internal const val ERROR_ENQUEUE_FAILED = ScanLifecycleErrors.WORK_ENQUEUE_FAILED

        internal val SAFE_ERROR_CODES = ScanLifecycleErrors.SAFE_ERROR_CODES

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
            "SCAN_CANCELLED",
            STAGE_CANCELLED
        )

        internal fun isCanonicalUuid(value: String): Boolean =
            ScanLifecycleRecord.isCanonicalUuid(value)

        internal fun secureInputData(requestId: String, generation: String? = null): Data {
            require(isCanonicalUuid(requestId)) {
                "Secure request reference must be a canonical UUID"
            }
            require(generation == null || isCanonicalUuid(generation)) {
                "Secure generation reference must be a canonical UUID"
            }
            val builder = Data.Builder().putString(KEY_REQUEST_ID, requestId)
            if (generation != null) {
                builder.putString(KEY_GENERATION, generation)
            }
            return builder.build()
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

        /**
         * The coordinator owns terminal history, while this worker owns the
         * encrypted process-death snapshot. Attach only the exact seed-bound
         * completed entry; never adopt another subject's process-global row.
         */
        internal fun attachLatestScanHistory(
            dossierCase: DossierCase,
            completedHistory: CaseScanHistoryEntry?
        ): DossierCase {
            val merged = (dossierCase.scanHistory + listOfNotNull(completedHistory))
                .distinctBy { it.scanId }
                .takeLast(MAX_SNAPSHOT_SCAN_HISTORY)
            return dossierCase.copy(scanHistory = merged)
        }

        private const val MAX_SNAPSHOT_SCAN_HISTORY = 8
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

    internal var nowEpochMillis: () -> Long = { System.currentTimeMillis() }
    internal var uuidGenerator: () -> UUID = { UUID.randomUUID() }
    internal var generationGenerator: () -> UUID = { UUID.randomUUID() }
    internal var lifecycleStoreProvider: (Context) -> ScanLifecycleStore = { ScanLifecycleStore(it) }
    internal var resumeStoreProvider: (Context) -> ScanResumeStore = { ScanResumeStore(it) }
    internal var resultStoreProvider: (Context) -> BackgroundScanResultStore = { BackgroundScanResultStore(it) }
    internal var profileCheckpointClearer: (Context, String) -> Boolean = ::clearRequestRecoveryState
    internal var profileCheckpointAllClearer: (Context) -> Boolean = ::clearAllRecoveryState
    internal var resumeStateAllClearer: (Context) -> Boolean = { ScanResumeStore(it).clear() }
    // Operation callbacks can synchronously invoke an already-completed
    // Future. Keep their default executor off the UI thread because
    // cancellation reconciliation performs an exact WorkManager lookup.
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dossier-scan-lifecycle").apply { isDaemon = true }
    }
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var directExecutor: Executor = backgroundExecutor

    internal fun resetSeams() {
        nowEpochMillis = { System.currentTimeMillis() }
        uuidGenerator = { UUID.randomUUID() }
        generationGenerator = { UUID.randomUUID() }
        lifecycleStoreProvider = { ScanLifecycleStore(it) }
        resumeStoreProvider = { ScanResumeStore(it) }
        resultStoreProvider = { BackgroundScanResultStore(it) }
        profileCheckpointClearer = ::clearRequestRecoveryState
        profileCheckpointAllClearer = ::clearAllRecoveryState
        resumeStateAllClearer = { ScanResumeStore(it).clear() }
        directExecutor = backgroundExecutor
    }

    fun enqueue(
        context: Context,
        input: IdentityInput,
        deepResearch: Boolean,
        strongFaceCorrelation: Boolean
    ): UUID {
        val appContext = context.applicationContext
        synchronized(LIFECYCLE_LOCK) {
            val resumeStore = resumeStoreProvider(appContext)
            val lifecycleStore = lifecycleStoreProvider(appContext)
            val resultStore = resultStoreProvider(appContext)

            val saved = resumeStore.prepareRequestDetailed(input, deepResearch, strongFaceCorrelation)
            val requestId = (saved as? ResumeWriteState.Saved)?.point?.requestId
                ?: throw BackgroundScanSchedulingException(BackgroundScanWorker.ERROR_REQUEST_PERSISTENCE_FAILED)

            val ownerUuid = uuidGenerator()
            val ownerId = ownerUuid.toString()
            val generationUuid = generationGenerator()
            val generation = generationUuid.toString()
            val request = buildRequest(requestId = requestId, generation = generation, ownerUuid = ownerUuid)
            val lifecycleRead = lifecycleStore.read()
            val existingLifecycle = (lifecycleRead as? ScanLifecycleReadResult.Available)?.record
            val legacyOwner = if (lifecycleRead == ScanLifecycleReadResult.Missing) {
                activeOwner(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            } else {
                null
            }

            val pendingRecord = ScanLifecycleRecord(
                ownerId = ownerId,
                requestId = requestId,
                generation = generation,
                phase = ScanLifecyclePhase.EnqueuePending,
                updatedAtEpochMillis = nowEpochMillis(),
                resultReady = false,
                errorCode = null
            )
            val publishResult = when (lifecycleRead) {
                is ScanLifecycleReadResult.Invalid,
                ScanLifecycleReadResult.StorageFailure -> {
                    resumeStore.discardPreparedRequest(requestId)
                    throw BackgroundScanSchedulingException(ScanLifecycleErrors.LIFECYCLE_STORAGE_FAILURE)
                }
                is ScanLifecycleReadResult.Available -> lifecycleStore.replace(existingLifecycle!!, pendingRecord)
                ScanLifecycleReadResult.Missing -> lifecycleStore.publish(pendingRecord)
            }
            if (publishResult !is ScanLifecycleWriteResult.Saved) {
                resumeStore.discardPreparedRequest(requestId)
                throw BackgroundScanSchedulingException(BackgroundScanWorker.ERROR_ACTIVE_MARKER_FAILED)
            }

            // The replacement lifecycle is now durable. Cancel only the old
            // exact WorkManager UUID after that atomic hand-off; never clear A
            // before B is represented durably.
            val replacedOwnerId = existingLifecycle?.ownerId ?: legacyOwner
            replacedOwnerId
                ?.takeIf { it != ownerId }
                ?.let { oldOwnerId ->
                    runCatching { UUID.fromString(oldOwnerId) }.getOrNull()?.let { oldOwnerUuid ->
                        runCatching {
                            WorkManager.getInstance(appContext).cancelWorkById(oldOwnerUuid)
                        }
                    }
                }

            val promoted = resumeStore.promotePreparedRequestDetailed(requestId)
            if (promoted !is ResumeReadState.Available) {
                // Keep pending B + its prepared record for startup recovery on
                // a transient promotion/storage failure. Invalid/expired B is
                // surfaced as a lifecycle failure without touching A's files.
                val failureCode = when (promoted) {
                    ResumeReadState.Expired,
                    ResumeReadState.Missing -> ScanLifecycleErrors.CHECKPOINT_MISSING
                    is ResumeReadState.Invalid -> ScanLifecycleErrors.CHECKPOINT_INVALID
                    is ResumeReadState.StorageFailure -> ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
                    is ResumeReadState.Available -> ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
                }
                if (promoted !is ResumeReadState.StorageFailure) {
                    lifecycleStore.transition(
                        expected = pendingRecord,
                        transition = ScanLifecycleTransition.MarkFailed(failureCode),
                        nowEpochMillis = nowEpochMillis()
                    )
                }
                throw BackgroundScanSchedulingException(BackgroundScanWorker.ERROR_REQUEST_PERSISTENCE_FAILED)
            }

            ScanSession.markBackgroundScheduled(input, deepResearch)
            try {
                val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                    BackgroundScanWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                monitorEnqueue(
                    operation = operation,
                    context = appContext,
                    pendingRecord = pendingRecord,
                    priorOwnerId = replacedOwnerId,
                    priorRequestId = existingLifecycle?.requestId
                )
            } catch (_: Exception) {
                FaceCorrelationSessionPolicy.useBasicMatching()
                ScanSession.markBackgroundFailure(BackgroundScanWorker.ERROR_ENQUEUE_FAILED)
                lifecycleStore.transition(
                    expected = pendingRecord,
                    transition = ScanLifecycleTransition.MarkFailed(BackgroundScanWorker.ERROR_ENQUEUE_FAILED),
                    nowEpochMillis = nowEpochMillis()
                )
                if (!clearProfileThenRequest(
                        context = appContext,
                        requestId = requestId,
                        clearRequest = { resumeStore.clearRequest(requestId) }
                    )
                ) {
                    ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
                }
                throw BackgroundScanSchedulingException(BackgroundScanWorker.ERROR_ENQUEUE_FAILED)
            }
            return request.id
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        synchronized(LIFECYCLE_LOCK) {
            val lifecycleStore = lifecycleStoreProvider(appContext)
            var legacyCancellation = false
            when (val readResult = lifecycleStore.read()) {
                is ScanLifecycleReadResult.Available -> {
                    val record = readResult.record
                    val requested = lifecycleStore.transition(
                        expected = record,
                        transition = ScanLifecycleTransition.RequestCancel,
                        nowEpochMillis = nowEpochMillis()
                    )
                    if (requested !is ScanLifecycleWriteResult.Saved) return@synchronized
                    val cancelling = (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                        ?.takeIf { it.ownerId == record.ownerId && it.requestId == record.requestId && it.generation == record.generation }
                        ?: return@synchronized
                    val ownerUuid = runCatching { UUID.fromString(record.ownerId) }.getOrNull()
                    if (ownerUuid != null) {
                        try {
                            monitorCancellation(
                                operation = WorkManager.getInstance(appContext).cancelWorkById(ownerUuid),
                                context = appContext,
                                expected = cancelling
                            )
                        } catch (_: Exception) {
                            lifecycleStore.transition(
                                expected = cancelling,
                                transition = ScanLifecycleTransition.MarkCancelFailed(),
                                nowEpochMillis = nowEpochMillis()
                            )
                            ScanSession.markBackgroundFailure(ScanLifecycleErrors.CANCEL_REQUEST_FAILED)
                        }
                    } else {
                        lifecycleStore.transition(
                            expected = cancelling,
                            transition = ScanLifecycleTransition.MarkCancelFailed(),
                            nowEpochMillis = nowEpochMillis()
                        )
                    }
                    FaceCorrelationSessionPolicy.useBasicMatching()
                }
                ScanLifecycleReadResult.Missing -> {
                    legacyCancellation = true
                    val legacyOwner = activeOwner(appContext)
                    val ownerUuid = legacyOwner?.let { value ->
                        runCatching { UUID.fromString(value) }.getOrNull()
                    }
                    if (ownerUuid != null) {
                        WorkManager.getInstance(appContext).cancelWorkById(ownerUuid)
                    } else {
                        WorkManager.getInstance(appContext).cancelUniqueWork(BackgroundScanWorker.UNIQUE_WORK_NAME)
                    }
                    clearActiveOwner(appContext, legacyOwner)
                }
                is ScanLifecycleReadResult.Invalid,
                ScanLifecycleReadResult.StorageFailure -> {
                    // There is no trustworthy owner to cancel. Never fall
                    // back to unique-work cancellation, which could target a
                    // replacement generation.
                    ScanSession.markBackgroundFailure(ScanLifecycleErrors.LIFECYCLE_STORAGE_FAILURE)
                    return@synchronized
                }
            }
            // MarkCancelled is published by monitorCancellation only after an
            // exact WorkInfo row confirms terminal cancellation. Legacy work
            // has no lifecycle row, so retain its historical immediate state.
            if (legacyCancellation) ScanSession.markBackgroundCancelled()
        }
    }

    /**
     * Requests a bounded pause of the exact lifecycle owner.  Pausing is a
     * durable two-step state: the reducer records intent first, then this
     * method asks WorkManager to cancel the exact UUID.  [monitorPause]
     * commits Paused only after the exact row is terminal, so an in-flight
     * worker cannot be mistaken for a resumable checkpoint.
     */
    fun pause(context: Context): Boolean {
        val appContext = context.applicationContext
        synchronized(LIFECYCLE_LOCK) {
            val lifecycleStore = lifecycleStoreProvider(appContext)
            val record = (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                ?: return false
            if (record.phase == ScanLifecyclePhase.Paused) return true
            if (record.phase !in setOf(
                    ScanLifecyclePhase.EnqueuePending,
                    ScanLifecyclePhase.Enqueued,
                    ScanLifecyclePhase.Running,
                    ScanLifecyclePhase.Pausing
                )
            ) return false

            val requested = lifecycleStore.transition(
                expected = record,
                transition = ScanLifecycleTransition.RequestPause,
                nowEpochMillis = nowEpochMillis()
            )
            if (requested !is ScanLifecycleWriteResult.Saved) return false
            val pausing = (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                ?.takeIf { isSameLifecycleGeneration(it, record) }
                ?: return false
            val ownerUuid = runCatching { UUID.fromString(pausing.ownerId) }.getOrNull()
                ?: return false
            try {
                monitorPause(
                    operation = WorkManager.getInstance(appContext).cancelWorkById(ownerUuid),
                    context = appContext,
                    expected = pausing
                )
            } catch (_: Exception) {
                // Keep Pausing durable. Startup reconciliation will retry the
                // exact owner rather than silently resuming or failing it.
            }
            return true
        }
    }

    /**
     * Resumes only a fully Paused generation. A fresh WorkManager UUID is
     * required because a cancelled WorkRequest cannot be enqueued again; the
     * request/generation remain constant and the exact Paused snapshot is the
     * compare-and-swap guard against stale callbacks.
     */
    fun resume(context: Context): Boolean {
        val appContext = context.applicationContext
        synchronized(LIFECYCLE_LOCK) {
            val lifecycleStore = lifecycleStoreProvider(appContext)
            val resumeStore = resumeStoreProvider(appContext)
            val resultStore = resultStoreProvider(appContext)
            val paused = (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                ?: return false
            if (paused.phase != ScanLifecyclePhase.Paused) return false

            // A result that belongs to this paused owner is a publication race,
            // not resumable work. Leave it untouched; reconciliation promotes
            // the exact lifecycle to Succeeded and keeps the evidence visible.
            val persistedResultOwner = runCatching { resultStore.load()?.workId }.getOrNull()
            if (paused.resultReady || persistedResultOwner == paused.ownerId) return false
            val checkpoint = runCatching { resumeStore.loadRequestDetailed(paused.requestId) }
                .getOrNull()
            if (checkpoint !is ResumeReadState.Available) {
                return false
            }

            val newOwnerId = uuidGenerator().toString()
            if (newOwnerId == paused.ownerId) return false
            val pending = try {
                ScanLifecycleRecord(
                    ownerId = newOwnerId,
                    requestId = paused.requestId,
                    generation = paused.generation,
                    phase = ScanLifecyclePhase.EnqueuePending,
                    updatedAtEpochMillis = nowEpochMillis(),
                    resultReady = false,
                    errorCode = null
                )
            } catch (_: IllegalArgumentException) {
                return false
            }
            if (lifecycleStore.replace(paused, pending) !is ScanLifecycleWriteResult.Saved) {
                return false
            }

            val ownerUuid = runCatching { UUID.fromString(newOwnerId) }.getOrNull()
                ?: return false
            val request = buildRequest(
                requestId = pending.requestId,
                generation = pending.generation,
                ownerUuid = ownerUuid
            )
            try {
                val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                    BackgroundScanWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                monitorEnqueue(
                    operation = operation,
                    context = appContext,
                    pendingRecord = pending,
                    priorOwnerId = paused.ownerId,
                    priorRequestId = null
                )
            } catch (_: Exception) {
                // Leave the exact pending record/checkpoint for startup
                // reconciliation to retry. No result or checkpoint is erased.
            }
            return true
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
        val selectedId = selectRelevantWorkId(
            activeOwnerId = ownerId,
            completedWorkId = completedWorkId,
            availableWorkIds = workInfos.mapTo(linkedSetOf()) { it.id.toString() }
        )
        selectedId?.let { id -> workInfos.firstOrNull { it.id.toString() == id } }
    }

    internal fun selectRelevantWorkId(
        activeOwnerId: String?,
        completedWorkId: String?,
        availableWorkIds: Set<String>
    ): String? = sequenceOf(activeOwnerId, completedWorkId)
        .filterNotNull()
        .distinct()
        .firstOrNull { it in availableWorkIds }

    internal fun safeStatusStage(
        progressStage: String?,
        outputStage: String?,
        state: WorkInfo.State
    ): String {
        val stateStage = when (state) {
            WorkInfo.State.ENQUEUED -> BackgroundScanWorker.STAGE_STARTING
            WorkInfo.State.SUCCEEDED -> BackgroundScanWorker.STAGE_COMPLETE
            WorkInfo.State.FAILED -> BackgroundScanWorker.STAGE_FAILED
            WorkInfo.State.CANCELLED -> BackgroundScanWorker.STAGE_CANCELLED
            else -> BackgroundScanWorker.STAGE_RUNNING
        }
        val terminal = state == WorkInfo.State.SUCCEEDED ||
            state == WorkInfo.State.FAILED ||
            state == WorkInfo.State.CANCELLED
        val candidate = if (terminal) outputStage ?: stateStage else progressStage ?: outputStage ?: stateStage
        val sanitized = BackgroundScanWorker.safeProgressData(candidate)
            .getString(BackgroundScanWorker.KEY_STAGE)
            ?: BackgroundScanWorker.STAGE_RUNNING
        return if (terminal && sanitized == BackgroundScanWorker.STAGE_RUNNING) stateStage else sanitized
    }

    fun toStatus(info: WorkInfo): Status = Status(
        id = info.id,
        state = info.state,
        stage = safeStatusStage(
            progressStage = info.progress.getString(BackgroundScanWorker.KEY_STAGE),
            outputStage = info.outputData.getString(BackgroundScanWorker.KEY_STAGE),
            state = info.state
        ),
        error = info.outputData.getString(BackgroundScanWorker.KEY_ERROR)
            ?.takeIf { it in BackgroundScanWorker.SAFE_ERROR_CODES }
    )

    fun latestResult(context: Context): BackgroundScanResultStore.Snapshot? = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val snapshot = resultStoreProvider(appContext).load() ?: return@synchronized null
        return@synchronized if (isResultVisible(
                lifecycle = lifecycleStoreProvider(appContext).read(),
                resultOwnerId = snapshot.workId
            )
        ) {
            snapshot
        } else {
            null
        }
    }

    suspend fun latestResultAsync(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): BackgroundScanResultStore.Snapshot? = withContext(dispatcher) {
        latestResult(context)
    }

    /**
     * A saved file is not yet a published result. Hide the narrow
     * save-before-marker crash window and every owner mismatch from UI/report
     * consumers. Missing lifecycle is the valid post-success cleanup state.
     */
    internal fun isResultVisible(
        lifecycle: ScanLifecycleReadResult,
        resultOwnerId: String
    ): Boolean = when (lifecycle) {
        ScanLifecycleReadResult.Missing -> BackgroundScanWorker.isCanonicalUuid(resultOwnerId)
        is ScanLifecycleReadResult.Available ->
            lifecycle.record.ownerId == resultOwnerId &&
                lifecycle.record.resultReady &&
                lifecycle.record.phase in setOf(
                    ScanLifecyclePhase.Running,
                    ScanLifecyclePhase.Pausing,
                    ScanLifecyclePhase.Paused,
                    ScanLifecyclePhase.Succeeded
                )
        is ScanLifecycleReadResult.Invalid,
        ScanLifecycleReadResult.StorageFailure -> false
    }

    /**
     * Idempotent retry guard for the narrow success-commit crash window. A
     * matching encrypted result proves this exact WorkManager UUID completed;
     * a lifecycle Succeeded row additionally binds request and generation.
     * Missing lifecycle is accepted only with the matching result owner,
     * representing automatic post-success lifecycle cleanup.
     */
    internal fun isDurableSuccessForWorker(
        context: Context,
        workerId: String,
        requestId: String,
        generation: String?
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        val lifecycleRead = lifecycleStoreProvider(context.applicationContext).read()
        val resultWorkId = resultStoreProvider(context.applicationContext).load()?.workId
        isDurableSuccess(
            lifecycle = lifecycleRead,
            resultWorkId = resultWorkId,
            workerId = workerId,
            requestId = requestId,
            generation = generation
        )
    }

    internal fun isDurableSuccess(
        lifecycle: ScanLifecycleReadResult,
        resultWorkId: String?,
        workerId: String,
        requestId: String,
        generation: String?
    ): Boolean {
        if (resultWorkId != workerId) return false
        return when (lifecycle) {
            ScanLifecycleReadResult.Missing -> true
            is ScanLifecycleReadResult.Available -> {
                val record = lifecycle.record
                record.ownerId == workerId &&
                    record.requestId == requestId &&
                    (generation == null || record.generation == generation) &&
                    record.phase in setOf(ScanLifecyclePhase.Succeeded, ScanLifecyclePhase.Paused) &&
                    record.resultReady
            }
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> false
        }
    }

    fun clearLatestResult(context: Context): Boolean = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val lifecycleStore = lifecycleStoreProvider(appContext)
        val resultStore = resultStoreProvider(appContext)
        when (val readResult = lifecycleStore.read()) {
            is ScanLifecycleReadResult.Available -> {
                val record = readResult.record
                val retainCancellationLifecycle = record.phase in setOf(
                    ScanLifecyclePhase.CancelRequested,
                    ScanLifecyclePhase.CancelFailed
                )
                val cleanupRecord = when {
                    record.phase in setOf(
                        ScanLifecyclePhase.Succeeded,
                        ScanLifecyclePhase.Failed,
                        ScanLifecyclePhase.Cancelled,
                        ScanLifecyclePhase.Paused
                    ) -> {
                        val transitioned = lifecycleStore.transition(
                            expected = record,
                            transition = ScanLifecycleTransition.BeginCleanup,
                            nowEpochMillis = nowEpochMillis()
                        )
                        if (transitioned !is ScanLifecycleWriteResult.Saved) return@synchronized false
                        (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                            ?.takeIf { it.ownerId == record.ownerId && it.requestId == record.requestId && it.generation == record.generation }
                    }
                    record.phase == ScanLifecyclePhase.CleanupPending -> record
                    retainCancellationLifecycle -> record
                    else -> return@synchronized false
                } ?: return@synchronized false

                val snapshot = resultStore.load()
                if (snapshot != null && !isResultSafeToRetire(
                        resultWorkId = snapshot.workId,
                        completedAtUtc = snapshot.completedAtUtc,
                        lifecycle = record
                    )
                ) {
                    // Preserve a result that cannot be proven to belong to,
                    // or predate, the lifecycle being explicitly purged.
                    return@synchronized false
                }
                val resumeStore = runCatching { resumeStoreProvider(appContext) }.getOrNull()
                    ?: return@synchronized false
                val resultCleared = snapshot == null || resultStore.clear()
                val requestStateCleared = resultCleared && clearProfileThenRequest(
                    context = appContext,
                    requestId = cleanupRecord.requestId,
                    clearRequest = { resumeStore.clearRequest(cleanupRecord.requestId) }
                )
                if (!requestStateCleared) {
                    ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
                    return@synchronized false
                }
                // purgeSession calls cancel before this method. Once durable
                // cancel intent exists, the encrypted checkpoint/result can
                // be removed immediately; retain only the non-sensitive UUID
                // lifecycle until the exact WorkInfo row becomes terminal.
                if (retainCancellationLifecycle) return@synchronized true
                if (!isExactLifecycle(lifecycleStore, cleanupRecord)) return@synchronized false
                return@synchronized lifecycleStore.clear(cleanupRecord) is ScanLifecycleWriteResult.Cleared
            }
            ScanLifecycleReadResult.Missing -> {
                // With no lifecycle owner, only clear an existing result by
                // the opaque owner embedded in that result; never infer a
                // generation from unique-work list ordering. Explicit purge
                // also retires any request-scoped orphans left by a crashed
                // replacement callback before clearing all resume state.
                val resultCleared = resultStore.load()?.let { resultStore.clear() } ?: true
                if (!resultCleared) return@synchronized false
                val profilesCleared = runCatching {
                    profileCheckpointAllClearer(appContext)
                }.getOrDefault(false)
                if (!profilesCleared) {
                    ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
                    return@synchronized false
                }
                val resumeCleared = runCatching {
                    resumeStateAllClearer(appContext)
                }.getOrDefault(false)
                if (!resumeCleared) {
                    ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
                    return@synchronized false
                }
                return@synchronized true
            }
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> {
                ScanSession.markBackgroundFailure(ScanLifecycleErrors.LIFECYCLE_STORAGE_FAILURE)
                return@synchronized false
            }
        }
    }

    suspend fun clearLatestResultAsync(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        clearLatestResult(context)
    }

    internal fun isResultSafeToRetire(
        resultWorkId: String,
        completedAtUtc: String,
        lifecycle: ScanLifecycleRecord
    ): Boolean {
        if (resultWorkId == lifecycle.ownerId) return true
        val completedAtEpochMillis = runCatching {
            Instant.parse(completedAtUtc).toEpochMilli()
        }.getOrNull() ?: return false
        return completedAtEpochMillis <= lifecycle.updatedAtEpochMillis
    }

    private fun clearRequestRecoveryState(context: Context, requestId: String): Boolean {
        val profilesCleared = ProfileScanCheckpointStore.clearRequest(context, requestId)
        val frontierCleared = PivotFrontierStore.clearRequest(context, requestId)
        return profilesCleared && frontierCleared
    }

    private fun clearAllRecoveryState(context: Context): Boolean {
        val profilesCleared = ProfileScanCheckpointStore.clearAll(context)
        val frontiersCleared = PivotFrontierStore.clearAll(context)
        return profilesCleared && frontiersCleared
    }

    fun hasActiveMarker(context: Context): Boolean = synchronized(LIFECYCLE_LOCK) {
        when (val lifecycleRead = lifecycleStoreProvider(context.applicationContext).read()) {
            is ScanLifecycleReadResult.Available -> {
                val lifecycle = lifecycleRead.record
                return@synchronized lifecycle.phase in setOf(
                    ScanLifecyclePhase.EnqueuePending,
                    ScanLifecyclePhase.Enqueued,
                    ScanLifecyclePhase.Running,
                    ScanLifecyclePhase.Pausing,
                    ScanLifecyclePhase.CancelRequested,
                    ScanLifecyclePhase.CancelFailed
                )
            }
            ScanLifecycleReadResult.Missing -> {
                return@synchronized activeOwner(context.applicationContext) != null
            }
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> {
                return@synchronized false
            }
        }
    }

    /**
     * Reads the encrypted lifecycle marker without performing keystore/file
     * work on a caller's UI thread. The synchronous form remains internal to
     * lifecycle code that already owns its dispatcher/lock boundary.
     */
    suspend fun hasActiveMarkerAsync(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Boolean = withContext(dispatcher) {
        hasActiveMarker(context)
    }

    /**
     * Reads the durable lifecycle phase without doing encrypted storage work
     * on the caller's UI thread.  WorkManager's row alone cannot represent a
     * paused checkpoint because a paused request is intentionally cancelled.
     */
    suspend fun lifecyclePhaseAsync(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): ScanLifecyclePhase? = withContext(dispatcher) {
        synchronized(LIFECYCLE_LOCK) {
            (lifecycleStoreProvider(context.applicationContext).read()
                as? ScanLifecycleReadResult.Available)
                ?.record
                ?.phase
        }
    }

    internal fun claimActive(context: Context, workerId: String): Boolean =
        claimRunning(context, workerId)

    internal fun claimRunning(
        context: Context,
        workerId: String,
        generation: String? = null,
        requestId: String? = null
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val lifecycleStore = lifecycleStoreProvider(appContext)
        when (val readResult = lifecycleStore.read()) {
            is ScanLifecycleReadResult.Available -> {
                val record = readResult.record
                if (record.ownerId != workerId) return@synchronized false
                if (generation != null && record.generation != generation) return@synchronized false
                if (requestId != null && record.requestId != requestId) return@synchronized false
                if (record.phase == ScanLifecyclePhase.Running) return@synchronized true
                if (record.phase in setOf(ScanLifecyclePhase.EnqueuePending, ScanLifecyclePhase.Enqueued)) {
                    val transitionResult = lifecycleStore.transition(
                        expected = record,
                        transition = ScanLifecycleTransition.MarkRunning,
                        nowEpochMillis = nowEpochMillis()
                    )
                    return@synchronized transitionResult is ScanLifecycleWriteResult.Saved
                }
                return@synchronized false
            }
            ScanLifecycleReadResult.Missing -> {
                if (requestId != null && BackgroundScanWorker.isCanonicalUuid(requestId)) {
                    val migGen = generation ?: generationGenerator().toString()
                    val mig = lifecycleStore.migrateLegacyActiveOwner(
                        currentEncryptedRequestId = requestId,
                        generation = migGen,
                        updatedAtEpochMillis = nowEpochMillis()
                    )
                    if (mig is ScanLifecycleWriteResult.Saved) {
                        val migRecord = (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                        if (migRecord != null && migRecord.ownerId == workerId) {
                            val res = lifecycleStore.transition(
                                expected = migRecord,
                                transition = ScanLifecycleTransition.MarkRunning,
                                nowEpochMillis = nowEpochMillis()
                            )
                            return@synchronized res is ScanLifecycleWriteResult.Saved
                        }
                    }
                }
                return@synchronized activeOwner(appContext) == workerId
            }
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> false
        }
    }

    internal fun isCurrentOwner(
        context: Context,
        workerId: String,
        generation: String? = null
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val lifecycleStore = lifecycleStoreProvider(appContext)
        when (val readResult = lifecycleStore.read()) {
            is ScanLifecycleReadResult.Available -> {
                val record = readResult.record
                if (record.ownerId != workerId) return@synchronized false
                if (generation != null && record.generation != generation) return@synchronized false
                return@synchronized record.phase in setOf(
                    ScanLifecyclePhase.EnqueuePending,
                    ScanLifecyclePhase.Enqueued,
                    ScanLifecyclePhase.Running,
                    ScanLifecyclePhase.Pausing
                )
            }
            ScanLifecycleReadResult.Missing -> activeOwner(appContext) == workerId
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> false
        }
    }

    internal fun publishResultIfOwner(
        context: Context,
        workerId: String,
        generation: String? = null
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val lifecycleStore = lifecycleStoreProvider(appContext)
        when (val readResult = lifecycleStore.read()) {
            is ScanLifecycleReadResult.Available -> {
                val record = readResult.record
                if (record.ownerId != workerId) return@synchronized false
                if (generation != null && record.generation != generation) return@synchronized false
                if (record.phase != ScanLifecyclePhase.Running &&
                    record.phase != ScanLifecyclePhase.Pausing
                ) return@synchronized false
                if (record.resultReady) return@synchronized true
                val transitionResult = lifecycleStore.transition(
                    expected = record,
                    transition = ScanLifecycleTransition.PublishResult,
                    nowEpochMillis = nowEpochMillis()
                )
                return@synchronized transitionResult is ScanLifecycleWriteResult.Saved
            }
            ScanLifecycleReadResult.Missing -> activeOwner(appContext) == workerId
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> false
        }
    }

    internal fun markSucceededIfOwner(
        context: Context,
        workerId: String,
        generation: String? = null
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val lifecycleStore = lifecycleStoreProvider(appContext)
        when (val readResult = lifecycleStore.read()) {
            is ScanLifecycleReadResult.Available -> {
                var record = readResult.record
                if (record.ownerId != workerId) return@synchronized false
                if (generation != null && record.generation != generation) return@synchronized false
                if (record.phase == ScanLifecyclePhase.Succeeded) return@synchronized true
                if (record.phase != ScanLifecyclePhase.Running &&
                    record.phase != ScanLifecyclePhase.Pausing
                ) return@synchronized false
                if (!record.resultReady) {
                    val pub = lifecycleStore.transition(
                        expected = record,
                        transition = ScanLifecycleTransition.PublishResult,
                        nowEpochMillis = nowEpochMillis()
                    )
                    if (pub !is ScanLifecycleWriteResult.Saved) return@synchronized false
                    record = (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                        ?: return@synchronized false
                }
                val transitionResult = lifecycleStore.transition(
                    expected = record,
                    transition = ScanLifecycleTransition.MarkSucceeded,
                    nowEpochMillis = nowEpochMillis()
                )
                return@synchronized transitionResult is ScanLifecycleWriteResult.Saved
            }
            ScanLifecycleReadResult.Missing -> activeOwner(appContext) == workerId
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> false
        }
    }

    internal fun markFailureIfOwner(
        context: Context,
        workerId: String,
        code: String,
        generation: String? = null
    ) {
        synchronized(LIFECYCLE_LOCK) {
            val appContext = context.applicationContext
            val lifecycleStore = lifecycleStoreProvider(appContext)
            when (val readResult = lifecycleStore.read()) {
                is ScanLifecycleReadResult.Available -> {
                    val record = readResult.record
                    if (record.ownerId == workerId && (generation == null || record.generation == generation)) {
                        val safeCode = if (code in ScanLifecycleErrors.SAFE_ERROR_CODES) code else ScanLifecycleErrors.SCAN_EXECUTION_FAILED
                        ScanSession.markBackgroundFailure(safeCode)
                        if (record.phase in setOf(
                                ScanLifecyclePhase.EnqueuePending,
                                ScanLifecyclePhase.Enqueued,
                                ScanLifecyclePhase.Running,
                                ScanLifecyclePhase.Pausing
                            )
                        ) {
                            lifecycleStore.transition(
                                expected = record,
                                transition = ScanLifecycleTransition.MarkFailed(safeCode),
                                nowEpochMillis = nowEpochMillis()
                            )
                        }
                    }
                }
                ScanLifecycleReadResult.Missing -> {
                    if (activeOwner(appContext) == workerId) {
                        val safeCode = if (code in ScanLifecycleErrors.SAFE_ERROR_CODES) {
                            code
                        } else {
                            ScanLifecycleErrors.SCAN_EXECUTION_FAILED
                        }
                        ScanSession.markBackgroundFailure(safeCode)
                    }
                }
                is ScanLifecycleReadResult.Invalid,
                ScanLifecycleReadResult.StorageFailure -> {
                    ScanSession.markBackgroundFailure(ScanLifecycleErrors.LIFECYCLE_STORAGE_FAILURE)
                }
            }
        }
    }

    internal fun saveResultIfOwner(
        context: Context,
        workId: String,
        dossierCase: DossierCase,
        analysis: OsintAnalysisBundle,
        generation: String? = null
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        if (!isCurrentOwner(context, workId, generation)) return@synchronized false
        resultStoreProvider(context.applicationContext).save(workId, dossierCase, analysis)
    }

    internal fun finishOwner(
        context: Context,
        workerId: String,
        completion: BackgroundScanCompletion? = null,
        generation: String? = null
    ) {
        synchronized(LIFECYCLE_LOCK) {
            val appContext = context.applicationContext
            val lifecycleStore = lifecycleStoreProvider(appContext)
            when (val readResult = lifecycleStore.read()) {
                is ScanLifecycleReadResult.Available -> {
                    val record = readResult.record
                    if (record.ownerId != workerId || (generation != null && record.generation != generation)) {
                        return@synchronized
                    }
                    FaceCorrelationSessionPolicy.useBasicMatching()
                    when (completion) {
                        BackgroundScanCompletion.Succeeded -> {
                            if (record.phase == ScanLifecyclePhase.Succeeded || record.resultReady) {
                                if (record.phase == ScanLifecyclePhase.Running ||
                                    record.phase == ScanLifecyclePhase.Pausing
                                ) {
                                    lifecycleStore.transition(
                                        expected = record,
                                        transition = ScanLifecycleTransition.MarkSucceeded,
                                        nowEpochMillis = nowEpochMillis()
                                    )
                                }
                                ScanSession.markBackgroundSucceeded()
                            } else if (record.phase == ScanLifecyclePhase.Running ||
                                record.phase == ScanLifecyclePhase.Pausing
                            ) {
                                // Do not manufacture a result marker in a
                                // terminal cleanup callback. The worker must
                                // persist the encrypted snapshot and publish
                                // it explicitly before claiming success.
                                ScanSession.markBackgroundFailure(ScanLifecycleErrors.RESULT_MISSING)
                                lifecycleStore.transition(
                                    expected = record,
                                    transition = ScanLifecycleTransition.MarkFailed(
                                        ScanLifecycleErrors.RESULT_MISSING
                                    ),
                                    nowEpochMillis = nowEpochMillis()
                                )
                            }
                        }
                        is BackgroundScanCompletion.Failed -> {
                            val safeCode = if (completion.code in ScanLifecycleErrors.SAFE_ERROR_CODES) {
                                completion.code
                            } else {
                                ScanLifecycleErrors.SCAN_EXECUTION_FAILED
                            }
                            ScanSession.markBackgroundFailure(safeCode)
                            if (record.phase in setOf(
                                    ScanLifecyclePhase.EnqueuePending,
                                    ScanLifecyclePhase.Enqueued,
                                    ScanLifecyclePhase.Running,
                                    ScanLifecyclePhase.Pausing
                                )
                            ) {
                                lifecycleStore.transition(
                                    expected = record,
                                    transition = ScanLifecycleTransition.MarkFailed(safeCode),
                                    nowEpochMillis = nowEpochMillis()
                                )
                            }
                        }
                        null -> ScanSession.markBackgroundFinished()
                    }
                    clearActiveOwner(appContext, workerId)
                }
                ScanLifecycleReadResult.Missing -> {
                    if (activeOwner(appContext) == workerId) {
                        FaceCorrelationSessionPolicy.useBasicMatching()
                        when (completion) {
                            BackgroundScanCompletion.Succeeded -> ScanSession.markBackgroundSucceeded()
                            is BackgroundScanCompletion.Failed -> ScanSession.markBackgroundFailure(completion.code)
                            null -> ScanSession.markBackgroundFinished()
                        }
                        clearActiveOwner(appContext, workerId)
                    }
                }
                is ScanLifecycleReadResult.Invalid,
                ScanLifecycleReadResult.StorageFailure -> Unit
            }
        }
    }

    internal fun buildRequest(requestId: String) =
        buildRequest(requestId = requestId, generation = null, ownerUuid = uuidGenerator())

    internal fun buildRequest(
        requestId: String,
        generation: String? = null,
        ownerUuid: UUID = uuidGenerator()
    ): OneTimeWorkRequest {
        require(BackgroundScanWorker.isCanonicalUuid(requestId)) {
            "Secure request reference must be a canonical UUID"
        }
        require(generation == null || BackgroundScanWorker.isCanonicalUuid(generation)) {
            "Secure generation reference must be a canonical UUID"
        }
        return OneTimeWorkRequestBuilder<BackgroundScanWorker>()
            .setId(ownerUuid)
            .setInputData(BackgroundScanWorker.secureInputData(requestId, generation))
            .addTag(BackgroundScanWorker.WORK_TAG)
            .build()
    }

    private fun lookupWorkInfo(context: Context, ownerId: String): ScanWorkInfoLookup {
        val ownerUuid = runCatching { UUID.fromString(ownerId) }.getOrNull()
            ?: return ScanWorkInfoLookup.Missing
        return try {
            val info = WorkManager.getInstance(context.applicationContext)
                .getWorkInfoById(ownerUuid)
                .get(WORK_INFO_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (info == null) {
                ScanWorkInfoLookup.Missing
            } else {
                val state = when (info.state) {
                    WorkInfo.State.ENQUEUED -> ScanWorkState.Enqueued
                    WorkInfo.State.RUNNING -> ScanWorkState.Running
                    WorkInfo.State.BLOCKED -> ScanWorkState.Blocked
                    WorkInfo.State.SUCCEEDED -> ScanWorkState.Succeeded
                    WorkInfo.State.FAILED -> ScanWorkState.Failed
                    WorkInfo.State.CANCELLED -> ScanWorkState.Cancelled
                }
                // Trust the UUID returned by the exact lookup, not the
                // lifecycle row that requested it.
                ScanWorkInfoLookup.Available(ScanWorkInfoSummary(info.id.toString(), state))
            }
        } catch (_: Exception) {
            ScanWorkInfoLookup.Unavailable
        }
    }

    fun reconcile(context: Context): ScanReconciliationAction = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val lifecycleStore = lifecycleStoreProvider(appContext)
        val resumeStore = resumeStoreProvider(appContext)
        val resultStore = resultStoreProvider(appContext)

        var preloadedWorkInfo: ScanWorkInfoLookup? = null
        val lifecycleRecord = when (val readResult = lifecycleStore.read()) {
            is ScanLifecycleReadResult.Available -> readResult.record
            ScanLifecycleReadResult.Missing -> {
                val legacyOwner = activeOwner(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                if (legacyOwner != null) {
                    // Probe the exact legacy UUID before migrating the marker.
                    // An unavailable lookup is a zero-mutation outcome; do
                    // not publish a lifecycle row that startup cannot yet
                    // reconcile.
                    val legacyLookup = lookupWorkInfo(appContext, legacyOwner)
                    if (legacyLookup is ScanWorkInfoLookup.Unavailable) {
                        return@synchronized ScanReconciliationAction.RetryLegacyLookup
                    }
                    preloadedWorkInfo = legacyLookup
                    val activeId = (resumeStore.loadDetailed() as? ResumeReadState.Available)?.point?.requestId
                    if (activeId != null) {
                        val mig = lifecycleStore.migrateLegacyActiveOwner(
                            currentEncryptedRequestId = activeId,
                            generation = generationGenerator().toString(),
                            updatedAtEpochMillis = nowEpochMillis()
                        )
                        if (mig is ScanLifecycleWriteResult.Saved) {
                            (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                        } else null
                    } else null
                } else null
            }
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> {
                ScanSession.markBackgroundFailure(ScanLifecycleErrors.LIFECYCLE_STORAGE_FAILURE)
                return@synchronized ScanReconciliationAction.DoNotAdopt
            }
        }

        if (lifecycleRecord == null) {
            return@synchronized ScanReconciliationAction.DoNotAdopt
        }

        val workInfoLookup = preloadedWorkInfo ?: lookupWorkInfo(appContext, lifecycleRecord.ownerId)
        // An unavailable exact lookup proves neither presence nor absence. Do
        // not read/clean checkpoints or results after this point; retry with
        // the same durable lifecycle snapshot instead.
        if (workInfoLookup is ScanWorkInfoLookup.Unavailable) {
            return@synchronized ScanReconciliationAction.RetryReconciliation(lifecycleRecord)
        }

        val activeCheckpoint = resumeStore.loadRequestDetailed(lifecycleRecord.requestId)
        val checkpointAvailability = when (activeCheckpoint) {
            is ResumeReadState.Available -> ScanCheckpointAvailability.Available
            ResumeReadState.Missing -> {
                // EnqueuePending is the only phase allowed to recover a
                // prepared B record. The lifecycle publication intentionally
                // precedes pointer promotion, so a crash in that window leaves
                // loadRequestDetailed(B) missing while B remains encrypted and
                // explicitly marked prepared.
                if (lifecycleRecord.phase == ScanLifecyclePhase.EnqueuePending) {
                    when (resumeStore.loadPreparedRequestDetailed(lifecycleRecord.requestId)) {
                        is ResumeReadState.Available -> ScanCheckpointAvailability.Available
                        ResumeReadState.Missing,
                        ResumeReadState.Expired -> ScanCheckpointAvailability.Missing
                        is ResumeReadState.Invalid -> ScanCheckpointAvailability.Invalid
                        is ResumeReadState.StorageFailure -> ScanCheckpointAvailability.StorageFailure
                    }
                } else {
                    ScanCheckpointAvailability.Missing
                }
            }
            ResumeReadState.Expired -> ScanCheckpointAvailability.Missing
            is ResumeReadState.Invalid -> ScanCheckpointAvailability.Invalid
            is ResumeReadState.StorageFailure -> ScanCheckpointAvailability.StorageFailure
        }

        val resultWorkId = resultStore.load()?.workId

        return@synchronized ScanLifecycleReconciler.plan(
            lifecycle = lifecycleRecord,
            workInfo = workInfoLookup,
            checkpoint = checkpointAvailability,
            resultWorkId = resultWorkId
        )
    }

    fun executeReconciliation(context: Context, action: ScanReconciliationAction): ScanReconciliationAction = synchronized(LIFECYCLE_LOCK) {
        val appContext = context.applicationContext
        val lifecycleStore = lifecycleStoreProvider(appContext)
        val resumeStore = resumeStoreProvider(appContext)
        val resultStore = resultStoreProvider(appContext)
        val now = nowEpochMillis()

        when (action) {
            is ScanReconciliationAction.DoNotAdopt -> action
            is ScanReconciliationAction.RetryLegacyLookup -> action
            is ScanReconciliationAction.RetryReconciliation -> action
            is ScanReconciliationAction.KeepOrRecover -> action
            is ScanReconciliationAction.KeepPaused -> action
            is ScanReconciliationAction.PausedTerminal -> {
                if (isExactLifecycle(lifecycleStore, action.expected)) {
                    lifecycleStore.transition(
                        expected = action.expected,
                        transition = ScanLifecycleTransition.MarkPaused,
                        nowEpochMillis = now
                    )
                }
                action
            }
            is ScanReconciliationAction.RetryPause -> {
                val current = lifecycleStore.read()
                if (current !is ScanLifecycleReadResult.Available || current.record != action.expected) {
                    return@synchronized action
                }
                val ownerUuid = runCatching { UUID.fromString(action.expected.ownerId) }.getOrNull()
                if (ownerUuid != null) {
                    runCatching {
                        monitorPause(
                            operation = WorkManager.getInstance(appContext).cancelWorkById(ownerUuid),
                            context = appContext,
                            expected = action.expected
                        )
                    }
                }
                action
            }
            is ScanReconciliationAction.TruthfulFailurePreserve -> {
                // The result is intentionally retained for inspection. Keep a
                // safe failure visible to the session without attempting any
                // generation-blind cleanup or replacing the evidence.
                if (isExactLifecycle(lifecycleStore, action.expected)) {
                    ScanSession.markBackgroundFailure(action.errorCode)
                }
                action
            }
            is ScanReconciliationAction.CleanupTerminal -> {
                completeCleanupIfExact(
                    context = appContext,
                    expected = action.expected,
                    lifecycleStore = lifecycleStore,
                    resumeStore = resumeStore,
                    resultStore = resultStore
                )
                action
            }
            is ScanReconciliationAction.ReenqueueSameUuid -> {
                // Reconciliation actions are snapshots. Re-read the exact
                // record before enqueueing so a stale startup callback cannot
                // resurrect an older UUID after a replacement was published.
                val current = lifecycleStore.read()
                if (current !is ScanLifecycleReadResult.Available || current.record != action.expected) {
                    return@synchronized action
                }
                when (val checkpoint = resumeStore.loadRequestDetailed(action.expected.requestId)) {
                    is ResumeReadState.Available -> Unit
                    // Crash recovery for the lifecycle-before-promotion
                    // window: promote only the exact prepared generation B,
                    // never a timestamp-selected/orphaned record.
                    ResumeReadState.Missing -> {
                        val prepared = resumeStore.loadPreparedRequestDetailed(action.expected.requestId)
                        val promoted = if (prepared is ResumeReadState.Available) {
                            resumeStore.promotePreparedRequestDetailed(action.expected.requestId)
                        } else {
                            prepared
                        }
                        if (promoted !is ResumeReadState.Available) {
                            val errorCode = when (promoted) {
                                ResumeReadState.Missing,
                                ResumeReadState.Expired -> ScanLifecycleErrors.CHECKPOINT_MISSING
                                is ResumeReadState.Invalid -> ScanLifecycleErrors.CHECKPOINT_INVALID
                                is ResumeReadState.StorageFailure ->
                                    ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
                                is ResumeReadState.Available ->
                                    ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
                            }
                            lifecycleStore.transition(
                                expected = action.expected,
                                transition = ScanLifecycleTransition.MarkFailed(errorCode),
                                nowEpochMillis = now
                            )
                            return@synchronized action
                        }
                    }
                    ResumeReadState.Expired,
                    is ResumeReadState.Invalid,
                    is ResumeReadState.StorageFailure -> {
                        lifecycleStore.transition(
                            expected = action.expected,
                            transition = ScanLifecycleTransition.MarkFailed(
                                when (checkpoint) {
                                    ResumeReadState.Expired -> ScanLifecycleErrors.CHECKPOINT_MISSING
                                    is ResumeReadState.Invalid -> ScanLifecycleErrors.CHECKPOINT_INVALID
                                    is ResumeReadState.StorageFailure -> ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
                                    else -> ScanLifecycleErrors.CHECKPOINT_MISSING
                                }
                            ),
                            nowEpochMillis = now
                        )
                        return@synchronized action
                    }
                }
                val ownerUuid = runCatching { UUID.fromString(action.expected.ownerId) }.getOrNull()
                if (ownerUuid != null) {
                    val request = buildRequest(
                        requestId = action.expected.requestId,
                        generation = action.expected.generation,
                        ownerUuid = ownerUuid
                    )
                    try {
                        val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                            BackgroundScanWorker.UNIQUE_WORK_NAME,
                            ExistingWorkPolicy.REPLACE,
                            request
                        )
                        monitorEnqueue(operation, appContext, action.expected, priorOwnerId = null)
                    } catch (_: Exception) {
                        lifecycleStore.transition(
                            expected = action.expected,
                            transition = ScanLifecycleTransition.MarkFailed(BackgroundScanWorker.ERROR_ENQUEUE_FAILED),
                            nowEpochMillis = now
                        )
                    }
                }
                action
            }
            is ScanReconciliationAction.RetryCancellation -> {
                val current = lifecycleStore.read()
                if (current !is ScanLifecycleReadResult.Available || current.record != action.expected) {
                    return@synchronized action
                }
                val ownerUuid = runCatching { UUID.fromString(action.expected.ownerId) }.getOrNull()
                if (ownerUuid != null) {
                    try {
                        monitorCancellation(
                            operation = WorkManager.getInstance(appContext).cancelWorkById(ownerUuid),
                            context = appContext,
                            expected = action.expected
                        )
                    } catch (_: Exception) {
                        lifecycleStore.transition(
                            expected = action.expected,
                            transition = ScanLifecycleTransition.MarkCancelFailed(),
                            nowEpochMillis = now
                        )
                        ScanSession.markBackgroundFailure(ScanLifecycleErrors.CANCEL_REQUEST_FAILED)
                    }
                }
                action
            }
            is ScanReconciliationAction.RecoverSucceeded -> {
                if (!isExactLifecycle(lifecycleStore, action.expected)) return@synchronized action
                lifecycleStore.transition(
                    expected = action.expected,
                    transition = ScanLifecycleTransition.RecoverSucceeded,
                    nowEpochMillis = now
                )
                action
            }
            is ScanReconciliationAction.CompleteCleanup -> {
                completeCleanupIfExact(
                    context = appContext,
                    expected = action.expected,
                    lifecycleStore = lifecycleStore,
                    resumeStore = resumeStore,
                    resultStore = resultStore
                )
                action
            }
            is ScanReconciliationAction.FailedTerminal -> {
                if (!isExactLifecycle(lifecycleStore, action.expected)) return@synchronized action
                if (action.expected.phase != ScanLifecyclePhase.Failed) {
                    lifecycleStore.transition(
                        expected = action.expected,
                        transition = ScanLifecycleTransition.MarkFailed(action.errorCode),
                        nowEpochMillis = now
                    )
                }
                ScanSession.markBackgroundFailure(action.errorCode)
                action
            }
            is ScanReconciliationAction.CancelledTerminal -> {
                if (!isExactLifecycle(lifecycleStore, action.expected)) return@synchronized action
                if (action.expected.phase != ScanLifecyclePhase.Cancelled) {
                    lifecycleStore.transition(
                        expected = action.expected,
                        transition = ScanLifecycleTransition.MarkCancelled,
                        nowEpochMillis = now
                    )
                }
                ScanSession.markBackgroundCancelled()
                action
            }
            is ScanReconciliationAction.FailNoRetry -> {
                if (!isExactLifecycle(lifecycleStore, action.expected)) return@synchronized action
                lifecycleStore.transition(
                    expected = action.expected,
                    transition = ScanLifecycleTransition.MarkFailed(action.errorCode),
                    nowEpochMillis = now
                )
                ScanSession.markBackgroundFailure(action.errorCode)
                action
            }
        }
    }

    /** Exact CAS guard used by every mutating reconciliation action. */
    private fun isExactLifecycle(
        lifecycleStore: ScanLifecycleStore,
        expected: ScanLifecycleRecord
    ): Boolean = (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record == expected

    /**
     * Complete terminal cleanup only after the exact lifecycle snapshot has
     * entered CleanupPending.  The result is checked by owner UUID before the
     * global result-file unlink, and the lifecycle is cleared only after both
     * exact request cleanup and result cleanup succeed.
     */
    private fun completeCleanupIfExact(
        context: Context,
        expected: ScanLifecycleRecord,
        lifecycleStore: ScanLifecycleStore,
        resumeStore: ScanResumeStore,
        resultStore: BackgroundScanResultStore
    ): Boolean {
        val current = lifecycleStore.read()
        if (current !is ScanLifecycleReadResult.Available || current.record != expected) {
            return false
        }

        // Startup reconciliation is not a user purge. Preserve the encrypted
        // latest result so process-death restore and the Analysis screen can
        // still inspect it; only remove the exact checkpoint and lifecycle
        // row after verifying that matching result exists.
        if (expected.phase == ScanLifecyclePhase.Succeeded) {
            val snapshot = resultStore.load()
            if (snapshot == null) {
                ScanSession.markBackgroundFailure(ScanLifecycleErrors.RESULT_MISSING)
                return false
            }
            if (snapshot.workId != expected.ownerId) {
                ScanSession.markBackgroundFailure(ScanLifecycleErrors.RESULT_MISMATCH)
                return false
            }
            if (!clearProfileThenRequest(
                    context = context.applicationContext,
                    requestId = expected.requestId,
                    clearRequest = { resumeStore.clearRequest(expected.requestId) }
                )
            ) {
                ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
                return false
            }
            val after = resultStore.load()
            if (after == null || after.workId != expected.ownerId) {
                ScanSession.markBackgroundFailure(
                    if (after == null) ScanLifecycleErrors.RESULT_MISSING
                    else ScanLifecycleErrors.RESULT_MISMATCH
                )
                return false
            }
            if (!isExactLifecycle(lifecycleStore, expected)) return false
            return lifecycleStore.clear(expected) is ScanLifecycleWriteResult.Cleared
        }

        val cleanupRecord = if (expected.phase == ScanLifecyclePhase.CleanupPending) {
            expected
        } else {
            val begun = lifecycleStore.transition(
                expected = expected,
                transition = ScanLifecycleTransition.BeginCleanup,
                nowEpochMillis = nowEpochMillis()
            )
            if (begun !is ScanLifecycleWriteResult.Saved) return false
            (lifecycleStore.read() as? ScanLifecycleReadResult.Available)?.record
                ?.takeIf {
                    it.ownerId == expected.ownerId &&
                        it.requestId == expected.requestId &&
                        it.generation == expected.generation &&
                        it.phase == ScanLifecyclePhase.CleanupPending
                }
                ?: return false
        }

        // A succeeded generation must retain a matching durable result. For a
        // failed/cancelled generation no result is expected, but an orphan
        // result is still cleared only when it belongs to this owner.
        val snapshot = resultStore.load()
        if (snapshot != null && snapshot.workId != expected.ownerId) {
            ScanSession.markBackgroundFailure(
                ScanLifecycleErrors.RESULT_MISMATCH
            )
            return false
        }

        // CleanupPending is durable proof of an explicit purge. A missing
        // result can therefore mean an earlier unlink completed before a
        // crash; treat it as already cleared and continue the exact cleanup.
        val resultCleared = snapshot == null || resultStore.clear()
        val requestStateCleared = resultCleared && clearProfileThenRequest(
            context = context.applicationContext,
            requestId = expected.requestId,
            clearRequest = { resumeStore.clearRequest(expected.requestId) }
        )
        if (!requestStateCleared) {
            ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
            return false
        }

        // Re-check the full snapshot after external file operations so a
        // replacement callback cannot be erased by an old cleanup action.
        if (!isExactLifecycle(lifecycleStore, cleanupRecord)) return false
        return lifecycleStore.clear(cleanupRecord) is ScanLifecycleWriteResult.Cleared
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    internal fun setActiveOwner(context: Context, ownerId: String): Boolean {
        require(BackgroundScanWorker.isCanonicalUuid(ownerId)) { "Background owner must be a canonical UUID" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            prefs.edit().remove(KEY_ACTIVE_OWNER).commit()
        }
    }

    internal fun activeOwner(context: Context): String? {
        return when (val lifecycleRead = lifecycleStoreProvider(context.applicationContext).read()) {
            is ScanLifecycleReadResult.Available -> lifecycleRead.record.ownerId
            ScanLifecycleReadResult.Missing -> activeOwner(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> null
        }
    }

    private fun activeOwner(prefs: android.content.SharedPreferences): String? =
        runCatching { prefs.getString(KEY_ACTIVE_OWNER, null) }
            .getOrNull()
            ?.takeIf(BackgroundScanWorker::isCanonicalUuid)

    private const val PREFS = "dossier-background-work"
    private const val KEY_ACTIVE_OWNER = "active_owner"
    private val LIFECYCLE_LOCK = Any()

    private fun monitorEnqueue(
        operation: androidx.work.Operation,
        context: Context,
        pendingRecord: ScanLifecycleRecord,
        priorOwnerId: String?,
        priorRequestId: String? = null
    ) {
        operation.result.addListener(
            {
                try {
                    operation.result.get()
                    // The enqueue itself is now acknowledged. The replaced
                    // request can be tombstoned even if a newer generation
                    // superseded this callback before it ran; request IDs are
                    // immutable and never reused. This closes A -> B -> C
                    // callback chains that would otherwise strand A.
                    if (priorRequestId != null && priorRequestId != pendingRecord.requestId) {
                        runCatching {
                            profileCheckpointClearer(context.applicationContext, priorRequestId)
                        }
                    }
                    synchronized(LIFECYCLE_LOCK) {
                        val lifecycleStore = lifecycleStoreProvider(context)
                        val current = lifecycleStore.read()
                        if (current !is ScanLifecycleReadResult.Available ||
                            !isSameLifecycleGeneration(current.record, pendingRecord)
                        ) {
                            return@synchronized
                        }
                        if (current.record.phase == ScanLifecyclePhase.EnqueuePending) {
                            lifecycleStore.transition(
                                expected = current.record,
                                transition = ScanLifecycleTransition.MarkEnqueued,
                                nowEpochMillis = nowEpochMillis()
                            )
                        }
                        if (priorOwnerId != null) {
                            // Do not discard a completed result until the new
                            // WorkSpec enqueue has been acknowledged. The
                            // result itself remains owner-bound, so a stale
                            // callback cannot remove a replacement snapshot.
                            val resultStore = resultStoreProvider(context.applicationContext)
                            if (shouldClearPriorResult(
                                    current = current.record,
                                    pending = pendingRecord,
                                    priorOwnerId = priorOwnerId,
                                    resultOwnerId = resultStore.load()?.workId
                                )
                            ) {
                                resultStore.clear()
                            }
                        }
                    }
                } catch (_: Exception) {
                    synchronized(LIFECYCLE_LOCK) {
                        val lifecycleStore = lifecycleStoreProvider(context)
                        val current = lifecycleStore.read()
                        if (current is ScanLifecycleReadResult.Available &&
                            current.record.ownerId == pendingRecord.ownerId &&
                            current.record.requestId == pendingRecord.requestId &&
                            current.record.generation == pendingRecord.generation
                        ) {
                            FaceCorrelationSessionPolicy.useBasicMatching()
                            ScanSession.markBackgroundFailure(BackgroundScanWorker.ERROR_ENQUEUE_FAILED)
                            lifecycleStore.transition(
                                expected = current.record,
                                transition = ScanLifecycleTransition.MarkFailed(BackgroundScanWorker.ERROR_ENQUEUE_FAILED),
                                nowEpochMillis = nowEpochMillis()
                            )
                            val resumeStore = resumeStoreProvider(context)
                            if (!clearProfileThenRequest(
                                    context = context.applicationContext,
                                    requestId = pendingRecord.requestId,
                                    clearRequest = { resumeStore.clearRequest(pendingRecord.requestId) }
                                )
                            ) {
                                ScanSession.markBackgroundFailure(ScanLifecycleErrors.CLEANUP_FAILED)
                            }
                        }
                    }
                }
            },
            directExecutor
        )
    }

    /**
     * Profile observations must be removed before their encrypted scan request.
     * Short-circuiting preserves the request and lifecycle for a later exact
     * cleanup retry when profile deletion cannot be proven durable.
     */
    internal fun clearProfileThenRequest(
        context: Context,
        requestId: String,
        clearRequest: () -> Boolean
    ): Boolean {
        val profileCleared = runCatching {
            profileCheckpointClearer(context.applicationContext, requestId)
        }.getOrDefault(false)
        if (!profileCleared) return false
        return runCatching(clearRequest).getOrDefault(false)
    }

    internal fun isSameLifecycleGeneration(
        current: ScanLifecycleRecord,
        expected: ScanLifecycleRecord
    ): Boolean = current.ownerId == expected.ownerId &&
        current.requestId == expected.requestId &&
        current.generation == expected.generation

    internal fun shouldClearPriorResult(
        current: ScanLifecycleRecord,
        pending: ScanLifecycleRecord,
        priorOwnerId: String?,
        resultOwnerId: String?
    ): Boolean = priorOwnerId != null &&
        resultOwnerId == priorOwnerId &&
        isSameLifecycleGeneration(current, pending)

    /**
     * Pause-specific cancellation monitor. Operation success acknowledges the
     * request only; Paused is committed after the exact WorkManager row is
     * terminal. A published result marker is carried through MarkPaused and
     * recovered to Succeeded by reconciliation, never cleared here.
     */
    private fun monitorPause(
        operation: androidx.work.Operation,
        context: Context,
        expected: ScanLifecycleRecord
    ) {
        operation.result.addListener(
            {
                try {
                    operation.result.get()
                } catch (_: Exception) {
                    // Keep the durable Pausing intent. A later reconciliation
                    // retries the exact UUID instead of claiming it paused.
                    return@addListener
                }

                lifecycleScope.launch {
                    val observed = runCatching {
                        withTimeoutOrNull(CANCEL_OBSERVATION_TIMEOUT_MILLIS) {
                            true to WorkManager.getInstance(context.applicationContext)
                                .getWorkInfoByIdFlow(UUID.fromString(expected.ownerId))
                                .first { info -> info == null || info.state.isFinished }
                        }
                    }.getOrNull() ?: return@launch
                    val terminal = observed.second
                    if (terminal == null || terminal.state == WorkInfo.State.CANCELLED) {
                        synchronized(LIFECYCLE_LOCK) {
                            val lifecycleStore = lifecycleStoreProvider(context.applicationContext)
                            val current = lifecycleStore.read()
                            if (current is ScanLifecycleReadResult.Available && current.record == expected) {
                                lifecycleStore.transition(
                                    expected = expected,
                                    transition = ScanLifecycleTransition.MarkPaused,
                                    nowEpochMillis = nowEpochMillis()
                                )
                            }
                        }
                    } else {
                        // Completion/failure won the pause race. Feed the exact
                        // terminal row through the ordinary result-aware path.
                        val action = reconcile(context.applicationContext)
                        executeReconciliation(context.applicationContext, action)
                    }
                }
            },
            directExecutor
        )
    }

    /**
     * Cancellation is a two-step truth: a successful Operation acknowledges
     * the request, while WorkInfo.CANCELLED is the terminal execution state.
     * Keep CancelRequested durable until the exact WorkManager row confirms
     * cancellation; an Operation failure becomes the safe CancelFailed code.
     */
    private fun monitorCancellation(
        operation: androidx.work.Operation,
        context: Context,
        expected: ScanLifecycleRecord
    ) {
        operation.result.addListener(
            {
                try {
                    operation.result.get()
                } catch (_: Exception) {
                    synchronized(LIFECYCLE_LOCK) {
                        val lifecycleStore = lifecycleStoreProvider(context.applicationContext)
                        val current = lifecycleStore.read()
                        if (current is ScanLifecycleReadResult.Available && current.record == expected) {
                            lifecycleStore.transition(
                                expected = expected,
                                transition = ScanLifecycleTransition.MarkCancelFailed(),
                                nowEpochMillis = nowEpochMillis()
                            )
                            ScanSession.markBackgroundFailure(ScanLifecycleErrors.CANCEL_REQUEST_FAILED)
                        }
                    }
                    return@addListener
                }

                // Operation success acknowledges the request, but the exact
                // WorkInfo row is the terminal truth. Observe it off-main so
                // a worker that is still unwinding cannot strand the durable
                // lifecycle in CancelRequested.
                lifecycleScope.launch {
                    val ownerUuid = UUID.fromString(expected.ownerId)
                    val observed = runCatching {
                        withTimeoutOrNull(CANCEL_OBSERVATION_TIMEOUT_MILLIS) {
                            true to WorkManager.getInstance(context.applicationContext)
                                .getWorkInfoByIdFlow(ownerUuid)
                                .first { info -> info == null || info.state.isFinished }
                        }
                    }.getOrNull() ?: return@launch
                    val terminal = observed.second

                    if (terminal == null || terminal.state == WorkInfo.State.CANCELLED) {
                        synchronized(LIFECYCLE_LOCK) {
                            val lifecycleStore = lifecycleStoreProvider(context.applicationContext)
                            val current = lifecycleStore.read()
                            if (current is ScanLifecycleReadResult.Available && current.record == expected) {
                                lifecycleStore.transition(
                                    expected = expected,
                                    transition = ScanLifecycleTransition.MarkCancelled,
                                    nowEpochMillis = nowEpochMillis()
                                )
                                ScanSession.markBackgroundCancelled()
                            }
                        }
                    } else {
                        // Completion may win the race with cancellation. Feed
                        // the exact terminal row back through the ordinary
                        // result-aware reconciler rather than asserting that
                        // the work was cancelled.
                        val action = reconcile(context.applicationContext)
                        executeReconciliation(context.applicationContext, action)
                    }
                }
            },
            directExecutor
        )
    }

    private const val CANCEL_OBSERVATION_TIMEOUT_MILLIS = 30_000L
    private const val WORK_INFO_LOOKUP_TIMEOUT_SECONDS = 10L
}

internal class BackgroundScanSchedulingException(val code: String) : IllegalStateException(code)
