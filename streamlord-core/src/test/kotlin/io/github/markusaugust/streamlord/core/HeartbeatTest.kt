package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.io.IOException

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
                    // The verdict's age is measured in real time, and runTest skips virtual time.
                    Thread.sleep(5)
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
    /**
     * A sink that takes its time over the last words, so the other coroutine falls due meanwhile:
     * longer than [quick]'s heartbeat, shorter than the limit on last words.
     */
    private class SlowLastWords : SseSink {
        val text = StringBuilder()

        override suspend fun write(text: String) {
            if ("gone" in text) delay(4.seconds)
            this.text.append(text)
        }

        override suspend fun flush() = Unit
    }

    private val quick = Streamlord(heartbeat = 2.seconds)

    private val lastWords = "event: datastar-patch-signals\ndata: signals {\"gone\":true}\n\n"

    // Refused on the heartbeat; the block wakes and is refused too while the last words are written.
    @Test
    fun `last words from the heartbeat are not cut short by the block`() =
        runTest {
            val sink = SlowLastWords()
            var allowed = true

            val finished =
                quick.stream(
                    sink,
                    StreamAuthorisation(every = 1.milliseconds, onRefused = { patchSignals("gone" to true) }) { allowed },
                ) {
                    Thread.sleep(5)
                    allowed = false
                    delay(3.seconds)
                    patchSignals("late" to true)
                }

            assertFalse(finished)
            assertEquals(lastWords, sink.text.toString())
        }

    // Refused in the block; the heartbeat falls due while the last words are written.
    @Test
    fun `last words from the block are not cut short by the heartbeat`() =
        runTest {
            val sink = SlowLastWords()
            var allowed = true

            val finished =
                quick.stream(
                    sink,
                    StreamAuthorisation(every = 1.milliseconds, onRefused = { patchSignals("gone" to true) }) { allowed },
                ) {
                    Thread.sleep(5)
                    allowed = false
                    patchSignals("late" to true)
                }

            assertFalse(finished)
            assertEquals(lastWords, sink.text.toString())
        }

    @Test
    fun `an exception from the block is the one that comes out`() =
        runTest {
            val thrown =
                assertFailsWith<IllegalStateException> {
                    beating.stream(BufferedSseSink(), null) {
                        delay(20.seconds)
                        error("the handler failed")
                    }
                }

            assertEquals("the handler failed", thrown.message)
        }

    // The comment is a write, so a reader who has gone is found while the block is idle.
    @Test
    fun `a reader who has gone is found on the heartbeat`() =
        runTest {
            val gone =
                object : SseSink {
                    override suspend fun write(text: String): Unit = throw IOException("Broken pipe")

                    override suspend fun flush() = Unit
                }
            var reachedTheEnd = false

            assertFailsWith<IOException> {
                beating.stream(gone, null) {
                    delay(1.seconds * 3600)
                    reachedTheEnd = true
                }
            }
            assertFalse(reachedTheEnd)
        }

    @Test
    fun `a block that swallows its refusal is ended by the next heartbeat`() =
        runTest {
            val sink = BufferedSseSink()
            var allowed = true
            var reachedTheEnd = false

            val finished =
                beating.stream(sink, StreamAuthorisation(every = 1.milliseconds) { allowed }) {
                    Thread.sleep(5)
                    allowed = false
                    try {
                        patchSignals("late" to true)
                    } catch (_: StreamRefusedException) {
                        // Carries on regardless.
                    }
                    delay(1.seconds * 3600)
                    reachedTheEnd = true
                }

            assertFalse(finished)
            assertFalse(reachedTheEnd, "the block waited out the hour after its refusal")
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `last words that never finish are cut off at the limit`() =
        runTest {
            val sink = BufferedSseSink()

            val finished =
                beating.stream(
                    sink,
                    StreamAuthorisation(every = 1.milliseconds, onRefused = {
                        patchSignals("gone" to true)
                        delay(1.seconds * 3600)
                    }) { false },
                ) { patchSignals("never" to true) }

            assertFalse(finished)
            assertEquals(lastWords, sink.text())
            assertTrue(testScheduler.currentTime <= StreamAuthorisation.LAST_WORDS_LIMIT.inWholeMilliseconds, "waited ${testScheduler.currentTime} ms")
        }
}
