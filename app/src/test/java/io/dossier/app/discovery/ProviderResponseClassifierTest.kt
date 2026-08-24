package io.dossier.app.discovery

import io.dossier.app.domain.discovery.ExistenceRules
import io.dossier.app.domain.discovery.ProviderCategory
import io.dossier.app.domain.discovery.ProviderDefinition
import io.dossier.app.domain.discovery.ProviderResponseClassifier
import io.dossier.app.domain.discovery.ProviderResponseObservation
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.QueryCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderResponseClassifierTest {
    private val provider = ProviderDefinition(
        id = "example",
        displayName = "Example",
        category = ProviderCategory.Social,
        profileUrlTemplate = "https://example.test/{username}",
        queryCapabilities = setOf(QueryCapability.Username),
        existenceRules = ExistenceRules(
            requiredStatus = setOf(200),
            notFoundStatus = setOf(404, 410),
            requiredText = listOf("profile"),
            softNotFoundText = listOf("user not found"),
            authenticationText = listOf("sign in to continue"),
            challengeText = listOf("verify you are human")
        )
    )

    @Test
    fun presentPageStillOnlyMeansProviderPageExists() {
        val decision = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = "https://example.test/sample_user",
                finalUrl = "https://example.test/sample_user",
                bodyText = "Public profile for sample_user"
            )
        )
        assertEquals(ProviderVerificationState.Present, decision.state)
    }

    @Test
    fun softNotFoundWinsOverHttp200() {
        val decision = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = "https://example.test/missing_user",
                bodyText = "Profile — user not found"
            )
        )
        assertEquals(ProviderVerificationState.SoftNotFound, decision.state)
    }

    @Test
    fun authenticationAndChallengesAreNotAbsence() {
        val auth = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(200, "https://example.test/sample_user", bodyText = "Sign in to continue")
        )
        val challenge = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(200, "https://example.test/sample_user", bodyText = "Verify you are human")
        )
        assertEquals(ProviderVerificationState.AuthenticationRequired, auth.state)
        assertEquals(ProviderVerificationState.AutomationChallenged, challenge.state)
    }

    @Test
    fun externalRedirectCannotBecomePresent() {
        val decision = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = "https://example.test/sample_user",
                finalUrl = "https://login.example.invalid/start",
                bodyText = "Profile"
            )
        )
        assertEquals(ProviderVerificationState.RedirectedOutsideProvider, decision.state)
    }

    @Test
    fun arbitrarySubdomainRedirectIsRejectedByDefault() {
        val decision = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = "https://example.test/sample_user",
                finalUrl = "https://cdn.example.test/sample_user",
                bodyText = "Profile"
            )
        )

        assertEquals(ProviderVerificationState.RedirectedOutsideProvider, decision.state)
        assertFalse(
            ProviderResponseClassifier.sameProviderHost(
                "https://example.test/sample_user",
                "https://cdn.example.test/sample_user"
            )
        )
    }

    @Test
    fun explicitlyApprovedProviderAliasMayReceiveRedirect() {
        val approvedProvider = provider.copy(approvedHosts = setOf("cdn.example.test"))
        val decision = ProviderResponseClassifier.classify(
            approvedProvider,
            ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = "https://example.test/sample_user",
                finalUrl = "https://cdn.example.test/sample_user",
                bodyText = "Profile"
            )
        )

        assertEquals(ProviderVerificationState.Present, decision.state)
        assertTrue(
            ProviderResponseClassifier.sameProviderHost(
                "https://example.test/sample_user",
                "https://cdn.example.test/sample_user",
                setOf("example.test", "cdn.example.test")
            )
        )
    }

    @Test
    fun httpsProviderCannotBeDowngradedByRedirect() {
        val decision = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = "https://example.test/sample_user",
                finalUrl = "http://example.test/sample_user",
                bodyText = "Profile"
            )
        )

        assertEquals(ProviderVerificationState.RedirectedOutsideProvider, decision.state)
        assertFalse(
            ProviderResponseClassifier.sameProviderHost(
                "https://example.test/sample_user",
                "http://example.test/sample_user"
            )
        )
    }

    @Test
    fun explicitNotFoundStatusIsNotFound() {
        val decision = ProviderResponseClassifier.classify(
            provider,
            ProviderResponseObservation(404, "https://example.test/missing_user", bodyText = "missing")
        )
        assertEquals(ProviderVerificationState.NotFound, decision.state)
    }
}
