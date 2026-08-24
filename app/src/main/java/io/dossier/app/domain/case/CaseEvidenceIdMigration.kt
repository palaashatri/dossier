package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.EvidenceIdPolicy

/** Pure metadata migration used by encrypted case loading/saving and JVM tests. */
object CaseEvidenceIdMigration {
    fun migrate(case: DossierCase): DossierCase {
        val migratedEvidence = case.evidenceRecords
            .map { evidence -> evidence.copy(id = EvidenceIdPolicy.migrate(evidence.id)) }
            .distinctBy { it.id }
            .take(MAX_EVIDENCE_RECORDS)
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
        val migratedRemediation = case.remediationRecords.map { record ->
            record.copy(evidenceId = record.evidenceId?.let(EvidenceIdPolicy::migrate))
        }
        return case.copy(
            schemaVersion = DossierCase.CURRENT_SCHEMA_VERSION,
            evidenceRecords = migratedEvidence,
            userCorrections = migratedCorrections,
            remediationRecords = migratedRemediation,
            entityGraph = case.entityGraph.copy(
                entities = migratedEntities,
                edges = migratedEdges
            )
        )
    }

    private const val MAX_EVIDENCE_RECORDS = 10_000
}
