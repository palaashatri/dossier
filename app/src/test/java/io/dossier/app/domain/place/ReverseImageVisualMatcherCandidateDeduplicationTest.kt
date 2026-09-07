package io.dossier.app.domain.place

import io.dossier.app.data.web.ReverseImageCandidateSearchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReverseImageVisualMatcherCandidateDeduplicationTest {

    @Test
    fun sameImageUrlFromDistinctSourcePagesRemainSeparatePreservingOrder() {
        val sharedAvatarUrl = "https://cdn.example.test/shared-default-avatar.png"
        val candidate1 = candidate(
            title = "Alice Profile",
            imageUrl = sharedAvatarUrl,
            sourcePageUrl = "https://github.com/alice",
            source = "Dossier profile discovery"
        )
        val candidate2 = candidate(
            title = "Bob Profile",
            imageUrl = sharedAvatarUrl,
            sourcePageUrl = "https://x.com/bob",
            source = "Dossier profile discovery"
        )
        val candidate3 = candidate(
            title = "Charlie Indexed",
            imageUrl = sharedAvatarUrl,
            sourcePageUrl = "https://linkedin.com/in/charlie",
            source = "Public web index"
        )

        val deduplicated = deduplicateReverseImageCandidates(
            listOf(candidate1, candidate2, candidate3)
        )

        assertEquals(3, deduplicated.size)
        assertEquals("https://github.com/alice", deduplicated[0].sourcePageUrl)
        assertEquals("https://x.com/bob", deduplicated[1].sourcePageUrl)
        assertEquals("https://linkedin.com/in/charlie", deduplicated[2].sourcePageUrl)
        assertEquals(listOf(candidate1, candidate2, candidate3), deduplicated)
    }

    @Test
    fun exactDuplicatePairCoalescesDeterministicallyPreservingFirst() {
        val candidateFirst = candidate(
            title = "Direct Profile Avatar",
            imageUrl = "https://cdn.example.test/avatar.jpg",
            sourcePageUrl = "https://example.test/alice",
            source = "Dossier profile discovery",
            query = "Profile discovery"
        )
        val candidateSecond = candidate(
            title = "Indexed Copy of Alice",
            imageUrl = "https://cdn.example.test/avatar.jpg",
            sourcePageUrl = "https://example.test/alice",
            source = "Web index",
            query = "Alice OCR match"
        )

        val deduplicated = deduplicateReverseImageCandidates(
            listOf(candidateFirst, candidateSecond)
        )

        assertEquals(1, deduplicated.size)
        assertSame(candidateFirst, deduplicated.single())
        assertEquals("Direct Profile Avatar", deduplicated.single().title)
        assertEquals("Dossier profile discovery", deduplicated.single().source)
    }

    @Test
    fun canonicalizationHandlesUrlFragmentsCaseAndTrailingSlashesSafely() {
        val original = candidate(
            title = "Original",
            imageUrl = "https://cdn.example.test/avatar.jpg",
            sourcePageUrl = "https://example.test/alice"
        )
        val upperCaseSchemeHost = candidate(
            title = "Uppercase Scheme and Host",
            imageUrl = "HTTPS://CDN.EXAMPLE.TEST/avatar.jpg",
            sourcePageUrl = "HTTPS://EXAMPLE.TEST/alice"
        )
        val trailingSlashAndFragment = candidate(
            title = "Trailing Slash and Fragment",
            imageUrl = "https://cdn.example.test/avatar.jpg#preview",
            sourcePageUrl = "https://example.test/alice/#profile-header"
        )
        val whitespacePadded = candidate(
            title = "Whitespace Padded",
            imageUrl = "  https://cdn.example.test/avatar.jpg  ",
            sourcePageUrl = "  https://example.test/alice/  "
        )

        val deduplicated = deduplicateReverseImageCandidates(
            listOf(original, upperCaseSchemeHost, trailingSlashAndFragment, whitespacePadded)
        )

        assertEquals(1, deduplicated.size)
        assertSame(original, deduplicated.single())
        assertEquals("Original", deduplicated.single().title)
    }

    @Test
    fun distinctImagesOnSameSourcePageRemainSeparate() {
        val candidate1 = candidate(
            title = "Header photo",
            imageUrl = "https://cdn.example.test/header.jpg",
            sourcePageUrl = "https://example.test/alice"
        )
        val candidate2 = candidate(
            title = "Avatar photo",
            imageUrl = "https://cdn.example.test/avatar.jpg",
            sourcePageUrl = "https://example.test/alice"
        )

        val deduplicated = deduplicateReverseImageCandidates(listOf(candidate1, candidate2))

        assertEquals(2, deduplicated.size)
        assertEquals("https://cdn.example.test/header.jpg", deduplicated[0].imageUrl)
        assertEquals("https://cdn.example.test/avatar.jpg", deduplicated[1].imageUrl)
    }

    @Test
    fun canonicalMediaUrlNormalizesSafely() {
        assertEquals(
            "https://cdn.example.test/avatar.jpg",
            canonicalMediaUrl("HTTPS://CDN.EXAMPLE.TEST/avatar.jpg#tag")
        )
        assertEquals(
            "https://example.test/alice",
            canonicalMediaUrl("https://example.test/alice/")
        )
        assertEquals(
            "https://example.test",
            canonicalMediaUrl("https://example.test/")
        )
        assertEquals(
            "https://example.test",
            canonicalMediaUrl("https://example.test")
        )
        assertEquals(
            "https://example.test/alice?view=full",
            canonicalMediaUrl("https://example.test/alice/?view=full#anchor")
        )
        assertEquals(
            "https://example.test/alice",
            canonicalMediaUrl("   https://example.test/alice   ")
        )
        assertEquals(
            "invalid url with spaces",
            canonicalMediaUrl("   invalid url with spaces#frag   ")
        )
    }

    private fun candidate(
        title: String,
        imageUrl: String,
        sourcePageUrl: String,
        source: String = "test-source",
        query: String = "test-query",
        thumbnailUrl: String? = null
    ) = ReverseImageCandidateSearchService.Candidate(
        title = title,
        imageUrl = imageUrl,
        thumbnailUrl = thumbnailUrl,
        sourcePageUrl = sourcePageUrl,
        query = query,
        source = source
    )
}
