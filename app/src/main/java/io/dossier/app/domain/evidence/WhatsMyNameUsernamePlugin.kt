package io.dossier.app.domain.evidence

import io.dossier.app.domain.discovery.DiscoveryScanPreferences
import io.dossier.app.domain.discovery.ProviderExecutionRuntime
import io.dossier.app.domain.discovery.ProviderRequestScheduler
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.discovery.WhatsMyNameCatalog
import io.dossier.app.domain.discovery.WhatsMyNameCatalogState
import io.dossier.app.domain.discovery.WhatsMyNameSite
import io.dossier.app.domain.discovery.WhatsMyNameResponseClassifier
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

class WhatsMyNameUsernamePlugin(
    private val runtime: ProviderExecutionRuntime = ProviderExecutionRuntime(
        scheduler = ProviderRequestScheduler(MAX_CONCURRENCY)
    ),
    private val timeSource: () -> Long = System::currentTimeMillis
) : ScannerPlugin {
    override val id: String = SOURCE_ID
    override val displayName: String = "WhatsMyName public username surface"

    override suspend fun scan(input: IdentityInput): EvidenceCollection {
        val handles = (listOfNotNull(input.primaryUsername) + input.usernames)
            .map(::normalizeHandle)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_HANDLES)

        if (handles.isEmpty()) {
            UsernameSurfaceRuntimeCache.replace(SOURCE_ID, emptyList())
            return EvidenceCollection()
        }

        val catalogState = WhatsMyNameCatalog.state
        if (catalogState !is WhatsMyNameCatalogState.Ready) {
            val reason = (catalogState as? WhatsMyNameCatalogState.Unavailable)?.reason ?: "Not initialized"
            val obs = handles.map { handle ->
                unavailable("WhatsMyName", handle, "", timeSource(), reason, SOURCE_ID)
            }
            UsernameSurfaceRuntimeCache.replace(SOURCE_ID, obs)
            return EvidenceCollection()
        }

        val mode = DiscoveryScanPreferences.selectedMode.value
        val eligibleSites = catalogState.sites.take(siteLimit(mode))

        val scanId = ScanCoordinatorRuntime.activeScanId()
            ?: ScanCoordinatorRuntime.claimProviderScanId()

        val operations = mutableListOf<Pair<WhatsMyNameSite, String>>()
        for (site in eligibleSites) {
            for (rawHandle in handles) {
                val cleanHandle = site.stripBadChar.fold(rawHandle) { current, character ->
                    current.replace(character.toString(), "")
                }
                if (cleanHandle.length < 2) continue
                operations.add(site to cleanHandle)
            }
        }

        val finalOps = operations.take(MAX_PLANNED_OPERATIONS)
        for (op in finalOps) {
            ScanCoordinatorRuntime.onProviderQueued(op.first.id, scanId)
        }

        val observations = try {
            finalOps.chunked(MAX_CONCURRENCY).flatMap { chunk ->
                coroutineScope {
                    chunk.map { (site, handle) ->
                        async(Dispatchers.IO) {
                            checkSite(site, handle, scanId)
                        }
                    }.awaitAll()
                }
            }
        } finally {
            runtime.scheduler.clearIdleState()
        }

        UsernameSurfaceRuntimeCache.replace(SOURCE_ID, observations)

        val evidence = observations
            .filter { it.state == UsernameSurfaceState.Present }
            .map { observation ->
                Evidence(
                    id = "wmn:${sha256("${observation.site}|${observation.username}|${observation.profileUrl}").take(32)}",
                    kind = EvidenceKind.Profile,
                    value = observation.profileUrl,
                    sourceUrl = observation.profileUrl,
                    snippet = "${observation.site} returned the WhatsMyName-defined public existence signal for @${observation.username}.",
                    confidence = observation.confidence.toFloat().coerceIn(0f, 1f),
                    risk = RiskLevel.Low,
                    signals = listOf(
                        "Direct GET check matched a WhatsMyName public existence rule",
                        "The handle was explicitly supplied to this assessment",
                        "Account existence does not establish ownership by the investigated identity"
                    ),
                    providerId = observation.providerId,
                    retrievedAtEpochMillis = observation.observedAtEpochMillis,
                    observedAtEpochMillis = observation.observedAtEpochMillis,
                    state = EvidenceState.Observed,
                    reliability = EvidenceReliability.DirectPublicProfile,
                    parserVersion = PARSER_VERSION,
                    historical = false
                )
            }

        val relationships = evidence.mapNotNull { ev ->
            val observation = observations.find { it.profileUrl == ev.value }
                ?: return@mapNotNull null
            EvidenceRelationship(
                fromValue = observation.username,
                toValue = observation.profileUrl,
                relation = "PUBLIC_PROFILE_EXISTS",
                evidence = "Direct public username-existence observation via WhatsMyName response rule; identity ownership unverified",
                evidenceIds = listOf(ev.id)
            )
        }

        return EvidenceCollection(
            evidence = evidence.distinctBy(Evidence::id),
            relationships = relationships.distinctBy { "${it.fromValue}|${it.toValue}|${it.relation}" }
        )
    }

    private suspend fun checkSite(
        site: WhatsMyNameSite,
        handle: String,
        scanId: ScanId
    ): UsernameSurfaceObservation {
        val checkUrl = site.uriCheck.replace("{account}", handle)
        var profileUrl = site.uriPretty.replace("{account}", handle)
        if (!isHttpsUrl(profileUrl)) {
            profileUrl = checkUrl
        }

        val providerDef = site.toProviderDefinition()
        val schedulingKey = providerKey(checkUrl) ?: providerDef.id

        return try {
            val result = runtime.execute(
                provider = providerDef,
                url = checkUrl,
                scanId = scanId,
                schedulingKey = schedulingKey,
                classifier = { _, observation ->
                    WhatsMyNameResponseClassifier.classify(site, observation)
                },
                maxBodyChars = MAX_RESPONSE_CHARS
            )

            val observedAt = timeSource()

            when (result.decision.state) {
                ProviderVerificationState.Present -> {
                    UsernameSurfaceObservation(
                        SOURCE_ID, site.name, handle, profileUrl,
                        UsernameSurfaceState.Present, 0.68,
                        "HTTP ${result.statusCode} matched the site's published existence rule", observedAt, site.id
                    )
                }
                ProviderVerificationState.NotFound -> {
                    UsernameSurfaceObservation(
                        SOURCE_ID, site.name, handle, profileUrl,
                        UsernameSurfaceState.Absent, 0.90,
                        "HTTP ${result.statusCode} matched the site's published missing-account rule", observedAt, site.id
                    )
                }
                ProviderVerificationState.RateLimited -> {
                    unavailable(site.name, handle, profileUrl, observedAt, "Provider rate-limited the public check", site.id)
                }
                ProviderVerificationState.AuthenticationRequired -> {
                    unavailable(site.name, handle, profileUrl, observedAt, "Provider requires authentication", site.id)
                }
                ProviderVerificationState.AutomationChallenged -> {
                    unavailable(site.name, handle, profileUrl, observedAt, "Provider returned an automation challenge", site.id)
                }
                else -> {
                    unavailable(
                        site.name, handle, profileUrl, observedAt,
                        "Response did not conclusively match the published exists/missing rules (HTTP ${result.statusCode})", site.id
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            unavailable(site.name, handle, profileUrl, timeSource(), "Provider could not be conclusively checked", site.id)
        }
    }

    private fun unavailable(
        siteName: String,
        handle: String,
        profileUrl: String,
        observedAt: Long,
        reason: String,
        providerId: String
    ) = UsernameSurfaceObservation(
        SOURCE_ID, siteName, handle, profileUrl,
        UsernameSurfaceState.Unavailable, 0.0, reason, observedAt, providerId
    )

    private fun providerKey(url: String): String? {
        return runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
    }

    private fun normalizeHandle(raw: String): String = raw.trim()
        .removePrefix("@")
        .removePrefix("u/")
        .lowercase(Locale.ROOT)
        .takeIf { it.matches(HANDLE) }
        .orEmpty()

    private fun isHttpsUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private fun siteLimit(mode: ScanMode): Int = when (mode) {
        ScanMode.Quick -> 50
        ScanMode.Standard -> 200
        ScanMode.Deep -> 500
        ScanMode.Exhaustive -> Int.MAX_VALUE
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SOURCE_ID = "whatsmyname-direct"
        const val MAX_HANDLES = 3
        const val MAX_CONCURRENCY = 6
        const val MAX_PLANNED_OPERATIONS = MAX_HANDLES * 644
        const val MAX_RESPONSE_CHARS = 192 * 1024
        const val PARSER_VERSION = "whatsmyname-pinned-e62338e-v3"
        val HANDLE = Regex("[a-z0-9_][a-z0-9_.-]{1,63}")
    }
}
