package io.dossier.app.domain.discovery

import android.content.Context
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.BackgroundScanWorker
import io.dossier.app.domain.scanner.ScanLifecycleErrors
import io.dossier.app.domain.scanner.ScanSession
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
    Completed,
    Cancelled,
    Failed
}

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
private const val GENERIC_SCAN_FAILURE = "SCAN_FAILED"
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
    val profileCount: Int = 0,
    val verifiedProfileCount: Int = 0,
    val faceComparisonCount: Int = 0,
    val breachRecordCount: Int = 0,
    val entityCount: Int = 0,
    val relationshipCount: Int = 0,
    val findingCount: Int = 0
)

/**
 * Compatibility coordinator/event bridge for the existing mature ScanSession.
 *
 * M2 intentionally wraps, rather than rewrites, the current vertical pipeline.
 * All emitted values are observations of real ScanSession state; no provider
 * completion events are invented because the legacy scanner does not yet expose
 * per-provider callbacks. Provider-level queue/start/completion events remain an
 * explicit M2 follow-up when the scanner scheduler is migrated.
 */
object ScanCoordinatorRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val monitoringStarted = AtomicBoolean(false)
    private val lock = Any()

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
                        _snapshot.value = LiveScanSnapshot(
                            scanId = id,
                            state = ScanRunState.Running,
                            mode = mode,
                            directProfileProviders = directProviders,
                            stage = safeCoordinatorStage(ScanSession.progressText.value)
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
        }
        DiscoveryScanPreferences.setMode(request.mode)
        ScanSession.setDeepResearch(request.deepResearch)
        ScanSession.startScan(context, request.input, request.deepResearch)
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
        _snapshot.value = LiveScanSnapshot()
        ScanHistoryRuntime.resetForTests()
    }

    private fun emit(event: ScanEvent) {
        _events.tryEmit(event)
    }

    private fun newScanId(): ScanId = ScanId(UUID.randomUUID().toString())

}
