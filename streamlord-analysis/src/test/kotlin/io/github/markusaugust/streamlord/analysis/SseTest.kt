package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of `twins/sse.json`, which the VS Code extension's `sse.test.ts` reads too. */
class SseTest {
    private fun shown(m: SseMessage) =
        mapOf(
            "event" to m.event,
            "id" to m.id,
            "retry" to m.retry?.toLong(),
            "data" to m.data,
            "comments" to m.comments,
        )

    // The projections of sse.test.ts: what each chunk hands back, or every message decoded.
    private val calls: Map<String, (List<String>) -> Any?> =
        mapOf(
            "feed" to { chunks ->
                val p = SseParser()
                chunks.map { p.feed(it).map(::shown) }
            },
            "decode" to { chunks ->
                val p = SseParser()
                chunks.flatMap(p::feed).map(::decodeDatastar).map { f ->
                    mapOf("event" to f.event, "id" to f.id, "retry" to f.retry?.toLong(), "args" to f.args.map { listOf(it.key, it.value) })
                }
            },
        )

    @TestFactory
    fun `sse parser`(): List<DynamicTest> =
        twinCases("sse.json").map { c: JsonObject ->
            val name = c.string("name")!!
            DynamicTest.dynamicTest(name) {
                val call = calls[c.string("call")] ?: error("$name: no call ${c.string("call")}")
                assertEquals(c["expected"]!!.toKotlin(), call(c.array("chunks")!!.strings()), name)
            }
        }

    // One long line in small pieces used to be scanned again from its start on every piece.
    @Test
    fun `a long line in small pieces is read in linear time`() {
        val parser = SseParser()
        val started = System.nanoTime()
        parser.feed("data: ")
        repeat(80_000) { parser.feed("0123456789") }
        val messages = parser.feed("\n\n")
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(
            800_000,
            messages
                .single()
                .data
                .single()
                .length,
        )
        kotlin.test.assertTrue(millis < 2_000, "took $millis ms")
    }

    // The inspector shows comments alone as a keep-alive; with an id or a retry they are a frame.
    @Test
    fun `comments with an id are not a keep-alive`() {
        assertEquals(false, SseParser().feed(": c\nid: 7\n\n").single().isComment)
        assertEquals(true, SseParser().feed(": c\n\n").single().isComment)
    }
}
