package io.dossier.app.domain.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted per-provider checkpoints for a single direct-profile scan plan.
 *
 * Each completed provider result is its own AES-GCM record. A WorkManager restart
 * can therefore skip every provider that completed before process death instead of
 * repeating the full initial fan-out. File and directory names contain hashes only.
 */
class ProfileScanCheckpointStore(
    private val context: Context,
    private val planFingerprint: String
) {
    @Serializable
    private data class StoredResult(
        val version: Int,
        val savedAtEpochMillis: Long,
        val result: ProfileScanResult
    )

    @Serializable
    private data class Envelope(
        val version: Int,
        val ivBase64: String,
        val ciphertextBase64: String
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val planDir: File
        get() = File(context.filesDir, "dossier_checkpoints/profile/$planFingerprint")

    fun load(candidate: UsernameCandidate): ProfileScanResult? = runCatching {
        val file = resultFile(candidate.url)
        if (!file.exists()) return null
        val stored = decrypt(file)
        if (stored.version != FORMAT_VERSION ||
            System.currentTimeMillis() - stored.savedAtEpochMillis > MAX_AGE_MILLIS ||
            !stored.result.candidate.url.equals(candidate.url, ignoreCase = true)
        ) {
            file.delete()
            return null
        }
        stored.result
    }.getOrNull()

    fun save(result: ProfileScanResult): Boolean = runCatching {
        planDir.mkdirs()
        val stored = StoredResult(
            version = FORMAT_VERSION,
            savedAtEpochMillis = System.currentTimeMillis(),
            result = result
        )
        val plaintext = json.encodeToString(stored).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = Envelope(
            version = FORMAT_VERSION,
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext)
        )
        atomicWrite(resultFile(result.candidate.url), json.encodeToString(envelope))
        true
    }.getOrDefault(false)

    fun clear(): Boolean = runCatching {
        !planDir.exists() || planDir.deleteRecursively()
    }.getOrDefault(false)

    private fun decrypt(file: File): StoredResult {
        val envelope = json.decodeFromString<Envelope>(file.readText())
        require(envelope.version == FORMAT_VERSION)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(envelope.ivBase64))
        )
        val plaintext = cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertextBase64))
        return json.decodeFromString(plaintext.toString(Charsets.UTF_8))
    }

    private fun resultFile(url: String): File = File(planDir, "${sha256(url.lowercase())}.checkpoint")

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) error("Unable to replace provider checkpoint")
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Unable to commit provider checkpoint")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun planFingerprint(
            input: IdentityInput,
            deepResearch: Boolean,
            candidates: List<UsernameCandidate>
        ): String {
            val payload = buildString {
                append("profile-checkpoint-v1\n")
                append("deep=").append(deepResearch).append('\n')
                append("name=").append(input.fullName.trim().lowercase()).append('\n')
                append("primary=").append(input.primaryUsername.orEmpty().trim().lowercase()).append('\n')
                append("usernames=").append(input.usernames.map { it.trim().lowercase() }.sorted().joinToString("|")).append('\n')
                append("emails=").append(input.emails.map { it.trim().lowercase() }.sorted().joinToString("|")).append('\n')
                append("phones=").append(input.phones.map(String::trim).sorted().joinToString("|")).append('\n')
                append("orgs=").append(input.organizations.map { it.trim().lowercase() }.sorted().joinToString("|")).append('\n')
                append("locations=").append(input.locations.map { it.trim().lowercase() }.sorted().joinToString("|")).append('\n')
                append("profiles=").append(input.profileUrls.map { it.trim().lowercase() }.sorted().joinToString("|")).append('\n')
                append("candidates=").append(candidates.joinToString("|") { it.url.lowercase() })
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private const val FORMAT_VERSION = 1
        private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dossier-profile-checkpoint-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
