package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
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
        assertEquals(23, cases.size)
        for (case in cases.filterIsInstance<JsonObject>()) {
            var store = JsonObject.EMPTY
            for (step in case.array("steps")!!.objects()) {
                store = mergePatchAsClient(store, JsonParser.parse(step.string("signals")!!), step.boolean("onlyIfMissing")!!)
            }
            assertEquals(case.string("client"), store.toJson(), case.string("name"))
        }
    }

    @Test
    fun `refuses what the client cannot hold`() {
        val store = JsonParser.parseObject("""{"a":[1]}""")
        assertFailsWith<IllegalArgumentException> { mergePatchAsClient(store, JsonParser.parse("""{"a":{"20000":1}}""")) }
        assertFailsWith<IllegalArgumentException> { mergePatchAsClient(store, JsonParser.parse("""{"__proto__":{"polluted":1}}""")) }
        assertEquals("""{"a":[1]}""", store.toJson())
    }
}
