package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.ReverseImageLookupResult

/**
 * Resolves the existing profile-evidence record behind a media candidate.
 *
 * A media candidate is not itself an Evidence record, and its stable candidate
 * id must never be repurposed as one. Corrections are therefore available only
 * for the narrow, directly verified-profile linkage that already declares one
 * evidence id. Every part of that linkage must match one persisted Profile
 * record exactly; missing, duplicate, or mismatched provenance fails closed.
 */
fun ReverseImageLookupResult.ImageCandidateProvenance.persistedLinkedProfileEvidenceId(
    evidenceRecords: List<Evidence>
): String? {
    val candidatePage = sourcePageUrl.trim()
    if (candidatePage.isBlank()) return null

    val linkage = accountLinkages
        .filter { it.basis == ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile }
        .takeIf { it.size == 1 }
        ?.singleOrNull()
        ?: return null
    if (linkage.accountUrl.trim() != candidatePage) return null

    val declaredEvidenceIds = linkage.evidenceIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (declaredEvidenceIds.size != 1) return null
    val declaredEvidenceId = declaredEvidenceIds.single()

    val pageMatches = evidenceRecords
        .asSequence()
        .filter { record ->
            record.kind == EvidenceKind.Profile &&
                (record.value.trim() == candidatePage || record.sourceUrl?.trim() == candidatePage)
        }
        .toList()

    if (pageMatches.size != 1) return null
    val record = pageMatches.single()
    return record.id.takeIf { id -> id == declaredEvidenceId && id.isNotBlank() }
}
