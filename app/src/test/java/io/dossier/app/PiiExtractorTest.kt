package io.dossier.app

import io.dossier.app.domain.pii.PiiExtractor
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.IdentityInput

import org.junit.Assert.*
import org.junit.Test

class PiiExtractorTest {

    @Test
    fun testEmailExtraction() {
        val extractor = PiiExtractor()
        val text = "Contact me at user@example.com for audits."
        val findings = extractor.extract(text, "https://github.com/user")

        val emailFinding = findings.firstOrNull { it.type == FindingType.Email }
        assertNotNull("Should extract email finding", emailFinding)
        assertEquals("user@example.com", emailFinding?.value)
        assertEquals(RiskLevel.High, emailFinding?.risk)
    }

    @Test
    fun testPhoneExtraction() {
        val extractor = PiiExtractor()
        val text = "My direct phone number is +1-555-0199."
        val findings = extractor.extract(text, "https://github.com/user")

        val phoneFinding = findings.firstOrNull { it.type == FindingType.Phone }
        assertNotNull("Should extract phone finding", phoneFinding)
        assertEquals("+1-555-0199", phoneFinding?.value)
        assertEquals(RiskLevel.Critical, phoneFinding?.risk)
    }

    @Test
    fun testLocationAndOrgExtraction() {
        val extractor = PiiExtractor()
        val text = "I am from New York and I works at Google."
        val findings = extractor.extract(text, "https://github.com/user")

        val locationFinding = findings.firstOrNull { it.type == FindingType.Location }
        assertNotNull("Should extract location finding", locationFinding)
        assertEquals("New York", locationFinding?.value)

        val orgFinding = findings.firstOrNull { it.type == FindingType.Organization }
        assertNotNull("Should extract organization finding", orgFinding)
        assertEquals("Google", orgFinding?.value)
    }

    @Test
    fun testNameAndAliasExposureExtraction() {
        val extractor = PiiExtractor()
        val identity = IdentityInput(
            fullName = "Jane Doe",
            aliases = listOf("janedoe", "doe-jane"),
            emails = listOf("jane@example.com"),
            phones = listOf("1234567890"),
            locations = listOf("New Delhi"),
            organizations = listOf("Dossier Security")
        )
        val text = "This is Jane Doe. My alias is janedoe. I live in New Delhi and work at Dossier Security."
        val findings = extractor.extract(text, "https://github.com/janedoe", identity)

        val nameFinding = findings.firstOrNull { it.value == "Name Exposure: Jane Doe" }
        assertNotNull("Should extract name exposure finding", nameFinding)
        assertEquals(FindingType.SensitiveSnippet, nameFinding?.type)

        val aliasFinding = findings.firstOrNull { it.value == "Alias Exposure: janedoe" }
        assertNotNull("Should extract alias exposure finding", aliasFinding)

        val locationFinding = findings.firstOrNull { it.type == FindingType.Location && it.value == "New Delhi" }
        assertNotNull("Should extract location finding", locationFinding)

        val orgFinding = findings.firstOrNull { it.type == FindingType.Organization && it.value == "Dossier Security" }
        assertNotNull("Should extract organization finding", orgFinding)
    }

    @Test
    fun testSmartReclassification() {
        val extractor = PiiExtractor()
        val text = "Jane is a developer from Replit who works at Delhi."
        val findings = extractor.extract(text, "https://github.com/jane")
        
        val replitFinding = findings.firstOrNull { it.value == "Replit" }
        assertNotNull("Should find Replit", replitFinding)
        assertEquals("Replit matched 'from Replit' but should be classified as Organization", FindingType.Organization, replitFinding?.type)

        val delhiFinding = findings.firstOrNull { it.value == "Delhi" }
        assertNotNull("Should find Delhi", delhiFinding)
        assertEquals("Delhi matched 'works at Delhi' but should be classified as Location", FindingType.Location, delhiFinding?.type)
    }
}
