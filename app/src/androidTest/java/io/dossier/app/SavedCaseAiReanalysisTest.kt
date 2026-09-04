package io.dossier.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanHistoryRuntime
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.evidence.UsernameSurfaceRuntimeCache
import io.dossier.app.domain.place.MediaIntelligenceSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedCaseAiReanalysisTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val store: CaseStore
        get() = CaseStore(composeRule.activity)

    @Before
    fun resetState() {
        composeRule.activity
            .getSharedPreferences("dossier-usage-notice", 0)
            .edit()
            .clear()
            .commit()
        DiscoveryScanPreferences.reset()
        ScanHistoryRuntime.resetForTests()
        EvidenceRuntimeCache.clear()
        UsernameSurfaceRuntimeCache.clear()
        MediaIntelligenceSession.clear()
        store.clear()
        composeRule.activity
            .getSharedPreferences("ai_provider_configs", 0)
            .edit()
            .clear()
            .commit()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @After
    fun clearState() {
        EvidenceRuntimeCache.clear()
        UsernameSurfaceRuntimeCache.clear()
        MediaIntelligenceSession.clear()
        store.clear()
    }

    @Test
    fun staleSavedCaseShowsReRunActionAndFailurePreservesRefreshState() {
        val saved = DossierCase(
            caseId = "ai-reanalysis-empty",
            createdAt = "2026-08-24 00:00",
            subjectName = "Empty subject",
            input = IdentityInput(fullName = "Empty subject"),
            aiSummary = "previous validated summary",
            aiSummaryNeedsRefresh = true
        )
        assertTrue(store.save(saved))

        openCases()

        composeRule
            .onNodeWithText("Re-run analysis")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForText("No validated analysis result was returned")

        val unchanged = requireNotNull(store.load(saved.caseId))
        assertEquals("previous validated summary", unchanged.aiSummary)
        assertTrue(unchanged.aiSummaryNeedsRefresh)
    }

    @Test
    fun successfulReRunPersistsSummaryAndClearsRefreshFlag() {
        val saved = DossierCase(
            caseId = "ai-reanalysis-success",
            createdAt = "2026-08-24 00:00",
            subjectName = "Jane Example",
            input = IdentityInput(fullName = "Jane Example"),
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "jane@example.test",
                    sourceUrl = "https://example.test/contact",
                    evidenceSnippet = "Public contact page",
                    confidence = 0.9f,
                    risk = RiskLevel.High,
                    remediation = "Review the public contact detail."
                )
            ),
            aiSummary = "previous validated summary",
            aiSummaryNeedsRefresh = true,
            riskLevel = RiskLevel.High
        )
        assertTrue(store.save(saved))

        openCases()

        composeRule.onNodeWithText("Re-run analysis").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            store.load(saved.caseId)?.let { loaded ->
                !loaded.aiSummaryNeedsRefresh &&
                    loaded.aiSummary?.contains("Local baseline analysis") == true
            } == true
        }

        val refreshed = requireNotNull(store.load(saved.caseId))
        assertTrue(refreshed.aiSummary?.contains("Network used for analysis: no") == true)
        assertTrue(refreshed.aiSummary?.contains("Input policy:") == true)
    }

    private fun openCases() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("CONTINUE").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Search name, username, phone, email or URL").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithText("CONTINUE").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("CONTINUE").performClick()
            waitForText("Search name, username, phone, email or URL")
        }
        composeRule
            .onNode(
                hasAnyDescendant(hasText("Cases")) and hasClickAction(),
                useUnmergedTree = true
            )
            .performClick()
        waitForText("Saved cases")
        waitForText("Review newer case")
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
