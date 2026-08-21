package io.dossier.app.domain.analysis

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OsintPostProcessorTest {
    @Test
    fun behavioralProfileUsesRealTimestampsAndTextOnly() {
        val base = Instant.parse("2026-08-01T18:00:00Z").toEpochMilli()
        val evidence = (0 until 10).map { index ->
            Evidence(
                id = "e$index",
                kind = EvidenceKind.PublicSearchEvidence,
                value = "https://example.test/$index",
                snippet = "Rust compiler performance analysis JVM runtime tooling question?",
                providerId = if (index < 5) "reddit" else "github",
                observedAtEpochMillis = base + index * 60L * 60L * 1000L
            )
        }

        val result = OsintPostProcessor.buildBehavioralProfile(evidence)

        assertEquals(10, result.textSampleCount)
        assertEquals(10, result.timestampedSampleCount)
        assertTrue(result.hourlyActivityUtc.sum() == 10)
        assertTrue(result.dominantPostingWindowUtc != null)
        assertTrue(result.topics.contains("rust"))
        assertTrue(result.timezoneHypotheses.size <= 3)
    }

    @Test
    fun timezoneIsWithheldForTinySamples() {
        val evidence = listOf(
            Evidence(
                id = "one",
                kind = EvidenceKind.PublicSearchEvidence,
                value = "one",
                snippet = "small sample",
                observedAtEpochMillis = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli()
            )
        )

        val result = OsintPostProcessor.buildBehavioralProfile(evidence)

        assertTrue(result.timezoneHypotheses.isEmpty())
        assertEquals(null, result.dominantPostingWindowUtc)
    }

    @Test
    fun interactionGraphWeightsRepliesAboveMentions() {
        val input = IdentityInput(fullName = "", primaryUsername = "alice")
        val relationships = listOf(
            EvidenceRelationship("alice", "bob", "MENTIONS", "post-1"),
            EvidenceRelationship("alice", "bob", "REPLIES_TO", "post-2"),
            EvidenceRelationship("alice", "carol", "MENTIONS", "post-3")
        )

        val graph = OsintPostProcessor.buildInteractionGraph(input, relationships)

        assertEquals(3, graph.nodeCount)
        assertEquals(2, graph.edgeCount)
        val bob = graph.edges.first { it.target == "bob" }
        val carol = graph.edges.first { it.target == "carol" }
        assertTrue(bob.weight > carol.weight)
        assertTrue(graph.influenceNodes.isNotEmpty())
        assertEquals(1, graph.clusters.size)
    }

    @Test
    fun solarPositionIsPhysicallyBounded() {
        val result = GeoTemporalAnalyzer.solarPosition(
            latitude = 28.6139,
            longitude = 77.2090,
            timestampUtcMillis = Instant.parse("2026-06-21T06:30:00Z").toEpochMilli()
        )

        assertTrue(result.azimuthDegrees in 0.0..360.0)
        assertTrue(result.elevationDegrees in -90.0..90.0)
        assertTrue(result.approximateShadowBearingDegrees in 0.0..360.0)
        if (result.elevationDegrees > 1.0) {
            assertTrue((result.shadowLengthToObjectHeightRatio ?: -1.0) > 0.0)
        }
    }
}
