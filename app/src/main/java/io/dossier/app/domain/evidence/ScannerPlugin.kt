package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.IdentityInput

/**
 * Plugin SDK surface (ROADMAP Milestone 15).
 *
 * Every scanner in Dossier is, conceptually, a [ScannerPlugin] that returns an
 * [EvidenceCollection]. The narrower [EvidenceProducer], [RelationshipProvider],
 * and [ConfidenceContributor] contracts let third parties extend a single axis
 * (producing evidence, asserting relationships, or contributing to a confidence
 * score) without reimplementing a whole scan pass.
 *
 * These interfaces are intentionally framework-free: they take plain domain
 * inputs and return plain domain outputs so a plugin can be unit-tested in
 * isolation and added to a scan without changes elsewhere.
 */
interface ScannerPlugin {
    /** Stable plugin id, e.g. "username-scanner". */
    val id: String

    /** Human-readable name for logs and the plugin list UI. */
    val displayName: String

    /**
     * Run the plugin and return evidence. Implementations must be suspend-friendly
     * (network/IO) and must never fabricate evidence they did not observe.
     */
    suspend fun scan(input: IdentityInput): EvidenceCollection
}

/** Contributes raw evidence for a slice of the identity (e.g. PII on a page). */
interface EvidenceProducer {
    val id: String
    suspend fun produce(input: IdentityInput): List<Evidence>
}

/** Asserts direct relationships between observed values (seeds the graph). */
interface RelationshipProvider {
    val id: String
    suspend fun relate(input: IdentityInput, evidence: List<Evidence>): List<EvidenceRelationship>
}

/**
 * Contributes a partial 0..1 confidence that two observed values refer to the
 * same subject, with the named [Signals] that justify it (ROADMAP Principle 3).
 */
interface ConfidenceContributor {
    val id: String

    data class Signals(
        val score: Float,
        val reasons: List<String>
    )

    /**
     * @param a first observed value (with its kind)
     * @param b second observed value (with its kind)
     * @return a [Signals] or null if this contributor has nothing to say
     */
    fun contribute(a: Evidence, b: Evidence): Signals?
}
