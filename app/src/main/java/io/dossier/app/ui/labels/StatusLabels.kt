package io.dossier.app.ui.labels

import io.dossier.app.domain.analysis.PresenceState
import io.dossier.app.domain.model.ProfileScanResult

/**
 * Stable, user-facing copy for persisted analysis states.
 *
 * Domain enum names remain the serialization contract. Keep this mapping in
 * the UI layer so stored evidence and exports retain their machine-readable
 * values while screens avoid leaking Kotlin identifiers to users.
 */
internal fun PresenceState.userFacingLabel(): String = when (this) {
    PresenceState.Exists -> "Exists"
    PresenceState.SuspiciousSimilarity -> "Suspicious similarity"
    PresenceState.NoMatch -> "No match"
    PresenceState.Unavailable -> "Unavailable"
}

/** The report badge vocabulary is shared by cards and export previews. */
internal fun ProfileScanResult.userFacingStatusLabel(): String = when {
    exists && verified -> "VERIFIED"
    exists -> "REVIEW"
    verificationStatus?.contains("unverifiable", ignoreCase = true) == true -> "UNAVAILABLE"
    else -> "NOT FOUND"
}
