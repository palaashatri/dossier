package io.dossier.app

import io.dossier.app.domain.face.FaceConsistencyChecker
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import io.dossier.app.domain.scanner.ScanSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceConsistencyCheckerTest {

    @Test
    fun selectProfileImageCandidates_prefersVerifiedAndCapsCount() {
        val results = (1..15).map { index ->
            profileResult(
                url = "https://example.com/u$index",
                imageUrl = "https://cdn.example.com/a$index.jpg",
                verified = index >= 10,
                confidence = index / 15f
            )
        }
        val selected = FaceConsistencyChecker.selectProfileImageCandidates(
            profileResults = results,
            maxImages = 5
        )

        assertEquals(5, selected.size)
        assertTrue(selected.all { it.second.startsWith("https://cdn.example.com/") })
        // Highest confidence verified rows should win the capped set.
        assertTrue(selected.any { it.first.endsWith("/u15") })
        assertTrue(selected.none { it.first.endsWith("/u1") })
        assertTrue(selected.all { pair ->
            results.first { it.candidate.url == pair.first }.verified
        })
    }

    @Test
    fun faceFindingsFromMatches_onlyElevatesCalibratedReviewOrHigh() {
        val findings = ScanSession.faceFindingsFromMatches(
            listOf(
                FaceConsistencyMatch(
                    profileUrl = "https://github.com/a",
                    similarityScore = 0.92f,
                    warning = "Calibrated face model reports a high visual similarity score. Confirm account ownership manually."
                ),
                FaceConsistencyMatch(
                    profileUrl = "https://github.com/b",
                    similarityScore = 0.55f,
                    warning = "Calibrated face model reports a review-range similarity score. Treat as supporting evidence only."
                ),
                FaceConsistencyMatch(
                    profileUrl = "https://github.com/c",
                    similarityScore = 0.41f,
                    warning = "Calibrated face model reports a low similarity score."
                ),
                FaceConsistencyMatch(
                    profileUrl = "https://github.com/d",
                    similarityScore = 0.80f,
                    warning = "Imported face model produced a cosine score, but no calibrated threshold file is imported."
                )
            )
        )

        assertEquals(2, findings.size)
        assertTrue(findings.all { it.type == FindingType.ImageConsistency })
        assertEquals(RiskLevel.High, findings.first { it.sourceUrl!!.endsWith("/a") }.risk)
        assertEquals(RiskLevel.Medium, findings.first { it.sourceUrl!!.endsWith("/b") }.risk)
    }

    private fun profileResult(
        url: String,
        imageUrl: String,
        verified: Boolean,
        confidence: Float
    ): ProfileScanResult =
        ProfileScanResult(
            candidate = UsernameCandidate(
                username = url.substringAfterLast('/'),
                platform = Platform.GitHub,
                url = url,
                matchType = UsernameMatchType.Exact,
                confidence = confidence
            ),
            exists = true,
            httpStatus = 200,
            displayName = null,
            bio = null,
            profileImageUrl = imageUrl,
            links = emptyList(),
            extractedText = "",
            findings = emptyList(),
            confidenceSignals = emptyList(),
            verified = verified
        )
}
