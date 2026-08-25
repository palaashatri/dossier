package io.dossier.app.domain.place

import android.content.Context
import android.net.Uri
import io.dossier.app.data.image.VisualFingerprint
import io.dossier.app.data.web.DiscoveryHttpPolicy
import io.dossier.app.data.web.ReverseImageCandidateSearchService
import io.dossier.app.domain.image.ImageDuplicateClusterer
import io.dossier.app.domain.model.ReverseImageLookupResult
import io.dossier.app.domain.model.ProfileScanResult
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Performs real on-device near-duplicate/repost matching.
 *
 * The user's image is never uploaded. Dossier gathers a bounded candidate corpus from
 * public image indexes and already-discovered profile avatars, downloads those public
 * images, and compares whole-image perceptual fingerprints locally. This can identify
 * copies, resizes, recompressions, screenshots, and modest crops. It intentionally does
 * not identify the same person across unrelated photos.
 */
internal class ReverseImageVisualMatcher(private val context: Context) {

    data class Outcome(
        val matches: List<ReverseImageLookupResult.VisualMatch>,
        val note: String,
        val candidateCount: Int,
        val candidates: List<ReverseImageLookupResult.ImageCandidateProvenance> = emptyList(),
        val clusters: List<ReverseImageLookupResult.ImageCluster> = emptyList()
    )

    private data class DownloadedImage(
        val bytes: ByteArray,
        val url: String,
        val retrievedAtEpochMillis: Long
    )

    private data class CandidateAnalysis(
        val provenance: ReverseImageLookupResult.ImageCandidateProvenance,
        val fingerprint: VisualFingerprint.FingerprintSet?,
        val match: ReverseImageLookupResult.VisualMatch?
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

        val profileCandidatesWithLinkage = ScanSession.profileScanResults.value
            .asSequence()
            .filter { it.exists && it.profileImageUrl?.startsWith("http", true) == true }
            .map { result ->
                val candidate = ReverseImageCandidateSearchService.Candidate(
                    title = result.displayName ?: result.candidate.username,
                    imageUrl = result.profileImageUrl!!,
                    thumbnailUrl = result.profileImageUrl,
                    sourcePageUrl = result.candidate.url,
                    query = "Previously discovered profile avatar",
                    source = "Dossier profile discovery"
                )
                candidate to verifiedProfileMediaLinkage(result)
            }
            .toList()
        val profileCandidates = profileCandidatesWithLinkage.map { it.first }
        val verifiedProfileLinkagesBySourcePage = profileCandidatesWithLinkage
            .mapNotNull { (candidate, linkage) ->
                linkage?.let { canonical(candidate.sourcePageUrl) to it }
            }
            .toMap()

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
        val analyses = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        compareCandidate(
                            query = queryFingerprint,
                            candidate = candidate,
                            accountLinkage = verifiedProfileLinkagesBySourcePage[canonical(candidate.sourcePageUrl)]
                        )
                    }
                }
            }.awaitAll()
        }

        val clusterInputs = analyses.mapNotNull { analysis ->
            val fingerprint = analysis.fingerprint ?: return@mapNotNull null
            val primary = fingerprint.variants.firstOrNull() ?: return@mapNotNull null
            ImageDuplicateClusterer.Candidate(
                id = analysis.provenance.id,
                sha256 = fingerprint.sha256,
                perceptualHash = primary.perceptualHash,
                querySimilarity = analysis.provenance.comparisonScore ?: 0f
            )
        }
        val duplicateClusters = ImageDuplicateClusterer.cluster(clusterInputs)
        val clusterByCandidate = buildMap {
            duplicateClusters.forEach { cluster ->
                cluster.memberCandidateIds.forEach { candidateId -> put(candidateId, cluster.id) }
            }
        }
        val provenance = analyses.map { analysis ->
            analysis.provenance.copy(clusterId = clusterByCandidate[analysis.provenance.id])
        }
        val matches = analyses
            .mapNotNull(CandidateAnalysis::match)
            .map { match -> match.copy(clusterId = match.candidateId?.let(clusterByCandidate::get)) }
            .distinctBy { "${canonical(it.imageUrl)}|${canonical(it.sourcePageUrl)}" }
            .sortedByDescending { it.similarity }
            .take(MAX_MATCHES)
        val clusters = duplicateClusters.map { cluster ->
            ReverseImageLookupResult.ImageCluster(
                id = cluster.id,
                type = when (cluster.type) {
                    ImageDuplicateClusterer.ClusterType.ExactContent ->
                        ReverseImageLookupResult.ImageClusterType.ExactContent
                    ImageDuplicateClusterer.ClusterType.PerceptualNearDuplicate ->
                        ReverseImageLookupResult.ImageClusterType.PerceptualNearDuplicate
                },
                representativeCandidateId = cluster.representativeCandidateId,
                memberCandidateIds = cluster.memberCandidateIds
            )
        }

        val note = when {
            matches.isNotEmpty() -> buildString {
                append("Compared ${candidates.size} public images locally using SHA-256, pHash, dHash, aHash, color histograms, and crop variants.")
                if (clusters.isNotEmpty()) append(" Grouped ${clusters.size} public duplicate/repost cluster(s).")
                append(" No facial identification was performed.")
            }
            else ->
                "Compared ${candidates.size} public images locally; no candidate crossed the ${(MIN_MATCH_SCORE * 100).toInt()}% near-duplicate threshold. This does not prove that no copy exists outside the candidate indexes."
        }

        Outcome(
            matches = matches,
            note = note,
            candidateCount = candidates.size,
            candidates = provenance,
            clusters = clusters
        )
    }

    private suspend fun compareCandidate(
        query: VisualFingerprint.FingerprintSet,
        candidate: ReverseImageCandidateSearchService.Candidate,
        accountLinkage: ReverseImageLookupResult.ImageAccountLinkage? = null
    ): CandidateAnalysis {
        val candidateId = stableCandidateId(candidate)
        val base = ReverseImageLookupResult.ImageCandidateProvenance(
            id = candidateId,
            title = candidate.title.ifBlank { "Public image candidate" },
            imageUrl = candidate.imageUrl,
            sourcePageUrl = candidate.sourcePageUrl,
            source = candidate.source,
            acquisitionQuery = candidate.query,
            accountLinkages = listOfNotNull(accountLinkage),
            state = ReverseImageLookupResult.ImageCandidateState.Indexed
        )

        val preferredUrl = candidate.thumbnailUrl?.takeIf { it.startsWith("http", true) }
            ?: candidate.imageUrl
        val firstDownload = download(preferredUrl)
            ?: candidate.imageUrl
                .takeIf { !it.equals(preferredUrl, ignoreCase = true) }
                ?.let { download(it) }
            ?: return CandidateAnalysis(
                provenance = base.copy(
                    comparedImageUrl = preferredUrl,
                    state = ReverseImageLookupResult.ImageCandidateState.DownloadUnavailable
                ),
                fingerprint = null,
                match = null
            )

        val firstFingerprint = VisualFingerprint.fromBytes(firstDownload.bytes)
            ?: return CandidateAnalysis(
                provenance = base.copy(
                    comparedImageUrl = firstDownload.url,
                    retrievedAtEpochMillis = firstDownload.retrievedAtEpochMillis,
                    state = ReverseImageLookupResult.ImageCandidateState.DecodeFailed
                ),
                fingerprint = null,
                match = null
            )

        var bestFingerprint = firstFingerprint
        var best = VisualFingerprint.compare(query, firstFingerprint)
        var comparedUrl = firstDownload.url
        var retrievedAt = firstDownload.retrievedAtEpochMillis

        if (!best.exactBytes && best.score >= FULL_IMAGE_RETRY_FLOOR &&
            !candidate.imageUrl.equals(comparedUrl, ignoreCase = true)) {
            download(candidate.imageUrl)?.let { fullDownload ->
                VisualFingerprint.fromBytes(fullDownload.bytes)?.let { fullFingerprint ->
                    val full = VisualFingerprint.compare(query, fullFingerprint)
                    if (full.score > best.score) {
                        best = full
                        bestFingerprint = fullFingerprint
                        comparedUrl = fullDownload.url
                        retrievedAt = fullDownload.retrievedAtEpochMillis
                    }
                }
            }
        }

        val primary = bestFingerprint.variants.firstOrNull()
        val matched = best.score >= MIN_MATCH_SCORE
        val provenance = base.copy(
            comparedImageUrl = comparedUrl,
            retrievedAtEpochMillis = retrievedAt,
            contentSha256 = bestFingerprint.sha256,
            width = bestFingerprint.width,
            height = bestFingerprint.height,
            averageHashHex = primary?.averageHash?.unsignedHex(),
            differenceHashHex = primary?.differenceHash?.unsignedHex(),
            perceptualHashHex = primary?.perceptualHash?.unsignedHex(),
            comparisonScore = best.score,
            exactBytes = best.exactBytes,
            state = if (matched) {
                ReverseImageLookupResult.ImageCandidateState.Matched
            } else {
                ReverseImageLookupResult.ImageCandidateState.ComparedNoMatch
            }
        )

        val visualMatch = if (!matched) {
            null
        } else {
            ReverseImageLookupResult.VisualMatch(
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
                },
                candidateId = candidateId
            )
        }
        return CandidateAnalysis(provenance, bestFingerprint, visualMatch)
    }

    private suspend fun download(url: String): DownloadedImage? {
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
                        val bytes = response.body?.byteStream()?.use {
                            readLimited(it, MAX_CANDIDATE_BYTES)
                        } ?: return null
                        return DownloadedImage(
                            bytes = bytes,
                            url = response.request.url.toString(),
                            retrievedAtEpochMillis = System.currentTimeMillis()
                        )
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

    private fun stableCandidateId(candidate: ReverseImageCandidateSearchService.Candidate): String {
        val canonicalValue = listOf(
            canonical(candidate.imageUrl),
            canonical(candidate.sourcePageUrl),
            candidate.source.trim().lowercase()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalValue.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "imgcandidate:${digest.take(20)}"
    }

    private fun Long.unsignedHex(): String =
        java.lang.Long.toUnsignedString(this, 16).padStart(16, '0')

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
        const val USER_AGENT = "Dossier/0.1 authorized-public-image-audit"
    }
}

/**
 * Converts only a directly verified public profile into explicit media linkage
 * provenance. Candidate image similarity, clusters, usernames, and guessed URLs
 * are intentionally not accepted as account-linkage evidence.
 */
internal fun verifiedProfileMediaLinkage(
    result: ProfileScanResult
): ReverseImageLookupResult.ImageAccountLinkage? {
    if (!result.exists || !result.verified) return null
    val accountUrl = result.candidate.url.trim()
    if (accountUrl.length > MAX_VERIFIED_PROFILE_URL_CHARS) return null
    val uri = runCatching { URI(accountUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null) return null
    return ReverseImageLookupResult.ImageAccountLinkage(
        accountUrl = accountUrl,
        basis = ReverseImageLookupResult.ImageAccountLinkageBasis.VerifiedProfile,
        evidenceIds = listOf("profile:$accountUrl")
    )
}

private const val MAX_VERIFIED_PROFILE_URL_CHARS = 2_048
