package io.dossier.app.domain.case

import android.content.Context
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local, on-device case store (ROADMAP M13/M14).
 *
 * Cases are plain JSON files in the app's private storage directory — no cloud,
 * no accounts, no telemetry (Out-of-Scope guarantees). Saving is always an
 * explicit user action; nothing is written during a normal scan.
 */
class CaseStore(private val context: Context) {

    private val json = Json { prettyPrint = false }
    private val dir: File
        get() = File(context.filesDir, "dossier_cases").also { it.mkdirs() }

    fun save(case: DossierCase): Boolean = runCatching {
        File(dir, "${case.caseId}.json").writeText(json.encodeToString(case))
    }.isSuccess

    fun load(caseId: String): DossierCase? = runCatching {
        val file = File(dir, "$caseId.json")
        if (!file.exists()) null else json.decodeFromString<DossierCase>(file.readText())
    }.getOrNull()

    fun list(): List<DossierCase> = runCatching {
        dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<DossierCase>(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }.getOrElse { emptyList() }

    fun delete(caseId: String): Boolean = runCatching {
        File(dir, "$caseId.json").delete()
    }.getOrDefault(false)

    fun clear(): Boolean = runCatching {
        dir.listFiles()?.forEach { it.delete() }; true
    }.getOrDefault(false)
}
