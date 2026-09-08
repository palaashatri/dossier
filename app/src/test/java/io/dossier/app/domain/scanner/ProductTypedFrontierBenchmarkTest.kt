package io.dossier.app.domain.scanner

import android.content.Context
import android.content.ContextWrapper
import io.dossier.app.data.web.DiscoveryBenchmark
import io.dossier.app.data.web.DiscoveryBenchmark.DiscoveryEvent
import io.dossier.app.data.web.DiscoveryBenchmark.EventStatus
import io.dossier.app.data.web.DiscoveryBenchmark.Fact
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticCase
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticRun
import io.dossier.app.data.web.PublicSearchDiscoveryService
import io.dossier.app.data.web.TypedSeedPublicFetchExecutor
import io.dossier.app.data.web.VerifiedPage
import io.dossier.app.domain.discovery.ProviderExecutionResult
import io.dossier.app.domain.discovery.ProviderResponseDecision
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.ScanId
import io.dossier.app.domain.discovery.TypedSeed
import io.dossier.app.domain.discovery.TypedSeedAdmissionModel
import io.dossier.app.domain.discovery.TypedSeedExecutionAvailability
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.discovery.TypedSeedOrigin
import io.dossier.app.domain.discovery.TypedSeedSafety
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.username.UsernameVariantGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Network-free synthetic benchmark over the product's rolling typed frontier.
 * Every event is derived from ProfileScanner output; elapsed times are fixed
 * fixture timestamps, never wall-clock measurements.
 */
class ProductTypedFrontierBenchmarkTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dossier-product-frontier").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun productBackedTypedFrontierMeasuresRecursiveCorpus() = runBlocking {
        val fixtures = listOf(urlCascadeFixture(), emailSearchFixture(), phoneFailureFixture())
        val traces = fixtures.map { fixture ->
            assertAttackerSeedOnly(fixture)
            fixture to runCase(fixture)
        }

        assertTrue("product frontier must mark supplied seed echoes for evaluator exclusion", traces.all { (fixture, trace) ->
            val seedEchoes = trace.events.filter { it.fact.matches(fixture.case.initialSeed) }
            seedEchoes.isNotEmpty() && seedEchoes.all(DiscoveryEvent::isInitialSeed)
        })

        assertUrlCascade(traces.first { it.first.id == "url-domain-document-archive" }.second)
        assertEmailSearch(traces.first { it.first.id == "email-search-replay" }.second)
        assertPhoneFailure(traces.first { it.first.id == "phone-provider-failure" }.second)
        assertUnsupportedSeedIsExplicit()

        val metrics = traces.map { (fixture, trace) ->
            DiscoveryBenchmark.evaluate(fixture.case, trace.run)
        }
        val urlMetrics = metrics[0]
        val emailMetrics = metrics[1]
        val phoneMetrics = metrics[2]

        // Hand-derived from the synthetic paths: five URL-cascade facts, three
        // email-search facts, and one phone-search fact are known exposures.
        assertEquals(5, urlMetrics.truePositives)
        assertEquals(1, urlMetrics.falsePositives)
        assertEquals(0, urlMetrics.falseNegatives)
        assertEquals(1.0, urlMetrics.recall, 0.0001)
        assertEquals(5.0 / 6.0, urlMetrics.precision, 0.0001)
        assertEquals(1.0, checkNotNull(urlMetrics.falsePositiveRate), 0.0001)
        assertEquals(1, urlMetrics.unavailableEventCount)
        assertEquals(0, urlMetrics.providerFailureEventCount)
        assertEquals(100L, urlMetrics.timeToFirstUsefulResultMs)
        assertEquals(100L, urlMetrics.timeToFirstHighValueExactIdentifierMs)
        assertEquals(100L, urlMetrics.timeTo50PercentRecallMs)
        assertEquals(100L, urlMetrics.timeTo80PercentRecallMs)
        assertEquals(320L, urlMetrics.totalScanDurationMs)
        assertEquals(7, urlMetrics.totalProviderRequestCount)

        assertEquals(3, emailMetrics.truePositives)
        assertEquals(0, emailMetrics.falsePositives)
        assertEquals(0, emailMetrics.falseNegatives)
        assertEquals(1.0, emailMetrics.recall, 0.0001)
        assertEquals(1.0, emailMetrics.precision, 0.0001)
        assertEquals(1, emailMetrics.candidateCount)
        assertEquals(0, emailMetrics.unavailableEventCount)
        assertEquals(0, emailMetrics.providerFailureEventCount)
        assertEquals(80L, emailMetrics.timeToFirstUsefulResultMs)
        assertEquals(80L, emailMetrics.timeToFirstVerifiedIdentityAnchorMs)
        assertEquals(80L, emailMetrics.timeToFirstHighValueExactIdentifierMs)
        assertEquals(80L, emailMetrics.timeTo50PercentRecallMs)
        assertEquals(80L, emailMetrics.timeTo80PercentRecallMs)
        assertEquals(160L, emailMetrics.totalScanDurationMs)
        assertEquals(3, emailMetrics.totalProviderRequestCount)
        assertEquals(0.0, checkNotNull(emailMetrics.falsePositiveRate), 0.0001)

        assertEquals(0, phoneMetrics.truePositives)
        assertEquals(0, phoneMetrics.falsePositives)
        assertEquals(1, phoneMetrics.falseNegatives)
        assertEquals(0.0, phoneMetrics.recall, 0.0001)
        assertEquals(0, phoneMetrics.candidateCount)
        assertEquals(0, phoneMetrics.unavailableEventCount)
        assertEquals(1, phoneMetrics.providerFailureEventCount)
        assertEquals(40L, phoneMetrics.totalScanDurationMs)
        assertEquals(8, phoneMetrics.totalProviderRequestCount)

        val aggregate = DiscoveryBenchmark.aggregate(metrics)
        assertEquals(3, aggregate.totalCases)
        assertEquals(8, aggregate.truePositives)
        assertEquals(1, aggregate.falsePositives)
        assertEquals(1, aggregate.falseNegatives)
        assertEquals(8.0 / 9.0, aggregate.corpusPrecision, 0.0001)
        assertEquals(0.5, checkNotNull(aggregate.corpusFalsePositiveRate), 0.0001)
        assertEquals(8.0 / 9.0, aggregate.recallAtKnownExposure, 0.0001)
        assertEquals((1.0 + 1.0 + 0.0) / 3.0, aggregate.averageRecall, 0.0001)
        assertEquals(3, aggregate.unresolvedCandidateCount)
        assertEquals(1, aggregate.candidateCount)
        assertEquals(1, aggregate.unavailableEventCount)
        assertEquals(1, aggregate.providerFailureEventCount)
        assertEquals(18, aggregate.totalProviderRequestCount)
        assertEquals(2, aggregate.totalFailedRequestCount)
        assertEquals(2.0 / 18.0, aggregate.providerFailureRate, 0.0001)
        assertEquals(90.0, checkNotNull(aggregate.averageTimeToFirstUsefulResultMs), 0.0001)
        assertEquals(90.0, checkNotNull(aggregate.averageTimeToFirstHighValueExactIdentifierMs), 0.0001)
        assertEquals(2, aggregate.timeToFirstUsefulResultCaseCount)
        assertEquals(2, aggregate.timeToFirstVerifiedIdentityAnchorCaseCount)
    }

    private suspend fun runCase(fixture: Fixture): Trace {
        // The rolling frontier runs independent fetches concurrently; keep
        // harness accounting lossless when those callbacks overlap.
        val fetchRequests = CopyOnWriteArrayList<String>()
        val searchRequests = CopyOnWriteArrayList<String>()
        val providerFailureRequests = ConcurrentHashMap.newKeySet<String>()
        val searchResultToRequest = fixture.searches.flatMap { (requestKey, response) ->
            when (response) {
                is SearchFixture.Success -> response.results.map { result ->
                    canonical(result.url) to requestKey
                }
                is SearchFixture.Failure -> emptyList()
            }
        }.toMap()

        val executor = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, requested, _, _ ->
                fetchRequests += requested
                val key = "fetch:${canonical(requested)}"
                val response = fixture.fetches[requested]
                if (response?.providerFailure == true) providerFailureRequests += key
                response?.toResult(requested) ?: fetchFailure(requested)
            },
            archiveResolver = TypedSeedPublicFetchExecutor.ArchiveSeedResolver {
                throw AssertionError("synthetic archive snapshot must use the fetcher")
            },
            nowMillis = { SYNTHETIC_RETRIEVAL_EPOCH_MS },
            searchOutcomeSearcher = { seed, _, _ ->
                val requestKey = searchKey(seed.kind, seed.normalizedValue)
                searchRequests += requestKey
                when (val response = fixture.searches[requestKey]) {
                    is SearchFixture.Success -> PublicSearchDiscoveryService.SearchOutcome.Success(response.results)
                    is SearchFixture.Failure -> {
                        providerFailureRequests += requestKey
                        PublicSearchDiscoveryService.SearchOutcome.Unavailable(response.reason)
                    }
                    null -> PublicSearchDiscoveryService.SearchOutcome.Unavailable("No synthetic response")
                }
            }
        )
        val scanner = ProfileScanner(
            context = FakeContext(root),
            piiExtractor = PiiExtractor(),
            variantGenerator = UsernameVariantGenerator(),
            typedSeedExecutorOverride = executor
        )
        val output = scanner.runTypedSeedFrontier(
            input = fixture.input,
            deepResearch = true,
            requestId = null,
            checkpointOwnerId = null,
            checkpointGeneration = null,
            planFingerprint = null,
            seedEvidence = fixture.seedEvidence,
            scanId = ScanId("synthetic-${fixture.id}")
        )

        val initialEvidenceIds = fixture.seedEvidence.evidence.map(Evidence::id).toSet()
        val assignedRequests = mutableSetOf<String>()
        val events = output.evidence.mapNotNull { evidence ->
            val requestKey = requestKeyFor(
                evidence = evidence,
                fixture = fixture,
                searchResultToRequest = searchResultToRequest
            )
            val status = when (evidence.state) {
                EvidenceState.Verified -> EventStatus.VERIFIED
                EvidenceState.Observed -> EventStatus.OBSERVED
                EvidenceState.Candidate,
                EvidenceState.Probable,
                EvidenceState.Conflicting,
                EvidenceState.Rejected -> EventStatus.CANDIDATE
                EvidenceState.Unavailable -> if (requestKey != null && requestKey in providerFailureRequests) {
                    EventStatus.PROVIDER_FAILURE
                } else {
                    EventStatus.UNAVAILABLE
                }
            }
            val fact = Fact(factKind(evidence, fixture), evidence.value)
            val requestCount = if (evidence.id !in initialEvidenceIds &&
                requestKey != null &&
                assignedRequests.add(requestKey)
            ) {
                1
            } else {
                0
            }
            DiscoveryEvent(
                elapsedTimeMs = fixture.elapsedTimeMs(evidence),
                fact = fact,
                status = status,
                providerId = evidence.providerId.orEmpty(),
                requestCount = requestCount,
                isIdentityAnchor = status == EventStatus.VERIFIED &&
                    fact.normalizedKind == "profile" &&
                    evidence.id !in initialEvidenceIds,
                usefulPivotCount = if (status == EventStatus.VERIFIED &&
                    evidence.id !in initialEvidenceIds &&
                    evidence.kind in setOf(EvidenceKind.Url, EvidenceKind.Document, EvidenceKind.Archive)
                ) {
                    1
                } else {
                    0
                },
                isInitialSeed = fact.matches(fixture.case.initialSeed)
            )
        }.sortedBy(DiscoveryEvent::elapsedTimeMs)

        assertTrue(
            "product evidence may merge duplicate requests fetch=$fetchRequests search=$searchRequests assigned=$assignedRequests",
            assignedRequests.size <= fetchRequests.size + searchRequests.size
        )
        return Trace(
            run = SyntheticRun(
                events = events,
                totalScanDurationMs = fixture.totalDurationMs,
                totalRequestCount = fetchRequests.size + searchRequests.size,
                providerFailureCount = providerFailureRequests.size
            ),
            evidence = output,
            fetchRequests = fetchRequests.toList(),
            searchRequests = searchRequests.toList()
        )
    }

    private fun assertUrlCascade(trace: Trace) {
        val seed = "https://public.example.test/jane"
        val domain = "public.example.test"
        val document = "https://public.example.test/docs/jane.txt"
        val archive = "https://web.archive.org/web/20250101120000id_/https://public.example.test/jane"
        val email = trace.evidence.evidence.first { it.kind == EvidenceKind.Email && it.value == "jane@example.test" }
        val phone = trace.evidence.evidence.first { it.kind == EvidenceKind.Phone && it.value == "+1 555 0100" }
        val domainEvidence = trace.evidence.evidence.first { it.kind == EvidenceKind.Domain && it.value == domain }
        val documentEvidence = trace.evidence.evidence.first { it.kind == EvidenceKind.Document && it.value == document }
        val archiveEvidence = trace.evidence.evidence.first { it.kind == EvidenceKind.Archive && it.value == archive }

        assertEquals(EvidenceState.Observed, email.state)
        assertEquals(EvidenceState.Observed, phone.state)
        assertEquals(EvidenceState.Verified, domainEvidence.state)
        assertEquals(EvidenceState.Verified, documentEvidence.state)
        assertEquals(EvidenceState.Verified, archiveEvidence.state)
        assertTrue("domain path must retain the URL hop", domainEvidence.discoveryPath.contains(seed))
        assertTrue("document path must retain the URL hop", documentEvidence.discoveryPath.contains(seed))
        assertTrue("archive path must retain the URL hop", archiveEvidence.discoveryPath.contains(seed))
        assertTrue("phone path must retain the URL source", phone.discoveryPath.contains(seed))
        assertTrue("missing fetch must remain an unavailable product event", trace.events.any {
            it.status == EventStatus.UNAVAILABLE && it.fact.normalizedValue == "https://public.example.test/missing"
        })
        assertEquals(7, trace.fetchRequests.size)
        assertEquals(0, trace.searchRequests.size)
    }

    private fun assertEmailSearch(trace: Trace) {
        val profileUrl = "https://search.example.test/contact-profile"
        val documentUrl = "https://search.example.test/contact-doc.txt"
        val candidate = "https://noise.example.test/contact"
        val profile = trace.evidence.evidence.first {
            it.kind == EvidenceKind.Url && it.value == profileUrl && it.state == EvidenceState.Verified
        }
        val phone = trace.evidence.evidence.first { it.kind == EvidenceKind.Phone && it.value == "+1 555 0123" }
        val candidateEvidence = trace.evidence.evidence.first {
            it.kind == EvidenceKind.PublicSearchEvidence &&
                it.value == candidate &&
                it.state == EvidenceState.Candidate
        }
        val document = trace.evidence.evidence.first {
            it.kind == EvidenceKind.Document && it.value == documentUrl && it.state == EvidenceState.Verified
        }

        assertTrue(profile.discoveryPath.contains(profileUrl))
        assertTrue(phone.discoveryPath.contains(profileUrl))
        assertTrue(document.discoveryPath.contains(profileUrl))
        assertEquals(EventStatus.CANDIDATE, trace.events.first {
            it.fact.normalizedValue == candidate
        }.status)
        assertEquals(EvidenceState.Candidate, candidateEvidence.state)
        assertEquals(
            setOf("https://search.example.test", documentUrl),
            trace.fetchRequests.toSet()
        )
        assertEquals(2, trace.fetchRequests.size)
        assertEquals(1, trace.searchRequests.size)
    }

    private fun assertPhoneFailure(trace: Trace) {
        assertTrue(trace.events.any { it.status == EventStatus.PROVIDER_FAILURE })
        assertTrue(trace.evidence.evidence.any { it.state == EvidenceState.Unavailable })
        assertTrue(trace.fetchRequests.isEmpty())
        // Search unavailability is retryable; the bounded typed frontier makes
        // all eight attempts before retaining the terminal failure.
        assertEquals(8, trace.searchRequests.size)
    }

    private suspend fun assertUnsupportedSeedIsExplicit() {
        assertEquals(
            TypedSeedExecutionAvailability.Unavailable,
            TypedSeedAdmissionModel().availabilityFor(TypedSeedKind.Photo)
        )
        assertFalse(TypedSeedKind.Photo in TypedSeedSafety.executableKinds)
        assertFalse(TypedSeedKind.Image in TypedSeedSafety.executableKinds)

        val photo = TypedSeed(
            kind = TypedSeedKind.Photo,
            value = "content://synthetic/photo",
            exactValue = "content://synthetic/photo",
            normalizedValue = "content://synthetic/photo",
            origin = TypedSeedOrigin.UserInput,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED,
            evidenceState = EvidenceState.Observed
        )
        val report = TypedSeedPublicFetchExecutor(
            fetcher = TypedSeedPublicFetchExecutor.PublicSeedFetcher { _, _, _, _ ->
                throw AssertionError("unsupported Photo seed must not reach the fetcher")
            },
            searchOutcomeSearcher = TypedSeedPublicFetchExecutor.PublicSearchOutcomeSeam { _, _, _ ->
                throw AssertionError("unsupported Photo seed must not reach the searcher")
            }
        ).executeDetailed(
            seeds = listOf(photo),
            input = IdentityInput(fullName = ""),
            scanId = ScanId("synthetic-unsupported")
        )
        assertEquals(TypedSeedPublicFetchExecutor.ExecutionState.Skipped, report.executions.single().state)
        assertTrue(report.executions.single().reason.orEmpty().contains("not executable"))
    }

    private fun assertAttackerSeedOnly(fixture: Fixture) {
        val input = fixture.input
        assertTrue(input.fullName.isBlank())
        assertTrue(input.aliases.isEmpty())
        assertTrue(input.locations.isEmpty())
        assertTrue(input.organizations.isEmpty())
        assertTrue(input.usernames.isEmpty())
        assertTrue(input.primaryUsername == null)
        assertTrue(input.selfieUri == null)
        val suppliedKinds = buildList {
            if (input.emails.isNotEmpty()) add(TypedSeedKind.Email)
            if (input.phones.isNotEmpty()) add(TypedSeedKind.Phone)
            if (input.profileUrls.isNotEmpty()) add(TypedSeedKind.Url)
        }
        assertTrue("each fixture supplies only one attacker seed", suppliedKinds.size <= 1)
        assertTrue("seed evidence must not smuggle extra PII", fixture.seedEvidence.evidence.all {
            it.kind in setOf(EvidenceKind.Domain, EvidenceKind.Document)
        })
    }

    private fun requestKeyFor(
        evidence: Evidence,
        fixture: Fixture,
        searchResultToRequest: Map<String, String>
    ): String? {
        if (evidence.id in fixture.seedEvidence.evidence.map(Evidence::id)) return null
        evidence.sourceUrl?.let { source ->
            searchResultToRequest[canonical(source)]?.let { return it }
            if (fixture.fetches.keys.any { canonical(it) == canonical(source) }) {
                return "fetch:${canonical(source)}"
            }
        }
        val initial = fixture.case.initialSeed
        if (evidence.kind == EvidenceKind.PublicSearchEvidence &&
            evidence.value.equals(initial.exactValue, ignoreCase = true)
        ) {
            return searchKey(initialTypedSeedKind(initial), initial.normalizedValue)
        }
        return null
    }

    private fun factKind(evidence: Evidence, fixture: Fixture): String = when (evidence.kind) {
        EvidenceKind.Email -> "email"
        EvidenceKind.Phone -> "phone"
        EvidenceKind.Domain -> "domain"
        EvidenceKind.Document -> "document"
        EvidenceKind.Archive -> "archive"
        EvidenceKind.Url,
        EvidenceKind.Profile,
        EvidenceKind.PublicSearchEvidence -> if (
            evidence.kind == EvidenceKind.PublicSearchEvidence &&
            evidence.value.equals(fixture.case.initialSeed.exactValue, ignoreCase = true)
        ) {
            initialKind(fixture.case.initialSeed)
        } else {
            "profile"
        }
        else -> "other"
    }

    private fun initialKind(seed: Fact): String = seed.normalizedKind

    private fun initialTypedSeedKind(seed: Fact): TypedSeedKind = when (seed.normalizedKind) {
        "email" -> TypedSeedKind.Email
        "phone" -> TypedSeedKind.Phone
        "profile",
        "url" -> TypedSeedKind.Url
        else -> error("Unsupported synthetic initial seed kind: ${seed.normalizedKind}")
    }

    private fun urlCascadeFixture(): Fixture {
        val seed = "https://public.example.test/jane"
        val domain = "public.example.test"
        val document = "https://public.example.test/docs/jane.txt"
        val archive = "https://web.archive.org/web/20250101120000id_/https://public.example.test/jane"
        val missing = "https://public.example.test/missing"
        val profileHtml = """
            <html><body>
            Jane Example public profile. Email jane@example.test. Phone: +1 555 0100.
            <a href="https://public.example.test">Domain</a>
            <a href="$document">Document</a>
            <a href="$archive">Archive</a>
            <a href="$missing">Unavailable page</a>
            </body></html>
        """.trimIndent()
        return Fixture(
            id = "url-domain-document-archive",
            case = SyntheticCase(
                name = "product-url-domain-document-archive",
                initialSeed = Fact("profile", seed),
                expectedFacts = listOf(
                    Fact("domain", domain),
                    Fact("document", document),
                    Fact("archive", archive),
                    Fact("email", "jane@example.test"),
                    Fact("phone", "+1 555 0100")
                ),
                knownNegatives = listOf(Fact("domain", "web.archive.org")),
                isCompleteGroundTruth = false
            ),
            input = IdentityInput(fullName = "", profileUrls = listOf(seed)),
            fetches = mapOf(
                seed to FetchFixture(profileHtml),
                "https://public.example.test" to FetchFixture("<html><body>Domain landing</body></html>"),
                document to FetchFixture("<html><body>Document landing</body></html>", contentType = "text/plain"),
                archive to FetchFixture("<html><body>Historical landing</body></html>"),
                "https://web.archive.org" to FetchFixture("<html><body>Archive host landing</body></html>"),
                missing to FetchFixture("Not found", statusCode = 404, state = ProviderVerificationState.NotFound)
            ),
            searches = emptyMap(),
            sourceTimes = mapOf(
                seed to 100L,
                "https://public.example.test" to 180L,
                document to 220L,
                archive to 260L,
                "https://web.archive.org" to 280L,
                missing to 300L
            ),
            totalDurationMs = 320L
        )
    }

    private fun emailSearchFixture(): Fixture {
        val email = "contact@example.test"
        val profile = "https://search.example.test/contact-profile"
        val document = "https://search.example.test/contact-doc.txt"
        val candidate = "https://noise.example.test/contact"
        val page = VerifiedPage(
            finalUrl = profile,
            title = "Contact profile",
            text = "Contact profile. Email $email. Phone: +1 555 0123.",
            contentHashSha256 = "synthetic-email-profile",
            links = listOf(document)
        )
        return Fixture(
            id = "email-search-replay",
            case = SyntheticCase(
                name = "product-email-search-replay",
                initialSeed = Fact("email", email),
                expectedFacts = listOf(
                    Fact("profile", profile),
                    Fact("document", document),
                    Fact("phone", "+1 555 0123")
                ),
                knownNegatives = listOf(Fact("profile", candidate)),
                isCompleteGroundTruth = false
            ),
            input = IdentityInput(fullName = "", emails = listOf(email)),
            fetches = mapOf(
                "https://search.example.test" to FetchFixture(
                    "<html><body>Search host</body></html>"
                ),
                document to FetchFixture("<html><body>Contact document</body></html>", contentType = "text/plain")
            ),
            searches = mapOf(
                searchKey(TypedSeedKind.Email, email) to SearchFixture.Success(
                    listOf(
                        PublicSearchDiscoveryService.PublicSearchResult(
                            title = "Contact profile",
                            snippet = "Public contact profile",
                            url = profile,
                            query = "\"$email\"",
                            source = "synthetic-index",
                            score = 0.98f,
                            directlyVerified = true,
                            verifiedPage = page
                        ),
                        PublicSearchDiscoveryService.PublicSearchResult(
                            title = "Unrelated contact",
                            snippet = "Candidate only",
                            url = candidate,
                            query = "\"$email\"",
                            source = "synthetic-index",
                            score = 0.30f,
                            directlyVerified = false
                        )
                    )
                )
            ),
            sourceTimes = mapOf(
                profile to 80L,
                document to 120L,
                candidate to 40L,
                "https://search.example.test" to 140L
            ),
            totalDurationMs = 160L
        )
    }

    private fun phoneFailureFixture(): Fixture {
        val phone = "+1 555 0190"
        return Fixture(
            id = "phone-provider-failure",
            case = SyntheticCase(
                name = "product-phone-provider-failure",
                initialSeed = Fact("phone", phone),
                expectedFacts = listOf(Fact("profile", "https://phone.example.test/profile")),
                isCompleteGroundTruth = true
            ),
            input = IdentityInput(fullName = "", phones = listOf(phone)),
            fetches = emptyMap(),
            searches = mapOf(
                searchKey(TypedSeedKind.Phone, "15550190") to SearchFixture.Failure("synthetic provider outage")
            ),
            sourceTimes = mapOf(phone to 40L),
            totalDurationMs = 40L
        )
    }

    private data class Fixture(
        val id: String,
        val case: SyntheticCase,
        val input: IdentityInput,
        val seedEvidence: EvidenceCollection = EvidenceCollection(),
        val fetches: Map<String, FetchFixture>,
        val searches: Map<String, SearchFixture>,
        val sourceTimes: Map<String, Long>,
        val totalDurationMs: Long
    ) {
        init {
            require(totalDurationMs >= 0L)
            (fetches.keys + sourceTimes.keys + searches.keys.flatMap { key ->
                val response = searches[key]
                if (response is SearchFixture.Success) response.results.map(PublicSearchDiscoveryService.PublicSearchResult::url) else emptyList()
            }).filter { it.startsWith("https://") }.forEach(::requireSyntheticUrl)
        }

        fun elapsedTimeMs(evidence: Evidence): Long = sourceTimes.entries.firstOrNull { (source, _) ->
            evidence.sourceUrl?.let { canonical(it) == canonical(source) } == true ||
                canonical(evidence.value) == canonical(source)
        }?.value ?: 0L
    }

    private data class Trace(
        val run: SyntheticRun,
        val evidence: EvidenceCollection,
        val fetchRequests: List<String>,
        val searchRequests: List<String>,
        val events: List<DiscoveryEvent> = run.events
    )

    private sealed interface SearchFixture {
        data class Success(val results: List<PublicSearchDiscoveryService.PublicSearchResult>) : SearchFixture
        data class Failure(val reason: String) : SearchFixture
    }

    private data class FetchFixture(
        val body: String,
        val statusCode: Int = 200,
        val state: ProviderVerificationState = ProviderVerificationState.Present,
        val contentType: String = "text/html",
        val providerFailure: Boolean = false
    ) {
        fun toResult(requested: String): ProviderExecutionResult = ProviderExecutionResult(
            decision = ProviderResponseDecision(state, "synthetic fixture response"),
            statusCode = statusCode,
            requestedUrl = requested,
            finalUrl = requested,
            bodyText = body,
            latencyMs = 0L,
            attemptCount = 1,
            contentType = contentType
        )
    }

    private class FakeContext(private val root: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }

    private companion object {
        const val SYNTHETIC_RETRIEVAL_EPOCH_MS = 1_700_000_000_000L

        fun searchKey(kind: TypedSeedKind, normalizedValue: String): String =
            "search:${kind.name}:$normalizedValue"

        fun canonical(value: String): String =
            PublicSearchDiscoveryService.canonicalUrlKey(value)

        fun fetchFailure(requested: String): ProviderExecutionResult = ProviderExecutionResult(
            decision = ProviderResponseDecision(ProviderVerificationState.NotFound, "missing synthetic fixture response"),
            statusCode = 404,
            requestedUrl = requested,
            finalUrl = requested,
            bodyText = "",
            latencyMs = 0L,
            attemptCount = 1,
            contentType = "text/plain"
        )

        fun requireSyntheticUrl(value: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            val host = uri?.host?.lowercase()
            require(
                uri?.scheme?.lowercase() == "https" &&
                    uri.userInfo == null &&
                    (host?.endsWith(".test") == true || host == "web.archive.org")
            ) { "Synthetic benchmark URL must use an https .test host or the fixed archive host: $value" }
        }
    }
}
