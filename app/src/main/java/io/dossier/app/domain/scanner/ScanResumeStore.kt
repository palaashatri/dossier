package io.dossier.app.domain.scanner

import android.content.Context
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Resumable-scan checkpoint (ROADMAP M16).
 *
 * Persists only the *resume point* — the identity input and the deep-research
 * flag — to a single small JSON file in private storage. This is deliberately
 * NOT a full saved case (that is the explicit M13 "Save Case" action); it just
 * lets the Scan screen offer "Resume last scan" so an interrupted/cancelled run
 * can be continued without re-typing the subject. No cloud, opt-in write.
 */
@Serializable
private data class ResumeMarker(
    val fullName: String,
    val primaryUsername: String?,
    val usernames: List<String>,
    val emails: List<String>,
    val phones: List<String>,
    val organizations: List<String>,
    val locations: List<String>,
    val profileUrls: List<String>,
    val deepResearch: Boolean
)

internal class ScanResumeStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "dossier_resume"))

    private val json = Json { prettyPrint = false }
    private val file: File
        get() = File(dir, "dossier_resume.json")

    fun save(input: IdentityInput, deepResearch: Boolean): Boolean = runCatching {
        val marker = ResumeMarker(
            fullName = input.fullName,
            primaryUsername = input.primaryUsername,
            usernames = input.usernames,
            emails = input.emails,
            phones = input.phones,
            organizations = input.organizations,
            locations = input.locations,
            profileUrls = input.profileUrls,
            deepResearch = deepResearch
        )
        file.writeText(json.encodeToString(marker))
    }.isSuccess

    fun load(): Pair<IdentityInput, Boolean>? = runCatching {
        if (!file.exists()) return null
        val marker = json.decodeFromString<ResumeMarker>(file.readText())
        IdentityInput(
            fullName = marker.fullName,
            primaryUsername = marker.primaryUsername,
            usernames = marker.usernames,
            emails = marker.emails,
            phones = marker.phones,
            organizations = marker.organizations,
            locations = marker.locations,
            profileUrls = marker.profileUrls
        ) to marker.deepResearch
    }.getOrNull()

    fun clear(): Boolean = runCatching { file.delete() }.getOrDefault(false)
}
