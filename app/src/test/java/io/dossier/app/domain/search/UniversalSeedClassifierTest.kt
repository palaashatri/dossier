package io.dossier.app.domain.search

import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalSeedClassifierTest {
    @Test
    fun blankInputProducesNoSeed() {
        assertNull(UniversalSeedClassifier.classify("   "))
    }

    @Test
    fun multiWordTextIsANameSeed() {
        val seed = requireNotNull(UniversalSeedClassifier.classify("Jane Example"))
        assertEquals(UniversalSeedType.Name, seed.type)
        assertEquals("Jane Example", seed.normalized)
        assertEquals("Jane Example", seed.toIdentityInput().fullName)
    }

    @Test
    fun explicitHandleIsAUsernameSeed() {
        val seed = requireNotNull(UniversalSeedClassifier.classify("@sample_user"))
        assertEquals(UniversalSeedType.Username, seed.type)
        assertEquals("sample_user", seed.normalized)
        assertEquals("sample_user", seed.toIdentityInput().primaryUsername)
        assertEquals(listOf("sample_user"), seed.toIdentityInput().usernames)
    }

    @Test
    fun lowercaseHandleLikeTokenIsAUsernameSeed() {
        val seed = requireNotNull(UniversalSeedClassifier.classify("sample_user2"))
        assertEquals(UniversalSeedType.Username, seed.type)
        assertEquals("sample_user2", seed.normalized)
    }

    @Test
    fun phoneFormattingIsNormalizedWithoutLosingInternationalPrefix() {
        val seed = requireNotNull(UniversalSeedClassifier.classify("+91 98765-43210"))
        assertEquals(UniversalSeedType.Phone, seed.type)
        assertEquals("+919876543210", seed.normalized)
        assertEquals(listOf("+919876543210"), seed.toIdentityInput().phones)
    }

    @Test
    fun emailIsRecognizedBeforeUsernameHeuristics() {
        val seed = requireNotNull(UniversalSeedClassifier.classify("jane@example.test"))
        assertEquals(UniversalSeedType.Email, seed.type)
        assertEquals("jane@example.test", seed.normalized)
        assertEquals(listOf("jane@example.test"), seed.toIdentityInput().emails)
    }

    @Test
    fun webUrlIsAUrlSeed() {
        val seed = requireNotNull(UniversalSeedClassifier.classify("https://example.test/profile/jane"))
        assertEquals(UniversalSeedType.Url, seed.type)
        assertEquals(listOf("https://example.test/profile/jane"), seed.toIdentityInput().profileUrls)
    }

    @Test
    fun plainCapitalizedSingleWordRemainsAName() {
        val seed = requireNotNull(UniversalSeedClassifier.classify("Jane"))
        assertEquals(UniversalSeedType.Name, seed.type)
    }

    @Test
    fun photoOnlyInputIsAUsableUniversalScanSeed() {
        val input = IdentityInput(fullName = "", selfieUri = "content://dossier.test/photo")
        assertTrue(input.hasUsableUniversalSeed())
    }
}
