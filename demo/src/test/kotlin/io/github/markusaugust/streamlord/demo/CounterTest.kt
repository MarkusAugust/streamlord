package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The counter is a function from signals to a flow of events, so the interesting half of it can
 * be tested without a server, a browser or a clock, since `runTest` skips the delays.
 */
class CounterTest {
    @Test
    fun `a second request ends the stream instead of starting another`() =
        runTest {
            val events = counterEvents(CounterSignals(running = true, count = 17)).toList()

            assertEquals(2, events.size, "a stop is two events: the signal and the last word")
            assertEquals("""{"running": false}""", (events[0] as PatchSignals).signals)
            assertTrue((events[1] as PatchElements).elements.orEmpty().contains("Stopped at 17"))
        }

    @Test
    fun `a first request counts, and says so before and after`() =
        runTest {
            val events = counterEvents(CounterSignals()).toList()

            assertEquals("""{"running": true}""", (events.first() as PatchSignals).signals)
            assertEquals("""{"running": false}""", (events.last() as PatchSignals).signals)

            // Thirty numbers, each one a signal and an element, between those two.
            assertEquals(62, events.size)
            assertTrue((events[1] as PatchSignals).signals.contains(""""count": 1"""))
            assertTrue((events[60] as PatchElements).elements.orEmpty().contains("Finished at 30"))
        }
}
