package io.dossier.app.domain.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileScanCheckpointCryptoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val aliases = mutableListOf<String>()
    private val requestIds = mutableListOf<String>()

    @After
    fun tearDown() {
        requestIds.forEach { ProfileScanCheckpointStore.clearRequest(context, it) }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        aliases.forEach { alias -> if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
    }

    @Test
    fun decryptWithMissingKeyDoesNotCreateAliasAndAadIsAuthenticated() {
        val alias = testAlias()
        val crypto = AndroidKeystoreCheckpointCrypto(alias)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias(alias))

        assertNull(
            crypto.decrypt(
                ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12)),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(16)),
                aad = "missing".toByteArray()
            )
        )
        assertFalse(keyStore.containsAlias(alias))

        val plaintext = "authorized payload".toByteArray()
        val aad = "exact request scope".toByteArray()
        val encrypted = crypto.encrypt(plaintext, aad)
        assertTrue(keyStore.containsAlias(alias))
        assertArrayEquals(plaintext, crypto.decrypt(encrypted.ivBase64, encrypted.ciphertextBase64, aad))
        assertNull(crypto.decrypt(encrypted.ivBase64, encrypted.ciphertextBase64, "other scope".toByteArray()))
    }

    @Test
    fun encryptedStoreRoundTripsWithoutPlaintextOnDisk() {
        val alias = testAlias()
        val requestId = UUID.randomUUID().toString().also(requestIds::add)
        val store = ProfileScanCheckpointStore(
            rootDir = context.filesDir,
            requestId = requestId,
            planFingerprint = "a".repeat(64),
            crypto = AndroidKeystoreCheckpointCrypto(alias),
            clockMillis = { 1_000L },
            dirSyncer = AndroidDirectorySyncer()
        )
        val secret = "authorized-subject-android-secret"
        val candidate = UsernameCandidate(
            username = secret,
            platform = Platform.Website,
            url = "https://example.test/Profile/$secret",
            matchType = UsernameMatchType.Exact,
            confidence = 1.0f,
            providerId = "website"
        )
        val result = ProfileScanResult(
            candidate = candidate,
            exists = true,
            httpStatus = 200,
            displayName = secret,
            bio = null,
            links = emptyList(),
            extractedText = secret,
            findings = emptyList(),
            confidenceSignals = emptyList(),
            providerVerificationState = ProviderVerificationState.Present
        )

        assertTrue(store.save(result))
        val file = store.resultFileForTesting(candidate)
        assertTrue(file.isFile)
        val envelope = file.readText()
        assertFalse(file.absolutePath.contains(secret))
        assertFalse(envelope.contains(secret))
        assertFalse(envelope.contains(candidate.url))
        assertEquals(result, store.load(candidate))
    }

    private fun testAlias(): String =
        "dossier-profile-checkpoint-test-${UUID.randomUUID()}".also(aliases::add)
}
