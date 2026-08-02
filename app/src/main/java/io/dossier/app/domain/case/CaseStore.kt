package io.dossier.app.domain.case

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted, versioned, local-only case store.
 *
 * New cases are written as AES-256-GCM envelopes using an Android Keystore key.
 * Legacy plaintext JSON remains readable solely for one-time migration and is
 * deleted after a successful encrypted rewrite. Saving never falls back to
 * plaintext when the keystore is unavailable.
 */
class CaseStore(private val context: Context) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }
    private val dir: File
        get() = File(context.filesDir, CASE_DIRECTORY).also { it.mkdirs() }

    fun save(case: DossierCase): Boolean = runCatching {
        val normalized = if (case.schemaVersion == DossierCase.CURRENT_SCHEMA_VERSION) {
            case
        } else {
            case.copy(schemaVersion = DossierCase.CURRENT_SCHEMA_VERSION)
        }
        val plaintext = json.encodeToString(normalized).toByteArray(Charsets.UTF_8)
        val envelope = encrypt(plaintext)
        atomicWrite(encryptedFile(case.caseId), json.encodeToString(envelope))
        legacyFile(case.caseId).delete()
        true
    }.getOrDefault(false)

    fun load(caseId: String): DossierCase? {
        val encrypted = encryptedFile(caseId)
        if (encrypted.exists()) {
            return runCatching {
                val envelope = json.decodeFromString<EncryptedCaseEnvelope>(encrypted.readText())
                val plaintext = decrypt(envelope)
                json.decodeFromString<DossierCase>(plaintext.toString(Charsets.UTF_8))
            }.getOrNull()
        }

        val legacy = legacyFile(caseId)
        if (!legacy.exists()) return null
        val migrated = runCatching {
            json.decodeFromString<DossierCase>(legacy.readText())
        }.getOrNull() ?: return null
        return if (save(migrated)) migrated.copy(schemaVersion = DossierCase.CURRENT_SCHEMA_VERSION) else migrated
    }

    fun list(): List<DossierCase> = runCatching {
        val caseIds = dir.listFiles().orEmpty()
            .filter { it.extension == ENCRYPTED_EXTENSION || it.extension == LEGACY_EXTENSION }
            .map { it.nameWithoutExtension }
            .distinct()
        caseIds.mapNotNull(::load).sortedByDescending { it.createdAt }
    }.getOrElse { emptyList() }

    fun delete(caseId: String): Boolean = runCatching {
        val encryptedDeleted = !encryptedFile(caseId).exists() || encryptedFile(caseId).delete()
        val legacyDeleted = !legacyFile(caseId).exists() || legacyFile(caseId).delete()
        encryptedDeleted && legacyDeleted
    }.getOrDefault(false)

    fun clear(): Boolean = runCatching {
        dir.listFiles().orEmpty().forEach { file ->
            if (file.extension in setOf(ENCRYPTED_EXTENSION, LEGACY_EXTENSION, TEMP_EXTENSION)) file.delete()
        }
        true
    }.getOrDefault(false)

    private fun encrypt(plaintext: ByteArray): EncryptedCaseEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedCaseEnvelope(
            formatVersion = ENVELOPE_VERSION,
            caseSchemaVersion = DossierCase.CURRENT_SCHEMA_VERSION,
            createdAtUtc = Instant.now().toString(),
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            plaintextSha256 = sha256(plaintext)
        )
    }

    private fun decrypt(envelope: EncryptedCaseEnvelope): ByteArray {
        require(envelope.formatVersion == ENVELOPE_VERSION) {
            "Unsupported encrypted case format ${envelope.formatVersion}."
        }
        val iv = Base64.getDecoder().decode(envelope.ivBase64)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        require(sha256(plaintext).equals(envelope.plaintextSha256, ignoreCase = true)) {
            "Case integrity check failed."
        }
        return plaintext
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    private fun atomicWrite(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}.$TEMP_EXTENSION")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) error("Unable to replace existing case.")
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Unable to commit encrypted case file.")
        }
    }

    private fun encryptedFile(caseId: String) = File(dir, "$caseId.$ENCRYPTED_EXTENSION")
    private fun legacyFile(caseId: String) = File(dir, "$caseId.$LEGACY_EXTENSION")

    @Serializable
    private data class EncryptedCaseEnvelope(
        val formatVersion: Int,
        val caseSchemaVersion: Int,
        val createdAtUtc: String,
        val ivBase64: String,
        val ciphertextBase64: String,
        val plaintextSha256: String
    )

    private companion object {
        const val CASE_DIRECTORY = "dossier_cases"
        const val ENCRYPTED_EXTENSION = "dcase"
        const val LEGACY_EXTENSION = "json"
        const val TEMP_EXTENSION = "tmp"
        const val ENVELOPE_VERSION = 1
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dossier-case-storage-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
