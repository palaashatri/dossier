package io.dossier.app.domain.case

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseMediaComparisonTest {
    @Test
    fun correlatesExactAndPerceptualFingerprintsAcrossCases() {
        val before = dossierCase(
            "a",
            imageResult(
                candidateId = "candidate-a",
                contentHash = "ABC123",
                perceptualHash = "F0F0",
                sourcePage = "https://old.example/post"
            )
        )
        val after = dossierCase(
            "b",
            imageResult(
                candidateId = "candidate-b",
                contentHash = "abc123",
                perceptualHash = "f0f0",
                sourcePage = "https://new.example/post"
            )
        )

        val media = CaseComparison().compare(before, after).media
        assertEquals(1, media.exactContentReused)
        assertEquals(1, media.perceptualFingerprintsReused)
        assertEquals(1, media.sourcePagesAdded)
        assertEquals(1, media.sourcePagesRemoved)
    }

    @Test
    fun clusterHistoryGroupsRepeatedWholeImageFingerprintAndRetainsProvenance() {
        val before = clusteredCase(
            id = "before",
            clusterId = "scan-specific-before",
            firstCandidateId = "before-a",
            secondCandidateId = "before-b",
            sourcePages = listOf(
                "https://old.example/profile",
                "https://old.example/post"
            )
        )
        val after = clusteredCase(
            id = "after",
            clusterId = "scan-specific-after",
            firstCandidateId = "after-a",
            secondCandidateId = "after-b",
            sourcePages = listOf(
                "https://new.example/profile",
                "https://new.example/post"
            )
        )

        val history = CaseComparison().mediaClusterHistory(listOf(before, after))
        val repeated = history.single { it.fingerprint == "sha-shared" }

        assertEquals(
            listOf("before", "after"),
            repeated.observations.map { it.caseId }
        )
        assertEquals(2, repeated.caseCount)
        assertEquals(4, repeated.memberCount)
        assertEquals("scan-specific-before", repeated.observations.first().clusterId)
        assertEquals(
            "https://old.example/profile",
            repeated.observations.first().members.first().sourcePageUrl
        )
        assertEquals("fixture-before", repeated.observations.first().members.first().source)
        assertEquals(101L, repeated.observations.first().members.first().retrievedAtEpochMillis)
        assertTrue(
            repeated.observations.first().members.any { member ->
                member.contentSha256 == "sha-shared"
            }
        )
    }

    @Test
    fun clusterHistoryDoesNotGuessMatchWhenFingerprintIsMissing() {
        val first = clusteredCase(
            id = "first",
            clusterId = "same-cluster-id",
            firstCandidateId = "first-a",
            secondCandidateId = "first-b",
            sourcePages = listOf("https://one.example/a", "https://one.example/b"),
            contentHash = null,
            perceptualHash = null
        )
        val second = clusteredCase(
            id = "second",
            clusterId = "same-cluster-id",
            firstCandidateId = "second-a",
            secondCandidateId = "second-b",
            sourcePages = listOf("https://two.example/a", "https://two.example/b"),
            contentHash = null,
            perceptualHash = null
        )

        val history = CaseComparison().mediaClusterHistory(listOf(first, second))

        assertEquals(2, history.size)
        assertTrue(history.all { it.fingerprint == null && it.caseCount == 1 })
        assertNull(history.first().fingerprint)
    }

    private fun dossierCase(id: String, media: ReverseImageLookupResult) = DossierCase(
        caseId = id,
        createdAt = "2026-08-21 01:00",
        subjectName = "X",
        input = IdentityInput(fullName = "", primaryUsername = "x"),
        mediaIntelligence = MediaIntelligenceSnapshot(imageResults = listOf(media))
    )

    private fun imageResult(
        candidateId: String,
        contentHash: String,
        perceptualHash: String,
        sourcePage: String
    ): ReverseImageLookupResult {
        val candidate = ReverseImageLookupResult.ImageCandidateProvenance(
            id = candidateId,
            title = "candidate",
            imageUrl = "$sourcePage/image.jpg",
            sourcePageUrl = sourcePage,
            source = "test",
            acquisitionQuery = "x",
            contentSha256 = contentHash,
            perceptualHashHex = perceptualHash,
            state = ReverseImageLookupResult.ImageCandidateState.Matched,
            clusterId = "cluster"
        )
        return ReverseImageLookupResult(
            gps = null,
            extractedText = null,
            labels = emptyList(),
            faceDetected = false,
            faceWarning = null,
            resolvedLocation = null,
            mapsUrl = null,
            webEvidence = emptyList(),
            visualMatches = emptyList(),
            visualCandidates = listOf(candidate),
            visualClusters = listOf(
                ReverseImageLookupResult.ImageCluster(
                    id = "cluster",
                    type = ReverseImageLookupResult.ImageClusterType.ExactContent,
                    representativeCandidateId = candidateId,
                    memberCandidateIds = listOf(candidateId)
                )
            ),
            visualSearchNote = null
        )
    }

    private fun clusteredCase(
        id: String,
        clusterId: String,
        firstCandidateId: String,
        secondCandidateId: String,
        sourcePages: List<String>,
        contentHash: String? = "sha-shared",
        perceptualHash: String? = "phash-shared"
    ) = DossierCase(
        caseId = id,
        createdAt = "2026-08-21 01:00",
        subjectName = "X",
        input = IdentityInput(fullName = "", primaryUsername = "x"),
        mediaIntelligence = MediaIntelligenceSnapshot(
            imageResults = listOf(
                ReverseImageLookupResult(
                    gps = null,
                    extractedText = null,
                    labels = emptyList(),
                    faceDetected = false,
                    faceWarning = null,
                    resolvedLocation = null,
                    mapsUrl = null,
                    webEvidence = emptyList(),
                    visualCandidates = listOf(
                        mediaCandidate(
                            id = firstCandidateId,
                            sourcePageUrl = sourcePages[0],
                            source = "fixture-$id",
                            contentHash = contentHash,
                            perceptualHash = perceptualHash,
                            retrievedAt = 101L,
                            clusterId = clusterId
                        ),
                        mediaCandidate(
                            id = secondCandidateId,
                            sourcePageUrl = sourcePages[1],
                            source = "fixture-$id",
                            contentHash = contentHash,
                            perceptualHash = perceptualHash,
                            retrievedAt = 102L,
                            clusterId = clusterId
                        )
                    ),
                    visualClusters = listOf(
                        ReverseImageLookupResult.ImageCluster(
                            id = clusterId,
                            type = ReverseImageLookupResult.ImageClusterType.ExactContent,
                            representativeCandidateId = firstCandidateId,
                            memberCandidateIds = listOf(firstCandidateId, secondCandidateId)
                        )
                    )
                )
            )
        )
    )

    private fun mediaCandidate(
        id: String,
        sourcePageUrl: String,
        source: String,
        contentHash: String?,
        perceptualHash: String?,
        retrievedAt: Long,
        clusterId: String
    ) = ReverseImageLookupResult.ImageCandidateProvenance(
        id = id,
        title = "candidate-$id",
        imageUrl = "$sourcePageUrl/image.jpg",
        sourcePageUrl = sourcePageUrl,
        source = source,
        acquisitionQuery = "fixture",
        retrievedAtEpochMillis = retrievedAt,
        contentSha256 = contentHash,
        perceptualHashHex = perceptualHash,
        state = ReverseImageLookupResult.ImageCandidateState.Matched,
        clusterId = clusterId
    )
}
