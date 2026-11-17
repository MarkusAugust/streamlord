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
                    Triple("page 2, order", "200", "built #1"),
                    Triple("page 2, order", "304", "not built"),
                    Triple("data", "changed", "now version 2"),
                    Triple("page 2, order", "200", "built #2"),
                ),
                ledgers
                    .open(visitor)
                    .log.value.lines
                    .map { Triple(it.what, it.status, it.result) },
            )
        }

    @Test
    fun `the sort rides in the signals, each sort is its own answer, and the tag does not name it`() =
        testApplication {
            application { live() }
            val visitor = ledgers.open(null).visitor
            val byWho = "datastar=" + java.net.URLEncoder.encode("""{"sort":"who"}""", Charsets.UTF_8)

            val plain = client.get("/orders/$visitor?page=1")
            val sorted = client.get("/orders/$visitor?page=1&$byWho")
            val nonsense = client.get("/orders/$visitor?page=1&datastar=" + java.net.URLEncoder.encode("""{"sort":"x"}""", Charsets.UTF_8))

            assertEquals(
                plain.headers[HttpHeaders.ETag],
                sorted.headers[HttpHeaders.ETag],
                "the browser keeps each URL apart, so the tag needs no sort",
            )
            assertNotEquals(plain.bodyAsText(), sorted.bodyAsText())
            assertTrue(sorted.bodyAsText().contains("Ashe"), "the first of the table by who: ${sorted.bodyAsText()}")
            assertEquals(plain.bodyAsText(), nonsense.bodyAsText(), "a sort the table does not have is the default one")
            assertEquals(
                "page 1, who",
                ledgers
                    .open(visitor)
                    .log.value.lines[1]
                    .what,
            )
        }

    @Test
    fun `sorting orders the whole table before it is cut into pages`() {
        val byWho = (1..ORDER_PAGES).flatMap { orderRows(it, 1, "who") }.map { it.who }
        assertEquals(byWho.sorted(), byWho)
        assertEquals(9, byWho.toSet().size)
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
            assertTrue(table.bodyAsText().contains("let this panel go"), table.bodyAsText())
            assertEquals("no-store", table.headers[HttpHeaders.CacheControl])
            assertEquals(before, ledgers.size)
        }

    @Test
    fun `a header the server cannot read is no match, not a failure`() =
        testApplication {
            application { live() }
            val visitor = ledgers.open(null).visitor

            for (malformed in listOf("\"v1-1\";q=0.5", "\"a b\"", "a\"b", "\"a,b\"")) {
                val response = client.get("/orders/$visitor?page=1") { header(HttpHeaders.IfNoneMatch, malformed) }
                assertEquals(HttpStatusCode.OK, response.status, malformed)
            }
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
        repeat(20) { ledger.answer(1, "order", "GET /orders") { 304 } }
        val before = ledger.log.value
        ledger.answer(1, "order", "GET /orders") { 304 }
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
    fun `past capacity, a ledger nobody pressed a page with goes before one in use`() {
        val ledgers = Ledgers(capacity = 2)
        val used = ledgers.open(null)
        used.answer(1, "order", "GET /orders") { 200 }
        val idle = ledgers.open(null)
        ledgers.open(null)
        assertSame(used, ledgers.find(used.visitor), "the one in use stays, though it is the least recent")
        assertNull(ledgers.find(idle.visitor))
    }

    @Test
    fun `a ledger pushed out ends its log, so the page can open a new one`() =
        runTest {
            val ledgers = Ledgers(capacity = 1)
            val first = ledgers.open(null)
            val events = mutableListOf<Any>()
            val log = launch { orderLog(first, wire = false, sent = "").toList(events) }
            delay(1.seconds)

            ledgers.open(null)
            delay(1.seconds)

            assertTrue(log.isCompleted, "the log of a ledger that is gone ends at once, not after the quiet minutes")
        }

    // The page fetches the first table when the id arrives, so a log reopened on the same id must
    // not send it again, or the table would jump back to page one.
    @Test
    fun `a log reopened on the id the page holds does not send it again`() =
        runTest {
            val ledger = Ledgers().open(null)
            val events = mutableListOf<Any>()
            val log = launch { orderLog(ledger, wire = false, sent = ledger.visitor).toList(events) }
            delay(1.seconds)
            log.cancel()

            assertTrue(events.none { it is PatchSignals }, events.toString())
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
            val log = launch { orderLog(ledger, wire = true, sent = "").toList(events) }

            delay(1.seconds)
            ledger.answer(1, "order", "GET /orders/x?page=1") { 200 }
            delay(119.seconds)
            ledger.answer(1, "order", "GET /orders/x?page=1\nIf-None-Match: \"v1-1\"") { 304 }
            delay(1.minutes)
            assertTrue(log.isActive, "a line keeps it open")
            delay(61.seconds)
            log.join()

            assertEquals("""{"visitor": "${ledger.visitor}"}""", (events.first() as PatchSignals).signals)
            val patches = events.filterIsInstance<PatchElements>()
            val shown = patches.filter { it.selector == "#orders-log" }.map { it.elements.orEmpty() }
            assertTrue(shown[1].contains("built #1"), shown[1])
            assertTrue(shown[2].contains("not built"), shown[2])
            val wire = patches.filter { it.selector == "#wire" }.map { it.elements.orEmpty() }
            assertEquals(2, wire.size, "one exchange per answer, and none for what was there before the log opened")
            assertTrue(wire[1].contains("304 Not Modified") && wire[1].contains("(no body)"), wire[1])
            assertTrue((events.last() as PatchElements).elements.orEmpty().contains("Closed after 2 quiet minutes"))
        }
}
