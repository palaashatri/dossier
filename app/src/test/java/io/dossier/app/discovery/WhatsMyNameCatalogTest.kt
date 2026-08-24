package io.dossier.app.discovery

import io.dossier.app.domain.discovery.ProviderCategory
import io.dossier.app.domain.discovery.ProviderDefinitionValidator
import io.dossier.app.domain.discovery.ProviderResponseObservation
import io.dossier.app.domain.discovery.ProviderVerificationState
import io.dossier.app.domain.discovery.WhatsMyNameCatalog
import io.dossier.app.domain.discovery.WhatsMyNameCatalogState
import io.dossier.app.domain.discovery.WhatsMyNameExclusionReason
import io.dossier.app.domain.discovery.WhatsMyNameResponseClassifier
import io.dossier.app.domain.discovery.WhatsMyNameSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class WhatsMyNameCatalogTest {
    @Test
    fun rejectsOversizedOrHashMismatchedCatalogs() {
        val oversized = WhatsMyNameCatalog.parse(ByteArray(WhatsMyNameCatalog.MAX_SIZE_BYTES + 1))
        assertUnavailable(oversized, "Catalog exceeds the maximum size")

        val badHash = WhatsMyNameCatalog.parse(validCatalogJson().toByteArray())
        assertUnavailable(badHash, "Catalog integrity hash does not match")
    }

    @Test
    fun checkedInAssetHasPinnedIntegrityCountsAndValidDefinitions() {
        val file = File("src/main/assets/providers/whatsmyname/wmn-data.json")
        assertTrue("Bundled catalog asset must exist", file.isFile)
        val bytes = file.readBytes()
        assertEquals(WhatsMyNameCatalog.PINNED_SIZE_BYTES, bytes.size)

        val ready = WhatsMyNameCatalog.parse(bytes) as? WhatsMyNameCatalogState.Ready
            ?: throw AssertionError("Pinned catalog did not parse as ready")
        assertEquals(716, ready.totalCount)
        assertEquals(644, ready.executableCount)
        assertEquals(72, ready.excludedCount)
        assertTrue(ready.license.any { it.contains("Micah Hoffman") })
        assertTrue(ready.authors.isNotEmpty())
        assertEquals(21, ready.categories.size)
        assertEquals(ready.sites.size, ready.sites.map { it.id }.distinct().size)
        assertTrue(ready.sites.all { SAFE_PROVIDER_ID.matches(it.id) })
        assertTrue(ready.sites.all { it.uriCheck.startsWith("https://") })
        assertTrue(ready.sites.all {
            ProviderDefinitionValidator.validate(it.toProviderDefinition()).isEmpty()
        })
    }

    @Test
    fun parserRetainsExplicitReasonForEveryPolicyExclusion() {
        val source = """
            {
              "license": ["Test license"],
              "authors": ["Test author"],
              "categories": ["social"],
              "sites": [
                ${validRecord("Valid", "https://valid.example/{account}")},
                {"valid": true},
                {"name": "Not valid", "valid": false},
                {"name": "POST", "post_body": "name={account}"},
                {"name": "Adult", "cat": "xx NSFW xx"},
                {"name": "No token", "uri_check": "https://none.example/user"},
                {"name": "Two tokens", "uri_check": "https://two.example/{account}/{account}"},
                {"name": "HTTP", "uri_check": "http://http.example/{account}"},
                {"name": "Bad host", "uri_check": "https:///{account}"},
                {"name": "Protected", "uri_check": "https://protected.example/{account}",
                 "protection": ["user-auth"]},
                {"name": "Bad status", "uri_check": "https://status.example/{account}",
                 "e_code": 99, "m_code": 404},
                {"name": "Ambiguous", "uri_check": "https://ambiguous.example/{account}",
                 "e_code": 200, "e_string": "", "m_code": 200, "m_string": ""},
                {"name": ["wrong type"], "uri_check": "https://schema.example/{account}"}
              ]
            }
        """.trimIndent()

        val ready = parseSynthetic(source) as? WhatsMyNameCatalogState.Ready
            ?: throw AssertionError("Synthetic policy catalog was not ready")
        assertEquals(13, ready.totalCount)
        assertEquals(1, ready.executableCount)
        assertEquals(12, ready.excludedCount)
        val reasons = ready.excluded.map { it.reason }.toSet()
        assertEquals(WhatsMyNameExclusionReason.entries.toSet(), reasons)
    }

    @Test
    fun parserRejectsDuplicateIdsAndMalformedRequiredMetadata() {
        val duplicate = """
            {
              "license": ["Test license"],
              "authors": ["Test author"],
              "categories": ["social"],
              "sites": [
                ${validRecord("Duplicate", "https://duplicate.example/{account}")},
                ${validRecord("Duplicate", "https://duplicate.example/{account}")}
              ]
            }
        """.trimIndent()
        val duplicateState = parseSynthetic(duplicate)
        assertTrue(duplicateState is WhatsMyNameCatalogState.Unavailable)
        assertTrue((duplicateState as WhatsMyNameCatalogState.Unavailable).reason.contains("duplicate provider id"))

        val malformedCatalogs = listOf(
            """{"authors":["A"],"categories":["social"],"sites":[{}]}""",
            """{"license":[],"authors":["A"],"categories":["social"],"sites":[{}]}""",
            """{"license":["L"],"authors":{},"categories":["social"],"sites":[{}]}""",
            """{"license":["L"],"authors":["A"],"categories":["social"],"sites":{}}"""
        )
        malformedCatalogs.forEach { source ->
            assertTrue(parseSynthetic(source) is WhatsMyNameCatalogState.Unavailable)
        }
    }

    @Test
    fun responseClassifierIsFailClosedAndCaseSensitive() {
        val site = site(eCode = 200, eString = "PresentMarker", mCode = 404, mString = "MissingMarker")
        assertState(site, 200, "PresentMarker", ProviderVerificationState.Present)
        assertState(site, 404, "MissingMarker", ProviderVerificationState.NotFound)
        assertState(site, 200, "presentmarker", ProviderVerificationState.InvalidResponse)
        assertState(site, 200, "unrelated", ProviderVerificationState.InvalidResponse)
        assertState(site, null, "", ProviderVerificationState.InvalidResponse)
        assertState(site, 401, "", ProviderVerificationState.AuthenticationRequired)
        assertState(site, 403, "", ProviderVerificationState.AuthenticationRequired)
        assertState(site, 429, "", ProviderVerificationState.RateLimited)
        assertState(site, 200, "verify you are human", ProviderVerificationState.AutomationChallenged)

        val redirected = WhatsMyNameResponseClassifier.classify(
            site,
            ProviderResponseObservation(
                statusCode = 200,
                requestedUrl = "https://test.com/user",
                finalUrl = "https://eviltest.com/user",
                bodyText = "PresentMarker"
            )
        )
        assertEquals(ProviderVerificationState.RedirectedOutsideProvider, redirected.state)
    }

    @Test
    fun sameStatusRulesRequireOneUnambiguousMarker() {
        val site = site(eCode = 200, eString = "exists", mCode = 200, mString = "missing")
        assertState(site, 200, "exists", ProviderVerificationState.Present)
        assertState(site, 200, "missing", ProviderVerificationState.NotFound)
        assertState(site, 200, "exists missing", ProviderVerificationState.InvalidResponse)
    }

    private fun assertState(
        site: WhatsMyNameSite,
        status: Int?,
        body: String,
        expected: ProviderVerificationState
    ) {
        val decision = WhatsMyNameResponseClassifier.classify(
            site,
            ProviderResponseObservation(
                statusCode = status,
                requestedUrl = "https://test.com/user",
                finalUrl = status?.let { "https://test.com/user" },
                bodyText = body
            )
        )
        assertEquals(expected, decision.state)
    }

    private fun site(
        eCode: Int,
        eString: String,
        mCode: Int,
        mString: String
    ) = WhatsMyNameSite(
        id = "wmn-test-0123456789",
        name = "Test",
        category = ProviderCategory.Social,
        uriPretty = "https://test.com/{account}",
        uriCheck = "https://test.com/{account}",
        eCode = eCode,
        eString = eString,
        mCode = mCode,
        mString = mString,
        stripBadChar = ""
    )

    private fun parseSynthetic(source: String): WhatsMyNameCatalogState {
        val bytes = source.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02X".format(it) }
        return WhatsMyNameCatalog.parse(bytes, hash)
    }

    private fun validCatalogJson(): String = """
        {
          "license": ["Test license"],
          "authors": ["Test author"],
          "categories": ["social"],
          "sites": [${validRecord("Valid", "https://valid.example/{account}")}]
        }
    """.trimIndent()

    private fun validRecord(name: String, uri: String): String =
        """{"name":"$name","cat":"social","uri_check":"$uri","e_code":200,"e_string":"exists","m_code":404,"m_string":"missing"}"""

    private fun assertUnavailable(state: WhatsMyNameCatalogState, expectedReason: String) {
        assertTrue(state is WhatsMyNameCatalogState.Unavailable)
        assertEquals(expectedReason, (state as WhatsMyNameCatalogState.Unavailable).reason)
    }

    private companion object {
        val SAFE_PROVIDER_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    }
}
