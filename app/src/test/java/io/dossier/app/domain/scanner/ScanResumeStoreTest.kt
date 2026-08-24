package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderPlanFingerprint
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.data.platform.ProviderCatalogV2
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class MemoryGuardTest {

    private fun finding(i: Int) = Finding(
        type = FindingType.Email,
        value = "user$i@example.com",
        sourceUrl = null,
        evidenceSnippet = null,
        confidence = 0.5f,
        risk = RiskLevel.Low,
        remediation = ""
    )

    @Test
    fun retainsBelowCap() {
        val inList = List(10) { finding(it) }
        val result = MemoryGuard.cap(inList)
        assertEquals(10, result.retained.size)
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun dropsAboveCap() {
        val inList = List(MemoryGuard.MAX_FINDINGS + 25) { finding(it) }
        val result = MemoryGuard.cap(inList)
        assertEquals(MemoryGuard.MAX_FINDINGS, result.retained.size)
        assertEquals(25, result.droppedCount)
    }

    @Test
    fun exactlyAtCapDropsNothing() {
        val inList = List(MemoryGuard.MAX_FINDINGS) { finding(it) }
        val result = MemoryGuard.cap(inList)
        assertEquals(MemoryGuard.MAX_FINDINGS, result.retained.size)
        assertEquals(0, result.droppedCount)
    }
}

class ScanResumeStoreTest {

    private val fixtures = mutableListOf<File>()

    @After
    fun resetModeAndFiles() {
        DiscoveryScanPreferences.reset()
        fixtures.forEach(File::deleteRecursively)
    }

    @Test
    fun saveLoadRoundTripsAllInputFieldsFlagAndScanMode() {
        val fixture = fixture()
        val store = store(fixture)
        val input = completeInput()
        DiscoveryScanPreferences.setMode(ScanMode.Deep)

        assertEquals(true, store.save(input, deepResearch = true))

        DiscoveryScanPreferences.reset()
        val loaded = store.load()
        assertEquals(input, loaded?.first)
        assertEquals(true, loaded?.second)
        assertEquals(ScanMode.Deep, DiscoveryScanPreferences.selectedMode.value)
    }

    @Test
    fun encryptedRecordUsesUuidPathAndContainsNoPlaintextSeeds() {
        val fixture = fixture()
        val store = store(fixture)
        val input = completeInput()

        val saved = store.saveDetailed(input, deepResearch = true) as ResumeWriteState.Saved
        val files = fixture.records.listFiles().orEmpty()
        val record = files.single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val pointer = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME)

        assertEquals("${saved.point.requestId}${ScanResumeStore.RECORD_EXTENSION}", record.name)
        assertEquals(saved.point.requestId, pointer.readText())
        assertTrue(record.name.matches(Regex("^[0-9a-f-]{36}\\.dscan$")))

        val recordBytes = record.readBytes().toString(Charsets.UTF_8)
        assertFalse(recordBytes.contains(input.fullName))
        assertFalse(recordBytes.contains(input.emails.single()))
        assertFalse(recordBytes.contains(input.selfieUri.orEmpty()))
        assertFalse(pointer.readText().contains(input.fullName))
        assertFalse(pointer.readText().contains(input.emails.single()))
    }

    @Test
    fun requestScopedLoadRestoresEncryptedModeAndFacePolicy() {
        val fixture = fixture()
        val store = store(fixture)
        DiscoveryScanPreferences.setMode(ScanMode.Exhaustive)

        val saved = store.saveRequestDetailed(
            input = completeInput(),
            deepResearch = true,
            strongFaceCorrelation = true
        ) as ResumeWriteState.Saved

        DiscoveryScanPreferences.reset()
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available

        assertEquals(ScanMode.Exhaustive, loaded.point.scanMode)
        assertTrue(loaded.point.deepResearch)
        assertTrue(loaded.point.strongFaceCorrelation)
    }

    @Test
    fun requestCheckpointCapturesDeterministicProviderPlanMetadata() {
        val fixture = fixture()
        val store = store(fixture)
        DiscoveryScanPreferences.setMode(ScanMode.Deep)
        val expected = ProviderCatalogV2.plan(ScanMode.Deep)

        val saved = store.saveRequestDetailed(
            input = completeInput(),
            deepResearch = true,
            strongFaceCorrelation = false
        ) as ResumeWriteState.Saved
        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available

        assertEquals(ProviderPlanFingerprint.forPlan(expected), saved.point.planFingerprint)
        assertEquals(saved.point.planFingerprint, loaded.point.planFingerprint)
        assertEquals(expected.providers.size, loaded.point.plannedProviderCount)
        assertEquals(expected.providers.map { it.id }, loaded.point.plannedProviderIds)
    }

    @Test
    fun coordinatorCheckpointBindsOwnerAndPersistsCompletedBoundaries() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(
            input = completeInput(),
            deepResearch = true,
            strongFaceCorrelation = false
        ) as ResumeWriteState.Saved

        assertTrue(
            store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
                is ResumeCheckpointWriteState.Saved
        )
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.DiscoveringUsernames,
                completed = false
            ) is ResumeCheckpointWriteState.Saved
        )
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.DiscoveringUsernames,
                completed = true
            ) is ResumeCheckpointWriteState.Saved
        )

        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(ScanCheckpointStage.DiscoveringUsernames, loaded.point.checkpointStage)
        assertEquals(listOf(ScanCheckpointStage.DiscoveringUsernames), loaded.point.completedCheckpointStages)
        assertEquals(OWNER_ONE, loaded.point.checkpointOwnerId)
        assertTrue(loaded.point.updatedAtEpochMillis >= loaded.point.createdAtEpochMillis)
    }

    @Test
    fun coordinatorCheckpointPersistsBoundedStageOutputShape() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(completeInput(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)

        val output = ScanStageOutput(itemCount = 12, verifiedCount = 7, omittedCount = 2)
        assertTrue(
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.BuildingEntityGraph,
                completed = true,
                output = output
            ) is ResumeCheckpointWriteState.Saved
        )

        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(output, loaded.point.stageOutputs[ScanCheckpointStage.BuildingEntityGraph])
    }

    @Test
    fun invalidStageOutputIsRejectedWithoutPersistingMetadata() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(completeInput(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)

        val result = store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.BuildingEntityGraph,
            completed = true,
            output = ScanStageOutput(itemCount = -1)
        )
        assertTrue(result is ResumeCheckpointWriteState.Invalid)

        val loaded = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertTrue(loaded.point.stageOutputs.isEmpty())
        assertEquals(ScanCheckpointStage.Queued, loaded.point.checkpointStage)
    }

    @Test
    fun staleCheckpointOwnerCannotOverwriteNewOwnerOrRegressStage() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(completeInput(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.BuildingEntityGraph,
            completed = true
        )
        store.bindCheckpointOwner(saved.point.requestId, OWNER_TWO)

        assertEquals(
            ResumeCheckpointWriteState.StaleOwner,
            store.advanceCheckpoint(
                requestId = saved.point.requestId,
                ownerId = OWNER_ONE,
                stage = ScanCheckpointStage.Completed,
                completed = true
            )
        )
        val retained = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(OWNER_TWO, retained.point.checkpointOwnerId)
        assertEquals(ScanCheckpointStage.Queued, retained.point.checkpointStage)
        assertEquals(listOf(ScanCheckpointStage.BuildingEntityGraph), retained.point.completedCheckpointStages)
    }

    @Test
    fun outOfOrderCheckpointDoesNotRegressCurrentStage() {
        val fixture = fixture()
        val store = store(fixture)
        val saved = store.saveRequestDetailed(completeInput(), false, false) as ResumeWriteState.Saved
        store.bindCheckpointOwner(saved.point.requestId, OWNER_ONE)
        store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.CompilingExposureScores,
            completed = false
        )
        store.advanceCheckpoint(
            requestId = saved.point.requestId,
            ownerId = OWNER_ONE,
            stage = ScanCheckpointStage.ComparingFaceConsistency,
            completed = true
        )

        val retained = store.loadRequestDetailed(saved.point.requestId) as ResumeReadState.Available
        assertEquals(ScanCheckpointStage.CompilingExposureScores, retained.point.checkpointStage)
        assertTrue(ScanCheckpointStage.ComparingFaceConsistency in retained.point.completedCheckpointStages)
    }

    @Test
    fun requestScopedLoadRejectsSupersededPointer() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_ONE, ID_TWO))
        val first = store.saveRequestDetailed(completeInput(), false, false) as ResumeWriteState.Saved
        val second = store.saveRequestDetailed(completeInput(), true, true) as ResumeWriteState.Saved

        assertEquals(ResumeReadState.Missing, store.loadRequestDetailed(first.point.requestId))
        assertTrue(store.loadRequestDetailed(second.point.requestId) is ResumeReadState.Available)
    }

    @Test
    fun requestScopedExpiryDurablyRemovesRecordAndPointer() {
        val fixture = fixture()
        var now = 1_000L
        val store = store(fixture, nowMillis = { now })
        val saved = store.saveRequestDetailed(completeInput(), false, false) as ResumeWriteState.Saved
        now = saved.point.expiresAtEpochMillis

        assertEquals(ResumeReadState.Expired, store.loadRequestDetailed(saved.point.requestId))
        assertFalse(File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).exists())
        assertTrue(
            fixture.records.listFiles().orEmpty()
                .none { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        )
    }

    @Test
    fun priorEncryptedFormatDefaultsStrongCorrelationToFalse() {
        val fixture = fixture()
        writePriorRecordWithoutStrongCorrelation(fixture, completeInput())

        val loaded = store(fixture).loadRequestDetailed(ID_ONE) as ResumeReadState.Available

        assertEquals(ScanMode.Deep, loaded.point.scanMode)
        assertTrue(loaded.point.deepResearch)
        assertFalse(loaded.point.strongFaceCorrelation)
        assertEquals(null, loaded.point.planFingerprint)
        assertTrue(loaded.point.plannedProviderIds.isEmpty())
        assertEquals(0, loaded.point.plannedProviderCount)
        assertEquals(ScanCheckpointStage.Queued, loaded.point.checkpointStage)
        assertTrue(loaded.point.completedCheckpointStages.isEmpty())
        assertEquals(null, loaded.point.checkpointOwnerId)
    }

    @Test
    fun ttlExpiresAtBoundaryAndRemovesActiveRecord() {
        val fixture = fixture()
        var now = 10_000L
        val store = store(fixture, nowMillis = { now })
        assertEquals(true, store.save(IdentityInput(fullName = "TTL"), deepResearch = false))

        now += ScanResumeStore.TTL_MILLIS
        assertEquals(ResumeReadState.Expired, store.loadDetailed())
        assertTrue(fixture.records.listFiles().orEmpty().none { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) })
        assertFalse(File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).exists())
    }

    @Test
    fun ciphertextTamperFailsClosed() {
        val fixture = fixture()
        val store = store(fixture)
        store.save(completeInput(), deepResearch = false)
        val record = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val text = record.readText()
        val marker = "\"ciphertextBase64\":\""
        val start = text.indexOf(marker) + marker.length
        check(start >= marker.length)
        val replacement = if (text[start] == 'A') 'B' else 'A'
        record.writeText(text.substring(0, start) + replacement + text.substring(start + 1))

        val state = store.loadDetailed()
        assertEquals(ResumeReadState.Invalid(ResumeInvalidReason.AuthenticationFailed), state)
        assertFalse(record.exists())
    }

    @Test
    fun movingCiphertextToAnotherUuidFailsAadAuthentication() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_ONE))
        store.save(completeInput(), deepResearch = false)
        val original = File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}")
        val moved = File(fixture.records, "$ID_TWO${ScanResumeStore.RECORD_EXTENSION}")
        original.copyTo(moved)
        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).writeText(ID_TWO)

        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.AuthenticationFailed),
            store.loadDetailed()
        )
        assertFalse(moved.exists())
        assertTrue(original.exists())
    }

    @Test
    fun invalidPointerAndGeneratedIdCannotEscapeRecordsDirectory() {
        val fixture = fixture()
        fixture.records.mkdirs()
        val outside = File(fixture.records.parentFile, "outside-seed.txt")
        outside.writeText("sentinel")
        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).writeText("../../outside-seed.txt")
        val store = store(fixture)

        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.InvalidRequestId),
            store.loadDetailed()
        )
        assertEquals("sentinel", outside.readText())

        val invalidIdStore = store(fixture, ids = listOf("../escape"))
        assertEquals(
            ResumeWriteState.Invalid(ResumeInvalidReason.InvalidRequestId),
            invalidIdStore.saveDetailed(IdentityInput(fullName = "X"), deepResearch = false)
        )
        assertFalse(File(fixture.records.parentFile, "escape.dscan").exists())
    }

    @Test
    fun legacyMarkerMigratesWithStandardDefaultAndDeletesPlaintext() {
        val fixture = fixture()
        fixture.legacy.mkdirs()
        val legacy = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        legacy.writeText(
            """{"fullName":"Legacy User","primaryUsername":"legacy-primary","usernames":["legacy-user"],"emails":["legacy@example.com"],"phones":["+1-555-0101"],"organizations":["Legacy Org"],"locations":["Legacy City"],"profileUrls":["https://legacy.example/profile"],"deepResearch":true}"""
        )
        DiscoveryScanPreferences.setMode(ScanMode.Exhaustive)

        val state = store(fixture).loadDetailed() as ResumeReadState.Available

        assertEquals("Legacy User", state.point.input.fullName)
        assertEquals("legacy-primary", state.point.input.primaryUsername)
        assertEquals(listOf("legacy-user"), state.point.input.usernames)
        assertEquals(listOf("legacy@example.com"), state.point.input.emails)
        assertEquals(listOf("+1-555-0101"), state.point.input.phones)
        assertEquals(listOf("Legacy Org"), state.point.input.organizations)
        assertEquals(listOf("Legacy City"), state.point.input.locations)
        assertEquals(listOf("https://legacy.example/profile"), state.point.input.profileUrls)
        assertTrue(state.point.input.aliases.isEmpty())
        assertEquals(null, state.point.input.selfieUri)
        assertTrue(state.point.deepResearch)
        assertEquals(ScanMode.Standard, state.point.scanMode)
        assertFalse(legacy.exists())
        assertTrue(fixture.records.listFiles().orEmpty().any { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) })
        val encrypted = fixture.records.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        assertFalse(encrypted.readText().contains("Legacy User"))
    }

    @Test
    fun existingEncryptedRecordMakesLegacyCleanupIdempotent() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_ONE, ID_TWO))
        assertEquals(true, store.save(IdentityInput(fullName = "Current"), deepResearch = true))
        val legacy = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        fixture.legacy.mkdirs()
        legacy.writeText(
            """{"fullName":"Legacy User","primaryUsername":"legacy","usernames":[],"emails":[],"phones":[],"organizations":[],"locations":[],"profileUrls":[],"deepResearch":false}"""
        )

        val state = store.loadDetailed() as ResumeReadState.Available

        assertEquals(ID_ONE, state.point.requestId)
        assertFalse(legacy.exists())
        assertEquals(1, fixture.records.listFiles().orEmpty().count { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) })
    }

    @Test
    fun malformedOrOversizedLegacyMarkerIsDeletedAndFailsClosed() {
        val malformedFixture = fixture()
        malformedFixture.legacy.mkdirs()
        val malformed = File(malformedFixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        malformed.writeText("not-json")
        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.MalformedLegacy),
            store(malformedFixture).loadDetailed()
        )
        assertFalse(malformed.exists())

        val oversizedFixture = fixture()
        oversizedFixture.legacy.mkdirs()
        val oversized = File(oversizedFixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        oversized.writeText("x".repeat(ScanResumeStore.MAX_LEGACY_BYTES.toInt() + 1))
        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.LegacyTooLarge),
            store(oversizedFixture).loadDetailed()
        )
        assertFalse(oversized.exists())
    }

    @Test
    fun oversizedMigrationCiphertextReturnsInvalidAndDeletesPlaintextLegacy() {
        val fixture = fixture()
        fixture.legacy.mkdirs()
        val legacy = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        legacy.writeText("{\"fullName\":\"Legacy User\"}")
        val store = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = OversizedCiphertextResumeCrypto(),
            idFactory = { ID_ONE }
        )

        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.RecordTooLarge),
            store.loadDetailed()
        )
        assertFalse(legacy.exists())
        assertTrue(fixture.records.listFiles().orEmpty().none { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) })
    }

    @Test
    fun oversizedCurrentInputIsRejectedBeforeAnyFileIsWritten() {
        val fixture = fixture()
        val store = store(fixture)
        val input = IdentityInput(fullName = "x".repeat(ScanResumeStore.MAX_FIELD_CHARS + 1))

        assertEquals(
            ResumeWriteState.Invalid(ResumeInvalidReason.InputTooLarge),
            store.saveDetailed(input, deepResearch = false)
        )
        assertTrue(fixture.records.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun clearDeletesResumeStateAndLegacyWithoutTouchingCases() {
        val fixture = fixture()
        val store = store(fixture)
        assertEquals(true, store.save(IdentityInput(fullName = "X"), deepResearch = false))
        fixture.legacy.mkdirs()
        File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME).writeText("legacy")
        val cases = File(fixture.records.parentFile, "dossier_cases").also { it.mkdirs() }
        val caseFile = File(cases, "saved.dcase").also { it.writeText("case") }

        assertEquals(ResumeWriteState.Cleared, store.clearDetailed())
        assertTrue(fixture.records.listFiles().orEmpty().isEmpty())
        assertFalse(File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME).exists())
        assertEquals("case", caseFile.readText())
    }


    @Test
    fun freshIvOnRepeatedSave() {
        val fixture = fixture()
        val store1 = store(fixture, ids = listOf(ID_ONE, ID_TWO))
        store1.save(completeInput(), false)
        val firstRecord = fixture.records.listFiles()!!.single { it.name.endsWith(".dscan") }.readText()
        val firstIv = firstRecord.substringAfter("ivBase64\":\"").substringBefore("\"")
        store1.save(completeInput(), true)
        val secondRecord = fixture.records.listFiles()!!.single { it.name.endsWith(".dscan") }.readText()
        val secondIv = secondRecord.substringAfter("ivBase64\":\"").substringBefore("\"")
        assertFalse(firstIv == secondIv)
    }

    @Test
    fun wrongKeyLoadFailsAuthentication() {
        val fixture = fixture()
        val writer = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = TestResumeCrypto(ByteArray(32) { 1 }),
            idFactory = { ID_ONE }
        )
        assertTrue(writer.save(IdentityInput(fullName = "X"), deepResearch = false))

        val reader = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = TestResumeCrypto(ByteArray(32) { 2 }),
            idFactory = { ID_ONE }
        )
        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.AuthenticationFailed),
            reader.loadDetailed()
        )
    }

    @Test
    fun missingKeyLoadReturnsTypedFailureAndRetainsCiphertext() {
        val fixture = fixture()
        val writer = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = TestResumeCrypto(ByteArray(32) { 1 }),
            idFactory = { ID_ONE }
        )
        assertTrue(writer.save(IdentityInput(fullName = "X"), deepResearch = false))
        val record = fixture.records.listFiles().orEmpty().single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val ciphertextBefore = record.readBytes()

        val reader = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = MissingKeyResumeCrypto(),
            idFactory = { ID_ONE }
        )
        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.KeyUnavailable),
            reader.loadDetailed()
        )
        assertTrue(record.exists())
        assertArrayEquals(ciphertextBefore, record.readBytes())
    }

    @Test
    fun oversizedPointerAndSyntacticallyValidEnvelopeAreRejected() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_ONE, ID_TWO))
        store.save(IdentityInput(fullName = "X"), false)
        val pointer = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME)
        pointer.writeText("x".repeat(ScanResumeStore.MAX_POINTER_BYTES.toInt() + 1))

        assertEquals(ResumeReadState.Invalid(ResumeInvalidReason.InvalidRequestId), store.loadDetailed())

        // Invalid pointer cleanup preserves the encrypted generation. Reconcile
        // that single unambiguous orphan before scheduling its replacement.
        assertTrue(store.loadDetailed() is ResumeReadState.Available)
        assertTrue(store.save(IdentityInput(fullName = "X"), false))
        val activeId = pointer.readText()
        val record = File(fixture.records, "$activeId${ScanResumeStore.RECORD_EXTENSION}")
        val oversizedCiphertext = Base64.getEncoder().encodeToString(
            ByteArray(ScanResumeStore.MAX_RECORD_BYTES + ScanResumeStore.GCM_TAG_BYTES + 1)
        )
        val validIv = Base64.getEncoder().encodeToString(ByteArray(ScanResumeStore.GCM_IV_BYTES))
        record.writeText(
            """{"formatVersion":${ScanResumeStore.FORMAT_VERSION},"ivBase64":"$validIv","ciphertextBase64":"$oversizedCiphertext"}"""
        )
        assertEquals(ResumeReadState.Invalid(ResumeInvalidReason.MalformedEnvelope), store.loadDetailed())
    }

    @Test
    fun negativeAndOverflowTimestampsAreRejectedWithoutFiles() {
        val negativeFixture = fixture()
        val negativeStore = store(negativeFixture, nowMillis = { -1L })
        assertEquals(
            ResumeWriteState.Invalid(ResumeInvalidReason.InvalidTimestamp),
            negativeStore.saveDetailed(IdentityInput(fullName = "X"), false)
        )
        assertTrue(negativeFixture.records.listFiles().orEmpty().isEmpty())

        val overflowFixture = fixture()
        val overflowStore = store(
            overflowFixture,
            nowMillis = { Long.MAX_VALUE - ScanResumeStore.TTL_MILLIS + 1L }
        )
        assertEquals(
            ResumeWriteState.Invalid(ResumeInvalidReason.InvalidTimestamp),
            overflowStore.saveDetailed(IdentityInput(fullName = "X"), false)
        )
        assertTrue(overflowFixture.records.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun listFilesNotDirectoryFailure() {
        val fixture = fixture()
        val store = store(fixture)
        fixture.records.mkdirs()
        File(fixture.records, "foo").writeText("x")
        fixture.records.deleteRecursively()
        fixture.records.writeText("not-a-dir")
        assertEquals(ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure), store.saveDetailed(IdentityInput(fullName = "X"), false))
        assertEquals(ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure), store.loadDetailed())
        assertEquals(ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure), store.clearDetailed())
    }

    @Test
    fun stalePointerOrMalformedRecordBlocksKeyCreation() {
        val stalePointerFixture = fixture()
        stalePointerFixture.records.mkdirs()
        File(stalePointerFixture.records, ScanResumeStore.POINTER_FILE_NAME).writeText("stale-pointer")
        val stalePointerCrypto = KeyCreationGuardResumeCrypto()
        val stalePointerStore = ScanResumeStore(
            recordsDir = stalePointerFixture.records,
            legacyDir = stalePointerFixture.legacy,
            crypto = stalePointerCrypto,
            idFactory = { ID_ONE }
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.PointerFailure),
            stalePointerStore.saveDetailed(IdentityInput(fullName = "Stale"), false)
        )
        assertTrue(stalePointerCrypto.allowKeyCreationCalls.isEmpty())
        assertTrue(stalePointerFixture.records.listFiles().orEmpty().none { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) })

        val malformedRecordFixture = fixture()
        malformedRecordFixture.records.mkdirs()
        val malformed = File(malformedRecordFixture.records, "not-a-uuid${ScanResumeStore.RECORD_EXTENSION}")
        malformed.writeText("malformed")
        val malformedRecordCrypto = KeyCreationGuardResumeCrypto()
        val malformedRecordStore = ScanResumeStore(
            recordsDir = malformedRecordFixture.records,
            legacyDir = malformedRecordFixture.legacy,
            crypto = malformedRecordCrypto,
            idFactory = { ID_ONE }
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.KeyUnavailable),
            malformedRecordStore.saveDetailed(IdentityInput(fullName = "Malformed"), false)
        )
        assertEquals(listOf(false), malformedRecordCrypto.allowKeyCreationCalls)
        assertTrue(malformed.exists())
        assertFalse(File(malformedRecordFixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun firstFreshSaveCapturesKeystoreCreationBeforePublishingPreparedMarker() {
        val fixture = fixture()
        val crypto = KeyCreationGuardResumeCrypto()
        val store = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = crypto,
            idFactory = { ID_ONE }
        )

        assertTrue(store.saveDetailed(IdentityInput(fullName = "Fresh Key"), false) is ResumeWriteState.Saved)
        assertEquals(listOf(true), crypto.allowKeyCreationCalls)
    }

    @Test
    fun preExistingResumeStateStillDeniesKeystoreCreationBeforePreparedMarker() {
        val fixture = fixture()
        assertTrue(store(fixture, ids = listOf(ID_ONE)).save(IdentityInput(fullName = "Existing"), false))
        val crypto = KeyCreationGuardResumeCrypto()
        val store = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = crypto,
            idFactory = { ID_TWO }
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.KeyUnavailable),
            store.prepareRequestDetailed(IdentityInput(fullName = "Second"), false, false)
        )
        assertEquals(listOf(false), crypto.allowKeyCreationCalls)
        assertTrue(File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun markerOnlyCrashIsDiscardedBeforeFreshKeystoreCreationRetry() {
        val fixture = fixture()
        fixture.records.mkdirs()
        val staleMarker = File(fixture.records, "$ID_ONE${ScanResumeStore.PREPARED_EXTENSION}")
        staleMarker.writeBytes(byteArrayOf(1))
        val crypto = KeyCreationGuardResumeCrypto()
        val store = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = crypto,
            idFactory = { ID_TWO }
        )

        assertTrue(store.saveDetailed(IdentityInput(fullName = "Retry After Marker"), false) is ResumeWriteState.Saved)
        assertEquals(listOf(true), crypto.allowKeyCreationCalls)
        assertFalse(staleMarker.exists())
    }

    @Test
    fun markerIsNotDiscardedWhenItsEncryptedRecordExists() {
        val fixture = fixture()
        val initial = store(fixture, ids = listOf(ID_ONE, ID_TWO))
        initial.saveDetailed(IdentityInput(fullName = "Existing"), false) as ResumeWriteState.Saved
        val prepared = initial.prepareRequestDetailed(IdentityInput(fullName = "Prepared"), false, false)
            as ResumeWriteState.Saved
        val crypto = KeyCreationGuardResumeCrypto()
        val retry = ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = crypto,
            idFactory = { ID_C }
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.KeyUnavailable),
            retry.prepareRequestDetailed(IdentityInput(fullName = "Must Not Create"), false, false)
        )
        assertEquals(listOf(false), crypto.allowKeyCreationCalls)
        assertTrue(File(fixture.records, "${prepared.point.requestId}${ScanResumeStore.PREPARED_EXTENSION}").exists())
        assertTrue(File(fixture.records, "${prepared.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun uniqueTempCleanup() {
        val fixture = fixture()
        val store = store(fixture)
        store.save(IdentityInput(fullName = "X"), false)
        assertTrue(fixture.records.listFiles()!!.none { it.name.contains(".tmp") })
    }

    @Test
    fun injectedDirectorySyncAtomicFailurePreservesPriorRecordAndPointer() {
        val fixture = fixture()
        val store1 = store(fixture, ids = listOf(ID_ONE))
        assertTrue(store1.save(IdentityInput(fullName = "Prior"), false))
        val priorRecord = File(fixture.records, ID_ONE + ScanResumeStore.RECORD_EXTENSION).readBytes()
        val priorPointer = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readBytes()

        var syncCount = 0
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (dir.name == "records" && ++syncCount == 3) throw java.io.IOException("Sync failed")
            }
        }
        val store2 = ScanResumeStore(fixture.records, TestResumeCrypto(), { 1000L }, { ID_TWO }, fixture.legacy, failingSyncer)

        assertEquals(ResumeWriteState.StorageFailure(ResumeStorageReason.PointerFailure), store2.saveDetailed(IdentityInput(fullName = "New"), false))

        assertArrayEquals(priorRecord, File(fixture.records, ID_ONE + ScanResumeStore.RECORD_EXTENSION).readBytes())
        assertArrayEquals(priorPointer, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readBytes())
        assertTrue(File(fixture.records, ID_TWO + ScanResumeStore.RECORD_EXTENSION).exists())
        assertTrue(File(fixture.records, ID_TWO + ScanResumeStore.PREPARED_EXTENSION).exists())
        val state = store1.loadDetailed() as ResumeReadState.Available
        assertEquals("Prior", state.point.input.fullName)
        assertEquals(ID_ONE, state.point.requestId)
    }

    @Test
    fun priorRecordDeletionSyncFailureRollsBackNewRecordAndRestoresPriorState() {
        val fixture = fixture()
        val priorStore = store(fixture, ids = listOf(ID_ONE))
        assertTrue(priorStore.save(IdentityInput(fullName = "Prior"), false))
        val priorRecord = File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").readBytes()
        val priorPointer = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readBytes()

        var syncCount = 0
        var priorDeletionFailureInjected = false
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (++syncCount == 4) {
                    priorDeletionFailureInjected = true
                    throw java.io.IOException("prior deletion fsync failed")
                }
            }
        }
        val nextStore = ScanResumeStore(
            fixture.records,
            fixture.legacy,
            TestResumeCrypto(),
            { 2_000L },
            { ID_TWO },
            failingSyncer
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure),
            nextStore.saveDetailed(IdentityInput(fullName = "New"), false)
        )
        assertTrue(priorDeletionFailureInjected)
        assertTrue(syncCount >= 3)
        assertArrayEquals(priorRecord, File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").readBytes())
        assertArrayEquals(priorPointer, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readBytes())
        assertFalse(File(fixture.records, "$ID_TWO${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals("Prior", (priorStore.loadDetailed() as ResumeReadState.Available).point.input.fullName)
    }

    @Test
    fun legacySaveRejectsPreparedUuidCollisionWithoutOverwritingEitherGeneration() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_ONE, ID_TWO, ID_TWO))
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false)
            as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        val preparedFile = File(fixture.records, "$ID_TWO${ScanResumeStore.RECORD_EXTENSION}")
        val preparedBytes = preparedFile.readBytes()

        assertEquals(
            ResumeWriteState.Invalid(ResumeInvalidReason.InvalidRequestId),
            store.saveDetailed(IdentityInput(fullName = "Collision"), false)
        )
        assertArrayEquals(preparedBytes, preparedFile.readBytes())
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertEquals(
            prepared.point.input,
            (store.loadPreparedRequestDetailed(prepared.point.requestId) as ResumeReadState.Available).point.input
        )
    }

    @Test
    fun legacyMarkerDeletionFailurePreservesCurrentEncryptedGeneration() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_ONE, ID_TWO))
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false)
            as ResumeWriteState.Saved
        fixture.legacy.mkdirs()
        val markerDirectory = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        markerDirectory.mkdirs()
        File(markerDirectory, "keep").writeText("plaintext marker cannot be removed")

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.LegacyDeletionFailure),
            store.saveDetailed(IdentityInput(fullName = "Rejected B"), false)
        )
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertTrue(File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertFalse(File(fixture.records, "$ID_TWO${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertTrue(markerDirectory.exists())
    }

    @Test
    fun failedEncryptionNeverDeletesTheOnlyLegacyResumeMarker() {
        val fixture = fixture()
        fixture.legacy.mkdirs()
        val legacy = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        legacy.writeText("{\"fullName\":\"Recoverable Legacy\"}")
        val failingCrypto = object : ResumeCrypto {
            override fun seal(
                plaintext: ByteArray,
                aad: ByteArray,
                allowKeyCreation: Boolean
            ): SealedResumePayload = throw ResumeCryptoFailure(ResumeStorageReason.KeyFailure)

            override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray =
                throw ResumeCryptoFailure(ResumeStorageReason.KeyFailure)
        }
        val store = ScanResumeStore(
            fixture.records,
            fixture.legacy,
            failingCrypto,
            { 1_000L },
            { ID_ONE },
            object : DirectorySyncer { override fun sync(dir: File) = Unit }
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.KeyFailure),
            store.saveDetailed(IdentityInput(fullName = "New"), false)
        )
        assertTrue(legacy.exists())
        assertTrue(fixture.records.listFiles().orEmpty().none {
            it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) ||
                it.name == ScanResumeStore.POINTER_FILE_NAME
        })
    }

    @Test
    fun legacySaveRejectsMalformedOrMissingPriorPointerTargetBeforeWriting() {
        val malformedFixture = fixture()
        malformedFixture.records.mkdirs()
        val malformedPointer = File(malformedFixture.records, ScanResumeStore.POINTER_FILE_NAME)
        malformedPointer.writeText("malformed-pointer")
        val malformedStore = store(malformedFixture, ids = listOf(ID_TWO))
        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.PointerFailure),
            malformedStore.saveDetailed(IdentityInput(fullName = "New"), false)
        )
        assertEquals("malformed-pointer", malformedPointer.readText())
        assertFalse(File(malformedFixture.records, "$ID_TWO${ScanResumeStore.RECORD_EXTENSION}").exists())

        val missingFixture = fixture()
        missingFixture.records.mkdirs()
        val stalePointer = File(missingFixture.records, ScanResumeStore.POINTER_FILE_NAME)
        stalePointer.writeText(ID_ONE)
        val missingStore = store(missingFixture, ids = listOf(ID_TWO))
        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure),
            missingStore.saveDetailed(IdentityInput(fullName = "New"), false)
        )
        assertEquals(ID_ONE, stalePointer.readText())
        assertFalse(File(missingFixture.records, "$ID_TWO${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun missingOrBlankPointerTargetIsClearedFailClosed() {
        val missingFixture = fixture()
        missingFixture.records.mkdirs()
        val missingPointer = File(missingFixture.records, ScanResumeStore.POINTER_FILE_NAME)
        missingPointer.writeText(ID_ONE)
        assertEquals(ResumeReadState.Missing, store(missingFixture).loadDetailed())
        assertFalse(missingPointer.exists())

        val blankFixture = fixture()
        blankFixture.records.mkdirs()
        val blankPointer = File(blankFixture.records, ScanResumeStore.POINTER_FILE_NAME)
        blankPointer.writeText("   ")
        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.InvalidRequestId),
            store(blankFixture).loadDetailed()
        )
        assertFalse(blankPointer.exists())
    }

    @Test
    fun clearRequestPointerSyncFailureRetainsEncryptedRecord() {
        val fixture = fixture()
        val initial = store(fixture, ids = listOf(ID_ONE))
        val saved = initial.saveDetailed(IdentityInput(fullName = "Active"), false)
            as ResumeWriteState.Saved
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                throw java.io.IOException("pointer fsync failed")
            }
        }
        val clearing = ScanResumeStore(
            fixture.records,
            fixture.legacy,
            TestResumeCrypto(),
            { 2_000L },
            { ID_TWO },
            failingSyncer
        )

        assertFalse(clearing.clearRequest(saved.point.requestId))
        assertTrue(File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun clearDetailedPointerSyncFailureDoesNotDeleteEncryptedRecords() {
        val fixture = fixture()
        val initial = store(fixture, ids = listOf(ID_ONE))
        initial.saveDetailed(IdentityInput(fullName = "Active"), false) as ResumeWriteState.Saved
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                throw java.io.IOException("pointer fsync failed")
            }
        }
        val clearing = ScanResumeStore(
            fixture.records,
            fixture.legacy,
            TestResumeCrypto(),
            { 2_000L },
            { ID_TWO },
            failingSyncer
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure),
            clearing.clearDetailed()
        )
        assertTrue(File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun legacyPointerRollbackNeverLeavesPointerWithoutItsRecord() {
        val fixture = fixture()
        val prior = store(fixture, ids = listOf(ID_ONE))
        prior.saveDetailed(IdentityInput(fullName = "Prior"), false) as ResumeWriteState.Saved
        var syncCount = 0
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                syncCount += 1
                if (syncCount == 3 || syncCount == 4) {
                    throw java.io.IOException("simulated pointer fsync failure")
                }
            }
        }
        val next = ScanResumeStore(
            fixture.records,
            fixture.legacy,
            TestResumeCrypto(),
            { 2_000L },
            { ID_TWO },
            failingSyncer
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.PointerFailure),
            next.saveDetailed(IdentityInput(fullName = "New"), false)
        )
        val pointer = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText()
        assertTrue(File(fixture.records, "$pointer${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun migrationCryptoFailurePreservesOnlyRecoverableLegacyMarker() {
        val fixture = fixture()
        fixture.legacy.mkdirs()
        val legacy = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        legacy.writeText("{\"fullName\":\"Legacy User\"}")
        val failingCrypto = object : ResumeCrypto {
            override fun seal(plaintext: ByteArray, aad: ByteArray, allowKeyCreation: Boolean): SealedResumePayload {
                throw ResumeCryptoFailure(ResumeStorageReason.KeyFailure)
            }
            override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray = throw ResumeCryptoFailure(ResumeStorageReason.KeyFailure)
        }
        val store = ScanResumeStore(fixture.records, failingCrypto, legacyDir = fixture.legacy)

        assertEquals(ResumeReadState.StorageFailure(ResumeStorageReason.KeyFailure), store.loadDetailed())
        assertTrue(legacy.exists())
    }

    @Test
    fun legacyDeletionFailure() {
        val fixture = fixture()
        fixture.legacy.mkdirs()
        val legacyMarkerPath = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        assertTrue(legacyMarkerPath.mkdirs())
        File(legacyMarkerPath, "keep").writeText("do not remove")

        val result = store(fixture).loadDetailed()
        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure),
            result
        )
        assertTrue(legacyMarkerPath.exists())
    }

    @Test
    fun clearTempIdempotency() {
        val fixture = fixture()
        val store = store(fixture)
        fixture.records.mkdirs()
        File(fixture.records, "foo.tmp").writeText("junk")
        assertEquals(ResumeWriteState.Cleared, store.clearDetailed())
        assertFalse(File(fixture.records, "foo.tmp").exists())
        assertEquals(ResumeWriteState.Cleared, store.clearDetailed())
    }

    @Test
    fun concurrentSavesLeaveOneValidActiveRecord() {
        val fixture = fixture()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val stores = listOf(
            store(fixture, ids = listOf(ID_ONE)),
            store(fixture, ids = listOf(ID_TWO))
        )
        stores.forEachIndexed { index, saveStore ->
            thread(start = true) {
                try {
                    start.await()
                    val result = saveStore.saveDetailed(IdentityInput(fullName = "Concurrent $index"), false)
                    if (result !is ResumeWriteState.Saved) failures += AssertionError("save failed: $result")
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()

        assertTrue(failures.toString(), failures.isEmpty())
        val records = fixture.records.listFiles().orEmpty().filter { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        assertEquals(1, records.size)
        val pointer = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText()
        assertEquals(pointer + ScanResumeStore.RECORD_EXTENSION, records.single().name)
        assertTrue(store(fixture).loadDetailed() is ResumeReadState.Available)
    }

    private fun fixture(): Fixture {
        val root = File.createTempFile("resume-secure", "").also {
            it.delete()
            it.mkdirs()
        }
        fixtures += root
        return Fixture(
            records = File(root, "records"),
            legacy = File(root, "legacy")
        )
    }

    private fun store(
        fixture: Fixture,
        nowMillis: () -> Long = { 1_000L },
        ids: List<String> = listOf(ID_ONE)
    ): ScanResumeStore {
        val sequence = ids.iterator()
        return ScanResumeStore(
            recordsDir = fixture.records,
            legacyDir = fixture.legacy,
            crypto = TestResumeCrypto(),
            nowMillis = nowMillis,
            idFactory = { if (sequence.hasNext()) sequence.next() else ID_ONE }
        )
    }

    private fun completeInput() = IdentityInput(
        fullName = "Jane Doe",
        aliases = listOf("J. Doe"),
        primaryUsername = "janedoe",
        usernames = listOf("janedoe", "jane_d"),
        emails = listOf("jane@example.com"),
        phones = listOf("+1-555-0100"),
        organizations = listOf("Acme"),
        locations = listOf("Berlin"),
        profileUrls = listOf("https://github.com/janedoe"),
        selfieUri = "content://example/selfie"
    )

    private fun writePriorRecordWithoutStrongCorrelation(fixture: Fixture, input: IdentityInput) {
        val createdAt = 1_000L
        val expiresAt = createdAt + ScanResumeStore.TTL_MILLIS
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val plaintext = """{
            "formatVersion":${ScanResumeStore.FORMAT_VERSION},
            "requestId":"$ID_ONE",
            "createdAtEpochMillis":$createdAt,
            "updatedAtEpochMillis":$createdAt,
            "expiresAtEpochMillis":$expiresAt,
            "input":${json.encodeToString(input)},
            "deepResearch":true,
            "scanMode":"Deep"
        }""".trimIndent().toByteArray(Charsets.UTF_8)
        val crypto = TestResumeCrypto()
        val sealed = crypto.seal(
            plaintext,
            "${ScanResumeStore.FORMAT_VERSION}:$ID_ONE".toByteArray(Charsets.UTF_8),
            allowKeyCreation = true
        )
        fixture.records.mkdirs()
        File(fixture.records, "$ID_ONE${ScanResumeStore.RECORD_EXTENSION}").writeText(
            """{"formatVersion":${ScanResumeStore.FORMAT_VERSION},"ivBase64":"${Base64.getEncoder().encodeToString(sealed.iv)}","ciphertextBase64":"${Base64.getEncoder().encodeToString(sealed.ciphertext)}"}"""
        )
        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).writeText(ID_ONE)
    }

    private data class Fixture(val records: File, val legacy: File)

    private companion object {
        const val ID_ONE = "123e4567-e89b-12d3-a456-426614174000"
        const val ID_TWO = "123e4567-e89b-12d3-a456-426614174001"
        const val ID_C = "123e4567-e89b-12d3-a456-426614174002"
        const val OWNER_ONE = "223e4567-e89b-42d3-a456-426614174000"
        const val OWNER_TWO = "223e4567-e89b-42d3-a456-426614174001"
    }
}

private class TestResumeCrypto(
    keyBytes: ByteArray = ByteArray(32) { (it + 1).toByte() }
) : ResumeCrypto {
    private val key = javax.crypto.spec.SecretKeySpec(keyBytes.copyOf(), "AES")

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

private class MissingKeyResumeCrypto : ResumeCrypto {
    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
        allowKeyCreation: Boolean
    ): SealedResumePayload = throw ResumeCryptoFailure(ResumeStorageReason.KeyUnavailable)

    override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray =
        throw ResumeCryptoFailure(ResumeStorageReason.KeyUnavailable)
}

private class KeyCreationGuardResumeCrypto : ResumeCrypto {
    private val delegate = TestResumeCrypto()
    val allowKeyCreationCalls = mutableListOf<Boolean>()

    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
        allowKeyCreation: Boolean
    ): SealedResumePayload {
        allowKeyCreationCalls += allowKeyCreation
        if (!allowKeyCreation) {
            throw ResumeCryptoFailure(ResumeStorageReason.KeyUnavailable)
        }
        return delegate.seal(plaintext, aad, allowKeyCreation)
    }

    override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray =
        delegate.open(payload, aad)
}

private class OversizedCiphertextResumeCrypto : ResumeCrypto {
    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
        allowKeyCreation: Boolean
    ): SealedResumePayload = SealedResumePayload(
        iv = ByteArray(ScanResumeStore.GCM_IV_BYTES),
        ciphertext = ByteArray(ScanResumeStore.MAX_RECORD_BYTES + ScanResumeStore.GCM_TAG_BYTES + 1)
    )

    override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray =
        error("open should not be reached")
}
