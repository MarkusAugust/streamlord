package io.github.markusaugust.streamlord.demo

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The search echoes the reader's words back into the page, which is the one place this service
 * puts untrusted input into markup. The line is built as a string and filled by `interpolate`,
 * so what this holds is that a hostile query comes back as text and not as markup.
 */
class SearchEchoTest {
    @Test
    fun `a hostile query is echoed as text, not as markup`() =
        testApplication {
            application { live() }

            // A word no docs page holds, so the reply echoes the query instead of listing hits.
            val body =
                client
                    .get("/search?datastar=%7B%22query%22%3A%22%3Cscript%3Ezqxjvw()%3C%2Fscript%3E%22%7D")
                    .bodyAsText()

            assertTrue(body.contains("&lt;script&gt;zqxjvw()&lt;/script&gt;"), body)
            assertFalse(body.contains("<script>"), "the query reached the page as markup")
        }
}
