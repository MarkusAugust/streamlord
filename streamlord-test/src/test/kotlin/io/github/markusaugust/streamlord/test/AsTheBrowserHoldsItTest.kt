package io.github.markusaugust.streamlord.test

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The assertions against what the Datastar 1.0.4 client ends up holding for the same stream. */
class AsTheBrowserHoldsItTest {
    private fun stream(vararg events: DatastarEvent) = datastarEvents(events.joinToString("") { SseEncoder.encode(it) })

    @Test
    fun `onlyIfMissing is honoured leaf by leaf`() {
        val events = stream(PatchSignals("""{"a":{"b":1}}"""), PatchSignals("""{"a":{"b":2,"c":3}}""", onlyIfMissing = true))

        events.assertSignal("a.b", 1)
        events.assertSignal("a.c", 3)
    }

    @Test
    fun `numbers are compared by value`() {
        val events = stream(PatchSignals("""{"heads":13,"ratio":0.50,"big":1e2}"""))

        events.assertSignal("heads", 13.0)
        events.assertSignal("ratio", 0.5)
        events.assertSignal("big", 100)
        assertFailsWith<AssertionError> { events.assertSignal("heads", 14) }
    }

    @Test
    fun `null means the signal is not there`() {
        val events = stream(PatchSignals("""{"a":1,"b":2}"""), PatchSignals("""{"a":null}"""))

        events.assertSignal("a", null)
        events.assertSignal("never", null)
        assertFailsWith<AssertionError> { events.assertSignal("b", null) }
    }

    @Test
    fun `a key that holds a dot is found`() {
        val events = stream(PatchSignals("""{"a.b":1,"c":{"d.e":2}}"""))

        events.assertSignal("a.b", 1)
        events.assertSignal("c.d.e", 2)
    }

    @Test
    fun `an object patched onto an array sets its indices`() {
        stream(PatchSignals("""{"items":[1,2]}"""), PatchSignals("""{"items":{"0":9}}""")).assertSignal("items", listOf(9, 2))
    }

    @Test
    fun `signals that are not json fail where they are read`() {
        // Streamlord can no longer send them; another server can, and the client would run them.
        val failure =
            assertFailsWith<DatastarEventValidationException> {
                datastarEvents("event: datastar-patch-signals\ndata: signals {count: 1}\n\n")
            }
        assertTrue("runs signals that are not JSON as JavaScript" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `assertExactly takes the event that was sent`() {
        stream(ExecuteScript("console.log(1)")).assertExactly(ExecuteScript("console.log(1)"))
        stream(PatchSignals("""{"total": 3}""", retry = 1.seconds)).assertExactly(PatchSignals("""{"total":3}"""))
        stream(PatchElements("<p id=\"a\">one\r\ntwo</p>")).assertExactly(PatchElements("<p id=\"a\">one\r\ntwo</p>"))
        assertFailsWith<AssertionError> { stream(PatchSignals("""{"total":3}""")).assertExactly(PatchSignals("""{"total":4}""")) }
    }

    @Test
    fun `markup is compared as the wire carries it`() {
        stream(PatchElements("<p id=\"a\">one\r\ntwo</p>")).assertPatchElements(elements = "<p id=\"a\">one\r\ntwo</p>")
    }

    // signals.ts mergeInner: an index past the end extends the array, and null leaves a hole.
    // The client reads a hole, and a null it holds, as an empty string: that is what `$list[1]` gives and what `@post` sends.
    @Test
    fun `a patch onto an array extends it, and a hole reads as an empty string as it does in the client`() {
        val events =
            stream(
                PatchSignals("""{"list":["a","b","c"]}"""),
                PatchSignals("""{"list":{"5":"x"}}"""),
                PatchSignals("""{"list":{"1":null,"01":"never","name":"never"}}"""),
            )

        events.assertSignal("list", listOf("a", "", "c", "", "", "x"))
    }

    @Test
    fun `an index far past the end fails rather than fill memory`() {
        val events = stream(PatchSignals("""{"list":["a"]}"""), PatchSignals("""{"list":{"100000000":"x"}}"""))

        assertFailsWith<AssertionError> { events.assertSignal("list", listOf("a")) }
    }
}
