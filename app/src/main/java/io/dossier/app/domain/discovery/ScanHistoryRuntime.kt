package io.dossier.app.domain.discovery

import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.model.IdentityInput
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * Process-local bridge between the active coordinator run and an explicitly
 * saved encrypted case.
 *
 * Dossier keeps only a SHA-256 fingerprint of normalized identity seeds here;
 * raw identity input is already owned by ScanSession and is not duplicated in
 * this lifecycle helper. A completed/cancelled record is attached only when the
 * case being initially saved has the same seed fingerprint.
 */
object ScanHistoryRuntime {
    private data class BoundEntry(
        val inputFingerprint: String,
        val entry: CaseScanHistoryEntry,
        /** True when the scan id came from durable WorkManager input. */
        val durableIdentity: Boolean = false
    )

    private val lock = Any()
    private var active: BoundEntry? = null
    private var latestTerminal: BoundEntry? = null

    fun scanStarted(
        scanId: ScanId,
        input: IdentityInput?,
        mode: ScanMode,
        directProfileProviderCount: Int,
        occurredAt: Instant
    ) {
        if (input == null) return
        synchronized(lock) {
            val fingerprint = fingerprint(input)
            val current = active
            // A coordinator callback can race the worker's durable binding. Once
            // the worker has claimed the WorkManager request, never replace its
            // exact id with a process-local random coordinator id.
            if (current?.durableIdentity == true && current.inputFingerprint == fingerprint) {
                return@synchronized
            }
            if (current?.entry?.scanId == scanId.value && current.inputFingerprint == fingerprint) {
                return@synchronized
            }
            active = BoundEntry(
                inputFingerprint = fingerprint,
                entry = CaseScanHistoryEntry(
                    scanId = scanId.value,
                    startedAtUtc = occurredAt.toString(),
                    mode = mode,
                    directProfileProviderCount = directProfileProviderCount
                )
            )
        }
    }

    /**
     * Establishes a scan-history start from the opaque WorkManager request id.
     *
     * WorkManager may recreate the worker in a new process after the enqueue
     * callback has already run. This operation is therefore deliberately
     * idempotent: retries for the same request keep the original start row,
     * while a stale worker cannot replace a different durable run for the same
     * normalized seeds.
     *
     * @return false when another durable request already owns this seed-bound
     * history slot.
     */
    fun ensureStarted(
        scanId: ScanId,
        input: IdentityInput,
        mode: ScanMode,
        directProfileProviderCount: Int,
        occurredAt: Instant
    ): Boolean = synchronized(lock) {
        val inputFingerprint = fingerprint(input)
        val current = active
        if (current != null) {
            if (current.inputFingerprint == inputFingerprint && current.entry.scanId == scanId.value) {
                return@synchronized true
            }
            if (current.durableIdentity) {
                return@synchronized false
            }
        }
        active = BoundEntry(
            inputFingerprint = inputFingerprint,
            entry = CaseScanHistoryEntry(
                scanId = scanId.value,
                startedAtUtc = occurredAt.toString(),
                mode = mode,
                directProfileProviderCount = directProfileProviderCount.coerceAtLeast(0)
            ),
            durableIdentity = true
        )
        true
    }

    fun scanFinished(
        scanId: ScanId,
        occurredAt: Instant,
        cancelled: Boolean,
        failed: Boolean = false,
        failureCode: String? = null,
        profileResultCount: Int,
        findingCount: Int,
        breachRecordCount: Int,
        graphEntityCount: Int,
        graphRelationshipCount: Int
    ) {
        synchronized(lock) {
            val current = active ?: return
            if (current.entry.scanId != scanId.value) return
            latestTerminal = current.copy(
                entry = terminalEntry(
                    started = current.entry,
                    occurredAt = occurredAt,
                    cancelled = cancelled,
                    failed = failed,
                    failureCode = failureCode,
                    profileResultCount = profileResultCount,
                    findingCount = findingCount,
                    breachRecordCount = breachRecordCount,
                    graphEntityCount = graphEntityCount,
                    graphRelationshipCount = graphRelationshipCount
                ),
                durableIdentity = false
            )
            active = null
        }
    }

    /**
     * Materializes the terminal entry immediately before a background worker
     * persists its process-death snapshot. The coordinator's state collector
     * normally calls [scanFinished] after ScanSession is marked terminal, which
     * is later than the encrypted result write. This seed-bound bridge keeps the
     * exact completed counts in that earlier snapshot; a later collector call is
     * harmless because the active entry has already been consumed.
     */
    fun finishForSnapshot(
        scanId: ScanId,
        input: IdentityInput,
        occurredAt: Instant,
        cancelled: Boolean = false,
        failed: Boolean = false,
        failureCode: String? = null,
        profileResultCount: Int,
        findingCount: Int,
        breachRecordCount: Int,
        graphEntityCount: Int,
        graphRelationshipCount: Int
    ): CaseScanHistoryEntry? = synchronized(lock) {
        finishForSnapshotLocked(
            scanId = scanId,
            input = input,
            occurredAt = occurredAt,
            cancelled = cancelled,
            failed = failed,
            failureCode = failureCode,
            profileResultCount = profileResultCount,
            findingCount = findingCount,
            breachRecordCount = breachRecordCount,
            graphEntityCount = graphEntityCount,
            graphRelationshipCount = graphRelationshipCount
        )
    }

    /**
     * Compatibility overload for process-local callers that already own the
     * active slot. New durable workers must use the explicit [scanId] overload.
     */
    fun finishForSnapshot(
        input: IdentityInput,
        occurredAt: Instant,
        cancelled: Boolean = false,
        failed: Boolean = false,
        failureCode: String? = null,
        profileResultCount: Int,
        findingCount: Int,
        breachRecordCount: Int,
        graphEntityCount: Int,
        graphRelationshipCount: Int
    ): CaseScanHistoryEntry? = synchronized(lock) {
        val current = active ?: return@synchronized null
        finishForSnapshotLocked(
            scanId = ScanId(current.entry.scanId),
            input = input,
            occurredAt = occurredAt,
            cancelled = cancelled,
            failed = failed,
            failureCode = failureCode,
            profileResultCount = profileResultCount,
            findingCount = findingCount,
            breachRecordCount = breachRecordCount,
            graphEntityCount = graphEntityCount,
            graphRelationshipCount = graphRelationshipCount
        )
    }

    private fun finishForSnapshotLocked(
        scanId: ScanId,
        input: IdentityInput,
        occurredAt: Instant,
        cancelled: Boolean,
        failed: Boolean,
        failureCode: String?,
        profileResultCount: Int,
        findingCount: Int,
        breachRecordCount: Int,
        graphEntityCount: Int,
        graphRelationshipCount: Int
    ): CaseScanHistoryEntry? {
        val current = active ?: return null
        if (current.entry.scanId != scanId.value) return null
        if (current.inputFingerprint != fingerprint(input)) return null
        val entry = terminalEntry(
            started = current.entry,
            occurredAt = occurredAt,
            cancelled = cancelled,
            failed = failed,
            failureCode = failureCode,
            profileResultCount = profileResultCount,
            findingCount = findingCount,
            breachRecordCount = breachRecordCount,
            graphEntityCount = graphEntityCount,
            graphRelationshipCount = graphRelationshipCount
        )
        latestTerminal = current.copy(entry = entry, durableIdentity = false)
        active = null
        return entry
    }

    fun latestFor(input: IdentityInput): CaseScanHistoryEntry? = synchronized(lock) {
        latestTerminal
            ?.takeIf { it.inputFingerprint == fingerprint(input) }
            ?.entry
    }

    internal fun resetForTests() = synchronized(lock) {
        active = null
        latestTerminal = null
    }

    internal fun fingerprintForTests(input: IdentityInput): String = fingerprint(input)

    private fun terminalEntry(
        started: CaseScanHistoryEntry,
        occurredAt: Instant,
        cancelled: Boolean,
        failed: Boolean,
        failureCode: String?,
        profileResultCount: Int,
        findingCount: Int,
        breachRecordCount: Int,
        graphEntityCount: Int,
        graphRelationshipCount: Int
    ): CaseScanHistoryEntry {
        val terminalFailed = failed
        return started.copy(
            completedAtUtc = occurredAt.toString(),
            profileResultCount = profileResultCount.coerceAtLeast(0),
            findingCount = findingCount.coerceAtLeast(0),
            breachRecordCount = breachRecordCount.coerceAtLeast(0),
            graphEntityCount = graphEntityCount.coerceAtLeast(0),
            graphRelationshipCount = graphRelationshipCount.coerceAtLeast(0),
            cancelled = cancelled && !terminalFailed,
            failed = terminalFailed,
            failureCode = if (terminalFailed) {
                sanitizeTerminalFailureCode(failureCode) ?: "SCAN_FAILED"
            } else {
                null
            }
        )
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
            append('\u001e')
            append(normalized(input.aliases))
            append('\u001e')
            append(normalized(input.emails))
            append('\u001e')
            append(normalized(input.phones))
            append('\u001e')
            append(normalized(input.locations))
            append('\u001e')
            append(normalized(input.organizations))
            append('\u001e')
            append(normalized(input.usernames))
            append('\u001e')
            append(input.primaryUsername?.trim()?.lowercase(Locale.ROOT).orEmpty())
            append('\u001e')
            append(normalized(input.profileUrls))
            // selfieUri is intentionally excluded: changing the locally selected
            // reference image must not make an otherwise identical audit look
            // like a different identity scope.
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
