package io.dossier.app.case

import io.dossier.app.domain.case.AuthorizedScope
import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.RemediationRecord
import io.dossier.app.domain.case.RemediationStatus
import io.dossier.app.domain.case.UserCorrection
import io.dossier.app.domain.case.UserCorrectionDecision
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseLifecycleSchemaTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun legacyV2JsonLoadsWithSafeLifecycleDefaults() {
        val legacy = """
            {
              "schemaVersion": 2,
              "caseId": "legacy-case",
              "createdAt": "2026-08-01T00:00:00Z",
              "subjectName": "Synthetic Subject",
              "input": {"fullName":"Synthetic Subject"}
            }
        """.trimIndent()

        val decoded = json.decodeFromString<DossierCase>(legacy)
        assertEquals(2, decoded.schemaVersion)
        assertEquals(AuthorizedScope.SelfAudit, decoded.authorizedScope)
        assertTrue(decoded.scanHistory.isEmpty())
        assertTrue(decoded.userCorrections.isEmpty())
        assertTrue(decoded.remediationRecords.isEmpty())
        assertTrue(decoded.exports.isEmpty())
    }

    @Test
    fun lifecycleStateRoundTripsWithoutLosingStatus() {
        val dossierCase = DossierCase(
            createdAt = "2026-08-08T00:00:00Z",
            subjectName = "Synthetic Subject",
            input = IdentityInput(fullName = "Synthetic Subject"),
            authorizedScope = AuthorizedScope.ExplicitConsent,
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-1",
                    startedAtUtc = "2026-08-08T00:00:00Z",
                    completedAtUtc = "2026-08-08T00:02:00Z",
                    mode = ScanMode.Deep,
                    directProfileProviderCount = 61,
                    findingCount = 4
                )
            ),
            userCorrections = listOf(
                UserCorrection(
                    evidenceId = "E1",
                    decision = UserCorrectionDecision.ThisIsNotMe,
                    createdAtUtc = "2026-08-08T00:03:00Z"
                )
            ),
            remediationRecords = listOf(
                RemediationRecord(
                    findingKey = "Email|sample@example.test|https://example.test",
                    action = "Request removal",
                    status = RemediationStatus.Submitted,
                    createdAtUtc = "2026-08-08T00:04:00Z",
                    updatedAtUtc = "2026-08-08T00:04:00Z"
                )
            )
        )

        val decoded = json.decodeFromString<DossierCase>(json.encodeToString(dossierCase))
        assertEquals(DossierCase.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(ScanMode.Deep, decoded.scanHistory.single().mode)
        assertEquals(UserCorrectionDecision.ThisIsNotMe, decoded.userCorrections.single().decision)
        assertEquals(RemediationStatus.Submitted, decoded.remediationRecords.single().status)
    }
}
