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

        // Only generate underscore/hyphen variants if the username contains spaces
        // (i.e., multi-word input that gets joined). Never generate arbitrary
        // mid-point dot variants — they produce nonsense like "ja.ne".
        if (primary.contains(" ")) {
            variants.add(
                UsernameVariant(
                    primary.replace(" ", "_"),
                    UsernameMatchType.UnderscoreVariant
                )
            )
            variants.add(
                UsernameVariant(
                    primary.replace(" ", "-"),
                    UsernameMatchType.HyphenVariant
                )
            )
        }

        return variants.distinctBy { it.username }
    }

    /**
     * Derives plausible usernames from a real name, e.g. "Jane Doe"
     * → janedoe, jane.doe, jane_doe, jane, doe, j.doe, jdoe
     */
    fun generateFromName(fullName: String): List<UsernameVariant> {
        if (fullName.isBlank()) return emptyList()

        val parts = fullName.trim().lowercase()
            .split("\\s+".toRegex())
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

        // firstlast (e.g., janedoe)
        variants.add(UsernameVariant(first + last, UsernameMatchType.Exact))
        // first.last
        variants.add(UsernameVariant("$first.$last", UsernameMatchType.DotVariant))
        // first_last
        variants.add(UsernameVariant("${first}_$last", UsernameMatchType.UnderscoreVariant))
        // first-last
        variants.add(UsernameVariant("$first-$last", UsernameMatchType.HyphenVariant))
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

        return variants.distinctBy { it.username }
    }
}
