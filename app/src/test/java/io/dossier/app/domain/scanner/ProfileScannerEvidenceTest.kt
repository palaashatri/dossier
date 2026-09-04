package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.toExposureLedger
import io.dossier.app.domain.discovery.TypedSeedEvidenceAdapter
import io.dossier.app.domain.discovery.TypedSeedKind
import io.dossier.app.domain.graph.EntityGraphBuilder
import io.dossier.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileScannerEvidenceTest {

    private fun result(
        username: String,
        url: String,
        exists: Boolean,
        verified: Boolean,
        provenance: String? = null,
        displayName: String? = username,
        bio: String? = null,
        links: List<String> = emptyList(),
        profileImageUrl: String? = null
    ) = ProfileScanResult(
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
        displayName = displayName,
        bio = bio,
        profileImageUrl = profileImageUrl,
        links = links,
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
        verificationStatus = if (verified) "Verified" else "Exists",
        provenance = provenance
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
            result(
                "unverified",
                "https://example.test/unverified",
                exists = true,
                verified = false,
                links = listOf("https://links.example.test/resume.pdf?download=1")
            )
        ).toEvidenceCollection(input, retrievedAtEpochMillis = 42_000L)

        val missing = collection.evidence.single { it.id == "profile:https://example.test/missing" }
        val unverified = collection.evidence.single { it.id == "profile:https://example.test/unverified" }
        assertEquals(EvidenceReliability.SearchEngineCandidate, missing.reliability)
        assertEquals(EvidenceState.Candidate, missing.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, unverified.reliability)
        assertEquals(EvidenceState.Observed, unverified.state)
        assertEquals(42_000L, missing.retrievedAtEpochMillis)
        val unverifiedLink = collection.evidence.single { it.value == "https://links.example.test/resume.pdf?download=1" }
        assertEquals(EvidenceState.Observed, unverifiedLink.state)
        assertEquals(EvidenceReliability.SearchEngineCandidate, unverifiedLink.reliability)
    }

    @Test
    fun profileProvenanceFlowsIntoProfileFindingAndLedgerFacts() {
        val path = "seed:name -> verified-profile"
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                provenance = path
            )
        ).toEvidenceCollection(input)

        val profile = collection.evidence.single { it.kind == EvidenceKind.Profile }
        val email = collection.evidence.single { it.kind == EvidenceKind.Email }
        assertEquals(listOf(path), profile.discoveryPath)
        assertEquals(listOf(path), email.discoveryPath)
    }

    @Test
    fun extractsVerifiedProfileFieldsAsTypedEvidence() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                displayName = "Jane Verified",
                bio = "Software Engineer",
                profileImageUrl = "https://example.com/avatar.jpg"
            )
        ).toEvidenceCollection(input)
        val nameEv = collection.evidence.find { it.kind == EvidenceKind.Username && it.value == "Jane Verified" }
        assertTrue(nameEv != null)
        assertTrue(nameEv?.state == EvidenceState.Verified)
        assertTrue(nameEv?.reliability == EvidenceReliability.DirectPublicProfile)
        val bioEv = collection.evidence.find { it.kind == EvidenceKind.SensitiveSnippet && it.value == "Software Engineer" }
        assertTrue(bioEv != null)

        val avatarEv = collection.evidence.find { it.kind == EvidenceKind.Image && it.value == "https://example.com/avatar.jpg" }
        assertTrue(avatarEv != null)
        assertEquals(EvidenceState.Verified, avatarEv?.state)
        assertEquals(EvidenceReliability.DirectPublicProfile, avatarEv?.reliability)
        assertTrue(collection.relationships.any {
            it.relation == "uses_avatar" && avatarEv?.id in it.evidenceIds
        })
    }

    @Test
    fun extractsPublicLinksAsTypedEvidence() {
        val input = IdentityInput(fullName = "Jane Doe")
        val collection = listOf(
            result(
                username = "janedoe",
                url = "https://github.com/janedoe",
                exists = true,
                verified = true,
                links = listOf(
                    "https://example.com/resume.pdf?download=1",
                    "https://web.archive.org/web/123/example.com?output=1",
                    "https://blog.com"
                )
            )
        ).toEvidenceCollection(input)
        val docEv = collection.evidence.find {
            it.kind == EvidenceKind.Document && it.value == "https://example.com/resume.pdf?download=1"
        }
        assertTrue(docEv != null)
        val archiveEv = collection.evidence.find {
            it.kind == EvidenceKind.Archive && it.value == "https://web.archive.org/web/123/example.com?output=1"
        }
        assertTrue(archiveEv != null)
        val urlEv = collection.evidence.find { it.kind == EvidenceKind.Url && it.value == "https://blog.com" }
        assertTrue(urlEv != null)
        val domainEv = collection.evidence.find { it.kind == EvidenceKind.Domain && it.value == "blog.com" }
        assertTrue(domainEv != null)

        val typed = TypedSeedEvidenceAdapter.admit(collection.evidence, input)
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Document && docEv?.id in it.evidenceIds })
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Archive && archiveEv?.id in it.evidenceIds })
        assertTrue(typed.admittedSeeds.any { it.kind == TypedSeedKind.Domain && domainEv?.id in it.evidenceIds })
    }

    @Test
    fun profileAttributeEvidenceIdsAreStableDigestsAndKeepExactValues() {
        val input = IdentityInput(fullName = "Jane Doe")
        val result = result(
            username = "janedoe",
            url = "https://github.com/janedoe",
            exists = true,
            verified = true,
            displayName = "Jane Verified"
        )
        val first = listOf(result).toEvidenceCollection(input)
        val second = listOf(result).toEvidenceCollection(input)
        val firstName = first.evidence.single { it.value == "Jane Verified" }
        val secondName = second.evidence.single { it.value == "Jane Verified" }
        assertEquals(firstName.id, secondName.id)
        assertTrue(firstName.id.startsWith("profile:display-name:"))
        assertTrue("Jane Verified" !in firstName.id)
        assertEquals("Jane Verified", firstName.value)
        assertEquals("https://github.com/janedoe", firstName.sourceUrl)
    }
}
