package io.dossier.app.data.web

import android.content.Context
import io.dossier.app.domain.discovery.ProviderDefinition
import io.dossier.app.domain.discovery.ProviderExecutionRuntime
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URL

/**
 * Follows a confirmed personal website to harvest additional self-disclosed
 * handles and links — the "Deep Research" extension inspired by
 * github.com/ni5arga/deanonymizer.
 *
 * Given a site URL linked from a confirmed profile, this fetches the site's
 * root page plus a small number of same-origin "self-disclosure" sub-pages
 * (about / resume / cv / contact / bio / portfolio / me) and aggregates their
 * visible text and outbound links. Downstream extractors can then mine that
 * aggregate for handles the user volunteered on their own site.
 *
 * Design constraints (mirrors ProfileScanner):
 *  - OkHttp + Jsoup only. No headless browser / WebView — keeps the follow
 *    lightweight and matches the deanonymizer approach.
 *  - 5s per-page connect/read timeout.
 *  - At most 6 HTTP fetches total (root + up to 5 sub-pages).
 *  - Every fetch is individually try/caught so one bad page never aborts the
 *    whole follow.
 *  - Only same-origin sub-pages are followed (host must equal the root's host).
 *
 * PRIVACY: this only reads public HTML the user published about themselves on
 * their own site. No telemetry, no third-party reporting.
 */
class WebsiteLinkFollower(
    @Suppress("UNUSED_PARAMETER") private val context: Context?,
    private val providerRuntime: ProviderExecutionRuntime = ProviderExecutionRuntime()
) {

    /** Result of following a site: aggregate visible text + outbound links. */
    data class FollowedSite(val url: String, val text: String, val links: List<String>)

    /**
     * Fetches [siteUrl] (root) and up to [MAX_SUB_PAGES] same-origin self-
     * disclosure sub-pages, aggregating their text and links.
     *
     * On root-page failure (non-2xx, exception, offline) returns a FollowedSite
     * with empty text/links — we never guess content we couldn't fetch.
     * Sub-page failures are swallowed: a single bad sub-page is skipped.
     */
    suspend fun follow(siteUrl: String, scanId: ScanId): FollowedSite = withContext(Dispatchers.IO) {
        val normalizedRoot = normalizeUrl(siteUrl)
        val rootHost = try {
            URL(normalizedRoot).host?.lowercase()
        } catch (e: Exception) {
            null
        }
        if (rootHost.isNullOrBlank()) {
            return@withContext FollowedSite(siteUrl, "", emptyList())
        }
        val provider = ProviderExecutionRuntime.uncataloguedProfileDefinition(normalizedRoot)
            ?: return@withContext FollowedSite(siteUrl, "", emptyList())

        val aggregateText = StringBuilder()
        val aggregateLinks = mutableListOf<String>()
        val visited = mutableSetOf(normalizedRoot.lowercase())

        // ---- Root page ----------------------------------------------------
        val rootResult = fetchPage(normalizedRoot, provider, scanId)
        if (rootResult == null) {
            return@withContext FollowedSite(siteUrl, "", emptyList())
        }
        aggregateText.append(rootResult.text)
        aggregateLinks.addAll(rootResult.links)

        // ---- Same-origin self-disclosure sub-pages ------------------------
        // Discovered via root links (deanonymizer-style) — we never synthesize
        // paths like "/about" that weren't actually linked, which would risk
        // hammering a stranger's site.
        val subPageCandidates = rootResult.links
            .asSequence()
            .mapNotNull { raw -> runCatching { URL(raw) }.getOrNull() }
            .filter { link ->
                link.host?.lowercase() == rootHost &&
                    link.protocol.startsWith("http", ignoreCase = true) &&
                    pathHasPriorityKeyword(link.path)
            }
            .map { it.toExternalForm() }
            .filter { it.lowercase() !in visited }
            .distinctBy { it.lowercase() }
            .take(MAX_SUB_PAGES)
            .toList()

        subPageCandidates.forEach { subUrl ->
            visited.add(subUrl.lowercase())
            val subResult = fetchPage(subUrl, provider, scanId)
            if (subResult != null) {
                if (aggregateText.isNotEmpty()) aggregateText.append('\n')
                aggregateText.append(subResult.text)
                aggregateLinks.addAll(subResult.links)
            }
        }

        FollowedSite(
            url = siteUrl,
            text = aggregateText.toString().trim(),
            links = aggregateLinks.distinct()
        )
    }

    /**
     * Fetches a single page; returns null on any failure (non-2xx, exception,
     * offline). Never throws — one bad page must not abort the whole follow.
     */
    private suspend fun fetchPage(
        url: String,
        provider: ProviderDefinition,
        scanId: ScanId
    ): PageFetch? {
        // Every page is a bounded provider operation. The provider ID is
        // derived from the host and remains opaque at the coordinator edge;
        // the requested URL never enters lifecycle events.
        ScanCoordinatorRuntime.onProviderQueued(provider.id, scanId)
        val execution = try {
            providerRuntime.execute(provider, url, scanId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (execution.decision.state != ProviderVerificationState.Present) return null

        val html = execution.bodyText
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html, url)
        val text = doc.text()
        val links = mutableListOf<String>()
        doc.select("a[href]").forEach { element ->
            val linkUrl = element.attr("abs:href")
            if (linkUrl.startsWith("http", ignoreCase = true)) {
                links.add(linkUrl)
            }
        }
        return PageFetch(text = text, links = links)
    }

    private data class PageFetch(val text: String, val links: List<String>)

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "https://$trimmed"
    }

    /**
     * True if [path] mentions one of the self-disclosure keywords as a path
     * *token* (segment or "-" / "_" sub-token, extension stripped). Tokenizing
     * stops the short keyword "me" from matching "home"/"media"/"images".
     */
    private fun pathHasPriorityKeyword(path: String): Boolean {
        val lowerPath = path.lowercase()
        if (lowerPath.isBlank()) return false
        val segments = lowerPath.split('/').filter { it.isNotBlank() }
        for (segment in segments) {
            val base = segment.substringBeforeLast('.', segment)
            val tokens = base.split('-', '_')
            for (token in tokens) {
                if (PRIORITY_PATH_KEYWORDS.contains(token)) return true
            }
        }
        return false
    }

    companion object {
        private const val MAX_SUB_PAGES = 5
        private val PRIORITY_PATH_KEYWORDS = setOf(
            "about", "resume", "cv", "contact", "bio", "portfolio", "me"
        )
    }
}
