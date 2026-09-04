package io.dossier.app

import io.dossier.app.data.web.DiscoveryBenchmark.EventStatus
import io.dossier.app.data.web.DiscoveryBenchmark.DiscoveryEvent
import io.dossier.app.data.web.DiscoveryBenchmark.Fact
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticCase
import io.dossier.app.data.web.DiscoveryBenchmark.SyntheticRun
import io.dossier.app.data.web.DiscoveryBenchmark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            initialSeed = Fact("email", "jane@example.test"),
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
        assertEquals(900L, metrics.timeToFirstHighValueExactIdentifierMs)
        assertEquals(1_200L, metrics.totalScanDurationMs)
        assertEquals(2.0 / 3.0, metrics.providerFailureRate, 0.0001)
        assertEquals(1.0 / 3.0, metrics.usefulFindingsPerRequest, 0.0001)
        assertEquals(2.0, metrics.usefulPivotsPerVerifiedFinding, 0.0001)
        assertEquals(0.0, checkNotNull(metrics.falsePositiveRate), 0.0001)
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
}
