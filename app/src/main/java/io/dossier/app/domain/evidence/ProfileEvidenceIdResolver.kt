package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.ProfileScanResult

/**
 * Resolves the persisted evidence record for one profile observation without
 * deriving a new ID or making an ownership inference.
 *
 * ProfileScanResult predates the universal Evidence model and therefore does
 * not carry an evidence ID itself. A correction is safe only when exactly one
 * persisted Profile record has the same candidate URL in its value or source
 * URL. Ambiguous or missing records fail closed so a decision cannot be
 * attached to an unrelated profile.
 */
fun ProfileScanResult.persistedEvidenceId(
    evidenceRecords: List<Evidence>
): String? {
    val candidateUrl = candidate.url.trim()
    if (candidateUrl.isBlank()) return null

    val matches = evidenceRecords
        .asSequence()
        .filter { record ->
            record.kind == EvidenceKind.Profile &&
                (record.value.trim() == candidateUrl || record.sourceUrl?.trim() == candidateUrl)
        }
        .map(Evidence::id)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    return matches.singleOrNull()
}
