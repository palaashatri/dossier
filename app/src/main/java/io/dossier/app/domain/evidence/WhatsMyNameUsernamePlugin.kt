package io.dossier.app.domain.evidence

import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderDiagnosticsRuntime
import io.dossier.app.domain.discovery.ProviderOutcome
import io.dossier.app.domain.discovery.ProviderRequestScheduler
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Broad username-surface enumeration using WhatsMyName's public site-definition
 * dataset as response-classification rules.
 *
 * Correctness boundary:
 * - only usernames explicitly supplied to the assessment are checked;
 * - GET-only entries with a literal {account} placeholder are eligible;
 * - POST probes, authentication/CAPTCHA flows, invalid/broken entries and NSFW
 *   categories are skipped rather than bypassed;
 * - direct account existence is an observation, never proof that the account
 *   belongs to the investigated identity.
 *
 * Requests are paced per provider and bounded globally. Aggregate provider health
 * records contain provider IDs/outcomes only and never queried handles or content.
 */
class WhatsMyNameUsernamePlugin : ScannerPlugin {
    override val id: String = SOURCE_ID
    override val displayName: String = "WhatsMyName public username surface"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val scheduler = ProviderRequestScheduler(MAX_CONCURRENCY)

    override suspend fun scan(input: IdentityInput): EvidenceCollection {
        val handles = (listOfNotNull(input.primaryUsername) + input.usernames)
            .map(::normalizeHandle)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_HANDLES)
        if (handles.isEmpty()) {
            UsernameSurfaceRuntimeCache.replace(SOURCE_ID, emptyList())
            return EvidenceCollection()
        }

        val sites = fetchDataset()
        if (sites.isEmpty()) {
            UsernameSurfaceRuntimeCache.replace(SOURCE_ID, emptyList())
            return EvidenceCollection()
        }

        val mode = DiscoveryScanPreferences.selectedMode.value
        val eligible = sites
            .asSequence()
            .filter(::eligibleSite)
            .take(siteLimit(mode))
            .toList()
        if (eligible.isEmpty()) {
            UsernameSurfaceRuntimeCache.replace(SOURCE_ID, emptyList())
            return EvidenceCollection()
        }

        val observations = coroutineScope {
            handles.flatMap { handle ->
                eligible.map { site ->
                    async(Dispatchers.IO) {
                        scheduler.execute(
                            providerKey = providerKey(site),
                            minimumIntervalMs = PER_PROVIDER_INTERVAL_MS
                        ) {
                            checkSite(site, handle)
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        scheduler.clearIdleState()

        UsernameSurfaceRuntimeCache.replace(SOURCE_ID, observations)

        val evidence = observations
            .filter { it.state == UsernameSurfaceState.Present }
            .map { observation ->
                Evidence(
                    id = "wmn:${sha256("${observation.site}|${observation.username}|${observation.profileUrl}").take(32)}",
                    kind = EvidenceKind.Profile,
                    value = observation.profileUrl,
                    sourceUrl = observation.profileUrl,
                    snippet = "${observation.site} returned the WhatsMyName-defined public existence signal for @${observation.username}.",
                    confidence = observation.confidence.toFloat().coerceIn(0f, 1f),
                    risk = RiskLevel.Low,
                    signals = listOf(
                        "Direct GET check matched a WhatsMyName public existence rule",
                        "The handle was explicitly supplied to this assessment",
                        "Account existence does not establish ownership by the investigated identity"
                    ),
                    providerId = SOURCE_ID,
                    retrievedAtEpochMillis = observation.observedAtEpochMillis,
                    observedAtEpochMillis = observation.observedAtEpochMillis,
                    state = EvidenceState.Observed,
                    reliability = EvidenceReliability.DirectPublicProfile,
                    parserVersion = PARSER_VERSION,
                    historical = false
                )
            }

        val relationships = evidence.mapNotNull { item ->
            val observation = observations.firstOrNull { it.profileUrl == item.value && it.state == UsernameSurfaceState.Present }
                ?: return@mapNotNull null
            EvidenceRelationship(
                fromValue = observation.username,
                toValue = observation.profileUrl,
                relation = "PUBLIC_PROFILE_EXISTS",
                evidence = "Direct public username-existence observation via WhatsMyName response rule; identity ownership unverified"
            )
        }

        return EvidenceCollection(
            evidence = evidence.distinctBy(Evidence::id),
            relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
        )
    }

    private fun fetchDataset(): List<JsonObject> = runCatching {
        val request = Request.Builder()
            .url(DATASET_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body ?: return emptyList()
            if ((body.contentLength().takeIf { it >= 0 } ?: 0L) > MAX_DATASET_BYTES) return emptyList()
            val text = body.charStream().use { reader ->
                val buffer = CharArray(8192)
                val out = StringBuilder()
                var chars = 0
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    chars += read
                    if (chars > MAX_DATASET_CHARS) return emptyList()
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
            val root = JSON.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            (root["sites"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
    }.getOrElse { emptyList() }

    private fun eligibleSite(site: JsonObject): Boolean {
        if ((site["valid"] as? JsonPrimitive)?.booleanOrNull == false) return false
        if ((site["post_body"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) return false
        val category = site.string("cat")?.lowercase(Locale.ROOT).orEmpty()
        if (category.contains("nsfw")) return false
        val uri = site.string("uri_check") ?: return false
        if (!uri.contains("{account}")) return false
        if (!isHttpUrl(uri.replace("{account}", "probe"))) return false
        val protection = (site["protection"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT) }
            .toSet()
        if ("captcha" in protection || "user-auth" in protection || "anubis" in protection) return false
        return true
    }

    private fun checkSite(site: JsonObject, originalHandle: String): UsernameSurfaceObservation? {
        val siteName = site.string("name")?.take(MAX_SITE_NAME_CHARS) ?: return null
        val healthId = "$SOURCE_ID:${siteName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(100)}"
        val stripChars = site.string("strip_bad_char").orEmpty()
        val handle = stripChars.fold(originalHandle) { acc, ch -> acc.replace(ch.toString(), "") }
            .takeIf { it.length >= 2 } ?: return null
        val checkTemplate = site.string("uri_check") ?: return null
        val checkUrl = checkTemplate.replace("{account}", handle)
        if (!isHttpUrl(checkUrl)) return null
        val prettyTemplate = site.string("uri_pretty")
        val profileUrl = prettyTemplate?.replace("{account}", handle)
            ?.takeIf(::isHttpUrl)
            ?: checkUrl
        val expectedExistsCode = site.int("e_code") ?: return null
        val expectedMissingCode = site.int("m_code") ?: return null
        val existsText = site.string("e_string").orEmpty()
        val missingText = site.string("m_string").orEmpty()
        val observedAt = System.currentTimeMillis()
        val started = System.nanoTime()

        return try {
            val request = Request.Builder()
                .url(checkUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.7")
                .build()
            client.newCall(request).execute().use { response ->
                val status = response.code
                val body = response.body?.source()?.let { source ->
                    source.request(MAX_RESPONSE_BYTES.toLong())
                    val byteCount = minOf(source.buffer.size, MAX_RESPONSE_BYTES.toLong())
                    source.buffer.clone().readUtf8(byteCount)
                }.orEmpty()
                val exists = status == expectedExistsCode && (existsText.isBlank() || body.contains(existsText, ignoreCase = false))
                val missing = status == expectedMissingCode && (missingText.isBlank() || body.contains(missingText, ignoreCase = false))
                val latency = elapsedMillis(started)
                when {
                    exists -> {
                        ProviderDiagnosticsRuntime.record(healthId, ProviderOutcome.Success, latency)
                        UsernameSurfaceObservation(
                            SOURCE_ID, siteName, originalHandle, profileUrl,
                            UsernameSurfaceState.Present, 0.68,
                            "HTTP $status matched the site's published existence rule", observedAt
                        )
                    }
                    missing -> {
                        ProviderDiagnosticsRuntime.record(healthId, ProviderOutcome.NotFound, latency)
                        UsernameSurfaceObservation(
                            SOURCE_ID, siteName, originalHandle, profileUrl,
                            UsernameSurfaceState.Absent, 0.90,
                            "HTTP $status matched the site's published missing-account rule", observedAt
                        )
                    }
                    status == 429 -> {
                        ProviderDiagnosticsRuntime.record(healthId, ProviderOutcome.RateLimited, latency)
                        unavailable(siteName, originalHandle, profileUrl, observedAt, "Provider rate-limited the public check")
                    }
                    status == 401 -> {
                        ProviderDiagnosticsRuntime.record(healthId, ProviderOutcome.AuthenticationRequired, latency)
                        unavailable(siteName, originalHandle, profileUrl, observedAt, "Provider requires authentication")
                    }
                    else -> {
                        ProviderDiagnosticsRuntime.record(healthId, ProviderOutcome.ParseFailure, latency)
                        unavailable(
                            siteName, originalHandle, profileUrl, observedAt,
                            "Response did not conclusively match the published exists/missing rules (HTTP $status)"
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val outcome = if (error is java.net.SocketTimeoutException) ProviderOutcome.Timeout else ProviderOutcome.NetworkFailure
            ProviderDiagnosticsRuntime.record(healthId, outcome, elapsedMillis(started))
            unavailable(siteName, originalHandle, profileUrl, observedAt, "Provider could not be conclusively checked")
        }
    }

    private fun unavailable(
        siteName: String,
        handle: String,
        profileUrl: String,
        observedAt: Long,
        reason: String
    ) = UsernameSurfaceObservation(
        SOURCE_ID, siteName, handle, profileUrl,
        UsernameSurfaceState.Unavailable, 0.0, reason, observedAt
    )

    private fun providerKey(site: JsonObject): String {
        val uri = site.string("uri_check").orEmpty().replace("{account}", "probe")
        return runCatching { URI(uri).host?.lowercase(Locale.ROOT) }.getOrNull()
            ?: site.string("name").orEmpty().lowercase(Locale.ROOT)
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun normalizeHandle(raw: String): String = raw.trim()
        .removePrefix("@")
        .removePrefix("u/")
        .lowercase(Locale.ROOT)
        .takeIf { it.matches(HANDLE) }
        .orEmpty()

    private fun isHttpUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun siteLimit(mode: ScanMode): Int = when (mode) {
        ScanMode.Quick -> 50
        ScanMode.Standard -> 200
        ScanMode.Deep -> 500
        ScanMode.Exhaustive -> Int.MAX_VALUE
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SOURCE_ID = "whatsmyname-direct"
        const val DATASET_URL = "https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json"
        const val USER_AGENT = "Dossier/0.1 authorized-assessment (+https://github.com/palaashatri/dossier)"
        const val MAX_HANDLES = 3
        const val MAX_CONCURRENCY = 6
        const val PER_PROVIDER_INTERVAL_MS = 350L
        const val MAX_RESPONSE_BYTES = 192 * 1024
        const val MAX_DATASET_BYTES = 4L * 1024L * 1024L
        const val MAX_DATASET_CHARS = 4 * 1024 * 1024
        const val MAX_SITE_NAME_CHARS = 120
        const val PARSER_VERSION = "whatsmyname-direct-v2"
        val HANDLE = Regex("[a-z0-9_][a-z0-9_.-]{1,63}")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
