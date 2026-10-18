package io.github.markusaugust.streamlord.demo

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The test panel's route, against the service as Main.kt assembles it: the feed read back by
 * streamlord-test, a pass when the field says what the feed sends, and the reader's own failure,
 * stream and all, when it does not.
 */
class AssertTest {
    private suspend fun ApplicationTestBuilder.assert(body: String): HttpResponse =
        client.post("/assert") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `thirteen heads and an expectation of fourteen is a pass that counts the events`() =
        testApplication {
            application { live() }

            val body = assert("""{"heads":13,"expect":14}""").bodyAsText()

            assertTrue(body.contains("data: selector #assertion"), body)
            assertTrue(body.contains("data: mode inner"), body)
            assertTrue(body.contains("""data-color="success""""), body)
            assertTrue(body.contains("Both assertions passed."), body)
            assertTrue(body.contains("The stream carried 2 events."), body)
            assertFalse(body.contains("assertion__said"), body)
        }

    @Test
    fun `an expectation of fifteen is the reader's failure, with the stream in it`() =
        testApplication {
            application { live() }

            val body = assert("""{"heads":13,"expect":15}""").bodyAsText()

            assertTrue(body.contains("""data-color="danger""""), body)
            assertTrue(body.contains("assertion__said"), body)
            // The reader's own words, which name the signal and both numbers.
            assertTrue(body.contains("heads"), body)
            assertTrue(body.contains("is 14, expected 15"), body)
            // And the stream it read, event by event, as every failure of the reader prints it.
            assertTrue(body.contains("The stream carried 2 events:"), body)
            assertTrue(body.contains("datastar-patch-elements"), body)
            assertTrue(body.contains("A new head hangs on the wall"), body)
            assertTrue(body.contains("datastar-patch-signals"), body)
            assertFalse(body.contains("Both assertions passed"), body)
        }

    @Test
    fun `a count the wall cannot hold fails the first assertion, since the feed hung no head`() =
        testApplication {
            application { live() }

            val body = assert("""{"heads":${HEADS_MAX + 1},"expect":14}""").bodyAsText()

            assertTrue(body.contains("""data-color="danger""""), body)
            assertTrue(body.contains("No element patch with selector #feed, mode append, elements containing A new head"), body)
            assertTrue(body.contains("is not a count it keeps"), body)
        }

    @Test
    fun `an expectation the wall cannot hold is told so, and nothing is asserted`() =
        testApplication {
            application { live() }

            val body = assert("""{"heads":13,"expect":-1}""").bodyAsText()

            assertTrue(body.contains("""data-color="warning""""), body)
            assertTrue(body.contains("-1 is not a count it keeps"), body)
            assertFalse(body.contains("The stream carried"), body)
        }

    @Test
    fun `an expectation that is not a number is a 400 that quotes nothing`() =
        testApplication {
            application { live() }

            val response = assert("""{"heads":13,"expect":"fourteen"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Signals could not be read", response.bodyAsText())
        }
}
