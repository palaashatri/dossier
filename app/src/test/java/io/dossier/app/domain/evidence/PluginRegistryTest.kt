package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PluginRegistryTest {

    private fun input() = IdentityInput(
        fullName = "Test User",
        emails = listOf("a@b.com"),
        phones = listOf("+15551234"),
        usernames = listOf("tester"),
        primaryUsername = "testuser"
    )

    @Test
    fun seedPluginEmitsEvidence() = runBlocking {
        val coll = SeedEvidencePlugin().scan(input())
        assertEquals(4, coll.evidence.size)
        assertTrue(coll.evidence.any { it.kind == EvidenceKind.Email && it.value == "a@b.com" })
        assertTrue(coll.evidence.any { it.kind == EvidenceKind.Username && it.value == "tester" })
    }

    @Test
    fun runPluginsAggregatesAndIsolatesFailures() = runBlocking {
        PluginRegistry.clear()
        PluginRegistry.register(SeedEvidencePlugin())
        PluginRegistry.register(object : ScannerPlugin {
            override val id = "boom"
            override val displayName = "boom"
            override suspend fun scan(input: IdentityInput): EvidenceCollection {
                throw RuntimeException("plugin failed")
            }
        })
        val result = runPlugins(input())
        // Boom plugin skipped; seed plugin still contributes.
        assertEquals(4, result.evidence.size)
        PluginRegistry.clear()
    }

    @Test
    fun registerDeduplicatesById() {
        PluginRegistry.clear()
        PluginRegistry.register(SeedEvidencePlugin())
        PluginRegistry.register(SeedEvidencePlugin())
        assertEquals(1, PluginRegistry.registered().size)
        PluginRegistry.clear()
    }

    @Test
    fun runPluginsMergesRelationshipEvidenceIdsFromIndependentProducers() = runBlocking {
        fun relationshipPlugin(pluginId: String, evidenceId: String) = object : ScannerPlugin {
            override val id = pluginId
            override val displayName = pluginId

            override suspend fun scan(input: IdentityInput): EvidenceCollection = EvidenceCollection(
                relationships = listOf(
                    EvidenceRelationship(
                        fromValue = "Test User",
                        toValue = "https://example.test/profile",
                        relation = "LINKS_TO",
                        evidence = "direct profile link",
                        evidenceIds = listOf(evidenceId)
                    )
                )
            )
        }

        val result = runPlugins(
            input(),
            plugins = listOf(
                relationshipPlugin("relationship-one", "evidence-one"),
                relationshipPlugin("relationship-two", "evidence-two")
            )
        )

        assertEquals(listOf("evidence-one", "evidence-two"), result.relationships.single().evidenceIds)
    }

    @Test
    fun runPluginsOverlapsIndependentFamiliesAndKeepsInputOrder() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        fun plugin(
            pluginId: String,
            started: CompletableDeferred<Unit>,
            otherStarted: CompletableDeferred<Unit>
        ) = object : ScannerPlugin {
            override val id = pluginId
            override val displayName = pluginId

            override suspend fun scan(input: IdentityInput): EvidenceCollection {
                started.complete(Unit)
                withTimeout(1_000) { otherStarted.await() }
                return EvidenceCollection(
                    evidence = listOf(
                        Evidence(
                            id = pluginId,
                            kind = EvidenceKind.PublicSearchEvidence,
                            value = pluginId
                        )
                    )
                )
            }
        }

        val result = withTimeout(2_000) {
            runPlugins(
                input(),
                plugins = listOf(
                    plugin("first", firstStarted, secondStarted),
                    plugin("second", secondStarted, firstStarted)
                )
            )
        }

        assertEquals(listOf("first", "second"), result.evidence.map(Evidence::id))
    }

    @Test
    fun runPluginsBoundsConcurrentFamilies() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val plugins = (0 until 6).map { index ->
            object : ScannerPlugin {
                override val id = "bounded-$index"
                override val displayName = id

                override suspend fun scan(input: IdentityInput): EvidenceCollection {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, current) }
                    try {
                        delay(25)
                        return EvidenceCollection(
                            evidence = listOf(
                                Evidence(
                                    id = id,
                                    kind = EvidenceKind.PublicSearchEvidence,
                                    value = id
                                )
                            )
                        )
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }

        val result = runPlugins(input(), plugins)

        assertTrue("independent families should overlap", peak.get() > 1)
        assertTrue("plugin coordinator exceeded its bound: ${peak.get()}", peak.get() <= 3)
        assertEquals(plugins.map(ScannerPlugin::id), result.evidence.map(Evidence::id))
    }

    @Test
    fun runPluginsNeverConvertsCancellationIntoProviderFailure() {
        val cancellingPlugin = object : ScannerPlugin {
            override val id = "cancel"
            override val displayName = "cancel"
            override suspend fun scan(input: IdentityInput): EvidenceCollection {
                throw CancellationException("replacement requested")
            }
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { runPlugins(input(), listOf(cancellingPlugin)) }
        }
    }
}
