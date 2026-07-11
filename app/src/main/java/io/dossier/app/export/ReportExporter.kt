package io.dossier.app.export

import android.content.Context
import android.content.Intent
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ReportExporter(private val context: Context) {
    private val json = Json { prettyPrint = true }

    fun exportToJson(findings: List<Finding>): String {
        return try {
            json.encodeToString(findings)
        } catch (e: Exception) {
            e.printStackTrace()
            "[]"
        }
    }

    /**
     * Shares a plain-text intelligence brief — classified-file aesthetic with
     * subject line, threat assessment, findings, profiles, face matches,
     * optional entity graph / breach digests, and AI summary.
     *
     * Network-sourced evidence may be included; face comparison (when present)
     * was computed locally. Footer is honest about that mix.
     */
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
        val now = java.time.LocalDateTime.now()
        val prepDate = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val fileNumber = "DS-${now.toLocalDate()}-" + subjectName.replace(" ", "").take(6).uppercase()

        val sb = StringBuilder()
        sb.append("═══════════════════════════════════════════\n")
        sb.append("  DOSSIER  //  CONFIDENTIAL\n")
        sb.append("═══════════════════════════════════════════\n\n")
        sb.append("FILE NO.  $fileNumber\n")
        sb.append("SUBJECT:  $subjectName\n")
        sb.append("PREPARED: $prepDate\n")
        sb.append("STATUS:   Authorized research / self-audit demo\n")
        if (!riskLevel.isNullOrBlank()) {
            sb.append("THREAT:   $riskLevel\n")
        }
        sb.append("\n")

        // --- AI summary ---
        if (!aiSummary.isNullOrBlank()) {
            sb.append("───────────────────────────────────────────\n")
            sb.append("AI ANALYSIS\n")
            sb.append("───────────────────────────────────────────\n\n")
            sb.append(aiSummary.trim())
            sb.append("\n\n")
        }

        // --- Threat / findings ---
        sb.append("───────────────────────────────────────────\n")
        sb.append("THREAT ASSESSMENT / FINDINGS\n")
        sb.append("───────────────────────────────────────────\n\n")

        if (findings.isEmpty()) {
            sb.append("No exposure findings detected. Subject's digital footprint is minimal or unverified.\n\n")
        } else {
            sb.append("${findings.size} finding(s) on record:\n\n")
            findings.forEachIndexed { i, finding ->
                sb.append("[${i + 1}] CLASSIFICATION: ${finding.risk}\n")
                sb.append("    CATEGORY:     ${finding.type}\n")
                sb.append("    DETAIL:       ${finding.value}\n")
                sb.append("    SOURCE:       ${finding.sourceUrl ?: "Self-supplied"}\n")
                if (!finding.evidenceSnippet.isNullOrBlank()) {
                    sb.append("    EVIDENCE:     \"${finding.evidenceSnippet}\"\n")
                }
                sb.append("    CONFIDENCE:   ${"%.0f".format(finding.confidence * 100)}%\n")
                sb.append("    RECOMMENDED ACTION: ${finding.remediation}\n")
                sb.append("\n")
            }
        }

        // --- Profile candidates ---
        if (profileSummaries.isNotEmpty()) {
            sb.append("───────────────────────────────────────────\n")
            sb.append("SUBJECT PROFILE CANDIDATES\n")
            sb.append("───────────────────────────────────────────\n\n")
            profileSummaries.forEachIndexed { i, line ->
                sb.append("[P${i + 1}] $line\n")
            }
            sb.append("\n")
        }

        // --- Face consistency ---
        if (faceMatches.isNotEmpty()) {
            sb.append("───────────────────────────────────────────\n")
            sb.append("VISUAL CONSISTENCY (local face comparison)\n")
            sb.append("───────────────────────────────────────────\n\n")
            faceMatches.forEachIndexed { i, match ->
                sb.append("[F${i + 1}] score=${"%.3f".format(match.similarityScore)}  ${match.profileUrl}\n")
                if (match.warning.isNotBlank()) {
                    sb.append("       ${match.warning}\n")
                }
            }
            sb.append("\n")
        }

        // --- Entity graph ---
        if (!entityGraphSummary.isNullOrBlank()) {
            sb.append("───────────────────────────────────────────\n")
            sb.append("ENTITY GRAPH\n")
            sb.append("───────────────────────────────────────────\n\n")
            sb.append(entityGraphSummary.trim())
            sb.append("\n\n")
        }

        // --- Breach digests ---
        if (breachDigests.isNotEmpty()) {
            sb.append("───────────────────────────────────────────\n")
            sb.append("BREACH EXPOSURE\n")
            sb.append("───────────────────────────────────────────\n\n")
            breachDigests.forEachIndexed { i, line ->
                sb.append("[B${i + 1}] $line\n")
            }
            sb.append("\n")
        }

        sb.append("───────────────────────────────────────────\n")
        sb.append("END OF FILE\n")
        sb.append("Generated by Dossier (academic / demo). Evidence may include\n")
        sb.append("public web fetches, search indexes, optional HIBP, and optional\n")
        sb.append("remote AI. Face comparison (if any) ran on-device with an imported model.\n")
        sb.append("Not fully offline. Confirm ownership manually before acting.\n")
        sb.append("═══════════════════════════════════════════\n")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DOSSIER // $subjectName // CONFIDENTIAL")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "Share Dossier").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
