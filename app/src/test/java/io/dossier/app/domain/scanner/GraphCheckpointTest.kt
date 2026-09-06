package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
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

class GraphCheckpointCodecTest {

    @Test
    fun graphRoundTripsOnlyWithinBoundedShape() {
        val graph = sampleGraph()
        val encoded = GraphCheckpointCodec.encode(graph)

        assertNotNull(encoded)
        assertEquals(graph, GraphCheckpointCodec.decode(encoded!!))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= GraphCheckpointCodec.MAX_GRAPH_BYTES)
    }

    @Test
    fun malformedOversizedDanglingAndUnsafeGraphsFailClosed() {
        assertNull(GraphCheckpointCodec.decode("not-json"))
        assertNull(GraphCheckpointCodec.decode("x".repeat(GraphCheckpointCodec.MAX_GRAPH_BYTES + 1)))

        val dangling = sampleGraph().copy(
            edges = listOf(DossierEdge("person:jane", "missing", "owns_profile"))
        )
        assertNull(GraphCheckpointCodec.encode(dangling))

        val unsafe = sampleGraph().copy(
            entities = sampleGraph().entities.map { entity ->
                if (entity.id == "profile:jane") entity.copy(label = "token=do-not-store") else entity
            }
        )
        assertNull(GraphCheckpointCodec.encode(unsafe))
    }

    @Test
    fun digestChangesWhenAnyGraphInputChanges() {
        val input = IdentityInput(fullName = "Jane Doe", usernames = listOf("janedoe"))
        val profiles = listOf(profile())
        val findings = listOf(
            Finding(
                type = FindingType.Profile,
                value = "janedoe",
                sourceUrl = "https://example.test/jane",
                evidenceSnippet = "verified",
                confidence = 0.8f,
                risk = RiskLevel.Medium,
                remediation = "review"
            )
        )
        val face = listOf(FaceConsistencyMatch("https://example.test/jane", 0.81f))
        val breaches = emptyList<io.dossier.app.domain.model.BreachDigest>()
        val evidence = listOf(
            Evidence(
                id = "ev2:graph",
                kind = EvidenceKind.Profile,
                value = "janedoe",
                sourceUrl = "https://example.test/jane",
                state = EvidenceState.Verified
            )
        )
        val relationships = listOf(EvidenceRelationship("janedoe", "https://example.test/jane", "LINKS_TO"))

        val original = GraphCheckpointCodec.inputDigest(
            input, profiles, findings, face, breaches, evidence, relationships
        )
        val changed = GraphCheckpointCodec.inputDigest(
            input, profiles, findings, face, breaches,
            evidence.map { it.copy(state = EvidenceState.Conflicting) }, relationships
        )

        assertTrue(GraphCheckpointCodec.isValidDigest(original))
        assertNotEquals(original, changed)
    }

    private fun profile() = ProfileScanResult(
        candidate = UsernameCandidate(
            username = "janedoe",
            platform = Platform.GitHub,
            url = "https://example.test/jane",
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
        verified = true,
        verificationStatus = "verified"
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
                sourceUrls = listOf("https://example.test/jane"),
                state = GraphNodeState.High,
                evidenceIds = listOf("ev2:graph")
            )
        ),
        edges = listOf(
            DossierEdge(
                fromId = "person:jane",
                toId = "profile:jane",
                relation = "owns_profile",
                evidence = "verified",
                evidenceIds = listOf("ev2:graph")
            )
        )
    )
}

class ScanResumeStoreGraphCheckpointTest {

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
                stage = ScanCheckpointStage.BuildingEntityGraph,
                completed = true,
                output = ScanStageOutput(itemCount = 2, verifiedCount = 1),
                entityGraphCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Saved
        )
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint, loaded.point.entityGraphCheckpoint)
        assertTrue(ScanCheckpointStage.BuildingEntityGraph in loaded.point.completedCheckpointStages)

        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
            .readText()
        assertFalse(record.contains("example.test"))

        assertTrue(store.bindCheckpointOwner(saved.point.requestId, OWNER_TWO) is ResumeCheckpointWriteState.Saved)
        val rebound = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(checkpoint.copy(ownerId = OWNER_TWO), rebound.point.entityGraphCheckpoint)
    }

    @Test
    fun checkpointCannotBeWrittenBeforeCompletedOrWithMismatchedBinding() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        val checkpoint = checkpoint(saved.point, OWNER_ONE)

        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.BuildingEntityGraph,
                completed = false,
                entityGraphCheckpoint = checkpoint
            ) is ResumeCheckpointWriteState.Invalid
        )
        assertNull((store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available).point.entityGraphCheckpoint)

        fun write(value: EntityGraphStageCheckpoint) = store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.BuildingEntityGraph,
            completed = true,
            entityGraphCheckpoint = value
        )
        assertTrue(write(checkpoint.copy(ownerId = OWNER_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(requestId = ID_TWO)) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(planFingerprint = "b".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(inputDigest = "A".repeat(64))) is ResumeCheckpointWriteState.Invalid)
        assertTrue(write(checkpoint.copy(graphJson = "not-json")) is ResumeCheckpointWriteState.Invalid)
    }

    @Test
    fun tamperedCheckpointFailsAuthenticationAndExpiryRemovesRecord() {
        val fixture = fixture()
        var now = 1_000L
        val store = store(fixture, nowMillis = { now })
        val saved = store.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.BuildingEntityGraph,
                completed = true,
                entityGraphCheckpoint = checkpoint(saved.point, OWNER_ONE)
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

        // A fresh record is still governed by the request TTL.
        val expiryFixture = fixture()
        var expiryNow = 1_000L
        val expiryStore = store(expiryFixture, nowMillis = { expiryNow })
        val expirySaved = expiryStore.saveRequestDetailed(input(), false, false) as ResumeWriteState.Saved
        expiryNow = expirySaved.point.expiresAtEpochMillis
        assertEquals(ResumeReadState.Expired, expiryStore.loadRequestDetailed(expirySaved.point.requestId))
    }

    private fun checkpoint(point: ResumePoint, ownerId: String) = EntityGraphStageCheckpoint(
        requestId = point.requestId,
        planFingerprint = point.planFingerprint!!,
        ownerId = ownerId,
        capturedAtEpochMillis = point.createdAtEpochMillis,
        inputDigest = "a".repeat(64),
        graphJson = GraphCheckpointCodec.encode(GraphCheckpointTestGraph.graph())!!
    )

    private fun fixture(): Fixture {
        val root = File.createTempFile("graph-checkpoint", "").also {
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
            crypto = GraphCheckpointTestCrypto(),
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

/** Keeps the store fixture independent from the codec test's private helpers. */
private object GraphCheckpointTestGraph {
    fun graph() = EntityGraph(
        entities = listOf(
            DossierEntity("person:jane", EntityType.Person, "Jane Doe", confidence = 1f, state = GraphNodeState.Confirmed),
            DossierEntity(
                "profile:jane",
                EntityType.Profile,
                "Example profile",
                confidence = 0.9f,
                sourceUrls = listOf("https://example.test/jane"),
                state = GraphNodeState.High
            )
        ),
        edges = listOf(DossierEdge("person:jane", "profile:jane", "owns_profile", evidence = "verified"))
    )
}

private class GraphCheckpointTestCrypto : ResumeCrypto {
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
