package io.dossier.app.data.web

import android.content.Context
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.WebViewScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Public image-index discovery for consented self-audits.
 *
 * Important: this does not upload the user's selfie or run face recognition. It
 * searches public image indexes for identity terms (name, handles, aliases) and
 * returns thumbnails/source pages for manual review.
 */
class PublicImageSearchService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    data class PublicImageResult(
        val title: String,
        val imageUrl: String,
        val thumbnailUrl: String?,
        val sourcePageUrl: String,
        val query: String,
        val source: String,
        val score: Float = 0f
    )

    private data class ImageProvider(
        val name: String,
        val searchUrl: (String) -> String,
        val allowBrowserFallback: Boolean = true
    )

    suspend fun discover(input: IdentityInput, deepResearch: Boolean = false): List<PublicImageResult> =
        withContext(Dispatchers.IO) {
            val queries = buildImageQueries(input, deepResearch)
                .take(if (deepResearch) MAX_DEEP_QUERIES else MAX_DEFAULT_QUERIES)
            if (queries.isEmpty()) return@withContext emptyList()

            val semaphore = Semaphore(MAX_PARALLEL_IMAGE_FETCHES)
            val rawResults = coroutineScope {
                providers().flatMap { provider ->
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
                .filter { it.score >= MIN_IMAGE_SCORE }
                .distinctBy { canonicalImageKey(it.imageUrl, it.sourcePageUrl) }
                .sortedWith(
                    compareByDescending<PublicImageResult> { it.score }
                        .thenBy { it.source }
                        .thenBy { it.title }
                )
                .take(MAX_IMAGE_RESULTS)
        }

    private suspend fun fetchProviderResults(
        provider: ImageProvider,
        query: String
    ): List<PublicImageResult> {
        val searchUrl = provider.searchUrl(query)
        var html: String? = null

        try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) html = response.body?.string()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if ((html.isNullOrBlank() || html!!.length < MIN_IMAGE_HTML_BYTES) && provider.allowBrowserFallback) {
            try {
                val render = WebViewScraper(context).scrape(searchUrl)
                if (render is WebViewScraper.Result.Rendered) html = render.html
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return parseImageResults(provider.name, query, html ?: "")
    }

    private fun providers(): List<ImageProvider> = listOf(
        ImageProvider("Bing Images", ::bingImagesUrl),
        ImageProvider("DuckDuckGo Images", ::duckDuckGoImagesUrl, allowBrowserFallback = false)
    )

    companion object {
        private const val MAX_DEFAULT_QUERIES = 8
        private const val MAX_DEEP_QUERIES = 14
        private const val MAX_PARALLEL_IMAGE_FETCHES = 4
        private const val MAX_IMAGE_RESULTS = 18
        private const val MIN_IMAGE_SCORE = 0.24f
        private const val MIN_IMAGE_HTML_BYTES = 500
        private const val USER_AGENT =
            "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

        private val json = Json { ignoreUnknownKeys = true }

        fun buildImageQueries(input: IdentityInput, deepResearch: Boolean = false): List<String> {
            val queries = linkedSetOf<String>()
            val name = input.fullName.trim()
            val handles = handleTerms(input)
            val aliases = input.aliases.mapNotNull { cleanTerm(it) }

            if (name.isNotBlank()) {
                val quotedName = quote(name)
                queries.add(quotedName)
                queries.add("$quotedName profile photo")
                queries.add("$quotedName avatar")
                queries.add("$quotedName github linkedin twitter x")
            }

            handles.take(if (deepResearch) 8 else 4).forEach { handle ->
                val quotedHandle = quote(handle)
                queries.add("$quotedHandle avatar")
                queries.add("$quotedHandle profile photo")
            }

            aliases.take(if (deepResearch) 5 else 2).forEach { alias ->
                queries.add("${quote(alias)} avatar")
            }

            return queries.toList()
        }

        fun parseImageResults(source: String, query: String, html: String): List<PublicImageResult> {
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html)
            val results = mutableListOf<PublicImageResult>()

            // Bing stores high-quality image metadata as JSON in the "m" attr.
            doc.select("a.iusc[m]").forEach { element ->
                val metadata = parseMetadataJson(element.attr("m")) ?: return@forEach
                val imageUrl = metadata["murl"]?.jsonPrimitive?.content?.trim().orEmpty()
                val thumbnailUrl = metadata["turl"]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
                val sourcePageUrl = metadata["purl"]?.jsonPrimitive?.content?.trim().orEmpty()
                val title = metadata["t"]?.jsonPrimitive?.content?.trim().orEmpty()
                    .ifBlank { element.attr("aria-label").trim() }
                if (!isUsableHttpUrl(imageUrl) || !isUsableHttpUrl(sourcePageUrl)) return@forEach
                results.add(
                    PublicImageResult(
                        title = title.ifBlank { "Public image result" }.take(160),
                        imageUrl = imageUrl,
                        thumbnailUrl = thumbnailUrl?.takeIf { isUsableHttpUrl(it) },
                        sourcePageUrl = sourcePageUrl,
                        query = query,
                        source = source
                    )
                )
            }

            // Generic fallback: useful for search result pages that expose images
            // directly rather than in Bing's metadata JSON.
            if (results.isEmpty()) {
                doc.select("a[href] img[src]").forEach { image ->
                    val imageUrl = image.attr("abs:src").ifBlank { image.attr("src") }
                    val sourcePageUrl = image.parent()?.attr("abs:href").orEmpty()
                    if (!isUsableHttpUrl(imageUrl) || !isUsableHttpUrl(sourcePageUrl)) return@forEach
                    val title = image.attr("alt").trim().ifBlank { image.parent()?.text()?.trim().orEmpty() }
                    results.add(
                        PublicImageResult(
                            title = title.ifBlank { "Public image result" }.take(160),
                            imageUrl = imageUrl,
                            thumbnailUrl = imageUrl,
                            sourcePageUrl = sourcePageUrl,
                            query = query,
                            source = source
                        )
                    )
                }
            }

            return results
                .filterNot { isNoisyImageUrl(it.imageUrl) || isNoisyImageUrl(it.sourcePageUrl) }
                .distinctBy { canonicalImageKey(it.imageUrl, it.sourcePageUrl) }
                .take(12)
        }

        fun scoreResult(input: IdentityInput, result: PublicImageResult): Float {
            val combined = "${result.title} ${result.imageUrl} ${result.sourcePageUrl}".lowercase()
            var score = 0.10f

            val name = input.fullName.trim()
            if (name.isNotBlank() && combined.contains(name.lowercase())) score += 0.28f

            val nameParts = name.lowercase()
                .split("\\s+".toRegex())
                .filter { it.length >= 3 }
            if (nameParts.size >= 2 && nameParts.all { combined.contains(it) }) score += 0.16f

            val handles = handleTerms(input)
            if (handles.any { combined.contains(it.lowercase()) }) score += 0.18f
            if (handles.any { result.sourcePageUrl.lowercase().contains(it.lowercase()) }) score += 0.12f

            if (knownVisualHost(result.sourcePageUrl)) score += 0.08f
            if (result.query.contains("avatar", ignoreCase = true) ||
                result.query.contains("profile photo", ignoreCase = true)) {
                score += 0.05f
            }

            return score.coerceIn(0f, 0.90f)
        }

        fun canonicalImageKey(imageUrl: String, sourcePageUrl: String): String =
            "${canonicalUrl(imageUrl)}|${canonicalUrl(sourcePageUrl)}"

        private fun parseMetadataJson(raw: String): JsonObject? = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        }

        private fun bingImagesUrl(query: String): String =
            "https://www.bing.com/images/search?q=${urlEncode(query)}&first=1"

        private fun duckDuckGoImagesUrl(query: String): String =
            "https://duckduckgo.com/?q=${urlEncode(query)}&iar=images&iax=images&ia=images"

        private fun quote(term: String): String =
            "\"${term.replace("\"", " ").trim().take(90)}\""

        private fun cleanTerm(term: String): String? =
            term.trim().removePrefix("@").takeIf { it.isNotBlank() }

        private fun handleTerms(input: IdentityInput): List<String> =
            (listOfNotNull(input.primaryUsername) + input.usernames + input.aliases)
                .mapNotNull { cleanTerm(it) }
                .filter { it.length in 2..40 }
                .distinctBy { it.lowercase() }

        private fun urlEncode(s: String): String =
            URLEncoder.encode(s, "UTF-8")

        private fun isUsableHttpUrl(url: String): Boolean =
            url.startsWith("http://", true) || url.startsWith("https://", true)

        private fun isNoisyImageUrl(url: String): Boolean {
            val host = try {
                URI(url).host?.removePrefix("www.")?.lowercase()
            } catch (e: Exception) {
                null
            } ?: return true
            if (host.contains("bing.com") || host.contains("duckduckgo.com")) return true
            return url.startsWith("data:", true) || url.contains("base64", true)
        }

        private fun knownVisualHost(url: String): Boolean {
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
                "instagram.com",
                "reddit.com",
                "youtube.com",
                "twitch.tv",
                "medium.com",
                "dev.to"
            ).any { host == it || host.endsWith(".$it") }
        }

        private fun canonicalUrl(url: String): String =
            url.trim().substringBefore("#").removeSuffix("/").lowercase()
    }
}
