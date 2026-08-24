package io.dossier.app.domain.remediation

import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.ProviderDefinition
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import java.net.URI

enum class RemediationResourceState {
    /** A reviewed provider-owned settings/privacy resource is available. */
    ProviderSpecific,
    /** The provider is known, but no reviewed action endpoint is registered. */
    ManualActionRequired,
    /** The source cannot be attributed to a reviewed provider resource. */
    Unavailable
}

data class RemediationResource(
    val state: RemediationResourceState,
    val providerId: String? = null,
    val providerName: String? = null,
    val actionLabel: String? = null,
    val actionUrl: String? = null,
    val note: String
)

/**
 * Structured remediation (ROADMAP Milestone 11).
 *
 * Each finding becomes a [RemediationItem] with an explicit Problem, the
 * Evidence behind it, the Risk, the Suggested fix, and an estimated impact of
 * applying it. This satisfies the roadmap's "every finding gets Problem /
 * Evidence / Risk / Suggested fix / Estimated impact" without losing the
 * existing flat-tip list (used by the report today).
 */
data class RemediationItem(
    val problem: String,
    val evidence: String,
    val risk: RiskLevel,
    val suggestedFix: String,
    val estimatedImpact: String,
    val resource: RemediationResource = RemediationResource(
        state = RemediationResourceState.Unavailable,
        note = "No reviewed provider-specific remediation resource is available."
    )
)

class RemediationProvider {
    fun getGlobalTips(findings: List<Finding>): List<String> {
        val tips = mutableListOf<String>()
        val types = findings.map { it.type }.toSet()

        if (types.contains(FindingType.Email)) {
            tips.add("Use a privacy-shielded email forwarder (e.g., SimpleLogin, Firefox Relay) for public bios.")
        }
        if (types.contains(FindingType.Phone)) {
            tips.add("Remove phone numbers from public bios. Transition from SMS 2FA to app-based TOTP authenticators.")
        }
        if (types.contains(FindingType.Location)) {
            tips.add("Obfuscate locations. Avoid naming your exact city or neighborhood in bios.")
        }
        if (types.contains(FindingType.UsernameReuse)) {
            tips.add("Differentiate usernames across accounts to prevent automated cross-indexing of your digital footprint.")
        }
        if (types.contains(FindingType.PublicSearchEvidence)) {
            tips.add("Review indexed search results and request removal or de-indexing for pages exposing personal details.")
        }
        if (types.contains(FindingType.PublicImageEvidence)) {
            tips.add("Review public image results and remove or de-index avatars/photos that link back to your identity.")
        }
        if (types.contains(FindingType.ImageConsistency)) {
            tips.add("Avoid reusing identical avatar images across platforms; crop, tint, or use unique avatars.")
        }
        if (findings.any {
                it.evidenceSnippet?.contains("breach", ignoreCase = true) == true ||
                    it.remediation.contains("MFA", ignoreCase = true)
            }
        ) {
            tips.add("Rotate credentials for breached emails, enable MFA, and watch for account-recovery phishing.")
        }

        if (tips.isEmpty()) {
            tips.add("No critical exposure patterns found. Continue practicing good digital hygiene.")
        }

        return tips
    }

    /**
     * Builds one structured [RemediationItem] per finding. The problem and fix
     * are derived from the finding type; the impact is a coarse estimate based
     * on the finding's risk.
     */
    fun getStructuredTips(findings: List<Finding>): List<RemediationItem> {
        if (findings.isEmpty()) return emptyList()
        return findings.sortedByDescending { riskWeight(it.risk) }.map { f ->
            RemediationItem(
                problem = problemFor(f),
                evidence = f.evidenceSnippet ?: f.value,
                risk = f.risk,
                suggestedFix = f.remediation.ifBlank { defaultFixFor(f) },
                estimatedImpact = impactFor(f.risk),
                resource = resourceFor(f)
            )
        }
    }

    /**
     * Resolve only provider resources that are explicitly allowlisted below.
     * Unknown hosts and known providers without a reviewed endpoint remain
     * visible as manual/unavailable states; Dossier never invents an action URL.
     */
    internal fun resourceFor(finding: Finding): RemediationResource {
        val sourceUrl = finding.sourceUrl
            ?.trim()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
        val provider = sourceUrl?.let(::providerForSource)
        if (provider == null) {
            return RemediationResource(
                state = RemediationResourceState.Unavailable,
                note = "No reviewed provider-specific remediation resource is available; review the source manually."
            )
        }

        val reviewed = REVIEWED_PROVIDER_RESOURCES[provider.id]
        if (reviewed == null) {
            return RemediationResource(
                state = RemediationResourceState.ManualActionRequired,
                providerId = provider.id,
                providerName = provider.displayName,
                actionLabel = "Open source for manual review",
                actionUrl = sourceUrl,
                note = "${provider.displayName} is a known provider, but Dossier has no reviewed privacy/deletion endpoint for it. Use the provider's own controls; completion is not asserted."
            )
        }

        return RemediationResource(
            state = RemediationResourceState.ProviderSpecific,
            providerId = provider.id,
            providerName = provider.displayName,
            actionLabel = reviewed.label,
            actionUrl = reviewed.url,
            note = "Official ${provider.displayName} settings resource. Opening it does not prove removal; verify any provider response with a later scan."
        )
    }

    private fun providerForSource(sourceUrl: String): ProviderDefinition? = runCatching {
        val sourceHost = URI(sourceUrl).host
            ?.removePrefix("www.")
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: return@runCatching null
        ProviderCatalogV2.definitions.firstOrNull { definition ->
            val template = definition.profileUrlTemplate ?: return@firstOrNull false
            val templateHost = URI(template.replace("{username}", "dossier-user"))
                .host
                ?.removePrefix("www.")
                ?.lowercase()
                ?: return@firstOrNull false
            sourceHost == templateHost ||
                (templateHost.startsWith("dossier-user.") && sourceHost.endsWith(templateHost.removePrefix("dossier-user")))
        }
    }.getOrNull()

    private data class ReviewedResource(val label: String, val url: String)

    /**
     * Small reviewed allowlist of provider-owned settings resources. Providers
     * not listed here deliberately stay manual rather than receiving guessed
     * help/privacy URLs.
     */
    private val REVIEWED_PROVIDER_RESOURCES = mapOf(
        "github" to ReviewedResource("Open GitHub profile settings", "https://github.com/settings/profile"),
        "reddit" to ReviewedResource("Open Reddit profile settings", "https://www.reddit.com/settings/profile"),
        "gitlab" to ReviewedResource("Open GitLab profile settings", "https://gitlab.com/-/profile"),
        "devto" to ReviewedResource("Open DEV settings", "https://dev.to/settings"),
        "medium" to ReviewedResource("Open Medium settings", "https://medium.com/me/settings"),
        "twitch" to ReviewedResource("Open Twitch profile settings", "https://www.twitch.tv/settings/profile"),
        "instagram" to ReviewedResource("Open Instagram profile settings", "https://www.instagram.com/accounts/edit/"),
        "x" to ReviewedResource("Open X profile settings", "https://x.com/settings/profile")
    )

    private fun problemFor(f: Finding): String = when (f.type) {
        FindingType.Email -> "Email address exposed publicly"
        FindingType.Phone -> "Phone number exposed publicly"
        FindingType.Location, FindingType.Address -> "Physical location exposed"
        FindingType.Username, FindingType.UsernameReuse -> "Username reused across platforms"
        FindingType.Profile, FindingType.PlausibleProfileMatch -> "Public profile linked to identity"
        FindingType.Organization -> "Organization affiliation exposed"
        FindingType.PublicSearchEvidence -> "Identity appears in public search results"
        FindingType.PublicImageEvidence -> "Identity appears in public image indexes"
        FindingType.ImageConsistency -> "Avatar reused; visual cross-linking possible"
        FindingType.SensitiveSnippet -> "Sensitive personal detail exposed"
    }

    private fun defaultFixFor(f: Finding): String = when (f.type) {
        FindingType.Email -> "Use an email forwarder for public bios."
        FindingType.Phone -> "Remove the number and switch to TOTP 2FA."
        FindingType.Location, FindingType.Address -> "Generalize the location in public profiles."
        FindingType.Username, FindingType.UsernameReuse -> "Adopt distinct handles per platform."
        FindingType.Profile, FindingType.PlausibleProfileMatch -> "Review and tighten the profile's visibility."
        FindingType.Organization -> "Limit public mention of the affiliation."
        FindingType.PublicSearchEvidence -> "Request de-indexing of exposing pages."
        FindingType.PublicImageEvidence -> "Remove or de-index the image."
        FindingType.ImageConsistency -> "Use unique avatars per account."
        FindingType.SensitiveSnippet -> "Redact the detail from public sources."
    }

    private fun impactFor(risk: RiskLevel): String = when (risk) {
        RiskLevel.Critical -> "High — directly enables account takeover or physical targeting"
        RiskLevel.High -> "Significant — reduces re-identification and phishing surface"
        RiskLevel.Medium -> "Moderate — lowers correlation and scraping risk"
        RiskLevel.Low -> "Minor — improves overall hygiene"
    }

    private fun riskWeight(risk: RiskLevel): Int = when (risk) {
        RiskLevel.Low -> 25
        RiskLevel.Medium -> 50
        RiskLevel.High -> 80
        RiskLevel.Critical -> 100
    }
}

