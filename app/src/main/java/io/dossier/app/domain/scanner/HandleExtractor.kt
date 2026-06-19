package io.dossier.app.domain.scanner

import io.dossier.app.data.platform.PLATFORMS
import io.dossier.app.data.platform.resolveProfileUrl
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType

/**
 * Extracts cross-platform handles/URLs that a user self-disclosed in a confirmed
 * profile's rendered content (text + links). This is the pivot-discovery core —
 * the only way to find handles like "samplecaster" that don't derive from a name.
 *
 * Inspired by the deanonymizer deterministic regex pass: read public content the
 * user already published, surface links/handles to other platforms, exclude the
 * audited handle itself and obvious non-profile destinations. No network.
 *
 * All discovery stays within AGENTS.md: it only reads content the user made
 * public on their own confirmed profiles.
 */
object HandleExtractor {

    private const val MIN_HANDLE_LEN = 2
    private const val MAX_HANDLE_LEN = 40

    // Path segments that indicate a non-profile URL even on a profile host.
    private val NON_PROFILE_PATHS = setOf(
        "home", "login", "signin", "signup", "register", "logout", "search",
        "explore", "settings", "terms", "privacy", "help", "directory",
        "policies", "tos", "about", "posts", "feed", "notifications",
        "messages", "i", "intent", "share", "watch", "shorts", "hashtag",
        "tags", "topics", "r", "user", "users", "channel", "c"
    )

    /**
     * @param profileText rendered page text of the confirmed profile
     * @param profileLinks all <a href> URLs found on the confirmed profile page
     * @param sourceUrl the confirmed profile's own URL (to exclude self-mentions)
     * @param alreadyScannedUrls URLs already checked in this scan (to avoid re-scanning)
     * @param sourcePlatformLabel human-readable label for provenance, e.g. "GitHub"
     * @return new [UsernameCandidate]s discovered via pivoting, deduped by URL
     */
    fun extract(
        profileText: String,
        profileLinks: List<String>,
        sourceUrl: String,
        alreadyScannedUrls: Set<String>,
        sourcePlatformLabel: String
    ): List<PivotCandidate> {
        val found = mutableMapOf<String, PivotCandidate>() // url -> candidate (dedupe)
        val scanned = alreadyScannedUrls + sourceUrl.lowercase()

        // 1. URL pass — resolve every link + every http(s) string in the text.
        val urlCandidates = (profileLinks + extractUrlsFromText(profileText))
            .distinct()
            .mapNotNull { rawUrl -> resolveProfileUrl(rawUrl) }
            .filter { resolved ->
                val urlLower = resolved.url.lowercase()
                urlLower !in scanned &&
                    resolved.username.length in MIN_HANDLE_LEN..MAX_HANDLE_LEN &&
                    !isNonProfileHandle(resolved.username) &&
                    !resolved.username.equals("users.noreply.github.com", true)
            }
        urlCandidates.forEach { resolved ->
            found.putIfAbsent(
                resolved.url.lowercase(),
                PivotCandidate(
                    candidate = UsernameCandidate(
                        username = resolved.username,
                        platform = resolved.platform,
                        url = resolved.url,
                        matchType = UsernameMatchType.FuzzyVariant,
                        confidence = 0.7f
                    ),
                    provenance = "discovered via $sourcePlatformLabel profile"
                )
            )
        }

        // 2. @-mention + "follow me on <platform>" pass — catches bare handles not
        //    embedded in full URLs (e.g. "also on twitch as samplecaster", "@sampleuser").
        extractMentionHandles(profileText).forEach { (handle, platform) ->
            if (handle.length !in MIN_HANDLE_LEN..MAX_HANDLE_LEN) return@forEach
            if (isNonProfileHandle(handle)) return@forEach
            val template = PLATFORMS.firstOrNull { it.platform == platform } ?: return@forEach
            val url = template.urlPattern.replace("{username}", handle)
            val urlLower = url.lowercase()
            if (urlLower in scanned || urlLower in found.keys) return@forEach
            found[urlLower] = PivotCandidate(
                candidate = UsernameCandidate(
                    username = handle,
                    platform = platform,
                    url = url,
                    matchType = UsernameMatchType.FuzzyVariant,
                    confidence = 0.6f
                ),
                provenance = "discovered via $sourcePlatformLabel profile"
            )
        }

        return found.values.toList()
    }

    /** Pull http(s) URLs out of free text (handles commas/parens trailing). */
    private fun extractUrlsFromText(text: String): List<String> {
        val urlRegex = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
        return urlRegex.findAll(text).map { m ->
            // Trim trailing punctuation that's almost never part of the URL.
            m.value.trimEnd(',', '.', ')', ']', '}', ';', ':', '!')
        }.toList()
    }

    /**
     * Extract bare @handles and "on <platform> as <handle>" / "follow me on
     * <platform>" phrases, mapping each to a platform when the platform is named.
     */
    private fun extractMentionHandles(text: String): List<Pair<String, Platform>> {
        val results = mutableListOf<Pair<String, Platform>>()
        // Strip http(s) URLs first — the URL pass already handles them, and leaving
        // them in would let the mention regex latch onto "https"/"www" fragments.
        val textWithoutUrls = Regex("https?://\\S+").replace(text, " ")
        val lower = textWithoutUrls.lowercase()

        // "on <platform> as <handle>" / "on <platform> @<handle>" / "<platform>: @<handle>"
        val platformNames = mapOf(
            "twitch" to Platform.Twitch, "github" to Platform.GitHub,
            "gitlab" to Platform.GitLab, "reddit" to Platform.Reddit,
            "instagram" to Platform.Instagram, "x" to Platform.X,
            "twitter" to Platform.X, "youtube" to Platform.YouTube,
            "tiktok" to Platform.TikTok, "telegram" to Platform.Telegram,
            "bluesky" to Platform.Bluesky, "mastodon" to Platform.Mastodon,
            "linkedin" to Platform.LinkedIn, "medium" to Platform.Medium,
            "threads" to Platform.Threads, "snapchat" to Platform.Snapchat,
            "discord" to Platform.Discord, "hacker news" to Platform.HackerNews,
            "hn" to Platform.HackerNews, "dev.to" to Platform.DevTo,
            "pinterest" to Platform.Pinterest, "facebook" to Platform.Facebook
        )

        // "<platform> [as|:|@|->] <handle>" — covers:
        //   "also on twitch as samplecaster", "reddit: @sampleuser", "twitch @samplecaster",
        //   "find me on github as janedoe". The platform name is an explicit
        //   word from the list; the handle follows an optional separator, with or
        //   without a leading @.
        val platformAlt = platformNames.keys
            .filter { it != "x" && it != "hn" } // too noisy as bare words
            .joinToString("|") { Regex.escape(it) }
        val mentionRegex = Regex(
            "(?:$platformAlt)\\s*(?:as|:|@|->|is|id)?\\s*@?([a-z0-9][a-z0-9._-]{1,29})",
            RegexOption.IGNORE_CASE
        )
        mentionRegex.findAll(text).forEach { m ->
            // The platform is the matched prefix; recover it by lowercasing the
            // start of the match and matching against known names.
            val matchedPrefix = m.value.substring(0, m.value.length - m.groupValues[1].length)
                .lowercase().trim()
            val platformKey = platformNames.keys.firstOrNull { matchedPrefix.startsWith(it) }
                ?: return@forEach
            val platform = platformNames[platformKey] ?: return@forEach
            val handle = m.groupValues[1].trim().removePrefix("@")
            if (handle.length !in MIN_HANDLE_LEN..MAX_HANDLE_LEN) return@forEach
            if (isNonProfileHandle(handle)) return@forEach
            results.add(handle to platform)
        }

        // Bare @handles where the platform name appears nearby (within 40 chars).
        // Match against the URL-stripped text so the match ranges index into the same
        // string we slice for the lookup window (the original `text` still contains URLs
        // which would make `lower` shorter than `text` and cause substring() to throw).
        val atHandleRegex = Regex("@([a-z0-9._-]{2,30})", RegexOption.IGNORE_CASE)
        atHandleRegex.findAll(textWithoutUrls).forEach { m ->
            val handle = m.groupValues[1].trim()
            if (isNonProfileHandle(handle)) return@forEach
            // Look for a platform name within a window before/after the match.
            val start = (m.range.first - 40).coerceAtLeast(0)
            val end = (m.range.last + 40).coerceAtMost(textWithoutUrls.length)
            val window = lower.substring(start, end)
            val platform = platformNames.entries.firstOrNull { (name, _) ->
                window.contains(name) && name != "x" // "x" too noisy as a bare word
            }?.value ?: return@forEach
            results.add(handle to platform)
        }

        return results.distinctBy { it.first.lowercase() to it.second }
    }

    private fun isNonProfileHandle(handle: String): Boolean {
        val lower = handle.lowercase()
        if (lower in NON_PROFILE_PATHS) return true
        // All-numeric or pure punctuation isn't a handle.
        if (lower.all { it.isDigit() || !it.isLetterOrDigit() }) return true
        // URL-ish fragments that aren't handles.
        if (lower in setOf("http", "https", "www", "com", "org", "net", "html", "php", "aspx")) return true
        // GitHub's privacy email host fragment.
        if (lower.endsWith("noreply.github.com")) return true
        return false
    }

    /** A pivot-discovered candidate plus where it was found. */
    data class PivotCandidate(
        val candidate: UsernameCandidate,
        val provenance: String
    )
}
