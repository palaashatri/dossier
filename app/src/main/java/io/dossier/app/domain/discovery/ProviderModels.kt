package io.dossier.app.domain.discovery

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale

@Serializable
enum class ProviderCategory {
    Developer,
    Social,
    Forum,
    Gaming,
    Creative,
    Publishing,
    Professional,
    Media,
    Commerce,
    Education,
    CodeHosting,
    PackageRegistry,
    PersonalWebsite,
    PublicDirectory,
    Archive,
    BreachMetadata,
    SearchEngine
}

@Serializable
enum class QueryCapability {
    Username,
    Name,
    Email,
    Phone,
    Domain,
    Url,
    Image,
    Archive,
    Breach
}

@Serializable
enum class SourceReliability {
    AuthoritativeApi,
    DirectPublicProfile,
    DirectPersonalWebsite,
    ArchiveSnapshot,
    SearchCandidate,
    ThirdPartyAggregation
}

@Serializable
data class ExistenceRules(
    val requiredStatus: Set<Int> = setOf(200),
    val notFoundStatus: Set<Int> = setOf(404),
    val requiredText: List<String> = emptyList(),
    val forbiddenText: List<String> = emptyList(),
    val softNotFoundText: List<String> = emptyList(),
    val authenticationText: List<String> = emptyList(),
    val challengeText: List<String> = emptyList(),
    val followRedirects: Boolean = true
)

@Serializable
data class ExtractionRules(
    val displayNameSelectors: List<String> = emptyList(),
    val bioSelectors: List<String> = emptyList(),
    val avatarSelectors: List<String> = emptyList(),
    val canonicalSelectors: List<String> = listOf("link[rel=canonical]"),
    val linkSelectors: List<String> = listOf("a[href]")
)

@Serializable
data class ProviderRequestPolicy(
    val maxConcurrency: Int = 1,
    val minimumIntervalMs: Long = 750,
    val timeoutMs: Long = 5_000,
    val retryBudget: Int = 1,
    val cooldownMs: Long = 30_000
)

@Serializable
data class ProviderDefinition(
    val id: String,
    val displayName: String,
    val category: ProviderCategory,
    val profileUrlTemplate: String? = null,
    val queryCapabilities: Set<QueryCapability>,
    val existenceRules: ExistenceRules? = null,
    val extractionRules: ExtractionRules? = null,
    val priority: Int = 50,
    val regions: Set<String> = setOf("GLOBAL"),
    val tags: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val reliability: SourceReliability = SourceReliability.DirectPublicProfile,
    val requestPolicy: ProviderRequestPolicy = ProviderRequestPolicy(),
    /** Existing enum name used only by the compatibility adapter. */
    val legacyPlatformName: String? = null,
    /** True only when the current ProfileScanner can safely execute the template. */
    val legacyTemplateCompatible: Boolean = profileUrlTemplate?.contains("{username}") == true
)

@Serializable
enum class ScanMode {
    Quick,
    Standard,
    Deep,
    Exhaustive;

    val providerLimit: Int
        get() = when (this) {
            Quick -> 50
            Standard -> 200
            Deep -> 500
            Exhaustive -> Int.MAX_VALUE
        }

    val includeHistoricalProviders: Boolean
        get() = this == Deep || this == Exhaustive

    /** Enables the existing bounded linked-site/search expansion path. */
    val includeExtendedDiscovery: Boolean
        get() = this == Deep || this == Exhaustive
}

@Serializable
data class ProviderScanPlan(
    val mode: ScanMode,
    val providers: List<ProviderDefinition>,
    val scheduledProviderCount: Int = providers.size
)

sealed interface ProviderValidationIssue {
    val providerId: String
    val message: String

    data class InvalidId(override val providerId: String) : ProviderValidationIssue {
        override val message: String = "Provider id must use lowercase letters, numbers and hyphens only"
    }

    data class InvalidPriority(override val providerId: String) : ProviderValidationIssue {
        override val message: String = "Priority must be in 0..100"
    }

    data class InvalidTemplate(override val providerId: String, override val message: String) : ProviderValidationIssue

    data class InvalidStatusRules(override val providerId: String) : ProviderValidationIssue {
        override val message: String = "requiredStatus and notFoundStatus must not overlap"
    }

    data class InvalidRequestPolicy(override val providerId: String, override val message: String) : ProviderValidationIssue

    data class DuplicateId(override val providerId: String) : ProviderValidationIssue {
        override val message: String = "Provider id is duplicated"
    }
}

object ProviderDefinitionValidator {
    private val idPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun validate(definition: ProviderDefinition): List<ProviderValidationIssue> = buildList {
        if (!idPattern.matches(definition.id)) {
            add(ProviderValidationIssue.InvalidId(definition.id))
        }
        if (definition.priority !in 0..100) {
            add(ProviderValidationIssue.InvalidPriority(definition.id))
        }
        definition.profileUrlTemplate?.let { template ->
            val lower = template.lowercase(Locale.US)
            if (!lower.startsWith("https://")) {
                add(ProviderValidationIssue.InvalidTemplate(definition.id, "Profile templates must use HTTPS"))
            }
            if (QueryCapability.Username in definition.queryCapabilities && !template.contains("{username}")) {
                add(ProviderValidationIssue.InvalidTemplate(definition.id, "Username-capable profile template must contain {username}"))
            }
            if (template.countOccurrences("{username}") > 1) {
                add(ProviderValidationIssue.InvalidTemplate(definition.id, "Profile template may contain {username} only once"))
            }
        }
        definition.existenceRules?.let { rules ->
            if (rules.requiredStatus.any { it in rules.notFoundStatus }) {
                add(ProviderValidationIssue.InvalidStatusRules(definition.id))
            }
        }
        val policy = definition.requestPolicy
        if (policy.maxConcurrency !in 1..8) {
            add(ProviderValidationIssue.InvalidRequestPolicy(definition.id, "maxConcurrency must be in 1..8"))
        }
        if (policy.minimumIntervalMs < 0 || policy.timeoutMs !in 500..60_000 || policy.retryBudget !in 0..4 || policy.cooldownMs < 0) {
            add(ProviderValidationIssue.InvalidRequestPolicy(definition.id, "Request timing/retry values are outside safe bounds"))
        }
    }

    fun validateRegistry(definitions: List<ProviderDefinition>): List<ProviderValidationIssue> {
        val duplicateIssues = definitions
            .groupBy(ProviderDefinition::id)
            .filterValues { it.size > 1 }
            .keys
            .map(ProviderValidationIssue::DuplicateId)
        return duplicateIssues + definitions.flatMap(::validate)
    }

    private fun String.countOccurrences(token: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val index = indexOf(token, offset)
            if (index < 0) return count
            count++
            offset = index + token.length
        }
    }
}

enum class ProviderOutcome {
    Success,
    NotFound,
    SoftNotFound,
    Timeout,
    RateLimited,
    AuthenticationRequired,
    UnsupportedAutomation,
    ParseFailure,
    NetworkFailure
}

data class ProviderHealthSnapshot(
    val providerId: String,
    val attempts: Long,
    val successes: Long,
    val notFound: Long,
    val softNotFound: Long,
    val timeouts: Long,
    val rateLimited: Long,
    val authenticationRequired: Long,
    val unsupportedAutomation: Long,
    val parseFailures: Long,
    val networkFailures: Long,
    val medianLatencyMs: Long?,
    val lastValidatedAt: Instant?
) {
    val successRate: Double
        get() = if (attempts == 0L) 0.0 else successes.toDouble() / attempts.toDouble()
}

/**
 * Process-local provider diagnostics. Persistent/longitudinal health belongs to
 * later production diagnostics that store no investigation content.
 */
class ProviderHealthTracker(private val latencyWindow: Int = 101) {
    private data class MutableHealth(
        var attempts: Long = 0,
        var successes: Long = 0,
        var notFound: Long = 0,
        var softNotFound: Long = 0,
        var timeouts: Long = 0,
        var rateLimited: Long = 0,
        var authenticationRequired: Long = 0,
        var unsupportedAutomation: Long = 0,
        var parseFailures: Long = 0,
        var networkFailures: Long = 0,
        var lastValidatedAt: Instant? = null,
        val latencies: ArrayDeque<Long> = ArrayDeque()
    )

    private val state = linkedMapOf<String, MutableHealth>()

    @Synchronized
    fun record(providerId: String, outcome: ProviderOutcome, latencyMs: Long, at: Instant = Instant.now()) {
        require(latencyMs >= 0) { "latencyMs must be non-negative" }
        val health = state.getOrPut(providerId) { MutableHealth() }
        health.attempts++
        when (outcome) {
            ProviderOutcome.Success -> health.successes++
            ProviderOutcome.NotFound -> health.notFound++
            ProviderOutcome.SoftNotFound -> health.softNotFound++
            ProviderOutcome.Timeout -> health.timeouts++
            ProviderOutcome.RateLimited -> health.rateLimited++
            ProviderOutcome.AuthenticationRequired -> health.authenticationRequired++
            ProviderOutcome.UnsupportedAutomation -> health.unsupportedAutomation++
            ProviderOutcome.ParseFailure -> health.parseFailures++
            ProviderOutcome.NetworkFailure -> health.networkFailures++
        }
        health.lastValidatedAt = at
        health.latencies.addLast(latencyMs)
        while (health.latencies.size > latencyWindow) health.latencies.removeFirst()
    }

    @Synchronized
    fun snapshot(providerId: String): ProviderHealthSnapshot {
        val health = state[providerId] ?: MutableHealth()
        val sorted = health.latencies.sorted()
        return ProviderHealthSnapshot(
            providerId = providerId,
            attempts = health.attempts,
            successes = health.successes,
            notFound = health.notFound,
            softNotFound = health.softNotFound,
            timeouts = health.timeouts,
            rateLimited = health.rateLimited,
            authenticationRequired = health.authenticationRequired,
            unsupportedAutomation = health.unsupportedAutomation,
            parseFailures = health.parseFailures,
            networkFailures = health.networkFailures,
            medianLatencyMs = sorted.takeIf { it.isNotEmpty() }?.get(sorted.size / 2),
            lastValidatedAt = health.lastValidatedAt
        )
    }
}
