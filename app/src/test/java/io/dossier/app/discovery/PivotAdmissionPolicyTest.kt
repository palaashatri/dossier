package io.dossier.app.discovery

import io.dossier.app.domain.discovery.PivotAdmissionDecision
import io.dossier.app.domain.discovery.PivotAdmissionPolicy
import io.dossier.app.domain.discovery.PivotAdmissionRequest
import io.dossier.app.domain.discovery.PivotSignalType
import org.junit.Assert.assertTrue
import org.junit.Test

class PivotAdmissionPolicyTest {

    @Test
    fun explicitCrossLinkIsAdmittedWithinDepthBudget() {
        val decision = PivotAdmissionPolicy.decide(
            PivotAdmissionRequest(
                signalType = PivotSignalType.ExplicitProfileLink,
                normalizedValue = "rare_handle_42",
                confidence = 0.7f,
                depth = 1
            )
        )
        assertTrue(decision is PivotAdmissionDecision.Admit)
    }

    @Test
    fun depthBeyondTwoIsRejected() {
        val decision = PivotAdmissionPolicy.decide(
            PivotAdmissionRequest(
                signalType = PivotSignalType.ExplicitProfileLink,
                normalizedValue = "rare_handle_42",
                confidence = 0.9f,
                depth = 3
            )
        )
        assertTrue(decision is PivotAdmissionDecision.Reject)
    }

    @Test
    fun visitedSignalIsRejectedBeforeExpansion() {
        val decision = PivotAdmissionPolicy.decide(
            PivotAdmissionRequest(
                signalType = PivotSignalType.ExplicitProfileLink,
                normalizedValue = "rare_handle_42",
                confidence = 0.9f,
                depth = 1,
                alreadyVisited = true
            )
        )
        assertTrue(decision is PivotAdmissionDecision.Reject)
    }

    @Test
    fun commonUsernameNeedsIndependentCorroboration() {
        val weak = PivotAdmissionPolicy.decide(
            PivotAdmissionRequest(
                signalType = PivotSignalType.CommonUsername,
                normalizedValue = "support",
                confidence = 0.9f,
                depth = 1,
                corroboratingEvidenceCount = 1
            )
        )
        val corroborated = PivotAdmissionPolicy.decide(
            PivotAdmissionRequest(
                signalType = PivotSignalType.CommonUsername,
                normalizedValue = "support",
                confidence = 0.8f,
                depth = 1,
                corroboratingEvidenceCount = 2
            )
        )
        assertTrue(weak is PivotAdmissionDecision.Reject)
        assertTrue(corroborated is PivotAdmissionDecision.Admit)
    }

    @Test
    fun faceNameLocationAndOccupationAloneCannotRecursivelyExpand() {
        listOf(
            PivotSignalType.FaceSimilarityOnly,
            PivotSignalType.NameOnly,
            PivotSignalType.LocationOnly,
            PivotSignalType.OccupationOnly
        ).forEach { type ->
            val decision = PivotAdmissionPolicy.decide(
                PivotAdmissionRequest(
                    signalType = type,
                    normalizedValue = "some-signal",
                    confidence = 0.99f,
                    depth = 1
                )
            )
            assertTrue("$type must not recursively expand on its own", decision is PivotAdmissionDecision.Reject)
        }
    }
}
