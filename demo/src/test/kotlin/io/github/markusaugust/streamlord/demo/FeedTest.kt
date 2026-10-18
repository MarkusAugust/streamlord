package io.github.markusaugust.streamlord.demo

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The front page's example, against the service as Main.kt assembles it: the count comes out of
 * the request, one head is added, and the frames follow when the page has somewhere to put them.
 */
class FeedTest {
    private val heads13WithWire = "/feed?datastar=%7B%22heads%22%3A13%2C%22wire%22%3Atrue%7D"

    @Test
    fun `thirteen heads in, one li and fourteen heads out, with the frames`() =
        testApplication {
            application { live() }

            val body = client.get(heads13WithWire).bodyAsText()
            val events = body.split("\n\n").filter { it.startsWith("event:") }

            assertEquals(3, events.size, "the li, the count and the frames: $body")
            assertTrue(events[0].startsWith("event: datastar-patch-elements"), events[0])
            assertTrue(events[0].contains("data: selector #feed"), events[0])
            assertTrue(events[0].contains("data: mode append"), events[0])
            assertTrue(events[0].contains("<li>A new head hangs on the wall</li>"), events[0])
            assertTrue(events[1].startsWith("event: datastar-patch-signals"), events[1])
            assertTrue(events[1].contains("""data: signals {"heads": 14}"""), events[1])

            // The wire panel shows the two frames above, encoded, and not a description of them.
            assertTrue(events[2].contains("data: selector #wire"), events[2])
            assertTrue(events[2].contains("datastar-patch-signals"), events[2])
            assertTrue(events[2].contains("&quot;heads&quot;: 14"), events[2])
        }

    @Test
    fun `without wire the frames stay home`() =
        testApplication {
            application { live() }

            val body = client.get("/feed?datastar=%7B%22heads%22%3A0%7D").bodyAsText()

            assertTrue(body.contains("""{"heads": 1}"""), body)
            assertFalse(body.contains("#wire"), body)
        }

    @Test
    fun `a count the wall cannot hold is a verdict in the feed, not a new count`() {
        for (heads in listOf(-1, HEADS_MAX + 1)) {
            val events = feedEvents(FeedSignals(heads = heads))

            assertEquals(1, events.size, "one verdict and nothing else for $heads")
            val said = events.single().toString()
            assertTrue(said.contains("is not a count it keeps"), said)
            assertFalse(said.contains("A new head"), said)
        }
    }

    @Test
    fun `the last head the wall holds still hangs`() {
        val events = feedEvents(FeedSignals(heads = HEADS_MAX - 1))

        assertEquals(2, events.size)
        assertTrue(events[1].toString().contains(""""heads": $HEADS_MAX"""), events[1].toString())
    }
}
