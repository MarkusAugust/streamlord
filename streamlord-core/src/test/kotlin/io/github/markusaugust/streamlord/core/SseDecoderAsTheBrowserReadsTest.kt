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
    fun `an event that is not Datastar's is skipped whatever its fields hold`() {
        assertEquals(emptyList(), SseDecoder.decode("event: other\nretry: soon\ndata: x\n\n"))
    }

    @Test
    fun `an empty id is no id`() {
        assertNull(SseDecoder.decodeOne("id:\n" + signals + "\n").eventId)
    }
}
