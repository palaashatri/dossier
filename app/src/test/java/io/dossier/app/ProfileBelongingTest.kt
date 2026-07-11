package io.dossier.app

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.scanner.ProfileScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the name-only hallucination: previously a profile was
 * attributed to the user purely because its candidate *username* happened to
 * contain both name parts — even when the rendered page mentioned neither the
 * name nor any other identity signal. That manufactured false-positive matches
 * against random strangers' accounts.
 *
 * The fix: belonging now ALWAYS requires rendered-page corroboration. The slug
 * overlap is, at most, a tiny confidence signal applied only after the page is
 * confirmed.
 */
class ProfileBelongingTest {

    private val input = IdentityInput(
        fullName = "Jane Doe",
        emails = emptyList(),
        phones = emptyList(),
        aliases = emptyList(),
        locations = emptyList(),
        organizations = emptyList(),
        usernames = emptyList(),
        primaryUsername = null,
        profileUrls = emptyList()
    )

    private fun belongs(
        candidateUsername: String,
        candidateUrl: String,
        extractedText: String,
        displayName: String? = null,
        input: IdentityInput = this.input
    ): Boolean = ProfileScanner.belongsToUserPure(
        candidateUsername = candidateUsername,
        candidateUrl = candidateUrl,
        platform = Platform.GitHub,
        extractedText = extractedText,
        displayName = displayName,
        input = input
    )

    @Test
    fun nameEmbeddingSlug_onVerifiedPage_isAccepted() {
        // The candidate handle "janedoe" embeds BOTH full name parts (jane + doe),
        // so such a handle uniquely derives from this person's name. A verified-existing
        // page under it is a plausible match — even if the rendered text only shows the
        // handle (e.g. a sparse GitHub/Reddit profile that doesn't surface the real name).
        // This is what lets name-only scans find more than just the one platform that
        // happens to render the display name in text.
        val sparsePage = "janedoe has 12 repositories. Follow their work."
        val result = belongs(
            candidateUsername = "janedoe",
            candidateUrl = "https://github.com/janedoe",
            extractedText = sparsePage
        )
        assertTrue("Name-embedding slug on a verified page should be accepted", result)
    }

    @Test
    fun weakInitialVariantSlug_withoutCorroboration_isRejected() {
        // Initials-only variants (e.g. "jdoe" from "Jane Doe") are ambiguous —
        // many strangers share them. Without page-text corroboration they must NOT match.
        val unrelatedPage = "jdoe's profile — hobbyist photographer based in Oslo."
        val result = belongs(
            candidateUsername = "jdoe",
            candidateUrl = "https://github.com/jdoe",
            extractedText = unrelatedPage
        )
        assertFalse("Initial-style variant without corroboration must be rejected", result)
    }

    @Test
    fun singleNamePartSlug_withoutCorroboration_isRejected() {
        // A handle using only one name part (e.g. "doe") is too common to attribute
        // without page corroboration.
        val unrelatedPage = "doe's blog about mechanical keyboards and coffee."
        val result = belongs(
            candidateUsername = "doe",
            candidateUrl = "https://github.com/doe",
            extractedText = unrelatedPage
        )
        assertFalse("Single-name-part slug without corroboration must be rejected", result)
    }

    @Test
    fun dotVariantNameEmbeddingSlug_isAccepted() {
        // "jane.doe" also embeds both full name parts → plausible match.
        val page = "@jane.doe — posts about ML and systems."
        val result = belongs(
            candidateUsername = "jane.doe",
            candidateUrl = "https://www.instagram.com/jane.doe/",
            extractedText = page
        )
        assertTrue("Dot-variant name-embedding slug should be accepted", result)
    }

    @Test
    fun pageContainsFullName_isAccepted() {
        val page = "Jane Doe — building things on the internet. Repositories and projects."
        val result = belongs(
            candidateUsername = "some-other-handle",
            candidateUrl = "https://github.com/some-other-handle",
            extractedText = page
        )
        assertTrue("Full name in rendered page should be accepted", result)
    }

    @Test
    fun pageContainsBothNamePartsSeparately_isAccepted() {
        val page = "Avatar by Doe. Contributions from Jane. Open source work."
        val result = belongs(
            candidateUsername = "jdoe",
            candidateUrl = "https://github.com/jdoe",
            extractedText = page
        )
        assertTrue("Both name parts in page should be accepted", result)
    }

    @Test
    fun pageMentionsOnlyFirstName_andHandleDoesNotEmbedLastName_isRejected() {
        // A single name part in the page is too weak — many unrelated people share a
        // first name. And because this handle ("jane") does NOT embed the last name,
        // it can't be attributed via the name-embedding rule either.
        val page = "Hi, I'm Jane! Random blog about cooking and travel."
        val result = belongs(
            candidateUsername = "jane",
            candidateUrl = "https://github.com/jane",
            extractedText = page
        )
        assertFalse("First name alone must not be enough", result)
    }

    @Test
    fun userSuppliedUrl_isTrustedRegardlessOfPageContent() {
        // URLs the user explicitly supplies are trusted as their own.
        val inputWithUrl = input.copy(
            profileUrls = listOf("https://github.com/whatever-handle")
        )
        val page = "Some completely unrelated content with no name."
        val result = belongs(
            candidateUsername = "whatever-handle",
            candidateUrl = "https://github.com/whatever-handle",
            extractedText = page,
            input = inputWithUrl
        )
        assertTrue("User-supplied URLs should be trusted", result)
    }

    @Test
    fun singleWordNameWithNoOtherSignals_isRejected() {
        // Weak-identity guard: a bare single-word name can't distinguish the user
        // from any stranger, so only user-supplied URLs may be accepted.
        val weakInput = IdentityInput(fullName = "Jane")
        val page = "Jane's profile on GitHub with some repositories."
        val result = belongs(
            candidateUsername = "jane",
            candidateUrl = "https://github.com/jane",
            extractedText = page,
            input = weakInput
        )
        assertFalse("Single-word name + no other signals must not auto-accept", result)
    }

    @Test
    fun matchingEmailInPage_isAccepted() {
        val inputWithEmail = input.copy(emails = listOf("jane@example.com"))
        val page = "Contact me at jane@example.com for collaborations."
        val result = belongs(
            candidateUsername = "janedoe",
            candidateUrl = "https://github.com/janedoe",
            extractedText = page,
            input = inputWithEmail
        )
        assertTrue("User-supplied email in page should be accepted", result)
    }

    @Test
    fun exactPrimaryUsername_onExistingPage_isAccepted() {
        val inputWithHandle = IdentityInput(
            fullName = "Jane",
            primaryUsername = "samplecaster"
        )
        val page = "samplecaster's stream schedule and VODs."
        val result = belongs(
            candidateUsername = "samplecaster",
            candidateUrl = "https://www.twitch.tv/samplecaster",
            extractedText = page,
            input = inputWithHandle
        )
        assertTrue("Exact primaryUsername match should attribute when page exists", result)
    }

    @Test
    fun exactUsernameInUsernamesList_isAccepted() {
        val inputWithHandle = IdentityInput(
            fullName = "",
            usernames = listOf("cool_hacker_42")
        )
        val page = "Projects by cool_hacker_42"
        val result = belongs(
            candidateUsername = "cool_hacker_42",
            candidateUrl = "https://github.com/cool_hacker_42",
            extractedText = page,
            input = inputWithHandle
        )
        assertTrue("Exact match against input.usernames should attribute", result)
    }

    @Test
    fun singleWordName_withEmailSignal_andNameOnPage_isAccepted() {
        val weakWithEmail = IdentityInput(fullName = "Jane", emails = listOf("jane@example.com"))
        val page = "Jane's profile on GitHub with some repositories."
        val result = belongs(
            candidateUsername = "otherhandle",
            candidateUrl = "https://github.com/otherhandle",
            extractedText = page,
            input = weakWithEmail
        )
        assertTrue("Single-word name + email present may accept name-on-page", result)
    }
}
