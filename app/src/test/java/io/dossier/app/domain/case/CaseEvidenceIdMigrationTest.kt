package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseEvidenceIdMigrationTest {
    @Test
    fun v3RawEvidenceIdsMigrateAcrossCorrectionsNodesAndEdges() {
        val rawOne = "ev:Email:jane@example.test:https://example.test/contact"
        val rawTwo = "ev:Profile:https://example.test/jane:https://example.test/jane"
        val original = DossierCase(
            schemaVersion = 3,
            caseId = "legacy-v3",
            createdAt = "2026-08-08 00:00",
            subjectName = "Jane Example",
            input = IdentityInput(fullName = "Jane Example"),
            userCorrections = listOf(
                UserCorrection(
                    correctionId = "correction-one",
                    evidenceId = rawOne,
                    decision = UserCorrectionDecision.ThisIsMe,
                    createdAtUtc = "2026-08-08T00:00:00Z"
                )
            ),
            entityGraph = EntityGraph(
                entities = listOf(
                    DossierEntity(
                        id = "profile:test",
                        type = EntityType.Profile,
                        label = "Example",
                        evidenceIds = listOf(rawOne, rawTwo)
                    )
                ),
                edges = listOf(
                    DossierEdge(
                        fromId = "person:test",
                        toId = "profile:test",
                        relation = "has_profile",
                        evidenceIds = listOf(rawOne),
                        contradictingEvidenceIds = listOf(rawTwo)
                    )
                )
            )
        )

        val migrated = CaseEvidenceIdMigration.migrate(original)

        assertEquals(DossierCase.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(EvidenceIdPolicy.migrate(rawOne), migrated.userCorrections.single().evidenceId)
        assertEquals(
            listOf(EvidenceIdPolicy.migrate(rawOne), EvidenceIdPolicy.migrate(rawTwo)),
            migrated.entityGraph.entities.single().evidenceIds
        )
        assertEquals(
            listOf(EvidenceIdPolicy.migrate(rawOne)),
            migrated.entityGraph.edges.single().evidenceIds
        )
        assertEquals(
            listOf(EvidenceIdPolicy.migrate(rawTwo)),
            migrated.entityGraph.edges.single().contradictingEvidenceIds
        )
        val serializedIds = buildList {
            addAll(migrated.entityGraph.entities.flatMap { it.evidenceIds })
            addAll(migrated.entityGraph.edges.flatMap { it.evidenceIds })
            addAll(migrated.entityGraph.edges.flatMap { it.contradictingEvidenceIds })
            addAll(migrated.userCorrections.mapNotNull { it.evidenceId })
        }
        assertTrue(serializedIds.all { it.startsWith("ev2:") })
        assertFalse(serializedIds.any { it.contains("jane@example.test") })
    }
}
