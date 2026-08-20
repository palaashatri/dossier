package io.dossier.app.data.web

/** Operational state for an external public-evidence source. */
enum class PublicSourceState {
    Active,
    BestEffort,
    Degraded,
    Retired,
    Unsupported
}

/**
 * Declarative source capability metadata used to avoid silently pretending that a
 * retired or brittle OSINT source is healthy.
 */
data class PublicSourceCapability(
    val id: String,
    val displayName: String,
    val state: PublicSourceState,
    val capabilities: Set<String>,
    val note: String,
    val directVerification: Boolean
)

object PublicSourceCatalog {
    val all: List<PublicSourceCapability> = listOf(
        PublicSourceCapability(
            id = "reddit-ghostddit-compatible",
            displayName = "Reddit public search (Ghostddit-compatible)",
            state = PublicSourceState.Active,
            capabilities = setOf("reddit-post-history", "reddit-comment-history", "exact-author-search"),
            note = "Uses Reddit's own public post search and Shreddit comment-search surfaces; Dossier does not depend on Ghostddit uptime.",
            directVerification = true
        ),
        PublicSourceCapability(
            id = "wayback",
            displayName = "Internet Archive Wayback Machine",
            state = PublicSourceState.Active,
            capabilities = setOf("historical-url", "snapshot-verification"),
            note = "Exact-URL historical lookup through Internet Archive availability/snapshot endpoints.",
            directVerification = true
        ),
        PublicSourceCapability(
            id = "archive-today",
            displayName = "Archive.today / archive.ph",
            state = PublicSourceState.BestEffort,
            capabilities = setOf("historical-url", "snapshot-verification"),
            note = "No stable official API is published; Dossier may use the public newest-snapshot route as a bounded fallback and must surface provider changes honestly.",
            directVerification = true
        ),
        PublicSourceCapability(
            id = "google-cache",
            displayName = "Google cached pages",
            state = PublicSourceState.Retired,
            capabilities = emptySet(),
            note = "Google retired cached-page links/cache lookup in 2024. Kept in the catalog so the UI/report can state that the source is unavailable rather than simulate support.",
            directVerification = false
        ),
        PublicSourceCapability(
            id = "bing-cache",
            displayName = "Bing cached pages",
            state = PublicSourceState.Retired,
            capabilities = emptySet(),
            note = "Bing removed its cached-page link after testing the removal in 2024. Use archive providers instead.",
            directVerification = false
        ),
        PublicSourceCapability(
            id = "twint",
            displayName = "Twint",
            state = PublicSourceState.Retired,
            capabilities = setOf("legacy-export-import"),
            note = "The upstream Twint repository was archived in 2023. Dossier must not rely on it as a live X/Twitter verification backend.",
            directVerification = false
        ),
        PublicSourceCapability(
            id = "snscrape",
            displayName = "snscrape",
            state = PublicSourceState.Degraded,
            capabilities = setOf("legacy-export-import"),
            note = "Useful as a legacy evidence format, but X/Twitter scraping is not dependable enough in 2026 to be an authoritative live provider. Imported records remain candidates until independently verified.",
            directVerification = false
        )
    )

    fun byId(id: String): PublicSourceCapability? = all.firstOrNull { it.id == id }

    fun usableFor(capability: String): List<PublicSourceCapability> = all.filter {
        capability in it.capabilities && it.state in setOf(PublicSourceState.Active, PublicSourceState.BestEffort)
    }
}
