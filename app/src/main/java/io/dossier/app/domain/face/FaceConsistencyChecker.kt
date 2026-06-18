package io.dossier.app.domain.face

import android.net.Uri
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.IdentityInput

class FaceConsistencyChecker(private val service: FaceEmbeddingService) {
    suspend fun checkSelfieVsProfiles(
        identity: IdentityInput,
        profileImages: Map<String, Uri> // url -> user-supplied image URI
    ): List<FaceConsistencyMatch> {
        if (identity.selfieUri == null) return emptyList()
        val selfieUri = Uri.parse(identity.selfieUri)
        
        val matches = mutableListOf<FaceConsistencyMatch>()
        for ((url, uri) in profileImages) {
            try {
                val match = service.extractAndCompare(selfieUri, uri)
                if (match.similarityScore > 0.6f) {
                    matches.add(match)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return matches
    }
}
