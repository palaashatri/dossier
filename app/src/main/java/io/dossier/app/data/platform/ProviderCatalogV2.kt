package io.dossier.app.data.platform

import io.dossier.app.domain.discovery.ExistenceRules
import io.dossier.app.domain.discovery.ExtractionRules
import io.dossier.app.domain.discovery.ProviderCategory
import io.dossier.app.domain.discovery.ProviderDefinition
import io.dossier.app.domain.discovery.ProviderDefinitionValidator
import io.dossier.app.domain.discovery.ProviderRequestPolicy
import io.dossier.app.domain.discovery.ProviderScanPlan
import io.dossier.app.domain.discovery.QueryCapability
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.discovery.SourceReliability

/**
 * Discovery Fabric v2 provider catalog.
 *
 * This is intentionally a declarative data set. A provider is not considered
 * live-validated merely because it appears here; schema validation and live
 * health are different concepts and are reported separately in TRUTH.md.
 */
object ProviderCatalogV2 {
    val definitions: List<ProviderDefinition> = listOf(
        // Existing first-class providers.
        p("github", "GitHub", ProviderCategory.CodeHosting, "https://github.com/{username}", 100, "GitHub"),
        p("reddit", "Reddit", ProviderCategory.Forum, "https://www.reddit.com/user/{username}", 98, "Reddit"),
        p("gitlab", "GitLab", ProviderCategory.CodeHosting, "https://gitlab.com/{username}", 94, "GitLab"),
        p("devto", "DEV Community", ProviderCategory.Publishing, "https://dev.to/{username}", 92, "DevTo"),
        p("hacker-news", "Hacker News", ProviderCategory.Forum, "https://news.ycombinator.com/user?id={username}", 90, "HackerNews"),
        p("bluesky", "Bluesky", ProviderCategory.Social, "https://bsky.app/profile/{username}", 90, "Bluesky"),
        p("youtube", "YouTube", ProviderCategory.Media, "https://www.youtube.com/@{username}", 88, "YouTube"),
        p("medium", "Medium", ProviderCategory.Publishing, "https://medium.com/@{username}", 86, "Medium"),
        p("twitch", "Twitch", ProviderCategory.Media, "https://www.twitch.tv/{username}", 84, "Twitch"),
        p("telegram", "Telegram", ProviderCategory.Social, "https://t.me/{username}", 82, "Telegram"),
        p("pinterest", "Pinterest", ProviderCategory.Creative, "https://www.pinterest.com/{username}/", 80, "Pinterest"),
        p("mastodon-social", "Mastodon.social", ProviderCategory.Social, "https://mastodon.social/@{username}", 78, "Mastodon"),
        p("instagram", "Instagram", ProviderCategory.Social, "https://www.instagram.com/{username}/", 76, "Instagram", tags = setOf("challenge-prone")),
        p("x", "X", ProviderCategory.Social, "https://x.com/{username}", 74, "X", tags = setOf("challenge-prone")),
        p("tiktok", "TikTok", ProviderCategory.Media, "https://www.tiktok.com/@{username}", 72, "TikTok", tags = setOf("challenge-prone")),
        p("threads", "Threads", ProviderCategory.Social, "https://www.threads.net/@{username}", 70, "Threads", tags = setOf("challenge-prone")),
        p("facebook", "Facebook", ProviderCategory.Social, "https://www.facebook.com/{username}", 45, "Facebook", enabled = false, tags = setOf("authentication-often-required")),
        p("linkedin", "LinkedIn", ProviderCategory.Professional, "https://www.linkedin.com/in/{username}", 45, "LinkedIn", enabled = false, tags = setOf("authentication-often-required")),
        p("snapchat", "Snapchat", ProviderCategory.Social, "https://www.snapchat.com/add/{username}", 35, "Snapchat", enabled = false, tags = setOf("limited-public-verification")),
        p("discord", "Discord", ProviderCategory.Social, "https://discord.com/users/{username}", 25, "Discord", enabled = false, tags = setOf("numeric-id-oriented")),
        p("stack-overflow", "Stack Overflow", ProviderCategory.Developer, "https://stackoverflow.com/users/{username}", 25, "StackOverflow", enabled = false, tags = setOf("numeric-id-oriented")),

        // Additional simple public-profile definitions. These use the generic
        // direct-page verifier; provider-specific adapters can be added only
        // when a generic definition is insufficient.
        p("codeberg", "Codeberg", ProviderCategory.CodeHosting, "https://codeberg.org/{username}", 92),
        p("sourcehut", "SourceHut", ProviderCategory.CodeHosting, "https://sr.ht/~{username}/", 90),
        p("gitee", "Gitee", ProviderCategory.CodeHosting, "https://gitee.com/{username}", 78),
        p("bitbucket", "Bitbucket", ProviderCategory.CodeHosting, "https://bitbucket.org/{username}/", 76),
        p("launchpad", "Launchpad", ProviderCategory.CodeHosting, "https://launchpad.net/~{username}", 74),
        p("keybase", "Keybase", ProviderCategory.Developer, "https://keybase.io/{username}", 90),
        p("hugging-face", "Hugging Face", ProviderCategory.Developer, "https://huggingface.co/{username}", 88),
        p("replit", "Replit", ProviderCategory.Developer, "https://replit.com/@{username}", 76),
        p("kaggle", "Kaggle", ProviderCategory.Developer, "https://www.kaggle.com/{username}", 76),
        p("leetcode", "LeetCode", ProviderCategory.Developer, "https://leetcode.com/u/{username}/", 74),
        p("codeforces", "Codeforces", ProviderCategory.Developer, "https://codeforces.com/profile/{username}", 74),
        p("npm", "npm", ProviderCategory.PackageRegistry, "https://www.npmjs.com/~{username}", 90),
        p("pypi", "PyPI", ProviderCategory.PackageRegistry, "https://pypi.org/user/{username}/", 88),
        p("rubygems", "RubyGems", ProviderCategory.PackageRegistry, "https://rubygems.org/profiles/{username}", 78),
        p("nuget", "NuGet", ProviderCategory.PackageRegistry, "https://www.nuget.org/profiles/{username}", 78),
        p("packagist", "Packagist", ProviderCategory.PackageRegistry, "https://packagist.org/users/{username}/", 76),
        p("metacpan", "MetaCPAN", ProviderCategory.PackageRegistry, "https://metacpan.org/author/{username}", 72),
        p("docker-hub", "Docker Hub", ProviderCategory.PackageRegistry, "https://hub.docker.com/u/{username}", 84),
        p("quay", "Quay", ProviderCategory.PackageRegistry, "https://quay.io/user/{username}", 68),
        p("chess-com", "Chess.com", ProviderCategory.Gaming, "https://www.chess.com/member/{username}", 70),
        p("lichess", "Lichess", ProviderCategory.Gaming, "https://lichess.org/@/{username}", 72),
        p("steam-community", "Steam Community", ProviderCategory.Gaming, "https://steamcommunity.com/id/{username}", 64),
        p("itch-io", "itch.io", ProviderCategory.Gaming, "https://{username}.itch.io/", 66, legacyCompatible = false),
        p("behance", "Behance", ProviderCategory.Creative, "https://www.behance.net/{username}", 74),
        p("dribbble", "Dribbble", ProviderCategory.Creative, "https://dribbble.com/{username}", 72),
        p("artstation", "ArtStation", ProviderCategory.Creative, "https://www.artstation.com/{username}", 70),
        p("deviantart", "DeviantArt", ProviderCategory.Creative, "https://www.deviantart.com/{username}", 68),
        p("unsplash", "Unsplash", ProviderCategory.Creative, "https://unsplash.com/@{username}", 72),
        p("flickr", "Flickr", ProviderCategory.Creative, "https://www.flickr.com/people/{username}/", 66),
        p("five-hundred-px", "500px", ProviderCategory.Creative, "https://500px.com/p/{username}", 58),
        p("soundcloud", "SoundCloud", ProviderCategory.Media, "https://soundcloud.com/{username}", 72),
        p("vimeo", "Vimeo", ProviderCategory.Media, "https://vimeo.com/{username}", 68),
        p("mixcloud", "Mixcloud", ProviderCategory.Media, "https://www.mixcloud.com/{username}/", 64),
        p("lastfm", "Last.fm", ProviderCategory.Media, "https://www.last.fm/user/{username}", 64),
        p("trakt", "Trakt", ProviderCategory.Media, "https://trakt.tv/users/{username}", 60),
        p("letterboxd", "Letterboxd", ProviderCategory.Media, "https://letterboxd.com/{username}/", 66),
        p("myanimelist", "MyAnimeList", ProviderCategory.Media, "https://myanimelist.net/profile/{username}", 58),
        p("hashnode", "Hashnode", ProviderCategory.Publishing, "https://hashnode.com/@{username}", 72),
        p("tumblr", "Tumblr", ProviderCategory.Publishing, "https://{username}.tumblr.com/", 64, legacyCompatible = false),
        p("wordpress", "WordPress.com", ProviderCategory.Publishing, "https://{username}.wordpress.com/", 60, legacyCompatible = false),
        p("substack", "Substack", ProviderCategory.Publishing, "https://{username}.substack.com/", 60, legacyCompatible = false),
        p("about-me", "about.me", ProviderCategory.PersonalWebsite, "https://about.me/{username}", 70),
        p("gravatar", "Gravatar", ProviderCategory.PersonalWebsite, "https://gravatar.com/{username}", 68),
        p("linktree", "Linktree", ProviderCategory.PersonalWebsite, "https://linktr.ee/{username}", 68),
        p("product-hunt", "Product Hunt", ProviderCategory.Professional, "https://www.producthunt.com/@{username}", 66),
        p("kofi", "Ko-fi", ProviderCategory.Commerce, "https://ko-fi.com/{username}", 58),
        p("buy-me-a-coffee", "Buy Me a Coffee", ProviderCategory.Commerce, "https://www.buymeacoffee.com/{username}", 56),
        p("patreon", "Patreon", ProviderCategory.Commerce, "https://www.patreon.com/{username}", 52, tags = setOf("challenge-prone")),
        p("quora", "Quora", ProviderCategory.Forum, "https://www.quora.com/profile/{username}", 54, tags = setOf("challenge-prone")),

        // Non-profile providers are represented in the same registry so scan
        // planning and health accounting have one source of truth. Existing
        // custom implementations continue to execute these today.
        service("wayback", "Internet Archive Wayback", ProviderCategory.Archive, setOf(QueryCapability.Url, QueryCapability.Archive), 95, SourceReliability.ArchiveSnapshot),
        service("hibp", "Have I Been Pwned", ProviderCategory.BreachMetadata, setOf(QueryCapability.Email, QueryCapability.Breach), 100, SourceReliability.AuthoritativeApi),
        service("search-brave", "Brave Search", ProviderCategory.SearchEngine, setOf(QueryCapability.Username, QueryCapability.Name, QueryCapability.Email), 75, SourceReliability.SearchCandidate),
        service("search-duckduckgo", "DuckDuckGo", ProviderCategory.SearchEngine, setOf(QueryCapability.Username, QueryCapability.Name, QueryCapability.Email), 70, SourceReliability.SearchCandidate),
        service("search-bing", "Bing", ProviderCategory.SearchEngine, setOf(QueryCapability.Username, QueryCapability.Name, QueryCapability.Email), 68, SourceReliability.SearchCandidate),
        service("search-yandex", "Yandex", ProviderCategory.SearchEngine, setOf(QueryCapability.Username, QueryCapability.Name, QueryCapability.Email, QueryCapability.Image), 66, SourceReliability.SearchCandidate),
        service("search-qwant", "Qwant", ProviderCategory.SearchEngine, setOf(QueryCapability.Username, QueryCapability.Name, QueryCapability.Email), 62, SourceReliability.SearchCandidate),
        service("search-mojeek", "Mojeek", ProviderCategory.SearchEngine, setOf(QueryCapability.Username, QueryCapability.Name, QueryCapability.Email), 60, SourceReliability.SearchCandidate)
    )

    val schemaIssues by lazy { ProviderDefinitionValidator.validateRegistry(definitions) }

    fun enabledDefinitions(): List<ProviderDefinition> = definitions.filter(ProviderDefinition::enabled)

    fun plan(
        mode: ScanMode,
        enabledCategories: Set<ProviderCategory> = ProviderCategory.entries.toSet()
    ): ProviderScanPlan {
        val candidates = enabledDefinitions()
            .asSequence()
            .filter { it.category in enabledCategories }
            .filter { mode.includeHistoricalProviders || it.category != ProviderCategory.Archive }
            .sortedWith(compareByDescending<ProviderDefinition> { it.priority }.thenBy { it.id })
            .take(mode.providerLimit)
            .toList()
        return ProviderScanPlan(mode = mode, providers = candidates)
    }

    /** Definitions executable by the current legacy profile fan-out. */
    fun legacyProfileDefinitions(mode: ScanMode = ScanMode.Standard): List<ProviderDefinition> =
        plan(mode).providers.filter { definition ->
            definition.profileUrlTemplate != null &&
                QueryCapability.Username in definition.queryCapabilities &&
                definition.legacyTemplateCompatible
        }

    private val definitionsById: Map<String, ProviderDefinition> by lazy {
        definitions.associateBy { it.id.lowercase() }
    }

    fun findById(id: String): ProviderDefinition? = definitionsById[id.trim().lowercase()]

    operator fun get(id: String): ProviderDefinition? = findById(id)

    fun schemaValidCount(): Int = definitions.size - schemaIssues.map { it.providerId }.distinct().size

    private fun p(
        id: String,
        displayName: String,
        category: ProviderCategory,
        template: String,
        priority: Int,
        legacyPlatformName: String? = null,
        enabled: Boolean = true,
        tags: Set<String> = emptySet(),
        legacyCompatible: Boolean = true
    ) = ProviderDefinition(
        id = id,
        displayName = displayName,
        category = category,
        profileUrlTemplate = template,
        queryCapabilities = setOf(QueryCapability.Username),
        existenceRules = ExistenceRules(
            requiredStatus = setOf(200),
            notFoundStatus = setOf(404, 410),
            softNotFoundText = listOf("page not found", "profile not found", "user not found", "doesn't exist"),
            authenticationText = listOf("sign in to continue", "log in to continue"),
            challengeText = listOf("verify you are human", "checking your browser")
        ),
        extractionRules = ExtractionRules(),
        priority = priority,
        tags = tags,
        enabled = enabled,
        reliability = SourceReliability.DirectPublicProfile,
        requestPolicy = ProviderRequestPolicy(
            maxConcurrency = 1,
            minimumIntervalMs = if ("challenge-prone" in tags) 1_500 else 750,
            timeoutMs = 5_000,
            retryBudget = if ("challenge-prone" in tags) 0 else 1,
            cooldownMs = 30_000
        ),
        legacyPlatformName = legacyPlatformName,
        legacyTemplateCompatible = legacyCompatible
    )

    private fun service(
        id: String,
        displayName: String,
        category: ProviderCategory,
        capabilities: Set<QueryCapability>,
        priority: Int,
        reliability: SourceReliability
    ) = ProviderDefinition(
        id = id,
        displayName = displayName,
        category = category,
        queryCapabilities = capabilities,
        priority = priority,
        reliability = reliability,
        requestPolicy = ProviderRequestPolicy(maxConcurrency = 1, minimumIntervalMs = 1_000)
    )
}
