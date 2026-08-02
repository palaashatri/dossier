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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bounded historical-page resolver for exact URLs.
 *
 * This is intentionally not a broad archive crawler. It asks the Internet
 * Archive Wayback Availability API for the closest accessible capture of one
 * exact URL, downloads that capture under strict limits, and returns text that
 * Dossier can run through its normal identity-attribution logic.
 *
 * Historical evidence must never be interpreted as proof that an account or
 * page is currently active.
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

            val resolved = queryAvailability(normalized)
            cache[normalized] = CachedResult(System.currentTimeMillis(), resolved)
            resolved
        }
    }

    private fun queryAvailability(originalUrl: String): Result {
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
                return fetchCapture(originalUrl, capture)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.Unavailable(
                "Wayback availability lookup failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun fetchCapture(originalUrl: String, capture: AvailabilityCapture): Result {
        val snapshotUrl = normalizeSnapshotUrl(capture.snapshotUrl)
            ?: return Result.Unavailable("Wayback returned an invalid snapshot URL")
        val request = Request.Builder()
            .url(snapshotUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.3")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.Unavailable("Wayback snapshot returned HTTP ${response.code}")
                }
                val contentType = response.body?.contentType()?.toString().orEmpty().lowercase()
                if (contentType.isNotBlank() &&
                    !contentType.contains("html") &&
                    !contentType.startsWith("text/")) {
                    return Result.Unavailable("Wayback snapshot is not an HTML/text document")
                }

                val body = response.body ?: return Result.Unavailable("Wayback snapshot was empty")
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_BODY_BYTES) {
                    return Result.Unavailable("Wayback snapshot exceeds verification size limit")
                }
                val bytes = body.byteStream().use { readBounded(it, MAX_BODY_BYTES) }
                    ?: return Result.Unavailable("Wayback snapshot exceeds verification size limit")
                val html = bytes.toString(Charsets.UTF_8)
                if (html.isBlank()) return Result.Unavailable("Wayback snapshot was empty")
                if (DiscoveryHttpPolicy.looksBlocked(html)) {
                    return Result.Unavailable("Wayback snapshot presented a challenge page")
                }

                val document = Jsoup.parse(html, snapshotUrl)
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
                if (title.isBlank() && description.isBlank() && text.isBlank()) {
                    return Result.Unavailable("Wayback snapshot contained no usable page content")
                }

                return Result.Found(
                    provider = PROVIDER_NAME,
                    originalUrl = originalUrl,
                    snapshotUrl = snapshotUrl,
                    timestamp = capture.timestamp,
                    title = title,
                    description = description,
                    text = text
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.Unavailable(
                "Wayback snapshot fetch failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    internal data class AvailabilityCapture(
        val snapshotUrl: String,
        val timestamp: String
    )

    companion object {
        private const val AVAILABILITY_ENDPOINT = "https://archive.org/wayback/available"
        private const val PROVIDER_NAME = "Internet Archive Wayback Machine"
        private const val USER_AGENT =
            "Dossier/0.1 authorized-public-self-audit (+https://github.com/palaashatri/dossier)"
        private const val MAX_BODY_BYTES = 2_000_000L
        private const val MAX_TEXT_CHARS = 10_000
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L

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
