package io.dossier.app.export

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphEntityKind
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.RelationshipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRedactorTest {

    private val sampleGraph = EntityGraph(
        entities = listOf(
            DossierEntity(
                id = "subject-jane-doe",
                type = EntityType.Person,
                label = "Jane Doe",
                confidence = 0.95f,
                sourceUrls = listOf("https://example.test/jane-profile"),
                kind = GraphEntityKind.Subject,
                state = GraphNodeState.Confirmed,
                evidenceIds = listOf("ev-1001", "ev-1002"),
                historical = false
            ),
            DossierEntity(
                id = "profile-jane-x",
                type = EntityType.Profile,
                label = "@janedoe_official",
                confidence = 0.88f,
                sourceUrls = listOf("https://x.example.test/janedoe"),
                kind = GraphEntityKind.Account,
                state = GraphNodeState.High,
                evidenceIds = listOf("ev-2001"),
                historical = false
            ),
            DossierEntity(
                id = "email-jane-personal",
                type = EntityType.Email,
                label = "jane.doe@private.test",
                confidence = 0.75f,
                sourceUrls = listOf("https://breaches.example.test/dump"),
                kind = GraphEntityKind.Email,
                state = GraphNodeState.Medium,
                evidenceIds = listOf("ev-3001"),
                historical = true
            )
        ),
        edges = listOf(
            DossierEdge(
                fromId = "subject-jane-doe",
                toId = "profile-jane-x",
                relation = "USES_ACCOUNT",
                evidence = "Observed bio linking to personal portfolio with handle @janedoe_official",
                relationType = RelationshipType.USES_ACCOUNT,
                evidenceIds = listOf("ev-1001", "ev-2001"),
                contradictingEvidenceIds = listOf("ev-9999"),
                confidence = 0.88f,
                historical = false
            ),
            DossierEdge(
                fromId = "profile-jane-x",
                toId = "email-jane-personal",
                relation = "APPEARED_IN_BREACH",
                evidence = "Credential dump contains jane.doe@private.test associated with @janedoe",
                relationType = RelationshipType.APPEARED_IN_BREACH,
                evidenceIds = listOf("ev-3001"),
                confidence = 0.75f,
                historical = true
            )
        )
    )

    @Test
    fun topologyIsStrictlyPreservedInRedactedProjection() {
        val redacted = GraphRedactor.redact(sampleGraph, ExportRedactionMode.ShareSafe)

        assertEquals("Node count must match", sampleGraph.entities.size, redacted.entities.size)
        assertEquals("Edge count must match", sampleGraph.edges.size, redacted.edges.size)

        // Verify that mapped node IDs correspond exactly to edge endpoints
        val node1OpaqueId = redacted.entities[0].id
        val node2OpaqueId = redacted.entities[1].id
        val node3OpaqueId = redacted.entities[2].id

        assertEquals(node1OpaqueId, redacted.edges[0].fromId)
        assertEquals(node2OpaqueId, redacted.edges[0].toId)
        assertEquals(node2OpaqueId, redacted.edges[1].fromId)
        assertEquals(node3OpaqueId, redacted.edges[1].toId)
    }

    @Test
    fun directIdentifiersAreRemovedFromRedactedProjection() {
        val redacted = GraphRedactor.redact(sampleGraph, ExportRedactionMode.ShareSafe)

        redacted.entities.forEach { entity ->
            assertFalse("ID must not leak raw name", entity.id.contains("jane", ignoreCase = true))
            assertFalse("Label must not leak raw name", entity.label.contains("jane", ignoreCase = true))
            assertFalse("Label must not leak raw email", entity.label.contains("@private.test", ignoreCase = true))
            assertFalse("Label must not leak raw handle", entity.label.contains("official", ignoreCase = true))
            assertTrue("Source URLs must be empty in redacted projection", entity.sourceUrls.isEmpty())
            assertTrue("Evidence IDs must be empty in redacted projection", entity.evidenceIds.isEmpty())
            assertTrue("Opaque ID should follow opaque prefix", entity.id.startsWith("node-"))
        }

        redacted.edges.forEach { edge ->
            assertNull("Evidence text must be stripped from edge", edge.evidence)
            assertTrue("Evidence IDs must be stripped from edge", edge.evidenceIds.isEmpty())
            assertTrue("Contradicting evidence IDs must be stripped from edge", edge.contradictingEvidenceIds.isEmpty())
        }
    }

    @Test
    fun relationMetadataAndNodeTypesArePreserved() {
        val redacted = GraphRedactor.redact(sampleGraph, ExportRedactionMode.ShareSafe)

        assertEquals(EntityType.Person, redacted.entities[0].type)
        assertEquals(GraphEntityKind.Subject, redacted.entities[0].kind)
        assertEquals(GraphNodeState.Confirmed, redacted.entities[0].state)
        assertEquals(0.95f, redacted.entities[0].confidence, 0.001f)
        assertEquals(false, redacted.entities[0].historical)
        assertEquals("[Redacted Subject 1]", redacted.entities[0].label)

        assertEquals(EntityType.Profile, redacted.entities[1].type)
        assertEquals(GraphEntityKind.Account, redacted.entities[1].kind)
        assertEquals(GraphNodeState.High, redacted.entities[1].state)
        assertEquals(0.88f, redacted.entities[1].confidence, 0.001f)
        assertEquals("[Redacted Account 1]", redacted.entities[1].label)

        assertEquals(EntityType.Email, redacted.entities[2].type)
        assertEquals(GraphEntityKind.Email, redacted.entities[2].kind)
        assertEquals(GraphNodeState.Medium, redacted.entities[2].state)
        assertEquals(true, redacted.entities[2].historical)
        assertEquals("[Redacted Email 1]", redacted.entities[2].label)

        assertEquals("USES_ACCOUNT", redacted.edges[0].relation)
        assertEquals(RelationshipType.USES_ACCOUNT, redacted.edges[0].relationType)
        assertEquals(0.88f, redacted.edges[0].confidence ?: 0f, 0.001f)
        assertEquals(false, redacted.edges[0].historical)

        assertEquals("APPEARED_IN_BREACH", redacted.edges[1].relation)
        assertEquals(RelationshipType.APPEARED_IN_BREACH, redacted.edges[1].relationType)
        assertEquals(0.75f, redacted.edges[1].confidence ?: 0f, 0.001f)
        assertEquals(true, redacted.edges[1].historical)
    }

    @Test
    fun redactionIsDeterministic() {
        val run1 = GraphRedactor.redact(sampleGraph, ExportRedactionMode.ShareSafe)
        val run2 = GraphRedactor.redact(sampleGraph, ExportRedactionMode.ShareSafe)

        assertEquals(run1.entities.map { it.id }, run2.entities.map { it.id })
        assertEquals(run1.entities.map { it.label }, run2.entities.map { it.label })
        assertEquals(run1.edges.map { it.fromId to it.toId }, run2.edges.map { it.fromId to it.toId })
    }

    @Test
    fun customSaltProducesDifferentOpaqueIds() {
        val policy1 = GraphRedactionPolicy(salt = "salt-alpha")
        val policy2 = GraphRedactionPolicy(salt = "salt-beta")

        val redacted1 = GraphRedactor.redact(sampleGraph, policy1)
        val redacted2 = GraphRedactor.redact(sampleGraph, policy2)

        assertNotEquals(redacted1.entities[0].id, redacted2.entities[0].id)
        assertNotEquals(redacted1.edges[0].fromId, redacted2.edges[0].fromId)
    }

    @Test
    fun unlistedEdgeEndpointsAreMappedDeterministically() {
        val graphWithExternalEdge = EntityGraph(
            entities = listOf(
                DossierEntity(id = "node-a", type = EntityType.Person, label = "Person A")
            ),
            edges = listOf(
                DossierEdge(
                    fromId = "node-a",
                    toId = "unlisted-external-entity",
                    relation = "LINKS_TO",
                    relationType = RelationshipType.LINKS_TO
                )
            )
        )

        val redacted = GraphRedactor.redact(graphWithExternalEdge, ExportRedactionMode.ShareSafe)
        val targetNodeId = redacted.edges.single().toId

        assertTrue(targetNodeId.startsWith("node-"))
        assertFalse(targetNodeId.contains("unlisted"))
    }

    @Test
    fun noneRedactionModeLeavesGraphUntouched() {
        val unredacted = GraphRedactor.redact(sampleGraph, ExportRedactionMode.None)
        assertEquals(sampleGraph, unredacted)
    }
}
