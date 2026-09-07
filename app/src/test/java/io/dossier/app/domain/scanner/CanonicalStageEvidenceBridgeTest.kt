package io.dossier.app.domain.scanner

import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.ExposureFactKind
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.evidence.toExposureLedger
import io.dossier.app.domain.graph.EntityGraphBuilder
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.FaceComparisonBackend
import io.dossier.app.domain.model.FaceComparisonCalibrationState
import io.dossier.app.domain.model.FaceComparisonProvenance
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalStageEvidenceBridgeTest {
    @Test
    fun faceMatchProjectsSupportingCandidateWithStructuredProvenance() {
        val profileUrl = "https://example.test/profile"
        val path = listOf("seed:photo", "profile:$profileUrl")
        val provenance = FaceComparisonProvenance(
            backend = FaceComparisonBackend.YuNetSFace,
            calibration = FaceComparisonCalibrationState.Measured,
            modelSource = "synthetic test model",
            pipelineVersion = "test-pipeline"
        )

        val evidence = listOf(
            FaceConsistencyMatch(
                profileUrl = profileUrl,
                similarityScore = 0.88f,
                warning = "Supporting visual similarity only",
                provenance = provenance
            )
        ).toFaceEvidenceCollection(
            retrievedAtEpochMillis = 123_000L,
            discoveryPath = path
        ).evidence.single()

        assertEquals(EvidenceKind.ImageConsistency, evidence.kind)
        assertEquals(profileUrl, evidence.sourceUrl)
        assertEquals(EvidenceState.Candidate, evidence.state)
        assertEquals(EvidenceReliability.LocalDerived, evidence.reliability)
        assertEquals(ExposureSourceClassification.LOCAL_IMPORT, evidence.sourceClassification)
        assertEquals(FindingAttribution.Candidate, evidence.attribution)
        assertEquals(provenance, evidence.faceComparisonProvenance)
        assertEquals(123_000L, evidence.retrievedAtEpochMillis)
        assertEquals(123_000L, evidence.observedAtEpochMillis)
        assertEquals(path, evidence.discoveryPath)
        assertTrue(evidence.snippet.orEmpty().contains("Supporting visual similarity"))
        assertTrue(evidence.signals.any { it.contains("YuNetSFace") })
    }

    @Test
    fun breachDigestProjectsIndexAndDerivedMembershipWithoutIdentityProof() {
        val email = "jane@example.test"
        val collection = listOf(
            BreachDigest(
                email = email,
                breachCount = 2,
                sources = listOf("Example Breach", "https://breach.example.test/report"),
                note = "Membership only; not identity proof",
                breachSources = listOf("Example Breach", "https://breach.example.test/report")
            )
        ).toBreachEvidenceCollection(retrievedAtEpochMillis = 456_000L)

        val membership = collection.evidence.filter { it.kind == EvidenceKind.BreachMembership }
        assertEquals(2, membership.size)
        assertEquals(
            setOf(
                ExposureSourceClassification.BREACH_INDEX,
                ExposureSourceClassification.BREACH_DERIVED
            ),
            membership.map { it.sourceClassification }.toSet()
        )
        assertTrue(membership.all { it.state == EvidenceState.Observed })
        assertTrue(membership.all { it.attribution == FindingAttribution.Unconfirmed })
        assertTrue(membership.all { it.reliability == EvidenceReliability.AuthoritativeApi })
        assertTrue(membership.all { it.retrievedAtEpochMillis == 456_000L })
        assertNotEquals(membership[0].id, membership[1].id)
        assertTrue(membership.any { it.sourceUrl == "https://breach.example.test/report" })

        val facts = collection.toExposureLedger().facts
        assertTrue(facts.all { it.kind == ExposureFactKind.BreachMembership })
        assertTrue(facts.all { it.verificationState != EvidenceState.Verified })
    }

    @Test
    fun ordinaryPublicEvidenceUrlNeverBecomesBreachMembership() {
        val publicUrl = "https://search.example.test/result"
        val collection = listOf(
            BreachDigest(
                email = "jane@example.test",
                breachCount = 1,
                sources = listOf(publicUrl),
                publicEvidenceUrls = listOf(publicUrl)
            )
        ).toBreachEvidenceCollection()

        assertTrue(collection.evidence.isNotEmpty())
        assertTrue(collection.evidence.all { it.sourceClassification != ExposureSourceClassification.BREACH_INDEX })
        assertTrue(collection.evidence.all { it.sourceUrl == null })
        assertTrue(collection.evidence.all { it.kind == EvidenceKind.BreachMembership })
    }

    @Test
    fun breachEvidenceCannotEnrichUserSuppliedEmailOwnershipEdge() {
        val email = "jane@example.test"
        val breachEvidence = Evidence(
            id = "breach-only",
            kind = EvidenceKind.BreachMembership,
            value = email,
            state = EvidenceState.Observed,
            sourceClassification = ExposureSourceClassification.BREACH_DERIVED
        )
        val input = IdentityInput(fullName = "Jane Example", emails = listOf(email))
        val graph = EntityGraphBuilder.build(input = input, evidence = listOf(breachEvidence))
        val subjectId = graph.entities.single { it.type == EntityType.Person }.id
        val hasEmail = graph.edges.single {
            it.fromId == subjectId && it.relation == "has_email"
        }

        assertTrue(hasEmail.evidenceIds.isEmpty())
        assertFalse(graph.edges.any {
            it.fromId == subjectId && it.relation == "has_breach_exposure"
        })
        assertTrue(graph.edges.any {
            it.relation == "exposed_in" && breachEvidence.id in it.evidenceIds
        })
    }

    @Test
    fun snapshotAndGraphRetainBridgeIdsOnFaceAndBreachRelationships() {
        val profileUrl = "https://example.test/profile"
        val input = IdentityInput(
            fullName = "Jane Example",
            emails = listOf("jane@example.test")
        )
        val face = FaceConsistencyMatch(profileUrl, 0.82f, "Review visual similarity")
        val breach = BreachDigest(
            email = "jane@example.test",
            breachCount = 1,
            sources = listOf("Example Breach")
        )

        val snapshot = ScanSession.buildEvidenceSnapshot(
            input = input,
            profileResults = emptyList(),
            pluginCollection = io.dossier.app.domain.evidence.EvidenceCollection(),
            findings = emptyList(),
            retrievedAtEpochMillis = 789_000L,
            faceMatches = listOf(face),
            breachDigests = listOf(breach)
        )
        val faceEvidence = snapshot.evidence.single { it.kind == EvidenceKind.ImageConsistency }
        val breachEvidence = snapshot.evidence.single { it.kind == EvidenceKind.BreachMembership }
        assertTrue(snapshot.toExposureLedger().facts.any { it.kind == ExposureFactKind.BreachMembership })

        // Restore/build from the canonical snapshot only. The structured stage
        // arguments are intentionally omitted so this exercises the persisted
        // evidence bridge used after process death and case restore.
        val graph = EntityGraphBuilder.build(
            input = input,
            evidence = snapshot.evidence,
            relationships = snapshot.relationships
        )

        assertTrue(graph.edges.any {
            it.relation == "face_similar_to" && faceEvidence.id in it.evidenceIds
        })
        assertTrue(graph.edges.any {
            it.relation == "image_of_profile" && faceEvidence.id in it.evidenceIds
        })
        assertTrue(graph.edges.any {
            it.relation == "exposed_in" && breachEvidence.id in it.evidenceIds
        })
        val subjectId = graph.entities.single { it.type == EntityType.Person }.id
        assertFalse(graph.edges.any {
            it.fromId == subjectId &&
                it.relation == "has_breach_exposure"
        })
        // The input email edge is user-supplied and may remain, but breach
        // membership evidence must never be attached to it as ownership proof.
        assertFalse(graph.edges.any {
            it.fromId == subjectId &&
                it.relation == "has_email" &&
                breachEvidence.id in it.evidenceIds
        })
        assertTrue(graph.entities.none { it.id.startsWith("value:image:face:") })
        assertTrue(graph.entities.none { it.id == "value:profile:${profileUrl.lowercase()}" })
        assertTrue(graph.entities.none { it.id == "value:email:${breach.email.lowercase()}" })
        assertTrue(graph.entities.none { it.id == "value:breach:${breach.email.lowercase()}" })
        assertFalse(
            graph.edges
                .filter { it.relation == "exposed_in" || it.relation == "has_breach_exposure" }
                .any { breachEvidence.id in it.contradictingEvidenceIds }
        )
    }
}
