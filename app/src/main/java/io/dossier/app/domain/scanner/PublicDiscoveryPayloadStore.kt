package io.dossier.app.domain.scanner

import android.content.Context
import io.dossier.app.domain.discovery.ProviderPlanFingerprint
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.ProfileScanResult
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
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * The only discovery payloads that are safe to reuse across a worker retry.
 * Search and image-index observations remain explicitly unverified; this enum
 * must not be expanded to a stage that can silently assert account ownership.
 */
@Serializable
enum class ScanPayloadStage(val wireName: String) {
    PublicSearch("PUBLIC_SEARCH"),
    PublicImage("PUBLIC_IMAGE")
}

/**
 * Sanitized metadata surfaced through the coordinator checkpoint.  The actual
 * [ProfileScanResult] list remains in the separate encrypted payload store.
 */
@Serializable
data class ScanPayloadSummary(
    val stage: ScanPayloadStage,
    val itemCount: Int,
    val digest: String,
    val payloadBytes: Int
) {
    fun isWellFormed(): Boolean =
        itemCount in 0..MAX_ITEMS &&
            payloadBytes in 0..MAX_PAYLOAD_BYTES &&
            digest.matches(DIGEST_PATTERN)

    companion object {
        internal const val MAX_ITEMS = 64
        internal const val MAX_PAYLOAD_BYTES = 512 * 1024
        private val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Request- and provider-plan-bound encrypted payloads for public search/image
 * discovery.  The store is deliberately separate from [ScanResumeStore]:
 * checkpoint metadata stays small and non-sensitive while this store retains
 * only the bounded, already-normalized result objects needed for a retry.
 *
 * Paths contain only a request digest and an allow-listed stage.  AES-GCM AAD
 * binds request, plan, and stage; a changed catalog plan therefore fails closed
 * even when an old request-scoped file is still present.
 */
internal class PublicDiscoveryPayloadStore internal constructor(
    private val rootDir: File,
    private val requestId: String,
    private val planFingerprint: String,
    private val crypto: CheckpointCrypto,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val dirSyncer: DirectorySyncer = object : DirectorySyncer {
        override fun sync(dir: File) = Unit
    }
) {
    constructor(context: Context, requestId: String, planFingerprint: String) : this(
        rootDir = context.applicationContext.filesDir,
        requestId = requestId,
        planFingerprint = planFingerprint,
        crypto = AndroidKeystoreCheckpointCrypto("dossier-public-discovery-v1"),
        dirSyncer = AndroidDirectorySyncer()
    )

    init {
        require(ScanLifecycleRecord.isCanonicalUuid(requestId)) {
            "requestId must be a canonical lowercase UUID"
        }
        require(ProviderPlanFingerprint.isValid(planFingerprint)) {
            "planFingerprint must be 64 lowercase hexadecimal characters"
        }
    }

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    /** Returns a complete valid payload, or null when no trustworthy cache exists. */
    fun load(stage: ScanPayloadStage): List<ProfileScanResult>? = synchronized(STORE_LOCK) {
        runCatching { loadRecord(stage)?.results }.getOrNull()
    }

    /** Reads only non-sensitive shape metadata for coordinator checkpointing. */
    fun loadSummary(stage: ScanPayloadStage): ScanPayloadSummary? = synchronized(STORE_LOCK) {
        runCatching { loadRecord(stage)?.summary() }.getOrNull()
    }

    fun summaries(): List<ScanPayloadSummary> = synchronized(STORE_LOCK) {
        ScanPayloadStage.entries.mapNotNull { stage ->
            runCatching { loadRecord(stage)?.summary() }.getOrNull()
        }
    }

    /**
     * Saves only a successful public-discovery result.  A null result means the
     * payload was unsafe or exceeded a bound; callers must continue using the
     * in-memory result and must not turn that failure into a fake cache hit.
     */
    fun save(stage: ScanPayloadStage, results: List<ProfileScanResult>): ScanPayloadSummary? =
        synchronized(STORE_LOCK) {
            runCatching {
                if (isTombstoned() || results.size > ScanPayloadSummary.MAX_ITEMS) return@runCatching null
                // Public search/image observations are never allowed to cross
                // this cache boundary as verified account ownership.
                if (results.any { !isSafeResult(it) }) return@runCatching null
                val now = nowMillis()
                if (now < 0L) return@runCatching null
                val record = PayloadRecord(
                    version = FORMAT_VERSION,
                    requestId = requestId,
                    planFingerprint = planFingerprint,
                    stage = stage,
                    savedAtEpochMillis = now,
                    results = results
                )
                val plaintext = json.encodeToString(record).toByteArray(Charsets.UTF_8)
                if (plaintext.size > ScanPayloadSummary.MAX_PAYLOAD_BYTES) return@runCatching null
                val encrypted = crypto.encrypt(plaintext, aad(stage))
                val envelope = PayloadEnvelope(
                    version = FORMAT_VERSION,
                    requestId = requestId,
                    stage = stage,
                    savedAtEpochMillis = now,
                    ivBase64 = encrypted.ivBase64,
                    ciphertextBase64 = encrypted.ciphertextBase64
                )
                val encoded = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
                if (encoded.size > MAX_ENVELOPE_BYTES) return@runCatching null
                atomicWrite(payloadFile(stage), encoded)
                record.summary()
            }.getOrNull()
        }

    /** Explicit request retirement prevents a late worker from recreating data. */
    fun clear(): Boolean = synchronized(STORE_LOCK) {
        runCatching {
            val directory = payloadDirectory()
            val tombstone = tombstoneFile()
            if (!isContained(directory) || !isContained(tombstone)) return@runCatching false
            val parent = directory.parentFile ?: return@runCatching false
            if (!isSafeDirectoryChain(rootDir, parent)) return@runCatching false
            if (!ensureTombstone(tombstone)) return@runCatching false
            if (!directory.exists()) return@runCatching true
            if (!isSafeDirectory(directory)) return@runCatching false
            ScanPayloadStage.entries.all { stage ->
                val file = payloadFile(stage)
                !file.exists() || (isSafeRegularFile(file) && file.delete())
            }.also { if (it) directory.parentFile?.let(dirSyncer::sync) }
        }.getOrDefault(false)
    }

    internal fun payloadFileForTesting(stage: ScanPayloadStage): File = payloadFile(stage)

    private fun loadRecord(stage: ScanPayloadStage): PayloadRecord? {
        val target = payloadFile(stage)
        val directory = target.parentFile ?: return null
        if (!isContained(target) || !isSafeDirectoryChain(rootDir, directory) || isTombstoned()) return null
        if (!isSafeRegularFile(target) || target.length() > MAX_ENVELOPE_BYTES) return null
        val envelope = json.decodeFromString<PayloadEnvelope>(target.readBounded())
        if (envelope.version != FORMAT_VERSION ||
            envelope.requestId != requestId ||
            envelope.stage != stage
        ) return null
        val now = nowMillis()
        if (now < 0L || envelope.savedAtEpochMillis < 0L || envelope.savedAtEpochMillis > now) return null
        if (now - envelope.savedAtEpochMillis > MAX_AGE_MILLIS) return null
        val plaintext = crypto.decrypt(
            envelope.ivBase64,
            envelope.ciphertextBase64,
            aad(stage)
        ) ?: return null
        if (plaintext.size > ScanPayloadSummary.MAX_PAYLOAD_BYTES) return null
        val record = json.decodeFromString<PayloadRecord>(String(plaintext, Charsets.UTF_8))
        if (record.version != FORMAT_VERSION ||
            record.requestId != requestId ||
            record.planFingerprint != planFingerprint ||
            record.stage != stage ||
            record.savedAtEpochMillis != envelope.savedAtEpochMillis ||
            !isValidRecord(record)
        ) return null
        return record
    }

    private fun PayloadRecord.summary(): ScanPayloadSummary {
        val plaintext = json.encodeToString(this).toByteArray(Charsets.UTF_8)
        return ScanPayloadSummary(
            stage = stage,
            itemCount = results.size,
            digest = sha256(plaintext),
            payloadBytes = plaintext.size
        )
    }

    private fun isValidRecord(record: PayloadRecord): Boolean {
        val now = nowMillis()
        return record.savedAtEpochMillis in 0L..now &&
            now - record.savedAtEpochMillis <= MAX_AGE_MILLIS &&
            record.results.size <= ScanPayloadSummary.MAX_ITEMS &&
            record.results.all(::isSafeResult) &&
            record.summary().isWellFormed()
    }

    private fun isSafeResult(result: ProfileScanResult): Boolean {
        val candidate = result.candidate
        val candidateUri = parseHttpUrl(candidate.url) ?: return false
        return !result.verified &&
            candidate.username.length in 1..MAX_SHORT_CHARS &&
            candidate.url.length <= MAX_URL_CHARS &&
            candidateUri.rawUserInfo == null &&
            result.httpStatus?.let { it in 100..599 } != false &&
            result.displayName.isSafeText(MAX_TEXT_CHARS) &&
            result.bio.isSafeText(MAX_TEXT_CHARS) &&
            result.profileImageUrl?.let { parseHttpUrl(it)?.rawUserInfo == null && it.length <= MAX_URL_CHARS } != false &&
            result.links.size <= MAX_LINKS &&
            result.links.all { link -> parseHttpUrl(link)?.rawUserInfo == null && link.length <= MAX_URL_CHARS } &&
            result.extractedText.length <= MAX_TEXT_CHARS &&
            result.extractedText.indexOf('\u0000') < 0 &&
            result.findings.size <= MAX_FINDINGS &&
            result.findings.all(::isSafeFinding) &&
            result.confidenceSignals.size <= MAX_SIGNALS &&
            result.confidenceSignals.all { it.isSafeText(MAX_TEXT_CHARS) } &&
            result.verificationStatus.isSafeText(MAX_TEXT_CHARS) &&
            result.provenance.isSafeText(MAX_TEXT_CHARS) &&
            result.candidate.confidence.isFinite() &&
            result.candidate.confidence in 0f..1f
    }

    private fun isSafeFinding(finding: Finding): Boolean =
        finding.value.isSafeText(MAX_TEXT_CHARS) &&
            finding.sourceUrl?.let { parseHttpUrl(it)?.rawUserInfo == null && it.length <= MAX_URL_CHARS } != false &&
            finding.evidenceSnippet.isSafeText(MAX_TEXT_CHARS) &&
            finding.confidence.isFinite() && finding.confidence in 0f..1f &&
            finding.remediation.isSafeText(MAX_TEXT_CHARS)

    private fun String?.isSafeText(max: Int): Boolean =
        this == null || (length <= max && indexOf('\u0000') < 0)

    private fun parseHttpUrl(value: String): URI? = runCatching {
        URI(value.trim()).takeIf { uri ->
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !uri.host.isNullOrBlank()
        }
    }.getOrNull()

    private fun aad(stage: ScanPayloadStage): ByteArray =
        "public-discovery-payload-v$FORMAT_VERSION|$requestId|$planFingerprint|${stage.wireName}"
            .toByteArray(Charsets.UTF_8)

    private fun payloadDirectory(): File = File(rootDir, ROOT_DIRECTORY)

    private fun payloadFile(stage: ScanPayloadStage): File =
        File(payloadDirectory(), "${sha256(requestId)}-${stage.wireName.lowercase(Locale.ROOT)}$FILE_EXTENSION")

    private fun tombstoneFile(): File =
        File(payloadDirectory(), "${sha256(requestId)}$CLEAR_TOMBSTONE_EXTENSION")

    private fun isTombstoned(): Boolean =
        Files.exists(tombstoneFile().toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun atomicWrite(target: File, content: ByteArray) {
        val parent = target.parentFile ?: throw IOException("Payload has no parent")
        if (!isContained(target) || !isContained(parent) || !isSafeDirectoryChain(rootDir, parent)) {
            throw IOException("Payload directory is unsafe")
        }
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create payload directory")
        }
        if (!isSafeDirectoryChain(rootDir, parent)) throw IOException("Payload directory is unsafe")
        val temporary = File(parent, "${target.name}.${UUID.randomUUID()}$TEMP_EXTENSION")
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
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun ensureTombstone(tombstone: File): Boolean = runCatching {
        if (Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching isSafeRegularFile(tombstone)
        }
        val parent = tombstone.parentFile ?: return@runCatching false
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return@runCatching false
        if (!isSafeDirectoryChain(rootDir, parent)) return@runCatching false
        val temporary = File(parent, "${tombstone.name}.${UUID.randomUUID()}$TEMP_EXTENSION")
        try {
            FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ).use { channel ->
                channel.write(ByteBuffer.wrap(CLEAR_TOMBSTONE_BYTES))
                channel.force(true)
            }
            Files.move(temporary.toPath(), tombstone.toPath(), StandardCopyOption.ATOMIC_MOVE)
            dirSyncer.sync(parent)
            true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.getOrDefault(false)

    private fun File.readBounded(): String {
        FileChannel.open(toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() > MAX_ENVELOPE_BYTES) throw IOException("Payload envelope is too large")
            val buffer = ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.flip()
            return String(buffer.array(), 0, buffer.limit(), Charsets.UTF_8)
        }
    }

    private fun isSafeRegularFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())

    private fun isSafeDirectory(directory: File): Boolean =
        Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directory.toPath())

    private fun isContained(target: File): Boolean = runCatching {
        target.toPath().toAbsolutePath().normalize()
            .startsWith(rootDir.toPath().toAbsolutePath().normalize())
    }.getOrDefault(false)

    private fun isSafeDirectoryChain(root: File, directory: File): Boolean = runCatching {
        val boundary = root.toPath().toAbsolutePath().normalize()
        var current = directory.toPath().toAbsolutePath().normalize()
        if (!current.startsWith(boundary)) return@runCatching false
        while (current != boundary) {
            if (Files.isSymbolicLink(current)) return@runCatching false
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) return@runCatching false
            current = current.parent ?: return@runCatching false
        }
        Files.isDirectory(boundary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(boundary)
    }.getOrDefault(false)

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
        private const val MAX_ENVELOPE_BYTES = 768 * 1024
        private const val MAX_URL_CHARS = 2_048
        private const val MAX_SHORT_CHARS = 256
        private const val MAX_TEXT_CHARS = 4_096
        private const val MAX_LINKS = 64
        private const val MAX_FINDINGS = 64
        private const val MAX_SIGNALS = 32
        private const val ROOT_DIRECTORY = "dossier_scan_payloads/public-v1"
        private const val FILE_EXTENSION = ".payload"
        private const val CLEAR_TOMBSTONE_EXTENSION = ".cleared"
        private const val TEMP_EXTENSION = ".tmp"
        private val CLEAR_TOMBSTONE_BYTES = "public-discovery-payload-cleared-v1\n".toByteArray(Charsets.UTF_8)
        private val STORE_LOCK = Any()

        internal fun clearRequest(context: Context, requestId: String): Boolean = runCatching {
            if (!ScanLifecycleRecord.isCanonicalUuid(requestId)) return@runCatching false
            PublicDiscoveryPayloadStore(
                rootDir = context.applicationContext.filesDir,
                requestId = requestId,
                planFingerprint = "0".repeat(64),
                crypto = AndroidKeystoreCheckpointCrypto("dossier-public-discovery-v1"),
                dirSyncer = AndroidDirectorySyncer()
            ).clearWithoutPlanValidation()
        }.getOrDefault(false)

        internal fun clearAll(context: Context): Boolean = clearAll(
            rootDir = context.applicationContext.filesDir,
            dirSyncer = AndroidDirectorySyncer()
        )

        internal fun clearAll(rootDir: File, dirSyncer: DirectorySyncer): Boolean = synchronized(STORE_LOCK) {
            runCatching {
                val root = File(rootDir, ROOT_DIRECTORY)
                if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching true
                if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root.toPath())) {
                    return@runCatching false
                }
                var cleared = true
                root.listFiles().orEmpty()
                    .filter { it.name.endsWith(FILE_EXTENSION) }
                    .forEach { file ->
                        val requestHash = file.name.substringBefore('-')
                        val tombstone = File(root, "$requestHash$CLEAR_TOMBSTONE_EXTENSION")
                        if (!ensureGlobalTombstone(rootDir, tombstone, dirSyncer)) {
                            cleared = false
                        } else if (!file.delete()) {
                            cleared = false
                        }
                    }
                cleared
            }.getOrDefault(false)
        }

        private fun ensureGlobalTombstone(rootDir: File, tombstone: File, dirSyncer: DirectorySyncer): Boolean = runCatching {
            if (Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching Files.isRegularFile(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(tombstone.toPath())
            }
            val parent = tombstone.parentFile ?: return@runCatching false
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return@runCatching false
            val boundary = rootDir.toPath().toAbsolutePath().normalize()
            if (!tombstone.toPath().toAbsolutePath().normalize().startsWith(boundary)) return@runCatching false
            if (!isSafeDirectoryChainStatic(rootDir, parent)) return@runCatching false
            val temporary = File(parent, "${tombstone.name}.${UUID.randomUUID()}$TEMP_EXTENSION")
            try {
                FileChannel.open(
                    temporary.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                ).use { channel ->
                    channel.write(ByteBuffer.wrap(CLEAR_TOMBSTONE_BYTES))
                    channel.force(true)
                }
                Files.move(temporary.toPath(), tombstone.toPath(), StandardCopyOption.ATOMIC_MOVE)
                dirSyncer.sync(parent)
                true
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }.getOrDefault(false)

        private fun isSafeDirectoryChainStatic(root: File, directory: File): Boolean = runCatching {
            val boundary = root.toPath().toAbsolutePath().normalize()
            var current = directory.toPath().toAbsolutePath().normalize()
            if (!current.startsWith(boundary)) return@runCatching false
            while (current != boundary) {
                if (Files.isSymbolicLink(current)) return@runCatching false
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                ) return@runCatching false
                current = current.parent ?: return@runCatching false
            }
            Files.isDirectory(boundary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(boundary)
        }.getOrDefault(false)

        private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

        private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun clearWithoutPlanValidation(): Boolean = synchronized(STORE_LOCK) {
        runCatching {
            val directory = payloadDirectory()
            val tombstone = tombstoneFile()
            val parent = directory.parentFile ?: return@runCatching false
            if (!isContained(directory) || !isContained(tombstone) || !isSafeDirectoryChain(rootDir, parent)) {
                return@runCatching false
            }
            if (!ensureTombstone(tombstone)) return@runCatching false
            if (!directory.exists()) return@runCatching true
            if (!isSafeDirectory(directory)) return@runCatching false
            ScanPayloadStage.entries.all { stage ->
                val file = payloadFile(stage)
                !file.exists() || (isSafeRegularFile(file) && file.delete())
            }.also { if (it) directory.parentFile?.let(dirSyncer::sync) }
        }.getOrDefault(false)
    }
}

@Serializable
private data class PayloadRecord(
    val version: Int,
    val requestId: String,
    val planFingerprint: String,
    val stage: ScanPayloadStage,
    val savedAtEpochMillis: Long,
    val results: List<ProfileScanResult>
)

@Serializable
private data class PayloadEnvelope(
    val version: Int,
    val requestId: String,
    val stage: ScanPayloadStage,
    val savedAtEpochMillis: Long,
    val ivBase64: String,
    val ciphertextBase64: String
)
