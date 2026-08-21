package io.dossier.app.domain.analysis

import io.dossier.app.domain.evidence.UsernameSurfaceObservation
import io.dossier.app.domain.evidence.UsernameSurfaceState

/**
 * Merges broad direct username-existence observations into the normal surface map.
 * A present username from an enumeration feed is deliberately a review candidate,
 * never a verified identity match.
 */
object UsernameSurfaceAnalysis {
    fun merge(
        base: IdentitySurfaceMap,
        observations: List<UsernameSurfaceObservation>
    ): IdentitySurfaceMap {
        if (observations.isEmpty()) return base

        val broadEntries = observations.map { item ->
            val state = when (item.state) {
                UsernameSurfaceState.Present -> PresenceState.SuspiciousSimilarity
                UsernameSurfaceState.Absent -> PresenceState.NoMatch
                UsernameSurfaceState.Unavailable -> PresenceState.Unavailable
            }
            SurfacePresence(
                platform = item.site,
                username = item.username,
                url = item.profileUrl,
                state = state,
                confidence = item.confidence.coerceIn(0.0, 1.0),
                reason = when (item.state) {
                    UsernameSurfaceState.Present ->
                        "Public username exists according to a direct ${item.source} check; ownership is unverified"
                    UsernameSurfaceState.Absent -> item.reason
                    UsernameSurfaceState.Unavailable -> item.reason
                }
            )
        }

        val merged = (base.entries + broadEntries)
            .groupBy { canonicalKey(it.url, it.username) }
            .map { (_, group) -> group.maxWithOrNull(compareBy<SurfacePresence> { stateStrength(it.state) }.thenBy { it.confidence })!! }
            .sortedWith(compareBy<SurfacePresence> { it.state.ordinal }.thenBy { it.platform.lowercase() })

        return IdentitySurfaceMap(
            entries = merged,
            confirmedCount = merged.count { it.state == PresenceState.Exists },
            reviewCount = merged.count { it.state == PresenceState.SuspiciousSimilarity },
            noMatchCount = merged.count { it.state == PresenceState.NoMatch },
            unavailableCount = merged.count { it.state == PresenceState.Unavailable }
        )
    }

    private fun canonicalKey(url: String, username: String): String =
        url.trim().trimEnd('/').lowercase().ifBlank { username.trim().lowercase() }

    private fun stateStrength(state: PresenceState): Int = when (state) {
        PresenceState.Exists -> 4
        PresenceState.SuspiciousSimilarity -> 3
        PresenceState.NoMatch -> 2
        PresenceState.Unavailable -> 1
    }
}
