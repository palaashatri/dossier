package io.dossier.app.domain.scanner

import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.model.IdentityInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ScanResumeStoreTwoPhaseTest {

    private val fixtures = mutableListOf<File>()

    @After
    fun cleanup() {
        DiscoveryScanPreferences.reset()
        fixtures.forEach(File::deleteRecursively)
    }

    @Test
    fun prepareKeepsAActiveAndLoadsBByExactIdWithoutPlaintextMetadata() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        val inputA = IdentityInput(fullName = "Active A", emails = listOf("active-a@example.test"))
        val inputB = IdentityInput(fullName = "Prepared B", emails = listOf("prepared-b@example.test"))

        val active = store.saveDetailed(inputA, deepResearch = false) as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(
            input = inputB,
            deepResearch = true,
            strongFaceCorrelation = true
        ) as ResumeWriteState.Saved

        val pointerFile = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME)
        assertEquals(active.point.requestId, pointerFile.readText())
        assertTrue(File(fixture.records, "${ID_A}${ScanResumeStore.RECORD_EXTENSION}").exists())
        val preparedFile = File(fixture.records, "${prepared.point.requestId}${ScanResumeStore.RECORD_EXTENSION}")
        assertTrue(preparedFile.exists())
        assertFalse(preparedFile.name.contains(inputB.fullName))
        assertFalse(pointerFile.readText().contains(inputB.fullName))
        assertFalse(pointerFile.readText().contains(inputB.emails.single()))
        assertFalse(preparedFile.readText().contains(inputB.fullName))
        assertFalse(preparedFile.readText().contains(inputB.emails.single()))

        assertEquals(
            inputA,
            (store.loadDetailed() as ResumeReadState.Available).point.input
        )
        val exactB = store.loadPreparedRequestDetailed(prepared.point.requestId)
            as ResumeReadState.Available
        assertEquals(inputB, exactB.point.input)
        assertTrue(exactB.point.deepResearch)
        assertTrue(exactB.point.strongFaceCorrelation)
    }

    @Test
    fun promotingBSwitchesPointerAndRemovesAOnlyAfterDurableCompletion() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        val inputA = IdentityInput(fullName = "Active A")
        val inputB = IdentityInput(fullName = "Prepared B")
        val active = store.saveDetailed(inputA, deepResearch = false) as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(inputB, deepResearch = true, strongFaceCorrelation = false)
            as ResumeWriteState.Saved

        assertTrue(store.promotePreparedRequest(prepared.point.requestId))
        assertEquals(
            prepared.point.requestId,
            File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText()
        )
        assertFalse(File(fixture.records, "${active.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertTrue(File(fixture.records, "${prepared.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals(
            inputB,
            (store.loadDetailed() as ResumeReadState.Available).point.input
        )
        assertEquals(ResumeReadState.Missing, store.loadRequestDetailed(active.point.requestId))
        assertTrue(store.loadPreparedRequestDetailed(prepared.point.requestId) is ResumeReadState.Available)
    }

    @Test
    fun discardingBLeavesAAndRefusesToDeleteCurrentGeneration() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved

        assertTrue(store.discardPreparedRequest(prepared.point.requestId))
        assertFalse(File(fixture.records, "${prepared.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertTrue(store.loadDetailed() is ResumeReadState.Available)
        assertFalse(store.discardPreparedRequest(active.point.requestId))
        assertTrue(File(fixture.records, "${active.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
    }

    @Test
    fun promotionDeletionFailureRollsBackPointerAndBothRecords() {
        val fixture = fixture()
        var syncCount = 0
        var armed = false
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (armed && ++syncCount == 2) {
                    throw java.io.IOException("simulated power-loss sync failure")
                }
            }
        }
        val store = store(
            fixture,
            ids = listOf(ID_A, ID_B),
            dirSyncer = failingSyncer
        )
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        armed = true
        syncCount = 0

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure),
            store.promotePreparedRequestDetailed(prepared.point.requestId)
        )
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertTrue(File(fixture.records, "${active.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertFalse(File(fixture.records, "${prepared.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals("Active A", (store.loadDetailed() as ResumeReadState.Available).point.input.fullName)
    }

    @Test
    fun promotionRollbackSyncFailureNeverLeavesPointerWithoutItsRecord() {
        val fixture = fixture()
        var syncCount = 0
        var armed = false
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (!armed) return
                syncCount += 1
                // A deletion fails after unlink, then pointer restoration fails
                // after its atomic move. Read-back verification must keep the
                // pointer/record pair coherent through both partial commits.
                if (syncCount == 2 || syncCount == 4) {
                    throw java.io.IOException("simulated rollback sync failure")
                }
            }
        }
        val store = store(
            fixture,
            ids = listOf(ID_A, ID_B),
            dirSyncer = failingSyncer
        )
        store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        armed = true
        syncCount = 0

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure),
            store.promotePreparedRequestDetailed(prepared.point.requestId)
        )
        val pointer = File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText()
        assertTrue(File(fixture.records, "$pointer${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun malformedAndExpiredPreparedRecordsFailClosedWithoutTouchingA() {
        val fixture = fixture()
        var now = 1_000L
        val store = store(fixture, ids = listOf(ID_A, ID_B, ID_C), nowMillis = { now })
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        val malformed = store.prepareRequestDetailed(IdentityInput(fullName = "Malformed B"), false, false)
            as ResumeWriteState.Saved
        val malformedFile = File(fixture.records, "${malformed.point.requestId}${ScanResumeStore.RECORD_EXTENSION}")
        malformedFile.writeText("not-an-envelope")

        assertEquals(
            ResumeReadState.Invalid(ResumeInvalidReason.MalformedEnvelope),
            store.loadPreparedRequestDetailed(malformed.point.requestId)
        )
        assertFalse(malformedFile.exists())
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertTrue(store.loadDetailed() is ResumeReadState.Available)

        val expired = store.prepareRequestDetailed(IdentityInput(fullName = "Expired C"), false, false)
            as ResumeWriteState.Saved
        now = expired.point.expiresAtEpochMillis
        assertEquals(ResumeReadState.Expired, store.loadPreparedRequestDetailed(expired.point.requestId))
        assertFalse(File(fixture.records, "${expired.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertTrue(File(fixture.records, "${active.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun preparedUuidCollisionNeverOverwritesCurrentRecord() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_A))
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false)
            as ResumeWriteState.Saved

        assertEquals(
            ResumeWriteState.Invalid(ResumeInvalidReason.InvalidRequestId),
            store.prepareRequestDetailed(IdentityInput(fullName = "Collision B"), false, false)
        )
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertEquals("Active A", (store.loadDetailed() as ResumeReadState.Available).point.input.fullName)
    }

    @Test
    fun malformedCurrentPointerBlocksPromotionAndPreservesBothEncryptedRecords() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).writeText("malformed-pointer")

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure),
            store.promotePreparedRequestDetailed(prepared.point.requestId)
        )
        assertTrue(File(fixture.records, "${ID_A}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertTrue(File(fixture.records, "${ID_B}${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals("malformed-pointer", File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
    }

    @Test
    fun preparingEncryptedRequestDeletesLegacyPlaintextMarker() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        fixture.legacy.mkdirs()
        val legacy = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        legacy.writeText("plaintext-identity-marker")

        assertTrue(
            store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
                is ResumeWriteState.Saved
        )
        assertFalse(legacy.exists())
    }

    @Test
    fun preparedBIsNeverAutoActivatedWhenAPointerDisappearsOrExpires() {
        val fixture = fixture()
        var now = 1_000L
        val store = store(
            fixture,
            ids = listOf(ID_A, ID_B),
            nowMillis = { now }
        )
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false)
            as ResumeWriteState.Saved
        now = 2_000L
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved

        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).delete()
        val recovered = store.loadDetailed() as ResumeReadState.Available
        assertEquals(active.point.requestId, recovered.point.requestId)
        assertEquals("Active A", recovered.point.input.fullName)

        now = active.point.expiresAtEpochMillis
        assertEquals(ResumeReadState.Expired, store.loadDetailed())
        assertEquals(ResumeReadState.Missing, store.loadDetailed())
        assertEquals(
            "Prepared B",
            (store.loadPreparedRequestDetailed(prepared.point.requestId) as ResumeReadState.Available)
                .point.input.fullName
        )
        assertFalse(File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).exists())
    }

    @Test
    fun uncertainLegacyUnlinkRetainsEncryptedPreparedRecovery() {
        val fixture = fixture()
        fixture.legacy.mkdirs()
        val legacy = File(fixture.legacy, ScanResumeStore.LEGACY_FILE_NAME)
        legacy.writeText("{\"fullName\":\"Legacy\"}")
        var syncCount = 0
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                syncCount += 1
                if (syncCount == 3) {
                    throw java.io.IOException("legacy unlink fsync failed")
                }
            }
        }
        val store = store(
            fixture,
            ids = listOf(ID_A),
            dirSyncer = failingSyncer
        )

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.LegacyDeletionFailure),
            store.prepareRequestDetailed(IdentityInput(fullName = "Encrypted Recovery"), false, false)
        )
        assertFalse(legacy.exists())
        assertTrue(File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertTrue(File(fixture.records, "$ID_A${ScanResumeStore.PREPARED_EXTENSION}").exists())
        assertEquals(
            "Encrypted Recovery",
            (store.loadPreparedRequestDetailed(ID_A) as ResumeReadState.Available).point.input.fullName
        )
    }

    @Test
    fun interruptedClearRequestLeavesDurableGuardAndRetryCompletesExactGeneration() {
        val fixture = fixture()
        val initial = store(fixture, ids = listOf(ID_A))
        val saved = initial.saveDetailed(
            IdentityInput(fullName = "Sensitive Clear Target", emails = listOf("secret@example.test")),
            deepResearch = false
        ) as ResumeWriteState.Saved
        var syncCount = 0
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (++syncCount == 3) {
                    throw java.io.IOException("record unlink fsync failed")
                }
            }
        }
        val clearing = store(fixture, ids = listOf(ID_B), dirSyncer = failingSyncer)
        val record = File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}")
        val tombstone = File(fixture.records, "$ID_A${ScanResumeStore.CLEAR_TOMBSTONE_EXTENSION}")

        assertFalse(clearing.clearRequest(saved.point.requestId))
        assertTrue(tombstone.exists())
        assertFalse(tombstone.readText().contains("Sensitive Clear Target"))
        assertFalse(tombstone.readText().contains("secret@example.test"))

        // A fresh store must never promote a generation explicitly being
        // cleared, even when the pointer unlink already committed.
        val restarted = store(fixture, ids = listOf(ID_B))
        assertFalse(restarted.loadDetailed() is ResumeReadState.Available)

        // Retrying the exact request is idempotent even though its pointer is
        // already absent; it removes only the guarded generation.
        assertTrue(restarted.clearRequest(saved.point.requestId))
        assertFalse(record.exists())
        assertFalse(tombstone.exists())
        assertEquals(ResumeReadState.Missing, restarted.loadDetailed())
    }

    @Test
    fun interruptedClearDetailedBlocksAllOrphanRecoveryUntilRetry() {
        val fixture = fixture()
        val initial = store(fixture, ids = listOf(ID_A))
        initial.saveDetailed(
            IdentityInput(fullName = "Sensitive Clear All", emails = listOf("clear-all@example.test")),
            deepResearch = false
        ) as ResumeWriteState.Saved
        var syncCount = 0
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (++syncCount == 3) {
                    throw java.io.IOException("record unlink fsync failed")
                }
            }
        }
        val clearing = store(fixture, ids = listOf(ID_B), dirSyncer = failingSyncer)
        val globalGuard = File(fixture.records, "clear.guard")

        assertEquals(
            ResumeWriteState.StorageFailure(ResumeStorageReason.IoFailure),
            clearing.clearDetailed()
        )
        assertTrue(globalGuard.exists())
        assertFalse(globalGuard.readText().contains("Sensitive Clear All"))
        assertFalse(globalGuard.readText().contains("clear-all@example.test"))

        val restarted = store(fixture, ids = listOf(ID_B))
        assertFalse(restarted.loadDetailed() is ResumeReadState.Available)
        assertEquals(ResumeWriteState.Cleared, restarted.clearDetailed())
        assertFalse(globalGuard.exists())
        assertEquals(ResumeReadState.Missing, restarted.loadDetailed())
    }

    @Test
    fun expiredCleanupKeepsGuardedOrphanUnavailableAfterPointerCommit() {
        val fixture = fixture()
        var now = 1_000L
        val initial = store(fixture, ids = listOf(ID_A), nowMillis = { now })
        val saved = initial.saveDetailed(IdentityInput(fullName = "Expired Clear Target"), false)
            as ResumeWriteState.Saved
        now = saved.point.expiresAtEpochMillis
        var syncCount = 0
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (++syncCount == 2) {
                    throw java.io.IOException("expired pointer unlink fsync failed")
                }
            }
        }
        val expiring = store(fixture, ids = listOf(ID_B), nowMillis = { now }, dirSyncer = failingSyncer)

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.PointerFailure),
            expiring.loadDetailed()
        )
        assertTrue(File(fixture.records, "$ID_A${ScanResumeStore.CLEAR_TOMBSTONE_EXTENSION}").exists())
        assertFalse(store(fixture, ids = listOf(ID_B), nowMillis = { now }).loadDetailed() is ResumeReadState.Available)
    }

    @Test
    fun tombstonedUuidCannotBeReusedForPreparedRecord() {
        val fixture = fixture()
        val initial = store(fixture, ids = listOf(ID_A))
        val saved = initial.saveDetailed(IdentityInput(fullName = "Tombstoned UUID"), false)
            as ResumeWriteState.Saved
        var syncCount = 0
        val failingSyncer = object : DirectorySyncer {
            override fun sync(dir: File) {
                if (++syncCount == 2) throw java.io.IOException("pointer unlink fsync failed")
            }
        }
        assertFalse(store(fixture, ids = listOf(ID_B), dirSyncer = failingSyncer).clearRequest(saved.point.requestId))

        assertEquals(
            ResumeWriteState.Invalid(ResumeInvalidReason.InvalidRequestId),
            store(fixture, ids = listOf(ID_A)).prepareRequestDetailed(
                IdentityInput(fullName = "Collision"),
                deepResearch = false,
                strongFaceCorrelation = false
            )
        )
    }

    @Test
    fun clearRequestRemovesExactUnmarkedOrphanWhenPointerIsAbsent() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A))
        val saved = store.saveDetailed(IdentityInput(fullName = "Orphan To Clear"), false)
            as ResumeWriteState.Saved
        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).delete()

        assertTrue(store.clearRequest(saved.point.requestId))
        assertFalse(File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertFalse(File(fixture.records, "$ID_A${ScanResumeStore.CLEAR_TOMBSTONE_EXTENSION}").exists())
        assertEquals(ResumeReadState.Missing, store.loadDetailed())
    }

    @Test
    fun clearRequestRemovesExactPreparedMarkerWithoutTouchingDifferentPointer() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false)
            as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        File(fixture.records, "$ID_B${ScanResumeStore.RECORD_EXTENSION}").delete()

        assertTrue(store.clearRequest(prepared.point.requestId))
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertTrue(File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertFalse(File(fixture.records, "$ID_B${ScanResumeStore.PREPARED_EXTENSION}").exists())
        assertEquals("Active A", (store.loadDetailed() as ResumeReadState.Available).point.input.fullName)
    }

    @Test
    fun promotionRejectsPointerLossWithUnrelatedOrphanThenSucceedsAfterRecovery() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        val active = store.saveDetailed(IdentityInput(fullName = "Active A"), false)
            as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).delete()

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure),
            store.promotePreparedRequestDetailed(prepared.point.requestId)
        )
        assertEquals(
            active.point.requestId,
            (store.loadDetailed() as ResumeReadState.Available).point.requestId
        )
        assertEquals(active.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())

        assertTrue(store.promotePreparedRequest(prepared.point.requestId))
        assertEquals(prepared.point.requestId, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertFalse(File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals("Prepared B", (store.loadDetailed() as ResumeReadState.Available).point.input.fullName)

        assertTrue(store.clearRequest(prepared.point.requestId))
        assertFalse(File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertFalse(File(fixture.records, "$ID_B${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertEquals(ResumeReadState.Missing, store.loadDetailed())
    }

    @Test
    fun promotionRejectsTombstonedPriorPointer() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        val prepared = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        File(fixture.records, "$ID_A${ScanResumeStore.CLEAR_TOMBSTONE_EXTENSION}").writeBytes(byteArrayOf(1))

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure),
            store.promotePreparedRequestDetailed(prepared.point.requestId)
        )
        assertEquals(ID_A, File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).readText())
        assertTrue(File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertTrue(File(fixture.records, "$ID_B${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    @Test
    fun promotionKeepsUnrelatedPreparedGenerationNonRecoverable() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B, ID_C))
        store.saveDetailed(IdentityInput(fullName = "Active A"), false) as ResumeWriteState.Saved
        val preparedB = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared B"), false, false)
            as ResumeWriteState.Saved
        val preparedC = store.prepareRequestDetailed(IdentityInput(fullName = "Prepared C"), false, false)
            as ResumeWriteState.Saved

        assertTrue(store.promotePreparedRequest(preparedB.point.requestId))
        assertTrue(store.clearRequest(preparedB.point.requestId))
        assertEquals(ResumeReadState.Missing, store.loadDetailed())
        assertEquals(
            "Prepared C",
            (store.loadPreparedRequestDetailed(preparedC.point.requestId) as ResumeReadState.Available)
                .point.input.fullName
        )
    }

    @Test
    fun multipleUnguardedOrphansFailClosedWithoutPointerMutation() {
        val fixture = fixture()
        val store = store(fixture, ids = listOf(ID_A, ID_B))
        store.saveDetailed(IdentityInput(fullName = "Orphan A"), false) as ResumeWriteState.Saved
        val preparedB = store.prepareRequestDetailed(IdentityInput(fullName = "Orphan B"), false, false)
            as ResumeWriteState.Saved
        File(fixture.records, "$ID_B${ScanResumeStore.PREPARED_EXTENSION}").delete()
        File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).delete()

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.IoFailure),
            store.loadDetailed()
        )
        assertFalse(File(fixture.records, ScanResumeStore.POINTER_FILE_NAME).exists())
        assertTrue(File(fixture.records, "$ID_A${ScanResumeStore.RECORD_EXTENSION}").exists())
        assertTrue(File(fixture.records, "${preparedB.point.requestId}${ScanResumeStore.RECORD_EXTENSION}").exists())
    }

    private fun fixture(): Fixture {
        val root = File.createTempFile("resume-two-phase", "")
        root.delete()
        root.mkdirs()
        fixtures += root
        return Fixture(File(root, "records"), File(root, "legacy"))
    }

    private fun store(
        fixture: Fixture,
        ids: List<String>,
        nowMillis: () -> Long = { 1_000L },
        dirSyncer: DirectorySyncer = object : DirectorySyncer {
            override fun sync(dir: File) = Unit
        }
    ): ScanResumeStore {
        val iterator = ids.iterator()
        return ScanResumeStore(
            fixture.records,
            fixture.legacy,
            DeterministicTwoPhaseCrypto(),
            nowMillis,
            { iterator.next() },
            dirSyncer
        )
    }

    private data class Fixture(val records: File, val legacy: File)

    private companion object {
        const val ID_A = "123e4567-e89b-42d3-a456-426614174000"
        const val ID_B = "123e4567-e89b-42d3-a456-426614174001"
        const val ID_C = "123e4567-e89b-42d3-a456-426614174002"
    }
}

private class DeterministicTwoPhaseCrypto : ResumeCrypto {
    private val key = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES")
    private var sealCount = 0

    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
        allowKeyCreation: Boolean
    ): SealedResumePayload {
        val iv = ByteArray(ScanResumeStore.GCM_IV_BYTES) { index -> (sealCount + index + 1).toByte() }
        sealCount += 1
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return SealedResumePayload(iv, cipher.doFinal(plaintext))
    }

    override fun open(payload: SealedResumePayload, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(payload.ciphertext)
    }
}
