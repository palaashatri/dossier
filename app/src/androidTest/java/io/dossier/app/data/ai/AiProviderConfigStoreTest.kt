package io.dossier.app.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dossier.app.domain.ai.AiProviderType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AiProviderConfigStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs
        get() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        deleteKey()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        deleteKey()
    }

    @Test
    fun legacyPlaintextKeyMigratesBeforeItIsReturned() {
        val provider = AiProviderType.OPENAI
        val legacyKey = "sk-legacy-migration-test"

        prefs.edit()
            .putBoolean("${provider.name}.enabled", true)
            .putString("${provider.name}.apiKey", legacyKey)
            .putString("${provider.name}.baseUrl", "https://example.test/v1")
            .putString("${provider.name}.model", "migration-model")
            .putInt("${provider.name}.priority", 7)
            .commit()

        val loaded = AiProviderConfigStore(context).get(provider)

        assertEquals(legacyKey, loaded.apiKey)
        assertTrue(loaded.enabled)
        assertEquals("https://example.test/v1", loaded.baseUrl)
        assertEquals("migration-model", loaded.model)
        assertEquals(7, loaded.priority)
        assertFalse(prefs.contains("${provider.name}.apiKey"))
        assertTrue(prefs.contains("${provider.name}.apiKeyEncrypted"))
        assertEquals(legacyKey, AiProviderConfigStore(context).get(provider).apiKey)
    }

    @Test
    fun unreadableEncryptedKeyDoesNotFallBackToLegacyPlaintext() {
        val provider = AiProviderType.ANTHROPIC
        val legacyKey = "sk-ant-legacy-never-exposed"

        prefs.edit()
            .putBoolean("${provider.name}.enabled", true)
            .putString("${provider.name}.apiKeyEncrypted", "not-a-valid-payload")
            .putString("${provider.name}.apiKey", legacyKey)
            .putString("${provider.name}.baseUrl", "https://example.test/anthropic")
            .putString("${provider.name}.model", "safe-model")
            .putInt("${provider.name}.priority", 3)
            .commit()

        val loaded = AiProviderConfigStore(context).get(provider)

        assertEquals("", loaded.apiKey)
        assertTrue(loaded.enabled)
        assertEquals("https://example.test/anthropic", loaded.baseUrl)
        assertEquals("safe-model", loaded.model)
        assertEquals(3, loaded.priority)
        assertFalse(prefs.contains("${provider.name}.apiKey"))
    }

    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                .takeIf { it.containsAlias(KEY_ALIAS) }
                ?.deleteEntry(KEY_ALIAS)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "ai_provider_configs"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "dossier_ai_provider_api_keys"
    }
}
