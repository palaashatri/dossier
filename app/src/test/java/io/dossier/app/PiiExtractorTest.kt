package io.dossier.app

import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.pii.PiiExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiExtractorTest {

    @Test
    fun genericEmailIsDetectedButNotAttributedAtHighRisk() {
        val findings = PiiExtractor().extract(
            "Contact the site administrator at user@example.com for audits.",
            "https://example.org/about"
        )
        val email = findings.firstOrNull { it.type == FindingType.Email }
        assertNotNull(email)
        assertEquals("user@example.com", email?.value)
        assertEquals(RiskLevel.Low, email?.risk)
        assertTrue((email?.confidence ?: 1f) < 0.5f)
    }

    @Test
    fun exactSuppliedEmailReceivesStrongAttribution() {
        val identity = IdentityInput(fullName = "Jane Doe", emails = listOf("jane@example.com"))
        val findings = PiiExtractor().extract(
            "Jane Doe can be reached at jane@example.com.",
            "https://example.org/jane",
            identity
        )
        val email = findings.firstOrNull { it.type == FindingType.Email }
        assertEquals(RiskLevel.High, email?.risk)
        assertTrue((email?.confidence ?: 0f) >= 0.95f)
    }

    @Test
    fun genericPhoneRequiresPhoneContextAndStaysReviewOnly() {
        val findings = PiiExtractor().extract(
            "My direct phone number is +1-555-0199.",
            "https://example.org/user"
        )
        val phone = findings.firstOrNull { it.type == FindingType.Phone }
        assertNotNull(phone)
        assertEquals(RiskLevel.Low, phone?.risk)
    }

    @Test
    fun datesAndCountersAreNotPhoneFindings() {
        val findings = PiiExtractor().extract(
            "Build date 2026-08-02. Views 12345678.",
            "https://example.org/releases"
        )
        assertNull(findings.firstOrNull { it.type == FindingType.Phone })
    }

    @Test
    fun extractsAndReclassifiesLocationAndOrganizationContext() {
        val findings = PiiExtractor().extract(
            "I am from New York and I works at Google.",
            "https://github.com/user"
        )
        assertEquals("New York", findings.firstOrNull { it.type == FindingType.Location }?.value)
        assertEquals("Google", findings.firstOrNull { it.type == FindingType.Organization }?.value)
    }

    @Test
    fun extractsSelfSuppliedNameAliasLocationAndOrganization() {
        val identity = IdentityInput(
            fullName = "Jane Doe",
            aliases = listOf("janedoe", "doe-jane"),
            emails = listOf("jane@example.com"),
            phones = listOf("1234567890"),
            locations = listOf("New Delhi"),
            organizations = listOf("Dossier Security")
        )
        val text = "This is Jane Doe. My alias is janedoe. I live in New Delhi and work at Dossier Security."
        val findings = PiiExtractor().extract(text, "https://github.com/janedoe", identity)

        assertNotNull(findings.firstOrNull { it.value == "Name Exposure: Jane Doe" })
        assertNotNull(findings.firstOrNull { it.value == "Alias Exposure: janedoe" })
        assertNotNull(findings.firstOrNull { it.type == FindingType.Location && it.value == "New Delhi" })
        assertNotNull(findings.firstOrNull { it.type == FindingType.Organization && it.value == "Dossier Security" })
    }

    @Test
    fun smartReclassificationUsesKnownEntityContext() {
        val findings = PiiExtractor().extract(
            "Jane is a developer from Replit who works at Delhi.",
            "https://github.com/jane"
        )
        val replit = findings.firstOrNull { it.value == "Replit" }
        val delhi = findings.firstOrNull { it.value == "Delhi" }
        assertEquals(FindingType.Organization, replit?.type)
        assertEquals(FindingType.Location, delhi?.type)
    }
}
