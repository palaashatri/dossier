package io.dossier.app.domain.evidence

import io.dossier.app.data.web.ExternalOsintImportSession
import io.dossier.app.data.web.ExternalOsintReportParser
import io.dossier.app.data.web.NumverifyReportParser
import io.dossier.app.domain.model.IdentityInput

/**
 * Converts user-selected external OSINT reports into bounded candidate evidence.
 * The plugin never launches the source tools and never upgrades imported records to
 * Verified. Promotion remains the responsibility of Dossier's direct/live/archive
 * verification paths.
 */
class ExternalOsintImportPlugin : ScannerPlugin {
    override val id: String = "external-osint-import"
    override val displayName: String = "External OSINT Report Imports"

    override suspend fun scan(input: IdentityInput): EvidenceCollection {
        val evidence = mutableListOf<Evidence>()
        val relationships = mutableListOf<EvidenceRelationship>()

        ExternalOsintImportSession.snapshot().forEach { pending ->
            if (NumverifyReportParser.looksLikeNumverify(pending.displayName, pending.rawText)) {
                val numverify = NumverifyReportParser.parse(
                    raw = pending.rawText,
                    input = input,
                    importDigest = pending.sha256
                )
                evidence += numverify.evidence.map { record ->
                    record.copy(
                        signals = record.signals + listOf(
                            "Locally selected report: ${pending.displayName}",
                            "Report SHA-256: ${pending.sha256}",
                            "Report bytes: ${pending.byteCount}"
                        )
                    )
                }
                relationships += numverify.relationships
                return@forEach
            }

            val parsed = ExternalOsintReportParser.parse(
                source = pending.source,
                raw = pending.rawText,
                input = input,
                importDigest = pending.sha256
            )
            evidence += parsed.collection.evidence.map { record ->
                record.copy(
                    signals = record.signals + listOf(
                        "Locally selected report: ${pending.displayName}",
                        "Report SHA-256: ${pending.sha256}",
                        "Report bytes: ${pending.byteCount}"
                    ) + parsed.warnings
                )
            }
            relationships += parsed.collection.relationships
        }

        return EvidenceCollection(
            evidence = evidence.distinctBy(Evidence::id),
            relationships = EvidenceRelationshipPolicy.normalize(relationships)
        )
    }
}
