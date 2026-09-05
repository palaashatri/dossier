package io.dossier.app.data.web

import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Re-fetches indexed search results before Dossier gives them meaningful confidence.
 * Search snippets are useful leads, but they are stale, truncated, and occasionally
 * associated with the wrong URL. This verifier confirms that the source still exists
 * and that independently corroborating identity signals are present on the actual page.
 *
 * A matching handle or name by itself can prove that an account/page exists, but it is
 * not sufficient to prove that the account belongs to the audited person. Exact URLs
 * supplied by the user, exact email/phone matches, or corroborated multi-signal matches
 * can qualify attribution.
 *
 * When a live page is definitively deleted, replaced, or lacks enough current attribution
 * evidence, the verifier performs one exact-URL archive lookup. A matching archive capture
 * is retained as explicitly historical evidence with a lower confidence ceiling; it is
 * never treated as proof that the profile or page is currently active.
 */
internal class PublicPageVerifier(
    private val client: OkHttpClient = defaultClient(),
    private val archiveResolver: ArchivePageResolver = ArchivePageResolver()
) {
    sealed class Outcome {
        data class Verified(
            val finalUrl: String,
            val title: String,
            val snippet: String,
            val directScore: Float,
            val confidenceCeiling: Float,
            val signals: List<String>
        ) : Outcome()

        data class Rejected(val reason: String) : Outcome()
        data class Unavailable(val reason: String) : Outcome()
    }

    suspend fun verify(
        input: IdentityInput,
        url: String,
        indexedTitle: String,
        indexedSnippet: String
    ): Outcome = withContext(Dispatchers.IO) {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.8,*/*;q=0.4")
                .header("Accept-Language", "en-US,en;q=0.8")
                .build()
        }.getOrElse { return@withContext Outcome.Rejected("Invalid source URL") }

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 || response.code == 410 ->
                        return@withContext verifyArchivedVersion(
                            input = input,
                            originalUrl = url,
                            indexedTitle = indexedTitle,
                            indexedSnippet = indexedSnippet,
                            noArchiveReason = "Source page no longer exists"
                        )
                    response.code == 401 || response.code == 403 || response.code == 429 ->
                        return@withContext Outcome.Unavailable("Source requires authentication or rate-limited access")
                    !response.isSuccessful ->
                        return@withContext Outcome.Unavailable("Source returned HTTP ${response.code}")
                }

                val contentLength = response.body?.contentLength() ?: -1L
                if (contentLength > MAX_BODY_BYTES) {
                    return@withContext Outcome.Unavailable("Source page exceeds verification size limit")
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext Outcome.Unavailable("Source returned an empty page")
                if (DiscoveryHttpPolicy.looksBlocked(body)) {
                    return@withContext Outcome.Unavailable("Source presented a challenge page")
                }

                val finalUrl = response.request.url.toString()
                val document = Jsoup.parse(body, finalUrl)
                document.select("script,style,noscript,svg,template").remove()
                val title = document.title().trim().ifBlank { indexedTitle.trim() }
                val description = document
                    .select("meta[name=description],meta[property=og:description],meta[name=twitter:description]")
                    .firstOrNull()
                    ?.attr("content")
                    ?.trim()
                    .orEmpty()
                val text = document.body()?.text()?.trim().orEmpty().take(MAX_TEXT_CHARS)
                val directContent = listOf(title, description, text)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val combined = listOf(directContent, indexedTitle, indexedSnippet)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")

                val assessment = assessIdentitySignals(input, finalUrl, combined)
                if (assessment.directScore <= 0f) {
                    return@withContext verifyArchivedVersion(
                        input = input,
                        originalUrl = url,
                        indexedTitle = indexedTitle,
                        indexedSnippet = indexedSnippet,
                        noArchiveReason = "Direct source contains no matching identity signal"
                    )
                }
                if (!assessment.verificationQualified) {
                    return@withContext verifyArchivedVersion(
                        input = input,
                        originalUrl = url,
                        indexedTitle = indexedTitle,
                        indexedSnippet = indexedSnippet,
                        noArchiveReason = "Direct source exists but attribution is not independently corroborated"
                    )
                }

                val snippet = description.ifBlank { text.take(360) }.ifBlank { indexedSnippet }.take(360)
                Outcome.Verified(
                    finalUrl = finalUrl,
                    title = title.take(180),
                    snippet = snippet,
                    directScore = assessment.directScore,
                    confidenceCeiling = assessment.confidenceCeiling,
                    signals = assessment.signals + "Independent attribution threshold satisfied"
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Outcome.Unavailable(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    private suspend fun verifyArchivedVersion(
        input: IdentityInput,
        originalUrl: String,
        indexedTitle: String,
        indexedSnippet: String,
        noArchiveReason: String
    ): Outcome = when (val archive = archiveResolver.resolveExactUrl(originalUrl)) {
        is ArchivePageResolver.Result.Found -> {
            // Do not use the search snippet to establish attribution: the archive
            // capture itself must independently expose enough identity signal.
            val archiveContent = listOf(archive.title, archive.description, archive.text)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            val assessment = assessIdentitySignals(input, archive.originalUrl, archiveContent)
            if (assessment.directScore <= 0f || !assessment.verificationQualified) {
                Outcome.Rejected("$noArchiveReason; archived capture did not independently corroborate attribution")
            } else {
                val date = ArchivePageResolver.displayTimestamp(archive.timestamp)
                val archiveSnippet = archive.description
                    .ifBlank { archive.text.take(300) }
                    .ifBlank { indexedSnippet }
                    .take(320)
                Outcome.Verified(
                    finalUrl = archive.snapshotUrl,
                    title = archive.title.ifBlank { indexedTitle }.take(180),
                    snippet = "Historical capture ($date): $archiveSnippet".take(360),
                    directScore = (assessment.directScore * HISTORICAL_SCORE_FACTOR).coerceIn(0f, 1f),
                    confidenceCeiling = historicalConfidenceCeiling(assessment.confidenceCeiling),
                    signals = listOf(
                        "Historical evidence only — live page is deleted, unavailable, replaced, or insufficiently attributed",
                        "Verified against ${archive.provider} capture dated $date",
                        "Original URL: ${archive.originalUrl}",
                        "Independent attribution threshold satisfied on archived content"
                    ) + assessment.signals
                )
            }
        }
        ArchivePageResolver.Result.NotFound ->
            Outcome.Rejected("$noArchiveReason; no accessible archive capture was found")
        is ArchivePageResolver.Result.Unavailable ->
            Outcome.Unavailable("$noArchiveReason; archive lookup unavailable: ${archive.reason}")
    }

    data class IdentityAssessment(
        val directScore: Float,
        val confidenceCeiling: Float,
        val signals: List<String>,
        val verificationQualified: Boolean
    )

    companion object {
        private const val MAX_BODY_BYTES = 2_000_000L
        private const val MAX_TEXT_CHARS = 8_000
        private const val USER_AGENT =
            "Dossier/0.1 public-self-audit"
        private const val HISTORICAL_CONFIDENCE_CEILING = 0.78f
        private const val HISTORICAL_SCORE_FACTOR = 0.90f

        internal fun historicalConfidenceCeiling(directCeiling: Float): Float =
            directCeiling.coerceAtMost(HISTORICAL_CONFIDENCE_CEILING)

        /** Pure scoring function so identity-attribution behaviour is regression-testable. */
        fun assessIdentitySignals(
            input: IdentityInput,
            url: String,
            pageText: String
        ): IdentityAssessment {
            val normalizedText = pageText.lowercase()
            val signals = mutableListOf<String>()
            var score = 0f

            val explicitUrl = input.profileUrls.any {
                canonical(it) == canonical(url)
            }
            if (explicitUrl) {
                score += 0.95f
                signals += "Source URL was explicitly supplied"
            }

            val handles = (listOfNotNull(input.primaryUsername) + input.usernames)
                .map { it.trim().removePrefix("@").lowercase() }
                .filter { it.length >= 2 }
                .distinct()
            val handleInPath = handles.firstOrNull { handleAppearsInPath(url, it) }
            if (handleInPath != null) {
                score += 0.50f
                signals += "Exact supplied handle appears in source URL"
            }
            val handleInContent = handles.any { containsToken(normalizedText, it) }
            if (handleInContent) {
                score += 0.14f
                signals += "Supplied handle appears in source content"
            }

            val emails = input.emails.map { it.trim().lowercase() }.filter { it.contains('@') }
            val exactEmailMatch = emails.any(normalizedText::contains)
            if (exactEmailMatch) {
                score += 0.72f
                signals += "Exact email appears on source page"
            }

            val pageDigits = normalizedText.filter(Char::isDigit)
            val phones = input.phones
                .map { it.filter(Char::isDigit) }
                .filter { it.length >= 8 }
            val exactPhoneMatch = phones.any(pageDigits::contains)
            if (exactPhoneMatch) {
                score += 0.70f
                signals += "Exact phone number appears on source page"
            }

            val name = input.fullName.trim().lowercase()
            val nameParts = name.split("\\s+".toRegex()).filter { it.length >= 3 }
            val fullNameMatch = nameParts.size >= 2 && normalizedText.contains(name)
            val bothNameParts = nameParts.size >= 2 && nameParts.all(normalizedText::contains)
            val nameMatch = fullNameMatch || bothNameParts
            if (fullNameMatch) {
                score += 0.34f
                signals += "Full name appears on source page"
            } else if (bothNameParts) {
                score += 0.24f
                signals += "First and last name both appear on source page"
            }

            val aliases = input.aliases
                .map { it.trim().removePrefix("@").lowercase() }
                .filter { it.length >= 3 }
            val independentAliasMatch = aliases.any { alias ->
                alias != handleInPath && containsToken(normalizedText, alias)
            }
            if (independentAliasMatch) {
                score += 0.18f
                signals += "Known independent alias appears on source page"
            }

            val organizationMatch = input.organizations.any {
                it.trim().length >= 3 && normalizedText.contains(it.trim().lowercase())
            }
            if (organizationMatch) {
                score += 0.12f
                signals += "Known organization appears on source page"
            }
            val locationMatch = input.locations.any {
                it.trim().length >= 3 && normalizedText.contains(it.trim().lowercase())
            }
            if (locationMatch) {
                score += 0.08f
                signals += "Known location appears on source page"
            }

            val contextualCorroborators = listOf(
                nameMatch,
                independentAliasMatch,
                organizationMatch,
                locationMatch
            ).count { it }

            val verificationQualified = when {
                explicitUrl -> true
                exactEmailMatch || exactPhoneMatch -> true
                handleInPath != null && contextualCorroborators >= 1 -> true
                nameMatch && (independentAliasMatch || organizationMatch || locationMatch) -> true
                else -> false
            }

            score = score.coerceIn(0f, 1f)
            val ceiling = when {
                explicitUrl -> 0.99f
                exactEmailMatch || exactPhoneMatch -> 0.97f
                handleInPath != null && contextualCorroborators >= 2 -> 0.95f
                handleInPath != null && contextualCorroborators == 1 -> 0.88f
                nameMatch && (organizationMatch || locationMatch || independentAliasMatch) -> 0.82f
                nameMatch -> 0.60f
                handleInPath != null -> 0.58f
                else -> 0.48f
            }
            return IdentityAssessment(
                directScore = score,
                confidenceCeiling = ceiling,
                signals = signals.distinct(),
                verificationQualified = verificationQualified
            )
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .dns(DiscoveryHttpPolicy.PUBLIC_DNS)
            .addNetworkInterceptor(DiscoveryHttpPolicy.PUBLIC_URL_INTERCEPTOR)
            .build()

        private fun handleAppearsInPath(url: String, handle: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val segments = uri.path.orEmpty().trim('/').split('/')
                .map { it.removePrefix("@").lowercase() }
            if (segments.any { it == handle }) return true
            return uri.rawQuery.orEmpty().split('&')
                .any { it.substringAfter('=', "").removePrefix("@").equals(handle, true) }
        }

        private fun containsToken(text: String, token: String): Boolean {
            if (token.isBlank()) return false
            val index = text.indexOf(token)
            if (index < 0) return false
            val beforeOk = index == 0 || !text[index - 1].isLetterOrDigit()
            val end = index + token.length
            val afterOk = end >= text.length || !text[end].isLetterOrDigit()
            return beforeOk && afterOk
        }

        private fun canonical(raw: String): String {
            var value = raw.trim()
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
                value = "https://$value"
            }
            return runCatching {
                val uri = URI(value)
                val host = uri.host?.removePrefix("www.")?.lowercase().orEmpty()
                val path = uri.path.orEmpty().trimEnd('/').lowercase()
                "$host$path"
            }.getOrDefault(value.lowercase().trimEnd('/'))
        }
    }
}
