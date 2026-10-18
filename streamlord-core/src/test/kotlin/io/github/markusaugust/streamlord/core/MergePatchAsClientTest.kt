package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.SignalPatchException
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Each case in `client-store-cases.json` was run through the real Datastar 1.0.4 `mergePatch`
 * (engine/signals.ts), and `client` is `JSON.stringify` of its root afterwards. The fold here has
 * to arrive at the same text.
 */
class MergePatchAsClientTest {
    @Test
    fun `holds what the client holds`() {
        val cases = JsonParser.parse(javaClass.getResource("/client-store-cases.json")!!.readText()) as JsonArray
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
     * Each scenario in `client-merge-differential.json` was run through `mergePatch` imported from
     * the 1.0.4 source (engine/signals.ts) under Bun and happy-dom, a fresh store per scenario, and
     * `client` is `JSON.stringify` of the root after each step, or `THROWS` and the root once the
     * step threw. The audit's 317 scenarios (17 by hand, 300 at random with seed 7) plus the cases
     * around `length`, a throw partway through a patch and key order. A scenario with a `store`
     * starts from that text as parsed here, out of JavaScript's key order, where the client starts
     * from `JSON.parse` of it.
     */
    @Test
    fun `folds each step as the client's own mergePatch does`() {
        val scenarios = JsonParser.parse(javaClass.getResource("/client-merge-differential.json")!!.readText()) as JsonArray
        assertEquals(329, scenarios.size)
        for (scenario in scenarios.objects()) {
            var store = scenario.string("store")?.let(JsonParser::parseObject) ?: JsonObject.EMPTY
            for ((i, step) in scenario.array("steps")!!.objects().withIndex()) {
                val patch = JsonParser.parse(step.string("signals")!!)
                val got =
                    try {
                        mergePatchAsClient(store, patch, step.boolean("onlyIfMissing")!!).also { store = it }.toJson()
                    } catch (e: SignalPatchException) {
                        store = e.store
                        "THROWS " + e.store.toJson()
                    }
                assertEquals(step.string("client"), got, "${scenario.string("name")} step $i")
            }
        }
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
        assertFailsWith<IllegalArgumentException> { mergePatchAsClient(store, JsonParser.parse("""{"a":{"20000":1}}""")) }
        assertFailsWith<IllegalArgumentException> { mergePatchAsClient(store, JsonParser.parse("""{"__proto__":{"polluted":1}}""")) }
        // The browser's RangeError: a length that is not a whole number of zero or more.
        for (length in listOf("-1", "\"x\"", "1.5")) {
            assertFailsWith<IllegalArgumentException>(
                length,
            ) { mergePatchAsClient(store, JsonParser.parse("""{"a":{"length":$length}}""")) }
        }
        assertEquals("""{"a":[1]}""", store.toJson())
    }
}
