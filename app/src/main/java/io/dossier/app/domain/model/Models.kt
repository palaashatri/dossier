package io.dossier.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class IdentityInput(
    val fullName: String,
    val aliases: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val organizations: List<String> = emptyList(),
    val usernames: List<String> = emptyList(),
    val primaryUsername: String? = null,
    val profileUrls: List<String> = emptyList(),
    val selfieUri: String? = null // Local URI (file://)
)

@Serializable
data class Finding(
    val type: FindingType,
    val value: String,
    val sourceUrl: String?,
    val evidenceSnippet: String?,
    val confidence: Float,
    val risk: RiskLevel,
    val remediation: String
)

enum class FindingType {
    Email,
    Phone,
    Address,
    Location,
    Username,
    Profile,
    Organization,
    UsernameReuse,
    PlausibleProfileMatch,
    ImageConsistency,
    SensitiveSnippet
}

enum class RiskLevel { Low, Medium, High, Critical }

@Serializable
data class UsernameCandidate(
    val username: String,
    val platform: Platform,
    val url: String,
    val matchType: UsernameMatchType,
    val confidence: Float
)

enum class UsernameMatchType {
    Exact,
    DotVariant,
    UnderscoreVariant,
    HyphenVariant,
    CaseVariant,
    FuzzyVariant
}

@Serializable
data class ProfileScanResult(
    val candidate: UsernameCandidate,
    val exists: Boolean,
    val httpStatus: Int?,
    val displayName: String?,
    val bio: String?,
    val links: List<String>,
    val extractedText: String,
    val findings: List<Finding>,
    val confidenceSignals: List<String>,
    // True only when existence was confirmed against the rendered DOM in the
    // embedded browser — never set by OkHttp HTML sniffing alone.
    val verified: Boolean = false,
    // Human-readable explanation of how existence was decided, e.g.
    // "✓ Verified in-browser", "HTTP 404 — not found",
    // "Unverifiable — challenge page", "Offline".
    val verificationStatus: String? = null,
    // For pivot-discovered profiles: which confirmed profile surfaced this one
    // (e.g. "discovered via GitHub profile"). Null for directly-sourced candidates.
    val provenance: String? = null
)

@Serializable
data class FaceConsistencyMatch(
    val profileUrl: String,
    val similarityScore: Float,
    val warning: String = "Profile image appears visually similar — confirm account ownership"
)

enum class Platform {
    X,
    Reddit,
    GitHub,
    StackOverflow,
    Instagram,
    Facebook,
    TikTok,
    YouTube,
    Medium,
    DevTo,
    LinkedIn,
    Pinterest,
    Telegram,
    Bluesky,
    Mastodon,
    Twitch,
    GitLab,
    HackerNews,
    Threads,
    Snapchat,
    Discord,
    Website
}

data class PlatformProfileTemplate(
    val platform: Platform,
    val urlPattern: String, // e.g., "https://github.com/{username}"
    val requiresLoginUsually: Boolean,
    val shouldFetchByDefault: Boolean
)

@Serializable
data class PlaceScanResult(
    val gps: String?,
    val locationQuery: String?,
    val faceSkipped: Boolean,
    val faceWarning: String?,
    val extractedText: String? = null,
    val detectedLandmarks: List<String> = emptyList()
)

/**
 * Result of a Reverse Image Lookup — estimates *where* an image was likely taken
 * using EXIF GPS, on-device vision (OCR + scene labels), and public-web search
 * of the extracted text/landmark clues.
 *
 * IMPORTANT (AGENTS.md): this is location-only. Faces trigger the safety gate —
 * identity search is skipped, but location lookup continues. Image bytes never
 * leave the device; only text/label *clues* are searched on the public web.
 */
@Serializable
data class ReverseImageLookupResult(
    val gps: String?,
    val extractedText: String?,
    val labels: List<ImageLabel>,
    val faceDetected: Boolean,
    val faceWarning: String?,
    val resolvedLocation: String?,
    val mapsUrl: String?,
    val webEvidence: List<WebEvidence>
) {
    @Serializable
    data class ImageLabel(val text: String, val confidence: Float)

    @Serializable
    data class WebEvidence(val title: String, val snippet: String, val url: String)
}
