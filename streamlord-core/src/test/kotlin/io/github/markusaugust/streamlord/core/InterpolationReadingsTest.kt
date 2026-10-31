package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.MistypedAttributeException
import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The readings interpolate has to take beyond the one it writes for: a `noscript` as the client's
 * DOMParser reads it (scripting off) and as a page reads it (scripting on), an svg or math patch
 * parsed inside its own element, and an [io.github.markusaugust.streamlord.core.domain.Html] that
 * leaves an element open.
 */
class InterpolationReadingsTest {
    private val code = "alert(document.domain)"

    private fun refused(
        format: String,
        vararg values: Any?,
    ): UnsafeInterpolationException = assertFailsWith<UnsafeInterpolationException>(format) { interpolate(format, *values) }

    // The client parses a patch with DOMParser, where scripting is off and a noscript holds elements.
    @Test
    fun `a noscript holds markup to the client, so what is inside it is read as markup`() {
        assertEquals(Position.CODE, refused("""<noscript><xmp><a title="</xmp><script>%s</script>""", code).position)
        assertEquals(
            Position.CODE,
            refused("""<noscript><textarea></noscript><p title="</textarea><img src=x onerror='%s'>">""", code).position,
        )
    }

    // A page the server renders is parsed with scripting on, where the noscript is text to its end tag.
    @Test
    fun `a noscript is text to a page, so what follows its end tag is read as markup too`() {
        assertEquals(Position.CODE, refused("""<noscript><p title="</noscript><img src=x onerror='%s'>">""", code).position)
        assertEquals("<noscript><p>x</p></noscript>", interpolate("<noscript><p>%s</p></noscript>", "x").text)
    }

    @Test
    fun `the elements guard reads the attributes inside a noscript`() {
        assertFailsWith<MistypedAttributeException> { ElementsGuard.check("""<noscript><div data-onn:click="go()"></div></noscript>""") }
    }

    @Test
    fun `an Html that leaves an svg open cannot make the markup after it run`() {
        assertEquals(1, refused("%s<title><xmp><!--</xmp><script>%s</script>", interpolate("<svg>"), code).index)
    }

    @Test
    fun `an Html that leaves foreign content, a template or a select open, or closes what it did not open, is written as text`() {
        val open = listOf("<svg>", "<math>", "<svg><g>", "<svg><foreignObject>", "<math><mi>", "<template>", "<select>", "</svg>", "</div>")
        for (markup in open) {
            val out = interpolate("<p>%s</p>", interpolate(markup)).text
            assertFalse('<' in out.removePrefix("<p>").removeSuffix("</p>"), "$markup: $out")
        }
    }

    @Test
    fun `an Html that closes what it opens is still markup`() {
        val closed =
            listOf(
                "<b>x</b>",
                "<br><img src=x><input>",
                "<svg><g/></svg>",
                "<ul><li>a<li>b</ul>",
                "<math><mi>x</mi></math>",
                "<hr/>",
                "<textarea>t</textarea><script>x</script>",
            )
        for (markup in closed) {
            assertEquals("<p>$markup</p>", interpolate("<p>%s</p>", interpolate(markup)).text, markup)
        }
    }

    // A patch with the svg namespace is parsed inside an svg, where a title is an HTML integration point.
    @Test
    fun `a hole is judged as an svg patch reads it`() {
        assertEquals(Position.CODE, refused("""<title><xmp><!--</xmp><img src=x onerror="%s">""", code).position)
    }

    // And one with the MathML namespace inside a math, where an mi is one.
    @Test
    fun `a hole is judged as a math patch reads it`() {
        assertEquals(Position.CODE, refused("""<textarea><mi><xmp><!--</xmp><img src=x onerror="%s">""", code).position)
    }

    @Test
    fun `a Trusted value in a URL does not waive the check for the other holes there`() {
        assertEquals(1, refused("""<a href="%s%s">x</a>""", Trusted(""), "javascript:alert(1)").index)
        assertEquals(1, refused("""<a href="%s%s">x</a>""", Trusted("javascript:"), "alert(1)").index)
        assertEquals(
            """<a href="https://x.example/a">x</a>""",
            interpolate("""<a href="%s%s">x</a>""", Trusted("https://x.example/"), "a").text,
        )
        assertEquals("""<a href="javascript:void(0)">x</a>""", interpolate("""<a href="%s">x</a>""", Trusted("javascript:void(0)")).text)
    }

    @Test
    fun `a Trusted value in a URL that loads code counts toward the host`() {
        assertEquals(1, refused("""<script src="%s%s"></script>""", Trusted(""), "//evil.example/x.js").index)
        assertEquals(
            """<script src="/js/app.js"></script>""",
            interpolate("""<script src="%s%s"></script>""", Trusted("/js/"), "app.js").text,
        )
    }

    @Test
    fun `a code-loading URL says the literal must fix the host`() {
        val failure = refused("""<script src="%s"></script>""", "https://cdn.example.com/x.js")

        assertTrue(failure.loadsCode)
        assertTrue("\"/js/%s\"" in failure.message!!, failure.message)
        assertFalse("Pass a relative URL" in failure.message!!, failure.message)
        assertFalse(refused("""<a href="%s">x</a>""", "javascript:x").loadsCode)
    }

    @Test
    fun `a link loads what it points at, so its host must be fixed`() {
        assertTrue(refused("""<link rel="stylesheet" href="%s">""", "https://evil.example/x.css").loadsCode)
        assertEquals(
            """<link rel="stylesheet" href="/css/a.css">""",
            interpolate("""<link rel="stylesheet" href="/css/%s">""", "a.css").text,
        )
    }

    @Test
    fun `the URL of a meta refresh is held to the URL schemes`() {
        assertEquals("content", refused("""<meta http-equiv="refresh" content="0;url=%s">""", "javascript:alert(1)").attribute)
        assertEquals("content", refused("""<meta http-equiv="refresh" content="%s">""", "0; URL='javascript:alert(1)'").attribute)
        assertEquals(
            """<meta http-equiv="refresh" content="5;url=https://x.example/">""",
            interpolate("""<meta http-equiv="refresh" content="5;url=%s">""", "https://x.example/").text,
        )
        assertEquals(
            """<meta name="description" content="javascript: a language">""",
            interpolate("""<meta name="description" content="%s">""", "javascript: a language").text,
        )
    }
}
