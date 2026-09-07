package io.dossier.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.scanner.ScanResumeStore
import io.dossier.app.domain.scanner.ResumeReadState
import io.dossier.app.domain.scanner.ResumeStorageReason
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class ScanResumeStoreAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = ScanResumeStore(context)

    @Before
    fun setup() {
        assertTrue(store.clear())
        DiscoveryScanPreferences.reset()
        deleteKey()
    }

    @After
    fun cleanup() {
        assertTrue(store.clear())
        DiscoveryScanPreferences.reset()
        deleteKey()
    }

    private fun deleteKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(ScanResumeStore.KEY_ALIAS)) {
            keyStore.deleteEntry(ScanResumeStore.KEY_ALIAS)
        }
        assertFalse(keyStore.containsAlias(ScanResumeStore.KEY_ALIAS))
    }

    @Test
    fun androidKeystoreRecordRoundTripsWithoutPlaintextSeedsOnDisk() {
        val input = IdentityInput(
            fullName = "Android Keystore User",
            aliases = listOf("A. K. User"),
            primaryUsername = "android_user",
            emails = listOf("android-user@example.test"),
            selfieUri = "content://example/android-selfie"
        )
        DiscoveryScanPreferences.setMode(ScanMode.Exhaustive)

        assertTrue(store.save(input, deepResearch = true))

        val recordsDir = File(context.filesDir, ScanResumeStore.RECORDS_DIRECTORY)
        val record = recordsDir.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val pointer = File(recordsDir, ScanResumeStore.POINTER_FILE_NAME)
        val recordText = record.readText()

        val pointerId = pointer.readText()
        assertEquals("$pointerId${ScanResumeStore.RECORD_EXTENSION}", record.name)
        assertTrue(pointerId.matches(Regex("^[0-9a-f-]{36}$")))
        assertFalse(recordText.contains(input.fullName))
        assertFalse(recordText.contains(input.emails.single()))
        assertFalse(recordText.contains(input.selfieUri.orEmpty()))

        DiscoveryScanPreferences.reset()
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(input, loaded?.first)
        assertEquals(true, loaded?.second)
        assertEquals(ScanMode.Exhaustive, DiscoveryScanPreferences.selectedMode.value)
    }
    @Test
    fun deletingKeyCausesKeyUnavailableWithCiphertextRetained() {
        val input = IdentityInput(fullName = "Android Keystore User")
        assertTrue(store.save(input, deepResearch = false))
        val recordsDir = File(context.filesDir, ScanResumeStore.RECORDS_DIRECTORY)
        val record = recordsDir.listFiles().orEmpty()
            .single { it.name.endsWith(ScanResumeStore.RECORD_EXTENSION) }
        val recordTextBefore = record.readText()
        deleteKey()

        assertEquals(
            ResumeReadState.StorageFailure(ResumeStorageReason.KeyUnavailable),
            store.loadDetailed()
        )

        val recordTextAfter = record.readText()
        assertEquals(recordTextBefore, recordTextAfter)
    }
}
