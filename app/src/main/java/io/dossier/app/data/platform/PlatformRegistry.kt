package io.dossier.app.data.platform

import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.PlatformProfileTemplate

val PLATFORMS = listOf(
    PlatformProfileTemplate(Platform.GitHub, "https://github.com/{username}", false, true),
    PlatformProfileTemplate(Platform.Instagram, "https://www.instagram.com/{username}/", false, true),
    PlatformProfileTemplate(Platform.Facebook, "https://www.facebook.com/{username}", true, false),
    PlatformProfileTemplate(Platform.X, "https://x.com/{username}", false, true),
    PlatformProfileTemplate(Platform.Reddit, "https://www.reddit.com/user/{username}", false, true),
    PlatformProfileTemplate(Platform.StackOverflow, "https://stackoverflow.com/users/{username}", false, true),
    PlatformProfileTemplate(Platform.TikTok, "https://www.tiktok.com/@{username}", false, true),
    PlatformProfileTemplate(Platform.YouTube, "https://www.youtube.com/@{username}", false, true),
    PlatformProfileTemplate(Platform.Medium, "https://medium.com/@{username}", false, true),
    PlatformProfileTemplate(Platform.LinkedIn, "https://www.linkedin.com/in/{username}", true, true),
    PlatformProfileTemplate(Platform.Pinterest, "https://www.pinterest.com/{username}/", false, true),
    PlatformProfileTemplate(Platform.Telegram, "https://t.me/{username}", false, true),
    PlatformProfileTemplate(Platform.Bluesky, "https://bsky.app/profile/{username}", false, true),
    PlatformProfileTemplate(Platform.Mastodon, "https://mastodon.social/@{username}", false, true),
    PlatformProfileTemplate(Platform.DevTo, "https://dev.to/{username}", false, true),
    // Pivot-discovery targets — commonly self-linked, public profile pages.
    PlatformProfileTemplate(Platform.Twitch, "https://www.twitch.tv/{username}", false, true),
    PlatformProfileTemplate(Platform.GitLab, "https://gitlab.com/{username}", false, true),
    PlatformProfileTemplate(Platform.HackerNews, "https://news.ycombinator.com/user?id={username}", false, true),
    PlatformProfileTemplate(Platform.Threads, "https://www.threads.net/@{username}", false, true),
    PlatformProfileTemplate(Platform.Snapchat, "https://www.snapchat.com/add/{username}", false, true),
    PlatformProfileTemplate(Platform.Discord, "https://discord.com/users/{username}", false, false)
)

/**
 * Maps a URL to the platform whose profile pattern it matches, plus the handle
 * segment. Used by HandleExtractor to resolve self-disclosed links.
 */
data class ResolvedProfile(val platform: Platform, val username: String, val url: String)

/**
 * Best-effort: resolve a raw URL to a (platform, username) pair using the
 * registry patterns. Returns null for non-profile or home/login URLs.
 */
fun resolveProfileUrl(rawUrl: String): ResolvedProfile? {
    var urlStr = rawUrl.trim()
    if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
        urlStr = "https://$urlStr"
    }
    val lower = urlStr.lowercase()

    val uri = try {
        java.net.URI(urlStr)
    } catch (e: Exception) {
        return null
    }
    val host = (uri.host ?: return null).removePrefix("www.")
    val path = uri.path?.trimStart('/').orEmpty()
    val query = uri.rawQuery.orEmpty()

    // No path (or trivial) → home root, not a profile.
    if (path.isBlank() || path.length < 2) return null

    // Reject obvious non-profile destinations by path segment.
    val nonProfileSegments = setOf(
        "home", "login", "signin", "signup", "register", "logout", "search",
        "explore", "settings", "terms", "privacy", "help", "directory",
        "policies", "tos", "about", "feed", "notifications", "messages",
        "intent", "share", "watch", "shorts", "hashtag", "tags", "topics"
    )
    val firstSegment = path.substringBefore("/").lowercase()
    if (firstSegment in nonProfileSegments) return null

    for (template in PLATFORMS) {
        if (template.platform == Platform.Website) continue
        val pattern = template.urlPattern
        val patternHost = pattern
            .replace("https://", "").replace("http://", "").replace("www.", "")
            .substringBefore("/")
        if (patternHost.isBlank() || !host.equals(patternHost, ignoreCase = true)) continue

        val placeholder = "{username}"
        if (!pattern.contains(placeholder)) continue

        if (pattern.contains("?id=$placeholder")) {
            // Query-style: news.ycombinator.com/user?id={username}
            val idValue = query.substringAfter("id=", "").substringBefore("&").trim()
            if (idValue.isNotBlank() && idValue.length >= 2) {
                return ResolvedProfile(template.platform, idValue, template.urlPattern.replace(placeholder, idValue))
            }
        } else {
            // Path-style. Compute the path prefix that precedes {username} in the
            // pattern, then pull the corresponding segment from the actual URL.
            val patternPath = pattern.substringAfter(patternHost).trimStart('/') // e.g. "user/{username}" or "{username}"
            val prefix = patternPath.substringBefore(placeholder).trimEnd('/')   // e.g. "user" or ""
            val handleSegment = if (prefix.isNotBlank() && path.startsWith(prefix, ignoreCase = true)) {
                path.substringAfter(prefix, "").trimStart('/').substringBefore("/").substringBefore("?")
            } else if (prefix.isBlank()) {
                path.substringBefore("/").substringBefore("?")
            } else {
                ""
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
