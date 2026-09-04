package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import java.security.MessageDigest

/**
 * Projects media observations into the canonical evidence stream. Visual
 * similarity never becomes an identity assertion: candidate pages are either
 * candidates, unavailable, or observed comparison results.
 */
internal fun MediaIntelligenceSnapshot.toEvidenceCollection(
    discoveryPath: List<String> = emptyList(),
    retrievedAtEpochMillis: Long? = null
): EvidenceCollection = imageResults
    .map { result ->
        result.toEvidenceCollection(
            discoveryPath = discoveryPath,
            retrievedAtEpochMillis = retrievedAtEpochMillis
        )
    }
    .fold(EvidenceCollection(), ::mergeEvidenceCollections)

internal fun ReverseImageLookupResult.toEvidenceCollection(
    discoveryPath: List<String> = emptyList(),
    retrievedAtEpochMillis: Long? = null
): EvidenceCollection {
    val boundedPath = discoveryPath
        .map(String::trim)
        .filter(String::isNotBlank)
        .take(Evidence.MAX_DISCOVERY_PATH_STEPS)
    val evidence = mutableListOf<Evidence>()
    val relationships = mutableListOf<EvidenceRelationship>()

    fun add(
        kind: EvidenceKind,
        value: String?,
        sourceUrl: String? = null,
        snippet: String? = null,
        confidence: Float = 0.5f,
        state: EvidenceState,
        reliability: EvidenceReliability,
        timestamp: Long? = retrievedAtEpochMillis
    ): String? {
        val exact = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val source = sourceUrl?.trim()?.takeIf(String::isNotBlank)
        val id = mediaEvidenceId(kind, exact, source)
        evidence += Evidence(
            id = id,
            kind = kind,
            value = exact,
            sourceUrl = source,
            snippet = snippet?.trim()?.takeIf(String::isNotBlank),
            confidence = confidence.coerceIn(0f, 1f),
            state = state,
            reliability = reliability,
            retrievedAtEpochMillis = timestamp,
            observedAtEpochMillis = timestamp,
            discoveryPath = boundedPath
        )
        return id
    }

    gps?.let { gpsValue ->
        add(
            kind = EvidenceKind.Location,
            value = gpsValue,
            sourceUrl = mapsUrl,
            snippet = "Embedded GPS metadata from the selected photo",
            confidence = 1f,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.LocalDerived
        )
    }

    extractedText?.takeIf(String::isNotBlank)?.let { text ->
        add(
            kind = EvidenceKind.SensitiveSnippet,
            value = text,
            snippet = "Text recognized locally from the selected photo",
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.LocalDerived
        )
    }

    labels.forEach { label ->
        add(
            kind = EvidenceKind.SensitiveSnippet,
            value = label.text,
            snippet = "Image label recognized locally (confidence ${label.confidence})",
            confidence = label.confidence,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.LocalDerived
        )
    }

    resolvedLocation
        ?.takeIf { it.isNotBlank() && !it.equals(gps, ignoreCase = false) }
        ?.let { location ->
            val hasPublicSupport = webEvidence.isNotEmpty()
            add(
                kind = EvidenceKind.Location,
                value = location,
                sourceUrl = mapsUrl,
                snippet = if (hasPublicSupport) {
                    "Location candidate derived from public source observations"
                } else {
                    "Location label derived from local media analysis"
                },
                state = EvidenceState.Candidate,
                reliability = if (hasPublicSupport) {
                    EvidenceReliability.SearchEngineCandidate
                } else {
                    EvidenceReliability.LocalDerived
                }
            )
        }

    webEvidence.forEach { web ->
        val webId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = web.title.ifBlank { web.url },
            sourceUrl = web.url,
            snippet = web.snippet,
            state = EvidenceState.Candidate,
            reliability = EvidenceReliability.SearchEngineCandidate
        )
        webId?.let { evidenceId ->
            relationships += EvidenceRelationship(
                fromValue = web.url,
                toValue = web.title.ifBlank { web.url },
                relation = "media_source_mentions",
                evidence = web.snippet,
                evidenceIds = listOf(evidenceId)
            )
        }
    }

    val candidateImageEvidenceIds = mutableMapOf<String, String>()
    visualCandidates.forEach { candidate ->
        val candidateState = candidate.state.toEvidenceState()
        val candidateTimestamp = candidate.retrievedAtEpochMillis ?: retrievedAtEpochMillis
        val metadata = buildString {
            candidate.source.takeIf(String::isNotBlank)?.let { append("Source: ").append(it) }
            candidate.acquisitionQuery.takeIf(String::isNotBlank)?.let {
                if (isNotEmpty()) append("; ")
                append("Query: ").append(it)
            }
            candidate.comparedImageUrl?.takeIf(String::isNotBlank)?.let {
                if (isNotEmpty()) append("; compared URL: ").append(it)
            }
        }.takeIf(String::isNotBlank)
        val pageId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = candidate.title.ifBlank { candidate.sourcePageUrl },
            sourceUrl = candidate.sourcePageUrl,
            snippet = metadata,
            confidence = candidate.comparisonScore ?: 0.5f,
            state = candidateState,
            reliability = EvidenceReliability.SearchEngineCandidate,
            timestamp = candidateTimestamp
        )
        val imageId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = candidate.imageUrl,
            sourceUrl = candidate.sourcePageUrl,
            snippet = metadata,
            confidence = candidate.comparisonScore ?: 0.5f,
            state = candidateState,
            reliability = EvidenceReliability.SearchEngineCandidate,
            timestamp = candidateTimestamp
        )
        imageId?.let { candidateImageEvidenceIds[candidate.id] = it }

        candidate.comparedImageUrl
            ?.takeIf { it.isNotBlank() && !it.equals(candidate.imageUrl, ignoreCase = true) }
            ?.let { comparedUrl ->
                add(
                    kind = EvidenceKind.PublicImageEvidence,
                    value = comparedUrl,
                    sourceUrl = candidate.sourcePageUrl,
                    snippet = "Compared public image URL",
                    confidence = candidate.comparisonScore ?: 0.5f,
                    state = candidateState,
                    reliability = EvidenceReliability.SearchEngineCandidate,
                    timestamp = candidateTimestamp
                )
            }

        if (candidate.sourcePageUrl.isNotBlank() && candidate.imageUrl.isNotBlank()) {
            relationships += EvidenceRelationship(
                fromValue = candidate.sourcePageUrl,
                toValue = candidate.imageUrl,
                relation = "public_image_observed_on_page",
                evidence = candidate.title.takeIf(String::isNotBlank),
                evidenceIds = listOfNotNull(pageId, imageId)
            )
        }
    }

    visualMatches.forEach { match ->
        val matchId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = match.title.ifBlank { match.imageUrl },
            sourceUrl = match.sourcePageUrl,
            snippet = buildString {
                match.evidence.takeIf(String::isNotBlank)?.let(::append)
                if (match.imageUrl.isNotBlank()) {
                    if (isNotEmpty()) append("; ")
                    append("Image URL: ").append(match.imageUrl)
                }
            },
            confidence = match.similarity,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.SearchEngineCandidate,
            timestamp = retrievedAtEpochMillis
        )
        if (match.sourcePageUrl.isNotBlank() && match.imageUrl.isNotBlank()) {
            relationships += EvidenceRelationship(
                fromValue = match.sourcePageUrl,
                toValue = match.imageUrl,
                relation = "visual_match_observed",
                evidence = match.matchType,
                evidenceIds = listOfNotNull(matchId, match.candidateId?.let(candidateImageEvidenceIds::get))
            )
        }
    }

    visualClusters.forEach { cluster ->
        val representative = visualCandidates.firstOrNull {
            it.id == cluster.representativeCandidateId
        } ?: return@forEach
        cluster.memberCandidateIds
            .asSequence()
            .filter { it != representative.id }
            .mapNotNull { memberId -> visualCandidates.firstOrNull { it.id == memberId } }
            .forEach { member ->
                if (representative.imageUrl.isBlank() || member.imageUrl.isBlank()) return@forEach
                relationships += EvidenceRelationship(
                    fromValue = representative.imageUrl,
                    toValue = member.imageUrl,
                    relation = when (cluster.type) {
                        ReverseImageLookupResult.ImageClusterType.ExactContent -> "same_image_content"
                        ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate ->
                            "perceptual_near_duplicate"
                    },
                    evidence = "Local image cluster ${cluster.id}",
                    evidenceIds = listOfNotNull(
                        candidateImageEvidenceIds[representative.id],
                        candidateImageEvidenceIds[member.id]
                    )
                )
            }
    }

    return EvidenceCollection(
        evidence = evidence.distinctBy(Evidence::id),
        relationships = relationships
            .filter { it.fromValue.isNotBlank() && it.toValue.isNotBlank() }
            .distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
    )
}

private fun ReverseImageLookupResult.ImageCandidateState.toEvidenceState(): EvidenceState = when (this) {
    ReverseImageLookupResult.ImageCandidateState.Indexed -> EvidenceState.Candidate
    ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable,
    ReverseImageLookupResult.ImageCandidateState.DecodeFailed -> EvidenceState.Unavailable
    ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch,
    ReverseImageLookupResult.ImageCandidateState.Matched -> EvidenceState.Observed
}

private fun mergeEvidenceCollections(
    first: EvidenceCollection,
    second: EvidenceCollection
): EvidenceCollection = EvidenceCollection(
    evidence = (first.evidence + second.evidence).distinctBy(Evidence::id),
    relationships = (first.relationships + second.relationships)
        .distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
)

private fun mediaEvidenceId(kind: EvidenceKind, value: String, sourceUrl: String?): String {
    val input = "$kind\u001f$value\u001f${sourceUrl.orEmpty()}"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return "media:$kind:${digest.take(32)}"
}
