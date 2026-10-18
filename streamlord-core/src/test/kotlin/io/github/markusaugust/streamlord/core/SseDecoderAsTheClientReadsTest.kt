package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decoder is what a test trusts to say what the client received, so it has to read a stream
 * the way Datastar's client does (fetch.ts in 1.0.4). Each case here is one where it used to be
 * more forgiving, or less.
 */
class SseDecoderAsTheClientReadsTest {
    private val signals = "event: datastar-patch-signals\ndata: signals {\"a\":1}\n"

    @Test
    fun `a message with no blank line after it is not an event`() {
        assertEquals(emptyList(), SseDecoder.decode(signals))
        assertEquals(1, SseDecoder.decode(signals + "\n").size)
    }

    @Test
    fun `a trailing comment is still a message`() {
        assertEquals(listOf("keep-alive"), SseDecoder.messages(": keep-alive\n").single().comments)
    }

    @Test
    fun `a line may end in a bare carriage return`() {
        assertEquals(PatchSignals("""{"a":1}"""), SseDecoder.decodeOne("event: datastar-patch-signals\rdata: signals {\"a\":1}\r\r"))
    }

    @Test
    fun `one leading byte order mark is not part of the stream`() {
        assertEquals(1, SseDecoder.decode("\uFEFF" + signals + "\n").size)
    }

    @Test
    fun `a mode is matched as written, because the client matches it so`() {
        assertFailsWith<DatastarEventValidationException> {
            SseDecoder.decode("event: datastar-patch-elements\ndata: selector #a\ndata: mode APPEND\ndata: elements <li>x</li>\n\n")
        }
    }

    @Test
    fun `a signal patch with no signals is malformed`() {
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode("event: datastar-patch-signals\n\n") }
    }

    @Test
    fun `an event that is not Datastar's is skipped whatever its data holds`() {
        assertEquals(emptyList(), SseDecoder.decode("event: other\ndata: x\n\n"))
    }

    // The client takes id and retry from every message, Datastar's or not (fetch.ts:421, :427).
    @Test
    fun `id and retry are checked on every message, since the client takes them from every one`() {
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode("event: other\nretry: soon\ndata: x\n\n") }
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode("event: other\nid: a\u0000b\ndata: x\n\n") }
    }

    // Per line, also in a message no blank line ever ends, as long as the line itself has ended.
    @Test
    fun `id and retry in an unfinished message are checked too`() {
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode(signals + "\nid: 9\nretry: soon\n") }
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode(signals + "\nid: a\u0000b\r\n") }
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode(signals + "\nretry: soon\r") }
        assertEquals(1, SseDecoder.decode(signals + "\nretry: soon").size, "a line still arriving is not read yet")
    }

    // The client keeps the last of each, finished message or not.
    @Test
    fun `the last retry of a message is the one judged`() {
        assertEquals(1, SseDecoder.decode("retry: soon\nretry: 5\n" + signals + "\n").size)
        assertEquals(1, SseDecoder.decode(signals + "\nretry: soon\nretry: 5\n").size)
    }

    // The id goes back as the last-event-id header, which only printable ASCII survives; the rule
    // is the one an event is built with, on every message.
    @Test
    fun `an id that would not come back as written is malformed`() {
        for (id in listOf("€1", "ø1", "a\u0001b", "a\u007Fb", "7 ")) {
            assertFailsWith<DatastarEventValidationException>(id) { SseDecoder.decode("id: $id\n" + signals + "\n") }
            assertFailsWith<DatastarEventValidationException>(id) { SseDecoder.decode("event: other\nid: $id\ndata: x\n\n") }
        }
        assertEquals("a b", SseDecoder.decodeOne("id: a b\n" + signals + "\n").eventId)
    }

    @Test
    fun `a retry over Int MAX_VALUE milliseconds is malformed on every message`() {
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode("event: other\nretry: 2147483648\ndata: x\n\n") }
    }

    // The client reads both flags with trim() === 'true' (utils/text.ts:24).
    @Test
    fun `a flag with spaces around true is true`() {
        val signals = SseDecoder.decodeOne("event: datastar-patch-signals\ndata: signals {}\ndata: onlyIfMissing true \n\n")
        val elements =
            SseDecoder.decodeOne("event: datastar-patch-elements\ndata: elements <p id=\"a\"></p>\ndata: useViewTransition  true\n\n")

        assertTrue((signals as PatchSignals).onlyIfMissing)
        assertTrue((elements as PatchElements).useViewTransition)
    }

    // Digits only, and not zero: zero has the client reconnect at once without end. Checked on
    // every message alike.
    @Test
    fun `a retry of zero or with a sign is malformed on every message`() {
        for (retry in listOf("0", "-5", "+5")) {
            assertFailsWith<DatastarEventValidationException>(retry) { SseDecoder.decode("retry: $retry\n" + signals + "\n") }
            assertFailsWith<DatastarEventValidationException>(retry) { SseDecoder.decode("event: other\nretry: $retry\ndata: x\n\n") }
        }
    }

    // Each field is decoded on its own and loses one leading mark; a second one is part of the name.
    @Test
    fun `two byte order marks before the first field leave one in its name`() {
        assertEquals(emptyList(), SseDecoder.decode("\uFEFF\uFEFF" + signals + "\n"))
    }

    @Test
    fun `an id is judged on a message that is not Datastar's too`() {
        val message = SseDecoder.messages("event: other\nid: a\u0000b\ndata: x\n\n").single()

        assertFailsWith<DatastarEventValidationException> { SseDecoder.event(message) }
    }

    // The client decodes each field and each value on its own, and each decode drops a leading
    // byte order mark (fetch.ts:406-408).
    @Test
    fun `a byte order mark before a field or a value is dropped`() {
        assertEquals(PatchSignals("{}"), SseDecoder.decodeOne("\uFEFFevent: datastar-patch-signals\ndata: signals {}\n\n"))
        assertEquals(PatchSignals("{}"), SseDecoder.decodeOne("event: \uFEFFdatastar-patch-signals\ndata: \uFEFFsignals {}\n\n"))
    }

    // The client stores it and sends it back as last-event-id, which a header cannot carry, so
    // every reconnect fails (fetch.ts:554, :683).
    @Test
    fun `an id with a NUL in it is malformed`() {
        assertFailsWith<DatastarEventValidationException> { SseDecoder.decode("id: a\u0000b\n" + signals + "\n") }
    }

    // fetch.ts:403 reads a field only up to a colon; a line without one is skipped.
    @Test
    fun `a line without a colon is ignored`() {
        assertEquals(
            PatchSignals("{}"),
            SseDecoder.decodeOne("event: datastar-patch-signals\nevent\ndata: signals {}\n\n"),
        )
        assertEquals("7", SseDecoder.decodeOne("id: 7\nid\n" + signals + "\n").eventId)
    }

    // The client splits a data line at its first space; without one it reads the key as the line
    // minus its last character (fetch.ts:115-117). No encoder writes such a line.
    @Test
    fun `a data line with no space after its key is malformed`() {
        assertFailsWith<DatastarEventValidationException> {
            SseDecoder.decode("event: datastar-patch-elements\ndata: selector #x\ndata: mode remove\ndata: elements\n\n")
        }
    }

    /**
     * Each frame in `client-decoder-cases.json` was run through Datastar 1.0.4 built from source in
     * happy-dom, on a page holding `<div id="wrap"><div id="target">old</div></div>` and the
     * signal `keep: 1`, with `startViewTransition` counting its calls. None made the client throw,
     * so none may make the decoder throw, and each must decode to what the client did: no event
     * where the page did not change, a removal where the target went and nothing took its place,
     * the elements as sent where the client put them in, a no-break space or U+001C among them,
     * the view transition the client started, and the store it ended up with. The one exception,
     * documented on [SseDecoder.decode]: a frame that changes nothing but asks for a view
     * transition, which the client starts around nothing, is no event.
     */
    @Test
    fun `decodes each frame to what the client did with it`() {
        val cases = JsonParser.parse(javaClass.getResource("/client-decoder-cases.json")!!.readText()) as JsonArray
        assertEquals(83, cases.size)

        // White space as HTML counts it, which the page shows as nothing between elements.
        fun shown(html: String) = html.filterNot { it in " \t\n\u000C\r" }
        val untouched = "<divid=\"wrap\"><divid=\"target\">old</div></div>"
        for (case in cases.objects()) {
            val name = case.string("name")!!
            val event = SseDecoder.decode(case.string("sse")!!).singleOrNull()
            val body = case.string("body")!!
            val raw = case["argsRaw"] as JsonObject
            if (raw.containsKey("signals")) {
                val patch = assertIs<PatchSignals>(event, name)
                val store =
                    mergePatchAsClient(JsonParser.parseObject("""{"keep":1}"""), JsonParser.parse(patch.signals), patch.onlyIfMissing)
                assertEquals(case["signals"], store, name)
                continue
            }
            if (shown(body) == untouched) {
                assertNull(event, name)
                continue
            }
            val patch = assertIs<PatchElements>(event, name)
            assertEquals(case.int("viewTransitions") == 1, patch.useViewTransition, name)
            if (shown(body) == "<divid=\"wrap\"></div>") {
                assertEquals(ElementPatchMode.REMOVE, patch.mode, name)
                assertEquals("#target", patch.selector, name)
            } else {
                assertEquals(raw.string("elements") ?: "", patch.elements, name)
            }
        }
    }

    @Test
    fun `an empty id is no id`() {
        assertNull(SseDecoder.decodeOne("id:\n" + signals + "\n").eventId)
    }
}
