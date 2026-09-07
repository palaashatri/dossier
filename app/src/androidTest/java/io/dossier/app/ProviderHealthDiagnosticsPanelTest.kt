package io.dossier.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.discovery.ProviderHealthAssessment
import io.dossier.app.domain.discovery.ProviderHealthReport
import io.dossier.app.domain.discovery.ProviderHealthStatus
import io.dossier.app.ui.screens.ProviderHealthDiagnosticsPanel
import io.dossier.app.ui.theme.DossierTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ProviderHealthDiagnosticsPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun surfacesEveryCatalogHealthBucketWithoutClaimingLiveValidation() {
        val now = Instant.parse("2026-08-25T12:00:00Z")
        val report = ProviderHealthReport(
            knownProviderCount = 5,
            observedProviderCount = 5,
            assessments = listOf(
                ProviderHealthAssessment("healthy", ProviderHealthStatus.Healthy, 3, 3, 1.0, 0.0, 100, now),
                ProviderHealthAssessment("degraded", ProviderHealthStatus.Degraded, 3, 2, 2.0 / 3.0, 1.0 / 3.0, 100, now),
                ProviderHealthAssessment("unavailable", ProviderHealthStatus.Unavailable, 3, 0, 0.0, 1.0, null, now),
                ProviderHealthAssessment("stale", ProviderHealthStatus.Stale, 3, 3, 1.0, 0.0, 100, now.minusSeconds(31 * 24 * 60 * 60)),
                ProviderHealthAssessment("unvalidated", ProviderHealthStatus.Unvalidated, 0, 0, 0.0, 0.0, null, null)
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                ProviderHealthDiagnosticsPanel(report)
            }
        }

        composeRule.onNodeWithText("Provider catalog health").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Healthy 1 · Degraded 1 · Unavailable 1 · Stale 1 · Unvalidated 1",
            substring = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText("UNVALIDATED").assertIsDisplayed()
        composeRule.onNodeWithText(
            "catalog membership, an HTTP 200, or a search hit is not live validation",
            substring = true
        ).assertIsDisplayed()
    }
}
