package io.dossier.app.ui.screens

import io.dossier.app.domain.discovery.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Test

class UsernameDiscoveryScreenTest {
    @Test
    fun unavailableCatalogOmitsUsernameRuleCount() {
        assertEquals(
            "47 profiles",
            formatModeCounts(47, null, ScanMode.Quick)
        )
    }

    @Test
    fun readyCatalogClampsEveryModeToItsRealBudget() {
        assertEquals(
            "47 profiles • 50 username rules",
            formatModeCounts(47, 644, ScanMode.Quick)
        )
        assertEquals(
            "150 profiles • 200 username rules",
            formatModeCounts(150, 644, ScanMode.Standard)
        )
        assertEquals(
            "300 profiles • 500 username rules",
            formatModeCounts(300, 644, ScanMode.Deep)
        )
        assertEquals(
            "350 profiles • 644 username rules",
            formatModeCounts(350, 644, ScanMode.Exhaustive)
        )
        assertEquals(
            "350 profiles • 600 username rules",
            formatModeCounts(350, 600, ScanMode.Exhaustive)
        )
    }
}
