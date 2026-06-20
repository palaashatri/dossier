package io.dossier.app

import io.dossier.app.data.breach.BreachCheckService
import org.junit.Assert.assertEquals
import org.junit.Test

class BreachCheckServiceTest {

    @Test
    fun sha1Hex_matchesKnownPasswordHash() {
        val hash = BreachCheckService.sha1Hex("password")

        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", hash)
        assertEquals("5BAA6", hash.take(5))
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
}
