package io.dossier.app.domain.place

import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.scanner.ScanSession
import org.junit.After
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
