package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.core.protocol.SseFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/** What an event may put on the wire, so the client can act on it and reconnect with it. */
class WireLimitsTest {
    // The line carries whole milliseconds: a fraction would be cut off, and under one it would
    // read retry: 0, a reconnect with no wait. Over Int.MAX_VALUE the browser's timer overflows.
    @Test
    fun `a retry is a whole number of milliseconds from 1 to Int MAX_VALUE`() {
        for (retry in listOf(500.microseconds, 1.5.milliseconds, 1000.5.milliseconds, (Int.MAX_VALUE.toLong() + 1).milliseconds)) {
            assertFailsWith<DatastarEventValidationException>("$retry") { PatchSignals("{}", retry = retry) }
            assertFailsWith<DatastarEventValidationException>("$retry") { SseFrame("e", retry = retry) }
        }
        assertTrue("retry: 1\n" in SseFrame("e", retry = 1.milliseconds).render())
        assertTrue("retry: 2147483647\n" in SseFrame("e", retry = Int.MAX_VALUE.milliseconds).render())
    }

    // It goes back as the last-event-id header, and only printable ASCII survives that: fetch
    // cannot send above U+00FF, servers refuse control characters, Ktor's CIO turns Latin-1 into
    // U+FFFD, and a space at either end is stripped.
    @Test
    fun `an event id is printable ASCII with no space at either end`() {
        for (id in listOf("€1", "ø1", "a\u0001b", "a\tb", "a\u007Fb", " 7", "7 ")) {
            assertFailsWith<DatastarEventValidationException>(id) { PatchSignals("{}", eventId = id) }
            assertFailsWith<DatastarEventValidationException>(id) { SseFrame("e", id = id) }
        }
    }

    // Whatever the encoder accepts, the decoder reads back.
    @Test
    fun `an event id the client can send round-trips`() {
        for (id in listOf("7", "a b", "event-1_x~")) {
            val event = PatchSignals("{}", eventId = id)
            assertEquals(event, SseDecoder.decodeOne(SseEncoder.encode(event)))
        }
    }

    // A frame built by hand is held to what an event is held to, and cannot write a line of its own.
    @Test
    fun `a frame refuses what an event refuses`() {
        assertFailsWith<DatastarEventValidationException> { SseFrame("e\nid: x") }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", id = "a\nb") }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", dataLines = listOf("a\rb")) }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", id = "a\u0000b") }
        assertFailsWith<DatastarEventValidationException> { SseFrame("e", retry = (-5).milliseconds) }
    }

    // Selectors take any character the page's own ids do; only the event id is held to ASCII.
    @Test
    fun `a selector may carry characters above U+00FF`() {
        PatchElements("<p id=\"日本\"></p>", selector = "[data-name=\"€\"]", viewTransitionSelector = "#日本")
    }

    // NUL in markup is harmless to the client, and an event that exists can always be written.
    @Test
    fun `a NUL in data is written as it is`() {
        val event = PatchElements("<p id=\"a\">x\u0000y</p>")

        assertEquals(event, SseDecoder.decodeOne(SseEncoder.encode(event)))
    }
}
