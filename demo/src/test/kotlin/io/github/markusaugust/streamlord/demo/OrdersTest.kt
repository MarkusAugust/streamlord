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
import kotlin.test.assertNull
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
                    Triple("page 2", "200", "built #1"),
                    Triple("page 2", "304", "not built"),
                    Triple("data", "changed", "now version 2"),
                    Triple("page 2", "200", "built #2"),
                ),
                ledgers
                    .open(visitor)
                    .log.value.lines
                    .map { Triple(it.what, it.status, it.result) },
            )
        }

    @Test
    fun `the same tag is always the same bytes`() =
        testApplication {
            application { live() }
            val visitor = ledgers.open(null).visitor

            val page1 = client.get("/orders/$visitor?page=1")
            client.get("/orders/$visitor?page=2")
            val page1Again = client.get("/orders/$visitor?page=1")

            assertEquals(page1.headers[HttpHeaders.ETag], page1Again.headers[HttpHeaders.ETag])
            assertEquals(page1.bodyAsText(), page1Again.bodyAsText())
        }

    @Test
    fun `an id the service does not hold makes no ledger, and the panel is told`() =
        testApplication {
            application { live() }
            val before = ledgers.size

            val table = client.get("/orders/0123456789abcdef?page=1")
            client.post("/orders/fedcba9876543210/change")

            assertEquals(HttpStatusCode.OK, table.status)
            assertTrue(table.bodyAsText().contains("forgotten"), table.bodyAsText())
            assertEquals("no-store", table.headers[HttpHeaders.CacheControl])
            assertEquals(before, ledgers.size)
        }

    @Test
    fun `a header the server cannot read is no match, not a failure`() =
        testApplication {
            application { live() }
            val visitor = ledgers.open(null).visitor

            val response = client.get("/orders/$visitor?page=1") { header(HttpHeaders.IfNoneMatch, "\"v1-1\";q=0.5") }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `a failed If-Match is logged as what it was`() =
        testApplication {
            application { live() }
            val ledger = ledgers.open(null)

            val response = client.get("/orders/${ledger.visitor}?page=1") { header(HttpHeaders.IfMatch, "\"nope\"") }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
            assertEquals(
                "412",
                ledger.log.value.lines
                    .single()
                    .status,
            )
        }

    @Test
    fun `the same line twice is still two lines`() {
        val ledger = Ledgers().open(null)
        repeat(20) { ledger.answer(1, "GET /orders") { 304 } }
        val before = ledger.log.value
        ledger.answer(1, "GET /orders") { 304 }
        assertNotEquals(before, ledger.log.value, "an equal state would not reach the log stream")
    }

    @Test
    fun `a visitor is handed an id, and keeps it`() {
        val ledgers = Ledgers()
        val first = ledgers.open(null)
        assertTrue(Regex("[0-9a-f]{16}").matches(first.visitor), first.visitor)
        assertSame(first, ledgers.open(first.visitor))
        assertSame(first, ledgers.find(first.visitor))
        assertNotSame(first, ledgers.open(null))
        assertNull(ledgers.find("0123456789abcdef"))
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
        assertSame(a, ledgers.find(a.visitor), "a was used after b, so b went")
        assertNull(ledgers.find(b.visitor))
    }

    @Test
    fun `a ledger nobody used for half an hour is gone`() {
        var now = 0L
        val ledgers = Ledgers(clock = { now })
        val a = ledgers.open(null)
        now += 31.minutes.inWholeMilliseconds
        assertNull(ledgers.find(a.visitor))
    }

    @Test
    fun `the log hands out the id, shows every line and the exchange, and closes after two quiet minutes`() =
        runTest {
            val ledger = Ledgers().open(null)
            val events = mutableListOf<Any>()
            val log = launch { orderLog(ledger, wire = true).toList(events) }

            delay(1.seconds)
            ledger.answer(1, "GET /orders/x?page=1") { 200 }
            delay(119.seconds)
            ledger.answer(1, "GET /orders/x?page=1\nIf-None-Match: \"v1-1\"") { 304 }
            delay(1.minutes)
            assertTrue(log.isActive, "a line keeps it open")
            delay(61.seconds)
            log.join()

            assertEquals("""{"visitor": "${ledger.visitor}", "logOpen": true}""", (events.first() as PatchSignals).signals)
            val patches = events.filterIsInstance<PatchElements>()
            val shown = patches.filter { it.selector == "#orders-log" }.map { it.elements.orEmpty() }
            assertTrue(shown[1].contains("built #1"), shown[1])
            assertTrue(shown[2].contains("not built"), shown[2])
            val wire = patches.filter { it.selector == "#wire" }.map { it.elements.orEmpty() }
            assertEquals(2, wire.size, "one exchange per answer, and none for what was there before the log opened")
            assertTrue(wire[1].contains("304 Not Modified") && wire[1].contains("(no body)"), wire[1])
            assertEquals("""{"logOpen": false}""", (events.last() as PatchSignals).signals)
        }
}
