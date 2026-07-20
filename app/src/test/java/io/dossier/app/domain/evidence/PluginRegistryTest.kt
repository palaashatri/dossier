package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
