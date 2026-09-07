package io.dossier.app.discovery

import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.ExistenceRules
import io.dossier.app.domain.discovery.ProviderDefinition
import io.dossier.app.domain.discovery.ProviderResponseClassifier
import io.dossier.app.domain.discovery.ProviderResponseObservation
import io.dossier.app.domain.discovery.ProviderVerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic contract fixtures for every declarative catalog definition.
 *
 * These checks exercise response classification only. They do not perform
 * network requests and do not promote fixture outcomes to provider health or
 * identity evidence. Service definitions intentionally remain invalid for the
 * generic profile classifier until their dedicated adapters provide rules.
 */
class ProviderCatalogContractTest {

    private enum class FixtureKind {
        Present,
        Absent,
        SoftError,
        Redirect,
        Challenge,
        Malformed
    }

    @Test
    fun everyCatalogDefinitionHasDeterministicResponseContracts() {
        var checkedFixtures = 0
        var profileDefinitions = 0
        var serviceDefinitions = 0

        ProviderCatalogV2.definitions.forEach { definition ->
            val rules = definition.existenceRules
            if (rules == null) {
                serviceDefinitions++
            } else {
                profileDefinitions++
                assertTrue("${definition.id} must declare a not-found status", rules.notFoundStatus.isNotEmpty())
                assertTrue("${definition.id} must declare soft-not-found text", rules.softNotFoundText.isNotEmpty())
                assertTrue("${definition.id} must declare challenge text", rules.challengeText.isNotEmpty())
            }

            FixtureKind.entries.forEach { fixture ->
                val decision = ProviderResponseClassifier.classify(
                    definition = definition,
                    observation = fixtureObservation(definition, fixture)
                )
                assertEquals(
                    "${definition.id} $fixture fixture was classified unexpectedly",
                    expectedState(rules, fixture),
                    decision.state
                )
                checkedFixtures++
            }
        }

        assertEquals(ProviderCatalogV2.definitions.size, profileDefinitions + serviceDefinitions)
        assertTrue("The fixture harness must exercise profile definitions", profileDefinitions > 0)
        assertTrue("The fixture harness must exercise service definitions", serviceDefinitions > 0)
        assertEquals(ProviderCatalogV2.definitions.size * FixtureKind.entries.size, checkedFixtures)
    }

    private fun expectedState(rules: ExistenceRules?, fixture: FixtureKind): ProviderVerificationState {
        if (rules == null) return ProviderVerificationState.InvalidResponse
        return when (fixture) {
            FixtureKind.Present -> ProviderVerificationState.Present
            FixtureKind.Absent -> ProviderVerificationState.NotFound
            FixtureKind.SoftError -> ProviderVerificationState.SoftNotFound
            FixtureKind.Redirect -> ProviderVerificationState.RedirectedOutsideProvider
            FixtureKind.Challenge -> ProviderVerificationState.AutomationChallenged
            FixtureKind.Malformed -> ProviderVerificationState.InvalidResponse
        }
    }

    private fun fixtureObservation(
        definition: ProviderDefinition,
        fixture: FixtureKind
    ): ProviderResponseObservation {
        val requestedUrl = definition.profileUrlTemplate
            ?.replace("{username}", "fixture-user")
            ?: "https://fixture.invalid/${definition.id}"
        val rules = definition.existenceRules
        return when (fixture) {
            FixtureKind.Present -> ProviderResponseObservation(
                statusCode = rules?.requiredStatus?.firstOrNull() ?: 200,
                requestedUrl = requestedUrl,
                finalUrl = requestedUrl,
                bodyText = "fixture profile ${rules?.requiredText.orEmpty().joinToString(" ")}"
            )

            FixtureKind.Absent -> ProviderResponseObservation(
                statusCode = rules?.notFoundStatus?.firstOrNull() ?: 404,
                requestedUrl = requestedUrl,
                bodyText = "fixture missing page"
            )

            FixtureKind.SoftError -> ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = requestedUrl,
                bodyText = rules?.softNotFoundText?.firstOrNull() ?: "fixture soft error"
            )

            FixtureKind.Redirect -> ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = requestedUrl,
                finalUrl = "https://redirect.invalid/${definition.id}",
                bodyText = "fixture profile"
            )

            FixtureKind.Challenge -> ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = requestedUrl,
                bodyText = rules?.challengeText?.firstOrNull() ?: "verify you are human"
            )

            FixtureKind.Malformed -> ProviderResponseObservation(
                statusCode = null,
                requestedUrl = requestedUrl,
                bodyText = "fixture malformed response"
            )
        }
    }
}
