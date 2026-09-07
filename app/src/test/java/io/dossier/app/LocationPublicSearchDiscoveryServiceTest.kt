package io.dossier.app

import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Query-plan coverage for context-scoped corroborated location pivots. */
class LocationPublicSearchDiscoveryServiceTest {

    @Test
    fun locationQueriesAlwaysPairTheObservedPlaceWithAuthorizedContext() {
        val location = corroboratedLocation()
        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(
                fullName = "Jane Example",
                organizations = listOf("Example Org"),
                usernames = listOf("sample_user")
            ),
            typedSeeds = listOf(location)
        )

        val locationEntries = plan.filter { it.pivotSeedKind == TypedSeedKind.Location }

        assertTrue("A corroborated location with identity context should produce queries", locationEntries.isNotEmpty())
        assertTrue(locationEntries.all { it.query.contains("\"Example City\"") })
        assertTrue(
            locationEntries.all { entry ->
                listOf("\"Jane Example\"", "\"Example Org\"", "\"sample_user\"")
                    .any(entry.query::contains)
            }
        )
        assertFalse("Location-only queries would broaden attribution", locationEntries.any {
            it.query == "\"Example City\""
        })

        val first = locationEntries.first()
        assertEquals(location.exactValue, first.pivotExactValue)
        assertEquals(location.normalizedValue, first.pivotNormalizedValue)
        assertEquals(location.evidenceIds, first.pivotEvidenceIds)
        assertEquals(location.sourceUrl, first.pivotSourceUrl)
        assertEquals(location.discoveryPath, first.pivotDiscoveryPath)
    }

    @Test
    fun locationOnlyInputProducesNoLocationSearchPlan() {
        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(fullName = ""),
            typedSeeds = listOf(corroboratedLocation())
        )

        assertTrue("A location without authorized identity context must not be searched", plan.isEmpty())
    }

    @Test
    fun locationPivotDoesNotDropAuthorizedOriginalEmailOrPhoneQueries() {
        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(
                fullName = "",
                emails = listOf("jane@example.test"),
                phones = listOf("+1 (555) 123-4567")
            ),
            typedSeeds = listOf(corroboratedLocation())
        )

        assertTrue(
            "The authorized email must remain an exact query when no location context exists",
            plan.any { it.stage == "original-email" && it.query == "\"jane@example.test\"" }
        )
        assertTrue(
            "The authorized phone must remain an exact normalized query when no location context exists",
            plan.any { it.stage == "original-phone" && it.query == "\"15551234567\"" }
        )
        assertTrue(
            "A location without name/organization/handle context must not emit a location query",
            plan.none { it.pivotSeedKind == TypedSeedKind.Location }
        )
    }

    @Test
    fun mixedLocationAndIndependentTypedSeedsKeepBothBoundedQueryFamilies() {
        val location = corroboratedLocation()
        val email = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "jane@example.test",
            exactValue = "jane@example.test",
            normalizedValue = "jane@example.test",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("email-evidence-1"),
            sourceUrl = "https://profile.example.test/contact"
        )

        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(fullName = "Jane Example"),
            typedSeeds = listOf(location, email)
        )

        val locationEntries = plan.filter { it.pivotSeedKind == TypedSeedKind.Location }
        val emailEntries = plan.filter { it.pivotSeedKind == TypedSeedKind.Email }

        assertTrue("Mixed plans should retain context-scoped location queries", locationEntries.isNotEmpty())
        assertTrue(locationEntries.all { it.query.contains("\"Example City\"") })
        assertTrue(locationEntries.all { it.query.contains("\"Jane Example\"") })
        assertTrue("Mixed plans should retain independent email queries", emailEntries.isNotEmpty())
        assertTrue(emailEntries.any { it.query == "\"jane@example.test\"" })
    }

    @Test
    fun locationSeedIsReservedWhenEarlierSafeSeedsFillDefaultSeedCap() {
        val earlierSeeds = (1..4).map { index ->
            TypedSeed(
                kind = TypedSeedKind.Email,
                value = "jane$index@example.test",
                exactValue = "jane$index@example.test",
                normalizedValue = "jane$index@example.test",
                isVerified = true,
                depth = 1,
                evidenceState = EvidenceState.Verified,
                origin = TypedSeedOrigin.Evidence,
                sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
                evidenceIds = listOf("email-evidence-$index"),
                sourceUrl = "https://profile.example.test/contact/$index"
            )
        }

        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(fullName = "Jane Example"),
            typedSeeds = earlierSeeds + corroboratedLocation()
        )

        assertTrue(
            "A corroborated location must retain one bounded query slot even after four earlier seeds",
            plan.any { it.pivotSeedKind == TypedSeedKind.Location }
        )
    }

    @Test
    fun mixedLocationSeedsCannotStarveEveryIndependentSeedFamily() {
        val locations = (1..4).map { index ->
            corroboratedLocation().copy(
                exactValue = "Example City $index",
                value = "example city $index",
                normalizedValue = "example city $index",
                sourceUrl = "https://maps.example.test/example-city-$index"
            )
        }
        val email = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "jane@example.test",
            exactValue = "jane@example.test",
            normalizedValue = "jane@example.test",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("email-evidence"),
            sourceUrl = "https://profile.example.test/contact"
        )

        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(fullName = "Jane Example"),
            typedSeeds = locations + email
        )

        assertTrue("At least one location pivot should remain eligible", plan.any {
            it.pivotSeedKind == TypedSeedKind.Location
        })
        assertTrue("An independent email pivot must retain a bounded slot", plan.any {
            it.pivotSeedKind == TypedSeedKind.Email && it.query == "\"jane@example.test\""
        })
    }

    @Test
    fun defaultSeedCapRetainsEmailAndPhoneAlongsideLocations() {
        val locations = (1..4).map { index ->
            corroboratedLocation().copy(
                exactValue = "Example City $index",
                value = "example city $index",
                normalizedValue = "example city $index",
                sourceUrl = "https://maps.example.test/example-city-$index"
            )
        }
        val email = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "jane@example.test",
            exactValue = "jane@example.test",
            normalizedValue = "jane@example.test",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("email-default-evidence"),
            sourceUrl = "https://profile.example.test/contact"
        )
        val phone = TypedSeed(
            kind = TypedSeedKind.Phone,
            value = "15551234567",
            exactValue = "+1 (555) 123-4567",
            normalizedValue = "15551234567",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("phone-default-evidence"),
            sourceUrl = "https://profile.example.test/contact"
        )

        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(fullName = "Jane Example"),
            typedSeeds = locations + email + phone
        )

        assertTrue("Default plans retain the exact email pivot", plan.any {
            it.pivotSeedKind == TypedSeedKind.Email && it.query == "\"jane@example.test\""
        })
        assertTrue("Default plans retain the exact phone pivot", plan.any {
            it.pivotSeedKind == TypedSeedKind.Phone && it.query == "\"+1 (555) 123-4567\""
        })
        assertTrue("Default plans retain a scoped location pivot", plan.any {
            it.pivotSeedKind == TypedSeedKind.Location && it.query.contains("\"Jane Example\"")
        })
    }

    @Test
    fun deepBudgetRetainsEmailAndPhoneWhenManyLocationContextsFillThePlan() {
        val locations = (1..6).map { index ->
            corroboratedLocation().copy(
                exactValue = "Example City $index",
                value = "example city $index",
                normalizedValue = "example city $index",
                sourceUrl = "https://maps.example.test/example-city-$index"
            )
        }
        val email = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "jane@example.test",
            exactValue = "jane@example.test",
            normalizedValue = "jane@example.test",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("email-evidence"),
            sourceUrl = "https://profile.example.test/contact"
        )
        val phone = TypedSeed(
            kind = TypedSeedKind.Phone,
            value = "15551234567",
            exactValue = "+1 (555) 123-4567",
            normalizedValue = "15551234567",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("phone-evidence"),
            sourceUrl = "https://profile.example.test/contact"
        )
        val input = IdentityInput(
            fullName = "Jane Example",
            organizations = listOf("Example Org 1", "Example Org 2", "Example Org 3", "Example Org 4"),
            usernames = (1..8).map { "jane_example_$it" }
        )

        val fullPlan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = input,
            deepResearch = true,
            typedSeeds = locations + email + phone
        )
        val boundedPlan = PublicSearchDiscoveryService.boundSearchQueryPlan(fullPlan, queryLimit = 40)

        assertEquals(40, boundedPlan.size)
        assertTrue(
            "The unbounded planner should reproduce the starvation shape used by this regression",
            fullPlan.take(40).none { entry ->
                entry.pivotSeedKind == TypedSeedKind.Email || entry.pivotSeedKind == TypedSeedKind.Phone
            }
        )
        assertTrue("Deep plans retain an independent email pivot within the cap", boundedPlan.any {
            it.pivotSeedKind == TypedSeedKind.Email && it.query == "\"jane@example.test\""
        })
        assertTrue("Deep plans retain an independent phone pivot within the cap", boundedPlan.any {
            it.pivotSeedKind == TypedSeedKind.Phone && it.query == "\"+1 (555) 123-4567\""
        })
        assertTrue("At least one scoped location query remains within the cap", boundedPlan.any {
            it.pivotSeedKind == TypedSeedKind.Location &&
                it.query.contains("\"Jane Example\"")
        })
    }

    @Test
    fun deepBudgetPrioritizesEmailAndPhoneAheadOfLeadingNameAndUsernameSeeds() {
        val leadingName = TypedSeed(
            kind = TypedSeedKind.Name,
            value = "Jane Example",
            exactValue = "Jane Example",
            normalizedValue = "Jane Example",
            origin = TypedSeedOrigin.UserInput,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED
        )
        val leadingUsername = TypedSeed(
            kind = TypedSeedKind.Username,
            value = "jane_example",
            exactValue = "jane_example",
            normalizedValue = "jane_example",
            origin = TypedSeedOrigin.UserInput,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED
        )
        val email = TypedSeed(
            kind = TypedSeedKind.Email,
            value = "jane@example.test",
            exactValue = "jane@example.test",
            normalizedValue = "jane@example.test",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("email-leading-evidence"),
            sourceUrl = "https://profile.example.test/contact"
        )
        val phone = TypedSeed(
            kind = TypedSeedKind.Phone,
            value = "15551234567",
            exactValue = "+1 (555) 123-4567",
            normalizedValue = "15551234567",
            isVerified = true,
            depth = 1,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
            evidenceIds = listOf("phone-leading-evidence"),
            sourceUrl = "https://profile.example.test/contact"
        )
        val locations = (1..6).map { index ->
            corroboratedLocation().copy(
                exactValue = "Example City $index",
                value = "example city $index",
                normalizedValue = "example city $index",
                sourceUrl = "https://maps.example.test/example-city-$index"
            )
        }
        val input = IdentityInput(
            fullName = "Jane Example",
            organizations = (1..4).map { "Example Org $it" },
            usernames = (1..8).map { "jane_example_$it" }
        )

        val fullPlan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = input,
            deepResearch = true,
            typedSeeds = listOf(leadingName, leadingUsername, email, phone) + locations
        )
        val boundedPlan = PublicSearchDiscoveryService.boundSearchQueryPlan(fullPlan, queryLimit = 40)

        assertTrue("Email must survive the bounded deep plan", boundedPlan.any {
            it.pivotSeedKind == TypedSeedKind.Email && it.query == "\"jane@example.test\""
        })
        assertTrue("Phone must survive the bounded deep plan", boundedPlan.any {
            it.pivotSeedKind == TypedSeedKind.Phone && it.query == "\"+1 (555) 123-4567\""
        })
        assertTrue("A scoped location query must remain in the bounded deep plan", boundedPlan.any {
            it.pivotSeedKind == TypedSeedKind.Location && it.query.contains("\"Jane Example\"")
        })
    }

    @Test
    fun weakLocationObservationIsNotAddedToTheTypedQueryPlan() {
        val visualGuess = corroboratedLocation().copy(
            evidenceState = EvidenceState.Candidate,
            isVerified = false,
            origin = TypedSeedOrigin.Candidate,
            locationEvidenceClass = ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS
        )
        val plan = PublicSearchDiscoveryService.buildSearchQueryPlan(
            input = IdentityInput(fullName = "Jane Example"),
            typedSeeds = listOf(visualGuess)
        )

        assertTrue(plan.none { it.pivotSeedKind == TypedSeedKind.Location })
        assertTrue(plan.none { it.query.contains("Example City", ignoreCase = true) })
    }

    private fun corroboratedLocation(): TypedSeed = TypedSeed(
        kind = TypedSeedKind.Location,
        value = "example city",
        exactValue = "Example City",
        normalizedValue = "example city",
        depth = 1,
        evidenceState = EvidenceState.Observed,
        origin = TypedSeedOrigin.Evidence,
        sourceClassification = ExposureSourceClassification.AUTHORIZED_API,
        evidenceIds = listOf("geo-evidence-1"),
        sourceUrl = "https://maps.example.test/example-city",
        discoveryPath = listOf("photo:synthetic-photo", "https://maps.example.test/example-city"),
        locationEvidenceClass = ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION
    )
}
