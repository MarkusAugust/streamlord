package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
