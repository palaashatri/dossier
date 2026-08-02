package io.dossier.app.domain.pii

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import java.net.URI

/** Attribution-aware PII extraction for public pages and search snippets. */
class PiiExtractor {
    private val emailRegex = Regex("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b")
    private val phoneRegex = Regex("(?<!\\d)(?:\\+\\d{1,3}[ .-]?)?(?:\\(?\\d{2,4}\\)?[ .-]?){2,5}\\d{2,4}(?!\\d)")
    private val locationRegex = Regex(
        "\\b(?i:lives in|based in|located in|from|location\\s*:)\\s+" +
            "([A-Z][\\p{L}.'-]+(?:\\s+[A-Z][\\p{L}.'-]+){0,4})"
    )
    private val orgRegex = Regex(
        "\\b(?i:works at|studied at|employed at|member of|developer at|engineer at|student at|" +
            "designer at|lead at|intern at|manager at|company\\s*:)\\s+" +
            "([A-Z][\\p{L}0-9&.'-]+(?:\\s+[A-Z][\\p{L}0-9&.'-]+){0,5})"
    )

    fun extract(text: String, sourceUrl: String, identity: IdentityInput? = null): List<Finding> {
        if (text.isBlank()) return emptyList()
        val findings = mutableListOf<Finding>()
        val attribution = attribution(text, sourceUrl, identity)
        val suppliedEmails = identity?.emails.orEmpty().map { it.trim().lowercase() }.toSet()
        val suppliedPhones = identity?.phones.orEmpty().map(::digits).filter { it.length >= 8 }.toSet()

        emailRegex.findAll(text).forEach { match ->
            val exact = match.value.lowercase() in suppliedEmails
            val associated = exact || attribution.strong
            findings += Finding(
                FindingType.Email,
                match.value,
                sourceUrl,
                snippetWithAttribution(text, match.range, exact, associated),
                when { exact -> 0.99f; associated -> 0.72f; else -> 0.42f },
                when { exact -> RiskLevel.High; associated -> RiskLevel.Medium; else -> RiskLevel.Low },
                if (exact) {
                    "This exact self-supplied email is public. Remove it or replace it with a masked alias."
                } else {
                    "Review the source before associating this email with the audited identity."
                }
            )
        }

        phoneRegex.findAll(text).forEach { match ->
            val normalized = digits(match.value)
            val exact = normalized in suppliedPhones
            if (normalized.length !in 8..15 || looksLikeDateOrCounter(normalized, match.value)) return@forEach
            if (!exact && !hasPhoneContext(text, match.range)) return@forEach
            val associated = exact || attribution.strong
            findings += Finding(
                FindingType.Phone,
                match.value.trim(),
                sourceUrl,
                snippetWithAttribution(text, match.range, exact, associated),
                when { exact -> 0.99f; associated -> 0.68f; else -> 0.36f },
                when { exact -> RiskLevel.Critical; associated -> RiskLevel.High; else -> RiskLevel.Low },
                if (exact) {
                    "This exact self-supplied phone number is public. Remove it and review account recovery exposure."
                } else {
                    "Review the context before treating this phone number as belonging to the subject."
                }
            )
        }

        locationRegex.findAll(text).forEach { match ->
            addContextualFinding(findings, match.groupValues[1], match.value, sourceUrl, identity, attribution, true)
        }
        orgRegex.findAll(text).forEach { match ->
            addContextualFinding(findings, match.groupValues[1], match.value, sourceUrl, identity, attribution, false)
        }

        identity?.let { supplied ->
            addNamedExposure(
                findings, text, sourceUrl, supplied.fullName, "Name Exposure",
                if (attribution.signalCount >= 2) 0.95f else 0.65f,
                if (attribution.signalCount >= 2) RiskLevel.High else RiskLevel.Medium,
                "Reduce unnecessary real-name exposure on profiles that should not be connected."
            )
            supplied.aliases.forEach { alias ->
                addNamedExposure(
                    findings, text, sourceUrl, alias, "Alias Exposure",
                    if (attribution.urlHandleMatch) 0.90f else 0.62f,
                    if (attribution.urlHandleMatch) RiskLevel.Medium else RiskLevel.Low,
                    "Confirm page ownership before changing an alias based on this result."
                )
            }
            supplied.locations.forEach {
                addExactTyped(findings, text, sourceUrl, it, FindingType.Location, attribution)
            }
            supplied.organizations.forEach {
                addExactTyped(findings, text, sourceUrl, it, FindingType.Organization, attribution)
            }
        }

        return findings.filter { it.value.isNotBlank() }.distinctBy {
            "${it.type}|${canonical(it.type, it.value)}|${it.sourceUrl}"
        }
    }

    private fun addContextualFinding(
        findings: MutableList<Finding>,
        rawValue: String,
        evidence: String,
        sourceUrl: String,
        identity: IdentityInput?,
        attribution: Attribution,
        preferLocation: Boolean
    ) {
        val value = rawValue.trim().trimEnd('.', ',', ';', ':')
        val type = classify(value, evidence, identity, preferLocation)
        val exact = when (type) {
            FindingType.Location -> identity?.locations.orEmpty().any { same(it, value) }
            FindingType.Organization -> identity?.organizations.orEmpty().any { same(it, value) }
            else -> false
        }
        val associated = exact || attribution.strong
        findings += Finding(
            type,
            value,
            sourceUrl,
            "$evidence ${label(exact, associated)}".take(260),
            when { exact -> 0.90f; associated -> 0.64f; else -> 0.44f },
            when {
                exact && type == FindingType.Location -> RiskLevel.High
                exact || associated -> RiskLevel.Medium
                else -> RiskLevel.Low
            },
            if (type == FindingType.Location) {
                "Review whether this location is necessary and reduce its precision where possible."
            } else {
                "Review whether this organisation association should remain public."
            }
        )
    }

    private fun addNamedExposure(
        findings: MutableList<Finding>,
        text: String,
        sourceUrl: String,
        supplied: String,
        prefix: String,
        confidence: Float,
        risk: RiskLevel,
        remediation: String
    ) {
        val clean = supplied.trim()
        if (clean.length < 2) return
        val match = exactTermRegex(clean).find(text) ?: return
        findings += Finding(
            FindingType.SensitiveSnippet,
            "$prefix: $clean",
            sourceUrl,
            snippet(text, match.range),
            confidence,
            risk,
            remediation
        )
    }

    private fun addExactTyped(
        findings: MutableList<Finding>,
        text: String,
        sourceUrl: String,
        supplied: String,
        type: FindingType,
        attribution: Attribution
    ) {
        val clean = supplied.trim()
        if (clean.length < 2 || findings.any { it.type == type && same(it.value, clean) }) return
        val match = exactTermRegex(clean).find(text) ?: return
        findings += Finding(
            type,
            clean,
            sourceUrl,
            "${snippet(text, match.range)} [exact self-supplied match]",
            if (attribution.strong) 0.92f else 0.80f,
            if (type == FindingType.Location) RiskLevel.High else RiskLevel.Medium,
            if (type == FindingType.Location) "Reduce precise public location exposure." else "Review this public organisation association."
        )
    }

    private data class Attribution(val urlHandleMatch: Boolean, val signalCount: Int) {
        val strong: Boolean get() = urlHandleMatch || signalCount >= 2
    }

    private fun attribution(text: String, sourceUrl: String, identity: IdentityInput?): Attribution {
        if (identity == null) return Attribution(false, 0)
        val lowerText = text.lowercase()
        val handles = (listOfNotNull(identity.primaryUsername) + identity.usernames + identity.aliases)
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.length >= 2 }
            .distinct()
        val urlSegments = runCatching {
            URI(sourceUrl).path.orEmpty().split('/').map { it.removePrefix("@").lowercase() }
        }.getOrDefault(emptyList())
        val urlHandleMatch = handles.any { it in urlSegments }
        var signals = 0
        if (identity.fullName.trim().length >= 3 && lowerText.contains(identity.fullName.trim().lowercase())) signals++
        if (identity.emails.any { it.isNotBlank() && lowerText.contains(it.trim().lowercase()) }) signals += 2
        val textDigits = text.filter(Char::isDigit)
        if (identity.phones.map(::digits).filter { it.length >= 8 }.any(textDigits::contains)) signals += 2
        if (handles.any(lowerText::contains)) signals++
        if (identity.organizations.any { it.length >= 3 && lowerText.contains(it.trim().lowercase()) }) signals++
        if (identity.locations.any { it.length >= 3 && lowerText.contains(it.trim().lowercase()) }) signals++
        return Attribution(urlHandleMatch, signals)
    }

    private fun classify(value: String, evidence: String, identity: IdentityInput?, preferLocation: Boolean): FindingType {
        if (identity?.organizations.orEmpty().any { same(it, value) }) return FindingType.Organization
        if (identity?.locations.orEmpty().any { same(it, value) }) return FindingType.Location
        val lower = value.lowercase()
        if (KNOWN_ORGS.any { same(it, value) } || ORG_SUFFIXES.any(lower::endsWith)) return FindingType.Organization
        if (KNOWN_LOCATIONS.any { same(it, value) } || LOCATION_SUFFIXES.any(lower::endsWith)) return FindingType.Location
        val context = evidence.lowercase()
        if (listOf("works at", "studied at", "employed at", "member of", "engineer at", "developer at").any(context::contains)) {
            return FindingType.Organization
        }
        if (listOf("lives in", "based in", "located in", "location").any(context::contains)) return FindingType.Location
        return if (preferLocation) FindingType.Location else FindingType.Organization
    }

    private fun hasPhoneContext(text: String, range: IntRange): Boolean {
        val context = snippet(text, range).lowercase()
        return PHONE_CONTEXT.any(context::contains) || text.substring(range).trim().startsWith('+')
    }

    private fun looksLikeDateOrCounter(normalized: String, raw: String): Boolean {
        if (normalized.length == 8 && normalized.take(4).toIntOrNull() in 1900..2100) return true
        if (raw.count { it == '-' || it == '/' } >= 2 && !raw.trim().startsWith('+')) return true
        return normalized.toSet().size == 1
    }

    private fun snippetWithAttribution(text: String, range: IntRange, exact: Boolean, associated: Boolean): String =
        "${snippet(text, range)} ${label(exact, associated)}"

    private fun label(exact: Boolean, associated: Boolean): String = when {
        exact -> "[exact self-supplied identifier]"
        associated -> "[page has independent identity signals]"
        else -> "[detected; attribution unconfirmed]"
    }

    private fun snippet(text: String, range: IntRange): String {
        val start = (range.first - 60).coerceAtLeast(0)
        val end = (range.last + 61).coerceAtMost(text.length)
        return text.substring(start, end).replace(Regex("\\s+"), " ").trim().take(240)
    }

    private fun exactTermRegex(value: String) =
        Regex("(?i)(?<![\\p{L}0-9])${Regex.escape(value)}(?![\\p{L}0-9])")

    private fun digits(value: String) = value.filter(Char::isDigit)
    private fun same(first: String, second: String) = first.trim().equals(second.trim(), ignoreCase = true)
    private fun canonical(type: FindingType, value: String) = when (type) {
        FindingType.Email -> value.trim().lowercase()
        FindingType.Phone -> digits(value)
        else -> value.trim().lowercase()
    }

    private companion object {
        val PHONE_CONTEXT = listOf("phone", "mobile", "telephone", "tel:", "call", "contact", "whatsapp", "signal")
        val ORG_SUFFIXES = listOf(" inc", " corp", " ltd", " llc", " university", " college", " systems", " technologies", " labs")
        val LOCATION_SUFFIXES = listOf(" city", " state", " province", " county", " district")
        val KNOWN_ORGS = setOf("Replit", "Google", "Microsoft", "Meta", "Amazon", "Apple", "GitHub", "GitLab", "Azul", "Azul Systems", "OpenAI", "Anthropic", "IIT Delhi", "MIT", "Stanford")
        val KNOWN_LOCATIONS = setOf("India", "Delhi", "New Delhi", "Gurgaon", "Gurugram", "Noida", "Bangalore", "Bengaluru", "Mumbai", "Pune", "Hyderabad", "Chennai", "Kolkata", "New York", "London", "Berlin", "Paris")
    }
}
