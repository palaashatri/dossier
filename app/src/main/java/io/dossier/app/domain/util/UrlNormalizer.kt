package io.dossier.app.domain.util

/**
 * Shared URL helpers used by scanners, downloaders, and platform resolvers.
 */
object UrlNormalizer {
    fun ensureHttps(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
    }

    fun isHttpUrl(raw: String): Boolean {
        val value = raw.trim()
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("//")
    }

    fun stripFragment(raw: String): String =
        raw.trim().substringBefore('#')
}
