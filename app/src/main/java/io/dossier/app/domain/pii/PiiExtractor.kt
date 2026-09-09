package io.dossier.app.domain.pii

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import java.net.URI

/** Attribution-aware PII extraction for public pages and search snippets. */
class PiiExtractor {
    private val emailRegex = Regex("(?i)(?<!@)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b")
    private val phoneRegex = Regex("(?<!\\d)(?:\\+\\d{1,3}[ .-]?)?(?:\\(?\\d{2,4}\\)?[ .-]?){2,5}\\d{2,4}(?!\\d)")
    private val addressContextRegex = Regex(
        """(?im)\b(?:street\s+address|mailing\s+address|home\s+address|postal\s+address|address|residence|lives\s+at|located\s+at)\s*[:\-]?\s*([^\r\n]{1,220})"""
    )
    private val streetAddressShapeRegex = Regex(
        """(?i)^\d{1,6}[A-Z]?(?:[-/]\d{1,6}[A-Z]?)?\s+[A-Z\p{L}][\p{L}0-9.'-]*(?:\s+[A-Z0-9\p{L}][\p{L}0-9.'-]*){0,8}\s+(?:street|st\.?|road|rd\.?|avenue|ave\.?|boulevard|blvd\.?|drive|dr\.?|lane|ln\.?|way|parkway|pkwy\.?|highway|hwy\.?|court|ct\.?|circle|cir\.?|terrace|ter\.?|trail|trl\.?|place|pl\.?)\b.*"""
    )
    private val postalCodeRegex = Regex(
        """(?i)(?<![\p{L}\d])(?:\d{5}(?:-\d{4})?|\d{6}|[A-Z]\d[A-Z]\s?\d[A-Z]\d)(?![\p{L}\d])"""
    )
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
                },
                // A page-level name/handle match does not by itself verify
                // every contact value printed on that page. Preserve the
                // independent-signal attribution so only a directly verified
                // profile can upgrade it for recursive pivoting.
                attribution = when {
                    exact -> FindingAttribution.ExactSelfSupplied
                    attribution.strong -> FindingAttribution.IndependentPageSignals
                    else -> FindingAttribution.Unconfirmed
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
                },
                attribution = when {
                    exact -> FindingAttribution.ExactSelfSupplied
                    attribution.strong -> FindingAttribution.IndependentPageSignals
                    else -> FindingAttribution.Unconfirmed
                }
            )
        }

        val addressRanges = mutableListOf<IntRange>()
        addressContextRegex.findAll(text).forEach { match ->
            val candidate = cleanAddressCandidate(match.groupValues[1])
            if (!streetAddressShapeRegex.matches(candidate)) return@forEach
            val groupRange = match.groups[1]?.range ?: return@forEach
            val valueStart = text.indexOf(candidate, groupRange.first)
            if (valueStart < 0) return@forEach
            val valueRange = valueStart..(valueStart + candidate.length - 1)
            addressRanges += valueRange
            addHighEntropyFinding(
                findings = findings,
                type = FindingType.Address,
                value = candidate,
                evidence = match.value,
                sourceUrl = sourceUrl,
                attribution = attribution,
                remediation = "Review and reduce precise public address exposure."
            )
        }

        postalCodeRegex.findAll(text).forEach { match ->
            val raw = match.value
            val normalized = raw.filterNot(Char::isWhitespace)
            if (looksLikeDateOrCounter(normalized, raw) || isPostalBoilerplate(text, match.range)) {
                return@forEach
            }
            if (!hasPostalContext(text, match.range, addressRanges)) return@forEach
            addHighEntropyFinding(
                findings = findings,
                type = FindingType.PostalCode,
                value = raw,
                // Postal evidence is intentionally line-scoped. A generic
                // +/- character snippet can pull an unrelated counter or
                // provider panel into the provenance shown for this value.
                evidence = lineSnippet(text, match.range),
                sourceUrl = sourceUrl,
                attribution = attribution,
                remediation = "Review and reduce precise public postal-code exposure."
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
            },
            attribution = when {
                exact -> FindingAttribution.ExactSelfSupplied
                attribution.strong -> FindingAttribution.IndependentPageSignals
                else -> FindingAttribution.Unconfirmed
            }
        )
    }

    private fun addHighEntropyFinding(
        findings: MutableList<Finding>,
        type: FindingType,
        value: String,
        evidence: String,
        sourceUrl: String,
        attribution: Attribution,
        remediation: String
    ) {
        val associated = attribution.strong
        findings += Finding(
            type = type,
            value = value,
            sourceUrl = sourceUrl,
            evidenceSnippet = "$evidence ${label(false, associated)}".take(260),
            confidence = if (associated) 0.68f else 0.36f,
            risk = if (associated) RiskLevel.High else RiskLevel.Low,
            remediation = remediation,
            attribution = if (associated) {
                FindingAttribution.IndependentPageSignals
            } else {
                FindingAttribution.Unconfirmed
            }
        )
    }

    private fun cleanAddressCandidate(raw: String): String {
        var value = raw.trim()
        TRAILING_CONTEXT_REGEX.find(value)?.let { boundary ->
            value = value.substring(0, boundary.range.first).trim()
        }
        SENTENCE_BOUNDARY_REGEX.find(value)?.let { boundary ->
            value = value.substring(0, boundary.range.first).trim()
        }
        value = value.trimEnd(',', ';', ':')
        if (value.endsWith('.') && !ADDRESS_ABBREVIATION_PERIOD_REGEX.containsMatchIn(value)) {
            value = value.dropLast(1)
        }
        return value.trim()
    }

    private fun hasPostalContext(
        text: String,
        range: IntRange,
        addressRanges: List<IntRange>
    ): Boolean {
        // Keep the label check on the same rendered line. Looking back across
        // an arbitrary character window lets a nearby navigation counter (for
        // example, "Views: 12345") inherit a postal label from an unrelated
        // address or provider panel above it.
        val lineStart = lineStart(text, range.first)
        val lineEnd = lineEnd(text, range.last)
        val beforeMatch = text.substring(lineStart, range.first).lowercase()
        if (range.last < lineEnd && POSTAL_CONTEXT_REGEX.containsMatchIn(beforeMatch)) return true

        return addressRanges.any { addressRange ->
            range.first >= addressRange.first &&
                range.last <= addressRange.last &&
                STREET_SUFFIX_REGEX.containsMatchIn(
                    text.substring(addressRange.first, range.first)
                )
        }
    }

    private fun isPostalBoilerplate(text: String, range: IntRange): Boolean {
        val lineStart = lineStart(text, range.first)
        val lineEnd = lineEnd(text, range.last)
        val line = text.substring(lineStart, lineEnd).lowercase()
        val matchStart = range.first - lineStart
        val matchEnd = range.last - lineStart + 1

        // Only treat boilerplate as belonging to this value when it is on the
        // same rendered line and close to the matched code. Looking through a
        // broad snippet lets a valid postal value inherit "postal code lookup"
        // from a later, unrelated panel.
        return POSTAL_BOILERPLATE.any { phrase ->
            var phraseStart = line.indexOf(phrase)
            while (phraseStart >= 0) {
                val phraseEnd = phraseStart + phrase.length
                val distance = when {
                    phraseEnd <= matchStart -> matchStart - phraseEnd
                    matchEnd <= phraseStart -> phraseStart - matchEnd
                    else -> 0
                }
                if (distance <= POSTAL_BOILERPLATE_CONTEXT_CHARS) return@any true
                phraseStart = line.indexOf(phrase, phraseStart + 1)
            }
            false
        }
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
            remediation,
            attribution = FindingAttribution.ExactSelfSupplied
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
            ,
            attribution = FindingAttribution.ExactSelfSupplied
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

    private fun lineSnippet(text: String, range: IntRange): String {
        val start = lineStart(text, range.first)
        val end = lineEnd(text, range.last)
        val rawLine = text.substring(start, end)
        val line = rawLine.replace(Regex("\\s+"), " ").trim()
        if (rawLine.length <= 240) return line

        // Keep a bounded local context while ensuring the exact value remains
        // visible when a source renders one very long line.
        val relativeStart = (range.first - start).coerceIn(0, rawLine.length)
        val windowStart = (relativeStart - 100).coerceAtLeast(0)
        val windowEnd = (windowStart + 240).coerceAtMost(rawLine.length)
        return rawLine.substring(windowStart, windowEnd).replace(Regex("\\s+"), " ").trim()
    }

    private fun lineStart(text: String, index: Int): Int {
        val newline = text.lastIndexOf('\n', index)
        val carriageReturn = text.lastIndexOf('\r', index)
        return maxOf(newline, carriageReturn) + 1
    }

    private fun lineEnd(text: String, index: Int): Int {
        val newline = text.indexOf('\n', index)
        val carriageReturn = text.indexOf('\r', index)
        return listOf(newline, carriageReturn)
            .filter { it >= 0 }
            .minOrNull()
            ?: text.length
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
        val POSTAL_CONTEXT_REGEX = Regex(
            "(?i)\\b(?:postal\\s+code|postcode|zip(?:\\s+code)?|zipcode|pin(?:\\s+code)?|pincode)\\b\\s*(?::|=|-)?\\s*$"
        )
        val STREET_SUFFIX_REGEX = Regex(
            "(?i)\\b(?:street|st\\.?|road|rd\\.?|avenue|ave\\.?|boulevard|blvd\\.?|drive|dr\\.?|lane|ln\\.?|way|parkway|pkwy\\.?|highway|hwy\\.?|court|ct\\.?|circle|cir\\.?|terrace|ter\\.?|trail|trl\\.?|place|pl\\.?)\\b"
        )
        val POSTAL_BOILERPLATE = listOf(
            "postal code lookup",
            "postcode lookup",
            "zip code lookup",
            "pincode search",
            "enter your postal",
            "enter your zip",
            "postal code required",
            "zip code required"
        )
        const val POSTAL_BOILERPLATE_CONTEXT_CHARS = 32
        val TRAILING_CONTEXT_REGEX = Regex(
            """(?i)\s+(?:phone|mobile|telephone|tel|email|e-mail|contact|views?|followers?|following|likes?|posted|updated|build|date|website|url|navigation|search|lookup|enter)\s*(?::|=|-|\b)"""
        )
        val SENTENCE_BOUNDARY_REGEX = Regex("\\.(?=\\s+[A-Z])")
        val ADDRESS_ABBREVIATION_PERIOD_REGEX = Regex(
            """(?i)\b(?:st|rd|ave|blvd|dr|ln|pkwy|hwy|ct|cir|ter|trl|pl)\.$"""
        )
        val ORG_SUFFIXES = listOf(" inc", " corp", " ltd", " llc", " university", " college", " systems", " technologies", " labs")
        val LOCATION_SUFFIXES = listOf(" city", " state", " province", " county", " district")
        val KNOWN_ORGS = setOf("Replit", "Google", "Microsoft", "Meta", "Amazon", "Apple", "GitHub", "GitLab", "Azul", "Azul Systems", "OpenAI", "Anthropic", "IIT Delhi", "MIT", "Stanford")
        val KNOWN_LOCATIONS = setOf("India", "Delhi", "New Delhi", "Gurgaon", "Gurugram", "Noida", "Bangalore", "Bengaluru", "Mumbai", "Pune", "Hyderabad", "Chennai", "Kolkata", "New York", "London", "Berlin", "Paris")
    }
}
