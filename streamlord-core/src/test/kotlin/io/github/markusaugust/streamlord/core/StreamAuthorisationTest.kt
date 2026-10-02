package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds

class StreamAuthorisationTest {
    private val streamlord = Streamlord()

    /** Long enough that one verdict stands for the whole test. */
    private val once = 1.hours

    /** Short enough that every write asks again. */
    private val always = 1.nanoseconds

    @Test
    fun `without an authorisation nothing changes`() =
        runTest {
            val sink = BufferedSseSink()

            val finished = streamlord.stream(sink, null) { patchSignals("count" to 1) }

            assertTrue(finished)
            assertEquals("event: datastar-patch-signals\ndata: signals {\"count\":1}\n\n", sink.text())
        }

    @Test
    fun `a yes lets the stream run to the end`() =
        runTest {
            val sink = BufferedSseSink()

            val finished =
                streamlord.stream(sink, StreamAuthorisation(every = always) { true }) {
                    patchSignals("count" to 1)
                    patchSignals("count" to 2)
                }

            assertTrue(finished)
            assertEquals(2, sink.text().split("event:").size - 1)
        }

    // The cheapest place to refuse a connection is before it starts.
    @Test
    fun `a no at the open writes nothing at all`() =
        runTest {
            val sink = BufferedSseSink()
            var ran = false

            val finished =
                streamlord.stream(sink, StreamAuthorisation(every = once) { false }) {
                    ran = true
                    patchSignals("count" to 1)
                }

            assertFalse(finished)
            assertFalse(ran, "the handler ran after the stream was refused")
            assertEquals("", sink.text())
        }

    @Test
    fun `a no partway through stops the stream where it stood`() =
        runTest {
            val sink = BufferedSseSink()
            var allowed = true
            var reached = 0

            val finished =
                streamlord.stream(sink, StreamAuthorisation(every = always) { allowed }) {
                    patchSignals("count" to 1)
                    reached = 1
                    allowed = false
                    patchSignals("count" to 2)
                    reached = 2
                }

            assertFalse(finished)
            assertEquals(1, reached, "the handler carried on past the refusal")
            assertEquals("event: datastar-patch-signals\ndata: signals {\"count\":1}\n\n", sink.text())
        }

    /** The last thing down the stream, so the reader is told rather than dropped. */
    @Test
    fun `onRefused writes to the stream before it closes`() =
        runTest {
            val sink = BufferedSseSink()
            val authorisation =
                StreamAuthorisation(
                    every = once,
                    onRefused = { redirect("/login") },
                ) { false }

            val finished = streamlord.stream(sink, authorisation) { patchSignals("count" to 1) }

            assertFalse(finished)
            assertTrue(sink.text().contains("datastar-patch-elements"), sink.text())
            assertTrue(sink.text().contains("/login"), sink.text())
        }

    @Test
    fun `a verdict stands until the interval has passed`() =
        runTest {
            val sink = BufferedSseSink()
            var asked = 0

            streamlord.stream(
                sink,
                StreamAuthorisation(every = once) {
                    asked++
                    true
                },
            ) {
                repeat(5) { patchSignals("count" to it) }
            }

            assertEquals(1, asked, "the check ran once per write instead of once per interval")
        }

    @Test
    fun `an interval of nothing is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { StreamAuthorisation(every = 0.nanoseconds) { true } }
    }

    /** Several coroutines writing at once must not each run the refusal block. */
    @Test
    fun `the refusal happens once however many writers there are`() =
        runTest {
            val sink = BufferedSseSink()
            var refusals = 0
            val authorisation =
                StreamAuthorisation(
                    every = always,
                    onRefused = { refusals++ },
                ) { false }

            streamlord.stream(sink, authorisation) {
                coroutineScope {
                    repeat(8) { launch { runCatching { patchSignals("count" to it) } } }
                }
            }

            assertEquals(1, refusals)
        }
}
