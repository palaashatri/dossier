package io.dossier.app.domain.remediation

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType

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
}
