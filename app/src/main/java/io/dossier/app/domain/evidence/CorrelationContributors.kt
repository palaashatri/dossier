package io.dossier.app.domain.evidence

import java.util.Locale

/**
 * Contributors that raise confidence two observed values belong to the same
 * subject, derived purely from fields already carried by [Evidence] (value /
 * kind). Each returns explainable [ConfidenceContributor.Signals] (ROADMAP
 * Principle 3).
 *
 * NOTE: a "shared avatar" contributor is intentionally deferred — it would need
 * avatar bytes/URLs threaded into [Evidence] (an optional media field), which is
 * a separate, larger change. These three cover the signals available today.
 */

/** Two emails on the same domain (e.g. both @company.com) → likely same org/person. */
class EmailDomainContributor : ConfidenceContributor {
    override val id: String = "email-domain"

    override fun contribute(a: Evidence, b: Evidence): ConfidenceContributor.Signals? {
        if (a.kind != EvidenceKind.Email || b.kind != EvidenceKind.Email) return null
        val da = domainOf(a.value) ?: return null
        val db = domainOf(b.value) ?: return null
        if (da.equals(db, ignoreCase = true)) {
            return ConfidenceContributor.Signals(
                score = 0.7f,
                reasons = listOf("Both emails share domain '$da'")
            )
        }
        return null
    }

    private fun domainOf(email: String): String? {
        val at = email.indexOf('@')
        if (at < 0 || at == email.length - 1) return null
        return email.substring(at + 1).trim().lowercase(Locale.US).takeIf { it.isNotBlank() }
    }
}

/** An email or website value that contains a known username → cross-kind link. */
class SharedIdentifierContributor(
    private val usernames: Set<String> = emptySet()
) : ConfidenceContributor {
    override val id: String = "shared-identifier"

    override fun contribute(a: Evidence, b: Evidence): ConfidenceContributor.Signals? {
        // Only meaningful when one side is a known identity seed (username) and
        // the other carries that username inside an email/website value.
        val seeds = usernames.ifEmpty { null } ?: return null
        val (username, other) = when {
            a.kind == EvidenceKind.Username && b.kind != EvidenceKind.Username ->
                a.value to b
            b.kind == EvidenceKind.Username && a.kind != EvidenceKind.Username ->
                b.value to a
            else -> return null
        }
        val norm = username.trim().lowercase(Locale.US)
        if (norm.isBlank()) return null
        val haystack = other.value.lowercase(Locale.US)
        if (haystack.contains(norm)) {
            return ConfidenceContributor.Signals(
                score = 0.65f,
                reasons = listOf("Value '${other.value}' contains username '$username'")
            )
        }
        return null
    }
}

/** Two website/profile evidence on the same registered domain → same subject. */
class SharedDomainContributor : ConfidenceContributor {
    override val id: String = "shared-domain"

    override fun contribute(a: Evidence, b: Evidence): ConfidenceContributor.Signals? {
        if (a.kind !in WEB_KINDS || b.kind !in WEB_KINDS) return null
        val da = registrableDomain(a.value) ?: return null
        val db = registrableDomain(b.value) ?: return null
        if (da.equals(db, ignoreCase = true)) {
            return ConfidenceContributor.Signals(
                score = 0.6f,
                reasons = listOf("Both links share domain '$da'")
            )
        }
        return null
    }

    private fun registrableDomain(url: String): String? {
        val cleaned = url.lowercase(Locale.US)
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        val slash = cleaned.indexOf('/')
        val host = if (slash >= 0) cleaned.substring(0, slash) else cleaned
        val at = host.indexOf('@')
        val bare = if (at >= 0) host.substring(at + 1) else host
        if (bare.isBlank() || !bare.contains('.')) return null
        // crude registrable-domain (last two labels) — sufficient for correlation
        val labels = bare.split('.')
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else bare
    }

    companion object {
        private val WEB_KINDS = setOf(
            EvidenceKind.Profile,
            EvidenceKind.PublicSearchEvidence,
            EvidenceKind.PublicImageEvidence
        )
    }
}
