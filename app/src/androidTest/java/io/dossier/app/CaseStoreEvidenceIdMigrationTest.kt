package io.dossier.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.IdentityInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaseStoreEvidenceIdMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = CaseStore(context)

    @After
    fun cleanup() {
        store.clear()
    }

    @Test
    fun saveAndLoadMigratesV3EvidenceIdsToOpaqueV4Ids() {
        val legacyId = "ev:Email:jane@example.test:https://example.test/contact"
        val case = DossierCase(
            schemaVersion = 3,
            caseId = "evidence-id-migration",
            createdAt = "2026-08-08 00:00",
            subjectName = "Jane Example",
            input = IdentityInput(fullName = "Jane Example"),
            userCorrections = listOf(
                UserCorrection(
                    correctionId = "correction-one",
                    evidenceId = legacyId,
                    decision = UserCorrectionDecision.ThisIsMe,
                    createdAtUtc = "2026-08-08T00:00:00Z"
                )
            ),
            entityGraph = EntityGraph(
                entities = listOf(
                    DossierEntity(
                        id = "profile:example",
                        type = EntityType.Profile,
                        label = "Example profile",
                        evidenceIds = listOf(legacyId)
                    )
                ),
                edges = listOf(
                    DossierEdge(
                        fromId = "person:example",
                        toId = "profile:example",
                        relation = "has_profile",
                        evidenceIds = listOf(legacyId),
                        contradictingEvidenceIds = listOf(legacyId)
                    )
                )
            )
        )

        assertTrue(store.save(case))
        val loaded = requireNotNull(store.load(case.caseId))
        val currentId = EvidenceIdPolicy.migrate(legacyId)

        assertEquals(DossierCase.CURRENT_SCHEMA_VERSION, loaded.schemaVersion)
        assertEquals(currentId, loaded.userCorrections.single().evidenceId)
        assertEquals(listOf(currentId), loaded.entityGraph.entities.single().evidenceIds)
        assertEquals(listOf(currentId), loaded.entityGraph.edges.single().evidenceIds)
        assertEquals(listOf(currentId), loaded.entityGraph.edges.single().contradictingEvidenceIds)
        assertTrue(currentId.startsWith("ev2:"))
        assertFalse(currentId.contains("jane@example.test"))
    }
}
