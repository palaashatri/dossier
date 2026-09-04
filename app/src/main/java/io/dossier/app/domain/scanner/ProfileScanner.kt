package io.dossier.app.domain.scanner

import io.dossier.app.data.platform.PLATFORMS
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.data.platform.resolveProfileUrl
import io.dossier.app.data.web.PublicImageSearchService
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderExecutionRuntime
import io.dossier.app.domain.discovery.ProviderPlanFingerprint
import io.dossier.app.domain.discovery.ProviderResponseClassifier
import io.dossier.app.domain.discovery.ProviderResponseDecision
import io.dossier.app.domain.discovery.ProviderRendererPolicy
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ExtractionRules
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.*
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.username.UsernameVariant
import io.dossier.app.domain.username.UsernameVariantGenerator
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.TimeUnit
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.selects.select


class ProfileScanner(
    private val context: Context,
    private val piiExtractor: PiiExtractor,
    private val variantGenerator: UsernameVariantGenerator
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val providerRuntime = ProviderExecutionRuntime(client)

    suspend fun scanIdentity(input: IdentityInput, deepResearch: Boolean = false, requestId: String? = null): List<ProfileScanResult> {
        val scanId = ScanCoordinatorRuntime.claimProviderScanId()
        val results = mutableListOf<ProfileScanResult>()

        // Public search/image observations are resumable only for a durable
        // WorkManager request.  The catalog-plan commitment prevents a cache
        // created under a changed provider plan from being mistaken for this
        // scan's output; interactive scans remain process-local as before.
        val payloadStore = requestId
            ?.takeIf(BackgroundScanWorker::isCanonicalUuid)
            ?.let { durableRequestId ->
                runCatching {
                    val plan = ProviderCatalogV2.plan(DiscoveryScanPreferences.selectedMode.value)
                    PublicDiscoveryPayloadStore(
                        context = context,
                        requestId = durableRequestId,
                        planFingerprint = ProviderPlanFingerprint.forPlan(plan)
                    )
                }.getOrNull()
            }

        val usernames = mutableSetOf<String>()
        input.primaryUsername?.let { if (it.isNotBlank()) usernames.add(it) }
        input.usernames.forEach { if (it.isNotBlank()) usernames.add(it) }

        val allCandidates = mutableListOf<UsernameCandidate>()

        // Email local-parts always seed username variants (even when explicit
        // usernames are also present). Critical for email-only scans.
        val emailBasedCandidates: List<UsernameVariant> =
            variantGenerator.generateFromEmails(input.emails)

        // If no usernames/primary username provided but name is, derive candidates from name.
        // Also derive name candidates when usernames are empty even if emails exist —
        // emails alone still benefit from name-based fan-out when a name is present.
        val nameBasedCandidates: List<UsernameVariant> = if (usernames.isEmpty() && input.fullName.isNotBlank()) {
            variantGenerator.generateFromName(input.fullName)
        } else {
            emptyList()
        }

        // Username-provided candidates
        usernames.forEach { baseUser ->
            val variants = variantGenerator.generate(baseUser)
            variants.forEach { variant ->
                addPlatformCandidates(
                    allCandidates = allCandidates,
                    variant = variant,
                    baseConfidence = if (variant.type == UsernameMatchType.Exact) 1.0f else 0.8f
                )
            }
        }

        // Email-local candidates (slightly below explicit username confidence)
        emailBasedCandidates.forEach { variant ->
            addPlatformCandidates(
                allCandidates = allCandidates,
                variant = variant,
                baseConfidence = when (variant.type) {
                    UsernameMatchType.Exact -> 0.85f
                    UsernameMatchType.FuzzyVariant -> 0.65f
                    else -> 0.75f
                }
            )
        }

        // Name-derived candidates (slightly lower base confidence since inferred)
        val isSingleWordName = input.fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size <= 1
        nameBasedCandidates.forEach { variant ->
            PLATFORMS.forEach { template ->
                if (template.shouldFetchByDefault) {
                    // For single-word names, only check platforms where short usernames
                    // are meaningful — skip Instagram, TikTok, YouTube, etc. to avoid
                    // matching random strangers who happen to have common usernames.
                    if (isSingleWordName && !listOf(Platform.GitHub, Platform.Reddit).contains(template.platform)) {
                        return@forEach
                    }
                    val profileUrl = template.urlPattern.replace("{username}", variant.username)
                    val baseConf = if (isSingleWordName) {
                        when (variant.type) {
                            UsernameMatchType.Exact -> 0.35f
                            else -> 0.20f
                        }
                    } else {
                        when (variant.type) {
                            UsernameMatchType.Exact -> 0.9f       // e.g., "janedoe" from "Jane Doe"
                            UsernameMatchType.DotVariant -> 0.85f
                            UsernameMatchType.UnderscoreVariant -> 0.85f
                            UsernameMatchType.HyphenVariant -> 0.8f
                            UsernameMatchType.FuzzyVariant -> 0.65f
                            UsernameMatchType.CaseVariant -> 0.75f
                        }
                    }
                    allCandidates.add(
                        UsernameCandidate(
                            username = variant.username,
                            platform = template.platform,
                            url = profileUrl,
                            matchType = variant.type,
                            confidence = baseConf,
                            providerId = template.providerId
                        )
                    )
                }
            }
        }

        input.profileUrls.forEach { url ->
            if (url.isNotBlank()) {
                var normalizedUrl = url.trim()
                if (!normalizedUrl.startsWith("http://", ignoreCase = true) && 
                    !normalizedUrl.startsWith("https://", ignoreCase = true)) {
                    normalizedUrl = "https://$normalizedUrl"
                }

                val resolved = resolveProfileUrl(normalizedUrl)
                // Unknown host → Website, never default to GitHub
                val platform = resolved?.platform ?: Platform.Website
                val username = resolved?.username ?: normalizedUrl.split("/").lastOrNull { it.isNotBlank() } ?: "unknown"
                val providerId = resolved?.providerId
                
                allCandidates.add(
                    UsernameCandidate(
                        username = username,
                        platform = platform,
                        url = normalizedUrl,
                        matchType = UsernameMatchType.Exact,
                        confidence = 1.0f,
                        providerId = providerId
                    )
                )
            }
        }

        // Cap the initial fan-out so worst-case scan time stays bounded. Sort by
        // confidence (user-supplied + exact-match candidates first) and take the
        // top N. Pivot discovery can still surface additional handles afterwards.
        val uniqueCandidates = allCandidates
            .distinctBy { ProfileScanCheckpointStore.canonicalCandidateKey(it.url) }
            .sortedByDescending { it.confidence }
            .take(MAX_INITIAL_CANDIDATES)

        // ---- Pass 1: initial fan-out over name-variant / user-supplied candidates.
        val initialResults = executeInitialPass(
            uniqueCandidates = uniqueCandidates,
            input = input,
            scanId = scanId,
            requestId = requestId,
            deepResearch = deepResearch,
            onRecovery = { summary ->
                ScanCoordinatorRuntime.onRecoveryDiagnostics(
                    scanId = scanId,
                    stage = ScanCheckpointStage.DiscoveringUsernames,
                    checkpointAvailable = summary.checkpointAvailable,
                    reusedCount = summary.reusedCount,
                    rerunCount = summary.rerunCount
                )
            }
        )

        // The recursive pass is backed by a request-scoped encrypted frontier
        // when WorkManager supplied a canonical request ID. A process-local
        // frontier keeps direct interactive scans bounded without inventing
        // persistence for requests that cannot be resumed safely.
        val frontierConfig = PivotFrontierConfig.forScan(deepResearch)
        val frontierRequestId = requestId
            ?.takeIf(BackgroundScanWorker::isCanonicalUuid)
            ?: EPHEMERAL_FRONTIER_REQUEST_ID
        val frontierStoreAttempt = requestId
            ?.takeIf(BackgroundScanWorker::isCanonicalUuid)
            ?.let { runCatching { PivotFrontierStore(context, it) } }
        val frontierStore = frontierStoreAttempt?.getOrNull()
        val pivotFrontier = restorePivotFrontierOrFail(
            requestId = frontierRequestId,
            config = frontierConfig,
            persisted = when {
                frontierStoreAttempt == null -> null
                frontierStoreAttempt.isFailure -> PivotFrontierLoadResult.Unavailable
                else -> frontierStore!!.loadDetailed(frontierConfig)
            }
        )
        pivotFrontier.markVisited(uniqueCandidates.map { it.url })
        publishPivotDiagnostics(scanId, pivotFrontier)

        // ---- Pass 2: bounded recursive pivot discovery. For every profile
        // confirmed in the preceding depth, read its rendered content/links for
        // OTHER handles the user self-disclosed and drain exactly that frontier
        // depth. The configured maximum depth, rather than a fixed hop count,
        // controls how far a scan may recurse.
        //
        // FAIL-SAFE: the entire pivot pass is wrapped so ANY failure here never
        // destroys the Pass-1 results. A broken pivot must not make the scan
        // return empty — Pass 1's findings are always surfaced.
        val pivotResults: List<ProfileScanResult> = try {
            drainPivotFrontier(
                initialResults = initialResults,
                uniqueCandidates = uniqueCandidates,
                input = input,
                deepResearch = deepResearch,
                scanId = scanId,
                frontier = pivotFrontier,
                frontierStore = frontierStore
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        } finally {
            // Persist admitted/rejected state even when a non-cancellation
            // failure aborts one depth. Cancellation is rethrown above, while
            // the durable queue remains resumable on the next request.
            frontierStore?.save(pivotFrontier)
        }

        // ---- Pass 3: public-search discovery. This broadens coverage beyond
        // deterministic username templates by querying public indexes for the
        // audited name/handles/emails and site-specific sources (including
        // Reddit + 4chan). These hits are review candidates, not verified account
        // ownership, so they are surfaced with verified=false.
        val publicSearchResults: List<ProfileScanResult> = payloadStore?.load(ScanPayloadStage.PublicSearch)
            ?: try {
                val confirmedUrls = (initialResults + pivotResults)
                    .filter { it.exists && it.verified }
                    .map { PublicSearchDiscoveryService.canonicalUrlKey(it.candidate.url) }
                    .toSet()
                val discovered = runPublicSearchPass(input, confirmedUrls, deepResearch)
                // Persist an empty list only after the pass completed normally;
                // exceptions below remain a cache miss and retry honestly.
                payloadStore?.save(ScanPayloadStage.PublicSearch, discovered)
                discovered
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }

        // ---- Pass 4: public image-index discovery. This searches image indexes
        // by identity terms only; it does not upload the user's selfie or perform
        // face recognition.
        val publicImageResults: List<ProfileScanResult> = payloadStore?.load(ScanPayloadStage.PublicImage)
            ?: try {
                val discovered = runPublicImagePass(input, publicSearchResults, deepResearch)
                payloadStore?.save(ScanPayloadStage.PublicImage, discovered)
                discovered
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }

        return initialResults + pivotResults + publicSearchResults + publicImageResults
    }

    private suspend fun executeInitialPass(
        uniqueCandidates: List<UsernameCandidate>,
        input: IdentityInput,
        scanId: ScanId,
        requestId: String?,
        deepResearch: Boolean,
        onRecovery: (ProfileInitialPassExecutor.RecoverySummary) -> Unit = {}
    ): List<ProfileScanResult> {
        val planFingerprint = if (requestId != null && BackgroundScanWorker.isCanonicalUuid(requestId)) {
            ProfileScanCheckpointStore.planFingerprint(input, deepResearch, uniqueCandidates)
        } else null

        val checkpointStore = if (requestId != null && planFingerprint != null) {
            runCatching {
                ProfileScanCheckpointStore(context, requestId, planFingerprint)
            }.getOrNull()
        } else null

        return ProfileInitialPassExecutor.execute(
            candidates = uniqueCandidates,
            checkpoint = checkpointStore,
            queueMiss = { candidate -> queueProviderCandidate(candidate, scanId) },
            fetchMiss = { candidate ->
                fetchAndParse(candidate, input, provenance = null, scanId = scanId)
            },
            onRecovery = onRecovery
        )
    }

    /**
     * Isolated pivot pass — extracted so it can be wrapped in a fail-safe
     * try/catch by the caller without entangling Pass-1 logic.
     */
    private suspend fun runPivotPass(
        seedResults: List<ProfileScanResult>,
        alreadyScannedUrls: Set<String>,
        input: IdentityInput,
        deepResearch: Boolean,
        remainingBudget: Int,
        scanId: ScanId,
        frontier: BoundedPivotFrontier,
        frontierStore: PivotFrontierStore?,
        depth: Int
    ): List<ProfileScanResult> {
        if (remainingBudget <= 0) return emptyList()
        val scannedUrls = alreadyScannedUrls.mapNotNull(BoundedPivotFrontier::canonicalUrlKey).toMutableSet()
        frontier.markVisited(scannedUrls)
        seedResults.filter { it.exists && it.verified }.forEach { confirmed ->
            if (frontier.pendingAtDepth(remainingBudget, depth).size >= remainingBudget) return@forEach
            val sourceLabel = confirmed.candidate.platform.name
            val pivots = HandleExtractor.extract(
                profileText = confirmed.extractedText,
                profileLinks = confirmed.links,
                sourceUrl = confirmed.candidate.url,
                alreadyScannedUrls = scannedUrls + frontier.visitedUrls,
                sourcePlatformLabel = sourceLabel,
                depth = depth,
                maxDepth = frontier.config.maxDepth,
                onRejected = { rejection ->
                    frontier.recordRejected(
                        key = rejection.candidateUrl?.let(BoundedPivotFrontier::canonicalUrlKey).orEmpty(),
                        normalizedValue = rejection.normalizedValue,
                        candidateUrl = rejection.candidateUrl,
                        sourceUrl = confirmed.candidate.url,
                        depth = depth,
                        signalType = rejection.signalType,
                        reason = rejection.reason
                    )
                    publishPivotDiagnostics(
                        scanId = scanId,
                        frontier = frontier,
                        decision = frontier.decisionDiagnostics.lastOrNull()
                    )
                }
            )
            pivots.forEach { pc ->
                if (frontier.pendingAtDepth(remainingBudget, depth).size >= remainingBudget) return@forEach
                val offer = frontier.offer(
                    candidate = pc.candidate,
                    depth = depth,
                    signalType = pc.signalType,
                    confidence = pc.candidate.confidence,
                    sourceUrl = confirmed.candidate.url,
                    provenance = pc.provenance,
                    admissionExplanation = pc.admissionExplanation
                )
                publishPivotDiagnostics(
                    scanId = scanId,
                    frontier = frontier,
                    decision = when (offer) {
                        is PivotOffer.Admitted -> frontier.decisionDiagnostics.lastOrNull()
                        is PivotOffer.Rejected -> offer.diagnostic
                    }
                )
                if (offer is PivotOffer.Admitted) {
                    scannedUrls += offer.entry.key
                }
            }

            // Deep Research: follow personal-website links self-disclosed on the
            // profile, read their pages for MORE handles, and pivot from those
            // too. (deanonymizer's website link-follower.) Bounded to keep
            // runtime sane.
            if (deepResearch && frontier.pendingAtDepth(remainingBudget, depth).size < remainingBudget) {
                val websiteFollower = io.dossier.app.data.web.WebsiteLinkFollower(context)
                val personalSites = confirmed.links
                    .filter { link ->
                        link.startsWith("http", ignoreCase = true) &&
                            io.dossier.app.data.platform.resolveProfileUrl(link) == null &&
                            !link.equals(confirmed.candidate.url, ignoreCase = true)
                    }
                    .distinctBy { it.lowercase() }
                    .take(MAX_WEBSITE_FOLLOWS)

                personalSites.forEach { siteUrl ->
                    try {
                        val followed = websiteFollower.follow(siteUrl, scanId)
                        if (followed.text.isBlank() && followed.links.isEmpty()) return@forEach
                        val sitePivots = HandleExtractor.extract(
                            profileText = followed.text,
                            profileLinks = followed.links,
                            sourceUrl = siteUrl,
                            alreadyScannedUrls = scannedUrls + frontier.visitedUrls,
                            sourcePlatformLabel = "$sourceLabel → website",
                            depth = depth,
                            maxDepth = frontier.config.maxDepth,
                            onRejected = { rejection ->
                                frontier.recordRejected(
                                    key = rejection.candidateUrl?.let(BoundedPivotFrontier::canonicalUrlKey).orEmpty(),
                                    normalizedValue = rejection.normalizedValue,
                                    candidateUrl = rejection.candidateUrl,
                                    sourceUrl = siteUrl,
                                    depth = depth,
                                    signalType = rejection.signalType,
                                    reason = rejection.reason
                                )
                                publishPivotDiagnostics(
                                    scanId = scanId,
                                    frontier = frontier,
                                    decision = frontier.decisionDiagnostics.lastOrNull()
                                )
                            }
                        )
                        sitePivots.forEach { pc ->
                            if (frontier.pendingAtDepth(remainingBudget, depth).size >= remainingBudget) return@forEach
                            val offer = frontier.offer(
                                candidate = pc.candidate,
                                depth = depth,
                                signalType = pc.signalType,
                                confidence = pc.candidate.confidence,
                                sourceUrl = siteUrl,
                                provenance = pc.provenance,
                                admissionExplanation = pc.admissionExplanation
                            )
                            publishPivotDiagnostics(
                                scanId = scanId,
                                frontier = frontier,
                                decision = when (offer) {
                                    is PivotOffer.Admitted -> frontier.decisionDiagnostics.lastOrNull()
                                    is PivotOffer.Rejected -> offer.diagnostic
                                }
                            )
                            if (offer is PivotOffer.Admitted) {
                                scannedUrls += offer.entry.key
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // One public website failure does not abort other bounded pivots.
                    }
                }
            }
        }

        frontierStore?.save(frontier)
        val pending = frontier.pendingAtDepth(remainingBudget, depth)
        if (pending.isEmpty()) return emptyList()

        pending.forEach { queueProviderCandidate(it.candidate, scanId) }

        val fetchPivot: suspend (PivotFrontierEntry) -> ProfileScanResult = { entry ->
            fetchAndParse(
                entry.candidate,
                input,
                provenance = entry.provenance,
                scanId = scanId
            )
        }
        return collectPivotFetches(
            pending = pending,
            frontier = frontier,
            frontierStore = frontierStore,
            fetch = fetchPivot,
            onCompleted = { _, _ -> publishPivotDiagnostics(scanId, frontier) }
        )
    }

    /**
     * Drain each configured frontier depth in order. Results fetched at depth N
     * become seeds for depth N+1 only when they are verified, preventing weak
     * or unverified pages from causing recursive false-positive cascades.
     *
     * The shared candidate budget is decremented by completed fetches. Pending
     * entries loaded from the encrypted request checkpoint are drained at their
     * persisted depth, so an interrupted scan resumes without replaying an
     * earlier hop or silently dropping admitted work.
     */
    private suspend fun drainPivotFrontier(
        initialResults: List<ProfileScanResult>,
        uniqueCandidates: List<UsernameCandidate>,
        input: IdentityInput,
        deepResearch: Boolean,
        scanId: ScanId,
        frontier: BoundedPivotFrontier,
        frontierStore: PivotFrontierStore?
    ): List<ProfileScanResult> {
        val scanned = uniqueCandidates
            .mapNotNull { url -> BoundedPivotFrontier.canonicalUrlKey(url.url) }
            .toMutableSet()
        var seeds = initialResults
        var remainingBudget = minOf(MAX_PIVOT_CANDIDATES, frontier.config.maxTotalPivots)
        val results = mutableListOf<ProfileScanResult>()

        for (depth in 1..frontier.config.maxDepth) {
            if (remainingBudget <= 0) break
            val fetched = runPivotPass(
                seedResults = seeds,
                alreadyScannedUrls = scanned,
                input = input,
                deepResearch = deepResearch,
                remainingBudget = remainingBudget,
                scanId = scanId,
                frontier = frontier,
                frontierStore = frontierStore,
                depth = depth
            )
            results += fetched
            fetched.forEach { result ->
                BoundedPivotFrontier.canonicalUrlKey(result.candidate.url)?.let(scanned::add)
            }
            remainingBudget = (remainingBudget - fetched.size).coerceAtLeast(0)
            seeds = verifiedPivotSeeds(fetched)
        }

        return results
    }

    /**
     * Public search pass for indexed evidence that template scans miss. Search
     * results are intentionally marked unverified because search engines prove a
     * page is indexed, not that the page belongs to the audited identity.
     */
    private suspend fun runPublicSearchPass(
        input: IdentityInput,
        alreadyConfirmedUrls: Set<String>,
        deepResearch: Boolean
    ): List<ProfileScanResult> {
        val service = PublicSearchDiscoveryService(context)
        val discovered = service.discover(input, deepResearch)
        if (discovered.isEmpty()) return emptyList()

        return discovered
            .mapNotNull { searchResult ->
                val result = buildPublicSearchProfileResult(searchResult, input)
                val urlKey = PublicSearchDiscoveryService.canonicalUrlKey(result.candidate.url)
                if (urlKey in alreadyConfirmedUrls) null else result
            }
            .distinctBy { PublicSearchDiscoveryService.canonicalUrlKey(it.candidate.url) }
    }

    private fun buildPublicSearchProfileResult(
        searchResult: PublicSearchDiscoveryService.PublicSearchResult,
        input: IdentityInput
    ): ProfileScanResult {
        val resolved = resolveProfileUrl(searchResult.url)
        val candidateUrl = resolved?.url ?: searchResult.url
        val platform = resolved?.platform ?: Platform.Website
        val username = resolved?.username ?: hostLabel(searchResult.url)
        val snippetText = listOf(searchResult.title, searchResult.snippet)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { searchResult.url }

        val findings = mutableListOf<Finding>()
        val confidenceSignals = mutableListOf(
            "Indexed by ${searchResult.source} for query: ${searchResult.query}"
        )
        if (resolved != null) {
            confidenceSignals.add("Search result URL matches known ${resolved.platform.name} profile pattern")
            findings.add(
                Finding(
                    type = FindingType.PlausibleProfileMatch,
                    value = candidateUrl,
                    sourceUrl = candidateUrl,
                    evidenceSnippet = "Public search candidate from ${searchResult.source}: ${searchResult.title}".take(220),
                    confidence = searchResult.score,
                    risk = if (searchResult.score >= 0.70f) RiskLevel.Medium else RiskLevel.Low,
                    remediation = "Open and review this indexed profile candidate before treating it as yours."
                )
            )
        }

        findings.add(
            Finding(
                type = FindingType.PublicSearchEvidence,
                value = searchResult.title.ifBlank { candidateUrl },
                sourceUrl = candidateUrl,
                evidenceSnippet = buildString {
                    append("${searchResult.source} result for ${searchResult.query}")
                    if (searchResult.snippet.isNotBlank()) append(": ${searchResult.snippet}")
                }.take(280),
                confidence = searchResult.score,
                risk = when {
                    searchResult.score >= 0.75f -> RiskLevel.Medium
                    else -> RiskLevel.Low
                },
                remediation = "Review this indexed result and remove or de-index public personal details where possible."
            )
        )

        if (snippetText.isNotBlank()) {
            findings.addAll(
                piiExtractor.extract(snippetText, candidateUrl, input)
                    .map { piiFinding ->
                        piiFinding.copy(
                            confidence = (piiFinding.confidence * searchResult.score).coerceAtLeast(0.35f),
                            remediation = "This appeared in a public search snippet. Review the source page and request removal or de-indexing if it exposes personal data."
                        )
                    }
            )
        }

        return ProfileScanResult(
            candidate = UsernameCandidate(
                username = username,
                platform = platform,
                url = candidateUrl,
                matchType = UsernameMatchType.FuzzyVariant,
                confidence = searchResult.score
            ),
            exists = true,
            httpStatus = null,
            displayName = searchResult.title.ifBlank { null },
            bio = searchResult.snippet.ifBlank { null },
            links = listOf(candidateUrl),
            extractedText = snippetText.take(1000),
            findings = findings.distinctBy { it.type.name + it.value + it.sourceUrl },
            confidenceSignals = confidenceSignals,
            verified = false,
            verificationStatus = if (resolved != null) {
                "Plausible public-search candidate — review manually"
            } else {
                "Indexed public-search evidence — review manually"
            },
            provenance = "public search via ${searchResult.source}"
        )
    }

    private fun hostLabel(url: String): String = try {
        URI(url).host?.removePrefix("www.") ?: "web"
    } catch (e: Exception) {
        "web"
    }

    private suspend fun runPublicImagePass(
        input: IdentityInput,
        publicSearchResults: List<ProfileScanResult>,
        deepResearch: Boolean
    ): List<ProfileScanResult> {
        val service = PublicImageSearchService(context)
        val discovered = service.discover(input, deepResearch)
        if (discovered.isEmpty()) return emptyList()

        val alreadySurfaced = publicSearchResults
            .map { PublicSearchDiscoveryService.canonicalUrlKey(it.candidate.url) }
            .toSet()

        return discovered
            .mapNotNull { imageResult ->
                val result = buildPublicImageProfileResult(imageResult)
                val sourceKey = PublicSearchDiscoveryService.canonicalUrlKey(result.candidate.url)
                if (sourceKey in alreadySurfaced && result.profileImageUrl == null) null else result
            }
            .distinctBy { result ->
                PublicImageSearchService.canonicalImageKey(
                    result.profileImageUrl.orEmpty(),
                    result.candidate.url
                )
            }
    }

    private fun buildPublicImageProfileResult(
        imageResult: PublicImageSearchService.PublicImageResult
    ): ProfileScanResult {
        val thumbnail = imageResult.thumbnailUrl ?: imageResult.imageUrl
        val title = imageResult.title.ifBlank { "Public image result" }
        val sourceHost = hostLabel(imageResult.sourcePageUrl)
        val finding = Finding(
            type = FindingType.PublicImageEvidence,
            value = title,
            sourceUrl = imageResult.sourcePageUrl,
            evidenceSnippet = "${imageResult.source} image result for ${imageResult.query}. Image URL: ${imageResult.imageUrl}".take(300),
            confidence = imageResult.score,
            risk = if (imageResult.score >= 0.65f) RiskLevel.Medium else RiskLevel.Low,
            remediation = "Review the source page and remove or de-index public photos or avatars you do not want associated with this identity."
        )

        return ProfileScanResult(
            candidate = UsernameCandidate(
                username = sourceHost,
                platform = Platform.Website,
                url = imageResult.sourcePageUrl,
                matchType = UsernameMatchType.FuzzyVariant,
                confidence = imageResult.score
            ),
            exists = true,
            httpStatus = null,
            displayName = title,
            bio = "Public image-index result from ${imageResult.source}",
            profileImageUrl = thumbnail,
            links = listOf(imageResult.sourcePageUrl, imageResult.imageUrl),
            extractedText = title,
            findings = listOf(finding),
            confidenceSignals = listOf(
                "Public image index matched query: ${imageResult.query}",
                "No selfie bytes uploaded; no face recognition performed"
            ),
            verified = false,
            verificationStatus = "Public image-search evidence — review manually",
            provenance = "public image search via ${imageResult.source}"
        )
    }

    // Concurrency limit for the (main-thread) embedded browser renders.
    private val webviewSemaphore = Semaphore(2)

    // Bounds total pivot candidates across hops so the pass can't run away.
    private val MAX_PIVOT_CANDIDATES = 30
    private val EPHEMERAL_FRONTIER_REQUEST_ID = "00000000-0000-4000-8000-000000000000"
    // Bounds the Deep Research website link-following per confirmed profile.
    private val MAX_WEBSITE_FOLLOWS = 5
    // Bounds the initial fan-out (name-variants × platforms) so a long name with
    // many variants doesn't produce 100+ candidates. Sorted by confidence first.
    private val MAX_INITIAL_CANDIDATES = 80

    private fun addPlatformCandidates(
        allCandidates: MutableList<UsernameCandidate>,
        variant: UsernameVariant,
        baseConfidence: Float
    ) {
        PLATFORMS.forEach { template ->
            if (template.shouldFetchByDefault) {
                val profileUrl = template.urlPattern.replace("{username}", variant.username)
                allCandidates.add(
                    UsernameCandidate(
                        username = variant.username,
                        platform = template.platform,
                        url = profileUrl,
                        matchType = variant.type,
                        confidence = baseConfidence,
                        providerId = template.providerId
                    )
                )
            }
        }
    }

    private fun queueProviderCandidate(candidate: UsernameCandidate, scanId: ScanId) {
        val catalogProviderId = candidate.providerId ?: resolveProfileUrl(candidate.url)?.providerId
        val providerId = catalogProviderId?.takeIf { ProviderCatalogV2.findById(it) != null }
            ?: ProviderExecutionRuntime.uncataloguedProviderId(candidate.url)
            ?: return
        ScanCoordinatorRuntime.onProviderQueued(providerId, scanId)
    }

    /**
     * Declarative provider execution with fallback for unmapped candidates:
     *   1. For catalog-backed candidates, executes through [ProviderExecutionRuntime]
     *      enforcing concurrency, interval, timeout, retries, bounded body, and lifecycle events.
     *   2. Applies [ProviderResponseClassifier] to classify the HTTP observation.
     *   3. Truthful state isolation:
     *      - NotFound & SoftNotFound -> absent
     *      - AuthenticationRequired & AutomationChallenged -> unavailable (no WebView fallback)
     *      - RedirectedOutsideProvider -> unavailable (no adoption)
     *      - UnexpectedStatus & InvalidResponse -> unavailable
     *      - Present -> direct attribution or bounded SPA WebView render
     */
    private suspend fun fetchAndParse(
        candidate: UsernameCandidate,
        input: IdentityInput,
        provenance: String? = null,
        scanId: ScanId
    ): ProfileScanResult {
        val providerId = candidate.providerId ?: resolveProfileUrl(candidate.url)?.providerId
        val definition = providerId?.let { ProviderCatalogV2.findById(it) }

        if (definition != null) {
            val exec = providerRuntime.execute(definition, candidate.url, scanId)
            val httpStatus = exec.statusCode
            val decision = exec.decision

            when (decision.state) {
                ProviderVerificationState.NotFound -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "HTTP ${httpStatus ?: 404} — not found",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.NotFound
                    )
                }
                ProviderVerificationState.SoftNotFound -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Not found (soft-404)",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.SoftNotFound
                    )
                }
                ProviderVerificationState.AuthenticationRequired -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — public response requires authentication",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.AuthenticationRequired
                    )
                }
                ProviderVerificationState.AutomationChallenged -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — automation or human verification challenge",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.AutomationChallenged
                    )
                }
                ProviderVerificationState.RateLimited -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — provider rate limit is active",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.RateLimited
                    )
                }
                ProviderVerificationState.Timeout -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — provider request timed out",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.Timeout
                    )
                }
                ProviderVerificationState.NetworkUnavailable -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — provider network is unavailable",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.NetworkUnavailable
                    )
                }
                ProviderVerificationState.RedirectedOutsideProvider -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — redirected outside provider host",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.RedirectedOutsideProvider
                    )
                }
                ProviderVerificationState.UnexpectedStatus -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — unexpected status (HTTP ${httpStatus ?: "error"})",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.UnexpectedStatus
                    )
                }
                ProviderVerificationState.InvalidResponse -> {
                    return buildResult(
                        candidate = candidate,
                        exists = false,
                        verified = false,
                        verificationStatus = "Unverifiable — ${decision.explanation}",
                        httpStatus = httpStatus,
                        adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = definition.id,
                        providerVerificationState = ProviderVerificationState.InvalidResponse
                    )
                }
                ProviderVerificationState.Present -> {
                    val rawHtml = exec.bodyText
                    val doc = Jsoup.parse(rawHtml, candidate.url)
                    val text = doc.text()
                    val title = doc.title()

                    if (isAccessChallengePage(rawHtml, text)) {
                        return buildResult(
                            candidate = candidate,
                            exists = false,
                            verified = false,
                            verificationStatus = "Unverifiable — automation or login challenge",
                            httpStatus = httpStatus,
                            adjustedConfidence = 0.0f,
                            provenance = provenance,
                            providerId = definition.id,
                            providerVerificationState = ProviderVerificationState.AutomationChallenged
                        )
                    }

                    if (text.trim().length >= 300 &&
                        !isProfileNotFoundPage(rawHtml, text, title, candidate.username, candidate.platform)
                    ) {
                        val extracted = DeclarativeProfileExtractor.extract(
                            document = doc,
                            rules = definition.extractionRules,
                            fallbackTitle = title
                        )
                        if (isProfileBelongingToUser(candidate, text, extracted.displayName, input)) {
                            val extractedText = text
                            val confidenceSignals = mutableListOf("Direct HTTP 200 page access — real content")
                            return finalizeResult(
                                candidate = candidate,
                                exists = true,
                                verified = true,
                                verificationStatus = "✓ Verified (HTTP 200, direct page access)",
                                httpStatus = httpStatus,
                                displayName = extracted.displayName,
                                bio = extracted.bio,
                                profileImageUrl = extracted.profileImageUrl,
                                links = extracted.links.toMutableList(),
                                extractedText = extractedText,
                                findings = mutableListOf(),
                                confidenceSignals = confidenceSignals,
                                adjustedConfidenceIn = candidate.confidence,
                                input = input,
                                provenance = provenance,
                                providerId = definition.id,
                                providerVerificationState = ProviderVerificationState.Present
                            )
                        } else {
                            return buildSoftExistenceResult(
                                candidate = candidate,
                                httpStatus = httpStatus,
                                displayName = extracted.displayName,
                                extractedText = text,
                                verificationStatus = "Exists but not attributed to this identity — possible account",
                                provenance = provenance,
                                providerId = definition.id,
                                providerVerificationState = ProviderVerificationState.Present
                            )
                        }
                    }

                    // Short legitimate Present SPA response -> use bounded WebView renderer
                    check(ProviderRendererPolicy.allows(decision.state)) {
                        "Only a directly present provider response may use the renderer"
                    }
                    val render: WebViewScraper.Result = try {
                        webviewSemaphore.withPermit {
                            WebViewScraper(context).scrape(candidate.url)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        WebViewScraper.Result.Failed("Renderer error: ${t.localizedMessage}")
                    }

                    when (render) {
                        is WebViewScraper.Result.ChallengeDetected -> {
                            return buildResult(
                                candidate = candidate,
                                exists = false,
                                verified = false,
                                verificationStatus = "Unverifiable — ${render.reason}",
                                httpStatus = httpStatus,
                                adjustedConfidence = 0.0f,
                                provenance = provenance,
                                providerId = definition.id,
                                providerVerificationState = ProviderVerificationState.AutomationChallenged
                            )
                        }
                        is WebViewScraper.Result.TimedOut -> {
                            return buildResult(
                                candidate = candidate,
                                exists = false,
                                verified = false,
                                verificationStatus = "Unverifiable — render timed out",
                                httpStatus = httpStatus,
                                adjustedConfidence = 0.0f,
                                provenance = provenance,
                                providerId = definition.id,
                                providerVerificationState = ProviderVerificationState.InvalidResponse
                            )
                        }
                        is WebViewScraper.Result.Failed -> {
                            return buildResult(
                                candidate = candidate,
                                exists = false,
                                verified = false,
                                verificationStatus = "Unverifiable — ${render.reason}",
                                httpStatus = httpStatus,
                                adjustedConfidence = 0.0f,
                                provenance = provenance,
                                providerId = definition.id,
                                providerVerificationState = ProviderVerificationState.InvalidResponse
                            )
                        }
                        is WebViewScraper.Result.Rendered -> {
                            val docRender = Jsoup.parse(render.html, candidate.url)
                            val extractedText = render.text
                            val extracted = DeclarativeProfileExtractor.extract(
                                document = docRender,
                                rules = definition.extractionRules,
                                fallbackTitle = docRender.title().ifBlank { title }
                            )

                            if (isProfileNotFoundPage(render.html, extractedText, docRender.title(), candidate.username, candidate.platform)) {
                                return buildResult(
                                    candidate = candidate,
                                    exists = false,
                                    verified = false,
                                    verificationStatus = "Not found (rendered 404)",
                                    httpStatus = httpStatus,
                                    adjustedConfidence = 0.0f,
                                    provenance = provenance,
                                    providerId = definition.id,
                                    providerVerificationState = ProviderVerificationState.NotFound
                                )
                            }

                            if (isProfileBelongingToUser(candidate, extractedText, extracted.displayName, input)) {
                                return finalizeResult(
                                    candidate = candidate,
                                    exists = true,
                                    verified = true,
                                    verificationStatus = "✓ Verified in-browser",
                                    httpStatus = httpStatus,
                                    displayName = extracted.displayName,
                                    bio = extracted.bio,
                                    profileImageUrl = extracted.profileImageUrl,
                                    links = extracted.links.toMutableList(),
                                    extractedText = extractedText,
                                    findings = mutableListOf(),
                                    confidenceSignals = mutableListOf("Embedded browser render confirmed against DOM"),
                                    adjustedConfidenceIn = (candidate.confidence * 0.95f).coerceAtLeast(0.3f),
                                    input = input,
                                    provenance = provenance,
                                    providerId = definition.id,
                                    providerVerificationState = ProviderVerificationState.Present
                                )
                            } else {
                                return buildSoftExistenceResult(
                                    candidate = candidate,
                                    httpStatus = httpStatus,
                                    displayName = extracted.displayName,
                                    extractedText = extractedText,
                                    verificationStatus = "Exists but not attributed to this identity — possible account",
                                    provenance = provenance,
                                    providerId = definition.id,
                                    providerVerificationState = ProviderVerificationState.Present
                                )
                            }
                        }
                    }
                }
            }
        }

        // Generic fallback for candidate without a catalog-backed provider definition
        var okhttpTitle: String? = null
        var okhttpConfirmed = false
        var httpStatus: Int? = null
        var displayName: String? = null
        var bio: String? = null
        var profileImageUrl: String? = null
        val links = mutableListOf<String>()
        var extractedText = ""
        val findings = mutableListOf<Finding>()
        val confidenceSignals = mutableListOf<String>()
        var adjustedConfidence = candidate.confidence

        val fallbackDefinition = ProviderExecutionRuntime.uncataloguedProfileDefinition(candidate.url)
            ?: return buildResult(
                candidate, exists = false, verified = false,
                verificationStatus = "Unverifiable — profile URL is not a supported HTTP(S) URL",
                httpStatus = null, adjustedConfidence = 0.0f,
                provenance = provenance,
                providerVerificationState = ProviderVerificationState.InvalidResponse
            )

        try {
            val execution = providerRuntime.execute(
                provider = fallbackDefinition,
                url = candidate.url,
                scanId = scanId,
                // Preserve the legacy fallback's explicit 401/403 handling
                // while using the shared declarative classifier everywhere
                // else.  The status is an authorization boundary, not absence.
                classifier = { _, observation ->
                    if (observation.statusCode == 401 || observation.statusCode == 403) {
                        ProviderResponseDecision(
                            ProviderVerificationState.AuthenticationRequired,
                            "Public response requires authentication"
                        )
                    } else {
                        ProviderResponseClassifier.classify(fallbackDefinition, observation)
                    }
                }
            )
            httpStatus = execution.statusCode

            when (execution.decision.state) {
                ProviderVerificationState.NotFound -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "HTTP ${httpStatus ?: 404} — not found",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.NotFound
                )
                ProviderVerificationState.SoftNotFound -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Not found (soft-404)",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.SoftNotFound
                )
                ProviderVerificationState.AuthenticationRequired -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — public response requires authentication",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.AuthenticationRequired
                )
                ProviderVerificationState.AutomationChallenged -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — automation or login challenge",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.AutomationChallenged
                )
                ProviderVerificationState.RateLimited -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — provider rate limit is active",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.RateLimited
                )
                ProviderVerificationState.Timeout -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — provider request timed out",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.Timeout
                )
                ProviderVerificationState.NetworkUnavailable -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Offline — could not reach host",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.NetworkUnavailable
                )
                ProviderVerificationState.RedirectedOutsideProvider -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — redirected outside provider host",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = ProviderVerificationState.RedirectedOutsideProvider
                )
                ProviderVerificationState.UnexpectedStatus,
                ProviderVerificationState.InvalidResponse -> return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — ${execution.decision.explanation}",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id,
                    providerVerificationState = execution.decision.state
                )
                ProviderVerificationState.Present -> {
                    val html = execution.bodyText
                    val doc = Jsoup.parse(html, candidate.url)
                    val text = doc.text()
                    val title = doc.title()
                    if (isAccessChallengePage(html, text)) {
                        return buildResult(
                            candidate, exists = false, verified = false,
                            verificationStatus = "Unverifiable — automation or login challenge",
                            httpStatus = httpStatus, adjustedConfidence = 0.0f,
                            provenance = provenance,
                            providerId = fallbackDefinition.id,
                            providerVerificationState = ProviderVerificationState.AutomationChallenged
                        )
                    }
                    if (isStrongNotFoundPage(text, title, candidate.platform)) {
                        return buildResult(
                            candidate, exists = false, verified = false,
                            verificationStatus = "Not found (soft-404)",
                            httpStatus = httpStatus, adjustedConfidence = 0.0f,
                            provenance = provenance,
                            providerId = fallbackDefinition.id,
                            providerVerificationState = ProviderVerificationState.SoftNotFound
                        )
                    }
                    okhttpTitle = title

                    if (text.trim().length >= 300 &&
                        !isProfileNotFoundPage(html, text, title, candidate.username, candidate.platform)) {
                        if (isProfileBelongingToUser(candidate, text, title, input)) {
                            displayName = title.ifBlank { null }
                            bio = doc.select("meta[name=description]").attr("content").trim().ifBlank {
                                doc.select("p").firstOrNull()?.text()?.take(200)
                            }
                            profileImageUrl = extractProfileImageUrl(doc)
                            doc.select("a[href]").forEach { element ->
                                val linkUrl = element.attr("abs:href")
                                if (linkUrl.startsWith("http")) {
                                    links.add(linkUrl)
                                }
                            }
                            extractedText = text
                            confidenceSignals.add("Direct HTTP 200 page access — real content")
                            okhttpConfirmed = true
                        } else {
                            return buildSoftExistenceResult(
                                candidate = candidate,
                                httpStatus = httpStatus,
                                displayName = title.ifBlank { null },
                                extractedText = text,
                                verificationStatus = "Exists but not attributed to this identity — possible account",
                                provenance = provenance,
                                providerId = fallbackDefinition.id
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val state = when (e) {
                is java.net.SocketTimeoutException,
                is java.io.InterruptedIOException -> ProviderVerificationState.Timeout
                is java.net.UnknownHostException,
                is java.net.ConnectException -> ProviderVerificationState.NetworkUnavailable
                else -> ProviderVerificationState.InvalidResponse
            }
            val status = when (state) {
                ProviderVerificationState.Timeout -> "Unverifiable — provider request timed out"
                ProviderVerificationState.NetworkUnavailable -> "Offline — could not reach host"
                else -> "Unverifiable — provider request failed"
            }
            return buildResult(
                candidate, exists = false, verified = false,
                verificationStatus = status,
                httpStatus = null, adjustedConfidence = 0.0f,
                provenance = provenance,
                providerId = fallbackDefinition.id,
                providerVerificationState = state
            )
        }

        if (okhttpConfirmed) {
            return finalizeResult(
                candidate, exists = true, verified = true,
                verificationStatus = "✓ Verified (HTTP 200, direct page access)",
                httpStatus = httpStatus,
                displayName = displayName, bio = bio, profileImageUrl = profileImageUrl,
                links = links, extractedText = extractedText, findings = findings,
                confidenceSignals = confidenceSignals, adjustedConfidenceIn = adjustedConfidence,
                input = input, provenance = provenance,
                providerId = fallbackDefinition.id
            )
        }

        val render: WebViewScraper.Result = try {
            webviewSemaphore.withPermit {
                WebViewScraper(context).scrape(candidate.url)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            WebViewScraper.Result.Failed("Renderer error: ${t.localizedMessage}")
        }

        when (render) {
            is WebViewScraper.Result.ChallengeDetected -> {
                return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — ${render.reason}",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id
                )
            }
            is WebViewScraper.Result.TimedOut -> {
                return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — render timed out",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id
                )
            }
            is WebViewScraper.Result.Failed -> {
                return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — ${render.reason}",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f,
                    provenance = provenance,
                    providerId = fallbackDefinition.id
                )
            }
            is WebViewScraper.Result.Rendered -> {
                val doc = Jsoup.parse(render.html, candidate.url)
                extractedText = render.text

                if (isProfileNotFoundPage(render.html, extractedText, doc.title(), candidate.username, candidate.platform)) {
                    return buildResult(
                        candidate, exists = false, verified = false,
                        verificationStatus = "Not found (rendered 404)",
                        httpStatus = httpStatus, adjustedConfidence = 0.0f,
                        provenance = provenance,
                        providerId = fallbackDefinition.id
                    )
                }

                if (isProfileBelongingToUser(candidate, extractedText, doc.title(), input)) {
                    displayName = doc.title().ifBlank { okhttpTitle }
                    bio = doc.select("meta[name=description]").attr("content").trim().ifBlank {
                        doc.select("p").firstOrNull()?.text()?.take(200)
                    }
                    profileImageUrl = extractProfileImageUrl(doc)
                    doc.select("a[href]").forEach { element ->
                        val linkUrl = element.attr("abs:href")
                        if (linkUrl.startsWith("http")) {
                            links.add(linkUrl)
                        }
                    }
                    confidenceSignals.add("Embedded browser render confirmed against DOM")
                    adjustedConfidence = (adjustedConfidence * 0.95f).coerceAtLeast(0.3f)
                    return finalizeResult(
                        candidate, exists = true, verified = true,
                        verificationStatus = "✓ Verified in-browser",
                        httpStatus = httpStatus,
                        displayName = displayName, bio = bio, profileImageUrl = profileImageUrl,
                        links = links, extractedText = extractedText, findings = findings,
                        confidenceSignals = confidenceSignals, adjustedConfidenceIn = adjustedConfidence,
                        input = input, provenance = provenance,
                        providerId = fallbackDefinition.id
                    )
                } else {
                    return buildSoftExistenceResult(
                        candidate = candidate,
                        httpStatus = httpStatus,
                        displayName = doc.title().ifBlank { okhttpTitle },
                        extractedText = extractedText,
                        verificationStatus = "Exists but not attributed to this identity — possible account",
                        provenance = provenance,
                        providerId = fallbackDefinition.id
                    )
                }
            }
        }
    }

    /**
     * Shared confidence-boost + PII + finding-assembly for a confirmed profile.
     * Used by both the direct HTTP-confirmed path and the WebView-confirmed path so
     * they produce identical output structure.
     */
    private fun finalizeResult(
        candidate: UsernameCandidate,
        exists: Boolean,
        verified: Boolean,
        verificationStatus: String?,
        httpStatus: Int?,
        displayName: String?,
        bio: String?,
        profileImageUrl: String?,
        links: MutableList<String>,
        extractedText: String,
        findings: MutableList<Finding>,
        confidenceSignals: MutableList<String>,
        adjustedConfidenceIn: Float,
        input: IdentityInput,
        provenance: String?,
        providerId: String? = candidate.providerId,
        providerVerificationState: ProviderVerificationState? = null
    ): ProfileScanResult {
        var adjustedConfidence = adjustedConfidenceIn

        if (exists && extractedText.isNotBlank()) {
            val nameParts = input.fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val isSingleWordName = nameParts.size <= 1
            val containsFullName = input.fullName.isNotBlank() &&
                extractedText.contains(input.fullName, ignoreCase = true)
            val containsFirstName = nameParts.isNotEmpty() &&
                extractedText.contains(nameParts.first(), ignoreCase = true)
            val containsLastName = nameParts.size > 1 &&
                extractedText.contains(nameParts.last(), ignoreCase = true)

            // Boost confidence on real data signals
            if (containsFullName && !isSingleWordName) {
                adjustedConfidence = (adjustedConfidence + 0.15f).coerceAtMost(1.0f)
                confidenceSignals.add("Full name found in page content (+15%)")
            } else if (containsFirstName && containsLastName) {
                adjustedConfidence = (adjustedConfidence + 0.10f).coerceAtMost(1.0f)
                confidenceSignals.add("Both name parts found in page content (+10%)")
            } else if (containsFirstName && !isSingleWordName) {
                adjustedConfidence = (adjustedConfidence + 0.05f).coerceAtMost(1.0f)
                confidenceSignals.add("First name found in page content (+5%)")
            } else if (isSingleWordName && containsFirstName) {
                adjustedConfidence = (adjustedConfidence + 0.02f).coerceAtMost(1.0f)
                confidenceSignals.add("Single name found in page content (+2%)")
            }

            val titleContainsUsername = displayName?.contains(candidate.username, ignoreCase = true) == true
            if (titleContainsUsername) {
                adjustedConfidence = (adjustedConfidence + 0.08f).coerceAtMost(1.0f)
                confidenceSignals.add("Username matches page title (+8%)")
            }

            // Optional minor signal: slug happens to contain both name parts.
            if (nameParts.size > 1) {
                val slug = candidate.username.lowercase()
                if (slug.contains(nameParts.first().lowercase()) && slug.contains(nameParts.last().lowercase())) {
                    adjustedConfidence = (adjustedConfidence + 0.03f).coerceAtMost(1.0f)
                    confidenceSignals.add("Username slug contains both name parts (+3%)")
                }
            }

            val piiFindings = piiExtractor.extract(extractedText, candidate.url, input)
            findings.addAll(piiFindings)

            if (piiFindings.isNotEmpty()) {
                val piiBump = (piiFindings.size * 0.05f).coerceAtMost(0.20f)
                adjustedConfidence = (adjustedConfidence + piiBump).coerceAtMost(1.0f)
                confidenceSignals.add("${piiFindings.size} PII signals found (+${(piiBump * 100).toInt()}%)")
            }

            if (candidate.matchType != UsernameMatchType.Exact) {
                findings.add(
                    Finding(
                        type = FindingType.UsernameReuse,
                        value = candidate.username,
                        sourceUrl = candidate.url,
                        evidenceSnippet = "Matched username variant (${candidate.matchType}) on ${candidate.platform}",
                        confidence = adjustedConfidence,
                        risk = RiskLevel.Medium,
                        remediation = "Differentiate profile usernames to reduce account linkage correlation."
                    )
                )
            }

            findings.add(
                Finding(
                    type = FindingType.PlausibleProfileMatch,
                    value = candidate.url,
                    sourceUrl = candidate.url,
                    evidenceSnippet = "Public account found under candidate profile username: ${candidate.username} (Confidence: ${(adjustedConfidence * 100).toInt()}%)",
                    confidence = adjustedConfidence,
                    risk = if (adjustedConfidence > 0.85f) RiskLevel.High else RiskLevel.Low,
                    remediation = "Review settings for this account and limit public search indexing."
                )
            )
        }

        val finalFindings = findings.map { finding ->
            if (finding.type == FindingType.PlausibleProfileMatch) {
                finding.copy(
                    confidence = adjustedConfidence,
                    risk = when {
                        adjustedConfidence > 0.85f -> RiskLevel.High
                        adjustedConfidence > 0.70f -> RiskLevel.Medium
                        adjustedConfidence > 0.50f -> RiskLevel.Low
                        else -> RiskLevel.Low
                    }
                )
            } else {
                finding
            }
        }

        return ProfileScanResult(
            candidate = candidate.copy(confidence = adjustedConfidence, providerId = providerId ?: candidate.providerId),
            exists = exists,
            httpStatus = httpStatus,
            displayName = displayName,
            bio = bio,
            profileImageUrl = profileImageUrl,
            links = links.take(10),
            extractedText = extractedText.take(1000),
            findings = finalFindings,
            confidenceSignals = confidenceSignals,
            verified = verified,
            verificationStatus = verificationStatus,
            provenance = provenance,
            providerId = providerId ?: candidate.providerId,
            providerVerificationState = providerVerificationState
        )
    }

    /**
     * Page clearly exists (HTTP 200 substantial / rendered) but belonging failed.
     * Report as a possible account without high-risk PII or PlausibleProfileMatch.
     */
    private fun buildSoftExistenceResult(
        candidate: UsernameCandidate,
        httpStatus: Int?,
        displayName: String?,
        extractedText: String,
        verificationStatus: String,
        provenance: String?,
        providerId: String? = candidate.providerId,
        providerVerificationState: ProviderVerificationState? = null
    ): ProfileScanResult {
        val softConfidence = (candidate.confidence * 0.25f).coerceIn(0.1f, 0.35f)
        val softFinding = Finding(
            type = FindingType.PublicSearchEvidence,
            value = candidate.url,
            sourceUrl = candidate.url,
            evidenceSnippet = "Account appears to exist on ${candidate.platform.name} under \"${candidate.username}\" but could not be attributed to this identity.",
            confidence = softConfidence,
            risk = RiskLevel.Low,
            remediation = "Review this possible account manually. Do not treat it as confirmed ownership."
        )
        return ProfileScanResult(
            candidate = candidate.copy(confidence = softConfidence, providerId = providerId ?: candidate.providerId),
            exists = true,
            httpStatus = httpStatus,
            displayName = displayName,
            bio = null,
            profileImageUrl = null,
            links = emptyList(),
            extractedText = extractedText.take(500),
            findings = listOf(softFinding),
            confidenceSignals = listOf("Page exists but not attributed to identity"),
            verified = false,
            verificationStatus = verificationStatus,
            provenance = provenance,
            providerId = providerId ?: candidate.providerId,
            providerVerificationState = providerVerificationState
        )
    }

    /** Centralizes result construction for the early-return paths. */
    private fun buildResult(
        candidate: UsernameCandidate,
        exists: Boolean,
        verified: Boolean,
        verificationStatus: String,
        httpStatus: Int?,
        adjustedConfidence: Float,
        provenance: String? = null,
        providerId: String? = candidate.providerId,
        providerVerificationState: ProviderVerificationState? = null
    ): ProfileScanResult = ProfileScanResult(
        candidate = candidate.copy(confidence = adjustedConfidence, providerId = providerId ?: candidate.providerId),
        exists = exists,
        httpStatus = httpStatus,
        displayName = null,
        bio = null,
        profileImageUrl = null,
        links = emptyList(),
        extractedText = "",
        findings = emptyList(),
        confidenceSignals = emptyList(),
        verified = verified,
        verificationStatus = verificationStatus,
        provenance = provenance,
        providerId = providerId ?: candidate.providerId,
        providerVerificationState = providerVerificationState
    )

    private fun extractProfileImageUrl(doc: Document): String? {
        val metaSelectors = listOf(
            "meta[property=og:image]",
            "meta[name=og:image]",
            "meta[name=twitter:image]",
            "meta[property=twitter:image]",
            "meta[name=twitter:image:src]",
            "meta[property=twitter:image:src]",
            "link[rel=image_src]"
        )

        metaSelectors.forEach { selector ->
            val element = doc.select(selector).firstOrNull() ?: return@forEach
            val raw = element.attr("abs:content").ifBlank {
                element.attr("content")
            }.ifBlank {
                element.attr("abs:href")
            }.ifBlank {
                element.attr("href")
            }
            normalizeProfileImageUrl(raw)?.let { return it }
        }

        val imageSelectors = listOf(
            "img.avatar",
            "img[alt*=avatar]",
            "img[alt*=profile]",
            "img[src*=avatar]",
            "img[src*=profile]"
        )
        imageSelectors.forEach { selector ->
            val raw = doc.select(selector).firstOrNull()?.attr("abs:src").orEmpty()
            normalizeProfileImageUrl(raw)?.let { return it }
        }

        return null
    }

    private fun normalizeProfileImageUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        // Profile images must already be absolute or scheme-relative HTTP(S) URLs.
        if (!io.dossier.app.domain.util.UrlNormalizer.isHttpUrl(trimmed)) return null
        return io.dossier.app.domain.util.UrlNormalizer.stripFragment(
            io.dossier.app.domain.util.UrlNormalizer.ensureHttps(trimmed)
        )
    }

    /**
     * Conservative not-found check for the OkHttp pre-filter — only strong,
     * specific signals that are reliable on raw (possibly JS-shell) HTML.
     * Never drops SPA shells on content length / nav-text heuristics; those run
     * only in [isProfileNotFoundPage] against the rendered DOM.
     */
    private fun isStrongNotFoundPage(text: String, title: String, platform: Platform): Boolean {
        val lowerText = text.lowercase()
        val lowerTitle = title.lowercase()
        val strong = listOf(
            "page not found", "404 not found", "user not found", "profile not found",
            "account not found", "does not exist", "doesn't exist",
            "sorry, this page isn't available", "couldn't find this account",
            "this account could not be found", "sorry, that page doesn't exist",
            "sorry, that page doesn’t exist", "nobody on reddit goes by that name",
            "site not found", "blog not found"
        )
        if (strong.any { lowerText.contains(it) || lowerTitle.contains(it) }) return true
        // Title-only signals that are safe on raw HTML.
        if (platform == Platform.GitHub && (lowerTitle.contains("page not found") || lowerTitle == "404")) return true
        if (lowerTitle.contains("error 404") || lowerTitle.contains("not found -")) return true
        return false
    }

    private fun isProfileNotFoundPage(html: String, text: String, title: String, username: String, platform: Platform): Boolean {
        val lowerText = text.lowercase()
        val lowerTitle = title.lowercase()
        val lowerHtml = html.lowercase()
        val contentLen = text.trim().length

        // A page with substantial rendered content is almost certainly a real page,
        // not a 404/auth-wall (those are short). Gate the aggressive heuristics
        // behind short content so we stop false-positiving real public profiles
        // that happen to have a "Sign in" nav button (i.e. nearly all of them).
        val isSubstantial = contentLen >= 300

        // Strong, specific not-found phrases — these are reliable even on long pages.
        val strongNotFounds = listOf(
            "page not found",
            "404 not found",
            "user not found",
            "profile not found",
            "account not found",
            "does not exist",
            "doesn't exist",
            "sorry, this page isn't available",
            "this content isn't available",
            "couldn't find this account",
            "this account could not be found",
            "sorry, that page doesn't exist",
            "sorry, that page doesn’t exist",
            "nobody on reddit goes by that name",
            "site not found",
            "blog not found"
        )
        if (strongNotFounds.any { lowerText.contains(it) || lowerTitle.contains(it) }) {
            return true
        }

        // Platform-specific checks — conservative. Only fire on specific signals,
        // never on generic nav text like "Sign in" (present on every public page).
        when (platform) {
            Platform.GitHub -> {
                // GitHub 404 pages have a distinctive title. Don't match bare "404"
                // anywhere (a repo with 404 stars would false-positive).
                if (lowerTitle.contains("page not found") || lowerTitle == "404") return true
            }
            Platform.Instagram -> {
                if (lowerHtml.contains("sorry, this page isn't available")) return true
                if (!isSubstantial && lowerText.contains("isn't available")) return true
            }
            Platform.Facebook -> {
                if (!isSubstantial && (lowerText.contains("page isn't available") || lowerText.contains("content not found"))) return true
            }
            Platform.Reddit -> {
                if (lowerText.contains("nobody on reddit goes by that name")) return true
                if (!isSubstantial && lowerText.contains("page not found")) return true
            }
            Platform.TikTok -> {
                if (lowerText.contains("couldn't find this account") || lowerText.contains("this account could not be found")) return true
            }
            Platform.YouTube -> {
                if (!isSubstantial && lowerText.contains("404 not found")) return true
            }
            Platform.LinkedIn -> {
                // Real LinkedIn profile pages still show "Sign in" in nav — do NOT
                // flag on that. Only the authwall interstitial counts.
                if (lowerHtml.contains("authwall")) return true
            }
            Platform.Telegram -> {
                // Only flag not-found if the page rendered almost nothing AND lacks
                // any of the profile markers. A real t.me profile may render lightly.
                if (!isSubstantial &&
                    !lowerText.contains("send message") &&
                    !lowerText.contains("view in telegram") &&
                    !lowerText.contains("preview channel") &&
                    !lowerText.contains(username.lowercase())) {
                    return true
                }
            }
            else -> {}
        }

        // Auth-wall / challenge: only treat as a wall when the page is DOMINANTLY a
        // login/captcha interstitial — i.e. SHORT content + login keywords + the
        // username absent. A long public profile with a "Sign in" nav link is NOT a
        // wall. This is the fix for the zero-results regression.
        if (!isSubstantial) {
            val isLoginDominant = lowerText.contains("login") ||
                lowerText.contains("sign-in") ||
                lowerText.contains("signin") ||
                lowerText.contains("log in to") ||
                lowerHtml.contains("captcha") ||
                lowerHtml.contains("checkpoint") ||
                lowerHtml.contains("enable javascript")
            if (isLoginDominant && !lowerText.contains(username.lowercase())) {
                return true
            }
        }

        // Genuinely empty render (challenge/JS shell that produced nothing).
        if (contentLen < 60) {
            return true
        }

        return false
    }

    private fun isProfileBelongingToUser(
        candidate: UsernameCandidate,
        extractedText: String,
        displayName: String?,
        input: IdentityInput
    ): Boolean = belongsToUserPure(
        candidateUsername = candidate.username,
        candidateUrl = candidate.url,
        platform = candidate.platform,
        extractedText = extractedText,
        displayName = displayName,
        input = input
    )

    companion object {
        internal fun isAccessChallengePage(html: String, text: String): Boolean {
            val lowerHtml = html.lowercase()
            val lowerText = text.lowercase()
            val compactLength = text.count { !it.isWhitespace() }
            val hardHtmlMarkers = listOf(
                "cf-challenge",
                "g-recaptcha",
                "h-captcha",
                "data-sitekey",
                "authwall"
            )
            if (hardHtmlMarkers.any(lowerHtml::contains)) return true

            val challengeTextMarkers = listOf(
                "checking your browser",
                "verify you are human",
                "are you a robot",
                "unusual traffic",
                "attention required",
                "log in to continue",
                "sign in to continue"
            )
            return compactLength < 600 && challengeTextMarkers.any {
                lowerText.contains(it) || lowerHtml.contains(it)
            }
        }

        /**
         * Pure (no Context / no network) belonging decision, extracted so it can be
         * unit-tested.
         *
         * This is called only after direct or rendered content passes provider
         * existence checks. Page existence remains separate from subject ownership.
         *
         * Attribution paths (any one is sufficient):
         *  - user explicitly supplied the URL
         *  - full name / both name parts appear in the rendered page
         *  - a user-supplied email / phone / alias / location / org appears in the page
         *
         * A matching URL slug or username is candidate discovery evidence only.
         * It never verifies ownership without an independent page signal.
         */
        fun belongsToUserPure(
            candidateUsername: String,
            candidateUrl: String,
            @Suppress("UNUSED_PARAMETER") platform: Platform,
            extractedText: String,
            displayName: String?,
            input: IdentityInput
        ): Boolean {
            // If the URL was explicitly supplied by the user, we trust it's theirs.
            if (input.profileUrls.any { it.trim().equals(candidateUrl.trim(), ignoreCase = true) }) {
                return true
            }

            val text = (extractedText + " " + (displayName ?: "")).lowercase()
            val candidateNeedle = candidateUsername.trim().lowercase()
            val corroboratingText = if (candidateNeedle.length >= 2) {
                text.replace(candidateNeedle, " ")
            } else {
                text
            }
            val fullNameLower = input.fullName.trim().lowercase()
            val nameParts = input.fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val isSingleWordName = nameParts.size <= 1

            // 1. Check for full name match
            if (fullNameLower.isNotBlank() && corroboratingText.contains(fullNameLower)) {
                if (!isSingleWordName) return true
            }

            // 2. Check if both first and last name match (for multi-word names)
            if (nameParts.size > 1) {
                val first = nameParts.first().lowercase()
                val last = nameParts.last().lowercase()
                if (corroboratingText.contains(first) && corroboratingText.contains(last)) {
                    return true
                }
            }

            // 3. Check for any of the user's supplied emails or phones in the page text
            val hasMatchingEmail = input.emails.any { it.isNotBlank() && text.contains(it.lowercase()) }
            val hasMatchingPhone = input.phones.any { it.isNotBlank() && text.filter { c -> c.isDigit() }.contains(it.filter { c -> c.isDigit() }) }
            if (hasMatchingEmail || hasMatchingPhone) {
                return true
            }

            // 4. Check for any of the user's supplied aliases, locations, or organizations
            val hasMatchingAlias = input.aliases.any { it.isNotBlank() && text.contains(it.lowercase()) }
            val hasMatchingLoc = input.locations.any { it.isNotBlank() && text.contains(it.lowercase()) }
            val hasMatchingOrg = input.organizations.any { it.isNotBlank() && text.contains(it.lowercase()) }
            if (hasMatchingAlias || hasMatchingLoc || hasMatchingOrg) {
                return true
            }

            return false
        }
    }

    /**
     * Native Evidence emission (ROADMAP: "Evidence is the universal language").
     *
     * Runs the same profile scan but returns an [EvidenceCollection] directly —
     * profile-matches become [EvidenceKind.Profile] observations, each PII/other
     * finding is bridged through the lossless [Finding.toEvidence] adapter, and
     * the scanner's own structural knowledge (a username lives on a profile,
     * a PII value was observed on a profile) is asserted as
     * [EvidenceRelationship]s that the correlation engine generalizes. This lets
     * new code consume the scanner's output as Evidence without the scan path
     * itself being rewritten.
     */
    suspend fun scanIdentityEvidence(
        input: IdentityInput,
        deepResearch: Boolean = false
    ): EvidenceCollection {
        val results = scanIdentity(input, deepResearch = deepResearch)
        // This is the retrieval boundary for a fresh scan. Capture one batch
        // timestamp here; the pure adapter below remains parameterized for
        // deterministic conversion of restored/test data.
        return results.toEvidenceCollection(
            input,
            retrievedAtEpochMillis = System.currentTimeMillis()
        )
    }

    /**
     * Maps already-fetched [ProfileScanResult]s to Evidence without re-scanning.
     * Callers converting a fresh fetch should retain the same batch timestamp
     * by passing it to the internal adapter; this public convenience method is
     * intentionally timestamp-free for restored data and deterministic callers.
     */
    fun toEvidenceCollection(
        results: List<ProfileScanResult>,
        input: IdentityInput
    ): EvidenceCollection = results.toEvidenceCollection(input)
}

internal data class DeclarativeProfileFields(
    val displayName: String?,
    val bio: String?,
    val profileImageUrl: String?,
    val links: List<String>
)

/** Applies provider-owned CSS extraction rules without making selectors fatal. */
internal object DeclarativeProfileExtractor {
    fun extract(
        document: Document,
        rules: ExtractionRules?,
        fallbackTitle: String? = null
    ): DeclarativeProfileFields {
        val configured = rules ?: ExtractionRules()
        val displayName = selectedText(document, configured.displayNameSelectors, MAX_DISPLAY_NAME_CHARS)
            ?: (fallbackTitle ?: document.title()).trim().take(MAX_DISPLAY_NAME_CHARS).takeIf(String::isNotBlank)
        val bio = selectedText(document, configured.bioSelectors, MAX_BIO_CHARS)
            ?: document.select("meta[name=description]").attr("content").trim().takeIf(String::isNotBlank)
            ?: document.select("p").firstOrNull()?.text()?.trim()?.take(MAX_BIO_CHARS)?.takeIf(String::isNotBlank)
        val profileImageUrl = selectedUrl(document, configured.avatarSelectors)
            ?.let(::normalizeHttpUrl)
            ?: defaultImageUrl(document)
        val linkSelectors = configured.linkSelectors.ifEmpty { ExtractionRules().linkSelectors }
        val links = selectedUrls(document, linkSelectors)
        val canonical = selectedUrl(document, configured.canonicalSelectors)
            ?.let(::normalizeHttpUrl)
        return DeclarativeProfileFields(
            displayName = displayName,
            bio = bio,
            profileImageUrl = profileImageUrl,
            links = (listOfNotNull(canonical) + links).distinct().take(MAX_LINKS)
        )
    }

    private fun selectedText(document: Document, selectors: List<String>, maxChars: Int): String? =
        selectedElements(document, selectors)
            .asSequence()
            .mapNotNull { element ->
                sequenceOf(
                    element.attr("content"),
                    element.attr("value"),
                    element.text()
                ).map(String::trim).firstOrNull(String::isNotBlank)?.take(maxChars)
            }
            .firstOrNull()

    private fun selectedUrl(document: Document, selectors: List<String>): String? =
        selectedElements(document, selectors)
            .asSequence()
            .mapNotNull { element ->
                sequenceOf(
                    element.attr("abs:content"),
                    element.attr("abs:src"),
                    element.attr("abs:href"),
                    element.attr("content"),
                    element.attr("src"),
                    element.attr("href")
                ).map(String::trim).firstOrNull(String::isNotBlank)?.take(MAX_URL_CHARS)
            }
            .firstOrNull()

    private fun selectedUrls(document: Document, selectors: List<String>): List<String> =
        selectedElements(document, selectors)
            .asSequence()
            .mapNotNull { element ->
                sequenceOf(element.attr("abs:href"), element.attr("abs:src"))
                    .map(String::trim)
                    .firstOrNull { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            }
            .distinct()
            .take(MAX_LINKS)
            .toList()

    private fun selectedElements(document: Document, selectors: List<String>): List<Element> =
        selectors.asSequence()
            .flatMap { selector ->
                runCatching { document.select(selector).asSequence() }
                    .getOrElse { emptySequence() }
            }
            .toList()

    private fun defaultImageUrl(document: Document): String? {
        val selectors = listOf(
            "meta[property=og:image]",
            "meta[name=og:image]",
            "meta[name=twitter:image]",
            "meta[property=twitter:image]",
            "meta[name=twitter:image:src]",
            "meta[property=twitter:image:src]",
            "link[rel=image_src]",
            "img.avatar",
            "img[alt*=avatar]",
            "img[alt*=profile]",
            "img[src*=avatar]",
            "img[src*=profile]"
        )
        return selectedUrl(document, selectors)?.let(::normalizeHttpUrl)
    }

    private fun normalizeHttpUrl(raw: String): String? {
        if (raw.length > MAX_URL_CHARS || !io.dossier.app.domain.util.UrlNormalizer.isHttpUrl(raw)) return null
        val normalized = io.dossier.app.domain.util.UrlNormalizer.stripFragment(
            io.dossier.app.domain.util.UrlNormalizer.ensureHttps(raw)
        )
        val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            return null
        }
        return normalized.take(MAX_URL_CHARS)
    }

    private const val MAX_DISPLAY_NAME_CHARS = 240
    private const val MAX_BIO_CHARS = 2_000
    private const val MAX_URL_CHARS = 4_096
    private const val MAX_LINKS = 256
}

/**
 * Collects recursive fetches as they finish while retaining queue order in the
 * returned list. Frontier completion is owned by this collector, so a fetch
 * that is still running (or is cancelled/throws) remains pending for resume.
 */
internal suspend fun collectPivotFetches(
    pending: List<PivotFrontierEntry>,
    frontier: BoundedPivotFrontier,
    frontierStore: PivotFrontierStore?,
    fetch: suspend (PivotFrontierEntry) -> ProfileScanResult,
    onCompleted: (PivotFrontierEntry, ProfileScanResult) -> Unit = { _, _ -> }
): List<ProfileScanResult> = coroutineScope {
    if (pending.isEmpty()) return@coroutineScope emptyList()

    val orderedResults = arrayOfNulls<ProfileScanResult>(pending.size)
    val deferredPivots = pending.mapIndexed { index, entry ->
        async(Dispatchers.IO) {
            index to fetch(entry)
        }
    }.toMutableList()

    while (deferredPivots.isNotEmpty()) {
        val completed = select<
            Pair<Deferred<Pair<Int, ProfileScanResult>>, Pair<Int, ProfileScanResult>>
            > {
            deferredPivots.forEach { deferred ->
                deferred.onAwait { deferred to it }
            }
        }
        deferredPivots.remove(completed.first)

        val (index, scanResult) = completed.second
        val entry = pending[index]
        frontier.complete(entry.key)
        frontierStore?.save(frontier)
        onCompleted(entry, scanResult)
        orderedResults[index] = scanResult
    }

    orderedResults.mapIndexed { index, result ->
        requireNotNull(result) { "Missing recursive result at index $index" }
    }
}

internal fun verifiedPivotSeeds(results: List<ProfileScanResult>): List<ProfileScanResult> =
    results.filter { it.exists && it.verified }

/**
 * Bridges only bounded frontier metadata to the coordinator. The diagnostic
 * intentionally excludes candidate/source URLs and normalized handles so the
 * live scan surface cannot become a second evidence store.
 */
private fun publishPivotDiagnostics(
    scanId: ScanId,
    frontier: BoundedPivotFrontier,
    decision: PivotDecisionDiagnostic? = null
) {
    ScanCoordinatorRuntime.onPivotDiagnostics(
        scanId = scanId,
        decision = decision?.let {
            io.dossier.app.domain.discovery.PivotDecisionSummary(
                admitted = it.admitted,
                signalType = it.signalType.name,
                depth = it.depth,
                reason = it.reason
            )
        },
        pendingCount = frontier.pendingCount,
        pendingByDepth = frontier.pendingByDepth,
        admittedCount = frontier.admittedCount,
        rejectedCount = frontier.rejectedCount,
        visitedCount = frontier.visitedCount,
        maxDepth = frontier.config.maxDepth,
        maxTotalPivots = frontier.config.maxTotalPivots
    )
}

/**
 * Converts scanner [ProfileScanResult]s into a native [EvidenceCollection],
 * asserting the scanner-known relationships (username↔profile, PII-on-profile)
 * that are not recoverable from findings alone.
 */
internal fun List<ProfileScanResult>.toEvidenceCollection(
    input: IdentityInput,
    retrievedAtEpochMillis: Long? = null
): EvidenceCollection {
    val evidence = mutableListOf<Evidence>()
    val relationships = mutableListOf<EvidenceRelationship>()

    forEach { result ->
        val url = result.candidate.url
        val conf = result.candidate.confidence.coerceIn(0f, 1f)
        val path = if (!result.provenance.isNullOrBlank()) listOf(result.provenance) else emptyList()

        // Profile observation (native, not via the Finding adapter).
        val profileEvidence = Evidence(
            id = "profile:${url}",
            kind = EvidenceKind.Profile,
            value = url,
            sourceUrl = url,
            snippet = result.displayName?.let { "Profile: $it" },
            confidence = conf,
            risk = if (result.verified && result.exists) RiskLevel.High else RiskLevel.Low,
            signals = result.confidenceSignals,
            providerId = result.providerId ?: result.candidate.providerId,
            retrievedAtEpochMillis = retrievedAtEpochMillis,
            state = when {
                !result.exists -> EvidenceState.Candidate
                result.verified -> EvidenceState.Verified
                else -> EvidenceState.Observed
            },
            reliability = if (result.exists && result.verified) {
                EvidenceReliability.DirectPublicProfile
            } else {
                EvidenceReliability.SearchEngineCandidate
            },
            discoveryPath = path
        )
        evidence.add(profileEvidence)

        // Username → profile assertion (scanner knowledge).
        if (result.candidate.username.isNotBlank()
            && result.candidate.username != "unknown"
            && result.candidate.username != "web"
        ) {
            relationships.add(
                EvidenceRelationship(
                    fromValue = result.candidate.username,
                    toValue = url,
                    relation = "username_on_profile",
                    evidence = result.candidate.platform.name,
                    evidenceIds = listOf(profileEvidence.id)
                )
            )
        }

        // Each finding bridges losslessly; PII-on-profile is asserted explicitly.
        result.findings.forEach { finding ->
            val findingEvidence = finding.toEvidence(retrievedAtEpochMillis, path)
            evidence.add(findingEvidence)
            if (finding.sourceUrl == url || result.exists) {
                relationships.add(
                    EvidenceRelationship(
                        fromValue = url,
                        toValue = finding.value,
                        relation = "mentions",
                        evidence = finding.type.name,
                        evidenceIds = listOf(findingEvidence.id)
                    )
                )
            }
        }
    }

    // Identity seeds as native evidence too, so the collection is self-contained.
    input.emails.filter { it.isNotBlank() }.forEach {
        evidence.add(Evidence(id = "seed:email:$it", kind = EvidenceKind.Email, value = it, confidence = 1.0f))
    }
    input.phones.filter { it.isNotBlank() }.forEach {
        evidence.add(Evidence(id = "seed:phone:$it", kind = EvidenceKind.Phone, value = it, confidence = 1.0f))
    }
    (listOfNotNull(input.primaryUsername) + input.usernames).filter { it.isNotBlank() }.distinctBy { it.lowercase() }.forEach {
        evidence.add(Evidence(id = "seed:username:$it", kind = EvidenceKind.Username, value = it, confidence = 1.0f))
    }

    return EvidenceCollection(
        evidence = evidence.distinctBy { it.id },
        relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
    )
}
