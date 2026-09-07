package io.dossier.app.domain.remediation

import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.discovery.ProviderDefinition
import java.net.URI

/**
 * The only reviewed remediation links Dossier may present as provider-owned
 * controls. This is deliberately a small, declarative catalog: membership is
 * not a live-health claim and opening a link never asserts that data was
 * removed.
 */
enum class RemediationResourceKind {
    ProviderSettings
}

data class ReviewedRemediationResource(
    val providerId: String,
    val actionLabel: String,
    val actionUrl: String,
    val kind: RemediationResourceKind = RemediationResourceKind.ProviderSettings,
    val reviewNote: String = "Provider-owned settings resource. Opening it does not prove removal; availability and removal outcome require manual verification."
)

data class RemediationCatalogIssue(
    val providerId: String?,
    val code: String,
    val detail: String
)

object RemediationResourceCatalog {
    /**
     * Reviewed links are intentionally settings/profile pages rather than
     * guessed deletion endpoints. Keep provider identity in ProviderCatalogV2
     * and only store the action-specific URL here.
     */
    val entries: List<ReviewedRemediationResource> = listOf(
        ReviewedRemediationResource("github", "Open GitHub profile settings", "https://github.com/settings/profile"),
        ReviewedRemediationResource("reddit", "Open Reddit profile settings", "https://www.reddit.com/settings/profile"),
        ReviewedRemediationResource("gitlab", "Open GitLab profile settings", "https://gitlab.com/-/profile"),
        ReviewedRemediationResource("devto", "Open DEV settings", "https://dev.to/settings"),
        ReviewedRemediationResource("medium", "Open Medium settings", "https://medium.com/me/settings"),
        ReviewedRemediationResource("twitch", "Open Twitch profile settings", "https://www.twitch.tv/settings/profile"),
        ReviewedRemediationResource("instagram", "Open Instagram profile settings", "https://www.instagram.com/accounts/edit/"),
        ReviewedRemediationResource("x", "Open X profile settings", "https://x.com/settings/profile")
    )

    /** Schema/ownership checks are deterministic and do not perform requests. */
    val schemaIssues: List<RemediationCatalogIssue> by lazy { validate(entries, ProviderCatalogV2.definitions) }

    fun find(providerId: String): ReviewedRemediationResource? =
        entries.firstOrNull { it.providerId == providerId && schemaIssues.none { issue -> issue.providerId == providerId } }

    /**
     * Validate a catalog snapshot for maintenance tooling and unit tests.
     * Provider-specific resources must remain HTTPS, host-owned, and free of
     * redirect/query material or credentials. Unknown providers fail closed.
     */
    fun validate(
        resources: List<ReviewedRemediationResource>,
        providers: List<ProviderDefinition>
    ): List<RemediationCatalogIssue> {
        val issues = mutableListOf<RemediationCatalogIssue>()
        val providerById = providers.associateBy(ProviderDefinition::id)
        resources.groupBy(ReviewedRemediationResource::providerId)
            .filterValues { it.size > 1 }
            .forEach { (providerId, duplicates) ->
                issues += RemediationCatalogIssue(
                    providerId = providerId,
                    code = "duplicate-provider",
                    detail = "${duplicates.size} remediation entries share one provider ID"
                )
            }

        resources.forEach { resource ->
            val providerId = resource.providerId.trim()
            val provider = providerById[providerId]
            if (providerId.isEmpty()) {
                issues += RemediationCatalogIssue(null, "blank-provider", "provider ID is blank")
            } else if (provider == null) {
                issues += RemediationCatalogIssue(providerId, "unknown-provider", "provider is absent from ProviderCatalogV2")
            } else if (provider.profileUrlTemplate == null) {
                issues += RemediationCatalogIssue(providerId, "non-profile-provider", "provider has no profile URL template")
            }

            if (resource.actionLabel.isBlank()) {
                issues += RemediationCatalogIssue(providerId.ifEmpty { null }, "blank-label", "action label is blank")
            }
            if (resource.reviewNote.isBlank()) {
                issues += RemediationCatalogIssue(providerId.ifEmpty { null }, "blank-review-note", "review note is blank")
            }
            if (resource.kind != RemediationResourceKind.ProviderSettings) {
                issues += RemediationCatalogIssue(providerId.ifEmpty { null }, "unsupported-kind", "resource kind is not provider settings")
            }

            val uri = runCatching { URI(resource.actionUrl.trim()) }.getOrNull()
            if (uri == null || uri.scheme?.equals("https", ignoreCase = true) != true) {
                issues += RemediationCatalogIssue(providerId.ifEmpty { null }, "https-required", "action URL must use HTTPS")
            } else {
                if (uri.host.isNullOrBlank()) {
                    issues += RemediationCatalogIssue(providerId.ifEmpty { null }, "host-required", "action URL host is missing")
                }
                if (uri.userInfo != null) {
                    issues += RemediationCatalogIssue(providerId.ifEmpty { null }, "userinfo-forbidden", "action URL must not include credentials")
                }
                if (uri.query != null || uri.fragment != null) {
                    issues += RemediationCatalogIssue(providerId.ifEmpty { null }, "redirect-material-forbidden", "action URL must not include query or fragment material")
                }

                val profileHost = provider?.profileUrlTemplate
                    ?.let { template -> runCatching { URI(template.replace("{username}", "dossier-user")).host }.getOrNull() }
                    ?.normalizeHost()
                val actionHost = uri.host?.normalizeHost()
                if (provider != null && profileHost != null && actionHost != profileHost &&
                    !(profileHost.startsWith("dossier-user.") && actionHost?.endsWith(profileHost.removePrefix("dossier-user")) == true)
                ) {
                    issues += RemediationCatalogIssue(
                        providerId,
                        "host-mismatch",
                        "action URL host does not match the provider profile host"
                    )
                }
            }
        }

        return issues.distinct()
    }

    private fun String.normalizeHost(): String = removePrefix("www.").lowercase()
}
