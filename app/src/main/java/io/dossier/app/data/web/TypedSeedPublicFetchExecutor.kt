package io.dossier.app.data.web

import io.dossier.app.domain.discovery.ProviderDefinition
import io.dossier.app.domain.discovery.ProviderExecutionResult
import io.dossier.app.domain.discovery.ProviderExecutionRuntime
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.discovery.TypedSeedSafety
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.evidence.HistoricalAttributeKind
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.pii.PiiExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private class TypedSeedUnavailableException(message: String) : RuntimeException(message)

/**
 * Builds one archive resolver per executor instance so its cache and request
 * mutex are shared across all archive seeds in a pass.
 */
private fun defaultArchiveSeedResolver(): TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
    val resolver = ArchivePageResolver()
    return TypedSeedPublicFetchExecutor.ArchiveSeedResolver { url ->
        when (val result = resolver.resolveExactUrl(url)) {
            is ArchivePageResolver.Result.Found -> TypedSeedPublicFetchExecutor.ArchiveSeedFetch(
                provider = result.provider,
                originalUrl = result.originalUrl,
                snapshotUrl = result.snapshotUrl,
                timestamp = result.timestamp,
                title = result.title,
                description = result.description,
                text = result.text
            )
            ArchivePageResolver.Result.NotFound -> null
            is ArchivePageResolver.Result.Unavailable ->
                throw TypedSeedUnavailableException(result.reason)
        }
    }
}

/**
 * A bounded executor for reviewed URL-like fetches and bounded
 * Email/Phone/Name/Username public-search pivots.
 *
 * The canonical [EvidenceCollection] is the only output of record.  The
 * detailed report is deliberately disposable and exists to make per-seed
 * unavailable/candidate states testable without introducing another ledger.
 * Direct HTTP fetches use [ProviderExecutionRuntime]; archive seeds use the
 * reviewed archive resolver and remain historical observations.
 */
class TypedSeedPublicFetchExecutor(
    private val providerRuntime: ProviderExecutionRuntime = defaultProviderRuntime(),
    private val archiveResolver: ArchiveSeedResolver = defaultArchiveSeedResolver(),
    private val fetcher: PublicSeedFetcher = PublicSeedFetcher { provider, url, scanId, maxBodyChars ->
        providerRuntime.execute(
            provider = provider,
            url = url,
            scanId = scanId,
            maxBodyChars = maxBodyChars
        )
    },
    private val piiExtractor: PiiExtractor = PiiExtractor(),
    /** Legacy list seam retained for deterministic JVM fixtures/callers. */
    private val searcher: PublicSearchSeam? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Outcome seam used by production to distinguish provider failure from an empty result. */
    private val searchOutcomeSearcher: PublicSearchOutcomeSeam? = null
) {

    /**
     * Page material produced by direct search verification. The map is scoped
     * to this executor instance and bounded; it is not a second evidence store.
     */
    private val reusableVerifiedPages = ConcurrentHashMap<String, ReusableVerifiedPage>()

    private data class ReusableVerifiedPage(
        val page: VerifiedPage,
        val providerId: String
    )

    /** Injectable seam around the existing provider runtime for JVM fixtures. */
    fun interface PublicSeedFetcher {
        suspend fun fetch(
            provider: ProviderDefinition,
            url: String,
            scanId: ScanId,
            maxBodyChars: Int
        ): ProviderExecutionResult
    }

    /** Injectable archive seam; the default delegates to [ArchivePageResolver]. */
    fun interface ArchiveSeedResolver {
        suspend fun resolve(url: String): ArchiveSeedFetch?
    }

    /** Injectable search seam for Email/Phone/Name/Username seeds. */
    fun interface PublicSearchSeam {
        suspend fun search(
            seed: TypedSeed,
            input: IdentityInput,
            scanId: ScanId
        ): List<PublicSearchDiscoveryService.PublicSearchResult>
    }

    /** Explicit search outcome used by the durable frontier. */
    fun interface PublicSearchOutcomeSeam {
        suspend fun search(
            seed: TypedSeed,
            input: IdentityInput,
            scanId: ScanId
        ): PublicSearchDiscoveryService.SearchOutcome
    }

    /** Archive payload used by the executor; [html] is optional for test fixtures. */
    data class ArchiveSeedFetch(
        val provider: String,
        val originalUrl: String,
        val snapshotUrl: String,
        val timestamp: String = "",
        val title: String = "",
        val description: String = "",
        val text: String,
        val html: String? = null
    )

    /** Parsed semantics for a URL that is already an archive snapshot. */
    internal data class DirectArchiveSnapshot(
        val provider: String,
        val snapshotUrl: String,
        val originalUrl: String?,
        val timestamp: String?
    )

    enum class ExecutionState {
        Verified,
        /** A valid search completed; its observations may still be candidates. */
        Completed,
        Unavailable,
        Candidate,
        Skipped
    }

    data class SeedExecution(
        val seed: TypedSeed,
        val state: ExecutionState,
        val reason: String? = null,
        val fetchAttempted: Boolean = false,
        val evidenceIds: List<String> = emptyList()
    )

    data class Report(
        val collection: EvidenceCollection,
        val executions: List<SeedExecution>
    ) {
        val evidence: List<Evidence> get() = collection.evidence
        val outcomes: List<SeedExecution> get() = executions
    }

    /** Executes safe URL-like fetches and Email/Phone/Name/Username searches into canonical evidence. */
    suspend fun execute(
        seeds: List<TypedSeed>,
        input: IdentityInput,
        scanId: ScanId
    ): EvidenceCollection = executeDetailed(seeds, input, scanId).collection

    /**
     * Executes one bounded pass. Unsafe/candidate seeds are never fetched. A
     * failed seed produces an explicit unavailable outcome while independent
     * seeds continue under the global fetch semaphore.
     */
    suspend fun executeDetailed(
        seeds: List<TypedSeed>,
        input: IdentityInput,
        scanId: ScanId
    ): Report = withContext(Dispatchers.IO) {
        val eligibleKinds = TypedSeedSafety.executableKinds

        val distinct = seeds.distinctBy(::seedKey)
        val bounded = distinct.take(MAX_SEEDS)
        // Keep the bound observable. Dropping overflow silently would make a
        // persisted frontier look complete even though admitted seeds were
        // never attempted.
        val overflow = distinct
            .drop(MAX_SEEDS)
            .map { skipped(it, "Seed was outside the bounded execution budget") }
        val safe = bounded.filter { seed ->
            seed.kind in eligibleKinds && TypedSeedSafety.isSafeExecutableSeed(seed)
        }
        val unsafe = bounded.filterNot { it in safe }.map { skipped(it) }

        val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)
        val fetched = supervisorScope {
            safe.map { seed ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        executeOne(seed, input, scanId)
                    }
                }
            }.awaitAll()
        }

        val executions = unsafe + overflow + fetched
        val evidence = executions
            .flatMap { execution -> execution.evidence }
            .distinctBy(Evidence::id)
        val relationships = executions
            .flatMap { execution -> execution.relationships }

        Report(
            collection = EvidenceCollection(
                evidence = evidence,
                relationships = EvidenceRelationshipPolicy.normalize(relationships)
            ),
            executions = executions.map { it.execution }
        )
    }

    private suspend fun executeOne(
        seed: TypedSeed,
        input: IdentityInput,
        scanId: ScanId
    ): SeedRun {
        return try {
            when (seed.kind) {
                TypedSeedKind.Archive -> executeArchive(seed, input, scanId)
                TypedSeedKind.Url,
                TypedSeedKind.Domain,
                TypedSeedKind.Document -> executePublic(seed, input, scanId)
                TypedSeedKind.Email,
                TypedSeedKind.Phone,
                TypedSeedKind.Name,
                TypedSeedKind.Username -> executeSearch(seed, input, scanId)
                else -> skipped(seed)
            }
        } catch (cancelled: CancellationException) {
            // Preserve prompt cancellation of the parent scan. A synthetic
            // cancellation thrown by an injected child while the parent is
            // still active is isolated as an unavailable per-seed outcome.
            if (!currentCoroutineContext().isActive) throw cancelled
            unavailable(seed, "Seed execution cancelled", fetchAttempted = true)
        } catch (error: Exception) {
            unavailable(
                seed,
                error.message?.take(MAX_REASON_CHARS)
                    ?: "Seed execution failed",
                fetchAttempted = true
            )
        }
    }

    private suspend fun executePublic(
        seed: TypedSeed,
        input: IdentityInput,
        scanId: ScanId
    ): SeedRun {
        val requestedUrl = requestUrl(seed)
            ?: return unavailable(seed, "Seed URL could not be normalized")
        if (!DiscoveryHttpPolicy.isSafePublicHttpUrl(requestedUrl)) {
            return unavailable(seed, "Seed URL is not a safe public HTTP(S) URL")
        }

        reusablePage(seed)?.let { (key, reusable) ->
            try {
                return replayVerifiedPage(seed, input, reusable)
            } finally {
                reusableVerifiedPages.remove(key, reusable)
            }
        }

        val provider = ProviderExecutionRuntime.uncataloguedProfileDefinition(requestedUrl)
            ?: return unavailable(seed, "Seed URL is not a supported HTTP(S) URL")
        ScanCoordinatorRuntime.onProviderQueued(provider.id, scanId)

        val execution = fetcher.fetch(
            provider = provider,
            url = requestedUrl,
            scanId = scanId,
            maxBodyChars = RESPONSE_TEXT_CHARS + 1
        )
        currentCoroutineContext().ensureActive()
        if (execution.bodyText.length > RESPONSE_TEXT_CHARS) {
            return unavailable(seed, "Response exceeds the bounded text limit", fetchAttempted = true)
        }
        if (execution.decision.state != ProviderVerificationState.Present) {
            return unavailable(
                seed,
                execution.decision.explanation,
                fetchAttempted = true,
                providerId = provider.id,
                status = execution.decision.state
            )
        }

        val finalUrl = execution.finalUrl?.let { returnedUrl ->
            if (!DiscoveryHttpPolicy.isSafePublicHttpUrl(returnedUrl)) {
                return unavailable(
                    seed,
                    "Provider returned an unsafe final URL",
                    fetchAttempted = true,
                    providerId = provider.id,
                    status = ProviderVerificationState.InvalidResponse
                )
            }
            returnedUrl
        } ?: requestedUrl
        val raw = execution.bodyText
        if (looksUnsupportedDocument(seed, raw, execution.contentType, finalUrl)) {
            return unavailable(
                seed,
                "Response format is not supported by the local parser",
                fetchAttempted = true,
                providerId = provider.id,
                status = ProviderVerificationState.Present
            )
        }
        val parsed = parsePublicDocument(raw, finalUrl)
        if (parsed.text.isBlank()) {
            return unavailable(
                seed,
                "Public response contained no usable text",
                fetchAttempted = true,
                providerId = provider.id,
                status = ProviderVerificationState.Present
            )
        }

        return verifiedRun(
            seed = seed,
            sourceUrl = finalUrl,
            text = parsed.text,
            title = parsed.title,
            links = parsed.links,
            input = input,
            providerId = provider.id,
            reliability = EvidenceReliability.DirectPersonalWebsite,
            sourceClassification = sourceClassification(seed),
            historical = false,
            observedAtEpochMillis = null,
            parserVersion = PARSER_VERSION,
            html = raw,
            sourceUrls = listOf(requestedUrl, finalUrl)
                .distinct()
                .take(Evidence.MAX_SOURCE_URLS),
            discoveryPathExtra = listOf(requestedUrl)
        )
    }

    private suspend fun executeArchive(
        seed: TypedSeed,
        input: IdentityInput,
        scanId: ScanId
    ): SeedRun {
        reusablePage(seed)?.let { (key, reusable) ->
            try {
                return replayVerifiedPage(seed, input, reusable)
            } finally {
                reusableVerifiedPages.remove(key, reusable)
            }
        }
        classifyArchiveSnapshot(seed.exactValue)?.let { direct ->
            return executeDirectArchiveSnapshot(seed, input, scanId, direct)
        }

        val archive = archiveResolver.resolve(seed.exactValue)
            ?: return unavailable(seed, "No historical snapshot was available", fetchAttempted = true)
        return executeArchiveFetch(seed, input, archive)
    }

    private suspend fun executeSearch(
        seed: TypedSeed,
        input: IdentityInput,
        scanId: ScanId
    ): SeedRun {
        currentCoroutineContext().ensureActive()
        val outcome = when {
            searchOutcomeSearcher != null -> searchOutcomeSearcher.search(seed, input, scanId)
            searcher != null -> PublicSearchDiscoveryService.SearchOutcome.Success(
                searcher.search(seed, input, scanId)
            )
            else -> return unavailable(seed, "Search adapter is not configured")
        }
        // A seam may complete after its caller was cancelled. Do not map any
        // late response into canonical evidence in that case.
        currentCoroutineContext().ensureActive()

        if (outcome is PublicSearchDiscoveryService.SearchOutcome.Unavailable) {
            return unavailable(
                seed,
                outcome.reason.take(MAX_REASON_CHARS).ifBlank { "Public search provider unavailable" },
                fetchAttempted = true
            )
        }

        val normalizedResults = (outcome as PublicSearchDiscoveryService.SearchOutcome.Success)
            .results
            .asSequence()
            .mapNotNull(::sanitizeSearchResult)
            .groupBy { PublicSearchDiscoveryService.canonicalUrlKey(it.url) }
            .values
            .mapNotNull { group ->
                group.maxWithOrNull(
                    compareBy<PublicSearchDiscoveryService.PublicSearchResult> { it.directlyVerified }
                        .thenBy { it.score }
                        .thenByDescending { it.providerCount }
                )
            }
            .take(MAX_SEARCH_RESULTS)
            .toList()

        normalizedResults.forEach { result ->
            if (result.directlyVerified) {
                result.verifiedPage?.let { page ->
                    rememberVerifiedPage(
                        result.url,
                        ReusableVerifiedPage(
                            page = page,
                            providerId = result.source.ifBlank { "public-search-verified" }
                        )
                    )
                }
            }
        }

        val retrievedAt = nowMillis()
        val evidenceList = normalizedResults.map { result ->
            currentCoroutineContext().ensureActive()
            val path = searchDiscoveryPath(seed, result)
            val sourceUrls = searchSourceUrls(seed, result)
            val supportingEvidenceIds = (seed.evidenceIds + result.pivotEvidenceIds)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(Evidence.MAX_SUPPORTING_EVIDENCE_IDS)
            Evidence(
                id = evidenceId("search", seed, result.url, result.query),
                kind = EvidenceKind.PublicSearchEvidence,
                value = result.url,
                sourceUrl = result.url,
                snippet = result.snippet.takeIf { it.isNotBlank() },
                confidence = result.score.coerceIn(0f, 1f),
                risk = RiskLevel.Medium,
                signals = searchSignals(seed, result),
                providerId = result.source,
                retrievedAtEpochMillis = retrievedAt,
                state = if (result.directlyVerified) {
                    EvidenceState.Observed
                } else {
                    EvidenceState.Candidate
                },
                reliability = if (result.verifiedPage?.historical == true) {
                    EvidenceReliability.ArchiveSnapshot
                } else {
                    EvidenceReliability.SearchEngineCandidate
                },
                sourceClassification = if (result.verifiedPage?.historical == true) {
                    ExposureSourceClassification.ARCHIVE
                } else {
                    ExposureSourceClassification.PUBLIC_WEB
                },
                contentHashSha256 = result.contentHashSha256,
                parserVersion = PARSER_VERSION,
                discoveryPath = path,
                sourceUrls = sourceUrls,
                supportingEvidenceIds = supportingEvidenceIds,
                attribution = FindingAttribution.Unconfirmed,
                historical = result.verifiedPage?.historical == true
            )
        }
        currentCoroutineContext().ensureActive()

        return SeedRun(
            execution = SeedExecution(
                seed = seed,
                state = ExecutionState.Completed,
                fetchAttempted = true,
                evidenceIds = evidenceList.map(Evidence::id).distinct().take(MAX_EVIDENCE_IDS)
            ),
            evidence = evidenceList,
            relationships = evidenceList.map { evidence ->
                EvidenceRelationship(
                    fromValue = seed.exactValue,
                    toValue = evidence.value,
                    relation = "indexed_result",
                    evidence = "Public search index observation; ownership unconfirmed",
                    evidenceIds = listOf(evidence.id)
                )
            },
            path = discoveryPath(seed, "search-results")
        )
    }

    /**
     * Replays a page that PublicPageVerifier already fetched for a search
     * result. Live pages use the same canonical parser/extractor path as a
     * normal URL fetch; archive pages retain their historical classification.
     */
    private suspend fun replayVerifiedPage(
        seed: TypedSeed,
        input: IdentityInput,
        reusable: ReusableVerifiedPage
    ): SeedRun {
        currentCoroutineContext().ensureActive()
        val page = reusable.page
        val sourceUrl = page.finalUrl.trim()
        if (sourceUrl.length > MAX_SEARCH_URL_CHARS ||
            !DiscoveryHttpPolicy.isSafePublicHttpUrl(sourceUrl)
        ) {
            return unavailable(seed, "Verified page returned an unsafe final URL")
        }

        if (page.historical) {
            return executeArchiveFetch(
                seed = seed,
                input = input,
                archive = ArchiveSeedFetch(
                    provider = page.archiveProvider.orEmpty(),
                    originalUrl = page.archiveOriginalUrl.orEmpty(),
                    snapshotUrl = sourceUrl,
                    timestamp = page.archiveTimestamp.orEmpty(),
                    title = page.title,
                    description = page.description,
                    text = page.text
                )
            )
        }

        val sourceUrls = listOf(seed.exactValue, sourceUrl)
            .map(String::trim)
            .filter(DiscoveryHttpPolicy::isSafePublicHttpUrl)
            .distinctBy(::canonicalUrl)
            .take(Evidence.MAX_SOURCE_URLS)
        return verifiedRun(
            seed = seed,
            sourceUrl = sourceUrl,
            text = page.text,
            title = page.title,
            links = page.links,
            input = input,
            providerId = reusable.providerId,
            reliability = EvidenceReliability.DirectPersonalWebsite,
            sourceClassification = sourceClassification(seed),
            historical = false,
            observedAtEpochMillis = null,
            parserVersion = PARSER_VERSION,
            html = null,
            contentHashSha256 = page.contentHashSha256,
            sourceUrls = sourceUrls,
            discoveryPathExtra = listOf(seed.exactValue)
        )
    }

    private fun rememberVerifiedPage(url: String, reusable: ReusableVerifiedPage) {
        val key = canonicalUrl(url)
        if (!DiscoveryHttpPolicy.isSafePublicHttpUrl(url)) return
        reusableVerifiedPages[key] = reusable
        while (reusableVerifiedPages.size > MAX_REUSABLE_VERIFIED_PAGES) {
            val eldest = reusableVerifiedPages.keys.firstOrNull() ?: break
            reusableVerifiedPages.remove(eldest)
        }
    }

    private fun reusablePage(seed: TypedSeed): Pair<String, ReusableVerifiedPage>? {
        val requestedUrl = requestUrl(seed) ?: return null
        val key = canonicalUrl(requestedUrl)
        return reusableVerifiedPages[key]?.let { key to it }
    }

    /** Executes an already archived URL directly; it is not an original URL lookup. */
    private suspend fun executeDirectArchiveSnapshot(
        seed: TypedSeed,
        input: IdentityInput,
        scanId: ScanId,
        direct: DirectArchiveSnapshot
    ): SeedRun {
        val requestedUrl = direct.snapshotUrl
        if (!DiscoveryHttpPolicy.isSafePublicHttpUrl(requestedUrl)) {
            return unavailable(seed, "Archive snapshot URL is not a safe public HTTP(S) URL")
        }
        val provider = ProviderExecutionRuntime.uncataloguedProfileDefinition(requestedUrl)
            ?: return unavailable(seed, "Archive snapshot URL is not a supported HTTP(S) URL")
        ScanCoordinatorRuntime.onProviderQueued(provider.id, scanId)

        val execution = fetcher.fetch(
            provider = provider,
            url = requestedUrl,
            scanId = scanId,
            maxBodyChars = RESPONSE_TEXT_CHARS + 1
        )
        currentCoroutineContext().ensureActive()
        if (execution.bodyText.length > RESPONSE_TEXT_CHARS) {
            return unavailable(
                seed,
                "Archived response exceeds the bounded text limit",
                fetchAttempted = true,
                providerId = provider.id,
                status = execution.decision.state
            )
        }
        if (execution.finalUrl != null && !DiscoveryHttpPolicy.isSafePublicHttpUrl(execution.finalUrl)) {
            return unavailable(
                seed,
                "Provider returned an unsafe archive final URL",
                fetchAttempted = true,
                providerId = provider.id,
                status = ProviderVerificationState.InvalidResponse
            )
        }
        if (execution.decision.state != ProviderVerificationState.Present) {
            return unavailable(
                seed,
                execution.decision.explanation,
                fetchAttempted = true,
                providerId = provider.id,
                status = execution.decision.state
            )
        }

        val finalUrl = execution.finalUrl ?: requestedUrl
        val finalSnapshot = classifyArchiveSnapshot(finalUrl)
        if (finalSnapshot == null) {
            return unavailable(
                seed,
                "Provider returned a non-archive or malformed snapshot URL",
                fetchAttempted = true,
                providerId = provider.id,
                status = ProviderVerificationState.InvalidResponse
            )
        }
        if (looksUnsupportedDocument(seed, execution.bodyText, execution.contentType, finalUrl)) {
            return unavailable(
                seed,
                "Archive response format is not supported by the local parser",
                fetchAttempted = true,
                providerId = provider.id,
                status = ProviderVerificationState.Present
            )
        }
        val parsed = parsePublicDocument(execution.bodyText, finalUrl)
        if (parsed.text.isBlank()) {
            return unavailable(
                seed,
                "Archived snapshot contained no usable text",
                fetchAttempted = true,
                providerId = provider.id,
                status = ProviderVerificationState.Present
            )
        }
        return executeArchiveFetch(
            seed = seed,
            input = input,
            archive = ArchiveSeedFetch(
                provider = direct.provider,
                originalUrl = direct.originalUrl.orEmpty(),
                snapshotUrl = finalSnapshot.snapshotUrl,
                timestamp = direct.timestamp ?: finalSnapshot.timestamp.orEmpty(),
                title = parsed.title,
                text = parsed.text,
                html = execution.bodyText
            )
        )
    }

    private suspend fun executeArchiveFetch(
        seed: TypedSeed,
        input: IdentityInput,
        archive: ArchiveSeedFetch
    ): SeedRun {
        if (archive.text.length > RESPONSE_TEXT_CHARS) {
            return unavailable(seed, "Archived response exceeds the bounded text limit", fetchAttempted = true)
        }
        val snapshot = classifyArchiveSnapshot(archive.snapshotUrl)
            ?: return unavailable(
                seed,
                "Archive resolver returned a non-archive or malformed snapshot URL",
                fetchAttempted = true
            )
        val sourceUrl = snapshot.snapshotUrl
        val parsed = archive.html?.let { parsePublicDocument(it, sourceUrl) }
            ?: ParsedDocument(
                title = archive.title,
                text = archive.text.take(RESPONSE_TEXT_CHARS),
                links = extractTextLinks(archive.text)
            )
        if (parsed.text.isBlank()) {
            return unavailable(seed, "Archived snapshot contained no usable text", fetchAttempted = true)
        }

        val originalUrl = archive.originalUrl.trim()
            .takeIf { it.isNotBlank() && DiscoveryHttpPolicy.isSafePublicHttpUrl(it) }
            ?: snapshot.originalUrl
        val sourceUrls = listOf(seed.exactValue, sourceUrl, originalUrl)
            .filterNotNull()
            .distinct()
            .take(Evidence.MAX_SOURCE_URLS)
        val archiveRun = verifiedRun(
            seed = seed,
            sourceUrl = sourceUrl,
            text = parsed.text,
            title = parsed.title,
            links = parsed.links,
            input = input,
            providerId = archive.provider.ifBlank { "archive-snapshot" },
            reliability = EvidenceReliability.ArchiveSnapshot,
            sourceClassification = ExposureSourceClassification.ARCHIVE,
            historical = true,
            observedAtEpochMillis = parseArchiveTimestamp(archive.timestamp)
                ?: snapshot.timestamp?.let(::parseArchiveTimestamp),
            parserVersion = ARCHIVE_PARSER_VERSION,
            html = archive.html,
            archiveDescription = archive.description,
            sourceUrls = sourceUrls,
            discoveryPathExtra = listOfNotNull(originalUrl)
        )

        val archiveRelation = originalUrl?.let { original ->
            archiveRun.evidence.firstOrNull()?.let { snapshotEvidence ->
                EvidenceRelationship(
                    fromValue = original,
                    toValue = sourceUrl,
                    relation = "ARCHIVED_AS",
                    evidence = "${archive.provider.ifBlank { "Archive" }} snapshot",
                    evidenceIds = listOf(snapshotEvidence.id)
                )
            }
        }
        val archiveRunWithRelation = if (archiveRelation == null) {
            archiveRun
        } else {
            archiveRun.copy(
                relationships = archiveRun.relationships + archiveRelation
            )
        }

        val metadata = archive.html?.let {
            ArchiveSnapshotExtractor.extract(
                html = it,
                snapshotUrl = sourceUrl,
                originalUrl = archive.originalUrl
            )
        }
        if (metadata == null || metadata.isEmpty) return archiveRunWithRelation

        val metadataRun = metadataEvidence(
            seed = seed,
            sourceUrl = sourceUrl,
            metadata = metadata,
            providerId = archive.provider.ifBlank { "archive-snapshot" },
            observedAtEpochMillis = parseArchiveTimestamp(archive.timestamp),
            discoveryPath = archiveRunWithRelation.path,
            sourceUrls = sourceUrls
        )
        return archiveRunWithRelation.merge(metadataRun)
    }

    private fun verifiedRun(
        seed: TypedSeed,
        sourceUrl: String,
        text: String,
        title: String,
        links: List<String>,
        input: IdentityInput,
        providerId: String,
        reliability: EvidenceReliability,
        sourceClassification: ExposureSourceClassification,
        historical: Boolean,
        observedAtEpochMillis: Long?,
        parserVersion: String,
        html: String?,
        contentHashSha256: String? = null,
        archiveDescription: String? = null,
        sourceUrls: List<String> = listOf(seed.exactValue),
        discoveryPathExtra: List<String> = emptyList()
    ): SeedRun {
        val retrievedAt = nowMillis()
        val path = discoveryPath(seed, sourceUrl, discoveryPathExtra)
        val seedId = evidenceId("seed", seed, sourceUrl, seed.exactValue)
        val sourceKind = seed.kind.toEvidenceKind()
        val seedEvidence = Evidence(
            id = seedId,
            kind = sourceKind,
            value = seed.exactValue,
            sourceUrl = sourceUrl,
            snippet = listOfNotNull(title.takeIf(String::isNotBlank), archiveDescription?.takeIf(String::isNotBlank))
                .joinToString(" — ")
                .takeIf(String::isNotBlank),
            confidence = 1.0f,
            risk = if (historical) RiskLevel.Low else RiskLevel.Medium,
            signals = buildList {
                add("Direct public ${seed.kind.name.lowercase(Locale.ROOT)} fetch completed")
                if (historical) add("Historical archive observation; not current-state evidence")
            },
            providerId = providerId,
            retrievedAtEpochMillis = retrievedAt,
            observedAtEpochMillis = observedAtEpochMillis,
            state = EvidenceState.Verified,
            reliability = reliability,
            sourceClassification = sourceClassification,
            contentHashSha256 = contentHashSha256 ?: sha256(text),
            parserVersion = parserVersion,
            historical = historical,
            discoveryPath = path,
            sourceUrls = sourceUrls.distinct().take(Evidence.MAX_SOURCE_URLS),
            attribution = if (seed.origin == TypedSeedOrigin.UserInput) {
                FindingAttribution.ExactSelfSupplied
            } else {
                null
            }
        )
        val evidence = mutableListOf(seedEvidence)
        val relationships = mutableListOf(
            EvidenceRelationship(
                fromValue = sourceUrl,
                toValue = seed.exactValue,
                relation = "source_of",
                evidence = "Typed seed fetch",
                evidenceIds = listOf(seedEvidence.id)
            )
        )

        val findings = piiExtractor.extract(text, sourceUrl, input)
        findings.forEach { finding ->
            val ev = findingEvidence(
                finding = finding,
                seed = seed,
                sourceUrl = sourceUrl,
                providerId = providerId,
                retrievedAtEpochMillis = retrievedAt,
                observedAtEpochMillis = observedAtEpochMillis,
                reliability = reliability,
                sourceClassification = sourceClassification,
                historical = historical,
                path = path,
                contentHash = seedEvidence.contentHashSha256,
                sourceUrls = sourceUrls,
                parserVersion = parserVersion
            )
            evidence += ev
            relationships += EvidenceRelationship(
                fromValue = sourceUrl,
                toValue = finding.value,
                relation = "mentions",
                evidence = finding.type.name,
                evidenceIds = listOf(ev.id)
            )
        }

        links.forEach { link ->
            val linkEvidence = linkEvidence(
                seed = seed,
                sourceUrl = sourceUrl,
                link = link,
                providerId = providerId,
                retrievedAtEpochMillis = retrievedAt,
                observedAtEpochMillis = observedAtEpochMillis,
                reliability = reliability,
                sourceClassification = sourceClassification,
                historical = historical,
                path = path,
                contentHash = seedEvidence.contentHashSha256,
                sourceUrls = sourceUrls,
                parserVersion = parserVersion
            ) ?: return@forEach
            evidence += linkEvidence.first
            relationships += linkEvidence.second

            // A safe URL is a bounded navigation pivot even when its target is
            // cross-site. Fetching it does not assert that the target belongs
            // to the audited subject; the link record retains that distinction
            // and remains Observed when attribution is unconfirmed.
            val host = runCatching { URI(link).host?.lowercase(Locale.ROOT)?.removeSuffix(".") }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
            if (host != null) {
                val domainId = evidenceId("domain", seed, sourceUrl, host)
                val domainEvidence = Evidence(
                    id = domainId,
                    kind = EvidenceKind.Domain,
                    value = host,
                    sourceUrl = sourceUrl,
                    confidence = 1.0f,
                    risk = RiskLevel.Low,
                    signals = listOf("Safe public domain extracted from a directly fetched page"),
                    providerId = providerId,
                    retrievedAtEpochMillis = retrievedAt,
                    observedAtEpochMillis = observedAtEpochMillis,
                    state = linkEvidence.first.state,
                    reliability = reliability,
                    sourceClassification = sourceClassification,
                    contentHashSha256 = seedEvidence.contentHashSha256,
                    parserVersion = parserVersion,
                    historical = historical,
                    discoveryPath = path,
                    sourceUrls = sourceUrls.distinct().take(Evidence.MAX_SOURCE_URLS),
                    attribution = linkEvidence.first.attribution
                )
                evidence += domainEvidence
                relationships += EvidenceRelationship(
                    fromValue = sourceUrl,
                    toValue = host,
                    relation = "links_to_domain",
                    evidence = "Typed seed page link",
                    evidenceIds = listOf(domainEvidence.id)
                )
            }
        }

        return SeedRun(
            execution = SeedExecution(
                seed = seed,
                state = ExecutionState.Verified,
                fetchAttempted = true,
                evidenceIds = evidence.map(Evidence::id).distinct().take(MAX_EVIDENCE_IDS)
            ),
            evidence = evidence,
            relationships = relationships,
            path = path
        )
    }

    private fun metadataEvidence(
        seed: TypedSeed,
        sourceUrl: String,
        metadata: ExtractedSnapshotMetadata,
        providerId: String,
        observedAtEpochMillis: Long?,
        discoveryPath: List<String>,
        sourceUrls: List<String>
    ): SeedRun {
        val retrievedAt = nowMillis()
        val records = mutableListOf<Evidence>()
        fun add(
            kind: EvidenceKind,
            attribute: HistoricalAttributeKind,
            value: String
        ) {
            if (value.isBlank()) return
            val record = Evidence(
                id = evidenceId("archive-attribute", seed, sourceUrl, "$attribute|$value"),
                kind = kind,
                value = value,
                sourceUrl = sourceUrl,
                snippet = "Historical ${attribute.name.lowercase(Locale.ROOT)} extracted from archive snapshot",
                confidence = 1.0f,
                risk = RiskLevel.Low,
                signals = listOf("Directly parsed historical archive metadata"),
                providerId = providerId,
                retrievedAtEpochMillis = retrievedAt,
                observedAtEpochMillis = observedAtEpochMillis,
                state = EvidenceState.Verified,
                reliability = EvidenceReliability.ArchiveSnapshot,
                sourceClassification = ExposureSourceClassification.ARCHIVE,
                parserVersion = ARCHIVE_PARSER_VERSION,
                historical = true,
                attributeKind = attribute,
                discoveryPath = discoveryPath,
                sourceUrls = sourceUrls.distinct().take(Evidence.MAX_SOURCE_URLS)
            )
            records += record
        }

        metadata.displayName?.let { add(EvidenceKind.Username, HistoricalAttributeKind.DisplayName, it) }
        metadata.bio?.let { add(EvidenceKind.SensitiveSnippet, HistoricalAttributeKind.Bio, it) }
        metadata.username?.let { add(EvidenceKind.Username, HistoricalAttributeKind.Username, it) }
        metadata.avatarUrl?.let { add(EvidenceKind.Image, HistoricalAttributeKind.AvatarUrl, it) }
        metadata.organization?.let { add(EvidenceKind.Organization, HistoricalAttributeKind.Organization, it) }
        metadata.location?.let { add(EvidenceKind.Location, HistoricalAttributeKind.Location, it) }
        metadata.externalLinks.forEach { add(EvidenceKind.Url, HistoricalAttributeKind.ExternalLink, it) }

        return SeedRun(
            execution = SeedExecution(
                seed = seed,
                state = ExecutionState.Verified,
                fetchAttempted = true,
                evidenceIds = records.map(Evidence::id)
            ),
            evidence = records,
            relationships = records.map { record ->
                EvidenceRelationship(
                    fromValue = sourceUrl,
                    toValue = record.value,
                    relation = "mentions",
                    evidence = record.attributeKind?.name,
                    evidenceIds = listOf(record.id)
                )
            },
            path = discoveryPath
        )
    }

    private fun findingEvidence(
        finding: Finding,
        seed: TypedSeed,
        sourceUrl: String,
        providerId: String,
        retrievedAtEpochMillis: Long,
        observedAtEpochMillis: Long?,
        reliability: EvidenceReliability,
        sourceClassification: ExposureSourceClassification,
        historical: Boolean,
        path: List<String>,
            contentHash: String?,
        sourceUrls: List<String>,
        parserVersion: String
    ): Evidence {
        val kind = when (finding.type) {
            FindingType.Email -> EvidenceKind.Email
            FindingType.Phone -> EvidenceKind.Phone
            FindingType.Address -> EvidenceKind.Address
            FindingType.Location -> EvidenceKind.Location
            FindingType.Organization -> EvidenceKind.Organization
            FindingType.Username -> EvidenceKind.Username
            FindingType.SensitiveSnippet -> EvidenceKind.SensitiveSnippet
            else -> EvidenceKind.PublicSearchEvidence
        }
        return Evidence(
            id = evidenceId("finding", seed, sourceUrl, "${finding.type}|${finding.value}"),
            kind = kind,
            value = finding.value,
            sourceUrl = sourceUrl,
            snippet = finding.evidenceSnippet,
            confidence = finding.confidence.coerceIn(0f, 1f),
            risk = finding.risk,
            signals = listOf("Exact value parsed from directly fetched public text") +
                finding.remediation.takeIf(String::isNotBlank).orEmpty(),
            providerId = providerId,
            retrievedAtEpochMillis = retrievedAtEpochMillis,
            observedAtEpochMillis = observedAtEpochMillis,
            state = findingState(finding),
            reliability = reliability,
            sourceClassification = sourceClassification,
            contentHashSha256 = contentHash,
            parserVersion = parserVersion,
            historical = historical,
            discoveryPath = path,
            sourceUrls = sourceUrls.distinct().take(Evidence.MAX_SOURCE_URLS),
            attribution = finding.attribution
        )
    }

    private fun linkEvidence(
        seed: TypedSeed,
        sourceUrl: String,
        link: String,
        providerId: String,
        retrievedAtEpochMillis: Long,
        observedAtEpochMillis: Long?,
        reliability: EvidenceReliability,
        sourceClassification: ExposureSourceClassification,
        historical: Boolean,
        path: List<String>,
        contentHash: String?,
        sourceUrls: List<String>,
        parserVersion: String
    ): Pair<Evidence, EvidenceRelationship>? {
        if (!DiscoveryHttpPolicy.isSafePublicHttpUrl(link)) return null
        val kind = classifyLink(link)
        val explicitlyAttributed = isExplicitlyAttributedPublicLink(sourceUrl, link)
        val record = Evidence(
            id = evidenceId("link", seed, sourceUrl, link),
            kind = kind,
            value = link,
            sourceUrl = sourceUrl,
            confidence = 1.0f,
            risk = RiskLevel.Low,
            signals = listOf(
                if (explicitlyAttributed) {
                    "Exact same-site or allowlisted public link extracted from directly fetched text"
                } else {
                    "Exact safe public link observed in directly fetched text; identity attribution is unconfirmed"
                }
            ),
            providerId = providerId,
            retrievedAtEpochMillis = retrievedAtEpochMillis,
            observedAtEpochMillis = observedAtEpochMillis,
            state = if (explicitlyAttributed) EvidenceState.Verified else EvidenceState.Observed,
            reliability = reliability,
            sourceClassification = sourceClassification,
            contentHashSha256 = contentHash,
            parserVersion = parserVersion,
            historical = historical,
            discoveryPath = path,
            sourceUrls = sourceUrls.distinct().take(Evidence.MAX_SOURCE_URLS),
            attribution = if (explicitlyAttributed) {
                FindingAttribution.Verified
            } else {
                FindingAttribution.Unconfirmed
            }
        )
        return record to EvidenceRelationship(
            fromValue = sourceUrl,
            toValue = link,
            relation = "links_to",
            evidence = "Typed seed page link",
            evidenceIds = listOf(record.id)
        )
    }

    private fun sanitizeSearchResult(
        result: PublicSearchDiscoveryService.PublicSearchResult
    ): PublicSearchDiscoveryService.PublicSearchResult? {
        val url = result.url.trim()
        if (url.length > MAX_SEARCH_URL_CHARS ||
            !DiscoveryHttpPolicy.isSafePublicHttpUrl(url)
        ) return null
        if (result.query.isBlank() || result.source.isBlank() || !result.score.isFinite()) return null

        val title = result.title.trim().take(MAX_SEARCH_TITLE_CHARS)
        val snippet = result.snippet.trim().take(MAX_SEARCH_SNIPPET_CHARS)
        if (title.isBlank() && snippet.isBlank()) return null
        val verifiedPage = result.verifiedPage?.let(::sanitizeVerifiedPage)

        val pivotSourceUrl = result.pivotSourceUrl
            ?.trim()
            ?.takeIf { it.length <= MAX_SEARCH_URL_CHARS }
            ?.takeIf(DiscoveryHttpPolicy::isSafePublicHttpUrl)
        val pivotPath = result.pivotDiscoveryPath
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.take(MAX_SEARCH_PATH_COMPONENT_CHARS) }
            .take(Evidence.MAX_DISCOVERY_PATH_STEPS)
            .toList()
        val pivotEvidenceIds = result.pivotEvidenceIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.take(TypedSeed.MAX_VALUE_CHARS) }
            .take(TypedSeed.MAX_EVIDENCE_IDS)
            .toList()

        return result.copy(
            title = title,
            snippet = snippet,
            url = url,
            query = result.query.trim().take(MAX_SEARCH_QUERY_CHARS),
            source = result.source.trim().take(MAX_SEARCH_PROVIDER_CHARS),
            score = result.score.coerceIn(0f, 1f),
            providerCount = result.providerCount.coerceIn(1, MAX_SEARCH_PROVIDER_COUNT),
            verificationNote = result.verificationNote
                ?.trim()
                ?.take(MAX_SEARCH_VERIFICATION_CHARS)
                ?.takeIf(String::isNotBlank),
            pivotExactValue = result.pivotExactValue
                ?.trim()
                ?.take(TypedSeed.MAX_VALUE_CHARS)
                ?.takeIf(String::isNotBlank),
            pivotNormalizedValue = result.pivotNormalizedValue
                ?.trim()
                ?.take(TypedSeed.MAX_VALUE_CHARS)
                ?.takeIf(String::isNotBlank),
            pivotEvidenceIds = pivotEvidenceIds,
            pivotDiscoveryPath = pivotPath,
            pivotStage = result.pivotStage
                ?.trim()
                ?.take(MAX_SEARCH_PATH_COMPONENT_CHARS)
                ?.takeIf(String::isNotBlank),
            pivotSourceUrl = pivotSourceUrl,
            contentHashSha256 = result.contentHashSha256
                ?.trim()
                ?.take(MAX_SEARCH_HASH_CHARS)
                ?.takeIf(String::isNotBlank)
                ?: verifiedPage?.contentHashSha256,
            verifiedPage = verifiedPage
        )
    }

    private fun sanitizeVerifiedPage(
        page: VerifiedPage
    ): VerifiedPage? {
        val finalUrl = page.finalUrl.trim()
        if (finalUrl.isBlank() || finalUrl.length > MAX_SEARCH_URL_CHARS ||
            !DiscoveryHttpPolicy.isSafePublicHttpUrl(finalUrl)
        ) return null
        val archiveOriginalUrl = page.archiveOriginalUrl
            ?.trim()
            ?.takeIf { it.length <= MAX_SEARCH_URL_CHARS }
            ?.takeIf(DiscoveryHttpPolicy::isSafePublicHttpUrl)
        val links = page.links
            .asSequence()
            .map(String::trim)
            .filter { it.length <= MAX_SEARCH_URL_CHARS }
            .filter(DiscoveryHttpPolicy::isSafePublicHttpUrl)
            .distinctBy(::canonicalUrl)
            .take(MAX_LINKS)
            .toList()
        return page.copy(
            finalUrl = finalUrl,
            title = page.title.trim().take(MAX_SEARCH_TITLE_CHARS),
            text = page.text.trim().take(RESPONSE_TEXT_CHARS),
            links = links,
            contentHashSha256 = page.contentHashSha256
                ?.trim()
                ?.take(MAX_SEARCH_HASH_CHARS)
                ?.takeIf(String::isNotBlank),
            description = page.description.trim().take(MAX_SEARCH_SNIPPET_CHARS),
            archiveProvider = page.archiveProvider
                ?.trim()
                ?.take(MAX_SEARCH_PROVIDER_CHARS)
                ?.takeIf(String::isNotBlank),
            archiveOriginalUrl = archiveOriginalUrl,
            archiveTimestamp = page.archiveTimestamp
                ?.trim()
                ?.take(MAX_ARCHIVE_TIMESTAMP_CHARS)
                ?.takeIf(String::isNotBlank)
        )
    }

    private fun searchDiscoveryPath(
        seed: TypedSeed,
        result: PublicSearchDiscoveryService.PublicSearchResult
    ): List<String> = discoveryPath(
        seed = seed,
        terminal = "search-results",
        extra = buildList {
            addAll(result.pivotDiscoveryPath)
            result.pivotStage?.let { add("stage:$it") }
        }
    )

    private fun searchSourceUrls(
        seed: TypedSeed,
        result: PublicSearchDiscoveryService.PublicSearchResult
    ): List<String> = listOfNotNull(
        result.url,
        seed.sourceUrl,
        result.pivotSourceUrl,
        result.verifiedPage?.archiveOriginalUrl
    )
        .map(String::trim)
        .filter { it.length <= MAX_SEARCH_URL_CHARS }
        .filter(DiscoveryHttpPolicy::isSafePublicHttpUrl)
        .distinctBy(::canonicalUrl)
        .take(Evidence.MAX_SOURCE_URLS)

    private fun searchSignals(
        seed: TypedSeed,
        result: PublicSearchDiscoveryService.PublicSearchResult
    ): List<String> = buildList {
        add("Public search result observation")
        add("Query: ${result.query}")
        add("Provider/source: ${result.source}")
        add("Directly verified: ${result.directlyVerified}")
        result.verificationNote?.let { add("Verification note: $it") }
        result.pivotSeedKind?.let { add("Pivot seed kind: ${it.name}") }
        result.pivotExactValue?.let { add("Pivot exact value: $it") }
        result.pivotNormalizedValue?.let { add("Pivot normalized value: $it") }
        if (result.pivotEvidenceIds.isNotEmpty()) {
            add("Pivot evidence IDs: ${result.pivotEvidenceIds.joinToString(",")}")
        }
        result.pivotStage?.let { add("Pivot stage: $it") }
        result.pivotSourceUrl?.let { add("Pivot source URL: $it") }
        result.contentHashSha256?.let { add("Content hash: $it") }
        add("Seed exact value: ${seed.exactValue}")
        add("Seed normalized value: ${seed.normalizedValue}")
        seed.sourceUrl?.let { add("Seed source URL: $it") }
        if (seed.evidenceIds.isNotEmpty()) {
            add("Seed evidence IDs: ${seed.evidenceIds.joinToString(",")}")
        }
    }
        .map { it.take(MAX_SEARCH_SIGNAL_CHARS) }
        .take(MAX_SEARCH_SIGNALS)

    private fun skipped(seed: TypedSeed, reasonOverride: String? = null): SeedRun {
        val reason = reasonOverride ?: when {
            seed.kind !in TypedSeedSafety.executableKinds ->
                "Seed kind is not executable by this bounded pass"
            !TypedSeedSafety.isSafeExecutableSeed(seed) ->
                if (seed.kind in TypedSeedSafety.publicSearchKinds &&
                    seed.kind !in TypedSeedSafety.publicFetchKinds
                ) {
                    "Seed failed safe public-search admission"
                } else {
                    "Seed failed safe public-fetch admission"
                }
            else -> "Seed was outside the bounded execution budget"
        }
        val state = if (seed.evidenceState == EvidenceState.Candidate) {
            ExecutionState.Candidate
        } else {
            ExecutionState.Skipped
        }
        return SeedRun(
            execution = SeedExecution(seed, state, reason, fetchAttempted = false),
            evidence = emptyList(),
            relationships = emptyList()
        )
    }

    private fun unavailable(
        seed: TypedSeed,
        reason: String,
        fetchAttempted: Boolean = false,
        providerId: String? = null,
        status: ProviderVerificationState? = null
    ): SeedRun {
        val safeSourceUrls = listOfNotNull(
            requestUrl(seed),
            seed.sourceUrl
        )
            .map(String::trim)
            .filter(DiscoveryHttpPolicy::isSafePublicHttpUrl)
            .distinctBy(::canonicalUrl)
            .take(Evidence.MAX_SOURCE_URLS)
        val safeUrl = safeSourceUrls.firstOrNull()
        val historical = seed.kind == TypedSeedKind.Archive
        val now = nowMillis()
        val record = Evidence(
            id = evidenceId("unavailable", seed, safeUrl ?: seed.exactValue, reason),
            kind = seed.kind.toEvidenceKind(),
            value = seed.exactValue,
            sourceUrl = safeUrl,
            snippet = reason.take(MAX_REASON_CHARS),
            confidence = 0f,
            risk = RiskLevel.Low,
            signals = buildList {
                add("Typed seed execution unavailable")
                status?.let { add("Provider state: ${it.name}") }
            },
            providerId = providerId,
            retrievedAtEpochMillis = now,
            state = EvidenceState.Unavailable,
            reliability = when {
                historical -> EvidenceReliability.ArchiveSnapshot
                seed.kind in TypedSeedSafety.publicSearchOnlyKinds ->
                    EvidenceReliability.SearchEngineCandidate
                else -> EvidenceReliability.DirectPersonalWebsite
            },
            sourceClassification = when {
                historical -> ExposureSourceClassification.ARCHIVE
                seed.kind in TypedSeedSafety.publicSearchOnlyKinds ->
                    ExposureSourceClassification.PUBLIC_WEB
                else -> sourceClassification(seed)
            },
            historical = historical,
            discoveryPath = discoveryPath(seed, "unavailable", safeSourceUrls),
            sourceUrls = safeSourceUrls
        )
        return SeedRun(
            execution = SeedExecution(
                seed = seed,
                state = ExecutionState.Unavailable,
                reason = reason.take(MAX_REASON_CHARS),
                fetchAttempted = fetchAttempted,
                evidenceIds = listOf(record.id)
            ),
            evidence = listOf(record),
            relationships = emptyList()
        )
    }

    private fun requestUrl(seed: TypedSeed): String? = when (seed.kind) {
        TypedSeedKind.Domain -> "https://${seed.normalizedValue}"
        TypedSeedKind.Url,
        TypedSeedKind.Document,
        TypedSeedKind.Archive -> seed.normalizedValue
        else -> null
    }

    private fun sourceClassification(seed: TypedSeed): ExposureSourceClassification = when (seed.kind) {
        TypedSeedKind.Document -> ExposureSourceClassification.PUBLIC_DOCUMENT
        TypedSeedKind.Archive -> ExposureSourceClassification.ARCHIVE
        else -> ExposureSourceClassification.PUBLIC_WEB
    }

    private fun TypedSeedKind.toEvidenceKind(): EvidenceKind = when (this) {
        TypedSeedKind.Url -> EvidenceKind.Url
        TypedSeedKind.Domain -> EvidenceKind.Domain
        TypedSeedKind.Document -> EvidenceKind.Document
        TypedSeedKind.Archive -> EvidenceKind.Archive
        else -> EvidenceKind.PublicSearchEvidence
    }

    private fun seedKey(seed: TypedSeed): String =
        "${seed.kind.name}:${seed.normalizedValue.lowercase(Locale.ROOT)}"

    private fun discoveryPath(
        seed: TypedSeed,
        terminal: String,
        extra: List<String> = emptyList()
    ): List<String> =
        (seed.discoveryPath + extra + terminal)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(Evidence.MAX_DISCOVERY_PATH_STEPS)

    private fun parsePublicDocument(raw: String, baseUrl: String): ParsedDocument {
        val doc = Jsoup.parse(raw, baseUrl)
        doc.select("script,style,noscript,svg,template").remove()
        val links = doc.select("a[href]")
            .mapNotNull { element ->
                val absolute = element.attr("abs:href").trim()
                absolute.takeIf(DiscoveryHttpPolicy::isSafePublicHttpUrl)
            }
            .distinctBy(::canonicalUrl)
            .take(MAX_LINKS)
        val text = doc.body()?.text()?.trim().orEmpty()
            .ifBlank { doc.text().trim() }
            .take(RESPONSE_TEXT_CHARS)
        val textLinks = extractTextLinks(text)
        return ParsedDocument(
            title = doc.title().trim().take(MAX_TITLE_CHARS),
            text = text,
            links = (links + textLinks).distinctBy(::canonicalUrl).take(MAX_LINKS)
        )
    }

    private fun extractTextLinks(text: String): List<String> =
        URL_PATTERN.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', ')', ']', '}') }
            .filter { DiscoveryHttpPolicy.isSafePublicHttpUrl(it) }
            .distinctBy(::canonicalUrl)
            .take(MAX_LINKS)
            .toList()

    private fun looksUnsupportedDocument(
        seed: TypedSeed,
        raw: String,
        contentType: String?,
        responseUrl: String? = null
    ): Boolean {
        if (seed.kind !in setOf(TypedSeedKind.Url, TypedSeedKind.Domain, TypedSeedKind.Document, TypedSeedKind.Archive)) {
            return false
        }
        val mediaType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        // The typed executor is a text extractor.  A non-null response media
        // type must be explicitly allowlisted; this prevents an image/audio/
        // video/octet-stream (or an unknown binary vendor type) from being fed
        // to Jsoup merely because the URL happens to look like a page.
        if (mediaType != null && !isAllowedTextMediaType(mediaType)) return true

        val lowerPaths = listOf(seed.normalizedValue, responseUrl.orEmpty())
            .map { it.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT) }
        if (lowerPaths.any { it.substringAfterLast('.', "") in UNSUPPORTED_DOCUMENT_EXTENSIONS }) return true

        // The runtime captures only bounded text, so inspect the leading
        // decoded bytes as well as metadata. This catches servers that omit
        // or mislabel Content-Type (PDF, ZIP/Office, RTF and OLE documents).
        val prefix = raw.take(16)
        return hasUnsupportedMagic(prefix) || looksBinaryText(raw)
    }

    private fun isAllowedTextMediaType(mediaType: String): Boolean =
        mediaType in TextResponsePolicy.ALLOWED_TEXT_CONTENT_TYPES ||
            mediaType.endsWith("+json") ||
            mediaType.endsWith("+xml")

    private fun hasUnsupportedMagic(prefix: String): Boolean =
        prefix.startsWith("%PDF-") ||
            prefix.startsWith("PK\u0003\u0004") ||
            prefix.startsWith("PK\u0005\u0006") ||
            prefix.startsWith("PK\u0007\u0008") ||
            prefix.startsWith("{\\rtf") ||
            prefix.length >= 8 && prefix[0].code == 0xD0 && prefix[1].code == 0xCF &&
            prefix[2].code == 0x11 && prefix[3].code == 0xE0 && prefix[4].code == 0xA1 &&
            prefix[5].code == 0xB1 && prefix[6].code == 0x1A && prefix[7].code == 0xE1 ||
            prefix.startsWith("GIF8") ||
            prefix.startsWith("RIFF") && prefix.contains("WEBP") ||
            prefix.startsWith("ID3") ||
            prefix.startsWith("OggS") ||
            prefix.length >= 8 && prefix.substring(4).startsWith("ftyp") ||
            prefix.length >= 3 && prefix[0].code == 0xFF && prefix[1].code == 0xD8 && prefix[2].code == 0xFF ||
            prefix.length >= 4 && prefix[0].code == 0x89 && prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G'

    private fun looksBinaryText(raw: String): Boolean {
        if (raw.isBlank()) return false
        val sample = raw.take(2_048)
        if ('\uFFFD' in sample) return true
        val controls = sample.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        return controls > maxOf(2, sample.length / 100)
    }

    private fun findingState(finding: Finding): EvidenceState = when (finding.attribution) {
        FindingAttribution.ExactSelfSupplied -> EvidenceState.Verified
        FindingAttribution.IndependentPageSignals -> EvidenceState.Probable
        FindingAttribution.Verified -> EvidenceState.Verified
        FindingAttribution.Probable -> EvidenceState.Probable
        FindingAttribution.Candidate -> EvidenceState.Candidate
        FindingAttribution.Conflicting -> EvidenceState.Conflicting
        FindingAttribution.Unconfirmed -> EvidenceState.Observed
    }

    private fun isExplicitlyAttributedPublicLink(sourceUrl: String, link: String): Boolean {
        val sourceHost = runCatching { URI(sourceUrl).host?.lowercase(Locale.ROOT)?.removeSuffix(".") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return false
        val targetHost = runCatching { URI(link).host?.lowercase(Locale.ROOT)?.removeSuffix(".") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return false
        if (isAllowlistedArchiveHost(targetHost)) return true
        // Host equality is intentionally stricter than registrable-domain
        // matching. Without a maintained public-suffix list, `a.example.co.uk`
        // and `b.other.co.uk` (or two github.io tenants) must not be treated as
        // one identity-attributed site.
        return sourceHost == targetHost
    }

    private fun isAllowlistedArchiveHost(host: String): Boolean =
        TextResponsePolicy.ARCHIVE_LINK_HOSTS.any { host == it || host.endsWith(".$it") }

    /*
     * Keep this list intentionally narrow.  A provider-owned alias can be
     * added only when its source contract is reviewed; arbitrary external
     * navigation remains Observed and cannot recurse.
     */
    private object TextResponsePolicy {
        val ALLOWED_TEXT_CONTENT_TYPES = setOf(
            "text/html",
            "application/xhtml+xml",
            "text/plain",
            "text/csv",
            "application/csv",
            "application/json",
            "text/json",
            "application/xml",
            "text/xml"
        )
        val ARCHIVE_LINK_HOSTS = setOf(
            "web.archive.org",
            "archive.org",
            "archive.today",
            "archive.ph",
            "archive.is"
        )
    }

    private fun classifyLink(link: String): EvidenceKind {
        val uri = runCatching { URI(link) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase(Locale.ROOT)
        if (host == "web.archive.org" || host.endsWith(".web.archive.org") ||
            host == "archive.org" || host.endsWith(".archive.org") ||
            host == "archive.today" || host.endsWith(".archive.today") ||
            host == "archive.ph" || host.endsWith(".archive.ph") ||
            host == "archive.is" || host.endsWith(".archive.is")
        ) return EvidenceKind.Archive
        val path = listOfNotNull(uri?.rawPath, uri?.rawQuery).joinToString("?").lowercase(Locale.ROOT)
        return if (DOCUMENT_EXTENSION.containsMatchIn(path)) EvidenceKind.Document else EvidenceKind.Url
    }

    private fun canonicalUrl(raw: String): String = runCatching {
        val uri = URI(raw)
        URI(
            uri.scheme?.lowercase(Locale.ROOT),
            uri.userInfo,
            uri.host?.lowercase(Locale.ROOT),
            uri.port,
            uri.path,
            uri.query,
            null
        ).toString()
    }.getOrElse { raw.lowercase(Locale.ROOT) }

    private fun parseArchiveTimestamp(raw: String): Long? {
        val value = raw.trim()
        // A capture timestamp is only meaningful at full second precision.
        // Never pad year/month/day prefixes into a fabricated instant.
        if (!value.matches(Regex("\\d{14}"))) return null
        return runCatching {
            LocalDateTime.parse(
                value,
                DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
                    .withResolverStyle(ResolverStyle.STRICT)
            )
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()
    }

    private fun evidenceId(prefix: String, seed: TypedSeed, source: String, value: String): String =
        "$prefix:${sha256("${seed.kind}|${seed.normalizedValue}|$source|$value").take(32)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ParsedDocument(
        val title: String,
        val text: String,
        val links: List<String>
    )

    private data class SeedRun(
        val execution: SeedExecution,
        val evidence: List<Evidence>,
        val relationships: List<EvidenceRelationship>,
        val path: List<String> = emptyList()
    ) {
        fun merge(other: SeedRun): SeedRun = SeedRun(
            execution = execution.copy(
                evidenceIds = (execution.evidenceIds + other.execution.evidenceIds)
                    .distinct()
                    .take(MAX_EVIDENCE_IDS)
            ),
            evidence = (evidence + other.evidence).distinctBy(Evidence::id),
            relationships = EvidenceRelationshipPolicy.normalize(relationships + other.relationships),
            path = path.ifEmpty { other.path }
        )
    }

    companion object {
        const val MAX_SEEDS = 12
        const val MAX_FETCHES = MAX_SEEDS
        const val MAX_CONCURRENT_FETCHES = 3
        const val RESPONSE_TEXT_CHARS = 24_000
        const val MAX_LINKS = 64
        const val MAX_EVIDENCE_IDS = 256
        const val MAX_SEARCH_RESULTS = 34
        private const val MAX_SEARCH_URL_CHARS = 4_096
        private const val MAX_SEARCH_TITLE_CHARS = 240
        private const val MAX_SEARCH_SNIPPET_CHARS = 512
        private const val MAX_SEARCH_QUERY_CHARS = 1_024
        private const val MAX_SEARCH_PROVIDER_CHARS = 128
        private const val MAX_SEARCH_VERIFICATION_CHARS = 512
        private const val MAX_SEARCH_PATH_COMPONENT_CHARS = 256
        private const val MAX_SEARCH_SIGNAL_CHARS = 1_024
        private const val MAX_SEARCH_SIGNALS = 32
        private const val MAX_SEARCH_HASH_CHARS = 128
        private const val MAX_SEARCH_PROVIDER_COUNT = 16
        private const val MAX_ARCHIVE_TIMESTAMP_CHARS = 32
        private const val MAX_REUSABLE_VERIFIED_PAGES = MAX_SEARCH_RESULTS
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_REASON_CHARS = 256
        private const val PARSER_VERSION = "typed-seed-public-v1"
        private const val ARCHIVE_PARSER_VERSION = "typed-seed-archive-v1"
        private val UNSUPPORTED_DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "rtf", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp"
        )
        private val UNSUPPORTED_CONTENT_TYPES = setOf(
            "application/pdf",
            "application/zip",
            "application/octet-stream",
            "application/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation"
        )
        private val DOCUMENT_EXTENSION = Regex(
            "\\.(?:pdf|docx?|rtf|odt|txt|csv|json|xml|xlsx?|pptx?|ods|odp)(?:$|[?#&])",
            RegexOption.IGNORE_CASE
        )
        private val URL_PATTERN = Regex(
            "(?i)(?<![A-Za-z0-9+._-])https?://[^\\s<>\\[\\]{}\"'`]+"
        )

        /**
         * Recognizes known snapshot URL forms without treating the snapshot
         * itself as a Wayback original URL. Unknown/partial capture precision
         * is retained as a null timestamp rather than padded into a false
         * point in time.
         */
        internal fun classifyArchiveSnapshot(raw: String): DirectArchiveSnapshot? {
            val snapshotUrl = raw.trim()
            val uri = runCatching { URI(snapshotUrl) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
            val host = uri.host?.removePrefix("www.")?.lowercase(Locale.ROOT) ?: return null
            if (host == "web.archive.org" || host.endsWith(".web.archive.org")) {
                val path = uri.rawPath.orEmpty()
                val prefix = "/web/"
                if (!path.startsWith(prefix, ignoreCase = true)) return null
                val remainder = path.substring(prefix.length)
                val capture = remainder.substringBefore('/')
                if (capture.isBlank()) return null
                val timestampLength = capture.indexOfFirst { !it.isDigit() }
                    .let { if (it < 0) capture.length else it }
                if (timestampLength !in 4..14) return null
                val timestamp = capture.substring(0, timestampLength)
                val marker = capture.substring(timestampLength)
                if (marker.any { !it.isLetterOrDigit() && it != '_' }) return null
                val originalPart = remainder.substringAfter('/', "")
                if (originalPart.isBlank()) return null
                val rawOriginal = buildString {
                    append(originalPart)
                    uri.rawQuery?.takeIf(String::isNotBlank)?.let {
                        append('?')
                        append(it)
                    }
                }
                val original = rawOriginal
                    .replace("%3A", ":", ignoreCase = true)
                    .replace("%2F", "/", ignoreCase = true)
                    .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?.takeIf(DiscoveryHttpPolicy::isSafePublicHttpUrl)
                return DirectArchiveSnapshot(
                    provider = "Internet Archive Wayback Machine",
                    snapshotUrl = snapshotUrl,
                    originalUrl = original,
                    timestamp = timestamp
                )
            }

            if (host in setOf("archive.ph", "archive.today", "archive.is")) {
                val path = uri.path.orEmpty().trim('/')
                if (path.isBlank() || path.startsWith("newest/", ignoreCase = true)) return null
                val first = path.substringBefore('/')
                if (first.length < 4 || first.equals("search", true) || first.equals("submit", true)) return null
                val original = uri.rawQuery
                    ?.split('&')
                    ?.firstNotNullOfOrNull { parameter ->
                        val parts = parameter.split('=', limit = 2)
                        if (parts.size == 2 && parts[0].equals("url", true)) {
                            parts[1]
                                .replace("%3A", ":", ignoreCase = true)
                                .replace("%2F", "/", ignoreCase = true)
                                .takeIf(DiscoveryHttpPolicy::isSafePublicHttpUrl)
                        } else {
                            null
                        }
                    }
                return DirectArchiveSnapshot(
                    provider = "Archive.today (archive.ph)",
                    snapshotUrl = snapshotUrl,
                    originalUrl = original,
                    timestamp = null
                )
            }
            return null
        }

        private fun defaultProviderRuntime(): ProviderExecutionRuntime {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .dns(DiscoveryHttpPolicy.PUBLIC_DNS)
                .addNetworkInterceptor(DiscoveryHttpPolicy.PUBLIC_URL_INTERCEPTOR)
                .build()
            return ProviderExecutionRuntime(client)
        }

        fun ProviderExecutionResult.isPresent(): Boolean =
            decision.state == ProviderVerificationState.Present
    }
}
