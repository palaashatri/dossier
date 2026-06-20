package io.dossier.app.data.breach

import android.content.Context
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.breach.EmailBreach
import io.dossier.app.domain.breach.EmailExposureResult
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
 * Safe breach checks.
 *
 * - Passwords: HIBP Pwned Passwords k-anonymity API. Only the first five SHA-1
 *   hex chars leave the device; plaintext is never sent or stored in results.
 * - Emails: optional HIBP breached-account API when the user provides an API
 *   key. Without a key, the app still searches public indexes for the email
 *   address, but never recovers leaked passwords from dumps.
 */
class BreachCheckService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkPasswords(passwords: List<String>): List<PasswordExposureResult> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                passwords
                    .mapIndexedNotNull { index, password ->
                        password.takeIf { it.isNotBlank() }?.let {
                            async { checkPassword(it, "Password ${index + 1}") }
                        }
                    }
                    .awaitAll()
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
                            label = label,
                            isPwned = false,
                            occurrenceCount = 0,
                            sha1Prefix = prefix,
                            error = "Pwned Passwords lookup failed: HTTP ${response.code}"
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val count = parsePwnedPasswordRange(body, suffix)
                    PasswordExposureResult(
                        label = label,
                        isPwned = count > 0,
                        occurrenceCount = count,
                        sha1Prefix = prefix
                    )
                }
            } catch (e: Exception) {
                PasswordExposureResult(
                    label = label,
                    isPwned = false,
                    occurrenceCount = 0,
                    sha1Prefix = prefix,
                    error = "Pwned Passwords lookup failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }

    suspend fun checkEmails(
        emails: List<String>,
        hibpApiKey: String? = null,
        deepResearch: Boolean = false
    ): List<EmailExposureResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            emails
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .map { email ->
                    async { checkEmail(email, hibpApiKey, deepResearch) }
                }
                .awaitAll()
        }
    }

    suspend fun checkEmail(
        email: String,
        hibpApiKey: String? = null,
        deepResearch: Boolean = false
    ): EmailExposureResult = withContext(Dispatchers.IO) {
        val breachesResult = fetchHibpBreaches(email, hibpApiKey)
        val publicEvidence = fetchPublicEmailEvidence(email, deepResearch)

        EmailExposureResult(
            email = email,
            breaches = breachesResult.breaches,
            publicEvidence = publicEvidence,
            error = breachesResult.error
        )
    }

    private suspend fun fetchHibpBreaches(email: String, hibpApiKey: String?): HibpFetchResult {
        val key = hibpApiKey?.trim().orEmpty()
        if (key.isBlank()) {
            return HibpFetchResult(
                breaches = emptyList(),
                error = "HIBP email breach metadata requires an API key; public email search still ran."
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
                        val body = response.body?.string().orEmpty()
                        HibpFetchResult(parseHibpBreaches(body), null)
                    }
                    404 -> HibpFetchResult(emptyList(), null)
                    401, 403 -> HibpFetchResult(emptyList(), "HIBP API key was rejected.")
                    429 -> HibpFetchResult(emptyList(), "HIBP rate limit reached. Try again later.")
                    else -> HibpFetchResult(emptyList(), "HIBP lookup failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            HibpFetchResult(
                breaches = emptyList(),
                error = "HIBP lookup failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
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
        val error: String?
    )

    companion object {
        private const val USER_AGENT =
            "Dossier Android self-audit app (contact: local-user)"

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

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")
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
