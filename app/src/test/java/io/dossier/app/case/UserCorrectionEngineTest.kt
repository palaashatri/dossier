package io.dossier.app.case

import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.case.UserCorrectionEngine
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphNodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserCorrectionEngineTest {
    private val evidence = listOf(
        Evidence(id = "E1", kind = EvidenceKind.Profile, value = "profile-a"),
        Evidence(id = "E2", kind = EvidenceKind.Username, value = "handle-a")
    )
    private val graph = EntityGraph(
        entities = listOf(
            DossierEntity("person:s", EntityType.Person, "Subject", state = GraphNodeState.Confirmed),
            DossierEntity("profile:a", EntityType.Profile, "profile-a", evidenceIds = listOf("E1"))
        ),
        edges = listOf(
            DossierEdge(
                fromId = "person:s",
                toId = "profile:a",
                relation = "has_profile",
                evidenceIds = listOf("E1", "E2"),
                confidence = 0.8f
            )
        )
    )

    @Test
    fun rejectEntityMarksConflictAndPreventsConfidentRelationship() {
        val view = UserCorrectionEngine.apply(
            evidence,
            graph,
            listOf(
                UserCorrection(
                    entityId = "profile:a",
                    decision = UserCorrectionDecision.ThisIsNotMe,
                    createdAtUtc = "2026-08-08T00:00:00Z"
                )
            )
        )

        assertEquals(GraphNodeState.Conflicting, view.graph.entity("profile:a")?.state)
        assertEquals(0f, view.graph.edges.single().confidence ?: -1f, 0.0001f)
        assertTrue("profile:a" in view.rejectedEntityIds)
    }

    @Test
    fun ignoreEvidenceRemovesItOnlyFromEffectiveAnalysisView() {
        val view = UserCorrectionEngine.apply(
            evidence,
            graph,
            listOf(
                UserCorrection(
                    evidenceId = "E1",
                    decision = UserCorrectionDecision.IgnoreEvidence,
                    createdAtUtc = "2026-08-08T00:00:00Z"
                )
            )
        )

        assertEquals(listOf("E2"), view.evidence.map { it.id })
        assertEquals(listOf("E2"), view.graph.edges.single().evidenceIds)
        assertEquals(2, evidence.size)
    }

    @Test
    fun explicitThisIsMeConfirmsEntityAndStrengthensRelationship() {
        val view = UserCorrectionEngine.apply(
            evidence,
            graph,
            listOf(
                UserCorrection(
                    entityId = "profile:a",
                    decision = UserCorrectionDecision.ThisIsMe,
                    createdAtUtc = "2026-08-08T00:00:00Z"
                )
            )
        )

        assertEquals(GraphNodeState.Confirmed, view.graph.entity("profile:a")?.state)
        assertTrue((view.graph.edges.single().confidence ?: 0f) >= 0.95f)
    }

    @Test
    fun evidenceThisIsNotMeIsRetainedButRejected() {
        val view = UserCorrectionEngine.apply(
            evidence,
            graph,
            listOf(
                UserCorrection(
                    evidenceId = "E1",
                    decision = UserCorrectionDecision.ThisIsNotMe,
                    createdAtUtc = "2026-08-08T00:00:00Z"
                )
            )
        )

        val retained = view.evidence.first { it.id == "E1" }
        assertEquals(EvidenceState.Rejected, retained.state)
    }
}
