package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.RiskLevel
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

private const val MAX_STAGE_SIGNALS = 24
private const val MAX_STAGE_SIGNAL_CHARS = 512
private const val MAX_STAGE_SNIPPET_CHARS = 1_024

/** Projects the structured face stage into the canonical evidence collection. */
internal fun List<FaceConsistencyMatch>.toFaceEvidenceCollection(
    retrievedAtEpochMillis: Long? = null,
    discoveryPath: List<String> = emptyList()
): EvidenceCollection {
    val records = mapNotNull { it.toFaceEvidence(retrievedAtEpochMillis, discoveryPath) }
        .distinctBy(Evidence::id)
    return EvidenceCollection(
        evidence = records,
        // The subject endpoint is intentionally not asserted here: this
        // stage only knows the observed profile URL. EntityGraphBuilder adds
        // subject -> image from the canonical record while this exact typed
        // assertion preserves image -> profile across snapshot restore.
        relationships = records.mapNotNull { record ->
            val profileUrl = record.sourceUrl?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            EvidenceRelationship(
                fromValue = typedEndpoint("image:face", profileUrl),
                toValue = typedEndpoint("profile", profileUrl),
                relation = "image_of_profile",
                evidence = record.snippet,
                evidenceIds = listOf(record.id)
            )
        }
    )
}

/**
 * Converts one structured face result without upgrading visual similarity to
 * identity proof. Positive scores remain manual-review candidates; a failed
 * comparison is retained as unavailable stage evidence.
 */
internal fun FaceConsistencyMatch.toFaceEvidence(
    retrievedAtEpochMillis: Long? = null,
    discoveryPath: List<String> = emptyList()
): Evidence? {
    val profileUrl = profileUrl.trim().takeIf(String::isNotBlank) ?: return null
    val score = similarityScore.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val state = if (score > 0f) EvidenceState.Candidate else EvidenceState.Unavailable
    val attribution = if (score > 0f) {
        FindingAttribution.Candidate
    } else {
        FindingAttribution.Unconfirmed
    }
    val timestamp = retrievedAtEpochMillis
    return Evidence(
        id = faceConsistencyEvidenceId(this),
        kind = EvidenceKind.ImageConsistency,
        value = "Face similarity ${(score * 100).toInt()}% vs $profileUrl",
        sourceUrl = profileUrl,
        snippet = warning.trim().take(MAX_STAGE_SNIPPET_CHARS).takeIf(String::isNotBlank),
        confidence = score,
        risk = faceRisk(warning),
        signals = faceSignals().take(MAX_STAGE_SIGNALS),
        retrievedAtEpochMillis = timestamp,
        observedAtEpochMillis = timestamp,
        state = state,
        reliability = EvidenceReliability.LocalDerived,
        sourceClassification = ExposureSourceClassification.LOCAL_IMPORT,
        firstObservedAtEpochMillis = timestamp,
        lastObservedAtEpochMillis = timestamp,
        sourceUrls = listOf(profileUrl),
        discoveryPath = discoveryPath
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(Evidence.MAX_DISCOVERY_PATH_STEPS),
        attribution = attribution,
        faceComparisonProvenance = provenance
    )
}

/** Projects breach-stage membership summaries into typed canonical evidence. */
internal fun List<BreachDigest>.toBreachEvidenceCollection(
    retrievedAtEpochMillis: Long? = null,
    discoveryPath: List<String> = emptyList()
): EvidenceCollection {
    val records = flatMap { it.toBreachEvidence(retrievedAtEpochMillis, discoveryPath) }
        .distinctBy(Evidence::id)
    return EvidenceCollection(
        evidence = records,
        relationships = records.mapNotNull { record ->
            val email = record.value.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            EvidenceRelationship(
                fromValue = typedEndpoint("email", email),
                toValue = typedEndpoint("breach", email),
                relation = "exposed_in",
                evidence = record.snippet,
                evidenceIds = listOf(record.id)
            )
        }
    )
}

/**
 * A breach digest establishes exposure of an identifier, not ownership of an
 * account. Explicit breachSources URLs are classified as breach-index
 * observations; title-only/provider summaries remain explicitly
 * breach-derived. Legacy/public-search URLs are never upgraded here.
 */
internal fun BreachDigest.toBreachEvidence(
    retrievedAtEpochMillis: Long? = null,
    discoveryPath: List<String> = emptyList()
): List<Evidence> {
    val email = email.trim().takeIf(String::isNotBlank) ?: return emptyList()
    if (breachCount <= 0) return emptyList()

    // `sources` is a legacy compatibility field that used to mix provider
    // breach labels with ordinary public-search URLs. Only the explicit
    // breachSources field is allowed to create breach-membership provenance;
    // publicEvidenceUrls and legacy HTTP values must never be upgraded to a
    // breach index observation.
    val breachSources = this.breachSources
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    val records = buildList {
        breachSources.forEach { source ->
            val classification = if (isSafePublicHttpUrl(source)) {
                ExposureSourceClassification.BREACH_INDEX
            } else {
                ExposureSourceClassification.BREACH_DERIVED
            }
            add(
                breachEvidence(
                    email = email,
                    breachCount = breachCount,
                    sourceLabel = source,
                    sourceUrl = source.takeIf(::isSafePublicHttpUrl),
                    sourceClassification = classification,
                    note = note,
                    retrievedAtEpochMillis = retrievedAtEpochMillis,
                    discoveryPath = discoveryPath
                )
            )
        }
        // Preserve the membership/count observation even when a provider did
        // not return a named breach source. This intentionally carries no
        // source URL and remains BREACH_DERIVED.
        if (breachSources.isEmpty()) {
            add(
                breachEvidence(
                    email = email,
                    breachCount = breachCount,
                    sourceLabel = "",
                    sourceUrl = null,
                    sourceClassification = ExposureSourceClassification.BREACH_DERIVED,
                    note = note,
                    retrievedAtEpochMillis = retrievedAtEpochMillis,
                    discoveryPath = discoveryPath
                )
            )
        }
    }
    return records
}

private fun breachEvidence(
    email: String,
    breachCount: Int,
    sourceLabel: String,
    sourceUrl: String?,
    sourceClassification: ExposureSourceClassification,
    note: String?,
    retrievedAtEpochMillis: Long?,
    discoveryPath: List<String>
): Evidence {
    val timestamp = retrievedAtEpochMillis
    val sourceDescription = sourceLabel.takeIf(String::isNotBlank)
    val snippet = buildString {
        append("Breach membership observed for ")
        append(email)
        append(" in ")
        append(breachCount)
        append(" known breach(es).")
        sourceDescription?.let { append(" Source: ").append(it) }
        note?.trim()?.takeIf(String::isNotBlank)?.let { append(" ").append(it) }
    }.take(MAX_STAGE_SNIPPET_CHARS)
    val signals = buildList {
        add("Breach membership count: $breachCount")
        sourceDescription?.let { add("Breach source: $it") }
        note?.trim()?.takeIf(String::isNotBlank)?.let { add("Breach stage note: $it") }
    }.map { it.take(MAX_STAGE_SIGNAL_CHARS) }
    return Evidence(
        id = breachMembershipEvidenceId(email, sourceClassification, sourceLabel),
        kind = EvidenceKind.BreachMembership,
        value = email,
        sourceUrl = sourceUrl,
        snippet = snippet,
        confidence = 0.95f,
        risk = RiskLevel.High,
        signals = signals.take(MAX_STAGE_SIGNALS),
        retrievedAtEpochMillis = timestamp,
        observedAtEpochMillis = timestamp,
        state = EvidenceState.Observed,
        reliability = EvidenceReliability.AuthoritativeApi,
        sourceClassification = sourceClassification,
        firstObservedAtEpochMillis = timestamp,
        lastObservedAtEpochMillis = timestamp,
        sourceUrls = listOfNotNull(sourceUrl),
        discoveryPath = discoveryPath
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(Evidence.MAX_DISCOVERY_PATH_STEPS),
        attribution = FindingAttribution.Unconfirmed
    )
}

/** Stable ID that does not put the profile URL or score into logs/edge keys. */
internal fun faceConsistencyEvidenceId(match: FaceConsistencyMatch): String =
    "face:${sha256("${normalKey(match.profileUrl)}\u001f${match.similarityScore}").take(32)}"

private fun breachMembershipEvidenceId(
    email: String,
    sourceClassification: ExposureSourceClassification,
    sourceLabel: String
): String =
    "breach:${sha256("${normalKey(email)}\u001f${sourceClassification.name}\u001f${normalKey(sourceLabel)}").take(32)}"

private fun FaceConsistencyMatch.faceSignals(): List<String> = buildList {
    add("Face comparison backend: ${provenance.backend.name}")
    add("Face comparison calibration: ${provenance.calibration.name}")
    provenance.modelSource?.trim()?.takeIf(String::isNotBlank)?.let {
        add("Face comparison model source: $it")
    }
    provenance.pipelineVersion?.trim()?.takeIf(String::isNotBlank)?.let {
        add("Face comparison pipeline: $it")
    }
    if (provenance.modelHashes.isNotEmpty()) {
        add("Face comparison model hashes: ${provenance.modelHashes.joinToString(",")}")
    }
    provenance.selfieQuality?.reason?.trim()?.takeIf(String::isNotBlank)?.let {
        add("Selfie quality: $it")
    }
    provenance.profileQuality?.reason?.trim()?.takeIf(String::isNotBlank)?.let {
        add("Profile quality: $it")
    }
}.map { it.take(MAX_STAGE_SIGNAL_CHARS) }

private fun faceRisk(warning: String): RiskLevel = when {
    warning.contains("high visual similarity", ignoreCase = true) -> RiskLevel.High
    warning.contains("review", ignoreCase = true) -> RiskLevel.Medium
    else -> RiskLevel.Low
}

private fun isSafePublicHttpUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)

private fun normalKey(value: String): String = value
    .trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)

/** Stable typed graph endpoint used only for explicit stage relationships. */
private fun typedEndpoint(type: String, value: String): String =
    "$type:${value.trim().lowercase(Locale.ROOT)}"

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
