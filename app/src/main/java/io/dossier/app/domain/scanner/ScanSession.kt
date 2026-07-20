package io.dossier.app.domain.scanner

import android.content.Context
import android.net.Uri
import io.dossier.app.data.ai.AiInsightService
import io.dossier.app.data.breach.BreachCheckService
import io.dossier.app.data.face.FaceEmbeddingModelStore
import io.dossier.app.data.face.ProfileImageDownloader
import io.dossier.app.data.local.ProfileConsistencyCache
import io.dossier.app.domain.ai.LocalAiModelType
import io.dossier.app.domain.face.FaceConsistencyChecker
import io.dossier.app.domain.face.FaceEmbeddingService
import io.dossier.app.domain.graph.EntityGraphBuilder
import io.dossier.app.domain.model.*
import io.dossier.app.domain.evidence.*
import io.dossier.app.domain.evidence.ExposureEngine.ExposureResult
import io.dossier.app.domain.evidence.AttackPathFinder.AttackPath
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.remediation.RemediationProvider
import io.dossier.app.domain.remediation.RemediationItem
import io.dossier.app.domain.risk.RiskScorer
import io.dossier.app.domain.username.UsernameVariantGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val _relationshipConfidence =
        MutableStateFlow<Map<String, RelationshipConfidence>>(emptyMap())
    val relationshipConfidence: StateFlow<Map<String, RelationshipConfidence>> =
        _relationshipConfidence

    private val _exposure = MutableStateFlow<ExposureResult?>(null)
    val exposure: StateFlow<ExposureResult?> = _exposure

    private val _attackPaths = MutableStateFlow<List<AttackPath>>(emptyList())
    val attackPaths: StateFlow<List<AttackPath>> = _attackPaths

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

    // M16: memory guard — count of findings dropped because the hard cap was hit.
    private val _memoryDropped = MutableStateFlow(0)
    val memoryDropped: StateFlow<Int> = _memoryDropped

    // M16: scans run on a dedicated scope so they can be cancelled cooperatively
    // (e.g. user hits Cancel, or the app is reset). The job is stored so a
    // cancel action anywhere can abort an in-flight scan.
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
    private var scanJob: Job? = null

    /** Launches the scan on the internal scope and tracks the job for cancellation. */
    fun startScan(context: Context, input: IdentityInput, deepResearch: Boolean = false) {
        // M16 (resumable): persist just the resume point — opt-in, local only.
        ScanResumeStore(context).save(input, deepResearch)
        scanJob?.cancel()
        scanJob = scope.launch {
            executeScan(context, input, deepResearch = deepResearch)
        }
    }

    /** M16 (resumable): returns the last scan's input + deep-research flag, if any. */
    fun loadResumePoint(context: Context): Pair<IdentityInput, Boolean>? =
        ScanResumeStore(context).load()

    /** M16 (resumable): clears the persisted resume point. */
    fun clearResumePoint(context: Context) {
        ScanResumeStore(context).clear()
    }

    /** Cooperatively cancels an in-flight scan. Partial results are kept. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        _progressText.value = "SCAN_CANCELLED"
    }

    // Persistent, observable Deep Research toggle — shown on all search panels
    // (Identity + Reverse Image Lookup). When on, the scan/lookup follows linked
    // personal sites and mines richer context. Unlike the old one-shot flag, this
    // stays set across re-runs until the user turns it off.
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

    /**
     * Snapshot of report-exportable session state after a scan.
     */
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

    /**
     * Builds a persistable [DossierCase] from the current session state. The
     * case is only written to disk when [saveCase] is called explicitly — Dossier
     * stays in-memory by default (Principle 4).
     */
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

    /** Persists the current case to local app storage. Returns the saved case, or null on failure. */
    fun saveCase(context: Context): DossierCase? {
        val case = buildCase() ?: return null
        val ok = CaseStore(context).save(case)
        return if (ok) case else null
    }

    suspend fun executeScan(context: Context, input: IdentityInput, deepResearch: Boolean = false) = withContext(Dispatchers.IO) {
        // HONESTY: no mock selfie is generated. If the user didn't supply one,
        // face comparison is skipped entirely (the report shows an honest
        // "no selfie provided" message instead of fabricating one).
        val inputToUse = input

        _currentInput.value = inputToUse
        _isScanning.value = true
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


        val cache = ProfileConsistencyCache(context)
        cache.clearAll()

        try {
            _progressText.value = "DISCOVERING_USERNAMES..."
            val piiExtractor = PiiExtractor()
            val variantGenerator = UsernameVariantGenerator()
            val profileScanner = ProfileScanner(context, piiExtractor, variantGenerator)
            
            val scanResults = profileScanner.scanIdentity(inputToUse, deepResearch = deepResearch)
            _profileScanResults.value = scanResults
            
            val allFindings = mutableListOf<Finding>()
            scanResults.forEach { result ->
                if (result.exists) {
                    allFindings.addAll(result.findings)
                }
            }


            _progressText.value = "COMPARING_FACE_CONSISTENCY..."
            val faceMatches = runFaceConsistency(
                context = context,
                input = inputToUse,
                profileResults = scanResults
            )
            _faceConsistencyMatches.value = faceMatches
            allFindings.addAll(faceFindingsFromMatches(faceMatches))

            // Breach check (best-effort) — never fails the scan.
            _progressText.value = "CHECKING_BREACH_EXPOSURE..."
            val digests = runBreachChecks(
                context = context,
                emails = inputToUse.emails,
                deepResearch = deepResearch,
                findingsOut = allFindings
            )
            _breachDigests.value = digests

            _progressText.value = "BUILDING_ENTITY_GRAPH..."
            // M6/native: prefer the scanner's own Evidence emission (profile +
            // PII + asserted relationships) as the primary Evidence source,
            // merged with plugin evidence and the adapter-bridged findings.
            val pluginCollection = runPlugins(inputToUse)
            val scannerEvidence = profileScanner.toEvidenceCollection(scanResults, inputToUse)
            val evidence = (scannerEvidence.evidence + pluginCollection.evidence + buildEvidence(inputToUse, allFindings))
                .distinctBy { it.id }
            val graph = EntityGraphBuilder.build(
                input = inputToUse,
                profileResults = scanResults,
                findings = allFindings,
                faceMatches = faceMatches,
                breachDigests = digests,
                evidence = evidence,
                relationships = (scannerEvidence.relationships + pluginCollection.relationships)
                    .distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
            )
            _entityGraph.value = graph

            _progressText.value = "SCORING_RELATIONSHIP_CONFIDENCE..."
            val usernameSeeds = (listOfNotNull(inputToUse.primaryUsername) + inputToUse.usernames)
                .filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
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
            val riskScorer = RiskScorer()
            val overallRisk = riskScorer.score(allFindings)
            _riskLevel.value = overallRisk

            val remediationProvider = RemediationProvider()
            _remediationTips.value = remediationProvider.getGlobalTips(allFindings)
            _remediationItems.value = remediationProvider.getStructuredTips(allFindings)

            _progressText.value = "COMPILING_EXPOSURE_SCORES..."
            _exposure.value = ExposureEngine().score(allFindings, digests)

            val distinctFindings = allFindings.distinctBy { it.type.name + it.value + it.sourceUrl }
            val capped = MemoryGuard.cap(distinctFindings)
            _memoryDropped.value = capped.droppedCount
            if (capped.droppedCount > 0) {
                _progressText.value = "MEMORY_LIMIT: ${
                    capped.droppedCount
                } findings omitted (cap ${MemoryGuard.MAX_FINDINGS})"
            }
            _findings.value = capped.retained

            _progressText.value = "GENERATING_AI_SUMMARY..."
            try {
                _aiSummary.value = AiInsightService(context).summarizeDossier(
                    input = inputToUse,
                    profileResults = scanResults,
                    findings = _findings.value
                )
            } catch (e: Exception) {
                e.printStackTrace()
        _aiSummary.value = null
        _memoryDropped.value = 0

            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Surface a diagnostic so a scan failure is never silently empty.
            // Whatever partial results exist are kept; this message tells the
            // user the scan hit an error rather than genuinely finding nothing.
            if (_findings.value.isEmpty() && _profileScanResults.value.isEmpty()) {
                _findings.value = listOf(
                    Finding(
                        type = FindingType.SensitiveSnippet,
                        value = "Scan interrupted",
                        sourceUrl = null,
                        evidenceSnippet = "The scan hit an unexpected error: ${e.localizedMessage ?: e.javaClass.simpleName}. Partial results, if any, are shown. Re-run or try Deep Research.",
                        confidence = 1.0f,
                        risk = RiskLevel.Medium,
                        remediation = "Check network connectivity and retry. If it persists, the target site may be blocking automated access."
                    )
                )
            }
        } finally {
            _isScanning.value = false
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
        placeImageUri = null

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
        val cleanEmails = emails.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (cleanEmails.isEmpty()) return emptyList()

        return try {
            val service = BreachCheckService(context)
            val results = service.checkEmails(cleanEmails, hibpApiKey = null, deepResearch = deepResearch)
            results.map { result ->
                val sources = buildList {
                    addAll(result.breaches.map { it.title.ifBlank { it.name } })
                    addAll(result.publicEvidence.map { it.url }.filter { it.isNotBlank() })
                }.distinct()
                val breachCount = result.breaches.size
                val publicHits = result.publicEvidence.size

                if (breachCount > 0) {
                    findingsOut.add(
                        Finding(
                            type = FindingType.Email,
                            value = result.email,
                            sourceUrl = null,
                            evidenceSnippet = "Appears in $breachCount known breach(es): ${result.breaches.take(5).joinToString { it.title.ifBlank { it.name } }}",
                            confidence = 0.95f,
                            risk = RiskLevel.High,
                            remediation = "Change passwords for this address, enable MFA, and monitor for account takeover."
                        )
                    )
                } else if (publicHits > 0) {
                    findingsOut.add(
                        Finding(
                            type = FindingType.SensitiveSnippet,
                            value = result.email,
                            sourceUrl = result.publicEvidence.firstOrNull()?.url,
                            evidenceSnippet = "Public index mentions this email ($publicHits hit(s)). ${result.error ?: ""}".trim(),
                            confidence = 0.55f,
                            risk = RiskLevel.Medium,
                            remediation = "Review indexed pages and request de-indexing where personal data is exposed."
                        )
                    )
                }

                BreachDigest(
                    email = result.email,
                    breachCount = breachCount,
                    sources = sources,
                    note = result.error
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        if (!modelStore.ensureModelAvailable()) {
            // Bundled model missing from APK assets — report stays empty.
            return emptyList()
        }

        val downloader = ProfileImageDownloader(context)
        val profileImages = FaceConsistencyChecker.buildProfileImageMap(
            profileResults = profileResults,
            download = { url -> downloader.download(url) }
        )
        if (profileImages.isEmpty()) return emptyList()

        return FaceConsistencyChecker(FaceEmbeddingService(context))
            .checkSelfieVsProfiles(input, profileImages)
    }

    /**
     * Elevates only calibrated review/high face scores into formal findings.
     * Uncalibrated cosine scores remain visible in the report log but do not
     * change risk scoring until a matching calibration sidecar is imported.
     */
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

    /**
     * Builds the [Evidence] list that feeds the [ConfidenceEngine]. Starts from
     * the identity seeds (emails/phones/usernames) and the scanner findings,
     * both converted through the lossless [Finding.toEvidence] adapter. This is
     * the bridge that lets the parallel Evidence model observe what scanners
     * already produced, without rewriting the scanners themselves.
     */
    internal fun buildEvidence(input: IdentityInput, findings: List<Finding>): List<Evidence> {
        val seeds = buildList {
            input.emails.filter { it.isNotBlank() }.forEach {
                add(Evidence(id = "seed:email:$it", kind = EvidenceKind.Email, value = it, confidence = 1.0f))
            }
            input.phones.filter { it.isNotBlank() }.forEach {
                add(Evidence(id = "seed:phone:$it", kind = EvidenceKind.Phone, value = it, confidence = 1.0f))
            }
            (listOfNotNull(input.primaryUsername) + input.usernames)
                .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.forEach {
                    add(Evidence(id = "seed:username:$it", kind = EvidenceKind.Username, value = it, confidence = 1.0f))
                }
        }
        val fromFindings = findings.map { it.toEvidence() }
        return (seeds + fromFindings).distinctBy { it.kind to it.value.lowercase() }
    }
}
