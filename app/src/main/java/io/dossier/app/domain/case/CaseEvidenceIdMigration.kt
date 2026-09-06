package io.dossier.app.domain.case

import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.ExposureLedger
import io.dossier.app.domain.evidence.ExposureLedgerPolicy

/** Pure metadata migration used by encrypted case loading/saving and JVM tests. */
object CaseEvidenceIdMigration {
    fun migrate(case: DossierCase): DossierCase {
        val migratedEvidence = case.evidenceRecords
            .map { evidence ->
                val migratedId = EvidenceIdPolicy.migrate(evidence.id)
                if (migratedId == evidence.id) evidence else evidence.copy(id = migratedId)
            }
            .distinctBy { it.id }
            .take(MAX_EVIDENCE_RECORDS)
        val migratedRelationships = EvidenceRelationshipPolicy.normalize(case.evidenceRelationships)
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
        val migratedLedger = ExposureLedgerPolicy.normalize(case.exposureLedger.facts)
            .let { facts -> ExposureLedgerPolicy.hydrateFromEvidence(facts, migratedEvidence) }
        return case.copy(
            schemaVersion = DossierCase.CURRENT_SCHEMA_VERSION,
            evidenceRecords = migratedEvidence,
            exposureLedger = ExposureLedger(migratedLedger),
            evidenceRelationships = migratedRelationships,
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
