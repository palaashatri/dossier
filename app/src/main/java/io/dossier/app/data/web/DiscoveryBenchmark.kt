package io.dossier.app.data.web

/**
 * Small, dependency-free benchmark evaluator used by regression fixtures and future
 * device/network corpora. Reliability claims must be derived from these metrics rather
 * than from the number of supported platform templates.
 */
object DiscoveryBenchmark {
    enum class Expected { BELONGS, DOES_NOT_BELONG, UNVERIFIABLE }
    enum class Actual { VERIFIED, PLAUSIBLE, NOT_FOUND, UNVERIFIABLE }

    data class Observation(
        val id: String,
        val expected: Expected,
        val actual: Actual
    )

    data class Metrics(
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        val trueNegatives: Int,
        val correctlyUnverifiable: Int,
        val total: Int
    ) {
        val precision: Double
            get() = ratio(truePositives, truePositives + falsePositives)
        val recall: Double
            get() = ratio(truePositives, truePositives + falseNegatives)
        val specificity: Double
            get() = ratio(trueNegatives, trueNegatives + falsePositives)
        val unverifiableAccuracy: Double
            get() = ratio(correctlyUnverifiable, total)
        val f1: Double
            get() = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)

        private fun ratio(numerator: Int, denominator: Int): Double =
            if (denominator == 0) 1.0 else numerator.toDouble() / denominator.toDouble()
    }

    fun evaluate(observations: List<Observation>): Metrics {
        var tp = 0
        var fp = 0
        var fn = 0
        var tn = 0
        var unverifiable = 0

        observations.forEach { observation ->
            when (observation.expected) {
                Expected.BELONGS -> when (observation.actual) {
                    Actual.VERIFIED -> tp++
                    Actual.PLAUSIBLE, Actual.NOT_FOUND, Actual.UNVERIFIABLE -> fn++
                }
                Expected.DOES_NOT_BELONG -> when (observation.actual) {
                    Actual.VERIFIED -> fp++
                    Actual.PLAUSIBLE, Actual.NOT_FOUND, Actual.UNVERIFIABLE -> tn++
                }
                Expected.UNVERIFIABLE -> {
                    if (observation.actual == Actual.UNVERIFIABLE) unverifiable++
                    if (observation.actual == Actual.VERIFIED) fp++
                }
            }
        }

        return Metrics(
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            trueNegatives = tn,
            correctlyUnverifiable = unverifiable,
            total = observations.size
        )
    }
}
