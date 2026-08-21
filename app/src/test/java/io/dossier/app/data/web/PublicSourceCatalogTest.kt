package io.dossier.app.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSourceCatalogTest {
    @Test
    fun sourceIdsAreUnique() {
        assertEquals(PublicSourceCatalog.all.size, PublicSourceCatalog.all.map { it.id }.toSet().size)
    }

    @Test
    fun broadToolFamiliesAreRepresentedTruthfully() {
        listOf(
            "spiderfoot", "recon-ng", "theharvester", "maigret", "sherlock", "holehe",
            "pushshift", "osintgram", "instaloader", "social-analyzer",
            "exiftool", "openstreetmap-overpass", "hibp", "opencorporates",
            "phoneinfoga", "onionscan", "censys", "shodan", "amass",
            "opencti", "gephi", "cytoscape", "networkx"
        ).forEach { id -> assertNotNull("Missing source $id", PublicSourceCatalog.byId(id)) }
    }

    @Test
    fun importOnlyToolsAreNotAdvertisedAsDirectVerification() {
        listOf("spiderfoot", "maigret", "sherlock", "holehe", "phoneinfoga", "amass").forEach { id ->
            val source = requireNotNull(PublicSourceCatalog.byId(id))
            assertEquals(PublicSourceIntegrationMode.ImportOnly, source.integrationMode)
            assertFalse(source.directVerification)
            assertFalse(source.automatedInAuthorizedScan)
        }
    }

    @Test
    fun retiredCachesCannotBecomeUsableProviders() {
        listOf("google-cache", "bing-cache").forEach { id ->
            val source = requireNotNull(PublicSourceCatalog.byId(id))
            assertEquals(PublicSourceState.Retired, source.state)
            assertEquals(PublicSourceIntegrationMode.Retired, source.integrationMode)
            assertTrue(source.capabilities.isEmpty())
        }
    }

    @Test
    fun onlyExplicitNativeSourcesAreAutomated() {
        val automated = PublicSourceCatalog.automated()
        assertTrue(automated.isNotEmpty())
        assertTrue(automated.all { it.integrationMode == PublicSourceIntegrationMode.Native })
        assertTrue(automated.none { it.category == PublicSourceCategory.DarkWeb })
        assertTrue(automated.none { it.id in setOf("holehe", "dehashed", "leakcheck", "onionscan") })
    }
}
