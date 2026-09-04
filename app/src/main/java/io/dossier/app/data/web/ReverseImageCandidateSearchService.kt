package io.dossier.app.data.web

import android.content.Context
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.WebViewScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds a bounded candidate corpus from public image indexes. The query image is never
 * uploaded: only OCR text, scene labels, and user-supplied identity terms are searched.
 * Candidate images are later downloaded and compared locally by ReverseImageVisualMatcher.
 */
internal class ReverseImageCandidateSearchService(private val context: Context) {

    data class Candidate(
        val title: String,
        val imageUrl: String,
        val thumbnailUrl: String?,
        val sourcePageUrl: String,
        val query: String,
        val source: String
    )

    private data class Provider(
        val name: String,
        val searchUrl: (String) -> String,
        val browserFallback: Boolean = true
    )

    private data class CacheEntry(val savedAt: Long, val results: List<Candidate>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(11, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val breaker = ProviderCircuitBreaker(failureThreshold = 2, cooldownMillis = 180_000L)
    private val browserSemaphore = Semaphore(1)

    suspend fun search(
        extractedText: String?,
        labels: List<String>,
        identity: IdentityInput?,
        deepResearch: Boolean
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val queries = buildQueries(extractedText, labels, identity, deepResearch)
        if (queries.isEmpty()) return@withContext emptyList()

        val providers = providers()
        val semaphore = Semaphore(MAX_PARALLEL_QUERIES)
        val raw = coroutineScope {
            queries.take(if (deepResearch) MAX_DEEP_QUERIES else MAX_DEFAULT_QUERIES)
                .mapIndexed { index, query ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            searchQuery(
                                query = query,
                                providers = providers,
                                startIndex = index % providers.size,
                                providerBudget = if (deepResearch) 5 else 3
                            )
                        }
                    }
                }.awaitAll().flatten()
        }

        raw.distinctBy { canonicalCandidateKey(it) }
            .filter { isHttp(it.imageUrl) }
            .take(if (deepResearch) MAX_DEEP_CANDIDATES else MAX_DEFAULT_CANDIDATES)
    }

    private suspend fun searchQuery(
        query: String,
        providers: List<Provider>,
        startIndex: Int,
        providerBudget: Int
    ): List<Candidate> {
        val ordered = providers.indices.map { providers[(startIndex + it) % providers.size] }
        val results = mutableListOf<Candidate>()
        var attempted = 0

        for (provider in ordered) {
            if (attempted >= providerBudget) break
            if (!breaker.canAttempt(provider.name)) continue
            attempted++
            results += fetch(provider, query)
            if (results.distinctBy(::canonicalCandidateKey).size >= RESULTS_BEFORE_STOP) break
        }
        return results.distinctBy(::canonicalCandidateKey)
    }

    private suspend fun fetch(provider: Provider, query: String): List<Candidate> {
        val key = "${provider.name}|$query"
        cache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAt <= CACHE_TTL_MS) return cached.results
            cache.remove(key, cached)
        }

        val url = provider.searchUrl(query)
        var lastHtml = ""
        var healthy = false
        var blocked = false

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENTS[attempt % USER_AGENTS.size])
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .build()
                client.newCall(request).execute().use { response ->
                    lastHtml = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful && lastHtml.length >= MIN_HTML_BYTES &&
                            !DiscoveryHttpPolicy.looksBlocked(lastHtml) -> {
                            healthy = true
                            val parsed = parse(provider.name, query, url, lastHtml)
                            if (parsed.isNotEmpty() || looksLikeNoResults(lastHtml)) {
                                breaker.recordSuccess(provider.name)
                                cache[key] = CacheEntry(System.currentTimeMillis(), parsed)
                                return parsed
                            }
                        }
                        DiscoveryHttpPolicy.isTransientHttpStatus(response.code) && attempt < MAX_ATTEMPTS - 1 ->
                            delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, response.header("Retry-After")))
                        DiscoveryHttpPolicy.looksBlocked(lastHtml) -> blocked = true
                    }
                }
            } catch (_: Exception) {
                if (attempt < MAX_ATTEMPTS - 1) delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, null))
            }
        }

        if (provider.browserFallback && (lastHtml.isBlank() || blocked || healthy)) {
            val rendered = browserSemaphore.withPermit {
                when (val result = WebViewScraper(context).scrape(url)) {
                    is WebViewScraper.Result.Rendered -> parse(provider.name, query, url, result.html)
                    else -> emptyList()
                }
            }
            if (rendered.isNotEmpty()) {
                breaker.recordSuccess(provider.name)
                cache[key] = CacheEntry(System.currentTimeMillis(), rendered)
                return rendered
            }
        }

        if (healthy) {
            breaker.recordSuccess(provider.name)
            cache[key] = CacheEntry(System.currentTimeMillis(), emptyList())
        } else {
            breaker.recordFailure(provider.name)
        }
        return emptyList()
    }

    private fun providers(): List<Provider> = listOf(
        Provider("Yandex Images", ::yandexImagesUrl),
        Provider("Brave Images", ::braveImagesUrl),
        Provider("Bing Images", ::bingImagesUrl),
        Provider("Qwant Images", ::qwantImagesUrl),
        Provider("DuckDuckGo Images", ::duckDuckGoImagesUrl, browserFallback = true)
    )

    companion object {
        private const val MAX_DEFAULT_QUERIES = 8
        private const val MAX_DEEP_QUERIES = 14
        private const val MAX_DEFAULT_CANDIDATES = 54
        private const val MAX_DEEP_CANDIDATES = 90
        private const val MAX_PARALLEL_QUERIES = 3
        private const val RESULTS_BEFORE_STOP = 8
        private const val MIN_HTML_BYTES = 500
        private const val MAX_ATTEMPTS = 2
        private const val CACHE_TTL_MS = 20 * 60 * 1_000L

        private val USER_AGENTS = listOf(
            "Dossier/0.1 public-exposure-audit",
            "Dossier/0.1 public-exposure-audit"
        )

        private val ENGINE_HOSTS = setOf(
            "bing.com", "duckduckgo.com", "yandex.com", "yandex.ru",
            "search.brave.com", "qwant.com"
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun buildQueries(
            extractedText: String?,
            labels: List<String>,
            identity: IdentityInput?,
            deepResearch: Boolean
        ): List<String> {
            val queries = linkedSetOf<String>()

            extractedText.orEmpty()
                .lineSequence()
                .map { it.trim().replace("\\s+".toRegex(), " ") }
                .filter { it.length in 4..100 }
                .distinctBy { it.lowercase() }
                .take(if (deepResearch) 6 else 3)
                .forEach { line ->
                    queries += quote(line)
                    queries += "${quote(line)} image"
                }

            val cleanLabels = labels
                .map { it.trim() }
                .filter { it.length in 3..40 }
                .distinctBy { it.lowercase() }
                .take(if (deepResearch) 7 else 4)
            if (cleanLabels.isNotEmpty()) {
                queries += cleanLabels.take(3).joinToString(" ") { quote(it) }
                queries += cleanLabels.take(4).joinToString(" ") + " photo"
            }

            identity?.let { input ->
                val name = input.fullName.trim()
                if (name.isNotBlank()) {
                    queries += "${quote(name)} avatar"
                    queries += "${quote(name)} profile photo"
                }
                (listOfNotNull(input.primaryUsername) + input.usernames + input.aliases)
                    .map { it.trim().removePrefix("@") }
                    .filter { it.length in 2..40 }
                    .distinctBy { it.lowercase() }
                    .take(if (deepResearch) 6 else 3)
                    .forEach { handle ->
                        queries += "${quote(handle)} avatar"
                        queries += "${quote(handle)} profile photo"
                    }
            }

            return queries.toList()
        }

        fun parse(source: String, query: String, baseUrl: String, html: String): List<Candidate> {
            if (html.isBlank() || DiscoveryHttpPolicy.looksBlocked(html)) return emptyList()
            val doc = Jsoup.parse(html, baseUrl)
            val candidates = mutableListOf<Candidate>()

            if (source.contains("bing", true)) parseBing(doc, query, source, candidates)
            if (source.contains("yandex", true)) parseYandex(doc, query, source, candidates)
            parseGeneric(doc, query, source, candidates)

            return candidates
                .filter { isHttp(it.imageUrl) }
                .filterNot { isEngineAsset(it.imageUrl) }
                .distinctBy(::canonicalCandidateKey)
                .take(18)
        }

        private fun parseBing(
            doc: Document,
            query: String,
            source: String,
            out: MutableList<Candidate>
        ) {
            doc.select("a.iusc[m]").forEach { element ->
                val obj = runCatching { json.parseToJsonElement(element.attr("m")) as? JsonObject }
                    .getOrNull() ?: return@forEach
                val image = obj.string("murl") ?: return@forEach
                val page = obj.string("purl") ?: image
                val thumb = obj.string("turl")
                out += Candidate(
                    title = obj.string("t") ?: element.attr("aria-label").ifBlank { "Image result" },
                    imageUrl = image,
                    thumbnailUrl = thumb,
                    sourcePageUrl = page,
                    query = query,
                    source = source
                )
            }
        }

        private fun parseYandex(
            doc: Document,
            query: String,
            source: String,
            out: MutableList<Candidate>
        ) {
            doc.select("[data-bem]").forEach { element ->
                val raw = element.attr("data-bem")
                if (!raw.contains("img", true) && !raw.contains("serp", true)) return@forEach
                val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return@forEach
                val image = root.findFirstString(setOf("img_href", "originUrl", "imageUrl", "url"))
                    ?.takeIf(::isHttp) ?: return@forEach
                val thumb = root.findFirstString(setOf("thumb", "preview", "thumbnail", "previewUrl"))
                    ?.takeIf(::isHttp)
                val page = root.findFirstString(setOf("snippetUrl", "pageUrl", "sourceUrl", "host"))
                    ?.takeIf(::isHttp) ?: image
                val title = root.findFirstString(setOf("title", "text", "description")) ?: "Yandex image result"
                out += Candidate(title.take(180), image, thumb, page, query, source)
            }
        }

        private fun parseGeneric(
            doc: Document,
            query: String,
            source: String,
            out: MutableList<Candidate>
        ) {
            val selectors = listOf(
                "a[href] img[src]", "a[href] img[data-src]", "a[href] img[data-lazy-src]",
                "article img[src]", ".image-result img[src]", ".tile img[src]"
            )
            doc.select(selectors.joinToString(",")).forEach { image ->
                val imageUrl = sequenceOf("data-src", "data-lazy-src", "src")
                    .map { image.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { raw -> absoluteUrl(raw, doc.baseUri()) }
                    ?: return@forEach
                if (!isHttp(imageUrl) || isEngineAsset(imageUrl)) return@forEach

                val anchor = image.parents().firstOrNull { it.tagName().equals("a", true) && it.hasAttr("href") }
                val pageCandidate = anchor?.attr("abs:href").orEmpty()
                    .ifBlank { anchor?.attr("href").orEmpty() }
                    .let { raw -> absoluteUrl(raw, doc.baseUri()) }
                val sourcePage = pageCandidate.takeIf { isHttp(it) && !isEngineHost(it) } ?: imageUrl
                val title = image.attr("alt").trim().ifBlank {
                    anchor?.attr("title")?.trim().orEmpty()
                }.ifBlank { "Public image candidate" }

                out += Candidate(
                    title = title.take(180),
                    imageUrl = imageUrl,
                    thumbnailUrl = imageUrl,
                    sourcePageUrl = sourcePage,
                    query = query,
                    source = source
                )
            }
        }

        private fun yandexImagesUrl(query: String): String =
            "https://yandex.com/images/search?text=${encode(query)}&noreask=1"

        private fun braveImagesUrl(query: String): String =
            "https://search.brave.com/images?q=${encode(query)}&source=web"

        private fun bingImagesUrl(query: String): String =
            "https://www.bing.com/images/search?q=${encode(query)}&first=1"

        private fun qwantImagesUrl(query: String): String =
            "https://www.qwant.com/?q=${encode(query)}&t=images"

        private fun duckDuckGoImagesUrl(query: String): String =
            "https://duckduckgo.com/?q=${encode(query)}&iar=images&iax=images&ia=images"

        private fun quote(value: String): String = "\"${value.replace("\"", " ").trim().take(100)}\""
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun canonicalCandidateKey(candidate: Candidate): String =
            "${canonical(candidate.imageUrl)}|${canonical(candidate.sourcePageUrl)}"

        private fun canonical(url: String): String =
            runCatching {
                val uri = URI(url)
                URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path, uri.query, null)
                    .toString().removeSuffix("/")
            }.getOrDefault(url.trim().substringBefore('#').removeSuffix("/").lowercase())

        private fun absoluteUrl(raw: String, base: String): String {
            if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return decode(raw)
            return runCatching { URI(base).resolve(raw).toString() }.getOrDefault(raw)
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        private fun isHttp(url: String): Boolean =
            url.startsWith("https://", true) || url.startsWith("http://", true)

        private fun isEngineHost(url: String): Boolean {
            val host = runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()
                ?: return false
            return ENGINE_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        private fun isEngineAsset(url: String): Boolean {
            if (url.startsWith("data:", true) || url.contains("base64", true)) return true
            val host = runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()
                ?: return true
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            if (ENGINE_HOSTS.any { host == it || host.endsWith(".$it") } &&
                (path.contains("logo") || path.contains("favicon") || path.contains("sprite"))) return true
            return false
        }

        private fun looksLikeNoResults(html: String): Boolean {
            val text = Jsoup.parse(html).text().lowercase()
            return listOf(
                "no image results", "no results found", "nothing found", "ничего не найдено",
                "aucun résultat", "keine ergebnisse"
            ).any(text::contains)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonElement.findFirstString(keys: Set<String>): String? = when (this) {
            is JsonObject -> {
                entries.firstNotNullOfOrNull { (key, value) ->
                    if (key in keys && value is JsonPrimitive) value.contentOrNull?.takeIf { it.isNotBlank() }
                    else null
                } ?: values.firstNotNullOfOrNull { it.findFirstString(keys) }
            }
            is JsonArray -> firstNotNullOfOrNull { it.findFirstString(keys) }
            else -> null
        }
    }
}
