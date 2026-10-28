package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.PROTO_REFUSED
import io.github.markusaugust.streamlord.core.json.STORE_PROTO_REFUSED
import io.github.markusaugust.streamlord.core.json.SignalPatchException
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Each case in `conformance/dom/store-cases.json` is run through the real Datastar 1.0.4
 * `mergePatch` (engine/signals.ts, the free bundle in happy-dom, by `conformance/generate/dom.mjs`),
 * and `client` is `JSON.stringify` of its root afterwards. The fold here has to arrive at the same
 * text.
 */
class MergePatchAsClientTest {
    @Test
    fun `holds what the client holds`() {
        val cases = JsonParser.parse(File("../conformance/dom/store-cases.json").readText()) as JsonArray
        assertEquals(27, cases.size)
        for (case in cases.filterIsInstance<JsonObject>()) {
            var store = JsonObject.EMPTY
            for (step in case.array("steps")!!.objects()) {
                store = mergePatchAsClient(store, JsonParser.parse(step.string("signals")!!), step.boolean("onlyIfMissing")!!)
            }
            assertEquals(case.string("client"), store.toJson(), case.string("name"))
        }
    }

    /**
     * Each scenario in `conformance/dom/merge-differential.json` is run through the 1.0.4 client's
     * `mergePatch` (engine/signals.ts) in happy-dom by `conformance/generate/dom.mjs`, a fresh store
     * per scenario, and `client` is `JSON.stringify` of the root after each step, or `THROWS` and
     * the root once the step threw. The audit's 317 scenarios (17 by hand, 300 at random with seed 7) plus the cases
     * around `length`, a throw partway through a patch and key order, a string patch of surrogate
     * pairs, and keys named `__proto__` or after what `Object.prototype` holds. A scenario with a `store`
     * starts from that text as parsed here, out of JavaScript's key order, where the client starts
     * from `JSON.parse` of it.
     *
     * A step with `refused` is one this fold refuses on purpose: a key `__proto__` anywhere in the
     * patch. Its `client` is still what the client shows for the patch as written, and its `kept`
     * is the client's root after the same patch cut before the key that holds the `__proto__`; the
     * client takes the rest of the scenario from there, in a fresh store.
     */
    @Test
    fun `folds each step as the client's own mergePatch does`() {
        val scenarios = JsonParser.parse(File("../conformance/dom/merge-differential.json").readText()) as JsonArray
        assertEquals(366, scenarios.size)
        for (scenario in scenarios.objects()) {
            var store = scenario.string("store")?.let(JsonParser::parseObject) ?: JsonObject.EMPTY
            for ((i, step) in scenario.array("steps")!!.objects().withIndex()) {
                val patch = JsonParser.parse(step.string("signals")!!)
                val got =
                    try {
                        mergePatchAsClient(store, patch, step.boolean("onlyIfMissing")!!).also { store = it }.toJson()
                    } catch (e: SignalPatchException) {
                        store = e.store
                        val refused = step.boolean("refused") == true
                        assertEquals(refused, e.refused, "${scenario.string("name")} step $i")
                        if (refused) assertEquals(PROTO_REFUSED, e.message)
                        "THROWS " + e.store.toJson()
                    }
                val expected = step.string("kept")?.let { "THROWS $it" } ?: step.string("client")
                assertEquals(expected, got, "${scenario.string("name")} step $i")
            }
        }
    }

    // Measured against the 1.0.4 source: the client gives the object a foreign prototype, then
    // throws on some later keys, replaces the object on a later patch, or pollutes String.prototype.
    @Test
    fun `refuses a key __proto__ anywhere in the patch, whatever it holds`() {
        val store = JsonParser.parseObject("""{"o":{"k":1},"a":[1]}""")
        for (value in listOf("null", "1", "\"s\"", "true", "[]", "[1]", "{}", """{"x":1}""", """{"__proto__":{"y":1}}""")) {
            for ((patch, kept) in listOf(
                """{"b":1,"__proto__":$value}""" to """{"o":{"k":1},"a":[1],"b":1}""",
                """{"o":{"m":1,"__proto__":$value,"n":2}}""" to """{"o":{"k":1,"m":1},"a":[1]}""",
                """{"b":1,"l":[0,{"__proto__":$value,"k":1}],"c":2}""" to """{"o":{"k":1},"a":[1],"b":1}""",
                """{"o":{"m":1,"l":[[{"k":{"__proto__":$value}}]]}}""" to """{"o":{"k":1,"m":1},"a":[1]}""",
                """{"a":{"0":2,"1":[{"__proto__":$value}]}}""" to """{"o":{"k":1},"a":[2]}""",
                """[5,[{"__proto__":$value}]]""" to """{"0":5,"o":{"k":1},"a":[1]}""",
            )) {
                for (ifMissing in listOf(false, true)) {
                    val e = assertFailsWith<SignalPatchException>(patch) { mergePatchAsClient(store, JsonParser.parse(patch), ifMissing) }
                    assertEquals(PROTO_REFUSED, e.message)
                    assertTrue(e.refused)
                    if (!ifMissing) assertEquals(kept, e.store.toJson(), patch)
                }
            }
        }
        assertEquals("""{"o":{"k":1},"a":[1]}""", store.toJson())
    }

    // A store the client never holds: refused where a patch reaches the object with the key.
    @Test
    fun `refuses to fold into an object of the store that has a key __proto__`() {
        val store = JsonParser.parseObject("""{"a":[{"k":1},{"__proto__":null,"k":2}],"o":{"k":1}}""")
        assertEquals(
            """{"a":[{"k":1,"m":1},{"__proto__":null,"k":2}],"o":{"k":1}}""",
            mergePatchAsClient(store, JsonParser.parse("""{"a":{"0":{"m":1}}}""")).toJson(),
        )
        val e = assertFailsWith<SignalPatchException> { mergePatchAsClient(store, JsonParser.parse("""{"o":{"j":1},"a":{"1":{"m":1}}}""")) }
        assertEquals(STORE_PROTO_REFUSED, e.message)
        assertTrue(e.refused)
        assertEquals("""{"a":[{"k":1},{"__proto__":null,"k":2}],"o":{"k":1,"j":1}}""", e.store.toJson())
        val top = JsonParser.parseObject("""{"__proto__":null,"k":1}""")
        assertTrue(assertFailsWith<SignalPatchException> { mergePatchAsClient(top, JsonParser.parse("""{"x":1}""")) }.refused)
    }

    // A fold copies each level on the patch's path in full width and no more: what is left alone
    // is the very same value, not a copy of it.
    @Test
    fun `leaves what a patch does not reach as it was`() {
        val big = "{" + (0 until 2000).joinToString(",") { "\"k$it\":{\"v\":$it,\"l\":[1,{\"b\":2,\"0\":3}]}" } + "}"
        val first = mergePatchAsClient(JsonObject.EMPTY, JsonParser.parse("{\"big\":$big,\"other\":{\"n\":1}}"))
        val second = mergePatchAsClient(first, JsonParser.parse("{\"count\":1,\"big\":{\"k1\":{\"v\":9}}}"))
        assertSame(first.obj("other"), second.obj("other"))
        assertNotSame(first.obj("big"), second.obj("big"))
        assertSame(first.obj("big")!!.obj("k2"), second.obj("big")!!.obj("k2"))
        assertSame(first.obj("big")!!.obj("k1")!!.array("l"), second.obj("big")!!.obj("k1")!!.array("l"))
        assertEquals("""{"v":9,"l":[1,{"0":3,"b":2}]}""", second.obj("big")!!.obj("k1")!!.toJson())

        // A store from elsewhere is put in JavaScript's key order once, where it is out of it, and
        // kept as it is where it is in it already.
        val parsed = JsonParser.parseObject("""{"a":{"x":1,"0":2},"b":{"y":1},"c":[{"z":1,"1":2}]}""")
        val folded = mergePatchAsClient(parsed, JsonParser.parse("""{"d":1}"""))
        assertEquals("""{"a":{"0":2,"x":1},"b":{"y":1},"c":[{"1":2,"z":1}],"d":1}""", folded.toJson())
        assertSame(parsed.obj("b"), folded.obj("b"))
        val again = mergePatchAsClient(folded, JsonParser.parse("""{"d":2}"""))
        assertSame(folded.obj("a"), again.obj("a"))
        assertSame(folded.array("c"), again.array("c"))
    }

    @Test
    fun `refuses what the client cannot hold`() {
        val store = JsonParser.parseObject("""{"a":[1]}""")
        // The client takes these as holes; the fold refuses on purpose.
        for (patch in listOf("""{"a":{"20000":1}}""", """{"a":{"length":20000}}""")) {
            assertTrue(assertFailsWith<SignalPatchException>(patch) { mergePatchAsClient(store, JsonParser.parse(patch)) }.refused)
        }
        // The browser's RangeError: a length that is not a whole number of zero or more.
        for (length in listOf("-1", "\"x\"", "1.5")) {
            val e =
                assertFailsWith<SignalPatchException>(length) {
                    mergePatchAsClient(store, JsonParser.parse("""{"a":{"length":$length}}"""))
                }
            assertFalse(e.refused)
        }
        assertEquals("""{"a":[1]}""", store.toJson())
    }
}
