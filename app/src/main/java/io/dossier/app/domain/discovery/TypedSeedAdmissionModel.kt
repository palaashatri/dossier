package io.dossier.app.domain.discovery

import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureFactKind
import io.dossier.app.domain.evidence.ExposureLedgerPolicy
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.util.UrlNormalizer
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

/** Typed values that may be admitted to the recursive discovery frontier. */
@Serializable
enum class TypedSeedKind {
    Email,
    Phone,
    Url,
    Domain,
    Document,
    Archive,
    Photo,
    Image,
    Username,
    Name
}

/** Where an admitted value came from; this is separate from verification state. */
@Serializable
enum class TypedSeedOrigin {
    UserInput,
    Evidence,
    Candidate,
    Import,
    LocalAnalysis,
    Unknown
}

/** Execution is intentionally unavailable until a reviewed per-kind executor exists. */
@Serializable
enum class TypedSeedExecutionAvailability {
    Unavailable
}

/**
 * Immutable derived view for UI/checkpoint diagnostics. The evidence
 * collection remains canonical; this snapshot is bounded and disposable.
 */
@Serializable
data class TypedSeedAdmissionSnapshot(
    val seeds: List<TypedSeed> = emptyList(),
    val pendingCount: Int = 0,
    val admittedCount: Int = 0,
    val executionAvailability: Map<TypedSeedKind, TypedSeedExecutionAvailability> =
        TypedSeedKind.entries.associateWith { TypedSeedExecutionAvailability.Unavailable }
) {
    init {
        require(seeds.size <= MAX_SEEDS) { "Too many typed seed snapshot records." }
        require(pendingCount >= 0) { "Typed seed pending count must not be negative." }
        require(admittedCount >= seeds.size) { "Typed seed admitted count is inconsistent." }
    }

    val admittedSeeds: List<TypedSeed> get() = seeds
    val isExecutionAvailable: Boolean
        get() = executionAvailability.values.any { it != TypedSeedExecutionAvailability.Unavailable }

    companion object {
        const val MAX_SEEDS = TypedSeedAdmissionConfig.MAX_ALLOWED_TOTAL_SEEDS
    }
}

@Serializable
data class TypedSeed(
    val kind: TypedSeedKind,
    /** Normalized value retained for compatibility with the original model. */
    val value: String,
    val isVerified: Boolean = false,
    val depth: Int = 0,
    val exactValue: String = value,
    val normalizedValue: String = value,
    val evidenceState: EvidenceState = if (isVerified) EvidenceState.Verified else EvidenceState.Observed,
    val sourceClassification: ExposureSourceClassification = ExposureSourceClassification.UNKNOWN_ORIGIN,
    val evidenceIds: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val discoveryPath: List<String> = emptyList(),
    val origin: TypedSeedOrigin = if (isVerified) TypedSeedOrigin.Evidence else TypedSeedOrigin.UserInput
) {
    /** Naming aliases keep source/verification terminology explicit to callers. */
    val source: TypedSeedOrigin get() = origin
    val verificationState: EvidenceState get() = evidenceState
    val sourceClass: ExposureSourceClassification get() = sourceClassification

    init {
        require(value == normalizedValue) { "Typed seed value must equal normalizedValue." }
        require(isVerified == (evidenceState == EvidenceState.Verified)) {
            "Typed seed verification state and isVerified must agree."
        }
        require(origin != TypedSeedOrigin.Candidate || evidenceState == EvidenceState.Candidate) {
            "Candidate seeds must retain Candidate evidence state."
        }
        require(
            origin !in setOf(TypedSeedOrigin.Import, TypedSeedOrigin.LocalAnalysis) ||
                (evidenceState == EvidenceState.Verified &&
                    sourceClassification == ExposureSourceClassification.LOCAL_IMPORT)
        ) {
            "Import and local-analysis seeds must be verified LOCAL_IMPORT evidence."
        }
        require(
            origin != TypedSeedOrigin.Evidence ||
                evidenceState != EvidenceState.Verified ||
                sourceClassification !in setOf(
                    ExposureSourceClassification.UNKNOWN_ORIGIN,
                    ExposureSourceClassification.BREACH_INDEX,
                    ExposureSourceClassification.BREACH_DERIVED,
                    ExposureSourceClassification.LOCAL_IMPORT,
                    ExposureSourceClassification.USER_IMPORTED
                )
        ) {
            "Verified evidence seeds require a public or authorized source classification."
        }
        require(
            evidenceState != EvidenceState.Verified ||
                sourceClassification !in setOf(
                    ExposureSourceClassification.BREACH_INDEX,
                    ExposureSourceClassification.BREACH_DERIVED
                )
        ) {
            "Breach evidence cannot become a verified typed seed."
        }
        require(origin != TypedSeedOrigin.Unknown || evidenceState != EvidenceState.Verified) {
            "Unknown-origin seeds cannot be marked Verified."
        }
        require(exactValue.length <= MAX_VALUE_CHARS) { "Typed seed exact value is too long." }
        require(depth >= 0) { "Typed seed depth must not be negative." }
        require(evidenceIds.size <= MAX_EVIDENCE_IDS) { "Too many typed seed evidence IDs." }
        require(discoveryPath.size <= MAX_DISCOVERY_PATH_STEPS) { "Typed seed discovery path is too long." }
    }

    companion object {
        const val MAX_VALUE_CHARS = 4_096
        const val MAX_EVIDENCE_IDS = 256
        const val MAX_DISCOVERY_PATH_STEPS = 64
    }
}

@Serializable
data class TypedSeedAdmissionConfig(
    val maxDepth: Int = 2,
    val maxTotalSeeds: Int = 30,
    val perKindBudgets: Map<TypedSeedKind, Int> = defaultBudgets(),
    /**
     * Local imports are evidence by default, not recursive pivots. A caller
     * may opt in only when the imported record is explicitly authorized for
     * discovery by the user and the source contract permits that use.
     */
    val allowAuthorizedImports: Boolean = false
) {
    init {
        require(maxDepth in 0..MAX_ALLOWED_DEPTH) {
            "maxDepth must be between 0 and $MAX_ALLOWED_DEPTH"
        }
        require(maxTotalSeeds in 1..MAX_ALLOWED_TOTAL_SEEDS) {
            "maxTotalSeeds must be between 1 and $MAX_ALLOWED_TOTAL_SEEDS"
        }
        require(perKindBudgets.keys.all { it in TypedSeedKind.entries }) {
            "Unknown typed seed kind"
        }
        require(perKindBudgets.values.all { it in 0..MAX_ALLOWED_KIND_BUDGET }) {
            "Per-kind seed budgets must be between 0 and $MAX_ALLOWED_KIND_BUDGET"
        }
    }

    fun budgetFor(kind: TypedSeedKind): Int = perKindBudgets[kind] ?: 0

    companion object {
        const val MAX_ALLOWED_DEPTH = 16
        const val MAX_ALLOWED_TOTAL_SEEDS = 256
        const val MAX_ALLOWED_KIND_BUDGET = 128

        fun defaultBudgets(): Map<TypedSeedKind, Int> = mapOf(
            TypedSeedKind.Email to 5,
            TypedSeedKind.Phone to 5,
            TypedSeedKind.Url to 10,
            TypedSeedKind.Domain to 10,
            TypedSeedKind.Document to 5,
            TypedSeedKind.Archive to 5,
            TypedSeedKind.Photo to 2,
            TypedSeedKind.Image to 2,
            TypedSeedKind.Username to 5,
            TypedSeedKind.Name to 5
        )
    }
}

/**
 * Small bounded admission queue. It admits initial user input and explicitly
 * verified evidence, while refusing unverified candidates/imports as pivots.
 */
class TypedSeedAdmissionModel(
    val config: TypedSeedAdmissionConfig = TypedSeedAdmissionConfig()
) {
    private val queue = ArrayDeque<TypedSeed>()
    private val admitted = mutableListOf<TypedSeed>()
    private val visited = mutableSetOf<String>()
    private val admittedByKind = mutableMapOf<TypedSeedKind, Int>()

    val pendingCount: Int get() = queue.size
    val admittedCount: Int get() = admitted.size
    val admittedSeeds: List<TypedSeed> get() = admitted.toList()
    val isExecutionAvailable: Boolean get() = false
    val executionAvailability: Map<TypedSeedKind, TypedSeedExecutionAvailability> =
        TypedSeedKind.entries.associateWith { TypedSeedExecutionAvailability.Unavailable }
    val availability: Map<TypedSeedKind, TypedSeedExecutionAvailability>
        get() = executionAvailability

    fun snapshot(): TypedSeedAdmissionSnapshot = TypedSeedAdmissionSnapshot(
        seeds = admittedSeeds,
        pendingCount = pendingCount,
        admittedCount = admittedCount,
        executionAvailability = executionAvailability
    )

    fun availabilityFor(kind: TypedSeedKind): TypedSeedExecutionAvailability =
        executionAvailability.getValue(kind)

    fun isExecutionAvailable(kind: TypedSeedKind): Boolean =
        availabilityFor(kind) != TypedSeedExecutionAvailability.Unavailable

    /** Backward-compatible user-input overload. */
    fun offer(
        kind: TypedSeedKind,
        rawValue: String,
        depth: Int,
        isVerified: Boolean = false
    ): Boolean = offer(
        kind = kind,
        rawValue = rawValue,
        depth = depth,
        origin = if (isVerified) TypedSeedOrigin.Evidence else TypedSeedOrigin.UserInput,
        evidenceState = if (isVerified) EvidenceState.Verified else EvidenceState.Observed,
        sourceClassification = if (isVerified) {
            ExposureSourceClassification.PUBLIC_WEB
        } else {
            ExposureSourceClassification.USER_IMPORTED
        }
    )

    /** Evidence/import-aware overload used by production adapters. */
    fun offer(
        kind: TypedSeedKind,
        rawValue: String,
        depth: Int,
        origin: TypedSeedOrigin,
        evidenceState: EvidenceState = origin.defaultEvidenceState(),
        sourceClassification: ExposureSourceClassification = origin.defaultSourceClassification(),
        evidenceIds: List<String> = emptyList(),
        sourceUrl: String? = null,
        discoveryPath: List<String> = emptyList()
    ): Boolean {
        if (depth !in 0..config.maxDepth) return false
        val normalized = normalize(kind, rawValue) ?: return false
        if (!isSafe(origin, evidenceState, sourceClassification)) return false

        val key = "${kind.name}:$normalized"
        if (!visited.add(key)) {
            mergeDuplicate(
                key = key,
                evidenceState = evidenceState,
                sourceClassification = sourceClassification,
                evidenceIds = evidenceIds,
                sourceUrl = sourceUrl,
                discoveryPath = discoveryPath,
                origin = origin
            )
            return false
        }
        val kindBudget = config.budgetFor(kind)
        if ((admittedByKind[kind] ?: 0) >= kindBudget || admitted.size >= config.maxTotalSeeds) {
            visited.remove(key)
            return false
        }

        val record = TypedSeed(
            kind = kind,
            value = normalized,
            isVerified = evidenceState == EvidenceState.Verified,
            depth = depth,
            exactValue = rawValue.take(TypedSeed.MAX_VALUE_CHARS),
            normalizedValue = normalized,
            evidenceState = evidenceState,
            sourceClassification = sourceClassification,
            evidenceIds = evidenceIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(TypedSeed.MAX_EVIDENCE_IDS),
            sourceUrl = sourceUrl?.take(TypedSeed.MAX_VALUE_CHARS),
            discoveryPath = discoveryPath
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(TypedSeed.MAX_DISCOVERY_PATH_STEPS),
            origin = origin
        )
        queue.addLast(record)
        admitted += record
        admittedByKind[kind] = (admittedByKind[kind] ?: 0) + 1
        return true
    }

    fun pop(): TypedSeed? = queue.removeFirstOrNull()

    private fun mergeDuplicate(
        key: String,
        evidenceState: EvidenceState,
        sourceClassification: ExposureSourceClassification,
        evidenceIds: List<String>,
        sourceUrl: String?,
        discoveryPath: List<String>,
        origin: TypedSeedOrigin
    ) {
        val index = admitted.indexOfFirst { "${it.kind.name}:${it.normalizedValue}" == key }
        if (index < 0) return
        val existing = admitted[index]
        val mergedState = strongerState(existing.evidenceState, evidenceState)
        val merged = existing.copy(
            isVerified = mergedState == EvidenceState.Verified,
            evidenceState = mergedState,
            sourceClassification = if (existing.origin == TypedSeedOrigin.UserInput) {
                existing.sourceClassification
            } else {
                sourceClassification
            },
            evidenceIds = (existing.evidenceIds + evidenceIds)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(TypedSeed.MAX_EVIDENCE_IDS),
            sourceUrl = existing.sourceUrl ?: sourceUrl,
            discoveryPath = (existing.discoveryPath + discoveryPath)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(TypedSeed.MAX_DISCOVERY_PATH_STEPS),
            origin = if (existing.origin == TypedSeedOrigin.UserInput) existing.origin else origin
        )
        admitted[index] = merged
        val pendingIndex = queue.indexOfFirst { "${it.kind.name}:${it.normalizedValue}" == key }
        if (pendingIndex >= 0) queue[pendingIndex] = merged
    }

    private fun strongerState(first: EvidenceState, second: EvidenceState): EvidenceState {
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

    private fun isSafe(
        origin: TypedSeedOrigin,
        evidenceState: EvidenceState,
        sourceClassification: ExposureSourceClassification
    ): Boolean {
        // Breach membership/derived rows are retained as evidence, but never
        // promoted into recursive pivots. They do not establish public
        // identity and may contain sensitive or stolen data.
        if (sourceClassification == ExposureSourceClassification.BREACH_INDEX ||
            sourceClassification == ExposureSourceClassification.BREACH_DERIVED
        ) {
            return false
        }
        if (origin == TypedSeedOrigin.Import || origin == TypedSeedOrigin.LocalAnalysis) {
            if (evidenceState != EvidenceState.Verified ||
                sourceClassification != ExposureSourceClassification.LOCAL_IMPORT
            ) {
                return false
            }
        }
        // Initial user values are authorized pivots even before a fetch verifies them.
        if (origin == TypedSeedOrigin.UserInput) return true
        if (evidenceState != EvidenceState.Verified) return false
        return when (origin) {
            TypedSeedOrigin.Evidence -> sourceClassification !in setOf(
                ExposureSourceClassification.UNKNOWN_ORIGIN,
                ExposureSourceClassification.LOCAL_IMPORT,
                ExposureSourceClassification.USER_IMPORTED
            )
            TypedSeedOrigin.LocalAnalysis -> true
            TypedSeedOrigin.Import ->
                config.allowAuthorizedImports &&
                    sourceClassification == ExposureSourceClassification.LOCAL_IMPORT
            TypedSeedOrigin.Candidate,
            TypedSeedOrigin.Unknown,
            TypedSeedOrigin.UserInput -> false
        }
    }

    private fun normalize(kind: TypedSeedKind, rawValue: String): String? {
        if (rawValue.length > TypedSeed.MAX_VALUE_CHARS || containsUnsafeCharacters(rawValue)) return null
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) return null
        return when (kind) {
            TypedSeedKind.Email -> normalizeEmail(trimmed)
            TypedSeedKind.Phone -> normalizePhone(trimmed)
            TypedSeedKind.Url,
            TypedSeedKind.Document,
            TypedSeedKind.Archive -> normalizeHttpUrl(trimmed)
            TypedSeedKind.Domain -> normalizeDomain(trimmed)
            TypedSeedKind.Photo,
            TypedSeedKind.Image -> normalizeMedia(trimmed)
            TypedSeedKind.Username -> trimmed.removePrefix("@").lowercase(Locale.ROOT)
                .takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isWhitespace) }
            TypedSeedKind.Name -> trimmed.replace(Regex("\\s+"), " ").takeIf { it.length <= 240 }
        }
    }

    private fun normalizeEmail(value: String): String? {
        if (value.length > 254 || value.count { it == '@' } != 1) return null
        val normalized = value.lowercase(Locale.ROOT)
        val at = normalized.indexOf('@')
        val local = normalized.substring(0, at)
        val domain = normalized.substring(at + 1)
        if (local.isBlank() || domain.isBlank() || domain.startsWith('.') || domain.endsWith('.') ||
            domain.contains("..") || local.contains("..") || local.any(Char::isWhitespace) ||
            !domain.contains('.') || domain.any { it.isWhitespace() || it == '/' }
        ) return null
        return normalized
    }

    private fun normalizePhone(value: String): String? {
        if (!PHONE_ALLOWED.matches(value) || value.count { it == '+' } > 1 ||
            (value.contains('+') && !value.startsWith('+'))
        ) return null
        val digits = value.filter(Char::isDigit)
        return digits.takeIf { digits.length in 7..15 }
    }

    private fun normalizeHttpUrl(value: String): String? {
        if (value.any(Char::isWhitespace)) return null
        val candidate = UrlNormalizer.ensureHttps(value)
        if (!UrlNormalizer.isHttpUrl(candidate)) return null
        val stripped = UrlNormalizer.stripFragment(candidate)
        return runCatching {
            val uri = URI(stripped)
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
                uri.host.isNullOrBlank() || uri.rawUserInfo != null
            ) {
                null
            } else {
                ExposureLedgerPolicy.normalizeValue(ExposureFactKind.Website, stripped)
                    .takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    private fun normalizeDomain(value: String): String? {
        val candidate = value.trim().removeSuffix(".")
        if (candidate.isBlank() || candidate.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        return runCatching {
            val uri = URI("https://$candidate")
            uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".")
                ?.takeIf { it.isNotBlank() && it == candidate.lowercase(Locale.ROOT) }
        }.getOrNull()
    }

    private fun normalizeMedia(value: String): String? {
        if (value.length > TypedSeed.MAX_VALUE_CHARS || value.any(Char::isWhitespace)) return null
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null || uri.scheme == null) return value
        return when (uri.scheme.lowercase(Locale.ROOT)) {
            "http", "https" -> normalizeHttpUrl(value)
            "content", "file", "android.resource" ->
                value.substringBefore('#').takeIf { uri.path.orEmpty().isNotBlank() }
            else -> null
        }
    }

    private fun containsUnsafeCharacters(value: String): Boolean = value.any { it.isISOControl() }

    private val PHONE_ALLOWED = Regex("\\+?[0-9\\s().-]+")

    private fun TypedSeedOrigin.defaultEvidenceState(): EvidenceState = when (this) {
        TypedSeedOrigin.UserInput -> EvidenceState.Observed
        TypedSeedOrigin.Evidence,
        TypedSeedOrigin.Import,
        TypedSeedOrigin.LocalAnalysis -> EvidenceState.Verified
        TypedSeedOrigin.Candidate,
        TypedSeedOrigin.Unknown -> EvidenceState.Candidate
    }

    private fun TypedSeedOrigin.defaultSourceClassification(): ExposureSourceClassification = when (this) {
        TypedSeedOrigin.UserInput -> ExposureSourceClassification.USER_IMPORTED
        TypedSeedOrigin.Evidence -> ExposureSourceClassification.PUBLIC_WEB
        TypedSeedOrigin.Import -> ExposureSourceClassification.LOCAL_IMPORT
        TypedSeedOrigin.LocalAnalysis -> ExposureSourceClassification.LOCAL_IMPORT
        TypedSeedOrigin.Candidate,
        TypedSeedOrigin.Unknown -> ExposureSourceClassification.UNKNOWN_ORIGIN
    }
}
