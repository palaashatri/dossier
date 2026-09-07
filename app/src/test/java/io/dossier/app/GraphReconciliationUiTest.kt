package io.dossier.app

import io.dossier.app.domain.graph.GraphEvidenceReconciliationReport
import io.dossier.app.ui.screens.graphReconciliationUiSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphReconciliationUiTest {

    @Test
    fun unavailableSnapshotMakesNoConsistencyClaim() {
        val summary = graphReconciliationUiSummary(null)

        assertEquals("Unavailable", summary.statusLabel)
        assertFalse(summary.hasDiagnosticIssues)
        assertTrue(summary.detail.contains("no consistency claim", ignoreCase = true))
    }

    @Test
    fun consistentReportSurfacesBoundedCounts() {
        val summary = graphReconciliationUiSummary(
            GraphEvidenceReconciliationReport(
                matchedRelationships = 3,
                missingGraphEdges = 0,
                extraGraphEdges = 0,
                conflictingEvidence = 0,
                ambiguousRelationships = 0,
                diagnostics = emptyList()
            )
        )

        assertEquals("Consistent", summary.statusLabel)
        assertFalse(summary.hasDiagnosticIssues)
        assertTrue(summary.detail.contains("Matched canonical relationships: 3"))
        assertTrue(summary.detail.contains("Missing graph edges: 0"))
    }

    @Test
    fun inconsistentReportSurfacesIssuesAndTruncationWithoutClaimingMutation() {
        val summary = graphReconciliationUiSummary(
            GraphEvidenceReconciliationReport(
                matchedRelationships = 1,
                missingGraphEdges = 2,
                extraGraphEdges = 1,
                conflictingEvidence = 1,
                ambiguousRelationships = 0,
                diagnostics = emptyList(),
                truncatedGraphEdges = 4,
                danglingGraphEntityEvidenceIds = 2
            )
        )

        assertEquals("Review needed", summary.statusLabel)
        assertTrue(summary.hasDiagnosticIssues)
        assertTrue(summary.detail.contains("Missing graph edges: 2"))
        assertTrue(summary.detail.contains("Truncated graph edges: 4"))
        assertTrue(summary.detail.contains("Dangling graph-entity evidence references: 2"))
    }
}
