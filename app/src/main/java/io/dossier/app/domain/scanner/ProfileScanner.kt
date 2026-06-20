package io.dossier.app.domain.scanner

import io.dossier.app.data.platform.PLATFORMS
import io.dossier.app.data.platform.resolveProfileUrl
import io.dossier.app.data.web.PublicImageSearchService
import io.dossier.app.data.web.PublicSearchDiscoveryService
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

        // If no usernames/primary username provided but name is, derive candidates from name
        val nameBasedCandidates: List<UsernameVariant> = if (usernames.isEmpty() && input.fullName.isNotBlank()) {
            variantGenerator.generateFromName(input.fullName)
        } else {
            emptyList()
        }

        // Username-provided candidates
        usernames.forEach { baseUser ->
            val variants = variantGenerator.generate(baseUser)
            variants.forEach { variant ->
                PLATFORMS.forEach { template ->
                    if (template.shouldFetchByDefault) {
                        val profileUrl = template.urlPattern.replace("{username}", variant.username)
                        allCandidates.add(
                            UsernameCandidate(
                                username = variant.username,
                                platform = template.platform,
                                url = profileUrl,
                                matchType = variant.type,
                                confidence = if (variant.type == UsernameMatchType.Exact) 1.0f else 0.8f
                            )
                        )
                    }
                }
            }
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
                val platform = matchedTemplate?.platform ?: Platform.GitHub
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

        // ---- Pass 2: one-hop pivot discovery. For every profile confirmed in
        // pass 1, read its rendered content/links for OTHER handles the user
        // self-disclosed, and check those as new candidates. This is the only way
        // to find handles like "samplecaster" that don't derive from the name.
        // (deanonymizer-style cross-platform pivot.)
        //
        // FAIL-SAFE: the entire pivot pass is wrapped so ANY failure here never
        // destroys the Pass-1 results. A broken pivot must not make the scan
        // return empty — Pass 1's findings are always surfaced.
        val pivotResults: List<ProfileScanResult> = try {
            runPivotPass(initialResults, uniqueCandidates.map { it.url.lowercase() }.toSet(), input, deepResearch)
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
        initialResults: List<ProfileScanResult>,
        alreadyScannedUrls: Set<String>,
        input: IdentityInput,
        deepResearch: Boolean
    ): List<ProfileScanResult> {
        val scannedUrls = alreadyScannedUrls.toMutableSet()
        val pivotCandidates = mutableListOf<HandleExtractor.PivotCandidate>()
        initialResults.filter { it.exists && it.verified }.forEach { confirmed ->
            val sourceLabel = confirmed.candidate.platform.name
            val pivots = HandleExtractor.extract(
                profileText = confirmed.extractedText,
                profileLinks = confirmed.links,
                sourceUrl = confirmed.candidate.url,
                alreadyScannedUrls = scannedUrls,
                sourcePlatformLabel = sourceLabel
            )
            pivots.forEach { pc ->
                if (pc.candidate.url.lowercase() !in scannedUrls && pivotCandidates.size < MAX_PIVOT_CANDIDATES) {
                    pivotCandidates.add(pc)
                    scannedUrls.add(pc.candidate.url.lowercase())
                }
            }

            // Deep Research: follow personal-website links self-disclosed on the
            // profile, read their pages for MORE handles, and pivot from those
            // too. (deanonymizer's website link-follower.) Bounded to keep
            // runtime sane.
            if (deepResearch && pivotCandidates.size < MAX_PIVOT_CANDIDATES) {
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
                            if (pc.candidate.url.lowercase() !in scannedUrls && pivotCandidates.size < MAX_PIVOT_CANDIDATES) {
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

    // Bounds the one-hop pivot pass so it can't run away.
    private val MAX_PIVOT_CANDIDATES = 20
    // Bounds the Deep Research website link-following per confirmed profile.
    private val MAX_WEBSITE_FOLLOWS = 3
    // Bounds the initial fan-out (name-variants × platforms) so a long name with
    // many variants doesn't produce 100+ candidates. Sorted by confidence first.
    private val MAX_INITIAL_CANDIDATES = 60

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
                                // Substantial page exists but doesn't belong to the user.
                                return buildResult(
                                    candidate, exists = false, verified = false,
                                    verificationStatus = "Exists but not attributed to this identity",
                                    httpStatus = httpStatus, adjustedConfidence = 0.0f
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
                    return buildResult(
                        candidate, exists = false, verified = false,
                        verificationStatus = "Exists but not attributed to this identity",
                        httpStatus = httpStatus, adjustedConfidence = 0.0f
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

        val finalFindings = findings.map { finding ->
            finding.copy(
                confidence = adjustedConfidence,
                risk = when {
                    adjustedConfidence > 0.85f -> RiskLevel.Critical
                    adjustedConfidence > 0.70f -> RiskLevel.High
                    adjustedConfidence > 0.50f -> RiskLevel.Medium
                    else -> RiskLevel.Low
                }
            )
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
        val withScheme = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> trimmed
        }
        if (!withScheme.startsWith("http://", ignoreCase = true) &&
            !withScheme.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return withScheme.substringBefore("#")
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

            // If user provided almost no identity signals, require VERY strong evidence.
            // With only a single-word name and nothing else, we have no reliable way to
            // distinguish the user's real accounts from random strangers — so ONLY accept
            // URLs the user explicitly supplied (handled above).
            val hasStrongIdentity = input.fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size > 1 ||
                input.emails.any { it.isNotBlank() } ||
                input.aliases.any { it.isNotBlank() } ||
                input.profileUrls.any { it.isNotBlank() }

            if (!hasStrongIdentity) {
                return false
            }

            val text = (extractedText + " " + (displayName ?: "")).lowercase()
            val fullNameLower = input.fullName.trim().lowercase()
            val nameParts = input.fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

            // 1. Check for full name match
            if (fullNameLower.isNotBlank() && text.contains(fullNameLower)) {
                return true
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
            // Guard: skip this for single-word names with no other identity signals,
            // because aliases/locations/orgs will be empty and location-only matches are too broad.
            val hasMeaningfulIdentitySignals = nameParts.size > 1 ||
                input.primaryUsername?.isNotBlank() == true ||
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

            // 5. If the username exactly matches the user's primary username, require corroborating
            // content on the page. A username match alone is NOT enough — random people can have
            // the same username on different platforms.
            if (input.primaryUsername?.equals(candidateUsername, ignoreCase = true) == true) {
                // For multi-word names: accept if the page contains the full name OR both name parts
                if (nameParts.size > 1 && candidateUsername.length >= 8) {
                    val first = nameParts.first().lowercase()
                    val last = nameParts.last().lowercase()
                    if (text.contains(fullNameLower) || (text.contains(first) && text.contains(last))) {
                        return true
                    }
                }
                // For any name length: accept if page contains a user-supplied email or alias
                val emailOnPage = input.emails.any { it.isNotBlank() && text.contains(it.lowercase()) }
                val aliasOnPage = input.aliases.any { it.isNotBlank() && text.contains(it.lowercase()) }
                if (emailOnPage || aliasOnPage) {
                    return true
                }
                // Otherwise: primary-username match alone is insufficient — fall through to #6.
            }

            // 6. Name-derived handle: the candidate username embeds BOTH the full first
            //    AND last name (e.g. "janedoe", "jane.doe", "jane_doe" from
            //    "Jane Doe"). Such a handle uniquely derives from this person's name,
            //    so a verified-existing page under it is accepted as a plausible match.
            //    Guards:
            //      - multi-word name only (single-word names can't disambiguate)
            //      - both parts >= 3 chars (avoid tiny fragments)
            //      - the slug contains each FULL part (rejects initials like "jdoe" and
            //        single names like "jane"/"doe", which stay ambiguous without
            //        page-text corroboration).
            //    Safe because this runs only after the WebView confirm stage verified the
            //    page renders real content — not the old unverified-slug hallucination.
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
}
