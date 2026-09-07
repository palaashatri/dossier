package io.dossier.app.ui.screens

import io.dossier.app.data.ai.AiRemotePermission
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseComparisonScreenAiReanalysisTest {

    private fun savedCase(
        aiSummary: String? = null,
        aiSummaryNeedsRefresh: Boolean = false
    ) = DossierCase(
        caseId = "saved-case-ai-test",
        createdAt = "2026-08-24 00:00",
        subjectName = "Test subject",
        input = IdentityInput(fullName = "Test subject"),
        aiSummary = aiSummary,
        aiSummaryNeedsRefresh = aiSummaryNeedsRefresh
    )

    @Test
    fun actionIsVisibleOnlyForMissingOrStaleAnalysis() {
        assertTrue(savedCase(aiSummaryNeedsRefresh = true).let(::savedCaseAiActionVisible))
        assertTrue(savedCase(aiSummary = null).let(::savedCaseAiActionVisible))
        assertFalse(savedCase(aiSummary = "fresh", aiSummaryNeedsRefresh = false).let(::savedCaseAiActionVisible))
    }

    @Test
    fun remotePermissionDefaultsToDeniedWithoutUsableProvider() {
        assertEquals(AiRemotePermission.Denied, savedCaseAiRemotePermission(false))
        assertEquals(
            AiRemotePermission.AllowRedactedEvidence,
            savedCaseAiRemotePermission(true)
        )
    }
}
