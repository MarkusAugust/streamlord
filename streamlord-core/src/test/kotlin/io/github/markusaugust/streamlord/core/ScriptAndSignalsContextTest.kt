package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.SignalsResponse
import io.github.markusaugust.streamlord.core.domain.Wire
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Streamlord writes where the browser runs it. Datastar's client reads signals with
 * JSON.parse and runs anything JSON.parse refuses as JavaScript, and a script element holding
 * `<!--` and then `<script` runs past its own end tag.
 */
class ScriptAndSignalsContextTest {
    @Test
    fun `signals that are not one JSON object are refused`() {
        for (text in listOf("{a: (window.pwned = 1)}", "[1, 2]", "\"x\"", "{\"a\": 1} {}", "{\"a\": 1", "{\"a\": 01}", "")) {
            assertFailsWith<DatastarEventValidationException>(text) { PatchSignals(text) }
            assertFailsWith<DatastarEventValidationException>(text) { SignalsResponse(text) }
        }
        val deep = "{\"a\":" + "[".repeat(5000) + "]".repeat(5000) + "}"
        assertEquals(deep, PatchSignals(deep).signals)
        assertEquals(
            " {\"a\": [1, -2.5e3, true, null, \"\\u00e9\\n\"], \"b\": {}} ",
            PatchSignals(" {\"a\": [1, -2.5e3, true, null, \"\\u00e9\\n\"], \"b\": {}} ").signals,
        )
    }

    // Measured against the 1.0.4 store: __proto__ throws midway through a batch it never closes,
    // constructor with an object under it reaches Object.prototype, and a dotted key is one key
    // that $a.b does not reach and a request drops.
    @Test
    fun `signals holding a key the client cannot hold are refused`() {
        val escapedProto = "{\"__pro" + "\\" + "u0074o__\": {}}"
        val escapedDot = "{\"a" + "\\" + "u002eb\": 1}"
        val refused =
            listOf(
                "{\"__proto__\": {\"x\": 1}}",
                "{\"__proto__\": 1}",
                "{\"constructor\": {\"prototype\": {\"x\": 1}}}",
                "{\"toString\": {\"name\": 1}}",
                "{\"a\": [{\"b\": {\"valueOf\": {}}}]}",
                "{\"a.b\": 1}",
                "{\"a\": {\"b.c\": 1}}",
                escapedProto,
                escapedDot,
            )
        for (text in refused) {
            assertFailsWith<DatastarEventValidationException>(text) { PatchSignals(text) }
            assertFailsWith<DatastarEventValidationException>(text) { SignalsResponse(text) }
        }
        // A plain value or an array under a name every object has is held as the client holds it.
        val fine =
            "{\"constructor\": 1, \"team\": {\"constructor\": \"Ferrari\"}, " +
                "\"toString\": [{}], \"valueOf\": 2, \"prototype\": {\"x\": 1}}"
        assertEquals(fine, PatchSignals(fine).signals)
        // Text that is not JSON is told so, before any of its keys.
        val broken = assertFailsWith<DatastarEventValidationException> { PatchSignals("{\"a.b\": {\"constructor\": {}}") }
        assertTrue("one JSON object" in broken.message.orEmpty(), broken.message)
    }

    // Each key is read once. Read in proportion to the rest of the text, 200 000 keys took nine
    // seconds; read once, ten milliseconds. A busy machine stretches any time, so the test compares
    // two texts in the same run that end in the same long string: read once, two thousand keys in
    // front of it add next to nothing; read to the end, each adds the whole string again.
    @Test
    fun `checking the keys takes time in proportion to the text`() {
        val tail = "\"tail\":\"" + "x".repeat(4_000_000) + "\"}"
        val one = "{\"k\":1,$tail"
        val many = (1..2_000).joinToString(",", "{", ",") { "\"k$it\":$it" } + tail
        assertEquals(many, PatchSignals(many).signals)

        fun nanos(text: String): Long {
            val started = System.nanoTime()
            PatchSignals(text)
            return System.nanoTime() - started
        }
        // Warmed up, then the fastest of several, taken in turn, so both see the same machine. Read
        // once, the two come out about even; read to the end, the keys made it some 300 times slower.
        repeat(3) {
            nanos(one)
            nanos(many)
        }
        var fastestOne = Long.MAX_VALUE
        var fastestMany = Long.MAX_VALUE
        repeat(10) {
            fastestOne = minOf(fastestOne, nanos(one))
            fastestMany = minOf(fastestMany, nanos(many))
        }
        val ratio = fastestMany.toDouble() / fastestOne
        // What this does not catch: a cost that grows with the square of the keys alone, each key
        // checked against those before it, say. Two thousand keys are too few beside the long string
        // for that to show.
        assertTrue(ratio < 50, "two thousand keys made it $ratio times slower: $fastestOne ns, then $fastestMany ns")
    }

    @Test
    fun `a JsonNumber holds a JSON number`() {
        for (text in listOf("(window.pwned = 1)", "1e", "01", "+1", ".5", "NaN", "Infinity", "1_000", "")) {
            assertFailsWith<IllegalArgumentException>(text) { JsonNumber(text) }
        }
        for (text in listOf("0", "-0", "12", "-1.5", "1e400", "2E-3")) assertEquals(text, JsonNumber(text).text)
    }

    @Test
    fun `a JSON string never opens a script comment`() {
        val written = JsonWriter.writeString("/search?q=<!--<script>")
        assertFalse("<!" in written, written)
        assertEquals("\"/search?q=\\u003c!--<script>\"", written)
    }

    @Test
    fun `a script body neither ends early nor runs past its end`() {
        assertEquals("a = '<\\/script>'", Wire.escapeScriptBody("a = '</script>'"))
        for (tag in listOf("body", "head", "html", "BODY")) {
            assertFalse("</$tag" in Wire.escapeScriptBody("a = '</$tag>'"), tag)
        }
        val commented = Wire.escapeScriptBody("a = '<!--<script>'; b = '</script>'")
        assertFalse(Regex("<script[\\s/>]", RegexOption.IGNORE_CASE).containsMatchIn(commented.substringAfter("<!--")), commented)
        // Without <!--, a <script inside a string is harmless and stays as written.
        assertEquals("a = '<script>'", Wire.escapeScriptBody("a = '<script>'"))
        assertTrue(
            "<\\/body>" in
                ExecuteScript("x = '</body>'").let {
                    io.github.markusaugust.streamlord.core.protocol.SseEncoder
                        .encode(it)
                },
        )
    }
}
