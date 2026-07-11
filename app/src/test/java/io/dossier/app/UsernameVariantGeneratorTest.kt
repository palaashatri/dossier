package io.dossier.app

import io.dossier.app.domain.username.UsernameVariantGenerator
import org.junit.Assert.*
import org.junit.Test

class UsernameVariantGeneratorTest {

    @Test
    fun testUsernameVariants() {
        val generator = UsernameVariantGenerator()
        val variants = generator.generate("jane doe")
        val usernames = variants.map { it.username }

        // The generator intentionally does NOT produce mid-word dot variants
        // like "ja.ne doe" (they're nonsense). It produces exact, underscore,
        // hyphen, dot, and concat variants for space-containing input.
        assertTrue("Should contain exact match", usernames.contains("jane doe"))
        assertTrue("Should contain underscore variant", usernames.contains("jane_doe"))
        assertTrue("Should contain hyphen variant", usernames.contains("jane-doe"))
        assertTrue("Should contain dot variant", usernames.contains("jane.doe"))
        assertTrue("Should contain concat variant", usernames.contains("janedoe"))
    }

    @Test
    fun generateFromName_keepsCoreVariantsAndLastFirst() {
        val generator = UsernameVariantGenerator()
        val usernames = generator.generateFromName("Jane Doe").map { it.username }

        assertTrue(usernames.contains("janedoe"))
        assertTrue(usernames.contains("jane.doe"))
        assertTrue(usernames.contains("jane_doe"))
        assertTrue(usernames.contains("jane-doe"))
        assertTrue(usernames.contains("doe.jane"))
        assertTrue(usernames.contains("jdoe"))
        // No random year suffixes
        assertFalse(usernames.any { it.matches(Regex(".*\\d{4}$")) })
    }

    @Test
    fun generateFromEmailLocalPart_splitsSeparators() {
        val generator = UsernameVariantGenerator()
        val usernames = generator.generateFromEmailLocalPart("jane.doe+tag").map { it.username }

        assertTrue("exact cleaned local", usernames.any { it == "jane.doe" || it.startsWith("jane.doe") })
        assertTrue(usernames.contains("jane.doe") || usernames.contains("jane_doe"))
        assertTrue(usernames.contains("janedoe") || usernames.contains("jane.doe"))
        assertTrue(usernames.contains("jane") || usernames.contains("doe") || usernames.contains("janedoe"))
    }

    @Test
    fun generateFromEmails_mapsLocalParts() {
        val generator = UsernameVariantGenerator()
        val usernames = generator.generateFromEmails(
            listOf("jane.doe@example.com", "jdoe@corp.io")
        ).map { it.username }

        assertTrue(usernames.contains("jane.doe") || usernames.contains("janedoe"))
        assertTrue(usernames.contains("jdoe"))
    }

    @Test
    fun generateAllSeeds_unionsSources() {
        val generator = UsernameVariantGenerator()
        val usernames = generator.generateAllSeeds(
            primary = "cool_user",
            name = "Jane Doe",
            usernames = listOf("step3handle"),
            emails = listOf("jane.doe@example.com")
        ).map { it.username.lowercase() }

        assertTrue(usernames.contains("cool_user"))
        assertTrue(usernames.contains("janedoe"))
        assertTrue(usernames.contains("step3handle"))
        assertTrue(usernames.any { it.contains("jane") })
    }
}
