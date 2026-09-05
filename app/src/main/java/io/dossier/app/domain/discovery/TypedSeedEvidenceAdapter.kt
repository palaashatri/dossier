package io.dossier.app.domain.discovery

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.IdentityInput

/**
 * Production bridge from canonical evidence to the typed admission queue.
 * Execution remains unavailable; this adapter only records safe, provenance-
 * carrying pivots for a future reviewed executor.
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
                    kind = TypedSeedKind.Url,
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
            val source = record.sourceClassification()
            model.offer(
                kind = kind,
                rawValue = record.value,
                depth = (record.discoveryPath.size + 1).coerceAtLeast(1),
                origin = origin,
                evidenceState = record.state,
                sourceClassification = source,
                evidenceIds = listOf(record.id),
                sourceUrl = record.sourceUrl,
                discoveryPath = record.discoveryPath
            )
        }
        return model
    }

    fun fromEvidence(
        evidence: List<Evidence>,
        input: IdentityInput? = null,
        config: TypedSeedAdmissionConfig = TypedSeedAdmissionConfig()
    ): TypedSeedAdmissionModel = admit(evidence, input, config)

    private fun Evidence.toTypedSeedKind(): TypedSeedKind? =
        if (kind == EvidenceKind.PublicSearchEvidence &&
            reliability == EvidenceReliability.ArchiveSnapshot
        ) {
            TypedSeedKind.Archive
        } else {
            kind.toTypedSeedKind()
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
        else -> null
    }

    private fun Evidence.origin(): TypedSeedOrigin = when {
        reliability == EvidenceReliability.UserSupplied -> TypedSeedOrigin.UserInput
        state == EvidenceState.Candidate -> TypedSeedOrigin.Candidate
        reliability == EvidenceReliability.LocalDerived -> TypedSeedOrigin.LocalAnalysis
        state == EvidenceState.Rejected || state == EvidenceState.Unavailable -> TypedSeedOrigin.Unknown
        else -> TypedSeedOrigin.Evidence
    }

    private fun Evidence.sourceClassification(): ExposureSourceClassification = when (reliability) {
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
    val publicSearchKinds: Set<TypedSeedKind> = setOf(
        TypedSeedKind.Url,
        TypedSeedKind.Domain,
        TypedSeedKind.Document,
        TypedSeedKind.Archive
    )

    private val publicEvidenceSources = setOf(
        ExposureSourceClassification.PUBLIC_WEB,
        ExposureSourceClassification.PUBLIC_PROFILE,
        ExposureSourceClassification.PUBLIC_DOCUMENT,
        ExposureSourceClassification.PUBLIC_RECORD,
        ExposureSourceClassification.ARCHIVE,
        ExposureSourceClassification.AUTHORIZED_API
    )

    fun isSafePublicSearchSeed(seed: TypedSeed): Boolean {
        if (seed.kind !in publicSearchKinds) return false
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

        // User-provided values are authorized even before a public fetch has
        // verified them. Evidence-derived pivots must be verified and public.
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

        // Reuse the canonical admission normalizers/structural validation;
        // this also catches malformed URL, domain, and archive values.
        val validator = TypedSeedAdmissionModel(
            TypedSeedAdmissionConfig(
                maxDepth = TypedSeedAdmissionConfig.MAX_ALLOWED_DEPTH,
                maxTotalSeeds = 1,
                perKindBudgets = mapOf(seed.kind to 1)
            )
        )
        return validator.offer(
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

    private fun isSafeEvidenceSourceUrl(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank() || value.any(Char::isISOControl)) return false
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.port in -1..65_535
    }
}
