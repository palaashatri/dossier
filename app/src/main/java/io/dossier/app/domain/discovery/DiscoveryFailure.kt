package io.dossier.app.domain.discovery

import kotlinx.serialization.Serializable

/** User-safe structured failure taxonomy. Never encode sensitive request data. */
@Serializable
sealed class DiscoveryFailure {
    abstract val message: String

    @Serializable
    data class NetworkUnavailable(override val message: String = "Network unavailable") : DiscoveryFailure()

    @Serializable
    data class Timeout(override val message: String = "Provider request timed out") : DiscoveryFailure()

    @Serializable
    data class RateLimited(
        override val message: String = "Provider rate limit reached",
        val retryAfterSeconds: Long? = null
    ) : DiscoveryFailure()

    @Serializable
    data class AuthenticationRequired(override val message: String = "Authentication is required") : DiscoveryFailure()

    @Serializable
    data class UnsupportedAutomation(override val message: String = "Provider does not support this automated public check") : DiscoveryFailure()

    @Serializable
    data class ProviderChanged(override val message: String = "Provider response no longer matches validated rules") : DiscoveryFailure()

    @Serializable
    data class ParseFailure(override val message: String = "Provider response could not be parsed safely") : DiscoveryFailure()

    @Serializable
    data class InvalidCandidate(override val message: String = "Candidate did not pass verification rules") : DiscoveryFailure()

    @Serializable
    data class PolicyRestriction(override val message: String = "Provider policy or robots restriction prevents this check") : DiscoveryFailure()

    @Serializable
    data class RemoteServiceUnavailable(override val message: String = "Remote service unavailable") : DiscoveryFailure()

    @Serializable
    data class ModelUnavailable(override val message: String = "Required local model unavailable") : DiscoveryFailure()

    @Serializable
    data class StorageFailure(override val message: String = "Local storage operation failed") : DiscoveryFailure()

    @Serializable
    data class Cancelled(override val message: String = "Scan cancelled") : DiscoveryFailure()
}

fun ProviderVerificationState.toFailureOrNull(): DiscoveryFailure? = when (this) {
    ProviderVerificationState.Present,
    ProviderVerificationState.NotFound,
    ProviderVerificationState.SoftNotFound -> null
    ProviderVerificationState.AuthenticationRequired -> DiscoveryFailure.AuthenticationRequired()
    ProviderVerificationState.AutomationChallenged -> DiscoveryFailure.UnsupportedAutomation(
        "Provider returned a human-verification or automation challenge"
    )
    ProviderVerificationState.RedirectedOutsideProvider -> DiscoveryFailure.InvalidCandidate(
        "Provider redirected outside the expected public source"
    )
    ProviderVerificationState.UnexpectedStatus -> DiscoveryFailure.ProviderChanged()
    ProviderVerificationState.InvalidResponse -> DiscoveryFailure.ParseFailure()
}
