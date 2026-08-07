package io.dossier.app.domain.image

import io.dossier.app.domain.model.ReverseImageLookupResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseImageProvenanceModelTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun candidateProvenanceAndClustersRoundTrip() {
        val candidate = ReverseImageLookupResult.ImageCandidateProvenance(
            id = "imgcandidate:abc",
            title = "Public avatar",
            imageUrl = "https://cdn.example.test/avatar.jpg",
            sourcePageUrl = "https://example.test/profile",
            source = "Example index",
            acquisitionQuery = "sample_user avatar",
            comparedImageUrl = "https://cdn.example.test/avatar-small.jpg",
            retrievedAtEpochMillis = 1_786_147_200_000L,
            contentSha256 = "a".repeat(64),
            width = 512,
            height = 512,
            averageHashHex = "0000000000000001",
            differenceHashHex = "0000000000000002",
            perceptualHashHex = "0000000000000003",
            comparisonScore = 0.91f,
            exactBytes = false,
            state = ReverseImageLookupResult.ImageCandidateState.Matched,
            clusterId = "imgcluster:cluster-one"
        )
        val result = ReverseImageLookupResult(
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
                    id = "imgcluster:cluster-one",
                    type = ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate,
                    representativeCandidateId = candidate.id,
                    memberCandidateIds = listOf(candidate.id, "imgcandidate:def")
                )
            )
        )

        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<ReverseImageLookupResult>(encoded)
        assertEquals(candidate, decoded.visualCandidates.single())
        assertEquals("imgcluster:cluster-one", decoded.visualClusters.single().id)
        assertTrue(encoded.contains("sample_user avatar"))
    }

    @Test
    fun oldResultsDecodeWithEmptyProvenanceDefaults() {
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

        val decoded = json.decodeFromString<ReverseImageLookupResult>(legacy)
        assertTrue(decoded.visualCandidates.isEmpty())
        assertTrue(decoded.visualClusters.isEmpty())
    }
}
