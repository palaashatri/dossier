package io.dossier.app.export

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphInteropExporterTest {
    private val graph = EntityGraph(
        entities = listOf(
            DossierEntity(id = "subject", type = EntityType.Person, label = "Jane & Doe", confidence = 0.9f),
            DossierEntity(id = "profile", type = EntityType.Profile, label = "@janedoe", confidence = 0.8f)
        ),
        edges = listOf(
            DossierEdge(fromId = "subject", toId = "profile", relation = "has_profile", confidence = 0.8f)
        )
    )

    @Test
    fun graphMlEscapesLabelsAndKeepsDirectedEdge() {
        val output = GraphInteropExporter.toGraphMl(graph)
        assertTrue(output.contains("Jane &amp; Doe"))
        assertTrue(output.contains("source=\"subject\" target=\"profile\""))
        assertFalse(output.contains("Jane & Doe</data>"))
    }

    @Test
    fun gexfContainsNodesAndEdges() {
        val output = GraphInteropExporter.toGexf(graph)
        assertTrue(output.contains("<node id=\"subject\""))
        assertTrue(output.contains("source=\"subject\" target=\"profile\""))
    }

    @Test
    fun nodeLinkJsonCarriesRelationshipType() {
        val output = GraphInteropExporter.toNodeLinkJson(graph)
        assertTrue(output.contains("\"directed\": true"))
        assertTrue(output.contains("\"source\": \"subject\""))
        assertTrue(output.contains("\"target\": \"profile\""))
        assertTrue(output.contains("\"relationType\""))
    }

    @Test
    fun redactPreservesTopologyAndNodeMetadataWhileRemovingPII() {
        val richGraph = EntityGraph(
            entities = listOf(
                DossierEntity(
                    id = "subject-jane",
                    type = EntityType.Person,
                    label = "Jane Doe",
                    confidence = 0.95f,
                    sourceUrls = listOf("https://profile.example.test/jane"),
                    evidenceIds = listOf("ev-1", "ev-2"),
                    historical = false
                ),
                DossierEntity(
                    id = "profile-jane-x",
                    type = EntityType.Profile,
                    label = "@janedoe",
                    confidence = 0.85f,
                    sourceUrls = listOf("https://x.com/janedoe"),
                    evidenceIds = listOf("ev-3"),
                    historical = true
                )
            ),
            edges = listOf(
                DossierEdge(
                    fromId = "subject-jane",
                    toId = "profile-jane-x",
                    relation = "has_profile",
                    evidence = "Found on profile: Jane Doe",
                    evidenceIds = listOf("ev-1"),
                    contradictingEvidenceIds = listOf("ev-9"),
                    confidence = 0.85f,
                    historical = true
                )
            )
        )

        val redacted = GraphInteropExporter.redact(richGraph, ExportRedactionMode.ShareSafe)

        // Topology invariants
        assertEquals(richGraph.entities.size, redacted.entities.size)
        assertEquals(richGraph.edges.size, redacted.edges.size)
        val redactedSubject = redacted.entities[0]
        val redactedProfile = redacted.entities[1]
        val redactedEdge = redacted.edges[0]

        // Edges connect mapped opaque IDs
        assertEquals(redactedSubject.id, redactedEdge.fromId)
        assertEquals(redactedProfile.id, redactedEdge.toId)

        // Opaque IDs
        assertTrue(redactedSubject.id.startsWith("node-subject-"))
        assertTrue(redactedProfile.id.startsWith("node-account-"))
        assertFalse(redactedSubject.id.contains("jane"))
        assertFalse(redactedProfile.id.contains("jane"))

        // Labels redacted
        assertEquals("[Redacted Subject 1]", redactedSubject.label)
        assertEquals("[Redacted Account 1]", redactedProfile.label)

        // PII and evidence identifiers stripped
        assertTrue(redactedSubject.sourceUrls.isEmpty())
        assertTrue(redactedSubject.evidenceIds.isEmpty())
        assertTrue(redactedProfile.sourceUrls.isEmpty())
        assertTrue(redactedProfile.evidenceIds.isEmpty())
        assertNull(redactedEdge.evidence)
        assertTrue(redactedEdge.evidenceIds.isEmpty())
        assertTrue(redactedEdge.contradictingEvidenceIds.isEmpty())

        // Metadata preserved
        assertEquals(0.95f, redactedSubject.confidence, 0.001f)
        assertEquals(0.85f, redactedProfile.confidence, 0.001f)
        assertFalse(redactedSubject.historical)
        assertTrue(redactedProfile.historical)
        assertEquals(io.dossier.app.domain.model.RelationshipType.USES_ACCOUNT, redactedEdge.relationType)
        assertEquals(0.85f, redactedEdge.confidence ?: 0f, 0.001f)
        assertTrue(redactedEdge.historical)
    }

    @Test
    fun redactIsDeterministicAndOffline() {
        val redacted1 = GraphInteropExporter.redact(graph, ExportRedactionMode.ShareSafe)
        val redacted2 = GraphInteropExporter.redact(graph, ExportRedactionMode.ShareSafe)

        assertEquals(redacted1.entities.map { it.id }, redacted2.entities.map { it.id })
        assertEquals(redacted1.entities.map { it.label }, redacted2.entities.map { it.label })
        assertEquals(redacted1.edges.map { it.fromId to it.toId }, redacted2.edges.map { it.fromId to it.toId })
    }

    @Test
    fun redactWithModeNoneLeavesGraphUnchanged() {
        val unchanged = GraphInteropExporter.redact(graph, ExportRedactionMode.None)
        assertEquals(graph, unchanged)
    }

    @Test
    fun shareSafeSerializersProduceRedactedOutput() {
        val graphMl = GraphInteropExporter.toGraphMl(graph, ExportRedactionMode.ShareSafe)
        assertTrue(graphMl.contains("[Redacted Subject 1]"))
        assertTrue(graphMl.contains("[Redacted Account 1]"))
        assertFalse(graphMl.contains("Jane"))
        assertFalse(graphMl.contains("@janedoe"))

        val gexf = GraphInteropExporter.toGexf(graph, ExportRedactionMode.ShareSafe)
        assertTrue(gexf.contains("[Redacted Subject 1]"))
        assertFalse(gexf.contains("Jane"))

        val nodeLinkJson = GraphInteropExporter.toNodeLinkJson(graph, ExportRedactionMode.ShareSafe)
        assertTrue(nodeLinkJson.contains("[Redacted Subject 1]"))
        assertFalse(nodeLinkJson.contains("Jane"))
        assertTrue(nodeLinkJson.contains("\"evidenceIds\": []"))
        assertTrue(nodeLinkJson.contains("\"sourceUrls\": []"))
    }
}
