package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of the VS Code extension's `sse.test.ts`. */
class SseTest {
    private fun data(messages: List<SseMessage>) = messages.map { it.data }

    @Test
    fun `a CRLF split across two chunks is one line ending`() {
        val p = SseParser()
        assertEquals(emptyList(), p.feed("data: x\r"))
        assertEquals(listOf(listOf("x", "y")), data(p.feed("\ndata: y\r\n\r\n")))
    }

    @Test
    fun `an empty chunk between the CR and the LF changes nothing`() {
        val p = SseParser()
        assertEquals(emptyList(), p.feed("data: x\r"))
        assertEquals(emptyList(), p.feed(""))
        assertEquals(emptyList(), p.feed("\n"))
        assertEquals(listOf(listOf("x")), data(p.feed("\r\n")))
    }

    @Test
    fun `a lone CR ends a line, also as the last character of a chunk`() {
        val p = SseParser()
        assertEquals(listOf(listOf("x")), data(p.feed("data: x\r\r")))
        assertEquals(listOf(listOf("y")), data(p.feed("data: y\r\r")))
        assertEquals(emptyList(), p.feed("data: z\r"))
        assertEquals(listOf(listOf("z")), data(p.feed("\r")))
    }

    @Test
    fun `a data line may start with any word`() {
        val p = SseParser()
        val messages = p.feed("event: e\ndata: constructor foo\ndata: toString bar\ndata: toString baz\ndata: __proto__ qux\n\n")
        assertEquals(
            mapOf("constructor" to "foo", "toString" to "bar\nbaz", "__proto__" to "qux"),
            decodeDatastar(messages.single()).args,
        )
    }

    @Test
    fun `a retry that does not fit an int is dropped`() {
        val p = SseParser()
        assertEquals(listOf(2147483647, null), p.feed("retry: 2147483647\ndata: a\n\nretry: 2147483648\ndata: b\n\n").map { it.retry })
    }
}
