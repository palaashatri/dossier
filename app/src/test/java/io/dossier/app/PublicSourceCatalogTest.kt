package io.dossier.app

import io.dossier.app.data.web.PublicSourceCatalog
import io.dossier.app.data.web.PublicSourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSourceCatalogTest {

    @Test
    fun activeAndBestEffortSourcesAreExplicit() {
        assertEquals(PublicSourceState.Active, PublicSourceCatalog.byId("reddit-ghostddit-compatible")?.state)
        assertEquals(PublicSourceState.Active, PublicSourceCatalog.byId("wayback")?.state)
        assertEquals(PublicSourceState.BestEffort, PublicSourceCatalog.byId("archive-today")?.state)
    }

    @Test
    fun retiredCachesAndLegacyScrapersAreNotReportedAsHealthyLiveProviders() {
        assertEquals(PublicSourceState.Retired, PublicSourceCatalog.byId("google-cache")?.state)
        assertEquals(PublicSourceState.Retired, PublicSourceCatalog.byId("bing-cache")?.state)
        assertEquals(PublicSourceState.Retired, PublicSourceCatalog.byId("twint")?.state)
        assertEquals(PublicSourceState.Degraded, PublicSourceCatalog.byId("snscrape")?.state)

        assertFalse(PublicSourceCatalog.byId("google-cache")!!.directVerification)
        assertFalse(PublicSourceCatalog.byId("bing-cache")!!.directVerification)
        assertFalse(PublicSourceCatalog.byId("twint")!!.directVerification)
        assertFalse(PublicSourceCatalog.byId("snscrape")!!.directVerification)
    }

    @Test
    fun historicalUrlRoutingOnlyReturnsUsableArchiveProviders() {
        val ids = PublicSourceCatalog.usableFor("historical-url").map { it.id }.toSet()
        assertEquals(setOf("wayback", "archive-today"), ids)
        assertTrue("google-cache" !in ids)
        assertTrue("bing-cache" !in ids)
    }
}
