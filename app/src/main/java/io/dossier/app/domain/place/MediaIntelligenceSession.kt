package io.dossier.app.domain.place

import android.content.Context
import android.net.Uri
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseVideoLookupResult
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

@Serializable
data class MediaIntelligenceSnapshot(
    val imageResults: List<ReverseImageLookupResult> = emptyList(),
    val videoResults: List<ReverseVideoLookupResult> = emptyList()
) {
    val isEmpty: Boolean
        get() = imageResults.isEmpty() && videoResults.isEmpty()
}

/**
 * Bounded process-local media analysis state.
 *
 * Reverse-media analysis is independent from the identity scan pipeline, but an
 * explicitly saved Case should still be able to retain the evidence gathered in
 * the same working session. CaseStore snapshots this state only when the operator
 * explicitly saves a new case; nothing is silently promoted to persistent storage.
 */
object MediaIntelligenceSession {
    private val lock = Any()
    private val _snapshot = MutableStateFlow(MediaIntelligenceSnapshot())
    val snapshotFlow: StateFlow<MediaIntelligenceSnapshot> = _snapshot
    private var boundInputFingerprint: String? = null
    private var bindingToken: String? = null

    /** Appends a result only when its lookup started in the current binding. */
    fun recordImage(token: String, result: ReverseImageLookupResult): Boolean = synchronized(lock) {
        if (token.isBlank() || token != bindingToken) return@synchronized false
        val current = _snapshot.value
        _snapshot.value = current.copy(
            imageResults = (current.imageResults + result).takeLast(MAX_IMAGE_RESULTS)
        )
        true
    }

    /** Appends a result only when its lookup started in the current binding. */
    fun recordVideo(token: String, result: ReverseVideoLookupResult): Boolean = synchronized(lock) {
        if (token.isBlank() || token != bindingToken) return@synchronized false
        val current = _snapshot.value
        _snapshot.value = current.copy(
            videoResults = (current.videoResults + result).takeLast(MAX_VIDEO_RESULTS)
        )
        true
    }

    /**
     * Persists bounded avatar observations from the current direct profile scan.
     * This is deliberately separate from [recordImage]: no selected image was
     * compared, so the resulting candidates remain Indexed with no visual score.
     */
    fun recordVerifiedProfileAvatars(
        token: String,
        input: IdentityInput,
        profiles: List<ProfileScanResult>
    ): Boolean = synchronized(lock) {
        if (token.isBlank() || token != bindingToken || boundInputFingerprint != fingerprint(input)) {
            return@synchronized false
        }

        val existingCandidateIds = _snapshot.value.imageResults
            .asSequence()
            .flatMap { it.visualCandidates.asSequence() }
            .map { it.id }
            .toHashSet()
        val candidates = VerifiedProfileAvatarProducer
            .produce(profiles)
            .filterNot { it.id in existingCandidateIds }
        if (candidates.isEmpty()) return@synchronized true

        // These are source observations fetched during the current scan. A
        // retrieval timestamp is required so the later evidence projection
        // can retain when the public avatar was observed, even when no image
        // comparison or selected-photo lookup runs afterward.
        val retrievedAtEpochMillis = System.currentTimeMillis()
        val timestampedCandidates = candidates.map { candidate ->
            candidate.copy(retrievedAtEpochMillis = retrievedAtEpochMillis)
        }

        val observation = ReverseImageLookupResult(
            gps = null,
            extractedText = null,
            labels = emptyList(),
            faceDetected = false,
            faceWarning = null,
            resolvedLocation = null,
            mapsUrl = null,
            webEvidence = emptyList(),
            visualCandidates = timestampedCandidates,
            visualSearchNote = "Directly verified public profile avatars were recorded as source observations; no local image comparison or face analysis was performed."
        )
        val current = _snapshot.value
        _snapshot.value = current.copy(
            imageResults = (current.imageResults + observation).takeLast(MAX_IMAGE_RESULTS)
        )
        true
    }

    /**
     * Compares the selected photo against the directly verified profile avatars
     * produced by this scan. The matcher performs all image work locally; this
     * method only commits the result if the scan binding is still current.
     */
    suspend fun compareVerifiedProfileAvatars(
        context: Context,
        token: String,
        input: IdentityInput,
        profiles: List<ProfileScanResult>,
        deepResearch: Boolean = false
    ): Boolean {
        val selfieUri = input.selfieUri?.trim()?.takeIf(String::isNotBlank) ?: return false
        if (token.isBlank() || profiles.none { it.exists && it.verified && !it.profileImageUrl.isNullOrBlank() }) {
            return false
        }
        val canStart = synchronized(lock) {
            token == bindingToken && boundInputFingerprint == fingerprint(input)
        }
        if (!canStart) return false

        val outcome = try {
            ReverseImageVisualMatcher(context).matchVerifiedProfileAvatars(
                queryUri = Uri.parse(selfieUri),
                profiles = profiles,
                deepResearch = deepResearch
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        currentCoroutineContext().ensureActive()

        return synchronized(lock) {
            if (token != bindingToken || boundInputFingerprint != fingerprint(input)) {
                false
            } else {
                _snapshot.value = mergeVerifiedProfileComparison(
                    current = _snapshot.value,
                    profiles = profiles,
                    outcome = outcome
                )
                true
            }
        }
    }

    /**
     * Attaches the existing local face-pipeline observations to their matching
     * verified-avatar candidates. Scores and warnings remain supporting
     * metadata; candidate state and account linkage are never upgraded here.
     */
    fun attachFaceComparisons(
        token: String,
        input: IdentityInput,
        matches: List<FaceConsistencyMatch>
    ): Boolean = synchronized(lock) {
        if (token.isBlank() || token != bindingToken || boundInputFingerprint != fingerprint(input)) {
            return@synchronized false
        }
        val faceByProfile = matches
            .asSequence()
            .mapNotNull { match ->
                val profileUrl = match.profileUrl.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                profileUrl to match
            }
            .toMap()
        if (faceByProfile.isEmpty()) return@synchronized true

        _snapshot.value = _snapshot.value.copy(
            imageResults = _snapshot.value.imageResults.map { result ->
                result.copy(
                    visualCandidates = result.visualCandidates.map { candidate ->
                        if (!candidate.hasVerifiedProfileLinkage()) {
                            candidate
                        } else {
                            val match = faceByProfile.entries.firstOrNull { (profileUrl, _) ->
                                sameMediaIdentifier(profileUrl, candidate.sourcePageUrl)
                            }?.value
                            match?.let { candidate.withFaceComparison(it) } ?: candidate
                        }
                    }
                )
            }
        )
        true
    }

    /**
     * Binds subsequent media results to this exact authorized identity input.
     * Binding does not claim the media proves identity; it only prevents a
     * process-global result from being grafted onto another subject.
     */
    fun bindTo(input: IdentityInput): String = synchronized(lock) {
        val fingerprint = fingerprint(input)
        if (boundInputFingerprint != fingerprint) {
            _snapshot.value = MediaIntelligenceSnapshot()
            boundInputFingerprint = fingerprint
        }
        UUID.randomUUID().toString().also { bindingToken = it }
    }

    /** Starts a new scan-owned media scope, even when the seeds are unchanged. */
    fun beginFor(input: IdentityInput): String = synchronized(lock) {
        val fingerprint = fingerprint(input)
        _snapshot.value = MediaIntelligenceSnapshot()
        boundInputFingerprint = fingerprint
        UUID.randomUUID().toString().also { bindingToken = it }
    }

    /** Returns media only when it was explicitly bound to this exact input. */
    fun snapshotFor(input: IdentityInput): MediaIntelligenceSnapshot = synchronized(lock) {
        if (boundInputFingerprint == fingerprint(input)) _snapshot.value
        else MediaIntelligenceSnapshot()
    }

    /** Returns media only when both the input and the scan-owned binding match. */
    fun snapshotFor(input: IdentityInput, token: String): MediaIntelligenceSnapshot = synchronized(lock) {
        if (token.isNotBlank() && token == bindingToken && boundInputFingerprint == fingerprint(input)) {
            _snapshot.value
        } else {
            MediaIntelligenceSnapshot()
        }
    }

    /** Invalidates late writes from a cancelled or terminal scan while retaining partial results. */
    fun invalidateBinding(token: String? = null): Boolean = synchronized(lock) {
        if (token != null && token != bindingToken) return@synchronized false
        bindingToken = null
        true
    }

    /** Rehydrates a process-death result into the exact restored subject scope. */
    fun restoreFor(input: IdentityInput, snapshot: MediaIntelligenceSnapshot) = synchronized(lock) {
        boundInputFingerprint = fingerprint(input)
        bindingToken = UUID.randomUUID().toString()
        _snapshot.value = snapshot
    }

    /** Unbound inspection is retained for diagnostics; persistence must use snapshotFor. */
    fun snapshot(): MediaIntelligenceSnapshot = synchronized(lock) { _snapshot.value }

    fun clear() = synchronized(lock) {
        _snapshot.value = MediaIntelligenceSnapshot()
        boundInputFingerprint = null
        bindingToken = null
    }

    internal fun mergeVerifiedProfileComparison(
        current: MediaIntelligenceSnapshot,
        profiles: List<ProfileScanResult>,
        outcome: ReverseImageVisualMatcher.Outcome
    ): MediaIntelligenceSnapshot {
        val produced = VerifiedProfileAvatarProducer.produce(profiles)
        if (produced.isEmpty()) return current

        val comparedById = outcome.candidates.associateBy { it.id }
        val compared = produced.map { candidate ->
            comparedById[candidate.id]?.let { comparedCandidate ->
                mergeCandidate(candidate, comparedCandidate)
            } ?: candidate
        }
        val avatarIds = produced.mapTo(hashSetOf()) { it.id }
        val observationIndex = current.imageResults.indexOfLast { result ->
            result.visualCandidates.any { it.id in avatarIds }
        }
        if (observationIndex < 0) {
            val observation = ReverseImageLookupResult(
                gps = null,
                extractedText = null,
                labels = emptyList(),
                faceDetected = false,
                faceWarning = null,
                resolvedLocation = null,
                mapsUrl = null,
                webEvidence = emptyList(),
                visualMatches = outcome.matches,
                visualCandidates = compared,
                visualClusters = outcome.clusters,
                visualSearchNote = outcome.note
            )
            return current.copy(
                imageResults = (current.imageResults + observation).takeLast(MAX_IMAGE_RESULTS)
            )
        }

        val existing = current.imageResults[observationIndex]
        val candidatesById = LinkedHashMap<String, ReverseImageLookupResult.ImageCandidateProvenance>()
        existing.visualCandidates.forEach { candidate -> candidatesById[candidate.id] = candidate }
        compared.forEach { candidate ->
            candidatesById[candidate.id] = mergeCandidate(candidatesById[candidate.id], candidate)
        }
        val matchesByKey = LinkedHashMap<String, ReverseImageLookupResult.VisualMatch>()
        (existing.visualMatches + outcome.matches).forEach { match ->
            val key = visualMatchKey(match)
            matchesByKey[key] = matchesByKey[key]?.let { previous ->
                strongerVisualMatch(previous, match)
            } ?: match
        }
        val clustersById = LinkedHashMap<String, ReverseImageLookupResult.ImageCluster>()
        (existing.visualClusters + outcome.clusters).forEach { cluster ->
            clustersById[cluster.id] = cluster
        }
        val updated = existing.copy(
            visualCandidates = candidatesById.values.toList(),
            visualMatches = matchesByKey.values.toList(),
            visualClusters = clustersById.values.toList(),
            visualSearchNote = outcome.note
        )
        return current.copy(
            imageResults = current.imageResults.toMutableList().also { it[observationIndex] = updated }
        )
    }

    private fun mergeCandidate(
        existing: ReverseImageLookupResult.ImageCandidateProvenance?,
        updated: ReverseImageLookupResult.ImageCandidateProvenance
    ): ReverseImageLookupResult.ImageCandidateProvenance {
        if (existing == null) return updated
        val updatedRank = candidateStateRank(updated.state)
        val existingRank = candidateStateRank(existing.state)
        val preferred = when {
            updatedRank > existingRank -> updated
            updatedRank < existingRank -> existing
            updated.state == ReverseImageLookupResult.ImageCandidateState.Matched &&
                (existing.comparisonScore ?: 0f) > (updated.comparisonScore ?: 0f) -> existing
            else -> updated
        }
        return preferred.copy(
            accountLinkages = (existing.accountLinkages + updated.accountLinkages)
                .distinctBy { "${it.basis.name}|${it.accountUrl}" },
            faceComparisonScore = updated.faceComparisonScore ?: existing.faceComparisonScore,
            faceComparisonWarning = updated.faceComparisonWarning ?: existing.faceComparisonWarning,
            faceComparisonProvenance = updated.faceComparisonProvenance
                ?: existing.faceComparisonProvenance
        )
    }

    private fun candidateStateRank(
        state: ReverseImageLookupResult.ImageCandidateState
    ): Int = when (state) {
        ReverseImageLookupResult.ImageCandidateState.Indexed -> 0
        ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable,
        ReverseImageLookupResult.ImageCandidateState.DecodeFailed -> 1
        ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch -> 2
        ReverseImageLookupResult.ImageCandidateState.Matched -> 3
    }

    private fun ReverseImageLookupResult.ImageCandidateProvenance.withFaceComparison(
        match: FaceConsistencyMatch
    ): ReverseImageLookupResult.ImageCandidateProvenance = copy(
        faceComparisonScore = match.similarityScore
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f),
        faceComparisonWarning = match.warning.take(MAX_FACE_WARNING_CHARS),
        faceComparisonProvenance = match.provenance
    )

    private fun ReverseImageLookupResult.ImageCandidateProvenance.hasVerifiedProfileLinkage(): Boolean =
        accountLinkages.any { linkage ->
            linkage.basis == ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile &&
                sameMediaIdentifier(linkage.accountUrl, sourcePageUrl)
        }

    private fun visualMatchKey(match: ReverseImageLookupResult.VisualMatch): String =
        match.candidateId?.let { "candidate:$it" }
            ?: "${canonicalUrl(match.imageUrl)}|${canonicalUrl(match.sourcePageUrl)}"

    private fun strongerVisualMatch(
        first: ReverseImageLookupResult.VisualMatch,
        second: ReverseImageLookupResult.VisualMatch
    ): ReverseImageLookupResult.VisualMatch {
        val firstScore = first.similarity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val secondScore = second.similarity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        return if (secondScore > firstScore) second else first
    }

    private fun sameMediaIdentifier(first: String, second: String): Boolean =
        canonicalUrl(first).substringBefore('?').equals(
            canonicalUrl(second).substringBefore('?'),
            ignoreCase = true
        )

    private fun canonicalUrl(raw: String): String = runCatching {
        val uri = URI(raw.trim())
        URI(
            uri.scheme?.lowercase(Locale.ROOT),
            null,
            uri.host?.lowercase(Locale.ROOT),
            uri.port,
            uri.path?.removeSuffix("/"),
            uri.query,
            null
        ).toString().removeSuffix("/")
    }.getOrDefault(raw.trim().substringBefore('#').removeSuffix("/").lowercase(Locale.ROOT))

    private const val MAX_FACE_WARNING_CHARS = 1_024

    private fun fingerprint(input: IdentityInput): String {
        fun normalized(values: List<String>): String = values
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("\u001f")

        val canonical = buildString {
            append(input.fullName.trim().lowercase(Locale.ROOT))
            append('\u001e').append(normalized(input.aliases))
            append('\u001e').append(normalized(input.emails))
            append('\u001e').append(normalized(input.phones))
            append('\u001e').append(normalized(input.locations))
            append('\u001e').append(normalized(input.organizations))
            append('\u001e').append(normalized(input.usernames))
            append('\u001e').append(input.primaryUsername?.trim()?.lowercase(Locale.ROOT).orEmpty())
            append('\u001e').append(normalized(input.profileUrls))
            append('\u001e').append(canonicalMediaUri(input.selfieUri))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Canonicalizes only the URI components whose casing is identifier-insensitive.
     * Path, query, and fragment casing remain exact so distinct media objects or
     * provider tokens cannot accidentally share a media scope.
     */
    private fun canonicalMediaUri(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""

        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme ?: return@runCatching trimmed
            val rawAuthority = uri.rawAuthority
            val authority = if (rawAuthority != null && uri.host != null) {
                canonicalAuthority(rawAuthority, uri.host)
            } else {
                rawAuthority
            }

            buildString {
                append(scheme.lowercase(Locale.ROOT)).append(':')
                if (rawAuthority != null) append("//").append(authority)
                if (uri.isOpaque) {
                    append(uri.rawSchemeSpecificPart)
                } else {
                    append(uri.rawPath.orEmpty())
                    uri.rawQuery?.let { append('?').append(it) }
                }
                uri.rawFragment?.let { append('#').append(it) }
            }
        }.getOrDefault(trimmed)
    }

    private fun canonicalAuthority(rawAuthority: String, host: String): String {
        val hostStart = rawAuthority.lastIndexOf('@') + 1
        val hostEnd = if (rawAuthority.getOrNull(hostStart) == '[') {
            rawAuthority.indexOf(']', hostStart)
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: rawAuthority.length
        } else {
            rawAuthority.indexOf(':', hostStart)
                .takeIf { it >= 0 }
                ?: rawAuthority.length
        }
        val canonicalHost = if (host.startsWith("[") && host.endsWith("]")) {
            "[${host.substring(1, host.length - 1).lowercase(Locale.ROOT)}]"
        } else {
            host.lowercase(Locale.ROOT)
        }
        return rawAuthority.replaceRange(hostStart, hostEnd, canonicalHost)
    }

    private const val MAX_IMAGE_RESULTS = 12
    private const val MAX_VIDEO_RESULTS = 6
}
