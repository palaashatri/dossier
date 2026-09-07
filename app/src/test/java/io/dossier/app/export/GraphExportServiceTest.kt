package io.dossier.app.export

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphEntityKind
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.IdentityInput
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

    @Test
    fun caseBundleKeepsCanonicalAssertionsInSeparateSidecar() {
        val outputDir = tempFolder.newFolder("case-exports")
        val canonical = listOf(
            EvidenceRelationship(
                fromValue = "Jane Doe",
                toValue = "@janedoe",
                relation = "USES_ACCOUNT",
                evidence = "Scanner observed the public profile link",
                evidenceIds = listOf("ev2:profile")
            )
        )
        val case = DossierCase(
            createdAt = "2026-08-25T00:00:00Z",
            subjectName = "Jane Doe",
            input = IdentityInput(fullName = "Jane Doe"),
            evidenceRelationships = canonical,
            entityGraph = testGraph
        )

        val bundle = GraphExportService.createBundle(
            directory = outputDir,
            case = case,
            label = "case-alpha",
            redactionMode = ExportRedactionMode.None
        )

        assertFalse(bundle.isRedacted)
        assertNotNull(bundle.canonicalAssertionsCsv)
        assertEquals(5, bundle.files().size)
        assertEquals(testGraph, json.decodeFromString<EntityGraph>(bundle.jsonFile.readText()))

        val assertions = requireNotNull(bundle.canonicalAssertionsCsv).readText()
        assertTrue(assertions.startsWith("from_value,to_value,relation,evidence,evidence_ids"))
        assertTrue(assertions.contains("Jane Doe"))
        assertTrue(assertions.contains("@janedoe"))
        assertTrue(assertions.contains("ev2:profile"))
        assertTrue(assertions.contains("Scanner observed the public profile link"))
        // The graph export remains the EntityGraph projection; the sidecar is
        // the only file carrying the separate canonical assertion record.
        assertFalse(bundle.edgesCsv.readText().contains("Scanner observed the public profile link"))
    }

    @Test
    fun shareSafeCaseBundleRedactsCanonicalAssertionValuesAndEvidence() {
        val outputDir = tempFolder.newFolder("case-redacted-exports")
        val case = DossierCase(
            createdAt = "2026-08-25T00:00:00Z",
            subjectName = "Jane Doe",
            input = IdentityInput(fullName = "Jane Doe"),
            evidenceRelationships = listOf(
                EvidenceRelationship(
                    fromValue = "Jane Doe",
                    toValue = "@janedoe",
                    relation = "USES_ACCOUNT",
                    evidence = "https://example.test/jane",
                    evidenceIds = listOf("ev2:profile")
                ),
                EvidenceRelationship(
                    fromValue = "@janedoe",
                    toValue = "Jane Doe",
                    relation = "CLAIMS_IDENTITY",
                    evidence = "private-looking snippet",
                    evidenceIds = listOf("ev2:claim")
                )
            ),
            entityGraph = testGraph
        )

        val bundle = GraphExportService.createBundle(
            directory = outputDir,
            case = case,
            label = "case-alpha",
            redactionMode = ExportRedactionMode.ShareSafe
        )

        assertTrue(bundle.isRedacted)
        val assertions = requireNotNull(bundle.canonicalAssertionsCsv).readText()
        assertFalse(assertions.contains("Jane Doe"))
        assertFalse(assertions.contains("@janedoe"))
        assertFalse(assertions.contains("example.test"))
        assertFalse(assertions.contains("private-looking snippet"))
        assertFalse(assertions.contains("ev2:profile"))
        assertFalse(assertions.contains("ev2:claim"))
        assertTrue(assertions.contains("[Redacted Assertion Endpoint 1]"))
        assertTrue(assertions.contains("USES_ACCOUNT"))
        assertTrue(assertions.contains("CLAIMS_IDENTITY"))
    }
}
