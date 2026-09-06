package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.identity.EntityResolverV2
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest

/**
 * Exact, optional output of the deterministic graph-build stage.
 *
 * The graph is retained only when it fits the authenticated encrypted request
 * record.  A retry must prove the same request, immutable provider plan, owner,
 * TTL and graph inputs before it can reuse this value; otherwise it rebuilds
 * the graph normally.
 */
@Serializable
internal data class EntityGraphStageCheckpoint(
    val requestId: String,
    val planFingerprint: String,
    val ownerId: String,
    val capturedAtEpochMillis: Long,
    val inputDigest: String,
    val graphJson: String
)

/** Codec and commitment for the deterministic EntityGraphBuilder boundary. */
internal object GraphCheckpointCodec {
    /** Keep space for the request record's input and other checkpoints. */
    const val MAX_GRAPH_BYTES = 48 * 1024

    private const val CODEC_VERSION = "entity-graph-stage-v1|schema-2|${EntityResolverV2.RESOLVER_VERSION}"
    private const val MAX_ENTITIES = 1_024
    private const val MAX_EDGES = 4_096
    private const val MAX_SOURCE_URLS = 32
    private const val MAX_EVIDENCE_IDS = 256
    private const val MAX_TEXT_CHARS = 4_096
    private const val MAX_ID_CHARS = 512
    private const val MAX_RELATION_CHARS = 256
    private const val MAX_URL_CHARS = 2_048

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    /** Serializes only a validated, bounded graph; an oversized graph is not checkpointed. */
    fun encode(graph: EntityGraph): String? = runCatching {
        if (!isValidGraphShape(graph)) return null
        json.encodeToString(graph)
    }.getOrNull()?.takeIf(::isValidSerializedGraph)

    /** Malformed, oversized, dangling or unsafe graphs fail closed. */
    fun decode(serialized: String): EntityGraph? {
        if (!isValidSerializedBytes(serialized)) return null
        return runCatching { json.decodeFromString<EntityGraph>(serialized) }
            .getOrNull()
            ?.takeIf(::isValidGraphShape)
    }

    fun isValidDigest(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    /**
     * Hashes every value consumed by EntityGraphBuilder.  The values stay in
     * memory and only the SHA-256 commitment is persisted in checkpoint
     * metadata; the graph itself remains inside the encrypted request record.
     */
    fun inputDigest(
        input: IdentityInput,
        profileResults: List<ProfileScanResult>,
        findings: List<Finding>,
        faceMatches: List<FaceConsistencyMatch>,
        breachDigests: List<BreachDigest>,
        evidence: List<Evidence>,
        relationships: List<EvidenceRelationship>
    ): String {
        val canonical = json.encodeToString(
            GraphDigestInput(
                codecVersion = CODEC_VERSION,
                input = input,
                profileResults = profileResults,
                findings = findings,
                faceMatches = faceMatches,
                breachDigests = breachDigests,
                evidence = evidence,
                relationships = relationships
            )
        )
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    internal fun isValidGraphShape(graph: EntityGraph): Boolean {
        if (graph.schemaVersion != 2 || graph.entities.size > MAX_ENTITIES || graph.edges.size > MAX_EDGES) {
            return false
        }
        val entityIds = graph.entities.map { it.id }
        if (entityIds.size != entityIds.distinct().size) return false
        if (graph.entities.any(::invalidEntity)) return false

        val edgeKeys = graph.edges.map { "${it.fromId}\u001f${it.toId}\u001f${it.relation}" }
        if (edgeKeys.size != edgeKeys.distinct().size) return false
        return graph.edges.all { edge ->
            !invalidEdge(edge) &&
                edge.fromId in entityIds &&
                edge.toId in entityIds
        }
    }

    private fun isValidSerializedGraph(serialized: String): Boolean =
        isValidSerializedBytes(serialized) && runCatching {
            isValidGraphShape(json.decodeFromString<EntityGraph>(serialized))
        }.getOrDefault(false)

    private fun isValidSerializedBytes(serialized: String): Boolean {
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        return bytes.isNotEmpty() &&
            bytes.size <= MAX_GRAPH_BYTES &&
            serialized.none { it.code < 0x20 || it.code == 0x7f }
    }

    private fun invalidEntity(entity: DossierEntity): Boolean =
        !safeText(entity.id, MAX_ID_CHARS, required = true) ||
            !safeText(entity.label, MAX_TEXT_CHARS) ||
            !finiteInRange(entity.confidence, 0f, 1f) ||
            entity.sourceUrls.size > MAX_SOURCE_URLS ||
            entity.sourceUrls.any { !safePublicUrl(it) } ||
            entity.evidenceIds.size > MAX_EVIDENCE_IDS ||
            entity.evidenceIds.any { !safeText(it, MAX_ID_CHARS, required = true) } ||
            entity.firstObservedAtEpochMillis?.let { it < 0L } == true ||
            entity.lastObservedAtEpochMillis?.let { it < 0L } == true

    private fun invalidEdge(edge: DossierEdge): Boolean =
        !safeText(edge.fromId, MAX_ID_CHARS, required = true) ||
            !safeText(edge.toId, MAX_ID_CHARS, required = true) ||
            !safeText(edge.relation, MAX_RELATION_CHARS, required = true) ||
            (edge.evidence != null && !safeText(edge.evidence, MAX_TEXT_CHARS)) ||
            edge.evidenceIds.size > MAX_EVIDENCE_IDS ||
            edge.evidenceIds.any { !safeText(it, MAX_ID_CHARS, required = true) } ||
            edge.contradictingEvidenceIds.size > MAX_EVIDENCE_IDS ||
            edge.contradictingEvidenceIds.any { !safeText(it, MAX_ID_CHARS, required = true) } ||
            edge.confidence?.let { !finiteInRange(it, 0f, 1f) } == true

    private fun safeText(value: String, maxChars: Int, required: Boolean = false): Boolean =
        value.length <= maxChars &&
            (!required || value.isNotBlank()) &&
            value.none { it.code < 0x20 || it.code == 0x7f } &&
            !UNSAFE_CREDENTIAL_PATTERN.containsMatchIn(value)

    private fun safePublicUrl(value: String): Boolean {
        if (!safeText(value, MAX_URL_CHARS, required = true)) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }.getOrDefault(false)
    }

    private fun finiteInRange(value: Float, min: Float, max: Float): Boolean =
        value.isFinite() && value in min..max

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class GraphDigestInput(
        val codecVersion: String,
        val input: IdentityInput,
        val profileResults: List<ProfileScanResult>,
        val findings: List<Finding>,
        val faceMatches: List<FaceConsistencyMatch>,
        val breachDigests: List<BreachDigest>,
        val evidence: List<Evidence>,
        val relationships: List<EvidenceRelationship>
    )

    private val UNSAFE_CREDENTIAL_PATTERN =
        Regex("(?i)(password|passwd|token|secret|cookie|authorization|bearer|api[ -_]?key)")
}
