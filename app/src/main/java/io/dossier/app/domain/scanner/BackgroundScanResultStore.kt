package io.dossier.app.domain.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.case.DossierCase
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
 * Encrypted transient storage for durable WorkManager scans.
 *
 * Background results are deliberately separate from [io.dossier.app.domain.case.CaseStore]:
 * finishing a background scan must not silently create a user-saved case. The latest
 * result can be restored into [ScanSession] and is only promoted to a saved case when
 * the user explicitly chooses Save.
 */
class BackgroundScanResultStore(private val context: Context) {
    @Serializable
    data class Snapshot(
        val workId: String,
        val completedAtUtc: String,
        val dossierCase: DossierCase
    )

    @Serializable
    private data class Envelope(
        val formatVersion: Int,
        val ivBase64: String,
        val ciphertextBase64: String,
        val plaintextSha256: String
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun save(workId: String, dossierCase: DossierCase): Boolean = runCatching {
        val snapshot = Snapshot(
            workId = workId,
            completedAtUtc = Instant.now().toString(),
            dossierCase = dossierCase
        )
        val plaintext = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = Envelope(
            formatVersion = FORMAT_VERSION,
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            plaintextSha256 = sha256(plaintext)
        )
        atomicWrite(resultFile(), json.encodeToString(envelope))
        true
    }.getOrDefault(false)

    fun load(): Snapshot? = runCatching {
        val file = resultFile()
        if (!file.exists()) return null
        val envelope = json.decodeFromString<Envelope>(file.readText())
        require(envelope.formatVersion == FORMAT_VERSION)
        val iv = Base64.getDecoder().decode(envelope.ivBase64)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        require(sha256(plaintext).equals(envelope.plaintextSha256, ignoreCase = true))
        json.decodeFromString<Snapshot>(plaintext.toString(Charsets.UTF_8))
    }.getOrNull()

    fun clear(): Boolean = runCatching {
        val file = resultFile()
        !file.exists() || file.delete()
    }.getOrDefault(false)

    private fun resultFile(): File = File(context.filesDir, FILE_NAME)

    private fun atomicWrite(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) error("Unable to replace background scan snapshot")
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Unable to commit background scan snapshot")
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FILE_NAME = "background_scan_latest.dscan"
        const val FORMAT_VERSION = 1
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dossier-background-scan-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
