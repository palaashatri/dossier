package io.dossier.app

import io.dossier.app.data.web.ArchivePageResolver
import io.dossier.app.data.web.PublicPageVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ArchivePageResolverTest {

    @Test
    fun parseAvailability_acceptsAccessibleSuccessfulCapture() {
        val payload = """
            {
              "archived_snapshots": {
                "closest": {
                  "available": true,
                  "url": "http://web.archive.org/web/20240102030405/https://example.com/user",
                  "timestamp": "20240102030405",
                  "status": "200"
                }
              }
            }
        """.trimIndent()

        val capture = ArchivePageResolver.parseAvailability(payload)

        requireNotNull(capture)
        assertEquals("20240102030405", capture.timestamp)
        assertEquals(
            "http://web.archive.org/web/20240102030405/https://example.com/user",
            capture.snapshotUrl
        )
    }

    @Test
    fun parseAvailability_rejectsUnavailableOrNonSuccessfulCapture() {
        assertNull(
            ArchivePageResolver.parseAvailability(
                """{"archived_snapshots":{"closest":{"available":false,"status":"200","url":"https://web.archive.org/x","timestamp":"2024"}}}"""
            )
        )
        assertNull(
            ArchivePageResolver.parseAvailability(
                """{"archived_snapshots":{"closest":{"available":true,"status":"404","url":"https://web.archive.org/x","timestamp":"2024"}}}"""
            )
        )
    }

    @Test
    fun normalizeSnapshotUrl_upgradesWaybackHttpAndRejectsOtherHosts() {
        assertEquals(
            "https://web.archive.org/web/20240101/https://example.com",
            ArchivePageResolver.normalizeSnapshotUrl(
                "http://web.archive.org/web/20240101/https://example.com"
            )
        )
        assertNull(ArchivePageResolver.normalizeSnapshotUrl("https://evil.example/archive"))
    }

    @Test
    fun historicalEvidenceNeverReceivesCurrentPageConfidence() {
        assertEquals(0.78f, PublicPageVerifier.historicalConfidenceCeiling(0.99f), 0.0001f)
        assertEquals(0.60f, PublicPageVerifier.historicalConfidenceCeiling(0.60f), 0.0001f)
        assertTrue(PublicPageVerifier.historicalConfidenceCeiling(1.0f) < 0.80f)
    }

    @Test
    fun displayTimestamp_isStableAndHumanReadable() {
        assertEquals("2024-01-02", ArchivePageResolver.displayTimestamp("20240102030405"))
        assertEquals("2024-01", ArchivePageResolver.displayTimestamp("202401"))
        assertEquals("2024", ArchivePageResolver.displayTimestamp("2024"))
    }

    @Test
    fun boundedRead_returnsSmallPayloadAndRejectsOversizedPayload() {
        val small = "archived page".toByteArray()
        assertArrayEquals(
            small,
            ArchivePageResolver.readBounded(ByteArrayInputStream(small), maxBytes = 64)
        )

        val oversized = ByteArray(65) { 1 }
        assertNull(
            ArchivePageResolver.readBounded(ByteArrayInputStream(oversized), maxBytes = 64)
        )
    }
}
