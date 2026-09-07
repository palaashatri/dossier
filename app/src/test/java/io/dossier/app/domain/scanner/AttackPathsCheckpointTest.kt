package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.AttackPathFinder
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

class AttackPathsCheckpointCodecTest {

    @Test
    fun attackPathsRoundTripWithinBoundedShape() {
        val paths = samplePaths()
        val encoded = AttackPathsCheckpointCodec.encode(paths)

        assertNotNull(encoded)
        assertEquals(paths, AttackPathsCheckpointCodec.decode(encoded!!))
        assertTrue(
            encoded.toByteArray(Charsets.UTF_8).size <=
                AttackPathsCheckpointCodec.MAX_ATTACK_PATHS_BYTES
        )
    }

    @Test
    fun malformedOversizedDuplicateAndUnsafeOutputFailsClosed() {
        assertNull(AttackPathsCheckpointCodec.decode("not-json"))
        assertNull(
            AttackPathsCheckpointCodec.decode("x".repeat(AttackPathsCheckpointCodec.MAX_ATTACK_PATHS_BYTES + 1))
        )
        val duplicate = samplePaths().let { it + it }
        assertNull(AttackPathsCheckpointCodec.encode(duplicate))
        assertNull(
            AttackPathsCheckpointCodec.encode(
                listOf(
                    AttackPathFinder.AttackPath(
                        endpointType = EntityType.Breach,
                        endpointLabel = "token=must-not-store",
                        steps = samplePaths().single().steps,
                        riskHint = "reachable"
                    )
                )
            )
        )
        assertNull(
            AttackPathsCheckpointCodec.encode(
                listOf(
                    samplePaths().single().copy(
                        steps = List(AttackPathsCheckpointCodec.MAX_STEPS + 1) {
                            samplePaths().single().steps.first()
                        }
                    )
                )
            )
        )
    }

    @Test
    fun digestChangesWhenGraphOrConfidenceInputChanges() {
        val input = IdentityInput(fullName = "Jane Doe", usernames = listOf("janedoe"))
        val graph = sampleGraph()
        val confidence = sampleConfidence()
        val original = AttackPathsCheckpointCodec.inputDigest(input, graph, confidence)

        assertTrue(AttackPathsCheckpointCodec.isValidDigest(original))
        assertNotEquals(
            original,
            AttackPathsCheckpointCodec.inputDigest(
                input.copy(usernames = listOf("other")), graph, confidence
            )
        )
        assertNotEquals(
            original,
            AttackPathsCheckpointCodec.inputDigest(
                input, graph.copy(edges = emptyList()), confidence
            )
        )
        assertNotEquals(
            original,
            AttackPathsCheckpointCodec.inputDigest(
                input, graph, confidence.mapValues { it.value.copy(score = 0.1f) }
            )
        )
    }

    private fun samplePaths() = listOf(
        AttackPathFinder.AttackPath(
            endpointType = EntityType.Breach,
            endpointLabel = "Example breach",
            steps = listOf(
                AttackPathFinder.Step(
                    fromLabel = "Jane Doe",
                    toLabel = "jane@example.com",
                    relation = "has_email",
                    evidence = "verified input",
                    confidence = 0.9f
                ),
                AttackPathFinder.Step(
                    fromLabel = "jane@example.com",
                    toLabel = "Example breach",
                    relation = "appeared_in_breach",
                    evidence = "breach metadata",
                    confidence = null
                )
            ),
            riskHint = "Reachable from subject in 2 hop(s)"
        )
    )

    private fun sampleConfidence() = mapOf(
        "person:jane|email:jane@example.com|has_email" to RelationshipConfidence(
            score = 0.8f,
            reasons = listOf("Verified input")
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
                id = "breach:example",
                type = EntityType.Breach,
                label = "Example breach",
                confidence = 0.9f,
                state = GraphNodeState.High
            )
        ),
        edges = listOf(
            DossierEdge("person:jane", "breach:example", "appeared_in_breach", evidence = "verified")
        )
    )
}

class ScanResumeStoreAttackPathsCheckpointTest {

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
                stage = ScanCheckpointStage.TracingAttackPaths,
                completed = true,
                output = ScanStageOutput(itemCount = 1),
                attackPathsCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Saved
        )
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint, loaded.point.attackPathsCheckpoint)
        assertTrue(ScanCheckpointStage.TracingAttackPaths in loaded.point.completedCheckpointStages)

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
            .readText()
        assertFalse(record.contains("Example breach"))

        assertTrue(store.bindCheckpointOwner(saved.point.requestId, OWNER_TWO) is ResumeCheckpointWriteState.Saved)
        val rebound = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint.copy(ownerId = OWNER_TWO), rebound.point.attackPathsCheckpoint)
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
                stage = ScanCheckpointStage.TracingAttackPaths,
                completed = false,
                attackPathsCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Invalid
        )

        fun write(value: AttackPathsStageCheckpoint): ResumeCheckpointWriteState =
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.TracingAttackPaths,
                completed = true,
                attackPathsCheckpoint = value
            )

        assertTrue(write(checkpoint.copy(ownerId = OWNER_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(requestId = ID_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(planFingerprint = "b".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(inputDigest = "A".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(attackPathsJson = "not-json")) is ResumeCheckpointWriteState.Invalid)
        assertNull(
            (store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available)
                .point.attackPathsCheckpoint
        )
    }

    @Test
    fun authenticatedPayloadWithoutCompletedStageMarkerIsNotReusable() {
        val fixture = fixture()
        val crypto = AttackPathsCheckpointTestCrypto()
        val store = store(fixture, crypto = crypto)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.TracingAttackPaths,
                completed = true,
                attackPathsCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
        val aad = "${ScanResumeStore.FORMAT_VERSION}:${saved.point.requestId}"
            .toByteArray(Charsets.UTF_8)
        val plaintext = crypto.open(
            SealedResumePayload(iv = iv, ciphertext = ciphertext),
            aad
        ).toString(Charsets.UTF_8)
        val marker = "\"completedCheckpointStages\":[\"TRACING_ATTACK_PATHS\"]"
        check(plaintext.contains(marker))
        val withoutStage = plaintext.replace(marker, "\"completedCheckpointStages\":[]")
        val resealed = crypto.seal(
            withoutStage.toByteArray(Charsets.UTF_8),
            aad,
            allowKeyCreation = false
        )
        record.writeText(
            "{\"formatVersion\":${ScanResumeStore.FORMAT_VERSION}," +
                "\"ivBase64\":\"${Base64.getEncoder().encodeToString(resealed.iv)}\"," +
                "\"ciphertextBase64\":\"${Base64.getEncoder().encodeToString(resealed.ciphertext)}\"}"
        )

        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertNull(loaded.point.attackPathsCheckpoint)
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
                stage = ScanCheckpointStage.TracingAttackPaths,
                completed = true,
                attackPathsCheckpoint = checkpoint(saved.point, OWNER_ONE)
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
                stage = ScanCheckpointStage.TracingAttackPaths,
                completed = true,
                attackPathsCheckpoint = checkpoint(saved.point, OWNER_ONE)
            ) is ResumeCheckpointWriteState.Saved
        )

        now = saved.point.expiresAtEpochMillis
        assertEquals(ResumeReadState.Expired, store.loadRequestDetailed(saved.point.requestId))
    }

    private fun checkpoint(point: ResumePoint, ownerId: String) = AttackPathsStageCheckpoint(
        requestId = point.requestId,
        planFingerprint = point.planFingerprint!!,
        ownerId = ownerId,
        capturedAtEpochMillis = point.createdAtEpochMillis,
        inputDigest = "a".repeat(64),
        attackPathsJson = AttackPathsCheckpointCodec.encode(
            listOf(
                AttackPathFinder.AttackPath(
                    endpointType = EntityType.Breach,
                    endpointLabel = "Example breach",
                    steps = listOf(
                        AttackPathFinder.Step("Jane Doe", "Example breach", "appeared_in_breach", "verified", 0.8f)
                    ),
                    riskHint = "Reachable from subject in 1 hop(s)"
                )
            )
        )!!
    )

    private fun fixture(): Fixture {
        val root = File.createTempFile("attack-paths-checkpoint", "").also {
            it.delete()
            it.mkdirs()
        }
        fixtures += root
        return Fixture(File(root, "records"), File(root, "legacy"))
    }

    private fun store(
        fixture: Fixture,
        nowMillis: () -> Long = { 1_000L },
        crypto: AttackPathsCheckpointTestCrypto = AttackPathsCheckpointTestCrypto()
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

private class AttackPathsCheckpointTestCrypto : ResumeCrypto {
    private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")

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
