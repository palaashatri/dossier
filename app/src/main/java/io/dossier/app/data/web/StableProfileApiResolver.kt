package io.dossier.app.data.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves public profile URLs through stable, unauthenticated platform endpoints before
 * falling back to brittle HTML/browser scraping. No credentials or private APIs are used.
 */
internal class StableProfileApiResolver(
    private val client: OkHttpClient = defaultClient()
) {
    sealed class Resolution {
        data class Found(val html: String, val text: String) : Resolution()
        data object NotFound : Resolution()
        data object Unsupported : Resolution()
        data class Unavailable(val reason: String) : Resolution()
    }

    enum class Kind { GITHUB, REDDIT, BLUESKY, GITLAB, HACKER_NEWS, DEV_TO, YOUTUBE, MASTODON }

    data class Endpoint(
        val kind: Kind,
        val username: String,
        val apiUrl: String,
        val canonicalProfileUrl: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(profileUrl: String): Resolution = withContext(Dispatchers.IO) {
        val endpoint = endpointFor(profileUrl) ?: return@withContext Resolution.Unsupported

        var lastReason = "structured endpoint unavailable"
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(endpoint.apiUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, application/activity+json;q=0.9, */*;q=0.5")
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> return@withContext Resolution.NotFound
                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            val profile = parse(endpoint, body)
                                ?: return@withContext Resolution.NotFound
                            return@withContext Resolution.Found(
                                html = profile.toHtml(endpoint.canonicalProfileUrl),
                                text = profile.toPlainText()
                            )
                        }
                        DiscoveryHttpPolicy.isTransientHttpStatus(response.code) -> {
                            lastReason = "HTTP ${response.code}"
                            if (attempt < MAX_ATTEMPTS - 1) {
                                delay(
                                    DiscoveryHttpPolicy.retryDelayMillis(
                                        attempt,
                                        response.header("Retry-After")
                                    )
                                )
                            }
                        }
                        else -> return@withContext Resolution.Unavailable("HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                lastReason = e.localizedMessage ?: e.javaClass.simpleName
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, null))
                }
            }
        }
        Resolution.Unavailable(lastReason)
    }

    private data class ProfilePayload(
        val username: String,
        val displayName: String? = null,
        val bio: String? = null,
        val location: String? = null,
        val avatarUrl: String? = null,
        val links: List<String> = emptyList(),
        val extraSignals: List<String> = emptyList()
    ) {
        fun toPlainText(): String = buildList {
            add(username)
            displayName?.takeIf { it.isNotBlank() }?.let(::add)
            bio?.takeIf { it.isNotBlank() }?.let(::add)
            location?.takeIf { it.isNotBlank() }?.let { add("Based in $it") }
            addAll(extraSignals.filter { it.isNotBlank() })
            addAll(links.filter { it.isNotBlank() })
        }.distinct().joinToString("\n")

        fun toHtml(canonicalUrl: String): String = buildString {
            append("<html><head><title>")
            append(escapeHtml(displayName ?: username))
            append("</title>")
            avatarUrl?.takeIf { it.startsWith("http", true) }?.let {
                append("<meta property=\"og:image\" content=\"")
                append(escapeHtml(it))
                append("\">")
            }
            append("</head><body><main>")
            append("<h1>").append(escapeHtml(displayName ?: username)).append("</h1>")
            append("<p>@").append(escapeHtml(username.removePrefix("@"))).append("</p>")
            bio?.let { append("<p>").append(escapeHtml(it)).append("</p>") }
            location?.let { append("<p>Based in ").append(escapeHtml(it)).append("</p>") }
            append("<a href=\"").append(escapeHtml(canonicalUrl)).append("\">profile</a>")
            links.distinct().take(12).forEach { link ->
                if (link.startsWith("http", true)) {
                    append("<a href=\"").append(escapeHtml(link)).append("\">")
                    append(escapeHtml(link)).append("</a>")
                }
            }
            append("</main></body></html>")
        }
    }

    private fun parse(endpoint: Endpoint, body: String): ProfilePayload? {
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        return when (endpoint.kind) {
            Kind.GITHUB -> parseGithub(root as? JsonObject ?: return null, endpoint)
            Kind.REDDIT -> parseReddit(root as? JsonObject ?: return null, endpoint)
            Kind.BLUESKY -> parseBluesky(root as? JsonObject ?: return null, endpoint)
            Kind.GITLAB -> parseGitLab(root as? JsonArray ?: return null, endpoint)
            Kind.HACKER_NEWS -> parseHackerNews(root as? JsonObject ?: return null, endpoint)
            Kind.DEV_TO -> parseDevTo(root as? JsonObject ?: return null, endpoint)
            Kind.YOUTUBE -> parseYouTube(root as? JsonObject ?: return null, endpoint)
            Kind.MASTODON -> parseMastodon(root as? JsonObject ?: return null, endpoint)
        }
    }

    private fun parseGithub(obj: JsonObject, endpoint: Endpoint) = ProfilePayload(
        username = obj.string("login") ?: endpoint.username,
        displayName = obj.string("name"),
        bio = obj.string("bio"),
        location = obj.string("location"),
        avatarUrl = obj.string("avatar_url"),
        links = listOfNotNull(obj.string("html_url"), obj.string("blog"))
    )

    private fun parseReddit(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val data = obj["data"] as? JsonObject ?: return null
        val subreddit = data["subreddit"] as? JsonObject
        return ProfilePayload(
            username = data.string("name") ?: endpoint.username,
            displayName = subreddit?.string("title"),
            bio = subreddit?.string("public_description"),
            avatarUrl = subreddit?.string("icon_img")?.substringBefore("?"),
            links = listOf(endpoint.canonicalProfileUrl),
            extraSignals = listOfNotNull(data.string("link_karma")?.let { "link karma $it" })
        )
    }

    private fun parseBluesky(obj: JsonObject, endpoint: Endpoint) = ProfilePayload(
        username = obj.string("handle") ?: endpoint.username,
        displayName = obj.string("displayName"),
        bio = obj.string("description"),
        avatarUrl = obj.string("avatar"),
        links = listOf(endpoint.canonicalProfileUrl)
    )

    private fun parseGitLab(array: JsonArray, endpoint: Endpoint): ProfilePayload? {
        val obj = array.mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("username")?.equals(endpoint.username, true) == true }
            ?: return null
        return ProfilePayload(
            username = obj.string("username") ?: endpoint.username,
            displayName = obj.string("name"),
            avatarUrl = obj.string("avatar_url"),
            links = listOfNotNull(obj.string("web_url"))
        )
    }

    private fun parseHackerNews(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val id = obj.string("id") ?: return null
        return ProfilePayload(
            username = id,
            bio = obj.string("about"),
            links = listOf(endpoint.canonicalProfileUrl),
            extraSignals = listOfNotNull(obj.string("karma")?.let { "karma $it" })
        )
    }

    private fun parseDevTo(obj: JsonObject, endpoint: Endpoint) = ProfilePayload(
        username = obj.string("username") ?: endpoint.username,
        displayName = obj.string("name"),
        bio = obj.string("summary"),
        avatarUrl = obj.string("profile_image"),
        links = listOfNotNull(
            obj.string("website_url"),
            obj.string("github_username")?.let { "https://github.com/$it" },
            obj.string("twitter_username")?.let { "https://x.com/$it" },
            endpoint.canonicalProfileUrl
        )
    )

    private fun parseYouTube(obj: JsonObject, endpoint: Endpoint) = ProfilePayload(
        username = endpoint.username,
        displayName = obj.string("author_name") ?: obj.string("title"),
        avatarUrl = obj.string("thumbnail_url"),
        links = listOfNotNull(obj.string("author_url"), endpoint.canonicalProfileUrl)
    )

    private fun parseMastodon(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val subject = obj.string("subject") ?: return null
        val aliases = (obj["aliases"] as? JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val linkHrefs = (obj["links"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("href") }
            .orEmpty()
        return ProfilePayload(
            username = subject.substringAfter("acct:").substringBefore("@"),
            displayName = subject,
            links = (aliases + linkHrefs + endpoint.canonicalProfileUrl).distinct()
        )
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val USER_AGENT = "Dossier/0.1 public-self-audit (+https://github.com/palaashatri/dossier)"

        private val RESERVED_ROOTS = setOf(
            "about", "account", "apps", "collections", "contact", "dashboard", "explore",
            "features", "help", "home", "issues", "login", "marketplace", "new", "notifications",
            "pricing", "privacy", "search", "security", "settings", "signup", "support", "topics"
        )

        fun endpointFor(rawUrl: String): Endpoint? {
            val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
            val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
            val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }

            return when {
                host == "github.com" && segments.isNotEmpty() -> {
                    val username = segments.first()
                    if (username.lowercase() in RESERVED_ROOTS) null else Endpoint(
                        Kind.GITHUB,
                        username,
                        "https://api.github.com/users/${encode(username)}",
                        "https://github.com/$username"
                    )
                }
                host.endsWith("reddit.com") && segments.size >= 2 && segments[0].equals("user", true) -> {
                    val username = segments[1]
                    Endpoint(
                        Kind.REDDIT,
                        username,
                        "https://www.reddit.com/user/${encode(username)}/about.json?raw_json=1",
                        "https://www.reddit.com/user/$username"
                    )
                }
                host == "bsky.app" && segments.size >= 2 && segments[0].equals("profile", true) -> {
                    val actor = segments[1]
                    Endpoint(
                        Kind.BLUESKY,
                        actor,
                        "https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=${encode(actor)}",
                        "https://bsky.app/profile/$actor"
                    )
                }
                host == "gitlab.com" && segments.isNotEmpty() -> {
                    val username = segments.first()
                    if (username.lowercase() in RESERVED_ROOTS) null else Endpoint(
                        Kind.GITLAB,
                        username,
                        "https://gitlab.com/api/v4/users?username=${encode(username)}",
                        "https://gitlab.com/$username"
                    )
                }
                host == "news.ycombinator.com" && segments.firstOrNull().equals("user", true) -> {
                    val username = parseQuery(uri.rawQuery)["id"] ?: return null
                    Endpoint(
                        Kind.HACKER_NEWS,
                        username,
                        "https://hacker-news.firebaseio.com/v0/user/${encode(username)}.json",
                        "https://news.ycombinator.com/user?id=${encode(username)}"
                    )
                }
                host == "dev.to" && segments.isNotEmpty() -> {
                    val username = segments.first().removePrefix("@")
                    if (username.lowercase() in RESERVED_ROOTS) null else Endpoint(
                        Kind.DEV_TO,
                        username,
                        "https://dev.to/api/users/by_username?url=${encode(username)}",
                        "https://dev.to/$username"
                    )
                }
                host.endsWith("youtube.com") && segments.firstOrNull()?.startsWith("@") == true -> {
                    val username = segments.first().removePrefix("@")
                    val profileUrl = "https://www.youtube.com/@$username"
                    Endpoint(
                        Kind.YOUTUBE,
                        username,
                        "https://www.youtube.com/oembed?url=${encode(profileUrl)}&format=json",
                        profileUrl
                    )
                }
                host == "mastodon.social" && segments.firstOrNull()?.startsWith("@") == true -> {
                    val username = segments.first().removePrefix("@").substringBefore('@')
                    val account = "acct:$username@$host"
                    Endpoint(
                        Kind.MASTODON,
                        username,
                        "https://$host/.well-known/webfinger?resource=${encode(account)}",
                        "https://$host/@$username"
                    )
                }
                else -> null
            }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to part.substringAfter('=', "")
            }.toMap()

        private fun escapeHtml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
