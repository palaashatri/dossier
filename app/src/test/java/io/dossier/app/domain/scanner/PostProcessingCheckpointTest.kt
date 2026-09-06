package io.dossier.app.domain.scanner

import io.dossier.app.domain.analysis.IdentitySurfaceMap
import io.dossier.app.domain.analysis.OsintAnalysisBundle
import io.dossier.app.domain.analysis.PresenceState
import io.dossier.app.domain.analysis.SurfacePresence
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PostProcessingCheckpointCodecTest {

    @Test
    fun analysisRoundTripsOnlyWithinBoundedShape() {
        val analysis = sampleAnalysis()
        val encoded = PostProcessingCheckpointCodec.encode(analysis)

        assertNotNull(encoded)
        assertEquals(analysis, PostProcessingCheckpointCodec.decode(encoded!!))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= PostProcessingCheckpointCodec.MAX_ANALYSIS_BYTES)
    }

    @Test
    fun malformedOrOversizedAnalysisFailsClosed() {
        assertNull(PostProcessingCheckpointCodec.decode("not-json"))
        assertNull(
            PostProcessingCheckpointCodec.decode(
                "x".repeat(PostProcessingCheckpointCodec.MAX_ANALYSIS_BYTES + 1)
            )
        )
        val malformed = """{"identitySurface":{"entries":[{"platform":"x"}]}}"""
        assertNull(PostProcessingCheckpointCodec.decode(malformed))
    }

    @Test
    fun inputDigestChangesWhenPostProcessingInputsChange() {
        val input = IdentityInput(fullName = "Jane Doe", usernames = listOf("janedoe"))
        val evidence = EvidenceCollection(
            evidence = listOf(
                Evidence(
                    id = "e1",
                    kind = EvidenceKind.Profile,
                    value = "janedoe",
                    sourceUrl = "https://example.test/janedoe",
                    snippet = "public profile",
                    observedAtEpochMillis = 100L,
                    state = EvidenceState.Verified
                )
            ),
            relationships = listOf(
                EvidenceRelationship("janedoe", "friend", "MENTIONS")
            )
        )
        val profiles = listOf(profile("https://example.test/janedoe", verified = true))
        val original = PostProcessingCheckpointCodec.inputDigest(input, profiles, evidence, emptyList())
        val changed = PostProcessingCheckpointCodec.inputDigest(
            input,
            profiles.map { it.copy(verificationStatus = "changed") },
            evidence,
            emptyList()
        )

        assertTrue(PostProcessingCheckpointCodec.isValidDigest(original))
        assertNotEquals(original, changed)
    }

    private fun sampleAnalysis() = OsintAnalysisBundle(
        identitySurface = IdentitySurfaceMap(
            entries = listOf(
                SurfacePresence(
                    platform = "Example",
                    username = "janedoe",
                    url = "https://example.test/janedoe",
                    state = PresenceState.Exists,
                    confidence = 0.9,
                    reason = "Directly verified public profile"
                )
            ),
            confirmedCount = 1
        )
    )

    private fun profile(url: String, verified: Boolean) = ProfileScanResult(
        candidate = UsernameCandidate(
            username = "janedoe",
            platform = Platform.GitHub,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = 0.8f
        ),
        exists = true,
        httpStatus = 200,
        displayName = "Jane Doe",
        bio = null,
        links = emptyList(),
        extractedText = "",
        findings = emptyList(),
        confidenceSignals = emptyList(),
        verified = verified,
        verificationStatus = if (verified) "verified" else "candidate"
    )
}

class ScanResumeStorePostProcessingCheckpointTest {

    private val fixtures = mutableListOf<File>()

    @After
    fun cleanup() {
        fixtures.forEach(File::deleteRecursively)
    }

    @Test
    fun checkpointRoundTripsEncryptedAndRebindsToFreshOwner() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        assertTrue(store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE) is ResumeCheckpointWriteState.Saved)
        val checkpoint = checkpoint(saved.point, OWNER_ONE)

        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.PostProcessing,
                completed = true,
                output = ScanStageOutput(itemCount = 1, verifiedCount = 1),
                postProcessingCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Saved
        )
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint, loaded.point.postProcessingCheckpoint)
        assertTrue(ScanCheckpointStage.PostProcessing in loaded.point.completedCheckpointStages)

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
            .readText()
        assertFalse(record.contains("example.test"))

        assertTrue(store.bindCheckpointOwner(saved.point.requestId, OWNER_TWO) is ResumeCheckpointWriteState.Saved)
        val rebound = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint.copy(ownerId = OWNER_TWO), rebound.point.postProcessingCheckpoint)
    }

    @Test
    fun mismatchedBindingAndUnsafePayloadAreRejectedWithoutReplacingCheckpoint() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        val checkpoint = checkpoint(saved.point, OWNER_ONE)

        fun write(value: PostProcessingStageCheckpoint): ResumeCheckpointWriteState = store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.PostProcessing,
            completed = true,
            postProcessingCheckpoint = value
        )

        assertTrue(write(checkpoint.copy(ownerId = OWNER_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(requestId = ID_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(planFingerprint = "b".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(inputDigest = "A".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(analysisJson = "not-json")) is ResumeCheckpointWriteState.Invalid)
        assertNull((store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available).point.postProcessingCheckpoint)
    }

    @Test
    fun checkpointUsesRequestTtlAndDoesNotRestoreAfterExpiry() {
        val fixture = fixture()
        var now = 1_000L
        val store = store(fixture, nowMillis = { now })
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.PostProcessing,
                completed = true,
                postProcessingCheckpoint = checkpoint(saved.point, OWNER_ONE)
            ) is ResumeCheckpointWriteState.Saved
        )

        now = saved.point.expiresAtEpochMillis
        assertEquals(ResumeReadState.Expired, store.loadRequestDetailed(saved.point.requestId))
    }

    @Test
    fun tamperedEncryptedCheckpointFailsAuthentication() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.PostProcessing,
                completed = true,
                postProcessingCheckpoint = checkpoint(saved.point, OWNER_ONE)
            ) is ResumeCheckpointWriteState.Saved
        )

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val encoded = record.readText()
        val marker = "\"ciphertextBase64\":\""
        val start = encoded.indexOf(marker) + marker.length
        check(start >= marker.length)
        val tampered = encoded.toCharArray().also { chars ->
            chars[start] = if (chars[start] == 'A') 'B' else 'A'
        }.concatToString()
        record.writeText(tampered)

        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.AuthenticationFailed),
            store.loadRequestDetailed(saved.point.requestId)
        )
    }

    private fun checkpoint(point: ResumePoint, ownerId: String): PostProcessingStageCheckpoint {
        val analysis = PostProcessingCheckpointCodec.encode(
            OsintAnalysisBundle(
                identitySurface = IdentitySurfaceMap(
                    entries = listOf(
                        SurfacePresence(
                            platform = "Example",
                            username = "janedoe",
                            url = "https://example.test/janedoe",
                            state = PresenceState.Exists,
                            confidence = 0.9,
                            reason = "verified"
                        )
                    )
                )
            )
        )!!
        return PostProcessingStageCheckpoint(
            requestId = point.requestId,
            planFingerprint = point.planFingerprint!!,
            ownerId = ownerId,
            capturedAtEpochMillis = point.createdAtEpochMillis,
            inputDigest = "a".repeat(64),
            analysisJson = analysis
        )
    }

    private fun fixture(): Fixture {
        val root = File.createTempFile("post-processing-checkpoint", "").also {
            it.delete()
            it.mkdirs()
        }
        fixtures += root
        return Fixture(File(root, "records"), File(root, "legacy"))
    }

    private fun store(fixture: Fixture, nowMillis: () -> Long = { 1_000L }): ScanResumeStore =
        ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = PostProcessingTestCrypto(),
            nowMillis = nowMillis,
            idFactory = { ID_ONE }
        )

    private fun input() = IdentityInput(
        fullName = "Jane Doe",
        usernames = listOf("janedoe"),
        emails = listOf("jane@example.com")
    )

    private data class Fixture(val records: File, val legacy: File)

    private companion object {
        const val ID_ONE = "123e4567-e89b-12d3-a456-426614174000"
        const val ID_TWO = "123e4567-e89b-12d3-a456-426614174001"
        const val OWNER_ONE = "223e4567-e89b-42d3-a456-426614174000"
        const val OWNER_TWO = "223e4567-e89b-42d3-a456-426614174001"
    }
}

private class PostProcessingTestCrypto : ResumeCrypto {
    private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES")

    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
        allowKeyCreation: Boolean
    ): SealedResumePayload {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return SealedResumePayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            key,
            javax.crypto.spec.GCMParameterSpec(128, payload.iv)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(payload.ciphertext)
    }
}
