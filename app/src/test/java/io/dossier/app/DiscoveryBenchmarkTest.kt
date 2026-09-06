package io.dossier.app

import io.dossier.app.data.web.DiscoveryBenchmark.EventStatus
import io.dossier.app.data.web.DiscoveryBenchmark.DiscoveryEvent
import io.dossier.app.data.web.DiscoveryBenchmark.Fact
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticCase
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticRun
import io.dossier.app.data.web.DiscoveryBenchmark
import io.dossier.app.data.web.SyntheticDiscoveryBenchmarkFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryBenchmarkTest {

    @Test
    fun syntheticEndToEnd_evaluatesMultiHopChain() = runBlocking {
        val case = SyntheticCase(
            name = "Jane Doe",
            initialSeed = Fact("name", "Jane Doe"),
            expectedFacts = listOf(
                Fact("profile", "https://example.test/janedoe"),
                Fact("username", "janedoe"),
                Fact("email", "jane@example.test"),
                Fact("document", "https://example.test/resume.pdf"),
                Fact("phone", "+15550100")
            ),
            isCompleteGroundTruth = true
        )

        val metrics = DiscoveryBenchmark.evaluateRun(case) {
            listOf(
                // Hop 1: name -> profile/username
                DiscoveryEvent(
                    elapsedTimeMs = 1000,
                    fact = Fact("profile", "https://example.test/janedoe"),
                    status = EventStatus.VERIFIED,
                    providerId = "Search",
                    requestCount = 1,
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                ),
                DiscoveryEvent(
                    elapsedTimeMs = 1050,
                    fact = Fact("username", "janedoe"),
                    status = EventStatus.VERIFIED,
                    providerId = "ProfileParser",
                    requestCount = 0, // from same page
                    isIdentityAnchor = true,
                    usefulPivotCount = 1
                ),
                // Hop 2: username -> email
                DiscoveryEvent(
                    elapsedTimeMs = 2000,
                    fact = Fact("email", "jane@example.test"),
                    status = EventStatus.VERIFIED,
                    providerId = "EmailFinder",
                    requestCount = 1,
                    isIdentityAnchor = false,
                    usefulPivotCount = 1
                ),
                // Hop 3: email -> document/phone
                DiscoveryEvent(
                    elapsedTimeMs = 3000,
                    fact = Fact("document", "https://example.test/resume.pdf"),
                    status = EventStatus.VERIFIED,
                    providerId = "DocSearch",
                    requestCount = 1,
                    isIdentityAnchor = false,
                    usefulPivotCount = 1
                ),
                DiscoveryEvent(
                    elapsedTimeMs = 3500,
                    fact = Fact("phone", "+15550100"),
                    status = EventStatus.VERIFIED,
                    providerId = "DocParser",
                    requestCount = 0,
                    isIdentityAnchor = false,
                    usefulPivotCount = 0
                ),
                // An unverified candidate
                DiscoveryEvent(
                    elapsedTimeMs = 4000,
                    fact = Fact("phone", "+15550200"),
                    status = EventStatus.CANDIDATE,
                    providerId = "DocParser",
                    requestCount = 0,
                    isIdentityAnchor = false,
                    usefulPivotCount = 0
                ),
                // Extra fact (false positive because complete ground truth is true)
                DiscoveryEvent(
                    elapsedTimeMs = 4500,
                    fact = Fact("location", "Test City"),
                    status = EventStatus.VERIFIED,
                    providerId = "LocationFinder",
                    requestCount = 1,
                    isIdentityAnchor = false,
                    usefulPivotCount = 0
                )
            )
        }

        assertEquals(5, metrics.truePositives)
        assertEquals(1, metrics.falsePositives)
        assertEquals(0, metrics.falseNegatives)
        assertEquals(1, metrics.unresolvedCandidateCount)
        assertEquals(0, metrics.unlabelledExtraCount)
        assertEquals(1000L, metrics.timeToFirstUsefulResultMs)
        assertEquals(1000L, metrics.timeToFirstVerifiedIdentityAnchorMs)
        assertEquals(2000L, metrics.timeToFirstHighValueExactIdentifierMs) // Email found at 2000ms
        assertEquals(2000L, metrics.timeTo50PercentRecallMs) // 3 out of 5 expected facts (60%) >= 50%
        assertEquals(3000L, metrics.timeTo80PercentRecallMs) // 4 out of 5 expected facts = 80%, happened at 3000ms
        assertEquals(4500L, metrics.totalScanDurationMs)
    }

    @Test
    fun syntheticEndToEnd_incompleteGroundTruthExtraFactsNotCountedAsFp() = runBlocking {
        val case = SyntheticCase(
            name = "Jane Doe",
            initialSeed = Fact("name", "Jane Doe"),
            expectedFacts = listOf(
                Fact("email", "jane@example.test")
            ),
            isCompleteGroundTruth = false
        )

        val metrics = DiscoveryBenchmark.evaluateRun(case) {
            listOf(
                DiscoveryEvent(
                    elapsedTimeMs = 1000,
                    fact = Fact("email", "jane@example.test"),
                    status = EventStatus.VERIFIED,
                    providerId = "P1",
                    requestCount = 1,
                    isIdentityAnchor = false,
                    usefulPivotCount = 0
                ),
                DiscoveryEvent(
                    elapsedTimeMs = 1500,
                    fact = Fact("phone", "+15550100"),
                    status = EventStatus.VERIFIED,
                    providerId = "P2",
                    requestCount = 1,
                    isIdentityAnchor = false,
                    usefulPivotCount = 0
                )
            )
        }

        assertEquals(1, metrics.truePositives)
        assertEquals(0, metrics.falsePositives) // incomplete ground truth -> phone is unlabelled extra
        assertEquals(1, metrics.unlabelledExtraCount)
    }

    @Test
    fun syntheticRun_distinguishesUnavailableExactValueAndReportsOperationalMetrics() {
        val case = SyntheticCase(
            name = "Unavailable source",
            initialSeed = Fact("name", "Jane Example"),
            expectedFacts = listOf(Fact("email", "jane@example.test")),
            knownNegatives = listOf(Fact("phone", "+15550999"))
        )

        val metrics = DiscoveryBenchmark.evaluate(
            case,
            SyntheticRun(
                events = listOf(
                    DiscoveryEvent(
                        elapsedTimeMs = 600,
                        fact = Fact("email", null),
                        status = EventStatus.UNAVAILABLE,
                        providerId = "ExposureIndex",
                        requestCount = 1
                    ),
                    DiscoveryEvent(
                        elapsedTimeMs = 900,
                        fact = Fact("email", "JANE@EXAMPLE.TEST"),
                        status = EventStatus.VERIFIED,
                        providerId = "PublicPage",
                        requestCount = 1,
                        usefulPivotCount = 2
                    )
                ),
                totalScanDurationMs = 1_200,
                totalRequestCount = 3,
                providerFailureCount = 1
            )
        )

        assertEquals(1, metrics.truePositives)
        assertEquals(0, metrics.falsePositives)
        assertEquals(1, metrics.unresolvedCandidateCount)
        assertEquals(0, metrics.candidateCount)
        assertEquals(1, metrics.unavailableEventCount)
        assertEquals(0, metrics.providerFailureEventCount)
        assertEquals(900L, metrics.timeToFirstHighValueExactIdentifierMs)
        assertEquals(1_200L, metrics.totalScanDurationMs)
        assertEquals(1.0 / 3.0, metrics.providerFailureRate, 0.0001)
        assertEquals(1.0 / 3.0, metrics.usefulFindingsPerRequest, 0.0001)
        assertEquals(2.0, metrics.usefulPivotsPerVerifiedFinding, 0.0001)
        assertEquals(1, metrics.failedRequestCount)
        assertEquals(2, metrics.usefulPivotCount)
        assertEquals(0.0, checkNotNull(metrics.falsePositiveRate), 0.0001)

        val legacyShape = metrics.copy(
            failedRequestCount = 0,
            usefulPivotCount = 0
        )
        val legacyAggregate = DiscoveryBenchmark.aggregate(listOf(legacyShape))
        assertEquals(metrics.providerFailureRate, legacyAggregate.providerFailureRate, 0.0001)
        assertEquals(
            metrics.usefulPivotsPerVerifiedFinding,
            legacyAggregate.usefulPivotsPerVerifiedFinding,
            0.0001
        )
    }

    @Test
    fun syntheticRun_withNoVerifiedFindingsReportsZeroPrecision() {
        val case = SyntheticCase(
            name = "No verified findings",
            initialSeed = Fact("name", "Jane Example"),
            expectedFacts = listOf(Fact("email", "jane@example.test"))
        )

        val metrics = DiscoveryBenchmark.evaluate(
            case,
            SyntheticRun(
                events = listOf(
                    DiscoveryEvent(
                        elapsedTimeMs = 100,
                        fact = Fact("email", null),
                        status = EventStatus.UNAVAILABLE,
                        requestCount = 1
                    )
                ),
                totalRequestCount = 1
            )
        )

        assertEquals(0, metrics.totalVerifiedFindingCount)
        assertEquals(0.0, metrics.precision, 0.0001)
        assertEquals(0.0, DiscoveryBenchmark.aggregate(listOf(metrics)).averagePrecision, 0.0001)
    }

    @Test
    fun syntheticRun_excludesAttackerSeedFromRecoveryAndMilestones() {
        val case = SyntheticCase(
            name = "Attacker seed exclusion",
            initialSeed = Fact("profile", "https://example.test/jane"),
            // The manifest may repeat the seed; it must not become a scored
            // exposure or inflate the known-exposure denominator.
            expectedFacts = listOf(
                // Public-fetch adapters may label the supplied profile URL as
                // `url` rather than `profile`; both forms must be excluded.
                Fact("url", "https://example.test/jane"),
                Fact("email", "jane@example.test")
            )
        )

        val metrics = DiscoveryBenchmark.evaluate(
            case,
            SyntheticRun(
                events = listOf(
                    DiscoveryEvent(
                        elapsedTimeMs = 0,
                        fact = Fact("profile", "https://example.test/jane"),
                        status = EventStatus.VERIFIED,
                        requestCount = 1,
                        isIdentityAnchor = true,
                        usefulPivotCount = 4
                    ),
                    DiscoveryEvent(
                        elapsedTimeMs = 100,
                        fact = Fact("email", "jane@example.test"),
                        status = EventStatus.OBSERVED,
                        requestCount = 1
                    )
                ),
                totalRequestCount = 2
            )
        )

        assertEquals(1, metrics.truePositives)
        assertEquals(0, metrics.falsePositives)
        assertEquals(0, metrics.falseNegatives)
        assertEquals(0, metrics.unlabelledExtraCount)
        assertEquals(0, metrics.totalVerifiedFindingCount)
        assertEquals(1, metrics.totalObservedFindingCount)
        assertEquals(100L, metrics.timeToFirstUsefulResultMs)
        assertNull(metrics.timeToFirstVerifiedIdentityAnchorMs)
        assertEquals(100L, metrics.timeToFirstHighValueExactIdentifierMs)
        assertEquals(100L, metrics.timeTo50PercentRecallMs)
        assertEquals(100L, metrics.timeTo80PercentRecallMs)
        assertEquals(2, metrics.totalProviderRequestCount)
        assertEquals(0, metrics.usefulPivotCount)
    }

    @Test
    fun syntheticRun_observedExpectedContactsCountForExposureRecallOnly() {
        val case = SyntheticCase(
            name = "Observed contacts",
            initialSeed = Fact("name", "Jane Example"),
            expectedFacts = listOf(
                Fact("email", "jane@example.test"),
                Fact("phone", "+1 555 0100")
            )
        )

        val metrics = DiscoveryBenchmark.evaluate(
            case,
            SyntheticRun(
                events = listOf(
                    DiscoveryEvent(
                        elapsedTimeMs = 20,
                        fact = Fact("email", "jane@example.test"),
                        status = EventStatus.OBSERVED,
                        usefulPivotCount = 4
                    ),
                    DiscoveryEvent(
                        elapsedTimeMs = 40,
                        fact = Fact("phone", "+1 555 0100"),
                        status = EventStatus.OBSERVED,
                        usefulPivotCount = 4
                    )
                )
            )
        )

        assertEquals(2, metrics.truePositives)
        assertEquals(2, metrics.observedExpectedCount)
        assertEquals(0, metrics.verifiedExpectedCount)
        assertEquals(1.0, metrics.exposureRecall, 0.0001)
        assertEquals(0, metrics.totalVerifiedFindingCount)
        assertEquals(2, metrics.totalObservedFindingCount)
        assertNull(metrics.timeToFirstVerifiedIdentityAnchorMs)
        assertEquals(20L, metrics.timeToFirstHighValueExactIdentifierMs)
        assertEquals(20L, metrics.timeTo50PercentRecallMs)
        assertEquals(40L, metrics.timeTo80PercentRecallMs)
        assertEquals(0, metrics.usefulPivotCount)
    }

    @Test
    fun syntheticRun_observedKnownNegativeIsNotVerifiedFalsePositive() {
        val case = SyntheticCase(
            name = "Observed known negative",
            initialSeed = Fact("name", "Jane Example"),
            expectedFacts = listOf(Fact("email", "jane@example.test")),
            knownNegatives = listOf(Fact("email", "noise@example.test"))
        )

        val metrics = DiscoveryBenchmark.evaluate(
            case,
            SyntheticRun(
                events = listOf(
                    DiscoveryEvent(
                        elapsedTimeMs = 10,
                        fact = Fact("email", "noise@example.test"),
                        status = EventStatus.OBSERVED
                    ),
                    DiscoveryEvent(
                        elapsedTimeMs = 20,
                        fact = Fact("email", "jane@example.test"),
                        status = EventStatus.OBSERVED
                    ),
                    DiscoveryEvent(
                        elapsedTimeMs = 30,
                        fact = Fact("email", "noise@example.test"),
                        status = EventStatus.CANDIDATE
                    )
                )
            )
        )

        assertEquals(1, metrics.truePositives)
        assertEquals(0, metrics.falsePositives)
        assertEquals(1, metrics.observedKnownNegativeCount)
        assertEquals(1, metrics.observedKnownNegativeEventCount)
        assertEquals(0, metrics.verifiedKnownNegativeCount)
        assertEquals(0.0, checkNotNull(metrics.falsePositiveRate), 0.0001)
        assertEquals(1, metrics.candidateCount)
        assertEquals(2, metrics.totalObservedFindingCount)
    }

    @Test
    fun syntheticRun_normalizesComparisonButRetainsExactSourceAndSupportsAggregate() = runBlocking {
        val case = SyntheticCase(
            name = "Normalization",
            initialSeed = Fact("name", "Jane Example"),
            expectedFacts = listOf(
                Fact("email", "jane@example.test"),
                Fact("profile", "https://EXAMPLE.test/profile/jane#history")
            ),
            isCompleteGroundTruth = true
        )

        val events = listOf(
            DiscoveryEvent(
                elapsedTimeMs = 100,
                fact = Fact("email", " JANE@EXAMPLE.TEST "),
                status = EventStatus.VERIFIED,
                requestCount = 1
            ),
            DiscoveryEvent(
                elapsedTimeMs = 200,
                fact = Fact("profile", "https://example.test/profile/jane"),
                status = EventStatus.VERIFIED,
                requestCount = 1
            ),
            DiscoveryEvent(
                elapsedTimeMs = 250,
                fact = Fact("email", "jane@example.test"),
                status = EventStatus.VERIFIED,
                requestCount = 1
            )
        )

        val metrics = DiscoveryBenchmark.run(case) { SyntheticRun(events) }
        assertEquals(2, metrics.truePositives)
        assertEquals(2, metrics.totalVerifiedFindingCount)
        assertEquals(3, metrics.totalProviderRequestCount)

        val aggregate = DiscoveryBenchmark.aggregate(listOf(metrics, metrics))
        assertEquals(2, aggregate.totalCases)
        assertEquals(4, aggregate.truePositives)
        assertEquals(metrics.recall, aggregate.averageRecall, 0.0001)
        assertNull(metrics.falsePositiveRate)
    }

    @Test
    fun syntheticCorpus_discovererTraversesEveryRequiredMultiHopPath() = runBlocking {
        val fixtures = SyntheticDiscoveryBenchmarkFixtures.corpus()
        val discoverer = SyntheticDiscoveryBenchmarkFixtures.Discoverer(fixtures)

        assertEquals(6, fixtures.size)
        val runs = fixtures.map { fixture ->
            val trace = discoverer.trace(fixture.case)
            assertEquals(trace, discoverer.trace(fixture.case))
            assertRequiredPath(fixture, trace)
            DiscoveryBenchmark.run(fixture.case) { discoverer.discover(it) }
        }

        val aggregate = DiscoveryBenchmark.aggregate(runs)
        assertEquals(6, aggregate.totalCases)
        assertEquals(25, aggregate.truePositives)
        assertEquals(3, aggregate.falsePositives)
        assertEquals(0, aggregate.falseNegatives)
        assertEquals(41.0 / 45.0, aggregate.averagePrecision, 0.0001)
        assertEquals(1.0, aggregate.averageRecall, 0.0001)
        assertEquals(565.0 / 594.0, aggregate.averageF1, 0.0001)
        assertEquals(25, aggregate.knownExposedFacts)
        assertEquals(25, aggregate.recoveredKnownExposedFacts)
        assertEquals(25.0 / 28.0, aggregate.corpusPrecision, 0.0001)
        assertEquals(1.0, aggregate.recallAtKnownExposure, 0.0001)
        assertEquals(1.0, checkNotNull(aggregate.corpusFalsePositiveRate), 0.0001)
        assertEquals(3, aggregate.knownNegativeFacts)
        assertEquals(3, aggregate.observedKnownNegativeFacts)
        assertEquals(85.0, checkNotNull(aggregate.averageTimeToFirstUsefulResultMs), 0.0001)
        assertEquals(178.3333, checkNotNull(aggregate.averageTimeToFirstVerifiedIdentityAnchorMs), 0.001)
        assertEquals(260.0, checkNotNull(aggregate.averageTimeToFirstHighValueExactIdentifierMs), 0.0001)
        assertEquals(183.3333, checkNotNull(aggregate.averageTimeTo50PercentRecallMs), 0.001)
        assertEquals(303.3333, checkNotNull(aggregate.averageTimeTo80PercentRecallMs), 0.001)
        assertEquals(356.6667, checkNotNull(aggregate.averageTotalScanDurationMs), 0.001)
        assertEquals(6, aggregate.timeToFirstUsefulResultCaseCount)
        assertEquals(6, aggregate.timeToFirstVerifiedIdentityAnchorCaseCount)
        assertEquals(4, aggregate.timeToFirstHighValueExactIdentifierCaseCount)
        assertEquals(6, aggregate.timeTo50PercentRecallCaseCount)
        assertEquals(6, aggregate.timeTo80PercentRecallCaseCount)
        assertEquals(7, aggregate.unresolvedCandidateCount)
        assertEquals(3, aggregate.candidateCount)
        assertEquals(3, aggregate.unavailableEventCount)
        assertEquals(1, aggregate.providerFailureEventCount)
        assertEquals(1, aggregate.unlabelledExtraCount)
        assertEquals(29, aggregate.totalVerifiedFindingCount)
        assertEquals(24, aggregate.totalProviderRequestCount)
        assertEquals(4, aggregate.totalFailedRequestCount)
        assertEquals(23, aggregate.totalUsefulPivotCount)
        assertEquals(1.0 / 6.0, aggregate.providerFailureRate, 0.0001)
        assertEquals(29.0 / 24.0, aggregate.usefulFindingsPerRequest, 0.0001)
        assertEquals(23.0 / 29.0, aggregate.usefulPivotsPerVerifiedFinding, 0.0001)
        assertEquals(1.0 / 29.0, aggregate.unlabelledFindingRate, 0.0001)
        assertEquals(25.0 / 28.0, aggregate.weightedPrecision, 0.0001)
        assertEquals(1.0, aggregate.weightedRecall, 0.0001)
    }

    @Test
    fun syntheticAggregate_emptyCorpusUsesNeutralCountsWithoutClaimingPrecision() {
        val aggregate = DiscoveryBenchmark.aggregate(emptyList())

        assertEquals(0, aggregate.totalCases)
        assertEquals(0.0, aggregate.corpusPrecision, 0.0001)
        assertEquals(0.0, aggregate.recallAtKnownExposure, 0.0001)
        assertEquals(0.0, aggregate.unlabelledFindingRate, 0.0001)
        assertNull(aggregate.corpusFalsePositiveRate)
        assertNull(aggregate.averageTimeToFirstUsefulResultMs)
    }

    @Test
    fun syntheticCase_rejectsExpectedAndKnownNegativeOverlapAfterNormalization() {
        val thrown = runCatching {
            SyntheticCase(
                name = "overlap",
                initialSeed = Fact("name", "Jane Example"),
                expectedFacts = listOf(Fact("email", "jane@example.test")),
                knownNegatives = listOf(Fact("email", "JANE@EXAMPLE.TEST"))
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun syntheticCase_rejectsBlankInitialSeedKind() {
        val thrown = runCatching {
            SyntheticCase(
                name = "blank kind",
                initialSeed = Fact("   ", "Jane Example"),
                expectedFacts = listOf(Fact("email", "jane@example.test"))
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun syntheticCase_rejectsBlankOrNormalizationEmptyInitialSeedValue() {
        val thrown1 = runCatching {
            SyntheticCase(
                name = "blank value",
                initialSeed = Fact("name", "   "),
                expectedFacts = listOf(Fact("email", "jane@example.test"))
            )
        }.exceptionOrNull()
        assertTrue(thrown1 is IllegalArgumentException)

        val thrown2 = runCatching {
            SyntheticCase(
                name = "normalization empty value",
                initialSeed = Fact("phone", "not-a-number"),
                expectedFacts = listOf(Fact("email", "jane@example.test"))
            )
        }.exceptionOrNull()
        assertTrue(thrown2 is IllegalArgumentException)
    }

    @Test
    fun syntheticCorpus_truthfullyKeepsCandidateUnavailableAndUnlabelledFactsOutOfRecall() {
        val fixtures = SyntheticDiscoveryBenchmarkFixtures.corpus()
        val discoverer = SyntheticDiscoveryBenchmarkFixtures.Discoverer(fixtures)

        val nameFixture = fixtures.first { it.id == "name-profile-email-document-phone" }
        val nameTrace = discoverer.trace(nameFixture.case)
        val nameMetrics = DiscoveryBenchmark.evaluate(nameFixture.case, nameTrace.run)
        assertTrue(nameTrace.traversed.any { it.event.status == EventStatus.CANDIDATE })
        assertTrue(nameTrace.traversed.any { it.event.status == EventStatus.UNAVAILABLE })
        assertEquals(2, nameMetrics.unresolvedCandidateCount)
        assertEquals(1, nameMetrics.unlabelledExtraCount)
        assertEquals(1, nameMetrics.falsePositives)
        assertEquals(5, nameMetrics.truePositives)

        val photoTrace = discoverer.trace(fixtures.first { it.id == "photo-ocr-location-source-page" }.case)
        assertTrue(photoTrace.traversed.any { it.event.status == EventStatus.CANDIDATE })
        assertTrue(photoTrace.traversed.any { it.event.status == EventStatus.UNAVAILABLE })
    }

    @Test
    fun syntheticRun_handlesOverlappingProviderFailureCountsWithoutDoubleCounting() {
        val case = SyntheticCase(
            name = "Overlapping failures",
            initialSeed = Fact("name", "Jane Example"),
            expectedFacts = listOf(Fact("email", "jane@example.test"))
        )

        val metrics = DiscoveryBenchmark.evaluate(
            case,
            SyntheticRun(
                events = listOf(
                    DiscoveryEvent(
                        elapsedTimeMs = 600,
                        fact = Fact("email", null),
                        status = EventStatus.PROVIDER_FAILURE,
                        providerId = "FailingProvider",
                        requestCount = 2
                    ),
                    DiscoveryEvent(
                        elapsedTimeMs = 900,
                        fact = Fact("email", "jane@example.test"),
                        status = EventStatus.VERIFIED,
                        providerId = "WorkingProvider",
                        requestCount = 1,
                        usefulPivotCount = 1
                    )
                ),
                totalScanDurationMs = 1_000,
                totalRequestCount = 3,
                providerFailureCount = 2
            )
        )

        assertEquals(2, metrics.failedRequestCount)
        assertEquals(3, metrics.totalProviderRequestCount)
        assertEquals(2.0 / 3.0, metrics.providerFailureRate, 0.0001)
        assertEquals(1.0 / 3.0, metrics.usefulFindingsPerRequest, 0.0001)
    }

    @Test
    fun syntheticRun_laterDuplicateAppliesMergePolicyWithoutDoubleCountingFinding() {
        val case = SyntheticCase(
            name = "Duplicate test",
            initialSeed = Fact("name", "Jane"),
            expectedFacts = listOf(Fact("profile", "https://example.test/jane"))
        )

        val metrics = DiscoveryBenchmark.evaluate(
            case,
            SyntheticRun(
                events = listOf(
                    // First event: basic finding, no anchor or pivots
                    DiscoveryEvent(
                        elapsedTimeMs = 500,
                        fact = Fact("profile", "https://example.test/jane"),
                        status = EventStatus.VERIFIED,
                        isIdentityAnchor = false,
                        usefulPivotCount = 0
                    ),
                    // Second event: duplicate fact, but adds identity anchor and 2 pivots
                    DiscoveryEvent(
                        elapsedTimeMs = 1500,
                        fact = Fact("profile", "https://example.test/jane"),
                        status = EventStatus.VERIFIED,
                        isIdentityAnchor = true,
                        usefulPivotCount = 2
                    ),
                    // Third event: another duplicate, just testing pivot max-logic
                    DiscoveryEvent(
                        elapsedTimeMs = 2500,
                        fact = Fact("profile", "https://example.test/jane"),
                        status = EventStatus.VERIFIED,
                        isIdentityAnchor = true,
                        usefulPivotCount = 1
                    )
                ),
                totalScanDurationMs = 3000,
                totalRequestCount = 3
            )
        )

        // Finding counts aren't duplicated
        assertEquals(1, metrics.truePositives)
        assertEquals(0, metrics.falsePositives)
        assertEquals(1, metrics.totalVerifiedFindingCount)

        // Milestones
        assertEquals(500L, metrics.timeToFirstUsefulResultMs)
        assertEquals(1500L, metrics.timeToFirstVerifiedIdentityAnchorMs) // Picked up from the 2nd event

        // Pivots
        assertEquals(2, metrics.usefulPivotCount) // Max of 0, 2, and 1
    }

    private fun assertRequiredPath(
        fixture: SyntheticDiscoveryBenchmarkFixtures.CaseFixture,
        trace: SyntheticDiscoveryBenchmarkFixtures.Trace
    ) {
        var previousTime = -1L
        fixture.requiredPath.windowed(2).forEach { (from, to) ->
            val transition = trace.traversed.firstOrNull {
                it.from.matches(from) &&
                    it.to.matches(to) &&
                    it.event.status == EventStatus.VERIFIED
            }
            assertTrue("${fixture.id} is missing ${from.kind} -> ${to.kind}", transition != null)
            assertTrue(transition!!.event.elapsedTimeMs > previousTime)
            previousTime = transition.event.elapsedTimeMs
        }
    }

}
