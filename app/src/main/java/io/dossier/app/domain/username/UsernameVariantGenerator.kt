package io.dossier.app.domain.username

import io.dossier.app.domain.model.UsernameMatchType

data class UsernameVariant(
    val username: String,
    val type: UsernameMatchType
)

class UsernameVariantGenerator {
    fun generate(primary: String): List<UsernameVariant> {
        if (primary.isBlank()) return emptyList()

        val variants = mutableListOf<UsernameVariant>()
        variants.add(UsernameVariant(primary, UsernameMatchType.Exact))

        // Multi-word primary username: space → underscore / hyphen / dot / concat.
        // Never generate arbitrary mid-point splits — they produce nonsense like "ja.ne".
        if (primary.contains(" ")) {
            val collapsed = primary.replace("\\s+".toRegex(), " ").trim()
            variants.add(
                UsernameVariant(
                    collapsed.replace(" ", "_"),
                    UsernameMatchType.UnderscoreVariant
                )
            )
            variants.add(
                UsernameVariant(
                    collapsed.replace(" ", "-"),
                    UsernameMatchType.HyphenVariant
                )
            )
            variants.add(
                UsernameVariant(
                    collapsed.replace(" ", "."),
                    UsernameMatchType.DotVariant
                )
            )
            variants.add(
                UsernameVariant(
                    collapsed.replace(" ", ""),
                    UsernameMatchType.Exact
                )
            )
        }

        return variants
            .map { it.copy(username = it.username.trim()) }
            .filter { it.username.isNotBlank() }
            .distinctBy { it.username.lowercase() }
    }

    /**
     * Derives plausible usernames from a real name, e.g. "Jane Doe"
     * → janedoe, jane.doe, jane_doe, jane, doe, j.doe, jdoe, doe.jane
     *
     * No random birth-year suffixes (false positives). Leet is intentionally
     * omitted for the same reason.
     */
    fun generateFromName(fullName: String): List<UsernameVariant> {
        if (fullName.isBlank()) return emptyList()

        val parts = fullName.trim().lowercase()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotBlank() }

        if (parts.isEmpty()) return emptyList()

        val variants = mutableListOf<UsernameVariant>()

        if (parts.size == 1) {
            // Single word name — only check exact match, nothing else.
            // Generating variants like "ja.ne" or "jane_" causes false positives
            // against random accounts belonging to other people.
            variants.add(UsernameVariant(parts[0], UsernameMatchType.Exact))
            return variants
        }

        val first = parts.first()
        val last = parts.last()
        val middle = if (parts.size > 2) parts[1] else null

        // firstlast (e.g., janedoe) — no digit/year suffixes
        variants.add(UsernameVariant(first + last, UsernameMatchType.Exact))
        // first.last
        variants.add(UsernameVariant("$first.$last", UsernameMatchType.DotVariant))
        // first_last
        variants.add(UsernameVariant("${first}_$last", UsernameMatchType.UnderscoreVariant))
        // first-last
        variants.add(UsernameVariant("$first-$last", UsernameMatchType.HyphenVariant))
        // last.first (common email/handle order)
        variants.add(UsernameVariant("$last.$first", UsernameMatchType.DotVariant))
        // last_first / lastfirst
        variants.add(UsernameVariant("${last}_$first", UsernameMatchType.UnderscoreVariant))
        variants.add(UsernameVariant(last + first, UsernameMatchType.FuzzyVariant))
        // first alone
        variants.add(UsernameVariant(first, UsernameMatchType.FuzzyVariant))
        // last alone
        variants.add(UsernameVariant(last, UsernameMatchType.FuzzyVariant))
        // first initial + last (e.g., jdoe)
        if (first.isNotEmpty()) {
            variants.add(UsernameVariant("${first[0]}$last", UsernameMatchType.FuzzyVariant))
            variants.add(UsernameVariant("${first[0]}.$last", UsernameMatchType.DotVariant))
        }
        // last + first initial
        if (first.isNotEmpty()) {
            variants.add(UsernameVariant("$last${first[0]}", UsernameMatchType.FuzzyVariant))
        }
        // first + last initial
        if (last.isNotEmpty()) {
            variants.add(UsernameVariant("$first${last[0]}", UsernameMatchType.FuzzyVariant))
        }
        // middle combinations
        if (middle != null) {
            variants.add(UsernameVariant(first + middle + last, UsernameMatchType.FuzzyVariant))
            variants.add(UsernameVariant("$first.$middle.$last", UsernameMatchType.DotVariant))
        }

        return variants
            .filter { it.username.length >= 2 }
            .distinctBy { it.username.lowercase() }
    }

    /**
     * Derives handle-like variants from an email local-part (before @).
     * Cleans non-alnum to separators; keeps exact plus underscore/dot/hyphen splits.
     */
    fun generateFromEmailLocalPart(local: String): List<UsernameVariant> {
        val raw = local.trim().removePrefix("+").substringBefore("+") // drop plus-addressing tags
        if (raw.isBlank()) return emptyList()

        val variants = mutableListOf<UsernameVariant>()

        // Exact cleaned local (keep alnum + common separators)
        val exact = raw.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (exact.length >= 2) {
            variants.add(UsernameVariant(exact, UsernameMatchType.Exact))
        }

        // Normalize non-alnum runs into a single separator, then emit split forms
        val tokens = raw.lowercase()
            .split("[^a-z0-9]+".toRegex())
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return variants.distinctBy { it.username.lowercase() }

        if (tokens.size == 1) {
            val t = tokens[0]
            if (t.length >= 2) {
                variants.add(UsernameVariant(t, UsernameMatchType.Exact))
            }
            return variants.distinctBy { it.username.lowercase() }
        }

        val joinedDot = tokens.joinToString(".")
        val joinedUnderscore = tokens.joinToString("_")
        val joinedHyphen = tokens.joinToString("-")
        val joinedConcat = tokens.joinToString("")

        variants.add(UsernameVariant(joinedDot, UsernameMatchType.DotVariant))
        variants.add(UsernameVariant(joinedUnderscore, UsernameMatchType.UnderscoreVariant))
        variants.add(UsernameVariant(joinedHyphen, UsernameMatchType.HyphenVariant))
        if (joinedConcat.length >= 2) {
            variants.add(UsernameVariant(joinedConcat, UsernameMatchType.Exact))
        }

        // Also keep first/last token alone when multi-token (common handle reuse)
        tokens.filter { it.length >= 3 }.forEach { token ->
            variants.add(UsernameVariant(token, UsernameMatchType.FuzzyVariant))
        }

        return variants
            .filter { it.username.length >= 2 }
            .distinctBy { it.username.lowercase() }
    }

    fun generateFromEmails(emails: List<String>): List<UsernameVariant> {
        return emails
            .mapNotNull { email ->
                val trimmed = email.trim()
                if (trimmed.isBlank() || !trimmed.contains("@")) return@mapNotNull null
                trimmed.substringBefore("@").takeIf { it.isNotBlank() }
            }
            .flatMap { generateFromEmailLocalPart(it) }
            .distinctBy { it.username.lowercase() }
    }

    /**
     * Convenience: union variants from primary username, full name, extra usernames,
     * and email local-parts. Deduped case-insensitively, first type wins.
     */
    fun generateAllSeeds(
        primary: String?,
        name: String?,
        usernames: List<String> = emptyList(),
        emails: List<String> = emptyList()
    ): List<UsernameVariant> {
        val variants = mutableListOf<UsernameVariant>()

        if (!primary.isNullOrBlank()) {
            variants.addAll(generate(primary))
        }
        if (!name.isNullOrBlank()) {
            variants.addAll(generateFromName(name))
        }
        usernames.filter { it.isNotBlank() }.forEach { u ->
            variants.add(UsernameVariant(u.trim(), UsernameMatchType.Exact))
            variants.addAll(generate(u))
        }
        variants.addAll(generateFromEmails(emails))

        return variants.distinctBy { it.username.lowercase() }
    }
}
