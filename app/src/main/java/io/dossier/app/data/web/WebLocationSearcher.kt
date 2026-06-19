package io.dossier.app.data.web

import android.content.Context
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.scanner.WebViewScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves a location from text + label clues by searching the public web.
 *
 * PRIVACY: only text/label *clues* are sent as search queries — never image
 * bytes. Uses DuckDuckGo's HTML endpoint (no API key, scrape-friendly), with a
 * WebView fallback for JS-rendered result pages, mirroring the existing
 * ProfileScanner fetch pattern.
 */
class WebLocationSearcher(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    data class Result(
        val resolvedLocation: String?,
        val mapsUrl: String?,
        val evidence: List<ReverseImageLookupResult.WebEvidence>
    )

    suspend fun search(
        textClues: String?,
        labelClues: List<String>,
        deepResearch: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        val query = buildSearchQuery(textClues, labelClues)
        if (query.isBlank()) return@withContext Result(null, null, emptyList())

        // 1. Try a fast OkHttp fetch of DuckDuckGo HTML results.
        val evidence = mutableListOf<ReverseImageLookupResult.WebEvidence>()
        var html: String? = null
        try {
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=${urlEncode(query)}")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    html = response.body?.string()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fall back to the in-built browser for JS-heavy / blocked responses.
        if (html.isNullOrBlank() || html!!.length < 500) {
            try {
                val render = WebViewScraper(context).scrape(
                    "https://html.duckduckgo.com/html/?q=${urlEncode(query)}"
                )
                if (render is WebViewScraper.Result.Rendered) {
                    html = render.html
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Parse results for location candidates + evidence snippets.
        val htmlContent = html
        if (!htmlContent.isNullOrBlank()) {
            val doc = Jsoup.parse(htmlContent)
            // DuckDuckGo HTML results wrap titles in .result__a and snippets in
            // .result__snippet. Be defensive — selectors vary.
            doc.select(".result, .web-result, .results_links").forEach { block ->
                val title = block.select(".result__a, a.result__a, h2 a").text().trim()
                val snippet = block.select(".result__snippet, .snippet").text().trim()
                val link = block.select(".result__a, a.result__a, h2 a").attr("abs:href").trim()
                if (title.isNotBlank() || snippet.isNotBlank()) {
                    evidence.add(
                        ReverseImageLookupResult.WebEvidence(
                            title = title.ifBlank { "Untitled result" },
                            snippet = snippet.take(200),
                            url = link
                        )
                    )
                }
            }
        }

        // Deep Research: fetch the top evidence pages and merge their text into the
        // clue pool for richer location context. Bounded to 2 extra fetches so it
        // can't run away. Each fetch is individually try/caught. The enriched entry
        // replaces the original in place (rather than being appended) so it keeps its
        // rank and is not truncated away by the final evidence.take(6).
        if (deepResearch && evidence.isNotEmpty()) {
            evidence
                .filter { it.url.startsWith("http") }
                .take(2)
                .forEachIndexed { idx, ev ->
                    try {
                        val req = Request.Builder()
                            .url(ev.url)
                            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body?.string() ?: ""
                                val doc = Jsoup.parse(body)
                                val pageText = doc.text().take(800)
                                if (pageText.isNotBlank()) {
                                    // Merge the fetched page text into the evidence snippet
                                    // so resolveLocationFromEvidence can mine it for place names.
                                    evidence[idx] = ev.copy(snippet = (ev.snippet + " " + pageText).take(400))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebLocationSearcher", "Deep-research fetch failed for ${ev.url}", e)
                    }
                }
        }

        val resolved = resolveLocationFromEvidence(query, evidence)
        val mapsUrl = if (resolved != null) {
            "https://www.google.com/maps/search/?api=1&query=${urlEncode(resolved)}"
        } else null

        Result(resolvedLocation = resolved, mapsUrl = mapsUrl, evidence = evidence.take(6))
    }

    companion object {
        /**
         * Pure (no network) query builder — extracted for unit testing.
         * Combines the strongest text clue (longest legible token run from OCR,
         * e.g. a sign) with the top scene labels, capped to keep queries focused.
         */
        fun buildSearchQuery(textClues: String?, labelClues: List<String>): String {
            val parts = mutableListOf<String>()

            // Prefer the longest contiguous alpha token run from OCR — signs and
            // storefronts tend to be the most location-discriminative clue. Keep
            // tokens >= 2 chars so short connector words that are part of place
            // names survive ("du" in "Gare du Nord", "de" in "Rio de Janeiro").
            val bestTextRun = textClues
                ?.split("\\s+".toRegex())
                ?.filter { it.length >= 2 && it.matches("[A-Za-z][A-Za-z0-9'.-]*".toRegex()) }
                ?.joinToString(" ")
                ?.take(60)
                ?.trim()
            if (!bestTextRun.isNullOrBlank()) parts.add(bestTextRun)

            // Add up to 3 high-value scene labels.
            labelClues
                .filter { it.length >= 3 && !GENERIC_LABELS.contains(it.lowercase()) }
                .take(3)
                .forEach { parts.add(it) }

            return parts.joinToString(" ").trim()
        }

        /**
         * Pure heuristic: pick the most location-like phrase from the search query
         * and evidence snippets. Falls back to the raw query if nothing better is
         * found. (No network.)
         */
        fun resolveLocationFromEvidence(
            query: String,
            evidence: List<ReverseImageLookupResult.WebEvidence>
        ): String? {
            if (query.isBlank()) return null

            // Look for capitalized multi-word phrases in snippets — these often
            // correspond to place names ("Eiffel Tower", "Times Square", "Gare du
            // Nord", "Rio de Janeiro"). Allow short lowercase connector words
            // (du, de, la, of, ...) between capitalized words so multi-word place
            // names aren't split apart.
            val candidates = mutableListOf<String>()
            val placePhraseRegex = Regex(
                "\\b([A-Z][a-zA-Z]+(?:(?:\\s+(?:du|de|da|la|le|el|los|las|of|the|san|st|von|van|di))??\\s+[A-Z][a-zA-Z]+){0,3})\\b"
            )
            evidence.forEach { ev ->
                placePhraseRegex.findAll(ev.snippet).forEach { m ->
                    val phrase = m.value.trim()
                    if (phrase.length in 4..40 && !GENERIC_PHRASES.contains(phrase.lowercase())) {
                        candidates.add(phrase)
                    }
                }
                placePhraseRegex.findAll(ev.title).forEach { m ->
                    val phrase = m.value.trim()
                    if (phrase.length in 4..40 && !GENERIC_PHRASES.contains(phrase.lowercase())) {
                        candidates.add(phrase)
                    }
                }
            }

            // Most frequent candidate phrase wins.
            if (candidates.isNotEmpty()) {
                return candidates.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
            }

            // No place-like phrase found — return the query itself so the maps link
            // at least points somewhere useful.
            return query
        }

        private fun urlEncode(s: String): String =
            URLEncoder.encode(s, "UTF-8")

        // Scene labels that are too generic to discriminate a location.
        private val GENERIC_LABELS = setOf(
            "building", "sky", "tree", "grass", "road", "car", "person", "people",
            "wall", "floor", "ceiling", "window", "door", "table", "chair", "food",
            "plant", "flower", "water", "cloud", "sand", "rock", "stone", "fabric",
            "textile", "material", "background", "foreground"
        )

        // Phrases that look place-like but aren't locations.
        private val GENERIC_PHRASES = setOf(
            "the", "this", "that", "these", "those", "your", "their", "about",
            "image", "photo", "picture", "results", "search", "click", "here",
            "sign in", "log in", "duckduckgo", "google", "maps"
        )
    }
}
