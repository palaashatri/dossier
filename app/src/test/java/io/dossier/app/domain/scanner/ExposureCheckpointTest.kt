package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.ExposureEngine
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
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

class ExposureCheckpointCodecTest {

    @Test
    fun roundTripsBoundedProjectionWithoutRawFindingValues() {
        val findings = sampleFindings()
        val result = ExposureEngine().score(findings, sampleBreaches())

        val encoded = ExposureCheckpointCodec.encode(result)

        assertNotNull(encoded)
        assertTrue(encoded!!.toByteArray(Charsets.UTF_8).size <= ExposureCheckpointCodec.MAX_EXPOSURE_BYTES)
        assertFalse(encoded.contains("jane@example.com"))
        assertFalse(encoded.contains("private snippet"))
        assertFalse(encoded.contains("https://example.test/jane"))
        val decoded = ExposureCheckpointCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(result, ExposureCheckpointCodec.rebuild(decoded!!, findings))
    }

    @Test
    fun malformedOversizedAndInvalidShapeFailsClosed() {
        assertNull(ExposureCheckpointCodec.decode("not-json"))
        assertNull(
            ExposureCheckpointCodec.decode(
                "x".repeat(ExposureCheckpointCodec.MAX_EXPOSURE_BYTES + 1)
            )
        )
        assertNull(ExposureCheckpointCodec.decode("{\"dimensions\":[],\"overall\":0,\"topFindingIds\":[]}"))
        assertNull(
            ExposureCheckpointCodec.decode(
                "{\"dimensions\":[],\"overall\":0,\"topFindingIds\":[\"ev2:not-a-digest\"]}"
            )
        )
        assertNull(ExposureCheckpointCodec.decode("{\"dimensions\":[],\"overall\":0,\"topFindingIds\":[]}" + '\u0001'))
    }

    @Test
    fun digestChangesForFindingAndBreachInputChanges() {
        val findings = sampleFindings()
        val breaches = sampleBreaches()
        val original = ExposureCheckpointCodec.inputDigest(findings, breaches)

        assertTrue(ExposureCheckpointCodec.isValidDigest(original))
        assertNotEquals(
            original,
            ExposureCheckpointCodec.inputDigest(findings.map { it.copy(value = "other@example.com") }, breaches)
        )
        assertNotEquals(
            original,
            ExposureCheckpointCodec.inputDigest(findings, breaches.map { it.copy(breachCount = 0) })
        )
    }

    @Test
    fun rebuildFailsClosedWhenCurrentFindingsDoNotMatchHashedReferences() {
        val result = ExposureEngine().score(sampleFindings(), sampleBreaches())
        val decoded = ExposureCheckpointCodec.decode(ExposureCheckpointCodec.encode(result)!!)

        assertNotNull(decoded)
        assertNull(ExposureCheckpointCodec.rebuild(decoded!!, emptyList()))
        assertNull(
            ExposureCheckpointCodec.rebuild(
                decoded,
                listOf(sampleFindings().first().copy(value = "different@example.com"))
            )
        )
    }

    private fun sampleFindings() = listOf(
        Finding(
            type = FindingType.Email,
            value = "jane@example.com",
            sourceUrl = "https://example.test/jane",
            evidenceSnippet = "private snippet",
            confidence = 0.95f,
            risk = RiskLevel.High,
            remediation = "remove"
        ),
        Finding(
            type = FindingType.Location,
            value = "Bengaluru",
            sourceUrl = "https://example.test/location",
            evidenceSnippet = "location snippet",
            confidence = 0.8f,
            risk = RiskLevel.Medium,
            remediation = "review"
        )
    )

    private fun sampleBreaches() = listOf(
        BreachDigest(
            email = "jane@example.com",
            breachCount = 1,
            sources = listOf("authoritative-source"),
            note = "metadata only"
        )
    )
}

class ScanResumeStoreExposureCheckpointTest {

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
                stage = ScanCheckpointStage.CompilingExposureScores,
                completed = true,
                output = ScanStageOutput(itemCount = 2),
                exposureCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Saved
        )
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint, loaded.point.exposureCheckpoint)
        assertTrue(ScanCheckpointStage.CompilingExposureScores in loaded.point.completedCheckpointStages)

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
            .readText()
        assertFalse(record.contains("jane@example.com"))
        assertFalse(record.contains("private snippet"))

        assertTrue(store.bindCheckpointOwner(saved.point.requestId, OWNER_TWO) is ResumeCheckpointWriteState.Saved)
        val rebound = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint.copy(ownerId = OWNER_TWO), rebound.point.exposureCheckpoint)
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
                stage = ScanCheckpointStage.CompilingExposureScores,
                completed = false,
                exposureCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Invalid
        )

        fun write(value: ExposureStageCheckpoint): ResumeCheckpointWriteState = store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.CompilingExposureScores,
            completed = true,
            exposureCheckpoint = value
        )

        assertTrue(write(checkpoint.copy(ownerId = OWNER_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(requestId = ID_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(planFingerprint = "b".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(inputDigest = "A".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(exposureJson = "not-json")) is ResumeCheckpointWriteState.Invalid)
        assertNull(
            (store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available)
                .point.exposureCheckpoint
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
                stage = ScanCheckpointStage.CompilingExposureScores,
                completed = true,
                exposureCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
                stage = ScanCheckpointStage.CompilingExposureScores,
                completed = true,
                exposureCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
        val crypto = ExposureCheckpointTestCrypto()
        val store = store(fixture, crypto = crypto)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.CompilingExposureScores,
                completed = true,
                exposureCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
        val marker = "\"completedCheckpointStages\":[\"COMPILING_EXPOSURE_SCORES\"]"
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
        assertNull(loaded.point.exposureCheckpoint)
    }

    private fun checkpoint(point: ResumePoint, ownerId: String) = ExposureStageCheckpoint(
        requestId = point.requestId,
        planFingerprint = point.planFingerprint!!,
        ownerId = ownerId,
        capturedAtEpochMillis = point.createdAtEpochMillis,
        inputDigest = "a".repeat(64),
        exposureJson = ExposureCheckpointCodec.encode(
            ExposureEngine().score(
                listOf(
                    Finding(
                        FindingType.Email,
                        "jane@example.com",
                        "https://example.test/jane",
                        "private snippet",
                        0.95f,
                        RiskLevel.High,
                        "remove"
                    ),
                    Finding(
                        FindingType.Location,
                        "Bengaluru",
                        "https://example.test/location",
                        "location snippet",
                        0.8f,
                        RiskLevel.Medium,
                        "review"
                    )
                ),
                listOf(BreachDigest("jane@example.com", 1, listOf("source")))
            )
        )!!
    )

    private fun fixture(): Fixture {
        val root = File.createTempFile("exposure-checkpoint", "").also {
            it.delete()
            it.mkdirs()
        }
        fixtures += root
        return Fixture(File(root, "records"), File(root, "legacy"))
    }

    private fun store(
        fixture: Fixture,
        nowMillis: () -> Long = { 1_000L },
        crypto: ExposureCheckpointTestCrypto = ExposureCheckpointTestCrypto()
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

private class ExposureCheckpointTestCrypto : ResumeCrypto {
    private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 19).toByte() }, "AES")

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
