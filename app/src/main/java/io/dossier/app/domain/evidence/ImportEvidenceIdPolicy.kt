package io.dossier.app.domain.evidence

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable opaque IDs for evidence emitted by user-selected import reports.
 *
 * The digest input is deliberately separated into provider/import/row
 * components so duplicate rows collapse deterministically while distinct rows
 * from the same report do not collide. Raw values never appear in the ID.
 */
object ImportEvidenceIdPolicy {
    private const val MAX_COMPONENT_CHARS = 12_000
    private const val DIGEST_CHARS = 32

    fun stableId(
        prefix: String,
        providerId: String,
        importDigest: String?,
        rowMaterial: String,
        discriminator: String = ""
    ): String {
        val canonical = listOf(
            providerId.trim().lowercase(Locale.ROOT),
            importDigest.orEmpty().trim().lowercase(Locale.ROOT),
            rowMaterial.take(MAX_COMPONENT_CHARS),
            discriminator.take(MAX_COMPONENT_CHARS)
        ).joinToString("\u001f")
        return "${prefix.trim()}:${sha256(canonical).take(DIGEST_CHARS)}"
    }

    fun digest(value: String): String = sha256(value.take(MAX_COMPONENT_CHARS))

    fun digestFields(fields: Map<String, String>): String = digest(
        fields.entries
            .sortedBy { it.key }
            .joinToString("\u001f") { "${it.key}=${it.value}" }
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
