package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.ExposureDimension
import io.dossier.app.domain.evidence.ExposureEngine
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Exact, optional output of deterministic exposure scoring.
 *
 * Finding values, URLs, snippets and remediation text are deliberately not
 * persisted. Top findings are represented by current-version evidence IDs and
 * are resolved against the current in-memory findings only after the complete
 * input digest has matched.
 */
@Serializable
internal data class ExposureStageCheckpoint(
    val requestId: String,
    val planFingerprint: String,
    val ownerId: String,
    val capturedAtEpochMillis: Long,
    val inputDigest: String,
    val exposureJson: String
)

internal data class DecodedExposureCheckpoint(
    val dimensions: List<ExposureEngine.DimensionScore>,
    val overall: Int,
    val topFindingIds: List<String>
)

/** Bounded codec and input commitment for the exposure-scoring boundary. */
internal object ExposureCheckpointCodec {
    const val MAX_EXPOSURE_BYTES = 16 * 1024

    private const val CODEC_VERSION = "exposure-stage-v1|exposure-engine-v1"
    private const val MAX_DIMENSIONS = 6
    private const val MAX_CONTRIBUTING_TYPES = 14
    private const val MAX_TOP_FINDING_IDS = 10
    private val FINDING_ID_PATTERN = Regex("^ev2:[0-9a-f]{32}$")

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    /** Serializes only dimension scores and hashed finding references. */
    fun encode(result: ExposureEngine.ExposureResult): String? = runCatching {
        val output = ExposureOutput(
            dimensions = result.dimensions.map { score ->
                DimensionOutput(
                    dimension = score.dimension,
                    score = score.score,
                    contributingTypes = score.contributingTypes
                )
            },
            overall = result.overall,
            topFindingIds = result.topFindings
                .take(MAX_TOP_FINDING_IDS)
                .map(EvidenceIdPolicy::findingId)
        )
        if (!isValidOutput(output)) return null
        json.encodeToString(output)
    }.getOrNull()?.takeIf(::isValidSerializedExposure)

    /** Malformed, oversized or unsafe output fails closed. */
    fun decode(serialized: String): DecodedExposureCheckpoint? {
        if (!isValidSerializedExposure(serialized)) return null
        return runCatching {
            val output = json.decodeFromString<ExposureOutput>(serialized)
            if (!isValidOutput(output)) return null
            DecodedExposureCheckpoint(
                dimensions = output.dimensions.map { dimension ->
                    ExposureEngine.DimensionScore(
                        dimension = dimension.dimension,
                        score = dimension.score,
                        contributingTypes = dimension.contributingTypes
                    )
                },
                overall = output.overall,
                topFindingIds = output.topFindingIds
            )
        }.getOrNull()
    }

    /** Rebuilds the sensitive top-finding projection from current memory only. */
    fun rebuild(
        checkpoint: DecodedExposureCheckpoint,
        findings: List<Finding>
    ): ExposureEngine.ExposureResult? {
        val byId = findings.groupBy(EvidenceIdPolicy::findingId)
            .mapValues { (_, values) -> values.toMutableList() }
        val topFindings = checkpoint.topFindingIds.map { id ->
            val matches = byId[id] ?: return null
            if (matches.isEmpty()) return null
            matches.removeAt(0)
        }
        return ExposureEngine.ExposureResult(
            dimensions = checkpoint.dimensions,
            overall = checkpoint.overall,
            topFindings = topFindings
        )
    }

    /** Hashes every value consumed by [ExposureEngine.score]. */
    fun inputDigest(findings: List<Finding>, breaches: List<BreachDigest>): String {
        val canonical = json.encodeToString(
            ExposureDigestInput(
                codecVersion = CODEC_VERSION,
                findings = findings,
                breaches = breaches
            )
        )
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    fun isValidDigest(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    private fun isValidSerializedExposure(serialized: String): Boolean {
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        return bytes.isNotEmpty() &&
            bytes.size <= MAX_EXPOSURE_BYTES &&
            serialized.none { it.code < 0x20 || it.code == 0x7f }
    }

    private fun isValidOutput(output: ExposureOutput): Boolean {
        if (output.dimensions.size != MAX_DIMENSIONS ||
            output.dimensions.map(DimensionOutput::dimension).distinct().size != output.dimensions.size ||
            output.overall !in 0..100 ||
            output.topFindingIds.size > MAX_TOP_FINDING_IDS ||
            output.topFindingIds.any { !FINDING_ID_PATTERN.matches(it) }
        ) return false
        return output.dimensions.all { dimension ->
            dimension.score in 0..100 &&
                dimension.contributingTypes.size <= MAX_CONTRIBUTING_TYPES &&
                dimension.contributingTypes.distinct().size == dimension.contributingTypes.size
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }

    @Serializable
    private data class ExposureOutput(
        val dimensions: List<DimensionOutput>,
        val overall: Int,
        val topFindingIds: List<String>
    )

    @Serializable
    private data class DimensionOutput(
        val dimension: ExposureDimension,
        val score: Int,
        val contributingTypes: List<FindingType>
    )

    @Serializable
    private data class ExposureDigestInput(
        val codecVersion: String,
        val findings: List<Finding>,
        val breaches: List<BreachDigest>
    )
}
