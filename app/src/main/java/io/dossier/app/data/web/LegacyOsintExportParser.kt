package io.dossier.app.data.web

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ImportEvidenceIdPolicy
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Local-only compatibility parser for historical Twint and snscrape exports.
 *
 * Dossier never launches either scraper. Every accepted record must belong to an
 * explicitly authorized handle and remains Candidate/ThirdPartyAggregation. Public
 * mention/reply metadata is converted into bounded interaction edges for local graph
 * analysis; those edges describe observed interactions and never establish identity.
 */
object LegacyOsintExportParser {
    enum class Source { TwintJson, SnscrapeJsonl }

    data class ParseResult(
        val collection: EvidenceCollection,
        val acceptedRecords: Int,
        val rejectedRecords: Int,
        val warnings: List<String>
    )

    fun parse(
        source: Source,
        raw: String,
        authorizedHandles: Collection<String>,
        importDigest: String? = null
    ): ParseResult {
        val handles = authorizedHandles
            .map(::normalizeHandle)
            .filter { it.length >= 2 }
            .toSet()
        if (handles.isEmpty() || raw.isBlank()) {
            return ParseResult(EvidenceCollection(), 0, 0, listOf("No authorized handle or export content supplied"))
        }

        val records = parseRecords(raw)
        val evidence = mutableListOf<Evidence>()
        val relationships = mutableListOf<EvidenceRelationship>()
        var rejected = 0

        records.take(MAX_RECORDS).forEach { record ->
            if (containsCredentialFields(record)) {
                rejected++
                return@forEach
            }
            val parsed = when (source) {
                Source.TwintJson -> parseTwint(record)
                Source.SnscrapeJsonl -> parseSnscrape(record)
            }
            if (
                parsed == null ||
                normalizeHandle(parsed.username) !in handles ||
                parsed.url.isBlank() ||
                containsCredentialMaterial(parsed)
            ) {
                rejected++
                return@forEach
            }

            val handle = normalizeHandle(parsed.username)
            val id = ImportEvidenceIdPolicy.stableId(
                prefix = "legacy-osint:${source.name.lowercase()}",
                providerId = providerId(source),
                importDigest = importDigest,
                rowMaterial = listOf(
                    handle,
                    parsed.url,
                    parsed.text,
                    parsed.observedAtEpochMillis?.toString().orEmpty(),
                    parsed.replyTo.orEmpty(),
                    parsed.mentions.joinToString(",")
                ).joinToString("\u001f"),
                discriminator = source.name
            )
            evidence += Evidence(
                id = id,
                kind = EvidenceKind.PublicSearchEvidence,
                value = parsed.url,
                sourceUrl = parsed.url,
                snippet = parsed.text.take(MAX_SNIPPET_CHARS),
                confidence = IMPORT_CONFIDENCE,
                risk = RiskLevel.Low,
                signals = listOf(
                    "Imported from a user-supplied ${sourceLabel(source)} export",
                    "Record handle exactly matched an explicitly authorized audit handle",
                    "Import is not live verification; re-fetch the public URL before relying on current ownership"
                ),
                providerId = providerId(source),
                retrievedAtEpochMillis = System.currentTimeMillis(),
                observedAtEpochMillis = parsed.observedAtEpochMillis,
                state = EvidenceState.Candidate,
                reliability = EvidenceReliability.ThirdPartyAggregation,
                contentHashSha256 = sha256(parsed.text),
                parserVersion = PARSER_VERSION,
                historical = true
            )
            relationships += EvidenceRelationship(
                fromValue = handle,
                toValue = parsed.url,
                relation = "IMPORTED_PUBLIC_ACTIVITY",
                evidence = "User-supplied ${sourceLabel(source)} export; independent verification required",
                evidenceIds = listOf(id)
            )

            parsed.replyTo?.let { target ->
                if (target != handle) {
                    relationships += EvidenceRelationship(
                        fromValue = handle,
                        toValue = target,
                        relation = "REPLIES_TO",
                        evidence = parsed.url,
                        evidenceIds = listOf(id)
                    )
                }
            }
            parsed.mentions
                .asSequence()
                .filter { it != handle && it != parsed.replyTo }
                .take(MAX_INTERACTIONS_PER_RECORD)
                .forEach { target ->
                    relationships += EvidenceRelationship(
                        fromValue = handle,
                        toValue = target,
                        relation = "MENTIONS",
                        evidence = parsed.url,
                        evidenceIds = listOf(id)
                    )
                }
        }

        val dedupedEvidence = evidence.distinctBy(Evidence::id)
        val dedupedRelationships = EvidenceRelationshipPolicy.normalize(relationships)
        val warnings = buildList {
            if (records.size > MAX_RECORDS) add("Export truncated to $MAX_RECORDS records")
            if (rejected > 0) add("$rejected record(s) rejected because the handle/URL did not match the authorized import contract")
        }
        return ParseResult(
            collection = EvidenceCollection(
                evidence = dedupedEvidence,
                relationships = dedupedRelationships
            ),
            acceptedRecords = dedupedEvidence.size,
            rejectedRecords = rejected,
            warnings = warnings
        )
    }

    private data class ImportedRecord(
        val username: String,
        val url: String,
        val text: String,
        val observedAtEpochMillis: Long?,
        val mentions: List<String> = emptyList(),
        val replyTo: String? = null
    )

    private fun parseTwint(obj: JsonObject): ImportedRecord? {
        val username = obj.string("username") ?: obj.obj("user")?.string("username") ?: return null
        val url = obj.string("link") ?: obj.string("url") ?: return null
        val text = obj.string("tweet") ?: obj.string("content") ?: obj.string("text").orEmpty()
        val timestamp = firstTimestamp(
            obj.string("created_at"),
            obj.string("date"),
            listOfNotNull(obj.string("date"), obj.string("time")).joinToString(" ").ifBlank { null }
        )
        val replyTo = firstHandle(
            obj.string("reply_to_username"),
            obj.string("in_reply_to_screen_name"),
            obj.obj("reply_to")?.string("username"),
            obj.obj("reply_to")?.string("screen_name")
        ) ?: handlesFromElement(obj["reply_to"]).firstOrNull()
        val mentions = (
            handlesFromElement(obj["mentions"]) +
                handlesFromElement(obj["mentioned_users"]) +
                handlesFromText(text)
            ).distinct().take(MAX_INTERACTIONS_PER_RECORD)
        return ImportedRecord(username, url, text, timestamp, mentions, replyTo)
    }

    private fun parseSnscrape(obj: JsonObject): ImportedRecord? {
        val user = obj.obj("user")
        val username = user?.string("username")
            ?: obj.string("username")
            ?: obj.string("user")
            ?: return null
        val url = obj.string("url") ?: obj.string("link") ?: return null
        val text = obj.string("rawContent")
            ?: obj.string("content")
            ?: obj.string("text")
            ?: obj.string("renderedContent")
            ?: ""
        val timestamp = firstTimestamp(obj.string("date"), obj.string("createdAt"), obj.string("created_at"))
        val replyObject = obj.obj("inReplyToUser") ?: obj.obj("in_reply_to_user")
        val replyTo = firstHandle(
            replyObject?.string("username"),
            replyObject?.string("displayname"),
            obj.string("inReplyToUserUsername"),
            obj.string("in_reply_to_screen_name")
        )
        val mentions = (
            handlesFromElement(obj["mentionedUsers"]) +
                handlesFromElement(obj["mentioned_users"]) +
                handlesFromText(text)
            ).distinct().take(MAX_INTERACTIONS_PER_RECORD)
        return ImportedRecord(username, url, text, timestamp, mentions, replyTo)
    }

    private fun handlesFromElement(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.flatMap(::handlesFromElement)
        is JsonObject -> listOfNotNull(
            element.string("username"),
            element.string("screen_name"),
            element.string("user")
        ).map(::normalizeHandle).filter(String::isNotBlank)
        is JsonPrimitive -> element.contentOrNull
            ?.let(::normalizeHandle)
            ?.takeIf(String::isNotBlank)
            ?.let(::listOf)
            ?: emptyList()
        else -> emptyList()
    }

    private fun handlesFromText(text: String): List<String> = HANDLE_MENTION.findAll(text)
        .map { normalizeHandle(it.groupValues[1]) }
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_INTERACTIONS_PER_RECORD)
        .toList()

    private fun firstHandle(vararg values: String?): String? = values
        .asSequence()
        .filterNotNull()
        .map(::normalizeHandle)
        .firstOrNull(String::isNotBlank)

    private fun parseRecords(raw: String): List<JsonObject> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()

        runCatching { JSON.parseToJsonElement(trimmed) }.getOrNull()?.let { root ->
            when (root) {
                is JsonObject -> return listOf(root)
                is JsonArray -> return root.mapNotNull { it as? JsonObject }
                else -> Unit
            }
        }

        return trimmed.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line -> runCatching { JSON.parseToJsonElement(line) as? JsonObject }.getOrNull() }
            .toList()
    }

    private fun firstTimestamp(vararg candidates: String?): Long? = candidates
        .asSequence()
        .filterNotNull()
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull(::parseTimestamp)
        .firstOrNull()

    private fun parseTimestamp(raw: String): Long? {
        raw.toLongOrNull()?.let { numeric ->
            return if (numeric > 10_000_000_000L) numeric else numeric * 1000L
        }
        runCatching { return Instant.parse(raw).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        val localFormats = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
        localFormats.forEach { formatter ->
            runCatching {
                return java.time.LocalDateTime.parse(raw, formatter)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }
        }
        return null
    }

    private fun normalizeHandle(raw: String): String = raw.trim()
        .removePrefix("@")
        .removePrefix("u/")
        .trimEnd(',', '.', ':', ';', ')', ']', '}')
        .lowercase()
        .takeIf { it.matches(HANDLE_VALUE) }
        .orEmpty()

    private fun containsCredentialMaterial(record: ImportedRecord): Boolean {
        val fields = listOf(record.username, record.url, record.text)
        return fields.any { value ->
            val lower = value.lowercase(Locale.ROOT)
            CREDENTIAL_MARKERS.any(lower::contains)
        }
    }

    private fun containsCredentialFields(record: JsonObject): Boolean {
        fun visit(element: JsonElement, depth: Int): Boolean {
            if (depth > MAX_JSON_DEPTH) return false
            return when (element) {
                is JsonObject -> element.entries.any { (key, value) ->
                    sensitiveKey(key) || visit(value, depth + 1)
                }
                is JsonArray -> element.any { visit(it, depth + 1) }
                is JsonPrimitive -> element.contentOrNull?.lowercase(Locale.ROOT)?.let { value ->
                    CREDENTIAL_MARKERS.any { marker -> value.contains(marker) }
                } == true
            }
        }
        return visit(record, 0)
    }

    private fun sensitiveKey(raw: String): Boolean {
        val normalized = raw.lowercase(Locale.ROOT).replace("[^a-z0-9]".toRegex(), "")
        return SENSITIVE_KEYS.any(normalized::contains)
    }

    private fun sourceLabel(source: Source): String = when (source) {
        Source.TwintJson -> "Twint JSON"
        Source.SnscrapeJsonl -> "snscrape JSONL"
    }

    private fun providerId(source: Source): String = when (source) {
        Source.TwintJson -> "twint-import"
        Source.SnscrapeJsonl -> "snscrape-import"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private const val MAX_RECORDS = 2_000
    private const val MAX_JSON_DEPTH = 4
    private const val MAX_SNIPPET_CHARS = 900
    private const val MAX_INTERACTIONS_PER_RECORD = 24
    private const val IMPORT_CONFIDENCE = 0.52f
    private const val PARSER_VERSION = "legacy-osint-import-v2"
    private val CREDENTIAL_MARKERS = listOf(
        "password=",
        "passwd=",
        "pwd=",
        "cookie=",
        "session=",
        "token=",
        "secret=",
        "api_key=",
        "apikey=",
        "authorization: bearer",
        "private key",
        "stealer log"
    )
    private val SENSITIVE_KEYS = setOf(
        "password",
        "passwd",
        "pwd",
        "hash",
        "cookie",
        "session",
        "token",
        "secret",
        "credential",
        "privatekey",
        "apikey",
        "authorization",
        "stealer"
    )
    private val HANDLE_MENTION = Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9_][A-Za-z0-9_.-]{1,63})")
    private val HANDLE_VALUE = Regex("[a-z0-9_][a-z0-9_.-]{1,63}")
    private val JSON = Json { ignoreUnknownKeys = true }
}
