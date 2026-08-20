package io.dossier.app.data.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bounded historical-page resolver for exact public URLs.
 *
 * Wayback is the primary provider because it exposes a stable availability API.
 * archive.today/archive.ph is a best-effort secondary provider: it has no stable
 * official API, so Dossier uses only its public newest-snapshot route and returns
 * Unavailable if that route changes or presents a challenge.
 */
internal class ArchivePageResolver(
    private val client: OkHttpClient = defaultClient()
) {
    sealed class Result {
        data class Found(
            val provider: String,
            val originalUrl: String,
            val snapshotUrl: String,
            val timestamp: String,
            val title: String,
            val description: String,
            val text: String
        ) : Result()

        data object NotFound : Result()
        data class Unavailable(val reason: String) : Result()
    }

    private data class CachedResult(val storedAt: Long, val result: Result)

    private val cache = ConcurrentHashMap<String, CachedResult>()
    private val requestMutex = Mutex()

    suspend fun resolveExactUrl(url: String): Result = withContext(Dispatchers.IO) {
        val normalized = normalizeOriginalUrl(url)
            ?: return@withContext Result.Unavailable("Invalid original URL")

        cache[normalized]?.let { cached ->
            if (System.currentTimeMillis() - cached.storedAt <= CACHE_TTL_MS) {
                return@withContext cached.result
            }
            cache.remove(normalized, cached)
        }

        requestMutex.withLock {
            cache[normalized]?.let { cached ->
                if (System.currentTimeMillis() - cached.storedAt <= CACHE_TTL_MS) {
                    return@withLock cached.result
                }
            }

            val wayback = queryWaybackAvailability(normalized)
            val resolved = if (wayback is Result.Found) {
                wayback
            } else {
                mergeArchiveFallback(wayback, queryArchiveToday(normalized))
            }
            cache[normalized] = CachedResult(System.currentTimeMillis(), resolved)
            resolved
        }
    }

    private fun mergeArchiveFallback(wayback: Result, archiveToday: Result): Result = when {
        archiveToday is Result.Found -> archiveToday
        wayback is Result.Unavailable && archiveToday is Result.Unavailable ->
            Result.Unavailable("${wayback.reason}; archive.today fallback unavailable: ${archiveToday.reason}")
        wayback is Result.Unavailable -> wayback
        archiveToday is Result.Unavailable -> archiveToday
        else -> Result.NotFound
    }

    private fun queryWaybackAvailability(originalUrl: String): Result {
        val availabilityUrl = AVAILABILITY_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("url", originalUrl)
            .build()
        val request = Request.Builder()
            .url(availabilityUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return if (response.code == 404) Result.NotFound
                    else Result.Unavailable("Wayback availability returned HTTP ${response.code}")
                }
                val payload = response.body?.string().orEmpty()
                val capture = parseAvailability(payload) ?: return Result.NotFound
                return fetchWaybackCapture(originalUrl, capture)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.Unavailable(
                "Wayback availability lookup failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun fetchWaybackCapture(originalUrl: String, capture: AvailabilityCapture): Result {
        val snapshotUrl = normalizeSnapshotUrl(capture.snapshotUrl)
            ?: return Result.Unavailable("Wayback returned an invalid snapshot URL")
        return fetchHtmlSnapshot(
            provider = WAYBACK_PROVIDER_NAME,
            originalUrl = originalUrl,
            snapshotUrl = snapshotUrl,
            timestamp = capture.timestamp,
            providerLabel = "Wayback"
        )
    }

    private fun queryArchiveToday(originalUrl: String): Result {
        val target = sanitizeArchiveTodayTarget(originalUrl)
            ?: return Result.Unavailable("archive.today received an invalid original URL")
        val lookupUrl = "$ARCHIVE_TODAY_NEWEST$target"
        val request = runCatching {
            Request.Builder()
                .url(lookupUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.3")
                .build()
        }.getOrElse { return Result.Unavailable("archive.today lookup URL could not be built") }

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> return Result.NotFound
                    response.code == 401 || response.code == 403 || response.code == 429 ->
                        return Result.Unavailable("archive.today rejected or rate-limited the lookup (HTTP ${response.code})")
                    !response.isSuccessful ->
                        return Result.Unavailable("archive.today returned HTTP ${response.code}")
                }

                val finalUrl = response.request.url.toString()
                val snapshotUrl = normalizeArchiveTodaySnapshotUrl(finalUrl) ?: return Result.NotFound
                val contentType = response.body?.contentType()?.toString().orEmpty().lowercase()
                if (contentType.isNotBlank() && !contentType.contains("html") && !contentType.startsWith("text/")) {
                    return Result.Unavailable("archive.today snapshot is not an HTML/text document")
                }
                val body = response.body ?: return Result.Unavailable("archive.today snapshot was empty")
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_BODY_BYTES) {
                    return Result.Unavailable("archive.today snapshot exceeds verification size limit")
                }
                val bytes = body.byteStream().use { readBounded(it, MAX_BODY_BYTES) }
                    ?: return Result.Unavailable("archive.today snapshot exceeds verification size limit")
                val html = bytes.toString(Charsets.UTF_8)
                if (html.isBlank()) return Result.Unavailable("archive.today snapshot was empty")
                if (DiscoveryHttpPolicy.looksBlocked(html)) {
                    return Result.Unavailable("archive.today snapshot presented a challenge page")
                }

                val parsed = parseHtml(snapshotUrl, html)
                    ?: return Result.Unavailable("archive.today snapshot contained no usable page content")
                return Result.Found(
                    provider = ARCHIVE_TODAY_PROVIDER_NAME,
                    originalUrl = originalUrl,
                    snapshotUrl = snapshotUrl,
                    timestamp = parseArchiveTimestamp(response.header("Memento-Datetime")).orEmpty(),
                    title = parsed.title,
                    description = parsed.description,
                    text = parsed.text
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.Unavailable(
                "archive.today lookup failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun fetchHtmlSnapshot(
        provider: String,
        originalUrl: String,
        snapshotUrl: String,
        timestamp: String,
        providerLabel: String
    ): Result {
        val request = Request.Builder()
            .url(snapshotUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.3")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.Unavailable("$providerLabel snapshot returned HTTP ${response.code}")
                }
                val contentType = response.body?.contentType()?.toString().orEmpty().lowercase()
                if (contentType.isNotBlank() &&
                    !contentType.contains("html") &&
                    !contentType.startsWith("text/")) {
                    return Result.Unavailable("$providerLabel snapshot is not an HTML/text document")
                }

                val body = response.body ?: return Result.Unavailable("$providerLabel snapshot was empty")
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_BODY_BYTES) {
                    return Result.Unavailable("$providerLabel snapshot exceeds verification size limit")
                }
                val bytes = body.byteStream().use { readBounded(it, MAX_BODY_BYTES) }
                    ?: return Result.Unavailable("$providerLabel snapshot exceeds verification size limit")
                val html = bytes.toString(Charsets.UTF_8)
                if (html.isBlank()) return Result.Unavailable("$providerLabel snapshot was empty")
                if (DiscoveryHttpPolicy.looksBlocked(html)) {
                    return Result.Unavailable("$providerLabel snapshot presented a challenge page")
                }

                val parsed = parseHtml(snapshotUrl, html)
                    ?: return Result.Unavailable("$providerLabel snapshot contained no usable page content")

                return Result.Found(
                    provider = provider,
                    originalUrl = originalUrl,
                    snapshotUrl = snapshotUrl,
                    timestamp = timestamp,
                    title = parsed.title,
                    description = parsed.description,
                    text = parsed.text
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.Unavailable(
                "$providerLabel snapshot fetch failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    private data class ParsedHtml(val title: String, val description: String, val text: String)

    private fun parseHtml(baseUrl: String, html: String): ParsedHtml? {
        val document = Jsoup.parse(html, baseUrl)
        document.select(
            "script,style,noscript,svg,template,#wm-ipp,#wm-ipp-base,.wb-autocomplete-suggestions"
        ).remove()
        val title = document.title().trim()
        val description = document
            .select("meta[name=description],meta[property=og:description],meta[name=twitter:description]")
            .firstOrNull()
            ?.attr("content")
            ?.trim()
            .orEmpty()
        val text = document.body()?.text()?.trim().orEmpty().take(MAX_TEXT_CHARS)
        if (title.isBlank() && description.isBlank() && text.isBlank()) return null
        return ParsedHtml(title, description, text)
    }

    internal data class AvailabilityCapture(
        val snapshotUrl: String,
        val timestamp: String
    )

    companion object {
        private const val AVAILABILITY_ENDPOINT = "https://archive.org/wayback/available"
        private const val ARCHIVE_TODAY_NEWEST = "https://archive.ph/newest/"
        private const val WAYBACK_PROVIDER_NAME = "Internet Archive Wayback Machine"
        private const val ARCHIVE_TODAY_PROVIDER_NAME = "Archive.today (archive.ph)"
        private const val USER_AGENT = "Dossier/0.1 authorized-public-self-audit"
        private const val MAX_BODY_BYTES = 2_000_000L
        private const val MAX_TEXT_CHARS = 10_000
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L
        private val ARCHIVE_TODAY_HOSTS = setOf("archive.ph", "archive.today", "archive.is")

        internal fun parseAvailability(payload: String): AvailabilityCapture? = runCatching {
            val root = Json.parseToJsonElement(payload).jsonObject
            val closest = root["archived_snapshots"]
                ?.jsonObject
                ?.get("closest")
                ?.jsonObject
                ?: return@runCatching null
            val available = closest["available"]?.jsonPrimitive?.booleanOrNull ?: false
            val status = closest["status"]?.jsonPrimitive?.content.orEmpty()
            val url = closest["url"]?.jsonPrimitive?.content.orEmpty()
            val timestamp = closest["timestamp"]?.jsonPrimitive?.content.orEmpty()
            if (!available || status != "200" || url.isBlank() || timestamp.length !in 4..14) {
                return@runCatching null
            }
            AvailabilityCapture(url, timestamp)
        }.getOrNull()

        internal fun normalizeSnapshotUrl(raw: String): String? {
            val upgraded = raw.trim().replaceFirst("http://web.archive.org", "https://web.archive.org")
            val uri = runCatching { URI(upgraded) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "web.archive.org" && !host.endsWith(".web.archive.org")) return null
            if (uri.scheme != "https") return null
            return upgraded
        }

        internal fun sanitizeArchiveTodayTarget(raw: String): String? {
            val normalized = normalizeOriginalUrl(raw) ?: return null
            // archive.today's public newest route treats an unescaped '?' as its own
            // query string. Prefer the stable base URL rather than pretending a
            // query-specific snapshot was verified.
            return normalized.substringBefore('?')
        }

        internal fun normalizeArchiveTodaySnapshotUrl(raw: String): String? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "https") return null
            val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
            if (host !in ARCHIVE_TODAY_HOSTS) return null
            val path = uri.path.orEmpty().trim('/')
            if (path.isBlank() || path.startsWith("newest/", true)) return null
            // Snapshot IDs are short opaque path components. Reject search/home routes.
            val first = path.substringBefore('/')
            if (first.length < 4 || first.equals("search", true) || first.equals("submit", true)) return null
            return raw.trim()
        }

        internal fun parseArchiveTimestamp(header: String?): String? {
            if (header.isNullOrBlank()) return null
            return runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT))
            }.getOrNull()
        }

        internal fun displayTimestamp(timestamp: String): String = when {
            timestamp.length >= 8 ->
                "${timestamp.substring(0, 4)}-${timestamp.substring(4, 6)}-${timestamp.substring(6, 8)}"
            timestamp.length >= 6 -> "${timestamp.substring(0, 4)}-${timestamp.substring(4, 6)}"
            timestamp.length >= 4 -> timestamp.substring(0, 4)
            else -> "unknown date"
        }

        internal fun readBounded(input: InputStream, maxBytes: Long): ByteArray? {
            require(maxBytes > 0)
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

        private fun normalizeOriginalUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            return trimmed.substringBefore('#')
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
