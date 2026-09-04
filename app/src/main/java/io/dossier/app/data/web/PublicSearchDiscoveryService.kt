package io.dossier.app.data.web

import android.content.Context
import io.dossier.app.data.platform.resolveProfileUrl
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bounded public-search discovery for consented self-audits.
 *
 * Uses several meaningfully different indexes while keeping concurrency and provider
 * budgets bounded. Search snippets remain lead generators: the strongest candidates
 * are re-fetched and must expose identity signals before receiving high confidence.
 */
class PublicSearchDiscoveryService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val pageVerifier = PublicPageVerifier()
    private val cache = ConcurrentHashMap<String, CachedResults>()
    private val breaker = ProviderCircuitBreaker()
    private val browserSemaphore = Semaphore(1)

    data class PublicSearchResult(
        val title: String,
        val snippet: String,
        val url: String,
        val query: String,
        val source: String,
        val score: Float = 0f,
        val providerCount: Int = 1,
        val directlyVerified: Boolean = false,
        val verificationNote: String? = null
    )

    private data class SearchProvider(
        val name: String,
        val searchUrl: (String) -> String,
        val allowBrowserFallback: Boolean = true
    )

    private data class CachedResults(
        val savedAtMillis: Long,
        val results: List<PublicSearchResult>
    )

    suspend fun discover(input: IdentityInput, deepResearch: Boolean = false): List<PublicSearchResult> =
        withContext(Dispatchers.IO) {
            val queryLimit = if (deepResearch) MAX_DEEP_QUERIES else MAX_DEFAULT_QUERIES
            val queries = buildSearchQueries(input, deepResearch).take(queryLimit)
            if (queries.isEmpty()) return@withContext emptyList()

            val providers = defaultProviders()
            val querySemaphore = Semaphore(MAX_PARALLEL_SEARCH_QUERIES)
            val raw = coroutineScope {
                queries.mapIndexed { index, query ->
                    async(Dispatchers.IO) {
                        querySemaphore.withPermit {
                            searchWithFailover(
                                query = query,
                                providers = providers,
                                startIndex = index % providers.size,
                                deepResearch = deepResearch
                            )
                        }
                    }
                }.awaitAll().flatten()
            }

            val scored = mergeProviderEvidence(raw)
                .map { it.copy(score = scoreResult(input, it)) }
                .filter { it.score >= MIN_INDEX_SCORE }
                .sortedByDescending { it.score }
                .take(MAX_PRE_VERIFICATION_RESULTS)

            val verificationKeys = scored
                .take(MAX_DIRECT_VERIFICATIONS)
                .map { canonicalUrlKey(it.url) }
                .toSet()
            val verifySemaphore = Semaphore(MAX_PARALLEL_DIRECT_VERIFICATIONS)

            coroutineScope {
                scored.map { result ->
                    async(Dispatchers.IO) {
                        if (canonicalUrlKey(result.url) !in verificationKeys) {
                            return@async result.copy(
                                score = result.score.coerceAtMost(INDEX_ONLY_CONFIDENCE_CEILING),
                                verificationNote = "Indexed lead; source page not re-fetched due to scan budget"
                            )
                        }

                        verifySemaphore.withPermit {
                            when (val verification = pageVerifier.verify(
                                input = input,
                                url = result.url,
                                indexedTitle = result.title,
                                indexedSnippet = result.snippet
                            )) {
                                is PublicPageVerifier.Outcome.Verified -> {
                                    val blended = (
                                        result.score * INDEX_WEIGHT +
                                            verification.directScore * DIRECT_PAGE_WEIGHT +
                                            consensusBonus(result.providerCount)
                                        ).coerceIn(0f, verification.confidenceCeiling)
                                    result.copy(
                                        title = verification.title.ifBlank { result.title },
                                        snippet = verification.snippet.ifBlank { result.snippet },
                                        url = verification.finalUrl,
                                        score = blended,
                                        directlyVerified = true,
                                        verificationNote = verification.signals.joinToString("; ")
                                    )
                                }
                                is PublicPageVerifier.Outcome.Rejected -> null
                                is PublicPageVerifier.Outcome.Unavailable -> result.copy(
                                    score = result.score.coerceAtMost(INDEX_ONLY_CONFIDENCE_CEILING),
                                    verificationNote = "Indexed lead only: ${verification.reason}"
                                )
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
                .filter { it.score >= MIN_PUBLIC_SEARCH_SCORE }
                .distinctBy { canonicalUrlKey(it.url) }
                .sortedWith(
                    compareByDescending<PublicSearchResult> { it.directlyVerified }
                        .thenByDescending { it.score }
                        .thenByDescending { it.providerCount }
                        .thenBy { it.title }
                )
                .take(MAX_PUBLIC_SEARCH_RESULTS)
        }

    private suspend fun searchWithFailover(
        query: String,
        providers: List<SearchProvider>,
        startIndex: Int,
        deepResearch: Boolean
    ): List<PublicSearchResult> {
        val ordered = providers.indices.map { providers[(startIndex + it) % providers.size] }
        val merged = mutableListOf<PublicSearchResult>()
        var attemptedProviders = 0
        val highSignal = isHighSignalQuery(query)
        val providerBudget = when {
            deepResearch && highSignal -> 5
            deepResearch -> 4
            highSignal -> 3
            else -> 2
        }

        for (provider in ordered) {
            if (attemptedProviders >= providerBudget) break
            if (!breaker.canAttempt(provider.name)) continue
            attemptedProviders++
            merged += fetchProviderResults(provider, query)
            val uniqueCount = merged.distinctBy { canonicalUrlKey(it.url) }.size
            val independentSources = merged.map { it.source }.distinct().size

            if (highSignal) {
                if (independentSources >= MIN_PROVIDERS_FOR_HIGH_SIGNAL_QUERY &&
                    uniqueCount >= MIN_HIGH_SIGNAL_RESULTS_BEFORE_STOP) break
            } else if (uniqueCount >= MIN_RESULTS_BEFORE_STOP) {
                break
            }
        }

        return merged
            .distinctBy { "${it.source}|${canonicalUrlKey(it.url)}" }
            .take(MAX_RESULTS_PER_QUERY * providerBudget)
    }

    private suspend fun fetchProviderResults(
        provider: SearchProvider,
        query: String
    ): List<PublicSearchResult> {
        val cacheKey = "${provider.name}|$query"
        cache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAtMillis <= CACHE_TTL_MS) return cached.results
            cache.remove(cacheKey, cached)
        }

        val searchUrl = provider.searchUrl(query)
        var lastHtml = ""
        var providerHealthy = false
        var blocked = false

        for (attempt in 0 until MAX_HTTP_ATTEMPTS) {
            try {
                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", userAgentFor(attempt))
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    lastHtml = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful && lastHtml.length >= MIN_SEARCH_HTML_BYTES &&
                            !DiscoveryHttpPolicy.looksBlocked(lastHtml) -> {
                            providerHealthy = true
                            val parsed = parseSearchResults(provider.name, query, lastHtml)
                            if (parsed.isNotEmpty() || looksLikeNoResults(lastHtml)) {
                                breaker.recordSuccess(provider.name)
                                cache[cacheKey] = CachedResults(System.currentTimeMillis(), parsed)
                                return parsed
                            }
                        }
                        DiscoveryHttpPolicy.isTransientHttpStatus(response.code) -> {
                            if (attempt < MAX_HTTP_ATTEMPTS - 1) {
                                delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, response.header("Retry-After")))
                            }
                        }
                        DiscoveryHttpPolicy.looksBlocked(lastHtml) -> blocked = true
                    }
                }
                if (blocked) break
            } catch (_: Exception) {
                if (attempt < MAX_HTTP_ATTEMPTS - 1) {
                    delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, null))
                }
            }
        }

        if (provider.allowBrowserFallback &&
            (lastHtml.isBlank() || blocked || (providerHealthy && !looksLikeNoResults(lastHtml)))) {
            val rendered = browserSemaphore.withPermit {
                when (val result = io.dossier.app.domain.scanner.WebViewScraper(context).scrape(searchUrl)) {
                    is io.dossier.app.domain.scanner.WebViewScraper.Result.Rendered ->
                        parseSearchResults(provider.name, query, result.html)
                    else -> emptyList()
                }
            }
            if (rendered.isNotEmpty()) {
                breaker.recordSuccess(provider.name)
                cache[cacheKey] = CachedResults(System.currentTimeMillis(), rendered)
                return rendered
            }
        }

        if (providerHealthy) {
            breaker.recordSuccess(provider.name)
            cache[cacheKey] = CachedResults(System.currentTimeMillis(), emptyList())
        } else {
            breaker.recordFailure(provider.name)
        }
        return emptyList()
    }

    private fun defaultProviders(): List<SearchProvider> = listOf(
        // Independent or meaningfully different indexes first; rotation prevents any one
        // provider from receiving every query.
        SearchProvider("Yandex", ::yandexUrl),
        SearchProvider("Brave", ::braveUrl),
        SearchProvider("Mojeek", ::mojeekUrl),
        SearchProvider("DuckDuckGo", ::duckDuckGoUrl),
        SearchProvider("Qwant", ::qwantUrl),
        SearchProvider("Bing", ::bingUrl)
    )

    companion object {
        private const val MAX_DEFAULT_QUERIES = 24
        private const val MAX_DEEP_QUERIES = 40
        private const val MAX_PARALLEL_SEARCH_QUERIES = 3
        private const val MAX_PARALLEL_DIRECT_VERIFICATIONS = 3
        private const val MAX_DIRECT_VERIFICATIONS = 28
        private const val MAX_PRE_VERIFICATION_RESULTS = 58
        private const val MAX_PUBLIC_SEARCH_RESULTS = 34
        private const val MAX_RESULTS_PER_QUERY = 8
        private const val MIN_RESULTS_BEFORE_STOP = 5
        private const val MIN_HIGH_SIGNAL_RESULTS_BEFORE_STOP = 2
        private const val MIN_PROVIDERS_FOR_HIGH_SIGNAL_QUERY = 3
        private const val MIN_INDEX_SCORE = 0.22f
        private const val MIN_PUBLIC_SEARCH_SCORE = 0.30f
        private const val MIN_SEARCH_HTML_BYTES = 500
        private const val MAX_HTTP_ATTEMPTS = 2
        private const val CACHE_TTL_MS = 20 * 60 * 1_000L
        private const val INDEX_ONLY_CONFIDENCE_CEILING = 0.58f
        private const val INDEX_WEIGHT = 0.42f
        private const val DIRECT_PAGE_WEIGHT = 0.68f
        private const val PROVIDER_CONSENSUS_BONUS = 0.04f
        private const val MAX_PROVIDER_CONSENSUS_BONUS = 0.12f

        private val USER_AGENTS = listOf(
            "Dossier/0.1 public-exposure-audit",
            "Dossier/0.1 public-exposure-audit",
            "Dossier/0.1 public-exposure-audit"
        )

        private val PROFILE_QUERY_SITES = listOf(
            "github.com", "linkedin.com/in", "x.com", "twitter.com", "reddit.com/user",
            "twitch.tv", "instagram.com", "youtube.com", "gitlab.com", "medium.com",
            "dev.to", "bsky.app/profile", "mastodon.social"
        )

        private val RELIABLE_PROFILE_QUERY_SITES = listOf(
            "github.com", "reddit.com/user", "youtube.com", "gitlab.com", "dev.to",
            "bsky.app/profile", "news.ycombinator.com/user"
        )

        private val PUBLIC_FORUM_QUERY_SITES = listOf(
            "reddit.com", "4chan.org", "boards.4chan.org"
        )

        private val SEARCH_ENGINE_HOST_FRAGMENTS = setOf(
            "duckduckgo.com", "google.com", "bing.com", "yandex.com", "yandex.ru",
            "search.brave.com", "brave.com", "mojeek.com", "qwant.com"
        )

        private val TRACKING_QUERY_PARAMS = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "gclid", "fbclid", "msclkid", "ref", "ref_src", "ved", "source", "yclid"
        )

        /** High-entropy identifiers are deliberately placed before broad name queries. */
        fun buildSearchQueries(input: IdentityInput, deepResearch: Boolean = false): List<String> {
            val queries = linkedSetOf<String>()
            val name = input.fullName.trim()
            val handles = buildHandleTerms(input)
            val aliases = input.aliases.mapNotNull(::cleanTerm)
            val emails = input.emails.mapNotNull(::cleanTerm)
            val organizations = input.organizations.mapNotNull(::cleanTerm)
            val locations = input.locations.mapNotNull(::cleanTerm)

            emails.take(if (deepResearch) 4 else 2).forEach { email ->
                queries += quote(email)
                val local = email.substringBefore('@').trim().removePrefix("+")
                if (local.length >= 3) queries += quote(local)
                queries += "${quote(email)} site:github.com"
                if (deepResearch) {
                    queries += "${quote(email)} site:pastebin.com"
                    queries += "${quote(email)} site:gitlab.com"
                }
            }

            input.phones
                .map { value -> value.filter(Char::isDigit) }
                .filter { it.length >= 8 }
                .distinct()
                .take(if (deepResearch) 3 else 2)
                .forEach { digits ->
                    queries += quote(digits)
                    if (digits.length >= 10) queries += quote(digits.takeLast(10))
                }

            handles.take(if (deepResearch) 8 else 5).forEach { handle ->
                val quotedHandle = quote(handle)
                queries += quotedHandle
                RELIABLE_PROFILE_QUERY_SITES.forEach { site -> queries += "$quotedHandle site:$site" }
                queries += "$quotedHandle github reddit gitlab dev.to bluesky youtube"
            }

            if (name.isNotBlank()) {
                val quotedName = quote(name)
                organizations.take(2).forEach { org -> queries += "$quotedName ${quote(org)}" }
                locations.take(2).forEach { location -> queries += "$quotedName ${quote(location)}" }
                handles.take(2).forEach { handle -> queries += "$quotedName ${quote(handle)}" }
                queries += quotedName
                queries += "$quotedName github linkedin x twitter reddit twitch instagram youtube"
                PROFILE_QUERY_SITES.forEach { site -> queries += "$quotedName site:$site" }
                PUBLIC_FORUM_QUERY_SITES.forEach { site -> queries += "$quotedName site:$site" }
            }

            aliases.take(if (deepResearch) 6 else 3).forEach { alias ->
                val quotedAlias = quote(alias)
                queries += quotedAlias
                queries += "$quotedAlias site:reddit.com"
                if (deepResearch) {
                    queries += "$quotedAlias site:4chan.org"
                    queries += "$quotedAlias site:boards.4chan.org"
                }
            }
            return queries.toList()
        }

        fun parseSearchResults(source: String, query: String, html: String): List<PublicSearchResult> {
            if (html.isBlank() || DiscoveryHttpPolicy.looksBlocked(html)) return emptyList()
            val root = Jsoup.parse(html).body()
            val results = when {
                source.contains("duck", true) -> parseDuckDuckGo(root, source, query)
                source.contains("bing", true) -> parseBing(root, source, query)
                source.contains("yandex", true) -> parseYandex(root, source, query)
                source.contains("brave", true) -> parseBrave(root, source, query)
                source.contains("mojeek", true) -> parseMojeek(root, source, query)
                source.contains("qwant", true) -> parseQwant(root, source, query)
                else -> parseGeneric(root, source, query)
            }
            return results.distinctBy { canonicalUrlKey(it.url) }.take(12)
        }

        private fun parseDuckDuckGo(root: Element, source: String, query: String) =
            parseBlocks(
                root, ".result, .results_links, .web-result",
                ".result__a[href], a.result__a[href], h2 a[href]",
                ".result__snippet, a.result__snippet, .result-snippet",
                source, query
            )

        private fun parseBing(root: Element, source: String, query: String) =
            parseBlocks(
                root, "li.b_algo, .b_algo",
                "h2 a[href], h3 a[href]",
                ".b_caption p, .b_snippet, p",
                source, query
            )

        private fun parseYandex(root: Element, source: String, query: String) =
            parseBlocks(
                root, "li.serp-item, .serp-item, .Organic, .organic",
                ".OrganicTitle-Link[href], h2 a[href], h3 a[href], a.Link[href], a.organic__url[href]",
                ".OrganicTextContentSpan, .TextContainer, .organic__content-wrapper, .organic__text, .serp-item__text, p",
                source, query
            )

        private fun parseBrave(root: Element, source: String, query: String) =
            parseBlocks(
                root, ".snippet, .result, .search-result, [data-type=web], article",
                "a[data-testid=result-header][href], .snippet-title a[href], a.result-header[href], h2 a[href], h3 a[href]",
                ".snippet-description, .snippet-content, .description, p",
                source, query
            )

        private fun parseMojeek(root: Element, source: String, query: String) =
            parseBlocks(
                root, ".results-standard li, .results li, .result, article",
                "a.title[href], h2 a[href], h3 a[href]",
                ".s, .snippet, .description, p",
                source, query
            )

        private fun parseQwant(root: Element, source: String, query: String) =
            parseBlocks(
                root, "article, [data-testid*=webResult], .web-result, .result",
                "a[data-testid*=title][href], h2 a[href], h3 a[href], a[href]",
                "[data-testid*=description], .description, .snippet, p",
                source, query
            )

        private fun parseBlocks(
            root: Element,
            blockSelector: String,
            linkSelector: String,
            snippetSelector: String,
            source: String,
            query: String
        ): List<PublicSearchResult> = root.select(blockSelector).mapNotNull { block ->
            resultFromBlock(block, linkSelector, snippetSelector, source, query)
        }.ifEmpty { parseGeneric(root, source, query) }

        private fun parseGeneric(root: Element, source: String, query: String): List<PublicSearchResult> {
            val blocks = root.select(
                ".result, .web-result, .results_links, li.b_algo, div.g, li.serp-item, " +
                    ".organic, .Organic, article, .snippet, .search-result"
            )
            if (blocks.isNotEmpty()) {
                val parsed = blocks.mapNotNull { block ->
                    resultFromBlock(
                        block,
                        ".result__a[href], a.result__a[href], h2 a[href], h3 a[href], a.title[href], a[href]",
                        ".result__snippet, .snippet, .b_caption p, .VwiC3b, .organic__content-wrapper, " +
                            ".organic__text, .OrganicTextContentSpan, .description, p",
                        source,
                        query
                    )
                }
                if (parsed.isNotEmpty()) return parsed
            }

            return root.select("a[href]").mapNotNull { anchor ->
                val url = normalizeSearchUrl(anchor.attr("href")) ?: return@mapNotNull null
                if (isNoisyResultUrl(url)) return@mapNotNull null
                val title = anchor.text().trim().takeIf { it.length >= 4 } ?: return@mapNotNull null
                PublicSearchResult(title.take(160), "", url, query, source)
            }
        }

        private fun resultFromBlock(
            block: Element,
            linkSelector: String,
            snippetSelector: String,
            source: String,
            query: String
        ): PublicSearchResult? {
            val linkElement = block.select(linkSelector).firstOrNull() ?: return null
            val url = normalizeSearchUrl(
                linkElement.attr("abs:href").ifBlank { linkElement.attr("href") }
            ) ?: return null
            if (isNoisyResultUrl(url)) return null
            val title = linkElement.text().trim().ifBlank {
                block.select("h2, h3, .result__title, .organic__url-text, .OrganicTitle").text().trim()
            }
            val snippet = block.select(snippetSelector).text().trim().ifBlank {
                block.text().removePrefix(title).trim()
            }
            if (title.isBlank() && snippet.isBlank()) return null
            return PublicSearchResult(
                title = title.ifBlank { "Untitled result" }.take(160),
                snippet = snippet.take(320),
                url = url,
                query = query,
                source = source
            )
        }

        fun scoreResult(input: IdentityInput, result: PublicSearchResult): Float {
            val combined = "${result.title} ${result.snippet} ${result.url}".lowercase()
            var score = 0.08f
            var directIdentitySignals = 0

            val name = input.fullName.trim()
            if (name.isNotBlank() && combined.contains(name.lowercase())) {
                score += 0.30f
                directIdentitySignals++
            }
            val nameParts = name.lowercase().split("\\s+".toRegex()).filter { it.length >= 3 }
            if (nameParts.size >= 2 && nameParts.all(combined::contains)) {
                score += 0.18f
                directIdentitySignals++
            }

            val handles = buildHandleTerms(input)
            if (handles.any { combined.contains(it.lowercase()) }) {
                score += 0.20f
                directIdentitySignals++
            }
            if (handles.any { handleAppearsInProfilePath(result.url, it) }) {
                score += 0.18f
                directIdentitySignals++
            }

            input.emails.filter { it.isNotBlank() }.forEach { email ->
                if (combined.contains(email.lowercase())) {
                    score += 0.25f
                    directIdentitySignals++
                }
            }
            input.phones.map { it.filter(Char::isDigit) }.filter { it.length >= 8 }.forEach { phone ->
                if (combined.filter(Char::isDigit).contains(phone)) {
                    score += 0.20f
                    directIdentitySignals++
                }
            }
            input.aliases.mapNotNull(::cleanTerm).forEach { alias ->
                if (combined.contains(alias.lowercase())) {
                    score += 0.10f
                    directIdentitySignals++
                }
            }

            if (resolveProfileUrl(result.url) != null) score += 0.14f
            if (isKnownExposureHost(result.url)) score += 0.05f
            if (result.query.contains("site:", true)) score += 0.03f
            score += consensusBonus(result.providerCount)
            if (directIdentitySignals == 0) score -= 0.20f
            return score.coerceIn(0f, 0.95f)
        }

        fun normalizeSearchUrl(rawHref: String): String? {
            val trimmed = rawHref.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#') || trimmed.startsWith("javascript:", true)) return null
            val decoded = safeDecode(trimmed)
            val redirect = extractRedirectParam(decoded, "uddg")
                ?: extractRedirectParam(decoded, "q")
                ?: extractRedirectParam(decoded, "url")
                ?: extractRedirectParam(decoded, "u")
                ?: extractRedirectParam(decoded, "target")
            val candidate = redirect ?: decoded
            if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) return null
            val withoutFragment = candidate.substringBefore('#')
            val uri = runCatching { URI(withoutFragment) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            return withoutFragment
        }

        fun canonicalUrlKey(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return url.trim().removeSuffix("/").lowercase()
            val builder = parsed.newBuilder().fragment(null)
            parsed.queryParameterNames
                .filter { it.lowercase() in TRACKING_QUERY_PARAMS }
                .forEach(builder::removeAllQueryParameters)
            return builder.build().toString().removeSuffix("/").lowercase()
        }

        private fun mergeProviderEvidence(results: List<PublicSearchResult>): List<PublicSearchResult> =
            results.groupBy { canonicalUrlKey(it.url) }.values.map { group ->
                val best = group.maxByOrNull { it.title.length + it.snippet.length } ?: group.first()
                val sources = group.map { it.source }.distinct()
                best.copy(source = sources.joinToString("+"), providerCount = sources.size)
            }

        private fun consensusBonus(providerCount: Int): Float =
            ((providerCount - 1).coerceAtLeast(0) * PROVIDER_CONSENSUS_BONUS)
                .coerceAtMost(MAX_PROVIDER_CONSENSUS_BONUS)

        private fun isHighSignalQuery(query: String): Boolean {
            val unquoted = query.replace("\"", "")
            val digits = unquoted.count(Char::isDigit)
            return unquoted.contains('@') || digits >= 8 ||
                (!query.contains("site:", true) && query.startsWith('"') && query.endsWith('"'))
        }

        private fun handleAppearsInProfilePath(url: String, handle: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val clean = handle.trim().removePrefix("@").lowercase()
            if (clean.isBlank()) return false
            val segments = uri.path.orEmpty().trim('/').split('/').map { it.removePrefix("@").lowercase() }
            if (segments.any { it == clean }) return true
            return uri.rawQuery.orEmpty().lowercase().split('&')
                .any { it.substringAfter('=', "") == clean }
        }

        private fun yandexUrl(query: String): String =
            "https://yandex.com/search/?text=${urlEncode(query)}&noreask=1"

        private fun braveUrl(query: String): String =
            "https://search.brave.com/search?q=${urlEncode(query)}&source=web"

        private fun mojeekUrl(query: String): String =
            "https://www.mojeek.com/search?q=${urlEncode(query)}"

        private fun duckDuckGoUrl(query: String): String =
            "https://html.duckduckgo.com/html/?q=${urlEncode(query)}"

        private fun qwantUrl(query: String): String =
            "https://www.qwant.com/?q=${urlEncode(query)}&t=web"

        private fun bingUrl(query: String): String =
            "https://www.bing.com/search?q=${urlEncode(query)}&count=10"

        private fun userAgentFor(attempt: Int): String = USER_AGENTS[attempt % USER_AGENTS.size]
        private fun quote(term: String): String = "\"${term.replace("\"", " ").trim().take(90)}\""
        private fun cleanTerm(term: String): String? = term.trim().removePrefix("@").takeIf { it.isNotBlank() }

        private fun buildHandleTerms(input: IdentityInput): List<String> =
            (listOfNotNull(input.primaryUsername) + input.usernames + input.aliases)
                .mapNotNull(::cleanTerm)
                .filter { it.length in 2..40 }
                .distinctBy { it.lowercase() }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
        private fun safeDecode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        private fun extractRedirectParam(value: String, param: String): String? {
            val uriValue = runCatching { URI(value) }.getOrNull()
            val query = uriValue?.rawQuery ?: value.substringAfter('?', "")
            query.split('&').forEach { part ->
                val key = part.substringBefore('=', "")
                if (key.equals(param, true)) {
                    return safeDecode(part.substringAfter('=', ""))
                        .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                }
            }
            val marker = "$param="
            val index = value.indexOf(marker)
            if (index < 0) return null
            return safeDecode(value.substring(index + marker.length).substringBefore('&'))
                .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }

        private fun looksLikeNoResults(html: String): Boolean {
            val lower = Jsoup.parse(html).text().lowercase()
            return listOf(
                "no results found", "there are no results for", "we did not find results",
                "no results containing all your search terms", "try different keywords",
                "ничего не найдено", "aucun résultat", "keine ergebnisse"
            ).any(lower::contains)
        }

        private fun isNoisyResultUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return true
            val host = (uri.host ?: return true).removePrefix("www.").lowercase()
            if (SEARCH_ENGINE_HOST_FRAGMENTS.any { host == it || host.endsWith(".$it") }) return true
            val path = uri.path.orEmpty().lowercase()
            return listOf(".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico")
                .any(path::endsWith)
        }

        private fun isKnownExposureHost(url: String): Boolean {
            val host = runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()
                ?: return false
            return listOf(
                "github.com", "linkedin.com", "x.com", "twitter.com", "reddit.com",
                "twitch.tv", "instagram.com", "youtube.com", "4chan.org",
                "boards.4chan.org", "medium.com", "dev.to", "gitlab.com",
                "bsky.app", "mastodon.social", "news.ycombinator.com"
            ).any { host == it || host.endsWith(".$it") }
        }
    }
}
