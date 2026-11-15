package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlHelpersTest {
    private suspend fun wire(block: suspend DatastarStream.() -> Unit): String {
        val sink = BufferedSseSink()
        Streamlord().stream(sink).block()
        return sink.text()
    }

    private fun script(body: String) =
        "event: datastar-patch-elements\n" +
            "data: selector body\n" +
            "data: mode append\n" +
            "data: elements <script data-effect=\"el.remove()\">$body</script>\n\n"

    @Test
    fun `replaceUrl swaps the current entry and keeps its state`() =
        runTest {
            assertEquals(
                script("window.history.replaceState(window.history.state, '', \"/search?q=ash&size=small\")"),
                wire { replaceUrl("/search?q=ash&size=small") },
            )
        }

    @Test
    fun `pushUrl adds an entry`() =
        runTest {
            assertEquals(
                script("window.history.pushState(null, '', \"/boards/7\")"),
                wire { pushUrl("/boards/7") },
            )
        }

    // A quote, a backslash, a closing tag, a line break and U+2028 all stay inside the string
    // literal, and the frame stays one data line.
    @Test
    fun `the URL cannot break out of its string or its line`() =
        runTest {
            assertEquals(
                script("window.history.replaceState(window.history.state, '', \"/x?q=\\\"\\\\<\\/script>\\u2028\\n\")"),
                wire { replaceUrl("/x?q=\"\\</script>\u2028\n") },
            )
        }

    /** A `Flow` of events, as WebFlux and `respondDatastar(flow)` take, builds the same events as the stream sends. */
    @Test
    fun `the events a flow carries are the ones the stream sends`() =
        runTest {
            fun encoded(vararg events: DatastarEvent) = events.joinToString("") { SseEncoder.encode(it) }
            assertEquals(wire { redirect("/hall") }, encoded(ExecuteScript.redirect("/hall")))
            assertEquals(wire { replaceUrl("/q?a=1") }, encoded(ExecuteScript.replaceUrl("/q?a=1")))
            assertEquals(wire { pushUrl("/boards/7") }, encoded(ExecuteScript.pushUrl("/boards/7")))
            assertEquals(wire { removeSignals("a", "a.b", "c.d") }, encoded(PatchSignals.remove("a", "a.b", "c.d")))
            assertEquals("""{"a":null,"c":{"d":null}}""", PatchSignals.remove("a", "a.b", "c.d").signals)
            assertFailsWith<IllegalArgumentException> { ExecuteScript.redirect("javascript:alert(1)") }
        }
}
