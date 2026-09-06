package io.dossier.app.data.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves public profile URLs through stable, unauthenticated platform endpoints before
 * falling back to brittle HTML/browser scraping. Results are cached process-locally and a
 * per-source circuit breaker prevents a degraded API from consuming the whole scan budget.
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

    enum class Kind {
        GITHUB,
        REDDIT,
        BLUESKY,
        GITLAB,
        HACKER_NEWS,
        DEV_TO,
        YOUTUBE,
        FEDIVERSE,
        STACK_OVERFLOW,
        KEYBASE
    }

    data class Endpoint(
        val kind: Kind,
        val username: String,
        val apiUrl: String,
        val canonicalProfileUrl: String,
        val authoritativeNotFound: Boolean = true
    )

    private data class CachedResolution(
        val savedAtMillis: Long,
        val resolution: Resolution
    )

    private data class ProfilePayload(
        val username: String,
        val displayName: String? = null,
        val bio: String? = null,
        val location: String? = null,
        val avatarUrl: String? = null,
        val links: List<String> = emptyList(),
        val extraSignals: List<String> = emptyList()
    ) {
        fun merge(other: ProfilePayload): ProfilePayload = ProfilePayload(
            username = other.username.ifBlank { username },
            displayName = other.displayName ?: displayName,
            bio = other.bio ?: bio,
            location = other.location ?: location,
            avatarUrl = other.avatarUrl ?: avatarUrl,
            links = (links + other.links).filter { it.isNotBlank() }.distinct(),
            extraSignals = (extraSignals + other.extraSignals).filter { it.isNotBlank() }.distinct()
        )

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
            bio?.takeIf { it.isNotBlank() }?.let {
                append("<meta name=\"description\" content=\"")
                append(escapeHtml(it))
                append("\">")
            }
            append("</head><body><main>")
            append("<h1>").append(escapeHtml(displayName ?: username)).append("</h1>")
            append("<p>@").append(escapeHtml(username.removePrefix("@"))).append("</p>")
            bio?.let { append("<p>").append(escapeHtml(it)).append("</p>") }
            location?.let { append("<p>Based in ").append(escapeHtml(it)).append("</p>") }
            extraSignals.distinct().take(12).forEach {
                append("<p>").append(escapeHtml(it)).append("</p>")
            }
            append("<a href=\"").append(escapeHtml(canonicalUrl)).append("\">profile</a>")
            links.distinct().take(16).forEach { link ->
                if (link.startsWith("http", true)) {
                    append("<a href=\"").append(escapeHtml(link)).append("\">")
                    append(escapeHtml(link)).append("</a>")
                }
            }
            append("</main></body></html>")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(profileUrl: String): Resolution = withContext(Dispatchers.IO) {
        val endpoint = endpointFor(profileUrl) ?: return@withContext Resolution.Unsupported
        val cacheKey = endpoint.apiUrl
        sharedCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAtMillis <= ttlFor(cached.resolution)) {
                return@withContext cached.resolution
            }
            sharedCache.remove(cacheKey, cached)
        }

        val breakerKey = "${endpoint.kind}:${runCatching { URI(endpoint.apiUrl).host }.getOrNull()}"
        if (!sharedBreaker.canAttempt(breakerKey)) {
            return@withContext Resolution.Unavailable("Structured source is temporarily cooling down")
        }

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
                        response.code == 404 -> {
                            sharedBreaker.recordSuccess(breakerKey)
                            val resolution = if (endpoint.authoritativeNotFound) {
                                Resolution.NotFound
                            } else {
                                Resolution.Unsupported
                            }
                            cache(cacheKey, resolution)
                            return@withContext resolution
                        }
                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            var profile = parse(endpoint, body)
                            if (profile == null) {
                                val resolution = if (endpoint.authoritativeNotFound) {
                                    Resolution.NotFound
                                } else {
                                    Resolution.Unsupported
                                }
                                sharedBreaker.recordSuccess(breakerKey)
                                cache(cacheKey, resolution)
                                return@withContext resolution
                            }
                            if (endpoint.kind == Kind.FEDIVERSE) {
                                hydrateFediverse(body)?.let { profile = profile!!.merge(it) }
                            }
                            val resolution = Resolution.Found(
                                html = profile!!.toHtml(endpoint.canonicalProfileUrl),
                                text = profile!!.toPlainText()
                            )
                            sharedBreaker.recordSuccess(breakerKey)
                            cache(cacheKey, resolution)
                            return@withContext resolution
                        }
                        DiscoveryHttpPolicy.isTransientHttpStatus(response.code) -> {
                            lastReason = "HTTP ${response.code}"
                            if (attempt < MAX_ATTEMPTS - 1) {
                                delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, response.header("Retry-After")))
                            }
                        }
                        else -> {
                            lastReason = "HTTP ${response.code}"
                            sharedBreaker.recordFailure(breakerKey)
                            val resolution = Resolution.Unavailable(lastReason)
                            cache(cacheKey, resolution)
                            return@withContext resolution
                        }
                    }
                }
            } catch (e: Exception) {
                lastReason = e.localizedMessage ?: e.javaClass.simpleName
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, null))
                }
            }
        }

        sharedBreaker.recordFailure(breakerKey)
        val resolution = Resolution.Unavailable(lastReason)
        cache(cacheKey, resolution)
        resolution
    }

    private fun cache(key: String, resolution: Resolution) {
        sharedCache[key] = CachedResolution(System.currentTimeMillis(), resolution)
    }

    private fun ttlFor(resolution: Resolution): Long = when (resolution) {
        is Resolution.Found, Resolution.NotFound -> SUCCESS_CACHE_TTL_MS
        is Resolution.Unavailable -> FAILURE_CACHE_TTL_MS
        Resolution.Unsupported -> UNSUPPORTED_CACHE_TTL_MS
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
            Kind.FEDIVERSE -> parseWebFinger(root as? JsonObject ?: return null, endpoint)
            Kind.STACK_OVERFLOW -> parseStackOverflow(root as? JsonObject ?: return null, endpoint)
            Kind.KEYBASE -> parseKeybase(root as? JsonObject ?: return null, endpoint)
        }
    }

    private fun parseGithub(obj: JsonObject, endpoint: Endpoint) = ProfilePayload(
        username = obj.string("login") ?: endpoint.username,
        displayName = obj.string("name"),
        bio = obj.string("bio"),
        location = obj.string("location"),
        avatarUrl = obj.string("avatar_url"),
        links = listOfNotNull(
            obj.string("html_url"),
            obj.string("blog"),
            obj.string("twitter_username")?.let { "https://x.com/$it" }
        ),
        extraSignals = listOfNotNull(
            obj.string("email")?.let { "Email: $it" },
            obj.string("company")?.let { "Organization: $it" }
        )
    )

    private fun parseReddit(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val data = obj.obj("data") ?: return null
        val subreddit = data.obj("subreddit")
        return ProfilePayload(
            username = data.string("name") ?: endpoint.username,
            displayName = subreddit?.string("title"),
            bio = subreddit?.string("public_description"),
            avatarUrl = subreddit?.string("icon_img")?.substringBefore('?'),
            links = listOf(endpoint.canonicalProfileUrl),
            extraSignals = listOfNotNull(
                data.string("link_karma")?.let { "Link karma: $it" },
                data.string("comment_karma")?.let { "Comment karma: $it" }
            )
        )
    }

    private fun parseBluesky(obj: JsonObject, endpoint: Endpoint) = ProfilePayload(
        username = obj.string("handle") ?: endpoint.username,
        displayName = obj.string("displayName"),
        bio = obj.string("description"),
        avatarUrl = obj.string("avatar"),
        links = listOf(endpoint.canonicalProfileUrl, obj.string("did").orEmpty()).filter { it.isNotBlank() }
    )

    private fun parseGitLab(array: JsonArray, endpoint: Endpoint): ProfilePayload? {
        val obj = array.mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("username")?.equals(endpoint.username, true) == true }
            ?: return null
        return ProfilePayload(
            username = obj.string("username") ?: endpoint.username,
            displayName = obj.string("name"),
            bio = obj.string("bio"),
            location = obj.string("location"),
            avatarUrl = obj.string("avatar_url"),
            links = listOfNotNull(obj.string("web_url"), obj.string("website_url"))
        )
    }

    private fun parseHackerNews(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val id = obj.string("id") ?: return null
        return ProfilePayload(
            username = id,
            bio = obj.string("about")?.let(::plainText),
            links = listOf(endpoint.canonicalProfileUrl),
            extraSignals = listOfNotNull(obj.string("karma")?.let { "Karma: $it" })
        )
    }

    private fun parseDevTo(obj: JsonObject, endpoint: Endpoint) = ProfilePayload(
        username = obj.string("username") ?: endpoint.username,
        displayName = obj.string("name"),
        bio = obj.string("summary"),
        location = obj.string("location"),
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

    private fun parseWebFinger(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val subject = obj.string("subject") ?: return null
        val aliases = obj.array("aliases").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val links = obj.array("links").orEmpty().mapNotNull { (it as? JsonObject)?.string("href") }
        return ProfilePayload(
            username = subject.substringAfter("acct:").substringBefore('@').ifBlank { endpoint.username },
            displayName = subject,
            links = (aliases + links + endpoint.canonicalProfileUrl).distinct()
        )
    }

    private fun parseStackOverflow(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val item = obj.array("items")?.mapNotNull { it as? JsonObject }?.firstOrNull() ?: return null
        return ProfilePayload(
            username = endpoint.username,
            displayName = item.string("display_name")?.let(::plainText),
            location = item.string("location"),
            avatarUrl = item.string("profile_image"),
            links = listOfNotNull(item.string("link"), item.string("website_url")),
            extraSignals = listOfNotNull(
                item.string("reputation")?.let { "Reputation: $it" },
                item.string("user_id")?.let { "Stack Overflow user id: $it" }
            )
        )
    }

    private fun parseKeybase(obj: JsonObject, endpoint: Endpoint): ProfilePayload? {
        val person = obj.array("them")?.mapNotNull { it as? JsonObject }?.firstOrNull() ?: return null
        val basics = person.obj("basics")
        val profile = person.obj("profile")
        val pictures = person.obj("pictures")?.obj("primary")
        val proofs = person.obj("proofs_summary")?.array("all").orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("proof_url") }
        return ProfilePayload(
            username = basics?.string("username") ?: endpoint.username,
            displayName = profile?.string("full_name"),
            bio = profile?.string("bio"),
            location = profile?.string("location"),
            avatarUrl = pictures?.string("url"),
            links = (proofs + endpoint.canonicalProfileUrl).distinct()
        )
    }

    private suspend fun hydrateFediverse(webFingerBody: String): ProfilePayload? {
        val root = runCatching { json.parseToJsonElement(webFingerBody) as? JsonObject }.getOrNull() ?: return null
        val actorUrl = root.array("links").orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull {
                it.string("rel") == "self" &&
                    (it.string("type")?.contains("activity+json", true) == true ||
                        it.string("type")?.contains("application/json", true) == true)
            }
            ?.string("href")
            ?: return null

        return try {
            val request = Request.Builder()
                .url(actorUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/activity+json, application/ld+json, application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val actor = runCatching {
                    json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                }.getOrNull() ?: return null
                val icon = when (val value = actor["icon"]) {
                    is JsonObject -> value.string("url")
                    is JsonArray -> value.mapNotNull { (it as? JsonObject)?.string("url") }.firstOrNull()
                    else -> null
                }
                val attachmentSignals = actor.array("attachment").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { item ->
                        val name = item.string("name")?.let(::plainText).orEmpty()
                        val value = item.string("value")?.let(::plainText).orEmpty()
                        listOf(name, value).filter { it.isNotBlank() }.joinToString(": ").takeIf { it.isNotBlank() }
                    }
                ProfilePayload(
                    username = actor.string("preferredUsername").orEmpty(),
                    displayName = actor.string("name")?.let(::plainText),
                    bio = actor.string("summary")?.let(::plainText),
                    avatarUrl = icon,
                    links = listOfNotNull(actor.string("url"), actorUrl),
                    extraSignals = attachmentSignals
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val SUCCESS_CACHE_TTL_MS = 20 * 60 * 1_000L
        private const val FAILURE_CACHE_TTL_MS = 45 * 1_000L
        private const val UNSUPPORTED_CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val USER_AGENT =
            "Dossier/0.1 public-self-audit"

        private val sharedCache = ConcurrentHashMap<String, CachedResolution>()
        private val sharedBreaker = ProviderCircuitBreaker(failureThreshold = 3, cooldownMillis = 90_000L)

        private val RESERVED_ROOTS = setOf(
            "about", "account", "apps", "collections", "contact", "dashboard", "explore",
            "features", "help", "home", "issues", "login", "marketplace", "new", "notifications",
            "pricing", "privacy", "search", "security", "settings", "signup", "support", "topics"
        )

        private val NON_FEDIVERSE_AT_HOSTS = setOf(
            "instagram.com", "threads.net", "tiktok.com", "youtube.com", "x.com", "twitter.com",
            "facebook.com", "pinterest.com", "telegram.me", "t.me", "medium.com"
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
                host.endsWith("youtube.com") && segments.firstOrNull()?.startsWith('@') == true -> {
                    val username = segments.first().removePrefix("@")
                    val profileUrl = "https://www.youtube.com/@$username"
                    Endpoint(
                        Kind.YOUTUBE,
                        username,
                        "https://www.youtube.com/oembed?url=${encode(profileUrl)}&format=json",
                        profileUrl
                    )
                }
                host == "stackoverflow.com" && segments.size >= 2 &&
                    segments[0].equals("users", true) && segments[1].all(Char::isDigit) -> {
                    val userId = segments[1]
                    Endpoint(
                        Kind.STACK_OVERFLOW,
                        segments.getOrNull(2)?.ifBlank { userId } ?: userId,
                        "https://api.stackexchange.com/2.3/users/$userId?site=stackoverflow",
                        "https://stackoverflow.com/users/$userId"
                    )
                }
                host == "keybase.io" && segments.isNotEmpty() -> {
                    val username = segments.first()
                    if (username.lowercase() in RESERVED_ROOTS) null else Endpoint(
                        Kind.KEYBASE,
                        username,
                        "https://keybase.io/_/api/1.0/user/lookup.json?usernames=${encode(username)}",
                        "https://keybase.io/$username"
                    )
                }
                isFediverseCandidate(host, segments) -> {
                    val username = when {
                        segments.first().startsWith('@') -> segments.first().removePrefix("@").substringBefore('@')
                        segments.first().equals("users", true) && segments.size >= 2 -> segments[1]
                        else -> return null
                    }
                    val authoritative = host == "mastodon.social"
                    Endpoint(
                        Kind.FEDIVERSE,
                        username,
                        "https://$host/.well-known/webfinger?resource=${encode("acct:$username@$host")}",
                        "https://$host/@$username",
                        authoritativeNotFound = authoritative
                    )
                }
                else -> null
            }
        }

        private fun isFediverseCandidate(host: String, segments: List<String>): Boolean {
            if (NON_FEDIVERSE_AT_HOSTS.any { host == it || host.endsWith(".$it") }) return false
            if (segments.isEmpty()) return false
            return (segments.size == 1 && segments.first().startsWith('@')) ||
                (segments.size >= 2 && segments.first().equals("users", true))
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(DiscoveryHttpPolicy.PUBLIC_DNS)
            .addNetworkInterceptor(DiscoveryHttpPolicy.PUBLIC_URL_INTERCEPTOR)
            .build()

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to part.substringAfter('=', "")
            }.toMap()

        private fun plainText(value: String): String = Jsoup.parse(value).text().trim()

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

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
