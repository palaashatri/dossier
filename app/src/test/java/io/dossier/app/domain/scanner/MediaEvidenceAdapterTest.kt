package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.toExposureLedger
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSession
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaEvidenceAdapterTest {

    @After
    fun clearMediaSession() {
        MediaIntelligenceSession.clear()
    }

    @Test
    fun mediaLookupSeamForwardsScanOwnedBindingAndPreservesFailureIsolation() = runBlocking {
        val input = IdentityInput(
            fullName = "Jane Example",
            selfieUri = "content://example/photo"
        )
        val token = MediaIntelligenceSession.beginFor(input)
        var forwardedToken: String? = null
        var forwardedUri: String? = null
        var forwardedDeepResearch = false

        val result = ScanSession.lookupMediaForScan(
            input = input,
            deepResearch = true,
            bindingToken = token
        ) { uri, deepResearch, binding ->
            forwardedUri = uri
            forwardedDeepResearch = deepResearch
            forwardedToken = binding
            sampleResult()
        }

        assertNotNull(result)
        assertEquals(input.selfieUri, forwardedUri)
        assertEquals(token, forwardedToken)
        assertTrue(forwardedDeepResearch)

        val failed = ScanSession.lookupMediaForScan(
            input = input,
            deepResearch = false,
            bindingToken = token
        ) { _, _, _ -> error("provider unavailable") }

        assertEquals(null, failed)
    }

    @Test
    fun mediaLookupPropagatesCancellation() = runBlocking {
        val input = IdentityInput(
            fullName = "Jane Example",
            selfieUri = "content://example/photo"
        )
        val token = MediaIntelligenceSession.beginFor(input)

        try {
            ScanSession.lookupMediaForScan(
                input = input,
                deepResearch = false,
                bindingToken = token
            ) { _, _, _ -> throw CancellationException("cancelled") }
            fail("media cancellation must propagate to the durable scan")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled", cancelled.message)
        }
    }

    @Test
    fun mediaObservationsMapToLedgerWithoutPromotingVisualIdentity() {
        val retrievedAt = 900L
        val path = listOf("seed:photo", "reverse-image")
        val candidate = ReverseImageLookupResult.ImageCandidateProvenance(
            id = "candidate-1",
            title = "Public repost",
            imageUrl = "https://images.example.test/repost.jpg",
            sourcePageUrl = "https://pages.example.test/repost",
            source = "Synthetic index",
            acquisitionQuery = "Jane Example",
            comparedImageUrl = "https://images.example.test/repost-thumb.jpg",
            retrievedAtEpochMillis = 123L,
            comparisonScore = 0.94f,
            state = ReverseImageLookupResult.ImageCandidateState.Matched
        )
        val result = sampleResult().copy(
            gps = "12.345,67.890",
            extractedText = "Cafe Example",
            resolvedLocation = "Cafe Example, Example City",
            mapsUrl = "https://maps.example.test/?q=Cafe+Example",
            webEvidence = listOf(
                ReverseImageLookupResult.WebEvidence(
                    title = "Cafe Example",
                    snippet = "Public source page",
                    url = "https://directory.example.test/cafe",
                    origin = ReverseImageLookupResult.WebEvidenceOrigin.ImageSearch
                ),
                ReverseImageLookupResult.WebEvidence(
                    title = "OpenStreetMap coordinate corroboration",
                    snippet = "EXIF coordinates reverse-geocode to Cafe Example, Example City.",
                    url = "https://www.openstreetmap.org/?mlat=12.345&mlon=67.890",
                    origin = ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration
                )
            ),
            visualCandidates = listOf(candidate),
            visualMatches = listOf(
                ReverseImageLookupResult.VisualMatch(
                    title = "Public repost",
                    imageUrl = candidate.imageUrl,
                    sourcePageUrl = candidate.sourcePageUrl,
                    source = candidate.source,
                    similarity = 0.94f,
                    matchType = "exact-content",
                    evidence = "Whole-image match",
                    candidateId = candidate.id
                )
            )
        )

        val collection = result.toEvidenceCollection(
            discoveryPath = path,
            mediaSourceUri = "content://example/photo",
            retrievedAtEpochMillis = retrievedAt
        )
        val ledger = collection.toExposureLedger()

        assertEquals(1, collection.evidence.count { it.value == result.gps })
        val gps = collection.evidence.single { it.value == result.gps }
        assertEquals(EvidenceKind.Location, gps.kind)
        assertEquals(EvidenceReliability.LocalDerived, gps.reliability)
        assertEquals(EvidenceState.Observed, gps.state)
        assertEquals("content://example/photo", gps.sourceUrl)
        assertFalse(gps.sourceUrl == result.mapsUrl)
        assertEquals(path, gps.discoveryPath)
        assertEquals(retrievedAt, gps.retrievedAtEpochMillis)

        assertEquals(1, collection.evidence.count {
            it.kind == EvidenceKind.SensitiveSnippet && it.value == result.extractedText
        })
        val ocr = collection.evidence.single {
            it.kind == EvidenceKind.SensitiveSnippet && it.value == result.extractedText
        }
        assertEquals(EvidenceKind.SensitiveSnippet, ocr.kind)
        assertEquals(EvidenceState.Observed, ocr.state)

        assertEquals(1, collection.evidence.count {
            it.kind == EvidenceKind.PublicImageEvidence &&
                it.sourceUrl == "https://directory.example.test/cafe"
        })
        val web = collection.evidence.single {
            it.kind == EvidenceKind.PublicImageEvidence &&
                it.sourceUrl == "https://directory.example.test/cafe"
        }
        assertEquals(EvidenceState.Candidate, web.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, web.reliability)

        val geo = collection.evidence.single {
            it.kind == EvidenceKind.PublicSearchEvidence &&
                it.sourceUrl?.startsWith("https://www.openstreetmap.org/") == true
        }
        assertEquals(EvidenceKind.PublicSearchEvidence, geo.kind)
        assertEquals(EvidenceState.Observed, geo.state)
        assertEquals(EvidenceReliability.AuthoritativeApi, geo.reliability)

        val resolved = collection.evidence.single {
            it.kind == EvidenceKind.Location && it.value == result.resolvedLocation
        }
        assertEquals("https://www.openstreetmap.org/?mlat=12.345&mlon=67.890", resolved.sourceUrl)
        assertEquals(EvidenceState.Observed, resolved.state)

        assertEquals(1, collection.evidence.count { it.value == candidate.imageUrl })
        val imageUrl = collection.evidence.single { it.value == candidate.imageUrl }
        assertEquals(retrievedAt, imageUrl.retrievedAtEpochMillis)
        assertEquals(candidate.sourcePageUrl, imageUrl.sourceUrl)
        assertEquals(EvidenceState.Observed, imageUrl.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, imageUrl.reliability)
        assertEquals(path, imageUrl.discoveryPath)
        assertFalse(collection.evidence.any { it.id == candidate.id })

        val imageFact = ledger.facts.single { it.exactValue == candidate.imageUrl }
        assertEquals(candidate.imageUrl.lowercase(), imageFact.normalizedValue)
        assertEquals(retrievedAt, imageFact.firstObservedAtEpochMillis)
        assertEquals(retrievedAt, imageFact.lastObservedAtEpochMillis)
        assertEquals(path, imageFact.discoveryPath)

        assertTrue(collection.relationships.none { it.relation == "visual_match_observed" })
    }

    @Test
    fun candidateStatesAndPageRelationsRemainTruthful() {
        val page = "https://pages.example.test/observed"
        val indexed = candidateState("indexed", ReverseImageLookupResult.ImageCandidateState.Indexed)
        val noMatch = candidateState("no-match", ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch)
        val unavailable = candidateState(
            "unavailable",
            ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable
        )
        val observedPage = candidateState(
            "observed",
            ReverseImageLookupResult.ImageCandidateState.Matched
        ).copy(
            sourcePageUrl = page,
            accountLinkages = listOf(
                ReverseImageLookupResult.ImageAccountLinkage(
                    accountUrl = page,
                    basis = ReverseImageLookupResult.ImageAccountLinkageBasis.UserReviewed,
                    evidenceIds = listOf("review-evidence")
                )
            )
        )
        val result = sampleResult().copy(
            visualCandidates = listOf(indexed, noMatch, unavailable, observedPage),
            visualMatches = listOf(
                ReverseImageLookupResult.VisualMatch(
                    title = observedPage.title,
                    imageUrl = observedPage.imageUrl,
                    sourcePageUrl = page,
                    source = "fixture",
                    similarity = 0.91f,
                    matchType = "exact-content",
                    evidence = "whole-image comparison",
                    candidateId = observedPage.id
                )
            )
        )

        val collection = result.toEvidenceCollection(
            discoveryPath = listOf("seed:photo"),
            retrievedAtEpochMillis = 42L
        )
        fun state(id: String) = collection.evidence.first {
            it.value == "https://images.example.test/$id.jpg"
        }.state

        assertEquals(EvidenceState.Candidate, state("indexed"))
        assertEquals(EvidenceState.Rejected, state("no-match"))
        assertEquals(EvidenceState.Unavailable, state("unavailable"))
        assertEquals(EvidenceState.Observed, state("observed"))
        assertTrue(collection.relationships.none { it.fromValue == indexed.sourcePageUrl })

        val relation = collection.relationships.single { it.relation == "visual_match_observed" }
        assertTrue(relation.evidenceIds.contains("review-evidence"))
    }

    @Test
    fun matchOnlyRetainsExactImageUrlAndBoundsMetadata() {
        val imageUrl = "https://images.example.test/match-only.jpg"
        val match = ReverseImageLookupResult.VisualMatch(
            title = "Match-only result",
            imageUrl = imageUrl,
            sourcePageUrl = "https://pages.example.test/match-only",
            source = "fixture",
            similarity = 0.88f,
            matchType = "near-duplicate",
            evidence = "evidence-${"x".repeat(5_000)}"
        )
        val collection = sampleResult().copy(visualMatches = listOf(match)).toEvidenceCollection(
            discoveryPath = listOf("seed:photo")
        )

        val image = collection.evidence.single { it.value == imageUrl }
        assertEquals(match.sourcePageUrl, image.sourceUrl)
        assertEquals(EvidenceState.Observed, image.state)
        assertTrue((image.snippet?.length ?: 0) <= 512)
        assertTrue(collection.relationships.none { it.relation == "visual_match_observed" })
    }

    @Test
    fun profileAvatarObservationsDoNotInheritPhotoSeedPath() {
        val avatar = candidateState("avatar", ReverseImageLookupResult.ImageCandidateState.Indexed)
        val snapshot = MediaIntelligenceSnapshot(
            imageResults = listOf(
                sampleResult().copy(
                    visualCandidates = listOf(avatar),
                    visualSearchNote = "Directly verified public profile avatars were recorded"
                ),
                sampleResult().copy(extractedText = "Text from selected photo")
            )
        )

        val collection = snapshot.toEvidenceCollection(
            discoveryPath = listOf("seed:photo"),
            mediaSourceUri = "content://example/photo"
        )
        val avatarEvidence = collection.evidence.single { it.value == avatar.imageUrl }
        val photoText = collection.evidence.single { it.value == "Text from selected photo" }
        assertEquals(listOf("profile:avatar"), avatarEvidence.discoveryPath)
        assertEquals(listOf("seed:photo"), photoText.discoveryPath)
    }

    private fun candidateState(
        id: String,
        state: ReverseImageLookupResult.ImageCandidateState
    ) = ReverseImageLookupResult.ImageCandidateProvenance(
        id = id,
        title = id,
        imageUrl = "https://images.example.test/$id.jpg",
        sourcePageUrl = "https://pages.example.test/$id",
        source = "fixture",
        acquisitionQuery = "fixture",
        retrievedAtEpochMillis = 1L,
        comparisonScore = if (state == ReverseImageLookupResult.ImageCandidateState.Indexed) null else 0.4f,
        state = state
    )

    private fun sampleResult() = ReverseImageLookupResult(
        gps = null,
        extractedText = null,
        labels = emptyList(),
        faceDetected = false,
        faceWarning = null,
        resolvedLocation = null,
        mapsUrl = null,
        webEvidence = emptyList()
    )
}
