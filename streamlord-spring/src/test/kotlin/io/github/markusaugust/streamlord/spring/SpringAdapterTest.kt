package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.github.markusaugust.streamlord.json.jackson.JacksonSignalsCodec
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SpringAdapterTest {

    data class Search(val query: String = "", val page: Int = 1)

    private val streamlord = Streamlord(codec = JacksonSignalsCodec.default())

    @Test
    fun `streaming response body writes sse with headers`() {
        val response = MockHttpServletResponse()
        val body = response.datastarStream(streamlord) {
            patchElements("""<div id="a">1</div>""")
            patchSignals(Search("ash", 2))
            removeElements("#b")
        }
        body.writeTo(response.outputStream)

        assertTrue(response.contentType!!.startsWith("text/event-stream"))
        assertEquals("no-cache", response.getHeader("Cache-Control"))
        assertEquals("keep-alive", response.getHeader("Connection"))
        assertEquals(
            "event: datastar-patch-elements\ndata: elements <div id=\"a\">1</div>\n\n" +
                "event: datastar-patch-signals\ndata: signals {\"query\":\"ash\",\"page\":2}\n\n" +
                "event: datastar-patch-elements\ndata: selector #b\ndata: mode remove\n\n",
            response.contentAsString,
        )
    }

    @Test
    fun `flow variant`() {
        val response = MockHttpServletResponse()
        response.datastarStream(flowOf(PatchSignals("{\"a\":1}"))).writeTo(response.outputStream)
        assertEquals("event: datastar-patch-signals\ndata: signals {\"a\":1}\n\n", response.contentAsString)
    }

    @Test
    fun `reads signals from query for GET and body for POST`() {
        val get = MockHttpServletRequest("GET", "/x").apply {
            addParameter("datastar", """{"query":"ash","page":3}""")
            addHeader("Datastar-Request", "true")
        }
        assertEquals(Search("ash", 3), get.readSignals<Search>(streamlord))
        assertEquals(3, get.readSignals().int("page"))
        assertTrue(get.isDatastarRequest)

        val post = MockHttpServletRequest("POST", "/x").apply {
            setContent("""{"query":"fire"}""".toByteArray())
            contentType = "application/json"
        }
        assertEquals(Search("fire"), post.readSignals<Search>(streamlord))
        assertEquals(Search("fire"), post.readSignalsOr(Search(), streamlord).let { Search("fire") })

        val empty = MockHttpServletRequest("GET", "/x")
        assertNull(empty.readSignals<Search>(streamlord))
        assertEquals(Search(), empty.readSignalsOr(Search(), streamlord))
    }

    @Test
    fun `oversized bodies are cut off at the limit`() {
        val small = Streamlord(maxSignalsSize = 64)
        val body = ("{\"a\":\"" + "x".repeat(10_000) + "\"}").toByteArray()

        // Declared length: rejected before a byte is read.
        val declared = MockHttpServletRequest("POST", "/x").apply { setContent(body) }
        assertEquals(body.size.toLong(), assertFailsWith<SignalsTooLargeException> { declared.readSignals(small) }.actualSize)

        // Chunked (no length): reading stops one byte past the limit.
        val chunked = object : MockHttpServletRequest("POST", "/x") {
            override fun getContentLengthLong(): Long = -1
        }.apply { setContent(body) }
        assertEquals(65L, assertFailsWith<SignalsTooLargeException> { chunked.readSignals(small) }.actualSize)
    }

    @Test
    fun `response entities carry datastar headers`() {
        val el = datastarElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND)
        assertEquals("text/html;charset=utf-8", el.headers.contentType.toString().replace(" ", ""))
        assertEquals("#list", el.headers.getFirst("datastar-selector"))
        assertEquals("append", el.headers.getFirst("datastar-mode"))

        val sig = datastarSignals(Search("q"), streamlord, onlyIfMissing = true)
        assertEquals("""{"query":"q","page":1}""", sig.body)
        assertEquals("true", sig.headers.getFirst("datastar-only-if-missing"))

        val js = datastarScript("1", mapOf("type" to "module"))
        assertEquals("""{"type":"module"}""", js.headers.getFirst("datastar-script-attributes"))
    }

    @Test
    fun `the elements guard reaches response entities and reactive events`() = runTest {
        val guarded = Streamlord(guardElements = true)
        val broken = """<li id="a" data-text=""></li>"""
        val e = assertFailsWith<InterpolatedExpressionException> { datastarElements(broken, streamlord = guarded) }
        assertEquals("data-text", e.attribute)
        assertFailsWith<InterpolatedExpressionException> { flowOf(PatchElements(broken)).asServerSentEvents(guarded).toList() }
        assertEquals(broken, datastarElements(broken).body)
        assertEquals(1, flowOf(PatchElements(broken)).asServerSentEvents().toList().size)
        assertEquals(1, flowOf(PatchElements("""<li id="a" data-text="${'$'}count"></li>""")).asServerSentEvents(guarded).toList().size)
    }

    @Test
    fun `events map onto spring server-sent events`() = runTest {
        val events = flowOf(
            PatchElements("<div>\n</div>", selector = "#a", mode = ElementPatchMode.INNER, eventId = "7", retry = 2.seconds),
            ExecuteScript("x()"),
        ).asServerSentEvents().toList()

        assertEquals("datastar-patch-elements", events[0].event())
        assertEquals("7", events[0].id())
        assertEquals(2000L, events[0].retry()?.toMillis())
        assertEquals("selector #a\nmode inner\nelements <div>\nelements </div>", events[0].data())
        assertNull(events[1].id())
        assertEquals("selector body\nmode append\nelements <script data-effect=\"el.remove()\">x()</script>", events[1].data())
    }

    @Test
    fun `patch mode converter binds wire tokens`() {
        assertEquals(ElementPatchMode.APPEND, ElementPatchModeConverter().convert("append"))
    }
}
