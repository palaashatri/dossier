package io.dossier.app.domain.remediation

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel

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
    val estimatedImpact: String
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
                estimatedImpact = impactFor(f.risk)
            )
        }
    }

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

