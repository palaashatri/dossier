package io.dossier.app

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.HistoricalAttributeKind
import io.dossier.app.domain.graph.EntityGraphBuilder
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.RiskLevel
import io.dossier.app.domain.model.RelationshipType
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityGraphBuilderTest {

    @Test
    fun entityEvidenceIdsMigrateTrimAndDeduplicateWithoutFoldingOpaqueCase() {
        val email = "alice@example.test"
        val legacyId = "ev:Email:$email:https://example.test/contact"
        val graph = EntityGraphBuilder.build(
            input = IdentityInput(fullName = "Authorized subject"),
            evidence = listOf(
                Evidence(
                    id = legacyId,
                    kind = EvidenceKind.Email,
                    value = email
                ),
                Evidence(
                    id = "  opaque-id  ",
                    kind = EvidenceKind.Email,
                    value = email
                ),
                Evidence(
                    id = "opaque-id",
                    kind = EvidenceKind.Email,
                    value = email
                ),
                Evidence(
                    id = "Opaque-Id",
                    kind = EvidenceKind.Email,
                    value = email
                )
            )
        )

        val entity = graph.entities.single { it.type == EntityType.Email && it.label == email }
        assertEquals(
            listOf(EvidenceIdPolicy.migrate(legacyId), "opaque-id", "Opaque-Id"),
            entity.evidenceIds
        )
    }

    @Test
    fun entityEvidenceIdsAreBoundedToGraphEvidenceLimit() {
        val email = "bounded@example.test"
        val graph = EntityGraphBuilder.build(
            input = IdentityInput(fullName = "Authorized subject"),
            evidence = (0 until 260).map { index ->
                Evidence(
                    id = "evidence-$index",
                    kind = EvidenceKind.Email,
                    value = email
                )
            }
        )

        val entity = graph.entities.single { it.type == EntityType.Email && it.label == email }
        assertEquals(256, entity.evidenceIds.size)
        assertEquals("evidence-0", entity.evidenceIds.first())
        assertEquals("evidence-255", entity.evidenceIds.last())
    }

    @Test
    fun buildsSubjectAndSeedEntities() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            emails = listOf("jane@example.com"),
            phones = listOf("+1-555-0100"),
            usernames = listOf("janedoe"),
            organizations = listOf("Acme Labs"),
            locations = listOf("Berlin")
        )

        val graph = EntityGraphBuilder.build(input)

        assertTrue(graph.entities.any { it.type == EntityType.Person && it.label == "Jane Doe" })
        assertTrue(graph.entities.any { it.type == EntityType.Email && it.label == "jane@example.com" })
        assertTrue(graph.entities.any { it.type == EntityType.Phone && it.label == "+1-555-0100" })
        assertTrue(graph.entities.any { it.type == EntityType.Username && it.label == "janedoe" })
        assertTrue(graph.entities.any { it.type == EntityType.Organization && it.label == "Acme Labs" })
        assertTrue(graph.entities.any { it.type == EntityType.Location && it.label == "Berlin" })

        val personId = graph.entities.first { it.type == EntityType.Person }.id
        assertTrue(graph.edges.any { it.fromId == personId && it.relation == "has_email" })
        assertTrue(graph.edges.any { it.fromId == personId && it.relation == "uses_username" })
    }

    @Test
    fun linksProfilesFindingsFaceAndBreaches() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            emails = listOf("jane@example.com"),
            usernames = listOf("janedoe")
        )
        val profile = ProfileScanResult(
            candidate = UsernameCandidate(
                username = "janedoe",
                platform = Platform.GitHub,
                url = "https://github.com/janedoe",
                matchType = UsernameMatchType.Exact,
                confidence = 0.92f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Jane Doe",
            bio = "builder",
            links = emptyList(),
            extractedText = "Jane Doe jane@example.com",
            findings = listOf(
                Finding(
                    type = FindingType.Email,
                    value = "jane@example.com",
                    sourceUrl = "https://github.com/janedoe",
                    evidenceSnippet = "contact",
                    confidence = 0.9f,
                    risk = RiskLevel.High,
                    remediation = "remove"
                )
            ),
            confidenceSignals = listOf("ok"),
            verified = true,
            verificationStatus = "✓ Verified"
        )
        val face = FaceConsistencyMatch(
            profileUrl = "https://github.com/janedoe",
            similarityScore = 0.88f,
            warning = "High visual similarity — review"
        )
        val breaches = listOf(
            BreachDigest(
                email = "jane@example.com",
                breachCount = 2,
                sources = listOf("ExampleBreach"),
                note = null
            )
        )
        val findings = listOf(
            Finding(
                type = FindingType.ImageConsistency,
                value = "Face similarity 88%",
                sourceUrl = "https://github.com/janedoe",
                evidenceSnippet = face.warning,
                confidence = 0.88f,
                risk = RiskLevel.High,
                remediation = "check"
            )
        )

        val graph = EntityGraphBuilder.build(
            input = input,
            profileResults = listOf(profile),
            findings = findings,
            faceMatches = listOf(face),
            breachDigests = breaches
        )

        assertTrue(graph.entities.any { it.type == EntityType.Profile && it.sourceUrls.contains("https://github.com/janedoe") })
        assertTrue(graph.entities.any { it.type == EntityType.Image })
        assertTrue(graph.entities.any { it.type == EntityType.Breach && it.label.contains("2 breach") })
        assertTrue(graph.edges.any { it.relation == "has_profile" })
        assertTrue(graph.edges.any { it.relation == "face_similar_to" })
        assertTrue(graph.edges.any { it.relation == "exposed_in" })
        assertTrue(graph.edges.any { it.relation == "mentions" })

        // Subject should be unique
        assertEquals(1, graph.entities.count { it.type == EntityType.Person })
    }

    @Test
    fun resolverProvenancePersistsSupportAndContradictionIdsOnProfileEdge() {
        val url = "https://github.com/shared_handle"
        val input = IdentityInput(
            fullName = "Alice Example",
            primaryUsername = "shared_handle"
        )
        val profile = ProfileScanResult(
            candidate = UsernameCandidate(
                username = "shared_handle",
                platform = Platform.GitHub,
                url = url,
                matchType = UsernameMatchType.Exact,
                confidence = 0.4f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "Robert Different",
            bio = null,
            links = emptyList(),
            extractedText = "Robert Different",
            findings = emptyList(),
            confidenceSignals = emptyList(),
            verified = false,
            verificationStatus = "review"
        )
        val profileEvidence = Evidence(
            id = "profile:$url",
            kind = EvidenceKind.Profile,
            value = url,
            sourceUrl = url,
            state = EvidenceState.Observed
        )

        val graph = EntityGraphBuilder.build(
            input = input,
            profileResults = listOf(profile),
            evidence = listOf(profileEvidence)
        )
        val subjectId = graph.entities.single { it.type == EntityType.Person }.id
        val profileId = graph.entities.single { it.type == EntityType.Profile && it.sourceUrls.contains(url) }.id
        val edge = graph.edges.single {
            it.fromId == subjectId && it.toId == profileId && it.relation == "candidate_profile"
        }

        assertEquals(listOf(profileEvidence.id), edge.evidenceIds)
        assertEquals(listOf(profileEvidence.id), edge.contradictingEvidenceIds)
    }

    @Test
    fun softProfileUsesPossibleRelation() {
        val input = IdentityInput(fullName = "Jane Doe", usernames = listOf("janedoe"))
        val soft = ProfileScanResult(
            candidate = UsernameCandidate(
                username = "janedoe",
                platform = Platform.Reddit,
                url = "https://www.reddit.com/user/janedoe",
                matchType = UsernameMatchType.Exact,
                confidence = 0.2f
            ),
            exists = true,
            httpStatus = 200,
            displayName = "u/janedoe",
            bio = null,
            links = emptyList(),
            extractedText = "some page",
            findings = emptyList(),
            confidenceSignals = emptyList(),
            verified = false,
            verificationStatus = "Exists but not attributed"
        )

        val graph = EntityGraphBuilder.build(input, profileResults = listOf(soft))
        assertTrue(graph.edges.any { it.relation == "possible_profile" })
    }

    @Test
    fun m6FusesEvidenceAndRelationshipsDirectly() {
        val input = IdentityInput(fullName = "Jane Doe", usernames = listOf("janedoe"))

        val evidence = listOf(
            Evidence(
                id = "ev1",
                kind = EvidenceKind.Email,
                value = "jane@example.com",
                sourceUrl = "https://github.com/janedoe",
                snippet = "contact email",
                confidence = 0.9f,
                risk = RiskLevel.High
            ),
            Evidence(
                id = "ev2",
                kind = EvidenceKind.Organization,
                value = "Acme Labs",
                confidence = 0.7f
            )
        )
        val relationships = listOf(
            EvidenceRelationship(
                fromValue = "janedoe",
                toValue = "https://github.com/janedoe",
                relation = "username_on_profile",
                evidence = "github"
            )
        )

        val graph = EntityGraphBuilder.build(
            input = input,
            evidence = evidence,
            relationships = relationships
        )

        // Evidence maps to the same entity types as findings (no adapter needed).
        assertTrue(graph.entities.any { it.type == EntityType.Email && it.label == "jane@example.com" })
        assertTrue(graph.entities.any { it.type == EntityType.Organization && it.label == "Acme Labs" })
        val personId = graph.entities.first { it.type == EntityType.Person }.id
        assertTrue(graph.edges.any { it.fromId == personId && it.relation == "has_email" })
        assertTrue(graph.edges.any { it.relation == "affiliated_with" })

        // Scanner-asserted relationship seeds a direct edge.
        assertTrue(graph.edges.any { it.relation == "username_on_profile" })
    }

    @Test
    fun duplicateRelationshipKeysIgnoreRelationCaseAndWhitespace() {
        val graph = EntityGraphBuilder.build(
            input = IdentityInput(fullName = "Jane Doe"),
            relationships = listOf(
                EvidenceRelationship(
                    fromValue = "alice",
                    toValue = "profile",
                    relation = "HAS_EMAIL",
                    evidence = "first",
                    evidenceIds = listOf("ev2:first")
                ),
                EvidenceRelationship(
                    fromValue = "alice",
                    toValue = "profile",
                    relation = " has_email ",
                    evidence = "second",
                    evidenceIds = listOf("ev2:second")
                )
            )
        )

        val edge = graph.edges.single { it.fromId == "value:alice" }
        assertEquals("HAS_EMAIL", edge.relation)
        assertEquals(RelationshipType.HAS_EMAIL, edge.relationType)
        assertEquals(listOf("ev2:first", "ev2:second"), edge.evidenceIds)
        assertEquals("first", edge.evidence)
    }

    @Test
    fun ambiguousLegacyEndpointProvenanceRemainsIdless() {
        val sharedSource = "https://example.test/activity/1"
        val evidence = listOf(
            Evidence(
                id = "activity-1",
                kind = EvidenceKind.PublicSearchEvidence,
                value = sharedSource,
                sourceUrl = sharedSource
            ),
            Evidence(
                id = "activity-2",
                kind = EvidenceKind.Profile,
                value = "Archived profile attribute",
                sourceUrl = sharedSource
            )
        )

        val graph = EntityGraphBuilder.build(
            input = IdentityInput(fullName = "Authorized subject"),
            evidence = evidence,
            relationships = listOf(
                EvidenceRelationship(
                    fromValue = "alice",
                    toValue = sharedSource,
                    relation = "MENTIONS",
                    evidence = "legacy assertion without a persisted ID"
                )
            )
        )

        val edge = graph.edges.single {
            it.fromId == "value:alice" && it.relation == "MENTIONS"
        }
        assertTrue(edge.evidenceIds.isEmpty())
        assertTrue(edge.contradictingEvidenceIds.isEmpty())
    }

    @Test
    fun legacyRelationshipReceivesOnlyExactExistingEvidenceProvenance() {
        val evidence = Evidence(
            id = "activity-1",
            kind = EvidenceKind.PublicSearchEvidence,
            value = "https://example.test/activity/1",
            sourceUrl = "https://example.test/activity/1",
            state = EvidenceState.Observed
        )
        val secondEvidence = evidence.copy(
            id = "activity-2",
            value = "https://example.test/activity/2",
            sourceUrl = "https://example.test/activity/2"
        )
        val relationship = EvidenceRelationship(
            fromValue = "alice",
            toValue = "bob",
            relation = "MENTIONS",
            evidence = evidence.sourceUrl
        )
        val duplicateRelationship = relationship.copy(evidence = secondEvidence.sourceUrl)

        val graph = EntityGraphBuilder.build(
            input = IdentityInput(fullName = "Authorized subject"),
            evidence = listOf(evidence, secondEvidence),
            relationships = listOf(relationship, duplicateRelationship)
        )

        val edge = graph.edges.single { it.relation == "MENTIONS" }
        assertEquals(listOf("activity-1", "activity-2"), edge.evidenceIds)
        assertTrue(graph.edgesWithEvidence("activity-1").contains(edge))
        // Endpoint names alone must not attach unrelated records.
        assertTrue(graph.edgesWithEvidence("unrelated").isEmpty())
    }

    @Test
    fun evidenceRelationshipReusesUniqueCanonicalEvidenceEntities() {
        val profileUrl = "https://example.test/alice"
        val emailValue = "alice@example.test"
        val evidence = listOf(
            Evidence(
                id = "profile-observation",
                kind = EvidenceKind.Profile,
                value = profileUrl,
                sourceUrl = profileUrl,
                state = EvidenceState.Verified
            ),
            Evidence(
                id = "email-observation",
                kind = EvidenceKind.Email,
                value = emailValue,
                sourceUrl = profileUrl,
                state = EvidenceState.Verified
            )
        )

        val graph = EntityGraphBuilder.build(
            input = IdentityInput(fullName = "Authorized subject"),
            evidence = evidence,
            relationships = listOf(
                EvidenceRelationship(
                    fromValue = emailValue,
                    toValue = profileUrl,
                    relation = "found_on",
                    evidenceIds = listOf("email-observation")
                )
            )
        )

        val emailId = graph.entities.single { it.type == EntityType.Email && it.label == emailValue }.id
        val profileId = graph.entities.single { it.type == EntityType.Profile && it.label == profileUrl }.id
        val edge = graph.edges.single { it.relation == "found_on" }
        assertEquals(emailId, edge.fromId)
        assertEquals(profileId, edge.toId)
        assertTrue(graph.entities.none { it.id == "value:$emailValue" })
        assertTrue(graph.entities.none { it.id == "value:${profileUrl.lowercase()}" })
        assertTrue(edge.evidenceIds.contains("email-observation"))
    }

    @Test
    fun historicalAttributesAttachToArchiveSourceWithoutCurrentOwnershipEdges() {
        val snapshotUrl = "https://web.archive.org/web/20240102030405id_/https://example.com/profile"
        val archiveEvidence = Evidence(
            id = "archive",
            kind = EvidenceKind.PublicSearchEvidence,
            value = snapshotUrl,
            sourceUrl = snapshotUrl,
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.ArchiveSnapshot,
            observedAtEpochMillis = 1_000L,
            historical = true
        )
        val displayName = Evidence(
            id = "archive-name",
            kind = EvidenceKind.Profile,
            attributeKind = HistoricalAttributeKind.DisplayName,
            value = "Archived Alice",
            sourceUrl = snapshotUrl,
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.ArchiveSnapshot,
            observedAtEpochMillis = 1_000L,
            historical = true
        )
        val username = Evidence(
            id = "archive-username",
            kind = EvidenceKind.Username,
            attributeKind = HistoricalAttributeKind.Username,
            value = "alice-archive",
            sourceUrl = snapshotUrl,
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.ArchiveSnapshot,
            observedAtEpochMillis = 1_000L,
            historical = true
        )

        val graph = EntityGraphBuilder.build(
            input = IdentityInput(fullName = "Authorized subject"),
            evidence = listOf(archiveEvidence, displayName, username)
        )

        assertTrue(graph.entities.any { it.type == EntityType.Website && it.label == snapshotUrl && it.historical })
        assertTrue(graph.entities.any { it.type == EntityType.Username && it.label == "alice-archive" && it.historical })
        assertTrue(graph.entities.none { it.type == EntityType.Profile && it.label == "Archived Alice" })
        assertTrue(
            graph.edges.any {
                it.relation == "archived_as" &&
                    it.historical &&
                    "archive-name" in it.evidenceIds
            }
        )
        assertTrue(
            graph.edges.any {
                it.relation == "claims_identity" &&
                    it.historical &&
                    "archive-username" in it.evidenceIds
            }
        )
        val subjectId = graph.entities.first { it.type == EntityType.Person }.id
        val usernameId = graph.entities.first { it.type == EntityType.Username && it.label == "alice-archive" }.id
        assertTrue(graph.edges.none { it.fromId == subjectId && it.toId == usernameId })
    }
}
