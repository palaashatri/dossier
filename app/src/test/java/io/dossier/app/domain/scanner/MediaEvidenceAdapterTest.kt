package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.toExposureLedger
import io.dossier.app.domain.discovery.TypedSeedEvidenceAdapter
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSession
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(candidate.retrievedAtEpochMillis, imageFact.firstObservedAtEpochMillis)
        assertEquals(retrievedAt, imageFact.lastObservedAtEpochMillis)
        assertEquals(path, imageFact.discoveryPath)

        assertTrue(collection.relationships.none { it.relation == "visual_match_observed" })
    }

    @Test
    fun legacyLocationObservationsReceiveExplicitClassesWithoutChangingSourceStates() {
        val result = sampleResult().copy(
            gps = "12.345,67.890",
            resolvedLocation = "Cafe Example, Example City",
            labels = listOf(ReverseImageLookupResult.ImageLabel("Cafe", 0.81f)),
            webEvidence = listOf(
                ReverseImageLookupResult.WebEvidence(
                    title = "Cafe directory",
                    snippet = "A public image-search result",
                    url = "https://directory.example.test/cafe",
                    origin = ReverseImageLookupResult.WebEvidenceOrigin.ImageSearch
                ),
                ReverseImageLookupResult.WebEvidence(
                    title = "Coordinate corroboration",
                    snippet = "The EXIF point reverse-geocodes to Cafe Example.",
                    url = "https://www.openstreetmap.org/?mlat=12.345&mlon=67.890",
                    origin = ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration
                )
            )
        )

        val collection = result.toEvidenceCollection(
            mediaSourceUri = "content://example/photo",
            retrievedAtEpochMillis = 123L
        )
        val gps = collection.evidence.single { it.value == result.gps }
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
            gps.locationEvidenceClass
        )
        assertEquals(EvidenceState.Observed, gps.state)
        assertEquals("Embedded GPS metadata from the selected photo", gps.locationEvidenceReason)

        val resolved = collection.evidence.single { it.value == result.resolvedLocation }
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION,
            resolved.locationEvidenceClass
        )
        assertEquals(EvidenceState.Observed, resolved.state)

        val imageSearch = collection.evidence.single {
            it.sourceUrl == "https://directory.example.test/cafe"
        }
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION,
            imageSearch.locationEvidenceClass
        )
        assertEquals(EvidenceState.Candidate, imageSearch.state)
        assertEquals(ExposureSourceClassification.PUBLIC_WEB, imageSearch.sourceClassification)

        val geoEvidence = collection.evidence.single {
            it.kind == EvidenceKind.PublicSearchEvidence &&
            it.sourceUrl?.startsWith("https://www.openstreetmap.org/") == true
        }
        assertEquals(ExposureSourceClassification.AUTHORIZED_API, geoEvidence.sourceClassification)

        val localLabel = collection.evidence.single { it.value == "Cafe" }
        assertNull(localLabel.locationEvidenceClass)
        assertEquals(EvidenceReliability.LocalDerived, localLabel.reliability)
        assertTrue(collection.evidence.none {
            it.kind == EvidenceKind.Location && it.value == "Cafe"
        })

        val ledgerFacts = collection.toExposureLedger().facts
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
            ledgerFacts.single { it.exactValue == result.gps }.locationEvidenceClass
        )
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION,
            ledgerFacts.single { it.exactValue == result.resolvedLocation }.locationEvidenceClass
        )
    }

    @Test
    fun explicitLocationCandidatesRetainReasonSupportingIdsAndConflictState() {
        val candidates = listOf(
            ReverseImageLookupResult.LocationCandidate(
                value = "12.345,67.890",
                evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
                reason = "GPS coordinates embedded by the camera",
                evidenceIds = listOf("gps-evidence"),
                sourceUrls = listOf("content://example/photo")
            ),
            ReverseImageLookupResult.LocationCandidate(
                value = "Cafe Example, Example City",
                evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING,
                reason = "Public page disagrees with the embedded coordinates",
                evidenceIds = listOf("page-evidence"),
                sourceUrls = listOf("https://pages.example.test/cafe"),
                confidence = 0.2f
            )
        )
        val collection = sampleResult().copy(locationCandidates = candidates)
            .toEvidenceCollection(retrievedAtEpochMillis = 99L)

        val exact = collection.evidence.single { it.value == candidates[0].value }
        assertEquals(candidates[0].reason, exact.locationEvidenceReason)
        assertEquals(candidates[0].evidenceIds, exact.supportingEvidenceIds)
        assertEquals(EvidenceState.Observed, exact.state)

        val conflict = collection.evidence.single { it.value == candidates[1].value }
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING,
            conflict.locationEvidenceClass
        )
        assertEquals(EvidenceState.Conflicting, conflict.state)
        assertEquals(candidates[1].reason, conflict.snippet)

        val conflictFact = collection.toExposureLedger().facts.single {
            it.exactValue == candidates[1].value
        }
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING,
            conflictFact.locationEvidenceClass
        )
        assertEquals(candidates[1].reason, conflictFact.locationEvidenceReason)
        assertEquals(candidates[1].evidenceIds, conflictFact.supportingEvidenceIds)
    }

    @Test
    fun locationCandidatesRoundTripAndLegacyResultsUseEmptyDefaults() {
        val candidate = ReverseImageLookupResult.LocationCandidate(
            value = "Example City",
            evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION,
            reason = "A public source page mentions the place",
            evidenceIds = listOf("source-evidence"),
            sourceUrls = listOf("https://pages.example.test/city")
        )
        val result = sampleResult().copy(locationCandidates = listOf(candidate))
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<ReverseImageLookupResult>(json.encodeToString(result))
        assertEquals(listOf(candidate), decoded.locationCandidates)

        val legacy = """{
          "gps": null,
          "extractedText": null,
          "labels": [],
          "faceDetected": false,
          "faceWarning": null,
          "resolvedLocation": null,
          "mapsUrl": null,
          "webEvidence": [],
          "visualMatches": []
        }"""
        assertTrue(json.decodeFromString<ReverseImageLookupResult>(legacy).locationCandidates.isEmpty())
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
    fun unknownOriginWebEvidenceCannotUpgradeLegacyResolvedLocation() {
        val result = sampleResult().copy(
            resolvedLocation = "Example City",
            webEvidence = listOf(
                ReverseImageLookupResult.WebEvidence(
                    title = "Unclassified location result",
                    snippet = "Provider origin was not recorded",
                    url = "https://unknown.example.test/location",
                    origin = ReverseImageLookupResult.WebEvidenceOrigin.Unknown
                )
            )
        )

        val location = result.toEvidenceCollection(
            mediaSourceUri = "content://example/photo"
        ).evidence.single { it.value == "Example City" }

        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS,
            location.locationEvidenceClass
        )
        assertEquals(EvidenceState.Candidate, location.state)
        assertEquals(EvidenceReliability.LocalDerived, location.reliability)
        assertEquals("content://example/photo", location.sourceUrl)
    }

    @Test
    fun reverseImageSourcePagesBecomeObservedTypedUrlPivotsWithoutPromotingImages() {
        val page = "https://pages.example.test/repost"
        val image = "https://images.example.test/repost.jpg"
        val candidate = ReverseImageLookupResult.ImageCandidateProvenance(
            id = "candidate-page-pivot",
            title = "Indexed repost",
            imageUrl = image,
            sourcePageUrl = page,
            source = "fixture-index",
            acquisitionQuery = "Jane Example",
            state = ReverseImageLookupResult.ImageCandidateState.Indexed
        )
        val collection = sampleResult().copy(visualCandidates = listOf(candidate))
            .toEvidenceCollection(
                discoveryPath = listOf("seed:photo"),
                retrievedAtEpochMillis = 17L
            )

        val imageEvidence = collection.evidence.single { it.value == image }
        assertEquals(EvidenceState.Candidate, imageEvidence.state)

        val pageEvidence = collection.evidence.single { it.value == page }
        assertEquals(EvidenceKind.Url, pageEvidence.kind)
        assertEquals(EvidenceState.Observed, pageEvidence.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, pageEvidence.reliability)
        assertEquals(ExposureSourceClassification.PUBLIC_WEB, pageEvidence.sourceClassification)
        assertEquals(page, pageEvidence.sourceUrl)
        assertEquals(17L, pageEvidence.observedAtEpochMillis)
        assertTrue(pageEvidence.supportingEvidenceIds.contains(imageEvidence.id))

        val typed = TypedSeedEvidenceAdapter.fromCollection(collection)
        val pageSeed = typed.admittedSeeds.single { it.exactValue == page }
        assertEquals(TypedSeedKind.Url, pageSeed.kind)
        assertEquals(EvidenceState.Observed, pageSeed.evidenceState)
        assertEquals(TypedSeedOrigin.Evidence, pageSeed.origin)
        assertEquals(listOf(pageEvidence.id), pageSeed.evidenceIds)
        assertTrue(typed.admittedSeeds.none { it.exactValue == image })
    }

    @Test
    fun rawImageAndUnsupportedSourcePageUrlsDoNotBecomeFetchPivots() {
        val rawImage = "https://images.example.test/raw.jpg"
        val document = "https://pages.example.test/public-resume.pdf"
        val archive = "https://web.archive.org/web/20240101000000/https://example.test/profile"
        val rawImageCandidate = candidateState("raw", ReverseImageLookupResult.ImageCandidateState.Indexed)
            .copy(sourcePageUrl = rawImage, imageUrl = rawImage)
        val documentCandidate = candidateState("document", ReverseImageLookupResult.ImageCandidateState.Indexed)
            .copy(sourcePageUrl = document)
        val archiveCandidate = candidateState("archive", ReverseImageLookupResult.ImageCandidateState.Indexed)
            .copy(sourcePageUrl = archive)
        val unsafeCandidate = candidateState("unsafe", ReverseImageLookupResult.ImageCandidateState.Indexed)
            .copy(sourcePageUrl = "http://127.0.0.1/private")

        val collection = sampleResult().copy(
            visualCandidates = listOf(
                rawImageCandidate,
                documentCandidate,
                archiveCandidate,
                unsafeCandidate
            )
        ).toEvidenceCollection()

        assertTrue(collection.evidence.any {
            it.value == rawImage && it.kind == EvidenceKind.PublicImageEvidence
        })
        assertTrue(collection.evidence.none { it.value == rawImage && it.kind != EvidenceKind.PublicImageEvidence })
        assertEquals(EvidenceKind.Document, collection.evidence.single { it.value == document }.kind)
        assertEquals(EvidenceKind.Archive, collection.evidence.single { it.value == archive }.kind)
        assertEquals(
            ExposureSourceClassification.PUBLIC_DOCUMENT,
            collection.evidence.single { it.value == document }.sourceClassification
        )
        assertEquals(
            ExposureSourceClassification.ARCHIVE,
            collection.evidence.single { it.value == archive }.sourceClassification
        )
        assertTrue(collection.evidence.none { it.value == "http://127.0.0.1/private" })

        val typed = TypedSeedEvidenceAdapter.fromCollection(collection)
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Document && it.exactValue == document })
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Archive && it.exactValue == archive })
        assertTrue(typed.admittedSeeds.none { it.exactValue == rawImage })
        assertTrue(typed.admittedSeeds.none { it.exactValue == "http://127.0.0.1/private" })
    }

    @Test
    fun onlyExactAndCorroboratedLocationEvidenceEntersTypedFrontier() {
        val candidates = listOf(
            ReverseImageLookupResult.LocationCandidate(
                value = "12.345,67.890",
                evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
                reason = "Embedded coordinates",
                sourceUrls = listOf("content://example/photo")
            ),
            ReverseImageLookupResult.LocationCandidate(
                value = "Cafe Example, Example City",
                evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION,
                reason = "Independent geocoder corroboration",
                sourceUrls = listOf("https://geo.example.test/place")
            ),
            ReverseImageLookupResult.LocationCandidate(
                value = "Example City",
                evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION,
                reason = "Indexed image-search clue"
            ),
            ReverseImageLookupResult.LocationCandidate(
                value = "Museum",
                evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS,
                reason = "On-device visual label"
            ),
            ReverseImageLookupResult.LocationCandidate(
                value = "Other City",
                evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING,
                reason = "Conflicts with embedded coordinates"
            )
        )
        val collection = sampleResult().copy(locationCandidates = candidates)
            .toEvidenceCollection(mediaSourceUri = "content://example/photo")
        val typed = TypedSeedEvidenceAdapter.fromCollection(collection)

        val exact = typed.admittedSeeds.single { it.exactValue == "12.345,67.890" }
        assertEquals(TypedSeedKind.Location, exact.kind)
        assertEquals(EvidenceState.Observed, exact.evidenceState)
        assertEquals(TypedSeedOrigin.LocalAnalysis, exact.origin)
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
            exact.locationEvidenceClass
        )
        val corroborated = typed.admittedSeeds.single { it.exactValue == "Cafe Example, Example City" }
        assertEquals(TypedSeedKind.Location, corroborated.kind)
        assertEquals(EvidenceState.Observed, corroborated.evidenceState)
        assertEquals(TypedSeedOrigin.Evidence, corroborated.origin)
        assertEquals(
            ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION,
            corroborated.locationEvidenceClass
        )
        assertTrue(typed.admittedSeeds.none { it.exactValue in setOf("Example City", "Museum", "Other City") })
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
            .copy(accountLinkages = listOf(verifiedProfileLinkage(avatarPageUrl("avatar"))))
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

    @Test
    fun exactMediaStringsUseNormalizedIdsWhileMergedRecordsRetainObservationWindowAndPaths() {
        val first = ReverseImageLookupResult.ImageCandidateProvenance(
            id = "candidate-first",
            title = "  Public repost  ",
            imageUrl = "  HTTPS://IMAGES.EXAMPLE.TEST/repost.jpg#fragment  ",
            sourcePageUrl = "  HTTPS://PAGES.EXAMPLE.TEST/repost/  ",
            source = "provider-a",
            acquisitionQuery = "first query",
            retrievedAtEpochMillis = 100L,
            contentSha256 = "hash-first",
            comparisonScore = 0.80f,
            state = ReverseImageLookupResult.ImageCandidateState.Matched
        )
        val second = first.copy(
            id = "candidate-second",
            title = "Public repost",
            imageUrl = "https://images.example.test/repost.jpg",
            sourcePageUrl = "https://pages.example.test/repost",
            source = "provider-b",
            acquisitionQuery = "second query",
            retrievedAtEpochMillis = 200L,
            contentSha256 = "hash-second",
            comparisonScore = 0.90f,
            accountLinkages = listOf(verifiedProfileLinkage("https://pages.example.test/repost"))
        )
        val snapshot = MediaIntelligenceSnapshot(
            imageResults = listOf(
                sampleResult().copy(visualCandidates = listOf(first)),
                sampleResult().copy(
                    visualCandidates = listOf(second),
                    visualSearchNote = "Directly verified public profile avatars were recorded"
                )
            )
        )

        val collection = snapshot.toEvidenceCollection(discoveryPath = listOf("seed:photo"))
        val image = collection.evidence.single {
            it.value == first.imageUrl
        }
        assertEquals(first.imageUrl, image.value)
        assertEquals(100L, image.firstObservedAtEpochMillis)
        assertEquals(200L, image.lastObservedAtEpochMillis)
        assertEquals(200L, image.retrievedAtEpochMillis)
        assertEquals(200L, image.observedAtEpochMillis)
        assertEquals(listOf("seed:photo", "profile:avatar"), image.discoveryPath)
        assertEquals(
            listOf(first.sourcePageUrl, second.sourcePageUrl),
            image.sourceUrls
        )
        assertEquals("provider-b", image.providerId)
        assertEquals("hash-second", image.contentHashSha256)

        val fact = collection.toExposureLedger().facts.single {
            it.exactValue == first.imageUrl
        }
        assertEquals(100L, fact.firstObservedAtEpochMillis)
        assertEquals(200L, fact.lastObservedAtEpochMillis)
    }

    @Test
    fun unknownWebOriginRemainsUnknownInTheLedger() {
        val unknown = ReverseImageLookupResult.WebEvidence(
            title = "Unclassified result",
            snippet = "Origin was not supplied by the provider",
            url = "https://unknown.example.test/result",
            origin = ReverseImageLookupResult.WebEvidenceOrigin.Unknown
        )
        val collection = sampleResult().copy(webEvidence = listOf(unknown)).toEvidenceCollection()

        val evidence = collection.evidence.single { it.sourceUrl == unknown.url }
        assertEquals(EvidenceKind.PublicSearchEvidence, evidence.kind)
        assertEquals(EvidenceReliability.Unknown, evidence.reliability)
        assertNull(evidence.locationEvidenceClass)
        assertEquals(
            ExposureSourceClassification.UNKNOWN_ORIGIN,
            collection.toExposureLedger().facts.single().sourceClassification
        )
    }

    @Test
    fun verifiedProfileAvatarEvidenceUsesDirectProfileReliability() {
        val avatar = candidateState("verified-avatar", ReverseImageLookupResult.ImageCandidateState.Indexed)
            .copy(accountLinkages = listOf(verifiedProfileLinkage(avatarPageUrl("verified-avatar"))))
        val snapshot = MediaIntelligenceSnapshot(
            imageResults = listOf(
                sampleResult().copy(
                    visualCandidates = listOf(avatar),
                    visualSearchNote = "Directly verified public profile avatars were recorded"
                )
            )
        )

        val collection = snapshot.toEvidenceCollection(discoveryPath = listOf("seed:photo"))
        val evidence = collection.evidence.single { it.value == avatar.imageUrl }
        assertEquals(EvidenceReliability.DirectPublicProfile, evidence.reliability)
        assertEquals(
            ExposureSourceClassification.PUBLIC_PROFILE,
            collection.toExposureLedger().facts.single { it.exactValue == avatar.imageUrl }
                .sourceClassification
        )
    }

    @Test
    fun mixedVisualResultsKeepProfileReliabilityScopedToExplicitLinkageAndCandidateMatch() {
        val ordinary = candidateState("ordinary", ReverseImageLookupResult.ImageCandidateState.Matched)
        val verified = candidateState("verified", ReverseImageLookupResult.ImageCandidateState.Matched)
            .copy(accountLinkages = listOf(verifiedProfileLinkage(avatarPageUrl("verified"))))
        val result = sampleResult().copy(
            visualCandidates = listOf(ordinary, verified),
            visualMatches = listOf(
                ReverseImageLookupResult.VisualMatch(
                    title = "Ordinary match",
                    imageUrl = ordinary.imageUrl,
                    sourcePageUrl = ordinary.sourcePageUrl,
                    source = "fixture",
                    similarity = 0.91f,
                    matchType = "exact-content",
                    evidence = "ordinary candidate match",
                    candidateId = ordinary.id
                ),
                ReverseImageLookupResult.VisualMatch(
                    title = "Verified match",
                    imageUrl = verified.imageUrl,
                    sourcePageUrl = verified.sourcePageUrl,
                    source = "fixture",
                    similarity = 0.92f,
                    matchType = "exact-content",
                    evidence = "verified candidate match",
                    candidateId = verified.id
                ),
                ReverseImageLookupResult.VisualMatch(
                    title = "Unlinked match",
                    imageUrl = "https://images.example.test/unlinked-match.jpg",
                    sourcePageUrl = "https://pages.example.test/unlinked-match",
                    source = "fixture",
                    similarity = 0.93f,
                    matchType = "near-duplicate",
                    evidence = "no candidate linkage"
                )
            )
        )

        val collection = result.toEvidenceCollection()
        val ledger = collection.toExposureLedger()
        fun evidenceFor(value: String) = collection.evidence.single { it.value == value }
        fun factFor(value: String) = ledger.facts.single { it.exactValue == value }

        assertEquals(EvidenceReliability.SearchEngineCandidate, evidenceFor(ordinary.imageUrl).reliability)
        assertEquals(ExposureSourceClassification.PUBLIC_WEB, factFor(ordinary.imageUrl).sourceClassification)
        assertEquals(EvidenceReliability.DirectPublicProfile, evidenceFor(verified.imageUrl).reliability)
        assertEquals(ExposureSourceClassification.PUBLIC_PROFILE, factFor(verified.imageUrl).sourceClassification)
        assertEquals(
            EvidenceReliability.SearchEngineCandidate,
            evidenceFor("https://images.example.test/unlinked-match.jpg").reliability
        )
        assertEquals(
            ExposureSourceClassification.PUBLIC_WEB,
            factFor("https://images.example.test/unlinked-match.jpg").sourceClassification
        )
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

    private fun verifiedProfileLinkage(accountUrl: String) =
        ReverseImageLookupResult.ImageAccountLinkage(
            accountUrl = accountUrl,
            basis = ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
            evidenceIds = listOf("profile:$accountUrl")
        )

    private fun avatarPageUrl(id: String): String = "https://pages.example.test/$id"

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
