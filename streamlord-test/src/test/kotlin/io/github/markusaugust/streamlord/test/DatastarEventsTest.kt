package io.github.markusaugust.streamlord.test

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatastarEventsTest {
    /** A handler that patches markup, sets two signals, and keeps the connection awake. */
    private val stream =
        SseEncoder.encode(PatchElements("<li>A new head</li>", selector = "#feed", mode = ElementPatchMode.APPEND)) +
            SseEncoder.comment("keep-alive") +
            SseEncoder.encode(PatchSignals("""{"heads":12,"open":true}""")) +
            SseEncoder.encode(PatchSignals("""{"heads":13}"""))

    private val events = datastarEvents(stream)

    @Test
    fun `the events come back as themselves`() {
        assertEquals(3, events.size)
        assertEquals(PatchElements::class, events[0]::class)
    }

    @Test
    fun `it is a list, so your own assertions work on it`() {
        assertEquals(2, events.filterIsInstance<PatchSignals>().size)
        assertTrue(events.any { it is PatchElements })
    }

    @Test
    fun `an element patch is found by any part of its shape`() {
        events.assertPatchElements(selector = "#feed")
        events.assertPatchElements(mode = ElementPatchMode.APPEND)
        events.assertPatchElements(containing = "A new head")
        events.assertPatchElements(elements = "<li>A new head</li>")
        assertEquals("#feed", events.assertPatchElements(containing = "head").selector)
    }

    // The point of the loose default: a test about markup survives a handler gaining a signal.
    @Test
    fun `other events in the stream are ignored`() {
        val busier = stream + SseEncoder.encode(PatchSignals("""{"unrelated":1}"""))

        datastarEvents(busier).assertPatchElements(selector = "#feed")
    }

    @Test
    fun `a signal reads as what the whole stream left it as`() {
        events.assertSignal("heads", 13)
        events.assertSignal("open", true)
        events.assertNoSignal("never-set")
    }

    @Test
    fun `a removed signal is gone rather than null`() {
        val removing = stream + SseEncoder.encode(PatchSignals("""{"open":null}"""))

        datastarEvents(removing).assertNoSignal("open")
    }

    @Test
    fun `onlyIfMissing does not overwrite what is already there`() {
        val wire =
            SseEncoder.encode(PatchSignals("""{"query":"ash"}""")) +
                SseEncoder.encode(PatchSignals("""{"query":"other","page":1}""", onlyIfMissing = true))

        val read = datastarEvents(wire)

        read.assertSignal("query", "ash")
        read.assertSignal("page", 1)
    }

    @Test
    fun `a nested signal is reached with dots`() {
        val wire = SseEncoder.encode(PatchSignals("""{"address":{"city":"Thurn"}}"""))

        datastarEvents(wire).assertSignal("address.city", "Thurn")
    }

    @Test
    fun `assertExactly pins the whole stream`() {
        val one = SseEncoder.encode(PatchSignals("""{"a":1}"""))

        datastarEvents(one).assertExactly(PatchSignals("""{"a":1}"""))
        assertFailsWith<AssertionError> { events.assertExactly(PatchSignals("""{"a":1}""")) }
    }

    @Test
    fun `a comment is carried but is not an event`() {
        assertEquals(4, events.messages.size)
        assertEquals(listOf("keep-alive"), events.messages[1].comments)
        datastarEvents(SseEncoder.comment("alone")).assertNoEvents()
    }

    /**
     * The failure message is most of the value. A test that says only "expected true, was false"
     * sends you back to the handler to print the stream by hand, which is the moment people
     * decide the test was not worth writing.
     */
    @Test
    fun `a failure says what was wanted and what the stream actually carried`() {
        val failure = assertFailsWith<AssertionError> { events.assertPatchElements(selector = "#missing") }
        val message = failure.message!!

        assertTrue(message.contains("selector #missing"), message)
        assertTrue(message.contains("The stream carried 3 events"), message)
        assertTrue(message.contains("datastar-patch-elements"), message)
        assertTrue(message.contains("A new head"), message)
    }

    @Test
    fun `a failing signal assertion names the value it found`() {
        val failure = assertFailsWith<AssertionError> { events.assertSignal("heads", 99) }

        assertTrue(failure.message!!.contains("'heads' is 13, expected 99"), failure.message!!)
    }

    @Test
    fun `a failure on an empty stream says so rather than printing nothing`() {
        val failure = assertFailsWith<AssertionError> { datastarEvents("").assertPatchElements() }

        assertTrue(failure.message!!.contains("no Datastar events"), failure.message!!)
    }

    @Test
    fun `a patch sent without a mode matches the default the client will use`() {
        val wire = SseEncoder.encode(PatchElements("<p>x</p>", selector = "#a"))

        datastarEvents(wire).assertPatchElements(mode = ElementPatchMode.OUTER)
    }
}
