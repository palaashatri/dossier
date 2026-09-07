package io.dossier.app.domain.discovery

private val TERMINAL_SCAN_STATES = setOf(
    ScanRunState.Completed,
    ScanRunState.Cancelled,
    ScanRunState.Failed
)

/**
 * Keeps late process-local lifecycle callbacks from mutating a terminal scan.
 *
 * Provider and pivot callbacks are observations of work that must stop at a
 * terminal boundary. A checkpoint is different: it is durable owner-validated
 * metadata and may still be published while the monitoring collector catches
 * up with the worker's terminal transition. The scan ID check remains strict
 * for both paths, so an old worker cannot project into a newer scan.
 */
internal fun acceptsCoordinatorProjectionEvent(
    snapshot: LiveScanSnapshot,
    event: ScanEvent
): Boolean {
    if (snapshot.scanId == null || snapshot.scanId != event.scanId) return false
    return snapshot.state !in TERMINAL_SCAN_STATES || event is ScanEvent.CheckpointUpdated
}
