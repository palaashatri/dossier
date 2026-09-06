package io.dossier.app.domain.discovery

import io.dossier.app.data.web.DiscoveryHttpPolicy
import io.dossier.app.data.web.TypedSeedPublicFetchExecutor
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.IdentityInput

/**
 * Production bridge from canonical evidence to the typed admission queue.
 * The reviewed URL/domain/document/archive and Email/Phone executors consume
 * admitted seeds;
 * this adapter only records safe, provenance-carrying pivots and does not
 * perform execution itself.
 */
object TypedSeedEvidenceAdapter {
    /**
     * Projects the canonical evidence collection into bounded typed pivots.
     * The collection remains the source of truth; callers retain this returned
     * model only for the lifetime of the operation that executes it.
     */
    fun fromCollection(
        collection: EvidenceCollection,
        input: IdentityInput? = null,
        config: TypedSeedAdmissionConfig = TypedSeedAdmissionConfig()
    ): TypedSeedAdmissionModel = admit(collection.evidence, input, config)

    fun admit(
        evidence: List<Evidence>,
        input: IdentityInput? = null,
        config: TypedSeedAdmissionConfig = TypedSeedAdmissionConfig()
    ): TypedSeedAdmissionModel {
        val model = TypedSeedAdmissionModel(config)

        // User input is retained even when it has not yet produced public evidence.
        input?.let { seedInput ->
            seedInput.fullName.takeIf(String::isNotBlank)?.let {
                model.offer(
                    kind = TypedSeedKind.Name,
                    rawValue = it,
                    depth = 0,
                    origin = TypedSeedOrigin.UserInput,
                    sourceClassification = ExposureSourceClassification.USER_IMPORTED
                )
            }
            (seedInput.usernames + listOfNotNull(seedInput.primaryUsername))
                .asSequence()
                .filter(String::isNotBlank)
                .forEach {
                    model.offer(
                        kind = TypedSeedKind.Username,
                        rawValue = it,
                        depth = 0,
                        origin = TypedSeedOrigin.UserInput,
                        sourceClassification = ExposureSourceClassification.USER_IMPORTED
                    )
                }
            seedInput.emails.forEach {
                model.offer(
                    kind = TypedSeedKind.Email,
                    rawValue = it,
                    depth = 0,
                    origin = TypedSeedOrigin.UserInput,
                    sourceClassification = ExposureSourceClassification.USER_IMPORTED
                )
            }
            seedInput.phones.forEach {
                model.offer(
                    kind = TypedSeedKind.Phone,
                    rawValue = it,
                    depth = 0,
                    origin = TypedSeedOrigin.UserInput,
                    sourceClassification = ExposureSourceClassification.USER_IMPORTED
                )
            }
            seedInput.profileUrls.forEach {
                model.offer(
                    // A user may start directly from a historical snapshot.
                    // Keep that seed historical so the executor does not
                    // route it through the original-URL availability lookup.
                    kind = if (TypedSeedPublicFetchExecutor.classifyArchiveSnapshot(it) != null) {
                        TypedSeedKind.Archive
                    } else {
                        TypedSeedKind.Url
                    },
                    rawValue = it,
                    depth = 0,
                    origin = TypedSeedOrigin.UserInput,
                    sourceClassification = ExposureSourceClassification.USER_IMPORTED
                )
            }
            seedInput.selfieUri?.let {
                model.offer(
                    kind = TypedSeedKind.Photo,
                    rawValue = it,
                    depth = 0,
                    origin = TypedSeedOrigin.UserInput,
                    sourceClassification = ExposureSourceClassification.USER_IMPORTED
                )
            }
        }

        evidence.forEach { record ->
            val kind = record.toTypedSeedKind() ?: return@forEach
            val origin = record.origin()
            val source = record.effectiveSourceClassification()
            model.offer(
                kind = kind,
                rawValue = record.value,
                // The path is the canonical serialized hop sequence. Keep
                // its depth exactly: an initial record with no path is depth
                // zero, while each appended hop advances one bounded level.
                depth = record.discoveryPath.size,
                origin = origin,
                evidenceState = record.state,
                sourceClassification = source,
                evidenceIds = listOf(record.id),
                sourceUrl = record.sourceUrl,
                discoveryPath = record.discoveryPath,
                locationEvidenceClass = record.locationEvidenceClass
            )
        }
        return model
    }

    fun fromEvidence(
        evidence: List<Evidence>,
        input: IdentityInput? = null,
        config: TypedSeedAdmissionConfig = TypedSeedAdmissionConfig()
    ): TypedSeedAdmissionModel = admit(evidence, input, config)

    private fun Evidence.toTypedSeedKind(): TypedSeedKind? = when {
        // Search results are intentionally not identity assertions.  The
        // typed frontier may nevertheless follow a result page when the
        // search adapter directly re-fetched and verified that URL.  Task 6
        // encodes that outcome as an Observed SearchEngineCandidate with
        // explicit unconfirmed attribution; candidates and unavailable
        // observations therefore remain evidence-only.
        kind == EvidenceKind.PublicSearchEvidence && isDirectlyVerifiedSearchUrl() ->
            TypedSeedKind.Url
        kind == EvidenceKind.PublicSearchEvidence &&
            reliability == EvidenceReliability.ArchiveSnapshot ->
            TypedSeedKind.Archive
        else -> kind.toTypedSeedKind()
    }

    private fun Evidence.isDirectlyVerifiedSearchUrl(): Boolean {
        if (state != EvidenceState.Observed ||
            reliability != EvidenceReliability.SearchEngineCandidate ||
            attribution != FindingAttribution.Unconfirmed ||
            id.isBlank() ||
            sourceUrl?.let(TypedSeedSafety::isSafeEvidenceSourceUrl) != true
        ) return false

        // A search result URL is a navigation pivot only when the target is a
        // safe public HTTP(S) URL.  The source URL is checked again by the
        // shared typed-seed safety predicate when the seed is admitted; this
        // check keeps malformed search observations out of the adapter path.
        return effectiveSourceClassification() in setOf(
            ExposureSourceClassification.PUBLIC_WEB,
            ExposureSourceClassification.PUBLIC_PROFILE,
            ExposureSourceClassification.PUBLIC_DOCUMENT,
            ExposureSourceClassification.PUBLIC_RECORD,
            ExposureSourceClassification.ARCHIVE,
            ExposureSourceClassification.AUTHORIZED_API
        ) && DiscoveryHttpPolicy.isSafePublicHttpUrl(value)
    }

    private fun EvidenceKind.toTypedSeedKind(): TypedSeedKind? = when (this) {
        EvidenceKind.Email -> TypedSeedKind.Email
        EvidenceKind.Phone -> TypedSeedKind.Phone
        EvidenceKind.Profile,
        EvidenceKind.Url -> TypedSeedKind.Url
        EvidenceKind.Domain -> TypedSeedKind.Domain
        EvidenceKind.Document -> TypedSeedKind.Document
        EvidenceKind.Archive -> TypedSeedKind.Archive
        EvidenceKind.Photo -> TypedSeedKind.Photo
        EvidenceKind.Image,
        EvidenceKind.PublicImageEvidence -> TypedSeedKind.Image
        EvidenceKind.Username -> TypedSeedKind.Username
        EvidenceKind.Location -> TypedSeedKind.Location
        else -> null
    }

    private fun Evidence.origin(): TypedSeedOrigin = when {
        // Launch values are added from IdentityInput above as UserInput. A
        // UserSupplied evidence record, however, may be a local/imported row;
        // never let that record inherit launch authorization for recursion.
        reliability == EvidenceReliability.UserSupplied -> TypedSeedOrigin.Import
        state == EvidenceState.Candidate -> TypedSeedOrigin.Candidate
        reliability == EvidenceReliability.LocalDerived -> TypedSeedOrigin.LocalAnalysis
        state == EvidenceState.Rejected || state == EvidenceState.Unavailable -> TypedSeedOrigin.Unknown
        else -> TypedSeedOrigin.Evidence
    }

    /**
     * Preserve an explicitly classified source while retaining compatibility
     * with older evidence records that only populated reliability.
     */
    private fun Evidence.effectiveSourceClassification(): ExposureSourceClassification =
        sourceClassification.takeUnless {
            it == ExposureSourceClassification.UNKNOWN_ORIGIN
        } ?: when (reliability) {
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
}

/**
 * Shared admission predicate for typed values that may become public-search
 * pivots.  Keep this separate from query generation so every caller applies
 * the same verification, origin, source, and bounded-value checks.
 */
object TypedSeedSafety {
    /** The reviewed public-page fetcher's URL-family seed kinds. */
    val publicFetchKinds: Set<TypedSeedKind> = PUBLIC_FETCH_TYPED_SEED_KINDS

    /** Seed kinds safe to include in a public-search query plan. */
    val publicSearchKinds: Set<TypedSeedKind> =
        publicFetchKinds + PUBLIC_SEARCH_TYPED_SEED_KINDS

    /** Every seed kind with a reviewed executor in the current scan tranche. */
    val executableKinds: Set<TypedSeedKind> = publicSearchKinds

    private val publicEvidenceSources = setOf(
        ExposureSourceClassification.PUBLIC_WEB,
        ExposureSourceClassification.PUBLIC_PROFILE,
        ExposureSourceClassification.PUBLIC_DOCUMENT,
        ExposureSourceClassification.PUBLIC_RECORD,
        ExposureSourceClassification.ARCHIVE,
        ExposureSourceClassification.AUTHORIZED_API
    )

    /**
     * Admission for a bounded public-page fetch. Observed URL-like evidence
     * is sufficient to follow a navigation link because fetching it does not
     * assert that the target belongs to the audited subject. PII and public
     * search pivots remain Verified-only in [isSafePublicSearchSeed].
     */
    fun isSafePublicFetchSeed(seed: TypedSeed): Boolean {
        if (seed.kind !in publicFetchKinds) return false
        if (!isStructurallySafe(seed)) return false
        if (!isSafePublicSearchValue(seed)) return false

        val userInput = seed.origin == TypedSeedOrigin.UserInput &&
            seed.evidenceState in setOf(EvidenceState.Observed, EvidenceState.Verified)
        val observedPublicEvidence = seed.origin == TypedSeedOrigin.Evidence &&
            seed.evidenceState in setOf(EvidenceState.Observed, EvidenceState.Verified) &&
            seed.evidenceIds.isNotEmpty() &&
            seed.sourceUrl?.let(::isSafeEvidenceSourceUrl) == true &&
            seed.sourceClassification in publicEvidenceSources
        if (!userInput && !observedPublicEvidence) return false

        return TypedSeedAdmissionModel(
            TypedSeedAdmissionConfig(
                maxDepth = TypedSeedAdmissionConfig.MAX_ALLOWED_DEPTH,
                maxTotalSeeds = 1,
                perKindBudgets = mapOf(seed.kind to 1)
            )
        ).offer(
            kind = seed.kind,
            rawValue = seed.exactValue,
            depth = seed.depth,
            origin = seed.origin,
            evidenceState = seed.evidenceState,
            sourceClassification = seed.sourceClassification,
            evidenceIds = seed.evidenceIds,
            sourceUrl = seed.sourceUrl,
            discoveryPath = seed.discoveryPath
        )
    }

    fun isSafePublicSearchSeed(seed: TypedSeed): Boolean {
        if (seed.kind !in publicSearchKinds) return false
        if (!isStructurallySafe(seed)) return false
        if (seed.kind in PUBLIC_SEARCH_TYPED_SEED_KINDS &&
            !hasCanonicalNormalizedValue(seed)
        ) return false
        if (!isSafePublicSearchValue(seed)) return false

        // User-provided values are authorized even before a public fetch has
        // verified them. Evidence-derived values used for search expansion
        // must be verified and public.
        if (seed.origin == TypedSeedOrigin.UserInput) {
            if (seed.evidenceState !in setOf(EvidenceState.Observed, EvidenceState.Verified)) return false
        } else if (seed.origin != TypedSeedOrigin.Evidence ||
            seed.evidenceState != EvidenceState.Verified ||
            seed.evidenceIds.isEmpty() ||
            seed.sourceUrl?.let(::isSafeEvidenceSourceUrl) != true ||
            seed.sourceClassification !in publicEvidenceSources
        ) {
            return false
        }

        return TypedSeedAdmissionModel(
            TypedSeedAdmissionConfig(
                maxDepth = TypedSeedAdmissionConfig.MAX_ALLOWED_DEPTH,
                maxTotalSeeds = 1,
                perKindBudgets = mapOf(seed.kind to 1)
            )
        ).offer(
            kind = seed.kind,
            rawValue = seed.exactValue,
            depth = seed.depth,
            origin = seed.origin,
            evidenceState = seed.evidenceState,
            sourceClassification = seed.sourceClassification,
            evidenceIds = seed.evidenceIds,
            sourceUrl = seed.sourceUrl,
            discoveryPath = seed.discoveryPath
        )
    }

    /** Applies the executor-specific safety policy for a typed seed. */
    fun isSafeExecutableSeed(seed: TypedSeed): Boolean = when {
        seed.kind in publicFetchKinds -> isSafePublicFetchSeed(seed)
        seed.kind in PUBLIC_SEARCH_TYPED_SEED_KINDS -> isSafePublicSearchSeed(seed)
        else -> false
    }

    private fun hasCanonicalNormalizedValue(seed: TypedSeed): Boolean {
        val normalizer = TypedSeedAdmissionModel()
        val exactNormalized = normalizer.normalizeForSafety(seed.kind, seed.exactValue)
        val storedNormalized = normalizer.normalizeForSafety(seed.kind, seed.normalizedValue)
        return exactNormalized == seed.normalizedValue &&
            storedNormalized == seed.normalizedValue
    }

    private fun isStructurallySafe(seed: TypedSeed): Boolean {
        if (seed.exactValue.isBlank() || seed.exactValue.length > TypedSeed.MAX_VALUE_CHARS) return false
        if (seed.value.isBlank() || seed.value.length > TypedSeed.MAX_VALUE_CHARS) return false
        if (seed.normalizedValue.isBlank() || seed.normalizedValue.length > TypedSeed.MAX_VALUE_CHARS) return false
        if (seed.exactValue.any(Char::isISOControl) || seed.value.any(Char::isISOControl)) return false
        if (seed.depth !in 0..TypedSeedAdmissionConfig.MAX_ALLOWED_DEPTH) return false
        if (seed.evidenceIds.size > TypedSeed.MAX_EVIDENCE_IDS ||
            seed.discoveryPath.size > TypedSeed.MAX_DISCOVERY_PATH_STEPS ||
            seed.evidenceIds.any { it.isBlank() || it.length > TypedSeed.MAX_VALUE_CHARS } ||
            seed.discoveryPath.any { it.isBlank() || it.length > TypedSeed.MAX_VALUE_CHARS }
        ) return false
        if (seed.sourceUrl?.length?.let { it > TypedSeed.MAX_VALUE_CHARS } == true) return false
        if (seed.sourceUrl != null && !isSafeEvidenceSourceUrl(seed.sourceUrl)) return false

        // URL-like pivots are user-controlled input at the launch screen as
        // well as evidence-derived values.  The admission model validates
        // syntax, but it deliberately does not perform the public-network
        // policy check; do that here before a value can be quoted into a
        // third-party search query.  Domains are checked through the same
        // URL policy so localhost/private/reserved literals cannot bypass the
        // URL-kind branch.
        if (!isSafePublicSearchValue(seed)) return false

        return true
    }

    internal fun isSafeEvidenceSourceUrl(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank() || value.any(Char::isISOControl)) return false
        if (value.startsWith("javascript:", ignoreCase = true)) return false
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.port in -1..65_535 &&
            DiscoveryHttpPolicy.isSafePublicHttpUrl(value)
    }

    private fun isSafePublicSearchValue(seed: TypedSeed): Boolean = when (seed.kind) {
        TypedSeedKind.Url,
        TypedSeedKind.Document,
        TypedSeedKind.Archive ->
            DiscoveryHttpPolicy.isSafePublicHttpUrl(seed.exactValue) &&
                DiscoveryHttpPolicy.isSafePublicHttpUrl(seed.normalizedValue)

        TypedSeedKind.Domain ->
            DiscoveryHttpPolicy.isSafePublicHttpUrl("https://${seed.exactValue}") &&
                DiscoveryHttpPolicy.isSafePublicHttpUrl("https://${seed.normalizedValue}")

        else -> true
    }
}
