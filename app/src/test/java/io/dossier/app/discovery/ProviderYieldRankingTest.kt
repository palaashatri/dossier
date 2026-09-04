package io.dossier.app.discovery

import io.dossier.app.domain.discovery.ProviderHealthAssessment
import io.dossier.app.domain.discovery.ProviderHealthDataQuality
import io.dossier.app.domain.discovery.ProviderHealthStatus
import io.dossier.app.domain.discovery.ProviderYieldRanking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ProviderYieldRankingTest {

    @Test
    fun `ranks unvalidated providers highest for exploration`() {
        val assessments = listOf(
            createAssessment("a-healthy", ProviderHealthStatus.Healthy, 10, 1.0, 0.0),
            createAssessment("b-unvalidated", ProviderHealthStatus.Unvalidated, 0, 0.0, 0.0),
            createAssessment("c-healthy", ProviderHealthStatus.Healthy, 10, 0.9, 0.0)
        )

        val ranked = ProviderYieldRanking.rank(assessments) { it }
        assertEquals("b-unvalidated", ranked[0].providerId)
        assertEquals("a-healthy", ranked[1].providerId)
        assertEquals("c-healthy", ranked[2].providerId)
    }

    @Test
    fun `treats stale and invalid diagnostics as exploration candidates`() {
        val assessments = listOf(
            createAssessment("healthy", ProviderHealthStatus.Healthy, 10, 1.0, 0.0),
            createAssessment("stale", ProviderHealthStatus.Stale, 10, 1.0, 0.0),
            createAssessment(
                "invalid",
                ProviderHealthStatus.Unavailable,
                10,
                1.0,
                0.0,
                dataQuality = ProviderHealthDataQuality.Invalid
            )
        )

        val ranked = ProviderYieldRanking.rank(assessments) { it }

        assertEquals(listOf("invalid", "stale", "healthy"), ranked.map { it.providerId })
    }

    @Test
    fun `favors high usable response rate`() {
        val assessments = listOf(
            createAssessment("a", ProviderHealthStatus.Healthy, 10, 0.5, 0.0),
            createAssessment("b", ProviderHealthStatus.Healthy, 10, 0.9, 0.0),
            createAssessment("c", ProviderHealthStatus.Healthy, 10, 0.7, 0.0)
        )
        val ranked = ProviderYieldRanking.rank(assessments) { it }
        assertEquals("b", ranked[0].providerId)
        assertEquals("c", ranked[1].providerId)
        assertEquals("a", ranked[2].providerId)
    }

    @Test
    fun `penalizes high failure rate`() {
        val assessments = listOf(
            createAssessment("a", ProviderHealthStatus.Healthy, 10, 0.5, 0.2),
            createAssessment("b", ProviderHealthStatus.Healthy, 10, 0.5, 0.0),
            createAssessment("c", ProviderHealthStatus.Healthy, 10, 0.5, 0.5)
        )
        val ranked = ProviderYieldRanking.rank(assessments) { it }
        assertEquals("b", ranked[0].providerId)
        assertEquals("a", ranked[1].providerId)
        assertEquals("c", ranked[2].providerId)
    }

    @Test
    fun `penalizes high latency`() {
        val assessments = listOf(
            createAssessment("a", ProviderHealthStatus.Healthy, 10, 0.5, 0.0, 500L),
            createAssessment("b", ProviderHealthStatus.Healthy, 10, 0.5, 0.0, 100L),
            createAssessment("c", ProviderHealthStatus.Healthy, 10, 0.5, 0.0, null)
        )
        val ranked = ProviderYieldRanking.rank(assessments) { it }
        assertEquals("b", ranked[0].providerId)
        assertEquals("a", ranked[1].providerId)
        assertEquals("c", ranked[2].providerId)
    }

    @Test
    fun `preserves stable catalog order on ties`() {
        val assessments = listOf(
            createAssessment("z-tied", ProviderHealthStatus.Healthy, 10, 0.5, 0.0, 100L),
            createAssessment("a-tied", ProviderHealthStatus.Healthy, 10, 0.5, 0.0, 100L),
            createAssessment("m-tied", ProviderHealthStatus.Healthy, 10, 0.5, 0.0, 100L)
        )
        val ranked = ProviderYieldRanking.rank(assessments) { it }
        assertEquals("z-tied", ranked[0].providerId)
        assertEquals("a-tied", ranked[1].providerId)
        assertEquals("m-tied", ranked[2].providerId)
    }

    private fun createAssessment(
        providerId: String,
        status: ProviderHealthStatus,
        attempts: Long,
        usableResponseRate: Double,
        failureRate: Double,
        latencyMs: Long? = 100L,
        dataQuality: ProviderHealthDataQuality = ProviderHealthDataQuality.Valid
    ) = ProviderHealthAssessment(
        providerId = providerId,
        status = status,
        attempts = attempts,
        usableResponses = (attempts * usableResponseRate).toLong(),
        usableResponseRate = usableResponseRate,
        failureRate = failureRate,
        latencyMs = latencyMs,
        lastValidatedAt = Instant.now(),
        dataQuality = dataQuality
    )
}
