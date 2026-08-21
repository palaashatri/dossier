package io.dossier.app.domain.case

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import org.junit.Assert.assertEquals
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

    private fun dossierCase(id: String, media: ReverseImageLookupResult) = DossierCase(
        caseId = id,
        createdAt = "2026-08-21 01:00",
        subjectName = "X",
        input = IdentityInput(primaryUsername = "x"),
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
}
