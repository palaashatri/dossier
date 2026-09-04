package io.dossier.app.domain.discovery

import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.ExposureSourceClassification
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
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
            TypedSeedKind.Username
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

        assertEquals("user@example.test", model.admittedSeeds[0].normalizedValue)
        assertEquals("15550100100", model.admittedSeeds[1].normalizedValue)
        assertEquals("http://example.test/path", model.admittedSeeds[2].normalizedValue)
        assertEquals("example.test", model.admittedSeeds[3].normalizedValue)
        assertEquals("alice_user", model.admittedSeeds[4].normalizedValue)
        assertEquals("content://media/item", model.admittedSeeds[5].normalizedValue)
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
    fun adapterCarriesEvidenceSourceStateAndPathWhileExecutionStaysUnavailable() {
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
        assertEquals(TypedSeedExecutionAvailability.Unavailable, model.availabilityFor(TypedSeedKind.Email))
        assertFalse(model.isExecutionAvailable)
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
}
