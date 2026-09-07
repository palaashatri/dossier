package io.dossier.app.data.web

/** Operational state for an external public-evidence source. */
enum class PublicSourceState {
    Active,
    BestEffort,
    Degraded,
    Retired,
    Unsupported
}

/** Broad product area a source contributes to. */
enum class PublicSourceCategory {
    Identity,
    Social,
    Geography,
    Breach,
    Corporate,
    Visual,
    Phone,
    DarkWeb,
    Infrastructure,
    Graph
}

/** How Dossier integrates the source without overstating what is actually implemented. */
enum class PublicSourceIntegrationMode {
    Native,
    NativeEquivalent,
    ImportOnly,
    ApiKeyRequired,
    ManualOnly,
    Retired,
    Unsupported
}

/**
 * Declarative source capability metadata used to avoid silently pretending that a
 * retired, commercial, authenticated, or brittle OSINT source is healthy.
 *
 * `directVerification=true` means Dossier can independently re-fetch/validate the
 * public evidence. Import-only tools always remain Candidate/Observed until that
 * happens. No entry authorizes private-source access, credential collection,
 * challenge bypass, or arbitrary-person deanonymization.
 */
data class PublicSourceCapability(
    val id: String,
    val displayName: String,
    val state: PublicSourceState,
    val capabilities: Set<String>,
    val note: String,
    val directVerification: Boolean,
    val category: PublicSourceCategory = PublicSourceCategory.Identity,
    val integrationMode: PublicSourceIntegrationMode = PublicSourceIntegrationMode.Native,
    val automatedInAuthorizedScan: Boolean = false
)

object PublicSourceCatalog {
    val all: List<PublicSourceCapability> = listOf(
        // ---- Identity footprint / discovery ----
        source(
            "spiderfoot", "SpiderFoot OSS", PublicSourceState.Active,
            PublicSourceCategory.Identity, PublicSourceIntegrationMode.ImportOnly,
            setOf("identity-report-import", "public-url-candidates", "domain-report-import"),
            "Dossier accepts bounded user-supplied SpiderFoot exports as candidate evidence. It does not run SpiderFoot's broad module set inside the Android app."
        ),
        source(
            "recon-ng", "Recon-ng", PublicSourceState.Active,
            PublicSourceCategory.Identity, PublicSourceIntegrationMode.ImportOnly,
            setOf("identity-report-import", "domain-report-import"),
            "Workspace/report exports may be imported locally. Module execution remains external and must already be within the user's authorization scope."
        ),
        source(
            "theharvester", "theHarvester", PublicSourceState.Active,
            PublicSourceCategory.Identity, PublicSourceIntegrationMode.ImportOnly,
            setOf("domain-report-import", "public-email-candidates", "public-url-candidates"),
            "Imports are limited to domains derived from explicit audit seeds; discovered values remain candidates until corroborated."
        ),
        source(
            "maigret", "Maigret", PublicSourceState.Active,
            PublicSourceCategory.Identity, PublicSourceIntegrationMode.ImportOnly,
            setOf("username-report-import", "public-profile-candidates"),
            "Maigret reports may seed profile candidates for explicitly supplied usernames. Dossier performs its own direct-page attribution checks before promotion."
        ),
        source(
            "sherlock", "Sherlock", PublicSourceState.Active,
            PublicSourceCategory.Identity, PublicSourceIntegrationMode.ImportOnly,
            setOf("username-report-import", "public-profile-candidates"),
            "Sherlock output is treated as enumeration evidence, never proof that the account belongs to the audited identity."
        ),
        source(
            "holehe", "Holehe", PublicSourceState.Degraded,
            PublicSourceCategory.Identity, PublicSourceIntegrationMode.ImportOnly,
            setOf("redacted-account-existence-import"),
            "Dossier does not run registration/password-recovery probes. A user may import a redacted report for an explicitly supplied email; service-existence rows remain low-confidence candidates."
        ),

        // ---- Social media ----
        source(
            "reddit-ghostddit-compatible", "Reddit public search (Ghostddit-compatible)", PublicSourceState.Active,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.Native,
            setOf("reddit-post-history", "reddit-comment-history", "exact-author-search"),
            "Uses Reddit public search surfaces; bounded direct Reddit re-fetches promote exact-author evidence to Verified.",
            direct = true, automated = true
        ),
        source(
            "pushshift", "Pushshift", PublicSourceState.Degraded,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ImportOnly,
            setOf("reddit-history-import"),
            "Access and coverage have changed repeatedly. Dossier accepts user-supplied exports but does not treat Pushshift as authoritative current Reddit state."
        ),
        source(
            "aeddit", "Aeddit", PublicSourceState.BestEffort,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ManualOnly,
            setOf("reddit-history-reference"),
            "Third-party Reddit history interfaces may disappear or change; links can be reviewed manually, while Dossier verifies surviving Reddit URLs directly."
        ),
        source(
            "wayback", "Internet Archive Wayback Machine", PublicSourceState.Active,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.Native,
            setOf("historical-url", "history-discovery", "snapshot-verification"),
            "Exact-URL recovery plus bounded CDX history discovery for profile/personal-site URLs explicitly supplied to the audit; selected snapshots are re-fetched before being marked Verified.",
            direct = true, automated = true
        ),
        source(
            "archive-today", "Archive.today / archive.ph", PublicSourceState.BestEffort,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.Native,
            setOf("historical-url", "snapshot-verification"),
            "No stable official API is published; Dossier uses a bounded exact-URL fallback and surfaces provider changes/challenges honestly.",
            direct = true, automated = true
        ),
        source(
            "twint", "Twint", PublicSourceState.Retired,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.Retired,
            setOf("legacy-export-import"),
            "The upstream project is archived. User-supplied Twint JSON can still be imported locally as Candidate/ThirdPartyAggregation evidence for authorized handles."
        ),
        source(
            "snscrape", "snscrape", PublicSourceState.Degraded,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ImportOnly,
            setOf("legacy-export-import"),
            "User-supplied JSON/JSONL may be imported. Dossier does not treat live X/Twitter scraping as authoritative and re-verifies public URLs independently."
        ),
        source(
            "tweetstamp", "TweetStamp", PublicSourceState.BestEffort,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ManualOnly,
            setOf("tweet-timestamp-reference"),
            "Timestamp/notarization links can be retained as corroborating references but do not establish account ownership by themselves."
        ),
        source(
            "nitter", "Nitter instances", PublicSourceState.BestEffort,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ManualOnly,
            setOf("x-public-mirror-reference"),
            "Instance availability is volatile. Mirrors are discovery aids only; canonical/archived URLs are preferred for verification."
        ),
        source(
            "osintgram", "OSINTgram", PublicSourceState.Degraded,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ImportOnly,
            setOf("instagram-report-import"),
            "Dossier does not accept Instagram login cookies or automate authenticated collection. Public-report exports can be imported as candidate evidence."
        ),
        source(
            "instaloader", "Instaloader", PublicSourceState.Active,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ImportOnly,
            setOf("instagram-public-export-import", "public-profile-candidates"),
            "Public metadata exports may be imported locally; no credentials/session cookies are stored by Dossier."
        ),
        source(
            "facebook-scraper", "facebook-scraper", PublicSourceState.Degraded,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ImportOnly,
            setOf("facebook-public-page-import"),
            "Only user-supplied public-page exports are accepted. Dossier does not automate login/session scraping."
        ),
        source(
            "social-analyzer", "Social Analyzer", PublicSourceState.Active,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ImportOnly,
            setOf("username-report-import", "public-profile-candidates"),
            "Enumeration reports seed candidates for explicit usernames; Dossier's verifier remains the attribution authority."
        ),
        source(
            "linkedin-scraper", "LinkedIn scraper exports", PublicSourceState.Degraded,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.ImportOnly,
            setOf("linkedin-public-export-import"),
            "No LinkedIn credentials, cookies, or authenticated scraping are supported. User-supplied public exports remain candidates until corroborated."
        ),

        // ---- Geography / location ----
        source(
            "exiftool", "ExifTool-compatible metadata", PublicSourceState.Active,
            PublicSourceCategory.Geography, PublicSourceIntegrationMode.NativeEquivalent,
            setOf("image-metadata", "gps-metadata", "capture-time", "device-metadata"),
            "Dossier performs bounded local EXIF extraction on user-selected media instead of shipping the Perl ExifTool runtime.",
            direct = true
        ),
        source(
            "geosocial", "GeoSocial footprint", PublicSourceState.Active,
            PublicSourceCategory.Geography, PublicSourceIntegrationMode.ImportOnly,
            setOf("public-geotag-import", "location-corroboration"),
            "Explicit geotags from authorized public exports can be imported; inferred movement history is never promoted from a single weak location signal."
        ),
        source(
            "openstreetmap-overpass", "OpenStreetMap / Overpass", PublicSourceState.Active,
            PublicSourceCategory.Geography, PublicSourceIntegrationMode.ManualOnly,
            setOf("place-verification", "address-reference", "nearby-feature-reference"),
            "Used as an open geographic cross-check for coordinates/places already present in the audit. Dossier does not use it to infer a person's location from unrelated data."
        ),
        source(
            "google-earth-streetview", "Google Earth / Street View", PublicSourceState.Active,
            PublicSourceCategory.Geography, PublicSourceIntegrationMode.ManualOnly,
            setOf("visual-place-cross-check"),
            "Proprietary external visual reference, not an open-source dependency. Dossier may open a user-driven location query but does not bulk-scrape Street View."
        ),
        source(
            "geospy", "GeoSpy", PublicSourceState.Active,
            PublicSourceCategory.Geography, PublicSourceIntegrationMode.ManualOnly,
            setOf("visual-geolocation-reference"),
            "External/proprietary geolocation assistance; results are corroborating leads only and are not treated as verified identity/location facts."
        ),
        source(
            "geoguessr-techniques", "GeoGuessr-style visual techniques", PublicSourceState.Active,
            PublicSourceCategory.Geography, PublicSourceIntegrationMode.ManualOnly,
            setOf("visual-geolocation-method"),
            "A human analysis method rather than a machine-verifiable provider."
        ),

        // ---- Breach / exposure ----
        source(
            "hibp", "Have I Been Pwned", PublicSourceState.Active,
            PublicSourceCategory.Breach, PublicSourceIntegrationMode.ApiKeyRequired,
            setOf("breach-account", "breach-catalogue", "pwned-password-range"),
            "Pwned Passwords is free; account/breach lookup availability depends on HIBP subscription capabilities. Dossier keeps HIBP coverage separate from ordinary web exposure.",
            direct = true
        ),
        source(
            "leakcheck", "LeakCheck", PublicSourceState.Active,
            PublicSourceCategory.Breach, PublicSourceIntegrationMode.ImportOnly,
            setOf("redacted-breach-summary-import"),
            "Commercial/freemium breach data is not queried automatically. Only redacted user-supplied summaries are eligible for import; passwords, hashes, cookies and tokens are rejected."
        ),
        source(
            "dehashed", "DeHashed", PublicSourceState.Active,
            PublicSourceCategory.Breach, PublicSourceIntegrationMode.ImportOnly,
            setOf("redacted-breach-summary-import"),
            "Dossier does not query or store credential material from DeHashed. Redacted exposure summaries may be retained as third-party evidence."
        ),
        source(
            "breach-parse", "Breach-Parse", PublicSourceState.Active,
            PublicSourceCategory.Breach, PublicSourceIntegrationMode.ImportOnly,
            setOf("redacted-breach-summary-import"),
            "Raw credential dumps are outside Dossier's import contract. Only sanitized summaries without secrets may enter the evidence pipeline."
        ),
        source(
            "buster-breachfinder", "Buster / BreachFinder", PublicSourceState.BestEffort,
            PublicSourceCategory.Breach, PublicSourceIntegrationMode.ImportOnly,
            setOf("redacted-breach-summary-import"),
            "Summary-only interoperability. Credential values and authentication artifacts are explicitly excluded."
        ),

        // ---- Corporate / professional ----
        source(
            "opencorporates", "OpenCorporates", PublicSourceState.Active,
            PublicSourceCategory.Corporate, PublicSourceIntegrationMode.ApiKeyRequired,
            setOf("company-verification", "organization-reference", "corporate-report-import"),
            "API access requires an account/key and licensing depends on use. Dossier limits automated use to organizations explicitly supplied in the audit rather than person-name fishing."
        ),
        source(
            "mca-india", "Ministry of Corporate Affairs (India)", PublicSourceState.Active,
            PublicSourceCategory.Corporate, PublicSourceIntegrationMode.ManualOnly,
            setOf("india-company-verification"),
            "Official public corporate records are suitable for manual corroboration. Dossier does not automate CAPTCHA/challenge flows."
        ),
        source(
            "zauba", "Zauba corporate records", PublicSourceState.BestEffort,
            PublicSourceCategory.Corporate, PublicSourceIntegrationMode.ManualOnly,
            setOf("india-company-reference"),
            "Third-party public corporate reference; primary MCA records are preferred when available."
        ),
        source(
            "github-osint", "GitHub OSINT / profile summaries", PublicSourceState.Active,
            PublicSourceCategory.Corporate, PublicSourceIntegrationMode.ImportOnly,
            setOf("github-public-report-import", "employment-claim-corroboration"),
            "Public GitHub-derived reports can seed evidence, but direct GitHub profile/repository data is preferred for verification."
        ),
        source(
            "gh-archive", "GH Archive", PublicSourceState.Active,
            PublicSourceCategory.Corporate, PublicSourceIntegrationMode.ImportOnly,
            setOf("github-historical-event-import"),
            "Historical public GitHub events may be imported when tied to explicitly supplied handles/repositories."
        ),

        // ---- Visual / image ----
        source(
            "google-lens", "Google Lens / Search by Image", PublicSourceState.Active,
            PublicSourceCategory.Visual, PublicSourceIntegrationMode.ManualOnly,
            setOf("reverse-image-reference"),
            "Proprietary external reverse-image search. Dossier performs local comparison of acquired public candidates and does not equate visual similarity with identity."
        ),
        source(
            "yandex-images", "Yandex Images", PublicSourceState.Active,
            PublicSourceCategory.Visual, PublicSourceIntegrationMode.Native,
            setOf("reverse-image-candidate-discovery"),
            "Candidate discovery only; downloaded images are compared locally and similarity remains evidence rather than identity proof.",
            automated = true
        ),
        source(
            "image-analyzer", "Image Analyzer exports", PublicSourceState.BestEffort,
            PublicSourceCategory.Visual, PublicSourceIntegrationMode.ImportOnly,
            setOf("image-analysis-import"),
            "Metadata/analysis summaries can be imported; opaque face-identity assertions are not promoted without Dossier's own consented local comparison."
        ),
        source(
            "face-recognition", "face_recognition", PublicSourceState.Active,
            PublicSourceCategory.Visual, PublicSourceIntegrationMode.NativeEquivalent,
            setOf("face-embedding-alternative"),
            "Dossier already uses a local consented face-correlation pipeline; it does not need the Python face_recognition runtime."
        ),
        source(
            "deepface", "DeepFace", PublicSourceState.Active,
            PublicSourceCategory.Visual, PublicSourceIntegrationMode.NativeEquivalent,
            setOf("face-embedding-alternative"),
            "External model/library alternative only. Dossier keeps face comparison local, consented, provenance-linked and non-identifying by itself."
        ),

        // ---- Phone ----
        source(
            "phoneinfoga", "PhoneInfoga", PublicSourceState.Active,
            PublicSourceCategory.Phone, PublicSourceIntegrationMode.ImportOnly,
            setOf("phone-public-metadata-import"),
            "Reports for explicitly supplied phone numbers may be imported. Results remain public-metadata leads and cannot justify private subscriber claims."
        ),
        source(
            "numverify", "Numverify", PublicSourceState.Active,
            PublicSourceCategory.Phone, PublicSourceIntegrationMode.ApiKeyRequired,
            setOf("phone-format-validation", "carrier-region-metadata"),
            "Commercial/freemium API. Any future connector must be restricted to phone numbers explicitly supplied to the audit."
        ),

        // ---- Dark/deep web ----
        source(
            "onionscan", "OnionScan", PublicSourceState.Degraded,
            PublicSourceCategory.DarkWeb, PublicSourceIntegrationMode.ManualOnly,
            setOf("user-supplied-onion-site-audit"),
            "Dossier does not crawl Tor to discover people or credential material. A separately authorized onion-site assessment may be referenced manually."
        ),
        source(
            "ahmia", "Ahmia", PublicSourceState.Active,
            PublicSourceCategory.DarkWeb, PublicSourceIntegrationMode.ManualOnly,
            setOf("onion-search-reference"),
            "Not used for automated person lookup. Manual use must remain within the user's lawful authorization and Dossier never imports credentials/secrets from results."
        ),
        source(
            "tor-browser", "Tor Browser", PublicSourceState.Active,
            PublicSourceCategory.DarkWeb, PublicSourceIntegrationMode.ManualOnly,
            setOf("manual-onion-review"),
            "External browser only; Dossier does not bundle Tor, hide collection traffic, or automate dark-web crawling."
        ),

        // ---- General metadata / infrastructure ----
        source(
            "foca", "FOCA-compatible metadata reports", PublicSourceState.Active,
            PublicSourceCategory.Infrastructure, PublicSourceIntegrationMode.ImportOnly,
            setOf("document-metadata-import"),
            "User-supplied metadata reports may be imported after secret/credential fields are stripped."
        ),
        source(
            "censys", "Censys", PublicSourceState.Active,
            PublicSourceCategory.Infrastructure, PublicSourceIntegrationMode.ApiKeyRequired,
            setOf("owned-domain-infrastructure", "ip-certificate-reference"),
            "Infrastructure verification is limited to domains/IPs explicitly in scope; it is not an identity-enumeration backend."
        ),
        source(
            "shodan", "Shodan", PublicSourceState.Active,
            PublicSourceCategory.Infrastructure, PublicSourceIntegrationMode.ApiKeyRequired,
            setOf("owned-domain-infrastructure", "ip-service-reference"),
            "Infrastructure-only integration boundary. No active exploitation or scanning is performed by Dossier."
        ),
        source(
            "amass", "OWASP Amass", PublicSourceState.Active,
            PublicSourceCategory.Infrastructure, PublicSourceIntegrationMode.ImportOnly,
            setOf("domain-report-import", "subdomain-reference"),
            "Reports are accepted only for explicit in-scope domains and are not used to infer personal identity."
        ),

        // ---- Link analysis / graph interoperability ----
        source(
            "opencti", "OpenCTI", PublicSourceState.Active,
            PublicSourceCategory.Graph, PublicSourceIntegrationMode.ImportOnly,
            setOf("stix-reference", "graph-interchange"),
            "OpenCTI is threat-intelligence software rather than a person-verification source. Interoperability is limited to graph/evidence exchange."
        ),
        source(
            "graphistry", "Graphistry", PublicSourceState.Active,
            PublicSourceCategory.Graph, PublicSourceIntegrationMode.ManualOnly,
            setOf("graph-export"),
            "External hosted/enterprise graph visualization option; not universally free or open source."
        ),
        source(
            "gephi", "Gephi", PublicSourceState.Active,
            PublicSourceCategory.Graph, PublicSourceIntegrationMode.NativeEquivalent,
            setOf("graphml-export", "gexf-export"),
            "Dossier graph data can be exported into standard graph formats suitable for Gephi."
        ),
        source(
            "cytoscape", "Cytoscape", PublicSourceState.Active,
            PublicSourceCategory.Graph, PublicSourceIntegrationMode.NativeEquivalent,
            setOf("graphml-export", "json-graph-export"),
            "Interoperability is through standard graph exports rather than embedding Cytoscape."
        ),
        source(
            "networkx", "NetworkX", PublicSourceState.Active,
            PublicSourceCategory.Graph, PublicSourceIntegrationMode.NativeEquivalent,
            setOf("graphml-export", "json-graph-export"),
            "Standard graph exports can be loaded into NetworkX for offline analysis."
        ),

        // ---- Explicitly retired caches retained for truthful reporting ----
        source(
            "google-cache", "Google cached pages", PublicSourceState.Retired,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.Retired,
            emptySet(),
            "Google retired the former cached-page lookup. The catalog keeps the name so Dossier can report it as unavailable rather than simulate support."
        ),
        source(
            "bing-cache", "Bing cached pages", PublicSourceState.Retired,
            PublicSourceCategory.Social, PublicSourceIntegrationMode.Retired,
            emptySet(),
            "Bing removed its public cached-page link. Archive providers are used instead."
        )
    )

    fun byId(id: String): PublicSourceCapability? = all.firstOrNull { it.id == id }

    fun byCategory(category: PublicSourceCategory): List<PublicSourceCapability> =
        all.filter { it.category == category }

    fun usableFor(capability: String): List<PublicSourceCapability> = all.filter {
        capability in it.capabilities && it.state in setOf(PublicSourceState.Active, PublicSourceState.BestEffort)
    }

    fun automated(): List<PublicSourceCapability> = all.filter {
        it.automatedInAuthorizedScan && it.state in setOf(PublicSourceState.Active, PublicSourceState.BestEffort)
    }

    private fun source(
        id: String,
        name: String,
        state: PublicSourceState,
        category: PublicSourceCategory,
        integration: PublicSourceIntegrationMode,
        capabilities: Set<String>,
        note: String,
        direct: Boolean = false,
        automated: Boolean = false
    ) = PublicSourceCapability(
        id = id,
        displayName = name,
        state = state,
        capabilities = capabilities,
        note = note,
        directVerification = direct,
        category = category,
        integrationMode = integration,
        automatedInAuthorizedScan = automated
    )
}
