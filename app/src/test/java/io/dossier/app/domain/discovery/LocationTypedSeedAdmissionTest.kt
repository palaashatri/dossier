package io.dossier.app.domain.discovery

import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.ReverseImageLookupResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the narrow public-search admission of locations. */
class LocationTypedSeedAdmissionTest {

    @Test
    fun onlyCorroboratedPublicLocationEvidenceIsSearchExecutable() {
        val seed = corroboratedLocation()

        assertTrue(TypedSeedSafety.isSafePublicSearchSeed(seed))
        assertTrue(TypedSeedSafety.isSafeExecutableSeed(seed))

        val model = TypedSeedAdmissionModel()
        assertTrue(
            model.offer(
                kind = seed.kind,
                rawValue = seed.exactValue,
                depth = seed.depth,
                origin = seed.origin,
                evidenceState = seed.evidenceState,
                sourceClassification = seed.sourceClassification,
                evidenceIds = seed.evidenceIds,
                sourceUrl = seed.sourceUrl,
                discoveryPath = seed.discoveryPath,
                locationEvidenceClass = seed.locationEvidenceClass
            )
        )
        assertTrue(model.admittedSeeds.single().locationEvidenceClass ==
            ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION)
    }

    @Test
    fun weakVisualAndLocalOnlyLocationEvidenceCannotEnterPublicSearch() {
        val visualGuess = TypedSeed(
            kind = TypedSeedKind.Location,
            value = "example city",
            exactValue = "Example City",
            normalizedValue = "example city",
            evidenceState = EvidenceState.Candidate,
            origin = TypedSeedOrigin.Candidate,
            sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
            locationEvidenceClass = ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS
        )
        val likelyLocation = visualGuess.copy(
            locationEvidenceClass = ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION
        )
        val exactMetadata = TypedSeed(
            kind = TypedSeedKind.Location,
            value = "example city",
            exactValue = "Example City",
            normalizedValue = "example city",
            evidenceState = EvidenceState.Observed,
            origin = TypedSeedOrigin.LocalAnalysis,
            sourceClassification = ExposureSourceClassification.LOCAL_IMPORT,
            locationEvidenceClass = ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA
        )

        listOf(visualGuess, likelyLocation, exactMetadata).forEach { seed ->
            assertFalse(
                "${seed.locationEvidenceClass} must not become a public-search pivot",
                TypedSeedSafety.isSafePublicSearchSeed(seed)
            )
        }
    }

    @Test
    fun conflictingLocationEvidenceRemainsUnavailableToSearch() {
        val conflicting = corroboratedLocation().copy(
            evidenceState = EvidenceState.Conflicting,
            isVerified = false,
            locationEvidenceClass = ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING
        )

        assertFalse(TypedSeedSafety.isSafePublicSearchSeed(conflicting))
        assertFalse(TypedSeedSafety.isSafeExecutableSeed(conflicting))
    }

    @Test
    fun malformedCorroboratedLocationWithNonCanonicalNormalizationCannotEnterPublicSearch() {
        val malformed = corroboratedLocation().copy(
            value = "different city",
            normalizedValue = "different city"
        )

        assertFalse(TypedSeedSafety.isSafePublicSearchSeed(malformed))
        assertFalse(TypedSeedSafety.isSafeExecutableSeed(malformed))
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
