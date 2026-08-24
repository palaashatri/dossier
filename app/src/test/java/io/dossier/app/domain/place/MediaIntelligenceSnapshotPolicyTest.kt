package io.dossier.app.domain.place

import io.dossier.app.domain.model.ReverseImageLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIntelligenceSnapshotPolicyTest {

    @Test
    fun normalizeDropsOrphanReferencesAndRepairsLinkedEvidence() {
        val retained = candidate("retained", "cluster:valid")
        val duplicate = candidate("retained", "cluster:orphan")
        val noCluster = candidate("unclustered", null)
        val mislinked = candidate("mislinked", "cluster:valid")
        val result = sampleResult(
            candidates = listOf(retained, duplicate, noCluster, mislinked),
            clusters = listOf(
                ReverseImageLookupResult.ImageCluster(
                    id = "cluster:valid",
                    type = ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate,
                    representativeCandidateId = "retained",
                    memberCandidateIds = listOf("retained", "unclustered", "missing")
                ),
                ReverseImageLookupResult.ImageCluster(
                    id = "cluster:orphan",
                    type = ReverseImageLookupResult.ImageClusterType.ExactContent,
                    representativeCandidateId = "missing",
                    memberCandidateIds = listOf("missing", "also-missing")
                )
            ),
            matches = listOf(
                ReverseImageLookupResult.VisualMatch(
                    title = "retained",
                    imageUrl = "https://images.example.test/retained.jpg",
                    sourcePageUrl = "https://example.test/profile",
                    source = "fixture",
                    similarity = 0.9f,
                    matchType = "near-duplicate",
                    evidence = "whole-image comparison",
                    candidateId = "missing",
                    clusterId = "cluster:orphan"
                )
            )
        )

        val normalized = MediaIntelligenceSnapshotPolicy.normalize(
            MediaIntelligenceSnapshot(imageResults = listOf(result))
        ).imageResults.single()

        assertEquals(listOf("retained", "unclustered", "mislinked"), normalized.visualCandidates.map { it.id })
        assertEquals("cluster:valid", normalized.visualCandidates.first().clusterId)
        assertNull(normalized.visualCandidates[1].clusterId)
        assertNull(normalized.visualCandidates[2].clusterId)
        assertEquals(listOf("retained", "unclustered"), normalized.visualClusters.single().memberCandidateIds)
        assertNull(normalized.visualMatches.single().candidateId)
        assertNull(normalized.visualMatches.single().clusterId)
    }

    @Test
    fun normalizeBoundsResultsAndDeduplicatesCandidateIds() {
        val candidates = (0 until 130).map { index ->
            candidate("candidate-$index", null)
        } + candidate("candidate-0", null)
        val matches = (0 until 130).map { index ->
            ReverseImageLookupResult.VisualMatch(
                title = "match-$index",
                imageUrl = "https://images.example.test/match-$index.jpg",
                sourcePageUrl = "https://example.test/match-$index",
                source = "fixture",
                similarity = 0.8f,
                matchType = "near-duplicate",
                evidence = "whole-image comparison"
            )
        }
        val normalized = MediaIntelligenceSnapshotPolicy.normalize(
            MediaIntelligenceSnapshot(
                imageResults = (0 until 16).map { index ->
                    sampleResult(candidates, emptyList(), matches).copy(extractedText = "result-$index")
                },
                videoResults = List(10) { ReverseVideoLookupResultFixture.empty() }
            )
        )

        assertEquals(12, normalized.imageResults.size)
        assertEquals(6, normalized.videoResults.size)
        assertEquals("result-4", normalized.imageResults.first().extractedText)
        assertEquals("result-15", normalized.imageResults.last().extractedText)
        assertEquals(100, normalized.imageResults.first().visualCandidates.size)
        assertEquals(100, normalized.imageResults.first().visualCandidates.map { it.id }.toSet().size)
        assertEquals(100, normalized.imageResults.first().visualMatches.size)
        assertTrue(normalized.imageResults.all { it.visualClusters.isEmpty() })
    }

    private fun sampleResult(
        candidates: List<ReverseImageLookupResult.ImageCandidateProvenance>,
        clusters: List<ReverseImageLookupResult.ImageCluster>,
        matches: List<ReverseImageLookupResult.VisualMatch> = emptyList()
    ) = ReverseImageLookupResult(
        gps = null,
        extractedText = null,
        labels = emptyList(),
        faceDetected = false,
        faceWarning = null,
        resolvedLocation = null,
        mapsUrl = null,
        webEvidence = emptyList(),
        visualMatches = matches,
        visualCandidates = candidates,
        visualClusters = clusters
    )

    private fun candidate(id: String, clusterId: String?) =
        ReverseImageLookupResult.ImageCandidateProvenance(
            id = id,
            title = id,
            imageUrl = "https://images.example.test/$id.jpg",
            sourcePageUrl = "https://example.test/$id",
            source = "fixture",
            acquisitionQuery = "fixture",
            clusterId = clusterId
        )

    private object ReverseVideoLookupResultFixture {
        fun empty() = io.dossier.app.domain.model.ReverseVideoLookupResult(
            durationMs = null,
            sampledFrames = 0,
            extractedText = null,
            labels = emptyList(),
            faceDetected = false,
            faceWarning = null,
            resolvedLocation = null,
            mapsUrl = null,
            webEvidence = emptyList()
        )
    }
}
