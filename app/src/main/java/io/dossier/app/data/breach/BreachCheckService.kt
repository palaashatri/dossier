package io.dossier.app.data.breach

import android.content.Context
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.breach.EmailBreach
import io.dossier.app.domain.breach.EmailExposureResult
import io.dossier.app.domain.breach.HibpCoverage
import io.dossier.app.domain.breach.PasswordExposureResult
import io.dossier.app.domain.breach.PublicEmailEvidence
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Safe breach and public-exposure checks.
 *
 * HIBP account coverage and ordinary web exposure are always represented as
 * separate channels. A missing API key can never be mistaken for a confirmed
 * zero-breach result.
 */
class BreachCheckService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun checkPasswords(passwords: List<String>): List<PasswordExposureResult> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                passwords.mapIndexedNotNull { index, password ->
                    password.takeIf { it.isNotBlank() }?.let {
                        async { checkPassword(it, "Password ${index + 1}") }
                    }
                }.awaitAll()
            }
        }

    suspend fun checkPassword(password: String, label: String): PasswordExposureResult =
        withContext(Dispatchers.IO) {
            val sha1 = sha1Hex(password)
            val prefix = sha1.take(5)
            val suffix = sha1.drop(5)
            try {
                val request = Request.Builder()
                    .url("https://api.pwnedpasswords.com/range/$prefix")
                    .header("User-Agent", USER_AGENT)
                    .header("Add-Padding", "true")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext PasswordExposureResult(
                            label, false, 0, prefix,
                            "Pwned Passwords lookup failed: HTTP ${response.code}"
                        )
                    }
                    val count = parsePwnedPasswordRange(response.body?.string().orEmpty(), suffix)
                    PasswordExposureResult(label, count > 0, count, prefix)
                }
            } catch (error: Exception) {
                PasswordExposureResult(
                    label, false, 0, prefix,
                    "Pwned Passwords lookup failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
                )
            }
        }

    suspend fun checkEmails(
        emails: List<String>,
        hibpApiKey: String? = null,
        deepResearch: Boolean = false
    ): List<EmailExposureResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            emails.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .map { email -> async { checkEmail(email, hibpApiKey, deepResearch) } }
                .awaitAll()
        }
    }

    suspend fun checkEmail(
        email: String,
        hibpApiKey: String? = null,
        deepResearch: Boolean = false
    ): EmailExposureResult = withContext(Dispatchers.IO) {
        val hibp = fetchHibpBreaches(email, hibpApiKey)
        val publicEvidence = fetchPublicEmailEvidence(email, deepResearch)
        EmailExposureResult(
            email = email,
            breaches = hibp.breaches,
            publicEvidence = publicEvidence,
            hibpCoverage = hibp.coverage,
            error = hibp.error
        )
    }

    private fun fetchHibpBreaches(email: String, hibpApiKey: String?): HibpFetchResult {
        val key = hibpApiKey?.trim().orEmpty()
        if (key.isBlank()) {
            return HibpFetchResult(
                breaches = emptyList(),
                coverage = HibpCoverage.NotConfigured,
                error = "HIBP account coverage was not run because no API key is configured. Public web exposure search ran separately."
            )
        }

        return try {
            val request = Request.Builder()
                .url("https://haveibeenpwned.com/api/v3/breachedaccount/${urlEncode(email)}?truncateResponse=false")
                .header("User-Agent", USER_AGENT)
                .header("hibp-api-key", key)
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val breaches = parseHibpBreaches(response.body?.string().orEmpty())
                        HibpFetchResult(
                            breaches,
                            if (breaches.isEmpty()) HibpCoverage.ConfirmedNoBreaches else HibpCoverage.ConfirmedBreaches,
                            null
                        )
                    }
                    404 -> HibpFetchResult(emptyList(), HibpCoverage.ConfirmedNoBreaches, null)
                    401, 403 -> HibpFetchResult(
                        emptyList(),
                        HibpCoverage.CredentialsRejected,
                        "HIBP API credentials were rejected."
                    )
                    429 -> HibpFetchResult(
                        emptyList(),
                        HibpCoverage.RateLimited,
                        "HIBP rate limit reached. Try again later."
                    )
                    else -> HibpFetchResult(
                        emptyList(),
                        HibpCoverage.Unavailable,
                        "HIBP lookup failed: HTTP ${response.code}"
                    )
                }
            }
        } catch (error: Exception) {
            HibpFetchResult(
                emptyList(),
                HibpCoverage.Unavailable,
                "HIBP lookup failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    private suspend fun fetchPublicEmailEvidence(
        email: String,
        deepResearch: Boolean
    ): List<PublicEmailEvidence> {
        val service = PublicSearchDiscoveryService(context)
        return service.discover(
            IdentityInput(fullName = "", emails = listOf(email)),
            deepResearch = deepResearch
        ).map {
            PublicEmailEvidence(
                title = it.title,
                snippet = it.snippet,
                url = it.url,
                source = it.source,
                confidence = it.score
            )
        }
    }

    private data class HibpFetchResult(
        val breaches: List<EmailBreach>,
        val coverage: HibpCoverage,
        val error: String?
    )

    companion object {
        private const val USER_AGENT = "Dossier Android self-audit app"

        fun sha1Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02X".format(Locale.US, it) }
        }

        fun parsePwnedPasswordRange(body: String, suffix: String): Int {
            val suffixUpper = suffix.uppercase()
            return body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) null else parts[0].uppercase() to parts[1].toIntOrNull()
                }
                .firstOrNull { (candidateSuffix, _) -> candidateSuffix == suffixUpper }
                ?.second ?: 0
        }

        fun parseHibpBreaches(body: String): List<EmailBreach> {
            if (body.isBlank()) return emptyList()
            return Json { ignoreUnknownKeys = true }
                .decodeFromString<List<HibpBreachDto>>(body)
                .map { dto ->
                    EmailBreach(
                        name = dto.name,
                        title = dto.title,
                        domain = dto.domain,
                        breachDate = dto.breachDate,
                        dataClasses = dto.dataClasses
                    )
                }
        }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

@Serializable
private data class HibpBreachDto(
    @SerialName("Name") val name: String,
    @SerialName("Title") val title: String,
    @SerialName("Domain") val domain: String = "",
    @SerialName("BreachDate") val breachDate: String? = null,
    @SerialName("DataClasses") val dataClasses: List<String> = emptyList()
)
