package io.dossier.app.domain.discovery

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

/** One non-sensitive, deterministic stage budget in a persisted scan plan. */
@Serializable
data class ScanPlanStageSummary(
    val name: String,
    val budget: Int
)

/**
 * Sanitized coordinator-owned scan plan committed with a durable request.
 *
 * It contains mode, a commitment to the declarative provider plan, bounded
 * provider counts, and an allow-listed stage order. It intentionally carries
 * no identity seeds, URLs, provider responses, or stage output payloads.
 */
@Serializable
data class ScanPlanSummary(
    val version: Int,
    val mode: ScanMode,
    val providerPlanFingerprint: String,
    val providerCount: Int,
    val scheduledProviderCount: Int,
    val stages: List<ScanPlanStageSummary>
) {
    /** Shape validation used at coordinator/event boundaries. */
    fun isWellFormed(): Boolean {
        if (version != CURRENT_VERSION || !ProviderPlanFingerprint.isValid(providerPlanFingerprint)) return false
        if (providerCount !in 0..MAX_PROVIDER_COUNT ||
            scheduledProviderCount !in 0..MAX_PROVIDER_COUNT
        ) return false
        if (stages.size != STAGE_ORDER.size || stages.map { it.name } != STAGE_ORDER) return false
        return stages.all { stage ->
            stage.budget in 0..MAX_STAGE_BUDGET &&
                (stage.name == "DISCOVERING_USERNAMES" || stage.budget == 1)
        }
    }

    /** Recomputes the summary commitment; a catalog change invalidates resume. */
    fun matches(plan: ProviderScanPlan): Boolean =
        isWellFormed() &&
            mode == plan.mode &&
            providerPlanFingerprint == ProviderPlanFingerprint.forPlan(plan) &&
            providerCount == plan.providers.size &&
            scheduledProviderCount == plan.scheduledProviderCount &&
            stages == expectedStages(plan)

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_PROVIDER_COUNT = 100_000
        const val MAX_STAGE_BUDGET = 100_000
        private val STAGE_ORDER = listOf(
            "QUEUED_BACKGROUND_SCAN",
            "DISCOVERING_USERNAMES",
            "COMPARING_FACE_CONSISTENCY",
            "CHECKING_BREACH_EXPOSURE",
            "BUILDING_ENTITY_GRAPH",
            "SCORING_RELATIONSHIP_CONFIDENCE",
            "TRACING_ATTACK_PATHS",
            "COMPILING_EXPOSURE_LEVELS",
            "COMPILING_EXPOSURE_SCORES",
            "GENERATING_AI_SUMMARY",
            "POST_PROCESSING",
            "BACKGROUND_SCAN_COMPLETE"
        )

        fun from(plan: ProviderScanPlan): ScanPlanSummary = ScanPlanSummary(
            version = CURRENT_VERSION,
            mode = plan.mode,
            providerPlanFingerprint = ProviderPlanFingerprint.forPlan(plan),
            providerCount = plan.providers.size,
            scheduledProviderCount = plan.scheduledProviderCount,
            stages = expectedStages(plan)
        )

        private fun expectedStages(plan: ProviderScanPlan): List<ScanPlanStageSummary> =
            STAGE_ORDER.map { stage ->
                ScanPlanStageSummary(
                    name = stage,
                    budget = if (stage == "DISCOVERING_USERNAMES") {
                        plan.scheduledProviderCount
                    } else {
                        1
                    }
                )
            }
    }
}

/**
 * Stable identity for one declarative provider plan.
 *
 * The fingerprint is a reproducibility guard for a durable scan request; it
 * is not a claim that every provider in the plan was executed.  The canonical
 * form includes execution-relevant definition fields and orders set-valued
 * fields so the result does not depend on collection iteration order.
 */
internal object ProviderPlanFingerprint {
    const val MAX_PERSISTED_PROVIDER_IDS = 2_048
    private val fingerprintPattern = Regex("^[0-9a-f]{64}$")
    private val providerIdPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun forPlan(plan: ProviderScanPlan): String {
        val canonical = buildString {
            appendValue(plan.mode.name)
            appendValue(plan.scheduledProviderCount)
            plan.providers.forEach { definition ->
                appendValue(definition.id)
                appendValue(definition.displayName)
                appendValue(definition.category.name)
                appendValue(definition.profileUrlTemplate)
                appendCollection(definition.queryCapabilities.map { it.name })
                appendExistenceRules(definition.existenceRules)
                appendExtractionRules(definition.extractionRules)
                appendValue(definition.priority)
                appendCollection(definition.regions)
                appendCollection(definition.tags)
                appendValue(definition.enabled)
                appendValue(definition.reliability.name)
                appendValue(definition.requestPolicy.maxConcurrency)
                appendValue(definition.requestPolicy.minimumIntervalMs)
                appendValue(definition.requestPolicy.timeoutMs)
                appendValue(definition.requestPolicy.retryBudget)
                appendValue(definition.requestPolicy.cooldownMs)
                appendValue(definition.legacyPlatformName)
                appendValue(definition.legacyTemplateCompatible)
                appendCollection(definition.approvedHosts)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    fun persistedProviderIds(plan: ProviderScanPlan): List<String> =
        plan.providers
            .asSequence()
            .map { it.id }
            .take(MAX_PERSISTED_PROVIDER_IDS)
            .toList()

    fun isValid(value: String?): Boolean = value != null && fingerprintPattern.matches(value)

    fun areValidProviderIds(values: List<String>): Boolean =
        values.size <= MAX_PERSISTED_PROVIDER_IDS &&
            values.distinct().size == values.size &&
            values.all { providerIdPattern.matches(it) }

    private fun StringBuilder.appendExistenceRules(rules: ExistenceRules?) {
        if (rules == null) {
            appendValue(null as String?)
            return
        }
        appendCollection(rules.requiredStatus.map(Int::toString))
        appendCollection(rules.notFoundStatus.map(Int::toString))
        appendCollection(rules.requiredText)
        appendCollection(rules.forbiddenText)
        appendCollection(rules.softNotFoundText)
        appendCollection(rules.authenticationText)
        appendCollection(rules.challengeText)
        appendValue(rules.followRedirects)
    }

    private fun StringBuilder.appendExtractionRules(rules: ExtractionRules?) {
        if (rules == null) {
            appendValue(null as String?)
            return
        }
        // Selector order is meaningful: the first matching selector wins for
        // scalar fields, so preserve list order rather than sorting it.
        appendCollection(rules.displayNameSelectors, sort = false)
        appendCollection(rules.bioSelectors, sort = false)
        appendCollection(rules.avatarSelectors, sort = false)
        appendCollection(rules.canonicalSelectors, sort = false)
        appendCollection(rules.linkSelectors, sort = false)
    }

    private fun StringBuilder.appendCollection(values: Collection<*>, sort: Boolean = true) {
        val normalized = values.map { it?.toString().orEmpty() }
            .let { if (sort) it.sorted() else it }
        appendValue(normalized.joinToString("\u001f"))
    }

    private fun StringBuilder.appendValue(value: Any?) {
        val text = value?.toString() ?: "<null>"
        // Length prefixing prevents delimiter collisions from changing the
        // canonical field boundaries.
        append(text.length).append(':').append(text).append('|')
    }
}
