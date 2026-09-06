package io.dossier.app.data.web

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.HistoricalAttributeKind
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
import java.time.format.ResolverStyle
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
                    val fetched = if (index < MAX_DIRECT_FETCHES_PER_URL) fetchSnapshot(snapshotUrl, originalUrl) else null
                    val state = if (fetched != null) EvidenceState.Verified else EvidenceState.Observed
                    val snippet = fetched?.let { page ->
                        buildList {
                            if (page.title.isNotBlank()) add(page.title)
                            if (page.description.isNotBlank()) add(page.description)
                            if (page.text.isNotBlank()) add(page.text.take(700))
                        }.joinToString(" — ").take(MAX_SNIPPET_CHARS)
                    } ?: "Wayback CDX capture ${capture.timestamp} for $originalUrl"

                    val now = System.currentTimeMillis()
                    val observedTimestamp = timestampMillis(capture.timestamp)

                    val snapshotEvidenceId = "wayback:${sha256("$originalUrl|${capture.timestamp}|${capture.digest}").take(32)}"
                    evidence += Evidence(
                        id = snapshotEvidenceId,
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
                        retrievedAtEpochMillis = now,
                        observedAtEpochMillis = observedTimestamp,
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
                        evidence = "Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                        evidenceIds = listOf(snapshotEvidenceId)
                    )

                    if (fetched != null) {
                        val meta = fetched.metadata
                        meta.displayName?.let { name ->
                            evidence += Evidence(
                                id = "wayback:attr:${sha256("$originalUrl|${capture.timestamp}|DisplayName|$name").take(32)}",
                                kind = EvidenceKind.Profile,
                                attributeKind = HistoricalAttributeKind.DisplayName,
                                value = name,
                                sourceUrl = snapshotUrl,
                                snippet = "Historical display name extracted from Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                confidence = VERIFIED_SNAPSHOT_CONFIDENCE,
                                risk = RiskLevel.Low,
                                signals = listOf(
                                    "Historical display name extracted from directly re-fetched Wayback snapshot",
                                    "Observed in historical capture dated ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                    "Historical observation only; does not prove current identity or active account ownership"
                                ),
                                providerId = "wayback-snapshot",
                                retrievedAtEpochMillis = now,
                                observedAtEpochMillis = observedTimestamp,
                                state = EvidenceState.Verified,
                                reliability = EvidenceReliability.ArchiveSnapshot,
                                contentHashSha256 = sha256(fetched.text),
                                parserVersion = PARSER_VERSION,
                                historical = true
                            )
                        }
                        meta.bio?.let { bio ->
                            evidence += Evidence(
                                id = "wayback:attr:${sha256("$originalUrl|${capture.timestamp}|Bio|$bio").take(32)}",
                                kind = EvidenceKind.Profile,
                                attributeKind = HistoricalAttributeKind.Bio,
                                value = bio,
                                sourceUrl = snapshotUrl,
                                snippet = "Historical bio extracted from Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                confidence = VERIFIED_SNAPSHOT_CONFIDENCE,
                                risk = RiskLevel.Low,
                                signals = listOf(
                                    "Historical bio extracted from directly re-fetched Wayback snapshot",
                                    "Observed in historical capture dated ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                    "Historical observation only; does not prove current identity or active account ownership"
                                ),
                                providerId = "wayback-snapshot",
                                retrievedAtEpochMillis = now,
                                observedAtEpochMillis = observedTimestamp,
                                state = EvidenceState.Verified,
                                reliability = EvidenceReliability.ArchiveSnapshot,
                                contentHashSha256 = sha256(fetched.text),
                                parserVersion = PARSER_VERSION,
                                historical = true
                            )
                        }
                        meta.username?.let { username ->
                            evidence += Evidence(
                                id = "wayback:attr:${sha256("$originalUrl|${capture.timestamp}|Username|$username").take(32)}",
                                kind = EvidenceKind.Username,
                                attributeKind = HistoricalAttributeKind.Username,
                                value = username,
                                sourceUrl = snapshotUrl,
                                snippet = "Historical username extracted from Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                confidence = VERIFIED_SNAPSHOT_CONFIDENCE,
                                risk = RiskLevel.Low,
                                signals = listOf(
                                    "Historical username extracted from directly re-fetched Wayback snapshot",
                                    "Observed in historical capture dated ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                    "Historical observation only; does not prove current identity or active account ownership"
                                ),
                                providerId = "wayback-snapshot",
                                retrievedAtEpochMillis = now,
                                observedAtEpochMillis = observedTimestamp,
                                state = EvidenceState.Verified,
                                reliability = EvidenceReliability.ArchiveSnapshot,
                                contentHashSha256 = sha256(fetched.text),
                                parserVersion = PARSER_VERSION,
                                historical = true
                            )
                        }
                        meta.avatarUrl?.let { avatarUrl ->
                            evidence += Evidence(
                                id = "wayback:attr:${sha256("$originalUrl|${capture.timestamp}|AvatarUrl|$avatarUrl").take(32)}",
                                kind = EvidenceKind.PublicImageEvidence,
                                attributeKind = HistoricalAttributeKind.AvatarUrl,
                                value = avatarUrl,
                                sourceUrl = snapshotUrl,
                                snippet = "Historical avatar URL extracted from Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                confidence = VERIFIED_SNAPSHOT_CONFIDENCE,
                                risk = RiskLevel.Low,
                                signals = listOf(
                                    "Historical avatar URL extracted from directly re-fetched Wayback snapshot",
                                    "Observed in historical capture dated ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                    "Historical observation only; does not prove current identity or active account ownership"
                                ),
                                providerId = "wayback-snapshot",
                                retrievedAtEpochMillis = now,
                                observedAtEpochMillis = observedTimestamp,
                                state = EvidenceState.Verified,
                                reliability = EvidenceReliability.ArchiveSnapshot,
                                contentHashSha256 = sha256(fetched.text),
                                parserVersion = PARSER_VERSION,
                                historical = true
                            )
                        }
                        meta.organization?.let { org ->
                            evidence += Evidence(
                                id = "wayback:attr:${sha256("$originalUrl|${capture.timestamp}|Organization|$org").take(32)}",
                                kind = EvidenceKind.Organization,
                                attributeKind = HistoricalAttributeKind.Organization,
                                value = org,
                                sourceUrl = snapshotUrl,
                                snippet = "Historical organization extracted from Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                confidence = VERIFIED_SNAPSHOT_CONFIDENCE,
                                risk = RiskLevel.Low,
                                signals = listOf(
                                    "Historical organization extracted from directly re-fetched Wayback snapshot",
                                    "Observed in historical capture dated ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                    "Historical observation only; does not prove current identity or active account ownership"
                                ),
                                providerId = "wayback-snapshot",
                                retrievedAtEpochMillis = now,
                                observedAtEpochMillis = observedTimestamp,
                                state = EvidenceState.Verified,
                                reliability = EvidenceReliability.ArchiveSnapshot,
                                contentHashSha256 = sha256(fetched.text),
                                parserVersion = PARSER_VERSION,
                                historical = true
                            )
                        }
                        meta.location?.let { loc ->
                            evidence += Evidence(
                                id = "wayback:attr:${sha256("$originalUrl|${capture.timestamp}|Location|$loc").take(32)}",
                                kind = EvidenceKind.Location,
                                attributeKind = HistoricalAttributeKind.Location,
                                value = loc,
                                sourceUrl = snapshotUrl,
                                snippet = "Historical location extracted from Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                confidence = VERIFIED_SNAPSHOT_CONFIDENCE,
                                risk = RiskLevel.Low,
                                signals = listOf(
                                    "Historical location extracted from directly re-fetched Wayback snapshot",
                                    "Observed in historical capture dated ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                    "Historical observation only; does not prove current identity or active account ownership"
                                ),
                                providerId = "wayback-snapshot",
                                retrievedAtEpochMillis = now,
                                observedAtEpochMillis = observedTimestamp,
                                state = EvidenceState.Verified,
                                reliability = EvidenceReliability.ArchiveSnapshot,
                                contentHashSha256 = sha256(fetched.text),
                                parserVersion = PARSER_VERSION,
                                historical = true
                            )
                        }
                        for (link in meta.externalLinks) {
                            evidence += Evidence(
                                id = "wayback:attr:${sha256("$originalUrl|${capture.timestamp}|ExternalLink|$link").take(32)}",
                                kind = EvidenceKind.PublicSearchEvidence,
                                attributeKind = HistoricalAttributeKind.ExternalLink,
                                value = link,
                                sourceUrl = snapshotUrl,
                                snippet = "Historical external link extracted from Wayback capture ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                confidence = VERIFIED_SNAPSHOT_CONFIDENCE,
                                risk = RiskLevel.Low,
                                signals = listOf(
                                    "Historical external link extracted from directly re-fetched Wayback snapshot",
                                    "Observed in historical capture dated ${ArchivePageResolver.displayTimestamp(capture.timestamp)}",
                                    "Historical observation only; does not prove current identity or active account ownership"
                                ),
                                providerId = "wayback-snapshot",
                                retrievedAtEpochMillis = now,
                                observedAtEpochMillis = observedTimestamp,
                                state = EvidenceState.Verified,
                                reliability = EvidenceReliability.ArchiveSnapshot,
                                contentHashSha256 = sha256(fetched.text),
                                parserVersion = PARSER_VERSION,
                                historical = true
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Keep an explicit, undated unavailable record for the URL that
                // was actually requested. An empty capture list is not evidence
                // that the archive had no history when the lookup itself failed.
                evidence += unavailableEvidence(originalUrl, error)
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
        val body = fetchText(url.toString(), "application/json", failOnError = true) ?: return emptyList()
        val root = runCatching { JSON.parseToJsonElement(body) as? JsonArray }.getOrNull()
        val header = (root?.firstOrNull() as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (root == null || header == null || !REQUIRED_CDX_FIELDS.all(header::contains)) {
            throw ArchiveLookupException("Wayback CDX response was malformed")
        }
        validateCdxRows(root, header, originalUrl)
        return parseCdx(body, originalUrl)
    }

    private data class ParsedPage(
        val title: String,
        val description: String,
        val text: String,
        val metadata: ExtractedSnapshotMetadata = ExtractedSnapshotMetadata()
    )

    private fun fetchSnapshot(url: String, originalUrl: String): ParsedPage? {
        val body = fetchText(url, "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.3") ?: return null
        if (body.isBlank() || DiscoveryHttpPolicy.looksBlocked(body)) return null
        val doc = Jsoup.parse(body, url)
        doc.select("script,style,noscript,svg,template,#wm-ipp,#wm-ipp-base,.wb-autocomplete-suggestions").remove()
        val title = doc.title().trim()
        val description = doc
            .select("meta[name=description],meta[property=og:description],meta[name=twitter:description]")
            .firstOrNull()?.attr("content")?.trim().orEmpty()
        val text = doc.body()?.text()?.trim().orEmpty().take(MAX_PAGE_TEXT_CHARS)
        val metadata = ArchiveSnapshotExtractor.extract(body, url, originalUrl)
        if (title.isBlank() && description.isBlank() && text.isBlank() && metadata.isEmpty) return null
        return ParsedPage(title, description, text, metadata)
    }

    private fun fetchText(url: String, accept: String, failOnError: Boolean = false): String? {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .build()
        }.getOrElse {
            if (failOnError) throw ArchiveLookupException("Wayback request could not be created")
            return null
        }
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (failOnError) throw ArchiveLookupException("Wayback returned HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BODY_BYTES) {
                    if (failOnError) throw ArchiveLookupException("Wayback response exceeded the bounded body limit")
                    return null
                }
                body.byteStream().use { input -> readBounded(input, MAX_BODY_BYTES) }
                    ?.toString(Charsets.UTF_8)
            }
        } catch (error: Exception) {
            if (failOnError) {
                if (error is ArchiveLookupException) throw error
                throw ArchiveLookupException("Wayback request failed")
            }
            null
        }
    }

    private fun unavailableEvidence(originalUrl: String, error: Exception): Evidence = Evidence(
        id = "wayback:unavailable:${sha256(originalUrl).take(32)}",
        kind = EvidenceKind.PublicSearchEvidence,
        value = originalUrl,
        sourceUrl = originalUrl,
        snippet = "Wayback historical lookup unavailable (${error.javaClass.simpleName})",
        confidence = 0f,
        risk = RiskLevel.Low,
        signals = listOf(
            "Exact URL was explicitly supplied to this authorized audit",
            "Historical lookup failed before a capture could be verified",
            "No historical observation or timestamp is asserted"
        ),
        providerId = id,
        state = EvidenceState.Unavailable,
        reliability = EvidenceReliability.ArchiveSnapshot,
        parserVersion = PARSER_VERSION,
        historical = true
    )

    private class ArchiveLookupException(message: String) : Exception(message)

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
        private const val PARSER_VERSION = "wayback-history-v2-attributes"
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
        private val REQUIRED_CDX_FIELDS = setOf("timestamp", "original", "digest", "statuscode", "mimetype")
        private val CDX_TIMESTAMP_PATTERN = Regex("^\\d{14}$")
        private val CDX_TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT)

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

        /**
         * A valid JSON/header envelope with malformed rows is a provider
         * response failure, not an empty archive. Keep [parseCdx] permissive for
         * deterministic parser callers, but make the production lookup fail
         * closed so scan output contains an explicit Unavailable record.
         */
        private fun validateCdxRows(
            root: JsonArray,
            header: List<String>,
            expectedOriginalUrl: String
        ) {
            if (root.size <= 1) return // A header-only response is legitimate no-history.
            val index = header.withIndex().associate { it.value to it.index }
            root.drop(1).forEach { element ->
                val row = element as? JsonArray
                    ?: throw ArchiveLookupException("Wayback CDX row was not an array")
                REQUIRED_CDX_FIELDS.forEach { field ->
                    val value = (row.getOrNull(index.getValue(field)) as? JsonPrimitive)
                        ?.contentOrNull
                        ?.trim()
                    if (value.isNullOrBlank()) {
                        throw ArchiveLookupException("Wayback CDX row was missing $field")
                    }
                }
                val timestamp = (row.getOrNull(index.getValue("timestamp")) as JsonPrimitive)
                    .contentOrNull
                    .orEmpty()
                if (!CDX_TIMESTAMP_PATTERN.matches(timestamp)) {
                    throw ArchiveLookupException("Wayback CDX row had an invalid timestamp")
                }
                if (timestampMillis(timestamp) == null) {
                    throw ArchiveLookupException("Wayback CDX row had an impossible calendar timestamp")
                }
                val original = (row.getOrNull(index.getValue("original")) as JsonPrimitive)
                    .contentOrNull
                    .orEmpty()
                    .trim()
                if (normalizeComparableUrl(original) != normalizeComparableUrl(expectedOriginalUrl)) {
                    throw ArchiveLookupException("Wayback CDX row did not match the requested URL")
                }
                val status = (row.getOrNull(index.getValue("statuscode")) as JsonPrimitive)
                    .contentOrNull
                    .orEmpty()
                    .trim()
                if (status != "200") {
                    throw ArchiveLookupException("Wayback CDX row had unexpected HTTP status")
                }
                val mime = (row.getOrNull(index.getValue("mimetype")) as JsonPrimitive)
                    .contentOrNull
                    .orEmpty()
                    .trim()
                if (!mime.equals("text/html", ignoreCase = true)) {
                    throw ArchiveLookupException("Wayback CDX row had unexpected media type")
                }
            }
        }

        internal fun snapshotUrl(capture: Capture): String =
            "https://web.archive.org/web/${capture.timestamp}id_/${capture.originalUrl}"

        internal fun timestampMillis(timestamp: String): Long? = runCatching {
            LocalDateTime.parse(timestamp, CDX_TIMESTAMP_FORMATTER)
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
            .dns(DiscoveryHttpPolicy.PUBLIC_DNS)
            .addNetworkInterceptor(DiscoveryHttpPolicy.PUBLIC_URL_INTERCEPTOR)
            .build()
    }
}
