package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HeartbeatTest {
    private val beating = Streamlord(heartbeat = 15.seconds)

    private fun BufferedSseSink.comments(): Int = text().lines().count { it == ": keep-alive" }

    @Test
    fun `a silent stream carries one comment per interval`() =
        runTest {
            val sink = BufferedSseSink()

            beating.stream(sink, null) { delay(50.seconds) }

            assertEquals(3, sink.comments())
        }

    @Test
    fun `a busy stream carries none`() =
        runTest {
            val sink = BufferedSseSink()

            beating.stream(sink, null) {
                repeat(10) {
                    patchSignals("tick" to it)
                    delay(10.seconds)
                }
            }

            assertEquals(0, sink.comments())
        }

    @Test
    fun `any frame restarts the wait`() =
        runTest {
            val sink = BufferedSseSink()

            beating.stream(sink, null) {
                delay(10.seconds)
                patchSignals("tick" to 1)
                delay(10.seconds)
            }

            assertEquals(0, sink.comments(), "20 seconds passed, but never 15 of them in silence")
        }

    @Test
    fun `the heartbeat stops when the block returns`() =
        runTest {
            val sink = BufferedSseSink()

            val finished = beating.stream(sink, null) { patchSignals("done" to true) }

            assertTrue(finished)
            assertEquals("event: datastar-patch-signals\ndata: signals {\"done\":true}\n\n", sink.text())
        }

    @Test
    fun `no heartbeat by default`() =
        runTest {
            val sink = BufferedSseSink()

            Streamlord().stream(sink, null) { delay(1.seconds * 60) }

            assertEquals("", sink.text())
        }

    // An idle stream is asked again on the heartbeat's schedule, and a no ends it there.
    @Test
    fun `a refusal on a heartbeat ends an idle stream`() =
        runTest {
            val sink = BufferedSseSink()
            var allowed = true
            var reachedTheEnd = false

            val finished =
                Streamlord(heartbeat = 15.seconds).stream(
                    sink,
                    StreamAuthorisation(every = 1.milliseconds, onRefused = { patchSignals("gone" to true) }) { allowed },
                ) {
                    allowed = false
                    delay(60.seconds)
                    reachedTheEnd = true
                }

            assertFalse(finished)
            assertFalse(reachedTheEnd, "the block ran on after the heartbeat was refused")
            assertEquals("event: datastar-patch-signals\ndata: signals {\"gone\":true}\n\n", sink.text())
        }

    @Test
    fun `a heartbeat must be positive`() {
        assertFailsWith<IllegalArgumentException> { Streamlord(heartbeat = 0.seconds) }
    }
}
