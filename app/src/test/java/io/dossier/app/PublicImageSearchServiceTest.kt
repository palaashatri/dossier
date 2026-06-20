package io.dossier.app

import io.dossier.app.data.web.PublicImageSearchService
import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicImageSearchServiceTest {

    @Test
    fun buildImageQueries_usesNameAndHandlesWithoutImageUpload() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe",
            aliases = listOf("jane.codes")
        )

        val queries = PublicImageSearchService.buildImageQueries(input)

        assertTrue(queries.any { it == "\"Jane Doe\"" })
        assertTrue(queries.any { it.contains("profile photo") })
        assertTrue(queries.any { it.contains("\"janedoe\" avatar") })
        assertTrue(queries.any { it.contains("\"jane.codes\" avatar") })
    }

    @Test
    fun parseImageResults_readsBingIuscMetadata() {
        val html = """
            <html><body>
              <a class="iusc" m='{"murl":"https://cdn.example.com/jane.jpg","turl":"https://thumb.example.com/jane.jpg","purl":"https://github.com/janedoe","t":"Jane Doe avatar"}'></a>
            </body></html>
        """.trimIndent()

        val results = PublicImageSearchService.parseImageResults("Bing Images", "\"Jane Doe\"", html)

        assertEquals(1, results.size)
        assertEquals("https://cdn.example.com/jane.jpg", results.first().imageUrl)
        assertEquals("https://thumb.example.com/jane.jpg", results.first().thumbnailUrl)
        assertEquals("https://github.com/janedoe", results.first().sourcePageUrl)
    }

    @Test
    fun scoreResult_boostsNameHandleAndVisualHost() {
        val input = IdentityInput(
            fullName = "Jane Doe",
            primaryUsername = "janedoe"
        )
        val result = PublicImageSearchService.PublicImageResult(
            title = "Jane Doe avatar",
            imageUrl = "https://cdn.example.com/janedoe.jpg",
            thumbnailUrl = "https://thumb.example.com/janedoe.jpg",
            sourcePageUrl = "https://github.com/janedoe",
            query = "\"Jane Doe\" profile photo",
            source = "Bing Images"
        )

        val score = PublicImageSearchService.scoreResult(input, result)

        assertTrue(score >= 0.70f)
    }
}
