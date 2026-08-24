package io.dossier.app.domain.case

import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphEntityKind
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.RelationshipType
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGraphEnricherTest {

    @Test
    fun verifiedProfileLinkCreatesExplicitAvatarEdgeWithoutUsingVisualScore() {
        val accountUrl = "https://www.example.test/alice/"
        val candidate = candidate(
            sourcePageUrl = accountUrl,
            state = ReverseImageLookupResult.ImageCandidateState.Indexed,
            comparisonScore = 0.99f,
            linkage = ReverseImageLookupResult.ImageAccountLinkage(
                accountUrl = accountUrl,
                basis = ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
                evidenceIds = listOf("profile-page-evidence"),
                linkedAtEpochMillis = 100L
            )
        )

        val graph = MediaGraphEnricher.enrich(
            graph = EntityGraph(
                entities = listOf(
                    account(
                        id = "profile:alice",
                        url = "https://example.test/alice",
                        state = GraphNodeState.Confirmed
                    )
                ),
                edges = emptyList()
            ),
            media = MediaIntelligenceSnapshot(imageResults = listOf(result(candidate)))
        )

        val edge = graph.edges.single { it.relation == "USES_AVATAR" }
        assertEquals("profile:alice", edge.fromId)
        assertEquals("media-image:${candidate.id}", edge.toId)
        assertEquals(RelationshipType.USES_AVATAR, edge.relationType)
        assertEquals(listOf("profile-page-evidence"), edge.evidenceIds)
        assertNull(edge.confidence)
        assertTrue(edge.evidence.orEmpty().contains("does not establish"))
        assertTrue(graph.entities.any { it.id == "media-image:${candidate.id}" })
    }

    @Test
    fun visualMatchAloneDoesNotCreateAvatarEdgeForUnverifiedAccount() {
        val accountUrl = "https://example.test/alice"
        val candidate = candidate(
            sourcePageUrl = accountUrl,
            state = ReverseImageLookupResult.ImageCandidateState.Matched,
            comparisonScore = 1f
        )

        val graph = MediaGraphEnricher.enrich(
            graph = EntityGraph(
                entities = listOf(
                    account(
                        id = "profile:alice",
                        url = accountUrl,
                        state = GraphNodeState.Medium
                    )
                ),
                edges = emptyList()
            ),
            media = MediaIntelligenceSnapshot(imageResults = listOf(result(candidate)))
        )

        assertFalse(graph.edges.any { it.relation == "USES_AVATAR" })
        assertTrue(graph.edges.any { it.relation == "HOSTS_PUBLIC_IMAGE" })
    }

    @Test
    fun confirmedAccountPageCreatesAvatarEdgeWithoutVisualMatchOrReviewRecord() {
        val accountUrl = "https://example.test/alice"
        val candidate = candidate(
            sourcePageUrl = accountUrl,
            state = ReverseImageLookupResult.ImageCandidateState.Indexed,
            comparisonScore = null
        )

        val graph = MediaGraphEnricher.enrich(
            graph = EntityGraph(
                entities = listOf(
                    account(
                        id = "profile:alice",
                        url = accountUrl,
                        state = GraphNodeState.Confirmed
                    )
                ),
                edges = emptyList()
            ),
            media = MediaIntelligenceSnapshot(imageResults = listOf(result(candidate)))
        )

        val edge = graph.edges.single { it.relation == "USES_AVATAR" }
        assertEquals("profile:alice", edge.fromId)
        assertNull(edge.confidence)
        assertTrue(edge.evidence.orEmpty().contains("source page"))
        assertTrue(edge.evidence.orEmpty().contains("does not establish"))
    }

    @Test
    fun reviewedLinkRequiresReviewProvenanceAndNeverClaimsIdentity() {
        val accountUrl = "https://example.test/alice"
        val withoutTimestamp = candidate(
            sourcePageUrl = "https://images.example.test/repost",
            state = ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch,
            linkage = ReverseImageLookupResult.ImageAccountLinkage(
                accountUrl = accountUrl,
                basis = ReverseImageLookupResult.ImageAccountLinkageBasis.UserReviewed
            )
        )
        val reviewed = withoutTimestamp.copy(
            id = "reviewed",
            accountLinkages = listOf(
                withoutTimestamp.accountLinkages.single().copy(linkedAtEpochMillis = 200L)
            )
        )

        val graph = MediaGraphEnricher.enrich(
            graph = EntityGraph(
                entities = listOf(
                    account(
                        id = "profile:alice",
                        url = accountUrl,
                        state = GraphNodeState.High
                    )
                ),
                edges = emptyList()
            ),
            media = MediaIntelligenceSnapshot(
                imageResults = listOf(result(withoutTimestamp), result(reviewed))
            )
        )

        assertEquals(1, graph.edges.count { it.relation == "USES_AVATAR" })
        val edge = graph.edges.single { it.relation == "USES_AVATAR" }
        assertEquals("media-image:reviewed", edge.toId)
        assertTrue(edge.evidence.orEmpty().contains("explicit user review"))
        assertTrue(edge.evidence.orEmpty().contains("does not establish"))
    }

    private fun account(id: String, url: String, state: GraphNodeState) = DossierEntity(
        id = id,
        type = EntityType.Profile,
        label = url,
        sourceUrls = listOf(url),
        kind = GraphEntityKind.Account,
        state = state
    )

    private fun candidate(
        sourcePageUrl: String,
        state: ReverseImageLookupResult.ImageCandidateState,
        comparisonScore: Float? = null,
        linkage: ReverseImageLookupResult.ImageAccountLinkage? = null
    ) = ReverseImageLookupResult.ImageCandidateProvenance(
        id = "candidate-${sourcePageUrl.hashCode()}-${state.name}",
        title = "candidate",
        imageUrl = "https://images.example.test/candidate.jpg",
        sourcePageUrl = sourcePageUrl,
        source = "fixture",
        acquisitionQuery = "fixture",
        comparisonScore = comparisonScore,
        state = state,
        accountLinkages = listOfNotNull(linkage)
    )

    private fun result(candidate: ReverseImageLookupResult.ImageCandidateProvenance) =
        ReverseImageLookupResult(
            gps = null,
            extractedText = null,
            labels = emptyList(),
            faceDetected = false,
            faceWarning = null,
            resolvedLocation = null,
            mapsUrl = null,
            webEvidence = emptyList(),
            visualCandidates = listOf(candidate)
        )
}
