package io.dossier.app.domain.scanner

import io.dossier.app.data.platform.PLATFORMS
import io.dossier.app.data.platform.resolveProfileUrl
import io.dossier.app.data.web.PublicImageSearchService
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.*
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.username.UsernameVariant
import io.dossier.app.domain.username.UsernameVariantGenerator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit


class ProfileScanner(
    private val context: Context,
    private val piiExtractor: PiiExtractor,
    private val variantGenerator: UsernameVariantGenerator
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun scanIdentity(input: IdentityInput, deepResearch: Boolean = false): List<ProfileScanResult> {
        val results = mutableListOf<ProfileScanResult>()

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
                            confidence = baseConf
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

                val matchedTemplate = PLATFORMS.firstOrNull { template ->
                    val domain = template.urlPattern
                        .replace("https://", "")
                        .replace("www.", "")
                        .split("/").firstOrNull() ?: ""
                    domain.isNotBlank() && normalizedUrl.contains(domain, ignoreCase = true)
                }
                // Unknown host → Website, never default to GitHub
                val platform = matchedTemplate?.platform ?: Platform.Website
                val username = normalizedUrl.split("/").lastOrNull { it.isNotBlank() } ?: "unknown"
                
                allCandidates.add(
                    UsernameCandidate(
                        username = username,
                        platform = platform,
                        url = normalizedUrl,
                        matchType = UsernameMatchType.Exact,
                        confidence = 1.0f
                    )
                )
            }
        }

        // Cap the initial fan-out so worst-case scan time stays bounded. Sort by
        // confidence (user-supplied + exact-match candidates first) and take the
        // top N. Pivot discovery can still surface additional handles afterwards.
        val uniqueCandidates = allCandidates
            .distinctBy { it.url }
            .sortedByDescending { it.confidence }
            .take(MAX_INITIAL_CANDIDATES)

        // ---- Pass 1: initial fan-out over name-variant / user-supplied candidates.
        val initialResults = coroutineScope {
            val deferredResults = uniqueCandidates.map { candidate ->
                async(Dispatchers.IO) {
                    fetchAndParse(candidate, input, provenance = null)
                }
            }
            deferredResults.awaitAll()
        }

        // ---- Pass 2 (+ hop 2): multi-hop pivot discovery. For every profile
        // confirmed in pass 1, read its rendered content/links for OTHER handles
        // the user self-disclosed, and check those as new candidates. A second
        // hop runs on newly verified pivot results (bounded total pivots).
        //
        // FAIL-SAFE: the entire pivot pass is wrapped so ANY failure here never
        // destroys the Pass-1 results. A broken pivot must not make the scan
        // return empty — Pass 1's findings are always surfaced.
        val pivotResults: List<ProfileScanResult> = try {
            val scanned = uniqueCandidates.map { it.url.lowercase() }.toMutableSet()
            val hop1 = runPivotPass(
                seedResults = initialResults,
                alreadyScannedUrls = scanned,
                input = input,
                deepResearch = deepResearch,
                remainingBudget = MAX_PIVOT_CANDIDATES
            )
            hop1.forEach { scanned.add(it.candidate.url.lowercase()) }
            // Hop 2: only on newly verified pivot results (soft-existence pages
            // with verified=false do not seed further pivots). Shared budget across hops.
            val usedBudget = hop1.size
            val remaining = (MAX_PIVOT_CANDIDATES - usedBudget).coerceAtLeast(0)
            val hop2Seeds = hop1.filter { it.exists && it.verified }
            val hop2 = if (hop2Seeds.isNotEmpty() && remaining > 0) {
                runPivotPass(
                    seedResults = hop2Seeds,
                    alreadyScannedUrls = scanned,
                    input = input,
                    deepResearch = deepResearch,
                    remainingBudget = remaining
                )
            } else {
                emptyList()
            }
            hop1 + hop2
        } catch (e: Throwable) {
            e.printStackTrace()
            emptyList()
        }

        // ---- Pass 3: public-search discovery. This broadens coverage beyond
        // deterministic username templates by querying public indexes for the
        // audited name/handles/emails and site-specific sources (including
        // Reddit + 4chan). These hits are review candidates, not verified account
        // ownership, so they are surfaced with verified=false.
        val publicSearchResults: List<ProfileScanResult> = try {
            val confirmedUrls = (initialResults + pivotResults)
                .filter { it.exists && it.verified }
                .map { PublicSearchDiscoveryService.canonicalUrlKey(it.candidate.url) }
                .toSet()
            runPublicSearchPass(input, confirmedUrls, deepResearch)
        } catch (e: Throwable) {
            e.printStackTrace()
            emptyList()
        }

        // ---- Pass 4: public image-index discovery. This searches image indexes
        // by identity terms only; it does not upload the user's selfie or perform
        // face recognition.
        val publicImageResults: List<ProfileScanResult> = try {
            runPublicImagePass(input, publicSearchResults, deepResearch)
        } catch (e: Throwable) {
            e.printStackTrace()
            emptyList()
        }

        return initialResults + pivotResults + publicSearchResults + publicImageResults
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
        remainingBudget: Int
    ): List<ProfileScanResult> {
        if (remainingBudget <= 0) return emptyList()
        val scannedUrls = alreadyScannedUrls.toMutableSet()
        val pivotCandidates = mutableListOf<HandleExtractor.PivotCandidate>()
        seedResults.filter { it.exists && it.verified }.forEach { confirmed ->
            if (pivotCandidates.size >= remainingBudget) return@forEach
            val sourceLabel = confirmed.candidate.platform.name
            val pivots = HandleExtractor.extract(
                profileText = confirmed.extractedText,
                profileLinks = confirmed.links,
                sourceUrl = confirmed.candidate.url,
                alreadyScannedUrls = scannedUrls,
                sourcePlatformLabel = sourceLabel
            )
            pivots.forEach { pc ->
                if (pc.candidate.url.lowercase() !in scannedUrls && pivotCandidates.size < remainingBudget) {
                    pivotCandidates.add(pc)
                    scannedUrls.add(pc.candidate.url.lowercase())
                }
            }

            // Deep Research: follow personal-website links self-disclosed on the
            // profile, read their pages for MORE handles, and pivot from those
            // too. (deanonymizer's website link-follower.) Bounded to keep
            // runtime sane.
            if (deepResearch && pivotCandidates.size < remainingBudget) {
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
                        val followed = websiteFollower.follow(siteUrl)
                        if (followed.text.isBlank() && followed.links.isEmpty()) return@forEach
                        val sitePivots = HandleExtractor.extract(
                            profileText = followed.text,
                            profileLinks = followed.links,
                            sourceUrl = siteUrl,
                            alreadyScannedUrls = scannedUrls,
                            sourcePlatformLabel = "$sourceLabel → website"
                        )
                        sitePivots.forEach { pc ->
                            if (pc.candidate.url.lowercase() !in scannedUrls && pivotCandidates.size < remainingBudget) {
                                pivotCandidates.add(pc)
                                scannedUrls.add(pc.candidate.url.lowercase())
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        if (pivotCandidates.isEmpty()) return emptyList()

        return coroutineScope {
            val deferredPivots = pivotCandidates.map { pc ->
                async(Dispatchers.IO) {
                    fetchAndParse(pc.candidate, input, provenance = pc.provenance)
                }
            }
            deferredPivots.awaitAll()
        }
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
                        confidence = baseConfidence
                    )
                )
            }
        }
    }

    /**
     * Two-stage verification:
     *   Stage 1 — OkHttp pre-filter: fast disqualify of *definitive* non-existence
     *             (hard 404, strong soft-404 text, offline). Never confirms existence.
     *   Stage 2 — WebView confirm:   the embedded browser is the authority. A profile
     *             is reported as existing [and verified] only if its rendered DOM
     *             survives [isProfileNotFoundPage] AND [isProfileBelongingToUser].
     */
    private suspend fun fetchAndParse(
        candidate: UsernameCandidate,
        input: IdentityInput,
        provenance: String? = null
    ): ProfileScanResult {
        var exists = false
        var verified = false
        var verificationStatus: String? = null
        var httpStatus: Int? = null
        var displayName: String? = null
        var bio: String? = null
        var profileImageUrl: String? = null
        val links = mutableListOf<String>()
        var extractedText = ""
        val findings = mutableListOf<Finding>()
        val confidenceSignals = mutableListOf<String>()
        var adjustedConfidence = candidate.confidence

        // ---- Stage 1: OkHttp fetch (also a valid confirmer for substantial pages) ---
        var okhttpTitle: String? = null
        var okhttpConfirmed = false

        try {
            val request = Request.Builder()
                .url(candidate.url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .build()

            client.newCall(request).execute().use { response ->
                httpStatus = response.code
                when {
                    response.code == 404 -> {
                        return buildResult(
                            candidate, exists = false, verified = false,
                            verificationStatus = "HTTP 404 — not found",
                            httpStatus = httpStatus, adjustedConfidence = 0.0f
                        )
                    }
                    response.isSuccessful -> {
                        val html = response.body?.string() ?: ""
                        val doc = Jsoup.parse(html, candidate.url)
                        val text = doc.text()
                        val title = doc.title()
                        // Drop on strong, specific not-found phrases (reliable on raw HTML).
                        if (isStrongNotFoundPage(text, title, candidate.platform)) {
                            return buildResult(
                                candidate, exists = false, verified = false,
                                verificationStatus = "Not found (soft-404)",
                                httpStatus = httpStatus, adjustedConfidence = 0.0f
                            )
                        }
                        okhttpTitle = title

                        // If OkHttp already got a SUBSTANTIAL, real page (not a JS shell),
                        // it can confirm existence + belonging directly — no WebView needed.
                        // This is the fix for the zero-results regression: a clean HTTP 200
                        // with real content is a legitimate confirmation (deanonymizer and
                        // every OSINT tool use it). WebView is only the fallback for
                        // SPA/ambiguous/blocked cases OkHttp can't resolve.
                        if (text.trim().length >= 300 &&
                            !isProfileNotFoundPage(html, text, title, candidate.username, candidate.platform)) {
                            if (isProfileBelongingToUser(candidate, text, title, input)) {
                                exists = true
                                verified = true
                                verificationStatus = "✓ Verified (HTTP 200, direct page access)"
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
                                // Soft existence: page is real but not attributed.
                                // Surface as exists=true, verified=false so the report
                                // can show "possible account" without high-risk PII.
                                return buildSoftExistenceResult(
                                    candidate = candidate,
                                    httpStatus = httpStatus,
                                    displayName = title.ifBlank { null },
                                    extractedText = text,
                                    verificationStatus = "Exists but not attributed to this identity — possible account",
                                    provenance = provenance
                                )
                            }
                        }
                        // Otherwise (JS shell / short content / ambiguous) → fall through to WebView.
                    }
                    else -> {
                        // Non-404 error (cloudflare 503, 429, 403...). Let the browser try.
                    }
                }
            }
        } catch (e: Throwable) {
            val isOffline = e is java.net.UnknownHostException ||
                            e is java.net.ConnectException ||
                            e is java.net.SocketTimeoutException
            if (isOffline) {
                return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Offline — could not reach host",
                    httpStatus = null, adjustedConfidence = 0.0f
                )
            }
            // Transient/other errors → fall through to browser attempt.
        }

        // OkHttp already confirmed — skip the (slow) WebView stage entirely and
        // fall through to the shared confidence/PII/finding assembly below.
        if (okhttpConfirmed) {
            return finalizeResult(candidate, exists, verified, verificationStatus, httpStatus,
                displayName, bio, profileImageUrl, links, extractedText, findings, confidenceSignals, adjustedConfidence, input, provenance)
        }

        // ---- Stage 2: WebView confirm (fallback for SPA/ambiguous/blocked) ----
        val render: WebViewScraper.Result = try {
            webviewSemaphore.withPermit {
                WebViewScraper(context).scrape(candidate.url)
            }
        } catch (t: Throwable) {
            WebViewScraper.Result.Failed("Renderer error: ${t.localizedMessage}")
        }

        when (render) {
            is WebViewScraper.Result.ChallengeDetected -> {
                // Bot-check / login wall hides the real content. The profile is
                // *unverifiable* — never a match, never counted as not-found.
                return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — ${render.reason}",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f
                )
            }
            is WebViewScraper.Result.TimedOut -> {
                return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — render timed out",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f
                )
            }
            is WebViewScraper.Result.Failed -> {
                return buildResult(
                    candidate, exists = false, verified = false,
                    verificationStatus = "Unverifiable — ${render.reason}",
                    httpStatus = httpStatus, adjustedConfidence = 0.0f
                )
            }
            is WebViewScraper.Result.Rendered -> {
                val doc = Jsoup.parse(render.html, candidate.url)
                extractedText = render.text

                if (isProfileNotFoundPage(render.html, extractedText, doc.title(), candidate.username, candidate.platform)) {
                    return buildResult(
                        candidate, exists = false, verified = false,
                        verificationStatus = "Not found (rendered 404)",
                        httpStatus = httpStatus, adjustedConfidence = 0.0f
                    )
                }

                // Belonging is decided against the rendered DOM.
                if (isProfileBelongingToUser(candidate, extractedText, doc.title(), input)) {
                    exists = true
                    verified = true
                    verificationStatus = "✓ Verified in-browser"
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
                } else {
                    // Soft existence for WebView path (same policy as OkHttp path).
                    return buildSoftExistenceResult(
                        candidate = candidate,
                        httpStatus = httpStatus,
                        displayName = doc.title().ifBlank { okhttpTitle },
                        extractedText = extractedText,
                        verificationStatus = "Exists but not attributed to this identity — possible account",
                        provenance = provenance
                    )
                }
            }
        }

        return finalizeResult(candidate, exists, verified, verificationStatus, httpStatus,
            displayName, bio, profileImageUrl, links, extractedText, findings, confidenceSignals, adjustedConfidence, input, provenance)
    }

    /**
     * Shared confidence-boost + PII + finding-assembly for a confirmed profile.
     * Used by both the OkHttp-confirmed path and the WebView-confirmed path so
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
        provenance: String?
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
        }

        if (exists && extractedText.isNotBlank()) {
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

        // CRITICAL: only rewrite risk/confidence for PlausibleProfileMatch.
        // PII findings (Email/Phone/etc.) keep the extractor's original risk —
        // overwriting them with profile confidence was elevating every email to
        // Critical whenever the account matched well.
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
            candidate = candidate.copy(confidence = adjustedConfidence),
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
            provenance = provenance
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
        provenance: String?
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
            candidate = candidate.copy(confidence = softConfidence),
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
            provenance = provenance
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
        provenance: String? = null
    ): ProfileScanResult = ProfileScanResult(
        candidate = candidate.copy(confidence = adjustedConfidence),
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
        provenance = provenance
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
        /**
         * Pure (no Context / no network) belonging decision, extracted so it can be
         * unit-tested.
         *
         * IMPORTANT: this is ONLY ever called after the WebView confirm stage has
         * verified that the page actually renders real content (not a 404, not a
         * challenge/bot wall). So unlike the old check #6 — which approved a slug
         * match with zero page verification and manufactured hallucinations — any
         * handle-based acceptance here operates on a verified-existing page.
         *
         * Attribution paths (any one is sufficient):
         *  - user explicitly supplied the URL
         *  - full name / both name parts appear in the rendered page
         *  - a user-supplied email / phone / alias / location / org appears in the page
         *  - the candidate handle embeds BOTH full first and last name (e.g.
         *    "janedoe", "jane.doe" from "Jane Doe") — such a handle
         *    uniquely derives from this person's name, so a verified-existing page
         *    under it is a plausible match. Weak variants (initials like "jdoe",
         *    or single names like "jane"/"doe") do NOT qualify.
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

            val suppliedUsernames = buildList {
                input.primaryUsername?.takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(input.usernames.filter { it.isNotBlank() })
            }
            val exactUsernameMatch = suppliedUsernames.any {
                it.equals(candidateUsername, ignoreCase = true)
            }

            // Strong identity includes explicit usernames / primaryUsername —
            // username-only scans must be able to attribute exact handle matches.
            val hasStrongIdentity = input.fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size > 1 ||
                input.emails.any { it.isNotBlank() } ||
                input.aliases.any { it.isNotBlank() } ||
                input.profileUrls.any { it.isNotBlank() } ||
                input.primaryUsername?.isNotBlank() == true ||
                input.usernames.any { it.isNotBlank() }

            // Exact username match on a verified-existing page is enough when the
            // user supplied that handle (hasStrongIdentity includes usernames).
            if (exactUsernameMatch && hasStrongIdentity) {
                return true
            }

            if (!hasStrongIdentity) {
                return false
            }

            val text = (extractedText + " " + (displayName ?: "")).lowercase()
            val fullNameLower = input.fullName.trim().lowercase()
            val nameParts = input.fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val isSingleWordName = nameParts.size <= 1

            // Single-word name is restricted: allow only when email/alias present
            // (handled below via page matches) OR exact username match (above).
            // Without those, fall through carefully — name-embedding is multi-word only.

            // 1. Check for full name match
            if (fullNameLower.isNotBlank() && text.contains(fullNameLower)) {
                // Single-word name alone is too weak unless email/alias also present
                // or exact username already matched (handled above).
                if (!isSingleWordName) return true
                val hasEmailOrAlias = input.emails.any { it.isNotBlank() } ||
                    input.aliases.any { it.isNotBlank() }
                if (hasEmailOrAlias) return true
            }

            // 2. Check if both first and last name match (for multi-word names)
            if (nameParts.size > 1) {
                val first = nameParts.first().lowercase()
                val last = nameParts.last().lowercase()
                if (text.contains(first) && text.contains(last)) {
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
            val hasMeaningfulIdentitySignals = nameParts.size > 1 ||
                input.primaryUsername?.isNotBlank() == true ||
                input.usernames.any { it.isNotBlank() } ||
                input.emails.any { it.isNotBlank() } ||
                input.aliases.any { it.isNotBlank() }
            if (hasMeaningfulIdentitySignals) {
                val hasMatchingAlias = input.aliases.any { it.isNotBlank() && text.contains(it.lowercase()) }
                val hasMatchingLoc = input.locations.any { it.isNotBlank() && text.contains(it.lowercase()) }
                val hasMatchingOrg = input.organizations.any { it.isNotBlank() && text.contains(it.lowercase()) }
                if (hasMatchingAlias || hasMatchingLoc || hasMatchingOrg) {
                    return true
                }
            }

            // 5. Name-derived handle: the candidate username embeds BOTH the full first
            //    AND last name (e.g. "janedoe", "jane.doe", "jane_doe" from
            //    "Jane Doe"). Multi-word names only.
            if (nameParts.size > 1) {
                val first = nameParts.first().lowercase()
                val last = nameParts.last().lowercase()
                val slug = candidateUsername.lowercase()
                if (first.length >= 3 && last.length >= 3 && slug.contains(first) && slug.contains(last)) {
                    return true
                }
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
        return results.toEvidenceCollection(input)
    }

    /** Maps already-fetched [ProfileScanResult]s to Evidence without re-scanning. */
    fun toEvidenceCollection(
        results: List<ProfileScanResult>,
        input: IdentityInput
    ): EvidenceCollection = results.toEvidenceCollection(input)
}

/**
 * Converts scanner [ProfileScanResult]s into a native [EvidenceCollection],
 * asserting the scanner-known relationships (username↔profile, PII-on-profile)
 * that are not recoverable from findings alone.
 */
internal fun List<ProfileScanResult>.toEvidenceCollection(
    input: IdentityInput
): EvidenceCollection {
    val evidence = mutableListOf<Evidence>()
    val relationships = mutableListOf<EvidenceRelationship>()

    forEach { result ->
        val url = result.candidate.url
        val conf = result.candidate.confidence.coerceIn(0f, 1f)

        // Profile observation (native, not via the Finding adapter).
        evidence.add(
            Evidence(
                id = "profile:${url}",
                kind = EvidenceKind.Profile,
                value = url,
                sourceUrl = url,
                snippet = result.displayName?.let { "Profile: $it" },
                confidence = conf,
                risk = if (result.verified && result.exists) RiskLevel.High else RiskLevel.Low,
                signals = result.confidenceSignals
            )
        )

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
                    evidence = result.candidate.platform.name
                )
            )
        }

        // Each finding bridges losslessly; PII-on-profile is asserted explicitly.
        result.findings.forEach { finding ->
            evidence.add(finding.toEvidence())
            if (finding.sourceUrl == url || result.exists) {
                relationships.add(
                    EvidenceRelationship(
                        fromValue = url,
                        toValue = finding.value,
                        relation = "mentions",
                        evidence = finding.type.name
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
