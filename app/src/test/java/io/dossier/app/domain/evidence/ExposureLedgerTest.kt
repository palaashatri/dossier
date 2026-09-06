package io.dossier.app.domain.evidence

import io.dossier.app.domain.case.DossierCase
import io.dossier.app.domain.case.RemediationStatus
import io.dossier.app.domain.model.FindingAttribution
import io.dossier.app.domain.model.IdentityInput
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureLedgerTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun exactValueIsRetainedAndUnavailableMayBeNull() {
        val observed = Evidence(
            id = "email-1",
            kind = EvidenceKind.Email,
            value = " Jane@Example.TEST ",
            state = EvidenceState.Verified
        ).toExposureFact()
        assertEquals(" Jane@Example.TEST ", observed.exactValue)
        assertEquals("jane@example.test", observed.normalizedValue)
        assertEquals(EvidenceState.Verified, observed.verificationState)

        val unavailable = ExposureFact(
            exactValue = null,
            normalizedValue = "email-digest",
            kind = ExposureFactKind.Email,
            verificationState = EvidenceState.Unavailable
        )
        assertNull(unavailable.exactValue)
        assertEquals("email-digest", unavailable.normalizedValue)
        assertEquals(EvidenceState.Unavailable, unavailable.verificationState)

        val unavailableEvidence = Evidence(
            id = "unavailable-email",
            kind = EvidenceKind.Email,
            value = "",
            state = EvidenceState.Unavailable
        ).toExposureFact()
        assertNull(unavailableEvidence.exactValue)
        assertEquals(EvidenceState.Unavailable, unavailableEvidence.verificationState)

        val breachMembership = ExposureFact(
            exactValue = null,
            normalizedValue = "sample@example.test",
            kind = ExposureFactKind.BreachMembership,
            sourceClassification = ExposureSourceClassification.BREACH_DERIVED,
            verificationState = EvidenceState.Unavailable
        )
        assertNull(breachMembership.exactValue)
        assertEquals(ExposureSourceClassification.BREACH_DERIVED, breachMembership.sourceClassification)
    }

    @Test
    fun commonFactTypesNormalizeWithoutChangingExactSource() {
        assertEquals(
            "15551234567",
            ExposureLedgerPolicy.normalizeValue(
                ExposureFactKind.Phone,
                "+1 (555) 123-4567"
            )
        )
        assertEquals(
            "sample_user",
            ExposureLedgerPolicy.normalizeValue(ExposureFactKind.Username, " @Sample_User ")
        )
        assertEquals(
            "https://example.test/Profile",
            ExposureLedgerPolicy.normalizeValue(
                ExposureFactKind.Profile,
                "HTTPS://Example.TEST/Profile#history"
            )
        )
        assertEquals(
            "acme systems",
            ExposureLedgerPolicy.normalizeValue(ExposureFactKind.Organization, " Acme   Systems ")
        )
    }

    @Test
    fun archiveEvidenceRetainsDistinctLedgerKindAndArchiveSource() {
        val evidence = Evidence(
            id = "archive-1",
            kind = EvidenceKind.Archive,
            value = "HTTPS://Archive.Today/ABC#snapshot",
            reliability = EvidenceReliability.ArchiveSnapshot,
            historical = true
        )

        val fact = evidence.toExposureFact()
        assertEquals(ExposureFactKind.Archive, fact.kind)
        assertEquals("https://archive.today/ABC", fact.normalizedValue)
        assertEquals("HTTPS://Archive.Today/ABC#snapshot", fact.exactValue)
        assertEquals(ExposureSourceClassification.ARCHIVE, fact.sourceClassification)
        assertTrue(fact.historical)
    }

    @Test
    fun reliabilityMapsToExplicitSourceClassification() {
        val expected = mapOf(
            EvidenceReliability.AuthoritativeApi to ExposureSourceClassification.AUTHORIZED_API,
            EvidenceReliability.DirectPublicProfile to ExposureSourceClassification.PUBLIC_PROFILE,
            EvidenceReliability.DirectPersonalWebsite to ExposureSourceClassification.PUBLIC_WEB,
            EvidenceReliability.ArchiveSnapshot to ExposureSourceClassification.ARCHIVE,
            EvidenceReliability.SearchEngineCandidate to ExposureSourceClassification.PUBLIC_WEB,
            EvidenceReliability.ThirdPartyAggregation to ExposureSourceClassification.DATA_BROKER,
            EvidenceReliability.LocalDerived to ExposureSourceClassification.LOCAL_IMPORT,
            EvidenceReliability.UserSupplied to ExposureSourceClassification.USER_IMPORTED,
            EvidenceReliability.Unknown to ExposureSourceClassification.UNKNOWN_ORIGIN
        )
        expected.forEach { (reliability, source) ->
            val fact = Evidence(
                id = "source-${reliability.name}",
                kind = EvidenceKind.Email,
                value = "sample@example.test",
                reliability = reliability
            ).toExposureFact()
            assertEquals(source, fact.sourceClassification)
        }
    }

    @Test
    fun collectionDeduplicatesFactsAndBoundsEvidence() {
        val sameFact = listOf(
            Evidence(
                id = "e1",
                kind = EvidenceKind.Email,
                value = "A@EXAMPLE.TEST",
                sourceUrl = "https://example.test/contact",
                observedAtEpochMillis = 20L
            ),
            Evidence(
                id = "e2",
                kind = EvidenceKind.Email,
                value = "a@example.test",
                sourceUrl = "https://example.test/contact",
                observedAtEpochMillis = 10L
            ),
            Evidence(
                id = "e3",
                kind = EvidenceKind.Email,
                value = "a@example.test",
                sourceUrl = "https://example.test/contact",
                state = EvidenceState.Rejected
            ),
            Evidence(
                id = "e1",
                kind = EvidenceKind.Email,
                value = "different@example.test",
                sourceUrl = "https://example.test/other"
            )
        )
        val ledger = sameFact.toExposureLedger()
        assertEquals(1, ledger.facts.size)
        assertEquals("A@EXAMPLE.TEST", ledger.facts.single().exactValue)
        assertEquals(listOf("e1", "e2", "e3"), ledger.facts.single().evidenceIds)
        assertEquals(10L, ledger.facts.single().firstObservedAtEpochMillis)
        assertEquals(20L, ledger.facts.single().lastObservedAtEpochMillis)
        assertEquals(EvidenceState.Conflicting, ledger.facts.single().verificationState)

        val bounded = (0..ExposureLedger.MAX_FACTS).map { index ->
            Evidence(
                id = "id-$index",
                kind = EvidenceKind.Email,
                value = "user$index@example.test",
                sourceUrl = "https://example.test/$index"
            )
        }.toExposureLedger()
        assertEquals(ExposureLedger.MAX_FACTS, bounded.facts.size)
    }

    @Test
    fun ledgerAndCaseRoundTripThroughJsonWithLegacyDefaults() {
        val ledger = ExposureLedger(
            facts = listOf(
                ExposureFact(
                    exactValue = "sample@example.test",
                    normalizedValue = "sample@example.test",
                    kind = ExposureFactKind.Email,
                    subjectId = "subject-1",
                    evidenceIds = listOf("ev-1"),
                    sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
                    sourceUrl = "https://example.test/profile",
                    providerId = "example",
                    firstObservedAtEpochMillis = 10L,
                    lastObservedAtEpochMillis = 20L,
                    verificationState = EvidenceState.Verified,
                    confidence = 0.9f,
                    historical = true,
                    discoveryPath = listOf("seed:name", "profile:url"),
                    remediationStatus = RemediationStatus.InProgress
                )
            )
        )
        assertEquals(ledger, json.decodeFromString<ExposureLedger>(json.encodeToString(ledger)))

        val dossierCase = DossierCase(
            createdAt = "2026-09-04T00:00:00Z",
            subjectName = "Synthetic Subject",
            input = IdentityInput(fullName = "Synthetic Subject"),
            exposureLedger = ledger
        )
        val decodedCase = json.decodeFromString<DossierCase>(json.encodeToString(dossierCase))
        assertEquals(ledger, decodedCase.exposureLedger)

        val legacy = """
            {"schemaVersion":8,"caseId":"legacy","createdAt":"2026-09-04",
             "subjectName":"Synthetic Subject","input":{"fullName":"Synthetic Subject"}}
        """.trimIndent()
        val legacyCase = json.decodeFromString<DossierCase>(legacy)
        assertTrue(legacyCase.exposureLedger.facts.isEmpty())
    }

    @Test
    fun discoveryPathNormalizesAndBounds() {
        val deduplicatedPath = listOf("step-1", " step-1 ", "step-2", "step-2")
        val factDeduplicated = ExposureFact(
            normalizedValue = "sample",
            discoveryPath = deduplicatedPath
        )
        val normalizedDeduplicated = ExposureLedgerPolicy.normalizeFact(factDeduplicated)
        assertEquals(listOf("step-1", "step-2"), normalizedDeduplicated.discoveryPath)
    }

    @Test
    fun ledgerMergeUnionsDistinctDiscoveryPathsInStableOrder() {
        val first = ExposureFact(
            exactValue = "jane@example.test",
            normalizedValue = "jane@example.test",
            kind = ExposureFactKind.Email,
            sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
            sourceUrl = "https://example.test/profile",
            evidenceIds = listOf("ev-first"),
            discoveryPath = listOf("seed:name", "profile:url")
        )
        val second = first.copy(
            evidenceIds = listOf("ev-second"),
            discoveryPath = listOf("profile:url", "document:url", "contact:email")
        )

        val merged = ExposureLedgerPolicy.normalize(listOf(first, second)).single()

        assertEquals(
            listOf("seed:name", "profile:url", "document:url", "contact:email"),
            merged.discoveryPath
        )
    }

    @Test
    fun ledgerMergeBoundsDiscoveryPathUnionAndTruncatesNewEntries() {
        val firstPath = (1..63).map { "hop-$it" }
        val first = ExposureFact(
            exactValue = "jane@example.test",
            normalizedValue = "jane@example.test",
            kind = ExposureFactKind.Email,
            sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
            sourceUrl = "https://example.test/profile",
            evidenceIds = listOf("ev-first"),
            discoveryPath = firstPath
        )
        val second = first.copy(
            evidenceIds = listOf("ev-second"),
            discoveryPath = listOf("hop-64", "hop-65", "hop-66", "hop-67")
        )

        val merged = ExposureLedgerPolicy.normalize(listOf(first, second)).single()

        assertEquals(ExposureFact.MAX_DISCOVERY_PATH_STEPS, merged.discoveryPath.size)
        assertEquals(firstPath + "hop-64", merged.discoveryPath)
        assertTrue("hop-65" !in merged.discoveryPath)
        assertTrue("hop-66" !in merged.discoveryPath)
        assertTrue("hop-67" !in merged.discoveryPath)
    }

    @Test
    fun discoveryPathPropagatesFromEvidenceToExposureFact() {
        val path = listOf("seed:email", "profile:url")
        val evidence = Evidence(
            id = "ev-1",
            kind = EvidenceKind.Email,
            value = "test@example.com",
            discoveryPath = path
        )
        val fact = evidence.toExposureFact()
        assertEquals(path, fact.discoveryPath)
    }

    @Test
    fun explicitAttributionPropagatesToLedgerAndSurvivesJson() {
        val evidence = Evidence(
            id = "ev-attribution",
            kind = EvidenceKind.Email,
            value = "jane@example.test",
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.DirectPublicProfile,
            attribution = FindingAttribution.ExactSelfSupplied
        )

        val ledger = evidence.toExposureLedger()
        assertEquals(FindingAttribution.ExactSelfSupplied, ledger.facts.single().attribution)
        assertEquals(ledger, json.decodeFromString<ExposureLedger>(json.encodeToString(ledger)))
    }

    @Test
    fun ledgerMergeKeepsExplicitAttributionWhenOtherObservationIsUnconfirmed() {
        val exact = Evidence(
            id = "ev-exact",
            kind = EvidenceKind.Email,
            value = "jane@example.test",
            sourceUrl = "https://example.test/profile",
            attribution = FindingAttribution.ExactSelfSupplied,
            state = EvidenceState.Verified
        )
        val observed = exact.copy(
            id = "ev-observed",
            attribution = FindingAttribution.Unconfirmed,
            state = EvidenceState.Observed
        )

        val fact = listOf(exact, observed).toExposureLedger().facts.single()
        assertEquals(FindingAttribution.ExactSelfSupplied, fact.attribution)
    }

    @Test
    fun evidenceRequiresBoundedDiscoveryPath() {
        val path = (1..65).map { "step-$it" }
        val exception = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Evidence(
                id = "ev-1",
                kind = EvidenceKind.Email,
                value = "test@example.com",
                discoveryPath = path
            )
        }
        assertTrue(exception.message!!.contains("64"))
    }
}
