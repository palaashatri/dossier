package io.dossier.app.domain.scanner

import io.dossier.app.data.web.DiscoveryHttpPolicy
import io.dossier.app.data.web.TypedSeedPublicFetchExecutor
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import java.net.URI
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
            result.toEvidenceCollection(
                discoveryPath = boundedPath,
                mediaSourceUri = mediaSourceUri,
                retrievedAtEpochMillis = retrievedAtEpochMillis
            )
        }
        .fold(EvidenceCollection(), ::mergeEvidenceCollections)
}

internal fun ReverseImageLookupResult.toEvidenceCollection(
    discoveryPath: List<String> = emptyList(),
    retrievedAtEpochMillis: Long? = null,
    mediaSourceUri: String? = null
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
        contentHashSha256: String? = null,
        locationEvidenceClass: ReverseImageLookupResult.LocationEvidenceClass? = null,
        locationEvidenceReason: String? = null,
        supportingEvidenceIds: List<String> = emptyList(),
        sourceClassification: ExposureSourceClassification = ExposureSourceClassification.UNKNOWN_ORIGIN
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
            sourceClassification = sourceClassification,
            retrievedAtEpochMillis = timestamp,
            observedAtEpochMillis = timestamp,
            contentHashSha256 = contentHashSha256?.trim()?.takeIf(String::isNotBlank),
            discoveryPath = boundedDiscoveryPath(path),
            firstObservedAtEpochMillis = timestamp,
            lastObservedAtEpochMillis = timestamp,
            sourceUrls = listOfNotNull(source),
            locationEvidenceClass = locationEvidenceClass,
            locationEvidenceReason = locationEvidenceReason
                ?.trim()
                ?.take(MAX_LOCATION_REASON_CHARS)
                ?.takeIf(String::isNotBlank),
            supportingEvidenceIds = supportingEvidenceIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(EvidenceIdPolicy::migrate)
                .distinct()
                .take(MAX_SUPPORTING_EVIDENCE_IDS)
        )
        return id
    }

    /**
     * A reverse-image index returning a source-page URL is an observed public
     * navigation pivot, even when the associated image remains a Candidate.
     * Keep the URL as its own evidence record so the typed frontier can fetch
     * the page without promoting the image result to identity proof.
     */
    fun addObservedSourcePage(
        pageUrl: String,
        imageUrl: String?,
        providerId: String?,
        path: List<String>,
        snippet: String?,
        timestamp: Long?,
        supportingImageEvidenceId: String? = null
    ): String? {
        val page = pageUrl.trim()
        if (!isSafeMediaSourcePage(page, imageUrl)) return null
        val kind = classifyMediaSourcePage(page)
        return add(
            kind = kind,
            value = page,
            sourceUrl = page,
            snippet = snippet,
            confidence = 1f,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.SearchEngineCandidate,
            timestamp = timestamp,
            path = path,
            providerId = providerId,
            supportingEvidenceIds = listOfNotNull(supportingImageEvidenceId),
            sourceClassification = when (kind) {
                EvidenceKind.Document -> ExposureSourceClassification.PUBLIC_DOCUMENT
                EvidenceKind.Archive -> ExposureSourceClassification.ARCHIVE
                else -> ExposureSourceClassification.PUBLIC_WEB
            }
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
    // Unknown-origin records are deliberately excluded from location support.
    // They do not establish enough provenance to upgrade a legacy resolved
    // location above a local visual guess.
    val locationSupport = geoSupport ?: imageSearchSupport

    /*
     * New results carry explicit location candidates. Legacy results only have
     * gps/resolvedLocation, so synthesize the same typed observations locally
     * without changing their serialized shape or claiming more than the source
     * supports.
     */
    val declaredLocationCandidates = this.locationCandidates
    val locationCandidates = buildList {
        addAll(declaredLocationCandidates.take(MAX_LOCATION_CANDIDATES))
        if (gps != null && none {
                sameLocationValue(it.value, gps) &&
                    it.evidenceClass == ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA
            }) {
            add(
                ReverseImageLookupResult.LocationCandidate(
                    value = gps,
                    evidenceClass = ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
                    reason = "Embedded GPS metadata from the selected photo",
                    sourceUrls = listOfNotNull(localSource)
                )
            )
        }
        resolvedLocation
            ?.takeIf { it.isNotBlank() && (gps == null || !sameLocationValue(it, gps)) }
            ?.takeIf { location -> none { sameLocationValue(it.value, location) } }
            ?.let { location ->
                val evidenceClass = when {
                    geoSupport != null -> ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION
                    imageSearchSupport != null ->
                        ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION
                    else -> ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS
                }
                val reason = when (evidenceClass) {
                    ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION ->
                        "Location candidate corroborated from EXIF coordinates"
                    ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION ->
                        "Location candidate derived from public web/image-search observations"
                    ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS ->
                        "Location label derived from local media analysis"
                    ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA ->
                        "Embedded GPS metadata from the selected photo"
                    ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING ->
                        "Location candidate conflicts with another observation"
                }
                add(
                    ReverseImageLookupResult.LocationCandidate(
                        value = location,
                        evidenceClass = evidenceClass,
                        reason = reason,
                        sourceUrls = listOfNotNull(locationSupport?.url ?: localSource)
                    )
                )
            }
    }

    locationCandidates.forEach { candidate ->
        val sourceUrl = candidate.sourceUrls.firstOrNull { it.isNotBlank() } ?: localSource
        val evidenceClass = candidate.evidenceClass
        add(
            kind = EvidenceKind.Location,
            value = candidate.value,
            sourceUrl = sourceUrl,
            snippet = candidate.reason.takeIf(String::isNotBlank),
            confidence = candidate.confidence,
            state = evidenceClass.toEvidenceState(),
            reliability = evidenceClass.toEvidenceReliability(),
            timestamp = candidate.observedAtEpochMillis ?: retrievedAtEpochMillis,
            locationEvidenceClass = evidenceClass,
            locationEvidenceReason = candidate.reason,
            supportingEvidenceIds = candidate.evidenceIds
        )
    }

    data class WebEvidenceMapping(
        val kind: EvidenceKind,
        val reliability: EvidenceReliability,
        val isObserved: Boolean,
        val locationClass: ReverseImageLookupResult.LocationEvidenceClass?
    )

    webEvidence.forEach { web ->
        val (kind, reliability, isObserved, locationClass) = when (web.origin) {
            ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration -> WebEvidenceMapping(
                EvidenceKind.PublicSearchEvidence,
                EvidenceReliability.AuthoritativeApi,
                true,
                ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION
            )
            ReverseImageLookupResult.WebEvidenceOrigin.ImageSearch -> WebEvidenceMapping(
                EvidenceKind.PublicImageEvidence,
                EvidenceReliability.SearchEngineCandidate,
                false,
                ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION
            )
            ReverseImageLookupResult.WebEvidenceOrigin.Unknown -> WebEvidenceMapping(
                // Unknown origin must remain explicit; do not present it as
                // an autonomous public-web/image-search observation.
                EvidenceKind.PublicSearchEvidence,
                EvidenceReliability.Unknown,
                false,
                null
            )
        }
        val webId = add(
            kind = kind,
            value = web.title.ifBlank { web.url },
            sourceUrl = web.url,
            snippet = web.snippet,
            state = if (isObserved) EvidenceState.Observed else EvidenceState.Candidate,
            reliability = reliability,
            locationEvidenceClass = locationClass,
            locationEvidenceReason = web.snippet,
            sourceClassification = when (web.origin) {
                ReverseImageLookupResult.WebEvidenceOrigin.GeoCorroboration ->
                    ExposureSourceClassification.AUTHORIZED_API
                ReverseImageLookupResult.WebEvidenceOrigin.ImageSearch ->
                    ExposureSourceClassification.PUBLIC_WEB
                ReverseImageLookupResult.WebEvidenceOrigin.Unknown ->
                    ExposureSourceClassification.UNKNOWN_ORIGIN
            }
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
    visualCandidates.forEach { candidate ->
        val candidateState = candidate.state.toEvidenceState()
        val candidateTimestamp = candidate.retrievedAtEpochMillis ?: retrievedAtEpochMillis
        val metadata = candidateMetadata(candidate)
        val candidatePath = candidate.discoveryPath(boundedPath)
        val candidateReliability = candidate.evidenceReliability()
        val pageId = add(
            kind = EvidenceKind.PublicImageEvidence,
            value = candidate.title.ifBlank { candidate.sourcePageUrl },
            sourceUrl = candidate.sourcePageUrl,
            snippet = metadata,
            confidence = candidate.comparisonScore ?: 0.5f,
            state = candidateState,
            reliability = candidateReliability,
            timestamp = candidateTimestamp,
            path = candidatePath,
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
            path = candidatePath,
            providerId = candidate.source,
            contentHashSha256 = candidate.contentSha256
        )
        imageId?.let { candidateImageEvidenceIds[candidate.id] = it }

        addObservedSourcePage(
            pageUrl = candidate.sourcePageUrl,
            imageUrl = candidate.imageUrl,
            providerId = candidate.source,
            path = candidatePath,
            snippet = "Reverse-image source page indexed by ${candidate.source.ifBlank { "provider" }}" +
                candidate.title.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
            timestamp = candidateTimestamp,
            supportingImageEvidenceId = imageId
        )

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
                    path = candidatePath,
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

    val candidatesById = visualCandidates.associateBy { it.id }
    visualMatches.forEach { match ->
        val metadata = matchMetadata(match)
        val linkedCandidate = match.candidateId?.let(candidatesById::get)
        val matchReliability = linkedCandidate?.evidenceReliability()
            ?: EvidenceReliability.SearchEngineCandidate
        val matchPath = linkedCandidate?.discoveryPath(boundedPath) ?: boundedPath
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
            path = matchPath,
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
            path = matchPath,
            providerId = match.source
        )
        addObservedSourcePage(
            pageUrl = match.sourcePageUrl,
            imageUrl = match.imageUrl,
            providerId = match.source,
            path = matchPath,
            snippet = "Reverse-image match source page indexed by ${match.source.ifBlank { "provider" }}" +
                match.title.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
            timestamp = retrievedAtEpochMillis,
            supportingImageEvidenceId = matchImageId
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

private fun ReverseImageLookupResult.LocationEvidenceClass.toEvidenceState(): EvidenceState = when (this) {
    ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
    ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION -> EvidenceState.Observed
    ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION,
    ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS -> EvidenceState.Candidate
    ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING -> EvidenceState.Conflicting
}

private fun ReverseImageLookupResult.LocationEvidenceClass.toEvidenceReliability(): EvidenceReliability = when (this) {
    ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA,
    ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS -> EvidenceReliability.LocalDerived
    ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION ->
        EvidenceReliability.AuthoritativeApi
    ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION ->
        EvidenceReliability.SearchEngineCandidate
    ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING -> EvidenceReliability.Unknown
}

private fun ReverseImageLookupResult.ImageCandidateProvenance.evidenceReliability(): EvidenceReliability =
    if (hasVerifiedProfileLinkage()) {
        EvidenceReliability.DirectPublicProfile
    } else {
        EvidenceReliability.SearchEngineCandidate
    }

private fun ReverseImageLookupResult.ImageCandidateProvenance.discoveryPath(
    fallback: List<String>
): List<String> = if (hasVerifiedProfileLinkage()) {
    listOf(PROFILE_AVATAR_PATH)
} else {
    fallback
}

private fun ReverseImageLookupResult.ImageCandidateProvenance.hasVerifiedProfileLinkage(): Boolean =
    sourcePageUrl.isNotBlank() && accountLinkages.any { linkage ->
        linkage.basis == ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile &&
            sameMediaIdentifier(linkage.accountUrl, sourcePageUrl)
    }

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

private fun boundedSourceUrls(sourceUrls: List<String>): List<String> = sourceUrls
    .filter(String::isNotBlank)
    .distinct()
    .take(Evidence.MAX_SOURCE_URLS)

private fun sameMediaIdentifier(first: String, second: String): Boolean =
    first.trim().trimEnd('/').substringBefore('#').equals(
        second.trim().trimEnd('/').substringBefore('#'),
        ignoreCase = true
    )

private fun isSafeMediaSourcePage(pageUrl: String, imageUrl: String?): Boolean {
    if (pageUrl.isBlank() || !DiscoveryHttpPolicy.isSafePublicHttpUrl(pageUrl)) return false
    if (imageUrl?.isNotBlank() == true && sameMediaIdentifier(pageUrl, imageUrl)) return false

    // A reverse-image provider may return the raw image URL in the page field.
    // Do not turn obvious image assets into HTML fetch pivots; the image record
    // remains available as candidate evidence instead.
    val pathAndQuery = runCatching {
        val uri = URI(pageUrl)
        listOfNotNull(uri.rawPath, uri.rawQuery)
            .joinToString("?")
            .lowercase(Locale.ROOT)
    }.getOrNull() ?: return false
    return !MEDIA_ASSET_EXTENSION.containsMatchIn(pathAndQuery)
}

private fun classifyMediaSourcePage(pageUrl: String): EvidenceKind {
    val uri = runCatching { URI(pageUrl) }.getOrNull()
    val host = uri?.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    val isArchive = host == "web.archive.org" || host.endsWith(".web.archive.org") ||
        host == "archive.org" || host.endsWith(".archive.org") ||
        host == "archive.today" || host.endsWith(".archive.today") ||
        host == "archive.ph" || host.endsWith(".archive.ph") ||
        host == "archive.is" || host.endsWith(".archive.is") ||
        TypedSeedPublicFetchExecutor.classifyArchiveSnapshot(pageUrl) != null
    if (isArchive) return EvidenceKind.Archive

    val pathAndQuery = listOfNotNull(uri?.rawPath, uri?.rawQuery)
        .joinToString("?")
        .lowercase(Locale.ROOT)
    return if (DOCUMENT_EXTENSION.containsMatchIn(pathAndQuery)) {
        EvidenceKind.Document
    } else {
        EvidenceKind.Url
    }
}

private fun sameLocationValue(first: String, second: String): Boolean =
    first.trim().replace(Regex("\\s+"), " ").equals(
        second.trim().replace(Regex("\\s+"), " "),
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
        sourceUrl = first.sourceUrl ?: second.sourceUrl,
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
        attribution = strongerAttribution(first.attribution, second.attribution),
        contentHashSha256 = preferred.contentHashSha256 ?: other.contentHashSha256,
        parserVersion = preferred.parserVersion ?: other.parserVersion,
        faceComparisonProvenance = preferred.faceComparisonProvenance
            ?: other.faceComparisonProvenance,
        historical = first.historical && second.historical,
        attributeKind = preferred.attributeKind ?: other.attributeKind,
        locationEvidenceClass = strongerLocationEvidenceClass(
            first.locationEvidenceClass,
            second.locationEvidenceClass
        ),
        locationEvidenceReason = first.locationEvidenceReason
            ?: second.locationEvidenceReason,
        supportingEvidenceIds = (first.supportingEvidenceIds + second.supportingEvidenceIds)
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(EvidenceIdPolicy::migrate)
            .distinct()
            .take(MAX_SUPPORTING_EVIDENCE_IDS),
        discoveryPath = boundedDiscoveryPath(first.discoveryPath + second.discoveryPath),
        firstObservedAtEpochMillis = minTimestamp(
            first.firstObservedAtEpochMillis ?: first.observedAtEpochMillis,
            second.firstObservedAtEpochMillis ?: second.observedAtEpochMillis
        ),
        lastObservedAtEpochMillis = maxTimestamp(
            first.lastObservedAtEpochMillis ?: first.observedAtEpochMillis,
            second.lastObservedAtEpochMillis ?: second.observedAtEpochMillis
        ),
        sourceUrls = boundedSourceUrls(
            first.sourceUrls + second.sourceUrls + listOfNotNull(first.sourceUrl, second.sourceUrl)
        )
    )
}

private fun strongerLocationEvidenceClass(
    first: ReverseImageLookupResult.LocationEvidenceClass?,
    second: ReverseImageLookupResult.LocationEvidenceClass?
): ReverseImageLookupResult.LocationEvidenceClass? {
    if (first == null) return second
    if (second == null) return first
    val firstRank = locationEvidenceClassRank(first)
    val secondRank = locationEvidenceClassRank(second)
    return if (secondRank > firstRank) second else first
}

private fun locationEvidenceClassRank(
    evidenceClass: ReverseImageLookupResult.LocationEvidenceClass
): Int = when (evidenceClass) {
    ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS -> 1
    ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION -> 2
    ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION -> 3
    ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA -> 4
    // Preserve disagreement over a stronger positive class.
    ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING -> 5
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

private fun strongerAttribution(
    first: FindingAttribution?,
    second: FindingAttribution?
): FindingAttribution? {
    if (first == null) return second
    if (second == null || first == second) return first
    if (first == FindingAttribution.Conflicting || second == FindingAttribution.Conflicting) {
        return FindingAttribution.Conflicting
    }
    return if (attributionRank(second) > attributionRank(first)) second else first
}

private fun attributionRank(attribution: FindingAttribution): Int = when (attribution) {
    FindingAttribution.Unconfirmed -> 0
    FindingAttribution.Candidate -> 1
    FindingAttribution.IndependentPageSignals -> 2
    FindingAttribution.Probable -> 3
    FindingAttribution.Verified -> 4
    FindingAttribution.ExactSelfSupplied -> 5
    FindingAttribution.Conflicting -> 6
}

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

private const val PROFILE_AVATAR_PATH = "profile:avatar"
private const val MAX_METADATA_CHARS = 512
private const val MAX_RELATIONSHIP_EVIDENCE_IDS = 256
private const val MAX_SIGNALS = 32
private const val MAX_LOCATION_CANDIDATES = 64
private const val MAX_SUPPORTING_EVIDENCE_IDS = 256
private const val MAX_LOCATION_REASON_CHARS = 512
private val MEDIA_ASSET_EXTENSION = Regex(
    "\\.(?:avif|bmp|gif|ico|jpe?g|png|svg|tiff?|webp)(?:$|[?#&])",
    RegexOption.IGNORE_CASE
)
private val DOCUMENT_EXTENSION = Regex(
    "\\.(?:csv|docx?|json|od[pt]|ods|pdf|pptx?|rtf|txt|xls[xm]?|xml)(?:$|[?#&])",
    RegexOption.IGNORE_CASE
)
