package io.dossier.app.data.face

import kotlin.math.floor

data class FaceEmbeddingCalibrationSample(
    val score: Float,
    val samePerson: Boolean
)

object FaceEmbeddingCalibrationCalculator {
    fun calibrate(
        samples: List<FaceEmbeddingCalibrationSample>,
        modelSha256: String,
        source: String,
        reviewMaxFalseAcceptRate: Float = 0.05f,
        samePersonMaxFalseAcceptRate: Float = 0.001f
    ): FaceEmbeddingThresholds {
        require(samples.isNotEmpty()) { "Calibration samples are empty." }
        require(reviewMaxFalseAcceptRate in 0f..1f) { "Review FAR target must be between 0 and 1." }
        require(samePersonMaxFalseAcceptRate in 0f..1f) {
            "Same-person FAR target must be between 0 and 1."
        }
        require(samePersonMaxFalseAcceptRate <= reviewMaxFalseAcceptRate) {
            "Same-person FAR target must be at least as strict as review FAR target."
        }

        val positiveScores = samples.filter { it.samePerson }.map { it.score }.also(::requireValidScores)
        val negativeScores = samples.filterNot { it.samePerson }.map { it.score }.also(::requireValidScores)
        require(positiveScores.isNotEmpty()) { "Calibration requires positive pairs." }
        require(negativeScores.isNotEmpty()) { "Calibration requires negative pairs." }

        val reviewThreshold = thresholdForMaxFalseAcceptRate(negativeScores, reviewMaxFalseAcceptRate)
        val samePersonThreshold = thresholdForMaxFalseAcceptRate(negativeScores, samePersonMaxFalseAcceptRate)
        return FaceEmbeddingThresholds(
            reviewThreshold = reviewThreshold,
            samePersonThreshold = samePersonThreshold,
            source = source,
            modelSha256 = modelSha256,
            positivePairCount = positiveScores.size,
            negativePairCount = negativeScores.size,
            reviewFalseAcceptRate = falseAcceptRate(negativeScores, reviewThreshold),
            samePersonFalseAcceptRate = falseAcceptRate(negativeScores, samePersonThreshold),
            reviewTrueAcceptRate = trueAcceptRate(positiveScores, reviewThreshold),
            samePersonTrueAcceptRate = trueAcceptRate(positiveScores, samePersonThreshold)
        )
    }

    private fun thresholdForMaxFalseAcceptRate(negativeScores: List<Float>, maxFalseAcceptRate: Float): Float {
        val allowedFalseAccepts = floor(maxFalseAcceptRate * negativeScores.size).toInt()
            .coerceIn(0, negativeScores.size)
        if (allowedFalseAccepts == negativeScores.size) return -1f

        val sortedDescending = negativeScores.sortedDescending()
        val boundary = sortedDescending[allowedFalseAccepts]
        val threshold = Math.nextUp(boundary)
        require(threshold <= 1f) {
            "Requested false-accept target cannot be achieved for this score distribution."
        }
        return threshold
    }

    private fun falseAcceptRate(negativeScores: List<Float>, threshold: Float): Float =
        negativeScores.count { it >= threshold }.toFloat() / negativeScores.size

    private fun trueAcceptRate(positiveScores: List<Float>, threshold: Float): Float =
        positiveScores.count { it >= threshold }.toFloat() / positiveScores.size

    private fun requireValidScores(scores: List<Float>) {
        require(scores.all { it.isFinite() && it in -1f..1f }) {
            "Calibration scores must be finite cosine values between -1 and 1."
        }
    }
}
