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
 * Reliability policy:
 *  - rotate a primary provider per query and fail over only when needed;
 *  - retry transient failures with backoff and respect Retry-After;
 *  - open a short circuit after repeated provider failures;
 *  - use provider-specific parsers before a conservative generic fallback;
 *  - cache successful query/provider results for the current process.
 */
class PublicSearchDiscoveryService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class PublicSearchResult(
        val title: String,
        val snippet: String,
        val url: String,
        val query: String,
        val source: String,
        val score: Float = 0f
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

    private val cache = ConcurrentHashMap<String, CachedResults>()
    private val breaker = ProviderCircuitBreaker()
    private val browserSemaphore = Semaphore(1)

    suspend fun discover(input: IdentityInput, deepResearch: Boolean = false): List<PublicSearchResult> =
        withContext(Dispatchers.IO) {
            val queryLimit = if (deepResearch) MAX_DEEP_QUERIES else MAX_DEFAULT_QUERIES
            val queries = buildSearchQueries(input, deepResearch).take(queryLimit)
            if (queries.isEmpty()) return@withContext emptyList()

            val providers = defaultProviders()
            val semaphore = Semaphore(MAX_PARALLEL_SEARCH_QUERIES)

            val rawResults = coroutineScope {
                queries.mapIndexed { index, query ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            searchWithFailover(query, providers, startIndex = index % providers.size)
                        }
                    }
                }.awaitAll().flatten()
            }

            rawResults
                .map { result -> result.copy(score = scoreResult(input, result)) }
                .filter { it.score >= MIN_PUBLIC_SEARCH_SCORE }
                .distinctBy { canonicalUrlKey(it.url) }
                .sortedWith(
                    compareByDescending<PublicSearchResult> { it.score }
                        .thenBy { it.source }
                        .thenBy { it.title }
                )
                .take(MAX_PUBLIC_SEARCH_RESULTS)
        }

    private suspend fun searchWithFailover(
        query: String,
        providers: List<SearchProvider>,
        startIndex: Int
    ): List<PublicSearchResult> {
        val ordered = providers.indices.map { providers[(startIndex + it) % providers.size] }
        val merged = mutableListOf<PublicSearchResult>()

        for (provider in ordered) {
            if (!breaker.canAttempt(provider.name)) continue
            merged += fetchProviderResults(provider, query)
            if (merged.distinctBy { canonicalUrlKey(it.url) }.size >= MIN_RESULTS_BEFORE_STOP) break
        }

        return merged
            .distinctBy { canonicalUrlKey(it.url) }
            .take(MAX_RESULTS_PER_QUERY)
    }

    private suspend fun fetchProviderResults(
        provider: SearchProvider,
        query: String
    ): List<PublicSearchResult> {
        val cacheKey = "${provider.name}|$query"
        cache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAtMillis <= CACHE_TTL_MS) {
                return cached.results
            }
            cache.remove(cacheKey, cached)
        }

        val searchUrl = provider.searchUrl(query)
        var lastHtml: String? = null
        var providerHealthy = false
        var stopTrying = false

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
                    val body = response.body?.string().orEmpty()
                    lastHtml = body

                    when {
                        response.isSuccessful &&
                            body.length >= MIN_SEARCH_HTML_BYTES &&
                            !DiscoveryHttpPolicy.looksBlocked(body) -> {
                            providerHealthy = true
                            val parsed = parseSearchResults(provider.name, query, body)
                            if (parsed.isNotEmpty() || looksLikeNoResults(body)) {
                                breaker.recordSuccess(provider.name)
                                cache[cacheKey] = CachedResults(System.currentTimeMillis(), parsed)
                                return parsed
                            }
                        }
                        DiscoveryHttpPolicy.isTransientHttpStatus(response.code) -> {
                            if (attempt < MAX_HTTP_ATTEMPTS - 1) {
                                delay(
                                    DiscoveryHttpPolicy.retryDelayMillis(
                                        attempt,
                                        response.header("Retry-After")
                                    )
                                )
                            }
                        }
                        else -> {
                            if (DiscoveryHttpPolicy.looksBlocked(body)) stopTrying = true
                        }
                    }
                }
                if (stopTrying) break
            } catch (_: Exception) {
                if (attempt < MAX_HTTP_ATTEMPTS - 1) {
                    delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, null))
                }
            }
        }

        if (provider.allowBrowserFallback &&
            (lastHtml.isNullOrBlank() ||
                DiscoveryHttpPolicy.looksBlocked(lastHtml.orEmpty()) ||
                (providerHealthy && !looksLikeNoResults(lastHtml.orEmpty())))) {
            val renderedResults = browserSemaphore.withPermit {
                when (val render = io.dossier.app.domain.scanner.WebViewScraper(context).scrape(searchUrl)) {
                    is io.dossier.app.domain.scanner.WebViewScraper.Result.Rendered ->
                        parseSearchResults(provider.name, query, render.html)
                    else -> emptyList()
                }
            }
            if (renderedResults.isNotEmpty()) {
                breaker.recordSuccess(provider.name)
                cache[cacheKey] = CachedResults(System.currentTimeMillis(), renderedResults)
                return renderedResults
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
        SearchProvider("DuckDuckGo", ::duckDuckGoUrl),
        SearchProvider("Bing", ::bingUrl)
    )

    companion object {
        private const val MAX_DEFAULT_QUERIES = 18
        private const val MAX_DEEP_QUERIES = 32
        private const val MAX_PARALLEL_SEARCH_QUERIES = 3
        private const val MAX_PUBLIC_SEARCH_RESULTS = 30
        private const val MAX_RESULTS_PER_QUERY = 8
        private const val MIN_RESULTS_BEFORE_STOP = 3
        private const val MIN_PUBLIC_SEARCH_SCORE = 0.30f
        private const val MIN_SEARCH_HTML_BYTES = 500
        private const val MAX_HTTP_ATTEMPTS = 3
        private const val CACHE_TTL_MS = 15 * 60 * 1_000L

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 14; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
        )

        private val PROFILE_QUERY_SITES = listOf(
            "github.com",
            "linkedin.com/in",
            "x.com",
            "twitter.com",
            "reddit.com/user",
            "twitch.tv",
            "instagram.com",
            "youtube.com",
            "gitlab.com",
            "medium.com",
            "dev.to",
            "bsky.app/profile"
        )

        private val PUBLIC_FORUM_QUERY_SITES = listOf(
            "reddit.com",
            "4chan.org",
            "boards.4chan.org"
        )

        private val SEARCH_ENGINE_HOST_FRAGMENTS = setOf(
            "duckduckgo.com",
            "google.com",
            "bing.com",
            "yandex.com",
            "yandex.ru"
        )

        private val TRACKING_QUERY_PARAMS = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "gclid", "fbclid", "msclkid", "ref", "ref_src", "ved", "source"
        )

        fun buildSearchQueries(input: IdentityInput, deepResearch: Boolean = false): List<String> {
            val queries = linkedSetOf<String>()
            val name = input.fullName.trim()
            val handles = buildHandleTerms(input)
            val aliases = input.aliases.mapNotNull { cleanTerm(it) }
            val emails = input.emails.mapNotNull { cleanTerm(it) }

            if (name.isNotBlank()) {
                val quotedName = quote(name)
                queries.add(quotedName)
                queries.add("$quotedName github linkedin x twitter reddit twitch instagram youtube")
                PROFILE_QUERY_SITES.forEach { site -> queries.add("$quotedName site:$site") }
                PUBLIC_FORUM_QUERY_SITES.forEach { site -> queries.add("$quotedName site:$site") }
            }

            emails.take(if (deepResearch) 4 else 2).forEach { email ->
                queries.add(quote(email))
                val local = email.substringBefore("@").trim().removePrefix("+")
                if (local.length >= 3) queries.add(quote(local))
                if (deepResearch) {
                    queries.add("${quote(email)} site:pastebin.com")
                    queries.add("${quote(email)} site:github.com")
                }
            }

            input.phones
                .map { value -> value.filter { ch -> ch.isDigit() } }
                .filter { it.length >= 8 }
                .distinct()
                .take(if (deepResearch) 3 else 2)
                .forEach { digits ->
                    queries.add(quote(digits))
                    if (digits.length >= 10) {
                        val last10 = digits.takeLast(10)
                        if (last10 != digits) queries.add(quote(last10))
                    }
                }

            handles.take(if (deepResearch) 8 else 4).forEach { handle ->
                val quotedHandle = quote(handle)
                queries.add(quotedHandle)
                queries.add("$quotedHandle github linkedin x twitter reddit twitch instagram youtube")
                PROFILE_QUERY_SITES.take(if (deepResearch) PROFILE_QUERY_SITES.size else 8)
                    .forEach { site -> queries.add("$quotedHandle site:$site") }
                PUBLIC_FORUM_QUERY_SITES.forEach { site -> queries.add("$quotedHandle site:$site") }
            }

            aliases.take(if (deepResearch) 6 else 3).forEach { alias ->
                val quotedAlias = quote(alias)
                queries.add(quotedAlias)
                queries.add("$quotedAlias site:reddit.com")
                if (deepResearch) {
                    queries.add("$quotedAlias site:4chan.org")
                    queries.add("$quotedAlias site:boards.4chan.org")
                }
            }

            return queries.toList()
        }

        fun parseSearchResults(source: String, query: String, html: String): List<PublicSearchResult> {
            if (html.isBlank() || DiscoveryHttpPolicy.looksBlocked(html)) return emptyList()
            val doc = Jsoup.parse(html)
            val sourceLower = source.lowercase()
            val results = when {
                sourceLower.contains("duck") -> parseDuckDuckGo(doc.body(), source, query)
                sourceLower.contains("bing") -> parseBing(doc.body(), source, query)
                else -> parseGeneric(doc.body(), source, query)
            }
            return results.distinctBy { canonicalUrlKey(it.url) }.take(10)
        }

        private fun parseDuckDuckGo(root: Element, source: String, query: String): List<PublicSearchResult> =
            root.select(".result, .results_links, .web-result").mapNotNull { block ->
                resultFromBlock(
                    block,
                    ".result__a[href], a.result__a[href], h2 a[href]",
                    ".result__snippet, a.result__snippet, .result-snippet",
                    source,
                    query
                )
            }

        private fun parseBing(root: Element, source: String, query: String): List<PublicSearchResult> =
            root.select("li.b_algo, .b_algo").mapNotNull { block ->
                resultFromBlock(
                    block,
                    "h2 a[href], h3 a[href]",
                    ".b_caption p, .b_snippet, p",
                    source,
                    query
                )
            }

        private fun parseGeneric(root: Element, source: String, query: String): List<PublicSearchResult> {
            val blocks = root.select(".result, .web-result, .results_links, li.b_algo, div.g, li.serp-item, .organic")
            if (blocks.isNotEmpty()) {
                return blocks.mapNotNull { block ->
                    resultFromBlock(
                        block,
                        ".result__a[href], a.result__a[href], h2 a[href], h3 a[href], a[href]",
                        ".result__snippet, .snippet, .b_caption p, .VwiC3b, .organic__content-wrapper, .organic__text, p",
                        source,
                        query
                    )
                }
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
            val url = normalizeSearchUrl(linkElement.attr("href")) ?: return null
            if (isNoisyResultUrl(url)) return null

            val title = linkElement.text().trim().ifBlank {
                block.select("h2, h3, .result__title, .organic__url-text").text().trim()
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

            val nameParts = name.lowercase()
                .split("\\s+".toRegex())
                .filter { it.length >= 3 }
            if (nameParts.size >= 2 && nameParts.all { combined.contains(it) }) {
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
            input.phones
                .map { value -> value.filter { ch -> ch.isDigit() } }
                .filter { it.length >= 8 }
                .forEach { phone ->
                    if (combined.filter { ch -> ch.isDigit() }.contains(phone)) {
                        score += 0.20f
                        directIdentitySignals++
                    }
                }
            input.aliases.mapNotNull { cleanTerm(it) }.forEach { alias ->
                if (combined.contains(alias.lowercase())) {
                    score += 0.10f
                    directIdentitySignals++
                }
            }

            if (resolveProfileUrl(result.url) != null) score += 0.14f
            if (isKnownExposureHost(result.url)) score += 0.05f
            if (result.query.contains("site:", ignoreCase = true)) score += 0.03f
            if (directIdentitySignals == 0) score -= 0.20f

            return score.coerceIn(0f, 0.95f)
        }

        fun normalizeSearchUrl(rawHref: String): String? {
            val trimmed = rawHref.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return null

            val decodedOnce = safeDecode(trimmed)
            val redirectParam = extractRedirectParam(decodedOnce, "uddg")
                ?: extractRedirectParam(decodedOnce, "q")
                ?: extractRedirectParam(decodedOnce, "url")
                ?: extractRedirectParam(decodedOnce, "u")

            val candidate = redirectParam ?: decodedOnce
            if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) return null

            val withoutFragment = candidate.substringBefore("#")
            val uri = runCatching { URI(withoutFragment) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            return withoutFragment
        }

        fun canonicalUrlKey(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return url.trim().removeSuffix("/").lowercase()
            val builder = parsed.newBuilder().fragment(null)
            parsed.queryParameterNames
                .filter { it.lowercase() in TRACKING_QUERY_PARAMS }
                .forEach { builder.removeAllQueryParameters(it) }
            return builder.build().toString().removeSuffix("/").lowercase()
        }

        private fun handleAppearsInProfilePath(url: String, handle: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val cleanHandle = handle.trim().removePrefix("@").lowercase()
            if (cleanHandle.isBlank()) return false
            val segments = uri.path.orEmpty().trim('/').split('/').map { it.removePrefix("@").lowercase() }
            if (segments.any { it == cleanHandle }) return true
            val query = uri.rawQuery.orEmpty().lowercase()
            return query.split('&').any { it.substringAfter('=', "") == cleanHandle }
        }

        private fun duckDuckGoUrl(query: String): String =
            "https://html.duckduckgo.com/html/?q=${urlEncode(query)}"

        private fun bingUrl(query: String): String =
            "https://www.bing.com/search?q=${urlEncode(query)}&count=10"

        private fun userAgentFor(attempt: Int): String = USER_AGENTS[attempt % USER_AGENTS.size]

        private fun quote(term: String): String =
            "\"${term.replace("\"", " ").trim().take(90)}\""

        private fun cleanTerm(term: String): String? =
            term.trim().removePrefix("@").takeIf { it.isNotBlank() }

        private fun buildHandleTerms(input: IdentityInput): List<String> =
            (listOfNotNull(input.primaryUsername) + input.usernames + input.aliases)
                .mapNotNull { cleanTerm(it) }
                .filter { it.length in 2..40 }
                .distinctBy { it.lowercase() }

        private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun safeDecode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

        private fun extractRedirectParam(value: String, param: String): String? {
            val marker = "$param="
            val idx = value.indexOf(marker)
            if (idx < 0) return null
            return safeDecode(value.substring(idx + marker.length).substringBefore("&"))
                .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }

        private fun looksLikeNoResults(html: String): Boolean {
            val lower = Jsoup.parse(html).text().lowercase()
            return listOf(
                "no results found",
                "there are no results for",
                "we did not find results",
                "no results containing all your search terms",
                "try different keywords"
            ).any { lower.contains(it) }
        }

        private fun isNoisyResultUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return true
            val host = (uri.host ?: return true).removePrefix("www.").lowercase()
            if (SEARCH_ENGINE_HOST_FRAGMENTS.any { host == it || host.endsWith(".$it") }) return true
            val path = uri.path.orEmpty().lowercase()
            return listOf(".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico")
                .any { suffix -> path.endsWith(suffix) }
        }

        private fun isKnownExposureHost(url: String): Boolean {
            val host = runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()
                ?: return false
            return listOf(
                "github.com", "linkedin.com", "x.com", "twitter.com", "reddit.com",
                "twitch.tv", "instagram.com", "youtube.com", "4chan.org",
                "boards.4chan.org", "medium.com", "dev.to", "gitlab.com", "bsky.app"
            ).any { host == it || host.endsWith(".$it") }
        }
    }
}
