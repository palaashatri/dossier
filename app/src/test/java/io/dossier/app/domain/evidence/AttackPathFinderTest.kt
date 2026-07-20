package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttackPathFinderTest {

    @Test
    fun findsPathFromSubjectToBreach() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("person:x", EntityType.Person, "x", 1.0f),
                DossierEntity("email:a@b.com", EntityType.Email, "a@b.com", 0.9f),
                DossierEntity("breach:a@b.com", EntityType.Breach, "breach a@b.com", 0.95f)
            ),
            edges = listOf(
                DossierEdge("person:x", "email:a@b.com", "has_email"),
                DossierEdge("email:a@b.com", "breach:a@b.com", "exposed_in")
            )
        )
        val paths = AttackPathFinder().findPaths(graph)
        assertEquals(1, paths.size)
        val path = paths.first()
        assertEquals("breach a@b.com", path.endpointLabel)
        assertEquals(2, path.steps.size)
        assertEquals("has_email", path.steps[0].relation)
        assertEquals("exposed_in", path.steps[1].relation)
    }

    @Test
    fun noBreachYieldsNoPaths() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("person:x", EntityType.Person, "x", 1.0f),
                DossierEntity("username:foo", EntityType.Username, "foo", 0.8f)
            ),
            edges = listOf(DossierEdge("person:x", "username:foo", "uses_username"))
        )
        assertTrue(AttackPathFinder().findPaths(graph).isEmpty())
    }

    @Test
    fun attachesConfidenceWhenScored() {
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity("person:x", EntityType.Person, "x", 1.0f),
                DossierEntity("email:a@b.com", EntityType.Email, "a@b.com", 0.9f),
                DossierEntity("breach:a@b.com", EntityType.Breach, "breach a@b.com", 0.95f)
            ),
            edges = listOf(
                DossierEdge("person:x", "email:a@b.com", "has_email"),
                DossierEdge("email:a@b.com", "breach:a@b.com", "exposed_in")
            )
        )
        val conf = mapOf(
            ConfidenceEngine.edgeKey("person:x", "email:a@b.com", "has_email") to
                RelationshipConfidence(0.7f, listOf("seed"))
        )
        val paths = AttackPathFinder().findPaths(graph, conf)
        assertEquals(0.7f, paths.first().steps[0].confidence!!, 1e-6f)
    }
}
