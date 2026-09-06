package io.dossier.app.data.platform

import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.QueryCapability
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.PlatformProfileTemplate

private data class LegacyProviderEntry(
    val providerId: String,
    val platform: Platform,
    val urlPattern: String,
    val requiresLoginUsually: Boolean
)

private val LEGACY_PROVIDER_ENTRIES: List<LegacyProviderEntry> = ProviderCatalogV2.definitions
    .asSequence()
    .filter { definition ->
        definition.profileUrlTemplate != null &&
            QueryCapability.Username in definition.queryCapabilities &&
            definition.legacyTemplateCompatible
    }
    .map { definition ->
        LegacyProviderEntry(
            providerId = definition.id,
            platform = definition.legacyPlatformName
                ?.let { name -> runCatching { Platform.valueOf(name) }.getOrNull() }
                ?: Platform.Website,
            urlPattern = requireNotNull(definition.profileUrlTemplate),
            requiresLoginUsually = "authentication-often-required" in definition.tags
        )
    }
    .toList()

/**
 * Compatibility view consumed by the existing ProfileScanner and HandleExtractor.
 *
 * Discovery Fabric v2 is authoritative for provider metadata. The list keeps
 * all resolvable templates available for explicit URLs, while
 * shouldFetchByDefault is computed from the currently selected real scan plan.
 * This lets Quick/Standard/Deep/Exhaustive alter actual provider fan-out without
 * duplicating the legacy scanner or faking progress totals.
 */
val PLATFORMS: List<PlatformProfileTemplate> = object : AbstractList<PlatformProfileTemplate>() {
    override val size: Int
        get() = LEGACY_PROVIDER_ENTRIES.size

    override fun get(index: Int): PlatformProfileTemplate {
        val entry = LEGACY_PROVIDER_ENTRIES[index]
        val plannedIds = ProviderCatalogV2
            .plan(DiscoveryScanPreferences.selectedMode.value)
            .providers
            .asSequence()
            .map { it.id }
            .toSet()
        return PlatformProfileTemplate(
            platform = entry.platform,
            urlPattern = entry.urlPattern,
            requiresLoginUsually = entry.requiresLoginUsually,
            shouldFetchByDefault = entry.providerId in plannedIds,
            providerId = entry.providerId
        )
    }
}

data class ResolvedProfile(
    val platform: Platform,
    val username: String,
    val url: String,
    val providerId: String? = null
)

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
        if (template.platform == Platform.Website && template.urlPattern.contains("{username}").not()) continue
        val pattern = template.urlPattern
        val patternHost = pattern
            .replace("https://", "", ignoreCase = true)
            .replace("http://", "", ignoreCase = true)
            .replace("www.", "", ignoreCase = true)
            .substringBefore("/")
            .lowercase()
        if (patternHost.isBlank() || patternHost.contains("{username}")) continue

        val hostMatches = normalizedHost == patternHost ||
            host == patternHost ||
            host.endsWith(".$patternHost")
        if (!hostMatches) continue

        val placeholder = "{username}"
        if (!pattern.contains(placeholder)) continue

        if (pattern.contains("?id=$placeholder")) {
            val idValue = query.substringAfter("id=", "").substringBefore("&").trim()
            if (idValue.isNotBlank() && idValue.length >= 2) {
                return ResolvedProfile(
                    template.platform,
                    idValue,
                    template.urlPattern.replace(placeholder, idValue),
                    template.providerId
                )
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
            return ResolvedProfile(
                template.platform,
                handle,
                template.urlPattern.replace(placeholder, handle),
                template.providerId
            )
        }
    }
    return null
}
