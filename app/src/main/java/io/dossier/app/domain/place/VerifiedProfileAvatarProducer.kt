package io.dossier.app.domain.place

import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.ReverseImageLookupResult
import java.net.URI
import java.security.MessageDigest

/**
 * Converts directly verified profile observations into bounded media candidates.
 *
 * This producer carries only URLs that the profile scanner already observed: the
 * public avatar URL and the exact verified profile URL. It does not synthesize a
 * URL from a username, infer an account from an image score, or create a visual
 * match. Candidates remain Indexed until a later local comparison supplies hashes.
 */
internal object VerifiedProfileAvatarProducer {

    fun produce(
        results: List<ProfileScanResult>
    ): List<ReverseImageLookupResult.ImageCandidateProvenance> = results
        .asSequence()
        .filter { it.exists && it.verified }
        .mapNotNull { result ->
            val linkage = verifiedProfileMediaLinkage(result) ?: return@mapNotNull null
            val imageUrl = result.profileImageUrl
                ?.trim()
                ?.takeIf(::isPublicHttpUrl)
                ?: return@mapNotNull null
            val accountUrl = linkage.accountUrl
            ReverseImageLookupResult.ImageCandidateProvenance(
                id = stableCandidateId(accountUrl, imageUrl),
                title = (result.displayName?.takeIf(String::isNotBlank)
                    ?: result.candidate.username.takeIf(String::isNotBlank)
                    ?: "Verified profile avatar").take(MAX_TITLE_CHARS),
                imageUrl = imageUrl,
                sourcePageUrl = accountUrl,
                source = SOURCE,
                acquisitionQuery = QUERY,
                state = ReverseImageLookupResult.ImageCandidateState.Indexed,
                accountLinkages = listOf(linkage)
            )
        }
        .distinctBy { candidate ->
            "${canonical(candidate.imageUrl)}|${canonical(candidate.sourcePageUrl)}"
        }
        .take(MAX_CANDIDATES)
        .toList()

    private fun stableCandidateId(accountUrl: String, imageUrl: String): String {
        // Match ReverseImageVisualMatcher's candidate identity so an automatic
        // observation and a later local comparison coalesce on the same node.
        val canonicalValue = listOf(
            canonical(imageUrl),
            canonical(accountUrl),
            SOURCE.lowercase()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalValue.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "imgcandidate:${digest.take(20)}"
    }

    private fun isPublicHttpUrl(raw: String): Boolean {
        if (raw.length > MAX_URL_CHARS) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") &&
            uri.host?.isNotBlank() == true &&
            uri.userInfo == null
    }

    private fun canonical(raw: String): String = runCatching {
        val uri = URI(raw.trim())
        URI(
            uri.scheme?.lowercase(),
            null,
            uri.host?.lowercase(),
            uri.port,
            uri.path,
            uri.query,
            null
        ).toString().removeSuffix("/")
    }.getOrDefault(raw.trim().substringBefore('#').removeSuffix("/").lowercase())

    private const val MAX_CANDIDATES = 64
    private const val MAX_TITLE_CHARS = 160
    private const val MAX_URL_CHARS = 2_048
    private const val SOURCE = "Dossier profile discovery"
    private const val QUERY = "Previously discovered profile avatar"
}
