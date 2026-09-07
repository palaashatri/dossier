package io.dossier.app.data.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/**
 * Structured container for explicit historical metadata extracted from an
 * archived HTML snapshot.
 */
internal data class ExtractedSnapshotMetadata(
    val displayName: String? = null,
    val bio: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val externalLinks: List<String> = emptyList(),
    val organization: String? = null,
    val location: String? = null
) {
    val isEmpty: Boolean
        get() = displayName == null &&
            bio == null &&
            username == null &&
            avatarUrl == null &&
            externalLinks.isEmpty() &&
            organization == null &&
            location == null
}

/**
 * Pure internal extractor that parses archived profile/page snapshots using Jsoup.
 *
 * Strict rules:
 * 1. Extracts only explicit historical metadata (display name, bio, username,
 *    avatar URL, external links, organization, location).
 * 2. Never infers username from URL/path (only explicit markup/meta tags).
 * 3. Enforces strict length and count caps.
 * 4. Strictly validates URLs: HTTP(S) only, valid host, rejects userinfo,
 *    unwraps Wayback replay prefixes.
 * 5. Fails soft on malformed HTML, invalid selectors, or missing fields.
 */
internal object ArchiveSnapshotExtractor {
    const val MAX_DISPLAY_NAME_CHARS = 150
    const val MAX_BIO_CHARS = 1000
    const val MAX_USERNAME_CHARS = 100
    const val MAX_ORGANIZATION_CHARS = 150
    const val MAX_LOCATION_CHARS = 150
    const val MAX_URL_CHARS = 1024
    const val MAX_EXTERNAL_LINKS = 10

    private val GENERIC_NAMES = setOf(
        "wayback machine", "internet archive", "404 not found", "access denied",
        "error", "home", "profile", "login", "sign in", "untitled", "index"
    )

    private val GENERIC_USERNAMES = setOf(
        "admin", "null", "undefined", "unknown", "profile", "user", "twitter",
        "github", "instagram", "facebook", "reddit", "youtube"
    )

    private val USERNAME_PATTERN = Regex("^[a-zA-Z0-9._-]{1,100}$")

    fun extract(
        html: String,
        snapshotUrl: String? = null,
        originalUrl: String? = null
    ): ExtractedSnapshotMetadata {
        if (html.isBlank()) return ExtractedSnapshotMetadata()
        val baseUrl = snapshotUrl?.takeIf(String::isNotBlank) ?: originalUrl?.takeIf(String::isNotBlank) ?: ""
        val doc = runCatching {
            Jsoup.parse(html, baseUrl)
        }.getOrNull() ?: return ExtractedSnapshotMetadata()

        runCatching {
            doc.select(
                "script,style,noscript,svg,template,#wm-ipp,#wm-ipp-base,#wm-ipp-inside," +
                    ".wb-autocomplete-suggestions,.donatenag,.wb-dropdown,.r-historical-toolbar"
            ).remove()
        }

        val effectiveBase = snapshotUrl ?: originalUrl
        val displayName = extractDisplayName(doc)
        val bio = extractBio(doc)
        val username = extractUsername(doc)
        val avatarUrl = extractAvatarUrl(doc, effectiveBase)
        val externalLinks = extractExternalLinks(doc, effectiveBase, originalUrl)
        val organization = extractOrganization(doc)
        val location = extractLocation(doc)

        return ExtractedSnapshotMetadata(
            displayName = displayName,
            bio = bio,
            username = username,
            avatarUrl = avatarUrl,
            externalLinks = externalLinks,
            organization = organization,
            location = location
        )
    }

    private fun extractDisplayName(doc: Document): String? {
        val candidates = mutableListOf<String>()

        runCatching {
            doc.select(
                "meta[property=og:title],meta[name=twitter:title]," +
                    "meta[property=profile:first_name],meta[name=author]"
            ).forEach { el ->
                val content = el.attr("content").trim()
                if (content.isNotBlank()) candidates.add(content)
            }
        }

        runCatching {
            doc.select(
                "[itemprop=name],[itemprop=givenName]," +
                    "h1.vcard-names span.p-name,h1.fn,.vcard .fn,.vcard .p-name," +
                    "[class*=author-name],[class*=profile-name],[class*=display-name]," +
                    "[class*=fullname],[class*=user-name]"
            ).forEach { el ->
                val text = el.ownText().trim().ifBlank { el.text().trim() }
                if (text.isNotBlank()) candidates.add(text)
            }
        }

        runCatching {
            val title = doc.title().trim()
            if (title.isNotBlank()) {
                val cleanedTitle = title
                    .replace(Regex("\\s+[-|/•·]\\s+.*$"), "")
                    .replace(Regex("\\s*\\(@[a-zA-Z0-9._-]+\\).*$"), "")
                    .trim()
                if (cleanedTitle.isNotBlank()) candidates.add(cleanedTitle)
            }
        }

        for (candidate in candidates) {
            val cleaned = cleanText(candidate, MAX_DISPLAY_NAME_CHARS) ?: continue
            if (cleaned.lowercase(Locale.ROOT) in GENERIC_NAMES) continue
            if (cleaned.length < 2) continue
            return cleaned
        }
        return null
    }

    private fun extractBio(doc: Document): String? {
        val candidates = mutableListOf<String>()

        runCatching {
            doc.select(
                "meta[name=description],meta[property=og:description],meta[name=twitter:description]"
            ).forEach { el ->
                val content = el.attr("content").trim()
                if (content.isNotBlank()) candidates.add(content)
            }
        }

        runCatching {
            doc.select(
                "[itemprop=description],[itemprop=about]," +
                    "[class*=user-profile-bio],[class*=profile-bio],[class*=about-me]," +
                    "[class*=user-description],[class*=bio-content],.p-note,.note"
            ).forEach { el ->
                val text = el.text().trim()
                if (text.isNotBlank()) candidates.add(text)
            }
        }

        for (candidate in candidates) {
            val cleaned = cleanText(candidate, MAX_BIO_CHARS) ?: continue
            if (cleaned.length < 3) continue
            val lower = cleaned.lowercase(Locale.ROOT)
            if (lower.startsWith("create an account or log in") ||
                lower.startsWith("log in or sign up") ||
                lower.startsWith("twitter is an open") ||
                lower.startsWith("follow their code on github") ||
                lower.startsWith("join github today") ||
                lower.startsWith("see instagram photos")
            ) {
                continue
            }
            return cleaned
        }
        return null
    }

    private fun extractUsername(doc: Document): String? {
        val candidates = mutableListOf<String>()

        runCatching {
            doc.select(
                "meta[name=twitter:creator],meta[property=profile:username]," +
                    "meta[name=profile:username],meta[name=twitter:site]"
            ).forEach { el ->
                val content = el.attr("content").trim().removePrefix("@")
                if (content.isNotBlank()) candidates.add(content)
            }
        }

        runCatching {
            doc.select(
                "[itemprop=additionalName],.vcard .p-nickname,.vcard .nickname," +
                    "[class*=screen-name],[class*=user-handle],[class*=profile-username]"
            ).forEach { el ->
                val text = el.ownText().trim().ifBlank { el.text().trim() }.removePrefix("@")
                if (text.isNotBlank()) candidates.add(text)
            }
        }

        for (candidate in candidates) {
            val cleaned = cleanText(candidate, MAX_USERNAME_CHARS)?.removePrefix("@") ?: continue
            if (cleaned.lowercase(Locale.ROOT) in GENERIC_USERNAMES) continue
            if (USERNAME_PATTERN.matches(cleaned)) {
                return cleaned
            }
        }
        return null
    }

    private fun extractAvatarUrl(doc: Document, baseUrl: String?): String? {
        val candidates = mutableListOf<String>()

        runCatching {
            doc.select(
                "meta[property=og:image],meta[property=og:image:url]," +
                    "meta[name=twitter:image],meta[name=twitter:image:src]," +
                    "link[rel=image_src]"
            ).forEach { el ->
                val content = el.attr("content").ifBlank { el.attr("href") }.trim()
                if (content.isNotBlank()) candidates.add(content)
            }
        }

        runCatching {
            doc.select(
                "[itemprop=image],img[class*=avatar],img[class*=profile-photo]," +
                    "img[class*=profile-image],img[class*=user-avatar],.vcard img.u-photo,.vcard img.photo"
            ).forEach { el ->
                val src = el.attr("abs:src").ifBlank { el.attr("src") }.trim()
                if (src.isNotBlank()) candidates.add(src)
            }
        }

        for (candidate in candidates) {
            val validated = validateAndNormalizeUrl(candidate, baseUrl) ?: continue
            val lower = validated.lowercase(Locale.ROOT)
            if (lower.endsWith(".ico") ||
                lower.endsWith(".svg") ||
                lower.contains("default_avatar") ||
                lower.contains("default_profile") ||
                lower.contains("favicon") ||
                lower.contains("placeholder") ||
                lower.contains("1x1")
            ) {
                continue
            }
            return validated
        }
        return null
    }

    private fun extractOrganization(doc: Document): String? {
        val candidates = mutableListOf<String>()

        runCatching {
            doc.select(
                "meta[property=profile:organization],meta[name=organization],meta[name=institution]"
            ).forEach { el ->
                val content = el.attr("content").trim()
                if (content.isNotBlank()) candidates.add(content)
            }
        }

        runCatching {
            doc.select(
                "[itemprop=worksFor],[itemprop=affiliation],[itemprop=sourceOrganization]," +
                    "[class*=user-organization],[class*=profile-organization]," +
                    "[class*=user-workplace],.vcard .p-org,.vcard .org"
            ).forEach { el ->
                val text = el.ownText().trim().ifBlank { el.text().trim() }
                if (text.isNotBlank()) candidates.add(text)
            }
        }

        for (candidate in candidates) {
            val cleaned = cleanText(candidate, MAX_ORGANIZATION_CHARS) ?: continue
            if (cleaned.length < 2) continue
            return cleaned
        }
        return null
    }

    private fun extractLocation(doc: Document): String? {
        val candidates = mutableListOf<String>()

        runCatching {
            doc.select(
                "meta[name=geo.placename],meta[name=location],meta[property=place:location:name]"
            ).forEach { el ->
                val content = el.attr("content").trim()
                if (content.isNotBlank()) candidates.add(content)
            }
        }

        runCatching {
            doc.select(
                "[itemprop=homeLocation],[itemprop=addressLocality],[itemprop=location]," +
                    "[itemprop=address],[class*=user-location],[class*=profile-location]," +
                    ".vcard .p-locality,.vcard .p-adr,.vcard .locality"
            ).forEach { el ->
                val text = el.ownText().trim().ifBlank { el.text().trim() }
                if (text.isNotBlank()) candidates.add(text)
            }
        }

        for (candidate in candidates) {
            val cleaned = cleanText(candidate, MAX_LOCATION_CHARS) ?: continue
            if (cleaned.length < 2) continue
            return cleaned
        }
        return null
    }

    private fun extractExternalLinks(
        doc: Document,
        baseUrl: String?,
        originalUrl: String?
    ): List<String> {
        val rawLinks = mutableListOf<String>()

        runCatching {
            doc.select(
                "a[rel*=me],a[rel*=external],[itemprop=url] a,a[itemprop=url]," +
                    "[class*=profile-link] a,[class*=user-website] a,[class*=social-link] a," +
                    ".vcard a.u-url,.vcard a.url,[class*=user-url] a"
            ).forEach { el ->
                // Validate the authored href before Jsoup's absolute-URL
                // resolver can normalize away userinfo or archive replay
                // markers. Relative links are resolved by
                // validateAndNormalizeUrl using the explicit base URL.
                val href = el.attr("href").ifBlank { el.attr("abs:href") }.trim()
                if (href.isNotBlank()) rawLinks.add(href)
            }
        }

        val originalComparable = originalUrl?.let(::normalizeForComparison)
        val results = mutableListOf<String>()

        for (raw in rawLinks) {
            if (isArchiveUrl(raw)) continue
            val validated = validateAndNormalizeUrl(raw, baseUrl) ?: continue
            val comparable = normalizeForComparison(validated)
            if (originalComparable != null && comparable == originalComparable) continue

            val uri = runCatching { URI(validated) }.getOrNull() ?: continue
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            if (!results.any { normalizeForComparison(it) == comparable }) {
                results.add(validated)
                if (results.size >= MAX_EXTERNAL_LINKS) break
            }
        }

        return results
    }

    internal fun unwrapWaybackUrl(raw: String): String {
        val trimmed = raw.trim()
        val marker = trimmed.indexOf("web.archive.org/web/", ignoreCase = true)
        if (marker >= 0) {
            val after = trimmed.substring(marker + "web.archive.org/web/".length)
            val firstSlash = after.indexOf('/')
            if (firstSlash >= 0) {
                val target = after.substring(firstSlash + 1).trim()
                if (target.startsWith("http://", ignoreCase = true) ||
                    target.startsWith("https://", ignoreCase = true)
                ) {
                    return target
                }
                val httpIdx = target.indexOf("http://", ignoreCase = true)
                val httpsIdx = target.indexOf("https://", ignoreCase = true)
                val cleanIdx = when {
                    httpIdx >= 0 && httpsIdx >= 0 -> minOf(httpIdx, httpsIdx)
                    httpIdx >= 0 -> httpIdx
                    httpsIdx >= 0 -> httpsIdx
                    else -> -1
                }
                if (cleanIdx >= 0) {
                    return target.substring(cleanIdx)
                }
            }
        }
        return trimmed
    }

    internal fun validateAndNormalizeUrl(raw: String?, baseUrl: String? = null): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("mailto:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("file:", ignoreCase = true) ||
            trimmed.startsWith("#")
        ) {
            return null
        }

        val unwrapped = unwrapWaybackUrl(trimmed)
        if (unwrapped.length > MAX_URL_CHARS) return null

        val resolved = if (baseUrl != null &&
            !unwrapped.startsWith("http://", ignoreCase = true) &&
            !unwrapped.startsWith("https://", ignoreCase = true)
        ) {
            val cleanBase = unwrapWaybackUrl(baseUrl)
            runCatching {
                val baseUri = URI(cleanBase)
                baseUri.resolve(unwrapped).toString()
            }.getOrNull() ?: return null
        } else {
            unwrapped
        }

        val uri = runCatching { URI(resolved) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https")) return null

        val host = uri.host?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (host.isBlank() || host.contains(" ") || host.contains("\t") || host.contains("\n")) return null

        // Strictly reject userinfo / credentials in URLs
        if (!uri.rawUserInfo.isNullOrBlank()) return null

        if (uri.port != -1 && (uri.port <= 0 || uri.port > 65535)) return null

        return resolved.substringBefore('#')
    }

    private fun isArchiveUrl(raw: String): Boolean = runCatching {
        val unwrapped = raw.trim()
        val uri = URI(unwrapped)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        host == "web.archive.org" ||
            host.endsWith(".web.archive.org") ||
            host == "archive.org" ||
            host.endsWith(".archive.org") ||
            host == "archive.today" ||
            host.endsWith(".archive.today") ||
            host == "archive.is" ||
            host.endsWith(".archive.is") ||
            host == "archive.ph" ||
            host.endsWith(".archive.ph") ||
            host == "webcitation.org" ||
            host.endsWith(".webcitation.org")
    }.getOrDefault(false)

    private fun cleanText(raw: String?, maxChars: Int): String? {
        if (raw.isNullOrBlank()) return null
        val collapsed = raw
            .replace('\u00A0', ' ')
            .replace(Regex("[\\p{Cntrl}&&[^\r\n\t]]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (collapsed.isBlank()) return null
        return collapsed.take(maxChars).trim()
    }

    private fun normalizeForComparison(raw: String): String = runCatching {
        val uri = URI(raw.trim().substringBefore('#'))
        val host = uri.host?.removePrefix("www.")?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "$host$path$query"
    }.getOrDefault(raw.trim().lowercase(Locale.ROOT).trimEnd('/'))
}
