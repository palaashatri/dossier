package io.dossier.app.domain.scanner

import io.dossier.app.data.face.FaceCorrelationModelPack
import io.dossier.app.domain.model.FaceComparisonProvenance
import io.dossier.app.domain.model.FaceComparisonQuality
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest

/**
 * Exact, optional output of the deterministic face-consistency stage.
 *
 * The checkpoint contains only bounded comparison metadata.  Selfie bytes,
 * downloaded profile image bytes, crops and embeddings never enter the
 * request record.  A retry must prove the same request, immutable plan,
 * owner, TTL, profile input digest and face model/pipeline commitment before
 * reusing the value.
 */
@Serializable
internal data class FaceConsistencyStageCheckpoint(
    val requestId: String,
    val planFingerprint: String,
    val ownerId: String,
    val capturedAtEpochMillis: Long,
    val inputDigest: String,
    val matchesJson: String
)

/** Bounded codec and input commitment for the face-consistency boundary. */
internal object FaceCheckpointCodec {
    /** Keep space for the request input, plan and other stage outputs. */
    const val MAX_FACE_BYTES = 24 * 1024

    private const val CODEC_VERSION =
        "face-consistency-stage-v1|schema-1|${FaceCorrelationModelPack.PIPELINE_VERSION}"
    private const val MAX_MATCHES = 12
    private const val MAX_URL_CHARS = 2_048
    private const val MAX_WARNING_CHARS = 512
    private const val MAX_MODEL_SOURCE_CHARS = 160
    private const val MAX_PIPELINE_CHARS = 160
    private const val MAX_QUALITY_REASON_CHARS = 256
    private const val MAX_MODEL_HASHES = 2
    private const val MAX_MODEL_HASH_CHARS = 64

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }
    private val unsafeCredentialPattern =
        Regex("(?i)(password|passwd|token|secret|cookie|authorization|bearer|api[ -_]?key)")

    @Serializable
    private data class FaceCheckpointPayload(
        val codecVersion: String,
        val matches: List<FaceConsistencyMatch>
    )

    @Serializable
    private data class FaceDigestInput(
        val codecVersion: String,
        val modelCommitment: String,
        val input: IdentityInput,
        val profileResults: List<ProfileScanResult>
    )

    /** Serializes only a validated, bounded face result list. */
    fun encode(matches: List<FaceConsistencyMatch>): String? = runCatching {
        if (!isValidMatches(matches)) return null
        json.encodeToString(
            FaceCheckpointPayload(
                codecVersion = CODEC_VERSION,
                matches = matches
            )
        )
    }.getOrNull()?.takeIf(::isValidSerializedBytes)

    /** Malformed, oversized or unsafe output fails closed. */
    fun decode(serialized: String): List<FaceConsistencyMatch>? {
        if (!isValidSerializedBytes(serialized)) return null
        return runCatching {
            json.decodeFromString<FaceCheckpointPayload>(serialized)
        }.getOrNull()
            ?.takeIf { it.codecVersion == CODEC_VERSION && isValidMatches(it.matches) }
            ?.matches
    }

    /**
     * Hashes every value consumed by the face stage.  The selfie URI,
     * profile URLs, snippets and other identity-bearing values stay in memory;
     * only this digest is persisted in the encrypted request metadata.
     */
    fun inputDigest(
        input: IdentityInput,
        profileResults: List<ProfileScanResult>,
        modelCommitment: String
    ): String {
        val canonical = json.encodeToString(
            FaceDigestInput(
                codecVersion = CODEC_VERSION,
                modelCommitment = modelCommitment,
                input = input,
                profileResults = profileResults
            )
        )
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * Binds the result to the currently selected backend/model policy without
     * persisting model files or calibration contents.  Imported model hashes
     * are optional because the built-in appearance fallback has no file.
     */
    fun modelCommitment(
        strongCorrelationEnabled: Boolean,
        importedModelSha256: String?
    ): String = sha256(
        buildString {
            append(CODEC_VERSION)
            append("|strong=").append(strongCorrelationEnabled)
            append("|yunet=").append(FaceCorrelationModelPack.YUNET.sha256)
            append("|sface=").append(FaceCorrelationModelPack.SFACE.sha256)
            append("|imported=").append(importedModelSha256.orEmpty())
        }.toByteArray(Charsets.UTF_8)
    )

    fun isValidDigest(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    private fun isValidMatches(matches: List<FaceConsistencyMatch>): Boolean {
        if (matches.size > MAX_MATCHES) return false
        val urls = matches.map(FaceConsistencyMatch::profileUrl)
        if (urls.size != urls.distinct().size) return false
        return matches.all(::isValidMatch)
    }

    private fun isValidMatch(match: FaceConsistencyMatch): Boolean =
        isSafePublicUrl(match.profileUrl, MAX_URL_CHARS) &&
            isSafeText(match.warning, MAX_WARNING_CHARS) &&
            match.similarityScore.isFinite() &&
            match.similarityScore in 0f..1f &&
            isValidProvenance(match.provenance)

    private fun isValidProvenance(provenance: FaceComparisonProvenance): Boolean =
        provenance.modelHashes.size <= MAX_MODEL_HASHES &&
            provenance.modelHashes.all { it.length == MAX_MODEL_HASH_CHARS && it.all(::isHex) } &&
            (provenance.modelSource == null ||
                isSafeText(provenance.modelSource, MAX_MODEL_SOURCE_CHARS)) &&
            (provenance.pipelineVersion == null ||
                isSafeText(provenance.pipelineVersion, MAX_PIPELINE_CHARS)) &&
            isValidQuality(provenance.selfieQuality) &&
            isValidQuality(provenance.profileQuality)

    private fun isValidQuality(quality: FaceComparisonQuality?): Boolean = quality?.let {
        isSafeText(it.reason, MAX_QUALITY_REASON_CHARS) &&
            finiteInRange(it.detectorScore, 0f..1f) &&
            finiteInRange(it.faceWidth, 0f..100_000f) &&
            finiteInRange(it.faceHeight, 0f..100_000f) &&
            finiteInRange(it.eyeDistance, 0f..100_000f) &&
            finiteInRange(it.rollDegrees, -180f..180f) &&
            finiteInRange(it.brightness, 0f..255f) &&
            finiteInRange(it.sharpness, 0f..1_000_000_000f)
    } ?: true

    private fun finiteInRange(value: Float?, range: ClosedFloatingPointRange<Float>): Boolean =
        value == null || (value.isFinite() && value in range)

    private fun isSafePublicUrl(value: String, maxChars: Int): Boolean {
        if (!isSafeText(value, maxChars)) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }.getOrDefault(false)
    }

    private fun isSafeText(value: String, maxChars: Int): Boolean =
        value.length in 1..maxChars &&
            value.none { it.code < 0x20 || it.code == 0x7f } &&
            !unsafeCredentialPattern.containsMatchIn(value)

    private fun isValidSerializedBytes(serialized: String): Boolean =
        serialized.toByteArray(Charsets.UTF_8).size <= MAX_FACE_BYTES &&
            serialized.isNotEmpty() &&
            serialized.none { it.code < 0x20 || it.code == 0x7f }

    private fun isHex(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }
}
