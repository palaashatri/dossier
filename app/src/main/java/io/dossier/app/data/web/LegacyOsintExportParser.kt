package io.dossier.app.data.web

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
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

/**
 * Local-only compatibility parser for historical Twint and snscrape exports.
 *
 * Dossier does not execute either scraper. The caller supplies an export created
 * elsewhere for an explicitly authorized handle; every parsed record stays a
 * Candidate/ThirdPartyAggregation observation until its public URL is independently
 * verified by Dossier's live/archive verification pipeline.
 */
object LegacyOsintExportParser {
    enum class Source { TwintJson, SnscrapeJsonl }

    data class ParseResult(
        val collection: EvidenceCollection,
        val acceptedRecords: Int,
        val rejectedRecords: Int,
        val warnings: List<String>
    )

    fun parse(source: Source, raw: String, authorizedHandles: Collection<String>): ParseResult {
        val handles = authorizedHandles
            .map { normalizeHandle(it) }
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
            val parsed = when (source) {
                Source.TwintJson -> parseTwint(record)
                Source.SnscrapeJsonl -> parseSnscrape(record)
            }
            if (parsed == null || normalizeHandle(parsed.username) !in handles || parsed.url.isBlank()) {
                rejected++
                return@forEach
            }

            val handle = normalizeHandle(parsed.username)
            val id = "legacy-osint:${source.name.lowercase()}:${sha256("$handle|${parsed.url}").take(32)}"
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
                evidence = "User-supplied ${sourceLabel(source)} export; independent verification required"
            )
        }

        val warnings = buildList {
            if (records.size > MAX_RECORDS) add("Export truncated to $MAX_RECORDS records")
            if (rejected > 0) add("$rejected record(s) rejected because the handle/URL did not match the authorized import contract")
        }
        return ParseResult(
            collection = EvidenceCollection(
                evidence = evidence.distinctBy { it.id },
                relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
            ),
            acceptedRecords = evidence.distinctBy { it.id }.size,
            rejectedRecords = rejected,
            warnings = warnings
        )
    }

    private data class ImportedRecord(
        val username: String,
        val url: String,
        val text: String,
        val observedAtEpochMillis: Long?
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
        return ImportedRecord(username, url, text, timestamp)
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
        return ImportedRecord(username, url, text, timestamp)
    }

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
            .filter { it.isNotBlank() }
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
        .lowercase()

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
    private const val MAX_SNIPPET_CHARS = 900
    private const val IMPORT_CONFIDENCE = 0.52f
    private const val PARSER_VERSION = "legacy-osint-import-v1"
    private val JSON = Json { ignoreUnknownKeys = true }
}
