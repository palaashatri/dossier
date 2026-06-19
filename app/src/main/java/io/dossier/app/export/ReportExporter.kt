package io.dossier.app.export

import android.content.Context
import android.content.Intent
import io.dossier.app.domain.model.Finding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ReportExporter(private val context: Context) {
    private val json = Json { prettyPrint = true }

    fun exportToJson(findings: List<Finding>): String {
        return try {
            json.encodeToString(findings)
        } catch (e: Exception) {
            android.util.Log.e("ReportExporter", "Failed to encode findings to JSON", e)
            "[]"
        }
    }

    /**
     * Shares the dossier as a plain-text intelligence brief — the same
     * classified-file aesthetic as the on-screen report (file number, SUBJECT
     * line, THREAT ASSESSMENT, numbered findings), readable in any text app.
     */
    fun shareReport(findings: List<Finding>, subjectName: String = "UNKNOWN SUBJECT") {
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
        sb.append("STATUS:   Self-audit  //  Local processing only\n\n")
        sb.append("───────────────────────────────────────────\n")
        sb.append("THREAT ASSESSMENT\n")
        sb.append("───────────────────────────────────────────\n\n")

        if (findings.isEmpty()) {
            sb.append("No exposure findings detected. Subject's digital footprint is minimal.\n")
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
                sb.append("    RECOMMENDED ACTION: ${finding.remediation}\n")
                sb.append("\n")
            }
        }

        sb.append("───────────────────────────────────────────\n")
        sb.append("END OF FILE  //  This dossier was generated locally on-device.\n")
        sb.append("No telemetry, no backend, no cloud lookup.\n")
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
        // Guard against devices with no app capable of handling ACTION_SEND.
        if (chooser.resolveActivity(context.packageManager) == null) {
            android.util.Log.w("ReportExporter", "No app available to share the dossier")
            return
        }
        try {
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w("ReportExporter", "Share activity not found", e)
        }
    }
}
