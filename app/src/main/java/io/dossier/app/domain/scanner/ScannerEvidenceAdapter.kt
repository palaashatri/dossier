package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType

/**
 * Scanner-local adapter mirroring the canonical Evidence adapter. Keeping this tiny
 * shim in the scanner package avoids making ScanSession depend on a wildcard import
 * while the legacy Finding model is still part of the pipeline.
 */
internal fun Finding.toEvidence(
    retrievedAtEpochMillis: Long? = null,
    discoveryPath: List<String> = emptyList()
): Evidence = Evidence(
    id = EvidenceIdPolicy.findingId(this),
    kind = when (type) {
        FindingType.Email -> EvidenceKind.Email
        FindingType.Phone -> EvidenceKind.Phone
        FindingType.Address -> EvidenceKind.Address
        FindingType.Location -> EvidenceKind.Location
        FindingType.Username -> EvidenceKind.Username
        FindingType.Profile -> EvidenceKind.Profile
        FindingType.Organization -> EvidenceKind.Organization
        FindingType.UsernameReuse -> EvidenceKind.UsernameReuse
        FindingType.PlausibleProfileMatch -> EvidenceKind.PlausibleProfileMatch
        FindingType.PublicSearchEvidence -> EvidenceKind.PublicSearchEvidence
        FindingType.PublicImageEvidence -> EvidenceKind.PublicImageEvidence
        FindingType.ImageConsistency -> EvidenceKind.ImageConsistency
        FindingType.SensitiveSnippet -> EvidenceKind.SensitiveSnippet
    },
    value = value,
    sourceUrl = sourceUrl,
    snippet = evidenceSnippet,
    confidence = confidence,
    risk = risk,
    signals = if (remediation.isBlank()) emptyList() else listOf(remediation),
    state = when (type) {
        FindingType.PlausibleProfileMatch,
        FindingType.PublicSearchEvidence,
        FindingType.PublicImageEvidence -> EvidenceState.Candidate
        else -> EvidenceState.Observed
    },
    reliability = when (type) {
        FindingType.PublicSearchEvidence,
        FindingType.PublicImageEvidence -> EvidenceReliability.SearchEngineCandidate
        FindingType.ImageConsistency -> EvidenceReliability.LocalDerived
        else -> EvidenceReliability.Unknown
    },
    retrievedAtEpochMillis = retrievedAtEpochMillis,
    discoveryPath = discoveryPath
)
