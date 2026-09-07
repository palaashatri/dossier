package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.AttackPathFinder
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Exact, optional output of deterministic attack-path tracing.
 *
 * The payload is kept only in the authenticated encrypted request record. A
 * retry must prove the same request, immutable provider plan, owner, TTL and
 * complete graph/confidence input commitment before reusing it.
 */
@Serializable
internal data class AttackPathsStageCheckpoint(
    val requestId: String,
    val planFingerprint: String,
    val ownerId: String,
    val capturedAtEpochMillis: Long,
    val inputDigest: String,
    val attackPathsJson: String
)

/** Bounded codec and input commitment for the deterministic attack-path stage. */
internal object AttackPathsCheckpointCodec {
    /** Leave space for the request record's graph and other stage outputs. */
    const val MAX_ATTACK_PATHS_BYTES = 32 * 1024
    const val MAX_PATHS = 5
    const val MAX_STEPS = 16

    private const val CODEC_VERSION = "attack-paths-stage-v1|attack-path-finder-v1"
    private const val MAX_LABEL_CHARS = 4_096
    private const val MAX_RELATION_CHARS = 256
    private const val MAX_EVIDENCE_CHARS = 4_096
    private const val MAX_RISK_HINT_CHARS = 512

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    /** Serializes only a validated, bounded list of paths. */
    fun encode(paths: List<AttackPathFinder.AttackPath>): String? = runCatching {
        if (!isValidPaths(paths)) return null
        json.encodeToString(AttackPathsOutput(paths))
    }.getOrNull()?.takeIf(::isValidSerializedAttackPaths)

    /** Malformed, oversized, duplicate or unsafe output fails closed. */
    fun decode(serialized: String): List<AttackPathFinder.AttackPath>? {
        if (!isValidSerializedBytes(serialized)) return null
        return runCatching {
            json.decodeFromString<AttackPathsOutput>(serialized)
        }.getOrNull()?.paths?.takeIf(::isValidPaths)
    }

    /** Returns a lower-case SHA-256 commitment suitable for checkpoint metadata. */
    fun inputDigest(
        input: IdentityInput,
        graph: EntityGraph,
        confidenceByEdge: Map<String, RelationshipConfidence>
    ): String {
        val canonical = json.encodeToString(
            AttackPathsDigestInput(
                codecVersion = CODEC_VERSION,
                input = input,
                graph = graph,
                confidence = confidenceByEdge.entries
                    .sortedBy { it.key }
                    .map { (edgeKey, value) ->
                        ConfidenceDigestEntry(
                            edgeKey = edgeKey,
                            score = value.score,
                            reasons = value.reasons
                        )
                    }
            )
        )
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    fun isValidDigest(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    private fun isValidSerializedAttackPaths(serialized: String): Boolean =
        isValidSerializedBytes(serialized) && runCatching {
            json.decodeFromString<AttackPathsOutput>(serialized).paths
        }.getOrNull()?.let(::isValidPaths) == true

    private fun isValidSerializedBytes(serialized: String): Boolean {
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        return bytes.isNotEmpty() &&
            bytes.size <= MAX_ATTACK_PATHS_BYTES &&
            serialized.none { it.code < 0x20 || it.code == 0x7f }
    }

    private fun isValidPaths(paths: List<AttackPathFinder.AttackPath>): Boolean {
        if (paths.size > MAX_PATHS || paths.distinct().size != paths.size) return false
        return paths.all { path ->
            safeText(path.endpointLabel, MAX_LABEL_CHARS, required = true) &&
                safeText(path.riskHint, MAX_RISK_HINT_CHARS, required = true) &&
                path.steps.size in 1..MAX_STEPS &&
                path.steps.all { step ->
                    safeText(step.fromLabel, MAX_LABEL_CHARS, required = true) &&
                        safeText(step.toLabel, MAX_LABEL_CHARS, required = true) &&
                        safeText(step.relation, MAX_RELATION_CHARS, required = true) &&
                        (step.evidence == null || safeText(step.evidence, MAX_EVIDENCE_CHARS, required = true)) &&
                        (step.confidence == null || step.confidence.isFinite() && step.confidence in 0f..1f)
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
    private data class AttackPathsOutput(
        val paths: List<AttackPathFinder.AttackPath>
    )

    @Serializable
    private data class AttackPathsDigestInput(
        val codecVersion: String,
        val input: IdentityInput,
        val graph: EntityGraph,
        val confidence: List<ConfidenceDigestEntry>
    )

    @Serializable
    private data class ConfidenceDigestEntry(
        val edgeKey: String,
        val score: Float,
        val reasons: List<String>
    )

    private val UNSAFE_CREDENTIAL_PATTERN =
        Regex("(?i)(password|passwd|token|secret|cookie|authorization|bearer|api[ -_]?key)")
}
