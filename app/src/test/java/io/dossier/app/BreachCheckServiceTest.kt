package io.dossier.app

import io.dossier.app.data.breach.BreachCheckService
import io.dossier.app.domain.breach.EmailExposureResult
import io.dossier.app.domain.breach.HibpCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BreachCheckServiceTest {

    @Test
    fun sha1Hex_matchesKnownPasswordHash() {
        val hash = BreachCheckService.sha1Hex("password")

        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", hash)
        assertEquals("5BAA6", hash.take(5))
    }

    @Test
    fun hibpBreachedAccountUrl_normalizesAndEncodesOneAccountPathSegment() {
        assertEquals(
            "https://haveibeenpwned.com/api/v3/breachedaccount/test%40example.com",
            BreachCheckService.hibpBreachedAccountUrl("  TEST@example.com  ")
        )
        assertEquals(
            "https://haveibeenpwned.com/api/v3/breachedaccount/person%2Btag%40example.com",
            BreachCheckService.hibpBreachedAccountUrl("person+tag@example.com")
        )
        assertEquals("test@example.com", BreachCheckService.normalizeEmailForLookup(" TEST@Example.COM "))
    }

    @Test
    fun parsePwnedPasswordRange_returnsMatchingSuffixCount() {
        val body = """
            003D68EB55068C33ACE09247EE4C639306B:2
            1E4C9B93F3F0682250B6CF8331B7EE68FD8:123456
            FFFFFFF000000000000000000000000000000:10
        """.trimIndent()

        val count = BreachCheckService.parsePwnedPasswordRange(
            body,
            "1E4C9B93F3F0682250B6CF8331B7EE68FD8"
        )

        assertEquals(123456, count)
    }

    @Test
    fun parseHibpBreaches_mapsMetadata() {
        val body = """
            [
              {
                "Name": "ExampleBreach",
                "Title": "Example Breach",
                "Domain": "example.com",
                "BreachDate": "2024-01-01",
                "DataClasses": ["Email addresses", "Passwords"]
              }
            ]
        """.trimIndent()

        val breaches = BreachCheckService.parseHibpBreaches(body)

        assertEquals(1, breaches.size)
        assertEquals("ExampleBreach", breaches.first().name)
        assertEquals("Passwords", breaches.first().dataClasses.last())
    }

    @Test
    fun hibpHttpStatusesKeepUnsupportedAndRateLimitedCoverageExplicit() {
        assertEquals(HibpCoverage.ConfirmedNoBreaches, BreachCheckService.hibpCoverageForHttpStatus(404))
        assertEquals(HibpCoverage.CredentialsRejected, BreachCheckService.hibpCoverageForHttpStatus(401))
        assertEquals(HibpCoverage.Unsupported, BreachCheckService.hibpCoverageForHttpStatus(403))
        assertEquals(HibpCoverage.RateLimited, BreachCheckService.hibpCoverageForHttpStatus(429))
        assertEquals(HibpCoverage.Unavailable, BreachCheckService.hibpCoverageForHttpStatus(503))
        assertFalse(
            EmailExposureResult(
                email = "jane@example.test",
                breaches = emptyList(),
                publicEvidence = emptyList(),
                hibpCoverage = HibpCoverage.Unsupported
            ).hasAuthoritativeBreachCoverage
        )
    }
}
