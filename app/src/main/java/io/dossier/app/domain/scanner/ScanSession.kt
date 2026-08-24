package io.dossier.app.domain.scanner

import android.content.Context
import android.net.Uri
import io.dossier.app.data.ai.AiInsightService
import io.dossier.app.data.breach.BreachCheckService
import io.dossier.app.data.face.FaceCorrelationSessionPolicy
import io.dossier.app.data.face.FaceEmbeddingModelStore
import io.dossier.app.data.face.ProfileImageDownloader
import io.dossier.app.data.local.ProfileConsistencyCache
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.evidence.AttackPathFinder
import io.dossier.app.domain.evidence.ConfidenceEngine
import io.dossier.app.domain.evidence.EmailDomainContributor
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
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
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.PlaceScanResult
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.remediation.RemediationItem
import io.dossier.app.domain.remediation.RemediationProvider
import io.dossier.app.domain.risk.RiskScorer
import io.dossier.app.domain.username.UsernameVariantGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal class ScanExecutionException : Exception()

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

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText

    private val _memoryDropped = MutableStateFlow(0)
    val memoryDropped: StateFlow<Int> = _memoryDropped

    private var scanApplicationContext: Context? = null

    /**
     * Enqueues one unique durable scan. Navigating away from the scan screen no
     * longer cancels work; Android/WorkManager owns execution and restart policy.
     */
    suspend fun startScan(context: Context, input: IdentityInput, deepResearch: Boolean = false) {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            scanApplicationContext = appContext

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

    /** Called under BackgroundScanManager's lifecycle lock after owner publication. */
    internal fun markBackgroundScheduled(input: IdentityInput, deepResearch: Boolean) {
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
    fun cancelScan() {
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
        return DossierCase(
            createdAt = createdAt,
            subjectName = input.fullName.trim().ifBlank { input.primaryUsername ?: "UNKNOWN SUBJECT" },
            input = input,
            findings = _findings.value,
            profileResults = _profileScanResults.value,
            faceMatches = _faceConsistencyMatches.value,
            entityGraph = _entityGraph.value,
            breachDigests = _breachDigests.value,
            riskLevel = _riskLevel.value,
            exposure = _exposure.value,
            attackPaths = _attackPaths.value,
            relationshipConfidence = _relationshipConfidence.value,
            aiSummary = _aiSummary.value
        )
    }

    /** Restores a transient encrypted background result after process death. */
    fun restoreFromCase(case: DossierCase) {
        _currentInput.value = case.input
        _findings.value = case.findings
        _profileScanResults.value = case.profileResults
        _faceConsistencyMatches.value = case.faceMatches
        _entityGraph.value = case.entityGraph
        _breachDigests.value = case.breachDigests
        _riskLevel.value = case.riskLevel
        _exposure.value = case.exposure
        _attackPaths.value = case.attackPaths
        _relationshipConfidence.value = case.relationshipConfidence
        _aiSummary.value = case.aiSummary
        val remediationProvider = RemediationProvider()
        _remediationTips.value = remediationProvider.getGlobalTips(case.findings)
        _remediationItems.value = remediationProvider.getStructuredTips(case.findings)
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

    suspend fun executeScan(
        context: Context,
        input: IdentityInput,
        deepResearch: Boolean = false,
        requestId: String? = null
    ) = withContext(Dispatchers.IO) {
        val inputToUse = input
        _currentInput.value = inputToUse
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
            _progressText.value = "DISCOVERING_USERNAMES..."
            WhatsMyNameCatalog.install(context)
            val piiExtractor = PiiExtractor()
            val variantGenerator = UsernameVariantGenerator()
            val profileScanner = ProfileScanner(context, piiExtractor, variantGenerator)

            val scanResults = profileScanner.scanIdentity(inputToUse, deepResearch = deepResearch, requestId = requestId)
            currentCoroutineContext().ensureActive()
            _profileScanResults.value = scanResults

            val allFindings = mutableListOf<Finding>()
            scanResults.filter { it.exists }.forEach { allFindings.addAll(it.findings) }

            _progressText.value = "COMPARING_FACE_CONSISTENCY..."
            val faceMatches = runFaceConsistency(context, inputToUse, scanResults)
            currentCoroutineContext().ensureActive()
            _faceConsistencyMatches.value = faceMatches
            allFindings.addAll(faceFindingsFromMatches(faceMatches))

            _progressText.value = "CHECKING_BREACH_EXPOSURE..."
            val digests = runBreachChecks(
                context = context,
                emails = inputToUse.emails,
                deepResearch = deepResearch,
                findingsOut = allFindings
            )
            currentCoroutineContext().ensureActive()
            _breachDigests.value = digests

            _progressText.value = "BUILDING_ENTITY_GRAPH..."
            val pluginCollection = runPlugins(inputToUse)
            currentCoroutineContext().ensureActive()
            val scannerEvidence = profileScanner.toEvidenceCollection(scanResults, inputToUse)
            val evidence = (
                scannerEvidence.evidence +
                    pluginCollection.evidence +
                    buildEvidence(inputToUse, allFindings)
                ).distinctBy { it.id }
            val relationships = (scannerEvidence.relationships + pluginCollection.relationships)
                .distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
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

            _progressText.value = "TRACING_ATTACK_PATHS..."
            _attackPaths.value = AttackPathFinder().findPaths(graph, _relationshipConfidence.value)

            _progressText.value = "COMPILING_EXPOSURE_LEVELS..."
            _riskLevel.value = RiskScorer().score(allFindings)
            val remediationProvider = RemediationProvider()
            _remediationTips.value = remediationProvider.getGlobalTips(allFindings)
            _remediationItems.value = remediationProvider.getStructuredTips(allFindings)

            _progressText.value = "COMPILING_EXPOSURE_SCORES..."
            _exposure.value = ExposureEngine().score(allFindings, digests)

            val distinctFindings = allFindings.distinctBy { it.type.name + it.value + it.sourceUrl }
            val capped = MemoryGuard.cap(distinctFindings)
            _memoryDropped.value = capped.droppedCount
            _findings.value = capped.retained
            if (capped.droppedCount > 0) {
                _progressText.value = "MEMORY_LIMIT: ${capped.droppedCount} findings omitted (cap ${MemoryGuard.MAX_FINDINGS})"
            }

            _progressText.value = "GENERATING_AI_SUMMARY..."
            val summary = try {
                AiInsightService(context).summarizeDossier(
                    input = inputToUse,
                    profileResults = scanResults,
                    findings = _findings.value
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            _aiSummary.value = summary
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw ScanExecutionException()
        } finally {
            cache.close()
        }
    }

    fun purgeSession(context: Context) {
        _currentInput.value = null
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

    internal fun buildEvidence(input: IdentityInput, findings: List<Finding>): List<Evidence> {
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
        val fromFindings = findings.map { it.toEvidence() }
        return (seeds + fromFindings).distinctBy { it.kind to it.value.lowercase() }
    }
}
