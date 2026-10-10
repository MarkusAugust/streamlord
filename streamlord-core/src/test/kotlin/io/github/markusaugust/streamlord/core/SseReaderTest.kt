package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseMessage
import io.github.markusaugust.streamlord.core.protocol.SseReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseReaderTest {
    private fun data(messages: List<SseMessage>) = messages.map { it.data }

    @Test
    fun `a message comes back the moment its blank line arrives`() {
        val reader = SseReader()

        assertEquals(emptyList(), reader.feed("event: datastar-patch-signals\n"))
        assertEquals(emptyList(), reader.feed("data: signals {\"n\":1}\n"))
        val messages = reader.feed("\n")

        assertEquals(PatchSignals("{\"n\":1}"), SseDecoder.event(messages.single()))
    }

    @Test
    fun `a line split anywhere reads as one line`() {
        val reader = SseReader()
        val wire = "event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n"

        val messages = wire.map { reader.feed(it.toString()) }.flatten()

        assertEquals(SseDecoder.messages(wire), messages)
    }

    @Test
    fun `a CRLF split across two pieces is one line ending`() {
        val reader = SseReader()
        assertEquals(emptyList(), reader.feed("data: x\r"))
        assertEquals(listOf(listOf("x", "y")), data(reader.feed("\ndata: y\r\n\r\n")))
    }

    @Test
    fun `a lone CR ends a line, also as the last character of a piece`() {
        val reader = SseReader()
        assertEquals(listOf(listOf("x")), data(reader.feed("data: x\r\r")))
        assertEquals(emptyList(), reader.feed("data: z\r"))
        assertEquals(listOf(listOf("z")), data(reader.feed("\r")))
    }

    @Test
    fun `an empty piece between the CR and the LF changes nothing`() {
        val reader = SseReader()
        assertEquals(emptyList(), reader.feed("data: x\r"))
        assertEquals(emptyList(), reader.feed(""))
        assertEquals(emptyList(), reader.feed("\n"))
        assertEquals(listOf(listOf("x")), data(reader.feed("\r\n")))
    }

    @Test
    fun `a keep-alive is a message of comments`() {
        val message = SseReader().feed(": keep-alive\n\n").single()

        assertEquals(listOf("keep-alive"), message.comments)
        assertNull(SseDecoder.event(message))
    }

    @Test
    fun `one leading byte order mark is dropped, a later one is text`() {
        val reader = SseReader()
        assertEquals(listOf(listOf("x")), data(reader.feed("﻿data: x\n\n")))
        assertEquals(listOf(listOf("﻿y")), data(reader.feed("data: ﻿y\n\n")))
    }

    @Test
    fun `fields with no blank line after them are not a message yet`() {
        assertEquals(emptyList(), SseReader().feed("event: datastar-patch-signals\ndata: signals {}\n"))
    }
}
