package io.dossier.app.data.web

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Bounded, local-only interoperability parser for public OSINT reports created by
 * external tools. It deliberately does not execute those tools.
 *
 * The parser has three safety invariants:
 *  1. a record must tie back to an explicit audit seed (handle, email, phone,
 *     profile URL, in-scope domain or organization according to source policy);
 *  2. passwords, hashes, cookies, tokens, session material and other credential
 *     fields are never imported; breach-oriented records containing those fields
 *     are rejected entirely;
 *  3. imported evidence is Candidate/ThirdPartyAggregation, never Verified.
 *
 * A direct public URL can later be re-fetched by Dossier's verifier. Importing a
 * scanner hit is therefore discovery, not an identity conclusion.
 */
object ExternalOsintReportParser {
    enum class ScopeSignal { Handle, Email, Phone, ProfileUrl, Domain, Organization }

    enum class Source(
        val providerId: String,
        val displayName: String,
        val allowedSignals: Set<ScopeSignal>,
        val allowSummaryWithoutUrl: Boolean = false,
        val rejectCredentialFields: Boolean = false
    ) {
        GenericPublicReport(
            "external-osint-import", "External OSINT report",
            setOf(ScopeSignal.Handle, ScopeSignal.Email, ScopeSignal.Phone, ScopeSignal.ProfileUrl, ScopeSignal.Domain)
        ),
        SpiderFoot(
            "spiderfoot-import", "SpiderFoot",
            setOf(ScopeSignal.Handle, ScopeSignal.Email, ScopeSignal.Phone, ScopeSignal.ProfileUrl, ScopeSignal.Domain, ScopeSignal.Organization)
        ),
        ReconNg(
            "recon-ng-import", "Recon-ng",
            setOf(ScopeSignal.Handle, ScopeSignal.Email, ScopeSignal.ProfileUrl, ScopeSignal.Domain, ScopeSignal.Organization)
        ),
        TheHarvester(
            "theharvester-import", "theHarvester",
            setOf(ScopeSignal.Email, ScopeSignal.Domain, ScopeSignal.Organization)
        ),
        Maigret(
            "maigret-import", "Maigret",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        Sherlock(
            "sherlock-import", "Sherlock",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        Holehe(
            "holehe-import", "Holehe",
            setOf(ScopeSignal.Email),
            allowSummaryWithoutUrl = true,
            rejectCredentialFields = true
        ),
        Pushshift(
            "pushshift-import", "Pushshift",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        Osintgram(
            "osintgram-import", "OSINTgram",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        Instaloader(
            "instaloader-import", "Instaloader",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        FacebookScraper(
            "facebook-scraper-import", "facebook-scraper",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        SocialAnalyzer(
            "social-analyzer-import", "Social Analyzer",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        LinkedInScraper(
            "linkedin-scraper-import", "LinkedIn public export",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl, ScopeSignal.Organization)
        ),
        GeoSocial(
            "geosocial-import", "GeoSocial footprint",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl),
            allowSummaryWithoutUrl = true
        ),
        LeakCheckSummary(
            "leakcheck-summary-import", "LeakCheck redacted summary",
            setOf(ScopeSignal.Email),
            allowSummaryWithoutUrl = true,
            rejectCredentialFields = true
        ),
        DehashedSummary(
            "dehashed-summary-import", "DeHashed redacted summary",
            setOf(ScopeSignal.Email),
            allowSummaryWithoutUrl = true,
            rejectCredentialFields = true
        ),
        BreachParseSummary(
            "breach-parse-summary-import", "Breach-Parse redacted summary",
            setOf(ScopeSignal.Email),
            allowSummaryWithoutUrl = true,
            rejectCredentialFields = true
        ),
        BusterBreachFinderSummary(
            "breachfinder-summary-import", "Buster / BreachFinder redacted summary",
            setOf(ScopeSignal.Email),
            allowSummaryWithoutUrl = true,
            rejectCredentialFields = true
        ),
        OpenCorporates(
            "opencorporates-import", "OpenCorporates",
            setOf(ScopeSignal.Organization, ScopeSignal.Domain, ScopeSignal.ProfileUrl),
            allowSummaryWithoutUrl = true
        ),
        GitHubOsint(
            "github-osint-import", "GitHub OSINT",
            setOf(ScopeSignal.Handle, ScopeSignal.Email, ScopeSignal.ProfileUrl, ScopeSignal.Organization)
        ),
        GhArchive(
            "gh-archive-import", "GH Archive",
            setOf(ScopeSignal.Handle, ScopeSignal.ProfileUrl)
        ),
        ImageAnalyzer(
            "image-analyzer-import", "Image Analyzer",
            setOf(ScopeSignal.ProfileUrl, ScopeSignal.Handle),
            allowSummaryWithoutUrl = true
        ),
        PhoneInfoga(
            "phoneinfoga-import", "PhoneInfoga",
            setOf(ScopeSignal.Phone),
            allowSummaryWithoutUrl = true
        ),
        Foca(
            "foca-import", "FOCA metadata report",
            setOf(ScopeSignal.Domain, ScopeSignal.Email, ScopeSignal.Organization),
            allowSummaryWithoutUrl = true,
            rejectCredentialFields = true
        ),
        Censys(
            "censys-import", "Censys",
            setOf(ScopeSignal.Domain, ScopeSignal.ProfileUrl)
        ),
        Shodan(
            "shodan-import", "Shodan",
            setOf(ScopeSignal.Domain, ScopeSignal.ProfileUrl)
        ),
        Amass(
            "amass-import", "OWASP Amass",
            setOf(ScopeSignal.Domain)
        )
    }

    data class ParseResult(
        val source: Source,
        val collection: EvidenceCollection,
        val acceptedRecords: Int,
        val rejectedRecords: Int,
        val warnings: List<String>
    )

    internal data class Scope(
        val handles: Set<String>,
        val emails: Set<String>,
        val phones: Set<String>,
        val profileUrls: Set<String>,
        val domains: Set<String>,
        val organizations: Set<String>
    )

    private data class Record(
        val fields: Map<String, String>,
        val text: String,
        val hasSensitiveField: Boolean
    )

    fun detectSource(displayName: String, raw: String): Source {
        val haystack = (displayName + "\n" + raw.take(4_000)).lowercase(Locale.ROOT)
        return when {
            "spiderfoot" in haystack || "sfp_" in haystack -> Source.SpiderFoot
            "recon-ng" in haystack || "reconng" in haystack -> Source.ReconNg
            "theharvester" in haystack || "the_harvester" in haystack -> Source.TheHarvester
            "maigret" in haystack -> Source.Maigret
            "sherlock" in haystack -> Source.Sherlock
            "holehe" in haystack -> Source.Holehe
            "pushshift" in haystack -> Source.Pushshift
            "osintgram" in haystack -> Source.Osintgram
            "instaloader" in haystack -> Source.Instaloader
            "facebook-scraper" in haystack || "facebook_scraper" in haystack -> Source.FacebookScraper
            "social analyzer" in haystack || "social-analyzer" in haystack -> Source.SocialAnalyzer
            "linkedin-scraper" in haystack || "linkedin_scraper" in haystack -> Source.LinkedInScraper
            "geosocial" in haystack || "geotag" in haystack -> Source.GeoSocial
            "leakcheck" in haystack -> Source.LeakCheckSummary
            "dehashed" in haystack -> Source.DehashedSummary
            "breach-parse" in haystack || "breach_parse" in haystack -> Source.BreachParseSummary
            "breachfinder" in haystack || "buster" in haystack -> Source.BusterBreachFinderSummary
            "opencorporates" in haystack -> Source.OpenCorporates
            "gh archive" in haystack || "gharchive" in haystack -> Source.GhArchive
            "github-osint" in haystack || "github osint" in haystack -> Source.GitHubOsint
            "phoneinfoga" in haystack -> Source.PhoneInfoga
            "foca" in haystack -> Source.Foca
            "censys" in haystack -> Source.Censys
            "shodan" in haystack -> Source.Shodan
            "amass" in haystack -> Source.Amass
            else -> Source.GenericPublicReport
        }
    }

    fun parse(
        source: Source,
        raw: String,
        input: IdentityInput
    ): ParseResult {
        if (raw.isBlank()) {
            return ParseResult(source, EvidenceCollection(), 0, 0, listOf("The selected report is empty"))
        }
        val scope = buildScope(input)
        if (!scope.hasAny(source.allowedSignals)) {
            return ParseResult(
                source,
                EvidenceCollection(),
                0,
                0,
                listOf("The active audit has no explicit seed compatible with ${source.displayName}")
            )
        }

        val records = parseRecords(raw).take(MAX_RECORDS)
        val evidence = mutableListOf<Evidence>()
        val relationships = mutableListOf<EvidenceRelationship>()
        var rejected = 0
        var sensitiveRejected = 0

        records.forEach { record ->
            if (source.rejectCredentialFields && record.hasSensitiveField) {
                rejected++
                sensitiveRejected++
                return@forEach
            }
            val sanitized = sanitize(record.text)
            val matches = matchingSignals(scope, source.allowedSignals, sanitized)
            if (matches.isEmpty()) {
                rejected++
                return@forEach
            }

            val urls = URL.findAll(sanitized)
                .map { normalizeUrl(it.value) }
                .filterNotNull()
                .distinct()
                .take(MAX_URLS_PER_RECORD)
                .toList()

            var emitted = false
            urls.forEach { url ->
                if (!urlAllowedByScope(url, scope, matches, source.allowedSignals)) return@forEach
                val id = "external-osint:${source.providerId}:${sha256("$url|${matches.joinToString()}").take(32)}"
                evidence += Evidence(
                    id = id,
                    kind = if (looksLikeProfileUrl(url, scope.handles)) EvidenceKind.Profile else EvidenceKind.PublicSearchEvidence,
                    value = url,
                    sourceUrl = url,
                    snippet = sanitized.take(MAX_SNIPPET_CHARS),
                    confidence = IMPORT_CONFIDENCE,
                    risk = RiskLevel.Low,
                    signals = listOf(
                        "Imported from a user-selected ${source.displayName} report",
                        "Record matched explicit audit seed(s): ${matches.joinToString()}",
                        "External report evidence is not verification; direct public re-fetch/corroboration is required"
                    ),
                    providerId = source.providerId,
                    retrievedAtEpochMillis = System.currentTimeMillis(),
                    state = EvidenceState.Candidate,
                    reliability = EvidenceReliability.ThirdPartyAggregation,
                    contentHashSha256 = sha256(sanitized),
                    parserVersion = PARSER_VERSION,
                    historical = source in HISTORICAL_SOURCES
                )
                relationships += EvidenceRelationship(
                    fromValue = matches.first(),
                    toValue = url,
                    relation = "IMPORTED_PUBLIC_EVIDENCE",
                    evidence = "User-supplied ${source.displayName} report; independent verification required"
                )
                emitted = true
            }

            if (!emitted && source.allowSummaryWithoutUrl) {
                val summary = sanitized.take(MAX_SUMMARY_CHARS).trim()
                if (summary.isNotBlank() && !containsCredentialMaterial(summary)) {
                    val id = "external-osint:${source.providerId}:summary:${sha256("$summary|${matches.joinToString()}").take(32)}"
                    evidence += Evidence(
                        id = id,
                        kind = summaryKind(source),
                        value = summary,
                        snippet = summary,
                        confidence = SUMMARY_CONFIDENCE,
                        risk = RiskLevel.Low,
                        signals = listOf(
                            "Imported from a user-selected ${source.displayName} report",
                            "Summary matched explicit audit seed(s): ${matches.joinToString()}",
                            "No directly verifiable public URL was present; treat this as a low-confidence third-party lead"
                        ),
                        providerId = source.providerId,
                        retrievedAtEpochMillis = System.currentTimeMillis(),
                        state = EvidenceState.Candidate,
                        reliability = EvidenceReliability.ThirdPartyAggregation,
                        contentHashSha256 = sha256(summary),
                        parserVersion = PARSER_VERSION,
                        historical = source in HISTORICAL_SOURCES
                    )
                    emitted = true
                }
            }

            if (!emitted) rejected++
        }

        val dedupedEvidence = evidence.distinctBy(Evidence::id)
        val warnings = buildList {
            if (records.size >= MAX_RECORDS) add("Report processing is bounded to $MAX_RECORDS records")
            if (sensitiveRejected > 0) add("$sensitiveRejected record(s) were rejected because they contained credential/secret fields")
            if (rejected > 0) add("$rejected record(s) did not satisfy the explicit-seed/public-evidence import contract")
        }
        return ParseResult(
            source = source,
            collection = EvidenceCollection(
                evidence = dedupedEvidence,
                relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
            ),
            acceptedRecords = dedupedEvidence.size,
            rejectedRecords = rejected,
            warnings = warnings
        )
    }

    internal fun buildScope(input: IdentityInput): Scope {
        val handles = (listOfNotNull(input.primaryUsername) + input.usernames)
            .map(::normalizeHandle)
            .filter { it.length >= 2 }
            .toSet()
        val emails = input.emails.map { it.trim().lowercase(Locale.ROOT) }
            .filter { '@' in it }
            .toSet()
        val phones = input.phones.map { it.filter(Char::isDigit) }
            .filter { it.length >= 8 }
            .toSet()
        val profileUrls = input.profileUrls.mapNotNull(::normalizeUrl).map(::canonicalUrl).toSet()
        val domains = buildSet {
            emails.mapNotNullTo(this) { it.substringAfter('@', "").takeIf(String::isNotBlank) }
            input.profileUrls.mapNotNullTo(this) { raw -> runCatching { URI(ensureScheme(raw)).host }.getOrNull() }
        }.map { it.removePrefix("www.").lowercase(Locale.ROOT) }.filter(String::isNotBlank).toSet()
        val organizations = input.organizations.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 3 }
            .toSet()
        return Scope(handles, emails, phones, profileUrls, domains, organizations)
    }

    private fun Scope.hasAny(signals: Set<ScopeSignal>): Boolean = signals.any { signal ->
        when (signal) {
            ScopeSignal.Handle -> handles.isNotEmpty()
            ScopeSignal.Email -> emails.isNotEmpty()
            ScopeSignal.Phone -> phones.isNotEmpty()
            ScopeSignal.ProfileUrl -> profileUrls.isNotEmpty()
            ScopeSignal.Domain -> domains.isNotEmpty()
            ScopeSignal.Organization -> organizations.isNotEmpty()
        }
    }

    private fun matchingSignals(scope: Scope, allowed: Set<ScopeSignal>, text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        val matches = mutableListOf<String>()
        if (ScopeSignal.Handle in allowed) {
            scope.handles.firstOrNull { containsHandle(lower, it) }?.let { matches += "@$it" }
        }
        if (ScopeSignal.Email in allowed) {
            scope.emails.firstOrNull(lower::contains)?.let(matches::add)
        }
        if (ScopeSignal.Phone in allowed) {
            val digits = lower.filter(Char::isDigit)
            scope.phones.firstOrNull(digits::contains)?.let { matches += "phone:${it.takeLast(4)}" }
        }
        if (ScopeSignal.ProfileUrl in allowed) {
            val urls = URL.findAll(text).mapNotNull { normalizeUrl(it.value) }.map(::canonicalUrl).toSet()
            scope.profileUrls.firstOrNull(urls::contains)?.let { matches += "profile-url" }
        }
        if (ScopeSignal.Domain in allowed) {
            scope.domains.firstOrNull { domain -> lower.contains(domain) }?.let { matches += "domain:$it" }
        }
        if (ScopeSignal.Organization in allowed) {
            scope.organizations.firstOrNull(lower::contains)?.let { matches += "org:$it" }
        }
        return matches.distinct()
    }

    private fun urlAllowedByScope(
        url: String,
        scope: Scope,
        matches: List<String>,
        allowed: Set<ScopeSignal>
    ): Boolean {
        val canonical = canonicalUrl(url)
        if (ScopeSignal.ProfileUrl in allowed && canonical in scope.profileUrls) return true
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.removePrefix("www.")?.lowercase(Locale.ROOT).orEmpty()
        if (ScopeSignal.Domain in allowed && scope.domains.any { host == it || host.endsWith(".$it") }) return true
        if (ScopeSignal.Handle in allowed && scope.handles.any { handleAppearsInUrl(url, it) }) return true
        // Exact email/phone/org match in the same sanitized report record is enough
        // to retain the public URL as a Candidate. It is still not verified.
        return matches.any {
            it.contains('@') || it.startsWith("phone:") || it.startsWith("org:")
        }
    }

    private fun parseRecords(raw: String): List<Record> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()

        runCatching { JSON.parseToJsonElement(trimmed) }.getOrNull()?.let { root ->
            val objects = mutableListOf<JsonObject>()
            collectObjects(root, objects, MAX_RECORDS)
            if (objects.isNotEmpty()) return objects.map(::recordFromJson)
        }

        val lines = trimmed.lineSequence().map(String::trim).filter(String::isNotBlank).take(MAX_RECORDS).toList()
        if (lines.isEmpty()) return emptyList()

        // JSONL / NDJSON.
        val jsonl = lines.mapNotNull { line -> runCatching { JSON.parseToJsonElement(line) as? JsonObject }.getOrNull() }
        if (jsonl.size >= lines.size.coerceAtMost(3)) return jsonl.map(::recordFromJson)

        // CSV/TSV with a header.
        val delimiter = when {
            lines.first().count { it == '\t' } >= 1 -> '\t'
            lines.first().count { it == ',' } >= 1 -> ','
            else -> null
        }
        if (delimiter != null && lines.size >= 2) {
            val header = parseDelimitedLine(lines.first(), delimiter).map { it.trim().lowercase(Locale.ROOT) }
            if (header.size >= 2) {
                return lines.drop(1).map { line ->
                    val values = parseDelimitedLine(line, delimiter)
                    val fields = header.mapIndexedNotNull { index, key ->
                        values.getOrNull(index)?.takeIf(String::isNotBlank)?.let { key to it }
                    }.toMap()
                    recordFromFields(fields, line)
                }
            }
        }

        return lines.map { line -> recordFromFields(mapOf("text" to line), line) }
    }

    private fun collectObjects(element: JsonElement, out: MutableList<JsonObject>, limit: Int) {
        if (out.size >= limit) return
        when (element) {
            is JsonObject -> {
                out += element
                element.values.forEach { collectObjects(it, out, limit) }
            }
            is JsonArray -> element.forEach { collectObjects(it, out, limit) }
            else -> Unit
        }
    }

    private fun recordFromJson(obj: JsonObject): Record {
        val fields = linkedMapOf<String, String>()
        flattenJson("", obj, fields, 0)
        return recordFromFields(fields, fields.values.joinToString(" | "))
    }

    private fun flattenJson(prefix: String, element: JsonElement, out: MutableMap<String, String>, depth: Int) {
        if (depth > MAX_JSON_DEPTH || out.size >= MAX_FIELDS_PER_RECORD) return
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                val next = if (prefix.isBlank()) key else "$prefix.$key"
                flattenJson(next, value, out, depth + 1)
            }
            is JsonArray -> element.take(MAX_ARRAY_ITEMS).forEachIndexed { index, value ->
                flattenJson("$prefix[$index]", value, out, depth + 1)
            }
            is JsonPrimitive -> element.contentOrNull?.take(MAX_FIELD_CHARS)?.let { out[prefix] = it }
        }
    }

    private fun recordFromFields(fields: Map<String, String>, fallbackText: String): Record {
        val sensitive = fields.keys.any(::sensitiveKey)
        val safeFields = fields.filterKeys { !sensitiveKey(it) }
            .mapValues { (_, value) -> sanitize(value).take(MAX_FIELD_CHARS) }
        val text = safeFields.entries.joinToString(" | ") { "${it.key}=${it.value}" }
            .ifBlank { sanitize(fallbackText) }
            .take(MAX_RECORD_CHARS)
        return Record(safeFields, text, sensitive)
    }

    private fun parseDelimitedLine(line: String, delimiter: Char): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                c == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            index++
        }
        values += current.toString()
        return values
    }

    private fun summaryKind(source: Source): EvidenceKind = when (source) {
        Source.PhoneInfoga -> EvidenceKind.Phone
        Source.OpenCorporates -> EvidenceKind.Organization
        Source.GeoSocial -> EvidenceKind.Location
        else -> EvidenceKind.PublicSearchEvidence
    }

    private fun sanitize(raw: String): String = raw
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun sensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace("[^a-z0-9]".toRegex(), "")
        return SENSITIVE_KEYS.any(normalized::contains)
    }

    private fun containsCredentialMaterial(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return CREDENTIAL_MARKERS.any(lower::contains)
    }

    private fun containsHandle(text: String, handle: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("@$handle") || lower.contains("u/$handle")) return true
        return Regex("(?<![a-z0-9_])${Regex.escape(handle)}(?![a-z0-9_])", RegexOption.IGNORE_CASE)
            .containsMatchIn(lower)
    }

    private fun handleAppearsInUrl(url: String, handle: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val pathParts = uri.path.orEmpty().split('/').map { it.removePrefix("@").lowercase(Locale.ROOT) }
        if (handle in pathParts) return true
        return uri.rawQuery.orEmpty().split('&').any {
            it.substringAfter('=', "").removePrefix("@").equals(handle, ignoreCase = true)
        }
    }

    private fun looksLikeProfileUrl(url: String, handles: Set<String>): Boolean =
        handles.any { handleAppearsInUrl(url, it) }

    private fun normalizeHandle(raw: String): String = raw.trim()
        .removePrefix("@").removePrefix("u/").lowercase(Locale.ROOT)

    private fun ensureScheme(raw: String): String = when {
        raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
        else -> "https://$raw"
    }

    private fun normalizeUrl(raw: String): String? {
        val cleaned = raw.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return cleaned.substringBefore('#')
    }

    private fun canonicalUrl(raw: String): String = runCatching {
        val uri = URI(raw)
        val host = uri.host?.removePrefix("www.")?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "$host$path$query"
    }.getOrDefault(raw.lowercase(Locale.ROOT).trimEnd('/'))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val MAX_RECORDS = 3_000
    private const val MAX_URLS_PER_RECORD = 6
    private const val MAX_FIELDS_PER_RECORD = 80
    private const val MAX_ARRAY_ITEMS = 40
    private const val MAX_JSON_DEPTH = 5
    private const val MAX_FIELD_CHARS = 4_000
    private const val MAX_RECORD_CHARS = 12_000
    private const val MAX_SNIPPET_CHARS = 900
    private const val MAX_SUMMARY_CHARS = 700
    private const val IMPORT_CONFIDENCE = 0.50f
    private const val SUMMARY_CONFIDENCE = 0.34f
    private const val PARSER_VERSION = "external-osint-report-v1"

    private val HISTORICAL_SOURCES = setOf(Source.Pushshift, Source.GhArchive)
    private val SENSITIVE_KEYS = setOf(
        "password", "passwd", "pwd", "hash", "cookie", "session", "token",
        "secret", "credential", "privatekey", "apikey", "authorization", "stealer"
    )
    private val CREDENTIAL_MARKERS = listOf(
        "password=", "passwd=", "pwd=", "cookie=", "session=", "token=",
        "private key", "api key=", "authorization: bearer", "stealer log"
    )
    private val URL = Regex("(?i)https?://[^\\s<>\\[\\]{}\\\"']+")
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
}
