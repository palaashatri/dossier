package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileScannerEvidenceTest {

    private fun result(username: String, url: String, exists: Boolean, verified: Boolean) = ProfileScanResult(
        candidate = UsernameCandidate(
            username = username,
            platform = Platform.GitHub,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = 0.9f
        ),
        exists = exists,
        httpStatus = 200,
        displayName = username,
        bio = null,
        links = emptyList(),
        extractedText = "",
        findings = listOf(
            Finding(
                type = FindingType.Email,
                value = "jane@example.com",
                sourceUrl = url,
                evidenceSnippet = "contact",
                confidence = 0.9f,
                risk = RiskLevel.High,
                remediation = "remove"
            )
        ),
        confidenceSignals = listOf("ok"),
        verified = verified,
        verificationStatus = if (verified) "Verified" else "Exists"
    )

    @Test
    fun emitsProfileEvidenceAndRelationships() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            emails = listOf("jane@example.com"),
            usernames = listOf("janedoe")
        )
        val results = listOf(result("janedoe", "https://github.com/janedoe", exists = true, verified = true))

        val collection = results.toEvidenceCollection(input)

        // Profile observation emitted natively (not via Finding adapter).
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Profile && it.value == "https://github.com/janedoe" })
        // PII finding bridged losslessly.
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Email && it.value == "jane@example.com" })
        // Scanner-asserted username↔profile relationship.
        assertTrue(collection.relationships.any { it.relation == "username_on_profile" && it.fromValue == "janedoe" })
        // Scanner-asserted PII-on-profile relationship.
        assertTrue(collection.relationships.any { it.relation == "mentions" && it.toValue == "jane@example.com" })
        // Identity seeds also present (self-contained collection).
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Username && it.value == "janedoe" })
    }

    @Test
    fun emptyResultsStillEmitsIdentitySeeds() {
        // Self-contained collection: even with no profile results, identity seeds
        // are emitted so downstream consumers have the subject's own evidence.
        val input = IdentityInput(fullName = "Jane", usernames = listOf("jane"))
        val collection = listOf<ProfileScanResult>().toEvidenceCollection(input)
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Username && it.value == "jane" })
        assertEquals(0, collection.relationships.size)
    }
}
