package io.dossier.app.domain.pii

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import java.net.URI

/**
 * Extracts possible public PII while keeping content detection separate from
 * attribution to the audited identity.
 *
 * Exact self-supplied identifiers may receive high confidence. Generic regex
 * hits remain low/review confidence unless independent identity signals connect
 * the page to the subject.
 */
class PiiExtractor {
    private val emailRegex = Regex("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b")
    private val phoneRegex = Regex("(?<!\\d)(?:\\+\\d{1,3}[ .-]?)?(?:\\(?\\d{2,4}\\)?[ .-]?){2,5}\\d{2,4}(?!\\d)")
    private val locationRegex = Regex(
        "(?i)\\b(?:lives in|based in|located in|from|location[:\\s]+)\\s+([A-Z][\\p{L}.'-]+(?:\\s+[A-Z][\\p{L}.'-]+){0,4})"
    )
    private val orgRegex = Regex(
        "(?i)\\b(?:works at|studied at|employed at|member of|developer at|engineer at|student at|designer at|lead at|intern at|manager at|company[:\\s]+)\\s+([A-Z][\\p{L}0-9&.'-]+(?:\\s+[A-Z][\\p{L}0-9&.'-]+){0,5})"
    )

    fun extract(text: String, sourceUrl: String, identity: IdentityInput? = null): List<Finding> {
        if (text.isBlank()) return emptyList()
        val findings = mutableListOf<Finding>()
        val attribution = attributionSignals(text, sourceUrl, identity)
        val identityEmails = identity?.emails.orEmpty().map(::normalizeEmail).filter { it.isNotBlank() }.toSet()
        val identityPhones = identity?.phones.orEmpty().map(::normalizePhone).filter { it.length >= 8 }.toSet()

        emailRegex.findAll(text).forEach { match ->
            val email = normalizeEmail(match.value)
            val exact = email in identityEmails
            val associated = exact || attribution.strong
            findings += Finding(
                type = FindingType.Email,
                value = match.value,
                sourceUrl = sourceUrl,
                evidenceSnippet = attributionSnippet(text, match.range, exact, associated),
                confidence = when {
                    exact -> 0.99f
                    associated -> 0.72f
                    else -> 0.42f
                },
                risk = when {
                    exact -> RiskLevel.High
                    associated -> RiskLevel.Medium
                    else -> RiskLevel.Low
                },
                remediation = if (exact) {
                    "This exact self-supplied email is publicly visible. Remove it or replace it with a masked alias."
                } else {
                    "Review the page before associating this email with the audited identity."
                }
            )
        }

        phoneRegex.findAll(text).forEach { match ->
            val normalized = normalizePhone(match.value)
            val exact = normalized in identityPhones
            if (normalized.length !in 8..15 || looksLikeDateOrCounter(normalized, match.value)) return@forEach
            if (!exact && !hasPhoneContext(text, match.range)) return@forEach
            val associated = exact || attribution.strong
            findings += Finding(
                type = FindingType.Phone,
                value = match.value.trim(),
                sourceUrl = sourceUrl,
                evidenceSnippet = attributionSnippet(text, match.range, exact, associated),
                confidence = when {
                    exact -> 0.99f
                    associated -> 0.68f
                    else -> 0.36f
                },
                risk = when {
                    exact -> RiskLevel.Critical
                    associated -> RiskLevel.High
                    else -> RiskLevel.Low
                },
                remediation = if (exact) {
                    "This exact self-supplied phone number is public. Remove it and review account recovery exposure."
                } else {
                    "Review the source context before treating this phone number as belonging to the subject."
                }
            )
        }

        locationRegex.findAll(text).forEach { match ->
            val value = match.groupValues[1].trim().trimEnd('.', ',', ';', ':')
            val type = classifyLocationOrOrganization(value, match.value, identity, preferLocation = true)
            val exact = when (type) {
                FindingType.Location -> identity?.locations.orEmpty().any { sameTerm(it, value) }
                FindingType.Organization -> identity?.organizations.orEmpty().any { sameTerm(it, value) }
                else -> false
            }
            findings += contextualFinding(type, value, sourceUrl, match.value, exact, attribution.strong)
        }

        orgRegex.findAll(text).forEach { match ->
            val value = match.groupValues[1].trim().trimEnd('.', ',', ';', ':')
            val type = classifyLocationOrOrganization(value, match.value, identity, preferLocation = false)
            val exact = when (type) {
                FindingType.Location -> identity?.locations.orEmpty().any { sameTerm(it, value) }
                FindingType.Organization -> identity?.organizations.orEmpty().any { sameTerm(it, value) }
                else -> false
            }
            findings += contextualFinding(type, value, sourceUrl, match.value, exact, attribution.strong)
        }

        identity?.let { supplied ->
            addExactTextExposure(
                findings = findings,
                text = text,
                sourceUrl = sourceUrl,
                label = supplied.fullName,
                valuePrefix = "Name Exposure",
                confidence = if (attribution.independentSignalCount >= 2) 0.95f else 0.65f,
                risk = if (attribution.independentSignalCount >= 2) RiskLevel.High else RiskLevel.Medium,
                remediation = "Reduce unnecessary real-name exposure on profiles that are not intended to be connected."
            )

            supplied.aliases.forEach { alias ->
                addExactTextExposure(
                    findings,
                    text,
                    sourceUrl,
                    alias,
                    "Alias Exposure",
                    if (attribution.urlHandleMatch) 0.90f else 0.62f,
                    if (attribution.urlHandleMatch) RiskLevel.Medium else RiskLevel.Low,
                    "Change or dissociate aliases only after confirming this page belongs to the audited identity."
                )
            }

            supplied.locations.forEach { location ->
                addDirectTypedExposure(findings, text, sourceUrl, location, FindingType.Location, attribution)
            }
            supplied.organizations.forEach { organization ->
                addDirectTypedExposure(findings, text, sourceUrl, organization, FindingType.Organization, attribution)
            }
        }

        return findings
            .filter { it.value.isNotBlank() }
            .distinctBy { "${it.type}|${canonicalValue(it.type, it.value)}|${it.sourceUrl}" }
    }

    private fun contextualFinding(
        type: FindingType,
        value: String,
        sourceUrl: String,
        evidence: String,
        exact: Boolean,
        associated: Boolean
    ): Finding {
        val isLocation = type == FindingType.Location
        return Finding(
            type = type,
            value = value,
            sourceUrl = sourceUrl,
            evidenceSnippet = buildString {
                append(evidence.take(220))
                append(
                    when {
                        exact -> " [exact self-supplied match]"
                        associated -> " [page has independent identity signals]"
                        else -> " [contextual extraction; attribution unconfirmed]"
                    }
                )
            },
            confidence = when {
                exact -> 0.90f
                associated -> 0.64f
                else -> 0.44f
            },
            risk = when {
                exact && isLocation -> RiskLevel.High
                exact -> RiskLevel.Medium
                associated -> RiskLevel.Medium
                else -> RiskLevel.Low
            },
            remediation = if (isLocation) {
                "Review whether this location is necessary on the public page and reduce its precision where possible."
            } else {
                "Review whether this organisation association should remain public."
            }
        )
    }

    private fun addExactTextExposure(
        findings: MutableList<Finding>,
        text: String,
        sourceUrl: String,
        label: String,
        valuePrefix: String,
        confidence: Float,
        risk: RiskLevel,
        remediation: String
    ) {
        val clean = label.trim()
        if (clean.length < 2) return
        val regex = Regex("(?i)(?<![\\p{L}0-9])${Regex.escape(clean)}(?![\\p{L}0-9])")
        val match = regex.find(text) ?: return
        findings += Finding(
            type = FindingType.SensitiveSnippet,
            value = "$valuePrefix: $clean",
            sourceUrl = sourceUrl,
            evidenceSnippet = getSnippet(text, match.range),
            confidence = confidence,
            risk = risk,
            remediation = remediation
        )
    }

    private fun addDirectTypedExposure(
        findings: MutableList<Finding>,
        text: String,
        sourceUrl: String,
        suppliedValue: String,
        type: FindingType,
        attribution: Attribution
    ) {
        val clean = suppliedValue.trim()
        if (clean.length < 2) return
        if (findings.any { it.type == type && sameTerm(it.value, clean) }) return
        val match = Regex("(?i)(?<![\\p{L}0-9])${Regex.escape(clean)}(?![\\p{L}0-9])").find(text) ?: return
        findings += Finding(
            type = type,
            value = clean,
            sourceUrl = sourceUrl,
            evidenceSnippet = "${getSnippet(text, match.range)} [exact self-supplied match]",
            confidence = if (attribution.strong) 0.92f else 0.80f,
            risk = if (type == FindingType.Location) RiskLevel.High else RiskLevel.Medium,
            remediation = if (type == FindingType.Location) {
                "Reduce precise location exposure on public pages."
            } else {
                "Review whether the organisation association should remain public."
            }
        )
    }

    private data class Attribution(
        val urlHandleMatch: Boolean,
        val independentSignalCount: Int
    ) {
        val strong: Boolean get() = urlHandleMatch || independentSignalCount >= 2
    }

    private fun attributionSignals(text: String, sourceUrl: String, identity: IdentityInput?): Attribution {
        if (identity == null) return Attribution(false, 0)
        val lowerText = text.lowercase()
        val handles = (listOfNotNull(identity.primaryUsername) + identity.usernames + identity.aliases)
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.length >= 2 }
            .distinct()
        val urlSegments = runCatching {
            URI(sourceUrl).path.orEmpty().split('/').map { it.removePrefix("@").lowercase() }
        }.getOrDefault(emptyList())
        val urlHandleMatch = handles.any { handle -> handle in urlSegments }

        var signals = 0
        if (identity.fullName.trim().length >= 3 && lowerText.contains(identity.fullName.trim().lowercase())) signals++
        if (identity.emails.any { it.isNotBlank() && lowerText.contains(it.trim().lowercase()) }) signals += 2
        if (identity.phones.map(::normalizePhone).filter { it.length >= 8 }
                .any { phone -> lowerText.filter(Char::isDigit).contains(phone) }) signals += 2
        if (handles.any(lowerText::contains)) signals++
        if (identity.organizations.any { it.length >= 3 && lowerText.contains(it.trim().lowercase()) }) signals++
        if (identity.locations.any { it.length >= 3 && lowerText.contains(it.trim().lowercase()) }) signals++
        return Attribution(urlHandleMatch, signals)
    }

    private fun classifyLocationOrOrganization(
        value: String,
        fullMatch: String,
        identity: IdentityInput?,
        preferLocation: Boolean
    ): FindingType {
        if (identity?.organizations.orEmpty().any { sameTerm(it, value) }) return FindingType.Organization
        if (identity?.locations.orEmpty().any { sameTerm(it, value) }) return FindingType.Location
        val normalized = value.lowercase()
        if (KNOWN_ORGANIZATIONS.any { sameTerm(it, value) } || ORG_SUFFIXES.any(normalized::endsWith)) {
            return FindingType.Organization
        }
        if (KNOWN_LOCATIONS.any { sameTerm(it, value) } || LOCATION_SUFFIXES.any(normalized::endsWith)) {
            return FindingType.Location
        }
        val context = fullMatch.lowercase()
        return when {
            listOf("works at", "studied at", "employed at", "member of", "engineer at", "developer at").any(context::contains) -> FindingType.Organization
            listOf("lives in", "based in", "located in", "location").any(context::contains) -> FindingType.Location
            preferLocation -> FindingType.Location
            else -> FindingType.Organization
        }
    }

    private fun hasPhoneContext(text: String, range: IntRange): Boolean {
        val snippet = getSnippet(text, range).lowercase()
        return PHONE_CONTEXT.any(snippet::contains) || text.substring(range).startsWith("+")
    }

    private fun looksLikeDateOrCounter(digits: String, raw: String): Boolean {
        if (digits.length == 8 && digits.take(4).toIntOrNull() in 1900..2100) return true
        if (raw.count { it == '-' || it == '/' } >= 2 && !raw.trim().startsWith('+')) return true
        return digits.toSet().size == 1
    }

    private fun attributionSnippet(text: String, range: IntRange, exact: Boolean, associated: Boolean): String =
        buildString {
            append(getSnippet(text, range))
            append(
                when {
                    exact -> " [exact self-supplied identifier]"
                    associated -> " [page has independent identity signals]"
                    else -> " [identifier detected; attribution unconfirmed]"
                }
            )
        }

    private fun getSnippet(text: String, range: IntRange): String {
        val start = (range.first - 60).coerceAtLeast(0)
        val end = (range.last + 61).coerceAtMost(text.length)
        return text.substring(start, end).replace(Regex("\\s+"), " ").trim().take(260)
    }

    private fun normalizeEmail(value: String): String = value.trim().lowercase()
    private fun normalizePhone(value: String): String = value.filter(Char::isDigit)
    private fun sameTerm(first: String, second: String): Boolean =
        first.trim().equals(second.trim(), ignoreCase = true)

    private fun canonicalValue(type: FindingType, value: String): String = when (type) {
        FindingType.Email -> normalizeEmail(value)
        FindingType.Phone -> normalizePhone(value)
        else -> value.trim().lowercase()
    }

    private companion object {
        val PHONE_CONTEXT = listOf("phone", "mobile", "telephone", "tel:", "call", "contact", "whatsapp", "signal")
        val ORG_SUFFIXES = listOf(" inc", " corp", " ltd", " llc", " university", " college", " systems", " technologies", " labs")
        val LOCATION_SUFFIXES = listOf(" city", " state", " province", " county", " district")
        val KNOWN_ORGANIZATIONS = setOf(
            "Replit", "Google", "Microsoft", "Meta", "Amazon", "Apple", "GitHub", "GitLab",
            "Azul", "Azul Systems", "OpenAI", "Anthropic", "IIT Delhi", "MIT", "Stanford"
        )
        val KNOWN_LOCATIONS = setOf(
            "India", "Delhi", "New Delhi", "Gurgaon", "Gurugram", "Noida", "Bangalore", "Bengaluru",
            "Mumbai", "Pune", "Hyderabad", "Chennai", "Kolkata", "New York", "London", "Berlin", "Paris"
        )
    }
}
