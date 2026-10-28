package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.SignalNames
import io.github.markusaugust.streamlord.core.domain.clientRewritten
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holds the core to `conformance/client/signal-store.json`, the output of the Datastar 1.0.4
 * client's `mergePatch` and `filtered` (engine/signals.ts), `pathToObj` (utils/paths.ts) and
 * `isStringTrue` (utils/text.ts) run by `conformance/generate/client.mjs`, and to
 * `conformance/client/expressions.json`, the body `genRx` (engine/engine.ts) compiles.
 */
class ClientOracleTest {
    private fun group(name: String): List<JsonObject> =
        JsonParser
            .parseObject(File("../conformance/client/signal-store.json").readText())
            .obj("cases")!!
            .array(name)!!
            .objects()

    /** `held` is `JSON.stringify` of the client's root after `mergePatch` of the store; the fold has to arrive at the same text. */
    @Test
    fun `holds a store as the client's mergePatch does`() {
        val cases = group("filtered")
        assertEquals(26, cases.size)
        for (case in cases) {
            val store = JsonParser.parseObject(case.string("store")!!)
            assertEquals(case.string("held"), mergePatchAsClient(JsonObject.EMPTY, store).toJson(), case.string("store"))
        }
    }

    /**
     * `filtered` runs before every request that sends signals. It throws or writes into a built-in
     * object exactly where [SignalNames.keyProblem] names a key, and a key holding a dot is gone
     * from what it sends.
     */
    @Test
    fun `knows the keys a request's filtered loses`() {
        for (case in group("filtered")) {
            val store = JsonParser.parseObject(case.string("store")!!)
            val lost = case.string("error") != null || case.array("pollutes")!!.isNotEmpty()
            val flagged =
                store.any { (key, value) ->
                    '.' !in key && SignalNames.keyProblem(key, objectUnder = value is JsonObject) != null
                }
            assertEquals(lost, flagged, store.toJson())
            val sent = case.string("filtered")?.let(JsonParser::parseObject)
            for (key in store.keys.filter { '.' in it }) {
                assertNotNull(SignalNames.keyProblem(key, objectUnder = false), key)
                if (sent != null) assertTrue(key !in sent, "$key is sent whole: ${case.string("filtered")}")
            }
        }
    }

    /** Whether [obj] holds [value] at the own keys [parts]. */
    private fun holds(
        obj: JsonValue,
        parts: List<String>,
        value: JsonValue,
    ): Boolean {
        var node: JsonValue = obj
        for (part in parts) node = (node as? JsonObject)?.get(part) ?: return false
        return node == value
    }

    /**
     * `pathToObj` follows a path without a guard. Where a part is a property every object has,
     * with more of the path under it, it writes into the built-in one or throws, and
     * [SignalNames.keyProblem] names that part; a path it holds whole is one it clears.
     */
    @Test
    fun `knows which paths pathToObj writes into a built-in object`() {
        val cases = group("pathToObj")
        assertEquals(34, cases.size)
        for (case in cases) {
            val paths = case.array("paths")!!.map { it as JsonArray }
            val result = case.string("result")?.let(JsonParser::parseObject)
            val held = result != null && paths.all { holds(result, (it[0] as JsonString).value.split('.'), it[1]) }
            val harmless = held && case.array("pollutes")!!.isEmpty()
            val single = paths.singleOrNull() ?: continue
            val parts = (single[0] as JsonString).value.split('.')
            val problem =
                parts.withIndex().firstNotNullOfOrNull { (i, part) ->
                    SignalNames.keyProblem(
                        part,
                        objectUnder =
                            i < parts.lastIndex,
                    )
                }
            if (harmless) assertNull(problem, single.toJson()) else assertNotNull(problem, single.toJson())
        }
    }

    /** A path given a value and then a path under it: the client throws, and [SignalNames.nest] refuses the pair. */
    @Test
    fun `refuses what pathToObj throws on`() {
        for (case in group("pathToObj")) {
            if (case.string("error") == null || case.array("paths")!!.size < 2) continue
            val pairs = case.array("paths")!!.map { (it as JsonArray).let { p -> (p[0] as JsonString).value to p[1] } }
            assertFailsWith<DatastarEventValidationException>(case.array("paths")!!.toJson()) { SignalNames.nest(pairs) }
        }
    }

    /** `isStringTrue` over a value, against the decoder's reading of the `onlyIfMissing` line that carries it. */
    @Test
    fun `reads a flag as isStringTrue does`() {
        val cases = group("isStringTrue")
        assertEquals(26, cases.size)
        var onTheWire = 0
        for (case in cases) {
            // Only a string goes on the wire, and a line break ends the data line.
            val value = (case["value"] as? JsonString)?.value ?: continue
            if ('\n' in value || '\r' in value) continue
            val text = "event: datastar-patch-signals\ndata: onlyIfMissing $value\ndata: signals {}\n\n"
            val event = SseDecoder.decodeOne(text) as PatchSignals
            assertEquals(case.boolean("isStringTrue"), event.onlyIfMissing, value.map { it.code }.toString())
            onTheWire++
        }
        assertEquals(21, onTheWire)
    }

    private val rewrites: List<JsonObject> =
        JsonParser
            .parseObject(File("../conformance/client/expressions.json").readText())
            .obj("cases")!!
            .array("rewrites")!!
            .objects()

    /** What JavaScript's `String.prototype.trim` takes off: its white space and line terminators. */
    private fun jsTrim(text: String): String {
        val space =
            "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" + (0x2000..0x200A).map { it.toChar() }.joinToString("")
        return text.trim { it in space }
    }

    /** With `returnsValue` false the client compiles the trimmed text as its one pattern rewrote it, which is what the scan writes. */
    @Test
    fun `finds the references genRx rewrites`() {
        val cases = rewrites.filter { it.boolean("returnsValue") == false }
        assertEquals(161, cases.size)
        for (case in cases) {
            val expression = case.string("expression")!!
            assertEquals(case.string("js"), clientRewritten(jsTrim(expression)).js, expression)
        }
    }

    /**
     * What [SignalNames.withoutReferenceHyphens] and [SignalNames.referenceAsSubtraction] say of a
     * name, each against the body the client compiles for `$` and that name: the part of it that
     * shows the hyphen is read as a key, as a signal of its own, or as a minus, or is template text.
     * The table is shared with the editor's clientOracle.test.ts.
     */
    @Test
    fun `reads a reference's hyphens as genRx does`() {
        val names =
            JsonParser
                .parseObject(File("../editors/vscode/test/fixtures/reference-hyphens.json").readText())
                .array("cases")!!
                .objects()
        assertEquals(20, names.size)
        for (row in names) {
            val name = row.string("name")!!
            val case = rewrites.single { it.string("expression") == "\$$name" && it.boolean("returnsValue") == false }
            assertEquals(true, case.boolean("compiles"), name)
            assertTrue(row.string("shows")!! in case.string("js")!!, "$name: ${case.string("js")}")
            assertEquals(row.string("fixed"), SignalNames.withoutReferenceHyphens(name), name)
            assertEquals(row.string("subtraction"), SignalNames.referenceAsSubtraction(name), name)
        }
    }
}
