package io.dossier.app.data.web

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Ephemeral local import session for legacy OSINT exports.
 *
 * Files are selected by the user through Android's document picker, read locally,
 * bounded before allocation, and kept in memory only. Dossier never launches Twint
 * or snscrape and never uploads the export. The parser later filters every record to
 * handles explicitly authorized in the active audit.
 */
object LegacyOsintImportSession {
    internal data class PendingImport(
        val id: String,
        val source: LegacyOsintExportParser.Source,
        val displayName: String,
        val rawText: String,
        val byteCount: Int,
        val sha256: String
    )

    data class ImportSummary(
        val id: String,
        val source: LegacyOsintExportParser.Source,
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

    suspend fun add(
        context: Context,
        uri: Uri,
        source: LegacyOsintExportParser.Source
    ): ImportResult = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_IMPORT_BYTES) }
                ?: return@withContext ImportResult.Rejected("The selected file could not be opened")
        } catch (_: SecurityException) {
            return@withContext ImportResult.Rejected("Dossier does not have permission to read the selected file")
        } catch (_: Exception) {
            return@withContext ImportResult.Rejected("The selected file could not be read")
        }

        if (bytes.isEmpty()) return@withContext ImportResult.Rejected("The selected export is empty")
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) return@withContext ImportResult.Rejected("The selected export contains no UTF-8 text")

        val digest = sha256(bytes)
        val displayName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.take(80)
            ?.ifBlank { null }
            ?: when (source) {
                LegacyOsintExportParser.Source.TwintJson -> "Twint JSON export"
                LegacyOsintExportParser.Source.SnscrapeJsonl -> "snscrape JSONL export"
            }
        val id = "legacy-import:${source.name.lowercase()}:${digest.take(24)}"
        val item = PendingImport(
            id = id,
            source = source,
            displayName = displayName,
            rawText = text,
            byteCount = bytes.size,
            sha256 = digest
        )

        synchronized(lock) {
            pending.removeAll { it.id == id }
            if (pending.size >= MAX_IMPORTS) pending.removeAt(0)
            pending += item
        }
        ImportResult.Added(item.toSummary())
    }

    fun summaries(): List<ImportSummary> = synchronized(lock) {
        pending.map(PendingImport::toSummary)
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

    private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
    private const val MAX_IMPORTS = 4
}
