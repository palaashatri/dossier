package io.dossier.app

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
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityGraphBuilderTest {

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
}
