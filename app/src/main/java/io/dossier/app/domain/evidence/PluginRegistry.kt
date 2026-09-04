package io.dossier.app.domain.evidence

import io.dossier.app.data.web.WaybackHistoryPlugin
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Process-local cache of the latest merged plugin evidence. It exists so deterministic
 * post-scan analyzers can reuse already-collected public activity rather than hitting
 * Reddit/archive/import sources a second time. Durable/background workers persist
 * derived analysis separately; this cache is not itself a persistence layer.
 */
object EvidenceRuntimeCache {
    /**
     * A restored case must not be able to repopulate the process cache with an
     * unbounded payload. Keep this cap aligned with the encrypted case store's
     * evidence bound while preserving the first record for each stable ID.
     */
    const val MAX_CASE_EVIDENCE = 10_000
    const val MAX_CASE_RELATIONSHIPS = EvidenceRelationshipPolicy.MAX_RELATIONSHIPS

    private val _collection = MutableStateFlow(EvidenceCollection())
    val collection: StateFlow<EvidenceCollection> = _collection

    fun replace(value: EvidenceCollection) {
        _collection.value = value.copy(
            evidence = value.evidence.distinctBy { it.id }.take(MAX_CASE_EVIDENCE),
            relationships = EvidenceRelationshipPolicy.normalize(value.relationships)
        )
    }

    /** Rehydrates bounded, ID-deduplicated evidence and relationship assertions. */
    fun replaceCaseEvidence(
        records: List<Evidence>,
        relationships: List<EvidenceRelationship> = emptyList()
    ) {
        replace(EvidenceCollection(evidence = records, relationships = relationships))
    }

    fun clear() {
        _collection.value = EvidenceCollection()
    }
}

/** Plugin registry + isolated runner. */
object PluginRegistry {
    private val plugins = mutableListOf<ScannerPlugin>(
        RedditPublicActivityPlugin(),
        WhatsMyNameUsernamePlugin(),
        WaybackHistoryPlugin(),
        LegacyOsintImportPlugin(),
        ExternalOsintImportPlugin(),
        ExternalInteractionImportPlugin()
    )

    fun register(plugin: ScannerPlugin) {
        if (plugins.none { it.id == plugin.id }) plugins.add(plugin)
    }

    fun unregister(id: String) {
        plugins.removeAll { it.id == id }
    }

    fun registered(): List<ScannerPlugin> = plugins.toList()

    fun clear() = plugins.clear()
}

private const val MAX_PLUGIN_CONCURRENCY = 3
private val pluginCoordinator = Semaphore(MAX_PLUGIN_CONCURRENCY)

/**
 * Runs plugins and merges their Evidence collections. A failing source is isolated.
 * The merged result is cached once so post-processing can remain local and bounded.
 */
suspend fun runPlugins(
    input: IdentityInput,
    plugins: List<ScannerPlugin> = PluginRegistry.registered()
): EvidenceCollection {
    EvidenceRuntimeCache.clear()
    UsernameSurfaceRuntimeCache.clear()

    // Each plugin is an independent evidence family. The shared permit bounds
    // family-level work while provider implementations retain their own throttles.
    val collections = supervisorScope {
        plugins.map { plugin ->
            async {
                try {
                    pluginCoordinator.withPermit { plugin.scan(input) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Isolation: one brittle provider/import never aborts the authorized scan.
                    null
                }
            }
        }.awaitAll()
    }

    val allEvidence = collections.filterNotNull().flatMap(EvidenceCollection::evidence)
    val allRelationships = collections.filterNotNull().flatMap(EvidenceCollection::relationships)
    val merged = EvidenceCollection(
        evidence = allEvidence.distinctBy { it.id },
        relationships = EvidenceRelationshipPolicy.normalize(allRelationships)
    )
    EvidenceRuntimeCache.replace(merged)
    return merged
}

/** Example plugin emitting explicit user-provided seeds. */
class SeedEvidencePlugin : ScannerPlugin {
    override val id: String = "seed-evidence"
    override val displayName: String = "Identity Seed Evidence"

    override suspend fun scan(input: IdentityInput): EvidenceCollection {
        val evidence = buildList {
            input.emails.filter { it.isNotBlank() }.forEach {
                add(Evidence(id = "seed:email:$it", kind = EvidenceKind.Email, value = it, confidence = 1.0f))
            }
            input.phones.filter { it.isNotBlank() }.forEach {
                add(Evidence(id = "seed:phone:$it", kind = EvidenceKind.Phone, value = it, confidence = 1.0f))
            }
            (listOfNotNull(input.primaryUsername) + input.usernames)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .forEach {
                    add(Evidence(id = "seed:username:$it", kind = EvidenceKind.Username, value = it, confidence = 1.0f))
                }
        }
        return EvidenceCollection(evidence = evidence)
    }
}
