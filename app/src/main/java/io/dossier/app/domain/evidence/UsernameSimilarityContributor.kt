package io.dossier.app.domain.evidence

/**
 * Example [ConfidenceContributor] (ROADMAP Milestone 7) that raises confidence
 * two observed values are the same subject when their usernames are similar
 * (exact, dot/underscore/hyphen variants, or case-insensitive equality).
 *
 * Every non-null result carries the named reasons so the conclusion is
 * explainable (ROADMAP Principle 3): we never say "same person" without stating
 * the signals that led there.
 */
class UsernameSimilarityContributor : ConfidenceContributor {

    override val id: String = "username-similarity"

    override fun contribute(a: Evidence, b: Evidence): ConfidenceContributor.Signals? {
        if (a.kind != EvidenceKind.Username || b.kind != EvidenceKind.Username) return null

        val x = a.value.trim()
        val y = b.value.trim()
        if (x.isBlank() || y.isBlank() || x.equals(y, ignoreCase = true)) {
            return null // identical handled elsewhere; here we look at variants
        }

        val reasons = mutableListOf<String>()
        var score = 0f

        when {
            x.equals(y, ignoreCase = true) -> {
                reasons += "Same username (case-insensitive)"
                score = maxOf(score, 0.9f)
            }
            normalize(x) == normalize(y) -> {
                reasons += "Same username after stripping separators (._-)"
                score = maxOf(score, 0.85f)
            }
            x.contains(y, ignoreCase = true) || y.contains(x, ignoreCase = true) -> {
                reasons += "One username is a substring of the other"
                score = maxOf(score, 0.6f)
            }
        }

        if (reasons.isEmpty()) return null
        return ConfidenceContributor.Signals(score = score, reasons = reasons)
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[._\\-]"), "")
}
