package io.dossier.app.domain.scanner

import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundScanWorkerHistoryTest {

    @Test
    fun workerSnapshotCarriesOnlyTheExactCompletedEntry() {
        val input = IdentityInput(fullName = "Snapshot Subject")
        val base = DossierCase(
            createdAt = "2026-08-24T00:00:00Z",
            subjectName = "Snapshot Subject",
            input = input
        )
        val completed = CaseScanHistoryEntry(
            scanId = "scan-exact",
            startedAtUtc = "2026-08-24T00:00:00Z",
            completedAtUtc = "2026-08-24T00:05:00Z",
            mode = ScanMode.Deep,
            directProfileProviderCount = 23,
            profileResultCount = 11,
            findingCount = 4,
            breachRecordCount = 1,
            graphEntityCount = 8,
            graphRelationshipCount = 9
        )

        val attached = BackgroundScanWorker.attachLatestScanHistory(base, completed)

        assertEquals(listOf(completed), attached.scanHistory)
        assertTrue(
            BackgroundScanWorker.attachLatestScanHistory(base, null).scanHistory.isEmpty()
        )
    }
}
