package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The caching demo: a table answered as plain HTML with an ETag, and a log of what the server
 * did with each request, streamed beside it. What has to hold is the caching page's promise, a
 * 304 with both headers and nothing built, and that a visitor's state stays theirs and small.
 */
class OrdersTest {
    @Test
    fun `a page is built once and then answered with 304, and the log says which`() =
        testApplication {
            application { live() }
            val visitor = ledgers.open(null).visitor

            val first = client.get("/orders/$visitor?page=2")
            assertEquals(HttpStatusCode.OK, first.status)
            assertTrue(first.bodyAsText().contains("""id="orders""""), first.bodyAsText())
            val etag = first.headers[HttpHeaders.ETag]!!
            assertEquals("private, no-cache", first.headers[HttpHeaders.CacheControl])

            val again = client.get("/orders/$visitor?page=2") { header(HttpHeaders.IfNoneMatch, etag) }
            assertEquals(HttpStatusCode.NotModified, again.status)
            assertEquals(etag, again.headers[HttpHeaders.ETag])
            assertEquals("private, no-cache", again.headers[HttpHeaders.CacheControl])

            client.post("/orders/$visitor/change")
            val changed = client.get("/orders/$visitor?page=2") { header(HttpHeaders.IfNoneMatch, etag) }
            assertEquals(HttpStatusCode.OK, changed.status)
            assertNotEquals(etag, changed.headers[HttpHeaders.ETag])

            assertEquals(
                listOf(
                    LogLine("page 2", "200", "built #1"),
                    LogLine("page 2", "304", "not built"),
                    LogLine("data", "changed", "now version 2"),
                    LogLine("page 2", "200", "built #2"),
                ),
                ledgers.open(visitor).lines.value,
            )
        }

    @Test
    fun `a visitor is handed an id, and keeps it`() {
        val ledgers = Ledgers()
        val first = ledgers.open(null)
        assertTrue(Regex("[0-9a-f]{16}").matches(first.visitor), first.visitor)
        assertSame(first, ledgers.open(first.visitor))
        assertNotSame(first, ledgers.open(null))
    }

    @Test
    fun `an id the service did not make the shape of is replaced`() {
        val ledgers = Ledgers()
        val ledger = ledgers.open("""<script>""")
        assertTrue(Regex("[0-9a-f]{16}").matches(ledger.visitor), ledger.visitor)
    }

    @Test
    fun `the ledgers hold no more than their capacity, dropping the least recent`() {
        val ledgers = Ledgers(capacity = 2)
        val a = ledgers.open(null)
        val b = ledgers.open(null)
        ledgers.open(a.visitor)
        ledgers.open(null)
        assertEquals(2, ledgers.size)
        assertSame(a, ledgers.open(a.visitor), "a was used after b, so b went")
        assertNotSame(b, ledgers.open(b.visitor))
    }

    @Test
    fun `a ledger nobody used for half an hour is gone`() {
        var now = 0L
        val ledgers = Ledgers(clock = { now })
        val a = ledgers.open(null)
        now += 31.minutes.inWholeMilliseconds
        assertNotSame(a, ledgers.open(a.visitor))
    }

    @Test
    fun `the log hands out the id, shows every line, and closes after two quiet minutes`() =
        runTest {
            val ledger = Ledgers().open(null)
            val events = mutableListOf<Any>()
            val log = launch { orderLog(ledger).toList(events) }

            delay(1.seconds)
            ledger.built(1)
            delay(119.seconds)
            ledger.notBuilt(1)
            delay(1.minutes)
            assertTrue(log.isActive, "a line keeps it open")
            delay(61.seconds)
            log.join()

            assertEquals("""{"visitor": "${ledger.visitor}", "logOpen": true}""", (events.first() as PatchSignals).signals)
            val shown = events.filterIsInstance<PatchElements>().filter { it.selector == "#orders-log" }.map { it.elements.orEmpty() }
            assertTrue(shown[1].contains("built #1"), shown[1])
            assertTrue(shown[2].contains("not built"), shown[2])
            assertEquals("""{"logOpen": false}""", (events.last() as PatchSignals).signals)
        }
}
