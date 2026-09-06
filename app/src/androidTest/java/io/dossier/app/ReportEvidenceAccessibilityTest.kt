package io.dossier.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.toEvidence
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import io.dossier.app.domain.scanner.ScanSession
import io.dossier.app.ui.screens.ReportScreen
import io.dossier.app.ui.theme.DossierTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportEvidenceAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun clearSession() {
        ScanSession.purgeSession(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun allReportTabsAreReachableAndReadable() {
        ScanSession.restoreFromCase(
            DossierCase(
                createdAt = "2026-08-25 00:00",
                subjectName = "Authorized subject",
                input = IdentityInput(fullName = "Authorized subject")
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                ReportScreen(
                    onReset = {},
                    onNavigateToBrowser = {}
                )
            }
        }

        val tabs = listOf("Overview", "Evidence", "Timeline", "Connections", "Actions")
        tabs.forEach { tab ->
            composeRule.onNode(hasText(tab) and hasClickAction())
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
        }
    }

    @Test
    fun findingSourceExposesMeaningfulButtonSemanticsAndOpensSource() {
        val source = "https://evidence.example.test/profile"
        ScanSession.restoreFromCase(
            DossierCase(
                createdAt = "2026-08-25 00:00",
                subjectName = "Authorized subject",
                input = IdentityInput(fullName = "Authorized subject"),
                findings = listOf(
                    Finding(
                        type = FindingType.Profile,
                        value = "profile evidence",
                        sourceUrl = source,
                        evidenceSnippet = "Direct public profile evidence",
                        confidence = 0.8f,
                        risk = RiskLevel.Medium,
                        remediation = "Review the source manually."
                    )
                ),
                evidenceRecords = listOf(
                    Finding(
                        type = FindingType.Profile,
                        value = "profile evidence",
                        sourceUrl = source,
                        evidenceSnippet = "Direct public profile evidence",
                        confidence = 0.8f,
                        risk = RiskLevel.Medium,
                        remediation = "Review the source manually."
                    ).toEvidence()
                )
            )
        )
        var openedSource: String? = null

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                ReportScreen(
                    onReset = {},
                    onNavigateToBrowser = { openedSource = it }
                )
            }
        }

        composeRule.onNode(hasText("Evidence") and hasClickAction()).performClick()
        val sourceDescription = "Open evidence source $source"
        val sourceNode = composeRule
            .onNodeWithContentDescription(sourceDescription)
            .fetchSemanticsNode()
        assertEquals(Role.Button, sourceNode.config[SemanticsProperties.Role])

        composeRule.onNodeWithContentDescription(sourceDescription).performClick()
        composeRule.runOnIdle {
            assertEquals(source, openedSource)
        }
    }

    @Test
    fun draftCorrectionUpdatesEffectiveReportAndRetainsRawFindingUntilSave() {
        val source = "https://evidence.example.test/profile"
        val finding = Finding(
            type = FindingType.Profile,
            value = "profile evidence",
            sourceUrl = source,
            evidenceSnippet = "Direct public profile evidence",
            confidence = 0.8f,
            risk = RiskLevel.Medium,
            remediation = "Review the source manually."
        )
        ScanSession.restoreFromCase(
            DossierCase(
                createdAt = "2026-08-25 00:00",
                subjectName = "Authorized subject",
                input = IdentityInput(fullName = "Authorized subject"),
                findings = listOf(finding),
                evidenceRecords = listOf(finding.toEvidence())
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                ReportScreen(
                    onReset = {},
                    onNavigateToBrowser = {}
                )
            }
        }
        composeRule.onNode(hasText("Evidence") and hasClickAction()).performClick()

        val reject = composeRule.onNodeWithContentDescription("Reject evidence correction")
        assertEquals(
            "Not selected",
            reject.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
        reject.performClick()
        composeRule.waitForIdle()

        assertEquals(UserCorrectionDecision.ThisIsNotMe, ScanSession.userCorrections.value.single().decision)
        assertEquals(listOf(finding), ScanSession.findings.value)
        assertEquals(
            "Selected",
            reject.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
        assertTrue(
            composeRule.onAllNodesWithText("Draft evidence decision · not saved")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithText("Draft decision: rejected by you", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
    }

    @Test
    fun profileCorrectionUsesExactPersistedIdAndRetainsRawProfileUntilSave() {
        val source = "https://profiles.example.test/alice"
        val profile = ProfileScanResult(
            candidate = UsernameCandidate(
                username = "alice",
                platform = Platform.Website,
                url = source,
                matchType = UsernameMatchType.Exact,
                confidence = 0.8f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Alice Example",
            bio = null,
            links = emptyList(),
            extractedText = "",
            findings = emptyList(),
            confidenceSignals = listOf("direct public profile"),
            verified = true
        )
        val persistedEvidenceId = "legacy-profile-record-42"

        ScanSession.restoreFromCase(
            DossierCase(
                createdAt = "2026-08-25 00:00",
                subjectName = "Authorized subject",
                input = IdentityInput(fullName = "Authorized subject"),
                profileResults = listOf(profile),
                evidenceRecords = listOf(
                    Evidence(
                        id = persistedEvidenceId,
                        kind = EvidenceKind.Profile,
                        value = source,
                        sourceUrl = source
                    )
                )
            )
        )

        composeRule.setContent {
            DossierTheme(darkTheme = false) {
                ReportScreen(
                    onReset = {},
                    onNavigateToBrowser = {}
                )
            }
        }
        composeRule.onNode(hasText("Evidence") and hasClickAction()).performClick()

        val profileSource = composeRule
            .onNodeWithContentDescription("Open profile $source")
            .fetchSemanticsNode()
        assertEquals(Role.Button, profileSource.config[SemanticsProperties.Role])

        val reject = composeRule.onNodeWithContentDescription("Reject profile correction")
        assertEquals(
            "Not selected",
            reject.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
        reject.performClick()
        composeRule.waitForIdle()

        assertEquals(UserCorrectionDecision.ThisIsNotMe, ScanSession.userCorrections.value.single().decision)
        assertEquals(persistedEvidenceId, ScanSession.userCorrections.value.single().evidenceId)
        assertEquals(listOf(profile), ScanSession.profileScanResults.value)
        assertEquals(
            "Selected",
            reject.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
        assertTrue(
            composeRule.onAllNodesWithText("Draft decision: rejected by you", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
    }
}
