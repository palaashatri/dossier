package io.dossier.app.graph

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphEntityKind
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.RelationshipType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphSchemaV2Test {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun legacyRelationshipStringsMapToTypedRelationships() {
        assertEquals(RelationshipType.HAS_EMAIL, DossierEdge("s", "e", "has_email").relationType)
        assertEquals(RelationshipType.USES_ACCOUNT, DossierEdge("s", "p", "has_profile").relationType)
        assertEquals(RelationshipType.VISUALLY_SIMILAR_TO, DossierEdge("s", "i", "face_similar_to").relationType)
        assertEquals(RelationshipType.OTHER, DossierEdge("s", "x", "legacy_unknown").relationType)
    }

    @Test
    fun graphCarriesV2KindWithoutDestroyingLegacyType() {
        val subject = DossierEntity(
            id = "person:jane",
            type = EntityType.Person,
            label = "Jane",
            confidence = 1f,
            kind = GraphEntityKind.Subject,
            state = GraphNodeState.Confirmed,
            evidenceIds = listOf("E1")
        )
        val graph = EntityGraph(entities = listOf(subject))

        assertEquals(2, graph.schemaVersion)
        assertEquals(EntityType.Person, graph.entity(subject.id)?.type)
        assertEquals(GraphEntityKind.Subject, graph.entity(subject.id)?.kind)
        assertEquals(listOf("E1"), graph.entity(subject.id)?.evidenceIds)
    }

    @Test
    fun graphQueriesExposeHistoricalAndConflictingState() {
        val subject = DossierEntity("person:jane", EntityType.Person, "Jane")
        val historical = DossierEntity(
            id = "profile:old",
            type = EntityType.Profile,
            label = "Old profile",
            kind = GraphEntityKind.ArchiveSnapshot,
            historical = true
        )
        val conflict = DossierEntity(
            id = "profile:conflict",
            type = EntityType.Profile,
            label = "Conflicting profile",
            kind = GraphEntityKind.Account,
            state = GraphNodeState.Conflicting
        )
        val edge = DossierEdge(
            fromId = subject.id,
            toId = historical.id,
            relation = "archived_as",
            relationType = RelationshipType.ARCHIVED_AS,
            evidenceIds = listOf("E42"),
            historical = true
        )
        val graph = EntityGraph(listOf(subject, historical, conflict), listOf(edge))

        assertEquals(listOf(historical), graph.historicalEntities())
        assertEquals(listOf(conflict), graph.conflictingEntities())
        assertEquals(listOf(edge), graph.outgoing(subject.id))
        assertEquals(listOf(edge), graph.incoming(historical.id))
    }

    @Test
    fun graphQueriesExposePositiveAndContradictingEvidenceLinks() {
        val subject = DossierEntity(
            id = "person:jane",
            type = EntityType.Person,
            label = "Jane",
            evidenceIds = listOf("E1")
        )
        val profile = DossierEntity(
            id = "profile:one",
            type = EntityType.Profile,
            label = "Profile",
            evidenceIds = listOf("E2")
        )
        val edge = DossierEdge(
            fromId = subject.id,
            toId = profile.id,
            relation = "has_profile",
            evidenceIds = listOf("E2"),
            contradictingEvidenceIds = listOf("E9")
        )
        val graph = EntityGraph(listOf(subject, profile), listOf(edge))

        assertEquals(listOf(edge), graph.edgesWithEvidence("E2"))
        assertEquals(listOf(edge), graph.edgesWithEvidence("E9"))
        assertTrue(graph.edgesWithEvidence("missing").isEmpty())
        assertEquals(listOf(subject), graph.entitiesWithEvidence("E1"))
        assertEquals(listOf(profile), graph.entitiesWithEvidence("E2"))
    }

    @Test
    fun legacyRelationshipNamesMapToTypedV2Relationships() {
        assertEquals(RelationshipType.ARCHIVED_AS, RelationshipType.fromLegacy("archived_as"))
        assertEquals(RelationshipType.SAME_IMAGE_AS, RelationshipType.fromLegacy("same_image_content"))
        assertEquals(RelationshipType.SIMILAR_IMAGE_TO, RelationshipType.fromLegacy("perceptual_near_duplicate"))
        assertEquals(RelationshipType.CLAIMS_IDENTITY, RelationshipType.fromLegacy("claims_identity"))
        assertEquals(RelationshipType.LINKS_TO, RelationshipType.fromLegacy("links_to"))
    }

    @Test
    fun v2GraphMetadataRoundTrips() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity(
                    id = "profile:a",
                    type = EntityType.Profile,
                    label = "A",
                    kind = GraphEntityKind.Account,
                    state = GraphNodeState.High,
                    evidenceIds = listOf("E1", "E2")
                )
            ),
            edges = listOf(
                DossierEdge(
                    fromId = "person:s",
                    toId = "profile:a",
                    relation = "has_profile",
                    relationType = RelationshipType.USES_ACCOUNT,
                    evidenceIds = listOf("E1", "E2"),
                    contradictingEvidenceIds = listOf("E9"),
                    confidence = 0.82f
                )
            )
        )
        val decoded = json.decodeFromString<EntityGraph>(json.encodeToString(graph))
        assertEquals(graph, decoded)
        assertTrue(decoded.edges.first().contradictingEvidenceIds.isNotEmpty())
    }
}
