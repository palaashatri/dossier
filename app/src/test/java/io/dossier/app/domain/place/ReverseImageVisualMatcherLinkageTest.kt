package io.dossier.app.domain.place

import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import io.dossier.app.domain.model.ReverseImageLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseImageVisualMatcherLinkageTest {

    @Test
    fun verifiedExistingProfileProducesOnlyExplicitProfileEvidenceLinkage() {
        val profile = profile(
            url = "https://www.example.test/alice/?from=scan#profile",
            exists = true,
            verified = true
        )

        val linkage = verifiedProfileMediaLinkage(profile)

        requireNotNull(linkage)
        assertEquals(profile.candidate.url, linkage.accountUrl)
        assertEquals(
            ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
            linkage.basis
        )
        assertEquals(listOf("profile:${profile.candidate.url}"), linkage.evidenceIds)
        assertNull(linkage.linkedAtEpochMillis)
    }

    @Test
    fun unverifiedMissingOrNonHttpProfilesNeverProduceAccountLinkage() {
        assertNull(verifiedProfileMediaLinkage(profile(exists = true, verified = false)))
        assertNull(verifiedProfileMediaLinkage(profile(exists = false, verified = true)))
        assertNull(
            verifiedProfileMediaLinkage(
                profile(url = "ftp://example.test/alice", exists = true, verified = true)
            )
        )
    }

    @Test
    fun linkageDoesNotDependOnVisualScoresOrGuessedProfileUrls() {
        val result = profile(
            url = "https://example.test/alice",
            exists = true,
            verified = true
        )

        val linkage = requireNotNull(verifiedProfileMediaLinkage(result))

        assertTrue(linkage.evidenceIds.single().startsWith("profile:https://"))
        assertTrue(linkage.accountUrl == result.candidate.url)
    }

    private fun profile(
        url: String = "https://example.test/alice",
        exists: Boolean,
        verified: Boolean
    ) = ProfileScanResult(
        candidate = UsernameCandidate(
            username = "alice",
            platform = Platform.GitHub,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = 0.9f
        ),
        exists = exists,
        httpStatus = if (exists) 200 else 404,
        displayName = "Alice",
        bio = null,
        links = emptyList(),
        extractedText = "Alice",
        findings = emptyList(),
        confidenceSignals = listOf("direct public page"),
        verified = verified,
        verificationStatus = if (verified) "Verified" else "Review"
    )
}
