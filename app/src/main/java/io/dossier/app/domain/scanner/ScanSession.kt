package io.dossier.app.domain.scanner

import android.content.Context
import android.net.Uri
import io.dossier.app.data.ai.AiInsightService
import io.dossier.app.data.ai.AiProviderConfigStore
import io.dossier.app.data.ai.AiRemotePermission
import io.dossier.app.data.breach.BreachCheckService
import io.dossier.app.data.face.FaceCorrelationSessionPolicy
import io.dossier.app.data.face.FaceEmbeddingModelStore
import io.dossier.app.data.face.ProfileImageDownloader
import io.dossier.app.data.local.ProfileConsistencyCache
import io.dossier.app.domain.ai.AiAnalysisSnapshot
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.CaseEvidenceIdMigration
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.evidence.AttackPathFinder
import io.dossier.app.domain.evidence.ConfidenceEngine
import io.dossier.app.domain.evidence.EmailDomainContributor
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.ExposureEngine
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.evidence.SharedDomainContributor
import io.dossier.app.domain.evidence.SharedIdentifierContributor
import io.dossier.app.domain.evidence.UsernameSimilarityContributor
import io.dossier.app.domain.evidence.withResolvedRelationshipEvidence
import io.dossier.app.domain.evidence.runPlugins
import io.dossier.app.domain.face.FaceConsistencyChecker
import io.dossier.app.domain.face.FaceEmbeddingService
import io.dossier.app.domain.graph.EntityGraphBuilder
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.discovery.WhatsMyNameCatalog
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderPlanFingerprint
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.PlaceScanResult
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.place.MediaIntelligenceSession
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.remediation.RemediationItem
import io.dossier.app.domain.remediation.RemediationProvider
import io.dossier.app.domain.risk.RiskScorer
import io.dossier.app.domain.username.UsernameVariantGenerator
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.ProviderDiagnosticsRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal class ScanExecutionException(
    val failureCode: String = ScanLifecycleErrors.SCAN_EXECUTION_FAILED
) : Exception()

private data class BreachStageRun(
    val digests: List<BreachDigest>,
    val findings: List<Finding>,
    val checkpointResults: List<BreachStageCheckpointResult>
)

/**
 * Observable scan/session state shared by the Compose UI and durable WorkManager
 * execution. Scan results remain transient unless the user explicitly saves a case.
 */
object ScanSession {
    /** Keep draft correction metadata bounded until the user explicitly saves a case. */
    const val MAX_DRAFT_CORRECTIONS = 256

    var tempInput: IdentityInput? = null
    val selectedModel = MutableStateFlow(LocalAiModelType.DEFAULT)

    private val _currentInput = MutableStateFlow<IdentityInput?>(null)
    val currentInput: StateFlow<IdentityInput?> = _currentInput

    private val _findings = MutableStateFlow<List<Finding>>(emptyList())
    val findings: StateFlow<List<Finding>> = _findings

    private val _placeScanResult = MutableStateFlow<PlaceScanResult?>(null)
    val placeScanResult: StateFlow<PlaceScanResult?> = _placeScanResult

    private val _profileScanResults = MutableStateFlow<List<ProfileScanResult>>(emptyList())
    val profileScanResults: StateFlow<List<ProfileScanResult>> = _profileScanResults

    private val _faceConsistencyMatches = MutableStateFlow<List<FaceConsistencyMatch>>(emptyList())
    val faceConsistencyMatches: StateFlow<List<FaceConsistencyMatch>> = _faceConsistencyMatches

    private val _entityGraph = MutableStateFlow(EntityGraph())
    val entityGraph: StateFlow<EntityGraph> = _entityGraph

    private val _relationshipConfidence = MutableStateFlow<Map<String, RelationshipConfidence>>(emptyMap())
    val relationshipConfidence: StateFlow<Map<String, RelationshipConfidence>> = _relationshipConfidence

    private val _exposure = MutableStateFlow<ExposureEngine.ExposureResult?>(null)
    val exposure: StateFlow<ExposureEngine.ExposureResult?> = _exposure

    private val _attackPaths = MutableStateFlow<List<AttackPathFinder.AttackPath>>(emptyList())
    val attackPaths: StateFlow<List<AttackPathFinder.AttackPath>> = _attackPaths

    private val _breachDigests = MutableStateFlow<List<BreachDigest>>(emptyList())
    val breachDigests: StateFlow<List<BreachDigest>> = _breachDigests

    private val _riskLevel = MutableStateFlow(RiskLevel.Low)
    val riskLevel: StateFlow<RiskLevel> = _riskLevel

    private val _remediationTips = MutableStateFlow<List<String>>(emptyList())
    val remediationTips: StateFlow<List<String>> = _remediationTips

    private val _remediationItems = MutableStateFlow<List<RemediationItem>>(emptyList())
    val remediationItems: StateFlow<List<RemediationItem>> = _remediationItems

    private val _aiSummary = MutableStateFlow<String?>(null)
    val aiSummary: StateFlow<String?> = _aiSummary

    private val _scanHistory = MutableStateFlow<List<CaseScanHistoryEntry>>(emptyList())
    val scanHistory: StateFlow<List<CaseScanHistoryEntry>> = _scanHistory

    private val _userCorrections = MutableStateFlow<List<UserCorrection>>(emptyList())
    val userCorrections: StateFlow<List<UserCorrection>> = _userCorrections

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText

    private val _memoryDropped = MutableStateFlow(0)
    val memoryDropped: StateFlow<Int> = _memoryDropped

    private var scanApplicationContext: Context? = null

    /**
     * Starts durable work through the scan coordinator. Navigating away from
     * the scan screen no longer cancels work; Android/WorkManager owns
     * execution and restart policy. Keep this entry internal so production
     * callers cannot bypass coordinator ownership.
     */
    internal suspend fun startScan(context: Context, input: IdentityInput, deepResearch: Boolean = false) {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            scanApplicationContext = appContext
            ProviderDiagnosticsRuntime.install(appContext)

            runCatching {
                BackgroundScanManager.enqueue(
                    context = appContext,
                    input = input,
                    deepResearch = deepResearch,
                    strongFaceCorrelation = FaceCorrelationSessionPolicy.isStrongCorrelationEnabled()
                )
            }.onFailure { error ->
                FaceCorrelationSessionPolicy.useBasicMatching()
                val code = (error as? BackgroundScanSchedulingException)?.code
                    ?: "WORK_SCHEDULING_FAILED"
                _progressText.value = "${BackgroundScanWorker.STAGE_FAILED}: $code"
                _isScanning.value = false
            }
        }
    }

    /**
     * Called under BackgroundScanManager's lifecycle lock after owner
     * publication, and again by a recreated WorkManager worker. The optional
     * durable id lets the latter rebuild process-local coordinator state without
     * resetting an already-running same-request session.
     */
    internal fun markBackgroundScheduled(
        input: IdentityInput,
        deepResearch: Boolean,
        scanId: ScanId? = null
    ) {
        scanId?.let { durableId ->
            if (ScanCoordinatorRuntime.activeScanId() != durableId) {
                ScanCoordinatorRuntime.resetCounts(durableId)
            }
        }
        if (_currentInput.value == input && _isScanning.value) {
            setDeepResearch(deepResearch)
            if (_progressText.value.isBlank()) _progressText.value = BackgroundScanWorker.STAGE_STARTING
            return
        }
        EvidenceRuntimeCache.clear()
        MediaIntelligenceSession.beginFor(input)
        _scanHistory.value = emptyList()
        _userCorrections.value = emptyList()
        _currentInput.value = input
        setDeepResearch(deepResearch)
        _progressText.value = BackgroundScanWorker.STAGE_STARTING
        _isScanning.value = true
    }

    fun loadResumePoint(context: Context): Pair<IdentityInput, Boolean>? = ScanResumeStore(context).load()

    fun clearResumePoint(context: Context) {
        ScanResumeStore(context).clear()
    }

    /** Cancels durable work. Partial in-memory results are intentionally retained. */
    /** Cancellation is coordinator-owned; this is an internal state bridge. */
    internal fun cancelScan() {
        _progressText.value = "SCAN_CANCELLED"
        scanApplicationContext?.let(BackgroundScanManager::cancel)
        _isScanning.value = false
    }

    /** Called by the worker when setup/execution fails before normal scan cleanup. */
    internal fun markBackgroundFailure(message: String) {
        _progressText.value = "${BackgroundScanWorker.STAGE_FAILED}: ${message.take(240)}"
        _isScanning.value = false
    }

    internal fun markBackgroundSucceeded() {
        _progressText.value = BackgroundScanWorker.STAGE_COMPLETE
        _isScanning.value = false
    }

    internal fun markBackgroundFinished() {
        _isScanning.value = false
    }

    /**
     * Pausing is not a terminal failure or completion. Keep the process-local
     * session out of the running state while retaining its durable checkpoint
     * for an explicit resume.
     */
    internal fun markBackgroundPaused() {
        _progressText.value = "SCAN_PAUSED"
        _isScanning.value = false
    }

    internal fun markBackgroundCancelled() {
        _progressText.value = "SCAN_CANCELLED"
        _isScanning.value = false
    }

    private val _deepResearchEnabled = MutableStateFlow(false)
    val deepResearchEnabled: StateFlow<Boolean> = _deepResearchEnabled

    fun toggleDeepResearch() {
        _deepResearchEnabled.value = !_deepResearchEnabled.value
    }

    fun setDeepResearch(enabled: Boolean) {
        _deepResearchEnabled.value = enabled
    }

    private var placeImageUri: Uri? = null

    fun setPlaceImage(uri: Uri?) {
        placeImageUri = uri
    }

    fun getPlaceImage(): Uri? = placeImageUri

    data class ExportState(
        val input: IdentityInput?,
        val findings: List<Finding>,
        val profileScanResults: List<ProfileScanResult>,
        val faceConsistencyMatches: List<FaceConsistencyMatch>,
        val entityGraph: EntityGraph,
        val breachDigests: List<BreachDigest>,
        val riskLevel: RiskLevel,
        val remediationTips: List<String>,
        val aiSummary: String?
    )

    fun exportState(): ExportState = ExportState(
        input = _currentInput.value,
        findings = _findings.value,
        profileScanResults = _profileScanResults.value,
        faceConsistencyMatches = _faceConsistencyMatches.value,
        entityGraph = _entityGraph.value,
        breachDigests = _breachDigests.value,
        riskLevel = _riskLevel.value,
        remediationTips = _remediationTips.value,
        aiSummary = _aiSummary.value
    )

    fun buildCase(): DossierCase? {
        val input = _currentInput.value ?: return null
        val createdAt = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val evidenceCollection = EvidenceRuntimeCache.collection.value
        return DossierCase(
            createdAt = createdAt,
            subjectName = input.fullName.trim().ifBlank { input.primaryUsername ?: "UNKNOWN SUBJECT" },
            input = input,
            findings = _findings.value,
            evidenceRecords = evidenceCollection.evidence,
            evidenceRelationships = evidenceCollection.relationships,
            profileResults = _profileScanResults.value,
            faceMatches = _faceConsistencyMatches.value,
            entityGraph = _entityGraph.value,
            breachDigests = _breachDigests.value,
            riskLevel = _riskLevel.value,
            mediaIntelligence = MediaIntelligenceSession.snapshotFor(input),
            exposure = _exposure.value,
            attackPaths = _attackPaths.value,
            relationshipConfidence = _relationshipConfidence.value,
            aiSummary = _aiSummary.value,
            scanHistory = _scanHistory.value,
            userCorrections = _userCorrections.value
        )
    }

    /** Restores a transient encrypted background result after process death. */
    fun restoreFromCase(case: DossierCase) {
        val migrated = CaseEvidenceIdMigration.migrate(case)
        EvidenceRuntimeCache.replaceCaseEvidence(migrated.evidenceRecords, migrated.evidenceRelationships)
        MediaIntelligenceSession.restoreFor(migrated.input, migrated.mediaIntelligence)
        _scanHistory.value = migrated.scanHistory
        _userCorrections.value = migrated.userCorrections
            .takeLast(MAX_DRAFT_CORRECTIONS)
        _currentInput.value = migrated.input
        _findings.value = migrated.findings
        _profileScanResults.value = migrated.profileResults
        _faceConsistencyMatches.value = migrated.faceMatches
        _entityGraph.value = migrated.entityGraph
        _breachDigests.value = migrated.breachDigests
        _riskLevel.value = migrated.riskLevel
        _exposure.value = migrated.exposure
        _attackPaths.value = migrated.attackPaths
        _relationshipConfidence.value = migrated.relationshipConfidence
        _aiSummary.value = migrated.aiSummary
        val remediationProvider = RemediationProvider()
        _remediationTips.value = remediationProvider.getGlobalTips(migrated.findings)
        _remediationItems.value = remediationProvider.getStructuredTips(migrated.findings)
        _memoryDropped.value = 0
        _isScanning.value = false
        _progressText.value = BackgroundScanWorker.STAGE_COMPLETE
    }

    /**
     * Applies one draft correction to the in-memory working session. Raw
     * evidence remains in [EvidenceRuntimeCache] and the correction is only
     * durable after the existing explicit encrypted Save Case action.
     */
    fun recordDraftCorrection(correction: UserCorrection): Boolean {
        val normalized = correction.copy(
            evidenceId = correction.evidenceId?.let(EvidenceIdPolicy::migrate)
        )
        if (normalized.evidenceId.isNullOrBlank() && normalized.entityId.isNullOrBlank()) {
            return false
        }
        val current = _userCorrections.value
        val replacesExisting = current.any { existing ->
            existing.correctionId == normalized.correctionId ||
                (existing.evidenceId != null && existing.evidenceId == normalized.evidenceId) ||
                (existing.entityId != null && existing.entityId == normalized.entityId)
        }
        if (!replacesExisting && current.size >= MAX_DRAFT_CORRECTIONS) return false

        _userCorrections.value = current.filterNot { existing ->
            existing.correctionId == normalized.correctionId ||
                (existing.evidenceId != null && existing.evidenceId == normalized.evidenceId) ||
                (existing.entityId != null && existing.entityId == normalized.entityId)
        } + normalized
        // Existing analysis was produced before this draft decision and is no
        // longer a truthful summary of the corrected working session.
        _aiSummary.value = null
        return true
    }

    fun restoreLatestBackgroundResult(context: Context): Boolean {
        val snapshot = BackgroundScanManager.latestResult(context) ?: return false
        restoreFromCase(snapshot.dossierCase)
        return true
    }

    fun saveCase(context: Context): DossierCase? {
        val case = buildCase() ?: return null
        val ok = CaseStore(context).save(case)
        return if (ok) case else null
    }

    /**
     * Persists an encrypted case away from the Compose/main dispatcher. Case
     * serialization, Keystore access and atomic file replacement can all block
     * long enough to cause UI jank on large investigations.
     */
    suspend fun saveCaseAsync(
        context: Context,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    ): DossierCase? = withContext(dispatcher) {
        saveCase(context)
    }

    suspend fun executeScan(
        context: Context,
        input: IdentityInput,
        deepResearch: Boolean = false,
        requestId: String? = null,
        checkpointOwnerId: String? = null,
        checkpointGeneration: String? = null
    ) = withContext(Dispatchers.IO) {
        ProviderDiagnosticsRuntime.install(context.applicationContext)
        val inputToUse = input
        _currentInput.value = inputToUse
        EvidenceRuntimeCache.clear()
        MediaIntelligenceSession.beginFor(inputToUse)
        _scanHistory.value = emptyList()
        _userCorrections.value = emptyList()
        _findings.value = emptyList()
        _placeScanResult.value = null
        _profileScanResults.value = emptyList()
        _faceConsistencyMatches.value = emptyList()
        _entityGraph.value = EntityGraph()
        _breachDigests.value = emptyList()
        _relationshipConfidence.value = emptyMap()
        _attackPaths.value = emptyList()
        _riskLevel.value = RiskLevel.Low
        _exposure.value = null
        _remediationTips.value = emptyList()
        _remediationItems.value = emptyList()
        _aiSummary.value = null
        _memoryDropped.value = 0

        val cache = ProfileConsistencyCache(context)
        cache.clearAll()

        try {
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.DiscoveringUsernames,
                completed = false
            )
            _progressText.value = "DISCOVERING_USERNAMES..."
            WhatsMyNameCatalog.install(context)
            val piiExtractor = PiiExtractor()
            val variantGenerator = UsernameVariantGenerator()
            val profileScanner = ProfileScanner(context, piiExtractor, variantGenerator)

            val publicPayloadStore = requestId
                ?.takeIf(BackgroundScanWorker::isCanonicalUuid)
                ?.let { durableRequestId ->
                    runCatching {
                        PublicDiscoveryPayloadStore(
                            context = context,
                            requestId = durableRequestId,
                            planFingerprint = ProviderPlanFingerprint.forPlan(
                                ProviderCatalogV2.plan(DiscoveryScanPreferences.selectedMode.value)
                            )
                        )
                    }.getOrNull()
                }

            val scanResults = profileScanner.scanIdentity(inputToUse, deepResearch = deepResearch, requestId = requestId)
            currentCoroutineContext().ensureActive()
            _profileScanResults.value = scanResults
            MediaIntelligenceSession.recordVerifiedProfileAvatars(inputToUse, scanResults)
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.DiscoveringUsernames,
                completed = true,
                output = ScanStageOutput(
                    itemCount = scanResults.size,
                    verifiedCount = scanResults.count { it.exists && it.verified }
                ),
                payloads = publicPayloadStore?.summaries().orEmpty()
            )

            val allFindings = mutableListOf<Finding>()
            scanResults.filter { it.exists }.forEach { allFindings.addAll(it.findings) }

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.ComparingFaceConsistency,
                completed = false
            )
            _progressText.value = "COMPARING_FACE_CONSISTENCY..."
            val faceMatches = runFaceConsistency(context, inputToUse, scanResults)
            currentCoroutineContext().ensureActive()
            _faceConsistencyMatches.value = faceMatches
            allFindings.addAll(faceFindingsFromMatches(faceMatches))
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.ComparingFaceConsistency,
                completed = true,
                output = ScanStageOutput(itemCount = faceMatches.size)
            )

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.CheckingBreachExposure,
                completed = false
            )
            _progressText.value = "CHECKING_BREACH_EXPOSURE..."
            val persistedBreach = loadReusableBreachCheckpoint(
                context = context,
                requestId = requestId,
                ownerId = checkpointOwnerId
            )
            val breachRun = if (persistedBreach != null) {
                BreachStageRun(
                    digests = persistedBreach.results.map { it.toDigest() },
                    findings = findingsFromBreachCheckpoint(persistedBreach),
                    checkpointResults = persistedBreach.results
                )
            } else {
                runBreachChecks(
                    context = context,
                    emails = inputToUse.emails,
                    deepResearch = deepResearch
                )
            }
            currentCoroutineContext().ensureActive()
            val digests = breachRun.digests
            allFindings.addAll(breachRun.findings)
            _breachDigests.value = digests
            val breachCheckpoint = persistedBreach ?: buildBreachCheckpoint(
                context = context,
                requestId = requestId,
                ownerId = checkpointOwnerId,
                results = breachRun.checkpointResults
            )
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.CheckingBreachExposure,
                completed = true,
                output = ScanStageOutput(
                    itemCount = digests.size,
                    verifiedCount = digests.count { it.breachCount > 0 }
                ),
                breachCheckpoint = breachCheckpoint
            )

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.BuildingEntityGraph,
                completed = false
            )
            _progressText.value = "BUILDING_ENTITY_GRAPH..."
            val pluginCollection = runPlugins(inputToUse)
            currentCoroutineContext().ensureActive()
            val evidenceSnapshot = buildEvidenceSnapshot(
                input = inputToUse,
                profileResults = scanResults,
                pluginCollection = pluginCollection,
                findings = allFindings,
                retrievedAtEpochMillis = System.currentTimeMillis()
            )
            val evidence = evidenceSnapshot.evidence
            val relationships = evidenceSnapshot.relationships
            EvidenceRuntimeCache.replace(evidenceSnapshot)
            val graphInputDigest = GraphCheckpointCodec.inputDigest(
                input = inputToUse,
                profileResults = scanResults,
                findings = allFindings,
                faceMatches = faceMatches,
                breachDigests = digests,
                evidence = evidence,
                relationships = relationships
            )
            val graphPlanFingerprint = requestId?.let { durableRequestId ->
                (runCatching {
                    ScanResumeStore(context).loadRequestDetailed(durableRequestId)
                }.getOrNull() as? ResumeReadState.Available)?.point?.planFingerprint
            }
            val graph = loadReusableEntityGraphCheckpoint(
                context = context,
                requestId = requestId,
                ownerId = checkpointOwnerId,
                planFingerprint = graphPlanFingerprint,
                inputDigest = graphInputDigest
            ) ?: EntityGraphBuilder.build(
                input = inputToUse,
                profileResults = scanResults,
                findings = allFindings,
                faceMatches = faceMatches,
                breachDigests = digests,
                evidence = evidence,
                relationships = relationships
            )
            _entityGraph.value = graph
            val graphCheckpoint = buildEntityGraphCheckpoint(
                requestId = requestId,
                ownerId = checkpointOwnerId,
                planFingerprint = graphPlanFingerprint,
                inputDigest = graphInputDigest,
                graph = graph
            )
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.BuildingEntityGraph,
                completed = true,
                output = ScanStageOutput(
                    itemCount = graph.entities.size,
                    verifiedCount = graph.entities.count { it.state == io.dossier.app.domain.model.GraphNodeState.Confirmed }
                ),
                entityGraphCheckpoint = graphCheckpoint
            )

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = false
            )
            _progressText.value = "SCORING_RELATIONSHIP_CONFIDENCE..."
            val usernameSeeds = (listOfNotNull(inputToUse.primaryUsername) + inputToUse.usernames)
                .filter { it.isNotBlank() }
                .map { it.lowercase() }
                .toSet()
            val confidenceInputDigest = ConfidenceCheckpointCodec.inputDigest(
                input = inputToUse,
                graph = graph,
                evidence = evidence,
                usernameSeeds = usernameSeeds.toList()
            )
            _relationshipConfidence.value = loadReusableRelationshipConfidenceCheckpoint(
                context = context,
                requestId = requestId,
                ownerId = checkpointOwnerId,
                planFingerprint = graphPlanFingerprint,
                inputDigest = confidenceInputDigest
            ) ?: ConfidenceEngine(
                contributors = listOf(
                    UsernameSimilarityContributor(),
                    EmailDomainContributor(),
                    SharedIdentifierContributor(usernameSeeds),
                    SharedDomainContributor()
                )
            ).score(graph, evidence)
            val relationshipConfidenceCheckpoint = buildRelationshipConfidenceCheckpoint(
                requestId = requestId,
                ownerId = checkpointOwnerId,
                planFingerprint = graphPlanFingerprint,
                inputDigest = confidenceInputDigest,
                confidence = _relationshipConfidence.value
            )
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = true,
                output = ScanStageOutput(itemCount = _relationshipConfidence.value.size),
                relationshipConfidenceCheckpoint = relationshipConfidenceCheckpoint
            )

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.TracingAttackPaths,
                completed = false
            )
            _progressText.value = "TRACING_ATTACK_PATHS..."
            val attackPathsInputDigest = AttackPathsCheckpointCodec.inputDigest(
                input = inputToUse,
                graph = graph,
                confidenceByEdge = _relationshipConfidence.value
            )
            _attackPaths.value = loadReusableAttackPathsCheckpoint(
                context = context,
                requestId = requestId,
                ownerId = checkpointOwnerId,
                planFingerprint = graphPlanFingerprint,
                inputDigest = attackPathsInputDigest
            ) ?: AttackPathFinder().findPaths(graph, _relationshipConfidence.value)
            val attackPathsCheckpoint = buildAttackPathsCheckpoint(
                requestId = requestId,
                ownerId = checkpointOwnerId,
                planFingerprint = graphPlanFingerprint,
                inputDigest = attackPathsInputDigest,
                paths = _attackPaths.value
            )
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.TracingAttackPaths,
                completed = true,
                output = ScanStageOutput(itemCount = _attackPaths.value.size),
                attackPathsCheckpoint = attackPathsCheckpoint
            )

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.CompilingExposureLevels,
                completed = false
            )
            _progressText.value = "COMPILING_EXPOSURE_LEVELS..."
            _riskLevel.value = RiskScorer().score(allFindings)
            val remediationProvider = RemediationProvider()
            _remediationTips.value = remediationProvider.getGlobalTips(allFindings)
            _remediationItems.value = remediationProvider.getStructuredTips(allFindings)
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.CompilingExposureLevels,
                completed = true,
                output = ScanStageOutput(
                    itemCount = allFindings.size,
                    verifiedCount = allFindings.count { it.risk != RiskLevel.Low }
                )
            )

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.CompilingExposureScores,
                completed = false
            )
            _progressText.value = "COMPILING_EXPOSURE_SCORES..."
            _exposure.value = ExposureEngine().score(allFindings, digests)

            val distinctFindings = allFindings.distinctBy { it.type.name + it.value + it.sourceUrl }
            val capped = MemoryGuard.cap(distinctFindings)
            _memoryDropped.value = capped.droppedCount
            _findings.value = capped.retained
            if (capped.droppedCount > 0) {
                _progressText.value = "MEMORY_LIMIT: ${capped.droppedCount} findings omitted (cap ${MemoryGuard.MAX_FINDINGS})"
            }
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.CompilingExposureScores,
                completed = true,
                output = ScanStageOutput(
                    itemCount = _findings.value.size,
                    omittedCount = _memoryDropped.value
                )
            )

            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.GeneratingAiSummary,
                completed = false
            )
            _progressText.value = "GENERATING_AI_SUMMARY..."
            val summary = try {
                // A configured-and-enabled provider is the explicit persisted
                // opt-in. Any config/keystore failure fails closed to local AI
                // and deterministic fallback; credentials alone never opt in.
                val remotePermission = runCatching {
                    if (AiProviderConfigStore(context).firstUsableRemoteProvider() != null) {
                        AiRemotePermission.AllowRedactedEvidence
                    } else {
                        AiRemotePermission.Denied
                    }
                }.getOrDefault(AiRemotePermission.Denied)
                AiInsightService(context).summarizeDossier(
                    snapshot = buildAiAnalysisSnapshot(
                        input = inputToUse,
                        profileResults = scanResults,
                        findings = _findings.value,
                        evidence = evidence,
                        graph = graph,
                        faceMatches = faceMatches,
                        breachDigests = digests,
                        exposure = _exposure.value
                    ),
                    remotePermission = remotePermission
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            _aiSummary.value = summary
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.GeneratingAiSummary,
                completed = true,
                output = ScanStageOutput(itemCount = if (summary.isNullOrBlank()) 0 else 1)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (execution: ScanExecutionException) {
            throw execution
        } catch (_: Exception) {
            throw ScanExecutionException()
        } finally {
            cache.close()
        }
    }

    /**
     * Persists only allow-listed semantic boundaries for durable workers. A
     * foreground/direct caller without an opaque owner has no request ledger,
     * so it retains the historical in-memory behavior. Stale workers cancel
     * cooperatively; storage corruption/failure is surfaced as an execution
     * failure rather than silently claiming resumability.
     */
    private fun checkpointStage(
        context: Context,
        requestId: String?,
        ownerId: String?,
        generation: String?,
        stage: ScanCheckpointStage,
        completed: Boolean,
        output: ScanStageOutput? = null,
        payloads: List<ScanPayloadSummary> = emptyList(),
        breachCheckpoint: BreachStageCheckpoint? = null,
        entityGraphCheckpoint: EntityGraphStageCheckpoint? = null,
        relationshipConfidenceCheckpoint: RelationshipConfidenceStageCheckpoint? = null,
        attackPathsCheckpoint: AttackPathsStageCheckpoint? = null
    ) {
        if (requestId == null || ownerId == null || generation == null) return
        fun writeCheckpoint(
            graphCheckpoint: EntityGraphStageCheckpoint?,
            confidenceCheckpoint: RelationshipConfidenceStageCheckpoint?,
            attackPathsCheckpoint: AttackPathsStageCheckpoint?
        ): ResumeCheckpointWriteState =
            ScanCoordinatorRuntime.recordCheckpoint(
                context = context,
                requestId = requestId,
                ownerId = ownerId,
                generation = generation,
                stage = stage,
                completed = completed,
                output = output,
                payloads = payloads,
                breachCheckpoint = breachCheckpoint,
                entityGraphCheckpoint = graphCheckpoint,
                relationshipConfidenceCheckpoint = confidenceCheckpoint,
                attackPathsCheckpoint = attackPathsCheckpoint
            )
        val first = writeCheckpoint(
            entityGraphCheckpoint,
            relationshipConfidenceCheckpoint,
            attackPathsCheckpoint
        )
        // Deterministic outputs are optional accelerators. If the encrypted
        // request record cannot fit the new payload, retain the semantic stage
        // boundary and let a retry rebuild that output.
        val result = if (
            first is ResumeCheckpointWriteState.Invalid &&
            first.reason == ResumeInvalidReason.RecordTooLarge &&
            (entityGraphCheckpoint != null ||
                relationshipConfidenceCheckpoint != null ||
                attackPathsCheckpoint != null)
        ) {
            writeCheckpoint(null, null, null)
        } else {
            first
        }
        when (result) {
            is ResumeCheckpointWriteState.Saved -> Unit
            ResumeCheckpointWriteState.StaleOwner,
            ResumeCheckpointWriteState.Missing -> throw CancellationException()
            is ResumeCheckpointWriteState.Invalid,
            is ResumeCheckpointWriteState.StorageFailure -> throw ScanExecutionException(
                ScanLifecycleErrors.CHECKPOINT_STORAGE_FAILURE
            )
        }
    }

    fun purgeSession(context: Context) {
        _currentInput.value = null
        EvidenceRuntimeCache.clear()
        MediaIntelligenceSession.clear()
        _scanHistory.value = emptyList()
        _findings.value = emptyList()
        _placeScanResult.value = null
        _profileScanResults.value = emptyList()
        _faceConsistencyMatches.value = emptyList()
        _entityGraph.value = EntityGraph()
        _breachDigests.value = emptyList()
        _relationshipConfidence.value = emptyMap()
        _attackPaths.value = emptyList()
        _riskLevel.value = RiskLevel.Low
        _exposure.value = null
        _remediationTips.value = emptyList()
        _remediationItems.value = emptyList()
        _aiSummary.value = null
        _userCorrections.value = emptyList()
        _memoryDropped.value = 0
        _isScanning.value = false
        _progressText.value = ""
        placeImageUri = null
        BackgroundScanManager.cancel(context)
        BackgroundScanManager.clearLatestResult(context)

        val cache = ProfileConsistencyCache(context)
        cache.clearAll()
        cache.close()
        ProfileImageDownloader(context).clearCache()
    }

    /**
     * Purges transient encrypted/background state from an IO dispatcher. UI
     * callers must use this suspend entry point so keystore/file cleanup does
     * not block composition or input handling.
     */
    suspend fun purgeSessionAsync(context: Context) = withContext(Dispatchers.IO) {
        purgeSession(context)
    }

    private suspend fun runBreachChecks(
        context: Context,
        emails: List<String>,
        deepResearch: Boolean
    ): BreachStageRun {
        val cleanEmails = emails.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (cleanEmails.isEmpty()) return BreachStageRun(emptyList(), emptyList(), emptyList())

        return try {
            val results = BreachCheckService(context).checkEmails(
                cleanEmails,
                hibpApiKey = null,
                deepResearch = deepResearch
            )
            val digests = results.map { result ->
                val sources = buildList {
                    addAll(result.breaches.map { it.title.ifBlank { it.name } })
                    addAll(result.publicEvidence.map { it.url }.filter { it.isNotBlank() })
                }.distinct()
                val breachCount = result.breaches.size
                BreachDigest(
                    email = result.email,
                    breachCount = breachCount,
                    sources = sources,
                    note = safeBreachNote(result.error)
                )
            }
            val checkpointResults = results.map { result ->
                val sourceUrls = result.publicEvidence.map { it.url }.filter { it.isNotBlank() }
                BreachStageCheckpointResult(
                    email = result.email.trim(),
                    breachCount = result.breaches.size,
                    breachTitles = result.breaches.map { it.title.ifBlank { it.name } },
                    publicHitCount = result.publicEvidence.size,
                    publicEvidenceUrls = sourceUrls,
                    sources = buildList {
                        addAll(result.breaches.map { it.title.ifBlank { it.name } })
                        addAll(sourceUrls)
                    }.distinct(),
                    note = safeBreachNote(result.error)
                )
            }
            BreachStageRun(
                digests = digests,
                findings = checkpointResults.flatMap(::findingsFromBreachResult),
                checkpointResults = checkpointResults
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BreachStageRun(emptyList(), emptyList(), emptyList())
        }
    }

    private fun buildBreachCheckpoint(
        context: Context,
        requestId: String?,
        ownerId: String?,
        results: List<BreachStageCheckpointResult>
    ): BreachStageCheckpoint? {
        if (requestId == null || ownerId == null || results.size > ScanResumeStore.MAX_BREACH_RESULTS) {
            return null
        }
        // Read the immutable plan commitment from the exact encrypted request
        // rather than the mutable process preference. A preference change or
        // a recreated worker must never attach output to another plan.
        val fingerprint = (runCatching {
            ScanResumeStore(context).loadRequestDetailed(requestId)
        }.getOrNull() as? ResumeReadState.Available)
            ?.point
            ?.planFingerprint
            ?.takeIf { ProviderPlanFingerprint.isValid(it) }
            ?: return null
        val candidate = BreachStageCheckpoint(
            requestId = requestId,
            planFingerprint = fingerprint,
            ownerId = ownerId,
            capturedAtEpochMillis = System.currentTimeMillis(),
            results = results
        )
        return candidate.takeIf { isLocallyBoundedBreachCheckpoint(it) }
    }

    private fun isLocallyBoundedBreachCheckpoint(checkpoint: BreachStageCheckpoint): Boolean =
        checkpoint.results.size <= ScanResumeStore.MAX_BREACH_RESULTS &&
            checkpoint.results.map { it.email.trim().lowercase() }.distinct().size == checkpoint.results.size &&
            checkpoint.results.all { result ->
                result.email.length in 1..ScanResumeStore.MAX_BREACH_EMAIL_CHARS &&
                    result.email.none { it.code < 0x20 || it.code == 0x7f } &&
                    result.breachCount in 0..ScanResumeStore.MAX_BREACH_COUNT &&
                    result.publicHitCount in 0..ScanResumeStore.MAX_BREACH_COUNT &&
                    result.breachTitles.size <= ScanResumeStore.MAX_BREACH_TITLES &&
                    result.sources.size <= ScanResumeStore.MAX_BREACH_SOURCES &&
                    result.publicEvidenceUrls.size <= ScanResumeStore.MAX_BREACH_SOURCES &&
                    result.breachTitles.all { it.length in 1..ScanResumeStore.MAX_BREACH_TITLE_CHARS && it.none { c -> c.code < 0x20 || c.code == 0x7f } } &&
                    result.sources.all { it.length in 1..ScanResumeStore.MAX_BREACH_SOURCE_CHARS && it.none { c -> c.code < 0x20 || c.code == 0x7f } } &&
                    result.publicEvidenceUrls.all { url ->
                        url.length in 1..ScanResumeStore.MAX_BREACH_URL_CHARS &&
                            runCatching {
                                val parsed = java.net.URI(url)
                                parsed.scheme.lowercase() in setOf("http", "https") &&
                                    !parsed.host.isNullOrBlank() && parsed.userInfo == null
                            }.getOrDefault(false)
                    } &&
                    result.note?.length?.let { it <= ScanResumeStore.MAX_BREACH_NOTE_CHARS } != false
            }

    private fun loadReusableBreachCheckpoint(
        context: Context,
        requestId: String?,
        ownerId: String?
    ): BreachStageCheckpoint? {
        if (requestId == null || ownerId == null) return null
        val point = (runCatching {
            ScanResumeStore(context).loadRequestDetailed(requestId)
        }.getOrNull() as? ResumeReadState.Available)?.point ?: return null
        if (point.checkpointOwnerId != ownerId ||
            point.planFingerprint.isNullOrBlank() ||
            ScanCheckpointStage.CheckingBreachExposure !in point.completedCheckpointStages
        ) return null
        return point.breachCheckpoint?.takeIf { checkpoint ->
            checkpoint.requestId == requestId &&
                checkpoint.ownerId == ownerId &&
                checkpoint.planFingerprint == point.planFingerprint
        }
    }

    private fun loadReusableEntityGraphCheckpoint(
        context: Context,
        requestId: String?,
        ownerId: String?,
        planFingerprint: String?,
        inputDigest: String
    ): EntityGraph? {
        if (requestId == null || ownerId == null || !ProviderPlanFingerprint.isValid(planFingerprint)) {
            return null
        }
        if (!GraphCheckpointCodec.isValidDigest(inputDigest)) return null
        val point = (runCatching {
            ScanResumeStore(context).loadRequestDetailed(requestId)
        }.getOrNull() as? ResumeReadState.Available)?.point ?: return null
        if (point.checkpointOwnerId != ownerId ||
            point.planFingerprint != planFingerprint ||
            ScanCheckpointStage.BuildingEntityGraph !in point.completedCheckpointStages
        ) return null
        val checkpoint = point.entityGraphCheckpoint ?: return null
        if (checkpoint.requestId != requestId ||
            checkpoint.ownerId != ownerId ||
            checkpoint.planFingerprint != planFingerprint ||
            checkpoint.inputDigest != inputDigest
        ) return null
        return GraphCheckpointCodec.decode(checkpoint.graphJson)
    }

    private fun buildEntityGraphCheckpoint(
        requestId: String?,
        ownerId: String?,
        planFingerprint: String?,
        inputDigest: String,
        graph: EntityGraph
    ): EntityGraphStageCheckpoint? {
        if (requestId == null || ownerId == null) {
            return null
        }
        val plan = planFingerprint?.takeIf(ProviderPlanFingerprint::isValid) ?: return null
        if (!GraphCheckpointCodec.isValidDigest(inputDigest)) return null
        val graphJson = GraphCheckpointCodec.encode(graph) ?: return null
        return EntityGraphStageCheckpoint(
            requestId = requestId,
            planFingerprint = plan,
            ownerId = ownerId,
            capturedAtEpochMillis = System.currentTimeMillis(),
            inputDigest = inputDigest,
            graphJson = graphJson
        )
    }

    private fun loadReusableRelationshipConfidenceCheckpoint(
        context: Context,
        requestId: String?,
        ownerId: String?,
        planFingerprint: String?,
        inputDigest: String
    ): Map<String, RelationshipConfidence>? {
        if (requestId == null || ownerId == null || !ProviderPlanFingerprint.isValid(planFingerprint)) {
            return null
        }
        if (!ConfidenceCheckpointCodec.isValidDigest(inputDigest)) return null
        val point = (runCatching {
            ScanResumeStore(context).loadRequestDetailed(requestId)
        }.getOrNull() as? ResumeReadState.Available)?.point ?: return null
        if (point.checkpointOwnerId != ownerId ||
            point.planFingerprint != planFingerprint ||
            ScanCheckpointStage.ScoringRelationshipConfidence !in point.completedCheckpointStages
        ) return null
        val checkpoint = point.relationshipConfidenceCheckpoint ?: return null
        if (checkpoint.requestId != requestId ||
            checkpoint.ownerId != ownerId ||
            checkpoint.planFingerprint != planFingerprint ||
            checkpoint.inputDigest != inputDigest
        ) return null
        return ConfidenceCheckpointCodec.decode(checkpoint.confidenceJson)
    }

    private fun buildRelationshipConfidenceCheckpoint(
        requestId: String?,
        ownerId: String?,
        planFingerprint: String?,
        inputDigest: String,
        confidence: Map<String, RelationshipConfidence>
    ): RelationshipConfidenceStageCheckpoint? {
        if (requestId == null || ownerId == null) return null
        val plan = planFingerprint?.takeIf(ProviderPlanFingerprint::isValid) ?: return null
        if (!ConfidenceCheckpointCodec.isValidDigest(inputDigest)) return null
        val confidenceJson = ConfidenceCheckpointCodec.encode(confidence) ?: return null
        return RelationshipConfidenceStageCheckpoint(
            requestId = requestId,
            planFingerprint = plan,
            ownerId = ownerId,
            capturedAtEpochMillis = System.currentTimeMillis(),
            inputDigest = inputDigest,
            confidenceJson = confidenceJson
        )
    }

    private fun loadReusableAttackPathsCheckpoint(
        context: Context,
        requestId: String?,
        ownerId: String?,
        planFingerprint: String?,
        inputDigest: String
    ): List<AttackPathFinder.AttackPath>? {
        if (requestId == null || ownerId == null || !ProviderPlanFingerprint.isValid(planFingerprint)) {
            return null
        }
        if (!AttackPathsCheckpointCodec.isValidDigest(inputDigest)) return null
        val point = (runCatching {
            ScanResumeStore(context).loadRequestDetailed(requestId)
        }.getOrNull() as? ResumeReadState.Available)?.point ?: return null
        if (point.checkpointOwnerId != ownerId ||
            point.planFingerprint != planFingerprint ||
            ScanCheckpointStage.TracingAttackPaths !in point.completedCheckpointStages
        ) return null
        val checkpoint = point.attackPathsCheckpoint ?: return null
        if (checkpoint.requestId != requestId ||
            checkpoint.ownerId != ownerId ||
            checkpoint.planFingerprint != planFingerprint ||
            checkpoint.inputDigest != inputDigest
        ) return null
        return AttackPathsCheckpointCodec.decode(checkpoint.attackPathsJson)
    }

    private fun buildAttackPathsCheckpoint(
        requestId: String?,
        ownerId: String?,
        planFingerprint: String?,
        inputDigest: String,
        paths: List<AttackPathFinder.AttackPath>
    ): AttackPathsStageCheckpoint? {
        if (requestId == null || ownerId == null) return null
        val plan = planFingerprint?.takeIf(ProviderPlanFingerprint::isValid) ?: return null
        if (!AttackPathsCheckpointCodec.isValidDigest(inputDigest)) return null
        val attackPathsJson = AttackPathsCheckpointCodec.encode(paths) ?: return null
        return AttackPathsStageCheckpoint(
            requestId = requestId,
            planFingerprint = plan,
            ownerId = ownerId,
            capturedAtEpochMillis = System.currentTimeMillis(),
            inputDigest = inputDigest,
            attackPathsJson = attackPathsJson
        )
    }

    internal fun findingsFromBreachCheckpoint(
        checkpoint: BreachStageCheckpoint
    ): List<Finding> = checkpoint.results.flatMap(::findingsFromBreachResult)

    private fun BreachStageCheckpointResult.toDigest(): BreachDigest = BreachDigest(
        email = email,
        breachCount = breachCount,
        sources = sources,
        note = note
    )

    private fun findingsFromBreachResult(result: BreachStageCheckpointResult): List<Finding> =
        when {
            result.breachCount > 0 -> listOf(
                Finding(
                    type = FindingType.Email,
                    value = result.email,
                    sourceUrl = null,
                    evidenceSnippet = "Appears in ${result.breachCount} known breach(es): ${result.breachTitles.take(5).joinToString()}",
                    confidence = 0.95f,
                    risk = RiskLevel.High,
                    remediation = "Change passwords for this address, enable MFA, and monitor for account takeover."
                )
            )
            result.publicHitCount > 0 -> listOf(
                Finding(
                    type = FindingType.SensitiveSnippet,
                    value = result.email,
                    sourceUrl = result.publicEvidenceUrls.firstOrNull(),
                    evidenceSnippet = "Public index mentions this email (${result.publicHitCount} hit(s)). ${result.note.orEmpty()}".trim(),
                    confidence = 0.55f,
                    risk = RiskLevel.Medium,
                    remediation = "Review indexed pages and request de-indexing where personal data is exposed."
                )
            )
            else -> emptyList()
        }

    private fun safeBreachNote(value: String?): String? {
        val normalized = value
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.take(ScanResumeStore.MAX_BREACH_NOTE_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (normalized.any { it.code < 0x20 || it.code == 0x7f }) return null
        if (Regex("(?i)(password|passwd|token|secret|cookie|authorization|bearer|api[ -_]?key)")
                .containsMatchIn(normalized)
        ) return null
        return normalized
    }

    private suspend fun runFaceConsistency(
        context: Context,
        input: IdentityInput,
        profileResults: List<ProfileScanResult>
    ): List<FaceConsistencyMatch> {
        if (input.selfieUri.isNullOrBlank()) return emptyList()
        val modelStore = FaceEmbeddingModelStore(context)
        if (!modelStore.ensureModelAvailable()) return emptyList()

        val downloader = ProfileImageDownloader(context)
        val profileImages = FaceConsistencyChecker.buildProfileImageMap(
            profileResults = profileResults,
            download = { url -> downloader.download(url) }
        )
        if (profileImages.isEmpty()) return emptyList()
        return FaceConsistencyChecker(FaceEmbeddingService(context)).checkSelfieVsProfiles(input, profileImages)
    }

    internal fun faceFindingsFromMatches(matches: List<FaceConsistencyMatch>): List<Finding> =
        matches.mapNotNull { match ->
            val warning = match.warning.lowercase()
            val isHigh = warning.contains("high visual similarity")
            val isReview = warning.contains("review-range")
            if (!isHigh && !isReview) return@mapNotNull null
            Finding(
                type = FindingType.ImageConsistency,
                value = "Face similarity ${(match.similarityScore * 100).toInt()}% vs ${match.profileUrl}",
                sourceUrl = match.profileUrl,
                evidenceSnippet = match.warning,
                confidence = match.similarityScore.coerceIn(0f, 1f),
                risk = if (isHigh) RiskLevel.High else RiskLevel.Medium,
                remediation = "Confirm ownership of this profile and avoid reusing the same avatar/selfie across accounts."
            )
        }

    internal fun buildEvidenceSnapshot(
        input: IdentityInput,
        profileResults: List<ProfileScanResult>,
        pluginCollection: EvidenceCollection,
        findings: List<Finding>,
        retrievedAtEpochMillis: Long? = null
    ): EvidenceCollection {
        val scannerEvidence = profileResults.toEvidenceCollection(input, retrievedAtEpochMillis)
        return EvidenceCollection(
            evidence = (
                scannerEvidence.evidence +
                    pluginCollection.evidence +
                    buildEvidence(input, findings, retrievedAtEpochMillis)
                ).distinctBy { it.id },
            relationships = EvidenceRelationshipPolicy.normalize(
                scannerEvidence.relationships + pluginCollection.relationships
            )
        ).withResolvedRelationshipEvidence()
    }

    internal fun buildAiAnalysisSnapshot(
        input: IdentityInput,
        profileResults: List<ProfileScanResult>,
        findings: List<Finding>,
        evidence: List<Evidence>,
        graph: EntityGraph,
        faceMatches: List<FaceConsistencyMatch> = emptyList(),
        breachDigests: List<BreachDigest> = emptyList(),
        exposure: ExposureEngine.ExposureResult? = null
    ): AiAnalysisSnapshot = AiAnalysisSnapshot.from(
        input = input,
        profileResults = profileResults,
        findings = findings,
        evidence = evidence,
        graph = graph,
        faceMatches = faceMatches,
        breachDigests = breachDigests,
        exposure = exposure
    )

    internal fun buildEvidence(
        input: IdentityInput,
        findings: List<Finding>,
        retrievedAtEpochMillis: Long? = null
    ): List<Evidence> {
        val seeds = buildList {
            input.emails.filter { it.isNotBlank() }.forEach {
                add(Evidence(id = "seed:email:$it", kind = EvidenceKind.Email, value = it, confidence = 1.0f))
            }
            input.phones.filter { it.isNotBlank() }.forEach {
                add(Evidence(id = "seed:phone:$it", kind = EvidenceKind.Phone, value = it, confidence = 1.0f))
            }
            (listOfNotNull(input.primaryUsername) + input.usernames)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .forEach {
                    add(Evidence(id = "seed:username:$it", kind = EvidenceKind.Username, value = it, confidence = 1.0f))
                }
        }
        val fromFindings = findings.map { it.toEvidence(retrievedAtEpochMillis) }
        return (seeds + fromFindings).distinctBy { it.kind to it.value.lowercase() }
    }
}
