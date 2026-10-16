package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The markup scanner under interpolate and ElementsGuard against the way the HTML tokenizer
 * reads the same text. Each format here was read differently by the scanner and the browser, and
 * a value the scanner took for text or an ordinary attribute ran in the real Datastar client.
 */
class MarkupAsTheBrowserReadsTest {
    private val script = "window.pwned = 1"

    private fun refused(
        format: String,
        vararg values: Any?,
    ) {
        assertFailsWith<UnsafeInterpolationException>(format) { interpolate(format, *values) }
    }

    @Test
    fun `comments end where the tokenizer ends them`() {
        refused("""<!--><div data-text="%s"></div>""", script)
        refused("""<!---><div data-text="%s"></div>""", script)
        refused("""<!-- note --!><div data-text="%s"></div>""", script)
        // A real comment still holds text.
        assertEquals("<!-- a&lt;b --><p></p>", interpolate("<!-- %s --><p></p>", "a<b"))
    }

    @Test
    fun `a bogus comment ends at the first greater-than`() {
        refused("""<!x <y a="> <b data-text='%s'>">""", script)
        refused("""<?x <y a="> <b data-text='%s'>">""", script)
        refused("""</ <y a="> <b data-text='%s'>">""", script)
    }

    @Test
    fun `only an ASCII letter opens a tag`() {
        refused("""<é title='<span data-text="%s">'>""", script)
    }

    @Test
    fun `a raw text element ends only at its own end tag`() {
        refused("""<title></titlex><b title='</title><span data-text="%s">'>""", script)
    }

    @Test
    fun `every raw text element of HTML is read as text`() {
        for (tag in listOf("xmp", "iframe", "noembed", "noframes", "noscript")) {
            refused("""<$tag><y a="</$tag><b data-text='%s'>"></$tag>""", script)
        }
    }

    @Test
    fun `inside svg a title or a textarea holds elements`() {
        refused("""<svg><title><span data-text="%s"></span></title></svg>""", script)
        refused("""<svg><textarea><div data-text="%s"></div></textarea></svg>""", script)
        // A patch with an SVG namespace has no <svg> of its own, so the format is judged as either.
        refused("""<title><span data-text="%s"></span></title>""", script)
    }

    @Test
    fun `a URL attribute is judged whole, every value in place`() {
        refused("""<a href="%s%s">x</a>""", "", "javascript:alert(1)")
        refused("""<a href="%s%s">x</a>""", "java", "script:alert(1)")
        refused("""<a href="%s%s">x</a>""", "javascript", ":alert(1)")
        refused("""<a href="javascript:show(%s)">x</a>""", "1")
        refused("""<a href="&#106;avascript:%s">x</a>""", "alert(1)")
        assertEquals("""<a href="/heads/x">x</a>""", interpolate("""<a href="/heads/%s">x</a>""", "x"))
        assertEquals("""<a href="https://e.com/a">x</a>""", interpolate("""<a href="%s%s">x</a>""", "https://e.com", "/a"))
    }

    @Test
    fun `values to and from on an SVG animation are URLs`() {
        refused("""<svg><a><animate attributeName="href" values="%s"/></a></svg>""", "javascript:alert(1)")
        refused("""<svg><a><set attributeName="href" to="%s"/></a></svg>""", "javascript:alert(1)")
        refused("""<svg><a><animate attributeName="href" values="/a;%s"/></a></svg>""", "javascript:alert(1)")
        assertEquals("""<feColorMatrix values="1 0"/>""", interpolate("""<feColorMatrix values="%s"/>""", "1 0"))
    }

    @Test
    fun `custom prefixes are tried longest first`() {
        assertFailsWith<UnsafeInterpolationException> {
            interpolate("""<div data-star-text="%s"></div>""", listOf(script), listOf("data-", "data-star-"))
        }
    }

    @Test
    fun `the guard reads comments and svg as the browser does`() {
        for (html in listOf(
            """<!--><b data-text="++"></b>""",
            """<!-- x --!><b data-text="++"></b>""",
            """<svg><title><b data-text="++"></b></title></svg>""",
        )) {
            assertFailsWith<InterpolatedExpressionException>(html) { ElementsGuard.check(html) }
        }
        // In HTML, markup a textarea shows is text, and stays allowed.
        val shown = """<textarea><div data-text=""></div></textarea>"""
        assertEquals(shown, ElementsGuard.check(shown))
    }

    @Test
    fun `the guard reads a patch with an SVG namespace as svg`() {
        val streamlord = Streamlord(guardElements = true)
        assertFailsWith<InterpolatedExpressionException> {
            streamlord.guard(PatchElements("""<title><b data-text="++"></b></title>""", namespace = ElementNamespace.SVG))
        }
    }
}
