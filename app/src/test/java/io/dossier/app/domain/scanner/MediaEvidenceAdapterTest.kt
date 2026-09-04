package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.toExposureLedger
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSession
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
                    url = "https://directory.example.test/cafe"
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
            retrievedAtEpochMillis = retrievedAt
        )
        val ledger = collection.toExposureLedger()

        val gps = collection.evidence.single { it.value == result.gps }
        assertEquals(EvidenceKind.Location, gps.kind)
        assertEquals(EvidenceReliability.LocalDerived, gps.reliability)
        assertEquals(EvidenceState.Observed, gps.state)
        assertEquals(path, gps.discoveryPath)
        assertEquals(retrievedAt, gps.retrievedAtEpochMillis)

        val ocr = collection.evidence.single {
            it.kind == EvidenceKind.SensitiveSnippet && it.value == result.extractedText
        }
        assertEquals(EvidenceKind.SensitiveSnippet, ocr.kind)
        assertEquals(EvidenceState.Observed, ocr.state)

        val web = collection.evidence.single { it.sourceUrl == "https://directory.example.test/cafe" }
        assertEquals(EvidenceState.Candidate, web.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, web.reliability)

        val imageUrl = collection.evidence.single { it.value == candidate.imageUrl }
        assertEquals(candidate.retrievedAtEpochMillis, imageUrl.retrievedAtEpochMillis)
        assertEquals(candidate.sourcePageUrl, imageUrl.sourceUrl)
        assertEquals(EvidenceState.Observed, imageUrl.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, imageUrl.reliability)
        assertEquals(path, imageUrl.discoveryPath)
        assertFalse(collection.evidence.any { it.id == candidate.id })

        val imageFact = ledger.facts.single { it.exactValue == candidate.imageUrl }
        assertEquals(candidate.imageUrl.lowercase(), imageFact.normalizedValue)
        assertEquals(candidate.retrievedAtEpochMillis, imageFact.firstObservedAtEpochMillis)
        assertEquals(candidate.retrievedAtEpochMillis, imageFact.lastObservedAtEpochMillis)
        assertEquals(path, imageFact.discoveryPath)

        val matchRelation = collection.relationships.first { it.toValue == candidate.imageUrl }
        assertTrue(matchRelation.evidenceIds.isNotEmpty())
    }

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
