package io.dossier.app.data.web

import io.dossier.app.domain.model.IdentityInput
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
 * and that an identity signal is present on the actual page.
 */
internal class PublicPageVerifier(
    private val client: OkHttpClient = defaultClient()
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
                        return@withContext Outcome.Rejected("Source page no longer exists")
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
                val combined = listOf(title, description, text, indexedTitle, indexedSnippet)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")

                val assessment = assessIdentitySignals(input, finalUrl, combined)
                if (assessment.directScore <= 0f) {
                    return@withContext Outcome.Rejected("Direct source contains no matching identity signal")
                }

                val snippet = description.ifBlank { text.take(360) }.ifBlank { indexedSnippet }.take(360)
                Outcome.Verified(
                    finalUrl = finalUrl,
                    title = title.take(180),
                    snippet = snippet,
                    directScore = assessment.directScore,
                    confidenceCeiling = assessment.confidenceCeiling,
                    signals = assessment.signals
                )
            }
        } catch (e: Exception) {
            Outcome.Unavailable(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    data class IdentityAssessment(
        val directScore: Float,
        val confidenceCeiling: Float,
        val signals: List<String>
    )

    companion object {
        private const val MAX_BODY_BYTES = 2_000_000L
        private const val MAX_TEXT_CHARS = 8_000
        private const val USER_AGENT =
            "Dossier/0.1 public-self-audit (+https://github.com/palaashatri/dossier)"

        /** Pure scoring function so identity-attribution behaviour is regression-testable. */
        fun assessIdentitySignals(
            input: IdentityInput,
            url: String,
            pageText: String
        ): IdentityAssessment {
            val normalizedText = pageText.lowercase()
            val signals = mutableListOf<String>()
            var score = 0f
            var strongCategories = 0

            val explicitUrl = input.profileUrls.any {
                canonical(it) == canonical(url)
            }
            if (explicitUrl) {
                score += 0.95f
                strongCategories++
                signals += "Source URL was explicitly supplied"
            }

            val handles = (listOfNotNull(input.primaryUsername) + input.usernames)
                .map { it.trim().removePrefix("@").lowercase() }
                .filter { it.length >= 2 }
                .distinct()
            val handleInPath = handles.firstOrNull { handleAppearsInPath(url, it) }
            if (handleInPath != null) {
                score += 0.50f
                strongCategories++
                signals += "Exact supplied handle appears in source URL"
            }
            if (handles.any { containsToken(normalizedText, it) }) {
                score += 0.14f
                signals += "Supplied handle appears in source content"
            }

            val emails = input.emails.map { it.trim().lowercase() }.filter { it.contains('@') }
            if (emails.any(normalizedText::contains)) {
                score += 0.72f
                strongCategories++
                signals += "Exact email appears on source page"
            }

            val pageDigits = normalizedText.filter(Char::isDigit)
            val phones = input.phones
                .map { it.filter(Char::isDigit) }
                .filter { it.length >= 8 }
            if (phones.any(pageDigits::contains)) {
                score += 0.70f
                strongCategories++
                signals += "Exact phone number appears on source page"
            }

            val name = input.fullName.trim().lowercase()
            val nameParts = name.split("\\s+".toRegex()).filter { it.length >= 3 }
            val fullNameMatch = nameParts.size >= 2 && normalizedText.contains(name)
            val bothNameParts = nameParts.size >= 2 && nameParts.all(normalizedText::contains)
            if (fullNameMatch) {
                score += 0.34f
                strongCategories++
                signals += "Full name appears on source page"
            } else if (bothNameParts) {
                score += 0.24f
                strongCategories++
                signals += "First and last name both appear on source page"
            }

            val aliases = input.aliases
                .map { it.trim().removePrefix("@").lowercase() }
                .filter { it.length >= 3 }
            if (aliases.any { containsToken(normalizedText, it) }) {
                score += 0.18f
                signals += "Known alias appears on source page"
            }

            if (input.organizations.any { it.trim().length >= 3 && normalizedText.contains(it.trim().lowercase()) }) {
                score += 0.12f
                signals += "Known organization appears on source page"
            }
            if (input.locations.any { it.trim().length >= 3 && normalizedText.contains(it.trim().lowercase()) }) {
                score += 0.08f
                signals += "Known location appears on source page"
            }

            score = score.coerceIn(0f, 1f)
            val ceiling = when {
                explicitUrl -> 0.99f
                emails.any(normalizedText::contains) || phones.any(pageDigits::contains) -> 0.97f
                handleInPath != null && strongCategories >= 2 -> 0.95f
                handleInPath != null -> 0.82f
                (fullNameMatch || bothNameParts) && signals.size >= 2 -> 0.86f
                fullNameMatch || bothNameParts -> 0.60f
                else -> 0.48f
            }
            return IdentityAssessment(score, ceiling, signals.distinct())
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
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
