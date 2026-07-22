package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfidenceEngineTest {

    private val engine = ConfidenceEngine(listOf(UsernameSimilarityContributor()))

    @Test
    fun scoresUsernameEdgeFromEvidence() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("person:janedoe", EntityType.Person, "janedoe", 1.0f),
                DossierEntity("username:jane.doe", EntityType.Username, "jane.doe", 0.85f)
            ),
            edges = listOf(
                DossierEdge("person:janedoe", "username:jane.doe", "uses_username")
            )
        )
        val evidence = listOf(
            Evidence(id = "e1", kind = EvidenceKind.Username, value = "janedoe"),
            Evidence(id = "e2", kind = EvidenceKind.Username, value = "jane.doe")
        )
        val scored = engine.score(graph, evidence)
        val key = ConfidenceEngine.edgeKey("person:janedoe", "username:jane.doe", "uses_username")
        val result = scored[key]
        assertNotNull(result)
        assertEquals(0.85f, result!!.score, 1e-6f)
        assertEquals(true, result.reasons.any { it.contains("separators") })
    }

    @Test
    fun ignoresEdgesWithNoContributor() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("person:x", EntityType.Person, "x", 1.0f),
                DossierEntity("email:a@b.com", EntityType.Email, "a@b.com", 0.9f)
            ),
            edges = listOf(DossierEdge("person:x", "email:a@b.com", "has_email"))
        )
        val evidence = listOf(
            Evidence(id = "e1", kind = EvidenceKind.Username, value = "x"),
            Evidence(id = "e2", kind = EvidenceKind.Email, value = "a@b.com")
        )
        val scored = engine.score(graph, evidence)
        // No contributor fires for username↔email, so the edge is unscored.
        assertEquals(true, scored.isEmpty())
    }

    @Test
    fun fallsBackToSyntheticEvidenceFromEntityLabel() {
        // Evidence list has no matching value, but the engine should still derive
        // a username-kind evidence from the entity label and score the edge.
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("person:palaashatri", EntityType.Person, "palaashatri", 1.0f),
                DossierEntity("username:palaash_atri", EntityType.Username, "palaash_atri", 0.85f)
            ),
            edges = listOf(DossierEdge("person:palaashatri", "username:palaash_atri", "uses_username"))
        )
        val scored = engine.score(graph, emptyList())
        assertEquals(false, scored.isEmpty())
    }
}
