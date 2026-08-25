package io.dossier.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Explicit export privacy choice. */
enum class ExportRedactionMode {
    None,
    ShareSafe
}

/** Produces human-readable and machine-verifiable local report exports. */
class ReportExporter(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun exportToJson(
        findings: List<Finding>,
        redactionMode: ExportRedactionMode = ExportRedactionMode.None
    ): String = runCatching {
        json.encodeToString(prepareExport(findings = findings, redactionMode = redactionMode).findings)
    }.getOrDefault("[]")

    fun shareReport(
        findings: List<Finding>,
        subjectName: String = "Unnamed subject",
        profileSummaries: List<String> = emptyList(),
        aiSummary: String? = null,
        faceMatches: List<FaceConsistencyMatch> = emptyList(),
        entityGraphSummary: String? = null,
        breachDigests: List<String> = emptyList(),
        riskLevel: String? = null,
        redactionMode: ExportRedactionMode = ExportRedactionMode.None,
        canonicalRelationships: List<EvidenceRelationship> = emptyList()
    ) {
        val generatedAt = Instant.now()
        val prepared = prepareExport(
            findings = findings,
            subjectName = subjectName,
            profileSummaries = profileSummaries,
            aiSummary = aiSummary,
            faceMatches = faceMatches,
            entityGraphSummary = entityGraphSummary,
            canonicalRelationships = canonicalRelationships,
            breachDigests = breachDigests,
            redactionMode = redactionMode
        )
        val reportText = buildReportText(
            prepared.findings,
            prepared.subjectName,
            prepared.profileSummaries,
            prepared.aiSummary,
            prepared.faceMatches,
            prepared.entityGraphSummary,
            prepared.canonicalRelationships,
            prepared.breachDigests,
            riskLevel,
            generatedAt,
            prepared.redacted
        )

        val attachments = runCatching {
            val directory = File(context.cacheDir, "reports").also { it.mkdirs() }
            val safeName = prepared.subjectName.replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-').ifBlank { "subject" }.take(40)
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now())
            val redactionSuffix = if (prepared.redacted) "-redacted" else ""
            val base = "dossier-$safeName-$timestamp$redactionSuffix"

            val pdfFile = File(directory, "$base.pdf")
            writePdf(pdfFile, reportText)
            val jsonFile = File(directory, "$base.evidence.json")
            writeEvidencePackage(
                file = jsonFile,
                generatedAt = generatedAt,
                subjectName = prepared.subjectName,
                findings = prepared.findings,
                profileSummaries = prepared.profileSummaries,
                aiSummary = prepared.aiSummary,
                faceMatches = prepared.faceMatches,
                entityGraphSummary = prepared.entityGraphSummary,
                canonicalRelationships = prepared.canonicalRelationships,
                breachDigests = prepared.breachDigests,
                riskLevel = riskLevel,
                reportText = reportText,
                redacted = prepared.redacted
            )
            listOf(pdfFile, jsonFile)
        }.getOrDefault(emptyList())

        if (attachments.isEmpty()) {
            sharePlainText(prepared.subjectName, reportText)
            return
        }

        val uris = ArrayList<Uri>(attachments.size)
        attachments.forEach { file ->
            uris += FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "Dossier privacy audit — ${prepared.subjectName}")
            putExtra(Intent.EXTRA_TEXT, reportText)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("Dossier report", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        context.startActivity(
            Intent.createChooser(intent, "Share privacy audit evidence package").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun buildReportText(
        findings: List<Finding>,
        subjectName: String,
        profileSummaries: List<String>,
        aiSummary: String?,
        faceMatches: List<FaceConsistencyMatch>,
        entityGraphSummary: String?,
        canonicalRelationships: List<EvidenceRelationship>,
        breachDigests: List<String>,
        riskLevel: String?,
        generatedAt: Instant,
        redacted: Boolean
    ): String {
        val localTime = LocalDateTime.now()
        val preparedAt = localTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val reportId = "DS-${localTime.toLocalDate()}-" +
            subjectName.replace(Regex("[^A-Za-z0-9]"), "").take(6).uppercase(Locale.US)
                .ifBlank { "SUBJECT" }

        return buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("  DOSSIER PRIVACY AUDIT")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            appendLine("REPORT ID: $reportId")
            appendLine("SUBJECT:   $subjectName")
            appendLine("PREPARED:  $preparedAt")
            appendLine("UTC:       $generatedAt")
            appendLine("PURPOSE:   Authorized public-footprint audit")
            appendLine("REDACTION: ${if (redacted) "SHARE-SAFE" else "NONE"}")
            if (!riskLevel.isNullOrBlank()) appendLine("PRIORITY:  $riskLevel")
            appendLine()
            if (redacted) {
                appendLine("Share-safe export: direct identity values, source URLs, snippets, graph details, breach details, and generated analysis were removed or generalized.")
                appendLine("This redaction is designed to reduce disclosure; review the generated files before sharing them.")
            }
            appendLine("Interpretation note: risk describes potential impact; confidence describes attribution support.")
            appendLine("An absent result does not prove that information never existed online.")
            appendLine()

            if (!aiSummary.isNullOrBlank()) {
                appendSection("ANALYSIS AND PROVENANCE")
                appendLine(aiSummary.trim())
                appendLine()
            }

            appendSection("EVIDENCE FINDINGS")
            if (findings.isEmpty()) {
                appendLine("No reportable finding was detected in the public sources inspected.")
                appendLine("Private, blocked, deleted, and unindexed content may be absent from this report.")
                appendLine()
            } else {
                appendLine("${findings.size} finding(s) recorded:")
                appendLine()
                findings.forEachIndexed { index, finding ->
                    appendLine("[${index + 1}] RISK:         ${finding.risk}")
                    appendLine("    CATEGORY:     ${finding.type}")
                    appendLine("    DETAIL:       ${finding.value}")
                    appendLine("    SOURCE:       ${finding.sourceUrl ?: if (redacted) "Redacted" else "Self-supplied or locally derived"}")
                    if (!finding.evidenceSnippet.isNullOrBlank()) {
                        appendLine("    EVIDENCE:     ${finding.evidenceSnippet}")
                    }
                    appendLine("    ATTRIBUTION:  ${"%.0f".format(Locale.US, finding.confidence * 100)}% confidence")
                    appendLine("    NEXT ACTION:  ${finding.remediation}")
                    appendLine()
                }
            }

            if (profileSummaries.isNotEmpty()) {
                appendSection("PROFILE CHECKS")
                profileSummaries.forEachIndexed { index, line -> appendLine("[P${index + 1}] $line") }
                appendLine()
            }

            if (faceMatches.isNotEmpty()) {
                appendSection("LOCAL VISUAL CORRELATION")
                appendLine("Scores are supporting evidence and do not prove identity or account ownership.")
                faceMatches.forEachIndexed { index, match ->
                    appendLine("[V${index + 1}] score=${"%.3f".format(Locale.US, match.similarityScore)} ${match.profileUrl}")
                    appendLine("       ${match.warning}")
                }
                appendLine()
            }

            if (!entityGraphSummary.isNullOrBlank()) {
                appendSection("GRAPH PROJECTION")
                appendLine("These are persisted EntityGraph edges. They may include derived or resolved material and are not the canonical scanner assertion ledger.")
                appendLine()
                appendLine(entityGraphSummary.trim())
                appendLine()
            }

            if (canonicalRelationships.isNotEmpty()) {
                appendSection("CANONICAL SCANNER ASSERTIONS")
                appendLine("These assertions are retained separately from graph edges. They record scanner/plugin claims and do not, by themselves, prove identity or account ownership.")
                appendLine()
                canonicalRelationships.forEachIndexed { index, relationship ->
                    appendLine("[A${index + 1}] ${relationship.fromValue} —${relationship.relation}→ ${relationship.toValue}")
                    relationship.evidence?.takeIf(String::isNotBlank)?.let { evidence ->
                        appendLine("     EVIDENCE: $evidence")
                    }
                    if (relationship.evidenceIds.isNotEmpty()) {
                        appendLine("     EVIDENCE IDS: ${relationship.evidenceIds.joinToString(" | ")}")
                    }
                }
                appendLine()
            }

            if (breachDigests.isNotEmpty()) {
                appendSection("BREACH AND PUBLIC-EXPOSURE COVERAGE")
                breachDigests.forEachIndexed { index, line -> appendLine("[B${index + 1}] $line") }
                appendLine()
            }

            appendLine("───────────────────────────────────────────")
            appendLine("END OF REPORT")
            appendLine("The accompanying JSON package contains SHA-256 section hashes and report metadata.")
            appendLine("Public search, archives, HIBP, and configured remote AI may involve network access.")
            appendLine("Visual comparison runs locally and its transient crops and embeddings are discarded.")
            appendLine("Verify review-only evidence independently before taking action.")
            appendLine("═══════════════════════════════════════════")
        }
    }

    private fun StringBuilder.appendSection(title: String) {
        appendLine("───────────────────────────────────────────")
        appendLine(title)
        appendLine("───────────────────────────────────────────")
        appendLine()
    }

    private fun writeEvidencePackage(
        file: File,
        generatedAt: Instant,
        subjectName: String,
        findings: List<Finding>,
        profileSummaries: List<String>,
        aiSummary: String?,
        faceMatches: List<FaceConsistencyMatch>,
        entityGraphSummary: String?,
        canonicalRelationships: List<EvidenceRelationship>,
        breachDigests: List<String>,
        riskLevel: String?,
        reportText: String,
        redacted: Boolean
    ) {
        val sections = linkedMapOf(
            "findings" to json.encodeToString(findings),
            "profiles" to json.encodeToString(profileSummaries),
            "faceMatches" to json.encodeToString(faceMatches),
            "breachDigests" to json.encodeToString(breachDigests),
            "analysis" to (aiSummary ?: ""),
            "entityGraphSummary" to (entityGraphSummary ?: ""),
            "canonicalAssertions" to json.encodeToString(canonicalRelationships),
            "reportText" to reportText
        )
        val sectionHashes = sections.mapValues { sha256(it.value.toByteArray(Charsets.UTF_8)) }
        val manifestCanonical = sectionHashes.entries.joinToString("\n") { "${it.key}:${it.value}" }

        val root = buildJsonObject {
            put("schemaVersion", JsonPrimitive(EVIDENCE_PACKAGE_SCHEMA_VERSION))
            put("generatedAtUtc", JsonPrimitive(generatedAt.toString()))
            put("subject", JsonPrimitive(subjectName))
            put("riskLevel", JsonPrimitive(riskLevel ?: "Unknown"))
            put("redacted", JsonPrimitive(redacted))
            put("redactionMode", JsonPrimitive(if (redacted) ExportRedactionMode.ShareSafe.name else ExportRedactionMode.None.name))
            put("integrityAlgorithm", JsonPrimitive("SHA-256"))
            put("manifestSha256", JsonPrimitive(sha256(manifestCanonical.toByteArray(Charsets.UTF_8))))
            put("sectionHashes", buildJsonObject {
                sectionHashes.forEach { (name, hash) -> put(name, JsonPrimitive(hash)) }
            })
            put("findings", json.parseToJsonElement(sections.getValue("findings")))
            put("profileSummaries", json.parseToJsonElement(sections.getValue("profiles")))
            put("faceMatches", json.parseToJsonElement(sections.getValue("faceMatches")))
            put("breachDigests", json.parseToJsonElement(sections.getValue("breachDigests")))
            put("analysis", JsonPrimitive(aiSummary ?: ""))
            put("entityGraphSummary", JsonPrimitive(entityGraphSummary ?: ""))
            put("canonicalAssertions", json.parseToJsonElement(sections.getValue("canonicalAssertions")))
            put("reportText", JsonPrimitive(reportText))
        }
        file.writeText(json.encodeToString(root))
    }

    private fun writePdf(file: File, reportText: String) {
        val document = PdfDocument()
        val paint = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            isAntiAlias = true
        }
        val lines = reportText.lineSequence()
            .flatMap { wrapLine(it, PDF_LINE_CHARACTERS).asSequence() }
            .toList()
        val pageChunks = lines.chunked(PDF_LINES_PER_PAGE)
            .ifEmpty { listOf(listOf("Dossier privacy audit")) }

        try {
            pageChunks.forEachIndexed { index, pageLines ->
                val pageInfo = PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, index + 1).create()
                val page = document.startPage(pageInfo)
                var y = PDF_MARGIN.toFloat()
                pageLines.forEach { line ->
                    page.canvas.drawText(line, PDF_MARGIN.toFloat(), y, paint)
                    y += PDF_LINE_HEIGHT
                }
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun wrapLine(line: String, maxCharacters: Int): List<String> {
        if (line.length <= maxCharacters) return listOf(line)
        val result = mutableListOf<String>()
        var remaining = line
        while (remaining.length > maxCharacters) {
            val split = remaining.lastIndexOf(' ', maxCharacters).takeIf { it > 0 } ?: maxCharacters
            result += remaining.substring(0, split)
            remaining = remaining.substring(split).trimStart()
        }
        result += remaining
        return result
    }

    private fun sharePlainText(subjectName: String, reportText: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Dossier privacy audit — $subjectName")
            putExtra(Intent.EXTRA_TEXT, reportText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share privacy audit report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    internal data class PreparedExport(
        val subjectName: String,
        val findings: List<Finding>,
        val profileSummaries: List<String>,
        val aiSummary: String?,
        val faceMatches: List<FaceConsistencyMatch>,
        val entityGraphSummary: String?,
        val canonicalRelationships: List<EvidenceRelationship>,
        val breachDigests: List<String>,
        val redacted: Boolean
    )

    companion object {
        internal fun prepareExport(
            findings: List<Finding>,
            subjectName: String = "Unnamed subject",
            profileSummaries: List<String> = emptyList(),
            aiSummary: String? = null,
            faceMatches: List<FaceConsistencyMatch> = emptyList(),
            entityGraphSummary: String? = null,
            breachDigests: List<String> = emptyList(),
            redactionMode: ExportRedactionMode = ExportRedactionMode.None,
            canonicalRelationships: List<EvidenceRelationship> = emptyList()
        ): PreparedExport {
            if (redactionMode == ExportRedactionMode.None) {
                return PreparedExport(
                    subjectName = subjectName,
                    findings = findings,
                    profileSummaries = profileSummaries,
                    aiSummary = aiSummary,
                    faceMatches = faceMatches,
                    entityGraphSummary = entityGraphSummary,
                    canonicalRelationships = canonicalRelationships,
                    breachDigests = breachDigests,
                    redacted = false
                )
            }

            val redactedFindings = findings.mapIndexed { index, finding ->
                val replacement = "[redacted ${finding.type.name.lowercase(Locale.US)} ${index + 1}]"
                finding.copy(
                    value = replacement,
                    sourceUrl = null,
                    evidenceSnippet = null,
                    remediation = finding.remediation
                        .replace(finding.value, "[redacted]", ignoreCase = false)
                )
            }
            val redactedProfiles = profileSummaries.mapIndexed { index, _ ->
                "Profile check ${index + 1}: identifying details redacted"
            }
            val redactedFaces = faceMatches.mapIndexed { index, match ->
                match.copy(profileUrl = "[redacted visual source ${index + 1}]")
            }
            val redactedBreaches = breachDigests.mapIndexed { index, _ ->
                "Breach/exposure record ${index + 1}: identifying details redacted"
            }
            val redactedCanonicalRelationships = redactCanonicalRelationships(canonicalRelationships)

            return PreparedExport(
                subjectName = "Redacted subject",
                findings = redactedFindings,
                profileSummaries = redactedProfiles,
                aiSummary = if (aiSummary.isNullOrBlank()) null else
                    "Generated analysis omitted from share-safe export because it may reproduce identifying evidence values.",
                faceMatches = redactedFaces,
                entityGraphSummary = if (entityGraphSummary.isNullOrBlank()) null else
                    "Relationship details omitted from share-safe export because graph labels may contain identifying values.",
                canonicalRelationships = redactedCanonicalRelationships,
                breachDigests = redactedBreaches,
                redacted = true
            )
        }

        const val PDF_WIDTH = 595
        const val PDF_HEIGHT = 842
        const val PDF_MARGIN = 34
        const val PDF_LINE_HEIGHT = 12f
        const val PDF_LINES_PER_PAGE = 62
        const val PDF_LINE_CHARACTERS = 94
        const val EVIDENCE_PACKAGE_SCHEMA_VERSION = 3

        private fun redactCanonicalRelationships(
            relationships: List<EvidenceRelationship>
        ): List<EvidenceRelationship> {
            val endpointLabels = linkedMapOf<String, String>()
            fun redactEndpoint(raw: String): String {
                val key = raw.trim().lowercase(Locale.US)
                return endpointLabels.getOrPut(key) {
                    "[redacted assertion endpoint ${endpointLabels.size + 1}]"
                }
            }
            return relationships.map { relationship ->
                relationship.copy(
                    fromValue = redactEndpoint(relationship.fromValue),
                    toValue = redactEndpoint(relationship.toValue),
                    evidence = null,
                    evidenceIds = emptyList()
                )
            }
        }
    }
}
