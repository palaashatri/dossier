package io.dossier.app.data.web

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOsintReportParserTest {
    @Test
    fun sherlockExplicitHandleProducesCandidateProfile() {
        val input = IdentityInput(fullName = "Jane Doe", primaryUsername = "janedoe")
        val report = """site,url,status
GitHub,https://github.com/janedoe,found
Reddit,https://www.reddit.com/user/janedoe,found
""".trimIndent()

        val result = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.Sherlock,
            report,
            input
        )

        assertEquals(2, result.collection.evidence.size)
        assertTrue(result.collection.evidence.all { it.state == EvidenceState.Candidate })
        assertTrue(result.collection.evidence.all { it.reliability == EvidenceReliability.ThirdPartyAggregation })
        assertTrue(result.collection.evidence.all { it.kind == EvidenceKind.Profile })
        assertEquals(2, result.collection.relationships.size)
        assertEquals(
            result.collection.evidence.mapTo(mutableSetOf(), io.dossier.app.domain.evidence.Evidence::id),
            result.collection.relationships.flatMap { it.evidenceIds }.toSet()
        )
    }

    @Test
    fun duplicatePublicRowsKeepStableEvidenceIdsAndMergeRelationshipIds() {
        val input = IdentityInput(fullName = "Jane Doe", primaryUsername = "janedoe")
        val report = """site,url,status
            GitHub,https://github.com/janedoe,found
            Mirror,https://github.com/janedoe,found
        """.trimIndent()

        val first = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.Sherlock,
            report,
            input,
            importDigest = "c".repeat(64)
        )
        val second = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.Sherlock,
            report,
            input,
            importDigest = "c".repeat(64)
        )

        assertEquals(first.collection.evidence.map { it.id }, second.collection.evidence.map { it.id })
        assertEquals(2, first.collection.evidence.size)
        assertEquals(1, first.collection.relationships.size)
        assertEquals(
            first.collection.evidence.mapTo(mutableSetOf(), io.dossier.app.domain.evidence.Evidence::id),
            first.collection.relationships.single().evidenceIds.toSet()
        )
        assertTrue(first.collection.evidence.none { record ->
            record.id.contains("janedoe", ignoreCase = true) || record.id.contains("github.com", ignoreCase = true)
        })
    }

    @Test
    fun unrelatedUsernameEnumerationIsRejected() {
        val input = IdentityInput(fullName = "", primaryUsername = "janedoe")
        val report = "https://github.com/random-stranger\nhttps://reddit.com/user/random-stranger"

        val result = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.Maigret,
            report,
            input
        )

        assertTrue(result.collection.evidence.isEmpty())
        assertTrue(result.rejectedRecords > 0)
    }

    @Test
    fun breachSummaryWithCredentialFieldIsRejectedEntirely() {
        val input = IdentityInput(fullName = "", emails = listOf("jane@example.com"))
        val report = """[
          {
            "email": "jane@example.com",
            "breach": "Example breach",
            "password": "do-not-import-me",
            "url": "https://example.com/breach"
          }
        ]""".trimIndent()

        val result = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.LeakCheckSummary,
            report,
            input
        )

        assertTrue(result.collection.evidence.isEmpty())
        assertTrue(result.collection.relationships.isEmpty())
        assertTrue(result.warnings.any { it.contains("credential/secret", ignoreCase = true) })
    }

    @Test
    fun redactedBreachSummaryMayRemainLowConfidenceCandidate() {
        val input = IdentityInput(fullName = "", emails = listOf("jane@example.com"))
        val report = "email=jane@example.com | breach=Example breach | exposed=data classes only"

        val result = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.DehashedSummary,
            report,
            input
        )

        assertEquals(1, result.collection.evidence.size)
        val item = result.collection.evidence.single()
        assertEquals(EvidenceState.Candidate, item.state)
        assertTrue(item.confidence < 0.5f)
        assertFalse(item.value.contains("password", ignoreCase = true))
    }

    @Test
    fun phoneInfogaRequiresExplicitPhoneSeed() {
        val input = IdentityInput(fullName = "", phones = listOf("+91 98765 43210"))
        val report = "number=+91 98765 43210 | country=India | carrier=Example Carrier"

        val result = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.PhoneInfoga,
            report,
            input
        )

        assertEquals(1, result.collection.evidence.size)
        assertEquals(EvidenceKind.Phone, result.collection.evidence.single().kind)
    }

    @Test
    fun amassOnlyRetainsInScopeDomainUrls() {
        val input = IdentityInput(fullName = "", emails = listOf("security@example.com"))
        val report = """host,url
api.example.com,https://api.example.com/status
unrelated.test,https://unrelated.test/status
""".trimIndent()

        val result = ExternalOsintReportParser.parse(
            ExternalOsintReportParser.Source.Amass,
            report,
            input
        )

        assertEquals(1, result.collection.evidence.size)
        assertTrue(result.collection.evidence.single().value.contains("example.com"))
    }

    @Test
    fun sourceDetectionRecognizesCommonTools() {
        assertEquals(
            ExternalOsintReportParser.Source.SpiderFoot,
            ExternalOsintReportParser.detectSource("spiderfoot-results.json", "[]")
        )
        assertEquals(
            ExternalOsintReportParser.Source.Sherlock,
            ExternalOsintReportParser.detectSource("sherlock.csv", "site,url")
        )
        assertEquals(
            ExternalOsintReportParser.Source.GenericPublicReport,
            ExternalOsintReportParser.detectSource("report.txt", "plain public report")
        )
    }
}
