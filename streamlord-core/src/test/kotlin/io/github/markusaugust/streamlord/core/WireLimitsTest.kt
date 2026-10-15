package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.core.protocol.SseFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/** What an event may put on the wire, so the client can act on it and reconnect with it. */
class WireLimitsTest {
    // Under a millisecond it is written as retry: 0, and the client reconnects at once without
    // end; over Int.MAX_VALUE the browser's timer overflows.
    @Test
    fun `a retry runs from one millisecond to Int MAX_VALUE milliseconds`() {
        for (retry in listOf(500.microseconds, (Int.MAX_VALUE.toLong() + 1).milliseconds)) {
            assertFailsWith<DatastarEventValidationException>("$retry") { PatchSignals("{}", retry = retry) }
            assertFailsWith<DatastarEventValidationException>("$retry") { SseFrame("e", retry = retry) }
        }
        PatchSignals("{}", retry = 1.milliseconds)
        PatchSignals("{}", retry = Int.MAX_VALUE.milliseconds)
    }

    // The client compares the number it reads, so 1000.5 ms is the default it would write.
    @Test
    fun `a retry that writes as 1000 is the default and is left off`() {
        assertFalse("retry:" in SseFrame("e", retry = 1000.5.milliseconds).render())
    }

    // fetch cannot send a character above U+00FF in the last-event-id header, so every reconnect
    // would fail; and it strips spaces and tabs at the edges, so the server would get another id.
    @Test
    fun `an event id the client could not send back as it is fails`() {
        for (id in listOf("€1", " 7", "7 ", "\t7")) {
            assertFailsWith<DatastarEventValidationException>(id) { PatchSignals("{}", eventId = id) }
            assertFailsWith<DatastarEventValidationException>(id) { SseFrame("e", id = id) }
        }
    }

    // Whatever the encoder accepts, the decoder reads back.
    @Test
    fun `an event id the client can send round-trips`() {
        for (id in listOf("ø1", "a\u0001b", "a b")) {
            val event = PatchSignals("{}", eventId = id)
            assertEquals(event, SseDecoder.decodeOne(SseEncoder.encode(event)))
        }
    }

    // A frame built by hand must not be able to write a line of its own.
    @Test
    fun `a frame refuses a line break in any field`() {
        assertFailsWith<DatastarEventValidationException> { SseFrame("e\nid: x") }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", id = "a\nb") }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", dataLines = listOf("a\rb")) }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", id = "a\u0000b") }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", retry = (-5).milliseconds) }
    }

    // NUL in markup is harmless to the client, and an event that exists can always be written.
    @Test
    fun `a NUL in data is written as it is`() {
        val event = PatchElements("<p id=\"a\">x\u0000y</p>")

        assertEquals(event, SseDecoder.decodeOne(SseEncoder.encode(event)))
    }
}
