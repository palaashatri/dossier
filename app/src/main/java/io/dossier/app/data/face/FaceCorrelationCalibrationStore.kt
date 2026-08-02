package io.dossier.app.data.face

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream

/**
 * Threshold policy for the pinned YuNet/SFace pipeline.
 *
 * Dossier ships a clearly labelled reference policy so the feature can operate
 * after the model pack is installed. It becomes a measured policy only when a
 * calibration JSON produced from an identity-disjoint labelled corpus is
 * imported and bound to both model hashes plus the exact preprocessing version.
 */
data class FaceCorrelationThresholds(
    val reviewThreshold: Float,
    val highSimilarityThreshold: Float,
    val source: String,
    val sfaceSha256: String,
    val yunetSha256: String,
    val pipelineVersion: String,
    val positivePairCount: Int,
    val negativePairCount: Int,
    val reviewFalseMatchRate: Float?,
    val highFalseMatchRate: Float?,
    val reviewTrueMatchRate: Float?,
    val highTrueMatchRate: Float?,
    val measured: Boolean
) {
    init {
        require(reviewThreshold.isFinite() && reviewThreshold in -1f..1f)
        require(highSimilarityThreshold.isFinite() && highSimilarityThreshold in -1f..1f)
        require(reviewThreshold <= highSimilarityThreshold)
        require(sfaceSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(yunetSha256.matches(Regex("[a-fA-F0-9]{64}")))
        if (measured) {
            require(positivePairCount > 0)
            require(negativePairCount > 0)
            require(reviewFalseMatchRate != null && reviewFalseMatchRate in 0f..1f)
            require(highFalseMatchRate != null && highFalseMatchRate in 0f..1f)
            require(reviewTrueMatchRate != null && reviewTrueMatchRate in 0f..1f)
            require(highTrueMatchRate != null && highTrueMatchRate in 0f..1f)
            require(highFalseMatchRate <= reviewFalseMatchRate)
            require(highTrueMatchRate <= reviewTrueMatchRate)
        }
    }

    fun decision(score: Float): String = when {
        score >= highSimilarityThreshold -> FaceCorrelationDecision.HIGH_SIMILARITY
        score >= reviewThreshold -> FaceCorrelationDecision.MANUAL_REVIEW
        else -> FaceCorrelationDecision.NO_SUPPORT
    }

    fun summary(): String = if (measured) {
        "Measured calibration: review >= ${format(reviewThreshold)}, high >= ${format(highSimilarityThreshold)}; " +
            "$positivePairCount positive and $negativePairCount negative held-out pairs."
    } else {
        "Reference policy: review >= ${format(reviewThreshold)}, high >= ${format(highSimilarityThreshold)}. " +
            "The high band is conservative but not yet Dossier-benchmarked."
    }

    private fun format(value: Float): String = "%.3f".format(value)
}

object FaceCorrelationDecision {
    const val NO_SUPPORT = "NO_SUPPORT"
    const val MANUAL_REVIEW = "MANUAL_REVIEW"
    const val HIGH_SIMILARITY = "HIGH_SIMILARITY"
    const val INSUFFICIENT_QUALITY = "INSUFFICIENT_QUALITY"
    const val NOT_RUN = "NOT_RUN"
}

class FaceCorrelationCalibrationStore(private val context: Context) {
    private val file = File(context.filesDir, CALIBRATION_FILE_NAME)

    fun getThresholds(): FaceCorrelationThresholds {
        val imported = if (file.isFile) {
            runCatching { parse(file.readText()) }
                .getOrNull()
                ?.takeIf(::matchesPinnedPipeline)
        } else null
        return imported ?: REFERENCE_THRESHOLDS
    }

    fun hasMeasuredCalibration(): Boolean = getThresholds().measured

    fun importCalibration(uri: Uri): FaceCorrelationThresholds {
        val json = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().use { it.readText() }
        } ?: error("Unable to read face-correlation calibration file.")
        val thresholds = parse(json)
        require(thresholds.measured) {
            "Calibration must include measured pair counts and error rates."
        }
        require(matchesPinnedPipeline(thresholds)) {
            "Calibration does not match the installed YuNet/SFace hashes or preprocessing version."
        }
        val temp = File(context.filesDir, "$CALIBRATION_FILE_NAME.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (file.exists()) file.delete()
            check(temp.renameTo(file)) { "Unable to store face-correlation calibration." }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return thresholds
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    companion object {
        const val CALIBRATION_FILE_NAME = "face-correlation-calibration.json"

        val REFERENCE_THRESHOLDS = FaceCorrelationThresholds(
            reviewThreshold = 0.363f,
            highSimilarityThreshold = 0.550f,
            source = "OpenCV SFace reference cosine operating point + conservative Dossier high band",
            sfaceSha256 = FaceCorrelationModelPack.SFACE.sha256,
            yunetSha256 = FaceCorrelationModelPack.YUNET.sha256,
            pipelineVersion = FaceCorrelationModelPack.PIPELINE_VERSION,
            positivePairCount = 0,
            negativePairCount = 0,
            reviewFalseMatchRate = null,
            highFalseMatchRate = null,
            reviewTrueMatchRate = null,
            highTrueMatchRate = null,
            measured = false
        )

        fun parse(json: String): FaceCorrelationThresholds {
            val root = Json.parseToJsonElement(json).jsonObject
            val review = root.float("reviewThreshold")
                ?: root.float("review_threshold")
                ?: error("Calibration is missing reviewThreshold.")
            val high = root.float("highSimilarityThreshold")
                ?: root.float("high_similarity_threshold")
                ?: root.float("samePersonThreshold")
                ?: error("Calibration is missing highSimilarityThreshold.")
            val source = root.string("source") ?: "Imported YuNet/SFace calibration"
            val sfaceSha = root.string("sfaceSha256")
                ?: root.string("sface_sha256")
                ?: root.string("modelSha256")
                ?: error("Calibration is missing sfaceSha256.")
            val yunetSha = root.string("yunetSha256")
                ?: root.string("yunet_sha256")
                ?: error("Calibration is missing yunetSha256.")
            val pipeline = root.string("pipelineVersion")
                ?: root.string("pipeline_version")
                ?: error("Calibration is missing pipelineVersion.")
            val positiveCount = root.int("positivePairCount")
                ?: root.int("positive_pair_count")
                ?: 0
            val negativeCount = root.int("negativePairCount")
                ?: root.int("negative_pair_count")
                ?: 0
            val reviewFmr = root.float("reviewFalseMatchRate")
                ?: root.float("review_false_match_rate")
                ?: root.float("reviewFalseAcceptRate")
            val highFmr = root.float("highFalseMatchRate")
                ?: root.float("high_false_match_rate")
                ?: root.float("samePersonFalseAcceptRate")
            val reviewTmr = root.float("reviewTrueMatchRate")
                ?: root.float("review_true_match_rate")
                ?: root.float("reviewTrueAcceptRate")
            val highTmr = root.float("highTrueMatchRate")
                ?: root.float("high_true_match_rate")
                ?: root.float("samePersonTrueAcceptRate")
            val measured = positiveCount > 0 && negativeCount > 0 &&
                reviewFmr != null && highFmr != null && reviewTmr != null && highTmr != null
            return FaceCorrelationThresholds(
                reviewThreshold = review,
                highSimilarityThreshold = high,
                source = source,
                sfaceSha256 = sfaceSha.lowercase(),
                yunetSha256 = yunetSha.lowercase(),
                pipelineVersion = pipeline,
                positivePairCount = positiveCount,
                negativePairCount = negativeCount,
                reviewFalseMatchRate = reviewFmr,
                highFalseMatchRate = highFmr,
                reviewTrueMatchRate = reviewTmr,
                highTrueMatchRate = highTmr,
                measured = measured
            )
        }

        private fun matchesPinnedPipeline(thresholds: FaceCorrelationThresholds): Boolean =
            thresholds.sfaceSha256.equals(FaceCorrelationModelPack.SFACE.sha256, true) &&
                thresholds.yunetSha256.equals(FaceCorrelationModelPack.YUNET.sha256, true) &&
                thresholds.pipelineVersion == FaceCorrelationModelPack.PIPELINE_VERSION

        private fun JsonObject.float(name: String): Float? = this[name]?.jsonPrimitive?.floatOrNull
        private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
        private fun JsonObject.string(name: String): String? =
            this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    }
}
