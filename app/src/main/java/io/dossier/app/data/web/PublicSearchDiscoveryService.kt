package io.dossier.app.data.web

import android.content.Context
import io.dossier.app.data.platform.resolveProfileUrl
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.WebViewScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Bounded public-search discovery for consented self-audits.
 *
 * This pass complements the deterministic username-template scan. Template scans
 * find "username on known platform"; search discovery finds indexed evidence:
 * search-result links for a name, handle, email, or site-specific query. Results
 * are intentionally returned as plausible review candidates, not as proof that an
 * account or page belongs to the audited person.
 */
class PublicSearchDiscoveryService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
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

    suspend fun discover(input: IdentityInput, deepResearch: Boolean = false): List<PublicSearchResult> =
        withContext(Dispatchers.IO) {
            val queryLimit = if (deepResearch) MAX_DEEP_QUERIES else MAX_DEFAULT_QUERIES
            val queries = buildSearchQueries(input, deepResearch).take(queryLimit)
            if (queries.isEmpty()) return@withContext emptyList()

            val providers = defaultProviders()
            val semaphore = Semaphore(MAX_PARALLEL_SEARCH_FETCHES)

            val rawResults = coroutineScope {
                providers.flatMap { provider ->
                    queries.map { query ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                fetchProviderResults(provider, query)
                            }
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

    private suspend fun fetchProviderResults(
        provider: SearchProvider,
        query: String
    ): List<PublicSearchResult> {
        val searchUrl = provider.searchUrl(query)
        var html: String? = null

        try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    html = response.body?.string()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if ((html.isNullOrBlank() || html!!.length < MIN_SEARCH_HTML_BYTES) && provider.allowBrowserFallback) {
            try {
                val render = WebViewScraper(context).scrape(searchUrl)
                if (render is WebViewScraper.Result.Rendered) {
                    html = render.html
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val htmlContent = html ?: return emptyList()
        return parseSearchResults(provider.name, query, htmlContent)
    }

    private fun defaultProviders(): List<SearchProvider> = listOf(
        SearchProvider("DuckDuckGo", ::duckDuckGoUrl),
        SearchProvider("Bing", ::bingUrl),
        SearchProvider("Google", ::googleUrl, allowBrowserFallback = false),
        SearchProvider("Yandex", ::yandexUrl, allowBrowserFallback = false)
    )

    companion object {
        private const val MAX_DEFAULT_QUERIES = 18
        private const val MAX_DEEP_QUERIES = 32
        private const val MAX_PARALLEL_SEARCH_FETCHES = 6
        private const val MAX_PUBLIC_SEARCH_RESULTS = 24
        private const val MIN_PUBLIC_SEARCH_SCORE = 0.28f
        private const val MIN_SEARCH_HTML_BYTES = 500
        private const val USER_AGENT =
            "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

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

        /**
         * Builds ranked query strings. Profile sites come first; Reddit and 4chan
         * are included as public-search evidence sources because one has profiles
         * and the other does not expose stable user profile URLs.
         */
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

            // Exact email search can reveal high-value leaks, but keep it bounded.
            emails.take(if (deepResearch) 4 else 2).forEach { email ->
                queries.add(quote(email))
            }

            return queries.toList()
        }

        fun parseSearchResults(source: String, query: String, html: String): List<PublicSearchResult> {
            val doc = Jsoup.parse(html)
            val results = mutableListOf<PublicSearchResult>()
            val blocks = doc.select(
                ".result, .web-result, .results_links, li.b_algo, div.g, li.serp-item, .organic"
            )

            if (blocks.isNotEmpty()) {
                blocks.forEach { block ->
                    val linkElement = block.select(
                        ".result__a[href], a.result__a[href], h2 a[href], h3 a[href], a[href]"
                    ).firstOrNull() ?: return@forEach
                    val url = normalizeSearchUrl(linkElement.attr("href")) ?: return@forEach
                    if (isNoisyResultUrl(url)) return@forEach

                    val title = linkElement.text().trim().ifBlank {
                        block.select("h2, h3, .result__title, .organic__url-text").text().trim()
                    }
                    val snippet = block.select(
                        ".result__snippet, .snippet, .b_caption p, .VwiC3b, .organic__content-wrapper, .organic__text, p"
                    ).text().trim().ifBlank {
                        block.text().trim()
                    }
                    if (title.isBlank() && snippet.isBlank()) return@forEach
                    results.add(
                        PublicSearchResult(
                            title = title.ifBlank { "Untitled result" }.take(160),
                            snippet = snippet.take(320),
                            url = url,
                            query = query,
                            source = source
                        )
                    )
                }
            }

            if (results.isEmpty()) {
                doc.select("a[href]").forEach { anchor ->
                    val url = normalizeSearchUrl(anchor.attr("href")) ?: return@forEach
                    if (isNoisyResultUrl(url)) return@forEach
                    val title = anchor.text().trim()
                    if (title.length < 4) return@forEach
                    results.add(
                        PublicSearchResult(
                            title = title.take(160),
                            snippet = "",
                            url = url,
                            query = query,
                            source = source
                        )
                    )
                }
            }

            return results.distinctBy { canonicalUrlKey(it.url) }.take(10)
        }

        fun scoreResult(input: IdentityInput, result: PublicSearchResult): Float {
            val combined = "${result.title} ${result.snippet} ${result.url}".lowercase()
            var score = 0.12f

            val name = input.fullName.trim()
            if (name.isNotBlank() && combined.contains(name.lowercase())) {
                score += 0.30f
            }

            val nameParts = name.lowercase()
                .split("\\s+".toRegex())
                .filter { it.length >= 3 }
            if (nameParts.size >= 2 && nameParts.all { combined.contains(it) }) {
                score += 0.18f
            }

            val handles = buildHandleTerms(input)
            if (handles.any { combined.contains(it.lowercase()) }) {
                score += 0.20f
            }
            if (handles.any { result.url.lowercase().contains(it.lowercase()) }) {
                score += 0.12f
            }

            input.emails.filter { it.isNotBlank() }.forEach { email ->
                if (combined.contains(email.lowercase())) score += 0.25f
            }
            input.phones.map { it.filter { ch -> ch.isDigit() } }.filter { it.length >= 8 }.forEach { phone ->
                if (combined.filter { ch -> ch.isDigit() }.contains(phone)) score += 0.20f
            }
            input.aliases.mapNotNull { cleanTerm(it) }.forEach { alias ->
                if (combined.contains(alias.lowercase())) score += 0.10f
            }

            if (resolveProfileUrl(result.url) != null) {
                score += 0.15f
            }
            if (isKnownExposureHost(result.url)) {
                score += 0.06f
            }
            if (result.query.contains("site:", ignoreCase = true)) {
                score += 0.04f
            }

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
            if (!candidate.startsWith("http://", ignoreCase = true) &&
                !candidate.startsWith("https://", ignoreCase = true)) {
                return null
            }

            val withoutFragment = candidate.substringBefore("#")
            val uri = try {
                URI(withoutFragment)
            } catch (e: Exception) {
                return null
            }
            val host = uri.host ?: return null
            if (host.isBlank()) return null
            return withoutFragment
        }

        fun canonicalUrlKey(url: String): String =
            url.trim()
                .removeSuffix("/")
                .substringBefore("?utm_")
                .lowercase()

        private fun duckDuckGoUrl(query: String): String =
            "https://html.duckduckgo.com/html/?q=${urlEncode(query)}"

        private fun bingUrl(query: String): String =
            "https://www.bing.com/search?q=${urlEncode(query)}"

        private fun googleUrl(query: String): String =
            "https://www.google.com/search?q=${urlEncode(query)}&num=10"

        private fun yandexUrl(query: String): String =
            "https://yandex.com/search/?text=${urlEncode(query)}"

        private fun quote(term: String): String =
            "\"${term.replace("\"", " ").trim().take(90)}\""

        private fun cleanTerm(term: String): String? =
            term.trim().removePrefix("@").takeIf { it.isNotBlank() }

        private fun buildHandleTerms(input: IdentityInput): List<String> =
            (listOfNotNull(input.primaryUsername) + input.usernames + input.aliases)
                .mapNotNull { cleanTerm(it) }
                .filter { it.length in 2..40 }
                .distinctBy { it.lowercase() }

        private fun urlEncode(s: String): String =
            URLEncoder.encode(s, "UTF-8")

        private fun safeDecode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }

        private fun extractRedirectParam(value: String, param: String): String? {
            val marker = "$param="
            val idx = value.indexOf(marker)
            if (idx < 0) return null
            return safeDecode(value.substring(idx + marker.length).substringBefore("&"))
                .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }

        private fun isNoisyResultUrl(url: String): Boolean {
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                return true
            }
            val host = (uri.host ?: return true).removePrefix("www.").lowercase()
            if (SEARCH_ENGINE_HOST_FRAGMENTS.any { host == it || host.endsWith(".$it") }) return true
            val path = uri.path.orEmpty().lowercase()
            if (path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".png") ||
                path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") ||
                path.endsWith(".svg") || path.endsWith(".ico")) {
                return true
            }
            return false
        }

        private fun isKnownExposureHost(url: String): Boolean {
            val host = try {
                URI(url).host?.removePrefix("www.")?.lowercase()
            } catch (e: Exception) {
                null
            } ?: return false
            return listOf(
                "github.com",
                "linkedin.com",
                "x.com",
                "twitter.com",
                "reddit.com",
                "twitch.tv",
                "instagram.com",
                "youtube.com",
                "4chan.org",
                "boards.4chan.org",
                "medium.com",
                "dev.to",
                "gitlab.com"
            ).any { host == it || host.endsWith(".$it") }
        }
    }
}
