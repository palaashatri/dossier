package io.dossier.app.data.face

/**
 * Process-local execution choice for the current scan.
 *
 * Persistent consent permits the installed pipeline to be offered. This policy
 * determines whether the current scan may actually invoke it. It is deliberately
 * not persisted, so a later scan must choose again when a selfie is supplied.
 */
object FaceCorrelationSessionPolicy {
    @Volatile
    private var strongCorrelationEnabled: Boolean = false

    fun useStrongCorrelation() {
        strongCorrelationEnabled = true
    }

    fun useBasicMatching() {
        strongCorrelationEnabled = false
    }

    fun isStrongCorrelationEnabled(): Boolean = strongCorrelationEnabled
}
