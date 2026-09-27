package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.DatastarAttributes
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SseEncoderTest {
    @Test
    fun `minimal patch elements writes only the elements line`() {
        val out = SseEncoder.encode(PatchElements("""<div id="a">x</div>"""))
        assertEquals("event: datastar-patch-elements\ndata: elements <div id=\"a\">x</div>\n\n", out)
    }

    @Test
    fun `all options are written in specification order`() {
        val out =
            SseEncoder.encode(
                PatchElements(
                    elements = "<div>\n  <span>1</span>\n</div>",
                    selector = "#feed",
                    mode = ElementPatchMode.INNER,
                    namespace = ElementNamespace.SVG,
                    useViewTransition = true,
                    viewTransitionSelector = "#main",
                    eventId = "123",
                    retry = 2.seconds,
                ),
            )
        assertEquals(
            """
            |event: datastar-patch-elements
            |id: 123
            |retry: 2000
            |data: selector #feed
            |data: mode inner
            |data: useViewTransition true
            |data: viewTransitionSelector #main
            |data: namespace svg
            |data: elements <div>
            |data: elements   <span>1</span>
            |data: elements </div>
            |
            |
            """.trimMargin(),
            out,
        )
    }

    @Test
    fun `default retry of one second is not written`() {
        val out = SseEncoder.encode(PatchElements("<div id=\"a\"></div>", retry = 1000.milliseconds))
        assertEquals("event: datastar-patch-elements\ndata: elements <div id=\"a\"></div>\n\n", out)
    }

    @Test
    fun `viewTransitionSelector is ignored without useViewTransition`() {
        val out = SseEncoder.encode(PatchElements("<div id=\"a\"></div>", viewTransitionSelector = "#x"))
        assertEquals("event: datastar-patch-elements\ndata: elements <div id=\"a\"></div>\n\n", out)
    }

    @Test
    fun `windows line endings become separate data lines`() {
        val out = SseEncoder.encode(PatchElements("<div>\r\n</div>"))
        assertEquals("event: datastar-patch-elements\ndata: elements <div>\ndata: elements </div>\n\n", out)
    }

    @Test
    fun `remove needs no elements`() {
        val out = SseEncoder.encode(PatchElements.remove("#a, #b"))
        assertEquals("event: datastar-patch-elements\ndata: selector #a, #b\ndata: mode remove\n\n", out)
    }

    @Test
    fun `patch signals writes onlyIfMissing before signals`() {
        val out = SseEncoder.encode(PatchSignals("""{"a":1}""", onlyIfMissing = true))
        assertEquals("event: datastar-patch-signals\ndata: onlyIfMissing true\ndata: signals {\"a\":1}\n\n", out)
    }

    @Test
    fun `execute script appends a self-removing script to body`() {
        val out = SseEncoder.encode(ExecuteScript("console.log('x')"))
        assertEquals(
            "event: datastar-patch-elements\ndata: selector body\ndata: mode append\n" +
                "data: elements <script data-effect=\"el.remove()\">console.log('x')</script>\n\n",
            out,
        )
    }

    @Test
    fun `execute script attributes come first and are escaped`() {
        val tag = ExecuteScript("1", autoRemove = false, attributes = mapOf("type" to "module", "nonce" to "a\"b")).scriptTag
        assertEquals("<script type=\"module\" nonce=\"a&quot;b\">1</script>", tag)
    }

    @Test
    fun `execute script cannot break out of its own tag`() {
        val tag = ExecuteScript("x = '</SCRIPT><img src=x onerror=alert(1)>'").scriptTag
        assertEquals("<script data-effect=\"el.remove()\">x = '<\\/script><img src=x onerror=alert(1)>'</script>", tag)
    }

    @Test
    fun `comment is written as an SSE comment`() {
        assertEquals(": ping\n\n", SseEncoder.comment("ping"))
    }

    @Test
    fun `selectors with line breaks are rejected`() {
        assertFailsWith<DatastarEventValidationException> {
            PatchElements("<div></div>", selector = "#a\ndata: elements <script>")
        }
        assertFailsWith<DatastarEventValidationException> {
            PatchElements("<div></div>", selector = "#a\r", mode = ElementPatchMode.APPEND)
        }
    }

    @Test
    fun `event ids with line breaks are rejected`() {
        assertFailsWith<DatastarEventValidationException> { PatchSignals("{}", eventId = "1\nretry: 0") }
        assertFailsWith<DatastarEventValidationException> { PatchSignals("{}", eventId = "") }
    }

    @Test
    fun `modes other than outer and replace require a selector`() {
        assertFailsWith<DatastarEventValidationException> { PatchElements("<div></div>", mode = ElementPatchMode.APPEND) }
        PatchElements("<div id=\"a\"></div>", mode = ElementPatchMode.REPLACE)
    }

    @Test
    fun `elements are required unless removing`() {
        assertFailsWith<DatastarEventValidationException> { PatchElements(elements = "  ") }
        assertFailsWith<DatastarEventValidationException> { PatchElements(elements = null) }
    }

    @Test
    fun `retry must be positive`() {
        assertFailsWith<DatastarEventValidationException> { PatchSignals("{}", retry = 0.seconds) }
    }

    @Test
    fun `script attribute names are validated`() {
        assertFailsWith<DatastarEventValidationException> { ExecuteScript("1", attributes = mapOf("on load\">" to "x")) }
    }

    @Test
    fun `wire tokens resolve case-insensitively`() {
        assertEquals(ElementPatchMode.APPEND, ElementPatchMode.fromWire("Append"))
        assertEquals(ElementNamespace.MATHML, ElementNamespace.fromWire("mathml"))
        assertFailsWith<DatastarEventValidationException> { ElementPatchMode.fromWire("merge") }
    }

    @Test
    fun `execute script follows the attribute prefix of the aliased bundle`() {
        DatastarAttributes.prefix = "data-star-"
        try {
            assertEquals("<script data-star-effect=\"el.remove()\">x()</script>", ExecuteScript("x()").scriptTag)
        } finally {
            DatastarAttributes.prefix = "data-"
        }
    }
}
