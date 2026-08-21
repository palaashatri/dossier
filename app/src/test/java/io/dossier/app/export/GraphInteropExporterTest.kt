package io.dossier.app.export

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import org.junit.Assert.assertFalse
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
}
