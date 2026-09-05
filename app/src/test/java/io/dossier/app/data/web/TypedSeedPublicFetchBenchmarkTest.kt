package io.dossier.app.data.web

import io.dossier.app.data.web.DiscoveryBenchmark.DiscoveryEvent
import io.dossier.app.data.web.DiscoveryBenchmark.EventStatus
import io.dossier.app.data.web.DiscoveryBenchmark.Fact
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticCase
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticRun
import io.dossier.app.domain.discovery.ProviderExecutionResult
import io.dossier.app.domain.discovery.ProviderResponseDecision
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedAdmissionConfig
import io.dossier.app.domain.discovery.TypedSeedAdmissionModel
import io.dossier.app.domain.discovery.TypedSeedEvidenceAdapter
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.IdentityInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the real typed public-fetch executor against a closed, synthetic
 * corpus. No network client is used: every response comes through the
 * injected fetcher, while the admission adapter supplies recursive pivots.
 */
class TypedSeedPublicFetchBenchmarkTest {

    @Test
    fun recursivePublicFetch_harnessMeasuresEvidenceAndAggregatesRuns() = runBlocking {
        val full = fullChainFixture()
        val partial = partialChainFixture()

        val fullTrace = runFixture(full)
        val partialTrace = runFixture(partial)

        assertFullChainEvidence(full, fullTrace)
        assertPartialRun(partial, partialTrace)

        val fullMetrics = DiscoveryBenchmark.evaluate(full.case, fullTrace.run)
        val partialMetrics = DiscoveryBenchmark.evaluate(partial.case, partialTrace.run)

        assertEquals(4, fullMetrics.truePositives)
        assertEquals(0, fullMetrics.falsePositives)
        assertEquals(0, fullMetrics.falseNegatives)
        assertEquals(1.0, fullMetrics.precision, 0.0001)
        assertEquals(1.0, fullMetrics.recall, 0.0001)
        assertEquals(5, fullMetrics.unresolvedCandidateCount)
        assertEquals(2, fullMetrics.unlabelledExtraCount)
        assertNotNull(fullMetrics.timeToFirstUsefulResultMs)
        assertNotNull(fullMetrics.timeToFirstVerifiedIdentityAnchorMs)
        assertNotNull(fullMetrics.timeToFirstHighValueExactIdentifierMs)
        assertNotNull(fullMetrics.timeTo50PercentRecallMs)
        assertNotNull(fullMetrics.timeTo80PercentRecallMs)
        assertTrue(
            fullMetrics.timeToFirstUsefulResultMs!! <= fullMetrics.timeToFirstHighValueExactIdentifierMs!!
        )
        assertTrue(
            fullMetrics.timeTo50PercentRecallMs!! <= fullMetrics.timeTo80PercentRecallMs!!
        )
        assertEquals(4, fullMetrics.totalProviderRequestCount)
        assertEquals(4, fullTrace.fetches.size)
        assertEquals(4, fullTrace.run.events.sumOf(DiscoveryEvent::requestCount))
        assertTrue(fullTrace.run.events.zipWithNext().all { (first, second) ->
            second.elapsedTimeMs >= first.elapsedTimeMs
        })
        assertTrue(fullMetrics.providerFailureRate > 0.0)
        assertEquals(0.0, checkNotNull(fullMetrics.falsePositiveRate), 0.0001)

        assertEquals(2, partialMetrics.truePositives)
        assertEquals(0, partialMetrics.falsePositives)
        assertEquals(2, partialMetrics.falseNegatives)
        assertEquals(1.0, partialMetrics.precision, 0.0001)
        assertEquals(0.5, partialMetrics.recall, 0.0001)
        assertEquals(0, partialMetrics.unresolvedCandidateCount)
        assertNotNull(partialMetrics.timeToFirstHighValueExactIdentifierMs)
        assertNotNull(partialMetrics.timeTo50PercentRecallMs)
        assertEquals(null, partialMetrics.timeTo80PercentRecallMs)
        assertEquals(1, partialMetrics.totalProviderRequestCount)

        val aggregate = DiscoveryBenchmark.aggregate(listOf(fullMetrics, partialMetrics))
        assertEquals(2, aggregate.totalCases)
        assertEquals(6, aggregate.truePositives)
        assertEquals(0, aggregate.falsePositives)
        assertEquals(2, aggregate.falseNegatives)
        assertEquals(1.0, aggregate.averagePrecision, 0.0001)
        assertEquals(0.75, aggregate.averageRecall, 0.0001)
        assertEquals(5.0 / 6.0, aggregate.averageF1, 0.0001)
    }

    private suspend fun runFixture(fixture: Fixture): Trace {
        val requests = AtomicInteger(0)
        val fetches = CopyOnWriteArrayList<String>()
        val archiveLookups = CopyOnWriteArrayList<String>()
        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                requests.incrementAndGet()
                fetches += requested
                fixture.responses[requested]?.toResult(requested)
                    ?: ProviderExecutionResult(
                        decision = ProviderResponseDecision(
                            ProviderVerificationState.InvalidResponse,
                            "Synthetic fixture has no response for requested URL"
                        ),
                        statusCode = 500,
                        requestedUrl = requested,
                        finalUrl = requested,
                        bodyText = "",
                        latencyMs = 0L,
                        attemptCount = 1,
                        contentType = "text/html"
                    )
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver { requested ->
                archiveLookups += requested
                null
            },
            nowMillis = { SYNTHETIC_RETRIEVAL_EPOCH_MS }
        )

        val admission = TypedSeedAdmissionModel(
            TypedSeedAdmissionConfig(
                maxDepth = 4,
                maxTotalSeeds = 12,
                perKindBudgets = TypedSeedAdmissionConfig.defaultBudgets()
            )
        )
        check(admission.offer(
            kind = TypedSeedKind.Url,
            rawValue = fixture.seedUrl,
            depth = 0,
            origin = TypedSeedOrigin.UserInput,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED
        ))

        val startedAt = System.nanoTime()
        var lastElapsedMs = 0L
        val events = mutableListOf<DiscoveryEvent>()
        val collections = mutableListOf<EvidenceCollection>()
        while (true) {
            val seed = admission.pop() ?: break
            val requestsBefore = requests.get()
            val report = executor.executeDetailed(
                seeds = listOf(seed),
                input = fixture.input,
                scanId = ScanId("synthetic-${fixture.id}")
            )
            collections += report.collection
            val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L)
                .coerceAtLeast(lastElapsedMs)
            lastElapsedMs = elapsedMs
            val passRequests = (requests.get() - requestsBefore).coerceAtLeast(0)

            val projection = TypedSeedEvidenceAdapter.admit(
                evidence = report.collection.evidence,
                input = null,
                config = admission.config
            )
            val admittedPivots = projection.admittedSeeds
                .filter { it.kind in EXECUTABLE_KINDS }
                .count { pivot ->
                    admission.offer(
                        kind = pivot.kind,
                        rawValue = pivot.exactValue,
                        depth = pivot.depth,
                        origin = pivot.origin,
                        evidenceState = pivot.evidenceState,
                        sourceClassification = pivot.sourceClassification,
                        evidenceIds = pivot.evidenceIds,
                        sourceUrl = pivot.sourceUrl,
                        discoveryPath = pivot.discoveryPath
                    )
                }

            val outputEvidence = report.collection.evidence
                .mapNotNull(::toFactEvent)
            outputEvidence.forEachIndexed { index, mapped ->
                val (evidence, fact, status) = mapped
                events += DiscoveryEvent(
                    elapsedTimeMs = elapsedMs,
                    fact = fact,
                    status = status,
                    providerId = evidence.providerId.orEmpty(),
                    requestCount = if (index == 0) passRequests else 0,
                    isIdentityAnchor = status == EventStatus.VERIFIED &&
                        fact.normalizedKind == "profile" &&
                        evidence.sourceUrl == fixture.seedUrl,
                    usefulPivotCount = if (index == 0 && status == EventStatus.VERIFIED) {
                        admittedPivots
                    } else {
                        0
                    }
                )
            }
        }
        val durationMs = ((System.nanoTime() - startedAt) / 1_000_000L)
            .coerceAtLeast(lastElapsedMs)
        assertTrue("archive resolver must stay unused", archiveLookups.isEmpty())
        assertEquals(
            "event request accounting must match injected fetches",
            requests.get(),
            events.sumOf(DiscoveryEvent::requestCount)
        )
        return Trace(
            run = SyntheticRun(
                events = events,
                totalScanDurationMs = durationMs,
                totalRequestCount = requests.get()
            ),
            collections = collections,
            fetches = fetches.toList()
        )
    }

    private fun assertFullChainEvidence(fixture: Fixture, trace: Trace) {
        val evidence = trace.collections.flatMap(EvidenceCollection::evidence)
        val profile = evidence.first { it.kind == EvidenceKind.Url && it.value == fixture.seedUrl }
        val documentLink = evidence.first { it.kind == EvidenceKind.Document && it.value == fixture.documentUrl }
        val documentFetch = evidence.first {
            it.kind == EvidenceKind.Document &&
                it.value == fixture.documentUrl &&
                it.sourceClassification == ExposureSourceClassification.PUBLIC_DOCUMENT
        }
        val email = evidence.first { it.kind == EvidenceKind.Email && it.value == fixture.input.emails.single() }
        val phone = evidence.first { it.kind == EvidenceKind.Phone && it.value == fixture.input.phones.single() }
        val falsePositive = evidence.first { it.kind == EvidenceKind.Email && it.value == fixture.falsePositiveEmail }
        val candidate = evidence.first { it.value == fixture.candidateUrl }
        val unavailable = evidence.first { it.value == fixture.missingUrl && it.state == EvidenceState.Unavailable }

        assertEquals(EvidenceState.Verified, profile.state)
        assertEquals(EvidenceState.Verified, documentLink.state)
        assertEquals(fixture.seedUrl, documentLink.sourceUrl)
        assertEquals(EvidenceState.Verified, documentFetch.state)
        assertTrue(phone.discoveryPath.contains(fixture.documentUrl))
        assertEquals(EvidenceState.Verified, email.state)
        assertEquals(EvidenceState.Observed, falsePositive.state)
        val combined = EvidenceCollection(
            evidence = trace.collections.flatMap { it.evidence }.distinctBy { it.id },
            relationships = trace.collections.flatMap { it.relationships }
        )
        assertTrue(
            TypedSeedEvidenceAdapter.fromCollection(combined, fixture.input)
                .admittedSeeds.none { it.exactValue == fixture.falsePositiveEmail }
        )
        assertEquals(EvidenceState.Observed, candidate.state)
        assertEquals(EvidenceState.Unavailable, unavailable.state)
        assertEquals(4, trace.fetches.size)
        assertTrue(trace.fetches.containsAll(listOf(fixture.seedUrl, fixture.documentUrl, fixture.missingUrl, fixture.candidateUrl)))
    }

    private fun assertPartialRun(fixture: Fixture, trace: Trace) {
        val evidence = trace.collections.flatMap(EvidenceCollection::evidence)
        assertTrue(evidence.any { it.kind == EvidenceKind.Url && it.value == fixture.seedUrl })
        assertTrue(evidence.any { it.kind == EvidenceKind.Email && it.value == fixture.input.emails.single() })
        assertEquals(listOf(fixture.seedUrl), trace.fetches)
        assertTrue(trace.run.events.all { it.status == EventStatus.VERIFIED })
    }

    private fun toFactEvent(evidence: Evidence): Triple<Evidence, Fact, EventStatus>? {
        val factKind = when (evidence.kind) {
            EvidenceKind.Email -> "email"
            EvidenceKind.Phone -> "phone"
            EvidenceKind.Url,
            EvidenceKind.Profile -> "profile"
            EvidenceKind.Document -> "document"
            EvidenceKind.Domain -> "domain"
            else -> return null
        }
        val status = when (evidence.state) {
            EvidenceState.Verified -> EventStatus.VERIFIED
            EvidenceState.Unavailable -> EventStatus.UNAVAILABLE
            EvidenceState.Observed,
            EvidenceState.Probable,
            EvidenceState.Candidate,
            EvidenceState.Conflicting,
            EvidenceState.Rejected -> EventStatus.CANDIDATE
        }
        return Triple(evidence, Fact(factKind, evidence.value), status)
    }

    private fun userSeed(url: String): TypedSeed = TypedSeed(
        kind = TypedSeedKind.Url,
        value = url,
        exactValue = url,
        normalizedValue = url,
        origin = TypedSeedOrigin.UserInput,
        sourceClassification = ExposureSourceClassification.USER_IMPORTED,
        evidenceState = EvidenceState.Observed
    )

    private fun fullChainFixture(): Fixture {
        val seedUrl = "https://public.example.test/profile/jane-example"
        val documentUrl = "https://public.example.test/docs/jane-example.txt"
        val missingUrl = "https://public.example.test/missing"
        val candidateUrl = "https://unrelated.test/noise"
        val input = syntheticInput()
        val profileHtml = """
            <html><head><title>Jane Example profile</title></head><body>
            <h1>Jane Example</h1>
            <p>Public contact ${input.emails.single()}.</p>
            <p>Alternate contact noise@example.test.</p>
            <a href="$documentUrl">Resume</a>
            <a href="$missingUrl">Missing page</a>
            <a href="$candidateUrl">Unrelated page</a>
            </body></html>
        """.trimIndent()
        val documentHtml = """
            <html><head><title>Jane Example CV</title></head><body>
            <h1>Jane Example</h1>
            <p>Phone: ${input.phones.single()}</p>
            </body></html>
        """.trimIndent()
        return Fixture(
            id = "full-chain",
            input = input,
            seedUrl = seedUrl,
            documentUrl = documentUrl,
            missingUrl = missingUrl,
            candidateUrl = candidateUrl,
            falsePositiveEmail = "noise@example.test",
            case = SyntheticCase(
                name = "typed-public-full-chain",
                initialSeed = Fact("profile", seedUrl),
                expectedFacts = listOf(
                    Fact("profile", seedUrl),
                    Fact("document", documentUrl),
                    Fact("email", input.emails.single()),
                    Fact("phone", input.phones.single())
                ),
                knownNegatives = listOf(Fact("email", "noise@example.test")),
                isCompleteGroundTruth = false
            ),
            responses = mapOf(
                seedUrl to FixtureResponse(200, profileHtml),
                documentUrl to FixtureResponse(200, documentHtml),
                missingUrl to FixtureResponse(404, "Page not found")
            )
        )
    }

    private fun partialChainFixture(): Fixture {
        val seedUrl = "https://limited.example.test/profile/jane-example"
        val documentUrl = "https://limited.example.test/docs/jane-example.txt"
        val input = syntheticInput()
        val profileHtml = """
            <html><head><title>Jane Example limited profile</title></head><body>
            <h1>Jane Example</h1>
            <p>Public contact ${input.emails.single()}.</p>
            </body></html>
        """.trimIndent()
        return Fixture(
            id = "partial-chain",
            input = input,
            seedUrl = seedUrl,
            documentUrl = documentUrl,
            missingUrl = "https://limited.example.test/missing",
            candidateUrl = "https://unrelated.test/partial-noise",
            falsePositiveEmail = "noise@example.test",
            case = SyntheticCase(
                name = "typed-public-partial-chain",
                initialSeed = Fact("profile", seedUrl),
                expectedFacts = listOf(
                    Fact("profile", seedUrl),
                    Fact("document", documentUrl),
                    Fact("email", input.emails.single()),
                    Fact("phone", input.phones.single())
                ),
                isCompleteGroundTruth = false
            ),
            responses = mapOf(seedUrl to FixtureResponse(200, profileHtml))
        )
    }

    private fun syntheticInput() = IdentityInput(
        fullName = "Jane Example",
        emails = listOf("jane@example.test"),
        phones = listOf("+1 555 0100"),
        usernames = listOf("jane-example"),
        primaryUsername = "jane-example"
    )

    private data class Fixture(
        val id: String,
        val input: IdentityInput,
        val seedUrl: String,
        val documentUrl: String,
        val missingUrl: String,
        val candidateUrl: String,
        val falsePositiveEmail: String,
        val case: SyntheticCase,
        val responses: Map<String, FixtureResponse>
    ) {
        init {
            // Keep the corpus visibly synthetic and network-free.
            require(responses.keys.all { it.endsWith(".test") || ".test/" in it })
        }
    }

    private data class FixtureResponse(
        val statusCode: Int,
        val body: String
    ) {
        fun toResult(url: String): ProviderExecutionResult {
            val state = if (statusCode == 200) {
                ProviderVerificationState.Present
            } else {
                ProviderVerificationState.NotFound
            }
            return ProviderExecutionResult(
                decision = ProviderResponseDecision(state, "synthetic fixture response"),
                statusCode = statusCode,
                requestedUrl = url,
                finalUrl = url,
                bodyText = body,
                latencyMs = 0L,
                attemptCount = 1,
                contentType = "text/html"
            )
        }
    }

    private data class Trace(
        val run: SyntheticRun,
        val collections: List<EvidenceCollection>,
        val fetches: List<String>
    )

    private companion object {
        val EXECUTABLE_KINDS = setOf(
            TypedSeedKind.Url,
            TypedSeedKind.Document,
            TypedSeedKind.Archive
        )
        const val SYNTHETIC_RETRIEVAL_EPOCH_MS = 1_700_000_000_000L
    }
}
