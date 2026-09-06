package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.IdentityInput
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

class ConfidenceCheckpointCodecTest {

    @Test
    fun confidenceRoundTripsOnlyWithinBoundedShape() {
        val confidence = sampleConfidence()
        val encoded = ConfidenceCheckpointCodec.encode(confidence)

        assertNotNull(encoded)
        assertEquals(confidence, ConfidenceCheckpointCodec.decode(encoded!!))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= ConfidenceCheckpointCodec.MAX_CONFIDENCE_BYTES)
    }

    @Test
    fun malformedOversizedDuplicateAndUnsafeOutputFailsClosed() {
        assertNull(ConfidenceCheckpointCodec.decode("not-json"))
        assertNull(
            ConfidenceCheckpointCodec.decode(
                "x".repeat(ConfidenceCheckpointCodec.MAX_CONFIDENCE_BYTES + 1)
            )
        )
        assertNull(
            ConfidenceCheckpointCodec.decode(
                """{"entries":[{"edgeKey":"edge","score":0.8,"reasons":["ok"]},{"edgeKey":"edge","score":0.7,"reasons":["ok"]}]}"""
            )
        )
        assertNull(
            ConfidenceCheckpointCodec.encode(
                mapOf("edge" to RelationshipConfidence(0.8f, listOf("token=must-not-store")))
            )
        )
    }

    @Test
    fun digestChangesWhenAnyConfidenceInputChanges() {
        val input = IdentityInput(fullName = "Jane Doe", usernames = listOf("janedoe"))
        val graph = sampleGraph()
        val evidence = sampleEvidence()
        val seeds = listOf("janedoe")

        val original = ConfidenceCheckpointCodec.inputDigest(input, graph, evidence, seeds)
        assertTrue(ConfidenceCheckpointCodec.isValidDigest(original))
        assertNotEquals(
            original,
            ConfidenceCheckpointCodec.inputDigest(
                input.copy(usernames = listOf("other")), graph, evidence, seeds
            )
        )
        assertNotEquals(
            original,
            ConfidenceCheckpointCodec.inputDigest(
                input, graph.copy(edges = emptyList()), evidence, seeds
            )
        )
        assertNotEquals(
            original,
            ConfidenceCheckpointCodec.inputDigest(
                input, graph, evidence.map { it.copy(state = EvidenceState.Conflicting) }, seeds
            )
        )
        assertNotEquals(
            original,
            ConfidenceCheckpointCodec.inputDigest(input, graph, evidence, listOf("different"))
        )
    }

    private fun sampleConfidence() = mapOf(
        "person:jane|profile:jane|owns_profile" to RelationshipConfidence(
            score = 0.8f,
            reasons = listOf("Shared verified username")
        )
    )

    private fun sampleGraph() = EntityGraph(
        entities = listOf(
            DossierEntity(
                id = "person:jane",
                type = EntityType.Person,
                label = "Jane Doe",
                confidence = 1f,
                state = GraphNodeState.Confirmed
            ),
            DossierEntity(
                id = "profile:jane",
                type = EntityType.Profile,
                label = "Example profile",
                confidence = 0.9f,
                state = GraphNodeState.High
            )
        ),
        edges = listOf(
            DossierEdge("person:jane", "profile:jane", "owns_profile", evidence = "verified")
        )
    )

    private fun sampleEvidence() = listOf(
        Evidence(
            id = "ev2:confidence",
            kind = EvidenceKind.Profile,
            value = "janedoe",
            sourceUrl = "https://example.test/jane",
            state = EvidenceState.Verified
        )
    )
}

class ScanResumeStoreConfidenceCheckpointTest {

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
                stage = ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = true,
                output = ScanStageOutput(itemCount = 1, verifiedCount = 1),
                relationshipConfidenceCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Saved
        )
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint, loaded.point.relationshipConfidenceCheckpoint)
        assertTrue(ScanCheckpointStage.ScoringRelationshipConfidence in loaded.point.completedCheckpointStages)

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
            .readText()
        assertFalse(record.contains("Shared verified username"))

        assertTrue(store.bindCheckpointOwner(saved.point.requestId, OWNER_TWO) is ResumeCheckpointWriteState.Saved)
        val rebound = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint.copy(ownerId = OWNER_TWO), rebound.point.relationshipConfidenceCheckpoint)
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
                stage = ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = false,
                relationshipConfidenceCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Invalid
        )

        fun write(value: RelationshipConfidenceStageCheckpoint): ResumeCheckpointWriteState =
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = true,
                relationshipConfidenceCheckpoint = value
            )

        assertTrue(write(checkpoint.copy(ownerId = OWNER_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(requestId = ID_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(planFingerprint = "b".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(inputDigest = "A".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(confidenceJson = "not-json")) is ResumeCheckpointWriteState.Invalid)
        assertNull(
            (store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available)
                .point.relationshipConfidenceCheckpoint
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
                stage = ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = true,
                relationshipConfidenceCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
                stage = ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = true,
                relationshipConfidenceCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
        val crypto = ConfidenceCheckpointTestCrypto()
        val store = store(fixture, crypto = crypto)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.ScoringRelationshipConfidence,
                completed = true,
                relationshipConfidenceCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
        val iv = Base64.getDecoder().decode(envelope.substring(ivStart, ivEnd))
        val ciphertext = Base64.getDecoder().decode(envelope.substring(ciphertextStart, ciphertextEnd))
        val plaintext = crypto.open(
            SealedResumePayload(iv = iv, ciphertext = ciphertext),
            "${ScanResumeStore.FORMAT_VERSION}:${saved.point.requestId}"
                .toByteArray(Charsets.UTF_8)
        ).toString(Charsets.UTF_8)
        val marker = "\"completedCheckpointStages\":[\"SCORING_RELATIONSHIP_CONFIDENCE\"]"
        check(plaintext.contains(marker))
        val withoutStage = plaintext.replace(marker, "\"completedCheckpointStages\":[]")
        val resealed = crypto.seal(
            withoutStage.toByteArray(Charsets.UTF_8),
            "${ScanResumeStore.FORMAT_VERSION}:${saved.point.requestId}"
                .toByteArray(Charsets.UTF_8),
            allowKeyCreation = false
        )
        record.writeText(
            "{\"formatVersion\":${ScanResumeStore.FORMAT_VERSION}," +
                "\"ivBase64\":\"${Base64.getEncoder().encodeToString(resealed.iv)}\"," +
                "\"ciphertextBase64\":\"${Base64.getEncoder().encodeToString(resealed.ciphertext)}\"}"
        )

        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertNull(loaded.point.relationshipConfidenceCheckpoint)
    }

    private fun checkpoint(point: ResumePoint, ownerId: String) = RelationshipConfidenceStageCheckpoint(
        requestId = point.requestId,
        planFingerprint = point.planFingerprint!!,
        ownerId = ownerId,
        capturedAtEpochMillis = point.createdAtEpochMillis,
        inputDigest = "a".repeat(64),
        confidenceJson = ConfidenceCheckpointCodec.encode(
            mapOf("person:jane|profile:jane|owns_profile" to RelationshipConfidence(0.8f, listOf("Shared verified username")))
        )!!
    )

    private fun fixture(): Fixture {
        val root = File.createTempFile("confidence-checkpoint", "").also {
            it.delete()
            it.mkdirs()
        }
        fixtures += root
        return Fixture(File(root, "records"), File(root, "legacy"))
    }

    private fun store(
        fixture: Fixture,
        nowMillis: () -> Long = { 1_000L },
        crypto: ConfidenceCheckpointTestCrypto = ConfidenceCheckpointTestCrypto()
    ): ScanResumeStore =
        ScanResumeStore(
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

private class ConfidenceCheckpointTestCrypto : ResumeCrypto {
    private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 13).toByte() }, "AES")

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
