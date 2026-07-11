package io.dossier.app.data.face

data class FaceEmbeddingThresholds(
    val reviewThreshold: Float,
    val samePersonThreshold: Float,
    val source: String,
    val modelSha256: String,
    val positivePairCount: Int,
    val negativePairCount: Int,
    val reviewFalseAcceptRate: Float,
    val samePersonFalseAcceptRate: Float,
    val reviewTrueAcceptRate: Float,
    val samePersonTrueAcceptRate: Float
) {
    /** Canonical JSON consumed by [FaceEmbeddingCalibrationStore]. */
    fun toCalibrationJson(): String =
        buildString {
            appendLine("{")
            appendLine("""  "reviewThreshold": $reviewThreshold,""")
            appendLine("""  "samePersonThreshold": $samePersonThreshold,""")
            appendLine("""  "modelSha256": "${modelSha256.lowercase()}",""")
            appendLine("""  "positivePairCount": $positivePairCount,""")
            appendLine("""  "negativePairCount": $negativePairCount,""")
            appendLine("""  "reviewFalseAcceptRate": $reviewFalseAcceptRate,""")
            appendLine("""  "samePersonFalseAcceptRate": $samePersonFalseAcceptRate,""")
            appendLine("""  "reviewTrueAcceptRate": $reviewTrueAcceptRate,""")
            appendLine("""  "samePersonTrueAcceptRate": $samePersonTrueAcceptRate,""")
            appendLine("""  "source": ${jsonString(source)}""")
            append("}")
        }

    private fun jsonString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    init {
        require(reviewThreshold.isFinite()) { "Review threshold must be finite." }
        require(samePersonThreshold.isFinite()) { "Same-person threshold must be finite." }
        require(reviewFalseAcceptRate.isFinite()) { "Review false-accept rate must be finite." }
        require(samePersonFalseAcceptRate.isFinite()) { "Same-person false-accept rate must be finite." }
        require(reviewTrueAcceptRate.isFinite()) { "Review true-accept rate must be finite." }
        require(samePersonTrueAcceptRate.isFinite()) { "Same-person true-accept rate must be finite." }
        require(reviewThreshold in -1f..1f) { "Review threshold must be between -1 and 1." }
        require(samePersonThreshold in -1f..1f) { "Same-person threshold must be between -1 and 1." }
        require(reviewThreshold <= samePersonThreshold) {
            "Review threshold must be less than or equal to same-person threshold."
        }
        require(positivePairCount > 0) { "Calibration must include at least one positive pair." }
        require(negativePairCount > 0) { "Calibration must include at least one negative pair." }
        require(reviewFalseAcceptRate in 0f..1f) { "Review false-accept rate must be between 0 and 1." }
        require(samePersonFalseAcceptRate in 0f..1f) {
            "Same-person false-accept rate must be between 0 and 1."
        }
        require(reviewTrueAcceptRate in 0f..1f) { "Review true-accept rate must be between 0 and 1." }
        require(samePersonTrueAcceptRate in 0f..1f) {
            "Same-person true-accept rate must be between 0 and 1."
        }
        require(samePersonFalseAcceptRate <= reviewFalseAcceptRate) {
            "Same-person threshold must be at least as strict as review threshold."
        }
        require(samePersonTrueAcceptRate <= reviewTrueAcceptRate) {
            "Same-person true-accept rate cannot exceed review true-accept rate."
        }
        require(modelSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "Model SHA-256 must be a 64-character hex string."
        }
    }

    fun isReviewScore(score: Float): Boolean =
        score >= reviewThreshold

    fun isSamePersonScore(score: Float): Boolean =
        score >= samePersonThreshold
}
