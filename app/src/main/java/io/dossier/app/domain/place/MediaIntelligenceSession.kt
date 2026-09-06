package io.dossier.app.domain.place

import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseVideoLookupResult
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

        val observation = ReverseImageLookupResult(
            gps = null,
            extractedText = null,
            labels = emptyList(),
            faceDetected = false,
            faceWarning = null,
            resolvedLocation = null,
            mapsUrl = null,
            webEvidence = emptyList(),
            visualCandidates = candidates,
            visualSearchNote = "Directly verified public profile avatars were recorded as source observations; no local image comparison or face analysis was performed."
        )
        val current = _snapshot.value
        _snapshot.value = current.copy(
            imageResults = (current.imageResults + observation).takeLast(MAX_IMAGE_RESULTS)
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
