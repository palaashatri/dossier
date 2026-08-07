package io.dossier.app.domain.scanner

import android.content.Context
import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small local resume marker for an interrupted/cancelled audit.
 *
 * This is not a saved case. It stores only seed input, the existing extended-
 * discovery flag, and the selected Discovery Fabric scan mode so resuming a
 * Deep/Exhaustive scan cannot silently fall back to Standard. Older markers
 * migrate through the default Standard mode.
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
    val deepResearch: Boolean,
    val scanMode: ScanMode = ScanMode.Standard
)

internal class ScanResumeStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "dossier_resume"))

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }
    private val file: File
        get() = File(dir, "dossier_resume.json")

    fun save(input: IdentityInput, deepResearch: Boolean): Boolean = runCatching {
        dir.mkdirs()
        val marker = ResumeMarker(
            fullName = input.fullName,
            primaryUsername = input.primaryUsername,
            usernames = input.usernames,
            emails = input.emails,
            phones = input.phones,
            organizations = input.organizations,
            locations = input.locations,
            profileUrls = input.profileUrls,
            deepResearch = deepResearch,
            scanMode = DiscoveryScanPreferences.selectedMode.value
        )
        file.writeText(json.encodeToString(marker))
    }.isSuccess

    fun load(): Pair<IdentityInput, Boolean>? = runCatching {
        if (!file.exists()) return null
        val marker = json.decodeFromString<ResumeMarker>(file.readText())
        DiscoveryScanPreferences.setMode(marker.scanMode)
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
