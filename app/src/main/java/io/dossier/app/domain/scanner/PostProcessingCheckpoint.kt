package io.dossier.app.domain.scanner

import io.dossier.app.domain.analysis.OsintAnalysisBundle
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.UsernameSurfaceObservation
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Codec and input commitment for the deterministic post-processing stage.
 *
 * The checkpoint stores the exact derived analysis only when it fits the
 * encrypted request record. The input commitment covers every stable field
 * consumed by [io.dossier.app.domain.analysis.OsintPostProcessor], so a retry
 * never reuses an analysis produced from a different evidence/profile set.
 */
internal object PostProcessingCheckpointCodec {
    /** Leaves headroom for the request record's other encrypted metadata. */
    const val MAX_ANALYSIS_BYTES = 64 * 1024
    private const val MAX_SURFACE_ENTRIES = 1_024
    private const val MAX_TIMEZONE_HYPOTHESES = 32
    private const val MAX_TOPICS = 128
    private const val MAX_STYLE_COMPARISONS = 256
    private const val MAX_INTERACTION_EDGES = 512
    private const val MAX_INFLUENCE_NODES = 512
    private const val MAX_CLUSTERS = 512
    private const val MAX_CLUSTER_NODES = 1_024
    private const val MAX_STRING_CHARS = 4_096

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    fun encode(analysis: OsintAnalysisBundle): String? = runCatching {
        json.encodeToString(analysis)
    }.getOrNull()?.takeIf(::isValidSerializedAnalysis)

    fun decode(serialized: String): OsintAnalysisBundle? {
        if (!isValidSerializedAnalysis(serialized)) return null
        return runCatching { json.decodeFromString<OsintAnalysisBundle>(serialized) }
            .getOrNull()
            ?.takeIf(::isValidAnalysisShape)
    }

    fun isValidSerializedAnalysis(serialized: String): Boolean {
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        return bytes.isNotEmpty() &&
            bytes.size <= MAX_ANALYSIS_BYTES &&
            serialized.none { it.code < 0x20 || it.code == 0x7f } &&
            decodeWithoutRecursion(serialized)
    }

    /**
     * Computes a stable SHA-256 commitment without persisting profile values,
     * URLs, snippets, or username observations in the checkpoint metadata.
     */
    fun inputDigest(
        input: IdentityInput,
        profiles: List<ProfileScanResult>,
        evidence: EvidenceCollection,
        usernameObservations: List<UsernameSurfaceObservation>
    ): String {
        val canonical = StringBuilder()
        appendField(canonical, input.primaryUsername)
        input.usernames.map(String::trim).sorted().forEach { appendField(canonical, it) }

        profiles
            .sortedWith(compareBy<ProfileScanResult> { it.candidate.url.lowercase() }
                .thenBy { it.candidate.platform.name }
                .thenBy { it.candidate.username.lowercase() })
            .forEach { profile ->
                appendField(canonical, profile.candidate.platform.name)
                appendField(canonical, profile.candidate.username)
                appendField(canonical, profile.candidate.url)
                appendField(canonical, profile.candidate.confidence.toString())
                appendField(canonical, profile.exists.toString())
                appendField(canonical, profile.verified.toString())
                appendField(canonical, profile.verificationStatus)
                appendField(canonical, profile.provenance)
            }

        evidence.evidence
            .sortedBy { it.id }
            .forEach { record ->
                appendField(canonical, record.id)
                appendField(canonical, record.kind.name)
                appendField(canonical, record.value)
                appendField(canonical, record.sourceUrl)
                appendField(canonical, record.snippet)
                appendField(canonical, record.providerId)
                appendField(canonical, record.observedAtEpochMillis?.toString())
            }
        evidence.relationships
            .sortedWith(compareBy({ it.fromValue }, { it.toValue }, { it.relation }, { it.evidence }))
            .forEach { relationship ->
                appendField(canonical, relationship.fromValue)
                appendField(canonical, relationship.toValue)
                appendField(canonical, relationship.relation)
            }

        usernameObservations
            .sortedWith(compareBy({ it.source }, { it.site }, { it.username }, { it.profileUrl }))
            .forEach { observation ->
                appendField(canonical, observation.source)
                appendField(canonical, observation.site)
                appendField(canonical, observation.username)
                appendField(canonical, observation.profileUrl)
                appendField(canonical, observation.state.name)
                appendField(canonical, observation.confidence.toString())
                appendField(canonical, observation.reason)
                appendField(canonical, observation.providerId)
            }
        return sha256(canonical.toString().toByteArray(Charsets.UTF_8))
    }

    fun isValidDigest(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    internal fun isValidAnalysisShape(analysis: OsintAnalysisBundle): Boolean {
        val surface = analysis.identitySurface
        if (surface.entries.size > MAX_SURFACE_ENTRIES) return false
        if (surface.entries.any { entry ->
                !safeString(entry.platform) ||
                    !safeString(entry.username) ||
                    !safeString(entry.url) ||
                    !safeString(entry.reason) ||
                    !finiteInRange(entry.confidence, 0.0, 1.0)
            }
        ) return false

        val behavioral = analysis.behavioral
        if (behavioral.hourlyActivityUtc.size != 24 ||
            behavioral.hourlyActivityUtc.any { it < 0 } ||
            behavioral.timezoneHypotheses.size > MAX_TIMEZONE_HYPOTHESES ||
            behavioral.topics.size > MAX_TOPICS ||
            behavioral.crossSourceStyle.size > MAX_STYLE_COMPARISONS ||
            behavioral.timezoneHypotheses.any { hypothesis ->
                !finiteInRange(hypothesis.score, 0.0, 1.0) ||
                    hypothesis.utcOffsetHours !in -24..24 ||
                    !safeString(hypothesis.note)
            } ||
            behavioral.topics.any(::safeStringFailure) ||
            !isValidStyle(behavioral.overallStyle) ||
            behavioral.crossSourceStyle.any { comparison ->
                !safeString(comparison.sourceA) ||
                    !safeString(comparison.sourceB) ||
                    !finiteInRange(comparison.similarity, 0.0, 1.0) ||
                    comparison.sampleCountA < 0 ||
                    comparison.sampleCountB < 0 ||
                    !safeString(comparison.note)
            } ||
            !safeString(behavioral.caveat)
        ) return false

        val interaction = analysis.interactionGraph
        if (interaction.nodeCount < 0 ||
            interaction.edgeCount < 0 ||
            !finiteNonNegative(interaction.totalInteractionWeight) ||
            interaction.edges.size > MAX_INTERACTION_EDGES ||
            interaction.influenceNodes.size > MAX_INFLUENCE_NODES ||
            interaction.clusters.size > MAX_CLUSTERS ||
            !safeString(interaction.caveat)
        ) return false
        if (interaction.edges.any { edge ->
                !safeString(edge.source) ||
                    !safeString(edge.target) ||
                    edge.mentions < 0 || edge.replies < 0 || edge.otherInteractions < 0 ||
                    !finiteNonNegative(edge.weight)
            }
        ) return false
        if (interaction.influenceNodes.any { node ->
                !safeString(node.node) ||
                    !finiteNonNegative(node.weightedDegree) ||
                    !finiteNonNegative(node.pageRank)
            }
        ) return false
        return interaction.clusters.all { cluster ->
            cluster.id >= 0 &&
                cluster.totalEdgeWeight >= 0.0 &&
                cluster.totalEdgeWeight.isFinite() &&
                cluster.nodes.size <= MAX_CLUSTER_NODES &&
                cluster.nodes.all(::safeString)
        }
    }

    private fun decodeWithoutRecursion(serialized: String): Boolean =
        runCatching {
            val analysis = json.decodeFromString<OsintAnalysisBundle>(serialized)
            isValidAnalysisShape(analysis)
        }.getOrDefault(false)

    private fun isValidStyle(style: io.dossier.app.domain.analysis.StyleFingerprint): Boolean =
        style.sampleCount >= 0 &&
            style.wordCount >= 0 &&
            finiteNonNegative(style.averageWordLength) &&
            finiteNonNegative(style.averageSentenceWords) &&
            finiteInRange(style.vocabularyRichness, 0.0, 1.0) &&
            finiteNonNegative(style.punctuationPer100Words) &&
            finiteNonNegative(style.questionPer100Words) &&
            finiteNonNegative(style.exclamationPer100Words) &&
            finiteInRange(style.uppercaseWordRatio, 0.0, 1.0)

    private fun safeString(value: String): Boolean =
        value.length <= MAX_STRING_CHARS &&
            value.none { it.code < 0x20 || it.code == 0x7f }

    private fun safeStringFailure(value: String): Boolean = !safeString(value)

    private fun finiteNonNegative(value: Double): Boolean = value.isFinite() && value >= 0.0

    private fun finiteInRange(value: Double, min: Double, max: Double): Boolean =
        value.isFinite() && value in min..max

    private fun appendField(builder: StringBuilder, value: String?) {
        val normalized = value ?: ""
        builder.append(normalized.length).append(':').append(normalized).append('|')
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }
}
