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
 * The test panel's route, against the service as Main.kt assembles it: a fresh feed from 13 read
 * back by streamlord-test, a pass on the expectation the panel shows, and the reader's own
 * failure, stream and all, when `broken` asks for 99.
 */
class AssertTest {
    private suspend fun ApplicationTestBuilder.assert(body: String): HttpResponse =
        client.post("/assert") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `the test passes and counts the events`() =
        testApplication {
            application { live() }

            val body = assert("""{"broken":false}""").bodyAsText()

            assertTrue(body.contains("data: selector #assertion"), body)
            assertTrue(body.contains("data: mode inner"), body)
            assertTrue(body.contains("""data-color="success""""), body)
            assertTrue(body.contains("Both assertions passed."), body)
            assertTrue(body.contains("The stream carried 2 events."), body)
            assertFalse(body.contains("assertion__said"), body)
        }

    @Test
    fun `the count on the page is not read, so the test passes whatever it holds`() =
        testApplication {
            application { live() }

            val body = assert("""{"heads":500,"wire":true,"tool":"test"}""").bodyAsText()

            assertTrue(body.contains("Both assertions passed."), body)
            assertFalse(body.contains("#wire"), body)
        }

    @Test
    fun `broken is the reader's failure on 99, with the stream in it`() =
        testApplication {
            application { live() }

            val body = assert("""{"broken":true}""").bodyAsText()

            assertTrue(body.contains("""data-color="danger""""), body)
            assertTrue(body.contains("assertion__said"), body)
            // The reader's own words, which name the signal and both numbers.
            assertTrue(body.contains("heads"), body)
            assertTrue(body.contains("is 14, expected 99"), body)
            // And the stream it read, event by event, as every failure of the reader prints it.
            assertTrue(body.contains("The stream carried 2 events:"), body)
            assertTrue(body.contains("datastar-patch-elements"), body)
            assertTrue(body.contains("A new head hangs on the wall"), body)
            assertTrue(body.contains("datastar-patch-signals"), body)
            // The one line under the red, which says what the reader just showed.
            assertTrue(body.contains("This is what the test reader prints"), body)
            assertFalse(body.contains("Both assertions passed"), body)
        }

    @Test
    fun `broken that is not a boolean is a 400 that quotes nothing`() =
        testApplication {
            application { live() }

            val response = assert("""{"broken":"yes"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Signals could not be read", response.bodyAsText())
        }
}
