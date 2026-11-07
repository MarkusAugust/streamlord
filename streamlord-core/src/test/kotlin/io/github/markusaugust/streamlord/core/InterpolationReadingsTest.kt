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
        // The last: the browser's adoption agency drops the inner span, so the `</span>` closes the span around the value.
        val open =
            listOf(
                "<svg>",
                "<math>",
                "<svg><g>",
                "<svg><foreignObject>",
                "<math><mi>",
                "<template>",
                "<select>",
                "</svg>",
                "</div>",
                "<b><span><div></b></div></span>",
            )
        for (markup in open) {
            val out = interpolate("<span>%s</span>", interpolate(markup)).text
            assertFalse('<' in out.removePrefix("<span>").removeSuffix("</span>"), "$markup: $out")
        }
    }

    // Where the value lands in svg or math, a tag that takes the browser back to HTML there closes
    // the elements around it, and the markup after it is read as HTML.
    @Test
    fun `an Html that leaves the svg or math it lands in is written as text`() {
        val formats =
            listOf(
                "<svg><g>%s</g></svg>",
                "<svg>%s</svg>",
                "<math><mrow>%s</mrow></math>",
                "<svg><g><g>%s</g></g></svg>",
                "<math><mi><svg>%s</svg></mi></math>",
            )
        val leaving = listOf("<em></em>", "<p>x</p>", "</p>", "<br>", "</br>", "<font color=red>x</font>", "<div></div>")
        for (format in formats) {
            for (markup in leaving) {
                val out = interpolate(format, interpolate(markup)).text
                assertFalse(markup in out, "$format with $markup: $out")
            }
        }
    }

    // Right in an integration point, an element the value leaves open is HTML, and the svg or math
    // end tags of the format after it stop at the integration point and close nothing.
    @Test
    fun `an Html that leaves an element open right in an integration point is written as text`() {
        val formats = listOf("<svg><foreignObject>%s</foreignObject></svg>", "<svg><desc>%s</desc></svg>", "<math><mi>%s</mi></math>")
        for (format in formats) {
            for (markup in listOf("<g/>", "<a/>", "<g>", "<span>x")) {
                val out = interpolate(format, interpolate(markup)).text
                assertFalse(markup in out, "$format with $markup: $out")
            }
        }
    }

    // An element a value leaves open is closed only by an end tag the browser lets reach it: an object,
    // an applet and a marquee bound every search, an integration point stops the svg's end tags, and an
    // `<a>` is opened again around the text after it. So a value is markup only when it leaves none open.
    @Test
    fun `an Html that leaves any element open is written as text`() {
        val cases =
            listOf(
                "<main><div>%s</div></main>" to "<object>",
                "<div>%s</div>" to "<applet>",
                "<div>%s</div>" to "<marquee>",
                "<div><div>%s</div></div>" to "<a>",
                "<div>%s</div>" to "<p>x",
                "<svg><foreignObject><form>%s</form></foreignObject></svg>" to "<g>",
                "<svg><foreignObject><p>%s</p></foreignObject></svg>" to "<button>",
                "<desc>%s</desc><g>after</g>" to "<g/>",
                "<mi>%s</mi>" to "<a/>",
            )
        for ((format, markup) in cases) {
            val out = interpolate(format, interpolate(markup)).text
            assertFalse(markup in out, "$format with $markup: $out")
        }
    }

    // Read from inside an svg or a math, a `form` or a `label` is an svg element, and an `<input>` in
    // it one left open; the browser reads them as HTML, where the input is void.
    @Test
    fun `an Html of void elements in a form, a label or a media element is still markup`() {
        val cases =
            listOf(
                "<form method=post>%s<button>Save</button></form>" to "<input name=email type=email>",
                "<label>%s Remember me</label>" to "<input type=checkbox>",
                "<table><tr><td>%s</td></tr></table>" to "<input type=checkbox name=a>",
                "<form>%s</form>" to "<div><input name=a></div>",
                "<video>%s</video>" to "<source src=a.mp4><track src=a.vtt>",
                "<colgroup>%s</colgroup>" to "<col>",
                "<button>%s</button>" to "<wbr>",
            )
        for ((format, markup) in cases) {
            assertEquals(format.replace("%s", markup), interpolate(format, interpolate(markup)).text, "$format with $markup")
        }
    }

    // A table part out of its place closes the table or the cell around it, or, as a first `<col>`,
    // turns what follows into a column group that drops it; a formatting element's end tag leaves a
    // block opened in it open; a form inside a form or a table moves the format's fields out of theirs;
    // and straight in an svg patch an `<image>` or an `<input>` is an svg element left open.
    @Test
    fun `an Html that reshapes a table, a form or a formatting element is written as text`() {
        val cases =
            listOf(
                "<table><tr><td>%s</td></tr></table>" to "<td></td>",
                "<table><tr><td>%s</td></tr></table>" to "<col>",
                "<table><tr><td><svg><desc>%s</desc></svg></td></tr></table>" to "<td></td>",
                "<template>%sQ1</template>" to "<col>",
                "Q0%s<p>Q1</p>" to "<col>",
                "<div>%s</div>" to "<tr><td>a</td></tr>",
                "<div>%sQ1</div>Q2" to "<b><div>x</b>",
                "<div>%sQ1</div>Q2" to "<b><p>x</b>",
                "<form>%s<input name=a></form>" to "<form></form>",
                "<table>%s<tr><td>Q1</td></tr></table>Q2" to "<table></table>",
                "<table><tr>%s<td>Q1</td></tr></table>Q2" to "<table></table>",
                "<table><tr><td>%s</td><td>Q1</td></tr></table>" to "<table><table></table></table>",
                "<div><form></div>%s" to "<form><input name=a></form>",
                "<div>%sQ1</div>Q2" to "<div><object></div>",
                "%s<text>Q1</text>" to "<image href=\"a.png\">",
                "%s<text>Q1</text>" to "<input>",
            )
        for ((format, markup) in cases) {
            val out = interpolate(format, interpolate(markup)).text
            assertFalse(markup in out, "$format with $markup: $out")
        }
    }

    @Test
    fun `an Html of table parts in their place, of a whole form, or of formatting is still markup`() {
        val cases =
            listOf(
                "<table><tr>%s</tr></table>" to "<td>a</td><td>b</td>",
                "<table><tbody>%s</tbody></table>" to "<tr><td>a</td></tr>",
                "<table>%s</table>" to "<caption>c</caption>",
                "<table><colgroup>%s</colgroup></table>" to "<col>",
                "<div>%s</div>" to "<table><tr><td>a</td></tr></table>",
                "<table><tr><td>%s</td></tr></table>" to "<table><tr><td>x</td></tr></table>",
                "<div>%s</div>" to "<b>x</b><div>y</div>",
                "<div>%s</div>" to "<form><input name=a></form>",
                "<table>%s</table>" to "<form></form>",
                "<div>%s</div>" to "<table><form></form></table>",
                "<table><tr><td>%s</td></tr></table>" to "<form><input name=a></form>",
                "%s" to "<image href=\"a.png\"/>",
                // At a patch's top level, or in a template, a row or a table section is no harm.
                "%s" to "<tr id=r1><td>a</td><td>b</td></tr>",
                "%s<p>after</p>" to "<thead><tr><td>a</td></tr></thead>",
                "<template>%s</template>" to "<tr><td>a</td></tr>",
                "%s" to "<colgroup><col></colgroup>",
            )
        for ((format, markup) in cases) {
            assertEquals(format.replace("%s", markup), interpolate(format, interpolate(markup)).text, "$format with $markup")
        }
    }

    // Read from inside an svg or a math, an `<a>` or a `<button>` is an svg element a `<span>` in it
    // leaves, so its end tag closed nothing there; where the value lands, in HTML, it is a link.
    @Test
    fun `an Html of a link, a button, a form or a section holding HTML is still markup`() {
        val values =
            listOf(
                "<a href=\"/x\"><span>a</span></a>",
                "<a href=x><img src=a></a>",
                "<button><span>a</span></button>",
                "<label><span>a</span></label>",
                "<form><div>a</div></form>",
                "<section><p>a</p></section>",
                "<article><h2>t</h2><p>a</p></article>",
            )
        for (format in listOf("<div>%s</div>", "<li>%s</li>")) {
            for (markup in values) {
                assertEquals(format.replace("%s", markup), interpolate(format, interpolate(markup)).text, "$format with $markup")
            }
        }
    }

    @Test
    fun `an Html that stays in the svg or math it lands in is still markup`() {
        val cases =
            listOf(
                "<svg>%s</svg>" to "<path d=\"M0 0\"/>",
                "<svg><g>%s</g></svg>" to "<g><circle r=\"1\"/></g>",
                "<math>%s</math>" to "<mi>x</mi>",
                "<svg><foreignObject>%s</foreignObject></svg>" to "<p>x</p>",
                "<math><mi>%s</mi></math>" to "<b>x</b>",
                "<svg><g>%s</g></svg>" to "<font>x</font>",
                "<math>%s</math>" to "<mrow><mi>x</mi></mrow>",
            )
        for ((format, markup) in cases) {
            assertEquals(format.replace("%s", markup), interpolate(format, interpolate(markup)).text, "$format with $markup")
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
                // The div stops the `</span>`, so it closes nothing around the value.
                "<div></span></div>",
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

    // In an svg or math patch, a `<p>` the next `<p>` or `<div>` closes, or an `<li>` the next one
    // closes, leaves the stack at the patch's own element again, where a `<script>` is foreign.
    @Test
    fun `an HTML element the browser closes by itself returns an svg or math patch to its own namespace`() {
        val formats =
            listOf(
                "<p><p></p><script><!</script>%s",
                "<li><li></li><script><!</script>%s",
                "<p><div></div><script><!</script>%s",
                "<dd><dt></dt><script><!</script>%s",
            )
        for (format in formats) assertEquals(Position.CODE, refused(format, code).position, format)
        assertEquals(Position.CODE, refused("""<p><p>a</p><title><xmp><!--</xmp><img src=x onerror="%s">""", code).position)
        assertEquals("<p><p>a</p><b>x</b>", interpolate("<p><p>a</p><b>%s</b>", "x").text)
        // Closed by a `<pre>`, the `<p>` leaves the `<style>` an svg one; the `<dd>` that ends it stays open.
        assertEquals(
            Position.CODE,
            refused("<p><pre></pre><style><dd>%s<li><li></li></style>%s", Trusted("<script>"), code).position,
        )
        assertEquals(Position.CODE, refused("<p><pre></pre><style><dd><script><li><li></li></style>%s", code).position)
        // Written into the svg itself, a `<p>` takes the browser out of it, and the `</svg>` after it is read in HTML.
        assertEquals("<svg>&lt;p&gt;a&lt;/p&gt;</svg>", interpolate("<svg>%s</svg>", interpolate("<p>%s</p>", "a")).text)
    }

    // Inside foreign content an `<svg>` or `<math>` tag takes the namespace it stands in, so in a
    // math patch `<svg><mi>` is a MathML mi, whose content is HTML.
    @Test
    fun `an svg tag inside a math is MathML, and a math inside an svg is svg`() {
        assertEquals(Position.STRUCTURE, refused("<xmp><svg><mi><textarea><a href='</textarea><img src=x onerror=%s>", code).position)
        assertEquals("<svg><math><mi>x</mi></math></svg>", interpolate("<svg><math><mi>%s</mi></math></svg>", "x").text)
    }

    // Escaping takes a value's `<` and `>`, not its dashes, `!` or `[`, which can still finish the
    // markup's own `<!` into a comment or end a comment where the markup writes a `>`.
    @Test
    fun `a value cannot open or end a comment the markup around it begins`() {
        assertEquals(Position.STRUCTURE, refused("""<!%s><a title="--><script>%s</script>">""", "-- x", code).position)
        assertEquals(Position.STRUCTURE, refused("""<!-%s><a title="--><script>%s</script>">""", "- x", code).position)
        assertEquals(Position.STRUCTURE, refused("""<!--%s><img src=x onerror=%s>-->""", "--", code).position)
        assertEquals(Position.STRUCTURE, refused("""<!--x-%s><img src=x onerror=%s>-->""", "-", code).position)
        assertEquals(Position.STRUCTURE, refused("""<svg><![CDATA[%s]><img src=x onerror=%s>]]></svg>""", "]", code).position)
        assertEquals("<!-- a - b -->", interpolate("<!-- %s -->", "a - b").text)
        assertEquals("<!--x-->", interpolate("<!--%s-->", "x").text)
    }

    // A Trusted value is markup, so the values beside it are judged where its text puts them.
    @Test
    fun `a Trusted value is read as the markup it writes`() {
        assertEquals(Position.DATASTAR, refused("""<button %s="%s">""", Trusted("data-on:click"), code).position)
        assertEquals(Position.CODE, refused("""<button %s="%s">""", Trusted("onclick"), code).position)
        assertEquals(Position.URL, refused("""<a %s="%s">x</a>""", Trusted("href"), "javascript:alert(1)").position)
        assertEquals(1, refused("<%s>%s</%s>", Trusted("script"), code, Trusted("script")).index)
        assertTrue(refused("""<%s src="%s"></script>""", Trusted("script"), "https://evil.example/x.js").loadsCode)
        assertEquals(Position.CODE, refused("""<p %sonclick="%s">""", Trusted(""), code).position)
        assertEquals(Position.CODE, refused("<scrip%st>%s</script>", Trusted(""), code).position)
        assertEquals(1, refused("""x<%s <img title="--><img src=x onerror=%s>">""", Trusted("!--"), code).index)
        assertEquals(1, refused("""x<%s<i src="<b><a href="x="%s>""", Trusted("/"), code).index)
        assertEquals("""<button title="&lt;b&gt;">""", interpolate("""<button %s="%s">""", Trusted("title"), "<b>").text)
        assertEquals("<b>%s</b>", interpolate("<%s>%s</%s>", Trusted("b"), "%s", Trusted("b")).text)
        // The format's own %% is one per cent; a Trusted value's text is written as it stands.
        assertEquals("<i>a%%b 1%</i>", interpolate("<i>%s %s%%</i>", Trusted("a%%b"), 1).text)
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
