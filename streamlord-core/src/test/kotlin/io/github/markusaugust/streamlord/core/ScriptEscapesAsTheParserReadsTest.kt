package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.Wire
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Each case in `conformance/dom/script-escapes.json` is a script body run through parse5 by
 * `conformance/generate/dom.mjs`, which tokenizes as the browser's DOMParser does: once inside
 * the wrapper the client puts a patch of elements in (`<body><template><script>…</script></template></body>`,
 * patchElements.ts), and once as the whole document the client parses instead when `/<\/body>/`,
 * `/<\/head>/` or `/<\/html>/` matches the text. `template.whole` says the script element held
 * the body as written and nothing followed it; `document` says where the script went and what
 * the body the client would morph in held; `matches` is what the three expressions said of the
 * raw text. The bodies that need no escape are their own escape, so the escape of every case is a
 * case too.
 */
class ScriptEscapesAsTheParserReadsTest {
    private val cases = JsonParser.parse(File("../conformance/dom/script-escapes.json").readText()) as JsonArray
    private val byInput = cases.objects().associateBy { it.string("input")!! }

    // What JavaScript reads from the escaped literal: `\/` is `/`, `\xHH` the character.
    private fun readBack(escaped: String) =
        escaped
            .replace("\\/", "/")
            .replace(Regex("\\\\x([0-9a-fA-F]{2})")) {
                it.groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            }

    @Test
    fun `an escaped body is one script element to the parser, holding the body as written`() {
        assertEquals(29, cases.size)
        for (input in byInput.keys) {
            val escaped = Wire.escapeScriptBody(input)
            val parsed = byInput[escaped] ?: error("the escape of $input is not in the fixture: $escaped")
            assertTrue(parsed.obj("template")!!.boolean("whole")!!, "not kept whole: ${parsed.obj("template")}")
            assertEquals(escaped, parsed.obj("document")!!.string("script"), escaped)
        }
    }

    @Test
    fun `an escaped body reads back as the body that was written`() {
        val plain = byInput.keys.filter { readBack(it) == it }
        assertEquals(16, plain.size)
        for (input in plain) assertEquals(input, readBack(Wire.escapeScriptBody(input)), input)
    }

    @Test
    fun `the client's test for a whole document never matches an escaped body`() {
        for (input in byInput.keys) {
            val matches = byInput.getValue(Wire.escapeScriptBody(input)).obj("matches")!!
            for (tag in listOf("body", "head", "html")) assertFalse(matches.boolean(tag)!!, "</$tag> matched in the escape of $input")
        }
    }

    // A body the parser cuts short, runs past its end or reads as a document is never sent as
    // written. The fixture has each kind, or the test would hold nothing.
    @Test
    fun `a body the parser or the client would read wrong is changed`() {
        val wrong =
            cases.objects().filter { case ->
                !case.obj("template")!!.boolean("whole")!! || listOf("body", "head", "html").any { case.obj("matches")!!.boolean(it)!! }
            }
        assertEquals(12, wrong.size)
        for (case in wrong) {
            val input = case.string("input")!!
            assertNotEquals(input, Wire.escapeScriptBody(input), input)
        }
    }
}
