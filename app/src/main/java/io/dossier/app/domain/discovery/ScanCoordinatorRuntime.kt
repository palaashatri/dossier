package io.dossier.app.domain.discovery

import android.content.Context
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.BackgroundScanManager
import io.dossier.app.domain.scanner.BackgroundScanWorker
import io.dossier.app.domain.scanner.PivotFrontierConfig
import io.dossier.app.domain.scanner.ResumeCheckpointWriteState
import io.dossier.app.domain.scanner.ScanCheckpointStage
import io.dossier.app.domain.scanner.ScanLifecycleErrors
import io.dossier.app.domain.scanner.ScanResumeStore
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.domain.scanner.ScanPayloadSummary
import io.dossier.app.domain.scanner.ScanStageOutput
import io.dossier.app.domain.scanner.BreachStageCheckpoint
import io.dossier.app.domain.scanner.PostProcessingStageCheckpoint
import io.dossier.app.domain.scanner.EntityGraphStageCheckpoint
import io.dossier.app.domain.scanner.RelationshipConfidenceStageCheckpoint
import io.dossier.app.domain.scanner.AttackPathsStageCheckpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@JvmInline
value class ScanId(val value: String)

data class ScanRequest(
    val input: IdentityInput,
    val mode: ScanMode,
    val deepResearch: Boolean = mode.includeExtendedDiscovery
)

enum class ScanRunState {
    Idle,
    Running,
    Paused,
    Completed,
    Cancelled,
    Failed
}

/**
 * Safe, evidence-free metadata about the most recent bounded pivot decision.
 *
 * The summary deliberately omits URLs, usernames, and source text.  Pivot
 * reasons are policy explanations (for example, a depth or budget rejection),
 * not identity evidence, and are bounded/sanitized at the coordinator edge.
 */
data class PivotDecisionSummary(
    val admitted: Boolean,
    val signalType: String,
    val depth: Int,
    val reason: String
)

sealed interface ScanEvent {
    val scanId: ScanId
    val occurredAt: Instant

    data class ScanStarted(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val mode: ScanMode,
        val directProfileProviders: Int,
        val extendedDiscovery: Boolean
    ) : ScanEvent

    data class StageChanged(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val stage: String
    ) : ScanEvent

    data class ProviderQueued(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val providerId: String
    ) : ScanEvent

    data class ProviderStarted(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val providerId: String,
        val attempt: Int = 1
    ) : ScanEvent

    data class ProviderCompleted(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val providerId: String,
        val state: ProviderVerificationState,
        val latencyMs: Long
    ) : ScanEvent

    data class ProviderUnavailable(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val providerId: String,
        val state: ProviderVerificationState,
        val latencyMs: Long
    ) : ScanEvent

    data class ProfileBatchUpdated(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val observedProfiles: Int,
        val verifiedProfiles: Int,
        val unavailableProfiles: Int
    ) : ScanEvent

    data class FaceCorrelationUpdated(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val comparisonCount: Int
    ) : ScanEvent

    data class BreachCoverageUpdated(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val identifierCount: Int,
        val breachRecordCount: Int
    ) : ScanEvent

    data class GraphUpdated(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val entityCount: Int,
        val relationshipCount: Int
    ) : ScanEvent

    data class AnalysisUpdated(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val findingCount: Int,
        val hasAiSummary: Boolean
    ) : ScanEvent

    /**
     * A real bounded-frontier observation emitted by ProfileScanner after a
     * pivot admission/rejection or queue mutation.  Counts and pending depths
     * are supplied by the request-scoped frontier; no UI counter is inferred.
     */
    data class PivotDiagnosticsUpdated(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val decision: PivotDecisionSummary?,
        val pendingCount: Int,
        val pendingByDepth: List<Int>,
        val admittedCount: Int,
        val rejectedCount: Int,
        val visitedCount: Int,
        val maxDepth: Int,
        val maxTotalPivots: Int
    ) : ScanEvent

    data class ScanCompleted(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val profileCount: Int,
        val findingCount: Int
    ) : ScanEvent

    data class ScanCancelled(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val profileCount: Int,
        val findingCount: Int
    ) : ScanEvent

    /** Durable request-scoped stage boundary written by the exact worker owner. */
    data class CheckpointUpdated(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val stage: String,
        val completedStages: List<String>,
        val plan: ScanPlanSummary? = null,
        val payloadSummaries: List<ScanPayloadSummary> = emptyList()
    ) : ScanEvent

    data class ScanPaused(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val profileCount: Int,
        val findingCount: Int
    ) : ScanEvent

    data class ScanFailed(
        override val scanId: ScanId,
        override val occurredAt: Instant,
        val profileCount: Int,
        val findingCount: Int,
        val errorCode: String
    ) : ScanEvent
}

internal data class TerminalScanClassification(
    val state: ScanRunState,
    val failureCode: String? = null
)

private const val STAGE_CANCELLED = "SCAN_CANCELLED"
private const val STAGE_PAUSED = "SCAN_PAUSED"
private const val GENERIC_SCAN_FAILURE = "SCAN_FAILED"
private const val MAX_SAFE_PIVOT_DECISIONS = 4_096
private const val MAX_SAFE_PIVOT_VISITED = 4_096
private const val MAX_SAFE_PIVOT_SIGNAL_LENGTH = 64
private const val MAX_SAFE_PIVOT_REASON_LENGTH = 256
private val SAFE_PIVOT_SIGNAL_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
private val SAFE_TERMINAL_FAILURE_CODES =
    BackgroundScanWorker.SAFE_ERROR_CODES +
        ScanLifecycleErrors.SAFE_ERROR_CODES +
        setOf(
            GENERIC_SCAN_FAILURE,
            "WORK_SCHEDULING_FAILED",
            "SECURE_REQUEST_PERSISTENCE_FAILED",
            "ACTIVE_MARKER_PERSISTENCE_FAILED",
            "WORK_ENQUEUE_FAILED"
        )

internal fun sanitizeTerminalFailureCode(code: String?): String? =
    code?.takeIf { it in SAFE_TERMINAL_FAILURE_CODES }

internal fun safeCoordinatorStage(stage: String): String =
    BackgroundScanWorker.safeProgressData(stage)
        .getString(BackgroundScanWorker.KEY_STAGE)
        ?: BackgroundScanWorker.STAGE_RUNNING

internal fun classifyTerminalStage(stage: String): TerminalScanClassification {
    if (stage == STAGE_CANCELLED || stage == BackgroundScanWorker.STAGE_CANCELLED) {
        return TerminalScanClassification(ScanRunState.Cancelled)
    }
    if (stage == STAGE_PAUSED) {
        return TerminalScanClassification(ScanRunState.Paused)
    }
    if (stage == BackgroundScanWorker.STAGE_FAILED) {
        return TerminalScanClassification(ScanRunState.Failed, GENERIC_SCAN_FAILURE)
    }
    if (stage.startsWith("${BackgroundScanWorker.STAGE_FAILED}:")) {
        val code = stage.substringAfter(':').trim()
            .let(::sanitizeTerminalFailureCode)
            ?: GENERIC_SCAN_FAILURE
        return TerminalScanClassification(ScanRunState.Failed, code)
    }
    if (stage == BackgroundScanWorker.STAGE_COMPLETE) {
        return TerminalScanClassification(ScanRunState.Completed)
    }
    return TerminalScanClassification(ScanRunState.Failed, GENERIC_SCAN_FAILURE)
}

data class LiveScanSnapshot(
    val scanId: ScanId? = null,
    val state: ScanRunState = ScanRunState.Idle,
    val mode: ScanMode = ScanMode.Standard,
    val directProfileProviders: Int = 0,
    val stage: String = "",
    val scheduledProviderCount: Int = 0,
    val startedProviderCount: Int = 0,
    val completedProviderCount: Int = 0,
    val unavailableProviderCount: Int = 0,
    val profileCount: Int = 0,
    val verifiedProfileCount: Int = 0,
    val faceComparisonCount: Int = 0,
    val breachRecordCount: Int = 0,
    val entityCount: Int = 0,
    val relationshipCount: Int = 0,
    val findingCount: Int = 0,
    val pivotPendingCount: Int = 0,
    val pivotPendingByDepth: List<Int> = emptyList(),
    val pivotAdmittedCount: Int = 0,
    val pivotRejectedCount: Int = 0,
    val pivotVisitedCount: Int = 0,
    val pivotMaxDepth: Int = 0,
    val pivotMaxTotalPivots: Int = 0,
    val pivotLastDecision: PivotDecisionSummary? = null,
    val checkpointStage: String = ScanCheckpointStage.Queued.wireName,
    val completedCheckpointStages: List<String> = emptyList(),
    val plan: ScanPlanSummary? = null,
    val payloadSummaries: List<ScanPayloadSummary> = emptyList()
)

/**
 * Compatibility coordinator/event bridge for the existing mature ScanSession.
 *
 * M2 intentionally wraps, rather than rewrites, the current vertical pipeline.
 * All emitted values are observations of real ScanSession and provider lifecycle
 * events; no fake completion is invented for unexecuted operations.
 */
object ScanCoordinatorRuntime {
    private val safeProviderIdPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val monitoringStarted = AtomicBoolean(false)
    private val lock = Any()

    fun activeScanId(): ScanId? = activeScanId

    /**
     * Claims one stable coordinator ID at the start of provider execution.
     * Passive lifecycle callbacks never create or adopt scans themselves.
     */
    fun claimProviderScanId(): ScanId {
        ensureMonitoring()
        return synchronized(lock) {
            val id = activeScanId ?: requestedScanId ?: newScanId()
            activeScanId = id
            if (_snapshot.value.scanId != id) {
                _snapshot.value = _snapshot.value.copy(
                    scanId = id,
                    scheduledProviderCount = 0,
                    startedProviderCount = 0,
                    completedProviderCount = 0,
                    unavailableProviderCount = 0,
                    pivotPendingCount = 0,
                    pivotPendingByDepth = emptyList(),
                    pivotAdmittedCount = 0,
                    pivotRejectedCount = 0,
                    pivotVisitedCount = 0,
                    pivotMaxDepth = 0,
                    pivotMaxTotalPivots = 0,
                    pivotLastDecision = null
                )
            }
            id
        }
    }

    fun resetCounts(scanId: ScanId = newScanId()): ScanId {
        synchronized(lock) {
            activeScanId = scanId
            val mode = DiscoveryScanPreferences.selectedMode.value
            val plan = ProviderCatalogV2.plan(mode)
            _snapshot.value = LiveScanSnapshot(
                scanId = scanId,
                state = ScanRunState.Running,
                mode = mode,
                directProfileProviders = ProviderCatalogV2.legacyProfileDefinitions(mode).size,
                scheduledProviderCount = 0,
                startedProviderCount = 0,
                completedProviderCount = 0,
                unavailableProviderCount = 0,
                plan = ScanPlanSummary.from(plan)
            )
        }
        return scanId
    }

    /** Binds an exact WorkManager owner before stage writes begin. */
    internal fun bindCheckpointOwner(
        context: Context,
        requestId: String,
        ownerId: String,
        generation: String
    ): ResumeCheckpointWriteState =
        BackgroundScanManager.bindCheckpointOwner(
            context = context,
            workerId = ownerId,
            generation = generation,
            requestId = requestId
        ).also(::publishCheckpointState)

    /** Writes a semantic stage boundary through the lifecycle-owned store. */
    internal fun recordCheckpoint(
        context: Context,
        requestId: String,
        ownerId: String,
        generation: String,
        stage: ScanCheckpointStage,
        completed: Boolean,
        output: ScanStageOutput? = null,
        payloads: List<ScanPayloadSummary> = emptyList(),
        breachCheckpoint: BreachStageCheckpoint? = null,
        postProcessingCheckpoint: PostProcessingStageCheckpoint? = null,
        entityGraphCheckpoint: EntityGraphStageCheckpoint? = null,
        relationshipConfidenceCheckpoint: RelationshipConfidenceStageCheckpoint? = null,
        attackPathsCheckpoint: AttackPathsStageCheckpoint? = null
    ): ResumeCheckpointWriteState =
        BackgroundScanManager.advanceCheckpointIfOwner(
            context = context,
            workerId = ownerId,
            generation = generation,
            requestId = requestId,
            stage = stage,
            completed = completed,
            output = output,
            payloads = payloads,
            breachCheckpoint = breachCheckpoint,
            postProcessingCheckpoint = postProcessingCheckpoint,
            entityGraphCheckpoint = entityGraphCheckpoint,
            relationshipConfidenceCheckpoint = relationshipConfidenceCheckpoint,
            attackPathsCheckpoint = attackPathsCheckpoint
        ).also(::publishCheckpointState)

    private fun publishCheckpointState(state: ResumeCheckpointWriteState) {
        val point = (state as? ResumeCheckpointWriteState.Saved)?.point ?: return
        val id = ScanId(point.requestId)
        synchronized(lock) {
            if (activeScanId != id || _snapshot.value.scanId != id) return
            val current = _snapshot.value.copy(
                checkpointStage = point.checkpointStage.wireName,
                completedCheckpointStages = point.completedCheckpointStages
                    .map(ScanCheckpointStage::wireName),
                plan = point.scanPlan,
                payloadSummaries = point.payloadSummaries
            )
            _snapshot.value = current
            emit(
                ScanEvent.CheckpointUpdated(
                    scanId = id,
                    occurredAt = Instant.now(),
                    stage = current.checkpointStage,
                    completedStages = current.completedCheckpointStages,
                    plan = current.plan,
                    payloadSummaries = current.payloadSummaries
                )
            )
        }
    }

    fun onProviderQueued(providerId: String, scanId: ScanId = requireActiveScanId()) {
        val event = ScanEvent.ProviderQueued(scanId, Instant.now(), safeProviderId(providerId))
        reduceProviderEvent(event)
        emit(event)
    }

    fun onProviderStarted(
        providerId: String,
        attempt: Int = 1,
        scanId: ScanId = requireActiveScanId()
    ) {
        val event = ScanEvent.ProviderStarted(
            scanId,
            Instant.now(),
            safeProviderId(providerId),
            attempt.coerceAtLeast(1)
        )
        reduceProviderEvent(event)
        emit(event)
    }

    fun onProviderCompleted(
        providerId: String,
        state: ProviderVerificationState,
        latencyMs: Long,
        scanId: ScanId = requireActiveScanId()
    ) {
        val event = ScanEvent.ProviderCompleted(
            scanId,
            Instant.now(),
            safeProviderId(providerId),
            state,
            latencyMs.coerceAtLeast(0L)
        )
        reduceProviderEvent(event)
        emit(event)
    }

    fun onProviderUnavailable(
        providerId: String,
        state: ProviderVerificationState,
        latencyMs: Long,
        scanId: ScanId = requireActiveScanId()
    ) {
        val event = ScanEvent.ProviderUnavailable(
            scanId,
            Instant.now(),
            safeProviderId(providerId),
            state,
            latencyMs.coerceAtLeast(0L)
        )
        reduceProviderEvent(event)
        emit(event)
    }

    /**
     * Publishes bounded pivot state directly from [ProfileScanner]'s frontier.
     * The optional decision is policy metadata only; identity values and URLs
     * are intentionally not part of the coordinator/UI boundary.
     */
    fun onPivotDiagnostics(
        scanId: ScanId,
        decision: PivotDecisionSummary?,
        pendingCount: Int,
        pendingByDepth: List<Int>,
        admittedCount: Int,
        rejectedCount: Int,
        visitedCount: Int,
        maxDepth: Int,
        maxTotalPivots: Int
    ) {
        val event = ScanEvent.PivotDiagnosticsUpdated(
            scanId = scanId,
            occurredAt = Instant.now(),
            decision = decision,
            pendingCount = pendingCount,
            pendingByDepth = pendingByDepth,
            admittedCount = admittedCount,
            rejectedCount = rejectedCount,
            visitedCount = visitedCount,
            maxDepth = maxDepth,
            maxTotalPivots = maxTotalPivots
        )
        val safeEvent = sanitizeEvent(event)
        reduceProviderEvent(safeEvent)
        emit(safeEvent)
    }

    fun dispatch(event: ScanEvent) {
        val safeEvent = sanitizeEvent(event)
        reduceProviderEvent(safeEvent)
        emit(safeEvent)
    }

    private fun reduceProviderEvent(event: ScanEvent) {
        synchronized(lock) {
            val current = _snapshot.value
            if (activeScanId != event.scanId || current.scanId != event.scanId) return
            _snapshot.value = when (event) {
                is ScanEvent.ProviderQueued -> current.copy(
                    scheduledProviderCount = current.scheduledProviderCount + 1
                )
                is ScanEvent.ProviderStarted -> if (
                    event.attempt == 1 && current.startedProviderCount < current.scheduledProviderCount
                ) {
                    current.copy(startedProviderCount = current.startedProviderCount + 1)
                } else {
                    current
                }
                is ScanEvent.ProviderCompleted -> if (
                    current.completedProviderCount + current.unavailableProviderCount <
                    current.scheduledProviderCount
                ) {
                    current.copy(completedProviderCount = current.completedProviderCount + 1)
                } else {
                    current
                }
                is ScanEvent.ProviderUnavailable -> if (
                    current.completedProviderCount + current.unavailableProviderCount <
                    current.scheduledProviderCount
                ) {
                    current.copy(unavailableProviderCount = current.unavailableProviderCount + 1)
                } else {
                    current
                }
                is ScanEvent.PivotDiagnosticsUpdated -> current.copy(
                    pivotPendingCount = event.pendingCount,
                    pivotPendingByDepth = event.pendingByDepth,
                    pivotAdmittedCount = event.admittedCount,
                    pivotRejectedCount = event.rejectedCount,
                    pivotVisitedCount = event.visitedCount,
                    pivotMaxDepth = event.maxDepth,
                    pivotMaxTotalPivots = event.maxTotalPivots,
                    pivotLastDecision = event.decision ?: current.pivotLastDecision
                )
                is ScanEvent.CheckpointUpdated -> current.copy(
                    checkpointStage = event.stage,
                    completedCheckpointStages = event.completedStages,
                    plan = event.plan ?: current.plan,
                    payloadSummaries = event.payloadSummaries
                        .takeIf { it.isNotEmpty() }
                        ?: current.payloadSummaries
                )
                else -> current
            }
        }
    }

    private fun sanitizeEvent(event: ScanEvent): ScanEvent = when (event) {
        is ScanEvent.ProviderQueued -> event.copy(providerId = safeProviderId(event.providerId))
        is ScanEvent.ProviderStarted -> event.copy(
            providerId = safeProviderId(event.providerId),
            attempt = event.attempt.coerceAtLeast(1)
        )
        is ScanEvent.ProviderCompleted -> event.copy(
            providerId = safeProviderId(event.providerId),
            latencyMs = event.latencyMs.coerceAtLeast(0L)
        )
        is ScanEvent.ProviderUnavailable -> event.copy(
            providerId = safeProviderId(event.providerId),
            latencyMs = event.latencyMs.coerceAtLeast(0L)
        )
        is ScanEvent.PivotDiagnosticsUpdated -> event.copy(
            decision = event.decision?.let(::sanitizePivotDecision),
            pendingCount = event.pendingCount.coerceIn(0, PivotFrontierConfig.MAX_ALLOWED_TOTAL_PIVOTS),
            pendingByDepth = event.pendingByDepth
                .take(PivotAdmissionPolicy.MAX_ALLOWED_DEPTH)
                .map { it.coerceIn(0, PivotFrontierConfig.MAX_ALLOWED_TOTAL_PIVOTS) },
            admittedCount = event.admittedCount.coerceIn(0, PivotFrontierConfig.MAX_ALLOWED_TOTAL_PIVOTS),
            // Rejections are intentionally not limited to the admission budget;
            // a noisy page may yield many policy decisions. Keep the state
            // bounded while preserving all normal frontier observations.
            rejectedCount = event.rejectedCount.coerceIn(0, MAX_SAFE_PIVOT_DECISIONS),
            visitedCount = event.visitedCount.coerceIn(0, MAX_SAFE_PIVOT_VISITED),
            maxDepth = event.maxDepth.coerceIn(0, PivotAdmissionPolicy.MAX_ALLOWED_DEPTH),
            maxTotalPivots = event.maxTotalPivots.coerceIn(0, PivotFrontierConfig.MAX_ALLOWED_TOTAL_PIVOTS)
        )
        is ScanEvent.CheckpointUpdated -> {
            val safeStage = ScanCheckpointStage.fromWire(event.stage)
            event.copy(
                stage = safeStage?.wireName ?: ScanCheckpointStage.Queued.wireName,
                completedStages = event.completedStages
                    .mapNotNull { ScanCheckpointStage.fromWire(it)?.wireName }
                    .distinct()
                    .take(ScanResumeStore.MAX_CHECKPOINT_STAGES),
                plan = event.plan?.takeIf(ScanPlanSummary::isWellFormed),
                // Payload metadata is meaningful only on the discovery
                // boundary that owns the public search/image envelopes.
                payloadSummaries = if (safeStage == ScanCheckpointStage.DiscoveringUsernames) {
                    event.payloadSummaries
                        .filter(ScanPayloadSummary::isWellFormed)
                        .distinctBy(ScanPayloadSummary::stage)
                        .take(ScanResumeStore.MAX_PAYLOAD_SUMMARIES)
                } else {
                    emptyList()
                }
            )
        }
        else -> event
    }

    private fun sanitizePivotDecision(decision: PivotDecisionSummary): PivotDecisionSummary =
        decision.copy(
            signalType = decision.signalType
                .trim()
                .take(MAX_SAFE_PIVOT_SIGNAL_LENGTH)
                .takeIf { SAFE_PIVOT_SIGNAL_PATTERN.matches(it) }
                ?: "Unknown",
            depth = decision.depth.coerceIn(0, PivotAdmissionPolicy.MAX_ALLOWED_DEPTH),
            reason = decision.reason
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .trim()
                .take(MAX_SAFE_PIVOT_REASON_LENGTH)
                .ifBlank { "No policy explanation supplied" }
        )

    private fun safeProviderId(value: String): String {
        val normalized = value.trim().lowercase()
        return normalized.takeIf {
            it.length in 1..160 && safeProviderIdPattern.matches(it)
        } ?: "unknown"
    }

    private fun requireActiveScanId(): ScanId = checkNotNull(activeScanId) {
        "Provider lifecycle event requires an explicitly claimed active scan"
    }

    private val _events = MutableSharedFlow<ScanEvent>(
        replay = 0,
        extraBufferCapacity = 128
    )
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    private val _snapshot = MutableStateFlow(LiveScanSnapshot())
    val snapshot: StateFlow<LiveScanSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var activeScanId: ScanId? = null

    @Volatile
    private var requestedScanId: ScanId? = null

    /**
     * Sole coordinator launch seam.  Production always delegates to the
     * durable ScanSession/WorkManager path; tests may replace this narrow
     * seam to assert that callers enter through the coordinator without
     * starting real work.
     */
    internal var scanStarter: suspend (Context, ScanRequest) -> Unit = { context, request ->
        ScanSession.startScan(context, request.input, request.deepResearch)
    }

    fun ensureMonitoring() {
        if (!monitoringStarted.compareAndSet(false, true)) return

        scope.launch {
            ScanSession.isScanning.collect { scanning ->
                if (scanning) {
                    val id = synchronized(lock) {
                        activeScanId ?: requestedScanId ?: newScanId().also {
                            activeScanId = it
                            requestedScanId = null
                        }
                    }
                    val mode = DiscoveryScanPreferences.selectedMode.value
                    val directProviders = ProviderCatalogV2.legacyProfileDefinitions(mode).size
                    val previous = _snapshot.value
                    if (previous.state != ScanRunState.Running || previous.scanId != id) {
                        val startedAt = Instant.now()
                        val existingProviderCounts = previous.takeIf { it.scanId == id }
                        _snapshot.value = LiveScanSnapshot(
                            scanId = id,
                            state = ScanRunState.Running,
                            mode = mode,
                            directProfileProviders = directProviders,
                            stage = safeCoordinatorStage(ScanSession.progressText.value),
                            scheduledProviderCount = existingProviderCounts?.scheduledProviderCount ?: 0,
                            startedProviderCount = existingProviderCounts?.startedProviderCount ?: 0,
                            completedProviderCount = existingProviderCounts?.completedProviderCount ?: 0,
                            unavailableProviderCount = existingProviderCounts?.unavailableProviderCount ?: 0,
                            pivotPendingCount = existingProviderCounts?.pivotPendingCount ?: 0,
                            pivotPendingByDepth = existingProviderCounts?.pivotPendingByDepth ?: emptyList(),
                            pivotAdmittedCount = existingProviderCounts?.pivotAdmittedCount ?: 0,
                            pivotRejectedCount = existingProviderCounts?.pivotRejectedCount ?: 0,
                            pivotVisitedCount = existingProviderCounts?.pivotVisitedCount ?: 0,
                            pivotMaxDepth = existingProviderCounts?.pivotMaxDepth ?: 0,
                            pivotMaxTotalPivots = existingProviderCounts?.pivotMaxTotalPivots ?: 0,
                            pivotLastDecision = existingProviderCounts?.pivotLastDecision
                        )
                        ScanHistoryRuntime.scanStarted(
                            scanId = id,
                            input = ScanSession.currentInput.value,
                            mode = mode,
                            directProfileProviderCount = directProviders,
                            occurredAt = startedAt
                        )
                        emit(
                            ScanEvent.ScanStarted(
                                scanId = id,
                                occurredAt = startedAt,
                                mode = mode,
                                directProfileProviders = directProviders,
                                extendedDiscovery = ScanSession.deepResearchEnabled.value
                            )
                        )
                    }
                } else {
                    val id = activeScanId ?: return@collect
                    val classification = classifyTerminalStage(ScanSession.progressText.value)
                    val terminal = classification.state
                    val cancelled = terminal == ScanRunState.Cancelled
                    val failed = terminal == ScanRunState.Failed
                    val finishedAt = Instant.now()
                    val profileCount = ScanSession.profileScanResults.value.size
                    val findingCount = ScanSession.findings.value.size
                    val breachRecordCount = ScanSession.breachDigests.value.sumOf { it.breachCount }
                    val graph = ScanSession.entityGraph.value
                    if (terminal == ScanRunState.Paused) {
                        _snapshot.value = _snapshot.value.copy(
                            state = ScanRunState.Paused,
                            profileCount = profileCount,
                            findingCount = findingCount,
                            breachRecordCount = breachRecordCount,
                            entityCount = graph.entities.size,
                            relationshipCount = graph.edges.size
                        )
                        emit(
                            ScanEvent.ScanPaused(
                                scanId = id,
                                occurredAt = finishedAt,
                                profileCount = profileCount,
                                findingCount = findingCount
                            )
                        )
                        // Paused is a resumable checkpoint, not a terminal
                        // history row. Keep the active ID for a later resume.
                        return@collect
                    }
                    _snapshot.value = _snapshot.value.copy(
                        state = terminal,
                        profileCount = profileCount,
                        findingCount = findingCount,
                        breachRecordCount = breachRecordCount,
                        entityCount = graph.entities.size,
                        relationshipCount = graph.edges.size
                    )
                    ScanHistoryRuntime.scanFinished(
                        scanId = id,
                        occurredAt = finishedAt,
                        cancelled = cancelled,
                        failed = failed,
                        failureCode = classification.failureCode,
                        profileResultCount = profileCount,
                        findingCount = findingCount,
                        breachRecordCount = breachRecordCount,
                        graphEntityCount = graph.entities.size,
                        graphRelationshipCount = graph.edges.size
                    )
                    when (terminal) {
                        ScanRunState.Cancelled -> emit(
                            ScanEvent.ScanCancelled(
                                scanId = id,
                                occurredAt = finishedAt,
                                profileCount = profileCount,
                                findingCount = findingCount
                            )
                        )
                        ScanRunState.Failed -> emit(
                            ScanEvent.ScanFailed(
                                scanId = id,
                                occurredAt = finishedAt,
                                profileCount = profileCount,
                                findingCount = findingCount,
                                errorCode = classification.failureCode ?: GENERIC_SCAN_FAILURE
                            )
                        )
                        else -> emit(
                            ScanEvent.ScanCompleted(
                                scanId = id,
                                occurredAt = finishedAt,
                                profileCount = profileCount,
                                findingCount = findingCount
                            )
                        )
                    }
                    synchronized(lock) {
                        activeScanId = null
                        requestedScanId = null
                    }
                }
            }
        }

        scope.launch {
            var previous = ""
            ScanSession.progressText.collect { stage ->
                val id = activeScanId ?: return@collect
                val safeStage = safeCoordinatorStage(stage)
                if (safeStage == previous) return@collect
                previous = safeStage
                _snapshot.value = _snapshot.value.copy(stage = safeStage)
                emit(ScanEvent.StageChanged(id, Instant.now(), safeStage))
            }
        }

        scope.launch {
            var previousSize = -1
            ScanSession.profileScanResults.collect { results ->
                val id = activeScanId ?: return@collect
                if (results.size == previousSize) return@collect
                previousSize = results.size
                val verified = results.count { it.exists && it.verified }
                val unavailable = results.count {
                    !it.exists && it.verificationStatus?.contains("unverifiable", true) == true
                }
                _snapshot.value = _snapshot.value.copy(
                    profileCount = results.size,
                    verifiedProfileCount = verified
                )
                emit(
                    ScanEvent.ProfileBatchUpdated(
                        id,
                        Instant.now(),
                        observedProfiles = results.size,
                        verifiedProfiles = verified,
                        unavailableProfiles = unavailable
                    )
                )
            }
        }

        scope.launch {
            var previousSize = -1
            ScanSession.faceConsistencyMatches.collect { matches ->
                val id = activeScanId ?: return@collect
                if (matches.size == previousSize) return@collect
                previousSize = matches.size
                _snapshot.value = _snapshot.value.copy(faceComparisonCount = matches.size)
                emit(ScanEvent.FaceCorrelationUpdated(id, Instant.now(), matches.size))
            }
        }

        scope.launch {
            var previousSignature = ""
            ScanSession.breachDigests.collect { digests ->
                val id = activeScanId ?: return@collect
                val recordCount = digests.sumOf { it.breachCount }
                val signature = "${digests.size}:$recordCount"
                if (signature == previousSignature) return@collect
                previousSignature = signature
                _snapshot.value = _snapshot.value.copy(breachRecordCount = recordCount)
                emit(
                    ScanEvent.BreachCoverageUpdated(
                        id,
                        Instant.now(),
                        identifierCount = digests.size,
                        breachRecordCount = recordCount
                    )
                )
            }
        }

        scope.launch {
            var previousSignature = ""
            ScanSession.entityGraph.collect { graph ->
                val id = activeScanId ?: return@collect
                val signature = "${graph.entities.size}:${graph.edges.size}"
                if (signature == previousSignature) return@collect
                previousSignature = signature
                _snapshot.value = _snapshot.value.copy(
                    entityCount = graph.entities.size,
                    relationshipCount = graph.edges.size
                )
                emit(
                    ScanEvent.GraphUpdated(
                        id,
                        Instant.now(),
                        entityCount = graph.entities.size,
                        relationshipCount = graph.edges.size
                    )
                )
            }
        }

        scope.launch {
            var previousSignature = ""
            kotlinx.coroutines.flow.combine(
                ScanSession.findings,
                ScanSession.aiSummary
            ) { findings, summary -> findings.size to !summary.isNullOrBlank() }
                .collect { (findingCount, hasAi) ->
                    val id = activeScanId ?: return@collect
                    val signature = "$findingCount:$hasAi"
                    if (signature == previousSignature) return@collect
                    previousSignature = signature
                    _snapshot.value = _snapshot.value.copy(findingCount = findingCount)
                    emit(ScanEvent.AnalysisUpdated(id, Instant.now(), findingCount, hasAi))
                }
        }
    }

    /**
     * New callers can start through the coordinator. Existing ScanScreen startup
     * is still observed truthfully by [ensureMonitoring] until face-consent flow
     * is migrated in a later M2 tranche.
     */
    suspend fun start(context: Context, request: ScanRequest): ScanId {
        ensureMonitoring()
        val id = newScanId()
        synchronized(lock) {
            requestedScanId = id
            activeScanId = id
            _snapshot.value = _snapshot.value.copy(
                scanId = id,
                mode = request.mode,
                plan = ScanPlanSummary.from(ProviderCatalogV2.plan(request.mode)),
                payloadSummaries = emptyList()
            )
        }
        DiscoveryScanPreferences.setMode(request.mode)
        ScanSession.setDeepResearch(request.deepResearch)
        scanStarter(context, request)
        return id
    }

    fun cancel(scanId: ScanId? = activeScanId) {
        val active = activeScanId
        if (scanId != null && active != null && scanId != active) return
        ScanSession.cancelScan()
    }

    internal fun resetForTests() {
        synchronized(lock) {
            activeScanId = null
            requestedScanId = null
        }
        scanStarter = { context, request ->
            ScanSession.startScan(context, request.input, request.deepResearch)
        }
        _snapshot.value = LiveScanSnapshot()
        ScanHistoryRuntime.resetForTests()
    }

    private fun emit(event: ScanEvent) {
        _events.tryEmit(event)
    }

    private fun newScanId(): ScanId = ScanId(UUID.randomUUID().toString())

}
