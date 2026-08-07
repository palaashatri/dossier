package io.dossier.app.discovery

import io.dossier.app.data.platform.ProviderCatalogV2
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCatalogCountTest {
    @Test
    fun catalogInventoryMatchesTruthDocument() {
        assertEquals(
            "Update TRUTH.md and this assertion together when the reviewed catalog changes",
            78,
            ProviderCatalogV2.definitions.size
        )
    }
}
