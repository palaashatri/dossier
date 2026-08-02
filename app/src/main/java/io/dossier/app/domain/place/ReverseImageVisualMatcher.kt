package io.dossier.app.domain.place

import android.content.Context
import android.net.Uri
import io.dossier.app.data.image.VisualFingerprint
import io.dossier.app.data.web.DiscoveryHttpPolicy
import io.dossier.app.data.web.ReverseImageCandidateSearchService
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.scanner.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Performs real on-device near-duplicate/repost matching.
 *
 * The user's image is never uploaded. Dossier gathers a bounded candidate corpus from
 * public image indexes and already-discovered profile avatars, downloads those public
 * images, and compares perceptual fingerprints locally. This can identify copies,
 * resizes, recompressions, screenshots, and modest crops. It intentionally cannot and
 * must not identify the same person across unrelated photos.
 */
internal class ReverseImageVisualMatcher(private val context: Context) {

    data class Outcome(
        val matches: List<ReverseImageLookupResult.VisualMatch>,
        val note: String,
        val candidateCount: Int
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun match(
        queryUri: Uri,
        extractedText: String?,
        labels: List<String>,
        deepResearch: Boolean
    ): Outcome = withContext(Dispatchers.IO) {
        val queryBytes = context.contentResolver.openInputStream(queryUri)?.use {
            readLimited(it, MAX_QUERY_BYTES)
        } ?: return@withContext Outcome(emptyList(), "Could not read the selected image", 0)

        val queryFingerprint = VisualFingerprint.fromBytes(queryBytes)
            ?: return@withContext Outcome(emptyList(), "The selected file could not be decoded as an image", 0)

        val identity = ScanSession.currentInput.value
        val indexedCandidates = ReverseImageCandidateSearchService(context).search(
            extractedText = extractedText,
            labels = labels,
            identity = identity,
            deepResearch = deepResearch
        )

        val profileCandidates = ScanSession.profileScanResults.value
            .asSequence()
            .filter { it.exists && it.profileImageUrl?.startsWith("http", true) == true }
            .map { result ->
                ReverseImageCandidateSearchService.Candidate(
                    title = result.displayName ?: result.candidate.username,
                    imageUrl = result.profileImageUrl!!,
                    thumbnailUrl = result.profileImageUrl,
                    sourcePageUrl = result.candidate.url,
                    query = "Previously discovered profile avatar",
                    source = "Dossier profile discovery"
                )
            }
            .toList()

        val candidates = (profileCandidates + indexedCandidates)
            .distinctBy { canonical(it.imageUrl) }
            .take(if (deepResearch) MAX_DEEP_CANDIDATES else MAX_DEFAULT_CANDIDATES)

        if (candidates.isEmpty()) {
            return@withContext Outcome(
                matches = emptyList(),
                note = "No public candidate images were available for local visual comparison. Add identity details or visible-text clues and try Deep Research.",
                candidateCount = 0
            )
        }

        val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
        val matches = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        compareCandidate(queryFingerprint, candidate)
                    }
                }
            }.awaitAll().filterNotNull()
        }
            .distinctBy { "${canonical(it.imageUrl)}|${canonical(it.sourcePageUrl)}" }
            .sortedByDescending { it.similarity }
            .take(MAX_MATCHES)

        val note = when {
            matches.isNotEmpty() ->
                "Compared ${candidates.size} public images locally using SHA-256, pHash, dHash, aHash, color histograms, and crop variants. No facial identification was performed."
            else ->
                "Compared ${candidates.size} public images locally; no candidate crossed the ${(MIN_MATCH_SCORE * 100).toInt()}% near-duplicate threshold. This does not prove that no copy exists outside the candidate indexes."
        }

        Outcome(matches, note, candidates.size)
    }

    private suspend fun compareCandidate(
        query: VisualFingerprint.FingerprintSet,
        candidate: ReverseImageCandidateSearchService.Candidate
    ): ReverseImageLookupResult.VisualMatch? {
        val firstUrl = candidate.thumbnailUrl?.takeIf { it.startsWith("http", true) }
            ?: candidate.imageUrl
        val firstBytes = download(firstUrl) ?: return null
        val firstFingerprint = VisualFingerprint.fromBytes(firstBytes) ?: return null
        var best = VisualFingerprint.compare(query, firstFingerprint)
        var comparedUrl = firstUrl

        if (!best.exactBytes && best.score >= FULL_IMAGE_RETRY_FLOOR &&
            !candidate.imageUrl.equals(firstUrl, ignoreCase = true)) {
            download(candidate.imageUrl)?.let { fullBytes ->
                VisualFingerprint.fromBytes(fullBytes)?.let { fullFingerprint ->
                    val full = VisualFingerprint.compare(query, fullFingerprint)
                    if (full.score > best.score) {
                        best = full
                        comparedUrl = candidate.imageUrl
                    }
                }
            }
        }

        if (best.score < MIN_MATCH_SCORE) return null

        return ReverseImageLookupResult.VisualMatch(
            title = candidate.title.ifBlank { "Visual match" },
            imageUrl = candidate.imageUrl,
            sourcePageUrl = candidate.sourcePageUrl,
            source = candidate.source,
            similarity = best.score,
            matchType = VisualFingerprint.classify(best.score, best.exactBytes),
            evidence = buildString {
                append("Whole-image near-duplicate comparison")
                if (comparedUrl != candidate.imageUrl) append(" using indexed thumbnail")
                append(": pHash ").append(percent(best.perceptual))
                append(", dHash ").append(percent(best.difference))
                append(", aHash ").append(percent(best.average))
                append(", color ").append(percent(best.color))
                if (best.exactBytes) append(", exact SHA-256 match")
            }
        )
    }

    private suspend fun download(url: String): ByteArray? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null

        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val length = response.body?.contentLength() ?: -1L
                        if (length > MAX_CANDIDATE_BYTES) return null
                        val contentType = response.body?.contentType()?.toString().orEmpty()
                        if (contentType.isNotBlank() && !contentType.startsWith("image/", true)) return null
                        return response.body?.byteStream()?.use {
                            readLimited(it, MAX_CANDIDATE_BYTES)
                        }
                    }
                    if (DiscoveryHttpPolicy.isTransientHttpStatus(response.code) &&
                        attempt < MAX_DOWNLOAD_ATTEMPTS - 1) {
                        delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, response.header("Retry-After")))
                    } else {
                        return null
                    }
                }
            } catch (_: Exception) {
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) {
                    delay(DiscoveryHttpPolicy.retryDelayMillis(attempt, null))
                }
            }
        }
        return null
    }

    private fun readLimited(stream: InputStream, maximum: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximum) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun canonical(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path, uri.query, null)
            .toString().removeSuffix("/")
    }.getOrDefault(url.trim().substringBefore('#').removeSuffix("/").lowercase())

    private fun percent(value: Float): String = "${(value * 100).toInt()}%"

    private companion object {
        const val MAX_QUERY_BYTES = 24L * 1024L * 1024L
        const val MAX_CANDIDATE_BYTES = 8L * 1024L * 1024L
        const val MAX_DEFAULT_CANDIDATES = 48
        const val MAX_DEEP_CANDIDATES = 84
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val MAX_DOWNLOAD_ATTEMPTS = 2
        const val MAX_MATCHES = 12
        const val MIN_MATCH_SCORE = 0.80f
        const val FULL_IMAGE_RETRY_FLOOR = 0.70f
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
