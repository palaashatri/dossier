package io.dossier.app.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDuplicateClustererTest {
    @Test
    fun exactContentClustersBySha256() {
        val clusters = ImageDuplicateClusterer.cluster(
            listOf(
                ImageDuplicateClusterer.Candidate("a", "same-sha", 0x0000L, 0.91f),
                ImageDuplicateClusterer.Candidate("b", "same-sha", 0xffffL, 0.87f),
                ImageDuplicateClusterer.Candidate("c", "other-sha", 0x5555L, 0.20f)
            )
        )

        assertEquals(1, clusters.size)
        val cluster = clusters.single()
        assertEquals(ImageDuplicateClusterer.ClusterType.ExactContent, cluster.type)
        assertEquals(listOf("a", "b"), cluster.memberCandidateIds)
        assertEquals("a", cluster.representativeCandidateId)
    }

    @Test
    fun nearPerceptualCopiesClusterWithoutClaimingExactBytes() {
        val clusters = ImageDuplicateClusterer.cluster(
            listOf(
                ImageDuplicateClusterer.Candidate("one", "sha-one", 0x0000000000000000L, 0.82f),
                ImageDuplicateClusterer.Candidate("two", "sha-two", 0x0000000000000001L, 0.94f),
                ImageDuplicateClusterer.Candidate("different", "sha-three", -1L, 0.95f)
            )
        )

        assertEquals(1, clusters.size)
        val cluster = clusters.single()
        assertEquals(ImageDuplicateClusterer.ClusterType.PerceptualNearDuplicate, cluster.type)
        assertEquals(listOf("one", "two"), cluster.memberCandidateIds)
        assertEquals("two", cluster.representativeCandidateId)
        assertTrue(cluster.id.startsWith("imgcluster:"))
    }

    @Test
    fun clusterIdsAreStableAcrossInputOrdering() {
        val first = listOf(
            ImageDuplicateClusterer.Candidate("a", "x", 0L, 0.7f),
            ImageDuplicateClusterer.Candidate("b", "x", 3L, 0.8f)
        )
        val reversed = first.reversed()

        val firstId = ImageDuplicateClusterer.cluster(first).single().id
        val secondId = ImageDuplicateClusterer.cluster(reversed).single().id
        assertEquals(firstId, secondId)
        assertNotEquals("", firstId)
    }
}
