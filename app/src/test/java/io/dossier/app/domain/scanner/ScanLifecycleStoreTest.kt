package io.dossier.app.domain.scanner

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLifecycleStoreTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val request = "22222222-2222-4222-8222-222222222222"
    private val generation = "33333333-3333-4333-8333-333333333333"
    private val nextGeneration = "44444444-4444-4444-8444-444444444444"
    private val otherOwner = "55555555-5555-4555-8555-555555555555"
    private val otherRequest = "66666666-6666-4666-8666-666666666666"

    private fun record(
        phase: ScanLifecyclePhase = ScanLifecyclePhase.Enqueued,
        generationId: String = generation,
        updatedAt: Long = 100L,
        ownerId: String = owner,
        requestId: String = request
    ) = ScanLifecycleRecord(
        ownerId = ownerId,
        requestId = requestId,
        generation = generationId,
        phase = phase,
        updatedAtEpochMillis = updatedAt,
        resultReady = phase == ScanLifecyclePhase.Succeeded,
        errorCode = when (phase) {
            ScanLifecyclePhase.Failed -> ScanLifecycleErrors.SCAN_EXECUTION_FAILED
            ScanLifecyclePhase.CancelFailed -> ScanLifecycleErrors.CANCEL_REQUEST_FAILED
            else -> null
        }
    )

    @Test
    fun publishAndReadRoundTripIsOneCommitAndRemovesLegacyKeys() {
        val preferences = FakePreferences().apply {
            values["active_owner"] = owner
            values["active"] = true
        }
        val store = ScanLifecycleStore(preferences, nowEpochMillis = { 100L }, generationFactory = { generation })

        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record()))
        assertEquals(1, preferences.commitCount)
        assertFalse(preferences.contains("active_owner"))
        assertFalse(preferences.contains("active"))
        assertEquals(ScanLifecycleReadResult.Available(record()), store.read())
        assertEquals(0, preferences.applyCount)
    }

    @Test
    fun commitFalseIsExposedAndDoesNotPretendToSave() {
        val preferences = FakePreferences().apply { commitResult = false }
        val store = ScanLifecycleStore(preferences)

        assertEquals(ScanLifecycleWriteResult.CommitFailed, store.publish(record()))
        assertEquals(ScanLifecycleReadResult.Missing, store.read())
    }

    @Test
    fun transitionAndClearRequireExactSnapshot() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record()))

        assertEquals(
            ScanLifecycleWriteResult.GenerationMismatch,
            store.transition(
                expected = record(generationId = nextGeneration),
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 101L
            )
        )
        assertEquals(ScanLifecycleReadResult.Available(record()), store.read())

        assertEquals(
            ScanLifecycleWriteResult.Saved,
            store.transition(
                expected = record(),
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 101L
            )
        )
        assertEquals(
            ScanLifecycleWriteResult.GenerationMismatch,
            store.clear(record(generationId = nextGeneration))
        )
        assertTrue(store.read() is ScanLifecycleReadResult.Available)
        assertEquals(
            ScanLifecycleWriteResult.Cleared,
            store.clear(record(phase = ScanLifecyclePhase.Running, updatedAt = 101L))
        )
        assertEquals(ScanLifecycleReadResult.Missing, store.read())
    }

    @Test
    fun transitionRejectsStaleSameTimestampSnapshot() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        val initial = record(updatedAt = 100L)
        val newer = record(phase = ScanLifecyclePhase.Running, updatedAt = 100L)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(initial))

        assertEquals(
            ScanLifecycleWriteResult.Saved,
            store.transition(
                expected = initial,
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 100L
            )
        )
        assertEquals(
            ScanLifecycleWriteResult.RecordMismatch,
            store.transition(
                expected = initial,
                transition = ScanLifecycleTransition.RequestCancel,
                nowEpochMillis = 100L
            )
        )
        assertEquals(ScanLifecycleReadResult.Available(newer), store.read())
    }

    @Test
    fun transitionCannotRebindOwnerRequestOrGeneration() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        val initial = record()
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(initial))

        assertEquals(
            ScanLifecycleWriteResult.Saved,
            store.transition(
                expected = initial,
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 101L
            )
        )
        assertEquals(
            ScanLifecycleWriteResult.RecordMismatch,
            store.transition(
                expected = record(ownerId = otherOwner),
                transition = ScanLifecycleTransition.RequestCancel,
                nowEpochMillis = 102L
            )
        )
        assertEquals(
            ScanLifecycleWriteResult.RecordMismatch,
            store.transition(
                expected = record(requestId = otherRequest),
                transition = ScanLifecycleTransition.RequestCancel,
                nowEpochMillis = 102L
            )
        )
        assertEquals(
            ScanLifecycleWriteResult.GenerationMismatch,
            store.transition(
                expected = record(generationId = nextGeneration),
                transition = ScanLifecycleTransition.RequestCancel,
                nowEpochMillis = 102L
            )
        )
        assertEquals(
            ScanLifecycleReadResult.Available(record(phase = ScanLifecyclePhase.Running, updatedAt = 101L)),
            store.read()
        )
    }

    @Test
    fun transitionRejectsTimestampRegression() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        val current = record(updatedAt = 100L)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(current))

        assertEquals(
            ScanLifecycleWriteResult.TransitionRejected(ScanLifecycleRejectionReason.TimestampRegression),
            store.transition(
                expected = current,
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 99L
            )
        )
        assertEquals(ScanLifecycleReadResult.Available(current), store.read())
    }

    @Test
    fun publishCannotOverwriteAndTransitionRejectsStaleSameGenerationSnapshot() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        val initial = record(updatedAt = 100L)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(initial))
        assertEquals(
            ScanLifecycleWriteResult.AlreadyPresent,
            store.publish(record(generationId = nextGeneration, updatedAt = 101L))
        )
        assertEquals(ScanLifecycleReadResult.Available(initial), store.read())

        val first = record(phase = ScanLifecyclePhase.Running, updatedAt = 101L)
        assertEquals(
            ScanLifecycleWriteResult.Saved,
            store.transition(
                expected = initial,
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 101L
            )
        )
        assertEquals(
            ScanLifecycleWriteResult.RecordMismatch,
            store.transition(
                expected = initial,
                transition = ScanLifecycleTransition.RequestCancel,
                nowEpochMillis = 102L
            )
        )
        assertEquals(ScanLifecycleReadResult.Available(first), store.read())
    }

    @Test
    fun transitionRejectsIllegalTerminalAndUnpublishedSuccessWithoutPersisting() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        val failed = record(
            phase = ScanLifecyclePhase.Failed,
            updatedAt = 100L
        )
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(failed))
        assertEquals(
            ScanLifecycleWriteResult.TransitionRejected(ScanLifecycleRejectionReason.IllegalTransition),
            store.transition(
                expected = failed,
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 101L
            )
        )
        assertEquals(ScanLifecycleReadResult.Available(failed), store.read())

        assertEquals(ScanLifecycleWriteResult.Cleared, store.clear(failed))
        val running = record(phase = ScanLifecyclePhase.Running, updatedAt = 100L)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(running))
        assertEquals(
            ScanLifecycleWriteResult.TransitionRejected(ScanLifecycleRejectionReason.ResultNotPublished),
            store.transition(
                expected = running,
                transition = ScanLifecycleTransition.MarkSucceeded,
                nowEpochMillis = 101L
            )
        )
        assertEquals(ScanLifecycleReadResult.Available(running), store.read())
    }

    @Test
    fun clearRejectsStaleSameGenerationSnapshotAndPreservesNewerRecord() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        val initial = record(updatedAt = 100L)
        val newer = record(phase = ScanLifecyclePhase.Running, updatedAt = 101L)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(initial))
        assertEquals(
            ScanLifecycleWriteResult.Saved,
            store.transition(
                expected = initial,
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 101L
            )
        )

        assertEquals(ScanLifecycleWriteResult.RecordMismatch, store.clear(initial))
        assertEquals(ScanLifecycleReadResult.Available(newer), store.read())
    }

    @Test
    fun clearRejectsStaleSameTimestampSnapshot() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        val initial = record(updatedAt = 100L)
        val newer = record(phase = ScanLifecyclePhase.Running, updatedAt = 100L)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(initial))
        assertEquals(
            ScanLifecycleWriteResult.Saved,
            store.transition(
                expected = initial,
                transition = ScanLifecycleTransition.MarkRunning,
                nowEpochMillis = 100L
            )
        )

        assertEquals(ScanLifecycleWriteResult.RecordMismatch, store.clear(initial))
        assertEquals(ScanLifecycleReadResult.Available(newer), store.read())
    }

    @Test
    fun migrationNeedsCanonicalRequestAndOwnerAndRemovesLegacyBoolean() {
        val preferences = FakePreferences().apply {
            values["active_owner"] = owner
            values["active"] = true
        }
        val store = ScanLifecycleStore(
            preferences,
            nowEpochMillis = { 200L },
            generationFactory = { generation }
        )

        assertEquals(
            ScanLifecycleWriteResult.Saved,
            store.migrateLegacyActiveOwner(request)
        )
        assertEquals(
            ScanLifecycleReadResult.Available(
                record(phase = ScanLifecyclePhase.Enqueued, updatedAt = 200L)
            ),
            store.read()
        )
        assertFalse(preferences.contains("active_owner"))
        assertFalse(preferences.contains("active"))
        assertEquals(
            ScanLifecycleWriteResult.AlreadyPresent,
            store.migrateLegacyActiveOwner(request)
        )
    }

    @Test
    fun orphanLegacyBooleanIsRemovedWithoutReadingAnyPayload() {
        val preferences = FakePreferences().apply {
            values["active"] = true
        }
        val store = ScanLifecycleStore(preferences)

        assertEquals(
            ScanLifecycleWriteResult.Cleared,
            store.migrateLegacyActiveOwner(request)
        )
        assertFalse(preferences.contains("active"))
        assertEquals(ScanLifecycleReadResult.Missing, store.read())
    }

    @Test
    fun migrationRejectsInvalidReferencesWithoutReadingRawWorkData() {
        val preferences = FakePreferences().apply {
            values["active_owner"] = owner
            values["active"] = true
            values["identity_json"] = "seed-must-never-be-read"
        }
        val store = ScanLifecycleStore(preferences)

        assertEquals(
            ScanLifecycleWriteResult.Invalid(ScanLifecycleStoreInvalidReason.InvalidRequestId),
            store.migrateLegacyActiveOwner("not-a-request")
        )
        assertTrue(preferences.contains("identity_json"))
        assertTrue(preferences.contains("active"))
    }

    @Test
    fun malformedPersistedRecordIsTypedInvalid() {
        val preferences = FakePreferences().apply {
            values["scan_lifecycle_format_version"] = 1
            values["scan_lifecycle_owner_id"] = owner
            values["scan_lifecycle_request_id"] = request
            values["scan_lifecycle_generation"] = generation
            values["scan_lifecycle_phase"] = "Succeeded"
            values["scan_lifecycle_updated_at"] = 1L
            values["scan_lifecycle_result_ready"] = false
        }
        val store = ScanLifecycleStore(preferences)
        assertTrue(store.read() is ScanLifecycleReadResult.Invalid)
    }

    @Test
    fun missingRequiredResultReadyFieldIsTypedPartialRecord() {
        val preferences = FakePreferences()
        val store = ScanLifecycleStore(preferences)
        assertEquals(ScanLifecycleWriteResult.Saved, store.publish(record()))
        preferences.values.remove("scan_lifecycle_result_ready")

        assertEquals(
            ScanLifecycleReadResult.Invalid(ScanLifecycleStoreInvalidReason.PartialRecord),
            store.read()
        )
    }

    private class FakePreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var commitResult: Boolean = true
        var commitCount: Int = 0
        var applyCount: Int = 0

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            (values[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = (values[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (values[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (values[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                pending[key] = values
                removals.remove(key)
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                pending[key] = value
                removals.remove(key)
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                pending.remove(key)
                removals += key
            }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                commitCount += 1
                if (!commitResult) return false
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
                return true
            }

            override fun apply() {
                applyCount += 1
                error("apply() is forbidden for lifecycle durability")
            }
        }
    }
}
