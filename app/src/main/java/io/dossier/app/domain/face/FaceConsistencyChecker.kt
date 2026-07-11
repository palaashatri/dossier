package io.dossier.app.domain.face

import android.net.Uri
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult

/**
 * Compares a user-provided selfie against profile avatar images using the
 * imported face embedding model. Only calibrated review/high scores should be
 * treated as identity evidence; uncalibrated scores are still returned with an
 * honest warning for manual review.
 */
class FaceConsistencyChecker(private val service: FaceEmbeddingService) {

    suspend fun checkSelfieVsProfiles(
        identity: IdentityInput,
        profileImages: Map<String, Uri>
    ): List<FaceConsistencyMatch> {
        if (identity.selfieUri.isNullOrBlank() || profileImages.isEmpty()) return emptyList()
        val selfieUri = Uri.parse(identity.selfieUri)

        val matches = mutableListOf<FaceConsistencyMatch>()
        for ((profileUrl, imageUri) in profileImages) {
            try {
                val match = service.compareFaces(
                    selfieUri = selfieUri,
                    profileUri = imageUri,
                    profileUrl = profileUrl
                )
                if (shouldSurface(match)) {
                    matches.add(match)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return matches.sortedByDescending { it.similarityScore }
    }

    private fun shouldSurface(match: FaceConsistencyMatch): Boolean {
        if (match.similarityScore > 0f) return true
        val warning = match.warning.lowercase()
        // Surface hard failures that explain why scoring could not run for a profile.
        return warning.contains("no face detected") ||
            warning.contains("could not run") ||
            warning.contains("no face embedding model")
    }

    companion object {
        const val MAX_PROFILE_IMAGES = 12

        /**
         * Pure selection of profile URL → remote image URL pairs, ordered by
         * verified status then confidence and capped at [maxImages].
         */
        fun selectProfileImageCandidates(
            profileResults: List<ProfileScanResult>,
            maxImages: Int = MAX_PROFILE_IMAGES
        ): List<Pair<String, String>> =
            profileResults
                .asSequence()
                .filter { it.exists }
                .mapNotNull { result ->
                    val imageUrl = result.profileImageUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Triple(result.candidate.url, imageUrl, result)
                }
                .sortedWith(
                    compareByDescending<Triple<String, String, ProfileScanResult>> { it.third.verified }
                        .thenByDescending { it.third.candidate.confidence }
                )
                .take(maxImages)
                .map { it.first to it.second }
                .toList()

        /**
         * Builds the profile-url → local-image-uri map from scan results that already
         * expose a profile image URL. [download] maps remote avatar URLs to local
         * cache URIs (or returns null when download fails).
         */
        fun buildProfileImageMap(
            profileResults: List<ProfileScanResult>,
            download: (imageUrl: String) -> Uri?,
            maxImages: Int = MAX_PROFILE_IMAGES
        ): Map<String, Uri> {
            val images = linkedMapOf<String, Uri>()
            for ((profileUrl, imageUrl) in selectProfileImageCandidates(profileResults, maxImages)) {
                val localUri = download(imageUrl) ?: continue
                images[profileUrl] = localUri
            }
            return images
        }
    }
}
