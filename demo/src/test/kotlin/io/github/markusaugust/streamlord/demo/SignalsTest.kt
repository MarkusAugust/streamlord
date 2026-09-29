package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.port.driven.decode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every signals class this service reads, read through the codec the service actually installs.
 *
 * This test exists because of a specific failure. The codec names its serializers so that the
 * service can be a native image; three classes were added with the demos and not named; the JVM
 * resolved them reflectively and said nothing; and the first sign of trouble was a container in
 * CI failing to decode a counter. The codec is strict now, so a type that is not named throws
 * here, on a JVM, in a second, with the name of the class.
 *
 * A route with a new signals class belongs in this list. If that is forgotten, this fails.
 */
class SignalsTest {
    @Test
    fun `the search reads its signals`() {
        assertEquals(
            SearchSignals(query = "readSignals"),
            SIGNALS.decode("""{"query":"readSignals","total":0}"""),
        )
    }

    @Test
    fun `the counter reads its signals`() {
        assertEquals(
            CounterSignals(running = true, count = 17),
            SIGNALS.decode("""{"running":true,"count":17,"query":""}"""),
        )
    }

    @Test
    fun `the form reads its signals`() {
        assertEquals(
            MusterSignals(banner = "Oxen Vow", swords = "120"),
            SIGNALS.decode("""{"banner":"Oxen Vow","swords":"120","mustered":false}"""),
        )
    }

    @Test
    fun `the gallery reads its signals`() {
        assertEquals(
            ModeSignals(mode = "remove"),
            SIGNALS.decode("""{"mode":"remove","lastMode":"inner"}"""),
        )
    }
}
