package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The decoder is what a test trusts to say what the browser received, so it has to read a stream
 * the way the browser does. Each case here is one where it used to be more forgiving, or less,
 * than the SSE grammar and the Datastar client.
 */
class SseDecoderAsTheBrowserReadsTest {
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
        assertEquals(1, SseDecoder.decode(signals + "\nretry: soon").size, "a line still arriving is not read yet")
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

    @Test
    fun `an empty id is no id`() {
        assertNull(SseDecoder.decodeOne("id:\n" + signals + "\n").eventId)
    }
}
