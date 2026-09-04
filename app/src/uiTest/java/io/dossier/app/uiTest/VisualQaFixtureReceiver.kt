package io.dossier.app.uiTest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.dossier.app.data.local.UsageNoticeStore
import io.dossier.app.domain.analysis.OsintPostProcessor
import io.dossier.app.domain.case.CaseScanHistoryEntry
import io.dossier.app.domain.case.CaseStore
import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanCoordinatorRuntime
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.ScanMode
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.RelationshipType
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import io.dossier.app.domain.evidence.ExposureEngine
import io.dossier.app.domain.scanner.BackgroundScanResultStore
import io.dossier.app.domain.scanner.ScanLifecycleReadResult
import io.dossier.app.domain.scanner.ScanLifecycleStore
import io.dossier.app.domain.scanner.ScanLifecycleWriteResult

/**
 * Test-only seed entry point for visual QA on the uiTest APK.
 *
 * The component is deliberately compiled out of debug/release. It writes the
 * same encrypted transient result and saved-case formats used by production,
 * but all values are reserved `.test` fixtures and must never be treated as
 * live evidence.
 *
 * Invoke with:
 * `adb shell am broadcast -n io.dossier.app/io.dossier.app.uiTest.VisualQaFixtureReceiver `
 * `-a io.dossier.app.action.SEED_VISUAL_QA_FIXTURE`
 *
 * Then launch the real navigation host explicitly with:
 * `adb shell am start -W -n io.dossier.app/.MainActivity`
 */
class VisualQaFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PROVIDER_PROGRESS) {
            seedProviderProgress()
            return
        }
        if (intent.action != ACTION_SEED) return

        val appContext = context.applicationContext
        val fixtureCase = VisualQaFixture.caseSnapshot()
        val caseStore = CaseStore(appContext)
        val resultStore = BackgroundScanResultStore(appContext)
        val lifecycleStore = ScanLifecycleStore(appContext)

        // A fixture invocation is an explicit reset of the uiTest app's local
        // state. It does not touch production code paths or remote services.
        clearLifecycleState(appContext, lifecycleStore)
        resultStore.clear()
        caseStore.clear()
        UsageNoticeStore.accept(appContext)

        check(caseStore.save(fixtureCase)) { "Unable to persist visual-QA encrypted case" }
        check(
            resultStore.save(
                workId = VisualQaFixture.WORK_ID,
                dossierCase = fixtureCase,
                analysis = VisualQaFixture.analysisBundle()
            )
        ) { "Unable to persist visual-QA encrypted transient result" }
    }

    /**
     * A previous scan can leave a terminal owner marker behind. The production
     * result projection correctly hides a result whose owner does not match
     * that marker, so fixture reset must clear the lifecycle marker as well as
     * the encrypted result. Invalid records cannot be cleared by exact-record
     * CAS and therefore reset the fixture-only preference file wholesale.
     */
    private fun clearLifecycleState(
        context: Context,
        lifecycleStore: ScanLifecycleStore
    ) {
        when (val current = lifecycleStore.read()) {
            is ScanLifecycleReadResult.Available -> {
                val cleared = lifecycleStore.clear(current.record)
                check(
                    cleared == ScanLifecycleWriteResult.Cleared ||
                        cleared == ScanLifecycleWriteResult.Missing
                ) { "Unable to clear visual-QA lifecycle marker" }
            }
            ScanLifecycleReadResult.Missing -> Unit
            is ScanLifecycleReadResult.Invalid,
            ScanLifecycleReadResult.StorageFailure -> {
                check(
                    context.getSharedPreferences(LIFECYCLE_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                ) { "Unable to clear invalid visual-QA lifecycle marker" }
            }
        }

        // Legacy active markers are intentionally not part of ScanLifecycleStore
        // read()'s canonical record detection, so remove them after every reset.
        val preferences = context.getSharedPreferences(LIFECYCLE_PREFS, Context.MODE_PRIVATE)
        if (preferences.contains(LEGACY_ACTIVE_OWNER) || preferences.contains(LEGACY_ACTIVE)) {
            check(
                preferences.edit()
                    .remove(LEGACY_ACTIVE_OWNER)
                    .remove(LEGACY_ACTIVE)
                    .commit()
            ) { "Unable to clear legacy visual-QA lifecycle marker" }
        }
    }

    private fun seedProviderProgress() {
        val scanId = ScanCoordinatorRuntime.resetCounts(ScanId(PROVIDER_PROGRESS_SCAN_ID))
        val providers = listOf(
            "github",
            "gitlab",
            "codeberg",
            "reddit",
            "mastodon",
            "medium",
            "devto",
            "keybase",
            "twitch",
            "vimeo",
            "soundcloud",
            "behance"
        )
        providers.forEach { ScanCoordinatorRuntime.onProviderQueued(it, scanId) }
        providers.take(10).forEach { ScanCoordinatorRuntime.onProviderStarted(it, scanId = scanId) }
        providers.take(7).forEach {
            ScanCoordinatorRuntime.onProviderCompleted(
                it,
                ProviderVerificationState.NotFound,
                latencyMs = 120,
                scanId = scanId
            )
        }
        providers.drop(7).take(2).forEach {
            ScanCoordinatorRuntime.onProviderUnavailable(
                it,
                ProviderVerificationState.RateLimited,
                latencyMs = 240,
                scanId = scanId
            )
        }
    }

    private companion object {
        const val ACTION_SEED = "io.dossier.app.action.SEED_VISUAL_QA_FIXTURE"
        const val ACTION_PROVIDER_PROGRESS = "io.dossier.app.action.SHOW_PROVIDER_PROGRESS_QA"
        const val PROVIDER_PROGRESS_SCAN_ID = "00000000-0000-4000-8000-000000000300"
        const val LIFECYCLE_PREFS = "dossier-background-work"
        const val LEGACY_ACTIVE_OWNER = "active_owner"
        const val LEGACY_ACTIVE = "active"
    }
}

/** All fixture values are deterministic and use reserved `.test` domains. */
private object VisualQaFixture {
    const val WORK_ID = "00000000-0000-4000-8000-000000000100"
    private const val OBSERVED_AT = 1_755_000_000_000L

    private val input = IdentityInput(
        fullName = "Jane Example",
        aliases = listOf("J. Example"),
        emails = listOf("jane@example.test"),
        locations = listOf("Example City"),
        organizations = listOf("Example Labs"),
        usernames = listOf("jane_example"),
        primaryUsername = "jane_example",
        profileUrls = listOf("https://example.test/about")
    )

    private val emailFinding = Finding(
        type = FindingType.Email,
        value = "jane@example.test",
        sourceUrl = "https://example.test/contact",
        evidenceSnippet = "Public contact page lists the supplied email address.",
        confidence = 0.96f,
        risk = RiskLevel.High,
        remediation = "Remove the address from public pages or use a forwarding alias."
    )

    private val usernameFinding = Finding(
        type = FindingType.UsernameReuse,
        value = "jane_example",
        sourceUrl = "https://profile.example.test/jane_example",
        evidenceSnippet = "The same supplied handle appears on a second public profile.",
        confidence = 0.82f,
        risk = RiskLevel.Medium,
        remediation = "Use distinct handles where cross-linking is not intended."
    )

    private val profileFinding = Finding(
        type = FindingType.Profile,
        value = "Jane Example · Example Profile",
        sourceUrl = "https://profile.example.test/jane_example",
        evidenceSnippet = "Display name and supplied username match on a public profile.",
        confidence = 0.78f,
        risk = RiskLevel.Medium,
        remediation = "Review the profile's public visibility and linked contact details."
    )

    private val profiles = listOf(
        ProfileScanResult(
            candidate = UsernameCandidate(
                username = "jane_example",
                platform = Platform.Website,
                url = "https://profile.example.test/jane_example",
                matchType = UsernameMatchType.Exact,
                confidence = 0.96f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Jane Example",
            bio = "Example Labs · privacy research · public contact page",
            profileImageUrl = "https://example.test/avatar.png",
            links = listOf("https://example.test/about"),
            extractedText = "Jane Example writes about privacy research. Contact Jane at jane@example.test.",
            findings = listOf(usernameFinding, profileFinding),
            confidenceSignals = listOf("exact username", "matching display name", "linked .test website"),
            verified = true,
            verificationStatus = "Direct public profile fixture",
            provenance = "uiTest synthetic fixture"
        ),
        ProfileScanResult(
            candidate = UsernameCandidate(
                username = "jane_example",
                platform = Platform.GitHub,
                url = "https://code.example.test/jane_example",
                matchType = UsernameMatchType.Exact,
                confidence = 0.62f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "J. Example",
            bio = "Public code samples for Example Labs",
            profileImageUrl = null,
            links = emptyList(),
            extractedText = "Privacy tooling and reproducible examples.",
            findings = listOf(usernameFinding),
            confidenceSignals = listOf("exact username"),
            verified = false,
            verificationStatus = "Candidate; manual attribution review required",
            provenance = "uiTest synthetic fixture"
        )
    )

    private val evidence = listOf(
        Evidence(
            id = "ev2:visual-qa-email",
            kind = EvidenceKind.Email,
            value = "jane@example.test",
            sourceUrl = "https://example.test/contact",
            snippet = "Public contact page lists the supplied email address.",
            confidence = 0.96f,
            risk = RiskLevel.High,
            providerId = "synthetic-visual-qa",
            retrievedAtEpochMillis = OBSERVED_AT,
            observedAtEpochMillis = OBSERVED_AT,
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.DirectPersonalWebsite,
            parserVersion = "visual-qa-1"
        ),
        Evidence(
            id = "ev2:visual-qa-profile",
            kind = EvidenceKind.Profile,
            value = "Jane Example · Example Profile",
            sourceUrl = "https://profile.example.test/jane_example",
            snippet = "Display name and supplied username match on a public profile.",
            confidence = 0.78f,
            risk = RiskLevel.Medium,
            providerId = "synthetic-visual-qa",
            retrievedAtEpochMillis = OBSERVED_AT + 60_000,
            observedAtEpochMillis = OBSERVED_AT + 60_000,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.DirectPublicProfile,
            parserVersion = "visual-qa-1"
        ),
        Evidence(
            id = "ev2:visual-qa-style-a",
            kind = EvidenceKind.PublicSearchEvidence,
            value = "privacy research notes",
            sourceUrl = "https://profile.example.test/jane_example",
            snippet = "Privacy research notes and reproducible examples for public readers.",
            confidence = 0.60f,
            risk = RiskLevel.Low,
            providerId = "synthetic-visual-qa",
            retrievedAtEpochMillis = OBSERVED_AT + 120_000,
            observedAtEpochMillis = OBSERVED_AT + 120_000,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.DirectPublicProfile,
            parserVersion = "visual-qa-1"
        ),
        Evidence(
            id = "ev2:visual-qa-style-b",
            kind = EvidenceKind.PublicSearchEvidence,
            value = "privacy tooling examples",
            sourceUrl = "https://code.example.test/jane_example",
            snippet = "Privacy tooling examples and reproducible research notes.",
            confidence = 0.60f,
            risk = RiskLevel.Low,
            providerId = "synthetic-visual-qa",
            retrievedAtEpochMillis = OBSERVED_AT + 180_000,
            observedAtEpochMillis = OBSERVED_AT + 180_000,
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.DirectPublicProfile,
            parserVersion = "visual-qa-1"
        )
    )

    fun caseSnapshot(): DossierCase {
        val graphEvidenceIds = evidence.map(Evidence::id)
        val graph = EntityGraph(
            entities = listOf(
                DossierEntity(
                    id = "subject-jane-example",
                    type = EntityType.Person,
                    label = "Jane Example",
                    confidence = 1.0f,
                    state = GraphNodeState.Confirmed,
                    evidenceIds = graphEvidenceIds,
                    firstObservedAtEpochMillis = OBSERVED_AT,
                    lastObservedAtEpochMillis = OBSERVED_AT + 180_000
                ),
                DossierEntity(
                    id = "username-jane-example",
                    type = EntityType.Username,
                    label = "jane_example",
                    confidence = 0.96f,
                    state = GraphNodeState.High,
                    evidenceIds = listOf("ev2:visual-qa-profile")
                ),
                DossierEntity(
                    id = "email-jane-example",
                    type = EntityType.Email,
                    label = "jane@example.test",
                    confidence = 1.0f,
                    state = GraphNodeState.Confirmed,
                    evidenceIds = listOf("ev2:visual-qa-email")
                ),
                DossierEntity(
                    id = "profile-example",
                    type = EntityType.Profile,
                    label = "Example Profile",
                    confidence = 0.78f,
                    sourceUrls = listOf("https://profile.example.test/jane_example"),
                    state = GraphNodeState.Medium,
                    evidenceIds = listOf("ev2:visual-qa-profile")
                ),
                DossierEntity(
                    id = "website-example",
                    type = EntityType.Website,
                    label = "example.test",
                    confidence = 0.72f,
                    sourceUrls = listOf("https://example.test/about"),
                    state = GraphNodeState.Medium,
                    evidenceIds = listOf("ev2:visual-qa-email")
                )
            ),
            edges = listOf(
                DossierEdge(
                    fromId = "subject-jane-example",
                    toId = "username-jane-example",
                    relation = "HAS_USERNAME",
                    relationType = RelationshipType.HAS_USERNAME,
                    evidenceIds = listOf("ev2:visual-qa-profile"),
                    confidence = 0.96f
                ),
                DossierEdge(
                    fromId = "subject-jane-example",
                    toId = "email-jane-example",
                    relation = "HAS_EMAIL",
                    relationType = RelationshipType.HAS_EMAIL,
                    evidenceIds = listOf("ev2:visual-qa-email"),
                    confidence = 1.0f
                ),
                DossierEdge(
                    fromId = "subject-jane-example",
                    toId = "profile-example",
                    relation = "USES_ACCOUNT",
                    relationType = RelationshipType.USES_ACCOUNT,
                    evidenceIds = listOf("ev2:visual-qa-profile"),
                    confidence = 0.78f
                ),
                DossierEdge(
                    fromId = "profile-example",
                    toId = "website-example",
                    relation = "LINKS_TO",
                    relationType = RelationshipType.LINKS_TO,
                    evidenceIds = listOf("ev2:visual-qa-profile"),
                    confidence = 0.72f
                )
            )
        )
        val findings = listOf(emailFinding, usernameFinding, profileFinding)
        return DossierCase(
            caseId = "00000000-0000-4000-8000-000000000200",
            createdAt = "2026-08-23 12:00",
            subjectName = "Jane Example",
            input = input,
            findings = findings,
            evidenceRecords = evidence,
            profileResults = profiles,
            entityGraph = graph,
            riskLevel = RiskLevel.High,
            exposure = ExposureEngine().score(findings),
            aiSummary = null,
            scanHistory = listOf(
                CaseScanHistoryEntry(
                    scanId = "visual-qa-scan-001",
                    startedAtUtc = "2026-08-23T12:00:00Z",
                    completedAtUtc = "2026-08-23T12:03:00Z",
                    mode = ScanMode.Standard,
                    directProfileProviderCount = 3,
                    profileResultCount = profiles.size,
                    findingCount = findings.size,
                    graphEntityCount = graph.entities.size,
                    graphRelationshipCount = graph.edges.size
                )
            )
        )
    }

    fun analysisBundle() = OsintPostProcessor.analyze(
        input = input,
        profiles = profiles,
        evidence = EvidenceCollection(
            evidence = evidence,
            relationships = listOf(
                io.dossier.app.domain.evidence.EvidenceRelationship(
                    fromValue = "jane_example",
                    toValue = "example_research",
                    relation = "MENTIONS",
                    evidence = "Synthetic interaction fixture"
                ),
                io.dossier.app.domain.evidence.EvidenceRelationship(
                    fromValue = "example_research",
                    toValue = "jane_example",
                    relation = "REPLIES_TO",
                    evidence = "Synthetic interaction fixture"
                )
            )
        )
    )
}
