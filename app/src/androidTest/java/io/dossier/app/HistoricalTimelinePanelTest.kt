package io.dossier.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.ui.screens.HistoricalTimelinePanel
import io.dossier.app.ui.theme.DossierTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoricalTimelinePanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsCurrentHistoricalAndChangeSemantics() {
        val case = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "current",
                    kind = EvidenceKind.Profile,
                    value = "https://social.example/current",
                    sourceUrl = "https://social.example/current",
                    providerId = "social-example",
                    observedAtEpochMillis = 2_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.DirectPublicProfile
                ),
                Evidence(
                    id = "historical",
                    kind = EvidenceKind.Profile,
                    value = "https://web.archive.org/web/1000/https://social.example/current",
                    sourceUrl = "https://web.archive.org/web/1000/https://social.example/current",
                    providerId = "wayback-snapshot",
                    observedAtEpochMillis = 1_000L,
                    retrievedAtEpochMillis = 1_500L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                HistoricalTimelinePanel(case)
            }
        }

        composeRule.onNodeWithText("Identity timeline").assertIsDisplayed()
        composeRule.onNodeWithText("VERIFIED CURRENT").assertIsDisplayed()
        composeRule.onNodeWithText("HISTORICAL").assertIsDisplayed()
        composeRule.onNodeWithText("HISTORICAL · RETRIEVAL RECORDED").assertIsDisplayed()
        composeRule.onNodeWithText("Current and historical observations are shown together", substring = true)
            .assertIsDisplayed()
        check(
            composeRule.onAllNodesWithText("provider wayback-snapshot", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
    }

    @Test
    fun explainsMissingTimestampsAndUnavailableArchive() {
        val case = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "undated",
                    kind = EvidenceKind.Profile,
                    value = "undated"
                ),
                Evidence(
                    id = "archive-unavailable",
                    kind = EvidenceKind.Profile,
                    value = "https://archive.example/unavailable",
                    state = EvidenceState.Unavailable,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                HistoricalTimelinePanel(case)
            }
        }

        composeRule.onNodeWithText("Some records are undated").assertIsDisplayed()
        composeRule.onNodeWithText("2 evidence record(s)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Historical lookup unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("no date is inferred", substring = true).assertIsDisplayed()
    }

    @Test
    fun filtersExposeTextSelectedStateAndUndatedBreachExplanation() {
        val case = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "current",
                    kind = EvidenceKind.Profile,
                    value = "current",
                    observedAtEpochMillis = 2_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.DirectPublicProfile
                ),
                Evidence(
                    id = "undated-seed",
                    kind = EvidenceKind.Username,
                    value = "subject"
                )
            ),
            breachDigests = listOf(
                BreachDigest(email = "subject@example.com", breachCount = 1)
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                HistoricalTimelinePanel(case)
            }
        }

        composeRule.onNodeWithText("All · selected").assertIsDisplayed()
        composeRule.onNodeWithText("Some records are undated").assertIsDisplayed()
        composeRule.onNodeWithText("1 evidence record(s)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Breaches").performClick()
        composeRule.onNodeWithText("Breaches · selected").assertIsDisplayed()
        composeRule.onNodeWithText("Breach records are undated").assertIsDisplayed()
        composeRule.onNodeWithText("no parseable timestamp stored", substring = true).assertIsDisplayed()
    }

    @Test
    fun liveAndArchiveFiltersDoNotSurfaceUnrelatedUndatedBreachNotice() {
        val case = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = IdentityInput(fullName = "Authorized subject"),
            evidenceRecords = listOf(
                Evidence(
                    id = "current",
                    kind = EvidenceKind.Profile,
                    value = "current",
                    observedAtEpochMillis = 2_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.DirectPublicProfile
                ),
                Evidence(
                    id = "archive-unavailable",
                    kind = EvidenceKind.Profile,
                    value = "https://archive.example/unavailable",
                    state = EvidenceState.Unavailable,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            ),
            breachDigests = listOf(
                BreachDigest(email = "subject@example.com", breachCount = 1)
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                HistoricalTimelinePanel(case)
            }
        }

        composeRule.onNodeWithText("1 breach record(s)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Live").performClick()
        composeRule.onNodeWithText("Live · selected").assertIsDisplayed()
        composeRule.onNodeWithText("1 breach record(s)", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Historical lookup unavailable").assertDoesNotExist()
        composeRule.onNodeWithText("Archives").performClick()
        composeRule.onNodeWithText("Archives · selected").assertIsDisplayed()
        composeRule.onNodeWithText("1 breach record(s)", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Historical lookup unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Breaches").performClick()
        composeRule.onNodeWithText("Breaches · selected").assertIsDisplayed()
        composeRule.onNodeWithText("Historical lookup unavailable").assertDoesNotExist()
        composeRule.onNodeWithText("Media").performClick()
        composeRule.onNodeWithText("Media · selected").assertIsDisplayed()
        composeRule.onNodeWithText("No media attached to this case").assertIsDisplayed()
    }
}
