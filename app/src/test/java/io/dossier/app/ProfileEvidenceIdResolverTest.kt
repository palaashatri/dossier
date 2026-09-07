package io.dossier.app

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.persistedEvidenceId
import io.dossier.app.domain.model.Platform
import io.dossier.app.domain.model.ProfileScanResult
import io.dossier.app.domain.model.UsernameCandidate
import io.dossier.app.domain.model.UsernameMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileEvidenceIdResolverTest {

    private val url = "https://profiles.example.test/alice"
    private val result = ProfileScanResult(
        candidate = UsernameCandidate(
            username = "alice",
            platform = Platform.Website,
            url = url,
            matchType = UsernameMatchType.Exact,
            confidence = 0.8f
        ),
        exists = true,
        httpStatus = 200,
        displayName = "Alice Example",
        bio = null,
        links = emptyList(),
        extractedText = "",
        findings = emptyList(),
        confidenceSignals = emptyList(),
        verified = true
    )

    @Test
    fun resolvesTheUniquePersistedProfileRecordInsteadOfDerivingAnId() {
        val evidence = Evidence(
            id = "legacy-profile-record-42",
            kind = EvidenceKind.Profile,
            value = url,
            sourceUrl = url
        )

        assertEquals("legacy-profile-record-42", result.persistedEvidenceId(listOf(evidence)))
    }

    @Test
    fun failsClosedWhenTheProfileRecordIsMissingOrAmbiguous() {
        assertNull(result.persistedEvidenceId(emptyList()))

        val first = Evidence(
            id = "profile-record-1",
            kind = EvidenceKind.Profile,
            value = url
        )
        val second = Evidence(
            id = "profile-record-2",
            kind = EvidenceKind.Profile,
            sourceUrl = url,
            value = "same source, different record"
        )

        assertNull(result.persistedEvidenceId(listOf(first, second)))
    }

    @Test
    fun ignoresNonProfileRecordsEvenWhenTheirUrlMatches() {
        val finding = Evidence(
            id = "search-record",
            kind = EvidenceKind.PublicSearchEvidence,
            value = url,
            sourceUrl = url
        )

        assertNull(result.persistedEvidenceId(listOf(finding)))
    }
}
