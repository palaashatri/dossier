package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.EvidenceIdPolicy

/** Pure metadata migration used by encrypted case loading/saving and JVM tests. */
object CaseEvidenceIdMigration {
    fun migrate(case: DossierCase): DossierCase {
        val migratedCorrections = case.userCorrections.map { correction ->
            correction.copy(evidenceId = correction.evidenceId?.let(EvidenceIdPolicy::migrate))
        }
        val migratedEntities = case.entityGraph.entities.map { entity ->
            entity.copy(evidenceIds = entity.evidenceIds.map(EvidenceIdPolicy::migrate).distinct())
        }
        val migratedEdges = case.entityGraph.edges.map { edge ->
            edge.copy(
                evidenceIds = edge.evidenceIds.map(EvidenceIdPolicy::migrate).distinct(),
                contradictingEvidenceIds = edge.contradictingEvidenceIds
                    .map(EvidenceIdPolicy::migrate)
                    .distinct()
            )
        }
        return case.copy(
            schemaVersion = DossierCase.CURRENT_SCHEMA_VERSION,
            userCorrections = migratedCorrections,
            entityGraph = case.entityGraph.copy(
                entities = migratedEntities,
                edges = migratedEdges
            )
        )
    }
}
