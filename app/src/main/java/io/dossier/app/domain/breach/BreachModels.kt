package io.dossier.app.domain.breach

data class PasswordExposureResult(
    val label: String,
    val isPwned: Boolean,
    val occurrenceCount: Int,
    val sha1Prefix: String,
    val error: String? = null
)

data class EmailBreach(
    val name: String,
    val title: String,
    val domain: String,
    val breachDate: String?,
    val dataClasses: List<String>
)

data class EmailExposureResult(
    val email: String,
    val breaches: List<EmailBreach>,
    val publicEvidence: List<PublicEmailEvidence>,
    val error: String? = null
)

data class PublicEmailEvidence(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String,
    val confidence: Float
)
