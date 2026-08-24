package io.dossier.app.domain.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.analysis.OsintAnalysisBundle
import io.dossier.app.domain.case.DossierCase
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted transient storage for durable WorkManager scans.
 *
 * Background results are deliberately separate from CaseStore: finishing a scan
 * never silently creates a user-saved case. The latest result can be restored into
 * ScanSession and promoted only after an explicit Save action.
 */
class BackgroundScanResultStore internal constructor(
    private val context: Context,
    private val directorySyncer: DirectorySyncer
) {
    constructor(context: Context) : this(
        context.applicationContext,
        AndroidDirectorySyncer()
    )

    @Serializable
    data class Snapshot(
        val workId: String,
        val completedAtUtc: String,
        val dossierCase: DossierCase,
        val analysis: OsintAnalysisBundle = OsintAnalysisBundle()
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

    fun save(
        workId: String,
        dossierCase: DossierCase,
        analysis: OsintAnalysisBundle = OsintAnalysisBundle()
    ): Boolean = runCatching {
        val snapshot = Snapshot(
            workId = workId,
            completedAtUtc = Instant.now().toString(),
            dossierCase = dossierCase,
            analysis = analysis
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
        writeEnvelopeAtomically(resultFile(), json.encodeToString(envelope))
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

    /**
     * Atomically replaces the encrypted envelope without unlinking the last
     * known-good result first. The file and its parent directory are both
     * synced so a reported success survives power loss.
     */
    internal fun writeEnvelopeAtomically(target: File, content: String) {
        val parent = target.parentFile ?: throw IOException("Missing result parent")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create result directory")
        }
        val temp = File(parent, "${target.name}.${UUID.randomUUID()}.tmp")
        var failure: Exception? = null
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            directorySyncer.sync(parent)
        } catch (error: Exception) {
            failure = error
        }
        if (temp.exists() && !temp.delete()) {
            val cleanup = IOException("Unable to remove temporary result file")
            if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
        }
        failure?.let { throw it }
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
