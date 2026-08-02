package io.dossier.app.data.breach

import android.content.Context
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.domain.breach.EmailBreach
import io.dossier.app.domain.breach.EmailExposureResult
import io.dossier.app.domain.breach.HibpCoverage
import io.dossier.app.domain.breach.PasswordExposureResult
import io.dossier.app.domain.breach.PublicEmailEvidence
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.CancellationException
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
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Safe breach and public-exposure checks.
 *
 * HIBP account coverage and ordinary web exposure are always represented as
 * separate channels. A missing API key can never be mistaken for a confirmed
 * zero-breach result.
 *
 * Email breach lookups use HIBP's authenticated k-anonymity range API: Dossier
 * normalizes and hashes the email locally, sends only the first six SHA-1
 * characters, matches the returned suffix locally, and immediately discards
 * every non-matching range entry. The complete email address is never sent to
 * HIBP by this client.
 */
class BreachCheckService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var breachCatalogue: Map<String, EmailBreach>? = null

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
            val prefix = sha1.take(PASSWORD_PREFIX_LENGTH)
            val suffix = sha1.drop(PASSWORD_PREFIX_LENGTH)
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
            } catch (cancelled: CancellationException) {
                throw cancelled
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
                .distinctBy { it.lowercase(Locale.ROOT) }
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

        val emailHash = sha1Hex(normalizeEmailForHash(email))
        val prefix = emailHash.take(EMAIL_PREFIX_LENGTH)
        val suffix = emailHash.drop(EMAIL_PREFIX_LENGTH)

        return try {
            val request = Request.Builder()
                .url("https://haveibeenpwned.com/api/v3/breachedaccount/range/$prefix")
                .header("User-Agent", USER_AGENT)
                .header("hibp-api-key", key)
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        // Only the exact local suffix match is retained. All other
                        // records in the anonymity range are discarded immediately.
                        val breachNames = parseHibpEmailRange(
                            response.body?.string().orEmpty(),
                            suffix
                        )
                        if (breachNames.isEmpty()) {
                            HibpFetchResult(
                                emptyList(),
                                HibpCoverage.ConfirmedNoBreaches,
                                null
                            )
                        } else {
                            val catalogue = getBreachCatalogue()
                            val breaches = breachNames.map { name ->
                                catalogue[name.lowercase(Locale.ROOT)] ?: EmailBreach(
                                    name = name,
                                    title = name,
                                    domain = "",
                                    breachDate = null,
                                    dataClasses = emptyList()
                                )
                            }
                            HibpFetchResult(
                                breaches,
                                HibpCoverage.ConfirmedBreaches,
                                null
                            )
                        }
                    }
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
                        "HIBP k-anonymity lookup failed: HTTP ${response.code}"
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HibpFetchResult(
                emptyList(),
                HibpCoverage.Unavailable,
                "HIBP k-anonymity lookup failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            )
        }
    }

    /**
     * The public breach catalogue contains incident metadata only; it does not
     * search for an email address. Cache it per service instance and use it only
     * to enrich breach names returned by the locally matched range record.
     */
    private fun getBreachCatalogue(): Map<String, EmailBreach> {
        breachCatalogue?.let { return it }
        return synchronized(this) {
            breachCatalogue?.let { return@synchronized it }
            val fetched = runCatching {
                val request = Request.Builder()
                    .url("https://haveibeenpwned.com/api/v3/breaches")
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyMap()
                    parseHibpBreaches(response.body?.string().orEmpty())
                        .associateBy { it.name.lowercase(Locale.ROOT) }
                }
            }.getOrDefault(emptyMap())
            breachCatalogue = fetched
            fetched
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
        private const val PASSWORD_PREFIX_LENGTH = 5
        private const val EMAIL_PREFIX_LENGTH = 6

        fun sha1Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02X".format(Locale.US, it) }
        }

        fun normalizeEmailForHash(email: String): String =
            email.trim().lowercase(Locale.ROOT)

        fun emailHashPrefix(email: String): String =
            sha1Hex(normalizeEmailForHash(email)).take(EMAIL_PREFIX_LENGTH)

        fun parsePwnedPasswordRange(body: String, suffix: String): Int {
            val suffixUpper = suffix.uppercase(Locale.ROOT)
            return body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) null else parts[0].uppercase(Locale.ROOT) to parts[1].toIntOrNull()
                }
                .firstOrNull { (candidateSuffix, _) -> candidateSuffix == suffixUpper }
                ?.second ?: 0
        }

        /** Returns only breach names associated with the exact local hash suffix. */
        fun parseHibpEmailRange(body: String, suffix: String): List<String> {
            if (body.isBlank()) return emptyList()
            val suffixUpper = suffix.uppercase(Locale.ROOT)
            val matches = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<HibpEmailRangeDto>>(body)
            }.getOrDefault(emptyList())
            return matches
                .firstOrNull { it.hashSuffix.uppercase(Locale.ROOT) == suffixUpper }
                ?.websites
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
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
    }
}

@Serializable
private data class HibpEmailRangeDto(
    val hashSuffix: String,
    val websites: List<String> = emptyList()
)

@Serializable
private data class HibpBreachDto(
    @SerialName("Name") val name: String,
    @SerialName("Title") val title: String,
    @SerialName("Domain") val domain: String = "",
    @SerialName("BreachDate") val breachDate: String? = null,
    @SerialName("DataClasses") val dataClasses: List<String> = emptyList()
)
