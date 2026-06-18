package io.dossier.app

import io.dossier.app.domain.username.UsernameVariantGenerator
import io.dossier.app.domain.model.UsernameMatchType
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
        // and hyphen variants for space-containing input.
        assertTrue("Should contain exact match", usernames.contains("jane doe"))
        assertTrue("Should contain underscore variant", usernames.contains("jane_doe"))
        assertTrue("Should contain hyphen variant", usernames.contains("jane-doe"))
    }
}
