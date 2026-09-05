package io.dossier.app.domain.scanner

import android.content.Context
import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedAdmissionConfig
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedSafety
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.withResolvedRelationshipEvidence
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
 * Typed seed execution is intentionally narrow in this tranche.  The
 * frontier still persists every admitted kind, but only these kinds have a
 * reviewed executor today.  Unsupported values remain visible as explicit
 * unavailable entries instead of silently disappearing from recovery state.
 */
internal val EXECUTABLE_TYPED_SEED_KINDS: Set<TypedSeedKind> = setOf(
    TypedSeedKind.Url,
    TypedSeedKind.Domain,
    TypedSeedKind.Document,
    TypedSeedKind.Archive
)

/** Compatibility value for direct JVM store tests; production passes its plan hash. */
internal const val TYPED_FRONTIER_DEFAULT_PLAN_FINGERPRINT =
    "0000000000000000000000000000000000000000000000000000000000000000"

@Serializable
internal enum class TypedSeedFrontierEntryState {
    Pending,
    InFlight,
    Completed,
    Unavailable
}

@Serializable
internal data class TypedSeedFrontierEntry(
    val key: String,
    val seed: TypedSeed,
    val state: TypedSeedFrontierEntryState = TypedSeedFrontierEntryState.Pending,
    val attempts: Int = 0,
    val lastAttemptAtEpochMillis: Long? = null,
    val unavailableReason: String? = null
) {
    init {
        require(key.isNotBlank()) { "Typed frontier entry key must not be blank." }
        require(attempts >= 0) { "Typed frontier attempts must not be negative." }
        require(unavailableReason == null || unavailableReason.length <= MAX_REASON_CHARS) {
            "Typed frontier unavailable reason is too long."
        }
    }

    companion object {
        private const val MAX_REASON_CHARS = 256
    }
}

/** Bounded explanation for a seed that could not enter the queue. */
@Serializable
internal data class TypedSeedFrontierRejection(
    val kind: TypedSeedKind,
    val value: String,
    val reason: String
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_VALUE_CHARS) {
            "Typed frontier rejection value is invalid."
        }
        require(reason.isNotBlank() && reason.length <= MAX_REASON_CHARS) {
            "Typed frontier rejection reason is invalid."
        }
    }

    private companion object {
        const val MAX_VALUE_CHARS = 256
        const val MAX_REASON_CHARS = 256
    }
}

/** Bounded, persisted configuration for the general typed frontier. */
@Serializable
internal data class TypedSeedFrontierConfig(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxTotalSeeds: Int = DEFAULT_MAX_TOTAL_SEEDS,
    val perKindBudgets: Map<TypedSeedKind, Int> = defaultBudgets()
) {
    init {
        require(maxDepth in 0..TypedSeedAdmissionConfig.MAX_ALLOWED_DEPTH) {
            "Typed frontier depth must remain within the admission bound."
        }
        require(maxTotalSeeds in 1..MAX_ALLOWED_TOTAL_SEEDS) {
            "Typed frontier total budget must remain bounded."
        }
        require(perKindBudgets.keys.all { it in TypedSeedKind.entries }) {
            "Typed frontier contains an unknown seed kind."
        }
        require(perKindBudgets.values.all { it in 0..MAX_ALLOWED_KIND_BUDGET }) {
            "Typed frontier per-kind budgets must remain bounded."
        }
    }

    fun budgetFor(kind: TypedSeedKind): Int = perKindBudgets[kind] ?: 0

    fun admissionConfig(): TypedSeedAdmissionConfig = TypedSeedAdmissionConfig(
        maxDepth = maxDepth,
        maxTotalSeeds = maxTotalSeeds,
        perKindBudgets = perKindBudgets
    )

    companion object {
        const val DEFAULT_MAX_DEPTH = 4
        const val DEFAULT_MAX_TOTAL_SEEDS = 64
        const val MAX_ALLOWED_TOTAL_SEEDS = 256
        const val MAX_ALLOWED_KIND_BUDGET = 128

        fun defaultBudgets(): Map<TypedSeedKind, Int> =
            TypedSeedAdmissionConfig.defaultBudgets()
    }
}

@Serializable
internal data class TypedSeedFrontierState(
    val version: Int,
    val requestId: String,
    val ownerId: String,
    val generation: String,
    val planFingerprint: String,
    val config: TypedSeedFrontierConfig,
    val updatedAtEpochMillis: Long,
    val entries: List<TypedSeedFrontierEntry>,
    val emittedEvidence: EvidenceCollection = EvidenceCollection(),
    val rejectionDiagnostics: List<TypedSeedFrontierRejection> = emptyList()
)

internal sealed interface TypedSeedFrontierLoadResult {
    data class Available(val frontier: TypedSeedFrontier) : TypedSeedFrontierLoadResult
    data object Missing : TypedSeedFrontierLoadResult
    data object StaleOwner : TypedSeedFrontierLoadResult
    data object Unavailable : TypedSeedFrontierLoadResult
}

internal sealed interface TypedSeedFrontierWriteResult {
    data object Saved : TypedSeedFrontierWriteResult
    data object Missing : TypedSeedFrontierWriteResult
    data object StaleOwner : TypedSeedFrontierWriteResult
    data object Tombstoned : TypedSeedFrontierWriteResult
    data object Invalid : TypedSeedFrontierWriteResult
    data object StorageFailure : TypedSeedFrontierWriteResult
}

/**
 * In-memory typed frontier shared by interactive scans and the durable store.
 * The queue is intentionally item-oriented: a process death can retry an
 * in-flight item, while completed/unavailable items remain persisted for
 * inspection and deduplication.
 */
internal class TypedSeedFrontier internal constructor(
    val requestId: String,
    val config: TypedSeedFrontierConfig,
    val ownerId: String? = null,
    val generation: String? = null,
    val planFingerprint: String? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    state: TypedSeedFrontierState? = null
) {
    private val entriesByKey = LinkedHashMap<String, TypedSeedFrontierEntry>()
    private val rejectionDiagnostics = ArrayDeque<TypedSeedFrontierRejection>()
    private var emittedEvidence: EvidenceCollection = state?.emittedEvidence ?: EvidenceCollection()

    init {
        require(ScanLifecycleRecord.isCanonicalUuid(requestId)) {
            "requestId must be a canonical lowercase UUID"
        }
        require(ownerId == null || ScanLifecycleRecord.isCanonicalUuid(ownerId)) {
            "ownerId must be a canonical lowercase UUID"
        }
        require(generation == null || ScanLifecycleRecord.isCanonicalUuid(generation)) {
            "generation must be a canonical lowercase UUID"
        }
        require(planFingerprint == null || isPlanFingerprint(planFingerprint)) {
            "planFingerprint must be 64 lowercase hexadecimal characters"
        }
        require((ownerId == null) == (generation == null)) {
            "Typed frontier owner and generation must be supplied together."
        }
        require(state == null || state.version == FORMAT_VERSION) {
            "Unsupported typed frontier format."
        }
        require(state == null || state.requestId == requestId) {
            "Typed frontier request scope mismatch."
        }
        require(state == null || state.ownerId == ownerId) {
            "Typed frontier owner scope mismatch."
        }
        require(state == null || state.generation == generation) {
            "Typed frontier generation scope mismatch."
        }
        require(state == null || state.planFingerprint == planFingerprint) {
            "Typed frontier plan scope mismatch."
        }
        require(state == null || state.config == config) {
            "Typed frontier configuration mismatch."
        }
        val persistedEntries = state?.entries.orEmpty()
        require(persistedEntries.size <= config.maxTotalSeeds) {
            "Persisted typed frontier exceeds the configured total budget."
        }
        require(
            persistedEntries
                .groupingBy { it.seed.kind }
                .eachCount()
                .all { (kind, count) -> count <= config.budgetFor(kind) }
        ) {
            "Persisted typed frontier exceeds a per-kind budget."
        }
        require(emittedEvidence.evidence.size <= MAX_EVIDENCE_RECORDS) {
            "Persisted typed frontier evidence exceeds the configured bound."
        }
        require(emittedEvidence.relationships.size <= MAX_RELATIONSHIPS) {
            "Persisted typed frontier relationships exceed the configured bound."
        }
        require(state?.rejectionDiagnostics.orEmpty().size <= MAX_REJECTION_DIAGNOSTICS) {
            "Persisted typed frontier rejection diagnostics exceed the configured bound."
        }
        require(persistedEntries.all(::isSafeEntry)) {
            "Persisted typed frontier contains an unsafe entry."
        }
        state?.rejectionDiagnostics.orEmpty().forEach { rejection ->
            require(rejection.value.length <= MAX_REJECTION_VALUE_CHARS)
            require(rejection.reason.length <= MAX_REASON_CHARS)
            rejectionDiagnostics.addLast(rejection)
        }
        persistedEntries.forEach { entry ->
            if (entriesByKey.put(entry.key, recoverInFlight(entry)) != null) {
                throw IllegalArgumentException("Duplicate typed frontier key")
            }
        }
    }

    val entries: List<TypedSeedFrontierEntry>
        get() = entriesByKey.values.toList()

    val pendingCount: Int
        get() = entriesByKey.values.count { it.state == TypedSeedFrontierEntryState.Pending }

    val inFlightCount: Int
        get() = entriesByKey.values.count { it.state == TypedSeedFrontierEntryState.InFlight }

    val completedCount: Int
        get() = entriesByKey.values.count { it.state == TypedSeedFrontierEntryState.Completed }

    val unavailableCount: Int
        get() = entriesByKey.values.count { it.state == TypedSeedFrontierEntryState.Unavailable }

    val admittedCount: Int
        get() = entriesByKey.size

    /** Bounded reasons for admission drops, retained for truthful diagnostics. */
    val rejectionDiagnosticsSnapshot: List<TypedSeedFrontierRejection>
        get() = rejectionDiagnostics.toList()

    /** Canonical evidence emitted by completed typed seed executions. */
    val evidence: EvidenceCollection
        get() = emittedEvidence

    /** Pending entries in priority/insertion order. */
    fun pending(maxEntries: Int = config.maxTotalSeeds): List<TypedSeedFrontierEntry> =
        entriesByKey.values
            .asSequence()
            .filter { it.state == TypedSeedFrontierEntryState.Pending }
            // Keep the queue bounded but let high-entropy, verified public
            // pivots run before low-value user/media seeds.  `withIndex()` is
            // the stable insertion tie-break: equal-priority entries retain
            // their admission order even after persistence/recovery.
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<TypedSeedFrontierEntry>> {
                    pendingPriority(it.value.seed)
                }.thenBy { it.index }
            )
            .map { it.value }
            .take(maxEntries.coerceAtLeast(0))
            .toList()

    /** Alias used by queue-oriented callers. */
    fun queue(maxEntries: Int = config.maxTotalSeeds): List<TypedSeedFrontierEntry> =
        pending(maxEntries)

    /**
     * Admits one seed.  Duplicate values merge provenance but never become a
     * second queue item.  Unsupported/unsafe kinds are retained as
     * [TypedSeedFrontierEntryState.Unavailable] so the limitation is durable.
     */
    fun offer(seed: TypedSeed): Boolean {
        if (seed.depth !in 0..config.maxDepth ||
            seed.exactValue.length > TypedSeed.MAX_VALUE_CHARS ||
            seed.normalizedValue.length > TypedSeed.MAX_VALUE_CHARS
        ) {
            recordRejection(seed, "Seed exceeded the configured depth or value bound.")
            return false
        }

        val key = keyFor(seed)
        val existing = entriesByKey[key]
        if (existing != null) {
            val merged = merge(existing, seed)
            entriesByKey[key] = merged
            return false
        }
        val kindBudget = config.budgetFor(seed.kind)
        if (kindBudget <= 0) {
            recordRejection(seed, "Seed kind budget is disabled for ${seed.kind.name}.")
            return false
        }

        val totalFull = entriesByKey.size >= config.maxTotalSeeds
        val kindFull = entriesByKey.values.count { it.seed.kind == seed.kind } >= kindBudget
        if (totalFull || kindFull) {
            val eviction = evictionCandidate(seed, kindFull)
                ?: run {
                    recordRejection(
                        seed,
                        "Frontier budget is full and no pending lower-priority entry can be evicted."
                    )
                    return false
                }
            // Do not let a late low-value observation displace work at the
            // same priority. A strictly better seed may replace only a
            // pending/unavailable entry; completed and in-flight work remains
            // durable and cannot be silently discarded. An actionable
            // executable seed is the one exception for an equal-priority
            // unavailable entry: that terminal entry cannot make progress,
            // while the incoming URL/document/archive can still be fetched.
            val incomingPriority = pendingPriority(seed)
            val evictionPriority = pendingPriority(eviction.seed)
            val equalPriorityUnavailable =
                incomingPriority == evictionPriority &&
                    eviction.state == TypedSeedFrontierEntryState.Unavailable &&
                    isActionableExecutable(seed)
            if (incomingPriority > evictionPriority ||
                (incomingPriority == evictionPriority && !equalPriorityUnavailable)
            ) {
                recordRejection(
                    seed,
                    "Frontier budget is full; existing work has equal or higher priority."
                )
                return false
            }
            entriesByKey.remove(eviction.key)
        }

        val unavailableReason = unavailableReason(seed)
        entriesByKey[key] = TypedSeedFrontierEntry(
            key = key,
            seed = seed,
            state = if (unavailableReason == null) {
                TypedSeedFrontierEntryState.Pending
            } else {
                TypedSeedFrontierEntryState.Unavailable
            },
            unavailableReason = unavailableReason
        )
        return true
    }

    private fun recordRejection(seed: TypedSeed, reason: String) {
        val value = seed.exactValue.trim().take(MAX_REJECTION_VALUE_CHARS).ifBlank {
            seed.kind.name
        }
        val diagnostic = TypedSeedFrontierRejection(
            kind = seed.kind,
            value = value,
            reason = reason.trim().take(MAX_REASON_CHARS)
        )
        rejectionDiagnostics.addLast(diagnostic)
        while (rejectionDiagnostics.size > MAX_REJECTION_DIAGNOSTICS) {
            rejectionDiagnostics.removeFirst()
        }
    }

    private fun evictionCandidate(
        incoming: TypedSeed,
        kindBudgetFull: Boolean
    ): TypedSeedFrontierEntry? = entriesByKey.values
        .asSequence()
        .filter {
            it.state == TypedSeedFrontierEntryState.Pending ||
                it.state == TypedSeedFrontierEntryState.Unavailable
        }
        .filter { !kindBudgetFull || it.seed.kind == incoming.kind }
        // A larger priority value is less valuable. Depth breaks ties so a
        // deeper observation is displaced before a shallower one.
        .maxWithOrNull(
            compareBy<TypedSeedFrontierEntry> { pendingPriority(it.seed) }
                .thenBy { it.seed.depth }
        )

    /** Merge one executor result into the encrypted frontier checkpoint. */
    fun mergeEvidence(collection: EvidenceCollection) {
        if (collection.evidence.isEmpty() && collection.relationships.isEmpty()) return
        emittedEvidence = EvidenceCollection(
            evidence = (emittedEvidence.evidence + collection.evidence)
                .distinctBy { it.id }
                .take(MAX_EVIDENCE_RECORDS),
            relationships = EvidenceRelationshipPolicy.normalize(
                emittedEvidence.relationships + collection.relationships
            )
                .take(MAX_RELATIONSHIPS)
        ).withResolvedRelationshipEvidence()
    }

    /** Marks one pending item in-flight before its network operation starts. */
    fun begin(key: String): TypedSeedFrontierEntry? {
        val current = entriesByKey[key] ?: return null
        if (current.state != TypedSeedFrontierEntryState.Pending) return null
        if (current.attempts >= MAX_ATTEMPTS) {
            entriesByKey[key] = current.copy(
                state = TypedSeedFrontierEntryState.Unavailable,
                unavailableReason = "Typed seed reached the maximum retry count."
            )
            return null
        }
        val now = nowMillis().coerceAtLeast(0L)
        val next = current.copy(
            state = TypedSeedFrontierEntryState.InFlight,
            attempts = (current.attempts + 1).coerceAtMost(MAX_ATTEMPTS),
            lastAttemptAtEpochMillis = now
        )
        entriesByKey[key] = next
        return next
    }

    /** Completed work is never re-enqueued on a retry. */
    fun complete(key: String): Boolean = transition(
        key = key,
        state = TypedSeedFrontierEntryState.Completed,
        reason = null
    )

    /**
     * Unsupported or failed work remains inspectable.  It is not retried
     * until a future executor explicitly re-admits a new generation.
     */
    fun unavailable(key: String, reason: String): Boolean = transition(
        key = key,
        state = TypedSeedFrontierEntryState.Unavailable,
        reason = reason
    )

    /** Parent cancellation releases an in-flight item back to Pending. */
    fun releaseInFlight(key: String): Boolean {
        val current = entriesByKey[key] ?: return false
        if (current.state != TypedSeedFrontierEntryState.InFlight) return false
        entriesByKey[key] = current.copy(state = TypedSeedFrontierEntryState.Pending)
        return true
    }

    fun snapshot(): TypedSeedFrontierState {
        val owner = ownerId ?: throw IllegalStateException("Durable snapshot requires an owner")
        val generationRef = generation ?: throw IllegalStateException("Durable snapshot requires a generation")
        val plan = planFingerprint
            ?: throw IllegalStateException("Durable snapshot requires a plan fingerprint")
        return snapshot(plan)
    }

    internal fun snapshot(plan: String): TypedSeedFrontierState {
        val owner = ownerId ?: throw IllegalStateException("Durable snapshot requires an owner")
        val generationRef = generation ?: throw IllegalStateException("Durable snapshot requires a generation")
        require(isPlanFingerprint(plan)) { "planFingerprint must be a valid SHA-256 value" }
        require(planFingerprint == null || planFingerprint == plan) {
            "Typed frontier plan scope mismatch."
        }
        return TypedSeedFrontierState(
            version = FORMAT_VERSION,
            requestId = requestId,
            ownerId = owner,
            generation = generationRef,
            planFingerprint = plan,
            config = config,
            updatedAtEpochMillis = nowMillis().coerceAtLeast(0L),
            entries = entries,
            emittedEvidence = emittedEvidence,
            rejectionDiagnostics = rejectionDiagnostics.toList()
        )
    }

    internal fun keyFor(seed: TypedSeed): String =
        "${seed.kind.name}:${seed.normalizedValue.trim().lowercase(Locale.ROOT)}"

    private fun transition(
        key: String,
        state: TypedSeedFrontierEntryState,
        reason: String?
    ): Boolean {
        val current = entriesByKey[key] ?: return false
        entriesByKey[key] = current.copy(
            state = state,
            unavailableReason = reason?.trim()?.take(MAX_REASON_CHARS)
        )
        return true
    }

    private fun merge(
        existing: TypedSeedFrontierEntry,
        incoming: TypedSeed
    ): TypedSeedFrontierEntry {
        val existingSeed = existing.seed
        val mergedState = strongerEvidenceState(existingSeed.evidenceState, incoming.evidenceState)
        val mergedSeed = existingSeed.copy(
            isVerified = mergedState == EvidenceState.Verified,
            evidenceState = mergedState,
            evidenceIds = (existingSeed.evidenceIds + incoming.evidenceIds)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(TypedSeed.MAX_EVIDENCE_IDS),
            sourceUrl = existingSeed.sourceUrl ?: incoming.sourceUrl,
            discoveryPath = (existingSeed.discoveryPath + incoming.discoveryPath)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(TypedSeed.MAX_DISCOVERY_PATH_STEPS)
        )
        val reason = unavailableReason(mergedSeed)
        val nextState = when {
            existing.state == TypedSeedFrontierEntryState.Pending && reason != null ->
                TypedSeedFrontierEntryState.Unavailable
            existing.state == TypedSeedFrontierEntryState.Unavailable &&
                reason == null &&
                mergedState == EvidenceState.Verified ->
                // A later independently verified observation can make a
                // previously failed executable seed eligible for retry. A
                // genuinely unsupported kind still has a non-null reason.
                TypedSeedFrontierEntryState.Pending
            else -> existing.state
        }
        return existing.copy(
            seed = mergedSeed,
            state = nextState,
            unavailableReason = reason
        )
    }

    private fun unavailableReason(seed: TypedSeed): String? = when {
        seed.kind !in EXECUTABLE_TYPED_SEED_KINDS ->
            "No reviewed executor is available for typed seed kind ${seed.kind.name}."
        !TypedSeedSafety.isSafePublicFetchSeed(seed) ->
            "Seed failed safe public-fetch admission and was retained without execution."
        else -> null
    }

    private fun isActionableExecutable(seed: TypedSeed): Boolean =
        seed.kind in EXECUTABLE_TYPED_SEED_KINDS && unavailableReason(seed) == null

    private fun recoverInFlight(entry: TypedSeedFrontierEntry): TypedSeedFrontierEntry =
        if (entry.state == TypedSeedFrontierEntryState.InFlight) {
            entry.copy(state = TypedSeedFrontierEntryState.Pending)
        } else {
            entry
        }

    private fun isSafeEntry(entry: TypedSeedFrontierEntry): Boolean =
        entry.key == keyFor(entry.seed) &&
            entry.seed.depth in 0..config.maxDepth &&
            entry.seed.exactValue.length <= TypedSeed.MAX_VALUE_CHARS &&
            entry.seed.normalizedValue.length <= TypedSeed.MAX_VALUE_CHARS &&
            entry.attempts in 0..MAX_ATTEMPTS

    private fun strongerEvidenceState(first: EvidenceState, second: EvidenceState): EvidenceState {
        if (first == second) return first
        if (first == EvidenceState.Conflicting || second == EvidenceState.Conflicting) {
            return EvidenceState.Conflicting
        }
        val rank = mapOf(
            EvidenceState.Unavailable to 0,
            EvidenceState.Rejected to 1,
            EvidenceState.Candidate to 2,
            EvidenceState.Observed to 3,
            EvidenceState.Probable to 4,
            EvidenceState.Verified to 5
        )
        return if ((rank[second] ?: 0) > (rank[first] ?: 0)) second else first
    }

    private fun pendingPriority(seed: TypedSeed): Int = when {
        // Only kinds with a reviewed public-page executor receive the top
        // slots. This keeps an unsupported Email/Phone record from blocking
        // an actionable URL, document, or archive discovered later.
        seed.kind in EXECUTABLE_NAVIGATION_KINDS && seed.evidenceState == EvidenceState.Verified -> 0
        seed.kind in EXECUTABLE_NAVIGATION_KINDS -> 1
        seed.kind == TypedSeedKind.Domain -> 2
        seed.kind in HIGH_ENTROPY_KINDS && seed.evidenceState == EvidenceState.Verified -> 3
        seed.kind in HIGH_ENTROPY_KINDS -> 4
        seed.kind in LOW_VALUE_KINDS -> 5
        else -> 6
    }

    companion object {
        internal const val FORMAT_VERSION = 1
        internal const val MAX_ATTEMPTS = 8
        internal const val MAX_REASON_CHARS = 256
        internal const val MAX_EVIDENCE_RECORDS = 512
        internal const val MAX_RELATIONSHIPS = 2_048
        internal const val MAX_REJECTION_DIAGNOSTICS = 64
        internal const val MAX_REJECTION_VALUE_CHARS = 256

        private val HIGH_ENTROPY_KINDS = setOf(
            TypedSeedKind.Email,
            TypedSeedKind.Phone
        )
        private val EXECUTABLE_NAVIGATION_KINDS = setOf(
            TypedSeedKind.Url,
            TypedSeedKind.Document,
            TypedSeedKind.Archive
        )
        private val LOW_VALUE_KINDS = setOf(
            TypedSeedKind.Name,
            TypedSeedKind.Username,
            TypedSeedKind.Photo,
            TypedSeedKind.Image,
            // Location observations are retained for diagnostics and later
            // reviewed location executors, but are not public-fetch pivots.
            TypedSeedKind.Location
        )

        private fun isPlanFingerprint(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/**
 * Request-scoped AES-GCM persistence for [TypedSeedFrontier].  Envelope
 * metadata contains only opaque UUIDs and bounded timestamps; exact typed
 * values and provenance stay inside authenticated ciphertext.
 */
internal class TypedSeedFrontierStore internal constructor(
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
        crypto = AndroidKeystoreCheckpointCrypto("dossier-typed-frontier-v1"),
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

    fun load(
        config: TypedSeedFrontierConfig,
        ownerId: String,
        generation: String,
        planFingerprint: String = TYPED_FRONTIER_DEFAULT_PLAN_FINGERPRINT
    ): TypedSeedFrontierLoadResult = loadDetailed(config, ownerId, generation, planFingerprint)

    fun loadDetailed(
        config: TypedSeedFrontierConfig,
        ownerId: String,
        generation: String,
        planFingerprint: String = TYPED_FRONTIER_DEFAULT_PLAN_FINGERPRINT
    ): TypedSeedFrontierLoadResult = synchronized(STORE_LOCK) {
        if (!isCanonicalUuid(ownerId) || !isCanonicalUuid(generation) || !isPlanFingerprint(planFingerprint)) {
            return@synchronized TypedSeedFrontierLoadResult.Unavailable
        }
        runCatching {
            val file = frontierFile()
            val parent = file.parentFile ?: return@runCatching TypedSeedFrontierLoadResult.Unavailable
            if (!isSafeDirectoryChain(rootDir, parent) || !isContained(file)) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching if (isTombstoned()) {
                    TypedSeedFrontierLoadResult.Unavailable
                } else {
                    TypedSeedFrontierLoadResult.Missing
                }
            }
            if (isTombstoned() || !isSafeRegularFile(file) || file.length() > MAX_ENVELOPE_BYTES) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            val envelope = json.decodeFromString<TypedSeedFrontierEnvelope>(file.readBounded())
            if (envelope.version != FORMAT_VERSION || envelope.requestId != requestId) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            if (envelope.ownerId != ownerId || envelope.generation != generation) {
                return@runCatching TypedSeedFrontierLoadResult.StaleOwner
            }
            if (envelope.planFingerprint != planFingerprint) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            val now = nowMillis()
            if (now < 0L || envelope.savedAtEpochMillis < 0L || envelope.savedAtEpochMillis > now) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            if (now - envelope.savedAtEpochMillis > MAX_AGE_MILLIS) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            val plaintext = crypto.decrypt(
                envelope.ivBase64,
                envelope.ciphertextBase64,
                aad(envelope.ownerId, envelope.generation, envelope.planFingerprint)
            ) ?: return@runCatching TypedSeedFrontierLoadResult.Unavailable
            if (plaintext.size > MAX_PAYLOAD_BYTES) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            val state = json.decodeFromString<TypedSeedFrontierState>(
                plaintext.toString(Charsets.UTF_8)
            )
            if (state.version != FORMAT_VERSION ||
                state.requestId != requestId ||
                state.ownerId != ownerId ||
                state.generation != generation ||
                state.planFingerprint != planFingerprint ||
                state.config != config
            ) {
                return@runCatching TypedSeedFrontierLoadResult.Unavailable
            }
            TypedSeedFrontierLoadResult.Available(
                TypedSeedFrontier(
                    requestId = requestId,
                    config = config,
                    ownerId = ownerId,
                    generation = generation,
                    planFingerprint = planFingerprint,
                    nowMillis = nowMillis,
                    state = state
                )
            )
        }.getOrElse { TypedSeedFrontierLoadResult.Unavailable }
    }

    /** Save only when the frontier and encrypted envelope have the same owner/generation. */
    fun save(
        frontier: TypedSeedFrontier,
        ownerId: String,
        generation: String,
        planFingerprint: String = frontier.planFingerprint ?: TYPED_FRONTIER_DEFAULT_PLAN_FINGERPRINT
    ): TypedSeedFrontierWriteResult = synchronized(STORE_LOCK) {
        if (frontier.requestId != requestId ||
            frontier.ownerId != ownerId ||
            frontier.generation != generation ||
            !isCanonicalUuid(ownerId) ||
            !isCanonicalUuid(generation) ||
            !isPlanFingerprint(planFingerprint)
        ) return@synchronized TypedSeedFrontierWriteResult.StaleOwner
        if (frontier.planFingerprint?.let { it != planFingerprint } == true) {
            return@synchronized TypedSeedFrontierWriteResult.Invalid
        }
        runCatching {
            val target = frontierFile()
            val parent = target.parentFile ?: return@runCatching TypedSeedFrontierWriteResult.StorageFailure
            if (isTombstoned()) return@runCatching TypedSeedFrontierWriteResult.Tombstoned
            if (!isSafeDirectoryChain(rootDir, parent)) {
                return@runCatching TypedSeedFrontierWriteResult.StorageFailure
            }
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val existing = readEnvelopeIfPresent(target)
                    ?: return@runCatching TypedSeedFrontierWriteResult.StorageFailure
                if (existing.ownerId != ownerId || existing.generation != generation) {
                    return@runCatching TypedSeedFrontierWriteResult.StaleOwner
                }
            }
            val plaintext = json.encodeToString(frontier.snapshot(planFingerprint)).toByteArray(Charsets.UTF_8)
            if (plaintext.size > MAX_PAYLOAD_BYTES) {
                return@runCatching TypedSeedFrontierWriteResult.Invalid
            }
            val now = nowMillis()
            if (now < 0L) return@runCatching TypedSeedFrontierWriteResult.Invalid
            val encrypted = crypto.encrypt(plaintext, aad(ownerId, generation, planFingerprint))
            val envelope = TypedSeedFrontierEnvelope(
                version = FORMAT_VERSION,
                requestId = requestId,
                ownerId = ownerId,
                generation = generation,
                planFingerprint = planFingerprint,
                savedAtEpochMillis = now,
                ivBase64 = encrypted.ivBase64,
                ciphertextBase64 = encrypted.ciphertextBase64
            )
            val encoded = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_ENVELOPE_BYTES) {
                return@runCatching TypedSeedFrontierWriteResult.Invalid
            }
            atomicWrite(target, encoded)
            TypedSeedFrontierWriteResult.Saved
        }.getOrElse { TypedSeedFrontierWriteResult.StorageFailure }
    }

    /**
     * Rebinds a paused generation to a new WorkManager owner.  The old owner
     * is authenticated from the envelope before re-encrypting under the new
     * AAD.  A generation mismatch is always stale, even when the caller has a
     * valid UUID.
     */
    fun rebindOwner(
        newOwnerId: String,
        generation: String,
        expectedOwnerId: String? = null,
        planFingerprint: String = TYPED_FRONTIER_DEFAULT_PLAN_FINGERPRINT
    ): TypedSeedFrontierWriteResult = synchronized(STORE_LOCK) {
        if (!isCanonicalUuid(newOwnerId) || !isCanonicalUuid(generation) ||
            !isPlanFingerprint(planFingerprint) ||
            expectedOwnerId?.let(::isCanonicalUuid) == false
        ) return@synchronized TypedSeedFrontierWriteResult.Invalid
        runCatching {
            val target = frontierFile()
            if (isTombstoned()) return@runCatching TypedSeedFrontierWriteResult.Tombstoned
            if (!isSafeRegularFile(target)) return@runCatching TypedSeedFrontierWriteResult.Missing
            val envelope = readEnvelopeIfPresent(target)
                ?: return@runCatching TypedSeedFrontierWriteResult.StorageFailure
            if (envelope.version != FORMAT_VERSION || envelope.requestId != requestId) {
                return@runCatching TypedSeedFrontierWriteResult.StorageFailure
            }
            if (envelope.generation != generation ||
                expectedOwnerId != null && envelope.ownerId != expectedOwnerId
            ) return@runCatching TypedSeedFrontierWriteResult.StaleOwner
            if (envelope.planFingerprint != planFingerprint) {
                return@runCatching TypedSeedFrontierWriteResult.StorageFailure
            }
            val now = nowMillis()
            if (now < 0L || envelope.savedAtEpochMillis < 0L ||
                envelope.savedAtEpochMillis > now ||
                now - envelope.savedAtEpochMillis > MAX_AGE_MILLIS
            ) {
                return@runCatching TypedSeedFrontierWriteResult.Invalid
            }
            val plaintext = crypto.decrypt(
                envelope.ivBase64,
                envelope.ciphertextBase64,
                aad(envelope.ownerId, envelope.generation, envelope.planFingerprint)
            ) ?: return@runCatching TypedSeedFrontierWriteResult.StorageFailure
            if (plaintext.size > MAX_PAYLOAD_BYTES) {
                return@runCatching TypedSeedFrontierWriteResult.Invalid
            }
            val state = json.decodeFromString<TypedSeedFrontierState>(
                plaintext.toString(Charsets.UTF_8)
            )
            if (state.version != FORMAT_VERSION ||
                state.requestId != requestId ||
                state.ownerId != envelope.ownerId ||
                state.generation != generation ||
                state.planFingerprint != planFingerprint
            ) return@runCatching TypedSeedFrontierWriteResult.StaleOwner
            val reboundState = state.copy(ownerId = newOwnerId, updatedAtEpochMillis = now)
            val reboundPlaintext = json.encodeToString(reboundState).toByteArray(Charsets.UTF_8)
            if (reboundPlaintext.size > MAX_PAYLOAD_BYTES) {
                return@runCatching TypedSeedFrontierWriteResult.Invalid
            }
            val encrypted = crypto.encrypt(reboundPlaintext, aad(newOwnerId, generation, planFingerprint))
            val reboundEnvelope = envelope.copy(
                ownerId = newOwnerId,
                ivBase64 = encrypted.ivBase64,
                ciphertextBase64 = encrypted.ciphertextBase64,
                savedAtEpochMillis = now
            )
            val encoded = json.encodeToString(reboundEnvelope).toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_ENVELOPE_BYTES) {
                return@runCatching TypedSeedFrontierWriteResult.Invalid
            }
            atomicWrite(target, encoded)
            TypedSeedFrontierWriteResult.Saved
        }.getOrElse { TypedSeedFrontierWriteResult.StorageFailure }
    }

    /** Clear is tombstone-first so a late worker cannot recreate the queue. */
    fun clear(): Boolean = synchronized(STORE_LOCK) {
        runCatching {
            val target = frontierFile()
            val tombstone = tombstoneFile()
            val parent = target.parentFile ?: return@runCatching false
            if (!isContained(target) || !isContained(tombstone) ||
                !isSafeDirectoryChain(rootDir, parent)
            ) return@runCatching false
            if (!ensureTombstone(rootDir, tombstone, dirSyncer)) return@runCatching false
            if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching true
            if (!isSafeRegularFile(target)) return@runCatching false
            target.delete().also { if (it) parent.let(dirSyncer::sync) }
        }.getOrDefault(false)
    }

    internal fun frontierFileForTesting(): File = frontierFile()
    internal fun tombstoneFileForTesting(): File = tombstoneFile()

    private fun aad(ownerId: String, generation: String, planFingerprint: String): ByteArray =
        "typed-seed-frontier-v$FORMAT_VERSION|$requestId|$ownerId|$generation|$planFingerprint"
            .toByteArray(Charsets.UTF_8)

    private fun frontierFile(): File =
        File(File(rootDir, ROOT_DIRECTORY), "${sha256(requestId)}$FILE_EXTENSION")

    private fun tombstoneFile(): File =
        File(File(rootDir, ROOT_DIRECTORY), "${sha256(requestId)}$CLEAR_TOMBSTONE_EXTENSION")

    private fun isTombstoned(): Boolean =
        Files.exists(tombstoneFile().toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun readEnvelopeIfPresent(file: File): TypedSeedFrontierEnvelope? {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (!isSafeRegularFile(file) || file.length() > MAX_ENVELOPE_BYTES) return null
        return json.decodeFromString(file.readBounded())
    }

    private fun atomicWrite(target: File, content: ByteArray) {
        val parent = target.parentFile ?: throw IOException("Typed frontier has no parent")
        if (!isContained(target) || !isSafeDirectoryChain(rootDir, parent)) {
            throw IOException("Typed frontier path is unsafe")
        }
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create typed frontier directory")
        }
        if (!isSafeDirectoryChain(rootDir, parent)) throw IOException("Typed frontier directory is unsafe")
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
            if (channel.size() > MAX_ENVELOPE_BYTES) throw IOException("Typed frontier envelope is too large")
            val buffer = ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.flip()
            return String(buffer.array(), 0, buffer.limit(), Charsets.UTF_8)
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_PAYLOAD_BYTES = 768 * 1024
        private const val MAX_ENVELOPE_BYTES = 1_024 * 1024
        private const val ROOT_DIRECTORY = "dossier_frontier/typed-v1"
        private const val FILE_EXTENSION = ".frontier"
        private const val CLEAR_TOMBSTONE_EXTENSION = ".cleared"
        private const val TEMP_EXTENSION = ".tmp"
        private val CLEAR_TOMBSTONE_BYTES = "typed-seed-frontier-cleared-v1\n".toByteArray(Charsets.UTF_8)
        private val STORE_LOCK = Any()

        private fun isPlanFingerprint(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        internal fun clearRequest(context: Context, requestId: String): Boolean = runCatching {
            TypedSeedFrontierStore(context.applicationContext, requestId).clear()
        }.getOrDefault(false)

        internal fun clearAll(context: Context): Boolean = clearAll(
            rootDir = context.applicationContext.filesDir,
            dirSyncer = AndroidDirectorySyncer()
        )

        internal fun clearAll(rootDir: File, dirSyncer: DirectorySyncer): Boolean = synchronized(STORE_LOCK) {
            runCatching {
                val root = File(rootDir, ROOT_DIRECTORY)
                if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching true
                if (!isSafeDirectoryChain(rootDir, root)) return@runCatching false
                var cleared = true
                root.listFiles().orEmpty().forEach { entry ->
                    val hash = entry.name.removeSuffix(FILE_EXTENSION)
                    if (entry.name.endsWith(FILE_EXTENSION) && isLowerHexSha256(hash)) {
                        if (!ensureTombstone(rootDir, File(root, "$hash$CLEAR_TOMBSTONE_EXTENSION"), dirSyncer)) {
                            cleared = false
                            return@forEach
                        }
                    }
                    if (!entry.name.endsWith(CLEAR_TOMBSTONE_EXTENSION)) {
                        if (!Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                            Files.isSymbolicLink(entry.toPath()) ||
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

        private fun ensureTombstone(rootDir: File, tombstone: File, dirSyncer: DirectorySyncer): Boolean = runCatching {
            if (Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching Files.isRegularFile(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(tombstone.toPath())
            }
            val parent = tombstone.parentFile ?: return@runCatching false
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return@runCatching false
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
                Files.move(temporary.toPath(), tombstone.toPath(), StandardCopyOption.ATOMIC_MOVE)
                dirSyncer.sync(parent)
                true
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }.getOrDefault(false)

        private fun isCanonicalUuid(value: String): Boolean =
            ScanLifecycleRecord.isCanonicalUuid(value)

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
private data class TypedSeedFrontierEnvelope(
    val version: Int,
    val requestId: String,
    val ownerId: String,
    val generation: String,
    val planFingerprint: String,
    val savedAtEpochMillis: Long,
    val ivBase64: String,
    val ciphertextBase64: String
)
