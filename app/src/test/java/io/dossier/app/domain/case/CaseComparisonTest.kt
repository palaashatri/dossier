package io.dossier.app.domain.case

import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import org.junit.Assert.assertEquals
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
        assertEquals(true, verification.explanation.contains("not proof", ignoreCase = true))
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
}
