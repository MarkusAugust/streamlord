package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.SignalPatchException
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
     * around `length`, a throw partway through a patch and key order.
     */
    @Test
    fun `folds each step as the client's own mergePatch does`() {
        val scenarios = JsonParser.parse(javaClass.getResource("/client-merge-differential.json")!!.readText()) as JsonArray
        assertEquals(326, scenarios.size)
        for (scenario in scenarios.objects()) {
            var store = JsonObject.EMPTY
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
