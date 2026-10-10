package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.port.driving.pushUrl
import io.github.markusaugust.streamlord.core.port.driving.replaceUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlHelpersTest {
    private suspend fun wire(block: suspend io.github.markusaugust.streamlord.core.port.driving.DatastarStream.() -> Unit): String {
        val sink = BufferedSseSink()
        Streamlord().stream(sink).block()
        return sink.text()
    }

    @Test
    fun `replaceUrl swaps the current entry`() =
        runTest {
            assertEquals(
                "event: datastar-patch-elements\n" +
                    "data: selector body\n" +
                    "data: mode append\n" +
                    "data: elements <script data-effect=\"el.remove()\">" +
                    "window.history.replaceState(window.history.state, '', \"/search?q=ash&size=small\")</script>\n\n",
                wire { replaceUrl("/search?q=ash&size=small") },
            )
        }

    @Test
    fun `pushUrl adds an entry`() =
        runTest {
            assertEquals(
                "event: datastar-patch-elements\n" +
                    "data: selector body\n" +
                    "data: mode append\n" +
                    "data: elements <script data-effect=\"el.remove()\">" +
                    "window.history.pushState(null, '', \"/boards/7\")</script>\n\n",
                wire { pushUrl("/boards/7") },
            )
        }

    // A quote, a backslash or a closing tag in the URL stays inside the string literal.
    @Test
    fun `the URL cannot break out of its string`() =
        runTest {
            val text = wire { replaceUrl("/x?q=\"); alert(1); (\"</script><b>") }

            assertEquals(1, Regex("""</script>""").findAll(text).count(), text)
            assertEquals(false, "\"); alert(1)" in text.replace("\\\"", ""), text)
        }
}
