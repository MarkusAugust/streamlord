package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.domain.MistypedAttributeException
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `a tag name is matched by its ASCII letters only`() {
        // The browser lowercases A to Z alone: </ſcript> (U+017F, a long s) is text inside the script.
        refused("<script>x</\u017Fcript>%s</script>", script)
        refused("<style>a</\u017Ftyle>%s</style>", "x")
        // And <ſcript> is an element of its own, whose content is text.
        assertEquals("<\u017Fcript>a&lt;b</\u017Fcript>", interpolate("<\u017Fcript>%s</\u017Fcript>", "a<b"))
    }

    @Test
    fun `in svg or math a CDATA section ends at its own end`() {
        // parse5 and the browser: the img is an element of the page, not the value of a.
        refused("""<svg><![CDATA[ > <x a="]]><img src=x onerror='%s'>">""", script)
        refused("""<math><![CDATA[ > <x a="]]><img src=x onerror='%s'>">""", script)
        // Outside them it is a bogus comment, which the first > ends.
        refused("""<![CDATA[ x ]]><img src=x onerror="%s">""", script)
        // So it is inside an HTML integration point of an svg, or after a breakout: there the img is an element.
        refused("""<svg><title><![CDATA[><img src=x onerror="%s">]]></title></svg>""", script)
        refused("""<svg><desc><![CDATA[><a href="%s">x</a>]]></desc></svg>""", "javascript:alert(1)")
        refused("""<math><title><p><![CDATA[><img onerror="%s">""", script)
    }

    @Test
    fun `character references decode as the HTML tokenizer decodes them`() {
        // &nbsp; is a no-break space, as &#160; is, and an expression of white space alone is none.
        val numeric = runCatching { ElementsGuard.check("""<b data-text="&#160;"></b>""") }.exceptionOrNull()
        val named = runCatching { ElementsGuard.check("""<b data-text="&nbsp;"></b>""") }.exceptionOrNull()
        assertEquals(numeric?.javaClass, named?.javaClass)
        assertTrue(named != null)
        // &#0; is U+FFFD to the browser, which no scheme starts with, so the link is a relative one.
        assertEquals("""<a href="&#0;javascript:alert(1)">x</a>""", interpolate("""<a href="&#0;javascript:%s">x</a>""", "alert(1)"))
        // The legacy names decode without their semicolon.
        refused("""<a href="javascript&colon;%s">x</a>""", "alert(1)")
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
        // So is markup after an end tag only Unicode case folding would read as the script's own.
        val inScript = "<script>x</\u017Fcript><b data-text=\"++\"></b></script>"
        assertEquals(inScript, ElementsGuard.check(inScript))
        assertFailsWith<InterpolatedExpressionException> { ElementsGuard.check("""<svg><![CDATA[ > <x a="]]><b data-text='++'></b>">""") }
        assertFailsWith<MistypedAttributeException> {
            ElementsGuard.check(
                """<svg><title><![CDATA[><div data-onn:click="x">]]></title></svg>""",
            )
        }
        // An </svg> inside a CDATA section closes nothing, so the textarea after it is the svg's.
        assertFailsWith<MistypedAttributeException> {
            ElementsGuard.check("""<svg><![CDATA[</svg><textarea>]]><div data-onn:click="x"></div>""")
        }
    }

    @Test
    fun `the guard reads as svg only inside an svg`() {
        val textarea = """<textarea readonly><div data-text=""></div></textarea>"""
        for (html in listOf(
            """<svg viewBox="0 0 1 1"><title>Icon</title></svg>$textarea""",
            """<script>const s = "<svg></svg>"</script>$textarea""",
            """<textarea>Draw with <svg> and <div data-text=""></textarea>""",
            """<svg><title>Icon</title></svg><title>Use <b data-show=""> tags</title>""",
        )) {
            assertEquals(html, ElementsGuard.check(html))
        }
        assertFailsWith<InterpolatedExpressionException> {
            ElementsGuard.check("""<p></p><svg><g><title><b data-text="++"></b></title></g></svg>$textarea""")
        }
    }

    @Test
    fun `Trusted waives the URL check too`() {
        assertEquals("""<a href="javascript:void(0)">x</a>""", interpolate("""<a href="%s">x</a>""", Trusted("javascript:void(0)")))
    }

    @Test
    fun `the guard reads a patch with an SVG namespace as svg`() {
        val streamlord = Streamlord(guardElements = true)
        assertFailsWith<InterpolatedExpressionException> {
            streamlord.guard(PatchElements("""<title><b data-text="++"></b></title>""", namespace = ElementNamespace.SVG))
        }
    }
}
