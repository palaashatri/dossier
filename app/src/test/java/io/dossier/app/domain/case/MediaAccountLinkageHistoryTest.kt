package io.dossier.app.domain.case

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.place.MediaIntelligenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAccountLinkageHistoryTest {

    @Test
    fun historyGroupsRepeatedFingerprintAndRetainsReviewProvenance() {
        val before = mediaCase(
            caseId = "before",
            candidateId = "candidate-before",
            contentHash = "ABCDEF",
            basis = ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
            evidenceIds = listOf("profile-before"),
            linkedAt = 100L
        )
        val after = mediaCase(
            caseId = "after",
            candidateId = "candidate-after",
            contentHash = "abcdef",
            basis = ReverseImageLookupResult.ImageAccountLinkageBasis.UserReviewed,
            evidenceIds = listOf("review-after"),
            linkedAt = 200L
        )

        val entry = CaseComparison()
            .mediaAccountLinkageHistory(listOf(before, after))
            .single()

        assertEquals("https://example.test/alice", entry.accountUrl)
        assertEquals(ReverseImageLookupResult.ImageClusterType.ExactContent, entry.fingerprintType)
        assertEquals("abcdef", entry.fingerprint)
        assertEquals(2, entry.caseCount)
        assertEquals(
            listOf(
                ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
                ReverseImageLookupResult.ImageAccountLinkageBasis.UserReviewed
            ),
            entry.observations.map { it.basis }
        )
        assertEquals(listOf("profile-before"), entry.observations.first().evidenceIds)
        assertEquals(200L, entry.observations.last().linkedAtEpochMillis)
    }

    @Test
    fun historyDoesNotMergeMissingFingerprintOrDifferentAccounts() {
        val first = mediaCase(
            caseId = "first",
            candidateId = "same-candidate-id",
            contentHash = null,
            basis = ReverseImageLookupResult.ImageAccountLinkageBasis.UserReviewed,
            accountUrl = "https://example.test/alice"
        )
        val second = mediaCase(
            caseId = "second",
            candidateId = "same-candidate-id",
            contentHash = null,
            basis = ReverseImageLookupResult.ImageAccountLinkageBasis.UserReviewed,
            accountUrl = "https://example.test/bob"
        )

        val history = CaseComparison().mediaAccountLinkageHistory(listOf(first, second))

        assertEquals(2, history.size)
        assertTrue(history.all { it.fingerprint == null && it.caseCount == 1 })
        assertEquals(
            setOf("https://example.test/alice", "https://example.test/bob"),
            history.map { it.accountUrl }.toSet()
        )
    }

    private fun mediaCase(
        caseId: String,
        candidateId: String,
        contentHash: String?,
        basis: ReverseImageLookupResult.ImageAccountLinkageBasis,
        evidenceIds: List<String> = emptyList(),
        linkedAt: Long? = 1L,
        accountUrl: String = "https://example.test/alice"
    ) = DossierCase(
        caseId = caseId,
        createdAt = "2026-08-25 00:00",
        subjectName = "Subject",
        input = IdentityInput(fullName = "Subject"),
        mediaIntelligence = MediaIntelligenceSnapshot(
            imageResults = listOf(
                ReverseImageLookupResult(
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
                            id = candidateId,
                            title = "candidate",
                            imageUrl = "https://images.example.test/$candidateId.jpg",
                            sourcePageUrl = "https://example.test/source",
                            source = "fixture",
                            acquisitionQuery = "fixture",
                            contentSha256 = contentHash,
                            accountLinkages = listOf(
                                ReverseImageLookupResult.ImageAccountLinkage(
                                    accountUrl = accountUrl,
                                    basis = basis,
                                    evidenceIds = evidenceIds,
                                    linkedAtEpochMillis = linkedAt
                                )
                            )
                        )
                    )
                )
            )
        )
    )
}
