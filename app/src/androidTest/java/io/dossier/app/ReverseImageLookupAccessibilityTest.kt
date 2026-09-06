package io.dossier.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.ui.screens.ReverseImageLookupScreen
import io.dossier.app.ui.screens.RenderVisualProvenance
import io.dossier.app.ui.theme.DossierCardShape
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReverseImageLookupAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun videoPickerExposesAnActionDescriptionAndButtonRole() {
        composeRule.setContent {
            ReverseImageLookupScreen(onNavigateToBrowser = {})
        }

        val picker = composeRule
            .onNodeWithContentDescription("Select a video for analysis.")
            .fetchSemanticsNode()

        assertEquals(
            Role.Button,
            picker.config[SemanticsProperties.Role]
        )
    }

    @Test
    fun verifiedMediaLinkageExposesOnlyTheExactEvidenceCorrectionControl() {
        var decision: UserCorrectionDecision? = null
        composeRule.setContent {
            RenderVisualProvenance(
                result = ReverseImageLookupResult(
                    gps = null,
                    extractedText = null,
                    labels = emptyList(),
                    faceDetected = false,
                    faceWarning = null,
                    resolvedLocation = null,
                    mapsUrl = null,
                    webEvidence = emptyList(),
                    visualCandidates = listOf(
                        ReverseImageLookupResult.ImageCandidateProvenance(
                            id = "imgcandidate:accessibility",
                            title = "Verified avatar",
                            imageUrl = "https://cdn.example.test/avatar.jpg",
                            sourcePageUrl = ACCOUNT_URL,
                            source = "Dossier profile discovery",
                            acquisitionQuery = "Previously discovered profile avatar",
                            accountLinkages = listOf(
                                ReverseImageLookupResult.ImageAccountLinkage(
                                    accountUrl = ACCOUNT_URL,
                                    basis = ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
                                    evidenceIds = listOf(EVIDENCE_ID)
                                )
                            )
                        )
                    )
                ),
                cardShape = DossierCardShape,
                onNavigateToBrowser = {},
                evidenceRecords = listOf(
                    Evidence(
                        id = EVIDENCE_ID,
                        kind = EvidenceKind.Profile,
                        value = ACCOUNT_URL,
                        sourceUrl = ACCOUNT_URL
                    )
                ),
                onDraftCorrection = { evidenceId, selected ->
                    assertEquals(EVIDENCE_ID, evidenceId)
                    decision = selected
                }
            )
        }

        composeRule
            .onNodeWithContentDescription("Reject linked profile evidence correction")
            .assertIsDisplayed()
            .performClick()
        assertEquals(UserCorrectionDecision.ThisIsNotMe, decision)
        val sourceNode = composeRule
            .onNodeWithContentDescription("Open public candidate source Verified avatar")
            .fetchSemanticsNode()
        assertEquals(Role.Button, sourceNode.config[SemanticsProperties.Role])
        composeRule.onNodeWithText("This control applies only to the exact linked profile observation; it does not establish image ownership. Raw media and profile evidence remain retained until encrypted case save.").assertIsDisplayed()
    }

    private companion object {
        const val ACCOUNT_URL = "https://example.test/alice"
        const val EVIDENCE_ID = "profile:https://example.test/alice"
    }
}
