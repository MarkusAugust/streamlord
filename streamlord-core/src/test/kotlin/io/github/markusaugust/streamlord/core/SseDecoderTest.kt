package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SseDecoderTest {
    /**
     * Every golden file the encoder is held to, read back.
     *
     * The encoder already has to match the official bytes; this asks the harder question of
     * whether those bytes still mean what they were built from. A field the encoder writes and
     * the decoder forgets is invisible to a comparison of text, and it is exactly the kind of
     * thing an assertion library would then quietly fail to see.
     */
    @TestFactory
    fun `every golden frame survives the round trip`(): List<DynamicTest> =
        listOf("get", "post").flatMap { kind ->
            val dir = File("src/test/resources/golden/$kind")
            assertTrue(dir.isDirectory, "golden directory missing: ${dir.absolutePath}")
            dir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.map { case ->
                DynamicTest.dynamicTest("$kind/${case.name}") {
                    val wire = File(case, "output.txt").readText()
                    val declared = JsonParser.parseObject(File(case, "input.json").readText()).array("events")!!.objects()
                    val decoded = SseDecoder.decode(wire)

                    // Every event the official fixture declares has to come back out of the bytes.
                    assertEquals(declared.size, decoded.size, "events lost or invented reading $kind/${case.name}")

                    // And the meaning has to survive a second trip, which is where a field the
                    // encoder writes and the decoder forgets would show itself.
                    assertEquals(decoded, SseDecoder.decode(decoded.joinToString("") { SseEncoder.encode(it) }))
                }
            }
        }

    @Test
    fun `an element patch comes back whole`() {
        val sent =
            PatchElements(
                elements = "<li>one</li>\n<li>two</li>",
                selector = "#feed",
                mode = ElementPatchMode.APPEND,
                namespace = ElementNamespace.SVG,
                useViewTransition = true,
                viewTransitionSelector = "#card",
                eventId = "17",
                retry = 2_000.milliseconds,
            )

        assertEquals(sent, SseDecoder.decodeOne(SseEncoder.encode(sent)))
    }

    @Test
    fun `a signal patch comes back whole`() {
        val sent = PatchSignals("""{"count":1}""", onlyIfMissing = true, eventId = "4")

        assertEquals(sent, SseDecoder.decodeOne(SseEncoder.encode(sent)))
    }

    /** The specification says a script is a patch of elements, so that is what returns. */
    @Test
    fun `a script comes back as the element patch it always was`() {
        val decoded = SseDecoder.decodeOne(SseEncoder.encode(ExecuteScript("alert(1)")))

        assertTrue(decoded is PatchElements, decoded.toString())
        val elements = decoded.elements
        assertTrue(elements != null && elements.contains("alert(1)"), elements.orEmpty())
        assertEquals("body", decoded.selector)
    }

    @Test
    fun `several events in one stream come back in order`() {
        val wire =
            SseEncoder.encode(PatchSignals("""{"a":1}""")) +
                SseEncoder.encode(PatchElements("<p>x</p>", selector = "#a")) +
                SseEncoder.encode(PatchSignals("""{"b":2}"""))

        val decoded = SseDecoder.decode(wire)

        assertEquals(3, decoded.size)
        assertEquals(listOf(PatchSignals::class, PatchElements::class, PatchSignals::class), decoded.map { it::class })
    }

    @Test
    fun `a heartbeat is a message and not an event`() {
        val wire = SseEncoder.comment("keep-alive") + SseEncoder.encode(PatchSignals("""{"a":1}"""))

        assertEquals(1, SseDecoder.decode(wire).size, "a comment is not a Datastar event")
        assertEquals(2, SseDecoder.messages(wire).size)
        assertEquals(listOf("keep-alive"), SseDecoder.messages(wire)[0].comments)
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
