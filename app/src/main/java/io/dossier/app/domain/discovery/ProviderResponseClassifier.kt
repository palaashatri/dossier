package io.dossier.app.domain.discovery

import java.net.URI

enum class ProviderVerificationState {
    Present,
    NotFound,
    SoftNotFound,
    AuthenticationRequired,
    AutomationChallenged,
    RedirectedOutsideProvider,
    UnexpectedStatus,
    InvalidResponse
}

data class ProviderResponseObservation(
    val statusCode: Int?,
    val requestedUrl: String,
    val finalUrl: String? = null,
    val bodyText: String = ""
)

data class ProviderResponseDecision(
    val state: ProviderVerificationState,
    val explanation: String
)

/**
 * Deterministic provider-response classification shared by contract fixtures and
 * future generic fetch adapters. It deliberately decides page state only; it
 * does not claim the page belongs to the audited subject.
 */
object ProviderResponseClassifier {
    fun classify(
        definition: ProviderDefinition,
        observation: ProviderResponseObservation
    ): ProviderResponseDecision {
        val rules = definition.existenceRules
            ?: return ProviderResponseDecision(
                ProviderVerificationState.InvalidResponse,
                "Provider has no direct-page existence rules"
            )
        val status = observation.statusCode
            ?: return ProviderResponseDecision(ProviderVerificationState.InvalidResponse, "No HTTP status was recorded")
        val normalizedText = observation.bodyText.lowercase()

        if (rules.authenticationText.any { normalizedText.contains(it.lowercase()) }) {
            return ProviderResponseDecision(
                ProviderVerificationState.AuthenticationRequired,
                "Public response requires authentication"
            )
        }
        if (rules.challengeText.any { normalizedText.contains(it.lowercase()) }) {
            return ProviderResponseDecision(
                ProviderVerificationState.AutomationChallenged,
                "Provider returned an automation/human-verification challenge"
            )
        }
        if (status in rules.notFoundStatus) {
            return ProviderResponseDecision(ProviderVerificationState.NotFound, "HTTP $status matches not-found rules")
        }
        if (rules.softNotFoundText.any { normalizedText.contains(it.lowercase()) }) {
            return ProviderResponseDecision(
                ProviderVerificationState.SoftNotFound,
                "Response body matches a soft-not-found rule"
            )
        }

        val finalUrl = observation.finalUrl
        if (finalUrl != null && !sameProviderHost(observation.requestedUrl, finalUrl)) {
            return ProviderResponseDecision(
                ProviderVerificationState.RedirectedOutsideProvider,
                "Final response host differs from requested provider host"
            )
        }

        if (status !in rules.requiredStatus) {
            return ProviderResponseDecision(
                ProviderVerificationState.UnexpectedStatus,
                "HTTP $status is neither a configured success nor not-found status"
            )
        }
        if (rules.forbiddenText.any { normalizedText.contains(it.lowercase()) }) {
            return ProviderResponseDecision(
                ProviderVerificationState.InvalidResponse,
                "Response contains a configured forbidden marker"
            )
        }
        val missingRequired = rules.requiredText.firstOrNull { marker ->
            !normalizedText.contains(marker.lowercase())
        }
        if (missingRequired != null) {
            return ProviderResponseDecision(
                ProviderVerificationState.InvalidResponse,
                "Response is missing required marker"
            )
        }
        return ProviderResponseDecision(
            ProviderVerificationState.Present,
            "Provider page satisfies declarative existence rules; subject attribution still requires evidence"
        )
    }

    private fun sameProviderHost(first: String, second: String): Boolean {
        val firstHost = runCatching { URI(first).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val secondHost = runCatching { URI(second).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        if (firstHost.isNullOrBlank() || secondHost.isNullOrBlank()) return false
        return firstHost == secondHost || firstHost.endsWith(".$secondHost") || secondHost.endsWith(".$firstHost")
    }
}
