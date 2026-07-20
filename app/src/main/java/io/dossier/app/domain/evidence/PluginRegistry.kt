package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.IdentityInput

/**
 * Plugin registry + runner (ROADMAP Milestone 15).
 *
 * Third parties implement [ScannerPlugin] and register an instance here; the
 * scan orchestrator can then fold every plugin's [EvidenceCollection] into the
 * same pipeline the built-in scanners feed. Adding a plugin requires **zero
 * changes elsewhere** — it only needs to be registered.
 *
 * This is intentionally Android-free so a plugin (and this registry) is
 * unit-testable without a device or Context.
 */
object PluginRegistry {
    private val plugins = mutableListOf<ScannerPlugin>()

    fun register(plugin: ScannerPlugin) {
        if (plugins.none { it.id == plugin.id }) plugins.add(plugin)
    }

    fun unregister(id: String) {
        plugins.removeAll { it.id == id }
    }

    fun registered(): List<ScannerPlugin> = plugins.toList()

    fun clear() = plugins.clear()
}

/**
 * Runs a set of plugins and merges their [EvidenceCollection] outputs.
 * Failures in one plugin are isolated: a throwing plugin contributes nothing
 * rather than aborting the whole scan (fail-safe, like the pivot pass).
 */
suspend fun runPlugins(
    input: IdentityInput,
    plugins: List<ScannerPlugin> = PluginRegistry.registered()
): EvidenceCollection {
    val allEvidence = mutableListOf<Evidence>()
    val allRelationships = mutableListOf<EvidenceRelationship>()
    for (plugin in plugins) {
        try {
            val result = plugin.scan(input)
            allEvidence.addAll(result.evidence)
            allRelationships.addAll(result.relationships)
        } catch (t: Throwable) {
            // Isolation: skip a misbehaving plugin, keep the rest.
        }
    }
    return EvidenceCollection(
        evidence = allEvidence.distinctBy { it.id },
        relationships = allRelationships
    )
}

/**
 * Example plugin: emits [Evidence] directly from the identity seeds the user
 * supplied (emails, phones, usernames). Demonstrates the [ScannerPlugin]
 * contract without any network/Android dependency.
 */
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
                .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.forEach {
                    add(Evidence(id = "seed:username:$it", kind = EvidenceKind.Username, value = it, confidence = 1.0f))
                }
        }
        return EvidenceCollection(evidence = evidence)
    }
}
