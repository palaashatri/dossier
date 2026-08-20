package io.dossier.app

import io.dossier.app.data.web.LegacyOsintExportParser
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyOsintExportParserTest {

    @Test
    fun twintJsonAcceptsOnlyAuthorizedHandleAndKeepsImportAsCandidate() {
        val raw = """
            [
              {"username":"alice","tweet":"authorized public post","link":"https://x.com/alice/status/1","date":"2024-01-02 03:04:05"},
              {"username":"bob","tweet":"wrong subject","link":"https://x.com/bob/status/2","date":"2024-01-03 03:04:05"}
            ]
        """.trimIndent()

        val result = LegacyOsintExportParser.parse(
            LegacyOsintExportParser.Source.TwintJson,
            raw,
            listOf("@alice")
        )

        assertEquals(1, result.acceptedRecords)
        assertEquals(1, result.rejectedRecords)
        val evidence = result.collection.evidence.single()
        assertEquals(EvidenceState.Candidate, evidence.state)
        assertEquals(EvidenceReliability.ThirdPartyAggregation, evidence.reliability)
        assertEquals("twint-import", evidence.providerId)
        assertTrue(evidence.historical)
        assertTrue(evidence.snippet!!.contains("authorized public post"))
    }

    @Test
    fun snscrapeJsonlParsesNestedUserAndIsoTimestamp() {
        val raw = """
            {"_type":"snscrape.modules.twitter.Tweet","url":"https://x.com/alice/status/10","date":"2025-05-06T07:08:09+00:00","rawContent":"hello","user":{"username":"alice"}}
            {"_type":"snscrape.modules.twitter.Tweet","url":"https://x.com/other/status/11","date":"2025-05-06T07:08:10+00:00","rawContent":"not mine","user":{"username":"other"}}
        """.trimIndent()

        val result = LegacyOsintExportParser.parse(
            LegacyOsintExportParser.Source.SnscrapeJsonl,
            raw,
            listOf("alice")
        )

        assertEquals(1, result.acceptedRecords)
        assertEquals(1, result.rejectedRecords)
        val evidence = result.collection.evidence.single()
        assertEquals("snscrape-import", evidence.providerId)
        assertEquals(1746515289000L, evidence.observedAtEpochMillis)
    }

    @Test
    fun noAuthorizedHandleProducesNoEvidence() {
        val result = LegacyOsintExportParser.parse(
            LegacyOsintExportParser.Source.SnscrapeJsonl,
            "{\"url\":\"https://x.com/a/status/1\",\"username\":\"a\"}",
            emptyList()
        )
        assertEquals(0, result.acceptedRecords)
        assertTrue(result.collection.evidence.isEmpty())
    }
}
