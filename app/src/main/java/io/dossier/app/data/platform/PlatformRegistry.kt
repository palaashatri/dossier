package io.dossier.app.data.platform

import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.PlatformProfileTemplate

/**
 * Public profile registry.
 *
 * `shouldFetchByDefault=false` means the platform is still supported for explicit
 * URLs and discovered pivots, but is not sprayed for every generated username.
 * This avoids spending the scan budget on destinations that are consistently
 * login-gated, numeric-ID-only, or non-public.
 */
val PLATFORMS = listOf(
    PlatformProfileTemplate(Platform.GitHub, "https://github.com/{username}", false, true),
    PlatformProfileTemplate(Platform.Instagram, "https://www.instagram.com/{username}/", false, true),
    PlatformProfileTemplate(Platform.Facebook, "https://www.facebook.com/{username}", true, false),
    PlatformProfileTemplate(Platform.X, "https://x.com/{username}", false, true),
    PlatformProfileTemplate(Platform.Reddit, "https://www.reddit.com/user/{username}", false, true),
    PlatformProfileTemplate(Platform.StackOverflow, "https://stackoverflow.com/users/{username}", false, false),
    PlatformProfileTemplate(Platform.TikTok, "https://www.tiktok.com/@{username}", false, true),
    PlatformProfileTemplate(Platform.YouTube, "https://www.youtube.com/@{username}", false, true),
    PlatformProfileTemplate(Platform.Medium, "https://medium.com/@{username}", false, true),
    PlatformProfileTemplate(Platform.LinkedIn, "https://www.linkedin.com/in/{username}", true, false),
    PlatformProfileTemplate(Platform.Pinterest, "https://www.pinterest.com/{username}/", false, true),
    PlatformProfileTemplate(Platform.Telegram, "https://t.me/{username}", false, true),
    PlatformProfileTemplate(Platform.Bluesky, "https://bsky.app/profile/{username}", false, true),
    PlatformProfileTemplate(Platform.Mastodon, "https://mastodon.social/@{username}", false, true),
    PlatformProfileTemplate(Platform.DevTo, "https://dev.to/{username}", false, true),
    PlatformProfileTemplate(Platform.Twitch, "https://www.twitch.tv/{username}", false, true),
    PlatformProfileTemplate(Platform.GitLab, "https://gitlab.com/{username}", false, true),
    PlatformProfileTemplate(Platform.HackerNews, "https://news.ycombinator.com/user?id={username}", false, true),
    PlatformProfileTemplate(Platform.Threads, "https://www.threads.net/@{username}", false, true),
    PlatformProfileTemplate(Platform.Snapchat, "https://www.snapchat.com/add/{username}", false, false),
    PlatformProfileTemplate(Platform.Discord, "https://discord.com/users/{username}", false, false),
)

data class ResolvedProfile(val platform: Platform, val username: String, val url: String)

/** Best-effort mapping from a public profile URL to a platform and handle. */
fun resolveProfileUrl(rawUrl: String): ResolvedProfile? {
    var urlStr = rawUrl.trim()
    if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
        urlStr = "https://$urlStr"
    }

    val uri = try {
        java.net.URI(urlStr)
    } catch (_: Exception) {
        return null
    }
    val host = (uri.host ?: return null).removePrefix("www.").lowercase()
    val path = uri.path?.trimStart('/').orEmpty()
    val query = uri.rawQuery.orEmpty()

    if (path.isBlank() || path.length < 2) return null

    val nonProfileSegments = setOf(
        "home", "login", "signin", "signup", "register", "logout", "search",
        "explore", "settings", "terms", "privacy", "help", "directory",
        "policies", "tos", "about", "feed", "notifications", "messages",
        "intent", "share", "watch", "shorts", "hashtag", "tags", "topics"
    )
    val firstSegment = path.substringBefore("/").lowercase()
    if (firstSegment in nonProfileSegments) return null

    val hostAliases = mapOf(
        "twitter.com" to "x.com",
        "mobile.twitter.com" to "x.com",
        "www.twitter.com" to "x.com",
        "m.facebook.com" to "facebook.com",
        "fb.com" to "facebook.com",
        "old.reddit.com" to "reddit.com",
        "m.youtube.com" to "youtube.com"
    )
    val normalizedHost = hostAliases[host] ?: host

    for (template in PLATFORMS) {
        if (template.platform == Platform.Website) continue
        val pattern = template.urlPattern
        val patternHost = pattern
            .replace("https://", "", ignoreCase = true)
            .replace("http://", "", ignoreCase = true)
            .replace("www.", "", ignoreCase = true)
            .substringBefore("/")
            .lowercase()
        if (patternHost.isBlank()) continue

        val hostMatches = normalizedHost == patternHost ||
            host == patternHost ||
            host.endsWith(".$patternHost")
        if (!hostMatches) continue

        val placeholder = "{username}"
        if (!pattern.contains(placeholder)) continue

        if (pattern.contains("?id=$placeholder")) {
            val idValue = query.substringAfter("id=", "").substringBefore("&").trim()
            if (idValue.isNotBlank() && idValue.length >= 2) {
                return ResolvedProfile(template.platform, idValue, template.urlPattern.replace(placeholder, idValue))
            }
        } else {
            val patternPath = pattern
                .substringAfter(patternHost, missingDelimiterValue = "")
                .ifBlank {
                    pattern.substringAfter("://").substringAfter("/").let { " /$it" }.trimStart()
                }
                .trimStart('/')
            val prefix = patternPath.substringBefore(placeholder).trimEnd('/')
            val handleSegment = when {
                prefix.isNotBlank() && path.startsWith(prefix, ignoreCase = true) ->
                    path.substringAfter(prefix, "").trimStart('/').substringBefore("/").substringBefore("?")
                prefix == "@" || patternPath.startsWith("@") ->
                    path.substringBefore("/").substringBefore("?").removePrefix("@")
                prefix.isBlank() ->
                    path.substringBefore("/").substringBefore("?").removePrefix("@")
                else -> ""
            }
            if (handleSegment.isBlank()) continue
            val handle = handleSegment.removePrefix("@")
            if (handle.length < 2) continue
            if (handle.equals("www", true) || handle.equals(host, true)) continue
            return ResolvedProfile(template.platform, handle, template.urlPattern.replace(placeholder, handle))
        }
    }
    return null
}
