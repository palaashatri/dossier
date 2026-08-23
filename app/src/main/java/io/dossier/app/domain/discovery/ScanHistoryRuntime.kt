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
        val entry: CaseScanHistoryEntry
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
            active = BoundEntry(
                inputFingerprint = fingerprint(input),
                entry = CaseScanHistoryEntry(
                    scanId = scanId.value,
                    startedAtUtc = occurredAt.toString(),
                    mode = mode,
                    directProfileProviderCount = directProfileProviderCount
                )
            )
        }
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
            val terminalFailed = failed
            val terminalCancelled = cancelled && !terminalFailed
            val safeFailureCode = if (terminalFailed) {
                sanitizeTerminalFailureCode(failureCode) ?: "SCAN_FAILED"
            } else {
                null
            }
            latestTerminal = current.copy(
                entry = current.entry.copy(
                    completedAtUtc = occurredAt.toString(),
                    profileResultCount = profileResultCount.coerceAtLeast(0),
                    findingCount = findingCount.coerceAtLeast(0),
                    breachRecordCount = breachRecordCount.coerceAtLeast(0),
                    graphEntityCount = graphEntityCount.coerceAtLeast(0),
                    graphRelationshipCount = graphRelationshipCount.coerceAtLeast(0),
                    cancelled = terminalCancelled,
                    failed = terminalFailed,
                    failureCode = safeFailureCode
                )
            )
            active = null
        }
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
