package io.dossier.app.domain.discovery

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
enum class ProviderVerificationState {
    Present,
    NotFound,
    SoftNotFound,
    AuthenticationRequired,
    AutomationChallenged,
    RateLimited,
    Timeout,
    NetworkUnavailable,
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

        if (containsGlobalChallenge(normalizedText)) {
            return ProviderResponseDecision(
                ProviderVerificationState.AutomationChallenged,
                "Provider returned an automation/human-verification challenge"
            )
        }

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
        if (status == 429) {
            return ProviderResponseDecision(
                ProviderVerificationState.RateLimited,
                "Provider rate limit is active"
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
        if (finalUrl != null) {
            val approvedHosts = approvedHosts(definition, observation.requestedUrl)
            if (!sameProviderHost(observation.requestedUrl, finalUrl, approvedHosts)) {
                return ProviderResponseDecision(
                    ProviderVerificationState.RedirectedOutsideProvider,
                    "Final response host is not an approved host for this provider"
                )
            }
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

    /**
     * Redirects are exact-host by default. A provider may explicitly declare
     * aliases (for example an owned CDN) through [approvedHosts], but arbitrary
     * subdomains are never trusted implicitly.
     */
    internal fun sameProviderHost(
        first: String,
        second: String,
        approvedHosts: Set<String> = emptySet()
    ): Boolean {
        val firstScheme = scheme(first) ?: return false
        val secondScheme = scheme(second) ?: return false
        if (firstScheme !in HTTP_SCHEMES || secondScheme !in HTTP_SCHEMES) return false
        if (firstScheme == "https" && secondScheme != "https") return false
        val firstHost = host(first)
        val secondHost = host(second)
        if (firstHost.isNullOrBlank() || secondHost.isNullOrBlank()) return false
        if (approvedHosts.isEmpty()) return firstHost == secondHost
        val approved = approvedHosts.mapNotNull(::hostValue).toSet()
        return firstHost in approved && secondHost in approved
    }

    private fun approvedHosts(
        definition: ProviderDefinition,
        requestedUrl: String
    ): Set<String> {
        val explicit = definition.approvedHosts.mapNotNull(::hostValue).toSet()
        if (explicit.isNotEmpty()) return explicit + setOfNotNull(host(requestedUrl))

        // A template host is declarative provider metadata. Replace path/query
        // tokens before parsing so a normal profile provider gets its exact
        // configured host without trusting arbitrary redirect subdomains.
        val templateHost = definition.profileUrlTemplate
            ?.replace("{username}", "probe")
            ?.let(::host)
        return setOfNotNull(templateHost, host(requestedUrl))
    }

    private fun host(value: String): String? = runCatching {
        URI(value).host?.let(::hostValue)
    }.getOrNull()

    private fun scheme(value: String): String? = runCatching {
        URI(value).scheme?.lowercase()
    }.getOrNull()

    private fun hostValue(value: String): String? {
        val normalized = value.trim().lowercase().removePrefix("www.")
        if (normalized.isBlank() || normalized.contains('/') || normalized.contains(':')) return null
        return normalized
    }

    private fun containsGlobalChallenge(body: String): Boolean {
        val hardMarkers = listOf("cf-challenge", "g-recaptcha", "h-captcha", "data-sitekey")
        if (hardMarkers.any(body::contains)) return true
        if (body.length > 12_000) return false
        return listOf(
            "checking your browser",
            "verify you are human",
            "are you a robot",
            "unusual traffic",
            "attention required"
        ).any(body::contains)
    }

    private val HTTP_SCHEMES = setOf("http", "https")
}
