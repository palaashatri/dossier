package io.dossier.app.export

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphEntityKind
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.RelationshipType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GraphExportServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val testGraph = EntityGraph(
        entities = listOf(
            DossierEntity(
                id = "subject-1",
                type = EntityType.Person,
                label = "Jane Doe",
                confidence = 0.95f,
                sourceUrls = listOf("https://example.test/subject"),
                kind = GraphEntityKind.Subject,
                state = GraphNodeState.Confirmed,
                evidenceIds = listOf("ev-sub-1")
            ),
            DossierEntity(
                id = "profile-1",
                type = EntityType.Profile,
                label = "@janedoe",
                confidence = 0.85f,
                sourceUrls = listOf("https://x.example.test/janedoe"),
                kind = GraphEntityKind.Account,
                state = GraphNodeState.High,
                evidenceIds = listOf("ev-prof-1")
            )
        ),
        edges = listOf(
            DossierEdge(
                fromId = "subject-1",
                toId = "profile-1",
                relation = "USES_ACCOUNT",
                evidence = "Direct profile link in user bio",
                relationType = RelationshipType.USES_ACCOUNT,
                evidenceIds = listOf("ev-sub-1", "ev-prof-1"),
                confidence = 0.85f,
                historical = false
            )
        )
    )

    @Test
    fun createBundleRawCreatesAllFourFilesWithRawContent() {
        val outputDir = tempFolder.newFolder("raw-exports")
        val bundle = GraphExportService.createBundle(
            directory = outputDir,
            graph = testGraph,
            label = "case-alpha",
            redactionMode = ExportRedactionMode.None
        )

        assertFalse("Bundle is not redacted", bundle.isRedacted)
        assertEquals(4, bundle.files().size)

        bundle.files().forEach { file ->
            assertTrue("File ${file.name} must exist", file.exists())
            assertTrue("File ${file.name} must not be empty", file.length() > 0)
        }

        // Verify GraphML
        val graphMlContent = bundle.graphMl.readText()
        assertTrue(graphMlContent.contains("<graphml"))
        assertTrue(graphMlContent.contains("Jane Doe"))
        assertTrue(graphMlContent.contains("@janedoe"))
        assertTrue(graphMlContent.contains("source=\"subject-1\" target=\"profile-1\""))

        // Verify Nodes CSV
        val nodesCsvContent = bundle.nodesCsv.readText()
        assertTrue(nodesCsvContent.startsWith("id,label,type,kind,state,confidence,historical,source_urls,evidence_ids"))
        assertTrue(nodesCsvContent.contains("\"subject-1\",\"Jane Doe\""))
        assertTrue(nodesCsvContent.contains("\"https://example.test/subject\""))

        // Verify Edges CSV
        val edgesCsvContent = bundle.edgesCsv.readText()
        assertTrue(edgesCsvContent.startsWith("source,target,relation,relation_type,confidence,historical,evidence,evidence_ids,contradicting_evidence_ids"))
        assertTrue(edgesCsvContent.contains("\"subject-1\",\"profile-1\",\"USES_ACCOUNT\",\"USES_ACCOUNT\""))
        assertTrue(edgesCsvContent.contains("Direct profile link in user bio"))

        // Verify JSON parses back to EntityGraph
        val jsonContent = bundle.jsonFile.readText()
        val parsedGraph = json.decodeFromString<EntityGraph>(jsonContent)
        assertEquals(testGraph.entities.size, parsedGraph.entities.size)
        assertEquals(testGraph.edges.size, parsedGraph.edges.size)
        assertEquals("Jane Doe", parsedGraph.entities[0].label)
    }

    @Test
    fun createBundleShareSafeRedactsIdentifiersAndPreservesTopology() {
        val outputDir = tempFolder.newFolder("redacted-exports")
        val bundle = GraphExportService.createBundle(
            directory = outputDir,
            graph = testGraph,
            label = "case-alpha",
            redactionMode = ExportRedactionMode.ShareSafe
        )

        assertTrue("Bundle is marked as redacted", bundle.isRedacted)
        assertTrue(bundle.graphMl.name.contains("share-safe"))
        assertTrue(bundle.nodesCsv.name.contains("share-safe"))
        assertTrue(bundle.edgesCsv.name.contains("share-safe"))
        assertTrue(bundle.jsonFile.name.contains("share-safe"))

        // Verify GraphML has no raw identifiers
        val graphMlContent = bundle.graphMl.readText()
        assertFalse(graphMlContent.contains("Jane"))
        assertFalse(graphMlContent.contains("@janedoe"))
        assertFalse(graphMlContent.contains("https://"))
        assertTrue(graphMlContent.contains("[Redacted Subject 1]"))
        assertTrue(graphMlContent.contains("[Redacted Account 1]"))
        assertTrue(graphMlContent.contains("node-subject-"))
        assertTrue(graphMlContent.contains("node-account-"))

        // Verify Nodes CSV
        val nodesCsvContent = bundle.nodesCsv.readText()
        assertFalse(nodesCsvContent.contains("Jane"))
        assertFalse(nodesCsvContent.contains("https://"))
        assertFalse(nodesCsvContent.contains("ev-sub-1"))
        assertTrue(nodesCsvContent.contains("\"[Redacted Subject 1]\""))
        assertTrue(nodesCsvContent.contains("\"[Redacted Account 1]\""))

        // Verify Edges CSV
        val edgesCsvContent = bundle.edgesCsv.readText()
        assertFalse(edgesCsvContent.contains("Direct profile link"))
        assertFalse(edgesCsvContent.contains("ev-sub-1"))
        assertTrue(edgesCsvContent.contains("\"USES_ACCOUNT\",\"USES_ACCOUNT\""))

        // Verify JSON parses back to EntityGraph with redacted invariants
        val jsonContent = bundle.jsonFile.readText()
        val parsedGraph = json.decodeFromString<EntityGraph>(jsonContent)
        assertEquals(2, parsedGraph.entities.size)
        assertEquals(1, parsedGraph.edges.size)
        assertEquals(parsedGraph.entities[0].id, parsedGraph.edges[0].fromId)
        assertEquals(parsedGraph.entities[1].id, parsedGraph.edges[0].toId)
        assertEquals(RelationshipType.USES_ACCOUNT, parsedGraph.edges[0].relationType)
    }

    @Test
    fun companionFormattersProduceConsistentOutputs() {
        val rawNodes = GraphExportService.nodesCsv(testGraph, ExportRedactionMode.None)
        val redactedNodes = GraphExportService.nodesCsv(testGraph, ExportRedactionMode.ShareSafe)

        assertTrue(rawNodes.contains("Jane Doe"))
        assertFalse(redactedNodes.contains("Jane Doe"))
        assertTrue(redactedNodes.contains("[Redacted Subject 1]"))

        val rawEdges = GraphExportService.edgesCsv(testGraph, ExportRedactionMode.None)
        val redactedEdges = GraphExportService.edgesCsv(testGraph, ExportRedactionMode.ShareSafe)

        assertTrue(rawEdges.contains("Direct profile link in user bio"))
        assertFalse(redactedEdges.contains("Direct profile link in user bio"))
        assertTrue(redactedEdges.contains("USES_ACCOUNT"))
    }
}
