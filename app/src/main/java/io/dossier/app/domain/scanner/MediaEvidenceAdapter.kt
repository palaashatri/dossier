package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import java.security.MessageDigest
import java.util.Locale

/** Projects media observations into the canonical evidence stream. */
internal fun MediaIntelligenceSnapshot.toEvidenceCollection(
    discoveryPath: List<String> = emptyList(),
    retrievedAtEpochMillis: Long? = null,
    mediaSourceUri: String? = null
): EvidenceCollection {
    val boundedPath = boundedDiscoveryPath(discoveryPath)
    return imageResults
        .map { result ->
            val profileAvatarObservation = result.isDirectProfileAvatarObservation()
            result.toEvidenceCollection(
                discoveryPath = if (profileAvatarObservation) {
                    listOf(PROFILE_AVATAR_PATH)
                } else {
                    boundedPath
                },
                mediaSourceUri = mediaSourceUri.takeUnless { profileAvatarObservation },
                retrievedAtEpochMillis = retrievedAtEpochMillis,
                profileAvatarObservation = profileAvatarObservation
            )
        }
        .fold(EvidenceCollection(), ::mergeEvidenceCollections)
}

internal fun ReverseImageLookupResult.toEvidenceCollection(
    discoveryPath: List<String> = emptyList(),
    retrievedAtEpochMillis: Long? = null,
    mediaSourceUri: String? = null,
    profileAvatarObservation: Boolean = false
): EvidenceCollection {
    val boundedPath = boundedDiscoveryPath(discoveryPath)
    val localSource = mediaSourceUri
        ?.takeIf(String::isNotBlank)
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
        timestamp: Long? = retrievedAtEpochMillis,
        path: List<String> = boundedPath,
        providerId: String? = null,
        contentHashSha256: String? = null
    ): String? {
        // Retain the exact observed value/source string. Normalization is
        // restricted to the stable ID key so the private ledger can show what
        // the provider actually returned without losing whitespace/casing.
        val exact = value?.takeIf(String::isNotBlank) ?: return null
        val source = sourceUrl
            ?.takeIf(String::isNotBlank)
        val id = mediaEvidenceId(kind, exact, source)
        evidence += Evidence(
            id = id,
            kind = kind,
            value = exact,
            sourceUrl = source,
            snippet = snippet?.trim()?.takeIf(String::isNotBlank)?.take(MAX_METADATA_CHARS),
            confidence = confidence.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            providerId = providerId?.trim()?.takeIf(String::isNotBlank),
            state = state,
            reliability = reliability,
            retrievedAtEpochMillis = timestamp,
            observedAtEpochMillis = timestamp,
            contentHashSha256 = contentHashSha256?.trim()?.takeIf(String::isNotBlank),
            discoveryPath = boundedDiscoveryPath(path),
            firstObservedAtEpochMillis = timestamp,
            lastObservedAtEpochMillis = timestamp
        )
        return id
    }

    gps?.let { gpsValue ->
        add(
            kind = EvidenceKind.Location,
            value = gpsValue,
            sourceUrl = localSource,
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
            sourceUrl = localSource,
            snippet = "Text recognized locally from the selected photo",
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.LocalDerived
        )
    }

    labels.forEach { label ->
        add(
            kind = EvidenceKind.SensitiveSnippet,
            value = label.text,
            sourceUrl = localSource,
            snippet = "Image label recognized locally (confidence ${label.confidence})",
            confidence = label.confidence,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.LocalDerived
        )
    }

    val geoSupport = webEvidence.firstOrNull {
        it.origin == ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration &&
            it.url.isNotBlank()
    }
    val imageSearchSupport = webEvidence.firstOrNull {
        it.origin == ReverseImageLookupResult.WebEvidenceOrigin.ImageSearch &&
            it.url.isNotBlank()
    }
    val unknownOriginSupport = webEvidence.firstOrNull {
        it.origin == ReverseImageLookupResult.WebEvidenceOrigin.Unknown &&
            it.url.isNotBlank()
    }
    val locationSupport = geoSupport ?: imageSearchSupport ?: unknownOriginSupport
    resolvedLocation
        ?.takeIf { it.isNotBlank() && !it.equals(gps, ignoreCase = false) }
        ?.let { location ->
            add(
                kind = EvidenceKind.Location,
                value = location,
                sourceUrl = locationSupport?.url ?: localSource,
                snippet = when {
                    geoSupport != null -> "Location candidate corroborated from EXIF coordinates"
                    imageSearchSupport != null -> "Location candidate derived from public image-search observations"
                    unknownOriginSupport != null -> "Location candidate derived from a web observation with unknown origin"
                    else -> "Location label derived from local media analysis"
                },
                confidence = when (locationSupport?.origin) {
                    ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration -> 0.9f
                    else -> 0.5f
                },
                state = when (locationSupport?.origin) {
                    ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration -> EvidenceState.Observed
                    else -> EvidenceState.Candidate
                },
                reliability = when (locationSupport?.origin) {
                    ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration ->
                        EvidenceReliability.AuthoritativeApi
                    ReverseImageLookupResult.WebEvidenceOrigin.ImageSearch ->
                        EvidenceReliability.SearchEngineCandidate
                    ReverseImageLookupResult.WebEvidenceOrigin.Unknown,
                    null -> if (locationSupport == null) {
                        EvidenceReliability.LocalDerived
                    } else {
                        EvidenceReliability.Unknown
                    }
                }
            )
        }

    webEvidence.forEach { web ->
        val (kind, reliability, isObserved) = when (web.origin) {
            ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration -> Triple(
                EvidenceKind.PublicSearchEvidence,
                EvidenceReliability.AuthoritativeApi,
                true
            )
            ReverseImageLookupResult.WebEvidenceOrigin.ImageSearch -> Triple(
                EvidenceKind.PublicImageEvidence,
                EvidenceReliability.SearchEngineCandidate,
                false
            )
            ReverseImageLookupResult.WebEvidenceOrigin.Unknown -> Triple(
                // Unknown origin must remain explicit; do not present it as
                // an autonomous public-web/image-search observation.
                EvidenceKind.PublicSearchEvidence,
                EvidenceReliability.Unknown,
                false
            )
        }
        val webId = add(
            kind = kind,
            value = web.title.ifBlank { web.url },
            sourceUrl = web.url,
            snippet = web.snippet,
            state = if (isObserved) EvidenceState.Observed else EvidenceState.Candidate,
            reliability = reliability
        )
        // A geo API response is observed directly. Search-index URLs are only
        // candidates until their source page is fetched, so no page relation is
        // asserted for them here.
        if (web.origin == ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration &&
            web.url.isNotBlank() &&
            webId != null
        ) {
            relationships += EvidenceRelationship(
                fromValue = web.url,
                toValue = web.title.ifBlank { web.url },
                relation = "location_source_mentions",
                evidence = web.snippet,
                evidenceIds = listOf(webId)
            )
        }
    }

    val candidateImageEvidenceIds = mutableMapOf<String, String>()
    val observedCandidatePages = mutableMapOf<String, String>()
    val candidateLinkageEvidenceIds = mutableMapOf<String, List<String>>()
    val candidateReliability = if (profileAvatarObservation) {
        EvidenceReliability.DirectPublicProfile
    } else {
        EvidenceReliability.SearchEngineCandidate
    }
    visualCandidates.forEach { candidate ->
        val candidateState = candidate.state.toEvidenceState()
        val candidateTimestamp = candidate.retrievedAtEpochMillis ?: retrievedAtEpochMillis
        val metadata = candidateMetadata(candidate)
        val pageId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = candidate.title.ifBlank { candidate.sourcePageUrl },
            sourceUrl = candidate.sourcePageUrl,
            snippet = metadata,
            confidence = candidate.comparisonScore ?: 0.5f,
            state = candidateState,
            reliability = candidateReliability,
            timestamp = candidateTimestamp,
            providerId = candidate.source,
            contentHashSha256 = candidate.contentSha256
        )
        val imageId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = candidate.imageUrl,
            sourceUrl = candidate.sourcePageUrl,
            snippet = metadata,
            confidence = candidate.comparisonScore ?: 0.5f,
            state = candidateState,
            reliability = candidateReliability,
            timestamp = candidateTimestamp,
            providerId = candidate.source,
            contentHashSha256 = candidate.contentSha256
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
                    reliability = candidateReliability,
                    timestamp = candidateTimestamp,
                    providerId = candidate.source,
                    contentHashSha256 = candidate.contentSha256
                )
            }

        val observedPage = candidate.hasObservedSourcePage()
        if (observedPage) {
            observedCandidatePages[candidate.id] = candidate.sourcePageUrl
            candidateLinkageEvidenceIds[candidate.id] = candidate.accountLinkages
                .flatMap { it.evidenceIds }
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(EvidenceIdPolicy::migrate)
                .distinct()
                .take(MAX_RELATIONSHIP_EVIDENCE_IDS)
        }
        if (observedPage && candidate.sourcePageUrl.isNotBlank() && candidate.imageUrl.isNotBlank()) {
            relationships += EvidenceRelationship(
                fromValue = candidate.sourcePageUrl,
                toValue = candidate.imageUrl,
                relation = "public_image_observed_on_page",
                evidence = candidate.title.takeIf(String::isNotBlank),
                evidenceIds = listOfNotNull(pageId, imageId) + candidateLinkageEvidenceIds[candidate.id].orEmpty()
            )
        }
    }

    val matchReliability = if (profileAvatarObservation) {
        EvidenceReliability.DirectPublicProfile
    } else {
        EvidenceReliability.SearchEngineCandidate
    }
    visualMatches.forEach { match ->
        val metadata = matchMetadata(match)
        // Keep the page title and exact image URL as separate structured records.
        // This also makes match-only results useful when no candidate provenance
        // record survived a provider response.
        val matchPageId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = match.title.ifBlank { match.sourcePageUrl },
            sourceUrl = match.sourcePageUrl,
            snippet = metadata,
            confidence = match.similarity,
            state = EvidenceState.Observed,
            reliability = matchReliability,
            timestamp = retrievedAtEpochMillis,
            providerId = match.source
        )
        val matchImageId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = match.imageUrl,
            sourceUrl = match.sourcePageUrl,
            snippet = metadata,
            confidence = match.similarity,
            state = EvidenceState.Observed,
            reliability = matchReliability,
            timestamp = retrievedAtEpochMillis,
            providerId = match.source
        )
        val candidateId = match.candidateId
        val candidatePage = candidateId?.let(observedCandidatePages::get)
        val pageWasObserved = candidatePage != null &&
            sameMediaIdentifier(candidatePage, match.sourcePageUrl)
        if (pageWasObserved && match.sourcePageUrl.isNotBlank() && match.imageUrl.isNotBlank()) {
            relationships += EvidenceRelationship(
                fromValue = match.sourcePageUrl,
                toValue = match.imageUrl,
                relation = "visual_match_observed",
                evidence = match.matchType,
                evidenceIds = listOfNotNull(matchPageId, matchImageId) +
                    listOfNotNull(candidateId?.let(candidateImageEvidenceIds::get)) +
                    candidateId?.let(candidateLinkageEvidenceIds::get).orEmpty()
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
        evidence = mergeEvidenceRecords(evidence),
        relationships = EvidenceRelationshipPolicy.normalize(
            relationships.filter { it.fromValue.isNotBlank() && it.toValue.isNotBlank() }
        )
    )
}

private fun ReverseImageLookupResult.ImageCandidateState.toEvidenceState(): EvidenceState = when (this) {
    ReverseImageLookupResult.ImageCandidateState.Indexed -> EvidenceState.Candidate
    ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable,
    ReverseImageLookupResult.ImageCandidateState.DecodeFailed -> EvidenceState.Unavailable
    ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch -> EvidenceState.Rejected
    ReverseImageLookupResult.ImageCandidateState.Matched -> EvidenceState.Observed
}

private fun ReverseImageLookupResult.isDirectProfileAvatarObservation(): Boolean =
    visualSearchNote?.startsWith(PROFILE_AVATAR_NOTE_PREFIX, ignoreCase = true) == true

private fun ReverseImageLookupResult.ImageCandidateProvenance.hasObservedSourcePage(): Boolean =
    sourcePageUrl.isNotBlank() && accountLinkages.any { linkage ->
        sameMediaIdentifier(linkage.accountUrl, sourcePageUrl)
    }

private fun candidateMetadata(
    candidate: ReverseImageLookupResult.ImageCandidateProvenance
): String = buildString {
    candidate.source.takeIf(String::isNotBlank)?.let { append("Source: ").append(it) }
    candidate.acquisitionQuery.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append("Query: ").append(it)
    }
    candidate.comparedImageUrl?.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append("Compared URL: ").append(it)
    }
    candidate.contentSha256?.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append("SHA-256: ").append(it)
    }
    if (candidate.width != null && candidate.height != null) {
        if (isNotEmpty()) append("; ")
        append("Dimensions: ").append(candidate.width).append('x').append(candidate.height)
    }
    candidate.state.name.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append("State: ").append(it)
    }
}.trim().take(MAX_METADATA_CHARS)

private fun matchMetadata(match: ReverseImageLookupResult.VisualMatch): String = buildString {
    match.source.takeIf(String::isNotBlank)?.let { append("Source: ").append(it) }
    match.matchType.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append("Match: ").append(it)
    }
    if (match.similarity.isFinite()) {
        if (isNotEmpty()) append("; ")
        append("Similarity: ").append(String.format(Locale.US, "%.3f", match.similarity.coerceIn(0f, 1f)))
    }
    match.clusterId?.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append("Cluster: ").append(it)
    }
    match.candidateId?.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append("Candidate: ").append(it)
    }
    match.evidence.takeIf(String::isNotBlank)?.let {
        if (isNotEmpty()) append("; ")
        append(it)
    }
}.trim().take(MAX_METADATA_CHARS)

private fun boundedDiscoveryPath(path: List<String>): List<String> = path
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .take(Evidence.MAX_DISCOVERY_PATH_STEPS)

private fun sameMediaIdentifier(first: String, second: String): Boolean =
    first.trim().trimEnd('/').substringBefore('#').equals(
        second.trim().trimEnd('/').substringBefore('#'),
        ignoreCase = true
    )

private fun mergeEvidenceCollections(
    first: EvidenceCollection,
    second: EvidenceCollection
): EvidenceCollection = EvidenceCollection(
    evidence = mergeEvidenceRecords(first.evidence + second.evidence),
    relationships = EvidenceRelationshipPolicy.normalize(first.relationships + second.relationships)
)

private fun mergeEvidenceRecords(records: List<Evidence>): List<Evidence> {
    val merged = LinkedHashMap<String, Evidence>()
    records.forEach { record ->
        merged[record.id] = merged[record.id]?.let { mergeEvidence(it, record) } ?: record
    }
    return merged.values.toList()
}

private fun mergeEvidence(first: Evidence, second: Evidence): Evidence {
    val preferred = strongerEvidence(first, second)
    val other = if (preferred === first) second else first
    return preferred.copy(
        // Keep the first observed source string intact. The records share a
        // normalized ID key, so only metadata is merged below.
        value = first.value,
        sourceUrl = preferred.sourceUrl ?: other.sourceUrl,
        snippet = listOfNotNull(first.snippet, second.snippet)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("; ")
            .takeIf(String::isNotBlank)
            ?.take(MAX_METADATA_CHARS),
        confidence = maxOf(first.confidence, second.confidence)
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f,
        signals = (first.signals + second.signals).distinct().take(MAX_SIGNALS),
        providerId = preferred.providerId ?: other.providerId,
        retrievedAtEpochMillis = maxTimestamp(first.retrievedAtEpochMillis, second.retrievedAtEpochMillis),
        observedAtEpochMillis = maxTimestamp(first.observedAtEpochMillis, second.observedAtEpochMillis),
        state = strongerState(first.state, second.state),
        reliability = strongerReliability(first.reliability, second.reliability),
        contentHashSha256 = preferred.contentHashSha256 ?: other.contentHashSha256,
        parserVersion = preferred.parserVersion ?: other.parserVersion,
        historical = first.historical && second.historical,
        attributeKind = preferred.attributeKind ?: other.attributeKind,
        discoveryPath = boundedDiscoveryPath(first.discoveryPath + second.discoveryPath),
        firstObservedAtEpochMillis = minTimestamp(
            first.firstObservedAtEpochMillis ?: first.observedAtEpochMillis,
            second.firstObservedAtEpochMillis ?: second.observedAtEpochMillis
        ),
        lastObservedAtEpochMillis = maxTimestamp(
            first.lastObservedAtEpochMillis ?: first.observedAtEpochMillis,
            second.lastObservedAtEpochMillis ?: second.observedAtEpochMillis
        )
    )
}

private fun strongerEvidence(first: Evidence, second: Evidence): Evidence {
    val stateComparison = stateRank(second.state).compareTo(stateRank(first.state))
    if (stateComparison != 0) return if (stateComparison > 0) second else first

    val reliabilityComparison = reliabilityRank(second.reliability)
        .compareTo(reliabilityRank(first.reliability))
    if (reliabilityComparison != 0) return if (reliabilityComparison > 0) second else first

    val confidenceComparison = second.confidence.compareTo(first.confidence)
    if (confidenceComparison != 0) return if (confidenceComparison > 0) second else first

    return if (first.snippet.isNullOrBlank() && !second.snippet.isNullOrBlank()) second else first
}

private fun strongerState(first: EvidenceState, second: EvidenceState): EvidenceState = when {
    first == second -> first
    first == EvidenceState.Conflicting || second == EvidenceState.Conflicting -> EvidenceState.Conflicting
    (first == EvidenceState.Rejected && second in POSITIVE_STATES) ||
        (second == EvidenceState.Rejected && first in POSITIVE_STATES) -> EvidenceState.Conflicting
    stateRank(second) > stateRank(first) -> second
    else -> first
}

private fun stateRank(state: EvidenceState): Int = when (state) {
    EvidenceState.Unavailable -> 0
    EvidenceState.Rejected -> 1
    EvidenceState.Candidate -> 2
    EvidenceState.Observed -> 3
    EvidenceState.Probable -> 4
    EvidenceState.Conflicting -> 4
    EvidenceState.Verified -> 5
}

private fun strongerReliability(
    first: EvidenceReliability,
    second: EvidenceReliability
): EvidenceReliability = if (reliabilityRank(second) > reliabilityRank(first)) second else first

private fun reliabilityRank(reliability: EvidenceReliability): Int = when (reliability) {
    EvidenceReliability.AuthoritativeApi -> 6
    EvidenceReliability.DirectPublicProfile -> 6
    EvidenceReliability.DirectPersonalWebsite -> 5
    EvidenceReliability.ArchiveSnapshot -> 5
    EvidenceReliability.SearchEngineCandidate -> 4
    EvidenceReliability.ThirdPartyAggregation -> 3
    EvidenceReliability.LocalDerived -> 2
    EvidenceReliability.UserSupplied -> 2
    EvidenceReliability.Unknown -> 0
}

private fun maxTimestamp(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}

private fun minTimestamp(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> minOf(first, second)
}

private fun mediaEvidenceId(kind: EvidenceKind, value: String, sourceUrl: String?): String {
    val input = "$kind\u001f${normalizedMediaKey(value)}\u001f${normalizedMediaKey(sourceUrl.orEmpty())}"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return "media:$kind:${digest.take(32)}"
}

/** Stable dedupe key kept separate from exact observed strings. */
private fun normalizedMediaKey(value: String): String {
    val compact = value
        .trim()
        .replace(Regex("\\s+"), " ")
    val urlLike = compact.contains("://")
    return (if (urlLike) compact.substringBefore('#') else compact)
        .trimEnd('/')
        .lowercase(Locale.ROOT)
}

private val POSITIVE_STATES = setOf(
    EvidenceState.Observed,
    EvidenceState.Probable,
    EvidenceState.Verified
)

private const val PROFILE_AVATAR_NOTE_PREFIX = "Directly verified public profile avatars"
private const val PROFILE_AVATAR_PATH = "profile:avatar"
private const val MAX_METADATA_CHARS = 512
private const val MAX_RELATIONSHIP_EVIDENCE_IDS = 256
private const val MAX_SIGNALS = 32
