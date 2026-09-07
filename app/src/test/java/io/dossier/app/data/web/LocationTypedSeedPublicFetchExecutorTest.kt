package io.dossier.app.data.web

import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Executor coverage for bounded, context-scoped location searches. */
class LocationTypedSeedPublicFetchExecutorTest {

    private val scanId = io.dossier.app.domain.discovery.ScanId("location-seed-test")

    @Test
    fun corroboratedLocationSearchUsesAuthorizedContextAndRetainsPivotProvenance() = runBlocking {
        val seed = corroboratedLocation()
        val authorized = IdentityInput(
            fullName = "Jane Example",
            emails = listOf("jane@example.test"),
            phones = listOf("15550100100"),
            organizations = listOf("Example Org"),
            usernames = listOf("sample_user"),
            primaryUsername = "sample_user"
        )
        var seenInput: IdentityInput? = null
        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { pivot, scopedInput, _ ->
                assertEquals(TypedSeedKind.Location, pivot.kind)
                seenInput = scopedInput
                PublicSearchDiscoveryService.SearchOutcome.Success(
                    listOf(
                        PublicSearchDiscoveryService.PublicSearchResult(
                            title = "Jane Example at Example City",
                            snippet = "Example City profile for sample_user",
                            url = "https://profile.example.test/sample_user",
                            query = "\"Example City\" \"Jane Example\"",
                            source = "Fixture Search",
                            score = 0.74f,
                            pivotSeedKind = pivot.kind,
                            pivotExactValue = pivot.exactValue,
                            pivotNormalizedValue = pivot.normalizedValue,
                            pivotEvidenceIds = pivot.evidenceIds,
                            pivotSourceUrl = pivot.sourceUrl,
                            pivotDiscoveryPath = pivot.discoveryPath,
                            pivotStage = "typed-location-name"
                        )
                    )
                )
            }
        )

        val report = executor.executeDetailed(listOf(seed), authorized, scanId)

        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Completed, report.executions.single().state)
        assertEquals("Jane Example", seenInput?.fullName)
        assertEquals(listOf(seed.exactValue), seenInput?.locations)
        assertEquals(listOf("Example Org"), seenInput?.organizations)
        assertEquals(listOf("sample_user"), seenInput?.usernames)
        assertTrue(seenInput?.emails.orEmpty().isEmpty())
        assertTrue(seenInput?.phones.orEmpty().isEmpty())

        val evidence = report.evidence.single()
        assertEquals(EvidenceState.Candidate, evidence.state)
        assertEquals(FindingAttribution.Unconfirmed, evidence.attribution)
        assertTrue(seed.evidenceIds.all { it in evidence.supportingEvidenceIds })
        assertTrue(seed.discoveryPath.all { it in evidence.discoveryPath })
        assertTrue(evidence.discoveryPath.any { it == "stage:typed-location-name" })
        assertTrue(evidence.signals.any { it.contains("Pivot seed kind: Location") })
        assertTrue(evidence.signals.any { it.contains(seed.exactValue) })
    }

    @Test
    fun locationOnlyObservationIsUnavailableWithoutCallingSearchAdapter() = runBlocking {
        val calls = AtomicInteger(0)
        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { _, _, _ ->
                calls.incrementAndGet()
                PublicSearchDiscoveryService.SearchOutcome.Success(emptyList())
            }
        )

        val report = executor.executeDetailed(
            seeds = listOf(corroboratedLocation()),
            input = IdentityInput(fullName = ""),
            scanId = scanId
        )

        assertEquals(0, calls.get())
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.single().state)
        assertTrue(report.executions.single().reason.orEmpty().contains("authorized", ignoreCase = true))
        assertTrue(report.evidence.single().state == EvidenceState.Unavailable)
    }

    @Test
    fun visualLocationGuessRemainsUnavailableAndIsNeverSearched() = runBlocking {
        val calls = AtomicInteger(0)
        val visualGuess = corroboratedLocation().copy(
            evidenceState = EvidenceState.Candidate,
            isVerified = false,
            origin = TypedSeedOrigin.Candidate,
            locationEvidenceClass = ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS
        )
        val executor = TypedSeedPublicFetchExecutor(
            searchOutcomeSearcher = { _, _, _ ->
                calls.incrementAndGet()
                PublicSearchDiscoveryService.SearchOutcome.Success(emptyList())
            }
        )

        val report = executor.executeDetailed(
            seeds = listOf(visualGuess),
            input = IdentityInput(fullName = "Jane Example"),
            scanId = scanId
        )

        assertEquals(0, calls.get())
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Unavailable, report.executions.single().state)
        assertFalse(report.executions.single().fetchAttempted)
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
