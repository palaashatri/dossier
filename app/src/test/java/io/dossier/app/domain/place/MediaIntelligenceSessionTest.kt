package io.dossier.app.domain.place

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import io.dossier.app.domain.scanner.ScanSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIntelligenceSessionTest {

    @After
    fun clearSession() {
        MediaIntelligenceSession.clear()
        ScanSession.cancelScan()
    }

    @Test
    fun matchingBindingAllowsMediaAndMismatchedInputIsEmpty() {
        val target = IdentityInput(fullName = "Alice Target", primaryUsername = "alice")
        val foreign = IdentityInput(fullName = "Bob Foreign", primaryUsername = "bob")

        val token = MediaIntelligenceSession.bindTo(target)
        assertTrue(MediaIntelligenceSession.recordImage(token, sampleImage()))

        assertFalse(MediaIntelligenceSession.snapshotFor(target).isEmpty)
        assertTrue(MediaIntelligenceSession.snapshotFor(foreign).isEmpty)
    }

    @Test
    fun unboundMediaCannotPopulateAnyCaseAndNewScanClearsSameSubjectMedia() {
        val target = IdentityInput(fullName = "Alice Target", primaryUsername = "alice")

        assertFalse(MediaIntelligenceSession.recordImage("unbound-token", sampleImage()))
        assertTrue(MediaIntelligenceSession.snapshotFor(target).isEmpty)

        val token = MediaIntelligenceSession.bindTo(target)
        assertTrue(MediaIntelligenceSession.recordImage(token, sampleImage()))
        MediaIntelligenceSession.beginFor(target)
        assertTrue(MediaIntelligenceSession.snapshotFor(target).isEmpty)
    }

    @Test
    fun staleLookupTokenCannotAttachResultsToReplacementSubject() {
        val first = IdentityInput(fullName = "First Subject", primaryUsername = "first")
        val replacement = IdentityInput(fullName = "Replacement Subject", primaryUsername = "replacement")

        val staleToken = MediaIntelligenceSession.bindTo(first)
        MediaIntelligenceSession.beginFor(replacement)

        assertFalse(MediaIntelligenceSession.recordImage(staleToken, sampleImage()))
        assertTrue(MediaIntelligenceSession.snapshotFor(replacement).isEmpty)
    }

    @Test
    fun scopedSnapshotRejectsStaleTokenAfterSameSeedReplacement() {
        val target = IdentityInput(fullName = "Same Subject", primaryUsername = "same")

        val staleToken = MediaIntelligenceSession.beginFor(target)
        assertTrue(MediaIntelligenceSession.recordImage(staleToken, sampleImage()))
        val replacementToken = MediaIntelligenceSession.beginFor(target)

        assertTrue(MediaIntelligenceSession.snapshotFor(target, staleToken).isEmpty)
        assertTrue(MediaIntelligenceSession.snapshotFor(target, replacementToken).isEmpty)
    }

    @Test
    fun inputFingerprintIncludesSelfieUri() {
        val first = IdentityInput(
            fullName = "Same Subject",
            primaryUsername = "same",
            selfieUri = "content://example/first-photo"
        )
        val second = first.copy(selfieUri = "content://example/second-photo")
        val token = MediaIntelligenceSession.bindTo(first)
        assertTrue(MediaIntelligenceSession.recordImage(token, sampleImage()))

        assertTrue(MediaIntelligenceSession.snapshotFor(second).isEmpty)
        assertTrue(MediaIntelligenceSession.snapshotFor(second, token).isEmpty)
    }

    @Test
    fun inputFingerprintCanonicalizesOnlyMediaUriSchemeAndHost() {
        val canonical = IdentityInput(
            fullName = "Same Subject",
            primaryUsername = "same",
            selfieUri = "content://example.test/Photo?Token=AbC#Frag"
        )
        val schemeAndHostCase = canonical.copy(
            selfieUri = "CONTENT://EXAMPLE.TEST/Photo?Token=AbC#Frag"
        )
        val pathCase = canonical.copy(
            selfieUri = "content://example.test/photo?Token=AbC#Frag"
        )
        val queryCase = canonical.copy(
            selfieUri = "content://example.test/Photo?token=AbC#Frag"
        )
        val fragmentCase = canonical.copy(
            selfieUri = "content://example.test/Photo?Token=AbC#frag"
        )
        val token = MediaIntelligenceSession.bindTo(canonical)
        assertTrue(MediaIntelligenceSession.recordImage(token, sampleImage()))

        assertFalse(MediaIntelligenceSession.snapshotFor(schemeAndHostCase).isEmpty)
        assertTrue(MediaIntelligenceSession.snapshotFor(pathCase).isEmpty)
        assertTrue(MediaIntelligenceSession.snapshotFor(queryCase).isEmpty)
        assertTrue(MediaIntelligenceSession.snapshotFor(fragmentCase).isEmpty)
    }

    @Test
    fun cancellationInvalidatesScopedSnapshotWithoutDiscardingPartialResults() {
        val target = IdentityInput(fullName = "Cancelled Subject", primaryUsername = "cancelled")

        val token = MediaIntelligenceSession.beginFor(target)
        assertTrue(MediaIntelligenceSession.recordImage(token, sampleImage()))
        assertFalse(MediaIntelligenceSession.snapshotFor(target, token).isEmpty)

        ScanSession.cancelScan()

        assertTrue(MediaIntelligenceSession.snapshotFor(target, token).isEmpty)
        assertFalse(MediaIntelligenceSession.snapshotFor(target).isEmpty)
    }

    @Test
    fun restoreForRehydratesOnlyTheBoundInputAfterProcessDeath() {
        val target = IdentityInput(fullName = "Alice Target", primaryUsername = "alice")
        val foreign = IdentityInput(fullName = "Bob Foreign", primaryUsername = "bob")
        val restored = MediaIntelligenceSnapshot(imageResults = listOf(sampleImage()))

        MediaIntelligenceSession.restoreFor(target, restored)

        assertFalse(MediaIntelligenceSession.snapshotFor(target).isEmpty)
        assertTrue(MediaIntelligenceSession.snapshotFor(foreign).isEmpty)
    }

    @Test
    fun matchingMediaReachesActiveCaseAndRestoresAfterProcessDeath() {
        val target = IdentityInput(fullName = "Alice Target", primaryUsername = "alice")

        ScanSession.markBackgroundScheduled(target, deepResearch = false)
        val token = MediaIntelligenceSession.bindTo(target)
        assertTrue(MediaIntelligenceSession.recordImage(token, sampleImage()))
        val built = requireNotNull(ScanSession.buildCase())

        assertFalse(built.mediaIntelligence.isEmpty)

        val foreign = IdentityInput(fullName = "Foreign Subject", primaryUsername = "foreign")
        val foreignToken = MediaIntelligenceSession.bindTo(foreign)
        MediaIntelligenceSession.recordImage(foreignToken, sampleImage())
        assertTrue(requireNotNull(ScanSession.buildCase()).mediaIntelligence.isEmpty)

        ScanSession.restoreFromCase(built)

        assertFalse(requireNotNull(ScanSession.buildCase()).mediaIntelligence.isEmpty)
    }

    @Test
    fun verifiedProfileAvatarsAreAutomaticallyBoundAndRemainIndexedObservations() {
        val target = IdentityInput(fullName = "Alice Target", primaryUsername = "alice")
        val foreign = IdentityInput(fullName = "Foreign Subject", primaryUsername = "foreign")
        val token = MediaIntelligenceSession.beginFor(target)

        assertTrue(
            MediaIntelligenceSession.recordVerifiedProfileAvatars(
                token,
                target,
                listOf(
                    ProfileScanResult(
                        candidate = UsernameCandidate(
                            username = "alice",
                            platform = Platform.GitHub,
                            url = "https://github.com/alice",
                            matchType = UsernameMatchType.Exact,
                            confidence = 0.9f
                        ),
                        exists = true,
                        httpStatus = 200,
                        displayName = "Alice",
                        bio = null,
                        profileImageUrl = "https://avatars.example.test/alice.jpg",
                        links = emptyList(),
                        extractedText = "Alice",
                        findings = emptyList(),
                        confidenceSignals = listOf("direct"),
                        verified = true,
                        verificationStatus = "Verified"
                    )
                )
            )
        )

        val observation = MediaIntelligenceSession.snapshotFor(target).imageResults.single()
        assertEquals(1, observation.visualCandidates.size)
        assertEquals(
            ReverseImageLookupResult.ImageCandidateState.Indexed,
            observation.visualCandidates.single().state
        )
        assertTrue(observation.visualMatches.isEmpty())
        assertTrue(observation.visualClusters.isEmpty())
        assertTrue(observation.visualSearchNote.orEmpty().contains("no local image comparison"))
        assertTrue(
            observation.visualCandidates.single().accountLinkages.single().evidenceIds
                .contains("profile:https://github.com/alice")
        )

        assertFalse(
            MediaIntelligenceSession.recordVerifiedProfileAvatars(
                token,
                foreign,
                emptyList()
            )
        )
    }

    private fun sampleImage() = ReverseImageLookupResult(
        gps = null,
        extractedText = "sample",
        labels = emptyList(),
        faceDetected = false,
        faceWarning = null,
        resolvedLocation = null,
        mapsUrl = null,
        webEvidence = emptyList()
    )
}
