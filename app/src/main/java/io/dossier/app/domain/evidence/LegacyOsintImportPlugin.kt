package io.dossier.app.domain.evidence

import io.dossier.app.data.web.LegacyOsintExportParser
import io.dossier.app.data.web.LegacyOsintImportSession
import io.dossier.app.domain.model.IdentityInput

/**
 * Converts user-selected Twint/snscrape exports into candidate evidence during the
 * active authorized scan. Imported rows can never become Verified here; the parser
 * deliberately emits Candidate/ThirdPartyAggregation evidence that must be checked
 * against live or archived public URLs by the rest of Dossier.
 */
class LegacyOsintImportPlugin : ScannerPlugin {
    override val id: String = "legacy-osint-import"
    override val displayName: String = "Legacy OSINT Export Imports"

    override suspend fun scan(input: IdentityInput): EvidenceCollection {
        val authorizedHandles = (listOfNotNull(input.primaryUsername) + input.usernames)
            .map { it.trim().removePrefix("@").removePrefix("u/") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (authorizedHandles.isEmpty()) return EvidenceCollection()

        val evidence = mutableListOf<Evidence>()
        val relationships = mutableListOf<EvidenceRelationship>()
        LegacyOsintImportSession.snapshot().forEach { pending ->
            val parsed = LegacyOsintExportParser.parse(
                source = pending.source,
                raw = pending.rawText,
                authorizedHandles = authorizedHandles
            )
            evidence += parsed.collection.evidence.map { record ->
                record.copy(
                    signals = record.signals + listOf(
                        "Locally selected import: ${pending.displayName}",
                        "Import SHA-256: ${pending.sha256}",
                        "Import bytes: ${pending.byteCount}"
                    )
                )
            }
            relationships += parsed.collection.relationships
        }

        return EvidenceCollection(
            evidence = evidence.distinctBy(Evidence::id),
            relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
        )
    }
}
