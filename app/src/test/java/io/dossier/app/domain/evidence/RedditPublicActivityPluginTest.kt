package io.dossier.app.domain.evidence

import io.dossier.app.domain.model.IdentityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditPublicActivityPluginTest {

    @Test
    fun explicitHandlesUseOnlyValidSuppliedHandles() {
        val input = IdentityInput(
            fullName = "",
            primaryUsername = "u/example_user",
            usernames = listOf("@second-user", "bad handle", "example_user")
        )
        assertEquals(
            listOf("example_user", "second-user"),
            RedditPublicActivityPlugin.explicitHandles(input)
        )
    }

    @Test
    fun searchUrlsUseExactAuthorQueries() {
        val post = RedditPublicActivityPlugin.buildPostSearchUrl("example_user")
        val comment = RedditPublicActivityPlugin.buildCommentSearchUrl("example_user")

        assertTrue(post.startsWith("https://api.reddit.com/search/"))
        assertTrue(post.contains("author%3A%22example_user%22"))
        assertTrue(comment.startsWith("https://www.reddit.com/svc/shreddit/search/"))
        assertTrue(comment.contains("type=comments"))
        assertTrue(comment.contains("author%3A%22example_user%22"))
    }

    @Test
    fun postSearchRejectsWrongAuthorsAndPreservesPagination() {
        val payload = """
            {
              "data": {
                "after": "t3_next",
                "children": [
                  {"kind":"t3","data":{"author":"example_user","permalink":"/r/test/comments/abc/title/","title":"My post","selftext":"body","subreddit":"test","created_utc":1700000000}},
                  {"kind":"t3","data":{"author":"someone_else","permalink":"/r/test/comments/def/title/","title":"Other","subreddit":"test","created_utc":1700000001}}
                ]
              }
            }
        """.trimIndent()

        val page = RedditPublicActivityPlugin.parsePostSearch(payload, "example_user")
        assertEquals("t3_next", page.after)
        assertEquals(1, page.items.size)
        assertTrue(page.items.single().verified)
        assertTrue(page.items.single().url.contains("/comments/abc/"))
        assertTrue(page.items.single().snippet.contains("My post"))
    }

    @Test
    fun shredditCommentSearchParsesPermalinkAndLazyContinuation() {
        val html = """
            <html><body>
              <search-telemetry-tracker data-faceplate-tracking-context="{&quot;comment&quot;:{&quot;id&quot;:&quot;c0ffee&quot;},&quot;post&quot;:{&quot;title&quot;:&quot;Post title&quot;},&quot;subreddit&quot;:{&quot;name&quot;:&quot;privacy&quot;}}">
                <div data-testid="search-sdui-comment-unit">
                  <a aria-labelledby="comment-content-c0ffee" href="/r/privacy/comments/abc123/post_title/c0ffee/">open</a>
                  <div data-testid="search-comment-content">
                    <div id="search-comment-c0ffee-post-rtjson-content"><p>Public comment body</p></div>
                  </div>
                  <faceplate-timeago ts="2026-08-20T12:34:56Z"></faceplate-timeago>
                </div>
              </search-telemetry-tracker>
              <faceplate-partial loading="lazy" src="/svc/shreddit/search/?q=next&amp;type=comments"></faceplate-partial>
            </body></html>
        """.trimIndent()

        val page = RedditPublicActivityPlugin.parseCommentSearch(
            html,
            RedditPublicActivityPlugin.buildCommentSearchUrl("example_user")
        )

        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertFalse(item.verified)
        assertTrue(item.url.endsWith("/c0ffee/"))
        assertTrue(item.snippet.contains("Public comment body"))
        assertNotNull(page.nextUrl)
        assertTrue(page.nextUrl!!.startsWith("https://www.reddit.com/svc/shreddit/search/"))
    }

    @Test
    fun commentPermalinkProducesDirectJsonVerificationUrl() {
        val permalink = "https://www.reddit.com/r/privacy/comments/abc123/post_title/c0ffee/"
        assertEquals("c0ffee", RedditPublicActivityPlugin.commentIdFromPermalink(permalink))
        assertEquals(
            "https://www.reddit.com/r/privacy/comments/abc123/post_title/c0ffee.json?raw_json=1",
            RedditPublicActivityPlugin.commentJsonUrl(permalink)
        )
    }
}
