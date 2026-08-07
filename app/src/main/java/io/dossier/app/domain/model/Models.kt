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
    val selfieUri: String? = null
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
    PublicSearchEvidence,
    PublicImageEvidence,
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
    val profileImageUrl: String? = null,
    val links: List<String>,
    val extractedText: String,
    val findings: List<Finding>,
    val confidenceSignals: List<String>,
    val verified: Boolean = false,
    val verificationStatus: String? = null,
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
    val urlPattern: String,
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

@Serializable
data class ReverseImageLookupResult(
    val gps: String?,
    val extractedText: String?,
    val labels: List<ImageLabel>,
    val faceDetected: Boolean,
    val faceWarning: String?,
    val resolvedLocation: String?,
    val mapsUrl: String?,
    val webEvidence: List<WebEvidence>,
    val visualMatches: List<VisualMatch> = emptyList(),
    val visualSearchNote: String? = null
) {
    @Serializable
    data class ImageLabel(val text: String, val confidence: Float)

    @Serializable
    data class WebEvidence(val title: String, val snippet: String, val url: String)

    @Serializable
    data class VisualMatch(
        val title: String,
        val imageUrl: String,
        val sourcePageUrl: String,
        val source: String,
        val similarity: Float,
        val matchType: String,
        val evidence: String
    )
}

@Serializable
data class ReverseVideoLookupResult(
    val durationMs: Long?,
    val sampledFrames: Int,
    val extractedText: String?,
    val labels: List<ReverseImageLookupResult.ImageLabel>,
    val faceDetected: Boolean,
    val faceWarning: String?,
    val resolvedLocation: String?,
    val mapsUrl: String?,
    val webEvidence: List<ReverseImageLookupResult.WebEvidence>,
    val frameSummaries: List<FrameEvidence> = emptyList()
) {
    @Serializable
    data class FrameEvidence(
        val timestampMs: Long,
        val extractedText: String?,
        val labels: List<ReverseImageLookupResult.ImageLabel>,
        val faceDetected: Boolean
    )
}

// ---- Identity graph v2 migration -------------------------------------------

/** Stable legacy storage/rendering type. Do not expand destructively. */
@Serializable
enum class EntityType {
    Person,
    Username,
    Email,
    Phone,
    Profile,
    Organization,
    Location,
    Image,
    Breach,
    Website
}

/** Full semantic node taxonomy required by the v2 graph. */
@Serializable
enum class GraphEntityKind {
    Subject,
    Account,
    Username,
    DisplayName,
    Email,
    Phone,
    Domain,
    URL,
    Image,
    Organization,
    Location,
    Occupation,
    Document,
    ArchiveSnapshot,
    Breach,
    Website,
    EvidenceArtifact
}

fun EntityType.toGraphEntityKind(): GraphEntityKind = when (this) {
    EntityType.Person -> GraphEntityKind.Subject
    EntityType.Username -> GraphEntityKind.Username
    EntityType.Email -> GraphEntityKind.Email
    EntityType.Phone -> GraphEntityKind.Phone
    EntityType.Profile -> GraphEntityKind.Account
    EntityType.Organization -> GraphEntityKind.Organization
    EntityType.Location -> GraphEntityKind.Location
    EntityType.Image -> GraphEntityKind.Image
    EntityType.Breach -> GraphEntityKind.Breach
    EntityType.Website -> GraphEntityKind.Website
}

@Serializable
enum class GraphNodeState {
    Confirmed,
    High,
    Medium,
    Low,
    Unresolved,
    Conflicting
}

@Serializable
enum class RelationshipType {
    HAS_USERNAME,
    HAS_EMAIL,
    HAS_PHONE,
    USES_ACCOUNT,
    USES_AVATAR,
    LINKS_TO,
    MENTIONS,
    OWNS_DOMAIN,
    AFFILIATED_WITH,
    LOCATED_IN,
    APPEARED_IN_BREACH,
    ARCHIVED_AS,
    SAME_IMAGE_AS,
    SIMILAR_IMAGE_TO,
    VISUALLY_SIMILAR_TO,
    REDIRECTS_TO,
    CLAIMS_IDENTITY,
    CROSS_LINKS_ACCOUNT,
    DERIVED_FROM,
    OTHER;

    companion object {
        fun fromLegacy(value: String): RelationshipType = when (value.lowercase()) {
            "has_email" -> HAS_EMAIL
            "has_phone" -> HAS_PHONE
            "uses_username", "username_on_profile" -> HAS_USERNAME
            "has_profile", "owns_profile", "possible_profile", "candidate_profile", "related_profile" -> USES_ACCOUNT
            "affiliated_with" -> AFFILIATED_WITH
            "associated_with_location" -> LOCATED_IN
            "linked_website" -> LINKS_TO
            "mentions" -> MENTIONS
            "related_image", "image_of_profile" -> USES_AVATAR
            "face_similar_to" -> VISUALLY_SIMILAR_TO
            "exposed_in", "has_breach_exposure" -> APPEARED_IN_BREACH
            else -> OTHER
        }
    }
}

@Serializable
data class DossierEntity(
    val id: String,
    val type: EntityType,
    val label: String,
    val confidence: Float = 0.5f,
    val sourceUrls: List<String> = emptyList(),
    val kind: GraphEntityKind = type.toGraphEntityKind(),
    val state: GraphNodeState = GraphNodeState.Unresolved,
    val evidenceIds: List<String> = emptyList(),
    val historical: Boolean = false,
    val firstObservedAtEpochMillis: Long? = null,
    val lastObservedAtEpochMillis: Long? = null
)

@Serializable
data class DossierEdge(
    val fromId: String,
    val toId: String,
    val relation: String,
    val evidence: String? = null,
    val relationType: RelationshipType = RelationshipType.fromLegacy(relation),
    val evidenceIds: List<String> = emptyList(),
    val contradictingEvidenceIds: List<String> = emptyList(),
    val confidence: Float? = null,
    val historical: Boolean = false
)

@Serializable
data class EntityGraph(
    val entities: List<DossierEntity> = emptyList(),
    val edges: List<DossierEdge> = emptyList(),
    val schemaVersion: Int = 2
) {
    fun entity(id: String): DossierEntity? = entities.firstOrNull { it.id == id }
    fun outgoing(id: String): List<DossierEdge> = edges.filter { it.fromId == id }
    fun incoming(id: String): List<DossierEdge> = edges.filter { it.toId == id }
    fun relationshipsFor(id: String): List<DossierEdge> = edges.filter { it.fromId == id || it.toId == id }
    fun historicalEntities(): List<DossierEntity> = entities.filter(DossierEntity::historical)
    fun conflictingEntities(): List<DossierEntity> = entities.filter { it.state == GraphNodeState.Conflicting }
}

@Serializable
data class BreachDigest(
    val email: String,
    val breachCount: Int,
    val sources: List<String> = emptyList(),
    val note: String? = null
)
