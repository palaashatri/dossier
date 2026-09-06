package io.dossier.app.domain.evidence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Outcome of a direct username-existence check. It says nothing about ownership. */
@Serializable
enum class UsernameSurfaceState {
    Present,
    Absent,
    Unavailable
}

@Serializable
data class UsernameSurfaceObservation(
    val source: String,
    val site: String,
    val username: String,
    val profileUrl: String,
    val state: UsernameSurfaceState,
    val confidence: Double,
    val reason: String,
    val observedAtEpochMillis: Long,
    val providerId: String = source
)

/**
 * Process-local bridge from broad enumeration plugins to deterministic post-processing.
 * The encrypted background snapshot persists the derived matrix, not this cache.
 */
object UsernameSurfaceRuntimeCache {
    private val _observations = MutableStateFlow<List<UsernameSurfaceObservation>>(emptyList())
    private val lock = Any()
    val observations: StateFlow<List<UsernameSurfaceObservation>> = _observations

    fun replace(source: String, items: List<UsernameSurfaceObservation>) {
        synchronized(lock) {
            _observations.value = (
                _observations.value.filterNot { it.source == source } + items
            ).distinctBy { "${it.source}|${it.site}|${it.username}|${it.profileUrl}" }
        }
    }

    fun clear() {
        synchronized(lock) {
            _observations.value = emptyList()
        }
    }
}
