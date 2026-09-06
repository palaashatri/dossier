package io.dossier.app.domain.evidence

import io.dossier.app.domain.case.RemediationStatus
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.util.UrlNormalizer
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

/** Typed fact kinds retained by the canonical exposure ledger. */
@Serializable
enum class ExposureFactKind {
    Name,
    Alias,
    Username,
    Email,
    Phone,
    Address,
    PostalCode,
    Profile,
    ProfileUrl,
    Website,
    Domain,
    Organization,
    Location,
    Image,
    Photo,
    Document,
    Archive,
    MessagingIdentifier,
    PaymentIdentifier,
    BreachMembership,
    PublicSearchEvidence,
    PublicImageEvidence,
    ImageConsistency,
    SensitiveSnippet,
    Other
}

/** Source classes from the product contract; unknown remains explicit. */
@Serializable
enum class ExposureSourceClassification {
    PUBLIC_WEB,
    PUBLIC_PROFILE,
    PUBLIC_DOCUMENT,
    PUBLIC_RECORD,
    DATA_BROKER,
    ARCHIVE,
    BREACH_INDEX,
    /** A provider or imported record asserted breach membership without a public source. */
    BREACH_DERIVED,
    AUTHORIZED_API,
    LOCAL_IMPORT,
    USER_IMPORTED,
    UNKNOWN_ORIGIN
}

/**
 * One exact exposed fact. A null [exactValue] is intentional: the source may
 * establish that a fact exists without returning its value.
 */
@Serializable
data class ExposureFact(
    val exactValue: String? = null,
    val normalizedValue: String = "",
    val kind: ExposureFactKind = ExposureFactKind.Other,
    val subjectId: String? = null,
    val evidenceIds: List<String> = emptyList(),
    val sourceClassification: ExposureSourceClassification = ExposureSourceClassification.UNKNOWN_ORIGIN,
    val sourceUrl: String? = null,
    val providerId: String? = null,
    val firstObservedAtEpochMillis: Long? = null,
    val lastObservedAtEpochMillis: Long? = null,
    val verificationState: EvidenceState = EvidenceState.Observed,
    val confidence: Float = 0.5f,
    val historical: Boolean = false,
    val discoveryPath: List<String> = emptyList(),
    val remediationStatus: RemediationStatus = RemediationStatus.NotStarted,
    /** Photo-location evidence class, when this fact came from media analysis. */
    val locationEvidenceClass: ReverseImageLookupResult.LocationEvidenceClass? = null,
    /** Why the location candidate was emitted, retained for local inspection. */
    val locationEvidenceReason: String? = null,
    /** Supporting location evidence IDs retained without inventing proof. */
    val supportingEvidenceIds: List<String> = emptyList(),
    /**
     * Explicit identity attribution carried from the canonical evidence
     * record. Null means the source predates attribution metadata.
     */
    val attribution: FindingAttribution? = null
) {
    init {
        require(evidenceIds.size <= MAX_EVIDENCE_IDS_PER_FACT) {
            "An exposure fact may retain at most $MAX_EVIDENCE_IDS_PER_FACT evidence IDs."
        }
        require(discoveryPath.size <= MAX_DISCOVERY_PATH_STEPS) {
            "An exposure fact may retain at most $MAX_DISCOVERY_PATH_STEPS discovery steps."
        }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "Exposure fact confidence must be finite and between 0 and 1."
        }
        require(supportingEvidenceIds.size <= MAX_SUPPORTING_EVIDENCE_IDS) {
            "An exposure fact may retain at most $MAX_SUPPORTING_EVIDENCE_IDS supporting evidence IDs."
        }
        require(locationEvidenceReason == null || locationEvidenceReason.length <= MAX_LOCATION_REASON_CHARS) {
            "Location evidence reason exceeds the bounded limit."
        }
    }

    /** Alias useful to callers that use entity terminology. */
    val entityId: String?
        get() = subjectId

    /** Naming aliases keep the canonical wire fields concise for callers. */
    val factType: ExposureFactKind
        get() = kind
    val sourceClass: ExposureSourceClassification
        get() = sourceClassification
    val source: ExposureSourceClassification
        get() = sourceClassification
    val verification: EvidenceState
        get() = verificationState

    /** True only for the explicit current-state representation. */
    val current: Boolean
        get() = !historical

    companion object {
        const val MAX_EVIDENCE_IDS_PER_FACT = 256
        const val MAX_DISCOVERY_PATH_STEPS = 64
        const val MAX_SUPPORTING_EVIDENCE_IDS = 256
        const val MAX_LOCATION_REASON_CHARS = 512
    }
}

/** Bounded canonical ledger of exact exposure facts. */
@Serializable
data class ExposureLedger(
    val facts: List<ExposureFact> = emptyList()
) {
    init {
        require(facts.size <= MAX_FACTS) {
            "Exposure ledger may retain at most $MAX_FACTS facts."
        }
    }

    /** Returns a deterministic, bounded, de-duplicated representation. */
    fun normalized(): ExposureLedger = ExposureLedger(ExposureLedgerPolicy.normalize(facts))

    companion object {
        const val MAX_FACTS = 10_000

        fun fromEvidence(evidence: List<Evidence>): ExposureLedger =
            evidence.toExposureLedger()

        fun fromEvidence(collection: EvidenceCollection): ExposureLedger =
            collection.toExposureLedger()
    }
}

/** Canonical bounds, normalization and evidence adapters for [ExposureLedger]. */
object ExposureLedgerPolicy {
    const val MAX_FACTS = ExposureLedger.MAX_FACTS
    const val MAX_EVIDENCE_IDS_PER_FACT = ExposureFact.MAX_EVIDENCE_IDS_PER_FACT
    const val MAX_DISCOVERY_PATH_STEPS = ExposureFact.MAX_DISCOVERY_PATH_STEPS
    const val MAX_SUPPORTING_EVIDENCE_IDS = ExposureFact.MAX_SUPPORTING_EVIDENCE_IDS
    const val MAX_LOCATION_REASON_CHARS = ExposureFact.MAX_LOCATION_REASON_CHARS

    fun normalize(facts: List<ExposureFact>): List<ExposureFact> {
        if (facts.isEmpty()) return emptyList()

        val merged = LinkedHashMap<FactKey, ExposureFact>()
        facts.take(MAX_FACTS).forEach { raw ->
            val fact = normalizeFact(raw)
            val key = FactKey(
                kind = fact.kind,
                normalizedValue = fact.normalizedValue,
                exactValue = fact.exactValue.takeIf { fact.normalizedValue.isBlank() },
                sourceClassification = fact.sourceClassification,
                sourceUrl = fact.sourceUrl,
                providerId = fact.providerId,
                historical = fact.historical
            )
            merged[key] = merged[key]?.let { merge(it, fact) } ?: fact
        }
        return merged.values.take(MAX_FACTS)
    }

    /**
     * Hydrates a persisted ledger with metadata from the canonical evidence
     * records that support it.
     *
     * The ledger was introduced before attribution and some of its facts may
     * therefore contain only legacy fields.  Matching by an already persisted
     * evidence ID is the only safe bridge: values and source URLs can be shared
     * by unrelated observations and must not be used as a fuzzy join.  Evidence
     * facts that are not referenced by the old ledger are appended as well so a
     * stale partial ledger cannot hide exact exposure records on migration.
     */
    fun hydrateFromEvidence(
        facts: List<ExposureFact>,
        evidence: List<Evidence>
    ): List<ExposureFact> {
        val normalizedFacts = normalize(facts)
        if (evidence.isEmpty()) return normalizedFacts

        val evidenceFacts = evidence
            .map(Evidence::toExposureFact)
            .let(::normalize)
        if (normalizedFacts.isEmpty()) return evidenceFacts

        val evidenceById = evidenceFacts
            .flatMap { fact -> fact.evidenceIds.map { id -> id to fact } }
            .groupBy({ it.first }, { it.second })
        val hydrated = normalizedFacts.map { fact ->
            val linkedFacts = fact.evidenceIds
                .flatMap { evidenceById[it].orEmpty() }
                .distinct()
            linkedFacts.fold(fact) { accumulator, linked ->
                merge(accumulator, linked)
            }
        }
        val linkedEvidenceIds = hydrated.flatMap { it.evidenceIds }.toSet()
        val unlinked = evidenceFacts.filter { fact ->
            fact.evidenceIds.none(linkedEvidenceIds::contains)
        }
        return normalize(hydrated + unlinked)
    }

    /** Common deterministic normalization while retaining the source string separately. */
    fun normalizeValue(kind: ExposureFactKind, value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        return when (kind) {
            ExposureFactKind.Email -> trimmed.lowercase(Locale.ROOT)
            ExposureFactKind.Phone -> trimmed.filter(Char::isDigit)
            ExposureFactKind.Username -> trimmed.removePrefix("@").lowercase(Locale.ROOT)
            ExposureFactKind.ProfileUrl,
            ExposureFactKind.Profile,
            ExposureFactKind.Website,
            ExposureFactKind.Domain,
            ExposureFactKind.Image,
            ExposureFactKind.Photo,
            ExposureFactKind.Archive -> normalizeUrl(trimmed)
            else -> collapseWhitespace(trimmed).lowercase(Locale.ROOT)
        }
    }

    internal fun normalizeFact(fact: ExposureFact): ExposureFact {
        val exact = fact.exactValue?.takeIf(String::isNotBlank)
        val normalized = fact.normalizedValue
            .takeIf(String::isNotBlank)
            ?.let(::collapseWhitespace)
            ?: exact?.let { normalizeValue(fact.kind, it) }
            .orEmpty()
        return fact.copy(
            exactValue = exact,
            normalizedValue = normalized,
            evidenceIds = fact.evidenceIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(EvidenceIdPolicy::migrate)
                .distinct()
                .take(MAX_EVIDENCE_IDS_PER_FACT),
            locationEvidenceReason = fact.locationEvidenceReason
                ?.trim()
                ?.take(MAX_LOCATION_REASON_CHARS)
                ?.takeIf(String::isNotBlank),
            supportingEvidenceIds = fact.supportingEvidenceIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(EvidenceIdPolicy::migrate)
                .distinct()
                .take(MAX_SUPPORTING_EVIDENCE_IDS),
            discoveryPath = fact.discoveryPath
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_DISCOVERY_PATH_STEPS)
        )
    }

    private fun merge(first: ExposureFact, second: ExposureFact): ExposureFact {
        val firstObserved = minTimestamp(first.firstObservedAtEpochMillis, second.firstObservedAtEpochMillis)
        val lastObserved = maxTimestamp(first.lastObservedAtEpochMillis, second.lastObservedAtEpochMillis)
        return first.copy(
            exactValue = first.exactValue ?: second.exactValue,
            evidenceIds = (first.evidenceIds + second.evidenceIds)
                .distinct()
                .take(MAX_EVIDENCE_IDS_PER_FACT),
            firstObservedAtEpochMillis = firstObserved,
            lastObservedAtEpochMillis = lastObserved,
            verificationState = strongerState(first.verificationState, second.verificationState),
            confidence = maxOf(first.confidence, second.confidence),
            attribution = strongerAttribution(first.attribution, second.attribution),
            // Keep every independent derivation in stable order: preserve the
            // existing fact's path entries first, then append entries from the
            // merged observation until the persisted bound is reached.
            discoveryPath = (first.discoveryPath + second.discoveryPath)
                .distinct()
                .take(MAX_DISCOVERY_PATH_STEPS),
            locationEvidenceClass = strongerLocationClass(
                first.locationEvidenceClass,
                second.locationEvidenceClass
            ),
            locationEvidenceReason = first.locationEvidenceReason
                ?: second.locationEvidenceReason,
            supportingEvidenceIds = (first.supportingEvidenceIds + second.supportingEvidenceIds)
                .distinct()
                .take(MAX_SUPPORTING_EVIDENCE_IDS),
            remediationStatus = if (first.remediationStatus == RemediationStatus.NotStarted) {
                second.remediationStatus
            } else {
                first.remediationStatus
            }
        )
    }

    private fun strongerState(first: EvidenceState, second: EvidenceState): EvidenceState {
        if (first == second) return first
        if (first == EvidenceState.Conflicting || second == EvidenceState.Conflicting) {
            return EvidenceState.Conflicting
        }
        val positive = setOf(
            EvidenceState.Observed,
            EvidenceState.Probable,
            EvidenceState.Verified
        )
        if ((first == EvidenceState.Rejected && second in positive) ||
            (second == EvidenceState.Rejected && first in positive)
        ) {
            return EvidenceState.Conflicting
        }
        val firstRank = STATE_RANK[first] ?: 0
        val secondRank = STATE_RANK[second] ?: 0
        return if (secondRank > firstRank) second else first
    }

    private fun strongerLocationClass(
        first: ReverseImageLookupResult.LocationEvidenceClass?,
        second: ReverseImageLookupResult.LocationEvidenceClass?
    ): ReverseImageLookupResult.LocationEvidenceClass? {
        if (first == null) return second
        if (second == null) return first
        val firstRank = LOCATION_CLASS_RANK[first] ?: 0
        val secondRank = LOCATION_CLASS_RANK[second] ?: 0
        return if (secondRank > firstRank) second else first
    }

    private fun strongerAttribution(
        first: FindingAttribution?,
        second: FindingAttribution?
    ): FindingAttribution? {
        if (first == null) return second
        if (second == null || first == second) return first
        // Contradictions remain visible even when another observation has a
        // stronger positive attribution; the ledger must not hide conflict.
        if (first == FindingAttribution.Conflicting || second == FindingAttribution.Conflicting) {
            return FindingAttribution.Conflicting
        }
        val firstRank = ATTRIBUTION_RANK[first] ?: 0
        val secondRank = ATTRIBUTION_RANK[second] ?: 0
        return if (secondRank > firstRank) second else first
    }

    private fun minTimestamp(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

    private fun maxTimestamp(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private fun normalizeUrl(value: String): String {
        val withoutFragment = UrlNormalizer.stripFragment(value)
        return runCatching {
            val uri = URI(withoutFragment)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching withoutFragment.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
                ?: return@runCatching withoutFragment.lowercase(Locale.ROOT)
            URI(scheme, uri.userInfo, host, uri.port, uri.path, uri.query, null).toString()
        }.getOrElse { withoutFragment.lowercase(Locale.ROOT) }
    }

    private fun collapseWhitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private data class FactKey(
        val kind: ExposureFactKind,
        val normalizedValue: String,
        val exactValue: String?,
        val sourceClassification: ExposureSourceClassification,
        val sourceUrl: String?,
        val providerId: String?,
        val historical: Boolean
    )

    private val STATE_RANK = mapOf(
        EvidenceState.Unavailable to 0,
        EvidenceState.Rejected to 1,
        EvidenceState.Candidate to 2,
        EvidenceState.Observed to 3,
        EvidenceState.Probable to 4,
        EvidenceState.Conflicting to 4,
        EvidenceState.Verified to 5
    )

    private val LOCATION_CLASS_RANK = mapOf(
        ReverseImageLookupResult.LocationEvidenceClass.VISUAL_GUESS to 1,
        ReverseImageLookupResult.LocationEvidenceClass.LIKELY_LOCATION to 2,
        ReverseImageLookupResult.LocationEvidenceClass.CORROBORATED_LOCATION to 3,
        ReverseImageLookupResult.LocationEvidenceClass.EXACT_METADATA to 4,
        // Preserve disagreement even though it is not a stronger positive
        // claim; callers need to see that observations conflict.
        ReverseImageLookupResult.LocationEvidenceClass.CONFLICTING to 5
    )

    private val ATTRIBUTION_RANK = mapOf(
        FindingAttribution.Unconfirmed to 0,
        FindingAttribution.Candidate to 1,
        FindingAttribution.IndependentPageSignals to 2,
        FindingAttribution.Probable to 3,
        FindingAttribution.Verified to 4,
        FindingAttribution.ExactSelfSupplied to 5,
        FindingAttribution.Conflicting to 6
    )
}

/** Converts one existing evidence record without inventing missing metadata. */
fun Evidence.toExposureFact(
    subjectId: String? = null,
    discoveryPath: List<String> = this.discoveryPath
): ExposureFact {
    val factKind = kind.toExposureFactKind()
    val exact = value.takeIf(String::isNotBlank)
    return ExposureFact(
        exactValue = exact,
        normalizedValue = ExposureLedgerPolicy.normalizeValue(factKind, value),
        kind = factKind,
        subjectId = subjectId,
        evidenceIds = listOfNotNull(
            EvidenceIdPolicy.migrate(id).takeIf(String::isNotBlank)
        ),
        // New producers retain the product source taxonomy directly on the
        // record. Legacy records leave it at UNKNOWN_ORIGIN and continue to
        // derive the equivalent class from reliability for compatibility.
        sourceClassification = sourceClassification.takeUnless {
            it == ExposureSourceClassification.UNKNOWN_ORIGIN
        } ?: reliability.toExposureSourceClassification(),
        sourceUrl = sourceUrl,
        providerId = providerId,
        firstObservedAtEpochMillis = firstObservedAtEpochMillis ?: observedAtEpochMillis,
        lastObservedAtEpochMillis = lastObservedAtEpochMillis ?: observedAtEpochMillis,
        verificationState = state,
        confidence = confidence,
        historical = historical,
        discoveryPath = discoveryPath,
        remediationStatus = RemediationStatus.NotStarted,
        attribution = attribution,
        locationEvidenceClass = locationEvidenceClass,
        locationEvidenceReason = locationEvidenceReason,
        supportingEvidenceIds = supportingEvidenceIds
    )
}

/** Converts a collection into a bounded, deterministic ledger. */
fun EvidenceCollection.toExposureLedger(): ExposureLedger = ExposureLedger(
    ExposureLedgerPolicy.normalize(
        evidence
            .distinctBy { it.id }
            .map(Evidence::toExposureFact)
    )
)

/** Convenience adapter for callers that already hold a list. */
fun List<Evidence>.toExposureLedger(): ExposureLedger =
    EvidenceCollection(evidence = this).toExposureLedger()

/** Convenience adapter for a single record. */
fun Evidence.toExposureLedger(): ExposureLedger =
    ExposureLedger.fromEvidence(listOf(this))

private fun EvidenceKind.toExposureFactKind(): ExposureFactKind = when (this) {
    EvidenceKind.Email -> ExposureFactKind.Email
    EvidenceKind.Phone -> ExposureFactKind.Phone
    EvidenceKind.Address -> ExposureFactKind.Address
    EvidenceKind.Location -> ExposureFactKind.Location
    EvidenceKind.Username,
    EvidenceKind.UsernameReuse -> ExposureFactKind.Username
    EvidenceKind.Profile,
    EvidenceKind.PlausibleProfileMatch -> ExposureFactKind.Profile
    EvidenceKind.Organization -> ExposureFactKind.Organization
    EvidenceKind.PublicSearchEvidence -> ExposureFactKind.PublicSearchEvidence
    EvidenceKind.PublicImageEvidence -> ExposureFactKind.PublicImageEvidence
    EvidenceKind.ImageConsistency -> ExposureFactKind.ImageConsistency
    EvidenceKind.SensitiveSnippet -> ExposureFactKind.SensitiveSnippet
    EvidenceKind.BreachMembership -> ExposureFactKind.BreachMembership
    EvidenceKind.Url -> ExposureFactKind.Website
    EvidenceKind.Document -> ExposureFactKind.Document
    EvidenceKind.Archive -> ExposureFactKind.Archive
    EvidenceKind.Domain -> ExposureFactKind.Domain
    EvidenceKind.Photo -> ExposureFactKind.Photo
    EvidenceKind.Image -> ExposureFactKind.Image
}

private fun EvidenceReliability.toExposureSourceClassification(): ExposureSourceClassification = when (this) {
    EvidenceReliability.AuthoritativeApi -> ExposureSourceClassification.AUTHORIZED_API
    EvidenceReliability.DirectPublicProfile -> ExposureSourceClassification.PUBLIC_PROFILE
    EvidenceReliability.DirectPersonalWebsite -> ExposureSourceClassification.PUBLIC_WEB
    EvidenceReliability.ArchiveSnapshot -> ExposureSourceClassification.ARCHIVE
    EvidenceReliability.SearchEngineCandidate -> ExposureSourceClassification.PUBLIC_WEB
    EvidenceReliability.ThirdPartyAggregation -> ExposureSourceClassification.DATA_BROKER
    EvidenceReliability.LocalDerived -> ExposureSourceClassification.LOCAL_IMPORT
    EvidenceReliability.UserSupplied -> ExposureSourceClassification.USER_IMPORTED
    EvidenceReliability.Unknown -> ExposureSourceClassification.UNKNOWN_ORIGIN
}
