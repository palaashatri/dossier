package io.dossier.app.data.web

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Ephemeral local import session for external OSINT reports.
 *
 * The selected file is read through Android's document provider, bounded before
 * allocation, kept in memory only, and never uploaded. Source detection is only a
 * parsing hint; regardless of the detected tool, the downstream parser enforces
 * explicit audit seeds and strips/rejects credential material.
 */
object ExternalOsintImportSession {
    internal data class PendingImport(
        val id: String,
        val source: ExternalOsintReportParser.Source,
        val displayName: String,
        val rawText: String,
        val byteCount: Int,
        val sha256: String
    )

    data class ImportSummary(
        val id: String,
        val source: ExternalOsintReportParser.Source,
        val displayName: String,
        val byteCount: Int,
        val sha256: String
    )

    sealed class ImportResult {
        data class Added(val summary: ImportSummary) : ImportResult()
        data class Rejected(val reason: String) : ImportResult()
    }

    private val lock = Any()
    private val pending = mutableListOf<PendingImport>()

    suspend fun add(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_IMPORT_BYTES) }
                ?: return@withContext ImportResult.Rejected("The selected report could not be opened")
        } catch (_: ImportTooLargeException) {
            return@withContext ImportResult.Rejected("The selected report exceeds the ${MAX_IMPORT_BYTES / (1024 * 1024)} MiB local import limit")
        } catch (_: SecurityException) {
            return@withContext ImportResult.Rejected("Dossier does not have permission to read the selected report")
        } catch (_: Exception) {
            return@withContext ImportResult.Rejected("The selected report could not be read")
        }

        if (bytes.isEmpty()) return@withContext ImportResult.Rejected("The selected report is empty")
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            ?: return@withContext ImportResult.Rejected("The selected report is not readable UTF-8 text")
        if (text.isBlank()) return@withContext ImportResult.Rejected("The selected report contains no text")

        val displayName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.take(100)
            ?.ifBlank { null }
            ?: "OSINT report"
        val source = ExternalOsintReportParser.detectSource(displayName, text)
        val digest = sha256(bytes)
        val id = "external-import:${source.name.lowercase()}:${digest.take(24)}"
        val item = PendingImport(id, source, displayName, text, bytes.size, digest)

        synchronized(lock) {
            pending.removeAll { it.id == id }
            if (pending.size >= MAX_IMPORTS) pending.removeAt(0)
            pending += item
        }
        ImportResult.Added(item.toSummary())
    }

    fun summaries(): List<ImportSummary> = synchronized(lock) {
        pending.map { item -> item.toSummary() }
    }

    internal fun snapshot(): List<PendingImport> = synchronized(lock) { pending.toList() }

    fun remove(id: String) {
        synchronized(lock) { pending.removeAll { it.id == id } }
    }

    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    private fun PendingImport.toSummary() = ImportSummary(id, source, displayName, byteCount, sha256)

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 32_768))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ImportTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class ImportTooLargeException : RuntimeException()

    private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    private const val MAX_IMPORTS = 8
}
