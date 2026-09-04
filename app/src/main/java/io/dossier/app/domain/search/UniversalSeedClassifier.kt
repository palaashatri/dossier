package io.dossier.app.domain.search

import io.dossier.app.domain.model.IdentityInput
import java.net.URI

/** The single starting signal accepted by Dossier's universal search entry. */
enum class UniversalSeedType {
    Name,
    Username,
    Phone,
    Email,
    Url
}

data class UniversalSeed(
    val type: UniversalSeedType,
    val raw: String,
    val normalized: String
) {
    fun toIdentityInput(): IdentityInput = when (type) {
        UniversalSeedType.Name -> IdentityInput(fullName = normalized)
        UniversalSeedType.Username -> IdentityInput(
            fullName = "",
            primaryUsername = normalized,
            usernames = listOf(normalized)
        )
        UniversalSeedType.Phone -> IdentityInput(
            fullName = "",
            phones = listOf(normalized)
        )
        UniversalSeedType.Email -> IdentityInput(
            fullName = "",
            emails = listOf(normalized)
        )
        UniversalSeedType.Url -> IdentityInput(
            fullName = "",
            profileUrls = listOf(normalized)
        )
    }
}

/**
 * Deterministic, local-only first-pass classifier for the universal launch box.
 *
 * Ambiguous single-token text deliberately uses a small heuristic rather than a
 * remote classifier. The UI exposes the detected type so the user can correct
 * ambiguity without sending the seed anywhere merely to classify it.
 */
object UniversalSeedClassifier {
    fun classify(raw: String): UniversalSeed? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        if (EMAIL.matches(trimmed)) {
            return UniversalSeed(UniversalSeedType.Email, raw, trimmed)
        }

        normalizePhone(trimmed)?.let { phone ->
            return UniversalSeed(UniversalSeedType.Phone, raw, phone)
        }

        if (isWebUrl(trimmed)) {
            return UniversalSeed(UniversalSeedType.Url, raw, trimmed)
        }

        val explicitHandle = trimmed.startsWith("@")
        val handle = trimmed.removePrefix("@")
        if (HANDLE.matches(handle) && (explicitHandle || looksLikeBareHandle(trimmed))) {
            return UniversalSeed(UniversalSeedType.Username, raw, handle)
        }

        val normalizedName = trimmed
            .split(WHITESPACE)
            .filter(String::isNotBlank)
            .joinToString(" ")
        return UniversalSeed(UniversalSeedType.Name, raw, normalizedName)
    }

    private fun normalizePhone(value: String): String? {
        if (!PHONE_CHARS.matches(value)) return null
        val digits = value.filter(Char::isDigit)
        if (digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) return null
        return if (value.startsWith('+')) "+$digits" else digits
    }

    private fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)

    private fun looksLikeBareHandle(value: String): Boolean {
        if (value.any(Char::isWhitespace)) return false
        if (value.any { it == '_' || it == '.' || it == '-' || it.isDigit() }) return true
        return value.any(Char::isLetter) && value.none(Char::isUpperCase)
    }

    private const val MIN_PHONE_DIGITS = 7
    private const val MAX_PHONE_DIGITS = 15
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val PHONE_CHARS = Regex("^\\+?[0-9][0-9\\s().-]{5,}$")
    private val HANDLE = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{1,63}")
    private val WHITESPACE = Regex("\\s+")
}

/** Shared scan-validity contract for text and photo universal seeds. */
fun IdentityInput.hasUsableUniversalSeed(): Boolean =
    fullName.isNotBlank() ||
        !primaryUsername.isNullOrBlank() ||
        usernames.any { it.isNotBlank() } ||
        emails.any { it.isNotBlank() } ||
        phones.any { it.isNotBlank() } ||
        profileUrls.any { it.isNotBlank() } ||
        !selfieUri.isNullOrBlank()
