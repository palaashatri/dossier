package io.dossier.app.domain.discovery

import io.dossier.app.data.web.TypedSeedPublicFetchExecutor
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.model.FindingAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TypedSeedAdmissionModelTest {

    @Test
    fun offersAreNormalizedAndDeduplicated() {
        val model = TypedSeedAdmissionModel()

        assertTrue(model.offer(TypedSeedKind.Email, " USER@EXAMPLE.COM ", 1))
        // Deduplication
        assertFalse(model.offer(TypedSeedKind.Email, "user@example.com", 1))

        assertEquals(1, model.pendingCount)
        assertEquals(1, model.admittedCount)

        val popped = model.pop()
        assertEquals("user@example.com", popped?.value)
        assertEquals(TypedSeedKind.Email, popped?.kind)
        assertEquals(1, popped?.depth)
    }

    @Test
    fun appliesPerKindBudgets() {
        val config = TypedSeedAdmissionConfig(perKindBudgets = mapOf(TypedSeedKind.Url to 2))
        val model = TypedSeedAdmissionModel(config)

        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/1", 1))
        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/2", 1))
        // Budget exhausted
        assertFalse(model.offer(TypedSeedKind.Url, "https://example.com/3", 1))

        assertEquals(2, model.pendingCount)
    }

    @Test
    fun appliesDepthBound() {
        val model = TypedSeedAdmissionModel(TypedSeedAdmissionConfig(maxDepth = 2))

        assertTrue(model.offer(TypedSeedKind.Phone, "12345678", 2))
        // Exceeds depth
        assertFalse(model.offer(TypedSeedKind.Phone, "87654321", 3))
    }

    @Test
    fun limitsTotalBudget() {
        val config = TypedSeedAdmissionConfig(maxTotalSeeds = 2, perKindBudgets = mapOf(TypedSeedKind.Url to 5))
        val model = TypedSeedAdmissionModel(config)

        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/1", 1))
        assertTrue(model.offer(TypedSeedKind.Url, "https://example.com/2", 1))
        // Total exhausted
        assertFalse(model.offer(TypedSeedKind.Url, "https://example.com/3", 1))
    }

    @Test
    fun rejectsUnverifiedCandidateAndImportPivotsButKeepsUserSeeds() {
        val kinds = listOf(
            TypedSeedKind.Email,
            TypedSeedKind.Phone,
            TypedSeedKind.Url,
            TypedSeedKind.Domain,
            TypedSeedKind.Document,
            TypedSeedKind.Archive,
            TypedSeedKind.Photo,
            TypedSeedKind.Image,
            TypedSeedKind.Username,
            TypedSeedKind.Location
        )
        kinds.forEach { kind ->
            val model = TypedSeedAdmissionModel()
            val value = when (kind) {
                TypedSeedKind.Email -> "pivot@example.test"
                TypedSeedKind.Phone -> "15550100100"
                TypedSeedKind.Url,
                TypedSeedKind.Document,
                TypedSeedKind.Archive,
                TypedSeedKind.Image,
                TypedSeedKind.Photo -> "https://example.test/value"
                TypedSeedKind.Domain -> "example.test"
                TypedSeedKind.Username -> "pivot_user"
                TypedSeedKind.Name -> "Pivot User"
                TypedSeedKind.Location -> "Example City"
            }
            assertFalse(
                "candidate $kind must not become a recursive pivot",
                model.offer(
                    kind = kind,
                    rawValue = value,
                    depth = 1,
                    origin = TypedSeedOrigin.Candidate,
                    evidenceState = EvidenceState.Candidate,
                    sourceClassification = ExposureSourceClassification.PUBLIC_WEB
                )
            )
            assertFalse(
                "import $kind must be explicitly verified",
                model.offer(
                    kind = kind,
                    rawValue = value,
                    depth = 1,
                    origin = TypedSeedOrigin.Import,
                    evidenceState = EvidenceState.Observed,
                    sourceClassification = ExposureSourceClassification.LOCAL_IMPORT
                )
            )
            assertTrue(
                "user $kind remains an authorized initial seed",
                model.offer(
                    kind = kind,
                    rawValue = value,
                    depth = 0,
                    origin = TypedSeedOrigin.UserInput,
                    evidenceState = EvidenceState.Observed,
                    sourceClassification = ExposureSourceClassification.USER_IMPORTED
                )
            )
        }
    }

    @Test
    fun rejectsVerifiedImportsAndBreachEvidenceByDefault() {
        val imported = TypedSeedAdmissionModel()
        assertFalse(
            imported.offer(
                kind = TypedSeedKind.Email,
                rawValue = "imported@example.test",
                depth = 1,
                origin = TypedSeedOrigin.Import,
                evidenceState = EvidenceState.Verified,
                sourceClassification = ExposureSourceClassification.LOCAL_IMPORT
            )
        )
        assertFalse(
            TypedSeedAdmissionModel().offer(
                kind = TypedSeedKind.Email,
                rawValue = "local@example.test",
                depth = 1,
                origin = TypedSeedOrigin.LocalAnalysis,
                evidenceState = EvidenceState.Verified,
                sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE
            )
        )

        val explicitlyAuthorized = TypedSeedAdmissionModel(
            TypedSeedAdmissionConfig(allowAuthorizedImports = true)
        )
        assertTrue(
            explicitlyAuthorized.offer(
                kind = TypedSeedKind.Email,
                rawValue = "imported@example.test",
                depth = 1,
                origin = TypedSeedOrigin.Import,
                evidenceState = EvidenceState.Verified,
                sourceClassification = ExposureSourceClassification.LOCAL_IMPORT
            )
        )

        listOf(
            ExposureSourceClassification.BREACH_INDEX,
            ExposureSourceClassification.BREACH_DERIVED
        ).forEach { source ->
            assertFalse(
                "breach source $source must remain evidence-only",
                TypedSeedAdmissionModel().offer(
                    kind = TypedSeedKind.Email,
                    rawValue = "exposed@example.test",
                    depth = 1,
                    origin = TypedSeedOrigin.Evidence,
                    evidenceState = EvidenceState.Verified,
                    sourceClassification = source
                )
            )
        }
    }

    @Test
    fun publicSeedConstructorRejectsInconsistentVerificationMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeed(
                kind = TypedSeedKind.Email,
                value = "person@example.test",
                isVerified = true,
                evidenceState = EvidenceState.Observed
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeed(
                kind = TypedSeedKind.Email,
                value = "person@example.test",
                evidenceState = EvidenceState.Verified,
                origin = TypedSeedOrigin.Unknown,
                isVerified = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeed(
                kind = TypedSeedKind.Email,
                value = "person@example.test",
                evidenceState = EvidenceState.Verified,
                origin = TypedSeedOrigin.Candidate,
                isVerified = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeed(
                kind = TypedSeedKind.Email,
                value = "person@example.test",
                evidenceState = EvidenceState.Verified,
                sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
                origin = TypedSeedOrigin.Import,
                isVerified = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeed(
                kind = TypedSeedKind.Email,
                value = "person@example.test",
                evidenceState = EvidenceState.Verified,
                sourceClassification = ExposureSourceClassification.PUBLIC_PROFILE,
                origin = TypedSeedOrigin.LocalAnalysis,
                isVerified = true
            )
        }
        listOf(
            ExposureSourceClassification.BREACH_INDEX,
            ExposureSourceClassification.BREACH_DERIVED
        ).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                TypedSeed(
                    kind = TypedSeedKind.Email,
                    value = "person@example.test",
                    evidenceState = EvidenceState.Verified,
                    sourceClassification = source,
                    origin = TypedSeedOrigin.Evidence,
                    isVerified = true
                )
            }
        }
        listOf(TypedSeedOrigin.Import, TypedSeedOrigin.LocalAnalysis).forEach { origin ->
            assertThrows(IllegalArgumentException::class.java) {
                TypedSeed(
                    kind = TypedSeedKind.Email,
                    value = "person@example.test",
                    evidenceState = EvidenceState.Observed,
                    sourceClassification = ExposureSourceClassification.LOCAL_IMPORT,
                    origin = origin,
                    isVerified = false
                )
            }
        }
        // A verified, explicitly authorized local import is representable, but
        // admission still requires the caller's allowAuthorizedImports opt-in.
        TypedSeed(
            kind = TypedSeedKind.Email,
            value = "person@example.test",
            evidenceState = EvidenceState.Verified,
            sourceClassification = ExposureSourceClassification.LOCAL_IMPORT,
            origin = TypedSeedOrigin.Import,
            isVerified = true
        )
    }

    @Test
    fun normalizesEmailPhoneUrlsDomainsUsernamesAndMediaDeterministically() {
        val model = TypedSeedAdmissionModel()
        assertTrue(model.offer(TypedSeedKind.Email, " USER@EXAMPLE.TEST ", 0))
        assertTrue(model.offer(TypedSeedKind.Phone, "+1 (555) 010-0100", 0))
        assertTrue(model.offer(TypedSeedKind.Url, "HTTP://EXAMPLE.TEST/path#fragment", 0))
        assertTrue(model.offer(TypedSeedKind.Domain, "EXAMPLE.TEST.", 0))
        assertTrue(model.offer(TypedSeedKind.Username, "@Alice_User", 0))
        assertTrue(model.offer(TypedSeedKind.Photo, "content://media/item#preview", 0))
        assertTrue(model.offer(TypedSeedKind.Location, "  Example   City  ", 0))

        assertEquals("user@example.test", model.admittedSeeds[0].normalizedValue)
        assertEquals("15550100100", model.admittedSeeds[1].normalizedValue)
        assertEquals("http://example.test/path", model.admittedSeeds[2].normalizedValue)
        assertEquals("example.test", model.admittedSeeds[3].normalizedValue)
        assertEquals("alice_user", model.admittedSeeds[4].normalizedValue)
        assertEquals("content://media/item", model.admittedSeeds[5].normalizedValue)
        assertEquals("example city", model.admittedSeeds[6].normalizedValue)
        assertEquals(" USER@EXAMPLE.TEST ", model.admittedSeeds[0].exactValue)
    }

    @Test
    fun validatesDepthAndBudgetConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeedAdmissionConfig(maxDepth = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeedAdmissionConfig(maxTotalSeeds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedSeedAdmissionConfig(
                perKindBudgets = mapOf(TypedSeedKind.Email to TypedSeedAdmissionConfig.MAX_ALLOWED_KIND_BUDGET + 1)
            )
        }
        val model = TypedSeedAdmissionModel(TypedSeedAdmissionConfig(maxDepth = 1))
        assertFalse(model.offer(TypedSeedKind.Email, "a@example.test", -1))
        assertFalse(model.offer(TypedSeedKind.Email, "b@example.test", 2))
    }

    @Test
    fun adapterCarriesEvidenceSourceStateAndPathWhileSearchExecutionIsAvailable() {
        val record = Evidence(
            id = "profile-email",
            kind = EvidenceKind.Email,
            value = "person@example.test",
            sourceUrl = "https://example.test/profile",
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.DirectPublicProfile
        )
        val model = TypedSeedEvidenceAdapter.admit(
            evidence = listOf(record),
            input = IdentityInput(fullName = "Jane Example")
        )
        val email = model.admittedSeeds.single { it.kind == TypedSeedKind.Email }
        assertEquals("person@example.test", email.normalizedValue)
        assertEquals("person@example.test", email.exactValue)
        assertEquals(listOf(record.id), email.evidenceIds)
        assertEquals(record.sourceUrl, email.sourceUrl)
        assertEquals(EvidenceState.Verified, email.evidenceState)
        assertEquals(ExposureSourceClassification.PUBLIC_PROFILE, email.sourceClassification)
        assertEquals(TypedSeedExecutionAvailability.Available, model.availabilityFor(TypedSeedKind.Email))
        assertTrue(model.isExecutionAvailable)
    }

    @Test
    fun adapterMapsEvidencePathLengthToRecursiveDepthWithoutInflatingHops() {
        val chain = (0..4).map { depth ->
            Evidence(
                id = "chain-$depth",
                kind = EvidenceKind.Url,
                value = "https://example.test/chain-$depth",
                sourceUrl = "https://example.test/source-$depth",
                state = EvidenceState.Observed,
                reliability = EvidenceReliability.DirectPublicProfile,
                discoveryPath = (0 until depth).map { "https://example.test/hop-$it" }
            )
        }

        val model = TypedSeedEvidenceAdapter.admit(
            evidence = chain,
            config = TypedSeedAdmissionConfig(maxDepth = 4)
        )

        chain.forEachIndexed { depth, record ->
            assertEquals(
                "A -> B -> C -> D -> E path depth",
                depth,
                model.admittedSeeds.single { it.exactValue == record.value }.depth
            )
        }
    }

    @Test
    fun adapterAcceptsCanonicalEvidenceCollectionWithoutCreatingAnotherStore() {
        val record = Evidence(
            id = "profile-email",
            kind = EvidenceKind.Email,
            value = "person@example.test",
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.DirectPublicProfile
        )
        val collection = io.dossier.app.domain.evidence.EvidenceCollection(evidence = listOf(record))
        val model = TypedSeedEvidenceAdapter.fromCollection(collection)
        assertEquals(listOf(record.id), model.admittedSeeds.single().evidenceIds)
        assertEquals(collection.evidence, listOf(record))
    }

    @Test
    fun adapterAdmitsVerifiedWaybackSearchEvidenceAsArchivePivot() {
        val record = Evidence(
            id = "wayback-snapshot",
            kind = EvidenceKind.PublicSearchEvidence,
            value = "https://web.archive.org/web/20240101000000/https://example.test/profile",
            sourceUrl = "https://web.archive.org/web/20240101000000/https://example.test/profile",
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.ArchiveSnapshot
        )

        val model = TypedSeedEvidenceAdapter.admit(listOf(record))
        val archive = model.admittedSeeds.single()
        assertEquals(TypedSeedKind.Archive, archive.kind)
        assertEquals(listOf(record.id), archive.evidenceIds)
        assertEquals(ExposureSourceClassification.ARCHIVE, archive.sourceClassification)
    }

    @Test
    fun adapterProjectsOnlyDirectlyVerifiedSearchUrlsAsNavigationPivots() {
        val direct = Evidence(
            id = "search-direct",
            kind = EvidenceKind.PublicSearchEvidence,
            value = "https://social.example.test/jane?ref=search#profile",
            sourceUrl = "https://social.example.test/jane?ref=search#profile",
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.SearchEngineCandidate,
            sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
            contentHashSha256 = "hash-direct",
            discoveryPath = listOf("seed@example.test", "search-results"),
            attribution = FindingAttribution.Unconfirmed
        )

        val model = TypedSeedEvidenceAdapter.admit(listOf(direct))
        val seed = model.admittedSeeds.single()

        assertEquals(TypedSeedKind.Url, seed.kind)
        assertEquals(direct.value, seed.exactValue)
        assertEquals("https://social.example.test/jane?ref=search", seed.normalizedValue)
        assertEquals(EvidenceState.Observed, seed.evidenceState)
        assertEquals(TypedSeedOrigin.Evidence, seed.origin)
        assertEquals(direct.sourceClassification, seed.sourceClassification)
        assertEquals(listOf(direct.id), seed.evidenceIds)
        assertEquals(direct.sourceUrl, seed.sourceUrl)
        assertEquals(direct.discoveryPath, seed.discoveryPath)
    }

    @Test
    fun adapterRejectsCandidateUnavailableMalformedAndNonUrlSearchObservations() {
        fun record(
            id: String,
            value: String,
            state: EvidenceState,
            attribution: FindingAttribution? = FindingAttribution.Unconfirmed
        ) = Evidence(
            id = id,
            kind = EvidenceKind.PublicSearchEvidence,
            value = value,
            sourceUrl = value.takeIf { it.startsWith("http") },
            state = state,
            reliability = EvidenceReliability.SearchEngineCandidate,
            sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
            attribution = attribution
        )

        val model = TypedSeedEvidenceAdapter.admit(
            listOf(
                record(
                    id = "candidate",
                    value = "https://social.example.test/candidate",
                    state = EvidenceState.Candidate,
                    attribution = FindingAttribution.Candidate
                ),
                record(
                    id = "unavailable",
                    value = "https://social.example.test/unavailable",
                    state = EvidenceState.Unavailable
                ),
                record(
                    id = "malformed",
                    value = "not-a-url",
                    state = EvidenceState.Observed
                ),
                record(
                    id = "unsafe",
                    value = "http://127.0.0.1/private",
                    state = EvidenceState.Observed
                ),
                Evidence(
                    id = "missing-source",
                    kind = EvidenceKind.PublicSearchEvidence,
                    value = "https://social.example.test/missing-source",
                    state = EvidenceState.Observed,
                    reliability = EvidenceReliability.SearchEngineCandidate,
                    sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
                    attribution = FindingAttribution.Unconfirmed
                ),
                Evidence(
                    id = "unsafe-source",
                    kind = EvidenceKind.PublicSearchEvidence,
                    value = "https://social.example.test/unsafe-source",
                    sourceUrl = "file:///tmp/fixture",
                    state = EvidenceState.Observed,
                    reliability = EvidenceReliability.SearchEngineCandidate,
                    sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
                    attribution = FindingAttribution.Unconfirmed
                ),
                Evidence(
                    id = "missing-attribution",
                    kind = EvidenceKind.PublicSearchEvidence,
                    value = "https://social.example.test/missing-attribution",
                    sourceUrl = "https://social.example.test/missing-attribution",
                    state = EvidenceState.Observed,
                    reliability = EvidenceReliability.SearchEngineCandidate,
                    sourceClassification = ExposureSourceClassification.PUBLIC_WEB
                )
            )
        )

        assertTrue(model.admittedSeeds.isEmpty())
    }

    @Test
    fun adapterDoesNotPromoteImportedUserSuppliedEvidenceIntoSearchPivots() {
        val imported = Evidence(
            id = "imported-email",
            kind = EvidenceKind.Email,
            value = "imported@example.test",
            sourceUrl = "https://import.example.test/record",
            state = EvidenceState.Verified,
            reliability = EvidenceReliability.UserSupplied,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED
        )

        val model = TypedSeedEvidenceAdapter.admit(listOf(imported))

        assertTrue(model.admittedSeeds.none { it.kind == TypedSeedKind.Email })
    }

    @Test
    fun adapterRetainsFrontierDepthBoundForDirectSearchUrlPivot() {
        val record = Evidence(
            id = "deep-search",
            kind = EvidenceKind.PublicSearchEvidence,
            value = "https://social.example.test/deep",
            sourceUrl = "https://social.example.test/deep",
            state = EvidenceState.Observed,
            reliability = EvidenceReliability.SearchEngineCandidate,
            sourceClassification = ExposureSourceClassification.PUBLIC_WEB,
            discoveryPath = listOf("hop-0", "hop-1", "hop-2")
        )

        val model = TypedSeedEvidenceAdapter.admit(
            evidence = listOf(record),
            config = TypedSeedAdmissionConfig(maxDepth = 2)
        )

        assertTrue(model.admittedSeeds.isEmpty())
    }

    @Test
    fun userProvidedArchiveSnapshotUrlIsAdmittedAsHistoricalArchiveSeed() {
        val snapshot = "https://web.archive.org/web/20240101000000id_/https://example.test/profile"
        val model = TypedSeedEvidenceAdapter.fromCollection(
            collection = io.dossier.app.domain.evidence.EvidenceCollection(),
            input = IdentityInput(
                fullName = "Jane Example",
                profileUrls = listOf(snapshot, "https://example.test/profile")
            )
        )

        val archive = model.admittedSeeds.single { it.exactValue == snapshot }
        assertEquals(TypedSeedKind.Archive, archive.kind)
        assertEquals(ExposureSourceClassification.USER_IMPORTED, archive.sourceClassification)
        assertTrue(TypedSeedPublicFetchExecutor.classifyArchiveSnapshot(snapshot) != null)
    }

    @Test
    fun publicSearchSafetyRequiresVerifiedPublicEvidenceProvenance() {
        fun seed(
            source: ExposureSourceClassification,
            evidenceIds: List<String> = listOf("ev-1"),
            sourceUrl: String? = "https://profile.example.test/jane"
        ) = TypedSeed(
            kind = TypedSeedKind.Document,
            value = "https://docs.example.test/resume.pdf",
            exactValue = "https://docs.example.test/resume.pdf",
            isVerified = true,
            evidenceState = EvidenceState.Verified,
            origin = TypedSeedOrigin.Evidence,
            sourceClassification = source,
            evidenceIds = evidenceIds,
            sourceUrl = sourceUrl
        )

        assertTrue(TypedSeedSafety.isSafePublicSearchSeed(seed(ExposureSourceClassification.PUBLIC_DOCUMENT)))
        assertFalse(
            TypedSeedSafety.isSafePublicSearchSeed(
                seed(ExposureSourceClassification.DATA_BROKER)
            )
        )
        assertFalse(TypedSeedSafety.isSafePublicSearchSeed(seed(ExposureSourceClassification.PUBLIC_DOCUMENT, emptyList())))
        assertFalse(TypedSeedSafety.isSafePublicSearchSeed(seed(ExposureSourceClassification.PUBLIC_DOCUMENT, sourceUrl = null)))
        assertFalse(
            TypedSeedSafety.isSafePublicSearchSeed(
                seed(ExposureSourceClassification.PUBLIC_DOCUMENT, sourceUrl = "file:///tmp/evidence")
            )
        )

        val userSeed = TypedSeed(
            kind = TypedSeedKind.Url,
            value = "https://example.test/profile",
            exactValue = "https://example.test/profile",
            evidenceState = EvidenceState.Observed,
            origin = TypedSeedOrigin.UserInput,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED
        )
        assertTrue(TypedSeedSafety.isSafePublicSearchSeed(userSeed))
    }

    @Test
    fun publicSearchSafetyRejectsMalformedEmailAndPhoneValues() {
        fun userSeed(kind: TypedSeedKind, value: String) = TypedSeed(
            kind = kind,
            value = value,
            exactValue = value,
            normalizedValue = value,
            origin = TypedSeedOrigin.UserInput,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED,
            evidenceState = EvidenceState.Observed
        )

        assertFalse(
            TypedSeedSafety.isSafePublicSearchSeed(
                userSeed(TypedSeedKind.Email, "not-an-email")
            )
        )
        assertFalse(
            TypedSeedSafety.isSafePublicSearchSeed(
                userSeed(TypedSeedKind.Phone, "not-a-phone")
            )
        )
    }

    @Test
    fun publicSearchSafetyRejectsOverBoundOrInconsistentNormalizedValues() {
        fun userSeed(kind: TypedSeedKind, exactValue: String, normalizedValue: String) = TypedSeed(
            kind = kind,
            value = normalizedValue,
            exactValue = exactValue,
            normalizedValue = normalizedValue,
            origin = TypedSeedOrigin.UserInput,
            sourceClassification = ExposureSourceClassification.USER_IMPORTED,
            evidenceState = EvidenceState.Observed
        )

        assertFalse(
            TypedSeedSafety.isSafePublicSearchSeed(
                userSeed(
                    kind = TypedSeedKind.Email,
                    exactValue = "valid@example.test",
                    normalizedValue = "not-an-email"
                )
            )
        )
        assertFalse(
            TypedSeedSafety.isSafePublicSearchSeed(
                userSeed(
                    kind = TypedSeedKind.Email,
                    exactValue = "valid@example.test",
                    normalizedValue = "a".repeat(255) + "@example.test"
                )
            )
        )
        assertFalse(
            TypedSeedSafety.isSafePublicSearchSeed(
                userSeed(
                    kind = TypedSeedKind.Phone,
                    exactValue = "15550100100",
                    normalizedValue = "1".repeat(16)
                )
            )
        )
    }

    @Test
    fun derivesExecutionAvailabilityFromSharedExecutableSet() {
        val model = TypedSeedAdmissionModel()

        // Executable kinds
        assertEquals(TypedSeedExecutionAvailability.Available, model.availabilityFor(TypedSeedKind.Url))
        assertEquals(TypedSeedExecutionAvailability.Available, model.availabilityFor(TypedSeedKind.Domain))
        assertEquals(TypedSeedExecutionAvailability.Available, model.availabilityFor(TypedSeedKind.Document))
        assertEquals(TypedSeedExecutionAvailability.Available, model.availabilityFor(TypedSeedKind.Archive))

        // Public-search kinds
        assertEquals(TypedSeedExecutionAvailability.Available, model.availabilityFor(TypedSeedKind.Email))
        assertEquals(TypedSeedExecutionAvailability.Available, model.availabilityFor(TypedSeedKind.Phone))
        assertEquals(TypedSeedExecutionAvailability.Unavailable, model.availabilityFor(TypedSeedKind.Username))

        assertTrue(model.isExecutionAvailable(TypedSeedKind.Url))
        assertTrue(model.isExecutionAvailable(TypedSeedKind.Email))

        // Without seeds, no execution is available
        assertFalse(model.isExecutionAvailable)

        // With an executable seed, it becomes available
        model.offer(TypedSeedKind.Url, "https://example.test", 0)
        assertTrue(model.isExecutionAvailable)

        val snapshot = model.snapshot()
        assertTrue(snapshot.isExecutionAvailable)
        assertEquals(TypedSeedExecutionAvailability.Available, snapshot.executionAvailability[TypedSeedKind.Url])
        assertEquals(TypedSeedExecutionAvailability.Available, snapshot.executionAvailability[TypedSeedKind.Email])
    }
}
