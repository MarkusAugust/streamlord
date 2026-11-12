package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SseDecoderTest {
    /**
     * Without a selector, `outer` and `replace` take the elements of what was sent
     * (`newContent.children` in patchElements.ts), so a comment or text alone patches nothing and
     * is no event; the first start tag makes it one.
     */
    @Test
    fun `an outer patch without a selector is an event only when it holds an element`() {
        fun decoded(elements: String) = SseDecoder.decode("event: datastar-patch-elements\ndata: elements $elements\n\n")
        assertEquals(emptyList(), decoded("<!-- c -->"))
        assertEquals(emptyList(), decoded("<!-- <p> -->"))
        assertEquals(emptyList(), decoded("<!-- <p id=\"x\">"))
        assertEquals(emptyList(), decoded("<!-- <svg></body></svg> -->"))
        assertEquals(emptyList(), decoded("a < b"))
        assertEquals(emptyList(), decoded("hello"))
        assertEquals(listOf(PatchElements("<p id=\"x\">x</p>")), decoded("<p id=\"x\">x</p>"))
        assertEquals(listOf(PatchElements("<!-- c --><p id=\"x\">x</p>")), decoded("<!-- c --><p id=\"x\">x</p>"))
        // A document end tag has the client read the whole text as a document and patch the page's body.
        assertEquals(listOf(PatchElements("text</body>")), decoded("text</body>"))
        assertEquals(listOf(PatchElements("<!-- </body> -->")), decoded("<!-- </body> -->"))
    }

    /** A stream may carry somebody else's events; guessing at them would be worse than silence. */
    @Test
    fun `an event Datastar does not define is skipped`() {
        val wire = "event: something-else\ndata: whatever\n\n"

        assertEquals(emptyList(), SseDecoder.decode(wire))
        assertEquals(1, SseDecoder.messages(wire).size)
    }

    @Test
    fun `carriage returns are tolerated`() {
        val wire = "event: datastar-patch-signals\r\ndata: signals {\"a\":1}\r\n\r\n"

        assertEquals(PatchSignals("""{"a":1}"""), SseDecoder.decodeOne(wire))
    }

    @Test
    fun `a malformed frame is reported rather than dropped`() {
        assertFailsWith<DatastarEventValidationException> {
            SseDecoder.decode("event: datastar-patch-elements\ndata: mode sideways\ndata: selector #a\n\n")
        }
        assertFailsWith<DatastarEventValidationException> {
            SseDecoder.decode("event: datastar-patch-signals\nretry: soon\ndata: signals {}\n\n")
        }
    }

    @Test
    fun `an empty stream decodes to nothing`() {
        assertEquals(emptyList(), SseDecoder.decode(""))
        assertEquals(emptyList(), SseDecoder.messages(""))
        assertNull(SseDecoder.messages("\n\n\n").firstOrNull())
    }
}
