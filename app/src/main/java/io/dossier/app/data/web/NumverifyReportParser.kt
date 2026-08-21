package io.dossier.app.data.web

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * Imports a user-selected Numverify response/report for a phone number explicitly
 * supplied to the active audit. Dossier does not retain a Numverify API key here.
 * Carrier/country/type fields are public metadata leads, not subscriber identity.
 */
object NumverifyReportParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun looksLikeNumverify(displayName: String, raw: String): Boolean {
        val sample = (displayName + "\n" + raw.take(2_000)).lowercase()
        if ("numverify" in sample) return true
        val markers = listOf("country_code", "country_name", "location", "carrier", "line_type")
        return markers.count(sample::contains) >= 3 && ("valid" in sample || "number" in sample)
    }

    fun parse(raw: String, input: IdentityInput): EvidenceCollection {
        val authorized = input.phones
            .map { it.filter(Char::isDigit) }
            .filter { it.length >= 8 }
            .distinct()
        if (authorized.isEmpty() || raw.isBlank()) return EvidenceCollection()

        val fields = parseFields(raw)
        val reportDigits = sequenceOf(
            fields["number"], fields["international_format"], fields["local_format"], raw
        ).filterNotNull().joinToString(" ").filter(Char::isDigit)
        val matched = authorized.firstOrNull(reportDigits::contains) ?: return EvidenceCollection()

        val safeSummary = buildList {
            fields["valid"]?.let { add("valid=$it") }
            fields["country_code"]?.let { add("country_code=${clean(it)}") }
            fields["country_name"]?.let { add("country=${clean(it)}") }
            fields["location"]?.let { add("location=${clean(it)}") }
            fields["carrier"]?.let { add("carrier=${clean(it)}") }
            fields["line_type"]?.let { add("line_type=${clean(it)}") }
        }.joinToString(" | ").ifBlank { "Numverify phone metadata response" }.take(600)

        val masked = "••••${matched.takeLast(4)}"
        val id = "numverify-import:${sha256("$matched|$safeSummary").take(32)}"
        val evidence = Evidence(
            id = id,
            kind = EvidenceKind.Phone,
            value = masked,
            snippet = safeSummary,
            confidence = 0.42f,
            risk = RiskLevel.Low,
            signals = listOf(
                "Numverify response matched a phone number explicitly supplied to this audit",
                "Carrier/region/type metadata does not identify the subscriber",
                "Imported third-party response; not an identity-verification result"
            ),
            providerId = "numverify-import",
            retrievedAtEpochMillis = System.currentTimeMillis(),
            state = EvidenceState.Candidate,
            reliability = EvidenceReliability.ThirdPartyAggregation,
            contentHashSha256 = sha256(safeSummary),
            parserVersion = "numverify-report-v1"
        )
        return EvidenceCollection(
            evidence = listOf(evidence),
            relationships = listOf(
                EvidenceRelationship(
                    fromValue = masked,
                    toValue = id,
                    relation = "HAS_PUBLIC_PHONE_METADATA",
                    evidence = "Imported Numverify metadata; subscriber identity not asserted"
                )
            )
        )
    }

    private fun parseFields(raw: String): Map<String, String> {
        val objectValue = runCatching { json.parseToJsonElement(raw.trim()) as? JsonObject }.getOrNull()
        if (objectValue != null) {
            return objectValue.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key.lowercase() to it }
            }.toMap()
        }
        return raw.lineSequence()
            .flatMap { it.split(',', '|').asSequence() }
            .map(String::trim)
            .mapNotNull { part ->
                val separator = when {
                    '=' in part -> '='
                    ':' in part -> ':'
                    else -> return@mapNotNull null
                }
                val key = part.substringBefore(separator).trim().trim('"').lowercase()
                val value = part.substringAfter(separator).trim().trim('"')
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun clean(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .trim()
        .take(120)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
