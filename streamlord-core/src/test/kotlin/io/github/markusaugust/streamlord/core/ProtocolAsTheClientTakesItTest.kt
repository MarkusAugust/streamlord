package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.ScriptResponse
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.core.protocol.SseFrame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Datastar 1.0.4's client takes from a stream or a response, and what it fails on. */
class ProtocolAsTheClientTakesItTest {
    @Test
    fun `a frame built by hand can send the empty id that drops last-event-id`() {
        assertEquals("event: x\nid: \n\n", SseFrame("x", id = "").render())
    }

    // fetch.ts:118 gathers the data lines in a plain object; a key Object.prototype has finds no
    // list there, and the client throws and reconnects.
    @Test
    fun `a data key every object already has fails the decode`() {
        for (key in listOf("constructor", "__proto__", "toString", "hasOwnProperty")) {
            assertFailsWith<DatastarEventValidationException>(key) {
                SseDecoder.decode("event: datastar-patch-elements\ndata: elements <p id=\"a\"></p>\ndata: $key x\n\n")
            }
        }
    }

    @Test
    fun `an empty selector is none, as the client reads it`() {
        val event = SseDecoder.decode("event: datastar-patch-elements\ndata: selector \ndata: elements <p id=\"a\"></p>\n\n").single()
        assertNull((event as PatchElements).selector)
    }

    @Test
    fun `an inner patch of nothing empties its target, and a remove response needs no body`() {
        val clear = PatchElements("", selector = "#list", mode = ElementPatchMode.INNER)
        assertTrue("data: elements \n" in SseEncoder.encode(clear), SseEncoder.encode(clear))
        ElementsResponse("", selector = "#list", mode = ElementPatchMode.INNER)
        ElementsResponse("", selector = "#gone", mode = ElementPatchMode.REMOVE)
        assertFailsWith<DatastarEventValidationException> { ElementsResponse("", selector = "#a") }
    }

    // A response header reaches the client as Latin-1.
    @Test
    fun `response headers carry only what survives as Latin-1`() {
        assertFailsWith<DatastarEventValidationException> { ElementsResponse("<p>x</p>", selector = "#café") }
        assertFailsWith<DatastarEventValidationException> { ElementsResponse("<p>x</p>", selector = "#a\u0001") }
        val header = ScriptResponse("run()", mapOf("data-k" to "vé日")).headers.values.single()
        assertTrue(header.all { it in ' '..'~' }, header)
        assertEquals("{\"data-k\":\"v\\u00e9\\u65e5\"}", header)
    }

    @Test
    fun `a surrogate without its half is escaped, and a pair is kept`() {
        assertEquals("\"a\\ud800b\"", JsonWriter.writeString("a\uD800b"))
        assertEquals("\"😀\"", JsonWriter.writeString("😀"))
    }

    @Test
    fun `a negative zero keeps its sign`() {
        assertEquals(Double.NEGATIVE_INFINITY, 1 / JsonNumber("-0").toKotlin().toDouble())
    }

    @Test
    fun `the guard decodes the references the browser decodes`() {
        for (html in listOf(
            """<b data-text="&plus;&plus;"></b>""",
            """<b data-text="&#43&#43"></b>""",
            """<b data-text="&equals;"></b>""",
        )) {
            assertFailsWith<InterpolatedExpressionException>(html) { ElementsGuard.check(html) }
        }
    }

    @Test
    fun `an effect attribute beside autoRemove is refused`() {
        assertFailsWith<DatastarEventValidationException> { ExecuteScript("run()", attributes = mapOf("data-effect" to "x")) }
        ExecuteScript("run()", autoRemove = false, attributes = mapOf("data-effect" to "x"))
    }

    @Test
    fun `redirect refuses a URL the browser would run`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                Streamlord(heartbeat = null).stream(BufferedSseSink(), null) { redirect("javascript:alert(1)") }
            }
            Streamlord(heartbeat = null).stream(BufferedSseSink(), null) { redirect("/next?q=1") }
        }
}
