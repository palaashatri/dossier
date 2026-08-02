package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                DossierEntity("email:a@example.test", EntityType.Email, "a@example.test", 0.9f)
            ),
            edges = listOf(DossierEdge("person:x", "email:a@example.test", "has_email"))
        )
        val evidence = listOf(
            Evidence(id = "e1", kind = EvidenceKind.Username, value = "x"),
            Evidence(id = "e2", kind = EvidenceKind.Email, value = "a@example.test")
        )
        val scored = engine.score(graph, evidence)
        assertEquals(true, scored.isEmpty())
    }

    @Test
    fun fallsBackToSyntheticEvidenceFromEntityLabel() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("person:sampleuser", EntityType.Person, "sampleuser", 1.0f),
                DossierEntity("username:sample_user", EntityType.Username, "sample_user", 0.85f)
            ),
            edges = listOf(
                DossierEdge("person:sampleuser", "username:sample_user", "uses_username")
            )
        )
        val scored = engine.score(graph, emptyList())
        assertEquals(false, scored.isEmpty())
    }
}
