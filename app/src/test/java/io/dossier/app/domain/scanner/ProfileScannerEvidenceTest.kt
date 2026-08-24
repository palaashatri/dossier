package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.graph.EntityGraphBuilder
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
            confidence = 0.9f,
            providerId = "github"
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
        assertEquals(
            "github",
            collection.evidence.first { it.kind == EvidenceKind.Profile }.providerId
        )
        // PII finding bridged losslessly.
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Email && it.value == "jane@example.com" })
        // Scanner-asserted username↔profile relationship.
        assertTrue(collection.relationships.any { it.relation == "username_on_profile" && it.fromValue == "janedoe" })
        // Scanner-asserted PII-on-profile relationship.
        assertTrue(collection.relationships.any { it.relation == "mentions" && it.toValue == "jane@example.com" })
        // Identity seeds also present (self-contained collection).
        assertTrue(collection.evidence.any { it.kind == EvidenceKind.Username && it.value == "janedoe" })

        val profileEvidence = collection.evidence.single {
            it.kind == EvidenceKind.Profile && it.value == "https://github.com/janedoe"
        }
        val emailEvidence = collection.evidence.first {
            it.kind == EvidenceKind.Email &&
                it.value == "jane@example.com" &&
                it.sourceUrl == "https://github.com/janedoe"
        }
        assertEquals(
            listOf(profileEvidence.id),
            collection.relationships.single { it.relation == "username_on_profile" }.evidenceIds
        )
        assertEquals(
            listOf(emailEvidence.id),
            collection.relationships.single { it.relation == "mentions" }.evidenceIds
        )

        // The graph consumes the same stable IDs instead of relying on a
        // relationship description or endpoint-value inference.
        val graph = EntityGraphBuilder.build(
            input = input,
            evidence = collection.evidence,
            relationships = collection.relationships
        )
        val usernameProfileEdges = graph.edges.filter {
            it.relation == "username_on_profile" &&
                it.fromId == "username:janedoe" &&
                it.toId == "profile:https://github.com/janedoe"
        }
        assertTrue(usernameProfileEdges.isNotEmpty())
        assertTrue(usernameProfileEdges.all { profileEvidence.id in it.evidenceIds })
        val profileMentionEdges = graph.edges.filter {
            it.relation == "mentions" && it.fromId == "profile:https://github.com/janedoe"
        }
        assertTrue(profileMentionEdges.isNotEmpty())
        assertTrue(profileMentionEdges.all { emailEvidence.id in it.evidenceIds })
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

    @Test
    fun absentOrUnverifiedProfilesDoNotClaimDirectPublicReliability() {
        val input = IdentityInput(fullName = "Jane")
        val collection = listOf(
            result("missing", "https://example.test/missing", exists = false, verified = false),
            result("unverified", "https://example.test/unverified", exists = true, verified = false)
        ).toEvidenceCollection(input, retrievedAtEpochMillis = 42_000L)

        val missing = collection.evidence.single { it.id == "profile:https://example.test/missing" }
        val unverified = collection.evidence.single { it.id == "profile:https://example.test/unverified" }
        assertEquals(EvidenceReliability.SearchEngineCandidate, missing.reliability)
        assertEquals(EvidenceState.Candidate, missing.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, unverified.reliability)
        assertEquals(EvidenceState.Observed, unverified.state)
        assertEquals(42_000L, missing.retrievedAtEpochMillis)
    }
}
