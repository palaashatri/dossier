package io.dossier.app.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.dossier.app.domain.ai.AiProviderConfig
import io.dossier.app.domain.ai.AiProviderType
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AiProviderConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_provider_configs", Context.MODE_PRIVATE)

    fun get(provider: AiProviderType): AiProviderConfig {
        val defaults = AiProviderConfig.default(provider)
        return AiProviderConfig(
            provider = provider,
            enabled = prefs.getBoolean("${provider.name}.enabled", defaults.enabled),
            apiKey = readApiKey(provider, defaults),
            baseUrl = prefs.getString("${provider.name}.baseUrl", defaults.baseUrl).orEmpty().ifBlank { defaults.baseUrl },
            model = prefs.getString("${provider.name}.model", defaults.model).orEmpty().ifBlank { defaults.model },
            priority = prefs.getInt("${provider.name}.priority", defaults.priority)
        )
    }

    fun getAll(): List<AiProviderConfig> =
        AiProviderType.entries
            .map { get(it) }
            .sortedWith(compareBy<AiProviderConfig> { it.priority }.thenBy { it.provider.ordinal })

    fun save(config: AiProviderConfig) {
        val editor = prefs.edit()
            .putBoolean("${config.provider.name}.enabled", config.enabled)
            .putString("${config.provider.name}.baseUrl", config.baseUrl)
            .putString("${config.provider.name}.model", config.model)
            .putInt("${config.provider.name}.priority", config.priority)

        if (config.apiKey.isBlank()) {
            editor
                .remove("${config.provider.name}.apiKey")
                .remove("${config.provider.name}.apiKeyEncrypted")
        } else {
            editor
                .putString("${config.provider.name}.apiKeyEncrypted", encrypt(config.apiKey))
                .remove("${config.provider.name}.apiKey")
        }

        editor.apply()
    }

    fun firstUsableRemoteProvider(): AiProviderConfig? =
        getAll().firstOrNull { config ->
            config.enabled && (!config.provider.needsApiKey || config.apiKey.isNotBlank())
        }

    private fun readApiKey(provider: AiProviderType, defaults: AiProviderConfig): String {
        val encrypted = prefs.getString("${provider.name}.apiKeyEncrypted", null)
        val decrypted = encrypted?.let { payload ->
            runCatching { decrypt(payload) }.getOrNull()
        }
        return decrypted ?: prefs.getString("${provider.name}.apiKey", defaults.apiKey).orEmpty()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES)
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existingEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) return existingEntry.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "dossier_ai_provider_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
