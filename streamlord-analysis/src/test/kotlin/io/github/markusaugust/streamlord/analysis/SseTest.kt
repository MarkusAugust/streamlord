package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** The cases of `cases/sse.json`. */
class SseTest {
    private fun shown(m: SseMessage) =
        mapOf(
            "event" to m.event,
            "id" to m.id,
            "retry" to m.retry?.toLong(),
            "data" to m.data,
            "comments" to m.comments,
        )

    // The projections of a case: what each chunk hands back, or every message decoded.
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

    @Test
    fun `sse parser`() =
        eachCase(sharedCases("sse.json"), { it.string("name")!! }) { c ->
            val name = c.string("name")!!
            val call = calls[c.string("call")] ?: error("$name: no call ${c.string("call")}")
            assertEquals(c["expected"]!!.toKotlin(), call(c.array("chunks")!!.strings()), name)
        }

    // One long line in small pieces used to be scanned again from its start on every piece.
    @Test
    fun `a long line in small pieces is read in linear time`() {
        val parser = SseParser()
        val started = TimeSource.Monotonic.markNow()
        parser.feed("data: ")
        repeat(80_000) { parser.feed("0123456789") }
        val messages = parser.feed("\n\n")
        val millis = started.elapsedNow().inWholeMilliseconds

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
