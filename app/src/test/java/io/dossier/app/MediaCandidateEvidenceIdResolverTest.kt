package io.dossier.app

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.persistedLinkedProfileEvidenceId
import io.dossier.app.domain.model.ReverseImageLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaCandidateEvidenceIdResolverTest {

    @Test
    fun resolvesOnlyTheDeclaredExactVerifiedProfileEvidence() {
        val candidate = candidate(
            linkage = ReverseImageLookupResult.ImageAccountLinkage(
                accountUrl = ACCOUNT_URL,
                basis = ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
                evidenceIds = listOf(EVIDENCE_ID)
            )
        )

        assertEquals(
            EVIDENCE_ID,
            candidate.persistedLinkedProfileEvidenceId(
                listOf(
                    Evidence(
                        id = EVIDENCE_ID,
                        kind = EvidenceKind.Profile,
                        value = ACCOUNT_URL,
                        sourceUrl = ACCOUNT_URL
                    )
                )
            )
        )
    }

    @Test
    fun failsClosedForMissingAmbiguousOrMismatchedLinkage() {
        val exact = Evidence(
            id = EVIDENCE_ID,
            kind = EvidenceKind.Profile,
            value = ACCOUNT_URL,
            sourceUrl = ACCOUNT_URL
        )
        val candidate = candidate(
            linkage = ReverseImageLookupResult.ImageAccountLinkage(
                accountUrl = ACCOUNT_URL,
                basis = ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
                evidenceIds = listOf(EVIDENCE_ID)
            )
        )

        assertNull(candidate.persistedLinkedProfileEvidenceId(emptyList()))
        assertNull(
            candidate.persistedLinkedProfileEvidenceId(
                listOf(
                    exact,
                    exact.copy(id = "profile:other", value = ACCOUNT_URL, sourceUrl = ACCOUNT_URL)
                )
            )
        )
        assertNull(
            candidate.copy(
                accountLinkages = listOf(
                    candidate.accountLinkages.single().copy(evidenceIds = listOf("profile:wrong"))
                )
            ).persistedLinkedProfileEvidenceId(listOf(exact))
        )
        assertNull(
            candidate.copy(
                accountLinkages = listOf(
                    candidate.accountLinkages.single().copy(
                        basis = ReverseImageLookupResult.ImageAccountLinkageBasis.UserReviewed
                    )
                )
            ).persistedLinkedProfileEvidenceId(listOf(exact))
        )
    }

    @Test
    fun genericVisualCandidateHasNoCorrectionKey() {
        val candidate = candidate(linkage = null)
        assertNull(
            candidate.persistedLinkedProfileEvidenceId(
                listOf(
                    Evidence(
                        id = EVIDENCE_ID,
                        kind = EvidenceKind.Profile,
                        value = ACCOUNT_URL,
                        sourceUrl = ACCOUNT_URL
                    )
                )
            )
        )
    }

    private fun candidate(
        linkage: ReverseImageLookupResult.ImageAccountLinkage?
    ): ReverseImageLookupResult.ImageCandidateProvenance =
        ReverseImageLookupResult.ImageCandidateProvenance(
            id = "imgcandidate:example",
            title = "Public avatar",
            imageUrl = "https://cdn.example.test/avatar.jpg",
            sourcePageUrl = ACCOUNT_URL,
            source = "Dossier profile discovery",
            acquisitionQuery = "Previously discovered profile avatar",
            accountLinkages = linkage?.let(::listOf).orEmpty()
        )

    private companion object {
        const val ACCOUNT_URL = "https://example.test/alice"
        const val EVIDENCE_ID = "profile:https://example.test/alice"
    }
}
