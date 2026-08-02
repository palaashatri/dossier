package io.dossier.app.data.face

import android.content.Context

/**
 * Explicit consent gate for cross-photo face correlation.
 *
 * Consent is granted only after the user chooses to install the verified
 * YuNet/SFace pack from the Models screen. Removing the pack revokes consent.
 * Merely selecting a selfie never enables biometric-derived correlation.
 */
class FaceCorrelationConsentStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun hasConsent(): Boolean =
        preferences.getBoolean(KEY_CONSENT, false) &&
            preferences.getString(KEY_PIPELINE_VERSION, null) == FaceCorrelationModelPack.PIPELINE_VERSION

    fun grantForInstalledPipeline() {
        preferences.edit()
            .putBoolean(KEY_CONSENT, true)
            .putString(KEY_PIPELINE_VERSION, FaceCorrelationModelPack.PIPELINE_VERSION)
            .apply()
    }

    fun revoke() {
        preferences.edit()
            .remove(KEY_CONSENT)
            .remove(KEY_PIPELINE_VERSION)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "face_correlation_consent"
        private const val KEY_CONSENT = "consented"
        private const val KEY_PIPELINE_VERSION = "pipeline_version"
    }
}
