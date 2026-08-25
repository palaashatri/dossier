package io.dossier.app.domain.identity

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

@Serializable
enum class CorrelationFeature {
    DirectVerification,
    UserSuppliedProfile,
    ExactSuppliedUsername,
    ExplicitCrossLink,
    ExactPublicEmail,
    DisplayNameAgreement,
    OrganizationAgreement,
    LocationAgreement,
    ConflictingDisplayName,
    ConflictingPersonalWebsite
}

@Serializable
data class CorrelationContribution(
    val feature: CorrelationFeature,
    /** Engineering contribution, not a scientific probability. */
    val weight: Double,
    val evidenceIds: List<String> = emptyList(),
    val explanation: String
)

@Serializable
enum class ResolutionBand {
    Confirmed,
    High,
    Medium,
    Low,
    Unresolved,
    Conflicting
}

@Serializable
data class EntityResolutionResult(
    val band: ResolutionBand,
    val score: Double,
    val supporting: List<CorrelationContribution>,
    val contradicting: List<CorrelationContribution>
) {
    val explanation: String
        get() = buildString {
            append(band.name)
            if (supporting.isNotEmpty()) {
                append(": ")
                append(supporting.joinToString("; ") { it.explanation })
            }
            if (contradicting.isNotEmpty()) {
                append(". Contradictions: ")
                append(contradicting.joinToString("; ") { it.explanation })
            }
        }
}

/**
 * Conservative account-to-subject resolver.
 *
 * This is intentionally not advertised as calibrated probability. The score is
 * a bounded engineering aggregation whose feature contributions and negative
 * evidence are always retained. Calibration against the acceptance benchmark is
 * a separate production gate.
 */
object EntityResolverV2 {
    const val RESOLVER_VERSION = "entity-resolver-v2"

    /**
     * An imported artifact only affects production when the strict calibration
     * loader has accepted it and it represents a sufficiently large consented
     * corpus. Synthetic/regression artifacts deliberately fall back to the
     * reviewed engineering defaults.
     */
    fun resolve(
        input: IdentityInput,
        result: ProfileScanResult,
        calibration: EntityResolutionCalibrationArtifact? = null,
        expectedCorpusDigest: String? = null,
        expectedTrainingCorpusDigest: String? = null,
        expectedAuthorizationRecordDigest: String? = null
    ): EntityResolutionResult {
        val policy = calibration?.productionPolicyOrNull(
            expectedCorpusDigest = expectedCorpusDigest,
            expectedTrainingCorpusDigest = expectedTrainingCorpusDigest,
            expectedAuthorizationRecordDigest = expectedAuthorizationRecordDigest
        )
            ?: EntityResolutionPolicy.DEFAULT
        if (!result.exists) {
            return EntityResolutionResult(
                band = ResolutionBand.Unresolved,
                score = 0.0,
                supporting = emptyList(),
                contradicting = emptyList()
            )
        }

        val supporting = mutableListOf<CorrelationContribution>()
        val contradicting = mutableListOf<CorrelationContribution>()
        val candidateUrl = normalizeUrl(result.candidate.url)
        val suppliedUrls = input.profileUrls.mapNotNull(::normalizeUrl).toSet()
        val suppliedUsernames = buildSet {
            input.primaryUsername?.takeIf(String::isNotBlank)?.let { add(normalizeHandle(it)) }
            input.usernames.filter(String::isNotBlank).forEach { add(normalizeHandle(it)) }
        }

        if (result.verified) {
            supporting += CorrelationContribution(
                feature = CorrelationFeature.DirectVerification,
                weight = policy.weight(CorrelationFeature.DirectVerification, 0.45),
                explanation = "The public profile passed Dossier's direct verification and attribution checks"
            )
        }
        if (candidateUrl != null && candidateUrl in suppliedUrls) {
            supporting += CorrelationContribution(
                feature = CorrelationFeature.UserSuppliedProfile,
                weight = 0.80,
                explanation = "The exact profile URL was supplied by the authorized user"
            )
        }

        val handle = normalizeHandle(result.candidate.username)
        if (handle.isNotBlank() && handle in suppliedUsernames) {
            // Exact username is useful but deliberately insufficient on its own.
            supporting += CorrelationContribution(
                feature = CorrelationFeature.ExactSuppliedUsername,
                weight = policy.weight(CorrelationFeature.ExactSuppliedUsername, 0.24),
                explanation = "The public account uses an exact supplied username"
            )
        }

        val suppliedHosts = input.profileUrls.mapNotNull(::hostOf).toSet()
        val linkedHosts = result.links.mapNotNull(::hostOf).toSet()
        val sharedHosts = suppliedHosts.intersect(linkedHosts)
        if (sharedHosts.isNotEmpty()) {
            supporting += CorrelationContribution(
                feature = CorrelationFeature.ExplicitCrossLink,
                weight = policy.weight(CorrelationFeature.ExplicitCrossLink, 0.42),
                explanation = "The public account cross-links a supplied public site (${sharedHosts.first()})"
            )
        }

        val findingEmails = result.findings
            .filter { it.type == io.dossier.app.domain.model.FindingType.Email }
            .map { it.value.trim().lowercase(Locale.ROOT) }
            .toSet()
        val suppliedEmails = input.emails.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (findingEmails.intersect(suppliedEmails).isNotEmpty()) {
            supporting += CorrelationContribution(
                feature = CorrelationFeature.ExactPublicEmail,
                weight = policy.weight(CorrelationFeature.ExactPublicEmail, 0.70),
                explanation = "The public profile exposes an exact supplied email address"
            )
        }

        val displayName = normalizeWords(result.displayName)
        val suppliedName = normalizeWords(input.fullName)
        if (displayName.isNotEmpty() && suppliedName.isNotEmpty()) {
            val overlap = tokenOverlap(displayName, suppliedName)
            when {
                overlap >= 0.8 -> supporting += CorrelationContribution(
                    CorrelationFeature.DisplayNameAgreement,
                    policy.weight(CorrelationFeature.DisplayNameAgreement, 0.18),
                    explanation = "Public display name strongly agrees with the supplied name"
                )
                overlap == 0.0 && displayName.size >= 2 && suppliedName.size >= 2 -> contradicting += CorrelationContribution(
                    CorrelationFeature.ConflictingDisplayName,
                    policy.weight(CorrelationFeature.ConflictingDisplayName, -0.45),
                    explanation = "Public display name has no token overlap with the supplied full name"
                )
            }
        }

        val text = listOfNotNull(result.displayName, result.bio, result.extractedText)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val matchingOrg = input.organizations
            .map(String::trim)
            .filter { it.length >= 3 }
            .firstOrNull { text.contains(it.lowercase(Locale.ROOT)) }
        if (matchingOrg != null) {
            supporting += CorrelationContribution(
                CorrelationFeature.OrganizationAgreement,
                policy.weight(CorrelationFeature.OrganizationAgreement, 0.12),
                explanation = "Public profile text mentions supplied organization '$matchingOrg'"
            )
        }
        val matchingLocation = input.locations
            .map(String::trim)
            .filter { it.length >= 3 }
            .firstOrNull { text.contains(it.lowercase(Locale.ROOT)) }
        if (matchingLocation != null) {
            supporting += CorrelationContribution(
                CorrelationFeature.LocationAgreement,
                policy.weight(CorrelationFeature.LocationAgreement, 0.08),
                explanation = "Public profile text mentions supplied location '$matchingLocation'"
            )
        }

        val positive = supporting.sumOf { it.weight }.coerceAtMost(1.0)
        val negative = contradicting.sumOf { -it.weight }.coerceAtMost(1.0)
        val score = (positive - negative).coerceIn(0.0, 1.0)

        val strongNonUsernameSignals = supporting.count {
            it.feature != CorrelationFeature.ExactSuppliedUsername && it.weight >= 0.18
        }
        val band = when {
            contradicting.any { -it.weight >= policy.contradictionWeight } && positive < 0.80 -> ResolutionBand.Conflicting
            supporting.any { it.feature == CorrelationFeature.UserSuppliedProfile } -> ResolutionBand.Confirmed
            result.verified && score >= policy.highScore &&
                strongNonUsernameSignals >= policy.highMinimumNonUsernameSignals -> ResolutionBand.High
            result.verified && score >= policy.mediumScore -> ResolutionBand.Medium
            score >= policy.corroboratedMediumScore && strongNonUsernameSignals >= 1 -> ResolutionBand.Medium
            score >= policy.lowScore -> ResolutionBand.Low
            else -> ResolutionBand.Unresolved
        }

        return EntityResolutionResult(
            band = band,
            score = score,
            supporting = supporting,
            contradicting = contradicting
        )
    }

    private fun normalizeHandle(value: String): String = value.trim().removePrefix("@").lowercase(Locale.ROOT)

    private fun normalizeUrl(value: String): String? = runCatching {
        val input = value.trim().trimEnd('/')
        val uri = URI(input)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val path = uri.path.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
        "$host$path"
    }.getOrNull()

    private fun hostOf(value: String): String? = runCatching {
        URI(value.trim()).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
    }.getOrNull()

    private fun normalizeWords(value: String?): Set<String> = value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun tokenOverlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / maxOf(a.size, b.size).toDouble()
    }
}
