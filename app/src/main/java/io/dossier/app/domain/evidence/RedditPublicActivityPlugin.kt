package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Bounded public Reddit-activity collector for authorized self-audits.
 *
 * Reddit profile curation can hide activity from a profile page while the same public
 * submissions/comments remain discoverable in Reddit search. This plugin deliberately
 * mirrors the public-search strategy popularized by Ghostddit instead of treating an
 * empty profile feed as proof that no activity exists.
 *
 * Safety boundary: activity is collected only for handles explicitly supplied to the
 * audit. The plugin does not derive new real-world identities, does not recurse from
 * comment content into unrelated people, and does not bypass login walls or challenges.
 */
class RedditPublicActivityPlugin(
    private val client: OkHttpClient = defaultClient()
) : ScannerPlugin {

    override val id: String = "reddit-public-activity"
    override val displayName: String = "Reddit Public Activity"

    override suspend fun scan(input: IdentityInput): EvidenceCollection = withContext(Dispatchers.IO) {
        val handles = explicitHandles(input).take(MAX_HANDLES)
        if (handles.isEmpty()) return@withContext EvidenceCollection()

        val evidence = mutableListOf<Evidence>()
        val relationships = mutableListOf<EvidenceRelationship>()

        for (handle in handles) {
            try {
                val posts = fetchPosts(handle)
                val comments = fetchComments(handle)
                val activities = (posts + comments)
                    .distinctBy { it.kind.name + "|" + canonicalUrl(it.url) }
                    .take(MAX_TOTAL_ACTIVITY_PER_HANDLE)

                activities.forEach { activity ->
                    val evidenceId = "reddit-activity:${sha256("$handle|${activity.kind}|${activity.url}").take(32)}"
                    evidence += Evidence(
                        id = evidenceId,
                        kind = EvidenceKind.PublicSearchEvidence,
                        value = activity.url,
                        sourceUrl = activity.url,
                        snippet = activity.snippet.take(MAX_SNIPPET_CHARS),
                        confidence = activity.confidence,
                        risk = RiskLevel.Low,
                        signals = buildList {
                            add("Public Reddit activity matched the exact supplied handle @$handle")
                            add(activity.sourceNote)
                            if (activity.verified) add("Author was independently verified on a direct Reddit response")
                            else add("Search-index evidence; review the direct permalink before treating content as current")
                        },
                        providerId = activity.providerId,
                        retrievedAtEpochMillis = System.currentTimeMillis(),
                        observedAtEpochMillis = activity.observedAtEpochMillis,
                        state = if (activity.verified) EvidenceState.Verified else EvidenceState.Observed,
                        reliability = if (activity.verified) {
                            EvidenceReliability.AuthoritativeApi
                        } else {
                            EvidenceReliability.DirectPublicProfile
                        },
                        contentHashSha256 = sha256(activity.snippet),
                        parserVersion = PARSER_VERSION,
                        historical = false
                    )
                    relationships += EvidenceRelationship(
                        fromValue = handle,
                        toValue = activity.url,
                        relation = "PUBLISHED_PUBLIC_ACTIVITY",
                        evidence = "Exact Reddit author search for @$handle (${activity.kind.name.lowercase()})",
                        evidenceIds = listOf(evidenceId)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Source failure is isolated by design. Other scanners/providers continue.
            }
        }

        EvidenceCollection(
            evidence = evidence.distinctBy { it.id },
            relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
        )
    }

    private suspend fun fetchPosts(handle: String): List<Activity> {
        val results = mutableListOf<Activity>()
        var after: String? = null
        repeat(MAX_POST_PAGES) {
            val url = buildPostSearchUrl(handle, after)
            val body = fetchText(url, "application/json") ?: return@repeat
            val page = parsePostSearch(body, handle)
            results += page.items
            after = page.after
            if (after.isNullOrBlank()) return results
        }
        return results
    }

    private suspend fun fetchComments(handle: String): List<Activity> {
        val discovered = mutableListOf<Activity>()
        var next: String? = buildCommentSearchUrl(handle)

        repeat(MAX_COMMENT_PAGES) {
            val url = next ?: return@repeat
            val body = fetchText(url, "text/vnd.reddit.partial+html, text/html;q=0.9") ?: return@repeat
            val page = parseCommentSearch(body, url)
            discovered += page.items
            next = page.nextUrl
            if (next == null) return@repeat
        }

        // Search HTML is a discovery surface. Re-fetch a bounded subset of direct
        // Reddit comment permalinks and verify the author before promoting to Verified.
        val verified = mutableListOf<Activity>()
        for ((index, item) in discovered.distinctBy { canonicalUrl(it.url) }.withIndex()) {
            if (index >= MAX_DIRECT_COMMENT_VERIFICATIONS) {
                verified += item
                continue
            }
            verified += verifyComment(item, handle) ?: item
        }
        return verified
    }

    private fun fetchText(url: String, accept: String): String? {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Language", "en-US,en;q=0.8")
                .build()
        }.getOrNull() ?: return null

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val length = body.contentLength()
                if (length > MAX_RESPONSE_BYTES) return null
                body.string().take(MAX_RESPONSE_CHARS)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun verifyComment(activity: Activity, handle: String): Activity? {
        val jsonUrl = commentJsonUrl(activity.url) ?: return null
        val payload = fetchText(jsonUrl, "application/json") ?: return null
        val root = runCatching { JSON.parseToJsonElement(payload) }.getOrNull() ?: return null
        val commentId = commentIdFromPermalink(activity.url) ?: return null
        val matched = findComment(root, commentId, handle) ?: return null
        val body = matched.string("body").orEmpty().trim()
        val created = matched.double("created_utc")?.let { (it * 1000.0).toLong() }
        return activity.copy(
            snippet = body.ifBlank { activity.snippet },
            observedAtEpochMillis = created ?: activity.observedAtEpochMillis,
            confidence = VERIFIED_COMMENT_CONFIDENCE,
            providerId = "reddit-direct-comment-json",
            sourceNote = "Discovered through Reddit Shreddit comment search (Ghostddit-compatible) and re-fetched from the direct Reddit JSON permalink",
            verified = true
        )
    }

    internal data class ActivityPage(
        val items: List<Activity>,
        val after: String? = null,
        val nextUrl: String? = null
    )

    internal enum class ActivityKind { Post, Comment }

    internal data class Activity(
        val kind: ActivityKind,
        val url: String,
        val snippet: String,
        val observedAtEpochMillis: Long?,
        val confidence: Float,
        val providerId: String,
        val sourceNote: String,
        val verified: Boolean
    )

    companion object {
        private const val USER_AGENT =
            "Dossier/0.1 authorized-public-self-audit (+https://github.com/palaashatri/dossier)"
        private const val PARSER_VERSION = "reddit-public-activity-v1"
        private const val MAX_HANDLES = 6
        private const val MAX_POST_PAGES = 2
        private const val MAX_COMMENT_PAGES = 2
        private const val MAX_DIRECT_COMMENT_VERIFICATIONS = 12
        private const val MAX_TOTAL_ACTIVITY_PER_HANDLE = 80
        private const val MAX_SNIPPET_CHARS = 900
        private const val MAX_RESPONSE_BYTES = 2_500_000L
        private const val MAX_RESPONSE_CHARS = 2_500_000
        private const val VERIFIED_POST_CONFIDENCE = 0.94f
        private const val INDEXED_COMMENT_CONFIDENCE = 0.68f
        private const val VERIFIED_COMMENT_CONFIDENCE = 0.92f
        private val HANDLE = Regex("^[A-Za-z0-9_-]{3,20}$")
        private val JSON = Json { ignoreUnknownKeys = true }

        internal fun explicitHandles(input: IdentityInput): List<String> =
            (listOfNotNull(input.primaryUsername) + input.usernames)
                .map { it.trim().removePrefix("u/").removePrefix("@") }
                .filter { HANDLE.matches(it) }
                .distinctBy { it.lowercase() }

        internal fun buildPostSearchUrl(handle: String, after: String? = null): String {
            val base = "https://api.reddit.com/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", "author:\"$handle\"")
                .addQueryParameter("sort", "new")
                .addQueryParameter("limit", "25")
                .addQueryParameter("raw_json", "1")
            if (!after.isNullOrBlank()) base.addQueryParameter("after", after)
            return base.build().toString()
        }

        internal fun buildCommentSearchUrl(handle: String): String =
            "https://www.reddit.com/svc/shreddit/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", "author:\"$handle\"")
                .addQueryParameter("type", "comments")
                .addQueryParameter("sort", "new")
                .build()
                .toString()

        internal fun parsePostSearch(payload: String, expectedHandle: String): ActivityPage {
            val root = runCatching { JSON.parseToJsonElement(payload).asObject() }.getOrNull()
                ?: return ActivityPage(emptyList())
            val data = root.obj("data") ?: return ActivityPage(emptyList())
            val children = data.array("children").orEmpty()
            val items = children.mapNotNull { childElement ->
                val child = childElement.asObject() ?: return@mapNotNull null
                if (child.string("kind") != "t3") return@mapNotNull null
                val post = child.obj("data") ?: return@mapNotNull null
                val author = post.string("author") ?: return@mapNotNull null
                if (!author.equals(expectedHandle, ignoreCase = true)) return@mapNotNull null
                val permalink = post.string("permalink") ?: return@mapNotNull null
                val title = post.string("title").orEmpty().trim()
                val selfText = post.string("selftext").orEmpty().trim()
                val subreddit = post.string("subreddit").orEmpty().trim()
                val snippet = buildList {
                    if (subreddit.isNotBlank()) add("r/$subreddit")
                    if (title.isNotBlank()) add(title)
                    if (selfText.isNotBlank()) add(selfText)
                }.joinToString(" — ").take(MAX_SNIPPET_CHARS)
                Activity(
                    kind = ActivityKind.Post,
                    url = redditAbsolute(permalink),
                    snippet = snippet,
                    observedAtEpochMillis = post.double("created_utc")?.let { (it * 1000.0).toLong() },
                    confidence = VERIFIED_POST_CONFIDENCE,
                    providerId = "reddit-public-search-api",
                    sourceNote = "Direct Reddit public post search using the same exact-author search strategy used by Ghostddit",
                    verified = true
                )
            }
            return ActivityPage(items = items, after = data.string("after"))
        }

        internal fun parseCommentSearch(html: String, requestUrl: String): ActivityPage {
            if (html.isBlank()) return ActivityPage(emptyList())
            val document = Jsoup.parse(html, requestUrl)
            val items = document.select("[data-testid=search-sdui-comment-unit]")
                .mapNotNull { card -> parseCommentCard(card) }
                .distinctBy { canonicalUrl(it.url) }
            val nextRaw = document.selectFirst("faceplate-partial[loading=lazy]")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
            val next = requestUrl.toHttpUrlOrNull()?.resolve(nextRaw.orEmpty())?.toString()
            return ActivityPage(items = items, nextUrl = next)
        }

        private fun parseCommentCard(card: Element): Activity? {
            val tracker = card.parents().firstOrNull { it.tagName().equals("search-telemetry-tracker", true) }
            val contextRaw = tracker?.attr("data-faceplate-tracking-context").orEmpty()
            val context = runCatching { JSON.parseToJsonElement(contextRaw).asObject() }.getOrNull()
            val commentId = context?.obj("comment")?.string("id")

            val link = card.selectFirst("a[aria-labelledby^=comment-content-]")
                ?: card.selectFirst("a[href*=/comments/]")
                ?: return null
            val permalink = link.attr("href").takeIf { it.isNotBlank() } ?: return null
            val inferredId = commentId ?: commentIdFromPermalink(permalink)
            if (inferredId.isNullOrBlank()) return null

            val body = card.selectFirst("[data-testid=search-comment-content] [id$=-post-rtjson-content]")
                ?.text()
                ?.trim()
                .orEmpty()
            val postTitle = context?.obj("post")?.string("title").orEmpty().trim()
            val subreddit = context?.obj("subreddit")?.string("name").orEmpty().trim()
            val ts = card.selectFirst("faceplate-timeago")?.attr("ts").orEmpty()
            val observed = parseIsoInstant(ts)
            val snippet = buildList {
                if (subreddit.isNotBlank()) add("r/$subreddit")
                if (postTitle.isNotBlank()) add(postTitle)
                if (body.isNotBlank()) add(body)
            }.joinToString(" — ").take(MAX_SNIPPET_CHARS)

            return Activity(
                kind = ActivityKind.Comment,
                url = redditAbsolute(permalink),
                snippet = snippet,
                observedAtEpochMillis = observed,
                confidence = INDEXED_COMMENT_CONFIDENCE,
                providerId = "reddit-shreddit-comment-search",
                sourceNote = "Reddit Shreddit public comment-search result discovered with a Ghostddit-compatible exact-author query",
                verified = false
            )
        }

        internal fun commentIdFromPermalink(rawUrl: String): String? {
            val path = runCatching { URI(redditAbsolute(rawUrl)).path }.getOrNull().orEmpty()
            val segments = path.trim('/').split('/').filter { it.isNotBlank() }
            val commentsIndex = segments.indexOfFirst { it.equals("comments", true) }
            if (commentsIndex < 0) return null
            // /r/<sub>/comments/<post-id>/<slug>/<comment-id>/
            return segments.getOrNull(commentsIndex + 3)?.takeIf { it.matches(Regex("^[a-z0-9]+$", RegexOption.IGNORE_CASE)) }
        }

        internal fun commentJsonUrl(rawUrl: String): String? {
            val absolute = redditAbsolute(rawUrl)
            val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
            val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
            if (host != "reddit.com") return null
            val commentId = commentIdFromPermalink(absolute) ?: return null
            val basePath = uri.path.orEmpty().trimEnd('/')
            if (basePath.isBlank() || !basePath.endsWith(commentId, ignoreCase = true)) return null
            return "https://www.reddit.com$basePath.json?raw_json=1"
        }

        private fun findComment(element: JsonElement, commentId: String, handle: String): JsonObject? {
            when (element) {
                is JsonObject -> {
                    if (element.string("kind") == "t1") {
                        val data = element.obj("data")
                        if (data?.string("id")?.equals(commentId, true) == true &&
                            data.string("author")?.equals(handle, true) == true) {
                            return data
                        }
                    }
                    element.values.forEach { child -> findComment(child, commentId, handle)?.let { return it } }
                }
                is JsonArray -> element.forEach { child -> findComment(child, commentId, handle)?.let { return it } }
                else -> Unit
            }
            return null
        }

        private fun parseIsoInstant(value: String): Long? = try {
            if (value.isBlank()) null else Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        private fun redditAbsolute(raw: String): String = when {
            raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
            raw.startsWith("/") -> "https://www.reddit.com$raw"
            else -> "https://www.reddit.com/$raw"
        }

        internal fun canonicalUrl(raw: String): String = runCatching {
            val uri = URI(redditAbsolute(raw))
            val host = uri.host?.removePrefix("www.")?.lowercase().orEmpty()
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            "$host$path"
        }.getOrDefault(raw.lowercase().trimEnd('/'))

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        private fun JsonObject.double(key: String): Double? =
            (this[key] as? JsonPrimitive)?.doubleOrNull
    }
}
