package io.dossier.app.domain.scanner

import android.content.Context
import io.dossier.app.domain.discovery.PivotAdmissionDecision
import io.dossier.app.domain.discovery.PivotAdmissionPolicy
import io.dossier.app.domain.discovery.PivotAdmissionRequest
import io.dossier.app.domain.discovery.PivotConfidenceBand
import io.dossier.app.domain.discovery.PivotSignalType
import io.dossier.app.domain.model.UsernameCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Explicit limits for recursive pivot expansion. Budgets are per signal type
 * in addition to the total frontier limit so one noisy signal cannot consume
 * the entire scan. The values are engineering guardrails, not identity
 * probabilities.
 */
@Serializable
data class PivotFrontierConfig(
    val maxDepth: Int = PivotAdmissionPolicy.DEFAULT_MAX_DEPTH,
    val maxTotalPivots: Int = DEFAULT_MAX_TOTAL_PIVOTS,
    val perSignalBudgets: Map<PivotSignalType, Int> = defaultSignalBudgets()
) {
    init {
        require(maxDepth in 1..PivotAdmissionPolicy.MAX_ALLOWED_DEPTH) {
            "maxDepth must remain within the allowed recursive depth bound"
        }
        require(maxTotalPivots in 1..MAX_ALLOWED_TOTAL_PIVOTS) {
            "maxTotalPivots must be bounded"
        }
        require(perSignalBudgets.keys.all { it in PivotSignalType.entries }) {
            "Unknown pivot signal type"
        }
        require(perSignalBudgets.values.all { it in 0..MAX_ALLOWED_SIGNAL_BUDGET }) {
            "Per-signal pivot budgets must be bounded"
        }
    }

    fun budgetFor(signalType: PivotSignalType): Int =
        perSignalBudgets[signalType] ?: 0

    companion object {
        const val DEFAULT_MAX_TOTAL_PIVOTS: Int = 30
        const val MAX_ALLOWED_TOTAL_PIVOTS: Int = 200
        const val MAX_ALLOWED_SIGNAL_BUDGET: Int = 100

        fun defaultSignalBudgets(): Map<PivotSignalType, Int> = mapOf(
            PivotSignalType.ExplicitProfileLink to 18,
            PivotSignalType.PersonalWebsiteCrossLink to 8,
            PivotSignalType.ExplicitPlatformMention to 12,
            PivotSignalType.SuppliedIdentifier to 8,
            // Common usernames are admitted only with independent
            // corroboration and remain deliberately scarce.
            PivotSignalType.CommonUsername to 2,
            PivotSignalType.NameOnly to 0,
            PivotSignalType.LocationOnly to 0,
            PivotSignalType.OccupationOnly to 0,
            PivotSignalType.FaceSimilarityOnly to 0
        )

        fun forScan(deepResearch: Boolean): PivotFrontierConfig =
            if (deepResearch) {
                PivotFrontierConfig()
            } else {
                PivotFrontierConfig(
                    maxTotalPivots = 15,
                    perSignalBudgets = defaultSignalBudgets().mapValues { (type, budget) ->
                        when (type) {
                            PivotSignalType.ExplicitProfileLink -> budget.coerceAtMost(10)
                            PivotSignalType.PersonalWebsiteCrossLink -> budget.coerceAtMost(4)
                            PivotSignalType.ExplicitPlatformMention -> budget.coerceAtMost(6)
                            PivotSignalType.SuppliedIdentifier -> budget.coerceAtMost(4)
                            PivotSignalType.CommonUsername -> budget.coerceAtMost(1)
                            else -> budget
                        }
                    }
                )
            }
    }
}

/** An admitted public candidate waiting to be fetched. */
@Serializable
internal data class PivotFrontierEntry(
    val key: String,
    val candidate: UsernameCandidate,
    val depth: Int,
    val signalType: PivotSignalType,
    val confidence: Float,
    val corroboratingEvidenceCount: Int,
    val sourceUrl: String?,
    val provenance: String,
    val admissionExplanation: String,
    val enqueuedAtEpochMillis: Long
)

/** Persisted, inspectable reason for admitting or rejecting a pivot. */
@Serializable
internal data class PivotDecisionDiagnostic(
    val key: String,
    val normalizedValue: String,
    val candidateUrl: String?,
    val sourceUrl: String?,
    val depth: Int,
    val signalType: PivotSignalType,
    val admitted: Boolean,
    val reason: String,
    val occurredAtEpochMillis: Long
)

@Serializable
internal data class PivotFrontierState(
    val version: Int,
    val requestId: String,
    val config: PivotFrontierConfig,
    val updatedAtEpochMillis: Long,
    val queue: List<PivotFrontierEntry>,
    val visitedKeys: Set<String>,
    val completedKeys: Set<String>,
    val admittedBySignal: Map<PivotSignalType, Int>,
    val diagnostics: List<PivotDecisionDiagnostic>
)

internal sealed interface PivotOffer {
    data class Admitted(val entry: PivotFrontierEntry) : PivotOffer
    data class Rejected(val diagnostic: PivotDecisionDiagnostic) : PivotOffer
}

/**
 * In-memory bounded frontier. The frontier is deliberately independent of
 * ProfileScanResult so a crash between enqueue and fetch leaves the pending
 * candidate available for the next request-scoped resume.
 */
internal class BoundedPivotFrontier internal constructor(
    private val requestId: String,
    val config: PivotFrontierConfig,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    state: PivotFrontierState? = null
) {
    private val queue = state?.queue?.toMutableList() ?: mutableListOf()
    private val visitedKeys = state?.visitedKeys?.toMutableSet() ?: mutableSetOf()
    private val completedKeys = state?.completedKeys?.toMutableSet() ?: mutableSetOf()
    private val admittedBySignal = state?.admittedBySignal?.toMutableMap()
        ?: mutableMapOf()
    private val diagnostics = (state?.diagnostics?.take(MAX_DIAGNOSTICS) ?: emptyList()).toMutableList()

    init {
        require(ScanLifecycleRecord.isCanonicalUuid(requestId)) {
            "requestId must be a canonical lowercase UUID"
        }
        require(state == null || state.version == FORMAT_VERSION) {
            "Unsupported pivot frontier format"
        }
        require(state == null || state.requestId == requestId) {
            "Pivot frontier request scope mismatch"
        }
        require(state == null || state.config == config) {
            "Pivot frontier configuration mismatch"
        }
        require(queue.size <= config.maxTotalPivots) {
            "Persisted pivot queue exceeds the configured total budget"
        }
        require(admittedBySignal.values.all { it in 0..PivotFrontierConfig.MAX_ALLOWED_SIGNAL_BUDGET }) {
            "Persisted pivot signal count is invalid"
        }
        require(admittedBySignal.values.sum() <= config.maxTotalPivots) {
            "Persisted pivot count exceeds the configured total budget"
        }
        queue.removeAll { !isSafeEntry(it) }
        visitedKeys.removeAll { !isSafeKey(it) }
        completedKeys.removeAll { !isSafeKey(it) }
    }

    val pendingCount: Int get() = queue.size
    val visitedCount: Int get() = visitedKeys.size
    val admittedCount: Int get() = admittedBySignal.values.sum()
    val rejectedCount: Int get() = diagnostics.count { !it.admitted }
    val decisionDiagnostics: List<PivotDecisionDiagnostic> get() = diagnostics.toList()
    val visitedUrls: Set<String> get() = visitedKeys.toSet()

    /** Marks initial/direct candidates as visited without inventing diagnostics. */
    fun markVisited(urls: Iterable<String>) {
        urls.mapNotNull(::canonicalUrlKey)
            .filter(::isSafeKey)
            .forEach(visitedKeys::add)
    }

    /**
     * Applies the shared conservative admission policy and then the frontier
     * budgets. A rejected signal is retained as a diagnostic but is not marked
     * visited, allowing stronger independent evidence to admit the same URL
     * later in the scan.
     */
    fun offer(
        candidate: UsernameCandidate,
        depth: Int,
        signalType: PivotSignalType,
        confidence: Float = candidate.confidence,
        corroboratingEvidenceCount: Int = 1,
        sourceUrl: String? = null,
        provenance: String = "",
        admissionExplanation: String = ""
    ): PivotOffer {
        val key = canonicalUrlKey(candidate.url)
        val normalized = candidate.username.trim().lowercase(Locale.ROOT)
        val now = safeNow()
        if (key == null) {
            return reject(
                key = "invalid:${sha256(candidate.url.trim())}",
                normalizedValue = normalized,
                candidateUrl = null,
                sourceUrl = sourceUrl,
                depth = depth,
                signalType = signalType,
                reason = "Pivot URL must be an absolute public HTTP(S) URL",
                now = now
            )
        }
        if (depth !in 1..config.maxDepth) {
            return reject(
                key = key,
                normalizedValue = normalized,
                candidateUrl = key,
                sourceUrl = sourceUrl,
                depth = depth,
                signalType = signalType,
                reason = "Pivot depth exceeds configured frontier maximum (${config.maxDepth})",
                now = now
            )
        }
        if (key in visitedKeys || queue.any { it.key == key }) {
            return reject(
                key = key,
                normalizedValue = normalized,
                candidateUrl = key,
                sourceUrl = sourceUrl,
                depth = depth,
                signalType = signalType,
                reason = "Pivot URL was already admitted or visited in this scan",
                now = now
            )
        }

        val policy = PivotAdmissionPolicy.decide(
            PivotAdmissionRequest(
                signalType = signalType,
                normalizedValue = normalized,
                confidence = confidence.coerceIn(0f, 1f),
                depth = depth,
                corroboratingEvidenceCount = corroboratingEvidenceCount.coerceAtLeast(0),
                alreadyVisited = false,
                maxDepth = config.maxDepth
            )
        )
        if (policy is PivotAdmissionDecision.Reject) {
            return reject(
                key = key,
                normalizedValue = normalized,
                candidateUrl = key,
                sourceUrl = sourceUrl,
                depth = depth,
                signalType = signalType,
                reason = policy.explanation,
                now = now
            )
        }

        val signalCount = admittedBySignal[signalType] ?: 0
        val signalBudget = config.budgetFor(signalType)
        if (signalCount >= signalBudget) {
            return reject(
                key = key,
                normalizedValue = normalized,
                candidateUrl = key,
                sourceUrl = sourceUrl,
                depth = depth,
                signalType = signalType,
                reason = "Per-signal pivot budget exhausted ($signalBudget)",
                now = now
            )
        }
        if (admittedCount >= config.maxTotalPivots) {
            return reject(
                key = key,
                normalizedValue = normalized,
                candidateUrl = key,
                sourceUrl = sourceUrl,
                depth = depth,
                signalType = signalType,
                reason = "Total recursive pivot budget exhausted (${config.maxTotalPivots})",
                now = now
            )
        }

        val admitted = policy as PivotAdmissionDecision.Admit
        val entry = PivotFrontierEntry(
            key = key,
            candidate = candidate,
            depth = depth,
            signalType = signalType,
            confidence = confidence.coerceIn(0f, 1f),
            corroboratingEvidenceCount = corroboratingEvidenceCount.coerceAtLeast(0),
            sourceUrl = sourceUrl,
            provenance = provenance,
            admissionExplanation = admissionExplanation.ifBlank { admitted.explanation },
            enqueuedAtEpochMillis = now
        )
        queue += entry
        visitedKeys += key
        admittedBySignal[signalType] = signalCount + 1
        addDiagnostic(
            PivotDecisionDiagnostic(
                key = key,
                normalizedValue = normalized,
                candidateUrl = key,
                sourceUrl = sourceUrl,
                depth = depth,
                signalType = signalType,
                admitted = true,
                reason = entry.admissionExplanation,
                occurredAtEpochMillis = now
            )
        )
        return PivotOffer.Admitted(entry)
    }

    /** Records a policy rejection produced while extracting a raw signal. */
    fun recordRejected(
        key: String,
        normalizedValue: String,
        candidateUrl: String?,
        sourceUrl: String?,
        depth: Int,
        signalType: PivotSignalType,
        reason: String
    ) {
        addDiagnostic(
            PivotDecisionDiagnostic(
                key = key.ifBlank { "invalid:${sha256(normalizedValue)}" },
                normalizedValue = normalizedValue.trim().lowercase(Locale.ROOT).take(MAX_DIAGNOSTIC_VALUE),
                candidateUrl = candidateUrl?.let(::canonicalUrlKey),
                sourceUrl = sourceUrl?.let(::canonicalUrlKey),
                depth = depth,
                signalType = signalType,
                admitted = false,
                reason = reason.take(MAX_DIAGNOSTIC_REASON),
                occurredAtEpochMillis = safeNow()
            )
        )
    }

    /** Returns pending entries up to [maxEntries] without removing them. */
    fun pending(maxEntries: Int, maxDepth: Int): List<PivotFrontierEntry> =
        queue.asSequence()
            .filter { it.depth <= maxDepth }
            .take(maxEntries.coerceAtLeast(0))
            .toList()

    /** Returns only entries at one frontier depth, preserving queue order. */
    fun pendingAtDepth(maxEntries: Int, depth: Int): List<PivotFrontierEntry> =
        queue.asSequence()
            .filter { it.depth == depth }
            .take(maxEntries.coerceAtLeast(0))
            .toList()

    /** A completed attempt is removed; interrupted entries remain resumable. */
    fun complete(key: String) {
        val removed = queue.removeAll { it.key == key }
        if (removed) completedKeys += key
    }

    fun snapshot(): PivotFrontierState = PivotFrontierState(
        version = FORMAT_VERSION,
        requestId = requestId,
        config = config,
        updatedAtEpochMillis = safeNow(),
        queue = queue.toList(),
        visitedKeys = visitedKeys.toSet(),
        completedKeys = completedKeys.toSet(),
        admittedBySignal = admittedBySignal.toMap(),
        diagnostics = diagnostics.toList()
    )

    private fun reject(
        key: String,
        normalizedValue: String,
        candidateUrl: String?,
        sourceUrl: String?,
        depth: Int,
        signalType: PivotSignalType,
        reason: String,
        now: Long
    ): PivotOffer.Rejected {
        val diagnostic = PivotDecisionDiagnostic(
            key = key.take(MAX_DIAGNOSTIC_KEY),
            normalizedValue = normalizedValue.take(MAX_DIAGNOSTIC_VALUE),
            candidateUrl = candidateUrl,
            sourceUrl = sourceUrl?.let(::canonicalUrlKey),
            depth = depth,
            signalType = signalType,
            admitted = false,
            reason = reason.take(MAX_DIAGNOSTIC_REASON),
            occurredAtEpochMillis = now
        )
        addDiagnostic(diagnostic)
        return PivotOffer.Rejected(diagnostic)
    }

    private fun addDiagnostic(diagnostic: PivotDecisionDiagnostic) {
        diagnostics += diagnostic
        if (diagnostics.size > MAX_DIAGNOSTICS) {
            diagnostics.subList(0, diagnostics.size - MAX_DIAGNOSTICS).clear()
        }
    }

    private fun safeNow(): Long = nowMillis().coerceAtLeast(0L)

    private fun isSafeEntry(entry: PivotFrontierEntry): Boolean =
        isSafeKey(entry.key) &&
            canonicalUrlKey(entry.candidate.url) == entry.key &&
            entry.depth in 1..config.maxDepth &&
            entry.confidence in 0f..1f &&
            entry.corroboratingEvidenceCount >= 0 &&
            entry.provenance.length <= MAX_DIAGNOSTIC_REASON &&
            entry.admissionExplanation.length <= MAX_DIAGNOSTIC_REASON

    companion object {
        internal const val FORMAT_VERSION = 1
        internal const val MAX_DIAGNOSTICS = 256
        private const val MAX_DIAGNOSTIC_VALUE = 160
        private const val MAX_DIAGNOSTIC_KEY = 512
        private const val MAX_DIAGNOSTIC_REASON = 512
        private const val SHA_256_HEX_LENGTH = 64

        fun canonicalUrlKey(url: String): String? = runCatching {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val hostValue = uri.host?.lowercase(Locale.ROOT)
            if (scheme !in setOf("http", "https") || hostValue.isNullOrBlank() || uri.rawUserInfo != null) {
                return@runCatching null
            }
            val host = if (':' in hostValue && !hostValue.startsWith("[")) "[$hostValue]" else hostValue
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty()
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$port$path$query"
        }.getOrNull()

        private fun isSafeKey(value: String): Boolean =
            value.length in 1..MAX_DIAGNOSTIC_KEY &&
                !value.contains('\n') &&
                canonicalUrlKey(value) == value

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

/**
 * Request-scoped encrypted persistence for the frontier. The profile
 * checkpoint crypto is reused with a distinct AAD namespace; reads never
 * create a Keystore key, and writes use an atomic, fsynced replacement.
 */
internal class PivotFrontierStore internal constructor(
    private val rootDir: File,
    private val requestId: String,
    private val crypto: CheckpointCrypto,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val dirSyncer: DirectorySyncer = object : DirectorySyncer {
        override fun sync(dir: File) = Unit
    }
) {
    constructor(context: Context, requestId: String) : this(
        rootDir = context.applicationContext.filesDir,
        requestId = requestId,
        crypto = AndroidKeystoreCheckpointCrypto("dossier-pivot-frontier-v1"),
        dirSyncer = AndroidDirectorySyncer()
    )

    init {
        require(ScanLifecycleRecord.isCanonicalUuid(requestId)) {
            "requestId must be a canonical lowercase UUID"
        }
    }

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    fun load(config: PivotFrontierConfig): BoundedPivotFrontier? = synchronized(STORE_LOCK) {
        runCatching {
            val target = frontierFile()
            val parent = target.parentFile ?: return@runCatching null
            if (!isSafeDirectoryChain(rootDir, parent) || isTombstoned()) return@runCatching null
            if (!isContained(target) || !isSafeRegularFile(target) || target.length() > MAX_ENVELOPE_BYTES) {
                return@runCatching null
            }
            val envelope = json.decodeFromString<PivotFrontierEnvelope>(target.readBounded())
            if (envelope.version != FORMAT_VERSION || envelope.requestId != requestId) return@runCatching null
            val now = nowMillis()
            if (now < 0L || envelope.savedAtEpochMillis < 0L || envelope.savedAtEpochMillis > now) {
                return@runCatching null
            }
            if (now - envelope.savedAtEpochMillis > MAX_AGE_MILLIS) return@runCatching null
            val plaintext = crypto.decrypt(
                envelope.ivBase64,
                envelope.ciphertextBase64,
                aad()
            ) ?: return@runCatching null
            if (plaintext.size > MAX_PAYLOAD_BYTES) return@runCatching null
            val state = json.decodeFromString<PivotFrontierState>(String(plaintext, Charsets.UTF_8))
            if (state.requestId != requestId || state.version != BoundedPivotFrontier.FORMAT_VERSION) {
                return@runCatching null
            }
            BoundedPivotFrontier(requestId, config, nowMillis, state)
        }.getOrNull()
    }

    fun save(frontier: BoundedPivotFrontier): Boolean = synchronized(STORE_LOCK) {
        runCatching {
            val parent = frontierFile().parentFile ?: return@runCatching false
            if (!isSafeDirectoryChain(rootDir, parent)) return@runCatching false
            if (isTombstoned()) return@runCatching false
            val now = nowMillis()
            if (now < 0L) return@runCatching false
            val plaintext = json.encodeToString(frontier.snapshot()).toByteArray(Charsets.UTF_8)
            if (plaintext.size > MAX_PAYLOAD_BYTES) return@runCatching false
            val encrypted = crypto.encrypt(plaintext, aad())
            val envelope = PivotFrontierEnvelope(
                version = FORMAT_VERSION,
                requestId = requestId,
                savedAtEpochMillis = now,
                ivBase64 = encrypted.ivBase64,
                ciphertextBase64 = encrypted.ciphertextBase64
            )
            val encoded = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_ENVELOPE_BYTES) return@runCatching false
            atomicWrite(frontierFile(), encoded)
            true
        }.getOrDefault(false)
    }

    fun clear(): Boolean = synchronized(STORE_LOCK) {
        runCatching {
            val file = frontierFile()
            val tombstone = tombstoneFile()
            if (!isContained(file) || !isContained(tombstone)) return@runCatching false
            val parent = file.parentFile ?: return@runCatching false
            if (!isSafeDirectoryChain(rootDir, parent)) return@runCatching false
            if (!ensureTombstone(rootDir, tombstone, dirSyncer)) return@runCatching false
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching true
            if (!isSafeRegularFile(file)) return@runCatching false
            file.delete().also { if (it) file.parentFile?.let(dirSyncer::sync) }
        }.getOrDefault(false)
    }

    internal fun frontierFileForTesting(): File = frontierFile()

    private fun aad(): ByteArray = "pivot-frontier-v$FORMAT_VERSION|$requestId".toByteArray(Charsets.UTF_8)

    private fun frontierFile(): File =
        File(File(rootDir, ROOT_DIRECTORY), "${sha256(requestId)}$FILE_EXTENSION")

    private fun tombstoneFile(): File =
        File(File(rootDir, ROOT_DIRECTORY), "${sha256(requestId)}$CLEAR_TOMBSTONE_EXTENSION")

    private fun isTombstoned(): Boolean =
        Files.exists(tombstoneFile().toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun atomicWrite(target: File, content: ByteArray) {
        val parent = target.parentFile ?: throw IOException("Frontier has no parent")
        if (!isContained(target) || !isContained(parent)) {
            throw IOException("Frontier path escaped filesDir")
        }
        if (!isSafeDirectoryChain(rootDir, parent)) throw IOException("Frontier directory chain is unsafe")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create frontier directory")
        }
        if (!isSafeDirectoryChain(rootDir, parent)) {
            throw IOException("Frontier directory is unsafe")
        }
        val temporary = File(parent, "${target.name}.${UUID.randomUUID()}$TEMP_EXTENSION")
        try {
            FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ).use { channel ->
                val buffer = ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            dirSyncer.sync(parent)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun isSafeRegularFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())

    private fun isContained(target: File): Boolean = runCatching {
        target.toPath().toAbsolutePath().normalize()
            .startsWith(rootDir.toPath().toAbsolutePath().normalize())
    }.getOrDefault(false)

    private fun File.readBounded(): String {
        FileChannel.open(toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() > MAX_ENVELOPE_BYTES) throw IOException("Frontier envelope is too large")
            val buffer = ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.flip()
            return String(buffer.array(), 0, buffer.limit(), Charsets.UTF_8)
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_PAYLOAD_BYTES = 512 * 1024
        private const val MAX_ENVELOPE_BYTES = 768 * 1024
        private const val ROOT_DIRECTORY = "dossier_frontier/pivot-v1"
        private const val FILE_EXTENSION = ".frontier"
        private const val CLEAR_TOMBSTONE_EXTENSION = ".cleared"
        private const val TEMP_EXTENSION = ".tmp"
        private val CLEAR_TOMBSTONE_BYTES = "pivot-frontier-cleared-v1\n".toByteArray(Charsets.UTF_8)
        private val STORE_LOCK = Any()

        fun clearRequest(context: Context, requestId: String): Boolean = runCatching {
            PivotFrontierStore(context.applicationContext, requestId).clear()
        }.getOrDefault(false)

        fun clearAll(context: Context): Boolean = clearAll(
            rootDir = context.applicationContext.filesDir,
            dirSyncer = AndroidDirectorySyncer()
        )

        internal fun clearAll(rootDir: File, dirSyncer: DirectorySyncer): Boolean =
            synchronized(STORE_LOCK) {
                runCatching {
                    val root = File(rootDir, ROOT_DIRECTORY)
                    if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        return@synchronized true
                    }
                    if (!isSafeDirectoryChain(rootDir, root)) return@synchronized false

                    var cleared = true
                    root.listFiles().orEmpty().forEach { entry ->
                        val requestHash = entry.name.removeSuffix(FILE_EXTENSION)
                        if (entry.name.endsWith(FILE_EXTENSION) && isLowerHexSha256(requestHash)) {
                            val tombstone = File(root, "$requestHash$CLEAR_TOMBSTONE_EXTENSION")
                            if (!ensureTombstone(rootDir, tombstone, dirSyncer)) {
                                cleared = false
                                return@forEach
                            }
                        }
                        if (!entry.name.endsWith(CLEAR_TOMBSTONE_EXTENSION)) {
                            val path = entry.toPath()
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                                Files.isSymbolicLink(path) ||
                                !entry.delete()
                            ) {
                                cleared = false
                            } else {
                                dirSyncer.sync(root)
                            }
                        }
                    }
                    cleared
                }.getOrDefault(false)
            }

        private fun ensureTombstone(
            rootDir: File,
            tombstone: File,
            dirSyncer: DirectorySyncer
        ): Boolean = runCatching {
            if (Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching Files.isRegularFile(
                    tombstone.toPath(),
                    LinkOption.NOFOLLOW_LINKS
                ) && !Files.isSymbolicLink(tombstone.toPath())
            }
            val boundary = rootDir.toPath().toAbsolutePath().normalize()
            val target = tombstone.toPath().toAbsolutePath().normalize()
            if (!target.startsWith(boundary)) return@runCatching false
            val parent = tombstone.parentFile ?: return@runCatching false
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                return@runCatching false
            }
            if (!isSafeDirectoryChain(rootDir, parent)) return@runCatching false
            val temporary = File(parent, "${tombstone.name}.${UUID.randomUUID()}$TEMP_EXTENSION")
            try {
                FileChannel.open(
                    temporary.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(CLEAR_TOMBSTONE_BYTES)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(
                    temporary.toPath(),
                    tombstone.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
                dirSyncer.sync(parent)
                true
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }.getOrDefault(false)

        private fun isLowerHexSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        private fun isSafeDirectoryChain(rootDir: File, directory: File): Boolean = runCatching {
            val boundary = rootDir.toPath().toAbsolutePath().normalize()
            var current = directory.toPath().toAbsolutePath().normalize()
            if (!current.startsWith(boundary)) return@runCatching false
            while (current != boundary) {
                if (Files.isSymbolicLink(current)) return@runCatching false
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                ) return@runCatching false
                current = current.parent ?: return@runCatching false
            }
            !Files.isSymbolicLink(boundary) &&
                Files.isDirectory(boundary, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

@Serializable
private data class PivotFrontierEnvelope(
    val version: Int,
    val requestId: String,
    val savedAtEpochMillis: Long,
    val ivBase64: String,
    val ciphertextBase64: String
)
