package io.dossier.app.domain.scanner

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.CaseTimelineBuilder
import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceRelationshipPolicy
import io.dossier.app.domain.evidence.EvidenceRuntimeCache
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionRestoreTest {

    @After
    fun clearRuntimeEvidence() {
        EvidenceRuntimeCache.clear()
        ScanSession.cancelScan()
    }

    @Test
    fun restoreFromCaseRehydratesBoundedDeduplicatedEvidence() {
        val duplicate = Evidence(
            id = "duplicate",
            kind = EvidenceKind.Profile,
            value = "first",
            observedAtEpochMillis = 1_000L
        )
        val records = buildList {
            add(duplicate)
            add(duplicate.copy(value = "later"))
            addAll(
                List(EvidenceRuntimeCache.MAX_CASE_EVIDENCE + 1) { index ->
                    Evidence(
                        id = "record-$index",
                        kind = EvidenceKind.Username,
                        value = "user-$index"
                    )
                }
            )
        }
        val dossierCase = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Restored subject",
            input = IdentityInput(fullName = "Restored subject"),
            evidenceRecords = records
        )

        ScanSession.restoreFromCase(dossierCase)

        val restored = EvidenceRuntimeCache.collection.value.evidence
        assertEquals(EvidenceRuntimeCache.MAX_CASE_EVIDENCE, restored.size)
        assertSame(duplicate, restored.first())
        assertEquals(
            records.distinctBy { it.id }
                .take(EvidenceRuntimeCache.MAX_CASE_EVIDENCE),
            restored
        )
    }

    @Test
    fun verifiedProfileAndFindingEvidenceReachTheRestoredTimelineSnapshot() {
        val profileUrl = "https://social.example/subject"
        val finding = Finding(
            type = FindingType.Profile,
            value = profileUrl,
            sourceUrl = profileUrl,
            evidenceSnippet = "Verified public profile",
            confidence = 0.92f,
            risk = RiskLevel.Medium,
            remediation = "Review the public profile"
        )
        val profile = ProfileScanResult(
            candidate = UsernameCandidate(
                username = "subject",
                platform = Platform.Website,
                url = profileUrl,
                matchType = UsernameMatchType.Exact,
                confidence = 0.92f,
                providerId = "social-example"
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Authorized subject",
            bio = "",
            links = emptyList(),
            extractedText = "",
            findings = listOf(finding),
            confidenceSignals = listOf("direct profile verification"),
            verified = true,
            verificationStatus = "verified"
        )
        val input = IdentityInput(fullName = "Authorized subject", primaryUsername = "subject")

        val snapshot = ScanSession.buildEvidenceSnapshot(
            input = input,
            profileResults = listOf(profile),
            pluginCollection = EvidenceCollection(),
            findings = listOf(finding),
            retrievedAtEpochMillis = 123_000L
        )
        assertTrue(ScanSession.typedSeedAdmission.value.admittedSeeds.any {
            it.kind == TypedSeedKind.Url && it.evidenceIds.isNotEmpty()
        })
        assertTrue(ScanSession.typedSeedAdmission.value.admittedSeeds.any {
            it.kind == TypedSeedKind.Username
        })
        assertTrue(ScanSession.typedSeedAdmission.value.isExecutionAvailable)
        EvidenceRuntimeCache.replace(snapshot)
        val case = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = input,
            evidenceRecords = snapshot.evidence
        )

        ScanSession.restoreFromCase(case)

        val restored = EvidenceRuntimeCache.collection.value.evidence
        val profileEvidence = restored.single { it.id == "profile:$profileUrl" }
        assertEquals(io.dossier.app.domain.evidence.EvidenceState.Verified, profileEvidence.state)
        assertEquals(EvidenceReliability.DirectPublicProfile, profileEvidence.reliability)
        assertEquals(123_000L, profileEvidence.retrievedAtEpochMillis)
        assertTrue(restored.any { it.id == EvidenceIdPolicy.findingId(finding) })
        assertEquals(123_000L, restored.single { it.id == EvidenceIdPolicy.findingId(finding) }.retrievedAtEpochMillis)
        assertNull(restored.single { it.id == "seed:username:subject" }.retrievedAtEpochMillis)
        assertEquals(restored, ScanSession.buildCase()?.evidenceRecords)
        assertTrue(CaseTimelineBuilder.build(case).isNotEmpty())
        assertTrue(CaseTimelineBuilder.presentation(case).availability.undatedEvidenceCount >= 1)
    }

    @Test
    fun buildEvidenceSnapshotMergesDuplicateRelationshipProvenance() {
        val snapshot = ScanSession.buildEvidenceSnapshot(
            input = IdentityInput(fullName = "Authorized subject"),
            profileResults = emptyList(),
            pluginCollection = EvidenceCollection(
                relationships = listOf(
                    EvidenceRelationship(
                        fromValue = "Alice",
                        toValue = "Profile",
                        relation = "MENTIONS",
                        evidence = "first assertion",
                        evidenceIds = listOf("evidence-a")
                    ),
                    EvidenceRelationship(
                        fromValue = " alice ",
                        toValue = " profile ",
                        relation = "mentions",
                        evidence = "second assertion",
                        evidenceIds = listOf("evidence-b")
                    )
                )
            ),
            findings = emptyList()
        )

        val relationship = snapshot.relationships.single()
        assertEquals("first assertion", relationship.evidence)
        assertEquals(listOf("evidence-a", "evidence-b"), relationship.evidenceIds)
    }

    @Test
    fun buildEvidenceSnapshotPersistsExactLegacyRelationshipProvenance() {
        val snapshotUrl = "https://web.archive.org/web/20240102030405id_/https://example.test/profile"
        val archivedEvidence = Evidence(
            id = "wayback:snapshot-1",
            kind = EvidenceKind.PublicSearchEvidence,
            value = snapshotUrl,
            sourceUrl = snapshotUrl,
            state = io.dossier.app.domain.evidence.EvidenceState.Verified
        )

        val snapshot = ScanSession.buildEvidenceSnapshot(
            input = IdentityInput(fullName = "Authorized subject"),
            profileResults = emptyList(),
            pluginCollection = EvidenceCollection(
                evidence = listOf(archivedEvidence),
                relationships = listOf(
                    EvidenceRelationship(
                        fromValue = "https://example.test/profile",
                        toValue = snapshotUrl,
                        relation = "ARCHIVED_AS",
                        evidence = "legacy archive assertion"
                    )
                )
            ),
            findings = emptyList()
        )

        assertEquals(listOf(archivedEvidence), snapshot.evidence.filter { it.id == archivedEvidence.id })
        assertEquals(listOf(archivedEvidence.id), snapshot.relationships.single().evidenceIds)
    }

    @Test
    fun buildEvidenceSnapshotLeavesAmbiguousExactRelationshipIdless() {
        val sourceUrl = "https://web.archive.org/web/20240102030405id_/https://example.test/profile"
        val records = listOf(
            Evidence(
                id = "wayback:snapshot-1",
                kind = EvidenceKind.PublicSearchEvidence,
                value = sourceUrl,
                sourceUrl = sourceUrl
            ),
            Evidence(
                id = "wayback:attribute-1",
                kind = EvidenceKind.Profile,
                value = "Archived name",
                sourceUrl = sourceUrl
            )
        )

        val snapshot = ScanSession.buildEvidenceSnapshot(
            input = IdentityInput(fullName = "Authorized subject"),
            profileResults = emptyList(),
            pluginCollection = EvidenceCollection(
                evidence = records,
                relationships = listOf(
                    EvidenceRelationship(
                        fromValue = "https://example.test/profile",
                        toValue = sourceUrl,
                        relation = "ARCHIVED_AS",
                        evidence = "legacy archive assertion"
                    )
                )
            ),
            findings = emptyList()
        )

        assertTrue(snapshot.relationships.single().evidenceIds.isEmpty())
        assertEquals(records, snapshot.evidence.filter { it.id.startsWith("wayback:") })
    }

    @Test
    fun buildAndRestorePreserveCanonicalRelationshipEvidence() {
        val input = IdentityInput(fullName = "Authorized subject")
        val snapshot = ScanSession.buildEvidenceSnapshot(
            input = input,
            profileResults = emptyList(),
            pluginCollection = EvidenceCollection(
                relationships = listOf(
                    EvidenceRelationship(
                        fromValue = "Subject",
                        toValue = "https://example.test/profile",
                        relation = "LINKS_TO",
                        evidence = "verified profile link",
                        evidenceIds = listOf("ev:Profile:https://example.test/profile:https://example.test/profile")
                    )
                )
            ),
            findings = emptyList(),
            retrievedAtEpochMillis = 123_000L
        )
        val expectedRelationships = snapshot.relationships
        val case = DossierCase(
            createdAt = "2026-08-24 12:00",
            subjectName = "Authorized subject",
            input = input,
            evidenceRecords = snapshot.evidence,
            evidenceRelationships = expectedRelationships
        )

        ScanSession.restoreFromCase(case)

        assertEquals(expectedRelationships, EvidenceRuntimeCache.collection.value.relationships)
        assertEquals(expectedRelationships, ScanSession.buildCase()?.evidenceRelationships)
        assertTrue(
            expectedRelationships.single().evidenceIds.all { it.startsWith("ev2:") }
        )
    }

    @Test
    fun runtimeRelationshipAssertionsRemainBounded() {
        val overflowingIds = EvidenceRelationship(
            fromValue = "Subject",
            toValue = "Profile",
            relation = "LINKS_TO",
            evidenceIds = List(EvidenceRelationshipPolicy.MAX_EVIDENCE_IDS_PER_RELATIONSHIP + 1) {
                "evidence-$it"
            }
        )
        val overflowingRelationships = List(EvidenceRelationshipPolicy.MAX_RELATIONSHIPS + 1) {
            EvidenceRelationship(
                fromValue = "Subject-$it",
                toValue = "Profile-$it",
                relation = "LINKS_TO",
                evidenceIds = listOf("evidence-$it")
            )
        }

        EvidenceRuntimeCache.replace(EvidenceCollection(relationships = listOf(overflowingIds)))
        assertEquals(
            EvidenceRelationshipPolicy.MAX_EVIDENCE_IDS_PER_RELATIONSHIP,
            EvidenceRuntimeCache.collection.value.relationships.single().evidenceIds.size
        )

        EvidenceRuntimeCache.replace(EvidenceCollection(relationships = overflowingRelationships))
        assertEquals(
            EvidenceRelationshipPolicy.MAX_RELATIONSHIPS,
            EvidenceRuntimeCache.collection.value.relationships.size
        )
    }

    @Test
    fun restoreFromCaseRetainsCompletedHistoryForTheActiveTimelineSnapshot() {
        val input = IdentityInput(fullName = "History Subject")
        val entry = CaseScanHistoryEntry(
            scanId = "scan-restored",
            startedAtUtc = "2026-08-24T12:00:00Z",
            completedAtUtc = "2026-08-24T12:04:00Z",
            mode = ScanMode.Standard,
            directProfileProviderCount = 18,
            profileResultCount = 6,
            findingCount = 2
        )
        val case = DossierCase(
            createdAt = "2026-08-24 12:04",
            subjectName = "History Subject",
            input = input,
            scanHistory = listOf(entry)
        )

        ScanSession.restoreFromCase(case)

        assertEquals(listOf(entry), ScanSession.scanHistory.value)
        assertEquals(listOf(entry), ScanSession.buildCase()?.scanHistory)
        assertTrue(
            CaseTimelineBuilder.build(ScanSession.buildCase()!!)
                .any { it.evidenceId == "scan:scan-restored:start" }
        )
    }

    @Test
    fun schedulingANewBackgroundScanClearsPriorSubjectEvidence() {
        EvidenceRuntimeCache.replace(
            EvidenceCollection(
                evidence = listOf(
                    Evidence(
                        id = "previous-subject",
                        kind = EvidenceKind.Profile,
                        value = "https://old.example/profile"
                    )
                )
            )
        )

        ScanSession.markBackgroundScheduled(
            input = IdentityInput(fullName = "New authorized subject"),
            deepResearch = false
        )

        assertTrue(EvidenceRuntimeCache.collection.value.evidence.isEmpty())
    }
}
