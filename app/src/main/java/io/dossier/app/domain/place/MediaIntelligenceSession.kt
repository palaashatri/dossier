package io.dossier.app.domain.place

import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ReverseVideoLookupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

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
    private val _snapshot = MutableStateFlow(MediaIntelligenceSnapshot())
    val snapshotFlow: StateFlow<MediaIntelligenceSnapshot> = _snapshot

    fun recordImage(result: ReverseImageLookupResult) {
        val current = _snapshot.value
        _snapshot.value = current.copy(
            imageResults = (current.imageResults + result).takeLast(MAX_IMAGE_RESULTS)
        )
    }

    fun recordVideo(result: ReverseVideoLookupResult) {
        val current = _snapshot.value
        _snapshot.value = current.copy(
            videoResults = (current.videoResults + result).takeLast(MAX_VIDEO_RESULTS)
        )
    }

    fun snapshot(): MediaIntelligenceSnapshot = _snapshot.value

    fun clear() {
        _snapshot.value = MediaIntelligenceSnapshot()
    }

    private const val MAX_IMAGE_RESULTS = 12
    private const val MAX_VIDEO_RESULTS = 6
}
