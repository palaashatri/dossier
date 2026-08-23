package io.dossier.app.domain.scanner

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundScanWorkerTest {

    @Test
    fun secureInputDataContainsOnlyOpaqueRequestReference() {
        val requestId = "6f0d7f1b-cb5e-4f14-b7b0-3dcf3c06d9bd"

        val data = BackgroundScanWorker.secureInputData(requestId)
        val serialized = data.toByteArray()
        val decoded = Data.fromByteArray(serialized)

        assertEquals(setOf(BackgroundScanWorker.KEY_REQUEST_ID), data.keyValueMap.keys)
        assertEquals(requestId, data.getString(BackgroundScanWorker.KEY_REQUEST_ID))
        assertEquals(data.keyValueMap, decoded.keyValueMap)
        assertFalse(BackgroundScanWorker.hasLegacyWorkData(data))
        assertFalse(data.hasKeyWithValueOfType("identity_json", String::class.java))
        assertFalse(data.hasKeyWithValueOfType("deep_research", Boolean::class.javaObjectType))
        assertFalse(data.hasKeyWithValueOfType("strong_face_correlation", Boolean::class.javaObjectType))
        assertFalse(serialized.toString(Charsets.UTF_8).contains("identity_json"))
    }

    @Test
    fun legacyPlaintextWorkDataIsRejectedWithoutEchoingItsValue() {
        val secret = "authorized@example.test"
        val legacy = Data.Builder()
            .putString("identity_json", "{\"fullName\":\"$secret\"}")
            .putBoolean("deep_research", false)
            .putBoolean("strong_face_correlation", true)
            .build()

        assertTrue(BackgroundScanWorker.hasLegacyWorkData(legacy))
        val failure = BackgroundScanWorker.failureData(
            BackgroundScanWorker.ERROR_LEGACY_WORK_DATA_UNSUPPORTED
        )
        assertEquals(
            BackgroundScanWorker.ERROR_LEGACY_WORK_DATA_UNSUPPORTED,
            failure.getString(BackgroundScanWorker.KEY_ERROR)
        )
        assertEquals(
            setOf(BackgroundScanWorker.KEY_STAGE, BackgroundScanWorker.KEY_ERROR),
            failure.keyValueMap.keys
        )
        assertFalse(failure.toByteArray().toString(Charsets.UTF_8).contains(secret))
    }

    @Test
    fun arbitraryErrorTextCannotEnterWorkManagerData() {
        val secret = "java.io.IOException: token=do-not-persist"

        assertThrows(IllegalArgumentException::class.java) {
            BackgroundScanWorker.failureData(secret)
        }
    }

    @Test
    fun arbitraryProgressTextCannotEnterWorkManagerData() {
        val secret = "DISCOVERING identity=authorized@example.test"

        val data = BackgroundScanWorker.safeProgressData(secret)

        assertEquals(
            BackgroundScanWorker.STAGE_RUNNING,
            data.getString(BackgroundScanWorker.KEY_STAGE)
        )
        assertFalse(data.toByteArray().toString(Charsets.UTF_8).contains(secret))
    }

    @Test
    fun requestReferenceMustBeCanonicalUuid() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundScanWorker.secureInputData("../../identity_json")
        }
    }
}
