package io.dossier.app.data.web

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ScannerPlugin
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Historical snapshot discovery for profile/personal-site URLs explicitly supplied
 * to an authorized audit. The CDX index is discovery only; selected snapshots are
 * re-fetched before their state is promoted to Verified.
 */
class WaybackHistoryPlugin(
    private val client: OkHttpClient = defaultClient()
) : ScannerPlugin {
    override val id: String = "wayback-history"
    override val displayName: String = "Wayback Historical Profiles"

    override suspend fun scan(input: IdentityInput): EvidenceCollection = withContext(Dispatchers.IO) {
        val urls = input.profileUrls
            .mapNotNull(::normalizeOriginalUrl)
            .distinctBy { it.lowercase() }
            .take(MAX_PROFILE_URLS)
        if (urls.isEmpty()) return@withContext EvidenceCollection()

        val evidence = mutableListOf<Evidence>()
        val relationships = mutableListOf<EvidenceRelationship>()

        for (originalUrl in urls) {
            try {
                val captures = discover(originalUrl).take(MAX_CAPTURES_PER_URL)
                for ((index, capture) in captures.withIndex()) {
                    val snapshotUrl = snapshotUrl(capture)
                    val fetched = if (index < MAX_DIRECT_FETCHES_PER_URL) fetchSnapshot(snapshotUrl) else null
                    val state = if (fetched != null) EvidenceState.Verified else EvidenceState.Observed
                    val snippet = fetched?.let { page ->
                        buildList {
                            if (page.title.isNotBlank()) add(page.title)
                            if (page.description.isNotBlank()) add(page.description)
                            if (page.text.isNotBlank()) add(page.text.take(700))
                        }.joinToString(" — ").take(MAX_SNIPPET_CHARS)
                    } ?: "Wayback CDX capture ${capture.timestamp} for $originalUrl"

                    evidence += Evidence(
                        id = "wayback:${sha256("$originalUrl|${capture.timestamp}|${capture.digest}").take(32)}",
                        kind = EvidenceKind.PublicSearchEvidence,
                        value = snapshotUrl,
                        sourceUrl = snapshotUrl,
                        snippet = snippet,
                        confidence = if (fetched != null) VERIFIED_SNAPSHOT_CONFIDENCE else INDEXED_SNAPSHOT_CONFIDENCE,
                        risk = RiskLevel.Low,
                        signals = buildList {
                            add("Exact URL was explicitly supplied to this authorized audit")
                            add("Wayback CDX indexed HTTP ${capture.statusCode} ${capture.mimeType} capture")
                            if (capture.digest.isNotBlank()) add("Wayback CDX digest: ${capture.digest} (archive digest, not SHA-256)")
                            if (fetched != null) add("Archived snapshot was directly re-fetched and parsed")
                            else add("CDX-indexed historical capture; snapshot body was not re-fetched within the verification budget")
                        },
                        providerId = if (fetched != null) "wayback-snapshot" else "wayback-cdx",
                        retrievedAtEpochMillis = System.currentTimeMillis(),
                        observedAtEpochMillis = timestampMillis(capture.timestamp),
                        state = state,
                        reliability = EvidenceReliability.ArchiveSnapshot,
                        contentHashSha256 = fetched?.let { sha256(it.text) },
                        parserVersion = PARSER_VERSION,
                        historical = true
                    )
                    relationships += EvidenceRelationship(
                        fromValue = originalUrl,
                        toValue = snapshotUrl,
                        relation = "ARCHIVED_AS",
                        evidence = "Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Historical-provider failure is isolated from the rest of the scan.
            }
        }

        EvidenceCollection(
            evidence = evidence.distinctBy(Evidence::id),
            relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
        )
    }

    private fun discover(originalUrl: String): List<Capture> {
        val url = CDX_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("url", originalUrl)
            .addQueryParameter("output", "json")
            .addQueryParameter("fl", "timestamp,original,digest,statuscode,mimetype")
            .addQueryParameter("filter", "statuscode:200")
            .addQueryParameter("filter", "mimetype:text/html")
            .addQueryParameter("collapse", "digest")
            .addQueryParameter("filter", "!digest:-")
            .addQueryParameter("limit", MAX_CDX_ROWS.toString())
            .build()
        val body = fetchText(url.toString(), "application/json") ?: return emptyList()
        return parseCdx(body, originalUrl)
    }

    private data class ParsedPage(val title: String, val description: String, val text: String)

    private fun fetchSnapshot(url: String): ParsedPage? {
        val body = fetchText(url, "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.3") ?: return null
        if (body.isBlank() || DiscoveryHttpPolicy.looksBlocked(body)) return null
        val doc = Jsoup.parse(body, url)
        doc.select("script,style,noscript,svg,template,#wm-ipp,#wm-ipp-base,.wb-autocomplete-suggestions").remove()
        val title = doc.title().trim()
        val description = doc
            .select("meta[name=description],meta[property=og:description],meta[name=twitter:description]")
            .firstOrNull()?.attr("content")?.trim().orEmpty()
        val text = doc.body()?.text()?.trim().orEmpty().take(MAX_PAGE_TEXT_CHARS)
        if (title.isBlank() && description.isBlank() && text.isBlank()) return null
        return ParsedPage(title, description, text)
    }

    private fun fetchText(url: String, accept: String): String? {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .build()
        }.getOrNull() ?: return null
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BODY_BYTES) return null
                body.byteStream().use { input -> readBounded(input, MAX_BODY_BYTES) }
                    ?.toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal data class Capture(
        val timestamp: String,
        val originalUrl: String,
        val digest: String,
        val statusCode: String,
        val mimeType: String
    )

    companion object {
        private const val CDX_ENDPOINT = "https://web.archive.org/cdx/search/cdx"
        private const val USER_AGENT = "Dossier/0.1 authorized-public-self-audit"
        private const val PARSER_VERSION = "wayback-history-v1"
        private const val MAX_PROFILE_URLS = 4
        private const val MAX_CDX_ROWS = 12
        private const val MAX_CAPTURES_PER_URL = 6
        private const val MAX_DIRECT_FETCHES_PER_URL = 3
        private const val MAX_BODY_BYTES = 2_000_000L
        private const val MAX_PAGE_TEXT_CHARS = 10_000
        private const val MAX_SNIPPET_CHARS = 900
        private const val INDEXED_SNAPSHOT_CONFIDENCE = 0.58f
        private const val VERIFIED_SNAPSHOT_CONFIDENCE = 0.82f
        private val JSON = Json { ignoreUnknownKeys = true }

        internal fun parseCdx(payload: String, expectedOriginalUrl: String): List<Capture> {
            val root = runCatching { JSON.parseToJsonElement(payload) as? JsonArray }.getOrNull()
                ?: return emptyList()
            if (root.size < 2) return emptyList()
            val header = (root.firstOrNull() as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: return emptyList()
            val index = header.withIndex().associate { it.value to it.index }
            val required = listOf("timestamp", "original", "digest", "statuscode", "mimetype")
            if (!required.all(index::containsKey)) return emptyList()

            return root.drop(1).mapNotNull { element ->
                val row = element as? JsonArray ?: return@mapNotNull null
                fun field(name: String): String = (row.getOrNull(index.getValue(name)) as? JsonPrimitive)
                    ?.contentOrNull.orEmpty()
                val timestamp = field("timestamp")
                val original = field("original")
                val digest = field("digest")
                val status = field("statuscode")
                val mime = field("mimetype")
                if (!timestamp.matches(Regex("^\\d{14}$"))) return@mapNotNull null
                if (status != "200" || !mime.equals("text/html", true)) return@mapNotNull null
                if (normalizeComparableUrl(original) != normalizeComparableUrl(expectedOriginalUrl)) return@mapNotNull null
                Capture(timestamp, original, digest, status, mime)
            }.distinctBy { "${it.timestamp}|${it.digest}" }
                .sortedByDescending(Capture::timestamp)
        }

        internal fun snapshotUrl(capture: Capture): String =
            "https://web.archive.org/web/${capture.timestamp}id_/${capture.originalUrl}"

        internal fun timestampMillis(timestamp: String): Long? = runCatching {
            LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()

        private fun normalizeOriginalUrl(raw: String): String? {
            val trimmed = raw.trim().substringBefore('#')
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
            return trimmed
        }

        private fun normalizeComparableUrl(raw: String): String = runCatching {
            val uri = URI(raw.trim().substringBefore('#'))
            val host = uri.host?.removePrefix("www.")?.lowercase().orEmpty()
            val path = uri.path.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$host$path$query"
        }.getOrDefault(raw.trim().lowercase().trimEnd('/'))

        private fun readBounded(input: InputStream, maxBytes: Long): ByteArray? {
            val output = ByteArrayOutputStream(minOf(maxBytes, 32_768L).toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
