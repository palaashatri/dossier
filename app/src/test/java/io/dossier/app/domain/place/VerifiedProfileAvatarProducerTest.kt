package io.dossier.app.domain.place

import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedProfileAvatarProducerTest {

    @Test
    fun producesIndexedCandidateWithExactVerifiedProfileProvenance() {
        val verified = profile(
            accountUrl = "https://www.example.test/alice",
            avatarUrl = "https://cdn.example.test/alice.jpg",
            exists = true,
            verified = true
        )

        val candidate = VerifiedProfileAvatarProducer.produce(listOf(verified)).single()

        assertEquals("https://cdn.example.test/alice.jpg", candidate.imageUrl)
        assertEquals(verified.candidate.url, candidate.sourcePageUrl)
        assertEquals(ReverseImageLookupResult.ImageCandidateState.Indexed, candidate.state)
        assertEquals("Dossier profile discovery", candidate.source)
        assertEquals("Previously discovered profile avatar", candidate.acquisitionQuery)
        val linkage = candidate.accountLinkages.single()
        assertEquals(
            ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
            linkage.basis
        )
        assertEquals(listOf("profile:${verified.candidate.url}"), linkage.evidenceIds)
        assertTrue(candidate.comparisonScore == null)
        assertTrue(candidate.contentSha256 == null)
    }

    @Test
    fun excludesUnverifiedMissingAndUnsafeAvatarSourcesWithoutGuessingUrls() {
        val unverified = profile(
            accountUrl = "https://example.test/unverified",
            avatarUrl = "https://cdn.example.test/unverified.jpg",
            exists = true,
            verified = false
        )
        val missingAvatar = profile(
            accountUrl = "https://example.test/missing",
            avatarUrl = null,
            exists = true,
            verified = true,
            links = listOf("https://cdn.example.test/guessed.jpg")
        )
        val unsafeAvatar = profile(
            accountUrl = "https://example.test/unsafe",
            avatarUrl = "https://user:secret@cdn.example.test/avatar.jpg",
            exists = true,
            verified = true
        )
        val missingProfile = profile(
            accountUrl = "https://example.test/not-found",
            avatarUrl = "https://cdn.example.test/not-found.jpg",
            exists = false,
            verified = true
        )

        assertTrue(
            VerifiedProfileAvatarProducer
                .produce(listOf(unverified, missingAvatar, unsafeAvatar, missingProfile))
                .isEmpty()
        )
    }

    @Test
    fun deduplicatesAndBoundsAutomaticallyAcquiredAvatars() {
        val duplicate = profile(
            accountUrl = "https://example.test/duplicate",
            avatarUrl = "https://cdn.example.test/duplicate.jpg",
            exists = true,
            verified = true
        )
        val unique = (0 until 70).map { index ->
            profile(
                accountUrl = "https://example.test/user-$index",
                avatarUrl = "https://cdn.example.test/avatar-$index.jpg",
                exists = true,
                verified = true
            )
        }

        val produced = VerifiedProfileAvatarProducer.produce(listOf(duplicate, duplicate) + unique)

        assertEquals(64, produced.size)
        assertEquals(produced.size, produced.map { it.id }.toSet().size)
        assertFalse(produced.zipWithNext().any { (left, right) -> left.id == right.id })
    }

    private fun profile(
        accountUrl: String,
        avatarUrl: String?,
        exists: Boolean,
        verified: Boolean,
        links: List<String> = emptyList()
    ) = ProfileScanResult(
        candidate = UsernameCandidate(
            username = accountUrl.substringAfterLast('/').ifBlank { "user" },
            platform = Platform.Website,
            url = accountUrl,
            matchType = UsernameMatchType.Exact,
            confidence = 0.95f
        ),
        exists = exists,
        httpStatus = if (exists) 200 else 404,
        displayName = "Verified user",
        bio = null,
        profileImageUrl = avatarUrl,
        links = links,
        extractedText = "Verified user",
        findings = emptyList(),
        confidenceSignals = listOf("direct public page"),
        verified = verified,
        verificationStatus = if (verified) "Verified" else "Review"
    )
}
