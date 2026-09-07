package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Exact, optional output of deterministic relationship-confidence scoring.
 *
 * The output is retained only when it fits the encrypted request record. A
 * retry must prove the same request, immutable provider plan, owner, TTL and
 * complete scoring input commitment before it can reuse this value.
 */
@Serializable
internal data class RelationshipConfidenceStageCheckpoint(
    val requestId: String,
    val planFingerprint: String,
    val ownerId: String,
    val capturedAtEpochMillis: Long,
    val inputDigest: String,
    val confidenceJson: String
)

/** Bounded codec and input commitment for the confidence-scoring boundary. */
internal object ConfidenceCheckpointCodec {
    /** Leave room for the request record's graph and post-processing outputs. */
    const val MAX_CONFIDENCE_BYTES = 32 * 1024

    private const val CODEC_VERSION = "relationship-confidence-stage-v1|confidence-engine-v1"
    private const val MAX_ENTRIES = 4_096
    private const val MAX_EDGE_KEY_CHARS = 1_024
    private const val MAX_REASONS = 16
    private const val MAX_REASON_CHARS = 512

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    /** Serializes only a validated, bounded confidence map. */
    fun encode(confidence: Map<String, RelationshipConfidence>): String? = runCatching {
        val entries = confidence.entries
            .sortedBy { it.key }
            .map { (edgeKey, value) ->
                ConfidenceEntry(
                    edgeKey = edgeKey,
                    score = value.score,
                    reasons = value.reasons
                )
            }
        if (!isValidEntries(entries)) return null
        json.encodeToString(ConfidenceOutput(entries))
    }.getOrNull()?.takeIf(::isValidSerializedConfidence)

    /** Malformed, oversized, duplicate or unsafe output fails closed. */
    fun decode(serialized: String): Map<String, RelationshipConfidence>? {
        if (!isValidSerializedConfidence(serialized)) return null
        return runCatching {
            val output = json.decodeFromString<ConfidenceOutput>(serialized)
            if (!isValidEntries(output.entries)) return null
            output.entries.associate { entry ->
                entry.edgeKey to RelationshipConfidence(entry.score, entry.reasons)
            }
        }.getOrNull()
    }

    /** Returns a lower-case SHA-256 commitment suitable for checkpoint metadata. */
    fun inputDigest(
        input: IdentityInput,
        graph: EntityGraph,
        evidence: List<Evidence>,
        usernameSeeds: List<String>
    ): String {
        val canonical = json.encodeToString(
            ConfidenceDigestInput(
                codecVersion = CODEC_VERSION,
                input = input,
                graph = graph,
                evidence = evidence,
                // ConfidenceEngine consumes these as a set. Sorting makes the
                // commitment independent of an equivalent set iteration order.
                usernameSeeds = usernameSeeds.distinct().sorted()
            )
        )
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    fun isValidDigest(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    private fun isValidSerializedConfidence(serialized: String): Boolean {
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        return bytes.isNotEmpty() &&
            bytes.size <= MAX_CONFIDENCE_BYTES &&
            serialized.none { it.code < 0x20 || it.code == 0x7f }
    }

    private fun isValidEntries(entries: List<ConfidenceEntry>): Boolean {
        if (entries.size > MAX_ENTRIES) return false
        if (entries.map(ConfidenceEntry::edgeKey).distinct().size != entries.size) return false
        return entries.all { entry ->
            safeText(entry.edgeKey, MAX_EDGE_KEY_CHARS, required = true) &&
                entry.score.isFinite() && entry.score in 0f..1f &&
                entry.reasons.size in 1..MAX_REASONS &&
                entry.reasons.all { reason ->
                    safeText(reason, MAX_REASON_CHARS, required = true)
                }
        }
    }

    private fun safeText(value: String, maxChars: Int, required: Boolean): Boolean =
        value.length <= maxChars &&
            (!required || value.isNotBlank()) &&
            value.none { it.code < 0x20 || it.code == 0x7f } &&
            !UNSAFE_CREDENTIAL_PATTERN.containsMatchIn(value)

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class ConfidenceOutput(
        val entries: List<ConfidenceEntry>
    )

    @Serializable
    private data class ConfidenceEntry(
        val edgeKey: String,
        val score: Float,
        val reasons: List<String>
    )

    @Serializable
    private data class ConfidenceDigestInput(
        val codecVersion: String,
        val input: IdentityInput,
        val graph: EntityGraph,
        val evidence: List<Evidence>,
        val usernameSeeds: List<String>
    )

    private val UNSAFE_CREDENTIAL_PATTERN =
        Regex("(?i)(password|passwd|token|secret|cookie|authorization|bearer|api[ -_]?key)")
}
