package io.dossier.app.domain.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Minimal seam used by the initial direct-profile pass. */
internal interface ProfileCheckpointAccess {
    fun load(candidate: UsernameCandidate): ProfileScanResult?
    fun save(result: ProfileScanResult): Boolean
}

/**
 * Encrypted, request-scoped checkpoints for the initial direct-profile pass.
 *
 * Paths contain only SHA-256 digests. The encrypted payload binds the exact
 * request, plan, and canonical candidate key through both metadata and
 * AES-GCM additional authenticated data. A new user request therefore never
 * restores an older request's observations.
 */
internal class ProfileScanCheckpointStore internal constructor(
    private val rootDir: File,
    private val requestId: String,
    private val planFingerprint: String,
    private val crypto: CheckpointCrypto,
    private val clockMillis: () -> Long,
    private val dirSyncer: DirectorySyncer
) : ProfileCheckpointAccess {

    /** Production construction always uses Android Keystore-backed crypto. */
    constructor(
        context: Context,
        requestId: String,
        planFingerprint: String
    ) : this(
        rootDir = context.applicationContext.filesDir,
        requestId = requestId,
        planFingerprint = planFingerprint,
        crypto = AndroidKeystoreCheckpointCrypto(),
        clockMillis = { System.currentTimeMillis() },
        dirSyncer = AndroidDirectorySyncer()
    ) {
        check(retireLegacyAndPrune(context.applicationContext)) {
            "Unable to retire legacy profile checkpoint state"
        }
    }

    init {
        require(ScanLifecycleRecord.isCanonicalUuid(requestId)) {
            "requestId must be a canonical lowercase UUID"
        }
        require(isLowerHexSha256(planFingerprint)) {
            "planFingerprint must be 64 lowercase hexadecimal characters"
        }
    }

    private val requestDirectory: File
        get() = File(profileRoot(rootDir), sha256(requestId))

    private val planDirectory: File
        get() = File(requestDirectory, planFingerprint)

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    /**
     * Missing checkpoints return without touching Android Keystore. Decryption
     * also performs lookup-only key access; key creation is reserved for save.
     */
    override fun load(candidate: UsernameCandidate): ProfileScanResult? = synchronized(STORE_LOCK) {
        runCatching {
            withProfileFileLock(rootDir, dirSyncer) {
                if (existsNoFollow(clearTombstone(rootDir, requestId))) return@withProfileFileLock null
                loadLocked(candidate)
            }
        }.getOrNull()
    }

    private fun loadLocked(candidate: UsernameCandidate): ProfileScanResult? {
        if (!isCheckpointableUrl(candidate.url)) return null
        val canonicalKey = canonicalCandidateKey(candidate.url)
        val file = resultFile(canonicalKey)
        if (!isSafeRegularFile(rootDir, file)) return null

        val envelopeBytes = file.readBounded(MAX_ENVELOPE_BYTES)
        val envelope = json.decodeFromString<CheckpointEnvelope>(
            envelopeBytes.toString(Charsets.UTF_8)
        )
        if (envelope.version != FORMAT_VERSION) return null

        val plaintext = crypto.decrypt(
            ivBase64 = envelope.ivBase64,
            ciphertextBase64 = envelope.ciphertextBase64,
            aad = aad(canonicalKey)
        ) ?: return null
        if (plaintext.size > MAX_PAYLOAD_BYTES) return null

        val stored = json.decodeFromString<StoredProfileResult>(
            plaintext.toString(Charsets.UTF_8)
        )
        if (stored.version != FORMAT_VERSION ||
            stored.requestId != requestId ||
            stored.planFingerprint != planFingerprint ||
            stored.canonicalCandidateKey != canonicalKey ||
            canonicalCandidateKey(stored.result.candidate.url) != canonicalKey ||
            stored.result.candidate != candidate ||
            !isReusable(stored.result)
        ) {
            return null
        }

        val now = clockMillis()
        if (now < 0L || stored.savedAtEpochMillis < 0L || stored.savedAtEpochMillis > now) return null
        if (now - stored.savedAtEpochMillis > MAX_AGE_MILLIS) return null
        return stored.result
    }

    /** Saves only stable response states; transient failures are always retried. */
    override fun save(result: ProfileScanResult): Boolean = synchronized(STORE_LOCK) {
        runCatching {
            withProfileFileLock(rootDir, dirSyncer) {
                if (existsNoFollow(clearTombstone(rootDir, requestId))) return@withProfileFileLock false
                saveLocked(result)
            }
        }.getOrDefault(false)
    }

    private fun saveLocked(result: ProfileScanResult): Boolean {
        if (!isReusable(result)) return false
        if (!isCheckpointableUrl(result.candidate.url)) return false
        val now = clockMillis()
        if (now < 0L) return false

        val canonicalKey = canonicalCandidateKey(result.candidate.url)
        val stored = StoredProfileResult(
            version = FORMAT_VERSION,
            requestId = requestId,
            planFingerprint = planFingerprint,
            canonicalCandidateKey = canonicalKey,
            savedAtEpochMillis = now,
            result = result
        )
        val plaintext = json.encodeToString(stored).toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_PAYLOAD_BYTES) return false

        val encrypted = crypto.encrypt(plaintext, aad(canonicalKey))
        val envelope = CheckpointEnvelope(
            version = FORMAT_VERSION,
            ivBase64 = encrypted.ivBase64,
            ciphertextBase64 = encrypted.ciphertextBase64
        )
        val encodedEnvelope = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        if (encodedEnvelope.size > MAX_ENVELOPE_BYTES) return false

        atomicWriteDurable(rootDir, resultFile(canonicalKey), encodedEnvelope, dirSyncer)
        return true
    }

    private fun aad(canonicalKey: String): ByteArray =
        "profile-checkpoint-v$FORMAT_VERSION|$requestId|$planFingerprint|$canonicalKey"
            .toByteArray(Charsets.UTF_8)

    private fun resultFile(canonicalKey: String): File =
        File(planDirectory, "${sha256(canonicalKey)}$CHECKPOINT_EXTENSION")

    internal fun resultFileForTesting(candidate: UsernameCandidate): File =
        resultFile(canonicalCandidateKey(candidate.url))

    private fun File.readBounded(maxBytes: Int): ByteArray {
        val buffer = ByteBuffer.allocate(maxBytes + 1)
        FileChannel.open(
            toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel ->
            if (channel.size() > maxBytes.toLong()) throw IOException("Invalid checkpoint file")
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
        }
        if (buffer.position() > maxBytes) throw IOException("Checkpoint envelope is too large")
        return buffer.array().copyOf(buffer.position())
    }

    companion object {
        internal const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
        internal const val MAX_PAYLOAD_BYTES = 1024 * 1024
        internal const val MAX_ENVELOPE_BYTES = 2 * 1024 * 1024
        internal const val PROFILE_GUARD_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

        /**
         * Request IDs already bind one immutable encrypted input. Keep this
         * path component free of names, handles, emails, URLs, or other PII;
         * candidate files are keyed separately and changed URLs simply miss.
         */
        fun planFingerprint(
            @Suppress("UNUSED_PARAMETER") input: IdentityInput,
            deepResearch: Boolean,
            candidates: List<UsernameCandidate>
        ): String {
            val candidatePlan = candidates.map { candidate ->
                listOf(
                    candidate.providerId.orEmpty(),
                    candidate.platform.name,
                    candidate.matchType.name,
                    candidate.confidence.toRawBits().toString()
                ).joinToString("\u001f")
            }.sorted().joinToString("\u001e")

            val payload = buildString {
                append(DIRECT_PROFILE_PLAN_POLICY).append('\n')
                append("deep=").append(deepResearch).append('\n')
                append("candidates=").append(candidatePlan)
            }
            return sha256(payload)
        }

        /** Lowercases only scheme/host, preserves port/path/query case, drops fragment. */
        fun canonicalCandidateKey(url: String): String {
            val trimmed = url.trim()
            return runCatching {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                    ?: return@runCatching trimmed.substringBefore('#')
                val hostValue = uri.host?.lowercase(Locale.ROOT)
                    ?: return@runCatching trimmed.substringBefore('#')
                val host = if (':' in hostValue && !hostValue.startsWith("[")) "[$hostValue]" else hostValue
                val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
                val port = if (uri.port >= 0) ":${uri.port}" else ""
                val path = uri.rawPath.orEmpty()
                val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                "$scheme://$userInfo$host$port$path$query"
            }.getOrElse { trimmed.substringBefore('#') }
        }

        fun isReusable(result: ProfileScanResult): Boolean = when (result.providerVerificationState) {
            ProviderVerificationState.Present,
            ProviderVerificationState.NotFound,
            ProviderVerificationState.SoftNotFound,
            ProviderVerificationState.AuthenticationRequired,
            ProviderVerificationState.RedirectedOutsideProvider -> true
            ProviderVerificationState.AutomationChallenged,
            ProviderVerificationState.RateLimited,
            ProviderVerificationState.Timeout,
            ProviderVerificationState.NetworkUnavailable,
            ProviderVerificationState.UnexpectedStatus,
            ProviderVerificationState.InvalidResponse,
            null -> false
        }

        private fun isCheckpointableUrl(url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null
        }.getOrDefault(false)

        fun clearRequest(context: Context, requestId: String): Boolean = clearRequest(
            rootDir = context.applicationContext.filesDir,
            requestId = requestId,
            dirSyncer = AndroidDirectorySyncer()
        )

        internal fun retireLegacyAndPrune(context: Context): Boolean {
            val syncer = AndroidDirectorySyncer()
            return synchronized(STORE_LOCK) {
                val dataCleared = runCatching {
                    clearLegacyProfileData(context.filesDir, syncer)
                }.getOrDefault(false)
                val keyCleared = runCatching {
                    AndroidKeystoreCheckpointCrypto.deleteLegacyKey()
                }.getOrDefault(false)
                val expiredStateCleared = pruneExpiredProfileState(
                    rootDir = context.filesDir,
                    nowEpochMillis = System.currentTimeMillis(),
                    dirSyncer = syncer
                )
                dataCleared && keyCleared && expiredStateCleared
            }
        }

        internal fun clearLegacyProfileData(
            rootDir: File,
            dirSyncer: DirectorySyncer
        ): Boolean = runCatching {
            var cleared = true
            LEGACY_PROFILE_ROOTS.forEach { relativePath ->
                val legacy = File(rootDir, relativePath)
                if (existsNoFollow(legacy) && !deleteTreeDurably(rootDir, legacy, dirSyncer)) {
                    cleared = false
                }
            }
            cleared
        }.getOrDefault(false)

        /** Explicit purge fallback for orphaned scopes when no lifecycle owner remains. */
        fun clearAll(context: Context): Boolean {
            val appContext = context.applicationContext
            val syncer = AndroidDirectorySyncer()
            val currentCleared = clearAll(appContext.filesDir, syncer)
            val legacyDataCleared = clearLegacyProfileData(appContext.filesDir, syncer)
            val legacyKeyCleared = AndroidKeystoreCheckpointCrypto.deleteLegacyKey()
            return currentCleared && legacyDataCleared && legacyKeyCleared
        }

        internal fun clearAll(rootDir: File, dirSyncer: DirectorySyncer): Boolean =
            synchronized(STORE_LOCK) {
                runCatching {
                    val root = profileRoot(rootDir)
                    if (!existsNoFollow(root)) return@synchronized true
                    withProfileFileLock(rootDir, dirSyncer) {
                        val requestHashes = root.listFiles().orEmpty().mapNotNull { entry ->
                            entry.name.take(SHA_256_HEX_LENGTH).takeIf(::isLowerHexSha256)
                        }.distinct()
                        var cleared = true
                        requestHashes.forEach { requestHash ->
                            val tombstone = File(root, "$requestHash$CLEAR_TOMBSTONE_EXTENSION")
                            if (!ensureClearTombstone(rootDir, tombstone, dirSyncer)) {
                                cleared = false
                                return@forEach
                            }
                            val requestDirectory = File(root, requestHash)
                            if (existsNoFollow(requestDirectory) &&
                                !deleteTreeDurably(rootDir, requestDirectory, dirSyncer)
                            ) {
                                cleared = false
                            }
                        }
                        root.listFiles().orEmpty().forEach { entry ->
                            val keep = entry.name == GLOBAL_LOCK_FILE ||
                                entry.name.endsWith(CLEAR_TOMBSTONE_EXTENSION)
                            val requestDirectory = entry.name.matches(Regex("[0-9a-f]{64}"))
                            if (!keep && !requestDirectory &&
                                !deleteTreeDurably(rootDir, entry, dirSyncer)
                            ) {
                                cleared = false
                            }
                        }
                        cleared
                    }
                }.getOrDefault(false)
            }

        internal fun pruneExpiredProfileState(
            rootDir: File,
            nowEpochMillis: Long,
            dirSyncer: DirectorySyncer
        ): Boolean = synchronized(STORE_LOCK) {
            runCatching {
                if (nowEpochMillis < PROFILE_GUARD_RETENTION_MILLIS) return@synchronized false
                val root = profileRoot(rootDir)
                if (!existsNoFollow(root)) return@synchronized true
                val cutoff = nowEpochMillis - PROFILE_GUARD_RETENTION_MILLIS
                withProfileFileLock(rootDir, dirSyncer) {
                    var pruned = true
                    root.listFiles().orEmpty()
                        .filter { entry ->
                            entry.name.matches(Regex("[0-9a-f]{64}")) &&
                                entry.lastModified() in 1..cutoff
                        }
                        .forEach { requestDirectory ->
                            val tombstone = File(
                                root,
                                "${requestDirectory.name}$CLEAR_TOMBSTONE_EXTENSION"
                            )
                            if (!ensureClearTombstone(rootDir, tombstone, dirSyncer)) {
                                pruned = false
                                return@forEach
                            }
                            if (!deleteTreeDurably(rootDir, requestDirectory, dirSyncer)) {
                                pruned = false
                            }
                        }
                    root.listFiles().orEmpty()
                        .filter { entry ->
                            entry.name.endsWith(CLEAR_TOMBSTONE_EXTENSION) &&
                                isLowerHexSha256(
                                    entry.name.removeSuffix(CLEAR_TOMBSTONE_EXTENSION)
                                ) &&
                                entry.lastModified() in 1..cutoff
                        }
                        .forEach { tombstone ->
                            if (!deleteTreeDurably(rootDir, tombstone, dirSyncer)) pruned = false
                        }
                    pruned
                }
            }.getOrDefault(false)
        }

        internal fun clearRequest(
            rootDir: File,
            requestId: String,
            dirSyncer: DirectorySyncer
        ): Boolean = synchronized(STORE_LOCK) {
            runCatching {
                require(ScanLifecycleRecord.isCanonicalUuid(requestId))
                withProfileFileLock(rootDir, dirSyncer) {
                    val tombstone = clearTombstone(rootDir, requestId)
                    if (!ensureClearTombstone(rootDir, tombstone, dirSyncer)) {
                        return@withProfileFileLock false
                    }

                    val root = profileRoot(rootDir)
                    val requestDirectory = File(root, sha256(requestId))
                    if (existsNoFollow(requestDirectory) &&
                        !deleteTreeDurably(rootDir, requestDirectory, dirSyncer)
                    ) {
                        return@withProfileFileLock false
                    }
                    dirSyncer.sync(root)
                    true
                }
            }.getOrDefault(false)
        }

        private fun profileRoot(rootDir: File): File = File(rootDir, PROFILE_ROOT)

        private fun clearTombstone(rootDir: File, requestId: String): File =
            File(profileRoot(rootDir), "${sha256(requestId)}$CLEAR_TOMBSTONE_EXTENSION")

        private fun ensureClearTombstone(
            rootDir: File,
            tombstone: File,
            dirSyncer: DirectorySyncer
        ): Boolean {
            if (existsNoFollow(tombstone)) return isSafeRegularFile(rootDir, tombstone)
            return runCatching {
                atomicWriteDurable(rootDir, tombstone, CLEAR_TOMBSTONE_BYTES, dirSyncer)
                isSafeRegularFile(rootDir, tombstone)
            }.getOrDefault(false)
        }

        /**
         * A request-wide OS file lock complements the in-process lock so a
         * late worker cannot recreate a scope after a durable clear tombstone.
         */
        private fun <T> withProfileFileLock(
            rootDir: File,
            dirSyncer: DirectorySyncer,
            block: () -> T
        ): T {
            val root = profileRoot(rootDir)
            ensureDirectoryDurable(rootDir, root, dirSyncer)
            val lockFile = File(root, GLOBAL_LOCK_FILE)
            val existed = existsNoFollow(lockFile)
            if (existed && !isSafeRegularFile(rootDir, lockFile)) {
                throw IOException("Unsafe request lock path")
            }
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ).use { channel ->
                if (!existed) {
                    channel.force(true)
                    dirSyncer.sync(root)
                }
                channel.lock().use {
                    return block()
                }
            }
        }

        private fun atomicWriteDurable(
            rootDir: File,
            target: File,
            content: ByteArray,
            dirSyncer: DirectorySyncer
        ) {
            requireContained(rootDir, target)
            val parent = target.parentFile ?: throw IOException("Checkpoint target has no parent")
            ensureDirectoryDurable(rootDir, parent, dirSyncer)
            val temporary = File(parent, "${target.name}.${UUID.randomUUID()}$TEMP_EXTENSION")
            var failure: Exception? = null
            try {
                FileChannel.open(
                    temporary.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(content)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                dirSyncer.sync(parent)
            } catch (error: Exception) {
                failure = error
            }
            if (temporary.exists() && !temporary.delete()) {
                val cleanupFailure = IOException("Unable to remove temporary checkpoint")
                if (failure == null) failure = cleanupFailure else failure?.addSuppressed(cleanupFailure)
            }
            failure?.let { throw it }
        }

        /** Creates and syncs every new directory entry from filesDir downward. */
        private fun ensureDirectoryDurable(
            rootDir: File,
            directory: File,
            dirSyncer: DirectorySyncer
        ) {
            requireContained(rootDir, directory)
            validateDirectoryChain(rootDir, directory)
            if (directory.exists()) {
                if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(directory.toPath())
                ) {
                    throw IOException("Checkpoint parent is not a safe directory")
                }
                return
            }
            val parent = directory.parentFile ?: throw IOException("Checkpoint directory has no parent")
            if (directory.toPath().toAbsolutePath().normalize() == rootDir.toPath().toAbsolutePath().normalize()) {
                throw IOException("Checkpoint root directory is missing")
            }
            ensureDirectoryDurable(rootDir, parent, dirSyncer)
            if (!directory.mkdir() &&
                !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                throw IOException("Unable to create checkpoint directory")
            }
            validateDirectoryChain(rootDir, directory)
            dirSyncer.sync(parent)
        }

        private fun deleteTreeDurably(
            rootDir: File,
            target: File,
            dirSyncer: DirectorySyncer
        ): Boolean {
            requireContained(rootDir, target)
            target.parentFile?.let { validateDirectoryChain(rootDir, it) }
            if (!existsNoFollow(target)) return true
            if (Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                target.listFiles()?.forEach { child ->
                    if (!deleteTreeDurably(rootDir, child, dirSyncer)) return false
                } ?: return false
            }
            val parent = target.parentFile ?: return false
            if (!target.delete()) return false
            dirSyncer.sync(parent)
            return true
        }

        private fun isSafeRegularFile(rootDir: File, file: File): Boolean = runCatching {
            requireContained(rootDir, file)
            file.parentFile?.let { validateDirectoryChain(rootDir, it) }
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(file.toPath())
        }.getOrDefault(false)

        private fun existsNoFollow(file: File): Boolean =
            Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

        private fun requireContained(rootDir: File, target: File) {
            val boundary = rootDir.toPath().toAbsolutePath().normalize()
            val normalizedTarget = target.toPath().toAbsolutePath().normalize()
            if (!normalizedTarget.startsWith(boundary)) {
                throw IOException("Checkpoint path escaped filesDir")
            }
        }

        /** Rejects symlinks or non-directories anywhere below the trusted filesDir. */
        private fun validateDirectoryChain(rootDir: File, directory: File) {
            val boundary = rootDir.toPath().toAbsolutePath().normalize()
            var current = directory.toPath().toAbsolutePath().normalize()
            if (!current.startsWith(boundary)) throw IOException("Checkpoint directory escaped filesDir")
            while (true) {
                if (current == boundary) {
                    if (Files.isSymbolicLink(current) ||
                        !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw IOException("filesDir is not a safe directory")
                    }
                    return
                }
                if (Files.isSymbolicLink(current)) {
                    throw IOException("Checkpoint directory chain contains a symlink")
                }
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                ) {
                    throw IOException("Checkpoint directory chain contains a non-directory")
                }
                current = current.parent ?: throw IOException("Checkpoint directory has no parent")
            }
        }

        private fun isLowerHexSha256(value: String): Boolean =
            value.length == SHA_256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

        private const val FORMAT_VERSION = 2
        private const val DIRECT_PROFILE_PLAN_POLICY = "direct-profile-plan-policy-v2"
        private const val PROFILE_ROOT = "dossier_checkpoints/profile-v2"
        private const val CHECKPOINT_EXTENSION = ".checkpoint"
        private const val CLEAR_TOMBSTONE_EXTENSION = ".cleared"
        private const val GLOBAL_LOCK_FILE = ".profile-checkpoint.lock"
        private const val TEMP_EXTENSION = ".tmp"
        private const val SHA_256_HEX_LENGTH = 64
        private val LEGACY_PROFILE_ROOTS = listOf(
            "dossier_checkpoints/profile",
            "dossier_checkpoints/profile-v1"
        )
        private val CLEAR_TOMBSTONE_BYTES = "profile-checkpoint-cleared-v2\n".toByteArray(Charsets.UTF_8)
        private val STORE_LOCK = Any()
    }
}

@Serializable
private data class StoredProfileResult(
    val version: Int,
    val requestId: String,
    val planFingerprint: String,
    val canonicalCandidateKey: String,
    val savedAtEpochMillis: Long,
    val result: ProfileScanResult
)

@Serializable
private data class CheckpointEnvelope(
    val version: Int,
    val ivBase64: String,
    val ciphertextBase64: String
)

internal interface CheckpointCrypto {
    data class Encrypted(
        val ivBase64: String,
        val ciphertextBase64: String
    )

    fun encrypt(plaintext: ByteArray, aad: ByteArray): Encrypted
    fun decrypt(ivBase64: String, ciphertextBase64: String, aad: ByteArray): ByteArray?
}

/** Android Keystore implementation; decrypt performs lookup-only key access. */
internal class AndroidKeystoreCheckpointCrypto internal constructor(
    private val keyAlias: String = KEY_ALIAS
) : CheckpointCrypto {

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): CheckpointCrypto.Encrypted {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return CheckpointCrypto.Encrypted(
            ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext)
        )
    }

    override fun decrypt(
        ivBase64: String,
        ciphertextBase64: String,
        aad: ByteArray
    ): ByteArray? = runCatching {
        val key = getKey() ?: return null
        val iv = Base64.getDecoder().decode(ivBase64)
        val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
        require(iv.size == GCM_IV_BYTES)
        require(ciphertext.size >= GCM_TAG_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        getKey()?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private fun getKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    companion object {
        internal fun deleteLegacyKey(): Boolean = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) keyStore.deleteEntry(LEGACY_KEY_ALIAS)
            true
        }.getOrDefault(false)

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dossier-profile-checkpoint-v2"
        private const val LEGACY_KEY_ALIAS = "dossier-profile-checkpoint-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val GCM_IV_BYTES = 12
        private val KEY_LOCK = Any()
    }
}
