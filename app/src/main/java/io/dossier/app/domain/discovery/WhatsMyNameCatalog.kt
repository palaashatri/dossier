package io.dossier.app.domain.discovery

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

enum class WhatsMyNameExclusionReason {
    InvalidSchema,
    MissingName,
    NotValid,
    ContainsPostBody,
    CategoryNSFW,
    MissingOrMultipleAccountTokens,
    NotHttps,
    InvalidUriHost,
    ProtectionEnabled,
    InvalidStatusCode,
    AmbiguousSameStatusNoMarkers
}

data class WhatsMyNameExcludedRecord(
    val name: String?,
    val reason: WhatsMyNameExclusionReason
)

data class WhatsMyNameSite(
    val id: String,
    val name: String,
    val category: ProviderCategory,
    val uriPretty: String,
    val uriCheck: String,
    val eCode: Int,
    val eString: String,
    val mCode: Int,
    val mString: String,
    val stripBadChar: String
) {
    fun toProviderDefinition(): ProviderDefinition = ProviderDefinition(
        id = id,
        displayName = name,
        category = category,
        profileUrlTemplate = uriCheck.replace(ACCOUNT_TOKEN, "{username}"),
        queryCapabilities = setOf(QueryCapability.Username),
        // WhatsMyName may distinguish present and absent responses with body
        // markers while using the same HTTP status. Its dedicated classifier
        // handles that distinction; these non-overlapping declarative rules
        // keep the generated definition valid for shared runtime policy.
        existenceRules = ExistenceRules(
            requiredStatus = setOf(eCode, mCode),
            notFoundStatus = emptySet()
        ),
        requestPolicy = ProviderRequestPolicy(
            maxConcurrency = 1,
            minimumIntervalMs = 350,
            timeoutMs = 6_000,
            retryBudget = 0,
            cooldownMs = 60_000
        ),
        legacyTemplateCompatible = false
    )

    private companion object {
        const val ACCOUNT_TOKEN = "{account}"
    }
}

sealed class WhatsMyNameCatalogState {
    data class Ready(
        val sites: List<WhatsMyNameSite>,
        val excluded: List<WhatsMyNameExcludedRecord>,
        val license: List<String>,
        val authors: List<String>,
        val categories: List<String>,
        val totalCount: Int,
        val executableCount: Int,
        val excludedCount: Int
    ) : WhatsMyNameCatalogState() {
        init {
            require(totalCount == executableCount + excludedCount)
            require(executableCount == sites.size)
            require(excludedCount == excluded.size)
        }
    }

    data class Unavailable(val reason: String) : WhatsMyNameCatalogState()
}

/** Deterministic response rules; an existence result never attributes ownership. */
object WhatsMyNameResponseClassifier {
    fun classify(
        site: WhatsMyNameSite,
        observation: ProviderResponseObservation
    ): ProviderResponseDecision {
        val finalUrl = observation.finalUrl
        if (finalUrl != null && !ProviderResponseClassifier.sameProviderHost(observation.requestedUrl, finalUrl)) {
            return ProviderResponseDecision(
                ProviderVerificationState.RedirectedOutsideProvider,
                "Final response host differs from requested provider host"
            )
        }

        val status = observation.statusCode ?: return ProviderResponseDecision(
            ProviderVerificationState.InvalidResponse,
            "Response did not include an HTTP status"
        )
        if (status == 429) {
            return ProviderResponseDecision(ProviderVerificationState.RateLimited, "Provider rate limit is active")
        }
        if (status == 401 || status == 403) {
            return ProviderResponseDecision(
                ProviderVerificationState.AuthenticationRequired,
                "Public response requires authentication"
            )
        }
        if (containsChallenge(observation.bodyText)) {
            return ProviderResponseDecision(
                ProviderVerificationState.AutomationChallenged,
                "Provider returned an automation or human-verification challenge"
            )
        }

        val present = status == site.eCode &&
            (site.eString.isBlank() || observation.bodyText.contains(site.eString))
        val absent = status == site.mCode &&
            (site.mString.isBlank() || observation.bodyText.contains(site.mString))

        return when {
            present && !absent -> ProviderResponseDecision(
                ProviderVerificationState.Present,
                "Response matches the published presence rule; ownership remains unverified"
            )
            absent && !present -> ProviderResponseDecision(
                ProviderVerificationState.NotFound,
                "Response matches the published missing-account rule"
            )
            else -> ProviderResponseDecision(
                ProviderVerificationState.InvalidResponse,
                "Response ambiguously matched or did not match the published rules"
            )
        }
    }

    private fun containsChallenge(bodyText: String): Boolean {
        val body = bodyText.lowercase(Locale.ROOT)
        if (listOf("cf-challenge", "g-recaptcha", "h-captcha", "data-sitekey").any(body::contains)) {
            return true
        }
        if (body.length > 12_000) return false
        return listOf(
            "checking your browser",
            "verify you are human",
            "are you a robot",
            "unusual traffic",
            "attention required",
            "security check"
        ).any(body::contains)
    }
}

/** Pinned, process-local catalog loaded from the application asset bundle. */
object WhatsMyNameCatalog {
    const val PINNED_SHA256 = "779922223756F47D1512F81A5A2D0C69D19418FE5DF1A2A9406C7CF18CF68F34"
    const val PINNED_SIZE_BYTES = 258_615
    const val MAX_SIZE_BYTES = 4 * 1024 * 1024
    const val ASSET_PATH = "providers/whatsmyname/wmn-data.json"

    private const val ACCOUNT_TOKEN = "{account}"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var state: WhatsMyNameCatalogState = WhatsMyNameCatalogState.Unavailable("Catalog is not installed")
        private set

    @Synchronized
    fun install(context: Context) {
        if (state is WhatsMyNameCatalogState.Ready) return
        state = try {
            parse(readBoundedAsset(context))
        } catch (_: Exception) {
            WhatsMyNameCatalogState.Unavailable("Bundled catalog could not be read")
        }
    }

    internal fun setTestState(newState: WhatsMyNameCatalogState) {
        state = newState
    }

    fun parse(
        data: ByteArray,
        expectedSha256: String = PINNED_SHA256
    ): WhatsMyNameCatalogState {
        if (data.size > MAX_SIZE_BYTES) {
            return WhatsMyNameCatalogState.Unavailable("Catalog exceeds the maximum size")
        }
        val actualHash = sha256(data).uppercase(Locale.ROOT)
        if (actualHash != expectedSha256.uppercase(Locale.ROOT)) {
            return WhatsMyNameCatalogState.Unavailable("Catalog integrity hash does not match")
        }
        return parseVerified(String(data, Charsets.UTF_8))
    }

    private fun parseVerified(source: String): WhatsMyNameCatalogState {
        val root = runCatching { json.parseToJsonElement(source) as? JsonObject }.getOrNull()
            ?: return WhatsMyNameCatalogState.Unavailable("Catalog top-level structure is malformed")
        val license = root.requiredStringArray("license")
            ?: return WhatsMyNameCatalogState.Unavailable("Catalog license metadata is malformed")
        val authors = root.requiredStringArray("authors")
            ?: return WhatsMyNameCatalogState.Unavailable("Catalog author metadata is malformed")
        val categories = root.requiredStringArray("categories")
            ?: return WhatsMyNameCatalogState.Unavailable("Catalog category metadata is malformed")
        val rawSites = root["sites"] as? JsonArray
            ?: return WhatsMyNameCatalogState.Unavailable("Catalog sites array is missing or malformed")
        if (rawSites.isEmpty()) {
            return WhatsMyNameCatalogState.Unavailable("Catalog sites array is empty")
        }

        val executable = mutableListOf<WhatsMyNameSite>()
        val excluded = mutableListOf<WhatsMyNameExcludedRecord>()
        val seenIds = mutableSetOf<String>()

        for (element in rawSites) {
            val record = element as? JsonObject
            if (record == null) {
                excluded += WhatsMyNameExcludedRecord(null, WhatsMyNameExclusionReason.InvalidSchema)
                continue
            }
            when (val parsed = parseRecord(record)) {
                is RecordParse.Excluded -> excluded += parsed.record
                is RecordParse.Executable -> {
                    if (!seenIds.add(parsed.site.id)) {
                        return WhatsMyNameCatalogState.Unavailable(
                            "Catalog contains duplicate provider id ${parsed.site.id}"
                        )
                    }
                    executable += parsed.site
                }
            }
        }

        return WhatsMyNameCatalogState.Ready(
            sites = executable.toList(),
            excluded = excluded.toList(),
            license = license,
            authors = authors,
            categories = categories,
            totalCount = rawSites.size,
            executableCount = executable.size,
            excludedCount = excluded.size
        )
    }

    private fun parseRecord(record: JsonObject): RecordParse {
        val nameField = record.optionalString("name")
        if (!nameField.valid) return record.excluded(null, WhatsMyNameExclusionReason.InvalidSchema)
        val name = nameField.value?.trim()?.takeIf(String::isNotBlank)
            ?: return record.excluded(null, WhatsMyNameExclusionReason.MissingName)

        val validElement = record["valid"]
        if (validElement != null && validElement !== JsonNull) {
            val valid = (validElement as? JsonPrimitive)?.booleanOrNull
                ?: return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
            if (!valid) return record.excluded(name, WhatsMyNameExclusionReason.NotValid)
        }

        val postBody = record.optionalString("post_body")
        if (!postBody.valid) return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
        if (!postBody.value.isNullOrBlank()) {
            return record.excluded(name, WhatsMyNameExclusionReason.ContainsPostBody)
        }

        val category = record.optionalString("cat")
        if (!category.valid) return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
        val rawCategory = category.value.orEmpty().trim()
        if (rawCategory.contains("nsfw", ignoreCase = true)) {
            return record.excluded(name, WhatsMyNameExclusionReason.CategoryNSFW)
        }

        val checkField = record.optionalString("uri_check")
        if (!checkField.valid) return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
        val uriCheck = checkField.value.orEmpty()
        if (uriCheck.countToken(ACCOUNT_TOKEN) != 1) {
            return record.excluded(name, WhatsMyNameExclusionReason.MissingOrMultipleAccountTokens)
        }
        val checkProbe = uriCheck.replace(ACCOUNT_TOKEN, "probe")
        val checkUri = runCatching { URI(checkProbe) }.getOrNull()
        if (checkUri?.scheme?.lowercase(Locale.ROOT) != "https") {
            return record.excluded(name, WhatsMyNameExclusionReason.NotHttps)
        }
        if (checkUri.host.isNullOrBlank() || checkUri.userInfo != null) {
            return record.excluded(name, WhatsMyNameExclusionReason.InvalidUriHost)
        }

        val protection = record.optionalStringArray("protection")
        if (!protection.valid) return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
        if (protection.values.any { it.lowercase(Locale.ROOT) in BLOCKED_PROTECTIONS }) {
            return record.excluded(name, WhatsMyNameExclusionReason.ProtectionEnabled)
        }

        val existsCode = (record["e_code"] as? JsonPrimitive)?.intOrNull
        val missingCode = (record["m_code"] as? JsonPrimitive)?.intOrNull
        if (existsCode !in 100..599 || missingCode !in 100..599) {
            return record.excluded(name, WhatsMyNameExclusionReason.InvalidStatusCode)
        }
        val existsMarker = record.optionalString("e_string")
        val missingMarker = record.optionalString("m_string")
        if (!existsMarker.valid || !missingMarker.valid) {
            return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
        }
        val eString = existsMarker.value.orEmpty()
        val mString = missingMarker.value.orEmpty()
        if (existsCode == missingCode && eString.isBlank() && mString.isBlank()) {
            return record.excluded(name, WhatsMyNameExclusionReason.AmbiguousSameStatusNoMarkers)
        }

        val pretty = record.optionalString("uri_pretty")
        val strip = record.optionalString("strip_bad_char")
        if (!pretty.valid || !strip.valid) {
            return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
        }

        val providerSlug = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(100)
            .ifBlank { "site" }
        val providerId = "wmn-$providerSlug-${sha256(checkProbe.toByteArray()).take(10)}"
        val site = WhatsMyNameSite(
            id = providerId,
            name = name.take(120),
            category = mapCategory(rawCategory),
            uriPretty = pretty.value.orEmpty(),
            uriCheck = uriCheck,
            eCode = checkNotNull(existsCode),
            eString = eString,
            mCode = checkNotNull(missingCode),
            mString = mString,
            stripBadChar = strip.value.orEmpty()
        )
        if (ProviderDefinitionValidator.validate(site.toProviderDefinition()).isNotEmpty()) {
            return record.excluded(name, WhatsMyNameExclusionReason.InvalidSchema)
        }
        return RecordParse.Executable(site)
    }

    private fun mapCategory(category: String): ProviderCategory = when (category.lowercase(Locale.ROOT)) {
        "art", "images" -> ProviderCategory.Creative
        "blog", "news", "political" -> ProviderCategory.Publishing
        "business" -> ProviderCategory.Professional
        "coding" -> ProviderCategory.CodeHosting
        "finance", "shopping" -> ProviderCategory.Commerce
        "gaming" -> ProviderCategory.Gaming
        "music", "video" -> ProviderCategory.Media
        "tech" -> ProviderCategory.Developer
        "social", "dating" -> ProviderCategory.Social
        else -> ProviderCategory.PublicDirectory
    }

    private fun readBoundedAsset(context: Context): ByteArray =
        context.assets.open(ASSET_PATH).use { input ->
            ByteArrayOutputStream(minOf(PINNED_SIZE_BYTES, MAX_SIZE_BYTES)).use { output ->
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SIZE_BYTES) {
                        throw IllegalStateException("Catalog asset exceeds maximum size")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }

    private fun JsonObject.requiredStringArray(key: String): List<String>? {
        val array = this[key] as? JsonArray ?: return null
        if (array.isEmpty()) return null
        val values = array.map { element ->
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            primitive.content.trim().takeIf(String::isNotBlank) ?: return null
        }
        return values.toList()
    }

    private fun JsonObject.optionalString(key: String): OptionalString {
        val element = this[key] ?: return OptionalString(true, null)
        if (element === JsonNull) return OptionalString(true, null)
        val primitive = element as? JsonPrimitive ?: return OptionalString(false, null)
        if (!primitive.isString) return OptionalString(false, null)
        return OptionalString(true, primitive.contentOrNull)
    }

    private fun JsonObject.optionalStringArray(key: String): OptionalStringArray {
        val element = this[key] ?: return OptionalStringArray(true, emptyList())
        if (element === JsonNull) return OptionalStringArray(true, emptyList())
        val array = element as? JsonArray ?: return OptionalStringArray(false, emptyList())
        val values = mutableListOf<String>()
        for (item in array) {
            val primitive = item as? JsonPrimitive ?: return OptionalStringArray(false, emptyList())
            if (!primitive.isString) return OptionalStringArray(false, emptyList())
            values += primitive.content
        }
        return OptionalStringArray(true, values.toList())
    }

    private fun JsonObject.excluded(
        name: String?,
        reason: WhatsMyNameExclusionReason
    ): RecordParse.Excluded = RecordParse.Excluded(WhatsMyNameExcludedRecord(name, reason))

    private fun String.countToken(token: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(token, start)
            if (index < 0) return count
            count++
            start = index + token.length
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class OptionalString(val valid: Boolean, val value: String?)
    private data class OptionalStringArray(val valid: Boolean, val values: List<String>)

    private sealed interface RecordParse {
        data class Executable(val site: WhatsMyNameSite) : RecordParse
        data class Excluded(val record: WhatsMyNameExcludedRecord) : RecordParse
    }

    private val BLOCKED_PROTECTIONS = setOf("captcha", "user-auth", "anubis")
}
