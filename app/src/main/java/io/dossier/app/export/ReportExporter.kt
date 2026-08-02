package io.dossier.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Produces human-readable and machine-verifiable local report exports. */
class ReportExporter(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun exportToJson(findings: List<Finding>): String = runCatching {
        json.encodeToString(findings)
    }.getOrDefault("[]")

    fun shareReport(
        findings: List<Finding>,
        subjectName: String = "UNKNOWN SUBJECT",
        profileSummaries: List<String> = emptyList(),
        aiSummary: String? = null,
        faceMatches: List<FaceConsistencyMatch> = emptyList(),
        entityGraphSummary: String? = null,
        breachDigests: List<String> = emptyList(),
        riskLevel: String? = null
    ) {
        val generatedAt = Instant.now()
        val reportText = buildReportText(
            findings,
            subjectName,
            profileSummaries,
            aiSummary,
            faceMatches,
            entityGraphSummary,
            breachDigests,
            riskLevel,
            generatedAt
        )

        val attachments = runCatching {
            val directory = File(context.cacheDir, "reports").also { it.mkdirs() }
            val safeName = subjectName.replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-').ifBlank { "subject" }.take(40)
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now())
            val base = "dossier-$safeName-$timestamp"

            val pdfFile = File(directory, "$base.pdf")
            writePdf(pdfFile, reportText)
            val jsonFile = File(directory, "$base.evidence.json")
            writeEvidencePackage(
                file = jsonFile,
                generatedAt = generatedAt,
                subjectName = subjectName,
                findings = findings,
                profileSummaries = profileSummaries,
                aiSummary = aiSummary,
                faceMatches = faceMatches,
                entityGraphSummary = entityGraphSummary,
                breachDigests = breachDigests,
                riskLevel = riskLevel,
                reportText = reportText
            )
            listOf(pdfFile, jsonFile)
        }.getOrDefault(emptyList())

        if (attachments.isEmpty()) {
            sharePlainText(subjectName, reportText)
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
            putExtra(Intent.EXTRA_SUBJECT, "DOSSIER // $subjectName // EVIDENCE PACKAGE")
            putExtra(Intent.EXTRA_TEXT, reportText)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("Dossier report", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        context.startActivity(
            Intent.createChooser(intent, "Share Dossier evidence package").apply {
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
        breachDigests: List<String>,
        riskLevel: String?,
        generatedAt: Instant
    ): String {
        val localTime = LocalDateTime.now()
        val prepDate = localTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val fileNumber = "DS-${localTime.toLocalDate()}-" + subjectName.replace(" ", "").take(6).uppercase(Locale.US)

        return buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("  DOSSIER  //  CONFIDENTIAL")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            appendLine("FILE NO.  $fileNumber")
            appendLine("SUBJECT:  $subjectName")
            appendLine("PREPARED: $prepDate")
            appendLine("UTC:      $generatedAt")
            appendLine("STATUS:   Authorized self-audit")
            if (!riskLevel.isNullOrBlank()) appendLine("THREAT:   $riskLevel")
            appendLine()

            if (!aiSummary.isNullOrBlank()) {
                appendSection("ANALYSIS")
                appendLine(aiSummary.trim())
                appendLine()
            }

            appendSection("THREAT ASSESSMENT / FINDINGS")
            if (findings.isEmpty()) {
                appendLine("No reportable exposure finding was detected in the sources inspected.")
                appendLine("This is not proof that no public exposure exists.")
                appendLine()
            } else {
                appendLine("${findings.size} finding(s) on record:")
                appendLine()
                findings.forEachIndexed { index, finding ->
                    appendLine("[${index + 1}] CLASSIFICATION: ${finding.risk}")
                    appendLine("    CATEGORY:     ${finding.type}")
                    appendLine("    DETAIL:       ${finding.value}")
                    appendLine("    SOURCE:       ${finding.sourceUrl ?: "Self-supplied or derived"}")
                    if (!finding.evidenceSnippet.isNullOrBlank()) {
                        appendLine("    EVIDENCE:     ${finding.evidenceSnippet}")
                    }
                    appendLine("    CONFIDENCE:   ${"%.0f".format(Locale.US, finding.confidence * 100)}%")
                    appendLine("    ACTION:       ${finding.remediation}")
                    appendLine()
                }
            }

            if (profileSummaries.isNotEmpty()) {
                appendSection("PROFILE CANDIDATES")
                profileSummaries.forEachIndexed { index, line -> appendLine("[P${index + 1}] $line") }
                appendLine()
            }

            if (faceMatches.isNotEmpty()) {
                appendSection("LOCAL VISUAL CONSISTENCY")
                faceMatches.forEachIndexed { index, match ->
                    appendLine("[F${index + 1}] score=${"%.3f".format(Locale.US, match.similarityScore)} ${match.profileUrl}")
                    appendLine("       ${match.warning}")
                }
                appendLine()
            }

            if (!entityGraphSummary.isNullOrBlank()) {
                appendSection("ENTITY GRAPH")
                appendLine(entityGraphSummary.trim())
                appendLine()
            }

            if (breachDigests.isNotEmpty()) {
                appendSection("BREACH / PUBLIC EXPOSURE COVERAGE")
                breachDigests.forEachIndexed { index, line -> appendLine("[B${index + 1}] $line") }
                appendLine()
            }

            appendLine("───────────────────────────────────────────")
            appendLine("END OF FILE")
            appendLine("The accompanying JSON package contains SHA-256 section hashes and report metadata.")
            appendLine("Public web evidence and configured remote AI may involve network access.")
            appendLine("Visual comparison runs on-device using an installed model or the built-in appearance descriptor.")
            appendLine("Confirm ownership manually before acting on review-only evidence.")
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
        breachDigests: List<String>,
        riskLevel: String?,
        reportText: String
    ) {
        val sections = linkedMapOf(
            "findings" to json.encodeToString(findings),
            "profiles" to json.encodeToString(profileSummaries),
            "faceMatches" to json.encodeToString(faceMatches),
            "breachDigests" to json.encodeToString(breachDigests),
            "analysis" to (aiSummary ?: ""),
            "entityGraphSummary" to (entityGraphSummary ?: ""),
            "reportText" to reportText
        )
        val sectionHashes = sections.mapValues { sha256(it.value.toByteArray(Charsets.UTF_8)) }
        val manifestCanonical = sectionHashes.entries.joinToString("\n") { "${it.key}:${it.value}" }

        val root = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("generatedAtUtc", JsonPrimitive(generatedAt.toString()))
            put("subject", JsonPrimitive(subjectName))
            put("riskLevel", JsonPrimitive(riskLevel ?: "Unknown"))
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
        val lines = reportText.lineSequence().flatMap { wrapLine(it, PDF_LINE_CHARACTERS).asSequence() }.toList()
        val pageChunks = lines.chunked(PDF_LINES_PER_PAGE).ifEmpty { listOf(listOf("Dossier report")) }

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
            putExtra(Intent.EXTRA_SUBJECT, "DOSSIER // $subjectName // CONFIDENTIAL")
            putExtra(Intent.EXTRA_TEXT, reportText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share Dossier").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PDF_WIDTH = 595
        const val PDF_HEIGHT = 842
        const val PDF_MARGIN = 34
        const val PDF_LINE_HEIGHT = 12f
        const val PDF_LINES_PER_PAGE = 62
        const val PDF_LINE_CHARACTERS = 94
    }
}
