package io.dossier.app.domain.graph

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEvidenceReconciliationTest {
    @Test
    fun exactNormalizedEndpointRelationAndEvidenceIdsMatch() {
        val relationship = EvidenceRelationship(
            fromValue = " Alice ",
            toValue = "Profile",
            relation = " mentions ",
            evidenceIds = listOf("ev2:profile-observation")
        )
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:alice",
                toId = "profile:alice",
                relation = "MENTIONS",
                evidenceIds = listOf("ev2:profile-observation")
            ),
            entities = listOf(
                DossierEntity("person:alice", EntityType.Person, "alice"),
                DossierEntity("profile:alice", EntityType.Profile, " profile ")
            )
        )

        val report = GraphEvidenceReconciliation.validate(
            DossierCase(
                createdAt = "2026-08-25T00:00:00Z",
                subjectName = "Alice",
                input = IdentityInput(fullName = "Alice"),
                evidenceRelationships = listOf(relationship),
                entityGraph = graph
            )
        )

        assertTrue(report.isConsistent)
        assertEquals(1, report.matchedRelationships)
        assertTrue(report.diagnostics.isEmpty())
    }

    @Test
    fun explicitEvidenceLedgerFlagsDanglingCanonicalAndGraphIds() {
        val danglingId = "ev2:missing-proof"
        val relationship = EvidenceRelationship(
            fromValue = "Alice",
            toValue = "Profile",
            relation = "MENTIONS",
            evidenceIds = listOf(danglingId)
        )
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:alice",
                toId = "profile:alice",
                relation = "MENTIONS",
                evidenceIds = listOf(danglingId)
            ),
            entities = listOf(
                DossierEntity("person:alice", EntityType.Person, "Alice"),
                DossierEntity("profile:alice", EntityType.Profile, "Profile")
            )
        )

        val report = GraphEvidenceReconciliation.validate(
            canonicalRelationships = listOf(relationship),
            graph = graph,
            evidenceRecords = listOf(
                Evidence(
                    id = "ev2:present-proof",
                    kind = EvidenceKind.Profile,
                    value = "Profile"
                )
            )
        )

        assertEquals(1, report.matchedRelationships)
        assertEquals(1, report.danglingCanonicalEvidenceIds)
        assertEquals(1, report.danglingGraphEvidenceIds)
        assertFalse(report.isConsistent)
        assertEquals(
            2,
            report.diagnostics.count {
                it.kind == GraphEvidenceReconciliationKind.DanglingEvidenceReference
            }
        )
        assertTrue(report.diagnostics.any { danglingId in it.canonicalEvidenceIds })
        assertTrue(report.diagnostics.any { danglingId in it.graphEvidenceIds })
    }

    @Test
    fun explicitEvidenceLedgerMigratesLegacyIdsBeforeCheckingReferences() {
        val legacyId = "ev:Profile:Profile:https://example.test/profile"
        val migratedId = EvidenceIdPolicy.migrate(legacyId)
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:alice",
                toId = "profile:alice",
                relation = "MENTIONS",
                evidenceIds = listOf(legacyId)
            ),
            entities = listOf(
                DossierEntity("person:alice", EntityType.Person, "Alice"),
                DossierEntity("profile:alice", EntityType.Profile, "Profile")
            )
        )

        val report = GraphEvidenceReconciliation.validate(
            canonicalRelationships = listOf(
                EvidenceRelationship(
                    fromValue = "Alice",
                    toValue = "Profile",
                    relation = "MENTIONS",
                    evidenceIds = listOf(legacyId)
                )
            ),
            graph = graph,
            evidenceRecords = listOf(
                Evidence(
                    id = migratedId,
                    kind = EvidenceKind.Profile,
                    value = "Profile",
                    sourceUrl = "https://example.test/profile"
                )
            )
        )

        assertTrue(report.isConsistent)
        assertEquals(0, report.danglingCanonicalEvidenceIds)
        assertEquals(0, report.danglingGraphEvidenceIds)
    }

    @Test
    fun graphOnlyLegacyEdgeIsReportedExtraAndRemainsUnresolved() {
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:legacy",
                toId = "profile:legacy",
                relation = "MENTIONS",
                evidenceIds = listOf("ev2:legacy")
            ),
            entities = listOf(
                DossierEntity("person:legacy", EntityType.Person, "Legacy subject"),
                DossierEntity("profile:legacy", EntityType.Profile, "https://example.test/legacy")
            )
        )
        val case = DossierCase(
            createdAt = "2026-08-25T00:00:00Z",
            subjectName = "Legacy subject",
            input = IdentityInput(fullName = "Legacy subject"),
            entityGraph = graph
        )

        val report = case.graphEvidenceReconciliation()

        assertFalse(report.isConsistent)
        assertEquals(0, report.matchedRelationships)
        assertEquals(1, report.extraGraphEdges)
        assertEquals(GraphEvidenceReconciliationKind.ExtraGraphEdge, report.diagnostics.single().kind)
        assertTrue(case.evidenceRelationships.isEmpty())
        assertTrue(case.canonicalEvidenceRelationships().isEmpty())
    }

    @Test
    fun canonicalCaseReadUsesPersistedAssertionsWithoutRewritingOrGraphFallback() {
        val first = EvidenceRelationship(
            fromValue = "Alice",
            toValue = "Profile",
            relation = "MENTIONS",
            evidence = "first assertion",
            evidenceIds = listOf("ev2:first")
        )
        val duplicate = first.copy(
            evidence = "second assertion",
            evidenceIds = listOf("ev2:second")
        )
        val case = DossierCase(
            createdAt = "2026-08-25T00:00:00Z",
            subjectName = "Alice",
            input = IdentityInput(fullName = "Alice"),
            evidenceRelationships = listOf(first, duplicate)
        )

        assertEquals(listOf(first, duplicate), case.canonicalEvidenceRelationships())
        val report = case.graphEvidenceReconciliation()

        assertFalse(report.isConsistent)
        assertEquals(1, report.ambiguousRelationships)
        assertEquals(GraphEvidenceReconciliationKind.Ambiguous, report.diagnostics.single().kind)
        assertEquals(listOf(first, duplicate), case.evidenceRelationships)
    }

    @Test
    fun exactEndpointMatchWithDifferentEvidenceIdsIsConflicting() {
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:alice",
                toId = "profile:alice",
                relation = "MENTIONS",
                evidenceIds = listOf("ev2:graph")
            ),
            entities = listOf(
                DossierEntity("person:alice", EntityType.Person, "Alice"),
                DossierEntity("profile:alice", EntityType.Profile, "Profile")
            )
        )
        val report = GraphEvidenceReconciliation.validate(
            listOf(
                EvidenceRelationship(
                    fromValue = "Alice",
                    toValue = "Profile",
                    relation = "MENTIONS",
                    evidenceIds = listOf("ev2:canonical")
                )
            ),
            graph
        )

        assertEquals(1, report.conflictingEvidence)
        assertEquals(GraphEvidenceReconciliationKind.ConflictingEvidence, report.diagnostics.single().kind)
        assertEquals(listOf("ev2:canonical"), report.diagnostics.single().canonicalEvidenceIds)
        assertEquals(listOf("ev2:graph"), report.diagnostics.single().graphEvidenceIds)
    }

    @Test
    fun idlessExactEndpointMatchIsAmbiguousRatherThanConsistent() {
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:alice",
                toId = "profile:alice",
                relation = "MENTIONS"
            ),
            entities = listOf(
                DossierEntity("person:alice", EntityType.Person, "Alice"),
                DossierEntity("profile:alice", EntityType.Profile, "Profile")
            )
        )

        val report = GraphEvidenceReconciliation.validate(
            listOf(EvidenceRelationship("Alice", "Profile", "MENTIONS")),
            graph
        )

        assertFalse(report.isConsistent)
        assertEquals(1, report.ambiguousRelationships)
        assertEquals(GraphEvidenceReconciliationKind.Ambiguous, report.diagnostics.single().kind)
    }

    @Test
    fun idlessGraphOnlyEdgeIsAmbiguousRatherThanClaimedAsCanonical() {
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:legacy",
                toId = "profile:legacy",
                relation = "MENTIONS"
            ),
            entities = listOf(
                DossierEntity("person:legacy", EntityType.Person, "Legacy subject"),
                DossierEntity("profile:legacy", EntityType.Profile, "https://example.test/legacy")
            )
        )

        val report = GraphEvidenceReconciliation.validate(emptyList(), graph)

        assertFalse(report.isConsistent)
        assertEquals(0, report.extraGraphEdges)
        assertEquals(1, report.ambiguousRelationships)
        assertEquals(GraphEvidenceReconciliationKind.Ambiguous, report.diagnostics.single().kind)
    }

    @Test
    fun duplicateGraphEdgesAreAmbiguousAndDiagnosticsAreBoundedDeterministically() {
        val entities = listOf(
            DossierEntity("person:one", EntityType.Person, "Alice"),
            DossierEntity("person:two", EntityType.Person, " Alice "),
            DossierEntity("profile:one", EntityType.Profile, "Profile")
        )
        val graph = EntityGraph(
            entities = entities,
            edges = listOf(
                DossierEdge("person:one", "profile:one", "MENTIONS", evidenceIds = listOf("ev2:one")),
                DossierEdge("person:two", "profile:one", "mentions", evidenceIds = listOf("ev2:two"))
            )
        )
        val canonical = listOf(
            EvidenceRelationship("Alice", "Profile", "MENTIONS", evidenceIds = listOf("ev2:canonical"))
        )

        val first = GraphEvidenceReconciliation.validate(canonical, graph)
        val second = GraphEvidenceReconciliation.validate(canonical, graph)

        assertEquals(1, first.ambiguousRelationships)
        assertEquals(GraphEvidenceReconciliationKind.Ambiguous, first.diagnostics.single().kind)
        assertEquals(first.diagnostics, second.diagnostics)
        assertTrue(first.diagnostics.size <= GraphEvidenceReconciliation.MAX_DIAGNOSTICS)
    }

    @Test
    fun missingDiagnosticsAreCappedWithoutChangingDeterministicCounts() {
        val canonical = List(GraphEvidenceReconciliation.MAX_DIAGNOSTICS + 8) { index ->
            EvidenceRelationship(
                fromValue = "subject-$index",
                toValue = "profile-$index",
                relation = "LINKS_TO",
                evidenceIds = listOf("ev2:canonical-$index")
            )
        }

        val first = GraphEvidenceReconciliation.validate(canonical, EntityGraph())
        val second = GraphEvidenceReconciliation.validate(canonical, EntityGraph())

        assertEquals(canonical.size, first.missingGraphEdges)
        assertEquals(GraphEvidenceReconciliation.MAX_DIAGNOSTICS, first.diagnostics.size)
        assertEquals(first.diagnostics, second.diagnostics)
        assertEquals(0, first.truncatedCanonicalRelationships)
    }

    @Test
    fun oversizedEvidenceIdSetsAreBoundedAndFailClosed() {
        val evidenceIds = List(GraphEvidenceReconciliation.MAX_EVIDENCE_IDS_PER_DIAGNOSTIC + 1) { index ->
            "ev2:oversized-$index"
        }
        val canonical = EvidenceRelationship(
            fromValue = "Alice",
            toValue = "Profile",
            relation = "MENTIONS",
            evidenceIds = evidenceIds
        )
        val graph = graph(
            edge = DossierEdge(
                fromId = "person:alice",
                toId = "profile:alice",
                relation = "MENTIONS",
                evidenceIds = evidenceIds
            ),
            entities = listOf(
                DossierEntity("person:alice", EntityType.Person, "Alice"),
                DossierEntity("profile:alice", EntityType.Profile, "Profile")
            )
        )

        val report = GraphEvidenceReconciliation.validate(listOf(canonical), graph)

        assertFalse(report.isConsistent)
        assertEquals(0, report.matchedRelationships)
        assertEquals(1, report.ambiguousRelationships)
        assertTrue(report.truncatedCanonicalEvidenceIds > 0)
        assertTrue(report.truncatedGraphEvidenceIds > 0)
        val diagnostic = report.diagnostics.single()
        assertEquals(GraphEvidenceReconciliationKind.Ambiguous, diagnostic.kind)
        assertEquals(GraphEvidenceReconciliation.MAX_EVIDENCE_IDS_PER_DIAGNOSTIC, diagnostic.canonicalEvidenceIds.size)
        assertEquals(GraphEvidenceReconciliation.MAX_EVIDENCE_IDS_PER_DIAGNOSTIC, diagnostic.graphEvidenceIds.size)
        assertTrue(diagnostic.truncatedCanonicalEvidenceIds > 0)
        assertTrue(diagnostic.truncatedGraphEvidenceIds > 0)
    }

    private fun graph(edge: DossierEdge, entities: List<DossierEntity>): EntityGraph =
        EntityGraph(entities = entities, edges = listOf(edge))
}
