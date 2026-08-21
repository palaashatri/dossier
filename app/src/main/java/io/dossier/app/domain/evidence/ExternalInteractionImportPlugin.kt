package io.dossier.app.domain.evidence

import io.dossier.app.data.web.ExternalOsintImportSession
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Extracts interaction edges from user-selected public API/report exports such as
 * Meltano/Singer output, CSV edge lists and other OSINT reports.
 *
 * It never launches external collectors. A relationship is admitted only when at
 * least one endpoint exactly matches an explicitly supplied audit handle. Numeric
 * weights are bounded and expanded only up to a small cap so a hostile report cannot
 * explode the graph.
 */
class ExternalInteractionImportPlugin : ScannerPlugin {
    override val id: String = "external-interaction-import"
    override val displayName: String = "External public interaction imports"

    override suspend fun scan(input: IdentityInput): EvidenceCollection {
        val authorized = (listOfNotNull(input.primaryUsername) + input.usernames)
            .map(::normalizeHandle)
            .filter(String::isNotBlank)
            .toSet()
        if (authorized.isEmpty()) return EvidenceCollection()

        val relationships = mutableListOf<EvidenceRelationship>()
        ExternalOsintImportSession.snapshot().forEach { pending ->
            parseRecords(pending.rawText)
                .take(MAX_RECORDS_PER_IMPORT)
                .forEach { row ->
                    val source = firstHandle(row, SOURCE_KEYS)
                    val explicitTarget = firstHandle(row, TARGET_KEYS)
                    val relation = normalizeRelation(firstText(row, RELATION_KEYS))
                    val rowText = firstText(row, TEXT_KEYS).orEmpty()
                    val textTargets = if (source != null) handlesFromText(rowText) else emptyList()
                    val targets = buildList {
                        explicitTarget?.let(::add)
                        addAll(textTargets)
                    }.distinct().take(MAX_TARGETS_PER_ROW)

                    if (source == null || targets.isEmpty()) return@forEach
                    targets.forEach { target ->
                        if (source == target) return@forEach
                        if (source !in authorized && target !in authorized) return@forEach
                        val inferredRelation = when {
                            explicitTarget != null -> relation
                            else -> "MENTIONS"
                        }
                        val repetitions = boundedWeight(firstText(row, WEIGHT_KEYS))
                        repeat(repetitions) { occurrence ->
                            relationships += EvidenceRelationship(
                                fromValue = source,
                                toValue = target,
                                relation = inferredRelation,
                                evidence = buildString {
                                    append("User-selected public interaction report: ${pending.displayName}")
                                    if (repetitions > 1) append("; bounded-weight-instance=${occurrence + 1}/$repetitions")
                                }
                            )
                        }
                    }
                }
        }

        return EvidenceCollection(
            relationships = relationships
                .distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}|${it.evidence.orEmpty()}" }
                .take(MAX_RELATIONSHIPS)
        )
    }

    private fun parseRecords(raw: String): Sequence<Map<String, String>> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptySequence()

        val jsonRoot = runCatching { JSON.parseToJsonElement(trimmed) }.getOrNull()
        if (jsonRoot != null) {
            return jsonObjects(jsonRoot).map(::flattenJsonObject)
        }

        val lines = trimmed.lineSequence().filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return emptySequence()

        // JSONL / Singer messages.
        val jsonLines = lines.mapNotNull { line ->
            runCatching { JSON.parseToJsonElement(line) as? JsonObject }.getOrNull()
        }
        if (jsonLines.isNotEmpty() && jsonLines.size >= lines.size / 2) {
            return jsonLines.asSequence().map { objectRow ->
                val record = objectRow["record"] as? JsonObject ?: objectRow
                flattenJsonObject(record)
            }
        }

        val delimiter = if (lines.first().count { it == '\t' } > lines.first().count { it == ',' }) '\t' else ','
        val headers = splitDelimited(lines.first(), delimiter).map { normalizeKey(it) }
        if (headers.size < 2) return emptySequence()
        return lines.drop(1).asSequence().mapNotNull { line ->
            val values = splitDelimited(line, delimiter)
            if (values.isEmpty()) null else headers.mapIndexed { index, key -> key to values.getOrElse(index) { "" } }.toMap()
        }
    }

    private fun jsonObjects(element: JsonElement): Sequence<JsonObject> = sequence {
        when (element) {
            is JsonObject -> {
                val record = element["record"] as? JsonObject
                if (record != null) yield(record) else yield(element)
                element.values.forEach { child ->
                    if (child is JsonArray) yieldAll(jsonObjects(child))
                }
            }
            is JsonArray -> element.forEach { yieldAll(jsonObjects(it)) }
            else -> Unit
        }
    }

    private fun flattenJsonObject(obj: JsonObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun visit(prefix: String, element: JsonElement, depth: Int) {
            if (depth > MAX_JSON_DEPTH) return
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.let { value ->
                    if (value.length <= MAX_FIELD_CHARS) result[normalizeKey(prefix)] = value
                }
                is JsonObject -> element.forEach { (key, value) ->
                    visit(if (prefix.isBlank()) key else "$prefix.$key", value, depth + 1)
                    if (value is JsonPrimitive && key !in result) {
                        value.contentOrNull?.let { result.putIfAbsent(normalizeKey(key), it.take(MAX_FIELD_CHARS)) }
                    }
                }
                is JsonArray -> {
                    val primitives = element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    if (primitives.isNotEmpty()) result[normalizeKey(prefix)] = primitives.joinToString(" ").take(MAX_FIELD_CHARS)
                }
            }
        }
        obj.forEach { (key, value) -> visit(key, value, 0) }
        return result
    }

    private fun firstHandle(row: Map<String, String>, keys: List<String>): String? = keys
        .asSequence()
        .mapNotNull { key -> valueForKey(row, key) }
        .map(::normalizeHandle)
        .firstOrNull(String::isNotBlank)

    private fun firstText(row: Map<String, String>, keys: List<String>): String? = keys
        .asSequence()
        .mapNotNull { key -> valueForKey(row, key)?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()

    private fun valueForKey(row: Map<String, String>, wanted: String): String? {
        val normalized = normalizeKey(wanted)
        return row[normalized] ?: row.entries.firstOrNull { (key, _) ->
            key == normalized || key.endsWith(".$normalized") || key.endsWith("_$normalized")
        }?.value
    }

    private fun normalizeRelation(raw: String?): String {
        val value = raw.orEmpty().lowercase(Locale.ROOT)
        return when {
            "reply" in value -> "REPLIES_TO"
            "mention" in value -> "MENTIONS"
            "retweet" in value || "repost" in value -> "RETWEETS"
            "quote" in value -> "QUOTES"
            else -> "INTERACTS_WITH"
        }
    }

    private fun boundedWeight(raw: String?): Int {
        val number = raw?.trim()?.toDoubleOrNull() ?: return 1
        return number.roundToInt().coerceIn(1, MAX_WEIGHT_EXPANSION)
    }

    private fun handlesFromText(text: String): List<String> = HANDLE_MENTION.findAll(text)
        .map { normalizeHandle(it.groupValues[1]) }
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_TARGETS_PER_ROW)
        .toList()

    private fun normalizeHandle(raw: String): String = raw.trim()
        .removePrefix("@")
        .removePrefix("u/")
        .trimEnd(',', '.', ':', ';', ')', ']', '}')
        .lowercase(Locale.ROOT)
        .takeIf { it.matches(HANDLE_VALUE) }
        .orEmpty()

    private fun normalizeKey(raw: String): String = raw.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9.]+"), "_")
        .trim('_')

    private fun splitDelimited(line: String, delimiter: Char): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> {
                    values += current.toString().trim(); current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        values += current.toString().trim()
        return values
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val SOURCE_KEYS = listOf("source", "from", "author", "username", "user", "sender", "actor")
        val TARGET_KEYS = listOf("target", "to", "recipient", "reply_to", "in_reply_to", "mentioned_user", "mentioned_username")
        val RELATION_KEYS = listOf("relation", "type", "event", "action", "interaction")
        val WEIGHT_KEYS = listOf("weight", "count", "frequency", "interactions")
        val TEXT_KEYS = listOf("text", "content", "body", "message", "raw_content", "rendered_content")
        val HANDLE_MENTION = Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9_][A-Za-z0-9_.-]{1,63})")
        val HANDLE_VALUE = Regex("[a-z0-9_][a-z0-9_.-]{1,63}")
        const val MAX_RECORDS_PER_IMPORT = 5_000
        const val MAX_RELATIONSHIPS = 10_000
        const val MAX_TARGETS_PER_ROW = 24
        const val MAX_WEIGHT_EXPANSION = 10
        const val MAX_JSON_DEPTH = 4
        const val MAX_FIELD_CHARS = 2_000
    }
}
