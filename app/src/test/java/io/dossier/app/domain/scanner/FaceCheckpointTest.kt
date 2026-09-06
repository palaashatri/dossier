package io.dossier.app.domain.scanner

import io.dossier.app.domain.model.FaceComparisonQuality
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
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
import java.util.Base64

class FaceCheckpointCodecTest {

    @Test
    fun roundTripsBoundedMatchesWithoutImageBytes() {
        val matches = sampleMatches()

        val encoded = FaceCheckpointCodec.encode(matches)

        assertNotNull(encoded)
        assertTrue(encoded!!.toByteArray(Charsets.UTF_8).size <= FaceCheckpointCodec.MAX_FACE_BYTES)
        assertFalse(encoded.contains("selfie-bytes"))
        assertFalse(encoded.contains("image-bytes"))
        assertEquals(matches, FaceCheckpointCodec.decode(encoded))
    }

    @Test
    fun malformedOversizedUnsafeAndNonFiniteOutputFailsClosed() {
        assertNull(FaceCheckpointCodec.decode("not-json"))
        assertNull(FaceCheckpointCodec.decode("x".repeat(FaceCheckpointCodec.MAX_FACE_BYTES + 1)))
        assertNull(FaceCheckpointCodec.decode("{\"codecVersion\":\"wrong\",\"matches\":[]}"))
        assertNull(
            FaceCheckpointCodec.encode(
                listOf(sampleMatches().single().copy(profileUrl = "file:///private/image"))
            )
        )
        assertNull(FaceCheckpointCodec.encode(listOf(sampleMatches().single().copy(warning = "token=secret"))))
        assertNull(FaceCheckpointCodec.encode(listOf(sampleMatches().single().copy(similarityScore = Float.NaN))))
        assertNull(
            FaceCheckpointCodec.encode(
                listOf(
                    sampleMatches().single().copy(
                        provenance = sampleMatches().single().provenance.copy(
                            profileQuality = FaceComparisonQuality(
                                accepted = true,
                                reason = "out of range",
                                brightness = 1_000f
                            )
                        )
                    )
                )
            )
        )
        assertNull(
            FaceCheckpointCodec.encode(
                listOf(
                    sampleMatches().single().copy(
                        provenance = sampleMatches().single().provenance.copy(
                            selfieQuality = FaceComparisonQuality(
                                accepted = true,
                                reason = "not finite",
                                sharpness = Float.POSITIVE_INFINITY
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun digestChangesForIdentityProfileAndModelCommitment() {
        val input = sampleInput()
        val profiles = sampleProfiles()
        val model = FaceCheckpointCodec.modelCommitment(false, null)
        val digest = FaceCheckpointCodec.inputDigest(input, profiles, model)

        assertTrue(FaceCheckpointCodec.isValidDigest(digest))
        assertNotEquals(
            digest,
            FaceCheckpointCodec.inputDigest(input.copy(fullName = "Different Name"), profiles, model)
        )
        assertNotEquals(
            digest,
            FaceCheckpointCodec.inputDigest(input, profiles.map { it.copy(displayName = "Different") }, model)
        )
        assertNotEquals(
            digest,
            FaceCheckpointCodec.inputDigest(input, profiles, FaceCheckpointCodec.modelCommitment(true, null))
        )
        assertNotEquals(
            digest,
            FaceCheckpointCodec.inputDigest(input, profiles, FaceCheckpointCodec.modelCommitment(false, "a".repeat(64)))
        )
    }

    @Test
    fun modelCommitmentRaceDiscardsStaleFaceCheckpoint() {
        val before = FaceCheckpointCodec.modelCommitment(false, null)
        val after = FaceCheckpointCodec.modelCommitment(true, null)
        val stable = ScanSession.buildFaceCheckpointIfModelStable(
            requestId = "123e4567-e89b-12d3-a456-426614174000",
            ownerId = "223e4567-e89b-42d3-a456-426614174000",
            planFingerprint = "a".repeat(64),
            modelCommitmentBefore = before,
            modelCommitmentAfter = after,
            input = sampleInput(),
            profileResults = sampleProfiles(),
            matches = sampleMatches()
        )
        assertNull(stable)
        assertNotNull(
            ScanSession.buildFaceCheckpointIfModelStable(
                requestId = "123e4567-e89b-12d3-a456-426614174000",
                ownerId = "223e4567-e89b-42d3-a456-426614174000",
                planFingerprint = "a".repeat(64),
                modelCommitmentBefore = before,
                modelCommitmentAfter = before,
                input = sampleInput(),
                profileResults = sampleProfiles(),
                matches = sampleMatches()
            )
        )
    }

    @Test
    fun invalidDigestShapeIsRejected() {
        assertFalse(FaceCheckpointCodec.isValidDigest("A".repeat(64)))
        assertFalse(FaceCheckpointCodec.isValidDigest("a".repeat(63)))
        assertFalse(FaceCheckpointCodec.isValidDigest("z".repeat(64)))
    }

    private fun sampleMatches() = listOf(
        FaceConsistencyMatch(
            profileUrl = "https://example.test/alice",
            similarityScore = 0.87f,
            warning = "High visual similarity — confirm account ownership"
        )
    )

    private fun sampleInput() = IdentityInput(
        fullName = "Jane Doe",
        usernames = listOf("janedoe"),
        emails = listOf("jane@example.com"),
        selfieUri = "content://selfie-bytes"
    )

    private fun sampleProfiles() = listOf(
        ProfileScanResult(
            candidate = UsernameCandidate(
                username = "janedoe",
                platform = Platform.GitHub,
                url = "https://example.test/alice",
                matchType = UsernameMatchType.Exact,
                confidence = 0.9f,
                providerId = "github"
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Jane Doe",
            bio = "public bio",
            profileImageUrl = "https://example.test/alice.png",
            links = listOf("https://example.test"),
            extractedText = "public profile",
            findings = emptyList(),
            confidenceSignals = listOf("exact username"),
            verified = true,
            verificationStatus = "verified",
            provenance = "provider",
            providerId = "github"
        )
    )
}

class ScanResumeStoreFaceCheckpointTest {

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
                stage = ScanCheckpointStage.ComparingFaceConsistency,
                completed = true,
                output = ScanStageOutput(itemCount = 1),
                faceCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Saved
        )
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint, loaded.point.faceCheckpoint)
        assertTrue(ScanCheckpointStage.ComparingFaceConsistency in loaded.point.completedCheckpointStages)

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
            .readText()
        assertFalse(record.contains("https://example.test/alice"))
        assertFalse(record.contains("selfie-bytes"))

        assertTrue(store.bindCheckpointOwner(saved.point.requestId, OWNER_TWO) is ResumeCheckpointWriteState.Saved)
        val rebound = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint.copy(ownerId = OWNER_TWO), rebound.point.faceCheckpoint)
    }

    @Test
    fun checkpointRejectsIncompleteAndMismatchedBindingsWithoutPersistingOutput() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        val checkpoint = checkpoint(saved.point, OWNER_ONE)

        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.ComparingFaceConsistency,
                completed = false,
                faceCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Invalid
        )

        fun write(value: FaceConsistencyStageCheckpoint): ResumeCheckpointWriteState = store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.ComparingFaceConsistency,
            completed = true,
            faceCheckpoint = value
        )

        assertTrue(write(checkpoint.copy(ownerId = OWNER_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(requestId = ID_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(planFingerprint = "b".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(inputDigest = "A".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(matchesJson = "not-json")) is ResumeCheckpointWriteState.Invalid)
        assertNull(
            (store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available)
                .point.faceCheckpoint
        )
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
                stage = ScanCheckpointStage.ComparingFaceConsistency,
                completed = true,
                faceCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
                stage = ScanCheckpointStage.ComparingFaceConsistency,
                completed = true,
                faceCheckpoint = checkpoint(saved.point, OWNER_ONE)
            ) is ResumeCheckpointWriteState.Saved
        )

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val encoded = record.readText()
        val marker = "\"ciphertextBase64\":\""
        val start = encoded.indexOf(marker) + marker.length
        check(start >= marker.length)
        record.writeText(encoded.toCharArray().also { chars ->
            chars[start] = if (chars[start] == 'A') 'B' else 'A'
        }.concatToString())

        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.AuthenticationFailed),
            store.loadRequestDetailed(saved.point.requestId)
        )
    }

    @Test
    fun authenticatedPayloadWithoutCompletedStageMarkerIsNotReusable() {
        val fixture = fixture()
        val crypto = FaceCheckpointTestCrypto()
        val store = store(fixture, crypto = crypto)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.ComparingFaceConsistency,
                completed = true,
                faceCheckpoint = checkpoint(saved.point, OWNER_ONE)
            ) is ResumeCheckpointWriteState.Saved
        )

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val envelope = record.readText()
        val ivMarker = "\"ivBase64\":\""
        val ciphertextMarker = "\"ciphertextBase64\":\""
        val ivStart = envelope.indexOf(ivMarker) + ivMarker.length
        val ciphertextStart = envelope.indexOf(ciphertextMarker) + ciphertextMarker.length
        check(ivStart >= ivMarker.length && ciphertextStart >= ciphertextMarker.length)
        val ivEnd = envelope.indexOf('"', ivStart)
        val ciphertextEnd = envelope.indexOf('"', ciphertextStart)
        val aad = "${ScanResumeStore.FORMAT_VERSION}:${saved.point.requestId}"
            .toByteArray(Charsets.UTF_8)
        val plaintext = crypto.open(
            SealedResumePayload(
                iv = Base64.getDecoder().decode(envelope.substring(ivStart, ivEnd)),
                ciphertext = Base64.getDecoder().decode(envelope.substring(ciphertextStart, ciphertextEnd))
            ),
            aad
        ).toString(Charsets.UTF_8)
        val marker = "\"completedCheckpointStages\":[\"COMPARING_FACE_CONSISTENCY\"]"
        check(plaintext.contains(marker))
        val resealed = crypto.seal(
            plaintext.replace(marker, "\"completedCheckpointStages\":[]")
                .toByteArray(Charsets.UTF_8),
            aad,
            allowKeyCreation = false
        )
        record.writeText(
            "{\"formatVersion\":${ScanResumeStore.FORMAT_VERSION}," +
                "\"ivBase64\":\"${Base64.getEncoder().encodeToString(resealed.iv)}\"," +
                "\"ciphertextBase64\":\"${Base64.getEncoder().encodeToString(resealed.ciphertext)}\"}"
        )

        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertNull(loaded.point.faceCheckpoint)
    }

    @Test
    fun oversizedAndMalformedStageOutputFailsClosed() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.ComparingFaceConsistency,
                completed = true,
                faceCheckpoint = checkpoint(saved.point, OWNER_ONE).copy(
                    matchesJson = "x".repeat(FaceCheckpointCodec.MAX_FACE_BYTES + 1)
                )
            ) is ResumeCheckpointWriteState.Invalid
        )
        assertNull((store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available).point.faceCheckpoint)
    }

    private fun checkpoint(point: ResumePoint, ownerId: String) = FaceConsistencyStageCheckpoint(
        requestId = point.requestId,
        planFingerprint = point.planFingerprint!!,
        ownerId = ownerId,
        capturedAtEpochMillis = point.createdAtEpochMillis,
        inputDigest = "a".repeat(64),
        matchesJson = FaceCheckpointCodec.encode(sampleMatches())!!
    )

    private fun sampleMatches() = listOf(
        FaceConsistencyMatch("https://example.test/alice", 0.87f)
    )

    private fun fixture(): Fixture {
        val root = File.createTempFile("face-checkpoint", "").also {
            it.delete()
            it.mkdirs()
        }
        fixtures += root
        return Fixture(File(root, "records"), File(root, "legacy"))
    }

    private fun store(
        fixture: Fixture,
        nowMillis: () -> Long = { 1_000L },
        crypto: FaceCheckpointTestCrypto = FaceCheckpointTestCrypto()
    ): ScanResumeStore = ScanResumeStore(
        recordsDir = fixture.records,
        legacyDir = fixture.legacy,
        crypto = crypto,
        nowMillis = nowMillis,
        idFactory = { ID_ONE }
    )

    private fun input() = IdentityInput(
        fullName = "Jane Doe",
        usernames = listOf("janedoe"),
        emails = listOf("jane@example.com"),
        selfieUri = "content://selfie-bytes"
    )

    private data class Fixture(val records: File, val legacy: File)

    private companion object {
        const val ID_ONE = "123e4567-e89b-12d3-a456-426614174000"
        const val ID_TWO = "123e4567-e89b-12d3-a456-426614174001"
        const val OWNER_ONE = "223e4567-e89b-42d3-a456-426614174000"
        const val OWNER_TWO = "223e4567-e89b-42d3-a456-426614174001"
    }
}

private class FaceCheckpointTestCrypto : ResumeCrypto {
    private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 23).toByte() }, "AES")

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
