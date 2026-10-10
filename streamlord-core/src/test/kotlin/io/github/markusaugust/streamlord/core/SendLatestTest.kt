package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds

class SendLatestTest {
    private fun count(n: Int) = listOf(PatchElements("""<b id="count">$n</b>"""))

    private suspend fun sent(
        resumeFrom: String? = null,
        interval: Int = 0,
        states: kotlinx.coroutines.flow.Flow<Int>,
    ): List<PatchElements> {
        val sink = BufferedSseSink()
        Streamlord().stream(sink).sendLatest(states, interval.milliseconds, resumeFrom) { count(it) }
        return SseDecoder.decode(sink.text()).map { it as PatchElements }
    }

    @Test
    fun `each render goes out with its fingerprint as the event id`() =
        runTest {
            val events = sent(states = flowOf(1, 2))

            assertEquals(listOf("""<b id="count">1</b>""", """<b id="count">2</b>"""), events.map { it.elements })
            assertEquals(SseEncoder.fingerprint(count(1)), events[0].eventId)
            assertEquals(SseEncoder.fingerprint(count(2)), events[1].eventId)
        }

    @Test
    fun `a render that changes nothing is not sent`() =
        runTest {
            assertEquals(2, sent(states = flowOf(1, 1, 1, 2, 2)).size)
        }

    // The first goes at once; of those within the interval after it, only the newest follows.
    @Test
    fun `a burst within the interval costs two renders`() =
        runTest {
            val states =
                flow {
                    emit(1)
                    delay(10)
                    emit(2)
                    delay(10)
                    emit(3)
                    delay(500)
                    emit(4)
                }

            assertEquals(listOf(1, 3, 4).map { """<b id="count">$it</b>""" }, sent(interval = 100, states = states).map { it.elements })
        }

    @Test
    fun `a reconnect that already shows the first render is not sent it again`() =
        runTest {
            val shown = SseEncoder.fingerprint(count(1))

            assertEquals(listOf("""<b id="count">2</b>"""), sent(resumeFrom = shown, states = flowOf(1, 2)).map { it.elements })
        }

    @Test
    fun `a reconnect that shows something older gets the latest`() =
        runTest {
            val older = SseEncoder.fingerprint(count(0))

            assertEquals(1, sent(resumeFrom = older, states = flowOf(1)).size)
        }

    @Test
    fun `only the last event of a render carries the fingerprint`() =
        runTest {
            val sink = BufferedSseSink()
            val render = listOf(PatchSignals("""{"n":1}""", eventId = "mine"), PatchElements("""<b id="count">1</b>"""))

            Streamlord().stream(sink).sendLatest(flowOf(1)) { render }

            val events = SseDecoder.decode(sink.text())
            assertEquals("mine", events[0].eventId)
            assertEquals(SseEncoder.fingerprint(render), events[1].eventId)
        }

    @Test
    fun `the fingerprint ignores event ids and sees every byte else`() {
        assertEquals(
            SseEncoder.fingerprint(listOf(PatchSignals("""{"n":1}""", eventId = "a"))),
            SseEncoder.fingerprint(listOf(PatchSignals("""{"n":1}""", eventId = "b"))),
        )
        assertNotEquals(
            SseEncoder.fingerprint(listOf(PatchSignals("""{"n":1}"""))),
            SseEncoder.fingerprint(listOf(PatchSignals("""{"n":1}""", onlyIfMissing = true))),
        )
        assertEquals(22, SseEncoder.fingerprint(count(1)).length)
    }

    @Test
    fun `an empty render sends nothing, and a negative interval is refused`() =
        runTest {
            val sink = BufferedSseSink()
            Streamlord().stream(sink).sendLatest(flowOf(1)) { emptyList() }
            assertEquals("", sink.text())

            assertFailsWith<IllegalArgumentException> { sent(interval = -1, states = flowOf(1)) }
        }
}
