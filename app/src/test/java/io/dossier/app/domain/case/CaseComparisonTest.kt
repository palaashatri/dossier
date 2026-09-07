package io.dossier.app.domain.case

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.HistoricalAttributeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseComparisonTest {

    private fun f(type: FindingType, value: String, risk: RiskLevel, url: String? = null) = Finding(
        type = type,
        value = value,
        sourceUrl = url,
        evidenceSnippet = null,
        confidence = 0.9f,
        risk = risk,
        remediation = ""
    )

    private fun case(
        id: String,
        created: String,
        risk: RiskLevel,
        findings: List<Finding>,
        remediationRecords: List<RemediationRecord> = emptyList()
    ) = DossierCase(
        caseId = id,
        createdAt = created,
        subjectName = "X",
        input = IdentityInput(fullName = "X"),
        findings = findings,
        riskLevel = risk,
        remediationRecords = remediationRecords
    )

    @Test
    fun detectsAddedAndRemoved() {
        val before = case("a", "t1", RiskLevel.Low, listOf(f(FindingType.Email, "old@x.com", RiskLevel.High)))
        val after = case("b", "t2", RiskLevel.Low, listOf(f(FindingType.Email, "new@x.com", RiskLevel.High)))
        val diff = CaseComparison().compare(before, after)
        assertEquals(1, diff.added.size)
        assertEquals("new@x.com", diff.added.first().value)
        assertEquals(1, diff.removed.size)
        assertEquals("old@x.com", diff.removed.first().value)
    }

    @Test
    fun detectsRiskChangeAndDelta() {
        val before = case("a", "t1", RiskLevel.Low, listOf(f(FindingType.Phone, "123", RiskLevel.Low)))
        val after = case("b", "t2", RiskLevel.Critical, listOf(f(FindingType.Phone, "123", RiskLevel.Critical)))
        val diff = CaseComparison().compare(before, after)
        assertEquals(1, diff.changed.size)
        assertEquals(true, diff.changed.first().riskChanged)
        assertEquals(75, diff.riskDelta)
    }

    @Test
    fun noChangeIsExplicitlyTracked() {
        val findings = listOf(f(FindingType.Email, "a@b.com", RiskLevel.Medium))
        val before = case("a", "t1", RiskLevel.Medium, findings)
        val after = case("b", "t2", RiskLevel.Medium, findings)
        val diff = CaseComparison().compare(before, after)
        assertEquals(0, diff.added.size)
        assertEquals(0, diff.removed.size)
        assertEquals(0, diff.changed.size)
        assertEquals(1, diff.unchanged.size)
        assertEquals(0, diff.riskDelta)
    }

    @Test
    fun completedRemediationStillObservedIsNotVerified() {
        val finding = f(FindingType.Email, "visible@example.test", RiskLevel.High, "https://example.test/page")
        val remediation = RemediationRecord(
            remediationId = "r1",
            findingKey = "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}",
            action = "Request removal",
            status = RemediationStatus.Completed,
            createdAtUtc = "t1",
            updatedAtUtc = "t1"
        )
        val before = case("a", "t1", RiskLevel.High, listOf(finding), listOf(remediation))
        val after = case("b", "t2", RiskLevel.High, listOf(finding), listOf(remediation))

        val verification = CaseComparison().compare(before, after).remediationVerification.single()
        assertEquals(CaseComparison.RemediationVerificationState.StillObserved, verification.state)
    }

    @Test
    fun absentFindingAfterCompletedRemediationUsesNonOverclaimingState() {
        val finding = f(FindingType.Email, "visible@example.test", RiskLevel.High, "https://example.test/page")
        val remediation = RemediationRecord(
            remediationId = "r1",
            findingKey = "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}",
            action = "Request removal",
            status = RemediationStatus.Completed,
            createdAtUtc = "t1",
            updatedAtUtc = "t1"
        )
        val before = case("a", "t1", RiskLevel.High, listOf(finding), listOf(remediation))
        val after = case("b", "t2", RiskLevel.Low, emptyList(), listOf(remediation))

        val verification = CaseComparison().compare(before, after).remediationVerification.single()
        assertEquals(CaseComparison.RemediationVerificationState.NotObservedInLatestScan, verification.state)
        assertEquals(null, verification.verificationScanId)
        assertEquals(true, verification.explanation.contains("not proof", ignoreCase = true))
    }

    @Test
    fun remediationRecheckLinksExactCurrentEvidenceAndSuccessfulScan() {
        val finding = f(FindingType.Email, "visible@example.test", RiskLevel.High, "https://example.test/page")
        val remediation = RemediationRecord(
            remediationId = "r-evidence",
            findingKey = "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}",
            action = "Request removal",
            status = RemediationStatus.Completed,
            createdAtUtc = "t1",
            updatedAtUtc = "t1"
        )
        val before = case("a", "t1", RiskLevel.High, listOf(finding), listOf(remediation)).copy(
            evidenceRecords = listOf(
                Evidence(
                    id = "before-evidence",
                    kind = EvidenceKind.Email,
                    value = finding.value,
                    sourceUrl = finding.sourceUrl,
                    state = EvidenceState.Verified
                )
            ),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-before",
                    startedAtUtc = "t1",
                    completedAtUtc = "t1",
                    findingCount = 1
                )
            )
        )
        val after = case("b", "t2", RiskLevel.High, emptyList(), listOf(remediation)).copy(
            // The provider returned a new evidence ID, so matching must use
            // the full finding key rather than an ID or value-only shortcut.
            evidenceRecords = listOf(
                Evidence(
                    id = "after-evidence",
                    kind = EvidenceKind.Email,
                    value = finding.value,
                    sourceUrl = finding.sourceUrl,
                    state = EvidenceState.Observed
                )
            ),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-after",
                    startedAtUtc = "t2",
                    completedAtUtc = "t2",
                    findingCount = 0
                )
            )
        )

        val verification = CaseComparison().compare(before, after).remediationVerification.single()

        assertEquals(CaseComparison.RemediationVerificationState.StillObserved, verification.state)
        assertEquals("after-evidence", verification.observedEvidenceId)
        assertEquals("scan-after", verification.verificationScanId)
    }

    @Test
    fun remediationRecheckDoesNotLinkDifferentSourceOrFailedScan() {
        val finding = f(FindingType.Email, "visible@example.test", RiskLevel.High, "https://example.test/page")
        val remediation = RemediationRecord(
            remediationId = "r-mismatch",
            findingKey = "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}",
            action = "Request removal",
            status = RemediationStatus.Completed,
            createdAtUtc = "t1",
            updatedAtUtc = "t1"
        )
        val before = case("a", "t1", RiskLevel.High, listOf(finding), listOf(remediation)).copy(
            evidenceRecords = listOf(
                Evidence(
                    id = "before-evidence",
                    kind = EvidenceKind.Email,
                    value = finding.value,
                    sourceUrl = finding.sourceUrl,
                    state = EvidenceState.Verified
                )
            )
        )
        val after = case("b", "t2", RiskLevel.Low, emptyList(), listOf(remediation)).copy(
            evidenceRecords = listOf(
                Evidence(
                    id = "different-source",
                    kind = EvidenceKind.Email,
                    value = finding.value,
                    sourceUrl = "https://other.example/page",
                    state = EvidenceState.Observed
                )
            ),
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-success-before-failure",
                    startedAtUtc = "t2",
                    completedAtUtc = "t2",
                    findingCount = 0
                ),
                CaseScanHistoryEntry(
                    scanId = "scan-failed",
                    startedAtUtc = "t3",
                    completedAtUtc = null,
                    failed = true,
                    findingCount = 0
                )
            )
        )

        val verification = CaseComparison().compare(before, after).remediationVerification.single()

        assertEquals(CaseComparison.RemediationVerificationState.NotRechecked, verification.state)
        assertEquals(null, verification.observedEvidenceId)
        assertEquals(null, verification.verificationScanId)
        assertTrue(verification.explanation.contains("did not complete", ignoreCase = true))
    }

    @Test
    fun remediationRecheckLinksNotObservedOutcomeOnlyToNewSuccessfulScan() {
        val finding = f(FindingType.Email, "visible@example.test", RiskLevel.High, "https://example.test/page")
        val remediation = RemediationRecord(
            remediationId = "r-scan",
            findingKey = "${finding.type.name}|${finding.value}|${finding.sourceUrl.orEmpty()}",
            action = "Request removal",
            status = RemediationStatus.Completed,
            createdAtUtc = "t1",
            updatedAtUtc = "t1"
        )
        val before = case("a", "t1", RiskLevel.High, listOf(finding), listOf(remediation)).copy(
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-before",
                    startedAtUtc = "t1",
                    completedAtUtc = "t1",
                    findingCount = 1
                )
            )
        )
        val after = case("b", "t2", RiskLevel.Low, emptyList(), listOf(remediation)).copy(
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "scan-before",
                    startedAtUtc = "t1",
                    completedAtUtc = "t1",
                    findingCount = 1
                ),
                CaseScanHistoryEntry(
                    scanId = "scan-after",
                    startedAtUtc = "t2",
                    completedAtUtc = "t2",
                    findingCount = 0
                )
            )
        )

        val verification = CaseComparison().compare(before, after).remediationVerification.single()

        assertEquals(CaseComparison.RemediationVerificationState.NotObservedInLatestScan, verification.state)
        assertEquals("scan-after", verification.verificationScanId)
        assertTrue(verification.explanation.contains("not proof", ignoreCase = true))
    }

    @Test
    fun countsProfileAndBreachDeltas() {
        val before = DossierCase(
            caseId = "a",
            createdAt = "t1",
            subjectName = "X",
            input = IdentityInput(fullName = "X"),
            profileResults = listOf(),
            breachDigests = listOf()
        )
        val after = DossierCase(
            caseId = "b",
            createdAt = "t2",
            subjectName = "X",
            input = IdentityInput(fullName = "X"),
            profileResults = listOf(
                io.dossier.app.domain.model.ProfileScanResult(
                    candidate = io.dossier.app.domain.model.UsernameCandidate(
                        "u",
                        io.dossier.app.domain.model.Platform.GitHub,
                        "https://github.com/u",
                        io.dossier.app.domain.model.UsernameMatchType.Exact,
                        1.0f
                    ),
                    exists = true,
                    httpStatus = 200,
                    displayName = "u",
                    bio = null,
                    profileImageUrl = null,
                    links = emptyList(),
                    extractedText = "",
                    findings = emptyList(),
                    confidenceSignals = emptyList(),
                    verified = true,
                    verificationStatus = "ok",
                    provenance = null
                )
            ),
            breachDigests = listOf(io.dossier.app.domain.model.BreachDigest("a@b.com", 1))
        )
        val diff = CaseComparison().compare(before, after)
        assertEquals(1, diff.profilesAdded)
        assertEquals(1, diff.breachesAdded)
    }

    @Test
    fun comparesHistoricalAttributesByCanonicalArchiveTarget() {
        val target = "https://social.example/profile"
        val before = DossierCase(
            caseId = "before",
            createdAt = "t1",
            subjectName = "X",
            input = IdentityInput(fullName = "X"),
            evidenceRecords = listOf(
                Evidence(
                    id = "old-name",
                    kind = EvidenceKind.PublicSearchEvidence,
                    attributeKind = HistoricalAttributeKind.DisplayName,
                    value = "Alice",
                    sourceUrl = "https://web.archive.org/web/20240101000000id_/$target",
                    observedAtEpochMillis = 1_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )
        val after = before.copy(
            caseId = "after",
            createdAt = "t2",
            evidenceRecords = listOf(
                Evidence(
                    id = "new-name",
                    kind = EvidenceKind.PublicSearchEvidence,
                    attributeKind = HistoricalAttributeKind.DisplayName,
                    value = "Alicia",
                    sourceUrl = "https://web.archive.org/web/20250101000000id_/$target",
                    observedAtEpochMillis = 2_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )

        val change = CaseComparison().compare(before, after).evidenceChanges.single()

        assertEquals(CaseComparison.EvidenceChangeKind.CHANGED, change.change)
        assertEquals("Alicia", change.after?.value)
        assertEquals(true, change.historical)
        assertTrue(change.key.startsWith("DisplayName|social.example/profile"))
        assertTrue(change.explanation.contains("timestamps"))
    }

    @Test
    fun unavailableLatestArchiveIsNotReportedAsRemoved() {
        val archiveUrl = "https://web.archive.org/web/20240101000000id_/https://social.example/profile"
        val before = DossierCase(
            caseId = "before",
            createdAt = "t1",
            subjectName = "X",
            input = IdentityInput(fullName = "X"),
            evidenceRecords = listOf(
                Evidence(
                    id = "archived-bio",
                    kind = EvidenceKind.PublicSearchEvidence,
                    attributeKind = HistoricalAttributeKind.Bio,
                    value = "Old bio",
                    sourceUrl = archiveUrl,
                    observedAtEpochMillis = 1_000L,
                    state = EvidenceState.Verified,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )
        val after = before.copy(
            caseId = "after",
            createdAt = "t2",
            evidenceRecords = listOf(
                Evidence(
                    id = "archive-unavailable",
                    kind = EvidenceKind.PublicSearchEvidence,
                    attributeKind = HistoricalAttributeKind.Bio,
                    value = archiveUrl,
                    sourceUrl = archiveUrl,
                    state = EvidenceState.Unavailable,
                    reliability = EvidenceReliability.ArchiveSnapshot,
                    historical = true
                )
            )
        )

        val change = CaseComparison().compare(before, after).evidenceChanges.single()

        assertEquals(CaseComparison.EvidenceChangeKind.UNAVAILABLE, change.change)
        assertTrue(change.explanation.contains("unavailable"))
        assertFalse(change.explanation.contains("removed", ignoreCase = true))
    }

    @Test
    fun missingSourceContextIsExcludedFromEvidenceDiff() {
        val before = case(
            "before",
            "t1",
            RiskLevel.Low,
            emptyList()
        ).copy(
            evidenceRecords = listOf(
                Evidence(
                    id = "undated",
                    kind = EvidenceKind.Username,
                    value = "alice"
                )
            )
        )
        val after = before.copy(caseId = "after", createdAt = "t2")

        assertTrue(CaseComparison().compare(before, after).evidenceChanges.isEmpty())
    }
}
