package io.dossier.app.domain.scanner

import io.dossier.app.data.platform.PLATFORMS
import io.dossier.app.data.platform.resolveProfileUrl
import io.dossier.app.domain.discovery.PivotAdmissionDecision
import io.dossier.app.domain.discovery.PivotAdmissionPolicy
import io.dossier.app.domain.discovery.PivotAdmissionRequest
import io.dossier.app.domain.discovery.PivotSignalType
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType

/**
 * Extracts cross-platform handles/URLs explicitly disclosed in verified public
 * profile evidence. Candidates pass the shared conservative pivot-admission
 * policy before they can enter ProfileScanner's bounded hop/budget logic.
 */
object HandleExtractor {

    private const val MIN_HANDLE_LEN = 2
    private const val MAX_HANDLE_LEN = 40

    private val NON_PROFILE_PATHS = setOf(
        "home", "login", "signin", "signup", "register", "logout", "search",
        "explore", "settings", "terms", "privacy", "help", "directory",
        "policies", "tos", "about", "posts", "feed", "notifications",
        "messages", "i", "intent", "share", "watch", "shorts", "hashtag",
        "tags", "topics", "r", "user", "users", "channel", "c"
    )

    fun extract(
        profileText: String,
        profileLinks: List<String>,
        sourceUrl: String,
        alreadyScannedUrls: Set<String>,
        sourcePlatformLabel: String
    ): List<PivotCandidate> {
        val found = mutableMapOf<String, PivotCandidate>()
        val scanned = alreadyScannedUrls.map(String::lowercase).toSet() + sourceUrl.lowercase()
        val fromPersonalWebsite = sourcePlatformLabel.contains("website", ignoreCase = true)

        val urlCandidates = (profileLinks + extractUrlsFromText(profileText))
            .distinct()
            .mapNotNull(::resolveProfileUrl)
            .filter { resolved ->
                val urlLower = resolved.url.lowercase()
                urlLower !in scanned &&
                    resolved.username.length in MIN_HANDLE_LEN..MAX_HANDLE_LEN &&
                    !isNonProfileHandle(resolved.username) &&
                    !resolved.username.equals("users.noreply.github.com", true)
            }

        urlCandidates.forEach { resolved ->
            val urlLower = resolved.url.lowercase()
            val signalType = if (fromPersonalWebsite) {
                PivotSignalType.PersonalWebsiteCrossLink
            } else {
                PivotSignalType.ExplicitProfileLink
            }
            val decision = PivotAdmissionPolicy.decide(
                PivotAdmissionRequest(
                    signalType = signalType,
                    normalizedValue = resolved.username,
                    confidence = 0.70f,
                    depth = 1,
                    alreadyVisited = urlLower in scanned
                )
            )
            val admitted = decision as? PivotAdmissionDecision.Admit ?: return@forEach
            found.putIfAbsent(
                urlLower,
                PivotCandidate(
                    candidate = UsernameCandidate(
                        username = resolved.username,
                        platform = resolved.platform,
                        url = resolved.url,
                        matchType = UsernameMatchType.FuzzyVariant,
                        confidence = 0.70f,
                        providerId = resolved.providerId
                    ),
                    provenance = "discovered via $sourcePlatformLabel profile; ${admitted.explanation}",
                    admissionExplanation = admitted.explanation
                )
            )
        }

        extractMentionHandles(profileText).forEach { (handle, platform) ->
            if (handle.length !in MIN_HANDLE_LEN..MAX_HANDLE_LEN) return@forEach
            if (isNonProfileHandle(handle)) return@forEach
            val template = PLATFORMS.firstOrNull { it.platform == platform } ?: return@forEach
            val url = template.urlPattern.replace("{username}", handle)
            val urlLower = url.lowercase()
            if (urlLower in scanned || urlLower in found.keys) return@forEach

            val decision = PivotAdmissionPolicy.decide(
                PivotAdmissionRequest(
                    signalType = PivotSignalType.ExplicitPlatformMention,
                    normalizedValue = handle,
                    confidence = 0.60f,
                    depth = 1,
                    alreadyVisited = false
                )
            )
            val admitted = decision as? PivotAdmissionDecision.Admit ?: return@forEach
            found[urlLower] = PivotCandidate(
                candidate = UsernameCandidate(
                    username = handle,
                    platform = platform,
                    url = url,
                    matchType = UsernameMatchType.FuzzyVariant,
                    confidence = 0.60f,
                    providerId = template.providerId
                ),
                provenance = "discovered via $sourcePlatformLabel profile; ${admitted.explanation}",
                admissionExplanation = admitted.explanation
            )
        }

        return found.values.toList()
    }

    private fun extractUrlsFromText(text: String): List<String> {
        val urlRegex = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
        return urlRegex.findAll(text).map { match ->
            match.value.trimEnd(',', '.', ')', ']', '}', ';', ':', '!')
        }.toList()
    }

    private fun extractMentionHandles(text: String): List<Pair<String, Platform>> {
        val results = mutableListOf<Pair<String, Platform>>()
        val textWithoutUrls = Regex("https?://\\S+").replace(text, " ")
        val lower = textWithoutUrls.lowercase()

        val platformNames = mapOf(
            "twitch" to Platform.Twitch,
            "github" to Platform.GitHub,
            "gitlab" to Platform.GitLab,
            "reddit" to Platform.Reddit,
            "instagram" to Platform.Instagram,
            "x" to Platform.X,
            "twitter" to Platform.X,
            "youtube" to Platform.YouTube,
            "tiktok" to Platform.TikTok,
            "telegram" to Platform.Telegram,
            "bluesky" to Platform.Bluesky,
            "mastodon" to Platform.Mastodon,
            "linkedin" to Platform.LinkedIn,
            "medium" to Platform.Medium,
            "threads" to Platform.Threads,
            "snapchat" to Platform.Snapchat,
            "discord" to Platform.Discord,
            "hacker news" to Platform.HackerNews,
            "hn" to Platform.HackerNews,
            "dev.to" to Platform.DevTo,
            "pinterest" to Platform.Pinterest,
            "facebook" to Platform.Facebook
        )

        val platformAlt = platformNames.keys
            .filter { it != "x" && it != "hn" }
            .joinToString("|") { Regex.escape(it) }
        val mentionRegex = Regex(
            "(?:$platformAlt)\\s*(?:as|:|@|->|is|id)?\\s*@?([a-z0-9][a-z0-9._-]{1,29})",
            RegexOption.IGNORE_CASE
        )
        mentionRegex.findAll(text).forEach { match ->
            val matchedPrefix = match.value
                .substring(0, match.value.length - match.groupValues[1].length)
                .lowercase()
                .trim()
            val platformKey = platformNames.keys.firstOrNull { matchedPrefix.startsWith(it) }
                ?: return@forEach
            val platform = platformNames[platformKey] ?: return@forEach
            val handle = match.groupValues[1].trim().removePrefix("@")
            if (handle.length !in MIN_HANDLE_LEN..MAX_HANDLE_LEN) return@forEach
            if (isNonProfileHandle(handle)) return@forEach
            results.add(handle to platform)
        }

        val atHandleRegex = Regex("@([a-z0-9._-]{2,30})", RegexOption.IGNORE_CASE)
        atHandleRegex.findAll(text).forEach { match ->
            val handle = match.groupValues[1].trim()
            if (isNonProfileHandle(handle)) return@forEach
            val start = (match.range.first - 40).coerceAtLeast(0)
            val end = (match.range.last + 40).coerceAtMost(text.length)
            val window = lower.substring(start, end)
            val platform = platformNames.entries.firstOrNull { (name, _) ->
                window.contains(name) && name != "x"
            }?.value ?: return@forEach
            results.add(handle to platform)
        }

        return results.distinctBy { it.first.lowercase() to it.second }
    }

    private fun isNonProfileHandle(handle: String): Boolean {
        val lower = handle.lowercase()
        if (lower in NON_PROFILE_PATHS) return true
        if (lower.all { it.isDigit() || !it.isLetterOrDigit() }) return true
        if (lower in setOf("http", "https", "www", "com", "org", "net", "html", "php", "aspx")) return true
        if (lower.endsWith("noreply.github.com")) return true
        return false
    }

    data class PivotCandidate(
        val candidate: UsernameCandidate,
        val provenance: String,
        val admissionExplanation: String = ""
    )
}
