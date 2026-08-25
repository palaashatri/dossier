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
import io.dossier.app.domain.evidence.AttackPathFinder
import io.dossier.app.domain.evidence.ConfidenceEngine
import io.dossier.app.domain.evidence.EmailDomainContributor
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.ExposureEngine
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.evidence.SharedDomainContributor
import io.dossier.app.domain.evidence.SharedIdentifierContributor
import io.dossier.app.domain.evidence.UsernameSimilarityContributor
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

/**
 * Observable scan/session state shared by the Compose UI and durable WorkManager
 * execution. Scan results remain transient unless the user explicitly saves a case.
 */
object ScanSession {
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
            scanHistory = _scanHistory.value
        )
    }

    /** Restores a transient encrypted background result after process death. */
    fun restoreFromCase(case: DossierCase) {
        val migrated = CaseEvidenceIdMigration.migrate(case)
        EvidenceRuntimeCache.replaceCaseEvidence(migrated.evidenceRecords, migrated.evidenceRelationships)
        MediaIntelligenceSession.restoreFor(migrated.input, migrated.mediaIntelligence)
        _scanHistory.value = migrated.scanHistory
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
            val digests = runBreachChecks(
                context = context,
                emails = inputToUse.emails,
                deepResearch = deepResearch,
                findingsOut = allFindings
            )
            currentCoroutineContext().ensureActive()
            _breachDigests.value = digests
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
                )
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
            val graph = EntityGraphBuilder.build(
                input = inputToUse,
                profileResults = scanResults,
                findings = allFindings,
                faceMatches = faceMatches,
                breachDigests = digests,
                evidence = evidence,
                relationships = relationships
            )
            _entityGraph.value = graph
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
                )
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
            _relationshipConfidence.value = ConfidenceEngine(
                contributors = listOf(
                    UsernameSimilarityContributor(),
                    EmailDomainContributor(),
                    SharedIdentifierContributor(usernameSeeds),
                    SharedDomainContributor()
                )
            ).score(graph, evidence)
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = true,
                output = ScanStageOutput(itemCount = _relationshipConfidence.value.size)
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
            _attackPaths.value = AttackPathFinder().findPaths(graph, _relationshipConfidence.value)
            checkpointStage(
                context,
                requestId,
                checkpointOwnerId,
                checkpointGeneration,
                ScanCheckpointStage.TracingAttackPaths,
                completed = true,
                output = ScanStageOutput(itemCount = _attackPaths.value.size)
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
        payloads: List<ScanPayloadSummary> = emptyList()
    ) {
        if (requestId == null || ownerId == null || generation == null) return
        when (
            val result = ScanCoordinatorRuntime.recordCheckpoint(
                context = context,
                requestId = requestId,
                ownerId = ownerId,
                generation = generation,
                stage = stage,
                completed = completed,
                output = output,
                payloads = payloads
            )
        ) {
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
        deepResearch: Boolean,
        findingsOut: MutableList<Finding>
    ): List<BreachDigest> {
        val cleanEmails = emails.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (cleanEmails.isEmpty()) return emptyList()

        return try {
            val results = BreachCheckService(context).checkEmails(
                cleanEmails,
                hibpApiKey = null,
                deepResearch = deepResearch
            )
            results.map { result ->
                val sources = buildList {
                    addAll(result.breaches.map { it.title.ifBlank { it.name } })
                    addAll(result.publicEvidence.map { it.url }.filter { it.isNotBlank() })
                }.distinct()
                val breachCount = result.breaches.size
                val publicHits = result.publicEvidence.size

                if (breachCount > 0) {
                    findingsOut += Finding(
                        type = FindingType.Email,
                        value = result.email,
                        sourceUrl = null,
                        evidenceSnippet = "Appears in $breachCount known breach(es): ${result.breaches.take(5).joinToString { it.title.ifBlank { it.name } }}",
                        confidence = 0.95f,
                        risk = RiskLevel.High,
                        remediation = "Change passwords for this address, enable MFA, and monitor for account takeover."
                    )
                } else if (publicHits > 0) {
                    findingsOut += Finding(
                        type = FindingType.SensitiveSnippet,
                        value = result.email,
                        sourceUrl = result.publicEvidence.firstOrNull()?.url,
                        evidenceSnippet = "Public index mentions this email ($publicHits hit(s)). ${result.error ?: ""}".trim(),
                        confidence = 0.55f,
                        risk = RiskLevel.Medium,
                        remediation = "Review indexed pages and request de-indexing where personal data is exposed."
                    )
                }

                BreachDigest(
                    email = result.email,
                    breachCount = breachCount,
                    sources = sources,
                    note = result.error
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
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
        )
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
