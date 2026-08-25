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
        assertEquals(listOf(evidence.id), result.collection.relationships.single().evidenceIds)
    }

    @Test
    fun duplicateRowsKeepStableEvidenceIdsAndMergeRelationshipProvenance() {
        val raw = """
            [
              {"username":"alice","tweet":"first public post","link":"https://x.com/alice/status/1"},
              {"username":"alice","tweet":"second public post","link":"https://x.com/alice/status/1"}
            ]
        """.trimIndent()

        val first = LegacyOsintExportParser.parse(
            LegacyOsintExportParser.Source.TwintJson,
            raw,
            listOf("alice"),
            importDigest = "d".repeat(64)
        )
        val second = LegacyOsintExportParser.parse(
            LegacyOsintExportParser.Source.TwintJson,
            raw,
            listOf("alice"),
            importDigest = "d".repeat(64)
        )

        assertEquals(first.collection.evidence.map { it.id }, second.collection.evidence.map { it.id })
        assertEquals(2, first.collection.evidence.size)
        assertEquals(1, first.collection.relationships.size)
        assertEquals(
            first.collection.evidence.mapTo(mutableSetOf()) { it.id },
            first.collection.relationships.single().evidenceIds.toSet()
        )
        assertTrue(first.collection.evidence.none { it.id.contains("alice", ignoreCase = true) })
    }

    @Test
    fun credentialMaterialInLegacyRowIsRejected() {
        val raw = """
            [{"username":"alice","tweet":"public post","link":"https://x.com/alice/status/1","password":"do-not-import"}]
        """.trimIndent()

        val result = LegacyOsintExportParser.parse(
            LegacyOsintExportParser.Source.TwintJson,
            raw,
            listOf("alice")
        )

        assertEquals(0, result.collection.evidence.size)
        assertTrue(result.collection.relationships.isEmpty())
        assertEquals(1, result.rejectedRecords)
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
